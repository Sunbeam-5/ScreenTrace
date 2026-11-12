package com.screentrace.app.uikit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CR-06（负责人勘误版）：24 小时全天环几何。变异验证过：daySweep 去掉
 * coerceIn(0f,1f) 后「超 24 小时封顶」用例变红。
 */
class RingMathTest {

    @Test
    fun `0 分钟不画弧`() {
        assertEquals(0f, RingMath.daySweep(0), 1e-6f)
    }

    @Test
    fun `弧长等于使用占 24 小时的比例`() {
        // 负责人例子：15 小时 → 15/24
        assertEquals(0.625f, RingMath.daySweep(15 * 60), 0.001f)
        // 设计稿例子：5:42 ≈ 5.7h → 5.7/24
        assertEquals(0.2375f, RingMath.daySweep(5 * 60 + 42), 0.001f)
    }

    @Test
    fun `满 24 小时为整圈`() {
        assertEquals(1f, RingMath.daySweep(24 * 60), 1e-6f)
    }

    @Test
    fun `超过 24 小时封顶不溢出`() {
        assertEquals(1f, RingMath.daySweep(26 * 60), 1e-6f)
    }

    @Test
    fun `超出 chip 只在真正超目标时出现`() {
        assertFalse(RingMath.shouldShowOverChip(usageMinutes = 200, goalMinutes = 240))
        assertFalse(RingMath.shouldShowOverChip(usageMinutes = 240, goalMinutes = 240)) // 恰好等于不算超
        assertFalse(RingMath.shouldShowOverChip(usageMinutes = 300, goalMinutes = 0))   // 无目标不出
        assertTrue(RingMath.shouldShowOverChip(usageMinutes = 282, goalMinutes = 180))
    }

    @Test
    fun `超出分钟数为正差`() {
        assertEquals(102, RingMath.overMinutes(usageMinutes = 282, goalMinutes = 180)) // 4h42 - 3h
        assertEquals(0, RingMath.overMinutes(usageMinutes = 100, goalMinutes = 180))
    }
}
