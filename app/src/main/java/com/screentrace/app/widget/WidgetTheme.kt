package com.screentrace.app.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.screentrace.app.data.wallpaper.WallpaperConfig
import com.screentrace.app.data.wallpaper.WallpaperTuning
import com.screentrace.app.theme.PaletteEngine
import com.screentrace.app.theme.ThemeVariant
import com.screentrace.app.theme.WallpaperPalette

data class WidgetColors(
    val cardSurface: Int,
    val tonalCardSurface: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val primary: Int,
    val barTrack: Int,
    val guideLine: Int,
    val progressTrack: Int,
    val overTarget: Int,
    val dayBar: Int,
    val nightBar: Int,
    val darkTheme: Boolean
)

fun resolveWidgetPalette(
    variant: ThemeVariant,
    darkTheme: Boolean,
    customThemeSeed: Long? = null,
    dynamicSeedArgb: Int? = null,
    wallpaperConfig: WallpaperConfig? = null
): WidgetColors {
    val base = WallpaperPalette.resolveBasePalette(
        variant = variant,
        dark = darkTheme,
        customSeed = customThemeSeed?.let { Color(it) },
        dynamicSeed = dynamicSeedArgb?.let { Color(it) }
    )
    val effective = if (wallpaperConfig?.enabled == true) {
        val rawSeed = wallpaperConfig.seedColor.takeIf { it != 0 } ?: WallpaperTuning.DEFAULT_SEED
        val wpSeed = Color(rawSeed)
        val wpPalette = PaletteEngine.paletteFromSeed(wpSeed, darkTheme)
        val dimAlpha = WallpaperTuning.dimAlpha(wallpaperConfig.dimPercent)
        val scrimAlpha = WallpaperTuning.themeScrimAlpha(wallpaperConfig.brightness, darkTheme)
        WallpaperPalette.paletteFor(
            themePalette = base,
            wallpaperPalette = wpPalette,
            scope = wallpaperConfig.colorScope,
            dark = darkTheme,
            wallpaperSeed = wpSeed,
            dimAlpha = dimAlpha,
            themeScrimAlpha = scrimAlpha
        )
    } else {
        base
    }

    // 桌面小组件卡片底色强制不透明（alpha = 1f），避免桌面壁纸透字降低对比度
    val safeCard = effective.panelSurface.copy(alpha = 1f)
    val safeTonal = effective.surfaceVariant.copy(alpha = 1f)
    return WidgetColors(
        cardSurface = safeCard.toArgb(),
        tonalCardSurface = safeTonal.toArgb(),
        onSurface = effective.onSurface.toArgb(),
        onSurfaceVariant = effective.onSurfaceVariant.toArgb(),
        primary = effective.primary.toArgb(),
        barTrack = effective.track.toArgb(),
        guideLine = effective.outline.copy(alpha = 0.35f).toArgb(),
        progressTrack = effective.track.toArgb(),
        overTarget = effective.overTarget.toArgb(),
        dayBar = effective.primary.toArgb(),
        nightBar = effective.nightIcon.toArgb(),
        darkTheme = darkTheme
    )
}
