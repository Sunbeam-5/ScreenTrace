package com.screentrace.app.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §7-13：合并策略的边界行为。
 *
 * 这些断言对应「写错就会悄悄丢数据」的四处：
 * 同日不是逐字段取大、通知不是按主键去重、限额以备份为准、旧备份要能识别缺表。
 */
class BackupMergeTest {

    private fun day(date: String, unlock: Int, unlocks: Int = 1) = DaySummaryDto(
        date = date,
        screenMin = unlock + 30,
        screenUnlockMin = unlock,
        unlocks = unlocks,
        launches = 3,
        longestSessionMin = 20,
        pulledAt = 1_700_000_000_000
    )

    @Test
    fun `同日取较大的一行整体保留而不是逐字段取大`() {
        val local = listOf(day("2026-11-10", 100, unlocks = 9))
        val backup = listOf(day("2026-11-10", 150, unlocks = 2))
        val plan = BackupMerge.daySummaries(local, backup)
        assertEquals(1, plan.rows.size)
        assertEquals(150, plan.rows.first().screenUnlockMin)
        // 整行来自备份：unlocks 跟着变成 2，而不是保留本地的 9
        assertEquals(2, plan.rows.first().unlocks)
        assertEquals(1, plan.report.updated)
    }

    @Test
    fun `本地更大时跳过且不覆盖`() {
        val local = listOf(day("2026-11-10", 200))
        val backup = listOf(day("2026-11-10", 150))
        val plan = BackupMerge.daySummaries(local, backup)
        assertTrue(plan.rows.isEmpty())
        assertEquals(1, plan.report.skipped)
    }

    @Test
    fun `新日期计为新增`() {
        val plan = BackupMerge.daySummaries(emptyList(), listOf(day("2026-11-09", 60)))
        assertEquals(1, plan.report.added)
        assertEquals(0, plan.report.skipped)
    }

    @Test
    fun `应用行按 date pkg 取较大分钟并保留对应名字`() {
        val local = listOf(
            AppUsageDto("2026-11-10", "com.a", "旧名字", 30, 1, "SOCIAL")
        )
        val backup = listOf(
            AppUsageDto("2026-11-10", "com.a", "新名字", 45, 2, "TOOLS"),
            AppUsageDto("2026-11-10", "com.b", "另一个", 10, 1, "OTHER")
        )
        val plan = BackupMerge.appUsage(local, backup)
        assertEquals(2, plan.rows.size)
        val a = plan.rows.first { it.pkg == "com.a" }
        assertEquals("新名字", a.name)
        assertEquals(45, a.minutes)
        assertEquals("TOOLS", a.category)
        assertEquals(1, plan.report.updated)
        assertEquals(1, plan.report.added)
    }

    @Test
    fun `时段行取较大分钟`() {
        val local = listOf(HourUsageDto("2026-11-10", 9, 20))
        val backup = listOf(HourUsageDto("2026-11-10", 9, 35), HourUsageDto("2026-11-10", 10, 5))
        val plan = BackupMerge.hourUsage(local, backup)
        assertEquals(2, plan.rows.size)
        assertTrue(plan.rows.any { it.hour == 9 && it.minutes == 35 })
    }

    @Test
    fun `限额以备份为准覆盖更小的本地值`() {
        val local = listOf(AppLimitDto("com.a", 120, true))
        val backup = listOf(AppLimitDto("com.a", 60, true), AppLimitDto("com.b", 30, false))
        val plan = BackupMerge.appLimits(local, backup)
        assertEquals(2, plan.rows.size)
        assertEquals(60, plan.rows.first { it.pkg == "com.a" }.limitMin)
        assertEquals(1, plan.report.updated)
        assertEquals(1, plan.report.added)
    }

    @Test
    fun `解锁事件按主键去重`() {
        val plan = BackupMerge.unlockEvents(
            localMs = setOf(1000L, 2000L),
            backup = listOf(UnlockEventDto(2000L), UnlockEventDto(3000L), UnlockEventDto(3000L))
        )
        assertEquals(1, plan.rows.size)
        assertEquals(3000L, plan.rows.first().occurredAtMs)
    }

