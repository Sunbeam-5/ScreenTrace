package com.screentrace.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.screentrace.app.contract.CalendarLevel
import com.screentrace.app.contract.DEFAULT_LOW_THRESHOLD_MIN
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.ExtremesInfo
import com.screentrace.app.contract.PeriodType
import com.screentrace.app.contract.StreakInfo
import com.screentrace.app.contract.UsageCalculator
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme
import com.screentrace.app.uikit.EmptyState
import com.screentrace.app.uikit.ErrorRetry
import com.screentrace.app.uikit.HeatmapCalendar
import com.screentrace.app.uikit.HeatmapLegend
import com.screentrace.app.uikit.LoadingSkeleton
import com.screentrace.app.uikit.MetricCardPair
import com.screentrace.app.uikit.MetricValue
import com.screentrace.app.uikit.MonthCalendar
import com.screentrace.app.uikit.MonthCalendarHeader
import com.screentrace.app.uikit.SegmentedControl
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth

/**
 * 日历页（§5.7，顺序固定）：① 连续记录 tonal 卡（hero 大数字 +「最长 N 天 · 委于 X」）
 * → ② 月/年 SegmentedControl → ③ 日历卡（月视图可翻月、点击格子显真实日期/记录状态/
 * 时长——0 分钟与无记录文案区分；年视图 20 周热力图）→ ④ tonal 双卡
 * 「最久的一天 / 低于 2 小时」。锚点 = 区间结束日（§5.6）。
 */
@Composable
fun CalendarScreen(
    state: LoadState<CalendarData>,
    monthLevelsState: LoadState<List<CalendarLevel>>,
    calendarMonth: YearMonth,
    onMonthChange: (YearMonth) -> Unit,
    onRetry: () -> Unit,
    /** 「低于 x 小时」的 x（分钟）。由设置页配置，默认 2 小时。 */
    lowThresholdMin: Int = DEFAULT_LOW_THRESHOLD_MIN,
    /** §5.11：日历页顶部的周期总结入口（与统计页底部同一个二级页）。 */
    onOpenPeriod: (PeriodType) -> Unit = {}
) {
    // 「有数据就画数据」（批 6g，理由见 TodayScreen）：换月份/换区间不闪骨架
    when {
        state.data != null -> CalendarContent(
            data = state.data,
            monthLevelsState = monthLevelsState,
            calendarMonth = calendarMonth,
            onMonthChange = onMonthChange,
            lowThresholdMin = lowThresholdMin,
            onOpenPeriod = onOpenPeriod
        )
        state.loading -> LoadingSkeleton()
        state.error != null -> ErrorRetry(message = state.error, onRetry = onRetry)
        else -> DataEmptyState(
            reason = state.empty,
            noDataTitle = "暂无日历数据",
            noDataDescription = "完成首次拉取后，这里会按月/年展示每日使用记录"
        )
    }
}

