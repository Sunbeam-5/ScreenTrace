package com.screentrace.app.data.pull

import com.screentrace.app.contract.MetricKind
import com.screentrace.app.contract.NotificationType
import com.screentrace.app.data.parse.MirrorAuditStatus
import com.screentrace.app.data.parse.MirrorConvergenceAudit
import com.screentrace.app.data.parse.UsageDumpParser
import com.screentrace.app.ui.detailMissingEventCopy
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批 13（§2.3 & §7-32）+ GPT Review P2-1/P2-2/P2-3 定向修复回归测试（纯 JVM，无 Android 依赖）。
 *
 * 覆盖三条行为变异锚点：
 * - **M-13R1（P2-1）**：账户数来自整个 dump 的 `user=N` 分区（某账户缺失镜像事件必报 ANOMALY），
 *   按事件类型分项审计，样本不足或无 `user=N` 分区头时明确标记 `INDETERMINATE`；
 * - **M-13R2（P2-2）**：`detailMissingEventCopy` 标题统一为「当前暂无可用事件数据」，
 *   避免将 `windowStart == null` 过度断言为机型不支持，且绝不伪造 0 次；
 * - **M-13R3（P2-3）**：不注入假 `parse` lambda，直接驱动 `ShizukuUsageProvider` 默认解析路径
 *   （真实 `UsageDumpParser.parse`）解析 vivo 与 HyperOS 脱敏完整样本，校验屏幕/应用/小时/解锁/通知/窗口全字段。
 */
class MultiDeviceAdaptationTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun ms(date: LocalDate, hour: Int, minute: Int = 0, second: Int = 0, millis: Int = 0): Long =
        date.atTime(hour, minute, second, millis * 1_000_000)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    // ---------- M-13R1（P2-1）：全 dump 分区账户计数 + 分类型审计 + INDETERMINATE ----------

    @Test
    fun `M-13R1 三账户分区中user999缺失镜像事件时必须报ANOMALY而非按2账户误判正常`() = runBlocking {
        // user=0 与 user=666 各有 6 条 SCREEN_INTERACTIVE，user=999 仅有 ACTIVITY_RESUMED（0 条镜像事件）。
        // 若错误地从镜像事件反推账户数，会误算为 2 账户 2.0×（正常）从而漏报；
        // 正确口径从 dump 分区提取 3 账户，实测 12/6=2.00× 低于 3×0.80=2.40×，必须触发 ANOMALY。
        val humanDump = buildString {
            appendLine("user=0")
            appendLine("  Last 24 hour events")
            for (i in 0 until 6) {
                val sec = 10 + i * 5
                appendLine("    time=\"2026-11-26 09:00:$sec\" type=SCREEN_INTERACTIVE package=android flags=0x0")
            }
            appendLine("user=666")
            appendLine("  Last 24 hour events")
            for (i in 0 until 6) {
                val sec = 10 + i * 5
                appendLine("    time=\"2026-11-26 09:00:$sec\" type=SCREEN_INTERACTIVE package=android flags=0x0")
            }
            appendLine("user=999")
            appendLine("  Last 24 hour events")
            appendLine("    time=\"2026-11-26 09:01:00\" type=ACTIVITY_RESUMED package=com.tencent.mm flags=0x0")
        }

        val provider = ShizukuUsageProvider(
            shell = FakeShell(
                ShizukuShell.CHECKIN_CMD to Result.success(HYPEROS_META_ONLY_CHECKIN),
                ShizukuShell.PLAIN_CMD to Result.success(humanDump)
            ),
            zone = zone,
            clock = { ms(LocalDate.of(2026, 11, 26), 10, 0) }
        )

        val loaded = provider.load() as LoadedUsage.Parsed
        val audit = loaded.result.mirrorAudit
        assertNotNull(audit)
        assertEquals("账户数必须来自整个 dump 的 user=N 分区（含无镜像事件的 user=999）", 3, audit!!.distinctUsers)
        assertEquals(MirrorAuditStatus.ANOMALY, audit.status)
        assertNotNull("3 账户缺失 1 个账户（2.00× < 2.40×）必须产出异常告警", audit.anomalyWarning)
        assertTrue(audit.anomalyWarning!!.contains("SCREEN_INTERACTIVE"))
        assertTrue(
            "异常告警必须经 ShizukuUsageProvider 追加到 LoadedUsage.Parsed.warning",
            loaded.warning.orEmpty().contains("镜像事件重复率异常")
        )
    }

    @Test
    fun `M-13R1 按事件类型独立审计可检出单类型缺失且样本不足或无分区头标记INDETERMINATE`() {
        // SCREEN_INTERACTIVE 正常 3.0×，但 KEYGUARD_HIDDEN 仅 1.0×（6→6）-> 必须检出 KEYGUARD_HIDDEN 异常
        val mixed = MirrorConvergenceAudit.evaluateByType(
            dumpUserCount = 3,
            perTypeCounts = mapOf(
                "SCREEN_INTERACTIVE" to (18 to 6),
                "KEYGUARD_HIDDEN" to (6 to 6)
            )
        )
        assertEquals(MirrorAuditStatus.ANOMALY, mixed.status)
        assertNotNull(mixed.anomalyWarning)
        assertTrue(mixed.anomalyWarning!!.contains("KEYGUARD_HIDDEN"))
        assertFalse(mixed.anomalyWarning!!.contains("SCREEN_INTERACTIVE"))

        // 样本不足（<5）-> INDETERMINATE（绝不默认 NORMAL）
        val smallSample = MirrorConvergenceAudit.evaluateByType(
            dumpUserCount = 3,
            perTypeCounts = mapOf("SCREEN_INTERACTIVE" to (12 to 4))
        )
        assertEquals(MirrorAuditStatus.INDETERMINATE, smallSample.status)
        assertNull(smallSample.anomalyWarning)

        // dump 缺少 user=N 分区头（dumpUserCount = 0）或仅单账户 -> INDETERMINATE
        val missingUserHeader = UsageDumpParser.parse(
            checkinText = "",
            humanText = "  time=\"2026-11-26 09:00:10\" type=SCREEN_INTERACTIVE package=android\n",
            zone = zone
        )
        assertEquals(MirrorAuditStatus.INDETERMINATE, missingUserHeader.mirrorAudit?.status)
        assertNull(missingUserHeader.mirrorAudit?.anomalyWarning)
    }

    // ---------- M-13R2（P2-2）：缺失事件源诚实文案不过度断言机型不支持 ----------

    @Test
    fun `M-13R2 通知与解锁缺失事件源统一展示当前暂无可用事件数据且不伪造0次`() {
        val (notifTitle, notifDesc) = detailMissingEventCopy(MetricKind.NOTIFICATION)
        assertEquals("当前暂无可用事件数据", notifTitle)
        assertFalse("不得仅凭无数据断言机型不支持", notifTitle.contains("不提供"))
        assertTrue(notifDesc.contains("通知"))
        assertTrue(notifDesc.contains("部分机型不输出"))
        assertFalse("缺失文案不得伪造 0 次", notifTitle.contains("0") || notifDesc.contains("0 次"))

        val (unlockTitle, unlockDesc) = detailMissingEventCopy(MetricKind.UNLOCK)
        assertEquals("当前暂无可用事件数据", unlockTitle)
        assertFalse("不得仅凭无数据断言机型不支持", unlockTitle.contains("不提供"))
        assertTrue(unlockDesc.contains("锁屏"))
        assertTrue(unlockDesc.contains("部分机型不输出"))
        assertFalse("缺失文案不得伪造 0 次", unlockTitle.contains("0") || unlockDesc.contains("0 次"))
    }

    // ---------- M-13R3（P2-3）：vivo 与 HyperOS 真实解析端到端回归（无 mock parse） ----------

    @Test
    fun `M-13R3 vivo 完整checkin样本走真实UsageDumpParser产出精确日聚合小时应用解锁通知与正常审计`() = runBlocking {
        val day = LocalDate.of(2026, 11, 25)
        val capturedAtMs = ms(day, 12, 0)

        // 构造贴近 reference/sample-checkin.txt 真实形态的三账户（0/666/999）vivo checkin 样本：
        // - 09:00:00 ~ 10:00:00 亮屏 60m，其中 09:00:00 ~ 09:10:00 锁屏 10m -> screenMin=60, screenUnlockMin=50
        // - 5 组三账户完整镜像（3×）使 MirrorConvergenceAudit 达到样本门限并判定 NORMAL
        // - 09:10:00 ~ 09:50:00 微信前台 40m（含 checkin package 聚合 40m / 3 次启动）
        // - 通知：1 条 INTERRUPTION（带 channelId）+ 1 条 SEEN
        val vivoCheckin = buildString {
            appendLine("Days:")
            appendLine("user=0")
            appendLine("  beginTime=${ms(day, 0, 0)} endTime=${ms(day, 12, 0)}")
            appendLine("    packages")
            appendLine("      package=com.tencent.mm totalTimeUsed=2400000 lastTimeUsed=${ms(day, 9, 50)} appLaunchCount=3")
            appendLine("    screen-interactive: 5x for 3600000")
            // 主会话：09:00~10:00（锁屏 09:00~09:10 -> 解锁亮屏 50m）
            appendLine("      time=${ms(day, 9, 0, 0, 100)} type=SCREEN_INTERACTIVE package=android flags=0x0")
            appendLine("      time=${ms(day, 9, 0, 0, 150)} type=KEYGUARD_SHOWN package=android flags=0x0")
            appendLine("      time=${ms(day, 9, 10, 0, 100)} type=KEYGUARD_HIDDEN package=android flags=0x0")
            appendLine("      time=${ms(day, 9, 10, 1, 0)} type=ACTIVITY_RESUMED package=com.tencent.mm flags=0x0")
            appendLine("      time=${ms(day, 9, 20, 0, 0)} type=NOTIFICATION_INTERRUPTION package=com.tencent.mm channelId=msg_ch flags=0x0")
            appendLine("      time=${ms(day, 9, 20, 5, 0)} type=NOTIFICATION_SEEN package=com.tencent.mm channelId=msg_ch flags=0x0")
            appendLine("      time=${ms(day, 9, 50, 1, 0)} type=ACTIVITY_PAUSED package=com.tencent.mm flags=0x0")
            appendLine("      time=${ms(day, 10, 0, 0, 100)} type=SCREEN_NON_INTERACTIVE package=android flags=0x0")
            // 额外 4 段 1 分钟短亮屏（10:10..10:11, 10:20..10:21, 10:30..10:31, 10:40..10:41）凑齐 ≥5 组样本
            for (k in 1..4) {
                val min = k * 10
                appendLine("      time=${ms(day, 10, min, 0, 100)} type=SCREEN_INTERACTIVE package=android flags=0x0")
                appendLine("      time=${ms(day, 10, min + 1, 0, 100)} type=SCREEN_NON_INTERACTIVE package=android flags=0x0")
            }
            // user=666 与 user=999 镜像广播（±200ms / ±400ms 偏差）
            for ((u, offset) in listOf(666 to 200, 999 to 400)) {
                appendLine("user=$u")
                appendLine("  beginTime=${ms(day, 0, 0)} endTime=${ms(day, 12, 0)}")
                appendLine("      time=${ms(day, 9, 0, 0, 100 + offset)} type=SCREEN_INTERACTIVE package=android flags=0x0")
                appendLine("      time=${ms(day, 9, 0, 0, 150 + offset)} type=KEYGUARD_SHOWN package=android flags=0x0")
                appendLine("      time=${ms(day, 9, 10, 0, 100 + offset)} type=KEYGUARD_HIDDEN package=android flags=0x0")
                appendLine("      time=${ms(day, 10, 0, 0, 100 + offset)} type=SCREEN_NON_INTERACTIVE package=android flags=0x0")
                for (k in 1..4) {
                    val min = k * 10
                    appendLine("      time=${ms(day, 10, min, 0, 100 + offset)} type=SCREEN_INTERACTIVE package=android flags=0x0")
                    appendLine("      time=${ms(day, 10, min + 1, 0, 100 + offset)} type=SCREEN_NON_INTERACTIVE package=android flags=0x0")
                }
            }
        }

        // 不传 parse lambda：走 ShizukuUsageProvider 默认真实 UsageDumpParser.parse(..., capturedAtMs)
        val provider = ShizukuUsageProvider(
            shell = FakeShell(
                ShizukuShell.CHECKIN_CMD to Result.success(vivoCheckin),
                ShizukuShell.PLAIN_CMD to Result.success("")
            ),
            zone = zone,
            clock = { capturedAtMs }
        )

        val loaded = provider.load()
        assertTrue("vivo 样本必须成功解析", loaded is LoadedUsage.Parsed)
        loaded as LoadedUsage.Parsed
        assertFalse("vivo 有效 checkin 必须走 checkin 主导", loaded.warning.orEmpty().contains("明文主导"))

        val result = loaded.result
        val metrics = result.days[day]
        assertNotNull("必须产出目标自然日指标", metrics)
        assertEquals("亮屏总时长 60m + 4*1m = 64m", 64, metrics!!.screenMin)
        assertEquals("解锁亮屏时长 50m + 4*1m = 54m", 54, metrics.screenUnlockMin)
        assertEquals("三账户镜像收敛后解锁次数必须为 1（绝非 3）", 1, metrics.unlocks)

        val hours = result.hourMinutes[day]
        assertNotNull(hours)
        assertEquals("09 时解锁亮屏 50m", 50, hours!![9])
        assertEquals("10 时解锁亮屏 4m", 4, hours[10])

        val mm = result.appUsage.firstOrNull { it.date == day && it.pkg == "com.tencent.mm" }
        assertNotNull("必须解析出微信应用时长", mm)
        assertEquals(2_400_000L, mm!!.totalMs)
        assertEquals(3, mm.launches)

        assertEquals(1, result.unlockEvents.size)
        assertEquals(1, result.notificationEvents.count { it.type == NotificationType.INTERRUPTION && it.channelId == "msg_ch" })
        assertEquals(1, result.notificationEvents.count { it.type == NotificationType.SEEN })

        assertNotNull("必须签发 BoundedObservedWindow", result.observedWindow)
        assertTrue("正常配对不应产生截断日", result.truncatedScreenDates.isEmpty())
        assertEquals(MirrorAuditStatus.NORMAL, result.mirrorAudit?.status)
        assertNull("3 账户正常 3.0× 不应误报告警", result.mirrorAudit?.anomalyWarning)
    }

    @Test
    fun `M-13R3 HyperOS 元信息checkin加完整明文走真实UsageDumpParser无静默漏数据`() = runBlocking {
        val day = LocalDate.of(2026, 11, 26)
        val capturedAtMs = ms(day, 11, 0)

        // 构造贴近小米 HyperOS 3 实测形态：checkin 仅元信息，明文双账户（0/999）含完整事件流 + In-memory
        val hyperosPlain = buildString {
            appendLine("Flags:")
            appendLine("    android.app.usage.user_interaction_type_api: true")
            appendLine("user=0")
            appendLine("  Last 24 hour events (timeRange=\"2026/11/25 11:00 – 2026/11/26 11:00\")")
            appendLine("    time=\"2026-11-26 08:00:00\" type=SCREEN_INTERACTIVE package=android flags=0x0")
            appendLine("    time=\"2026-11-26 08:00:00\" type=KEYGUARD_SHOWN package=android flags=0x0")
            appendLine("    time=\"2026-11-26 08:05:00\" type=KEYGUARD_HIDDEN package=android flags=0x0")
            appendLine("    time=\"2026-11-26 08:05:05\" type=ACTIVITY_RESUMED package=tv.danmaku.bili flags=0x0")
            appendLine("    time=\"2026-11-26 08:20:00\" type=NOTIFICATION_INTERRUPTION package=com.tencent.mm channelId=mipush|mm|1 flags=0x0")
            appendLine("    time=\"2026-11-26 08:20:10\" type=NOTIFICATION_SEEN package=com.tencent.mm channelId=mipush|mm|1 flags=0x0")
            appendLine("    time=\"2026-11-26 08:45:05\" type=ACTIVITY_PAUSED package=tv.danmaku.bili flags=0x0")
            appendLine("    time=\"2026-11-26 09:00:00\" type=SCREEN_NON_INTERACTIVE package=android flags=0x0")
            appendLine("  In-memory daily stats")
            appendLine("  timeRange=\"2026/11/26 00:00 – 2026/11/26 11:00\"")
            appendLine("    packages")
            appendLine("      package=tv.danmaku.bili totalTimeUsed=\"40:00\" lastTimeUsed=\"2026-11-26 08:45:05\" appLaunchCount=2")
            appendLine("    event aggregations")
            appendLine("      screen-interactive: 1x for \"01:00:00\"")
            appendLine("user=999")
            appendLine("  Last 24 hour events (timeRange=\"2026/11/25 11:00 – 2026/11/26 11:00\")")
            appendLine("    time=\"2026-11-26 08:00:00\" type=SCREEN_INTERACTIVE package=android flags=0x0")
            appendLine("    time=\"2026-11-26 08:00:00\" type=KEYGUARD_SHOWN package=android flags=0x0")
            appendLine("    time=\"2026-11-26 08:05:00\" type=KEYGUARD_HIDDEN package=android flags=0x0")
            appendLine("    time=\"2026-11-26 09:00:00\" type=SCREEN_NON_INTERACTIVE package=android flags=0x0")
        }

        // 不传 parse lambda：走 ShizukuUsageProvider 默认真实 UsageDumpParser.parse(..., capturedAtMs)
        val provider = ShizukuUsageProvider(
            shell = FakeShell(
                ShizukuShell.CHECKIN_CMD to Result.success(HYPEROS_META_ONLY_CHECKIN),
                ShizukuShell.PLAIN_CMD to Result.success(hyperosPlain)
            ),
            zone = zone,
            clock = { capturedAtMs }
        )

        val loaded = provider.load()
        assertTrue("HyperOS 明文主导样本必须成功解析", loaded is LoadedUsage.Parsed)
        loaded as LoadedUsage.Parsed
        assertTrue("HyperOS 必须走明文主导分支", loaded.warning.orEmpty().contains("明文主导"))

        val result = loaded.result
        val metrics = result.days[day]
        assertNotNull("明文主导必须产出日汇总，不得静默漏数据", metrics)
        assertEquals("亮屏 08:00~09:00 = 60m", 60, metrics!!.screenMin)
        assertEquals("扣除 5m 锁屏 -> 解锁亮屏 55m", 55, metrics.screenUnlockMin)
        assertEquals("双账户镜像收敛后解锁次数必须为 1", 1, metrics.unlocks)

        val hours = result.hourMinutes[day]
        assertNotNull(hours)
        assertEquals("08 时解锁亮屏 55m", 55, hours!![8])

        val bili = result.appUsage.firstOrNull { it.date == day && it.pkg == "tv.danmaku.bili" }
        assertNotNull("明文 In-memory 必须解析出 B 站时长（两段式 40:00 = 40 分钟）", bili)
        assertEquals(2_400_000L, bili!!.totalMs)
        assertEquals(2, bili.launches)

        assertEquals(1, result.unlockEvents.size)
        assertEquals(1, result.notificationEvents.count { it.type == NotificationType.INTERRUPTION && it.pkg == "com.tencent.mm" })
        assertEquals(1, result.notificationEvents.count { it.type == NotificationType.SEEN && it.pkg == "com.tencent.mm" })
        assertNotNull("HyperOS 明文主导必须签发 BoundedObservedWindow", result.observedWindow)
        assertTrue(result.truncatedScreenDates.isEmpty())
    }

    @Test
    fun `M-13R3 真实采集样本文件 reference sample-checkin 与 sample-human 走真实解析器双形态端到端对齐`() = runBlocking {
        val checkinFile = File("../reference/sample-checkin.txt")
        val humanFile = File("../reference/sample-human.txt")
        assertTrue("缺少真实样本文件 ${checkinFile.absolutePath}", checkinFile.exists())
        assertTrue("缺少真实样本文件 ${humanFile.absolutePath}", humanFile.exists())
        val realCheckin = checkinFile.readText()
        val realHuman = humanFile.readText()
        val capturedAtMs = 1_794_203_723_476L + 60_000L

        // 1) 真实 vivo 双路样本（reference/sample-checkin.txt + sample-human.txt）走真实解析器，
        //    直接用独立于解析器的固定 Golden 预期值逐项断言（不再仅与 UsageDumpParser 自比）
        val dualProvider = ShizukuUsageProvider(
            shell = FakeShell(
                ShizukuShell.CHECKIN_CMD to Result.success(realCheckin),
                ShizukuShell.PLAIN_CMD to Result.success(realHuman)
            ),
            zone = zone,
            clock = { capturedAtMs }
        )
        val dualLoaded = dualProvider.load()
        assertTrue("真实 vivo 双路样本必须解析成功", dualLoaded is LoadedUsage.Parsed)
        dualLoaded as LoadedUsage.Parsed
        assertFalse("有效 checkin 样本不应误走明文主导", dualLoaded.warning.orEmpty().contains("明文主导"))

        val res = dualLoaded.result
        // Golden 日期范围：事件流覆盖 2026-10-31 ~ 2026-11-08 共 9 个自然日（checkin 聚合区间进 W1 screenEvidence）
        assertEquals(9, res.days.size)
        assertEquals(LocalDate.of(2026, 10, 31), res.days.keys.min())
        assertEquals(LocalDate.of(2026, 11, 8), res.days.keys.max())

        // Golden 通知事件：sample-checkin.txt 含 7 条 NOTIFICATION_INTERRUPTION、0 条 SEEN
        assertEquals(7, res.notificationEvents.size)
        assertEquals(7, res.notificationEvents.count { it.type == NotificationType.INTERRUPTION })
        assertEquals(0, res.notificationEvents.count { it.type == NotificationType.SEEN })
        assertEquals(2, res.notificationEvents.count { it.pkg == "com.max.xiaoheihe" })
        assertEquals(1, res.notificationEvents.count { it.pkg == "com.vivo.health" })
        assertEquals(1, res.notificationEvents.count { it.pkg == "android" })
        assertEquals(3, res.notificationEvents.count { it.pkg == "com.xiaomi.smarthome" })

        // Golden 解锁事件：sample-human.txt 含 1 条 KEYGUARD_HIDDEN（2026-11-08 16:33:13 +08:00）
        assertEquals(1, res.unlockEvents.size)
        assertEquals(ms(LocalDate.of(2026, 11, 8), 16, 33, 13), res.unlockEvents.single().occurredAtMs)

        // Golden 应用时长：sample-human.txt 2026-11-09 关键应用精确毫秒与启动次数
        val dayNov9 = LocalDate.of(2026, 11, 9)
        fun appOn(pkg: String) = res.appUsage.firstOrNull { it.date == dayNov9 && it.pkg == pkg }
        assertEquals(4_081_621L, appOn("com.example.piliplus")?.totalMs) // 1:12:28 × 950/1012 跨日分摊
        assertEquals(7, appOn("com.example.piliplus")?.launches)
        assertEquals(1_973_221L, appOn("com.tencent.mm")?.totalMs)       // 35:02 × 950/1012 跨日分摊
        assertEquals(25, appOn("com.tencent.mm")?.launches)              // 27 × 950/1012 跨日分摊
        assertEquals(1_138_686L, appOn("tv.danmaku.bili")?.totalMs)      // 20:13 × 950/1012 跨日分摊
        assertEquals(7, appOn("tv.danmaku.bili")?.launches)

        // Golden 观测窗口与审计：human 通道 16:33:13 SCREEN_INTERACTIVE → capturedAtMs 签发有界观测窗口，且无 user=N 头
        assertEquals(ms(LocalDate.of(2026, 11, 8), 16, 33, 13), res.observedWindow?.startMs)
        assertEquals(capturedAtMs, res.observedWindow?.endMs)
        assertTrue("checkin 含 27 条 screen-interactive 聚合证据", res.screenEvidence.size >= 27)
        assertEquals(
            "reference 节选无 user=N 分区头，审计应明确标记 INDETERMINATE",
            MirrorAuditStatus.INDETERMINATE,
            res.mirrorAudit?.status
        )
        assertNull(res.mirrorAudit?.anomalyWarning)

        // 2) 元信息 checkin + 真实 sample-human.txt（明文主导分支）走真实解析器
        val expectedPlain = UsageDumpParser.parse("", realHuman, zone, capturedAtMs = capturedAtMs)
        assertTrue("真实明文样本单独解析必须产出日汇总", expectedPlain.days.isNotEmpty())
        assertTrue("真实明文样本单独解析必须产出应用时长", expectedPlain.appUsage.isNotEmpty())

        val plainProvider = ShizukuUsageProvider(
            shell = FakeShell(
                ShizukuShell.CHECKIN_CMD to Result.success(HYPEROS_META_ONLY_CHECKIN),
                ShizukuShell.PLAIN_CMD to Result.success(realHuman)
            ),
            zone = zone,
            clock = { capturedAtMs }
        )
        val plainLoaded = plainProvider.load()
        assertTrue("真实明文主导样本必须解析成功", plainLoaded is LoadedUsage.Parsed)
        plainLoaded as LoadedUsage.Parsed
        assertTrue("元信息 checkin 配真实明文必须走明文主导分支", plainLoaded.warning.orEmpty().contains("明文主导"))
        assertEquals(expectedPlain.days, plainLoaded.result.days)
        assertEquals(expectedPlain.hourMinutes, plainLoaded.result.hourMinutes)
        assertEquals(expectedPlain.appUsage, plainLoaded.result.appUsage)
        assertEquals(expectedPlain.unlockEvents, plainLoaded.result.unlockEvents)
        assertEquals(expectedPlain.notificationEvents, plainLoaded.result.notificationEvents)
    }

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
    }
}