    @Test
    fun `通知按秒级分组取较大条数而不是主键去重`() {
        // 同一秒内：本地 1 条、备份 2 条 → 应补 1 条（简单去重会一条都不补）
        val local = listOf(NotificationEventDto(10_200L, "INTERRUPTION", "com.a"))
        val backup = listOf(
            NotificationEventDto(10_100L, "INTERRUPTION", "com.a"),
            NotificationEventDto(10_900L, "INTERRUPTION", "com.a")
        )
        val plan = BackupMerge.notificationEvents(local, backup)
        assertEquals(1, plan.rows.size)
        assertEquals(1, plan.report.skipped)
    }

    @Test
    fun `备份条数不多时不新增`() {
        val local = listOf(
            NotificationEventDto(10_100L, "INTERRUPTION", "com.a"),
            NotificationEventDto(10_900L, "INTERRUPTION", "com.a")
        )
        val backup = listOf(NotificationEventDto(10_500L, "INTERRUPTION", "com.a"))
        val plan = BackupMerge.notificationEvents(local, backup)
        assertTrue(plan.rows.isEmpty())
    }

    // ---------------------------------------------------------------- 编解码

    private fun sampleFile(version: Int = BackupCodec.SCHEMA_VERSION) = BackupFile(
        schemaVersion = version,
        exportedAt = "2026-11-12 08:00",
        appVersion = "0.1.0",
        settings = BackupSettings(dailyGoalMin = 240, themeVariant = "MINT"),
        daySummary = listOf(day("2026-11-10", 100)),
        appUsage = listOf(AppUsageDto("2026-11-10", "com.a", "应用", 30, 1, "SOCIAL")),
        hourUsage = listOf(HourUsageDto("2026-11-10", 9, 20)),
        pullLog = listOf(PullLogDto(1_700_000_000_000, "shizuku", true, null)),
        unlockEvent = listOf(UnlockEventDto(1_700_000_000_000)),
        notificationEvent = listOf(NotificationEventDto(1_700_000_000_000, "SEEN", "com.a", null)),
        screenSession = listOf(ScreenSessionDto(1_700_000_000_000, 1_700_000_060_000, "SCREEN")),
        appLimit = listOf(AppLimitDto("com.a", 60, true))
    )

    @Test
    fun `编码后能原样解回`() {
        val decoded = BackupCodec.decode(BackupCodec.encode(sampleFile()))
        assertTrue(decoded is BackupCodec.DecodeResult.Ok)
        val file = (decoded as BackupCodec.DecodeResult.Ok).file
        val total = file.daySummary.size + file.appUsage.size + file.hourUsage.size +
            file.pullLog.size + file.unlockEvent.size + file.notificationEvent.size +
            file.screenSession.size + file.appLimit.size
        assertEquals(8, total) // 八张表各一行
        assertEquals("MINT", file.settings?.themeVariant)
        assertEquals(60, file.appLimit.first().limitMin)
    }

    @Test
    fun `缺 schemaVersion 给明确错误`() {
        val result = BackupCodec.decode("""{"exportedAt":"x"}""")
        assertTrue(result is BackupCodec.DecodeResult.Invalid)
        assertTrue((result as BackupCodec.DecodeResult.Invalid).reason.contains("schemaVersion"))
    }

    @Test
    fun `版本高于当前 App 时拒绝`() {
        val result = BackupCodec.decode(BackupCodec.encode(sampleFile(version = 99)))
        assertTrue(result is BackupCodec.DecodeResult.Invalid)
        assertTrue((result as BackupCodec.DecodeResult.Invalid).reason.contains("99"))
    }

    @Test
    fun `非 JSON 文本拒绝`() {
        val result = BackupCodec.decode("这不是 json")
        assertTrue(result is BackupCodec.DecodeResult.Invalid)
    }

    @Test
    fun `v1 备份逐条提示缺表但不阻断`() {
        val v1 = BackupFile(
            schemaVersion = 1,
            exportedAt = "2026-10-01 08:00",
            appVersion = "0.0.9",
            daySummary = listOf(day("2026-09-30", 90))
        )
        val result = BackupCodec.decode(BackupCodec.encode(v1))
        assertTrue(result is BackupCodec.DecodeResult.Ok)
        val summary = (result as BackupCodec.DecodeResult.Ok).summary
        assertEquals(3, summary.missingTables.size)
        assertTrue(summary.missingTables.any { it.contains("事件明细") })
        assertTrue(summary.missingTables.any { it.contains("会话区间") })
        assertTrue(summary.missingTables.any { it.contains("应用限额") })
        assertEquals("2026-09-30", summary.firstDate)
    }
}
