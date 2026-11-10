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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RingProgress(
    progress: Float,
    timeText: String,
    caption: String,
    note: String? = null,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.size(184.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val strokeWidth = 9.dp.toPx()
            val half = strokeWidth / 2f
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            val arcTopLeft = Offset(half, half)
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            drawArc(
                color = FreshColors.GreenWash,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = stroke
            )
            drawArc(
                color = FreshColors.GreenDeep,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = stroke
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = timeText,
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1.5).sp,
                color = FreshColors.Ink
            )
            Text(text = caption, fontSize = 11.sp, color = FreshColors.InkSecondary)
            if (note != null) {
                Spacer(Modifier.height(4.dp))
                Text(text = note, fontSize = 11.sp, color = FreshColors.InkSecondary)
            }
        }
    }
}
