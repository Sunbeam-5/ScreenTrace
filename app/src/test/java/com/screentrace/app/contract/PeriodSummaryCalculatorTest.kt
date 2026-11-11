package com.screentrace.app

import com.screentrace.app.contract.AppCategory
import com.screentrace.app.contract.AppUsage
import com.screentrace.app.contract.DailyPoint
import com.screentrace.app.contract.HourBucket
import com.screentrace.app.contract.PeriodSummaryCalculator
import com.screentrace.app.contract.PeriodType
import com.screentrace.app.contract.UsageCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * §5.11 / §7-27 周期总结的纯函数回归。
 *
 * 覆盖重点（都是「换个日期就会错」的地方）：
 * - 周起点是**周一**、跨年周（2026-01-01 属于 2025-12-29 那一周）；
 * - 当期未结束 → `unfinished`、数据窗口只到「今天」，覆盖率分母用**已过天数**；
 * - 对比用**上一周期的等长窗口**（未结束时截断到同样天数），不是整个上一周期；
 * - 无记录日不进日均分母、也不画成 0 柱；
 * - 年视图是 12 根「月日均」柱，只有有记录的月份才出现；
 * - 结论：数据不足不出结论；文案不许出现因果归因或健康建议。
 */
class PeriodSummaryCalculatorTest {

    /** 2026-11-12 是**周四**：本周 = 11/9（周一）–11/15（周日），已过 4 天。 */
    private val thursday = LocalDate.of(2026, 11, 12)

    private fun point(date: LocalDate, minutes: Int?, ) = DailyPoint(
        date = date,
        minutes = minutes ?: 0,
        recorded = minutes != null
    )

    private fun app(pkg: String, name: String, minutes: Int) = AppUsage(
        date = thursday,
        pkg = pkg,
        name = name,
        minutes = minutes,
        launches = 1,
        category = AppCategory.OTHER
    )

    /** 周一~周四 4 天都有的周窗口数据；周五四天之后不填（未到）。 */
    private fun weekTrend(minutes: List<Int?>) = minutes.mapIndexed { index, value ->
        point(LocalDate.of(2026, 11, 9).plusDays(index.toLong()), value)
    }

    @Test
    fun weekStartsOnMondayAndIsUnfinished() {
        val range = PeriodType.WEEK.natural(thursday)
        assertEquals(LocalDate.of(2026, 11, 9), range.start)
        assertEquals(LocalDate.of(2026, 11, 15), range.end)
        assertEquals(thursday, range.dataEnd)
        assertTrue(range.unfinished)
        assertEquals(4, range.dayCount)      // 只算已过去的 4 天
        assertEquals(7, range.fullDayCount)
    }

    @Test
    fun weekAcrossNewYearBelongsToPreviousDecemberWeek() {
        // 2026-01-01 周四：ISO 周一起始，本周从 2025-12-29（周一）开始
        val range = PeriodType.WEEK.natural(LocalDate.of(2026, 1, 1))
        assertEquals(LocalDate.of(2025, 12, 29), range.start)
        assertEquals(LocalDate.of(2026, 1, 4), range.end)
        assertEquals(4, range.dayCount)
    }

    @Test
    fun previousWeekIsEqualLengthNotWholeWeek() {
        // 未结束的 4 天 → 上一周期同样只取 4 天（11/2–11/5），而不是整周 11/2–11/8
        val range = PeriodType.WEEK.natural(thursday)
        val previous = range.previousEqualLength()
        assertEquals(LocalDate.of(2026, 11, 2), previous.start)
        assertEquals(LocalDate.of(2026, 11, 5), previous.end)
        assertEquals(4, previous.dayCount)
        assertFalse(previous.unfinished) // 上一周期整段都已经过去
    }

    @Test
    fun monthWindowUsesElapsedDaysAsDenominator() {
        val range = PeriodType.MONTH.natural(thursday)
        assertEquals(LocalDate.of(2026, 11, 1), range.start)
        assertEquals(LocalDate.of(2026, 11, 30), range.end)
        assertEquals(12, range.dayCount)
        assertTrue(range.unfinished)

        // 上一周期：10/1–10/12（等长 12 天），不是整个 10 月
        val previous = range.previousEqualLength()
        assertEquals(LocalDate.of(2026, 10, 1), previous.start)
        assertEquals(LocalDate.of(2026, 10, 12), previous.end)
        assertEquals(12, previous.dayCount)
    }

