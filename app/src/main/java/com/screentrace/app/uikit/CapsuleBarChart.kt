package com.screentrace.app.uikit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.screentrace.app.contract.DailyPoint
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * §5.7 / §7-8 趋势胶囊柱图：
 * - 消费 `contract/DailyPoint`；零使用与无记录均不画实体柱，靠详情文案区分（§5.7）；
 * - 左侧刻度列独占 32dp 固定不动：0 →「向上取整后的最大整小时」均分 4 档，最小量程 1h；
 *   刻度标签按 §5.3 轴标用 `Nh`（非整小时用 `N.Nh`）；
 * - 1–7 天用 24dp 粗胶囊柱，8 天及以上用 7dp 细柱；
 * - 31–366 天仅绘图区横向滚动，不压缩、不丢点、柱体不重叠；
 * - 全部颜色走 LocalFreshPalette（chart1 / track / onSurfaceVariant）。
 */
@Composable
fun CapsuleBarChart(
    points: List<DailyPoint>,
    modifier: Modifier = Modifier,
    onPointClick: ((DailyPoint) -> Unit)? = null
) {
    val palette = LocalFreshPalette.current
    if (points.isEmpty()) {
        EmptyState(title = "暂无趋势数据", modifier = modifier)
        return
    }
    val maxMinutes = points.maxOfOrNull { it.minutes } ?: 0
    // §5.7：量程 0→向上取整的最大整小时，最小 1h，均分 4 档
    val scaleMaxMinutes = ((maxMinutes + 59) / 60).coerceAtLeast(1) * 60

    val thick = points.size <= 7
    val plotHeight = if (thick) 126.dp else 92.dp
    val barWidth = if (thick) 24.dp else 7.dp
    val slotGap = if (thick) 10.dp else 4.dp
    val labelRowHeight = 22.dp
    // 标签稀疏化：槽宽不足 28dp 时按 k 间隔取标签，避免重叠
    val labelEvery = ((28f / (barWidth + slotGap).value).toInt()).coerceAtLeast(1)

    Row(modifier = modifier.fillMaxWidth()) {
        // 固定刻度列：独占 32dp，不随绘图区滚动
        Box(
            modifier = Modifier
                .width(32.dp)
                .height(plotHeight)
        ) {
            repeat(5) { i ->
                Text(
                    text = axisLabel(scaleMaxMinutes * i / 4),
                    style = FreshType.label,
                    color = palette.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(y = plotHeight * (1f - i / 4f) - 8.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .height(plotHeight + labelRowHeight)
        ) {
            // 固定网格线（刻度位置），绘图区滚动时不动
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(plotHeight)
            ) {
                repeat(5) { i ->
                    val y = size.height * (1f - i / 4f)
                    drawLine(
                        color = palette.track,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }
            // 仅绘图区横向滚动；每个自然日一个槽位，一个不丢
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(slotGap),
                verticalAlignment = Alignment.Bottom
            ) {
                points.forEachIndexed { index, point ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .width(barWidth)
                                .height(plotHeight)
                                .then(
                                    if (onPointClick != null) {
                                        Modifier.clickable { onPointClick(point) }
                                    } else {
                                        Modifier
                                    }
                                ),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            // 零使用与无记录均不画实体柱（§5.7）
                            if (point.minutes > 0) {
                                val barHeight = plotHeight * (point.minutes.toFloat() / scaleMaxMinutes)
                                Box(
                                    modifier = Modifier
                                        .width(barWidth)
                                        .height(barHeight)
                                        .clip(CircleShape)
                                        .background(palette.chart1)
                                )
                            }
                        }
                        if (index % labelEvery == 0) {
                            Text(
                                text = point.date.format(DATE_LABEL),
                                style = FreshType.label,
                                color = palette.onSurfaceVariant
                            )
                        } else {
                            Spacer(Modifier.height(labelRowHeight))
                        }
                    }
                }
            }
        }
    }
}

private val DATE_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d")

/** §5.3 轴标：整小时 `Nh`，非整小时最多两位小数 `N.Nh`，其余 `Nmin`。 */
private fun axisLabel(minutes: Int): String = when {
    minutes == 0 -> "0"
    minutes % 60 == 0 -> "${minutes / 60}h"
    minutes % 15 == 0 -> {
        val hours = minutes / 60.0
        val text = "%.2f".format(hours).trimEnd('0').trimEnd('.')
        "${text}h"
    }
    else -> "${minutes}min"
}

private fun previewPoints(days: Int, endDay: Long = 10L): List<DailyPoint> =
    (0 until days).map { i ->
        val date = LocalDate.of(2026, 11, 1).plusDays(i.toLong())
        when {
            i % 11 == 3 -> DailyPoint(date, 0, recorded = true)      // 真实 0
            i % 11 == 7 -> DailyPoint(date, 0, recorded = false)     // 无记录
            else -> DailyPoint(date, 60 + (i * 37) % 220, recorded = true)
        }
    }

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun CapsuleBarChartThickPreview() {
    ScreenTimeTheme {
        CapsuleBarChart(
            points = previewPoints(7),
            modifier = Modifier.padding(20.dp)
        )
    }
}

@Preview(widthDp = 360, showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CapsuleBarChartThinDarkPreview() {
    ScreenTimeTheme(darkTheme = true) {
        CapsuleBarChart(
            points = previewPoints(30),
            modifier = Modifier.padding(20.dp)
        )
    }
}

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun CapsuleBarChartExtremePreview() {
    ScreenTimeTheme {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 366 天极端值：可滚动、不丢点
            CapsuleBarChart(points = previewPoints(366))
            // 全零：不画实体柱，量程最小 1h
            CapsuleBarChart(points = previewPoints(7).map { it.copy(minutes = 0) })
        }
    }
}
