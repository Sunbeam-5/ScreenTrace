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
    fun `小时表比日聚合多时按时钟跨度钳制`() {
        // 评审 P2-2 的真实危险场景：7 天都有 hour_usage，但只有 3 天写了
        // day_summary（降级通道与 Shizuku 混用）。分母 3、分子是 7 天累计：
        // 06–12 段 445×7 = 3115 分钟 ÷ 3 = 1038 分钟，远超该段 6 小时上限。
        // 旧版「输入 = 上限 × 7、除以 7」的用例是恒真的（输出必然等于输入/7），
        // 证明不了任何东西；这里改用不可能的输入，断言结果被显式钳制。
        val caps = listOf(360, 360, 240, 480)
        val cumulative = listOf(445, 600, 300, 900).map { it * 7 }
        assertEquals(caps, UsageCalculator.dailyAveragePeriods(cumulative, 3))
    }

    @Test
    fun `恰好达到时钟跨度的单日数据不被钳制误伤`() {
        // 单日区间、四段都刚好用满：钳制不得把合法值改小
        val caps = listOf(360, 360, 240, 480)
        assertEquals(caps, UsageCalculator.dailyAveragePeriods(caps, 1))
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
