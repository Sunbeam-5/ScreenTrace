package com.screentrace.app.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.screentrace.app.contract.UsageCalculator
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme

/**
 * CR-07 §5.2「时段分布」卡：36dp 四段色带 + 2×2 图例网格。
 *
 * 原来复用通用 [SegmentedBar]（34dp 胶囊 + 一行文字图例），信息密度不够；
 * 这里另起专用组件，四段按日均分钟数分配权重，百分比与 [PercentMath] 同源。
 * 不再修改 [SegmentedBar]：排行页与周期总结页仍在用它。
 */
@Composable
fun PeriodDistribution(
    periods: List<Int>,
    modifier: Modifier = Modifier
) {
    val palette = LocalFreshPalette.current
    val values = (0 until 4).map { periods.getOrElse(it) { 0 }.coerceAtLeast(0) }
    val labels = listOf("06–12", "12–18", "18–22", "22–06")
    val colors = listOf(palette.chart1, palette.chart2, palette.chart3, palette.chart4)
    val percents = PercentMath.distribute(values)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "时段分布", style = FreshType.sectionTitle, color = palette.onSurface)
            Text(text = "日均", style = FreshType.bodySmall, color = palette.onSurfaceVariant)
        }

        if (values.sum() <= 0) {
            EmptyState(title = "暂无时段数据")
        } else {
            // 首末段外侧 18dp、内侧 8dp；段间距 3dp
            Row(
                modifier = Modifier.fillMaxWidth().height(36.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                val lastVisible = values.indices.last { values[it] > 0 }
                values.forEachIndexed { index, minutes ->
                    if (minutes <= 0) return@forEachIndexed
                    val outer = 18.dp
                    val inner = 8.dp
                    val left = if (index == 0) outer else inner
                    val right = if (index == lastVisible) outer else inner
                    Box(
                        modifier = Modifier
                            .weight(minutes.toFloat())
                            .fillMaxHeight()
                            .clip(
                                RoundedCornerShape(
                                    topStart = left, bottomStart = left,
                                    topEnd = right, bottomEnd = right
                                )
                            )
                            .background(colors[index])
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(2) { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        repeat(2) { col ->
                            val index = row * 2 + col
                            PeriodLegendCell(
                                label = labels[index],
                                minutes = values[index],
                                percent = percents[index],
                                dotColor = colors[index],
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PeriodLegendCell(
    label: String,
    minutes: Int,
    percent: Int,
    dotColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    val palette = LocalFreshPalette.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = label, style = FreshType.bodySmall, color = palette.onSurfaceVariant)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = UsageCalculator.formatDuration(minutes),
                    style = FreshType.numberBodyLarge,
                    color = palette.onSurface,
                    maxLines = 1
                )
                Text(
                    text = "$percent%",
                    style = FreshType.label,
                    color = palette.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun PeriodDistributionPreview() {
    ScreenTimeTheme {
        Column(modifier = Modifier.padding(20.dp)) {
            PeriodDistribution(periods = listOf(84, 132, 210, 45))
        }
    }
}
