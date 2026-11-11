package com.screentrace.app.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screentrace.app.contract.CalendarLevel
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme
import java.time.LocalDate

/**
 * §5.7 / §7-9 年视图热力图：20 周 × 7 天（列=周、行=星期一至日），9dp 格 / 3dp 圆角 /
 * 3dp 间距，含星期列总宽约 252dp；heat0~4 五档（空档 = 真实 0 或无记录）；
 * 逐格可点击（回调携带日期 / 记录状态 / 色档，时长详情由页面补充显示）；
 * 超窄屏网格区横向滚动，星期列固定。
 *
 * levels 需为周一对齐的连续 weeks×7 天（`UsageCalculator.yearLevels` 输出顺序：
 * 第 week 周第 day 天 = index week*7+day，day 0 = 周一）。
 */
@Composable
fun HeatmapCalendar(
    levels: List<CalendarLevel>,
    modifier: Modifier = Modifier,
    weeks: Int = 20,
    cellSize: Dp = 9.dp,
    gap: Dp = 3.dp,
    onDayClick: (CalendarLevel) -> Unit = {}
) {
    val palette = LocalFreshPalette.current
    val heatRamp = listOf(palette.heat0, palette.heat1, palette.heat2, palette.heat3, palette.heat4)
    // §7-24：年视图必须**完整落在卡片可视区内且水平居中**。
    // 原来固定 9dp 格 + 网格区 horizontalScroll：宽屏上整个 Row 是 wrap-content 靠左，
    // 窄屏上右半被滚动裁掉——真机反馈「年视图截断且偏左」就是这两件事叠在一起。
    // 现在按可用宽度反推格子尺寸（上限仍是传入的 cellSize），20 周一屏放下。
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        val labelWidth = 12.dp
        // 总宽 = 星期列 + weeks*格 + weeks*间距（星期列与网格之间 1 个 + 列间 weeks-1 个）
        val derived = (maxWidth - labelWidth - gap * weeks) / weeks
        val effectiveCell = minOf(cellSize, derived).coerceAtLeast(MIN_CELL_SIZE)
        val gridWidth = labelWidth + gap * weeks + effectiveCell * weeks
        val scrollState = rememberScrollState()
        val needsScroll = gridWidth > maxWidth
        Row(
            modifier = if (needsScroll) {
                Modifier.horizontalScroll(scrollState)
            } else {
                Modifier
            },
            horizontalArrangement = Arrangement.spacedBy(gap)
        ) {
            // 星期列固定（一/三/五/日 行标）
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                weekdayLabels.forEach { label ->
                    Box(
                        modifier = Modifier.size(width = labelWidth, height = effectiveCell),
                    contentAlignment = Alignment.Center
                ) {
                    if (label.isNotEmpty()) {
                        Text(
                            text = label,
                            // 格子高只有 9dp（窄屏还会更小），12sp 的正文标签会逐行重叠；
                            // 按格子量级缩到 8sp，保持「一/三/五/日」能分辨即可。
                            style = FreshType.label.copy(fontSize = 8.sp),
                            maxLines = 1,
                            color = palette.onSurfaceVariant
                        )
                    }
                }
            }
        }
        // 网格区：已经按可用宽度缩过格子，正常屏不需要滚动
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            repeat(weeks) { week ->
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    repeat(7) { day ->
                        val cell = levels.getOrNull(week * 7 + day)
                        if (cell != null) {
                            Box(
                                modifier = Modifier
                                    .size(effectiveCell)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(heatRamp[cell.level.coerceIn(0, 4)])
                                    .semantics {
                                        contentDescription = heatmapCellDescription(cell)
                                    }
                                    .clickable { onDayClick(cell) }
                            )
                        } else {
                            Spacer(Modifier.size(effectiveCell))
                        }
                    }
                }
            }
        }
        }
    }
}

/** 极窄屏的格子下限：再小就看不清了，此时退化为网格区横向滚动。 */
private val MIN_CELL_SIZE = 4.dp

private val weekdayLabels = listOf("一", "", "三", "", "五", "", "日")

/** §5.9：逐格播报真实日期 + 记录状态（色档换算为时长区间）。 */
private fun heatmapCellDescription(cell: CalendarLevel): String {
    val dateText = "${cell.date.year}年${cell.date.monthValue}月${cell.date.dayOfMonth}日"
    val stateText = when {
        !cell.recorded -> "无记录"
        cell.level == 0 -> "0分钟"
        cell.level == 1 -> "低于 2 小时"
        cell.level == 2 -> "2 至 4 小时"
        cell.level == 3 -> "4 至 6 小时"
        else -> "6 小时以上"
    }
    return "$dateText，$stateText"
}

/** §5.7 图例：少 → 多，五档色阶（heat0~4）。 */
@Composable
fun HeatmapLegend(modifier: Modifier = Modifier) {
    val palette = LocalFreshPalette.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = "少", style = FreshType.label, color = palette.onSurfaceVariant)
        listOf(palette.heat0, palette.heat1, palette.heat2, palette.heat3, palette.heat4).forEach { color ->
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
        }
        Text(text = "多", style = FreshType.label, color = palette.onSurfaceVariant)
    }
}

private fun previewLevels(count: Int): List<CalendarLevel> {
    val start = LocalDate.of(2026, 6, 29) // 周一
    return (0 until count).map { i ->
        val date = start.plusDays(i.toLong())
        when {
            i % 9 == 4 -> CalendarLevel(date, 0, recorded = false)
            i % 7 == 0 -> CalendarLevel(date, 4, recorded = true)
            else -> CalendarLevel(date, (i % 4), recorded = true)
        }
    }
}

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun HeatmapCalendarPreview() {
    ScreenTimeTheme {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HeatmapCalendar(levels = previewLevels(140), onDayClick = {})
            HeatmapLegend()
        }
    }
}

@Preview(widthDp = 360, showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HeatmapCalendarDarkEmptyPreview() {
    ScreenTimeTheme(darkTheme = true) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 极端值：全部空档
            HeatmapCalendar(
                levels = previewLevels(140).map { it.copy(level = 0, recorded = false) },
                onDayClick = {}
            )
            HeatmapLegend()
        }
    }
}
