package com.screentrace.app.theme

import androidx.compose.ui.graphics.Color
import com.screentrace.app.data.wallpaper.WallpaperColorScope
import com.screentrace.app.data.wallpaper.WallpaperTuning

/**
 * §5.4 CR-20 ②：壁纸模式下的色板派生。
 *
 * **一条派生链、两套方案**：
 * - [WallpaperColorScope.CARDS_ONLY]：卡片底色用壁纸派生的色板，强调色 / 图表色仍取当前主题；
 * - [WallpaperColorScope.FULL_THEME]：整份色板都按壁纸主色派生。
 *
 * 关键点：**半透明卡片上的文字对比度必须按「合成后的实际底色」算**，不能按不透明卡片算。
 * 这里把「壁纸 → 用户暗化 → 主题底色遮罩 → 卡片色叠加」整条链复算一遍（顺序与
 * `PageScaffold` 的绘制顺序一一对应），不足 4.5:1 时把**卡片底色**朝安全端推到位
 * （浅色 → 白、深色 → 黑），而不是让文字去迁就。
 *
 * 纯函数，不依赖 Compose 状态，JVM 单测可打靶。
 */
/**
 * CR-30 §7-65：主题色彩来源的统一语义优先级。
 *
 * 1. 壁纸「整套主题」→ [WALLPAPER_FULL_THEME]（明确由壁纸驱动，九套预设不假生效）；
 * 2. 自定义种子色 → [CUSTOM_SEED]；
 * 3. 系统动态取色（Android 12+）→ [DYNAMIC_COLOR]；
 * 4. 九套预设主题 → [PRESET]。
 */
enum class ThemeColorSource {
    WALLPAPER_FULL_THEME,
    CUSTOM_SEED,
    DYNAMIC_COLOR,
    PRESET
}

object WallpaperPalette {

    /** 正文对比度红线（§5.4）。 */
    const val TEXT_CONTRAST_TARGET = 4.5

    /** 图表与主色等图形元素的对比度红线（§5.4 / §7-65）。 */
    const val GRAPHICAL_CONTRAST_TARGET = 3.0

    /** 是否处于「壁纸整套主题」模式（九套预设此时不应假生效）。 */
    fun isWallpaperDrivenTheme(
        wallpaperEnabled: Boolean,
        scope: WallpaperColorScope
    ): Boolean = wallpaperEnabled && scope == WallpaperColorScope.FULL_THEME

    /** 统一判定当前生效的主题色彩来源（纯函数，UI 展示与色板派生共用）。 */
    fun resolveThemeSource(
        wallpaperEnabled: Boolean,
        wallpaperScope: WallpaperColorScope,
        customSeedActive: Boolean,
        dynamicColorActive: Boolean
    ): ThemeColorSource = when {
        isWallpaperDrivenTheme(wallpaperEnabled, wallpaperScope) -> ThemeColorSource.WALLPAPER_FULL_THEME
        customSeedActive -> ThemeColorSource.CUSTOM_SEED
        dynamicColorActive -> ThemeColorSource.DYNAMIC_COLOR
        else -> ThemeColorSource.PRESET
    }

    /**
     * 统一基础色板解析（CR-30 §7-65）：
     * 自定义主题与系统动态取色同走 [PaletteEngine.paletteFromSeed]，保证自绘图表与 Material3 语义一致。
     */
    fun resolveBasePalette(
        variant: ThemeVariant,
        dark: Boolean,
        customSeed: Color? = null,
        dynamicSeed: Color? = null
    ): FreshPalette = when {
        customSeed != null -> PaletteEngine.paletteFromSeed(customSeed, dark)
        dynamicSeed != null -> PaletteEngine.paletteFromSeed(dynamicSeed, dark)
        else -> variant.palette(dark)
    }

    /** 合成：前景色按 alpha 叠在背景色上，返回不透明色。 */
    fun composite(fg: Color, alpha: Float, bg: Color): Color {
        val a = alpha.coerceIn(0f, 1f)
        return Color(
            red = fg.red + (bg.red - fg.red) * (1f - a),
            green = fg.green + (bg.green - fg.green) * (1f - a),
            blue = fg.blue + (bg.blue - fg.blue) * (1f - a),
            alpha = 1f
        )
    }

    /**
     * 卡片背后的**实际背景色**：壁纸 → 用户暗化（黑）→ 主题底色遮罩。
     * 与 `PageScaffold` 的绘制顺序一一对应（暗化层是纯黑，遮罩层用主题底色）。
     */
    fun backgroundColor(
        wallpaperSeed: Color,
        dimAlpha: Float,
        themeScrimAlpha: Float,
        themeBackground: Color
    ): Color {
        val dimmed = wallpaperSeed.mix(Black, dimAlpha.coerceIn(0f, 1f))
        return dimmed.mix(themeBackground, themeScrimAlpha.coerceIn(0f, 1f))
    }

    /**
     * 卡片底色兜底：合成后与 [text] 对比度不足时，把卡片底色朝安全端推到位。
     * 浅色主题往白推（卡片更亮，深色正文更清楚），深色主题往黑推。
     */
    fun safeCardColor(
        card: Color,
        alpha: Float,
        background: Color,
        text: Color,
        dark: Boolean
    ): Color {
        val safe = if (dark) Black else White
        var candidate = card
        var guard = 0
        while (
            contrastRatio(composite(candidate, alpha, background), text) < TEXT_CONTRAST_TARGET &&
            guard < MAX_SAFE_STEPS
        ) {
            candidate = candidate.mix(safe, SAFE_STEP)
            guard++
        }
        return candidate
    }