    @Test
    fun yearWindowSpansWholeYearButDataEndsToday() {
        val range = PeriodType.YEAR.natural(thursday)
        assertEquals(LocalDate.of(2026, 1, 1), range.start)
        assertEquals(LocalDate.of(2026, 12, 31), range.end)
        assertEquals(316, range.dayCount) // 1/1 – 11/12
        assertTrue(range.unfinished)
    }

    @Test
    fun averageIgnoresUnrecordedDays() {
        // 4 天里只有 2 天有记录：日均 = (120+240)/2 = 180，不是 /4 = 90
        val period = PeriodSummaryCalculator.build(
            type = PeriodType.WEEK,
            today = thursday,
            trend = weekTrend(listOf(120, null, 240, null)),
            previousSummary = null,
            apps = emptyList(),
            hours = emptyList()
        )
        assertEquals(2, period.summary.recordedDays)
        assertEquals(4, period.summary.totalDays)
        assertEquals(2, period.gapDays)
        assertEquals(360, period.summary.totalScreenUnlockMin)
        assertEquals(180, period.summary.averageScreenUnlockMin)
        assertEquals("暂无上一周期数据", period.compare.text)
        assertNull(period.compare.increase)
    }

    @Test
    fun unrecordedDaysAreNotDrawnAsZeroBars() {
        val period = PeriodSummaryCalculator.build(
            type = PeriodType.WEEK,
            today = thursday,
            trend = weekTrend(listOf(120, null, 240, null)),
            previousSummary = null,
            apps = emptyList(),
            hours = emptyList()
        )
        assertEquals(4, period.trend.size)
        assertTrue(period.trend[1].recorded.not())
        assertEquals(0, period.trend[1].minutes)
        assertTrue(period.trend[0].recorded)
    }

