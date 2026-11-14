package com.screentrace.app.data.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §5.4 批 E：壁纸口径纯函数回归（变异锚点见各用例注释）。 */
class WallpaperTuningTest {

    @Test
    fun `inSampleSize 保证解码后长边不超过 1920`() {
        assertEquals(1, WallpaperTuning.sampleSize(1080, 1920))
        assertEquals(1, WallpaperTuning.sampleSize(1920, 1920))
        assertEquals(2, WallpaperTuning.sampleSize(3840, 2160))
        assertEquals(4, WallpaperTuning.sampleSize(4000, 3000))
        assertEquals(8, WallpaperTuning.sampleSize(8000, 4000))
        assertEquals(1, WallpaperTuning.sampleSize(0, 0))
        assertTrue(4000 / WallpaperTuning.sampleSize(4000, 3000) <= WallpaperTuning.MAX_EDGE)
    }

    @Test
    fun `平均亮度按 Rec601 加权且空输入回落中性灰`() {
        assertEquals(0, WallpaperTuning.brightnessOf(intArrayOf(0xFF000000.toInt())))
        assertEquals(255, WallpaperTuning.brightnessOf(intArrayOf(0xFFFFFFFF.toInt())))
        assertEquals(76, WallpaperTuning.brightnessOf(intArrayOf(0xFFFF0000.toInt())))
        assertEquals(149, WallpaperTuning.brightnessOf(intArrayOf(0xFF00FF00.toInt())))
        assertEquals(29, WallpaperTuning.brightnessOf(intArrayOf(0xFF0000FF.toInt())))
        assertEquals(128, WallpaperTuning.brightnessOf(intArrayOf()))
        assertEquals(
            76,
            WallpaperTuning.brightnessOf(IntArray(4) { 0xFFFF0000.toInt() })
        )
    }

    @Test
    fun `参数夹取与暗化不透明度`() {
        assertEquals(0, WallpaperTuning.clampDim(-5))
        assertEquals(60, WallpaperTuning.clampDim(99))
        assertEquals(16, WallpaperTuning.clampBlur(50))
        assertEquals(255, WallpaperTuning.clampBrightness(999))
        assertEquals(0f, WallpaperTuning.dimAlpha(0), 0.0001f)
        assertEquals(0.3f, WallpaperTuning.dimAlpha(30), 0.0001f)
        assertEquals(0.6f, WallpaperTuning.dimAlpha(60), 0.0001f)
        assertEquals(0.6f, WallpaperTuning.dimAlpha(200), 0.0001f)
    }

    @Test
    fun `主题底色随壁纸亮度反向调节 且落在 0 15 到 0 55`() {
        val lightDarkWall = WallpaperTuning.themeScrimAlpha(20, darkTheme = false)
        val lightBrightWall = WallpaperTuning.themeScrimAlpha(240, darkTheme = false)
        val darkBrightWall = WallpaperTuning.themeScrimAlpha(240, darkTheme = true)
        val darkDarkWall = WallpaperTuning.themeScrimAlpha(20, darkTheme = true)
        assertTrue("浅色主题遇到暗壁纸要多叠白", lightDarkWall > lightBrightWall)
        assertTrue("深色主题遇到亮壁纸要多叠黑", darkBrightWall > darkDarkWall)
        assertTrue(lightDarkWall in 0.15f..0.55f)
        assertTrue(darkBrightWall in 0.15f..0.55f)
    }

    @Test
    fun `有效亮度先压暗壁纸再叠主题底色`() {
        // 浅色主题：白遮罩把暗壁纸提亮
        val light = WallpaperTuning.effectiveBrightness(60, 0f, 0.5f, darkTheme = false)
        assertEquals(157, light)
        assertTrue(light > 60)
        // 深色主题：黑遮罩把亮壁纸压暗
        val dark = WallpaperTuning.effectiveBrightness(200, 0f, 0.5f, darkTheme = true)
        assertEquals(100, dark)
        assertTrue(dark < 200)
        // 用户暗化必须真的降低有效亮度
        assertTrue(
            WallpaperTuning.effectiveBrightness(200, 0.5f, 0f, darkTheme = true) <
                WallpaperTuning.effectiveBrightness(200, 0f, 0f, darkTheme = true)
        )
    }

    @Test
    fun `状态栏图标在亮背景用深色 暗背景用浅色`() {
        assertFalse(WallpaperTuning.lightStatusIcons(127))
        assertFalse(WallpaperTuning.lightStatusIcons(128)) // 暗化后刚好落在这里，也不该用深色图标
        assertFalse(WallpaperTuning.lightStatusIcons(139))
        assertTrue(WallpaperTuning.lightStatusIcons(140))
        assertTrue(WallpaperTuning.lightStatusIcons(255))
    }

    @Test
    fun `模糊在 Android 12 以下显式归零`() {
        assertFalse(WallpaperTuning.blurSupported(30))
        assertTrue(WallpaperTuning.blurSupported(31))
        assertEquals(0f, WallpaperTuning.effectiveBlurDp(12, 30), 0.0001f)
        assertEquals(12f, WallpaperTuning.effectiveBlurDp(12, 33), 0.0001f)
        assertEquals(16f, WallpaperTuning.effectiveBlurDp(99, 33), 0.0001f) // 越界值夹到上限
    }
}
