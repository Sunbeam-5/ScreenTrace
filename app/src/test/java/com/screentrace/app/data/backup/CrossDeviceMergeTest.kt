package com.screentrace.app.data.backup

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CR-28：跨设备合并的**写前去重**纯函数 + 日期/账本辅助口径。
 *
 * 历史注记：这里原是 CR-21「跨设备**累加**」的口径测试。CR-28 把落库形态改为
 * **按来源分量写行**（同来源取大，见 [BackupMergeTest]），**跨来源求和只发生在
 * 读侧聚合**（见 `CrossSourceAggregationTest`）——累加写入口径废除，本文件
 * 只保留「备份内重复行去重」与日期口径两组纯函数。
 */
class CrossDeviceMergeTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun day(
        date: String,
        screen: Int = 100,
        unlock: Int = 90,
        unlocks: Int = 10,
        launches: Int = 20,
        longest: Int = 30,
        pulledAt: Long = 1_000L
    ) = DaySummaryDto(date, screen, unlock, unlocks, launches, longest, pulledAt)

    @Test
    fun `备份里同一天多行只留主指标最大的一行 不翻倍`() {
        val rows = CrossDeviceMerge.dedupeDaySummaries(
            listOf(
                day("2026-11-01", screen = 30, unlock = 90),
                day("2026-11-01", screen = 50, unlock = 95),
                day("2026-11-02", screen = 10)
            )
        )
        assertEquals(2, rows.size)
        val winner = rows.single { it.date == "2026-11-01" }
        assertEquals("取 screen_unlock_min 较大的整行", 95, winner.screenUnlockMin)
        assertEquals(50, winner.screenMin)
    }

    @Test
    fun `平手时次级比较 screenMin 且胜者整行保留不拼字段`() {
        val rows = CrossDeviceMerge.dedupeDaySummaries(
            listOf(
                day("2026-11-01", screen = 30, unlock = 90, unlocks = 1, launches = 1, longest = 5),
                day("2026-11-01", screen = 50, unlock = 90, unlocks = 2, launches = 2, longest = 7)
            )
        )
        val winner = rows.single()
        assertEquals(50, winner.screenMin)
        assertEquals("整行保留胜者字段，不逐字段拼", 2, winner.unlocks)
        assertEquals(2, winner.launches)
        assertEquals(7, winner.longestSessionMin)
    }

    @Test
    fun `应用与时段同键去重 取 minutes 最大的一行`() {
        val apps = CrossDeviceMerge.dedupeAppUsage(
            listOf(
                AppUsageDto("2026-11-01", "com.a", "小", 30, 2, "GAME"),
                AppUsageDto("2026-11-01", "com.a", "大", 50, 3, "SOCIAL"),
                AppUsageDto("2026-11-01", "com.b", "B", 10, 1, "TOOLS")
            )
        )
        assertEquals(2, apps.size)
        val winner = apps.single { it.pkg == "com.a" }
        assertEquals(50, winner.minutes)
        assertEquals("大", winner.name)

        val hours = CrossDeviceMerge.dedupeHourUsage(
            listOf(
                HourUsageDto("2026-11-01", 9, 30),
                HourUsageDto("2026-11-01", 9, 15)
            )
        )
        assertEquals(30, hours.single().minutes)
    }

    @Test
    fun `防重复：已合并过的日期不再并入`() {
        val file = BackupFile(
            schemaVersion = 5,
            exportedAt = "2026-11-16 10:00",
            appVersion = "1.0",
            daySummary = listOf(day("2026-11-01"), day("2026-11-02"))
        )
        val allowed = CrossDeviceMerge.allowedDatesOf(file, setOf("2026-11-01"), zone)
        assertEquals(setOf("2026-11-02"), allowed)
    }

    @Test
    fun `日期集合含事件与会话 不会漏掉只有事件的日期`() {
        val file = BackupFile(
            schemaVersion = 5,
            exportedAt = "2026-11-16 10:00",
            appVersion = "1.0",
            unlockEvent = listOf(UnlockEventDto(1_767_225_600_000L)), // 2026-01-01T00:00Z
            screenSession = listOf(ScreenSessionDto(1_767_225_600_000L, 1_767_229_600_000L, "SCREEN"))
        )
        val dates = CrossDeviceMerge.datesOf(file, zone)
        assertTrue("事件日期必须计入", dates.contains("2026-01-01"))
    }

    @Test
    fun `跨零点的会话跨两天`() {
        val start = 1_767_225_600_000L // 2026-01-01T00:00Z
        val end = start + 36 * 60 * 60 * 1000L // 2026-01-02T12:00Z
        val dates = CrossDeviceMerge.datesOfSession(start, end, zone)
        assertEquals(setOf("2026-01-01", "2026-01-02"), dates)
    }
}
