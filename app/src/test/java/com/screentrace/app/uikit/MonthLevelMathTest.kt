package com.screentrace.app.uikit

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CR-07 §4.8 / 需求 §5.7 第 2 条：月历四档按「当天使用 / 每日目标」比值切分。
 * 档位边界（≤100%/≤140%/≤175%）与退化路径（无记录/真实 0/目标未设置）都是纯函数，
 * 放 JVM 单测钉死。
 */
class MonthLevelMathTest {

    @Test
    fun `无记录 null 与 0 分钟一律 0 档不着色`() {
        assertEquals(0, MonthLevelMath.levelOf(minutes = null, recorded = false, goalMinutes = 240))
        assertEquals(0, MonthLevelMath.levelOf(minutes = 0, recorded = true, goalMinutes = 240))
        assertEquals(0, MonthLevelMath.levelOf(minutes = 0, recorded = false, goalMinutes = 240))
        assertEquals(0, MonthLevelMath.levelOf(minutes = 300, recorded = false, goalMinutes = 240))
    }

    @Test
    fun `目标未设置或非正时退化为最低有记录档`() {
        // goalMinutes = 0（未设置）：有数据但无法算比值 → 档 1，避免「有数据却全白」
        assertEquals(1, MonthLevelMath.levelOf(minutes = 5, recorded = true, goalMinutes = 0))
        assertEquals(1, MonthLevelMath.levelOf(minutes = 600, recorded = true, goalMinutes = 0))
        assertEquals(1, MonthLevelMath.levelOf(minutes = 10, recorded = true, goalMinutes = -1))
        // 负分钟异常输入也按 0 档（与 0 分钟同语义）
        assertEquals(0, MonthLevelMath.levelOf(minutes = -5, recorded = true, goalMinutes = 240))
    }

    @Test
    fun `比值边界 100 140 175 百分比逐点钉死`() {
        val goal = 240 // 4 小时，与设计稿示例一致
        // ≤100% → 1
        assertEquals(1, MonthLevelMath.levelOf(240, recorded = true, goalMinutes = goal))
        assertEquals(1, MonthLevelMath.levelOf(239, recorded = true, goalMinutes = goal))
        // (100%, 140%] → 2
        assertEquals(2, MonthLevelMath.levelOf(241, recorded = true, goalMinutes = goal))
        assertEquals(2, MonthLevelMath.levelOf(336, recorded = true, goalMinutes = goal)) // 140%
        // (140%, 175%] → 3
        assertEquals(3, MonthLevelMath.levelOf(337, recorded = true, goalMinutes = goal))
        assertEquals(3, MonthLevelMath.levelOf(420, recorded = true, goalMinutes = goal)) // 175%
        // >175% → 4
        assertEquals(4, MonthLevelMath.levelOf(421, recorded = true, goalMinutes = goal))
    }

    @Test
    fun `改目标后同一天的档位随之重算`() {
        // 需求 §5.7：目标改 6h 后，同样 3h 应从高档降到低档（240 目标时 180min=75%→1；
        // 但 240min 在 240 目标=100%→1、在 180 目标=133%→2，证明读的是传入目标）
        assertEquals(2, MonthLevelMath.levelOf(240, recorded = true, goalMinutes = 180))
        assertEquals(1, MonthLevelMath.levelOf(240, recorded = true, goalMinutes = 240))
        assertEquals(4, MonthLevelMath.levelOf(240, recorded = true, goalMinutes = 120)) // 200%
        assertEquals(3, MonthLevelMath.levelOf(300, recorded = true, goalMinutes = 180)) // 166.7%
    }
}
