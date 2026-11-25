package com.screentrace.app.theme

import androidx.compose.ui.graphics.Color
import com.screentrace.app.data.wallpaper.WallpaperColorScope
import com.screentrace.app.data.wallpaper.WallpaperTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CR-30 阶段 C（§7-65 主题与壁纸）行为测试：
 * - 「仅卡片 / 整套主题」双模式语义优先级明确（整套主题时明确由壁纸驱动）；
 * - 动态取色与自定义主题统一经 PaletteEngine 派生基础色板；
 * - 深浅色 + 高饱和 / 极端色壁纸下，正文与主色/图表色均满足对比度红线。
 */
class StageCThemeWallpaperTest {

    @Test
    fun `整套主题壁纸优先级高于自定义和动态取色 仅卡片模式不覆盖主题来源`() {
        assertTrue(WallpaperPalette.isWallpaperDrivenTheme(true, WallpaperColorScope.FULL_THEME))
        assertFalse(WallpaperPalette.isWallpaperDrivenTheme(true, WallpaperColorScope.CARDS_ONLY))
        assertFalse(WallpaperPalette.isWallpaperDrivenTheme(false, WallpaperColorScope.FULL_THEME))

        assertEquals(
            ThemeColorSource.WALLPAPER_FULL_THEME,
            WallpaperPalette.resolveThemeSource(
                wallpaperEnabled = true,
                wallpaperScope = WallpaperColorScope.FULL_THEME,
                customSeedActive = true,
                dynamicColorActive = true
            )
        )
        assertEquals(
            ThemeColorSource.CUSTOM_SEED,
            WallpaperPalette.resolveThemeSource(
                wallpaperEnabled = true,
                wallpaperScope = WallpaperColorScope.CARDS_ONLY,
                customSeedActive = true,
                dynamicColorActive = true
            )
        )
        assertEquals(
            ThemeColorSource.DYNAMIC_COLOR,
            WallpaperPalette.resolveThemeSource(
                wallpaperEnabled = false,
                wallpaperScope = WallpaperColorScope.CARDS_ONLY,
                customSeedActive = false,
                dynamicColorActive = true
            )
        )
        assertEquals(
            ThemeColorSource.PRESET,
            WallpaperPalette.resolveThemeSource(
                wallpaperEnabled = false,
                wallpaperScope = WallpaperColorScope.CARDS_ONLY,
                customSeedActive = false,
                dynamicColorActive = false
            )
        )
    }

    @Test
    fun `动态取色开启时基础色板跟随动态种子而非停留在预设主题`() {
        val dynamicSeed = Color(0xFFD84315)
        val preset = ThemeVariant.MINT.palette(darkTheme = false)
        val resolved = WallpaperPalette.resolveBasePalette(
            variant = ThemeVariant.MINT,
            dark = false,
            customSeed = null,
            dynamicSeed = dynamicSeed
        )
        val expected = PaletteEngine.paletteFromSeed(dynamicSeed, dark = false)
        assertEquals(expected, resolved)
        assertNotEquals(preset.primary, resolved.primary)
    }

    @Test
    fun `极端与高饱和壁纸在深浅色与双模式下正文和图表均达标`() {
        val extremeSeeds = listOf(
            Color(0xFFFFFFFF), // 纯白
            Color(0xFF000000), // 纯黑
            Color(0xFFFF0000), // 高饱和纯红
            Color(0xFFFFFF00), // 高饱和纯黄
            Color(0xFF00FFFF), // 高饱和霓虹青
            Color(0xFFFF00FF)  // 高饱和霓虹品红
        )
        val themeLight = ThemeVariant.MINT.palette(darkTheme = false)
        val themeDark = ThemeVariant.MINT.palette(darkTheme = true)

        for (dark in listOf(false, true)) {
            val theme = if (dark) themeDark else themeLight
            val alpha = WallpaperTuning.cardAlpha(dark)
            for (scope in WallpaperColorScope.values()) {
                for (seed in extremeSeeds) {
                    val wpPalette = PaletteEngine.paletteFromSeed(seed, dark)
                    val scrim = WallpaperTuning.themeScrimAlpha(
                        brightness = if (seed == Color.White) 250 else 30,
                        darkTheme = dark,
                        cardTranslucent = true
                    )
                    val eff = WallpaperPalette.effectivePalette(
                        scope = scope,
                        themePalette = theme,
                        wallpaperPalette = wpPalette,
                        dark = dark,
                        wallpaperSeed = seed,
                        dimAlpha = 0.25f,
                        themeScrimAlpha = scrim
                    )
                    val base = if (scope == WallpaperColorScope.FULL_THEME) wpPalette else theme
                    val bg = WallpaperPalette.backgroundColor(seed, 0.25f, scrim, base.background)
                    val effSurface = WallpaperPalette.composite(eff.surface.copy(alpha = 1f), alpha, bg)

                    assertTrue(
                        "onSurface contrast failed for seed=$seed dark=$dark scope=$scope",
                        contrastRatio(eff.onSurface, effSurface) >= WallpaperPalette.TEXT_CONTRAST_TARGET
                    )
                    assertTrue(
                        "onSurfaceVariant contrast failed for seed=$seed dark=$dark scope=$scope",
                        contrastRatio(eff.onSurfaceVariant, effSurface) >= WallpaperPalette.TEXT_CONTRAST_TARGET
                    )
                    listOf(
                        "primary" to eff.primary,
                        "chart1" to eff.chart1,
                        "chart2" to eff.chart2,
                        "chart3" to eff.chart3,
                        "chart4" to eff.chart4,
                        "chart5" to eff.chart5
                    ).forEach { (name, color) ->
                        assertTrue(
                            "$name contrast failed for seed=$seed dark=$dark scope=$scope: ${contrastRatio(color, effSurface)}",
                            contrastRatio(color, effSurface) >= WallpaperPalette.GRAPHICAL_CONTRAST_TARGET
                        )
                    }
                }
            }
        }
    }
}
