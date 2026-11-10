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

import androidx.compose.ui.graphics.Color

object FreshColors {
    val Ink = Color(0xFF1D322B)
    val InkSecondary = Color(0xFF7A8D83)
    val Background = Color(0xFFF4F7F3)
    val Surface = Color(0xFFFFFFFF)
    val Outline = Color(0xFFCFE2D5)

    val GreenDeep = Color(0xFF1B6B5A)
    val GreenMid = Color(0xFF3D8C76)
    val GreenSoft = Color(0xFF7FBBA2)
    val GreenPale = Color(0xFFC7E5D5)
    val GreenWash = Color(0xFFE9F3EC)
    val GreenTrack = Color(0xFFEAF2EC)

    val Mint = Color(0xFFA9E7C9)
    val MintOn = Color(0xFF12483C)
    val MintButton = Color(0xFFCDEFD9)
    val NavSurface = Color(0xFFB9EDD5)

    val Blue = Color(0xFFC6E9FA)
    val BlueOn = Color(0xFF1E5473)
    val BlueDeep = Color(0xFF3E7191)

    val HeatRamp = listOf(
        GreenWash,
        Color(0xFFC4E3D1),
        GreenSoft,
        GreenMid,
        GreenDeep
    )

    val DarkInk = Color(0xFFE6F0E9)
    val DarkInkSecondary = Color(0xFF9DB3A7)
    val DarkBackground = Color(0xFF121E19)
    val DarkSurface = Color(0xFF1B2A23)
}
