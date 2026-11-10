package com.screentrace.app.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 需求文档 §5.4 / §6：全 App 唯一允许的语义颜色来源。
 * 业务组件只能读 LocalFreshPalette.current 或 MaterialTheme.colorScheme，
 * 禁止直接写十六进制颜色、Color.White 或 FreshColors.*（legacy 包除外，见其文件头）。
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
    val outline: Color
)

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

private val White = Color(0xFFFFFFFF)
private val DarkNeutral = Color(0xFF101613)
private val DeepInk = Color(0xFF10201A)

/** sRGB 通道线性插值，用于从主色派生整套色阶，避免手写 9 × 2 × 25 个色值。 */
private fun Color.mix(other: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = red + (other.red - red) * f,
        green = green + (other.green - green) * f,
        blue = blue + (other.blue - blue) * f,
        alpha = 1f
    )
}

/**
 * 每套主题同时提供浅色 / 深色调色板（§5.4、§7-10）。
 * 注意：这是骨架期的派生实现，深色不是简单反色（背景中性深、卡片略亮、主色提亮、
 * tonal 容器压暗并换亮字）；交付前仍需逐套校验正文 ≥4.5:1、大号文字 ≥3:1 并微调。
 */
fun ThemeVariant.palette(darkTheme: Boolean): FreshPalette {
    if (!darkTheme) {
        val onSurface = Color(0xFF1D322B)
        val onSurfaceVariant = Color(0xFF5F7268)
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
            month1 = seed.mix(White, 0.82f),
            month2 = seed.mix(White, 0.62f),
            month3 = seed.mix(White, 0.42f),
            heat0 = seed.mix(White, 0.94f),
            heat1 = seed.mix(White, 0.78f),
            heat2 = seed.mix(White, 0.56f),
            heat3 = seed.mix(White, 0.34f),
            heat4 = seed,
            scrim = Color(0x66000000),
            error = Color(0xFFB3261E),
            outline = seed.mix(White, 0.78f)
        )
    }

    val background = DarkNeutral.mix(seed, 0.10f)
    val surface = background.mix(White, 0.08f)
    val primary = seed.mix(White, 0.48f)
    return FreshPalette(
        background = background,
        surface = surface,
        surfaceVariant = background.mix(White, 0.13f),
        onSurface = Color(0xFFE9F0EC),
        onSurfaceVariant = Color(0xFFAABBB1),
        primary = primary,
        onPrimary = DarkNeutral,
        primaryContainer = seed.mix(DarkNeutral, 0.42f),
        onPrimaryContainer = seed.mix(White, 0.80f),
        secondaryContainer = tonalB.mix(DarkNeutral, 0.58f),
        onSecondaryContainer = tonalB.mix(White, 0.82f),
        navSurface = background.mix(White, 0.16f),
        buttonSurface = background.mix(White, 0.20f),
        track = background.mix(White, 0.12f),
        chart1 = primary,
        chart2 = seed.mix(White, 0.62f),
        chart3 = seed.mix(White, 0.42f),
        chart4 = seed.mix(White, 0.26f),
        chart5 = seed.mix(White, 0.16f),
        month1 = seed.mix(White, 0.30f),
        month2 = seed.mix(White, 0.48f),
        month3 = seed.mix(White, 0.66f),
        heat0 = background.mix(White, 0.10f),
        heat1 = seed.mix(White, 0.12f),
        heat2 = seed.mix(White, 0.28f),
        heat3 = seed.mix(White, 0.42f),
        heat4 = seed.mix(White, 0.60f),
        scrim = Color(0x99000000),
        error = Color(0xFFFFB4AB),
        outline = background.mix(White, 0.24f)
    )
}

val LocalFreshPalette = staticCompositionLocalOf { ThemeVariant.MINT.palette(false) }
