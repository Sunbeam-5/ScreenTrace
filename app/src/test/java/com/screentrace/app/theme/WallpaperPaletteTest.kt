package com.screentrace.app.theme

import androidx.compose.ui.graphics.Color
import com.screentrace.app.data.wallpaper.WallpaperColorScope
import com.screentrace.app.data.wallpaper.WallpaperTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5.4 CR-20 ②：壁纸模式下的色板派生。
 *
 * 两条红线：
 * 1. **卡片底色按壁纸主色派生**（不是原主题色），且不透明度大幅下调；
 * 2. **半透明卡片上的正文对比度仍 ≥4.5:1**——按「合成后的实际底色」算，不是按不透明卡片算。
 */
class WallpaperPaletteTest {

    private val lightTheme = ThemeVariant.MINT.palette(false)
    private val darkTheme = ThemeVariant.MINT.palette(true)

    private val seeds = listOf(
        Color(0xFF3060C0.toInt()), // 蓝
        Color(0xFFFF7A30.toInt()), // 橙
        Color(0xFF1E8E5A.toInt()), // 绿
        Color(0xFF8A8A8A.toInt()), // 灰
        Color(0xFFF2F2F2.toInt()), // 近白
        Color(0xFF141414.toInt())  // 近黑
    )

    private fun build(
        scope: WallpaperColorScope,
        dark: Boolean,
        seed: Color,
        brightness: Int,
        dimAlpha: Float = 0.3f
    ): Pair<FreshPalette, Color> {
        val theme = if (dark) darkTheme else lightTheme
        val wallpaperPalette = PaletteEngine.paletteFromSeed(seed, dark)
        val scrim = WallpaperTuning.themeScrimAlpha(brightness, dark, cardTranslucent = true)
        val palette = WallpaperPalette.effectivePalette(
            scope = scope,
            themePalette = theme,
            wallpaperPalette = wallpaperPalette,
            dark = dark,
            wallpaperSeed = seed,
            dimAlpha = dimAlpha,
            themeScrimAlpha = scrim
        )
        val background = WallpaperPalette.backgroundColor(seed, dimAlpha, scrim, theme.background)
        return palette to background
    }

    @Test
    fun `仅卡片方案 卡片底色按壁纸主色派生且大幅降不透明度`() {
        val seed = Color(0xFFFF7A30.toInt())
        val (palette, _) = build(WallpaperColorScope.CARDS_ONLY, dark = false, seed = seed, brightness = 128)
        assertEquals(WallpaperTuning.cardAlpha(false), palette.surface.alpha, 1e-4f)
        assertEquals(WallpaperTuning.cardAlpha(false), palette.surfaceVariant.alpha, 1e-4f)
        assertEquals(WallpaperTuning.cardAlpha(false), palette.primaryContainer.alpha, 1e-4f)
        // 卡片底色应带壁纸的橙味（红分量明显高于蓝）
        assertTrue(
            "卡片底色应跟随壁纸色相",
            palette.surfaceVariant.red > palette.surfaceVariant.blue
        )
    }

    @Test
    fun `仅卡片方案 强调色与图表色仍取当前主题`() {
        val seed = Color(0xFFFF7A30.toInt())
        val (palette, _) = build(WallpaperColorScope.CARDS_ONLY, dark = false, seed = seed, brightness = 128)
        assertEquals(lightTheme.primary, palette.primary)
        assertEquals(lightTheme.chart1, palette.chart1)
    }

    @Test
    fun `整套主题方案 主色与图表色也按壁纸主色派生`() {
        val seed = Color(0xFFFF7A30.toInt())
        val (palette, _) = build(WallpaperColorScope.FULL_THEME, dark = false, seed = seed, brightness = 128)
        assertTrue("主色不应等于主题主色", palette.primary != lightTheme.primary)
        assertTrue("图表色不应等于主题图表色", palette.chart1 != lightTheme.chart1)
        // 但卡片底色仍带壁纸色相
        assertTrue(palette.surfaceVariant.red > palette.surfaceVariant.blue)
    }

    @Test
    fun `两套方案的卡片底色一致 差别在强调色`() {
        val seed = Color(0xFF3060C0.toInt())
        val (cardsOnly, _) = build(WallpaperColorScope.CARDS_ONLY, dark = false, seed = seed, brightness = 128)
        val (full, _) = build(WallpaperColorScope.FULL_THEME, dark = false, seed = seed, brightness = 128)
        assertEquals(cardsOnly.surface, full.surface)
        assertEquals(cardsOnly.surfaceVariant, full.surfaceVariant)
        assertTrue(cardsOnly.primary != full.primary)
    }

