package com.screentrace.app.contract

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CR-15 评审 P1-2：周期总结页「时段分布」的**接线**回归。
 *
 * `DailyAveragePeriodsTest` 只覆盖纯函数本身；把 `PeriodSummaryCalculator.build`
 * 里的 `dailyAveragePeriods` 改回 `periodBuckets(hours)`（区间累计）时，原有测试
 * 一条都不会红。这里直接打靶 `build()` 产出的 `period.periods`：去掉除法必须变红。
 */
class PeriodPeriodsWiringTest {

    /** 2026-11-12 是周四：本周 11/9–11/15，已过 4 天。 */
    private val thursday = LocalDate.of(2026, 11, 12)

    @Test
    fun `周期总结时段分布用有记录日日均而不是区间累计`() {
        // 4 天有记录，其中 11/12 为今日进行中（D-07 拍板 2 排除）→ recordedDays = 4, completeDays = 3
        val trend = (0L..3L).map {
            DailyPoint(LocalDate.of(2026, 11, 9).plusDays(it), 120, true)
        }
        // 06–11 时各 40 分钟 → 06–12 段累计 240 分钟；完整日日均 = 240 / 3 = 80
        val hours = (6..11).map { HourBucket(it, 40) }

        val period = PeriodSummaryCalculator.build(
            type = PeriodType.WEEK,
            today = thursday,
            trend = trend,
            previousSummary = null,
            apps = emptyList(),
            hours = hours
        )

        assertEquals(4, period.summary.recordedDays)
        assertEquals(3, period.summary.completeDays)
        assertEquals("周期页接线必须走 dailyAveragePeriods", 80, period.periods[0])
        assertEquals(80, period.periods.sum())
    }
}
