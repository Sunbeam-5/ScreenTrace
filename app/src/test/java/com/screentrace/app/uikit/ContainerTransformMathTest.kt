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
    fun `前半段不画内容——只看到底板在放大`() {
        // 用户要的系统启动动画：先把图标放大到接近一半，再显示真实界面
        assertEquals(0f, ContainerTransformMath.contentAlpha(0f), eps)
        assertEquals(0f, ContainerTransformMath.contentAlpha(0.2f), eps)
        assertEquals(0f, ContainerTransformMath.contentAlpha(CONTENT_FADE_START), eps)
    }

    @Test
    fun `后半段内容淡入并铺满到终点`() {
        val mid = ContainerTransformMath.contentAlpha(
            (CONTENT_FADE_START + CONTENT_FADE_END) / 2f
        )
        assertTrue("中点应该半透明：$mid", mid > 0.3f && mid < 0.7f)
        assertEquals(1f, ContainerTransformMath.contentAlpha(CONTENT_FADE_END), eps)
        assertEquals(1f, ContainerTransformMath.contentAlpha(1f), eps)
    }

    @Test
    fun `内容透明度单调不减`() {
        var prev = -1f
        for (i in 0..100) {
            val a = ContainerTransformMath.contentAlpha(i / 100f)
            assertTrue("i=$i 处出现回退：$prev -> $a", a >= prev - eps)
            prev = a
        }
        assertEquals(1f, prev, eps)
    }

    @Test
    fun `超出范围的进度被夹住`() {
        assertEquals(0.43f, ContainerTransformMath.scale(0.43f, -0.5f), eps)
        assertEquals(1f, ContainerTransformMath.scale(0.43f, 1.7f), eps)
        assertEquals(969f, ContainerTransformMath.offset(969f, -0.5f), eps)
        assertEquals(0f, ContainerTransformMath.offset(969f, 2f), eps)
    }

    // ---- CR-09 验收 42：退出两段式（快出缓收） ----

    @Test
    fun `退出重映射两端归位且单调不减`() {
        assertEquals(0f, ContainerTransformMath.exitProgress(0f), eps)
        assertEquals(1f, ContainerTransformMath.exitProgress(1f), eps)
        var prev = -1f
        for (i in 0..100) {
            val p = ContainerTransformMath.exitProgress(i / 100f)
            assertTrue("i=$i 处退出重映射出现回退：$prev -> $p", p >= prev - eps)
            prev = p
        }
    }

    @Test
    fun `退出前半程比线性更快——页面先缩到约半屏`() {
        // 线性时 progress=0.5 → mapped=0.5；重映射后 0.5^1.6 ≈ 0.33
        // mapped 更小 = 页面更接近入口矩形（缩得更多），即「先缩到约半屏再缓缓收」
        val half = ContainerTransformMath.exitProgress(0.5f)
        assertTrue("0.5 处应小于线性：$half", half < 0.5f && half > 0.2f)
        // progress=0.9（退出刚开始 10%）时应已明显缩小
        val early = ContainerTransformMath.exitProgress(0.9f)
        assertTrue("退出初期应快于线性：$early", early < 0.9f && early > 0.7f)
    }
}