    @Test
    fun `浅色主题下 合成后的卡片底色与正文对比度不低于 4 比 5`() {
        for (seed in seeds) {
            for (brightness in listOf(20, 128, 240)) {
                val (palette, background) = build(
                    WallpaperColorScope.CARDS_ONLY, dark = false, seed = seed, brightness = brightness
                )
                val effective = WallpaperPalette.composite(
                    palette.surface, WallpaperTuning.cardAlpha(false), background
                )
                val ratio = contrastRatio(effective, palette.onSurface)
                assertTrue(
                    "种子 $seed 亮度 $brightness 下卡片正文对比度只有 $ratio",
                    ratio >= WallpaperPalette.TEXT_CONTRAST_TARGET
                )
            }
        }
    }

    @Test
    fun `深色主题下 合成后的卡片底色与正文对比度不低于 4 比 5`() {
        for (seed in seeds) {
            for (brightness in listOf(20, 128, 240)) {
                val (palette, background) = build(
                    WallpaperColorScope.CARDS_ONLY, dark = true, seed = seed, brightness = brightness
                )
                val effective = WallpaperPalette.composite(
                    palette.surface, WallpaperTuning.cardAlpha(true), background
                )
                val ratio = contrastRatio(effective, palette.onSurface)
                assertTrue(
                    "种子 $seed 亮度 $brightness 下卡片正文对比度只有 $ratio",
                    ratio >= WallpaperPalette.TEXT_CONTRAST_TARGET
                )
            }
        }
    }

    @Test
    fun `safeCardColor 在对比度不足时把底色推到安全端`() {
        // 深色卡片 + 深色正文：合成后几乎同色，必须被推到浅色端才够 4.5:1
        val card = Color(0xFF101010.toInt())
        val background = Color(0xFF808080.toInt())
        val text = Color(0xFF1D322B.toInt())
        val safe = WallpaperPalette.safeCardColor(card, 0.6f, background, text, dark = false)
        assertTrue("底色应被推亮", safe.red > card.red)
        val ratio = contrastRatio(WallpaperPalette.composite(safe, 0.6f, background), text)
        assertTrue("推完之后对比度仍不达标：$ratio", ratio >= WallpaperPalette.TEXT_CONTRAST_TARGET)
    }

    @Test
    fun `composite 按不透明度把前景叠到背景上`() {
        val fg = Color(0xFFFFFFFF.toInt())
        val bg = Color(0xFF000000.toInt())
        assertEquals(1f, WallpaperPalette.composite(fg, 1f, bg).red, 1e-4f)
        assertEquals(0f, WallpaperPalette.composite(fg, 0f, bg).red, 1e-4f)
        // Compose 的 Color 每通道按 8 bit 存（0.5 → 128/255 ≈ 0.50196），容差放宽到 1/255
        assertEquals(0.5f, WallpaperPalette.composite(fg, 0.5f, bg).red, 1f / 255f)
    }

    @Test
    fun `未启用壁纸时主题色板一行不改`() {
        val result = WallpaperPalette.paletteFor(
            themePalette = lightTheme,
            wallpaperPalette = null,
            scope = WallpaperColorScope.FULL_THEME,
            dark = false,
            wallpaperSeed = Color(0xFFFF7A30.toInt()),
            dimAlpha = 0.3f,
            themeScrimAlpha = 0.4f
        )
        assertSame("没装壁纸时必须原样返回主题色板", lightTheme, result)
        assertEquals(1f, result.surface.alpha, 1e-4f)
    }

    @Test
    fun `浮层底色保持不透明 页内卡片仍半透明`() {
        val (palette, _) = build(
            WallpaperColorScope.CARDS_ONLY, dark = false, seed = Color(0xFFFF7A30.toInt()), brightness = 128
        )
        assertEquals("浮层底色必须不透明", 1f, palette.panelSurface.alpha, 1e-4f)
        assertEquals("页内卡片仍按壁纸半透明", WallpaperTuning.cardAlpha(false), palette.surface.alpha, 1e-4f)
    }

    @Test
    fun `次要正文在半透明卡片上仍不低于 4 比 5`() {        for (seed in seeds) {
            for (brightness in listOf(20, 128, 240)) {
                val (palette, background) = build(
                    WallpaperColorScope.CARDS_ONLY, dark = false, seed = seed, brightness = brightness
                )
                val effective = WallpaperPalette.composite(
                    palette.surface, WallpaperTuning.cardAlpha(false), background
                )
                val ratio = contrastRatio(effective, palette.onSurfaceVariant)
                assertTrue(
                    "种子 $seed 亮度 $brightness 下次要正文对比度只有 $ratio",
                    ratio >= WallpaperPalette.TEXT_CONTRAST_TARGET
                )
            }
        }
    }
}