    /**
     * 壁纸感知的色板入口。
     *
     * [wallpaperPalette] 为 null（**未启用壁纸**）时**原样返回主题色板**——
     * 「装了壁纸才换观感」这条红线在这里落地成可测的一行。
     */
    fun paletteFor(
        themePalette: FreshPalette,
        wallpaperPalette: FreshPalette?,
        scope: WallpaperColorScope,
        dark: Boolean,
        wallpaperSeed: Color,
        dimAlpha: Float,
        themeScrimAlpha: Float
    ): FreshPalette = if (wallpaperPalette == null) {
        themePalette
    } else {
        effectivePalette(
            scope = scope,
            themePalette = themePalette,
            wallpaperPalette = wallpaperPalette,
            dark = dark,
            wallpaperSeed = wallpaperSeed,
            dimAlpha = dimAlpha,
            themeScrimAlpha = themeScrimAlpha
        )
    }

    /**
     * 壁纸模式下的最终色板。
     *
     * @param themePalette 当前主题（或自定义种子）派生出的色板
     * @param wallpaperPalette 按壁纸主色派生出的色板（`paletteFromSeed`）
     */
    fun effectivePalette(
        scope: WallpaperColorScope,
        themePalette: FreshPalette,
        wallpaperPalette: FreshPalette,
        dark: Boolean,
        wallpaperSeed: Color,
        dimAlpha: Float,
        themeScrimAlpha: Float
    ): FreshPalette {
        val base = if (scope == WallpaperColorScope.FULL_THEME) wallpaperPalette else themePalette
        val alpha = WallpaperTuning.cardAlpha(dark)
        val background = backgroundColor(wallpaperSeed, dimAlpha, themeScrimAlpha, base.background)

        // 卡片底色取壁纸派生色板的 surfaceVariant：浅色下它带壁纸色相
        // （surface 是纯白、没有壁纸风格），深色下它比 surface 略亮、同样带色相。
        val tinted = wallpaperPalette.surfaceVariant
        val surface = safeCardColor(
            if (dark) tinted.mix(Black, 0.04f) else tinted.mix(White, 0.06f),
            alpha, background, base.onSurface, dark
        )
        val surfaceVariant = safeCardColor(tinted, alpha, background, base.onSurface, dark)
        val primaryContainer = safeCardColor(
            wallpaperPalette.primaryContainer, alpha, background, base.onPrimaryContainer, dark
        )
        val secondaryContainer = safeCardColor(
            wallpaperPalette.secondaryContainer, alpha, background, base.onSecondaryContainer, dark
        )

        val effSurface = composite(surface, alpha, background)
        val effPrimaryContainer = composite(primaryContainer, alpha, background)
        val effSecondaryContainer = composite(secondaryContainer, alpha, background)

        // CR-30 §7-65：极端/高饱和/深浅壁纸下，正文、主色与图表色均按合成后的实际卡片底色兜底对比度
        val safeOnSurface = PaletteEngine.ensureContrast(base.onSurface, effSurface, dark, TEXT_CONTRAST_TARGET)
        val safePrimary = PaletteEngine.ensureContrast(base.primary, effSurface, dark, GRAPHICAL_CONTRAST_TARGET)
        val safeChart1 = PaletteEngine.ensureContrast(base.chart1, effSurface, dark, GRAPHICAL_CONTRAST_TARGET)
        val safeChart2 = PaletteEngine.ensureContrast(base.chart2, effSurface, dark, GRAPHICAL_CONTRAST_TARGET)
        val safeChart3 = PaletteEngine.ensureContrast(base.chart3, effSurface, dark, GRAPHICAL_CONTRAST_TARGET)
        val safeChart4 = PaletteEngine.ensureContrast(base.chart4, effSurface, dark, GRAPHICAL_CONTRAST_TARGET)
        val safeChart5 = PaletteEngine.ensureContrast(base.chart5, effSurface, dark, GRAPHICAL_CONTRAST_TARGET)

        return base.copy(
            surface = surface.copy(alpha = alpha),
            surfaceVariant = surfaceVariant.copy(alpha = alpha),
            primaryContainer = primaryContainer.copy(alpha = alpha),
            secondaryContainer = secondaryContainer.copy(alpha = alpha),
            // CR-20 ③：浮层（弹层 / 面板 / 提示条）叠在内容之上，底色必须**不透明**，
            // 否则底下的数字会透上来与浮层文字叠字（真机复核实测）。
            panelSurface = surface.copy(alpha = 1f),
            primary = safePrimary,
            onSurface = safeOnSurface,
            chart1 = safeChart1,
            chart2 = safeChart2,
            chart3 = safeChart3,
            chart4 = safeChart4,
            chart5 = safeChart5,
            // 卡片变半透明后，次要正文与单位色要按「实际底色」重新兜底
            onSurfaceVariant = PaletteEngine.ensureContrast(base.onSurfaceVariant, effSurface, dark),
            onPrimaryContainer = PaletteEngine.ensureContrast(
                base.onPrimaryContainer, effPrimaryContainer, dark
            ),
            onSecondaryContainer = PaletteEngine.ensureContrast(
                base.onSecondaryContainer, effSecondaryContainer, dark
            ),
            onSurfaceMuted = muteForeground(safeOnSurface, effSurface, dark)
        )
    }

    /** 兜底步数上限（每次 4% 明度，50 步足够走到安全端）。 */
    private const val MAX_SAFE_STEPS = 50

    /** 单步明度推进比例。 */
    private const val SAFE_STEP = 0.04f
}
