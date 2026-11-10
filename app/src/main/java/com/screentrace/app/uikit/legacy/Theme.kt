// LEGACY / 参考起点 —— 来自 compose-uikit/，尚未按需求文档 v2.3 §5.4 / §6 整改。
// 已知差距：
//  1) 硬编码 FreshColors.* / Color.White / 十六进制色，须改为 LocalFreshPalette 语义 token；
//  2) 未支持九套主题与深色模式；
//  3) CapsuleBarChart 缺固定 32dp 刻度列、动态量程、零值区分与横向滚动（§5.7 / §7-8）；
//  4) HeatmapCalendar 仍是 12dp/4dp 且无点击详情，应为 9dp/3dp（§5.7）；
//  5) MonthCalendar 未保留连续组内每日独立色档，且有 week[i+k]!! 非空断言。
// 新页面请使用 theme/ 下的语义色板，不要直接把本包组件当作完成品。
//
package com.screentrace.app.uikit.legacy

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val FreshLightScheme = lightColorScheme(
    primary = FreshColors.GreenDeep,
    onPrimary = FreshColors.Surface,
    primaryContainer = FreshColors.MintButton,
    onPrimaryContainer = FreshColors.MintOn,
    secondary = FreshColors.BlueDeep,
    secondaryContainer = FreshColors.Blue,
    onSecondaryContainer = FreshColors.BlueOn,
    background = FreshColors.Background,
    onBackground = FreshColors.Ink,
    surface = FreshColors.Surface,
    onSurface = FreshColors.Ink,
    surfaceVariant = FreshColors.GreenWash,
    onSurfaceVariant = FreshColors.InkSecondary,
    outline = FreshColors.Outline
)

private val FreshDarkScheme = darkColorScheme(
    primary = FreshColors.GreenSoft,
    onPrimary = FreshColors.DarkBackground,
    primaryContainer = FreshColors.GreenDeep,
    onPrimaryContainer = FreshColors.DarkInk,
    secondary = FreshColors.Blue,
    onSecondaryContainer = FreshColors.BlueOn,
    background = FreshColors.DarkBackground,
    onBackground = FreshColors.DarkInk,
    surface = FreshColors.DarkSurface,
    onSurface = FreshColors.DarkInk,
    surfaceVariant = Color(0xFF263A30),
    onSurfaceVariant = FreshColors.DarkInkSecondary
)

private val FreshShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

private val FreshTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        letterSpacing = (-0.6).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        letterSpacing = (-0.3).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp
    )
)

// 旧版单套主题入口；新代码请用 com.screentrace.app.theme.ScreenTimeTheme。
@Composable
fun LegacyScreenTimeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> FreshDarkScheme
        else -> FreshLightScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = FreshShapes,
        typography = FreshTypography,
        content = content
    )
}
