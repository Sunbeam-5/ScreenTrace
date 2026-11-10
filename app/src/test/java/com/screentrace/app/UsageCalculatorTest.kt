package com.screentrace.app

import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.DaySummary
import com.screentrace.app.contract.HourUsage
import com.screentrace.app.contract.UsageCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

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
}
