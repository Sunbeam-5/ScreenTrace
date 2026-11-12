package com.screentrace.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
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
import com.screentrace.app.uikit.FreshCard
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
import com.screentrace.app.uikit.MonthCalendarLegend
import com.screentrace.app.uikit.SegmentedControl
import com.screentrace.app.uikit.rememberReduceMotion
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth

/**
 * 日历页（§5.7，顺序固定）：① 连续记录 tonal 卡（56dp 图标块 + 34sp 大数字 +
 * 「最长 N 天 · 始于 X」）→ ② 月/年 SegmentedControl → ③ 日历卡（月视图独立
 * 38dp 方块格子、可翻月、点击格子显真实日期/记录状态/时长——0 分钟与无记录文案
 * 区分；年视图 20 周热力图）→ ④ tonal 双卡「最久的一天 / 低于 x 小时」。
 * 锚点 = 区间结束日（§5.6）。
 */
@Composable
fun CalendarScreen(
    state: LoadState<CalendarData>,
    monthLevelsState: LoadState<List<CalendarLevel>>,
    calendarMonth: YearMonth,
    onMonthChange: (YearMonth) -> Unit,
    onRetry: () -> Unit,
    /** §5.8：用户实际设置的每日目标（分钟）。月历四档按「使用/目标」比值切分。 */
    goalMinutes: Int = 0,
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
            goalMinutes = goalMinutes,
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
    goalMinutes: Int,
    lowThresholdMin: Int,
    onOpenPeriod: (PeriodType) -> Unit
) {
    val palette = LocalFreshPalette.current
    val reduceMotion = rememberReduceMotion()
    var selectedEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedMinutes by rememberSaveable { mutableStateOf<Int?>(null) }
    var viewMode by rememberSaveable { mutableStateOf(0) }

    // ① 连续记录 tonal 卡（CR-07 §5.3-2：圆角 28、56dp 白 55% 图标块 + 火苗线性图标、
    // 34sp 大数字；本页唯一核心大数字）
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(palette.secondaryContainer)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(palette.surface.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center
        ) {
            FlameIcon(
                modifier = Modifier.size(28.dp),
                tint = palette.onSecondaryContainer
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "连续记录",
                style = FreshType.bodySmall,
                color = palette.onSecondaryContainer
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "${data.streak.currentDays}",
                    style = FreshType.numberStreak,
                    color = palette.onSecondaryContainer
                )
                Text(
                    text = "天",
                    style = FreshType.body,
                    color = palette.onSecondaryContainer,
                    modifier = Modifier.padding(start = 4.dp, bottom = 5.dp)
                )
            }
            val longestText = data.streak.longestStart?.let {
                "最长 ${data.streak.longestDays} 天 · 始于 ${it.monthValue}月${it.dayOfMonth}日"
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
    FreshCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (viewMode == 0) {
            // 翻月头（CR-07 §4.8：44dp 圆形按钮 + chipSurface 底 + 主色 20dp 箭头）
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
                // §6.1：切换月份新一月淡入 + 8dp 水平位移（200ms）；减少动画直出终态。
                // AnimatedContent 内容 lambda 是单插槽——这里只有一个 MonthCalendar 根节点，
                // 不踩「多根全叠 top-start」的坑。
                if (reduceMotion) {
                    MonthCalendar(
                        yearMonth = calendarMonth,
                        levels = levels,
                        goalMinutes = goalMinutes,
                        onDayClick = {
                            selectedEpochDay = it.date.toEpochDay()
                            selectedMinutes = it.minutes
                        }
                    )
                } else {
                    val slidePx = with(LocalDensity.current) { 8.dp.roundToPx() }
                    AnimatedContent(
                        targetState = calendarMonth,
                        transitionSpec = {
                            val forward = targetState > initialState
                            val enter = fadeIn(tween(200)) + slideInHorizontally(tween(200)) {
                                if (forward) slidePx else -slidePx
                            }
                            val exit = fadeOut(tween(200)) + slideOutHorizontally(tween(200)) {
                                if (forward) -slidePx else slidePx
                            }
                            enter togetherWith exit
                        },
                        label = "monthSwitch"
                    ) { month ->
                        MonthCalendar(
                            yearMonth = month,
                            levels = levels,
                            goalMinutes = goalMinutes,
                            onDayClick = {
                                selectedEpochDay = it.date.toEpochDay()
                                selectedMinutes = it.minutes
                            }
                        )
                    }
                }
                // §5.7 第 2 条：卡内底部图例（少 [四块] 多 + ○ 今天）
                MonthCalendarLegend()
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

    // ④ tonal 双卡（CR-07 §5.3-5：左「最久的一天」+ 日期周几；右「低于 x 小时」 +
    // 「共 N 个有记录日」；N 从 20 周年视图 levels 统计——contract 未提供该计数，
    // UI 层可从既有数据推出，不动数据口径）
    val recordedDays = data.yearLevels.count { it.recorded }
    MetricCardPair(
        leftLabel = "最久的一天",
        leftValue = MetricValue.Duration(data.extremes.longestMinutes),
        leftNote = data.extremes.longestDate?.let { dateWithWeekday(it) },
        // 标题跟着阈值走：以前写死「低于 2 小时」，用户改不了，标题也就永远是 2。
        // 整小时显示「低于 3 小时」，非整小时（如 90 分钟）退回「低于 90 分钟」。
        rightLabel = thresholdLabel(lowThresholdMin),
        rightValue = MetricValue.Number("${data.extremes.underThresholdDays}"),
        rightNote = "共 $recordedDays 个有记录日",
        compact = true
    )
}

/** 「低于 x 小时」标题：整小时用小时，否则用分钟，避免出现「低于 1.5 小时」。 */
private fun thresholdLabel(minutes: Int): String =
    if (minutes % 60 == 0) "低于 ${minutes / 60} 小时" else "低于 $minutes 分钟"

private val CALENDAR_WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** CR-07 §5.3-5：双卡副行「10月31日 · 周六」（与统计页同款）。 */
private fun dateWithWeekday(date: LocalDate): String =
    "${date.monthValue}月${date.dayOfMonth}日 · ${CALENDAR_WEEKDAYS[date.dayOfWeek.value - 1]}"

/**
 * §5.3-2 火苗线性图标：material `whatshot` 外轮廓（24 视口）Canvas 描边自绘。
 * 工程只依赖 material-icons-core（无 Whatshot），又不为此引入 icons-extended
 * 全量包——与 AppBreakdown 日/夜图标同一策略（Canvas 自绘，不留新依赖）。
 */
private const val FLAME_PATH =
    "M13.5.67s.74 2.65.74 4.8c0 2.06-1.35 3.73-3.41 3.73-2.07 0-3.63-1.67-3.63-3.73" +
        "l.03-.36C5.21 7.51 4 10.62 4 14c0 4.42 3.58 8 8 8s8-3.58 8-8C20 8.61 17.41 3.8 13.5.67z"

@Composable
private fun FlameIcon(modifier: Modifier = Modifier, tint: Color) {
    // 24 视口基准路径只解析一次；每次绘制在**副本**上做缩放（transform 是就地变换，
    // 复用同一 Path 会随重绘越画越大）
    val basePath = remember {
        PathParser().parsePathString(FLAME_PATH).toPath()
    }
    Canvas(modifier = modifier) {
        // 24 视口路径 → 画布尺寸：scale 原点缩放，线宽随缩放保持视口比例（1.7/24）
        scale(scaleX = size.width / 24f, scaleY = size.height / 24f, pivot = Offset.Zero) {
            drawPath(
                basePath,
                tint,
                style = Stroke(width = 1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

/** CR-07 §4.8：44dp 圆形翻月按钮（chipSurface 底 + 主色 20dp 箭头），点击区扩到 48dp（§6.2）。 */
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
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(palette.chipSurface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isNext) {
                    Icons.AutoMirrored.Filled.KeyboardArrowRight
                } else {
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft
                },
                contentDescription = null,
                tint = palette.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun CalendarScreenPreview() {
    ScreenTimeTheme {
        val month = YearMonth.of(2026, 11)
        val levels = (1..30).map { day ->
            val date = month.atDay(day)
            val minutes = when {
                day % 6 == 0 -> null
                day % 5 == 0 -> 420
                day % 3 == 0 -> 300
                else -> 180
            }
            CalendarLevel(date, 0, recorded = minutes != null, minutes = minutes)
        }
        CalendarScreen(
            state = LoadState(
                data = CalendarData(
                    streak = StreakInfo(
                        currentDays = 18,
                        longestDays = 18,
                        longestStart = LocalDate.of(2026, 10, 26)
                    ),
                    extremes = ExtremesInfo(LocalDate.of(2026, 10, 31), 450, null, 0, 3),
                    yearLevels = levels
                )
            ),
            monthLevelsState = LoadState(data = levels),
            calendarMonth = month,
            onMonthChange = {},
            onRetry = {},
            goalMinutes = 240
        )
    }
}
