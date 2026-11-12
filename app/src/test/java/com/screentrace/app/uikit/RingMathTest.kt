package com.screentrace.app.uikit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CR-07（新规格 §4.2）：目标环双层几何。变异验证过：secondSweep 去掉
 * coerceIn 上限后「超 2 倍目标封顶」用例变红。
 */
class RingMathTest {

    @Test
    fun `未达目标只有一圈且不满`() {
        assertEquals(0.5f, RingMath.firstSweep(0.5f), 1e-6f)
        assertFalse(RingMath.shouldShowSecond(0.5f))
    }

    @Test
    fun `恰好达目标时第一圈满且无第二圈`() {
        assertEquals(1f, RingMath.firstSweep(1f), 1e-6f)
        assertFalse(RingMath.shouldShowSecond(1f))
    }

    @Test
    fun `超目标时第二圈弧长等于超出比例`() {
        // 设计稿示例：目标 4:00、已用 5:42 → progress = 1.425，第二圈 ≈ 153°（0.425 圈）
        val progress = (5 * 60 + 42) / (4f * 60) // = 1.425
        assertTrue(RingMath.shouldShowSecond(progress))
        assertEquals(1f, RingMath.firstSweep(progress), 1e-6f)
        assertEquals(0.425f, RingMath.secondSweep(progress), 0.001f) // ≈153°（设计稿示例）
    }

    @Test
    fun `超过两倍目标第二圈封顶不画螺旋`() {
        assertTrue(RingMath.shouldShowSecond(2.6f))
        assertEquals(1f, RingMath.secondSweep(2.6f), 1e-6f)
        assertEquals(2f, RingMath.clampProgress(2.6f), 1e-6f)
    }

    @Test
    fun `负值不画任何弧`() {
        assertEquals(0f, RingMath.firstSweep(-1f), 1e-6f)
        assertFalse(RingMath.shouldShowSecond(-1f))
    }
}
