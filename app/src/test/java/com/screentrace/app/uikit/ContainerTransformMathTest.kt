package com.screentrace.app.uikit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 容器变换转场的几何契约（§5.5 / §7-22）。
 *
 * 这些断言存在的理由：「终点是否真的铺满」「起点是否真的等于入口矩形」靠肉眼在逐帧
 * 截图里判断并不可靠——上一版就因为起点停在 26% 的尺寸上被移除，只有慢放才看得出来。
 */
class ContainerTransformMathTest {

    private val eps = 1e-4f

    @Test
    fun `起点等于入口矩形占比`() {
        // 卡片 525×324 在 1220×2712 的屏幕上：X 轴 0.43、Y 轴 0.12
        assertEquals(0.43f, ContainerTransformMath.scale(0.43f, 0f), eps)
        assertEquals(0.12f, ContainerTransformMath.scale(0.12f, 0f), eps)
        // 位移起点就是入口左上角
        assertEquals(969f, ContainerTransformMath.offset(969f, 0f), eps)
    }

    @Test
    fun `终点必须铺满整屏——否则边缘会露出下层的缝`() {
        assertEquals(1f, ContainerTransformMath.scale(0.43f, 1f), eps)
        assertEquals(1f, ContainerTransformMath.scale(0.12f, 1f), eps)
        assertEquals(0f, ContainerTransformMath.offset(969f, 1f), eps)
        assertEquals(0f, ContainerTransformMath.offset(147f, 1f), eps)
    }

    @Test
    fun `缩放全程单调不减且收尾于 1`() {
        var prev = -1f
        for (i in 0..100) {
            val s = ContainerTransformMath.scale(0.12f, i / 100f)
            assertTrue("i=$i 处出现回缩：$prev -> $s", s >= prev - eps)
            prev = s
        }
        assertEquals(1f, prev, eps)
    }

    @Test
    fun `位移全程单调不增且收尾于 0`() {
        var prev = Float.MAX_VALUE
        for (i in 0..100) {
            val d = ContainerTransformMath.offset(969f, i / 100f)
            assertTrue("i=$i 处位移出现回升：$prev -> $d", d <= prev + eps)
            prev = d
        }
        assertEquals(0f, prev, eps)
    }

    @Test
    fun `极小入口被夹到下限——避免缩略图小到只剩一条线`() {
        // 1dp 级的入口比例（0.0008）会被抬到 0.05
        assertEquals(0.05f, ContainerTransformMath.scale(0.0008f, 0f), eps)
        // 大入口不受影响
        assertEquals(0.92f, ContainerTransformMath.scale(0.92f, 0f), eps)
    }

    @Test
    fun `超出范围的进度被夹住`() {
        assertEquals(0.43f, ContainerTransformMath.scale(0.43f, -0.5f), eps)
        assertEquals(1f, ContainerTransformMath.scale(0.43f, 1.7f), eps)
        assertEquals(969f, ContainerTransformMath.offset(969f, -0.5f), eps)
        assertEquals(0f, ContainerTransformMath.offset(969f, 2f), eps)
    }
}
