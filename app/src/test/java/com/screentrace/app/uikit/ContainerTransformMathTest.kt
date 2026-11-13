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
        // 极端入口比例（0.0008）会被抬到 0.01
        assertEquals(0.01f, ContainerTransformMath.scale(0.0008f, 0f), eps)
        // 顶部 40dp 圆按钮在屏高上的占比约 0.044，必须原样保留（旧下限 0.05 会把它撑高 11%）
        assertEquals(0.044f, ContainerTransformMath.scale(0.044f, 0f), eps)
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

    // ---- 底板 / 图标层 ----

    @Test
    fun `底板两端透明——起止都露出入口控件本身，不再闪一帧`() {
        assertEquals(0f, ContainerTransformMath.plateAlpha(0f), eps)
        assertEquals(1f, ContainerTransformMath.plateAlpha(PLATE_FADE_END), eps)
        assertEquals(1f, ContainerTransformMath.plateAlpha(1f), eps)
        var prev = -1f
        for (i in 0..100) {
            val a = ContainerTransformMath.plateAlpha(i / 100f)
            assertTrue("i=$i 处底板透明度回退：$prev -> $a", a >= prev - eps)
            prev = a
        }
    }

    @Test
    fun `图标起步完全可见 在约一半处消失 且早于内容完全浮现`() {
        assertEquals(1f, ContainerTransformMath.iconAlpha(0f), eps)
        assertEquals(1f, ContainerTransformMath.iconAlpha(ICON_FADE_START), eps)
        assertEquals(0f, ContainerTransformMath.iconAlpha(ICON_FADE_END), eps)
        assertEquals(0f, ContainerTransformMath.iconAlpha(1f), eps)
        assertTrue(ICON_FADE_END <= CONTENT_FADE_END)
        // 图标与内容有交叠窗口：交叠期间两者都部分可见，才是「变成」而不是「先灭后亮」
        assertTrue(ICON_FADE_END > CONTENT_FADE_START)
        var prev = 2f
        for (i in 0..100) {
            val a = ContainerTransformMath.iconAlpha(i / 100f)
            assertTrue("i=$i 处图标透明度回升：$prev -> $a", a <= prev + eps)
            prev = a
        }
    }

    @Test
    fun `退出时长不短于进入`() {
        assertTrue(ContainerTransformMath.EXIT_DURATION_MS >= ContainerTransformMath.ENTER_DURATION_MS)
    }

    // ---- 等比窗口 + 独立裁剪框 ----

    @Test
    fun `页面等比缩放起点盖住入口矩形 终点为 1`() {
        // 40×40 的图标在 412×892 屏上：宽向占比 0.097 > 高向 0.045，取大者
        val s0 = ContainerTransformMath.coverScale(40f, 40f, 412f, 892f, 0f)
        assertEquals(40f / 412f, s0, eps)
        assertEquals(1f, ContainerTransformMath.coverScale(40f, 40f, 412f, 892f, 1f), eps)
    }

    @Test
    fun `任一进度下页面都盖得住裁剪框`() {
        val w = 412f; val h = 892f; val ow = 40f; val oh = 40f
        for (i in 0..100) {
            val p = i / 100f
            val s = ContainerTransformMath.coverScale(ow, oh, w, h, p)
            val rectW = ow + (w - ow) * p
            val rectH = oh + (h - oh) * p
            assertTrue("p=$p 宽向露缝", s * w >= rectW - 1e-3f)
            assertTrue("p=$p 高向露缝", s * h >= rectH - 1e-3f)
        }
    }

    @Test
    fun `圆角大半程保持入口圆角 终点归零`() {
        assertEquals(55f, ContainerTransformMath.cornerRadius(55f, 0f), eps)
        assertEquals(55f, ContainerTransformMath.cornerRadius(55f, CORNER_HOLD), eps)
        assertEquals(0f, ContainerTransformMath.cornerRadius(55f, 1f), eps)
    }
}
