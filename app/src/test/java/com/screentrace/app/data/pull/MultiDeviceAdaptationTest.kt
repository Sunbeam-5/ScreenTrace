package com.screentrace.app.data.pull

import com.screentrace.app.contract.MetricKind
import com.screentrace.app.data.parse.MirrorConvergenceAudit
import com.screentrace.app.data.parse.MirrorConvergenceReport
import com.screentrace.app.data.parse.ParseResult
import com.screentrace.app.data.parse.UsageDumpParser
import com.screentrace.app.ui.detailMissingEventCopy
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批 13（§2.3 & §7-32）多机型适配与镜像收敛自检单测（纯 JVM，无 Android 依赖）。
 *
 * 覆盖三条行为变异锚点：
 * - **M-13A**：多账户镜像事件重复率偏离预期时产出结构化告警，并经 ShizukuUsageProvider 写入 warning（→ pull_log.note）；
 * - **M-13B**：通知/解锁事件缺失时 detailMissingEventCopy 统一展示「当前机型不提供该数据」，绝不伪造 0 次；
 * - **M-13C**：vivo（完整 checkin）与 HyperOS（元信息 checkin + 完整明文）双形态判定及「谁有数据谁主导」分支。
 */
class MultiDeviceAdaptationTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    // ---------- M-13A：镜像收敛自检与 pull_log.note 告警链路 ----------

    @Test
    fun `M-13A 3账户重复率显著偏离3倍时产出告警并写入Shizuku warning`() = runBlocking {
        // 3 账户（user=0/666/999），6 个收敛后物理事件但原始仅 8 条（实测 1.33×，低于 3×0.65=1.95×）
        val report = MirrorConvergenceAudit.evaluate(
            distinctUsers = 3,
            rawMirrorEvents = 8,
            convergedMirrorEvents = 6
        )
        assertNotNull("3 账户重复率 1.33× 必须触发异常告警", report.anomalyWarning)
        assertTrue(report.anomalyWarning!!.contains("镜像事件重复率异常"))
        assertTrue(report.anomalyWarning!!.contains("3账户"))

        // 正常 ≈3× 不误报
        val normal = MirrorConvergenceAudit.evaluate(
            distinctUsers = 3,
            rawMirrorEvents = 18,
            convergedMirrorEvents = 6
        )
        assertNull("3 账户正常 3.00× 不应触发告警", normal.anomalyWarning)

        // 单账户或极小样本（<5）不误报
        assertNull(MirrorConvergenceAudit.evaluate(1, 6, 6).anomalyWarning)
        assertNull(MirrorConvergenceAudit.evaluate(3, 5, 4).anomalyWarning)

        // 端到端验证：ShizukuUsageProvider 将 mirrorAudit.anomalyWarning 追加进 LoadedUsage.Parsed.warning
        val shell = FakeShell(
            ShizukuShell.CHECKIN_CMD to Result.success(VIVO_CHECKIN_SAMPLE),
            ShizukuShell.PLAIN_CMD to Result.success(HYPEROS_PLAIN_SAMPLE)
        )
        val provider = ShizukuUsageProvider(
            shell = shell,
            zone = zone,
            clock = { 1_700_000_000_000L },
            parse = { _, _ ->
                ParseResult(
                    days = emptyMap(),
                    hourMinutes = emptyMap(),
                    appUsage = emptyList(),
                    unlockEvents = emptyList(),
                    notificationEvents = emptyList(),
                    mirrorAudit = report
                )
            }
        )
        val loaded = provider.load()
        assertTrue(loaded is LoadedUsage.Parsed)
        loaded as LoadedUsage.Parsed
        assertNotNull(loaded.warning)
        assertTrue(
            "镜像收敛异常告警必须写入 LoadedUsage.Parsed.warning 以便落入 pull_log.note",
            loaded.warning!!.contains("镜像事件重复率异常")
        )
    }

    @Test
    fun `M-13A UsageDumpParser 跨账户收敛同步填充 mirrorAudit 且保持收敛结果与有界窗口不变`() {
        // 构造 3 个账户（0/666/999）共 6 组时间戳的 SCREEN_INTERACTIVE，但仅第 1 组三账户齐全，后 5 组仅 user=0
        val humanLines = buildString {
            appendLine("user=0")
            appendLine("  Last 24 hour events")
            for (i in 0 until 6) {
                val sec = 10 + i * 5
                appendLine("    time=\"2026-11-26 09:00:$sec\" type=SCREEN_INTERACTIVE package=android")
            }
            appendLine("user=666")
            appendLine("  Last 24 hour events")
            appendLine("    time=\"2026-11-26 09:00:10\" type=SCREEN_INTERACTIVE package=android")
            appendLine("user=999")
            appendLine("  Last 24 hour events")
            appendLine("    time=\"2026-11-26 09:00:10\" type=SCREEN_INTERACTIVE package=android")
        }
        val parsed = UsageDumpParser.parse("", humanLines, zone)
        val audit = parsed.mirrorAudit
        assertNotNull(audit)
        assertEquals(3, audit!!.distinctUsers)
        assertEquals(8, audit.rawMirrorEvents)
        assertEquals(6, audit.convergedMirrorEvents)
        assertNotNull("实测 8/6=1.33× 偏离 3 账户预期应产出告警", audit.anomalyWarning)
        // 确认 D-07 P1-2 的 observedWindow 路径未被破坏
        assertNotNull("连续屏幕事件仍应正常签发 BoundedObservedWindow", parsed.observedWindow)
    }

    // ---------- M-13B：通知/解锁缺失诚实文案（§7-32 红线） ----------

    @Test
    fun `M-13B 通知与解锁缺失事件源统一展示当前机型不提供该数据且不含0次`() {
        val (notifTitle, notifDesc) = detailMissingEventCopy(MetricKind.NOTIFICATION)
        assertEquals("当前机型不提供该数据", notifTitle)
        assertTrue(notifDesc.contains("通知"))
        assertFalse("缺失文案不得伪造 0 次", notifTitle.contains("0") || notifDesc.contains("0 次"))

        val (unlockTitle, unlockDesc) = detailMissingEventCopy(MetricKind.UNLOCK)
        assertEquals("当前机型不提供该数据", unlockTitle)
        assertTrue(unlockDesc.contains("锁屏"))
        assertFalse("缺失文案不得伪造 0 次", unlockTitle.contains("0") || unlockDesc.contains("0 次"))
    }

    // ---------- M-13C：vivo 与 HyperOS 双形态识别与谁有数据谁主导 ----------

    @Test
    fun `M-13C HyperOS 元信息checkin配完整明文走明文主导且不喂无效checkin`() = runBlocking {
        val checkinShape = DumpShape.of(HYPEROS_META_ONLY_CHECKIN)
        val plainShape = DumpShape.of(HYPEROS_PLAIN_SAMPLE)
        assertFalse("HyperOS 仅元信息 checkin 不应判为 CHECKIN", checkinShape.looksLikeCheckin)
        assertFalse("HyperOS 仅元信息 checkin 不应含使用数据", checkinShape.hasUsageData)
        assertTrue("HyperOS 完整明文必须可用作明文主输入", plainShape.usableAsPlain)

        var receivedCheckin: String? = null
        var receivedPlain: String? = null
        val provider = ShizukuUsageProvider(
            shell = FakeShell(
                ShizukuShell.CHECKIN_CMD to Result.success(HYPEROS_META_ONLY_CHECKIN),
                ShizukuShell.PLAIN_CMD to Result.success(HYPEROS_PLAIN_SAMPLE)
            ),
            zone = zone,
            clock = { 1_700_000_000_000L },
            parse = { checkin, plain ->
                receivedCheckin = checkin
                receivedPlain = plain
                emptyParseResult()
            }
        )

        val loaded = provider.load()
        assertTrue(loaded is LoadedUsage.Parsed)
        loaded as LoadedUsage.Parsed
        assertEquals("HyperOS 无效 checkin 必须传空串给解析器", "", receivedCheckin)
        assertEquals(HYPEROS_PLAIN_SAMPLE, receivedPlain)
        assertTrue(loaded.warning.orEmpty().contains("明文主导"))
    }

    @Test
    fun `M-13C vivo 完整checkin走checkin主导并完整传入解析器`() = runBlocking {
        val checkinShape = DumpShape.of(VIVO_CHECKIN_SAMPLE)
        assertTrue("vivo 完整 CSV 必须判为 CHECKIN", checkinShape.looksLikeCheckin)

        var receivedCheckin: String? = null
        val provider = ShizukuUsageProvider(
            shell = FakeShell(
                ShizukuShell.CHECKIN_CMD to Result.success(VIVO_CHECKIN_SAMPLE),
                ShizukuShell.PLAIN_CMD to Result.success(HYPEROS_PLAIN_SAMPLE)
            ),
            zone = zone,
            clock = { 1_700_000_000_000L },
            parse = { checkin, _ ->
                receivedCheckin = checkin
                emptyParseResult()
            }
        )

        val loaded = provider.load()
        assertTrue(loaded is LoadedUsage.Parsed)
        loaded as LoadedUsage.Parsed
        assertEquals("vivo 有效 checkin 必须原样传给解析器", VIVO_CHECKIN_SAMPLE, receivedCheckin)
        assertFalse(loaded.warning.orEmpty().contains("明文主导"))
    }

    private fun emptyParseResult(): ParseResult = ParseResult(
        days = emptyMap(),
        hourMinutes = emptyMap(),
        appUsage = emptyList(),
        unlockEvents = emptyList(),
        notificationEvents = emptyList(),
        mirrorAudit = MirrorConvergenceReport(1, 0, 0, 0.0, null)
    )

    private class FakeShell(
        private val responses: Map<String, Result<String>>
    ) : ShellRunner {
        constructor(vararg pairs: Pair<String, Result<String>>) : this(pairs.toMap())

        override suspend fun exec(command: String, timeoutMs: Long): String =
            (responses[command] ?: Result.success("")).getOrThrow()
    }

    companion object {
        private val HYPEROS_META_ONLY_CHECKIN = """
            user=0
            App Standby States:
              package=com.tencent.mm u=0 bucket=10 reason=u
            Broadcast response stats:
        """.trimIndent()

        private val HYPEROS_PLAIN_SAMPLE = """
            user=0
            In-memory daily stats
              timeRange="2026/11/26 00:00 – 2026/11/26 09:00"
            Last 24 hour events
              time="2026-11-26 08:00:00" type=ACTIVITY_RESUMED package=com.tencent.mm
        """.trimIndent()

        private val VIVO_CHECKIN_SAMPLE = """
            user=0
            Days:
            beginTime=1764086400000 endTime=1764172800000
            screen-interactive: 12x for 3600000
        """.trimIndent()
    }
}
