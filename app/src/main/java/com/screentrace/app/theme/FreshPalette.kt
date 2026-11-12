package com.screentrace.app.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 需求文档 §5.4 / §6：全 App 唯一允许的语义颜色来源。
 * 业务组件只能读 LocalFreshPalette.current 或 MaterialTheme.colorScheme，
 * 禁止直接写十六进制颜色、Color.White 或任何旧调色板符号。
 */
data class FreshPalette(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val navSurface: Color,
    val buttonSurface: Color,
    val track: Color,
    val chart1: Color,
    val chart2: Color,
    val chart3: Color,
    val chart4: Color,
    val chart5: Color,
    val month1: Color,
    val month2: Color,
    val month3: Color,
    val heat0: Color,
    val heat1: Color,
    val heat2: Color,
    val heat3: Color,
    val heat4: Color,
    val scrim: Color,
    val error: Color,
    val outline: Color,
    /** CR-07：页面背景渐变三段——0% 顶部、18% 中段、40% 起落回 background。 */
    val gradientTop: Color = Color(0xFFD6E7EF),
    val gradientMid: Color = Color(0xFFE9F1F5),
    /** CR-07：卡片阴影色（主色低透明度近似两层阴影；深色不用，靠色阶分层）。 */
    val shadow: Color = Color(0x142C576D),
    /** CR-06/VI-1-3：超目标信号色（珊瑚暖色，固定色相不随主题——信号不是警报，不整圈变红）。 */
    val overTarget: Color = Color(0xFFB04A28),
    /** CR-06/VI-1-3：超目标 chip 底色（配 overTarget 文字，浅色 ≥4.5:1）。 */
    val overTargetSurface: Color = Color(0xFFFBE9E1),
    /** §5.4：「数字 + 小单位」排版的单位色（白卡取值）；tonal 卡用 [mutedOn] 同规则派生。 */
    val onSurfaceMuted: Color,
    /** 派生用：是否深色调色板（决定单位色混合上限 30% / 46%，§5.4）。 */
    val darkTheme: Boolean
) {
    /**
     * §5.3/§5.4 单位色推导（2026-11-10 增补）：前景向所在卡片背景混合，
     * 浅色比例上限 30%、深色上限 46%，并以对比度 ≥4.5:1 为硬约束——
     * 混合超限即破 4.5 时降低混合比例。禁止照抄 Apple 系统灰 #8E8E93。
     */
    fun mutedOn(background: Color): Color = muteForeground(onSurface, background, darkTheme)
}

/** 九套主题的浅色锚点色值，逐行抄自需求文档 §5.4 表格，不要凭审美改。 */
enum class ThemeVariant(
    val label: String,
    val seed: Color,
    val tonalA: Color,
    val tonalB: Color,
    val navLight: Color,
    val backgroundLight: Color
) {
    MINT("薄荷青绿", Color(0xFF1B6B5A), Color(0xFFA9E7C9), Color(0xFFC6E9FA), Color(0xFFB9EDD5), Color(0xFFF4F7F3)),
    MIST("雾蓝", Color(0xFF2C576D), Color(0xFFAFD2E4), Color(0xFFCECCE6), Color(0xFFC5DCE7), Color(0xFFF6F8F9)),
    INDIGO("靛青", Color(0xFF302E6B), Color(0xFFB1B0E3), Color(0xFFE0CDE5), Color(0xFFC7C6E7), Color(0xFFF6F6F9)),
    IRIS("鸢尾紫", Color(0xFF542F6A), Color(0xFFD0B1E2), Color(0xFFE4CDDB), Color(0xFFDAC7E6), Color(0xFFF8F6F9)),
    SAKURA("樱粉", Color(0xFF6D2C4F), Color(0xFFE4AFCB), Color(0xFFE6D1CC), Color(0xFFE7C5D7), Color(0xFFF9F6F7)),
    CORAL("珊瑚橘", Color(0xFF703529), Color(0xFFE6B6AD), Color(0xFFE7E5CA), Color(0xFFE9CAC3), Color(0xFFF9F6F5)),
    AMBER("琥珀黄", Color(0xFF705829), Color(0xFFE6D3AD), Color(0xFFDBE7CA), Color(0xFFE9DCC3), Color(0xFFF9F8F5)),
    FOREST("森绿", Color(0xFF466732), Color(0xFFC3E0B3), Color(0xFFCFE3D6), Color(0xFFD3E4C8), Color(0xFFF7F8F6)),
    SLATE("石墨蓝", Color(0xFF424C57), Color(0xFFBEC9D5), Color(0xFFD7D5DD), Color(0xFFD0D6DC), Color(0xFFF7F7F8))
}

