package com.screentrace.app.data.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperCropTest {

    private fun geo(iw: Float, ih: Float, sw: Float, sh: Float) =
        WallpaperCropMath.geometry(iw, ih, sw, sh)!!

    @Test
    fun `同比例图片恰好盖满 minZoom 为 1`() {
        val g = geo(1080f, 2400f, 1080f, 2400f)
        assertEquals(1f, g.fitScale, 1e-4f)
        assertEquals(1f, g.minZoom, 1e-4f)
    }

    @Test
    fun `宽图在竖屏上 minZoom 等于盖满所需倍数`() {
        // 1920x1080 图在 1080x2400 屏：fit = 0.5625，显示尺寸 1080×607.5，
        // 盖满高度需 zoom = 2400 / 607.5 ≈ 3.9526
        val g = geo(1920f, 1080f, 1080f, 2400f)
        assertEquals(2400f / 607.5f, g.minZoom, 1e-3f)
        assertTrue(g.minZoom > 1f)
    }

    @Test
    fun `无效尺寸返回 null`() {
        assertNull(WallpaperCropMath.geometry(0f, 100f, 100f, 100f))
        assertNull(WallpaperCropMath.geometry(100f, 100f, 0f, 100f))
        assertNull(WallpaperCropMath.geometry(100f, 100f, 100f, 0f))
    }

    @Test
    fun `zoom 夹取上下限`() {
        val wide = geo(1920f, 1080f, 1080f, 2400f)
        // 宽图 minZoom ≈ 3.95：低于下限的请求被抬到「恰好盖满」
        assertEquals(wide.minZoom, WallpaperCropMath.clampedZoom(0.2f, wide), 1e-4f)
        assertEquals(wide.minZoom, WallpaperCropMath.clampedZoom(2f, wide), 1e-4f)
        assertEquals(4f, WallpaperCropMath.clampedZoom(9f, wide), 1e-4f)
        // 同比例图 minZoom = 1：合法区间内原样返回
        val same = geo(1080f, 2400f, 1080f, 2400f)
        assertEquals(2f, WallpaperCropMath.clampedZoom(2f, same), 1e-4f)
    }

    @Test
    fun `平移钳制在图片范围内`() {
        val g = geo(1080f, 2400f, 1080f, 2400f)
        // zoom = 1（恰好盖满）：不允许平移
        val (tx, ty) = WallpaperCropMath.clampedTranslation(500f, -800f, 1f, g, 1080f, 2400f)
        assertEquals(0f, tx, 1e-4f)
        assertEquals(0f, ty, 1e-4f)
        // zoom = 2：dW = 2160 → |tx| ≤ 540；dH = 4800 → |ty| ≤ 1200
        val (tx2, ty2) = WallpaperCropMath.clampedTranslation(900f, -2000f, 2f, g, 1080f, 2400f)
        assertEquals(540f, tx2, 1e-4f)
        assertEquals(-1200f, ty2, 1e-4f)
    }

    @Test
    fun `围绕焦点缩放保持焦点图像点不动`() {
        // screen(p) = center + t + (p − imgCenter)·z；对「当前在焦点下的那个 p」
        // 要求 screen'(p) = screen(p)，解得 t' = f − (f − t)·(z'/z)。
        // f=(200,300)（相对屏幕中心）、t=(100,-50)、z=2→3：
        // t'x = 200 − 100·1.5 = 50；t'y = 300 − 350·1.5 = −225
        val (tx, ty) = WallpaperCropMath.focusedTranslation(100f, -50f, 2f, 3f, 200f, 300f)
        assertEquals(50f, tx, 1e-3f)
        assertEquals(-225f, ty, 1e-3f)
    }

    @Test
    fun `配置夹取与 NaN 兜底`() {
        assertEquals(1f, WallpaperTuning.clampCropScale(0.2f), 1e-4f)
        assertEquals(4f, WallpaperTuning.clampCropScale(9f), 1e-4f)
        assertEquals(1f, WallpaperTuning.clampCropScale(Float.NaN), 1e-4f)
        assertEquals(-1f, WallpaperTuning.clampCropOffset(-3f), 1e-4f)
        assertEquals(0f, WallpaperTuning.clampCropOffset(Float.NaN), 1e-4f)
    }

    @Test
    fun `半透明卡片时遮罩加深且未启用时不变`() {
        val base = WallpaperTuning.themeScrimAlpha(128, darkTheme = false)
        val boosted = WallpaperTuning.themeScrimAlpha(128, darkTheme = false, cardTranslucent = true)
        assertTrue(boosted > base)
        val baseD = WallpaperTuning.themeScrimAlpha(128, darkTheme = true)
        val boostD = WallpaperTuning.themeScrimAlpha(128, darkTheme = true, cardTranslucent = true)
        assertTrue(boostD > baseD)
        // CR-20 ②：卡片透明度大幅下调（CR-19 ② 的 92%/88% 几乎不透，壁纸被挡完）
        assertEquals(0.60f, WallpaperTuning.cardAlpha(false), 1e-4f)
        assertEquals(0.55f, WallpaperTuning.cardAlpha(true), 1e-4f)
    }
}
