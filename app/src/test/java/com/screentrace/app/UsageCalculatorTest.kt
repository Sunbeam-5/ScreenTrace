package com.screentrace.app

import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.DaySummary
import com.screentrace.app.contract.HourUsage
import com.screentrace.app.contract.UsageCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/**
 * 领域计算器的纯 JVM 回归测试，覆盖需求文档 §5.6 / §5.7 / §7-11 的关键口径。
 * 解析器 golden fixtures（M:SS、事件去重、跨零点）属于 §7-5，需另建测试资源。
 */
class UsageCalculatorTest {

    private val day1 = LocalDate.of(2026, 11, 3)
    private val day7 = LocalDate.of(2026, 11, 9)

    private fun record(date: LocalDate, minutes: Int, unlocks: Int = 10, longest: Int = 25) =
        DaySummary(date, minutes + 8, minutes, unlocks, 20, longest, 0L)

    @Test
    fun averageOnlyDividesRecordedDays() {
        val selection = DateSelection.Range(day1, day7) // 7 个自然日
        val summary = UsageCalculator.summarize(
            selection,
            listOf(record(day1, 200), record(day1.plusDays(2), 100))
        )
        assertEquals(7, summary.totalDays)
        assertEquals(2, summary.recordedDays)
        assertEquals(300, summary.totalScreenUnlockMin)
        assertEquals(150, summary.averageScreenUnlockMin) // 不能除以 7 → 42
        assertTrue(summary.hasAnyRecord)
    }

    @Test
    fun missingDayIsNotRealZero() {
        val selection = DateSelection.Range(day1, day7)
        val points = UsageCalculator.dailyPoints(selection, listOf(record(day1, 180)))
        assertEquals(7, points.size)
        assertEquals(180, points.first().minutes)
        assertTrue(points.first().recorded)
        assertFalse(points[1].recorded)
        assertEquals(0, points[1].minutes)
    }

    @Test
    fun previousPeriodHasEqualLengthAndNoOverlap() {
        val selection = DateSelection.lastDays(7, day7)
        val previous = UsageCalculator.previousPeriod(selection)
        assertEquals(7, previous.days)
        assertEquals(selection.startDate.minusDays(1), previous.endDate)
        assertEquals(selection.startDate.minusDays(7), previous.startDate)
    }

    @Test
    fun reversedRangeIsNormalized() {
        val range = DateSelection.Range.normalized(day7, day1)
        assertEquals(day1, range.startDate)
        assertEquals(day7, range.endDate)
    }

    @Test
    fun nightWindowIs22To6ExclusiveOf6() {
        val hours = listOf(21, 22, 23, 0, 5, 6).map { HourUsage(day1, it, 10) }
        val periods = UsageCalculator.periodBuckets(hours)
        assertEquals(40, periods[3]) // 22、23、0、5
        assertEquals(10, periods[0]) // 06
        assertEquals(40, UsageCalculator.nightMinutes(hours))
        assertEquals(60, periods.sum())
    }

    @Test
    fun monthAndYearLevelsUseDocumentThresholds() {
        assertEquals(0, UsageCalculator.monthLevel(0, recorded = true))
        assertEquals(0, UsageCalculator.monthLevel(300, recorded = false))
        assertEquals(1, UsageCalculator.monthLevel(119, recorded = true))
        assertEquals(2, UsageCalculator.monthLevel(120, recorded = true))
        assertEquals(3, UsageCalculator.monthLevel(240, recorded = true))
        assertEquals(3, UsageCalculator.yearLevel(240, recorded = true))
        assertEquals(4, UsageCalculator.yearLevel(360, recorded = true))
    }

    @Test
    fun durationAndClockFormatsAreUnified() {
        assertEquals("4:32", UsageCalculator.formatClock(272))
        assertEquals("0分钟", UsageCalculator.formatDuration(0))
        assertEquals("45分钟", UsageCalculator.formatDuration(45))
        assertEquals("2小时", UsageCalculator.formatDuration(120))
        assertEquals("2小时5分钟", UsageCalculator.formatDuration(125))
    }

    @Test
    fun streakCountsConsecutiveDaysUpToAnchor() {
        // 两段连续：day1–day3 与 day5–day7，均为 3 天；锚点 day7 有使用
        val records = listOf(
            record(day1, 100), record(day1.plusDays(1), 100), record(day1.plusDays(2), 100),
            record(day1.plusDays(4), 50), record(day1.plusDays(5), 80), record(day7, 90)
        )
        val info = UsageCalculator.streak(records, day7)
        assertEquals(3, info.currentDays) // 以锚点为末端的连续段
        assertEquals(3, info.longestDays) // 并列时取更早的一段
        assertEquals(day1, info.longestStart)

        // 锚点日真实 0 分钟 → 当前 streak 归零，最长段不受影响
        val zeroAnchor = UsageCalculator.streak(records.filter { it.date != day7 } + record(day7, 0), day7)
        assertEquals(0, zeroAnchor.currentDays)
        assertEquals(3, zeroAnchor.longestDays)
    }

    @Test
    fun extremesOnlyCountRecordedDaysInRange() {
        val selection = DateSelection.Range(day1, day7)
        val records = listOf(
            record(day1, 100),          // 有记录，低于 2h
            record(day1.plusDays(1), 0), // 真实 0 分钟：计入低于 2h 且参与最短竞争
            record(day1.plusDays(3), 250),
            record(day7.plusDays(2), 999) // 区间外，忽略
        )
        val info = UsageCalculator.extremes(selection, records)
        assertEquals(day1.plusDays(3), info.longestDate)
        assertEquals(250, info.longestMinutes)
        assertEquals(day1.plusDays(1), info.shortestDate)
        assertEquals(0, info.shortestMinutes)
        assertEquals(2, info.under2hDays)

        val empty = UsageCalculator.extremes(selection, emptyList())
        assertNull(empty.longestDate)
        assertNull(empty.shortestDate)
        assertEquals(0, empty.under2hDays)
    }

    @Test
    fun monthLevelsDistinguishRealZeroFromMissing() {
        val yearMonth = YearMonth.of(2026, 11) // 30 天
        val records = listOf(
            record(yearMonth.atDay(2), 0),   // 真实 0 分钟
            record(yearMonth.atDay(3), 130)  // [2h,4h) → 2 档
        )
        val levels = UsageCalculator.monthLevels(yearMonth, records)
        assertEquals(30, levels.size)
        assertTrue(levels[1].recorded)
        assertEquals(0, levels[1].level) // 真实 0：不着色但 recorded
        assertEquals(2, levels[2].level)
        assertFalse(levels[4].recorded) // 无记录
        assertEquals(0, levels[4].level)
    }

    @Test
    fun yearLevelsAlignToMondayWeeksAndKeepAnchorLevel() {
        val records = listOf(record(day7, 400)) // ≥6h → 4 档
        val levels = UsageCalculator.yearLevels(day7, 2, records)
        assertEquals(14, levels.size)
        // 周一对齐：锚点所在周 + 往前 1 周，共 2 周 × 7 天
        val anchorMonday = day7.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        assertEquals(anchorMonday.minusDays(7), levels.first().date)
        assertEquals(anchorMonday.plusDays(6), levels.last().date)
        // 锚点自身在网格内且保留档位（锚点之后为「未来」空档）
        val anchorCell = levels.first { it.date == day7 }
        assertTrue(anchorCell.recorded)
        assertEquals(4, anchorCell.level)
    }
}
