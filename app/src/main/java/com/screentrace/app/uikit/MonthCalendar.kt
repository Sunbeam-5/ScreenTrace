package com.screentrace.app.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import com.screentrace.app.contract.UsageCalculator
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme
import com.screentrace.app.theme.contrastRatio
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * CR-07 §4.8 / 需求 §5.7 月视图：周一起始、独立方块日期格（38dp / 圆角 13 / 间距 4，
 * **不再合并胶囊**——旧「连续日期合并胶囊」条款已按 CR-07 定稿废止），
 * 7 列在卡片内水平居中（§7-24）。
 *
 * 着色档位由 [MonthLevelMath] 按「当天使用 / 每日目标」比值切分（≤100%/≤140%/
 * ≤175%/>175% 四档，复用 heat1~4 色阶，第 4 档日号白字）；**目标必须读用户实际
 * 设置**（[goalMinutes] 由页面传入 `dailyGoalMin`），不得用设计稿示例值。
 * 真实 0 分钟与无记录均不着色；「今天」白底 + 主色 2dp 外环（§5.7 第 2 条）。
 *
 * 宽度自适应：360dp 屏上 7×38+6×4=290dp 会超出卡片内容宽（§5.9 无溢出红线），
 * 与年视图 [HeatmapCalendar] 同一策略——按可用宽度等比缩小格子，上限仍是 38dp。
 */
