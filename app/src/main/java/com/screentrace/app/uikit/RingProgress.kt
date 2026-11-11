package com.screentrace.app.uikit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.screentrace.app.contract.UsageCalculator
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme

/**
 * §5.7 hero 圆环：9dp 细线、圆头（§7-7），track/primary 语义 token（§5.4）。
 * 中心固定 `H:MM` 大数字；[caption] 与 [note] 可选。
 *
 * `H:MM` 本身已经自解释（`9:42` 不会有人读成 9.42 分钟），再在下面写一行
 * 「小时 : 分钟」属于重复标注、纯占高度——真机上看就是圆环里塞了三行字。
 * 所以 caption 默认 null，由调用方决定要不要（当前所有页面都不需要）。
 */
@Composable
fun RingProgress(
    progress: Float,
    timeText: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    note: String? = null
) {
    val palette = LocalFreshPalette.current
    Box(
        modifier = modifier.size(184.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val strokeWidth = 9.dp.toPx()
            val half = strokeWidth / 2f
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            val arcTopLeft = Offset(half, half)
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            drawArc(
                color = palette.track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = stroke
            )
            drawArc(
                color = palette.primary,
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
                style = FreshType.heroNumber,
                color = palette.onSurface
            )
            if (caption != null) {
                Text(text = caption, style = FreshType.label, color = palette.onSurfaceVariant)
            }
            if (note != null) {
                Spacer(Modifier.height(4.dp))
                Text(text = note, style = FreshType.label, color = palette.onSurfaceVariant)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RingProgressPreview() {
    ScreenTimeTheme {
        RingProgress(
            progress = 0.76f,
            timeText = UsageCalculator.formatClock(272),
            note = "较昨日增加 12 分钟"
        )
    }
}
