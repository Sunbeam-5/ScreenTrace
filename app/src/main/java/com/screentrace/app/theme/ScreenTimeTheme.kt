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
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.screentrace.app.R
import androidx.compose.ui.unit.sp

/** §5.3 字体 token：组件内不得另设字号或字重（family 分层见 CR-06）。 */
object FreshType {
    /**
     * CR-06 / VI-1-1：数字与拉丁用 Manrope（几何无衬线、tnum 等宽），中文走系统字体
     * （MIUI/HyperOS 即 MiSans，观感接近设计稿）。**不打包完整中文字体**——单字重
     * 8–10MB 会翻倍 APK 而肉眼差别极小，这是有意识的取舍（见开发与构建说明「字体」节）。
     */
    private val numberFamily = FontFamily(
        Font(R.font.manrope_regular, FontWeight.Normal),
        Font(R.font.manrope_medium, FontWeight.Medium),
        Font(R.font.manrope_semibold, FontWeight.SemiBold),
        Font(R.font.manrope_extrabold, FontWeight.Bold) // ExtraBold 注册在 700 位：数字 token 请求 700 时直接命中
    )
    private val textFamily = FontFamily.Default

    val pageTitle = TextStyle(fontFamily = textFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp)
    val sectionTitle = TextStyle(fontFamily = textFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    val heroNumber = TextStyle(
        fontFamily = numberFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        letterSpacing = (-1.6).sp, // 约 -0.04em
        fontFeatureSettings = "'tnum'" // 等宽数字：刷新时宽度不跳
    )
    val metricNumber = TextStyle(
        fontFamily = numberFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        fontFeatureSettings = "'tnum'"
    )
    val body = TextStyle(fontFamily = textFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp)
    val label = TextStyle(fontFamily = textFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp)
    val button = TextStyle(fontFamily = textFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
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
    // 评审 P2-7：派生 + 自动校正最坏上千次对比度计算，必须 remember——
    // 否则每次重组合都重跑一遍
    val palette = customSeed
        ?.let { seed -> remember(seed, darkTheme) { PaletteEngine.paletteFromSeed(seed, darkTheme) } }
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
