package com.screentrace.app.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.screentrace.app.contract.CalendarLevel
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme
import com.screentrace.app.theme.contrastRatio
import java.time.DayOfWeek
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * §5.7 / §7-9 月视图：周一起始、7 列 34dp、间距 4dp；前后月补位显示灰色日期。
 * 同一行连续有使用（level > 0）日期合并成胶囊，但**每一天保留自己的色档**
 * （month1~3 相邻色块分段，不得整组继承第一天颜色）；单日圆角 10dp，
 * 连续组外轮廓全胶囊；真实 0 分钟与无记录均不着色（recorded 区分文案）。
 *
 * 网格由组件自建（levels 仅当月数据，`UsageCalculator.monthLevels` 输出）：
 * 组内等宽分段使整行恒为 7×34 + 6×4 = 262dp，合并组不破坏列对齐。
 */
@Composable
fun MonthCalendar(
    yearMonth: YearMonth,
    levels: List<CalendarLevel>,
    modifier: Modifier = Modifier,
    cellSize: Dp = 34.dp,
    gap: Dp = 4.dp,
    onDayClick: (CalendarLevel) -> Unit = {}
) {
    val palette = LocalFreshPalette.current
    val byDate = levels.associateBy { it.date }
    val firstOfMonth = yearMonth.atDay(1)
    val gridStart = firstOfMonth.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val leading = ChronoUnit.DAYS.between(gridStart, firstOfMonth).toInt()
    val totalCells = ((leading + yearMonth.lengthOfMonth() + 6) / 7) * 7

    val monthColor: (Int) -> Color = { level ->
        when (level.coerceIn(0, 3)) {
            1 -> palette.month1
            2 -> palette.month2
            3 -> palette.month3
            else -> Color.Transparent
        }
    }
    val textColorOn: (Color) -> Color = { fill ->
        // §5.4：颜色不唯一区分 + 色档上文字取对比更优的语义 token
        if (contrastRatio(palette.onSurface, fill) >= contrastRatio(palette.onPrimary, fill)) {
            palette.onSurface
        } else {
            palette.onPrimary
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        (0 until totalCells step 7).forEach { weekStart ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                var i = 0
                while (i < 7) {
                    val date = gridStart.plusDays((weekStart + i).toLong())
                    val cell = byDate[date]
                    val inMonth = date.month == yearMonth.month && date.year == yearMonth.year
                    if (inMonth && cell != null && cell.level > 0) {
                        // 连续有使用日期 → 合并胶囊，逐日分段保留色档
                        var j = i
                        while (j < 7) {
                            val nextDate = gridStart.plusDays((weekStart + j).toLong())
                            val next = byDate[nextDate]
                            val nextInMonth =
                                nextDate.month == yearMonth.month && nextDate.year == yearMonth.year
                            if (!(nextInMonth && next != null && next.level > 0)) break
                            j++
                        }
                        val count = j - i
                        val runWidth = cellSize * count + gap * (count - 1)
                        val runCells = (i until j).map { k ->
                            byDate.getValue(gridStart.plusDays((weekStart + k).toLong()))
                        }
                        Row(
                            modifier = Modifier
                                .size(width = runWidth, height = cellSize)
                                .clip(RoundedCornerShape(50.dp)),
                            horizontalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            runCells.forEach { runCell ->
                                val fill = monthColor(runCell.level)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .background(fill)
                                        .semantics {
                                            contentDescription = monthCellDescription(runCell)
                                        }
                                        .clickable { onDayClick(runCell) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = runCell.date.dayOfMonth.toString(),
                                        style = FreshType.label,
                                        color = textColorOn(fill)
                                    )
                                }
                            }
                        }
                        i = j
                    } else {
                        val fill = Color.Transparent
                        Box(
                            modifier = Modifier
                                .size(cellSize)
                                .clip(RoundedCornerShape(10.dp))
                                .background(fill)
                                .then(
                                    if (inMonth && cell != null) {
                                        Modifier
                                            .semantics { contentDescription = monthCellDescription(cell) }
                                            .clickable { onDayClick(cell) }
                                    } else {
                                        Modifier
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (inMonth && cell != null) {
                                Text(
                                    text = date.dayOfMonth.toString(),
                                    style = FreshType.label,
                                    color = palette.onSurface
                                )
                            } else {
                                // 前后月补位灰色日期（§5.7）
                                Text(
                                    text = date.dayOfMonth.toString(),
                                    style = FreshType.label,
                                    color = palette.onSurfaceVariant
                                )
                            }
                        }
                        i++
                    }
                }
            }
        }
    }
}

/** §5.9：真实日期 + 记录状态；0 分钟与无记录文案区分（§5.7）。 */
private fun monthCellDescription(cell: CalendarLevel): String {
    val dateText = "${cell.date.year}年${cell.date.monthValue}月${cell.date.dayOfMonth}日"
    val stateText = when {
        !cell.recorded -> "无记录"
        cell.level == 0 -> "0分钟"
        cell.level == 1 -> "低于 2 小时"
        cell.level == 2 -> "2 至 4 小时"
        else -> "4 小时以上"
    }
    return "$dateText，$stateText"
}

@Composable
fun MonthCalendarHeader(
    labels: List<String> = listOf("一", "二", "三", "四", "五", "六", "日"),
    modifier: Modifier = Modifier,
    cellSize: Dp = 34.dp,
    gap: Dp = 4.dp
) {
    val palette = LocalFreshPalette.current
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(gap)) {
        labels.forEach { label ->
            Box(
                modifier = Modifier.size(width = cellSize, height = cellSize),
                contentAlignment = Alignment.Center
            ) {
                Text(text = label, style = FreshType.label, color = palette.onSurfaceVariant)
            }
        }
    }
}

private fun previewMonthLevels(yearMonth: YearMonth): List<CalendarLevel> =
    (1..yearMonth.lengthOfMonth()).map { day ->
        val date = yearMonth.atDay(day)
        when {
            day % 9 == 4 -> CalendarLevel(date, 0, recorded = false)
            day % 8 == 0 -> CalendarLevel(date, 0, recorded = true) // 真实 0
            day % 5 == 0 -> CalendarLevel(date, 3, recorded = true)
            day % 3 == 0 -> CalendarLevel(date, 2, recorded = true)
            day % 2 == 0 -> CalendarLevel(date, 1, recorded = true)
            else -> CalendarLevel(date, 0, recorded = true)
        }
    }

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun MonthCalendarPreview() {
    ScreenTimeTheme {
        val yearMonth = YearMonth.of(2026, 11)
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            MonthCalendarHeader()
            MonthCalendar(yearMonth = yearMonth, levels = previewMonthLevels(yearMonth), onDayClick = {})
        }
    }
}

@Preview(widthDp = 360, showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MonthCalendarDarkEmptyPreview() {
    ScreenTimeTheme(darkTheme = true) {
        val yearMonth = YearMonth.of(2026, 12)
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            MonthCalendarHeader()
            // 极端值：整月无数据
            MonthCalendar(
                yearMonth = yearMonth,
                levels = previewMonthLevels(yearMonth).map { it.copy(level = 0, recorded = false) },
                onDayClick = {}
            )
        }
    }
}
