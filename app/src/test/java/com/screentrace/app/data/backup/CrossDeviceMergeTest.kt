package com.screentrace.app.data.backup

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §4 CR-21：跨设备**累加**合并的纯函数口径。
 *
 * 这组测试的对照物是 [BackupMergeTest]（同设备「取较大」口径）——两套口径必须
 * 分得开：累加的同日结果是**相加**，不是取大。
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
    fun `同日的使用量相加 而不是取较大`() {
        val plan = CrossDeviceMerge.daySummaries(
            local = listOf(day("2026-11-01", screen = 100, unlock = 90, unlocks = 10, launches = 20)),
            backup = listOf(day("2026-11-01", screen = 60, unlock = 50, unlocks = 5, launches = 8))
        )
        val row = plan.rows.single()
        assertEquals(160, row.screenMin)
        assertEquals(140, row.screenUnlockMin)
        assertEquals(15, row.unlocks)
        assertEquals(28, row.launches)
    }

    @Test
    fun `longest 取较大 pulledAt 取较晚`() {
        val plan = CrossDeviceMerge.daySummaries(
            local = listOf(day("2026-11-01", longest = 30, pulledAt = 1_000L)),
            backup = listOf(day("2026-11-01", longest = 25, pulledAt = 2_000L))
        )
        val row = plan.rows.single()
        assertEquals("最长连续是 max 型指标，不能相加", 30, row.longestSessionMin)
        assertEquals(2_000L, row.pulledAt)
    }

    @Test
    fun `本机没有的那天直接并入 不会被改写`() {
        val plan = CrossDeviceMerge.daySummaries(
            local = emptyList(),
            backup = listOf(day("2026-11-02", screen = 42))
        )
        assertEquals(42, plan.rows.single().screenMin)
        assertEquals(1, plan.report.added)
    }

    @Test
    fun `备份里同一天多行只取主指标最大的一行 不翻倍`() {
        val plan = CrossDeviceMerge.daySummaries(
            local = listOf(day("2026-11-01", screen = 10, unlock = 90)),
            backup = listOf(
                day("2026-11-01", screen = 30, unlock = 90),
                day("2026-11-01", screen = 50, unlock = 95)
            )
        )
        val row = plan.rows.single()
        assertEquals("同日期多行必须先去重再累加", 10 + 50, row.screenMin)
    }

    @Test
    fun `应用时长与启动数相加 名字以本机为准`() {
        val plan = CrossDeviceMerge.appUsage(
            local = listOf(AppUsageDto("2026-11-01", "com.a", "本机名", 120, 5, "SOCIAL")),
            backup = listOf(AppUsageDto("2026-11-01", "com.a", "备份名", 30, 2, "GAME"))
        )
        val row = plan.rows.single()
        assertEquals(150, row.minutes)
        assertEquals(7, row.launches)
        assertEquals("本机名", row.name)
        assertEquals("SOCIAL", row.category)
    }

    @Test
    fun `本机没有的包沿用备份里的名字与分类`() {
        val plan = CrossDeviceMerge.appUsage(
            local = emptyList(),
            backup = listOf(AppUsageDto("2026-11-01", "com.b", "旧机名", 30, 2, "GAME"))
        )
        val row = plan.rows.single()
        assertEquals(30, row.minutes)
        assertEquals("旧机名", row.name)
    }

    @Test
    fun `时段分钟相加`() {
        val plan = CrossDeviceMerge.hourUsage(
            local = listOf(HourUsageDto("2026-11-01", 9, 30)),
            backup = listOf(HourUsageDto("2026-11-01", 9, 15))
        )
        assertEquals(45, plan.rows.single().minutes)
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