@Composable
private fun CalendarContent(
    data: CalendarData,
    monthLevelsState: LoadState<List<CalendarLevel>>,
    calendarMonth: YearMonth,
    onMonthChange: (YearMonth) -> Unit,
    lowThresholdMin: Int,
    onOpenPeriod: (PeriodType) -> Unit
) {
    val palette = LocalFreshPalette.current
    var selectedEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedMinutes by rememberSaveable { mutableStateOf<Int?>(null) }
    var viewMode by rememberSaveable { mutableStateOf(0) }

    // ① 连续记录 tonal 卡（hero 大数字，本页唯一核心大数字；tonal 卡内边距 16dp，§5.2）
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(palette.secondaryContainer)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "${data.streak.currentDays}",
            style = FreshType.heroNumber,
            color = palette.onSecondaryContainer
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "连续记录天数",
                style = FreshType.body,
                color = palette.onSecondaryContainer
            )
            val longestText = data.streak.longestStart?.let {
                "最长 ${data.streak.longestDays} 天 · 委于 ${it.monthValue}月${it.dayOfMonth}日"
            } ?: "暂无连续记录"
            Text(
                text = longestText,
                style = FreshType.label,
                color = palette.onSecondaryContainer
            )
        }
    }

    // §5.11：日历页顶部同一入口（一行即可，日历页的主线是月/年视图，不抢戏）
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(palette.surface)
            .clickable { onOpenPeriod(PeriodType.WEEK) }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "本周总结",
            style = FreshType.body,
            color = palette.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "查看周/月/年",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
        Text(text = "›", style = FreshType.sectionTitle, color = palette.onSurfaceVariant)
    }

    // ② 月/年 segmented
    SegmentedControl(
        items = listOf("月视图", "年视图"),
        selectedIndex = viewMode,
        onSelect = { viewMode = it; selectedEpochDay = null; selectedMinutes = null }
    )

    // ③ 日历卡
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(palette.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (viewMode == 0) {
            // 翻月头
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MonthArrow(isNext = false) {
                    selectedEpochDay = null; selectedMinutes = null // 复审 P2-A：翻月清残留选中
                    onMonthChange(calendarMonth.minusMonths(1))
                }
                Text(
                    text = "${calendarMonth.year} 年 ${calendarMonth.monthValue} 月",
                    style = FreshType.sectionTitle,
                    color = palette.onSurface
                )
                MonthArrow(isNext = true) {
                    selectedEpochDay = null; selectedMinutes = null
                    onMonthChange(calendarMonth.plusMonths(1))
                }
            }
            MonthCalendarHeader()
            val levels = monthLevelsState.data.orEmpty()
            if (monthLevelsState.error != null) {
                ErrorRetry(message = monthLevelsState.error, onRetry = { onMonthChange(calendarMonth) })
            } else {
                MonthCalendar(
                    yearMonth = calendarMonth,
                    levels = levels,
                    onDayClick = {
                        selectedEpochDay = it.date.toEpochDay()
                        selectedMinutes = it.minutes
                    }
                )
            }
        } else {
            Text(text = "过去 20 周", style = FreshType.sectionTitle, color = palette.onSurface)
            HeatmapCalendar(
                levels = data.yearLevels,
                onDayClick = {
                    selectedEpochDay = it.date.toEpochDay()
                    selectedMinutes = it.minutes
                }
            )
            HeatmapLegend()
        }
        // 点击详情（§5.7：真实日期、记录状态与时长；0 分钟与无记录文案区分。
        // 返修 P1-4：时长直接来自 CalendarLevel.minutes，翻月/年视图任意格子均可显示）
        selectedEpochDay?.let { epochDay ->
            val date = LocalDate.ofEpochDay(epochDay)
            val minutes = selectedMinutes
            val stateText = when (minutes) {
                null -> "无记录"
                else -> UsageCalculator.formatDuration(minutes)
            }
            Text(
                text = "${date.year}年${date.monthValue}月${date.dayOfMonth}日 · $stateText",
                style = FreshType.body,
                color = palette.onSurface
            )
        }
    }

    // ④ tonal 双卡（最久的一天 / 低于 x 小时）
    MetricCardPair(
        leftLabel = "最久的一天",
        leftValue = MetricValue.Duration(data.extremes.longestMinutes),
        leftNote = data.extremes.longestDate?.let { "${it.monthValue}月${it.dayOfMonth}日" },
        // 标题跟着阈值走：以前写死「低于 2 小时」，用户改不了，标题也就永远是 2。
        // 整小时显示「低于 3 小时」，非整小时（如 90 分钟）退回「低于 90 分钟」。
        rightLabel = thresholdLabel(lowThresholdMin),
        rightValue = MetricValue.Number("${data.extremes.underThresholdDays}"),
        rightNote = "天（有记录日）"
    )
}

/** 「低于 x 小时」标题：整小时用小时，否则用分钟，避免出现「低于 1.5 小时」。 */
private fun thresholdLabel(minutes: Int): String =
    if (minutes % 60 == 0) "低于 ${minutes / 60} 小时" else "低于 $minutes 分钟"

@Composable
private fun MonthArrow(isNext: Boolean, onClick: () -> Unit) {
    val palette = LocalFreshPalette.current
    Box(
        modifier = Modifier
            .size(48.dp)
            .semantics {
                contentDescription = if (isNext) "下一个月" else "上一个月"
                role = Role.Button
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isNext) {
                Icons.AutoMirrored.Filled.KeyboardArrowRight
            } else {
                Icons.AutoMirrored.Filled.KeyboardArrowLeft
            },
            contentDescription = null,
            tint = palette.onSurface
        )
    }
}

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun CalendarScreenPreview() {
    ScreenTimeTheme {
        val month = YearMonth.of(2026, 11)
        val levels = (1..30).map { day ->
            val date = month.atDay(day)
            CalendarLevel(date, day % 4, recorded = day % 6 != 0)
        }
        CalendarScreen(
            state = LoadState(
                data = CalendarData(
                    streak = StreakInfo(
                        currentDays = 23,
                        longestDays = 41,
                        longestStart = LocalDate.of(2026, 3, 2)
                    ),
                    extremes = ExtremesInfo(LocalDate.of(2026, 11, 3), 320, null, 0, 4),
                    yearLevels = levels
                )
            ),
            monthLevelsState = LoadState(data = levels),
            calendarMonth = month,
            onMonthChange = {},
            onRetry = {}
        )
    }
}