@Composable
fun MonthCalendar(
    yearMonth: YearMonth,
    levels: List<CalendarLevel>,
    goalMinutes: Int,
    modifier: Modifier = Modifier,
    cellSize: Dp = 38.dp,
    gap: Dp = 4.dp,
    today: LocalDate = LocalDate.now(),
    onDayClick: (CalendarLevel) -> Unit = {}
) {
    val palette = LocalFreshPalette.current
    val byDate = levels.associateBy { it.date }
    val firstOfMonth = yearMonth.atDay(1)
    val gridStart = firstOfMonth.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val leading = ChronoUnit.DAYS.between(gridStart, firstOfMonth).toInt()
    val totalCells = ((leading + yearMonth.lengthOfMonth() + 6) / 7) * 7

    // CR-07 §4.8：档位在 UI 层按比值重算（contract 的 level 仍是旧三档口径，不动数据层）
    fun levelOf(cell: CalendarLevel?): Int =
        MonthLevelMath.levelOf(cell?.minutes, cell?.recorded == true, goalMinutes)

    val cellColor: (Int) -> Color = { level ->
        when (level) {
            1 -> palette.heat1
            2 -> palette.heat2
            3 -> palette.heat3
            4 -> palette.heat4
            else -> Color.Transparent
        }
    }
    val textColorOn: (Color) -> Color = { fill ->
        // §5.4：颜色不唯一区分 + 色档上文字取对比更优的语义 token（第 4 档深底白字由
        // 对比函数自然得出，不必特判）
        if (contrastRatio(palette.onSurface, fill) >= contrastRatio(palette.onPrimary, fill)) {
            palette.onSurface
        } else {
            palette.onPrimary
        }
    }

    // §7-24：网格必须水平居中；§5.9：360dp 下不溢出 → 格子按可用宽度收缩
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        val derived = (maxWidth - gap * 6) / 7
        val effectiveCell = minOf(cellSize, derived).coerceAtLeast(MIN_MONTH_CELL)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(gap),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            (0 until totalCells step 7).forEach { weekStart ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    (0 until 7).forEach { i ->
                        val date = gridStart.plusDays((weekStart + i).toLong())
                        val cell = byDate[date]
                        val inMonth = date.month == yearMonth.month && date.year == yearMonth.year
                        val isToday = date == today
                        val level = if (inMonth) levelOf(cell) else 0
                        val fill = cellColor(level)
                        val dayNumber = date.dayOfMonth.toString()
                        val desc = if (inMonth && cell != null) {
                            monthCellDescription(cell)
                        } else {
                            null // 补位日不可点、不播报（§4.8：补位日只是布局补齐）
                        }
                        Box(
                            modifier = Modifier
                                .size(effectiveCell)
                                .then(
                                    if (isToday) {
                                        // §5.7 第 2 条：白底 + 2dp 间隔 + 主色 2dp 外环；
                                        // border 画在 38dp 边界内侧，白底由 surface 提供
                                        Modifier
                                            .border(2.dp, palette.primary, RoundedCornerShape(13.dp))
                                            .clip(RoundedCornerShape(13.dp))
                                            .background(palette.surface)
                                    } else {
                                        Modifier
                                            .clip(RoundedCornerShape(13.dp))
                                            .background(fill)
                                    }
                                )
                                .then(
                                    if (desc != null) {
                                        Modifier
                                            .semantics { contentDescription = desc }
                                            .clickable { onDayClick(cell!!) }
                                    } else {
                                        Modifier
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (inMonth || cell == null) {
                                Text(
                                    text = dayNumber,
                                    style = FreshType.numberDay,
                                    color = when {
                                        !inMonth -> palette.onSurfaceVariant // 补位日灰色（§4.8）
                                        isToday -> palette.onSurface
                                        level > 0 -> textColorOn(fill)
                                        else -> palette.onSurface
                                    }
                                )
                            } else {
                                // 理论不可达（levels 覆盖整月时 inMonth 必有 cell）：
                                // 保底显示数字，颜色跟普通日
                                Text(
                                    text = dayNumber,
                                    style = FreshType.numberDay,
                                    color = palette.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 360dp 红线下的格子下限：再小格子里的 14sp 日号就挤不下时宁可换行也不裁切。 */
private val MIN_MONTH_CELL = 30.dp

/**
 * §5.9 / §6.2：真实日期 + 记录状态；0 分钟与无记录文案区分；着色档位的读屏文案
 * 直接播报**真实时长**——旧版写死「低于 2 小时」的档位文案在新比值口径下不再成立。
 */
private fun monthCellDescription(cell: CalendarLevel): String {
    val dateText = "${cell.date.year}年${cell.date.monthValue}月${cell.date.dayOfMonth}日"
    val stateText = when {
        !cell.recorded -> "无记录"
        cell.minutes == null || cell.minutes <= 0 -> "0分钟"
        else -> UsageCalculator.formatDuration(cell.minutes)
    }
    return "$dateText，$stateText"
}

/**
 * CR-07 §4.8：月视图卡内底部图例——左侧「少 [四块色阶] 多」（16dp 方块、圆角 6，
 * heat1~4），右侧「○ 今天」（主色圆环）。空档不进图例：月视图不画空档。
 */
@Composable
fun MonthCalendarLegend(modifier: Modifier = Modifier) {
    val palette = LocalFreshPalette.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "少", style = FreshType.label, color = palette.onSurfaceVariant)
        Row(
            modifier = Modifier.padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(palette.heat1, palette.heat2, palette.heat3, palette.heat4).forEach { color ->
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(color)
                )
            }
        }
        Text(text = "多", style = FreshType.label, color = palette.onSurfaceVariant)
        Spacer(modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(12.dp)
                .border(2.dp, palette.primary, CircleShape)
        )
        Text(
            text = "今天",
            style = FreshType.label,
            color = palette.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

@Composable
fun MonthCalendarHeader(
    labels: List<String> = listOf("一", "二", "三", "四", "五", "六", "日"),
    modifier: Modifier = Modifier,
    cellSize: Dp = 38.dp,
    gap: Dp = 4.dp
) {
    val palette = LocalFreshPalette.current
    // 星期表头与日期列同宽同间距，且用同一个居中 Arrangement 保证列对齐
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
    ) {
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
        val minutes = when {
            day % 9 == 4 -> null // 无记录
            day % 8 == 0 -> 0    // 真实 0
            day % 5 == 0 -> 420  // 7h：目标 4h 时 >175%
            day % 3 == 0 -> 300  // 5h：>140%
            day % 2 == 0 -> 200  // 3h20m：>100%
            else -> 120          // 2h：≤100%
        }
        CalendarLevel(date, 0, recorded = minutes != null, minutes = minutes)
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
            MonthCalendar(
                yearMonth = yearMonth,
                levels = previewMonthLevels(yearMonth),
                goalMinutes = 240,
                today = yearMonth.atDay(12),
                onDayClick = {}
            )
            MonthCalendarLegend()
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
                levels = previewMonthLevels(yearMonth).map { it.copy(level = 0, recorded = false, minutes = null) },
                goalMinutes = 240,
                today = yearMonth.atDay(12),
                onDayClick = {}
            )
            MonthCalendarLegend()
        }
    }
}