internal val White = Color(0xFFFFFFFF)
internal val DarkNeutral = Color(0xFF101613)
internal val DeepInk = Color(0xFF10201A)

/** sRGB 通道线性插值，用于从主色派生整套色阶，避免手写 9 × 2 × 25 个色值。 */
internal fun Color.mix(other: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = red + (other.red - red) * f,
        green = green + (other.green - green) * f,
        blue = blue + (other.blue - blue) * f,
        alpha = 1f
    )
}

/** WCAG 相对亮度（sRGB 通道线性化）。 */
fun relativeLuminance(color: Color): Double {
    fun channel(c: Float): Double {
        val x = c.toDouble()
        return if (x <= 0.04045) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
}

/** WCAG 对比度（1.0–21.0）。 */
fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/** §5.3/§5.4 单位色：向背景混合（浅 ≤30% / 深 ≤46%），对比度 ≥4.5:1 优先于比例上限。 */
internal fun muteForeground(fg: Color, bg: Color, dark: Boolean): Color {
    val cap = if (dark) 0.46f else 0.30f
    var fraction = cap
    while (fraction > 0f) {
        val candidate = fg.mix(bg, fraction)
        if (contrastRatio(candidate, bg) >= 4.5) return candidate
        fraction -= 0.02f
    }
    return fg
}

/**
 * 每套主题同时提供浅色 / 深色调色板（§5.4、§7-10）。
 * 注意：这是骨架期的派生实现，深色不是简单反色（背景中性深、卡片略亮、主色提亮、
 * tonal 容器压暗并换亮字）；交付前仍需逐套校验正文 ≥4.5:1、大号文字 ≥3:1 并微调。
 *
 * 批 10：派生逻辑抽成 [derivePalette]，预设主题与自定义主题（[PaletteEngine]）共用同一套
 * 混合比例——两边各写一份的话，「自定义主题」迟早会和九套预设的观感不一致。
 */
fun ThemeVariant.palette(darkTheme: Boolean): FreshPalette =
    derivePalette(seed, tonalA, tonalB, navLight, backgroundLight, darkTheme)

internal fun derivePalette(
    seed: Color,
    tonalA: Color,
    tonalB: Color,
    navLight: Color,
    backgroundLight: Color,
    darkTheme: Boolean
): FreshPalette {
    if (!darkTheme) {
        val onSurface = Color(0xFF1D322B)
        // 批次 1 评审 P1-2：#5F7268 在九套 surfaceVariant 上对比度 4.28–4.45 不达 4.5，
        // 加深为 #556A5F；复算由 FreshPaletteContrastTest 逐套断言，不手写。
        val onSurfaceVariant = Color(0xFF556A5F)
        return FreshPalette(
            background = backgroundLight,
            surface = White,
            surfaceVariant = seed.mix(White, 0.90f),
            onSurface = onSurface,
            onSurfaceVariant = onSurfaceVariant,
            primary = seed,
            onPrimary = White,
            primaryContainer = tonalA,
            onPrimaryContainer = seed.mix(DeepInk, 0.18f),
            secondaryContainer = tonalB,
            onSecondaryContainer = seed.mix(DeepInk, 0.28f),
            navSurface = navLight,
            buttonSurface = tonalA.mix(White, 0.35f),
            track = seed.mix(White, 0.92f),
            chart1 = seed,
            chart2 = seed.mix(White, 0.28f),
            chart3 = seed.mix(White, 0.50f),
            chart4 = seed.mix(White, 0.70f),
            chart5 = seed.mix(White, 0.86f),
            // 浅色月阶 0.82/0.66/0.50：0.42 的月3（如靓青）落入亮暗文字均不达标的
            // 死区（3.9），提亮到 0.50 后深色日号全主题 ≥4.5（测试复算）
            month1 = seed.mix(White, 0.82f),
            month2 = seed.mix(White, 0.66f),
            month3 = seed.mix(White, 0.50f),
            heat0 = seed.mix(White, 0.94f),
            heat1 = seed.mix(White, 0.78f),
            heat2 = seed.mix(White, 0.56f),
            heat3 = seed.mix(White, 0.34f),
            heat4 = seed,
            scrim = Color(0x66000000),
            error = Color(0xFFB3261E),
            outline = seed.mix(White, 0.78f),
            // CR-07：渐变 0% 段 = seed 混白 85%（雾蓝设计值 #D6E7EF 的派生规则）
            gradientTop = seed.mix(White, 0.85f),
            gradientMid = seed.mix(White, 0.93f),
            shadow = seed.copy(alpha = 0.08f),
            overTarget = Color(0xFFB04A28),
            overTargetSurface = Color(0xFFFBE9E1),
            onSurfaceMuted = muteForeground(onSurface, White, dark = false),
            darkTheme = false
        )
    }

    val background = DarkNeutral.mix(seed, 0.10f)
    val surface = background.mix(White, 0.08f)
    // 0.48 时深色 tonal 容器上对比行 2.4–4.5 不达标（靛青最差），按 §5.4「深色主色提高亮度」
    // 提亮到 0.58：最差主题（靛青）对比行 ≈4.8，全部色对 ≥4.5（FreshPaletteContrastTest 复算）
    val primary = seed.mix(White, 0.58f)
    val onSurface = Color(0xFFE9F0EC)
    return FreshPalette(
        background = background,
        surface = surface,
        surfaceVariant = background.mix(White, 0.13f),
        onSurface = onSurface,
        onSurfaceVariant = Color(0xFFAABBB1),
        primary = primary,
        onPrimary = DarkNeutral,
        primaryContainer = seed.mix(DarkNeutral, 0.42f),
        onPrimaryContainer = seed.mix(White, 0.80f),
        // 0.58 时 tonalB 派生出中间灰（对比行 primary 对其仅 2.44），按 §5.4「tonal 容器压暗」提到 0.80
        secondaryContainer = tonalB.mix(DarkNeutral, 0.80f),
        onSecondaryContainer = tonalB.mix(White, 0.82f),
        navSurface = background.mix(White, 0.16f),
        buttonSurface = background.mix(White, 0.20f),
        track = background.mix(White, 0.12f),
        chart1 = primary,
        chart2 = seed.mix(White, 0.62f),
        chart3 = seed.mix(White, 0.42f),
        chart4 = seed.mix(White, 0.26f),
        chart5 = seed.mix(White, 0.16f),
        // month1 取 0.38：≥0.211 亮度后深色日号（onPrimary）全主题 ≥4.5；
        // 0.30/0.22 对深色系 seed 会落入「亮暗文字均不达标」死区（测试复算验证）
        month1 = seed.mix(White, 0.38f),
        month2 = seed.mix(White, 0.54f),
        month3 = seed.mix(White, 0.70f),
        heat0 = background.mix(White, 0.10f),
        heat1 = seed.mix(White, 0.12f),
        heat2 = seed.mix(White, 0.28f),
        heat3 = seed.mix(White, 0.42f),
        heat4 = seed.mix(White, 0.60f),
        scrim = Color(0x99000000),
        error = Color(0xFFFFB4AB),
        outline = background.mix(White, 0.24f),
        gradientTop = seed.mix(DarkNeutral, 0.86f),
        gradientMid = seed.mix(DarkNeutral, 0.93f),
        shadow = seed.copy(alpha = 0.20f),
        overTarget = Color(0xFFE8926F),
        overTargetSurface = Color(0xFF3A2A22),
        onSurfaceMuted = muteForeground(onSurface, surface, dark = true),
        darkTheme = true
    )
}

val LocalFreshPalette = staticCompositionLocalOf { ThemeVariant.MINT.palette(false) }