    @Test
    fun compareUsesAverageOfPreviousEqualWindow() {
        // 本周日均 180；上一周期（同样 4 天）日均 150 → +20%
        val previous = UsageCalculator.summarizePoints(
            com.screentrace.app.contract.DateSelection.Range(
                LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 11, 5)
            ),
            listOf(
                point(LocalDate.of(2026, 11, 2), 150),
                point(LocalDate.of(2026, 11, 3), 150)
            )
        )
        val period = PeriodSummaryCalculator.build(
            type = PeriodType.WEEK,
            today = thursday,
            trend = weekTrend(listOf(180, 180, null, null)),
            previousSummary = previous,
            apps = emptyList(),
            hours = emptyList()
        )
        assertEquals("较上周日均增加 20%", period.compare.text)
        assertEquals(true, period.compare.increase)
        assertEquals(previous, period.previous?.summary)
    }

    @Test
    fun longestAndShortestSkipUnrecordedDays() {
        val period = PeriodSummaryCalculator.build(
            type = PeriodType.WEEK,
            today = thursday,
            trend = weekTrend(listOf(300, null, 60, 180)),
            previousSummary = null,
            apps = emptyList(),
            hours = emptyList()
        )
        assertEquals(LocalDate.of(2026, 11, 9), period.extremes.longestDate)
        assertEquals(300, period.extremes.longestMinutes)
        // 最短是有记录的 11/11（60 分钟），不是无记录的 11/10
        assertEquals(LocalDate.of(2026, 11, 11), period.extremes.shortestDate)
        assertEquals(60, period.extremes.shortestMinutes)
    }

    @Test
    fun topFiveAppsSumAndPercentDenominator() {
        val apps = (1..6).map { app("p$it", "应用$it", it * 10) } // 10..60
        val period = PeriodSummaryCalculator.build(
            type = PeriodType.WEEK,
            today = thursday,
            trend = weekTrend(listOf(200, 200, 200, 200)),
            previousSummary = null,
            apps = apps,
            hours = emptyList()
        )
        assertEquals(5, period.topApps.size)
        assertEquals("应用6", period.topApps.first().name) // 降序
        assertEquals(210, period.appTotalMinutes)          // 10+20+30+40+50+60
    }

    @Test
    fun yearTrendIsMonthlyAverageAndHidesEmptyMonths() {
        val trend = buildList {
            add(point(LocalDate.of(2026, 1, 5), 100))
            add(point(LocalDate.of(2026, 1, 6), 200)) // 1 月日均 = 150
            add(point(LocalDate.of(2026, 3, 1), 60))  // 3 月日均 = 60
            add(point(LocalDate.of(2026, 3, 2), null))
            add(point(thursday, 300))                 // 11 月
        }
        val period = PeriodSummaryCalculator.build(
            type = PeriodType.YEAR,
            today = thursday,
            trend = trend,
            previousSummary = null,
            apps = emptyList(),
            hours = emptyList()
        )
        assertEquals(listOf(1, 3, 11), period.monthTrend.map { it.month })
        assertEquals(150, period.monthTrend[0].averageMinutes)
        assertEquals(60, period.monthTrend[1].averageMinutes)
        assertEquals(2, period.monthTrend[0].recordedDays)
        assertTrue(period.monthTrend[0].finished)
        assertFalse(period.monthTrend[2].finished) // 11 月还没过完
        // 年视图不用逐日柱，避免 365 根柱子
        assertTrue(period.trend.isEmpty())
    }

    @Test
    fun insufficientDataProducesNoInsights() {
        val period = PeriodSummaryCalculator.build(
            type = PeriodType.WEEK,
            today = thursday,
            trend = weekTrend(listOf(120, null, null, null)),
            previousSummary = null,
            apps = listOf(app("p", "微信", 60)),
            hours = listOf(HourBucket(9, 60))
        )
        assertTrue(period.insufficient)
        assertTrue(period.insights.isEmpty())
    }

    @Test
    fun insightsStateFactsWithoutCausalOrAdviceWording() {
        val period = PeriodSummaryCalculator.build(
            type = PeriodType.WEEK,
            today = thursday,
            trend = weekTrend(listOf(120, 480, 60, 240)),
            previousSummary = UsageCalculator.summarizePoints(
                com.screentrace.app.contract.DateSelection.Range(
                    LocalDate.of(2026, 11, 2),
                    LocalDate.of(2026, 11, 5)
                ),
                listOf(point(LocalDate.of(2026, 11, 2), 100))
            ),
            apps = listOf(app("com.tencent.mm", "微信", 300), app("p2", "抖音", 100)),
            hours = listOf(
                HourBucket(9, 100),
                HourBucket(14, 100),
                HourBucket(20, 100),
                HourBucket(23, 300)
            )
        )
        assertFalse(period.insufficient)
        assertTrue(period.insights.size in 3..6)
        // 最长的一天是 11/10（周二）
        assertTrue(period.insights.any { it.contains("周二") && it.contains("使用最长") })
        // 夜间占比 (300/600) = 50%，且是四段最高
        assertEquals(50, period.nightPercent)
        assertTrue(period.insights.any { it.contains("夜间") && it.contains("50%") })
        assertTrue(period.insights.any { it.contains("微信") })
        // 文案纪律：不许出现因果归因 / 健康建议
        val banned = listOf("因为", "所以", "建议", "应该", "注意", "健康", "小心")
        period.insights.forEach { line ->
            banned.forEach { word ->
                assertFalse("结论不得含「$word」：$line", line.contains(word))
            }
        }
    }

    @Test
    fun subtitleMarksUnfinishedPeriodWithDataEnd() {
        val period = PeriodSummaryCalculator.build(
            type = PeriodType.MONTH,
            today = thursday,
            trend = emptyList(),
            previousSummary = null,
            apps = emptyList(),
            hours = emptyList()
        )
        assertEquals("本月", period.title)
        assertTrue(period.subtitle.contains("11月1日–11月30日"))
        assertTrue(period.subtitle.contains("未结束"))
        assertTrue(period.subtitle.contains("截至 11月12日"))
    }

    @Test
    fun plainTextExportSharesSameNumbers() {
        val apps = listOf(app("p", "微信", 120))
        val period = PeriodSummaryCalculator.build(
            type = PeriodType.WEEK,
            today = thursday,
            trend = weekTrend(listOf(120, 240, 60, 180)),
            previousSummary = null,
            apps = apps,
            hours = listOf(HourBucket(9, 300), HourBucket(23, 300))
        )
        val text = PeriodSummaryCalculator.toPlainText(period)
        assertTrue(text.contains("本周")) // 标题
        assertTrue(text.contains("总时长 10小时"))
        assertTrue(text.contains("日均 2小时30分钟"))
        assertTrue(text.contains("有记录 4 天 / 共 4 天"))
        assertTrue(text.contains("微信"))
        // 隐藏应用名称：同源数据只换显示名，占比与时长不变
        val hidden = PeriodSummaryCalculator.toPlainText(period, hideAppNames = true)
        assertFalse(hidden.contains("微信"))
        assertTrue(hidden.contains("应用 2小时"))
    }
}
