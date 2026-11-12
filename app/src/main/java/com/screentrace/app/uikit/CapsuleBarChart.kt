package com.screentrace.app.uikit

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.screentrace.app.contract.AppUsage
import com.screentrace.app.contract.DailyPoint
import com.screentrace.app.contract.UsageCalculator
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 趋势柱图（CR-07 §4.5 改版；量程/标签数学仍全部走 [TrendChartMath]，不改算法）。
 *
 * 与批 6g 版本的结构差异：点柱详情从「落在对侧的浮层」改为「图表正上方的固定面板」。
 * 浮层在柱子靠近两端时对侧空间不足，会被挤到柱子上；固定面板不占绘图区、高度恒定，
 * 点选时页面不会跳。默认选中最后一天（今天）。
 *
 * 其余规则保留：量程 0→min(ceil(最大到整小时),24h)、三档刻度、平均线只算有记录日、
 * 30 天一屏放下、31 天起横向滚动、零使用与无记录都不画实体柱、标签独立层按槽心定位。
 */
@Composable
fun CapsuleBarChart(
    points: List<DailyPoint>,
    modifier: Modifier = Modifier,
    /** 每日 top3（详情面板数据源）；缺数据的日期只显示总时长。 */
    topAppsPerDay: Map<LocalDate, List<AppUsage>> = emptyMap(),
    /** 置位键变化时回到默认选中（页面切走 / 换区间，Pager 常驻不销毁组合）。 */
    resetKey: Any? = null
) {
    val palette = LocalFreshPalette.current
    if (points.isEmpty()) {
        EmptyState(title = "暂无趋势数据", modifier = modifier)
        return
    }

    val count = points.size
    val scaleMax = TrendChartMath.scaleMaxMinutes(points.maxOfOrNull { it.minutes } ?: 0)
    val average = TrendChartMath.averageMinutes(points)
    val scrollable = TrendChartMath.scrollable(count)
    val plotHeight = 150.dp
    val labelRowHeight = 20.dp
    val axisWidth = 32.dp
    val labelIndices = TrendChartMath.labelIndices(count)
    val reduceMotion = rememberReduceMotion()
    val haptic = LocalHapticFeedback.current

    // §4.5：默认选中最后一天；切主页面时回到默认，避免切走再回来还停在几天前。
    // 只用 remember 的 key 做「换数据/切页回到默认」，**不要再配一个 LaunchedEffect 重置**：
    // effect 会在 key 变化的那一帧之后把用户刚点中的柱子立刻改回最后一天（真机实测点柱无效）。
    var selectedIndex by remember(points, resetKey) {
        mutableStateOf(TrendSelectionMath.defaultIndex(count))
    }
    val currentIndex by rememberUpdatedState(selectedIndex)

    // 柱子生长动画（§6.1）：0→1、500ms、每根延迟 30ms；减少动画直接落终值。
    var started by remember(points) { mutableStateOf(false) }
    LaunchedEffect(points) { started = true }

    val safeIndex = TrendSelectionMath.step(selectedIndex, 0, count)
    val selectedPoint = points[safeIndex]

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TrendDetailPanel(
            point = selectedPoint,
            apps = topAppsPerDay[selectedPoint.date].orEmpty()
        )

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val plotAreaDp = (maxWidth - axisWidth).coerceAtLeast(1.dp)
            val slotDp = if (scrollable) 12.dp else plotAreaDp / count
            // §4.5：7 天粗柱 18dp；30 天按可用宽度反推 6–8dp（数学留给 TrendChartMath）
            val barWidth = if (count <= 7 && !scrollable) {
                18.dp
            } else {
                TrendChartMath.barWidthDp(count, plotAreaDp.value).dp
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.width(axisWidth).height(plotHeight)) {
                    Text(
                        text = axisLabel(scaleMax), style = FreshType.label,
                        color = palette.onSurfaceVariant, modifier = Modifier.align(Alignment.TopStart)
                    )
                    Text(
                        text = axisLabel(scaleMax / 2), style = FreshType.label,
                        color = palette.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterStart)
                    )
                    Text(
                        text = "0", style = FreshType.label,
                        color = palette.onSurfaceVariant, modifier = Modifier.align(Alignment.BottomStart)
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(plotHeight + labelRowHeight)
                        // §6.1 / §5.7：左右滑动切换选中日，30dp 阈值防误触并轻震
                        .pointerInput(count) {
                            var accumulated = 0f
                            val threshold = 30.dp.toPx()
                            detectHorizontalDragGestures(
                                onDragStart = { accumulated = 0f },
                                onHorizontalDrag = { change, dragAmount ->
                                    accumulated += dragAmount
                                    if (abs(accumulated) >= threshold) {
                                        val step = if (accumulated > 0) -1 else 1
                                        selectedIndex = TrendSelectionMath.step(currentIndex, step, count)
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        accumulated = 0f
                                    }
                                    change.consume()
                                }
                            )
                        }
                ) {
                    // 三档网格线 + 平均线（主色 55%、1.5dp 虚线；图例移到卡片下方）
                    Canvas(modifier = Modifier.fillMaxWidth().height(plotHeight)) {
                        listOf(0f, 0.5f, 1f).forEach { fraction ->
                            val y = size.height * fraction
                            drawLine(
                                color = palette.track,
                                start = Offset(0f, y), end = Offset(size.width, y),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                        if (average != null) {
                            val y = size.height * (1f - average.toFloat() / scaleMax)
                            drawLine(
                                color = palette.primary.copy(alpha = 0.55f),
                                start = Offset(0f, y), end = Offset(size.width, y),
                                strokeWidth = 1.5.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(6.dp.toPx(), 5.dp.toPx())
                                )
                            )
                        }
                    }

                    val contentWidth = if (scrollable) slotDp * count else plotAreaDp
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier
                            )
                    ) {
                        Column(modifier = Modifier.width(contentWidth)) {
                            Row(
                                modifier = Modifier.width(contentWidth).height(plotHeight),
                                verticalAlignment = Alignment.Bottom
                            ) {
                                points.forEachIndexed { index, point ->
                                    val growth by animateFloatAsState(
                                        targetValue = if (started || reduceMotion) 1f else 0f,
                                        animationSpec = tween(
                                            durationMillis = 500,
                                            delayMillis = if (reduceMotion) 0 else index * 30,
                                            easing = FastOutSlowInEasing
                                        ),
                                        label = "barGrow$index"
                                    )
                                    val selected = selectedIndex == index
                                    Box(
                                        modifier = Modifier
                                            .width(slotDp)
                                            .fillMaxHeight()
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) {
                                                selectedIndex = index
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            },
                                        contentAlignment = Alignment.BottomCenter
                                    ) {
                                        if (point.minutes > 0) {
                                            Box(
                                                modifier = Modifier
                                                    .width(barWidth)
                                                    .height(
                                                        plotHeight *
                                                            (point.minutes.toFloat() / scaleMax) * growth
                                                    )
                                                    .clip(
                                                        RoundedCornerShape(
                                                            topStart = 9.dp, topEnd = 9.dp,
                                                            bottomStart = 4.dp, bottomEnd = 4.dp
                                                        )
                                                    )
                                                    .background(
                                                        if (selected) palette.primary else palette.barSurface
                                                    )
                                            )
                                        }
                                    }
                                }
                            }
                            DayLabelRow(
                                points = points,
                                labelIndices = labelIndices,
                                slotDp = slotDp.value,
                                selectedIndex = selectedIndex,
                                modifier = Modifier.width(contentWidth).height(labelRowHeight)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * §4.5 选中日详情面板：固定在图表正上方，不随柱浮动、不遮柱。
 * 底 chipSurface、圆角 18、内边距 12/14；第一行日期 + 总时长，第二行最多三个应用小药丸。
 */
@Composable
private fun TrendDetailPanel(point: DailyPoint, apps: List<AppUsage>) {
    val palette = LocalFreshPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(palette.chipSurface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${weekdayLabel(point.date)} · ${point.date.monthValue}月${point.date.dayOfMonth}日",
                style = FreshType.bodySmall,
                color = palette.onSurface
            )
            Text(
                text = if (point.recorded) {
                    UsageCalculator.formatDuration(point.minutes)
                } else {
                    "无记录"
                },
                style = FreshType.numberSection,
                color = palette.onSurface
            )
        }
        when {
            !point.recorded || point.minutes <= 0 -> Text(
                text = if (point.recorded) "这天没有使用记录" else "当天未采集到数据",
                style = FreshType.label,
                color = palette.onSurfaceVariant
            )
            apps.isEmpty() -> Text(
                text = "（该日无应用明细）",
                style = FreshType.label,
                color = palette.onSurfaceVariant
            )
            else -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                apps.take(3).forEach { app ->
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .background(palette.surface)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        AppIcon(pkg = app.pkg, name = app.name, size = 20.dp)
                        // 紧凑 H:MM：三个药丸必须都放得下。formatDuration 的
                        // 「13小时16分钟」会把第三个挤成省略号（真机实测）；
                        // H:MM 是项目既有的数字形态（§5.3，圆环同款）。
                        Text(
                            text = UsageCalculator.formatClock(app.minutes),
                            style = FreshType.label,
                            color = palette.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/** 日期标签独立层：按槽心定位、允许溢出槽宽；选中日加粗并使用主文字色（§4.5）。 */
@Composable
private fun DayLabelRow(
    points: List<DailyPoint>,
    labelIndices: Set<Int>,
    slotDp: Float,
    selectedIndex: Int,
    modifier: Modifier = Modifier
) {
    val palette = LocalFreshPalette.current
    val labels = labelIndices.sorted().map { it to points[it].date.format(DATE_LABEL) }
    Layout(
        content = {
            labels.forEach { (index, text) ->
                val selected = index == selectedIndex
                Text(
                    text = text,
                    style = if (selected) FreshType.labelBold else FreshType.label,
                    color = if (selected) palette.onSurface else palette.onSurfaceVariant,
                    maxLines = 1
                )
            }
        },
        modifier = modifier
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { i, placeable ->
                val index = labels[i].first
                val center = slotDp.dp.toPx() * (index + 0.5f)
                val x = (center - placeable.width / 2f).roundToInt()
                    .coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
                placeable.placeRelative(x, 0)
            }
        }
    }
}

private val DATE_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d")
private val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private fun weekdayLabel(date: LocalDate): String = WEEKDAYS[date.dayOfWeek.value - 1]

/** §5.3 轴标：整小时 `Nh`，非整小时最多两位小数 `N.Nh`，其余 `Nmin`。 */
private fun axisLabel(minutes: Int): String = when {
    minutes == 0 -> "0"
    minutes % 60 == 0 -> "${minutes / 60}h"
    minutes % 15 == 0 -> "${minutes / 60}.${(minutes % 60) * 10 / 60}h"
    else -> "${minutes}min"
}

private fun previewPoints(days: Int): List<DailyPoint> {
    val today = LocalDate.of(2026, 11, 12)
    return (0 until days).map { i ->
        DailyPoint(
            date = today.minusDays((days - 1 - i).toLong()),
            minutes = 60 + (i * 47) % 540,
            recorded = i % 9 != 3
        )
    }
}

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun CapsuleBarChartThickPreview() {
    ScreenTimeTheme {
        Column(Modifier.padding(16.dp)) { CapsuleBarChart(points = previewPoints(7)) }
    }
}

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun CapsuleBarChartThinPreview() {
    ScreenTimeTheme {
        Column(Modifier.padding(16.dp)) { CapsuleBarChart(points = previewPoints(30)) }
    }
}
