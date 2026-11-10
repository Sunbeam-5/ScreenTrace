package com.screentrace.app.data.parse

import com.screentrace.app.contract.NotificationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * §7-5 / §7-17 解析器 golden fixtures（自建、可独立重复执行）：
 * M:SS 两段式、毫秒/秒两级去重、跨零点、数字事件类型、锁屏扣减、未闭合亮屏、
 * 小时合计一致性；CR-01 增补：同秒多条通知不丢失、双源合并不重复计数、
 * 解锁事件毫秒逐条。原始切片 sample-*.txt 只读引用做格式回归（不得修改）。
 */
class UsageDumpParserTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val day1: LocalDate = LocalDate.of(2026, 11, 3)

    /** 本地时间 → 毫秒（与解析器同 zone，测试与实现时区无关）。 */
    private fun ms(date: LocalDate, hour: Int, minute: Int = 0, second: Int = 0): Long =
        date.atTime(hour, minute, second).atZone(zone).toInstant().toEpochMilli()

    private fun ev(ms: Long, type: String, pkg: String = "android", channel: String? = null): String =
        "      time=$ms type=$type package=$pkg" +
            (channel?.let { " channelId=$it" } ?: "") + " flags=0x0"

    private fun humanEv(ms: Long, type: String, pkg: String = "android"): String =
        "    time=\"" + java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .format(java.time.Instant.ofEpochMilli(ms).atZone(zone)) +
            "\" type=$type package=$pkg flags=0x0"

    private fun checkin(intervalLines: List<String>, events: List<String>, pkgs: List<String> = emptyList()): String =
        (intervalLines + events + pkgs).joinToString("\n")

    private fun parseOf(checkin: String, human: String = "", today: LocalDate? = null) =
        UsageDumpParser.parse(checkin, human, zone, today)

    // ---- §7-5：M:SS 两段式（分:秒）与三段式 ----

    @Test
    fun mSsDurationParsesAsMinutesSeconds() {
        val human = """
            In-memory daily stats
                package=com.foo totalTimeUsed="54:01" lastTimeUsed="1970-01-01 08:00:00" appLaunchCount=3
                package=com.bar totalTimeUsed="1:02:03" lastTimeUsed="1970-01-01 08:00:00" appLaunchCount=1
            In-memory weekly stats
        """.trimIndent()
        val result = parseOf("", human)
        val foo = result.appUsage.first { it.pkg == "com.foo" }
        val bar = result.appUsage.first { it.pkg == "com.bar" }
        assertEquals(3241_000L, foo.totalMs) // 54:01 = 54 分 1 秒
        assertEquals(3723_000L, bar.totalMs) // 1:02:03 = 时:分:秒
        assertEquals(3, foo.launches)
    }

    // ---- §7-5：跨零点切分 ----

    @Test
    fun crossMidnightSplitsByNaturalDay() {
        val events = listOf(
            ev(ms(day1, 23, 30), "SCREEN_INTERACTIVE"),
            ev(ms(day1.plusDays(1), 0, 30), "SCREEN_NON_INTERACTIVE")
        )
        val result = parseOf(checkin(emptyList(), events))
        assertEquals(30, result.days.getValue(day1).screenUnlockMin)
        assertEquals(30, result.days.getValue(day1.plusDays(1)).screenUnlockMin)
    }

    // ---- §7-5：锁屏扣减（sub_kg）与未闭合亮屏截断 ----

    @Test
    fun subKgDeductsKeyguardOverlap() {
        val events = listOf(
            ev(ms(day1, 9, 0), "SCREEN_INTERACTIVE"),
            ev(ms(day1, 9, 20), "KEYGUARD_SHOWN"),
            ev(ms(day1, 9, 40), "KEYGUARD_HIDDEN"),
            ev(ms(day1, 10, 0), "SCREEN_NON_INTERACTIVE")
        )
        val result = parseOf(checkin(emptyList(), events))
        val day = result.days.getValue(day1)
        assertEquals(60, day.screenMin)
        assertEquals(40, day.screenUnlockMin) // 60 - 20 锁屏
        assertEquals(1, day.unlocks)
    }

    @Test
    fun unclosedScreenIntervalTruncatedToLastEvent() {
        val events = listOf(
            ev(ms(day1, 22, 0), "SCREEN_INTERACTIVE"),
            ev(ms(day1, 23, 0), "KEYGUARD_HIDDEN") // 末事件，亮屏未闭合
        )
        val result = parseOf(checkin(emptyList(), events))
        assertEquals(60, result.days.getValue(day1).screenUnlockMin)
    }

    // ---- §7-5：小时合计与同日 screen_unlock 一致（§6 红线口径）----

    @Test
    fun hourMatrixUsesUnlockBucketsAndSumsToDayTotal() {
        val events = listOf(
            ev(ms(day1, 9, 0), "SCREEN_INTERACTIVE"),
            ev(ms(day1, 9, 20), "KEYGUARD_SHOWN"),
            ev(ms(day1, 9, 40), "KEYGUARD_HIDDEN"),
            ev(ms(day1, 10, 30), "SCREEN_NON_INTERACTIVE")
        )
        val result = parseOf(checkin(emptyList(), events))
        val buckets = result.hourMinutes.getValue(day1)
        assertEquals(40, buckets[9]) // 9:00–9:20 + 9:40–10:00（扣锁屏 20 分钟）
        assertEquals(30, buckets[10])
        assertEquals(70, buckets.sum())
        assertEquals(70, result.days.getValue(day1).screenUnlockMin) // 取整误差内一致
    }

    // ---- §7-5：数字事件类型归一化 ----

    @Test
    fun numericEventTypesNormalize() {
        val events = listOf(
            ev(ms(day1, 8, 0), "15"), // SCREEN_INTERACTIVE
            ev(ms(day1, 8, 30), "16"), // SCREEN_NON_INTERACTIVE
            ev(ms(day1, 8, 10), "18") // KEYGUARD_HIDDEN
        )
        val result = parseOf(checkin(emptyList(), events))
        assertEquals(30, result.days.getValue(day1).screenUnlockMin)
        assertEquals(1, result.days.getValue(day1).unlocks)
        assertEquals(1, result.unlockEvents.size)
    }

    // ---- §7-17：计数层毫秒保留——同秒多条通知不丢失 ----

    @Test
    fun sameSecondNotificationsNotLost() {
        val base = ms(day1, 11, 0, 0)
        val events = listOf(
            ev(base + 100, "NOTIFICATION_INTERRUPTION", "com.a", "ch1"),
            ev(base + 300, "NOTIFICATION_INTERRUPTION", "com.a", "ch1"),
            ev(base + 500, "NOTIFICATION_SEEN", "com.a", "ch1")
        )
        val result = parseOf(checkin(emptyList(), events))
        assertEquals(3, result.notificationEvents.size) // 秒级 set 去重会吞掉 2 条
        assertEquals(2, result.notificationEvents.count { it.type == NotificationType.INTERRUPTION })
        assertEquals(1, result.notificationEvents.count { it.type == NotificationType.SEEN })
        assertEquals("ch1", result.notificationEvents.first().channelId)
        assertEquals(base + 300, result.notificationEvents[1].occurredAtMs) // 毫秒逐条保真
    }

    // ---- §7-17：双源合并取较大条数（多重集合，不重复计数）----

    @Test
    fun dualSourceMergeTakesLargerSide() {
        val base = ms(day1, 12, 0, 0)
        val checkinEvents = listOf(
            ev(base + 100, "NOTIFICATION_INTERRUPTION", "com.a"),
            ev(base + 200, "NOTIFICATION_INTERRUPTION", "com.a"),
            ev(base + 300, "NOTIFICATION_INTERRUPTION", "com.a")
        )
        val humanEvents = listOf(humanEv(base, "NOTIFICATION_INTERRUPTION", "com.a"))
        // checkin 3 条 vs human 1 条 → 取 3（不加和为 4）
        val result = parseOf(checkin(emptyList(), checkinEvents), humanEvents.joinToString("\n"))
        assertEquals(3, result.notificationEvents.size)

        // 反向：checkin 1 条 vs human 3 条 → 取 3
        val result2 = parseOf(
            checkin(emptyList(), listOf(ev(base + 100, "NOTIFICATION_INTERRUPTION", "com.a"))),
            listOf(
                humanEv(base, "NOTIFICATION_INTERRUPTION", "com.a"),
                humanEv(base, "NOTIFICATION_INTERRUPTION", "com.a"),
                humanEv(base, "NOTIFICATION_SEEN", "com.a")
            ).joinToString("\n")
        )
        // 同秒同 type 的两条 human 同 key 组：该组条数 2 vs checkin 1 → 2；SEEN 组 1 条独立
        assertEquals(3, result2.notificationEvents.size)
    }

    // ---- §7-17：解锁事件毫秒逐条 + 最长连续口径 ----

    @Test
    fun unlockEventsCarryMillisecondsAndLongestSession() {
        val base = ms(day1, 7, 0, 0)
        val events = listOf(
            ev(base + 111, "KEYGUARD_HIDDEN"),
            ev(base + 222, "KEYGUARD_HIDDEN"),
            ev(ms(day1, 7, 0), "SCREEN_INTERACTIVE"),
            ev(ms(day1, 7, 30), "SCREEN_NON_INTERACTIVE"),
            ev(ms(day1, 8, 0), "SCREEN_INTERACTIVE"),
            ev(ms(day1, 8, 10), "SCREEN_NON_INTERACTIVE")
        )
        val result = parseOf(checkin(emptyList(), events))
        assertEquals(listOf(base + 111, base + 222), result.unlockEvents.map { it.occurredAtMs })
        assertEquals(2, result.days.getValue(day1).unlocks)
        assertEquals(30, result.days.getValue(day1).longestSessionMin) // 单区间 sub_kg 最大值
    }

    // ---- CR-02：区间全账户保留（不再 >50 包过滤），0 时长滤除 ----

    @Test
    fun allIntervalsAggregateWithZeroFiltered() {
        val begin = ms(day1, 0, 0)
        val end = ms(day1, 23, 59)
        val pkgs = (1..60).joinToString("\n") { i ->
            "      package=com.pkg$i totalTimeUsed=60000 lastTimeUsed=$begin appLaunchCount=1"
        }
        val text = """
            beginTime=$begin endTime=$end
                package=com.small totalTimeUsed=60000 lastTimeUsed=$begin appLaunchCount=1
                package=com.zero totalTimeUsed=0 lastTimeUsed=$begin appLaunchCount=0
            beginTime=$begin endTime=$end
            $pkgs
        """.trimIndent()
        val result = parseOf(text)
        assertTrue(result.appUsage.none { it.pkg == "com.zero" }) // 0 时长滤除
        assertTrue(result.appUsage.any { it.pkg == "com.small" }) // CR-02：小账户也收
        assertEquals(60, result.appUsage.count { it.pkg.startsWith("com.pkg") })
        assertEquals(day1, result.appUsage.first().date) // 归属日 = end-60s 所在自然日
    }

    // ---- 原始切片只读格式回归（§7-5：sample-*.txt 不修改、可重复执行）----

    @Test
    fun sampleSlicesParseWithoutError() {
        val checkinFile = File("../reference/sample-checkin.txt")
        val humanFile = File("../reference/sample-human.txt")
        assertTrue("sample-checkin.txt 缺失", checkinFile.exists())
        assertTrue("sample-human.txt 缺失", humanFile.exists())
        val result = parseOf(checkinFile.readText(), humanFile.readText())
        assertTrue("样本应解析出日指标", result.days.isNotEmpty())
        assertTrue("样本应解析出通知事件", result.notificationEvents.isNotEmpty())
    }

    // ---- 返修 P0-1（CR-02）：系统镜像事件跨账户收敛 ----

    @Test
    fun systemMirrorEventsConvergeAcrossUsers() {
        val base = ms(day1, 8, 0, 0)
        // 三账户各广播一条 KEYGUARD_HIDDEN（±100ms）→ 同一物理事件，收敛为 1
        val text = listOf(
            "user=0",
            ev(base, "KEYGUARD_HIDDEN"),
            "user=666",
            ev(base + 100, "KEYGUARD_HIDDEN"),
            "user=999",
            ev(base + 200, "KEYGUARD_HIDDEN")
        ).joinToString("\n")
        val result = parseOf(text)
        assertEquals(1, result.days.getValue(day1).unlocks) // 未收敛时为 3
        assertEquals(1, result.unlockEvents.size)
    }

    @Test
    fun appEventsAcrossUsersAreNeverConverged() {
        val base = ms(day1, 9, 0, 0)
        // 同秒同 pkg 的通知来自三个账户：应用级事件独立，全收（CR-01 红线）
        val text = listOf(
            "user=0",
            ev(base + 10, "NOTIFICATION_INTERRUPTION", "com.a"),
            "user=666",
            ev(base + 20, "NOTIFICATION_INTERRUPTION", "com.a"),
            "user=999",
            ev(base + 30, "NOTIFICATION_SEEN", "com.a")
        ).joinToString("\n")
        val result = parseOf(text)
        assertEquals(3, result.notificationEvents.size)
    }

    // ---- 返修 P0-2：最长连续逐日切片，不跨零点复制整段 ----

    @Test
    fun longestSessionSlicesByNaturalDay() {
        // 23:00 – 02:00 跨零点亮屏：day1 最长 60（切片），day2 120；不得都记 180
        val events = listOf(
            ev(ms(day1, 23, 0), "SCREEN_INTERACTIVE"),
            ev(ms(day1.plusDays(1), 2, 0), "SCREEN_NON_INTERACTIVE")
        )
        val result = parseOf(checkin(emptyList(), events))
        assertEquals(60, result.days.getValue(day1).longestSessionMin)
        assertEquals(120, result.days.getValue(day1.plusDays(1)).longestSessionMin)
    }

    // ---- 返修 P1-5/P1-6：In-memory 跨账户归属、与区间取大、滤 0、一行一日一包 ----

    @Test
    fun inMemoryMergesAcrossUsersPerPkg() {
        val human = """
            user=0
            In-memory daily stats
                package=com.tencent.mm totalTimeUsed="10:00" lastTimeUsed="1970-01-01 08:00:00" appLaunchCount=2
                package=com.zero totalTimeUsed="00:00" lastTimeUsed="1970-01-01 08:00:00" appLaunchCount=0
            In-memory weekly stats
            user=999
            In-memory daily stats
                package=com.tencent.mm totalTimeUsed="05:00" lastTimeUsed="1970-01-01 08:00:00" appLaunchCount=1
            In-memory weekly stats
        """.trimIndent()
        val result = parseOf("", human, today = day1)
        val mm = result.appUsage.single { it.pkg == "com.tencent.mm" }
        assertEquals(15 * 60_000L, mm.totalMs) // 跨账户求和（分身微信 + 主微信 = 一行）
        assertEquals(3, mm.launches)
        assertTrue(result.appUsage.none { it.pkg == "com.zero" }) // 0 时长行滤除
    }

    @Test
    fun inMemoryTakesLargerInsteadOfAddingToIntervals() {
        // 同 (date,pkg)：区间 10min + In-memory 5min → 取 10（补采不叠加）
        val begin = ms(day1, 0, 0)
        val end = ms(day1, 23, 59)
        val checkinText = """
            beginTime=$begin endTime=$end
                package=com.foo totalTimeUsed=600000 lastTimeUsed=$begin appLaunchCount=1
        """.trimIndent()
        val human = """
            user=0
            In-memory daily stats
                package=com.foo totalTimeUsed="05:00" lastTimeUsed="1970-01-01 08:00:00" appLaunchCount=1
            In-memory weekly stats
        """.trimIndent()
        val result = parseOf(checkinText, human, today = day1)
        val foo = result.appUsage.single { it.pkg == "com.foo" }
        assertEquals(600_000L, foo.totalMs) // 10min（取大），不是 15min
    }

    // ---- 真实 dump 不变式对账（批 5 返修验收物；无数据时跳过）----

    private fun findSourceFile(name: String): File? = listOf(
        File("E:/project/屏幕使用时间App/源数据/$name"),
        File("E:/project/屏幕使用时间App/$name"),
        File("../源数据/$name")
    ).firstOrNull { it.exists() }

    @Test
    fun realDumpReconciliationInvariants() {
        val checkinFile = findSourceFile("usagestats-checkin.txt")
        val humanFile = findSourceFile("usagestats(1).txt")
        val reportFile = findSourceFile("screen_usage_report.json")
        org.junit.Assume.assumeTrue(
            "真实 dump 不在本机，跳过对账（fixtures 测试仍覆盖口径）",
            checkinFile != null && humanFile != null && reportFile != null
        )
        // dump 归属「今天」= 2026-11-09（文件生成日）
        val result = parseOf(
            checkinFile!!.readText(),
            humanFile!!.readText(),
            today = LocalDate.of(2026, 11, 9)
        )
        val report = reportFile!!.readText()

        // 不变式 1（DeepSeek P0-1，按 §3-9 已知口径差放宽）：应用合计不得显著超过亮屏。
        // 滚动日界归属（end-60s）会把前一晚 22:50–24:00 计入次日，且分屏重复计时是
        // 系统固有（§3-9）——上界取 1.2×+30min 只防「重复计数」级别的错误。
        for ((date, day) in result.days) {
            val appSumMin = result.appUsage.filter { it.date == date }.sumOf { it.totalMs } / 60_000
            assertTrue(
                "[$date] 应用合计 ${appSumMin}min 显著超过亮屏 ${day.screenMin}min（重复计数嫌疑）",
                appSumMin <= day.screenMin * 1.2 + 30
            )
        }

        // 不变式 2：米家 10 天合计 ≈ 356.7min（u0 205.8 + 分身 150.9，跨账户求和真值）
        val mijiaMin = result.appUsage
            .filter { it.pkg == "com.xiaomi.smarthome" }
            .sumOf { it.totalMs } / 60_000
        assertTrue("米家合计 ${mijiaMin}min 应 ≈356.7（330–390）", mijiaMin in 330..390)

        // 不变式 3：逐日解锁与已验证基线比值 ∈ [0.98, 1.05]（收敛后不再 ~3× 虚高）
        val baseUnlocks = Regex(""""unlocks": (\d+)""").findAll(report).map { it.groupValues[1].toInt() }.toList()
        val ktUnlocks = listOf("10-31", "11-01", "11-02", "11-03", "11-04", "11-05", "11-06", "11-07", "11-08", "11-09")
            .mapNotNull { md ->
                val date = LocalDate.parse("2026-$md")
                result.days[date]?.unlocks
            }
        if (baseUnlocks.size == ktUnlocks.size) {
            for ((base, kt) in baseUnlocks.zip(ktUnlocks)) {
                val ratio = kt.toDouble() / base.coerceAtLeast(1)
                assertTrue("解锁比值 $kt/$base = $ratio 应在 [0.98,1.05]", ratio in 0.98..1.05)
            }
        }
    }
}
