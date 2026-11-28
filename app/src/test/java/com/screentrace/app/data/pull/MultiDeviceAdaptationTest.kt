package com.screentrace.app.data.pull

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.contract.MetricKind
import com.screentrace.app.contract.NotificationType
import com.screentrace.app.data.IngestPipeline
import com.screentrace.app.data.db.DataQualityValues
import com.screentrace.app.data.db.ScreenTraceDatabase
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 批 13（§2.3 & §7-32）+ GPT Review P2-1/P2-2/P2-3 + Carbon 终审裁断回归测试。
 *
 * 覆盖三条行为变异锚点：
 * - **M-13R1（P2-1）**：账户数来自整个 dump 的 `user=N` 分区（某账户缺失镜像事件必报 ANOMALY），
 *   按事件类型分项审计，样本不足或无 `user=N` 分区头时明确标记 `INDETERMINATE`；
 * - **M-13R2（P2-2）**：`detailMissingEventCopy` 标题统一为「当前暂无可用事件数据」，
 *   避免将 `windowStart == null` 过度断言为机型不支持，且绝不伪造 0 次；
 * - **M-13R3（P2-3）**：不注入假 `parse` lambda，直接驱动 `ShizukuUsageProvider` 默认解析路径
 *   （真实 `UsageDumpParser.parse`）解析 vivo 真实样本（`reference/sample-checkin.txt`、
 *   `reference/sample-human.txt` 各自独立 Golden + 跨 8 天错配反伪造回归）与 HyperOS 形态合成契约样本
 *   （真实 Redmi HyperOS 设备原始切片待连机补录）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
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
    fun `M-13R3 HyperOS 形态合成契约样本走真实UsageDumpParser无静默漏数据`() = runBlocking {
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
    fun `M-13R3 vivo 真实 sample-checkin 独立 Golden 断言`() = runBlocking {
        val checkinFile = File("../reference/sample-checkin.txt")
        assertTrue("缺少真实样本文件 ${checkinFile.absolutePath}", checkinFile.exists())
        val realCheckin = checkinFile.readText()
        val capturedAtMs = 1_794_203_723_476L + 60_000L

        val provider = ShizukuUsageProvider(
            shell = FakeShell(
                ShizukuShell.CHECKIN_CMD to Result.success(realCheckin),
                ShizukuShell.PLAIN_CMD to Result.success("")
            ),
            zone = zone,
            clock = { capturedAtMs }
        )
        val loaded = provider.load()
        assertTrue("真实 sample-checkin.txt 必须解析成功", loaded is LoadedUsage.Parsed)
        loaded as LoadedUsage.Parsed
        assertFalse("有效 checkin 样本不应误走明文主导", loaded.warning.orEmpty().contains("明文主导"))

        val res = loaded.result
        val oct31 = LocalDate.of(2026, 10, 31)
        // 仅产出 2026-10-31 单日：09:05:21~09:06:10 (49s) + 09:06:46~09:07:36 (50s) = 99s -> 2m，绝非 894m
        assertEquals(setOf(oct31), res.days.keys)
        assertEquals(2, res.days[oct31]?.screenMin)
        assertEquals(2, res.days[oct31]?.screenUnlockMin)
        assertEquals(6, res.days[oct31]?.launches)
        assertEquals(0, res.days[oct31]?.unlocks)

        val oct31Hours = res.hourMinutes[oct31]
        assertNotNull(oct31Hours)
        assertEquals(2, oct31Hours!![9])
        assertEquals("除 09 时外其余 23 桶必须全为 0", 2, oct31Hours.sum())

        // 7 条通知打扰明细不变
        assertEquals(7, res.notificationEvents.size)
        assertEquals(7, res.notificationEvents.count { it.type == NotificationType.INTERRUPTION })
        assertEquals(0, res.notificationEvents.count { it.type == NotificationType.SEEN })
        assertEquals(2, res.notificationEvents.count { it.pkg == "com.max.xiaoheihe" })
        assertEquals(1, res.notificationEvents.count { it.pkg == "com.vivo.health" })
        assertEquals(1, res.notificationEvents.count { it.pkg == "android" })
        assertEquals(3, res.notificationEvents.count { it.pkg == "com.xiaomi.smarthome" })

        // 无 SCREEN 事件 -> observedWindow == null；27 条 screen-interactive 全部落 WINDOW 证据，11-01~11-08 零日聚合行
        assertNull(res.observedWindow)
        assertEquals(27, res.screenEvidence.count { it.evidenceType == "WINDOW" && it.quality == "WINDOW" })
        for (d in 1..8) {
            val emptyDay = LocalDate.of(2026, 11, d)
            assertNull("W1 聚合日 $emptyDay 不得生成 day_summary 行", res.days[emptyDay])
            assertNull("W1 聚合日 $emptyDay 不得生成 hourMinutes 行", res.hourMinutes[emptyDay])
        }
    }

    @Test
    fun `M-13R3 元信息 checkin 回退分支配 vivo 真实明文样本独立 Golden 断言`() = runBlocking {
        val humanFile = File("../reference/sample-human.txt")
        assertTrue("缺少真实样本文件 ${humanFile.absolutePath}", humanFile.exists())
        val realHuman = humanFile.readText()
        val capturedAtMs = 1_794_203_723_476L + 60_000L

        // 元信息 checkin 回退分支配 vivo 真实明文样本（非 HyperOS 真机文件）
        val plainProvider = ShizukuUsageProvider(
            shell = FakeShell(
                ShizukuShell.CHECKIN_CMD to Result.success(HYPEROS_META_ONLY_CHECKIN),
                ShizukuShell.PLAIN_CMD to Result.success(realHuman)
            ),
            zone = zone,
            clock = { capturedAtMs }
        )
        val plainLoaded = plainProvider.load()
        assertTrue("元信息 checkin 配 vivo 真实明文必须解析成功", plainLoaded is LoadedUsage.Parsed)
        plainLoaded as LoadedUsage.Parsed
        assertTrue("元信息 checkin 配真实明文必须走明文主导分支", plainLoaded.warning.orEmpty().contains("明文主导"))

        val plainRes = plainLoaded.result
        val nov8 = LocalDate.of(2026, 11, 8)
        val nov9 = LocalDate.of(2026, 11, 9)

        // 仅产出 2026-11-08 单日：已闭合 Activity 并集 38s -> 1m；16:33:13 未闭合 ON 落 E3 ANCHOR_ONLY
        assertEquals(setOf(nov8), plainRes.days.keys)
        assertEquals(1, plainRes.days[nov8]?.screenMin)
        assertEquals(1, plainRes.days[nov8]?.screenUnlockMin)
        assertEquals(1, plainRes.days[nov8]?.unlocks)
        val nov8Hours = plainRes.hourMinutes[nov8]
        assertNotNull(nov8Hours)
        assertEquals(1, nov8Hours!![16])
        assertEquals("除 16 时外其余 23 小时不得伪造非零分钟", 1, nov8Hours.sum())

        val anchor = plainRes.screenEvidence.single { it.evidenceType == "ANCHOR" }
        assertEquals("ANCHOR_ONLY", anchor.quality)
        assertEquals(ms(nov8, 16, 33, 13), anchor.startMs)
        assertNull("E3 锚点不得伪造 endMs", anchor.endMs)
        assertTrue("2026-11-08 含未闭合 ON，必须进入 truncatedScreenDates 拦截 EXACT", nov8 in plainRes.truncatedScreenDates)

        assertEquals(1, plainRes.unlockEvents.size)
        assertEquals(ms(nov8, 16, 33, 13), plainRes.unlockEvents.single().occurredAtMs)
        assertEquals(0, plainRes.notificationEvents.size)
        assertEquals(ms(nov8, 16, 33, 13), plainRes.observedWindow?.startMs)
        assertEquals(capturedAtMs, plainRes.observedWindow?.endMs)
        assertEquals(MirrorAuditStatus.INDETERMINATE, plainRes.mirrorAudit?.status)
        assertNull(plainRes.mirrorAudit?.anomalyWarning)

        // 2026-11-09 In-memory 跨日分摊固定 Golden 常量（权重 = 950.0 / (950.0 + 38.0/60.0)，严禁调解析器自算）
        fun plainAppOn(pkg: String) = plainRes.appUsage.firstOrNull { it.date == nov9 && it.pkg == pkg }
        assertEquals(4_345_103L, plainAppOn("com.example.piliplus")?.totalMs)
        assertEquals(7, plainAppOn("com.example.piliplus")?.launches)
        assertEquals(2_100_600L, plainAppOn("com.tencent.mm")?.totalMs)
        assertEquals(27, plainAppOn("com.tencent.mm")?.launches)
        assertEquals(1_212_192L, plainAppOn("tv.danmaku.bili")?.totalMs)
        assertEquals(7, plainAppOn("tv.danmaku.bili")?.launches)
    }

    @Test
    fun `M-13R3 跨8天错配双源输入反伪造回归与IngestPipeline PARTIAL门禁`() = runBlocking {
        val checkinFile = File("../reference/sample-checkin.txt")
        val humanFile = File("../reference/sample-human.txt")
        assertTrue(checkinFile.exists())
        assertTrue(humanFile.exists())
        val realCheckin = checkinFile.readText()
        val realHuman = humanFile.readText()
        val capturedAtMs = 1_794_203_723_476L + 60_000L

        val provider = ShizukuUsageProvider(
            shell = FakeShell(
                ShizukuShell.CHECKIN_CMD to Result.success(realCheckin),
                ShizukuShell.PLAIN_CMD to Result.success(realHuman)
            ),
            zone = zone,
            clock = { capturedAtMs }
        )
        val loaded = provider.load()
        assertTrue(loaded is LoadedUsage.Parsed)
        val res = (loaded as LoadedUsage.Parsed).result

        val oct31 = LocalDate.of(2026, 10, 31)
        val nov8 = LocalDate.of(2026, 11, 8)
        // 严禁跨 8 天桥接伪造 11-01~11-07 的 1440m、10-31 的 894m 或 11-08 的 994m
        assertEquals(setOf(oct31, nov8), res.days.keys)
        for (d in 1..7) {
            val midDay = LocalDate.of(2026, 11, d)
            assertNull("跨 8 天错配输入绝不得伪造 $midDay 的日聚合", res.days[midDay])
            assertNull("跨 8 天错配输入绝不得伪造 $midDay 的小时分布", res.hourMinutes[midDay])
        }
        assertEquals(2, res.days[oct31]?.screenMin)
        assertEquals(1, res.days[nov8]?.screenMin)
        assertTrue(nov8 in res.truncatedScreenDates)

        // 叠加真实 IngestPipeline 入库质量门禁断言：10-31 与 11-08 均必为 PARTIAL，绝不升格 EXACT
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ScreenTraceDatabase::class.java
        )
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        try {
            val pipeline = IngestPipeline(db = db, localDeviceId = { "vivo-test" }, zone = zone)
            val outcome = pipeline.ingest(res, source = "shizuku", pulledAtMs = capturedAtMs)
            assertTrue("入库应成功", outcome.ok)
            assertEquals(
                "10-31 不在有界观测窗口内，必须落 PARTIAL",
                DataQualityValues.PARTIAL,
                db.daySummaryDao().get(oct31.toString(), "vivo-test")?.screenQuality
            )
            assertEquals(
                "11-08 含未闭合 ON 截断，必须落 PARTIAL",
                DataQualityValues.PARTIAL,
                db.daySummaryDao().get(nov8.toString(), "vivo-test")?.screenQuality
            )
            for (d in 1..7) {
                val midDay = LocalDate.of(2026, 11, d)
                assertNull(
                    "跨 8 天错配输入绝不得在数据库写入 $midDay",
                    db.daySummaryDao().get(midDay.toString(), "vivo-test")
                )
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `M-13R3 HyperOS 真实 Redmi 脱敏切片 sample-hyperos-checkin 与 sample-hyperos-human 端到端独立 Golden`() = runBlocking {
        val checkinFile = File("../reference/sample-hyperos-checkin.txt")
        val humanFile = File("../reference/sample-hyperos-human.txt")
        assertTrue("缺少 HyperOS 真实 checkin 切片 ${checkinFile.absolutePath}", checkinFile.exists())
        assertTrue("缺少 HyperOS 真实明文切片 ${humanFile.absolutePath}", humanFile.exists())
        val hyperCheckin = checkinFile.readText()
        val hyperHuman = humanFile.readText()
        val nov28 = LocalDate.of(2026, 11, 28)
        val capturedAtMs = ms(nov28, 17, 14, 0)

        val provider = ShizukuUsageProvider(
            shell = FakeShell(
                ShizukuShell.CHECKIN_CMD to Result.success(hyperCheckin),
                ShizukuShell.PLAIN_CMD to Result.success(hyperHuman)
            ),
            zone = zone,
            clock = { capturedAtMs }
        )
        val loaded = provider.load()
        assertTrue("HyperOS 真实脱敏切片必须解析成功", loaded is LoadedUsage.Parsed)
        loaded as LoadedUsage.Parsed
        assertTrue("HyperOS 元信息 checkin 必须命中明文主导回退", loaded.warning.orEmpty().contains("明文主导"))

        val res = loaded.result
        // 1) 独立手算 5 段闭合亮屏区间（11:45:18~12:34:25=2947s, 13:32:45~14:37:47=3902s,
        //    14:38:51~14:53:53=902s, 14:53:54~15:37:21=2607s, 15:39:31~15:39:34=3s -> 10361s = 172.68m -> 173m）
        assertEquals(setOf(nov28), res.days.keys)
        assertEquals(173, res.days[nov28]?.screenMin)
        assertEquals(173, res.days[nov28]?.screenUnlockMin)
        assertEquals(0, res.days[nov28]?.unlocks)

        // 2) 小时桶独立 Golden：仅 11~15 时有值，其余 19 个空小时严格为 0
        val hours = res.hourMinutes[nov28]
        assertNotNull(hours)
        val expectedHours = mapOf(11 to 15, 12 to 34, 13 to 27, 14 to 59, 15 to 37)
        for (h in 0..23) {
            assertEquals("hour[$h] 必须精确匹配独立手算值", expectedHours[h] ?: 0, hours!![h])
        }

        // 3) 真实通知事件（含 mipush 通道）
        assertEquals(35, res.notificationEvents.size)
        assertEquals(30, res.notificationEvents.count { it.type == NotificationType.INTERRUPTION })
        assertEquals(5, res.notificationEvents.count { it.type == NotificationType.SEEN })
        assertEquals(10, res.notificationEvents.count { it.channelId.orEmpty().startsWith("mipush|") })
        assertEquals(8, res.notificationEvents.count { it.pkg == "com.ss.android.ugc.aweme" })
        assertEquals(2, res.notificationEvents.count { it.pkg == "tv.danmaku.bili" })
        assertEquals(9, res.notificationEvents.count { it.pkg == "android" })
        assertEquals(2, res.notificationEvents.count { it.pkg == "com.xiaomi.smarthome" })

        // 4) In-memory daily stats 应用时长（2:06:16 = 7_576_000L, 00:03 = 3_000L）
        fun appOn(pkg: String) = res.appUsage.firstOrNull { it.date == nov28 && it.pkg == pkg }
        assertEquals(7_576_000L, appOn("com.screentrace.app")?.totalMs)
        assertEquals(3_000L, appOn("com.miui.securitycenter")?.totalMs)

        // 5) 有界观测窗口与无截断
        assertEquals(ms(nov28, 11, 45, 18), res.observedWindow?.startMs)
        assertEquals(capturedAtMs, res.observedWindow?.endMs)
        assertTrue("5 段亮屏区间全部闭合，不应存在截断日", res.truncatedScreenDates.isEmpty())

        // 6) 双账户（user=0 + user=999）镜像收敛审计：10 原始 ON/OFF 收敛为 5 组，倍率 2.0x -> NORMAL 无误报
        val audit = res.mirrorAudit
        assertNotNull(audit)
        assertEquals(2, audit!!.distinctUsers)
        assertEquals(20, audit.rawMirrorEvents)
        assertEquals(10, audit.convergedMirrorEvents)
        assertEquals(MirrorAuditStatus.NORMAL, audit.status)
        assertNull(audit.anomalyWarning)

        // 7) 真实 IngestPipeline 入库质量门禁：窗口始于 11:45:18 未覆盖全天 00:00~24:00 -> PARTIAL
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ScreenTraceDatabase::class.java
        )
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        try {
            val pipeline = IngestPipeline(db = db, localDeviceId = { "redmi-hyperos-test" }, zone = zone)
            val outcome = pipeline.ingest(res, source = "shizuku", pulledAtMs = capturedAtMs)
            assertTrue("HyperOS 切片入库应成功", outcome.ok)
            assertEquals(
                "窗口 11:45:18~17:14:00 仅覆盖当日部分时段，screenQuality 必须为 PARTIAL",
                DataQualityValues.PARTIAL,
                db.daySummaryDao().get(nov28.toString(), "redmi-hyperos-test")?.screenQuality
            )
        } finally {
            db.close()
        }
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
