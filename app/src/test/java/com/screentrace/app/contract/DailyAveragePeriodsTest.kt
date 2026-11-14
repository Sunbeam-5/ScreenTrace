package com.screentrace.app.contract

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CR-15 A1 / §5.7 第 7 条：时段分布必须是「有记录日日均」，不能把区间 SUM 累计值
 * 直接渲染（真机 06–12 显示 7小时25分的根因）。
 *
 * 变异锚点：去掉 `dailyAveragePeriods` 里的除法（或把分母改成区间自然日），
 * 下面除「单日区间」外的用例都会变红。
 */
class DailyAveragePeriodsTest {

    @Test
    fun `七日累计归一为日均分钟`() {
        val cumulative = listOf(445, 600, 300, 900)
        assertEquals(listOf(64, 86, 43, 129), UsageCalculator.dailyAveragePeriods(cumulative, 7))
    }

    @Test
    fun `四段日均不超过各自时钟跨度`() {
        // 06–12 / 12–18 / 18–22 / 22–06 = 6h / 6h / 4h / 8h
        val caps = listOf(360, 360, 240, 480)
        val cumulative = caps.map { it * 7 }
        assertEquals(caps, UsageCalculator.dailyAveragePeriods(cumulative, 7))
        assertEquals(
            true,
            UsageCalculator.dailyAveragePeriods(cumulative, 7)
                .zip(caps).all { (actual, cap) -> actual <= cap }
        )
    }

    @Test
    fun `单日区间等于原累计值`() {
        val cumulative = listOf(120, 90, 60, 200)
        assertEquals(cumulative, UsageCalculator.dailyAveragePeriods(cumulative, 1))
    }

    @Test
    fun `分母只含有记录日而非区间自然日`() {
        // 近 30 天里只有 7 天有记录：按 7 除得 64，不是按 30 除得 15
        assertEquals(64, UsageCalculator.dailyAveragePeriods(listOf(445), 7)[0])
        assertEquals(15, UsageCalculator.dailyAveragePeriods(listOf(445), 30)[0])
    }

    @Test
    fun `无记录日返回全零不渲染假数值`() {
        assertEquals(
            listOf(0, 0, 0, 0),
            UsageCalculator.dailyAveragePeriods(listOf(445, 600, 300, 900), 0)
        )
        assertEquals(
            listOf(0, 0, 0, 0),
            UsageCalculator.dailyAveragePeriods(listOf(445, 600, 300, 900), -1)
        )
    }
}
