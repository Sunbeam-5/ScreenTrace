package com.screentrace.app.uikit

import androidx.compose.animation.core.TweenSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §7-49 / §7-50（CR-23）：tab 转场运动的三条纯映射。
 *
 * 真机反馈（2026-11-16）：点 tab「好僵硬」、滑动「很丝滑」。`screenrecord` + ffmpeg
 * 抽帧逐帧定位到两处：① 标题滞后于内容（日历页已到位、标题还停在「今日」）；
 * ② 页面内容是纯平移、无景深层次。`dumpsys gfxinfo` 证实 50th=8ms、真 jank 0.5%，
 * 不是掉帧、是运动设计问题。
 *
 * 这里把观感参数钉成纯函数，便于变异验证：改坏映射必须变红。
 */
class TabMotionTest {

    private val eps = 1e-4f

    // ------------------------------------------------------------ 景深透明度

    @Test
    fun `居中页完全清晰`() {
        assertEquals(1f, TabMotion.alphaFor(0f), eps)
    }

    @Test
    fun `完全滑出时最暗`() {
        assertEquals(TabMotion.DEPTH_ALPHA_AT_EDGE, TabMotion.alphaFor(1f), eps)
        assertEquals(0.45f, TabMotion.alphaFor(1f), eps)
    }

    @Test
    fun `中间位置线性过渡`() {
        assertEquals(0.725f, TabMotion.alphaFor(0.5f), eps)
    }

    @Test
    fun `偏移取绝对值 左右滑出一样暗`() {
        assertEquals(TabMotion.alphaFor(1f), TabMotion.alphaFor(-1f), eps)
        assertEquals(TabMotion.alphaFor(0.3f), TabMotion.alphaFor(-0.3f), eps)
    }

    @Test
    fun `偏移超界夹取 不会反向变亮`() {
        assertEquals(TabMotion.alphaFor(1f), TabMotion.alphaFor(3f), eps)
        assertEquals("超界一律按最暗一档", TabMotion.DEPTH_ALPHA_AT_EDGE, TabMotion.alphaFor(-9f), eps)
    }

    // ------------------------------------------------------------ 景深缩放

    @Test
    fun `居中页不缩放`() {
        assertEquals(1f, TabMotion.scaleFor(0f), eps)
    }

    @Test
    fun `完全滑出时最暗最小`() {
        assertEquals(TabMotion.DEPTH_SCALE_AT_EDGE, TabMotion.scaleFor(1f), eps)
        assertEquals(0.95f, TabMotion.scaleFor(1f), eps)
    }

    @Test
    fun `缩放只做轻微收缩 不压扁文字`() {
        assertTrue("滑出页也不能缩得太狠", TabMotion.scaleFor(1f) >= 0.9f)
    }

    // ------------------------------------------------------------ 标题位移

    @Test
    fun `落定时位移归零`() {
        assertEquals(0f, TabMotion.titleShiftDp(target = 2f, progress = 2f), eps)
        assertEquals(0f, TabMotion.titleShiftDp(target = 0f, progress = 0f), eps)
    }

    @Test
    fun `前进时标题从右侧滑入`() {
        // 目标页在右、进度还在左 → 正位移（标题偏右，随进度滑回原位）
        assertTrue(TabMotion.titleShiftDp(target = 2f, progress = 0f) > 0f)
    }

    @Test
    fun `后退时标题从左侧滑入`() {
        assertTrue(TabMotion.titleShiftDp(target = 0f, progress = 2f) < 0f)
    }

    @Test
    fun `每跨一页位移十八dp`() {
        assertEquals(18f, TabMotion.titleShiftDp(target = 1f, progress = 0f), eps)
        assertEquals(36f, TabMotion.titleShiftDp(target = 2f, progress = 0f), eps)
        assertEquals(18f, TabMotion.titleShiftDp(target = 2f, progress = 1f), eps)
    }

    @Test
    fun `跨页越多位移越大 落定都归零`() {
        assertTrue(
            TabMotion.titleShiftDp(3f, 0f) > TabMotion.titleShiftDp(1f, 0f)
        )
    }

    // ------------------------------------------------------------ 软着陆曲线

    @Test
    fun `滚动曲线是有明确落点的强调缓出 不是渐近弹簧`() {
        // CR-25：spring 的渐近尾巴（78% 行程 147ms、最后 22% 爬 630ms）就是「卡住」的来源；
        // tween 到时即进度 1.0，末端准确停住。
        val spec = TabMotion.scrollSpecFor(1)
        assertTrue("必须是 tween（有明确落点）", spec is TweenSpec<*>)
        val tween = spec as TweenSpec<*>
        assertEquals(TabMotion.EMPHASIZED_DECELERATE, tween.easing)
        assertEquals(TabMotion.SCROLL_BASE_MS, tween.durationMillis)
    }

    @Test
    fun `缓动是强调缓出 中点已走完大部分行程`() {
        // 直接打缓动函数的输出，不拿字段跟自己比（那样变异改字段也永远相等）。
        // 强调缓出 = 起步快、后段稳：t=0.5 时应已走完 >80% 行程；线性是 0.5。
        val e = TabMotion.EMPHASIZED_DECELERATE
        assertEquals("起点 0", 0f, e.transform(0f), eps)
        assertEquals("终点 1", 1f, e.transform(1f), eps)
        val mid = e.transform(0.5f)
        assertTrue("强调缓出中点应 > 0.8（线性是 0.5），实测 $mid", mid > 0.8f)
    }

    @Test
    fun `行程越远越久 但不超过 500ms`() {
        assertEquals(350, TabMotion.scrollDurationMs(1))
        assertEquals(440, TabMotion.scrollDurationMs(2))
        assertEquals(480, TabMotion.scrollDurationMs(3))
        assertEquals("超长行程封顶", 480, TabMotion.scrollDurationMs(9))
        TabMotion.scrollDurationMs(1).let { assertTrue("单页 $it 应 ≤500ms", it <= 500) }
    }

    @Test
    fun `行程为 0 或负数按一页算`() {
        assertEquals(350, TabMotion.scrollDurationMs(0))
        assertEquals(350, TabMotion.scrollDurationMs(-3))
    }
}
