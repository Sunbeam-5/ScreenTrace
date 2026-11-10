package com.screentrace.app

import com.screentrace.app.contract.Coverage
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.DaySummary
import com.screentrace.app.contract.HourUsage
import com.screentrace.app.contract.MetricKind
import com.screentrace.app.contract.NotificationEvent
import com.screentrace.app.contract.NotificationType
import com.screentrace.app.contract.UnlockEvent
import com.screentrace.app.contract.UsageCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * 领域计算器的纯 JVM 回归测试，覆盖需求文档 §5.6 / §5.7 / §7-11 的关键口径。
 * 解析器 golden fixtures（M:SS、事件去重、跨零点）属于 §7-5，需另建测试资源。
 */
class UsageCalculatorTest {

    private val day1 = LocalDate.of(2026, 11, 3)
    private val day3 = LocalDate.of(2026, 11, 5)
    private val day4 = LocalDate.of(2026, 11, 6)
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

    // ---- CR-01（C04）：metricDetail 事件聚合 ----

    @Test
    fun compareUsesUnifiedWording() {
        val selection = DateSelection.Range(day1, day4)
        val current = UsageCalculator.summarize(selection, listOf(record(day1, 200), record(day3, 100)))
        // 单日：分钟差
        val single = UsageCalculator.compare(
            current, UsageCalculator.summarize(selection, listOf(record(day1, 100))), single = true
        )
        assertEquals("较昨日增加 3小时20分钟", single.text)
        assertEquals(true, single.increase)
        // 无上一周期 → 不得显示 0%
        val none = UsageCalculator.compare(current, previous = null, single = true)
        assertEquals("暂无上一周期数据", none.text)
        assertNull(none.increase)
        // 区间：百分比（上一区间日均 100 → 当前 150 = +50%）
        val range = UsageCalculator.compare(
            current, UsageCalculator.summarize(selection, listOf(record(day1, 100))), single = false
        )
        assertEquals("较上一区间增加 50%", range.text)
    }

    /** 毫秒时间戳按设备本地时区构造，与计算器同口径（测试与实现时区无关）。 */
    private fun ms(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun metricDetailHourlyAggregationForSingleDay() {
        val events = listOf(
            UnlockEvent(ms(day1, 8, 0)),
            UnlockEvent(ms(day1, 8, 30)),
            UnlockEvent(ms(day1, 9, 0))
        )
        val detail = UsageCalculator.metricDetail(
            MetricKind.UNLOCK, DateSelection.Day(day1),
            unlockEvents = events, windowStart = day1
        )
        assertEquals(24, detail.points.size)
        assertEquals(2, detail.points[8].value)
        assertEquals(1, detail.points[9].value)
        assertEquals(0, detail.points[0].value)
        assertEquals(Coverage.RECORDED, detail.points[0].coverage) // 真实 0 仍算有记录
        assertEquals(3, detail.total)
        assertNull(detail.dailyAverage) // 单日无日均
        assertEquals(8, detail.peakHour)
        assertEquals(day1, detail.busiestDate)
        assertTrue(detail.appRanking.isEmpty()) // 解锁无应用归因（§5.7）
    }

    @Test
    fun metricDetailRangeAveragesOnlyRecordedDays() {
        // windowStart=day3：day1/day2 窗口外；day3×2、day4×4
        val events = listOf(
            UnlockEvent(ms(day3, 10, 0)),
            UnlockEvent(ms(day3, 11, 0)),
            UnlockEvent(ms(day4, 8, 0)),
            UnlockEvent(ms(day4, 9, 0)),
            UnlockEvent(ms(day4, 10, 0)),
            UnlockEvent(ms(day4, 11, 0))
        )
        val detail = UsageCalculator.metricDetail(
            MetricKind.UNLOCK, DateSelection.Range(day1, day4),
            unlockEvents = events, windowStart = day3
        )
        assertEquals(4, detail.points.size)
        assertEquals(Coverage.NO_RECORD, detail.points[0].coverage)
        assertEquals(Coverage.NO_RECORD, detail.points[1].coverage)
        assertEquals(Coverage.RECORDED, detail.points[2].coverage)
        assertEquals(6, detail.total)
        assertEquals(3, detail.dailyAverage) // 6 / 2 个有记录日，窗口外不拉低（§5.6）
    }

    @Test
    fun metricDetailCoverageThreeStates() {
        val detail = UsageCalculator.metricDetail(
            MetricKind.UNLOCK, DateSelection.Range(day1, day7),
            unlockEvents = emptyList(), windowStart = day3, gapDays = setOf(day1.plusDays(4))
        )
        assertEquals(Coverage.NO_RECORD, detail.points[0].coverage) // 窗口外
        assertEquals(Coverage.NO_RECORD, detail.points[1].coverage)
        assertEquals(Coverage.RECORDED, detail.points[2].coverage) // 真实 0
        assertEquals(Coverage.RECORDED, detail.points[3].coverage)
        assertEquals(Coverage.GAP, detail.points[4].coverage) // 拉取缺口
        assertEquals(Coverage.RECORDED, detail.points[5].coverage)
        assertEquals(0, detail.points[2].value)
    }

    @Test
    fun metricDetailNotificationDualMetricsAndRanking() {
        val events = listOf(
            NotificationEvent(ms(day1, 8, 0), NotificationType.INTERRUPTION, "pkg.a", null),
            NotificationEvent(ms(day1, 8, 30), NotificationType.INTERRUPTION, "pkg.a", "ch1"),
            NotificationEvent(ms(day1, 9, 0), NotificationType.INTERRUPTION, "pkg.b", null),
            NotificationEvent(ms(day1, 10, 0), NotificationType.SEEN, "pkg.a", null)
        )
        val detail = UsageCalculator.metricDetail(
            MetricKind.NOTIFICATION, DateSelection.Day(day1),
            notificationEvents = events, windowStart = day1,
            appLabels = mapOf("pkg.a" to "应用A")
        )
        assertEquals(3, detail.notification?.interruptions) // 打扰
        assertEquals(1, detail.notification?.seen) // 已查看
        assertEquals(3, detail.total) // 主指标 = 打扰，与卡片一致
        assertEquals(2, detail.appRanking.size)
        assertEquals("应用A", detail.appRanking[0].name) // 按打扰降序
        assertEquals(2, detail.appRanking[0].interruptions)
        assertEquals(1, detail.appRanking[0].seen)
        assertEquals("pkg.b", detail.appRanking[1].name) // 无映射回退包名
    }
}
