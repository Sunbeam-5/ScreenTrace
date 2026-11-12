package com.screentrace.app.uikit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CR-07 Step 5（统计页）的纯函数：整数百分比分配与柱图选中日规则。
 * 变异验证过：跳过最大余数法、把默认选中改成第 0 天，本文件对应用例均变红。
 */
class StatsStep5MathTest {

    @Test
    fun `三等分百分比合计一百且并列时靠前者优先`() {
        val percents = PercentMath.distribute(listOf(10, 10, 10))
        assertEquals(listOf(34, 33, 33), percents)
        assertEquals(100, percents.sum())
    }

    @Test
    fun `零值段不会被补到百分之一`() {
        val percents = PercentMath.distribute(listOf(10, 10, 10, 0))
        assertEquals(0, percents.last())
        assertEquals(100, percents.sum())
    }

    @Test
    fun `全零与空列表都返回等长零值`() {
        assertEquals(listOf(0, 0, 0, 0), PercentMath.distribute(listOf(0, 0, 0, 0)))
        assertTrue(PercentMath.distribute(emptyList()).isEmpty())
    }

    @Test
    fun `百分比长度与输入一致`() {
        val input = listOf(84, 132, 210, 45)
        val percents = PercentMath.distribute(input)
        assertEquals(input.size, percents.size)
        assertEquals(100, percents.sum())
    }

    @Test
    fun `默认选中最后一天而不是第一天`() {
        assertEquals(6, TrendSelectionMath.defaultIndex(7))
        assertEquals(29, TrendSelectionMath.defaultIndex(30))
        assertEquals(-1, TrendSelectionMath.defaultIndex(0))
    }

    @Test
    fun `左右滑动在两端夹取不越界`() {
        assertEquals(5, TrendSelectionMath.step(6, -1, 7))
        assertEquals(0, TrendSelectionMath.step(0, -1, 7))
        assertEquals(6, TrendSelectionMath.step(6, 1, 7))
        assertEquals(1, TrendSelectionMath.step(0, 1, 7))
        assertEquals(-1, TrendSelectionMath.step(0, 1, 0))
    }
}
