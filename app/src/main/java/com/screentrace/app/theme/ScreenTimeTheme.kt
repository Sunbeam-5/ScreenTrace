package com.screentrace.app.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** §5.3 字体 token：组件内不得另设字号或字重。 */
object FreshType {
    private val family = FontFamily.Default
    val pageTitle = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 24.sp)
    val sectionTitle = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    val heroNumber = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        letterSpacing = (-1.6).sp // 约 -0.04em
    )
    val metricNumber = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 26.sp)
    val body = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 14.sp)
    val label = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 12.sp)
    val button = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
}

/** §5.2：卡片统一 24dp，无描边、无阴影；间距只用 4dp 基数。 */
private val FreshShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

private val FreshTypography = Typography(
    headlineMedium = FreshType.pageTitle,
    titleLarge = FreshType.sectionTitle,
    titleMedium = FreshType.metricNumber,
    bodyLarge = FreshType.body,
    bodyMedium = FreshType.body,
    labelLarge = FreshType.button,
    labelSmall = FreshType.label
)

private fun FreshPalette.toColorScheme(dark: Boolean): ColorScheme = if (dark) {
    darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        background = background,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        outline = outline,
        error = error
    )
} else {
    lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        background = background,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        outline = outline,
        error = error
    )
}

/**
 * §6 要求的主题入口：ScreenTimeTheme(variant, darkTheme, dynamicColor)。
 * 切换 variant / 深浅色只替换调色板，不重新拉数据、不改筛选、不重置滚动或导航（§5.4）。
 * dynamicColor 开启时 MaterialTheme.colorScheme 走 Android 12+ 壁纸取色，
 * LocalFreshPalette 仍由所选 variant 提供，保证自绘图表颜色不消失。
 *
 * [customSeed]（CR-04）：非空表示用户启用了自定义主题，调色板由 [PaletteEngine] 派生，
 * 预设 variant 只作为「关掉自定义后回到哪套」的记忆；派生结果已过对比度校正，
 * 因此这里不需要再兜一层。
 */
@Composable
fun ScreenTimeTheme(
    variant: ThemeVariant = ThemeVariant.MINT,
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    customSeed: androidx.compose.ui.graphics.Color? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val palette = customSeed
        ?.let { PaletteEngine.paletteFromSeed(it, darkTheme) }
        ?: variant.palette(darkTheme)
    val useDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        useDynamic && darkTheme -> dynamicDarkColorScheme(context)
        useDynamic -> dynamicLightColorScheme(context)
        else -> palette.toColorScheme(darkTheme)
    }

    CompositionLocalProvider(LocalFreshPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = FreshShapes,
            typography = FreshTypography,
            content = content
        )
    }
}
