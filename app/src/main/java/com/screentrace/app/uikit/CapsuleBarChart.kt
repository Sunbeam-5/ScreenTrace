package com.screentrace.app.uikit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
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
import kotlin.math.roundToInt

/**
 * §5.7-2~5 / §7-20~21 趋势柱图（批 6g 按对照图重定规范）：
 *
 * - **量程统一**：0 → `min(ceil(最大值到整小时), 24h)`，7 天与 30 天同一套算法；
 *   用了 23h40m 顶格就是 24h，不会因为某天特别长把纵轴拉出屏幕；
 * - **三档刻度 + 平均线**：只画 `0 / 中（顶格一半）/ 顶`，另加一条虚线平均线并标「平均」。
 *   平均只算**有记录日**——把「没采集到」当 0 会把线拉低，是假数据；
 * - **柱形**：≤7 天 24dp 粗胶囊，8 天以上按可用宽度反推细柱（≤7dp），
 *   **30 天必须一屏放下**，只有 31 天以上才横向滚动；
 * - **日期标签**：≤7 天全标，8 天以上只标首 / 1÷3 / 2÷3 / 末这 4 个。
 *   ⚠️ 标签不能放在「槽宽 = 柱宽」的格子里：30 天时槽宽只有 9dp，
 *   `10/13` 会被裁成 `1`（真机实测）。这里用独立标签层，按槽心定位、允许溢出槽宽，
 *   首末两个再夹进边界内；
 * - **点柱看详情**：点任意柱弹出浮层（当日时长、星期与日期、前三名应用），
 *   浮层落在选中柱的**对侧**，不遮挡该柱；再点一次或点空白处关闭。
 */
@Composable
fun CapsuleBarChart(
    points: List<DailyPoint>,
    modifier: Modifier = Modifier,
    /** 每日 top3（§5.7-5 浮层数据源）；缺数据的日期浮层只显示时长。 */
    topAppsPerDay: Map<LocalDate, List<AppUsage>> = emptyMap()
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
    val thick = count <= 7
    val plotHeight = if (thick) 140.dp else 108.dp
    val labelRowHeight = 20.dp
    val axisWidth = 32.dp
    val labelIndices = TrendChartMath.labelIndices(count)

    var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val plotAreaDp = (maxWidth - axisWidth).coerceAtLeast(1.dp)
        val slotDp = if (scrollable) 12.dp else plotAreaDp / count
        val barWidth = if (scrollable) {
            7.dp
        } else {
            TrendChartMath.barWidthDp(count, plotAreaDp.value).dp
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            // 固定刻度列：0 / 中 / 顶 三个值（§5.7-3）
            Box(
                modifier = Modifier
                    .width(axisWidth)
                    .height(plotHeight)
            ) {
                Text(
                    text = axisLabel(scaleMax),
                    style = FreshType.label,
                    color = palette.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.TopStart)
                )
                Text(
                    text = axisLabel(scaleMax / 2),
                    style = FreshType.label,
                    color = palette.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterStart)
                )
                Text(
                    text = "0",
                    style = FreshType.label,
                    color = palette.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.BottomStart)
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(plotHeight + labelRowHeight)
                    // 点空白处关闭浮层；子节点的点击会先被消费，不影响柱体选中
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { selectedIndex = null }
            ) {
                // 网格线（三档）+ 平均线
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(plotHeight)
                ) {
                    listOf(0f, 0.5f, 1f).forEach { fraction ->
                        val y = size.height * fraction
                        drawLine(
                            color = palette.track,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                    if (average != null) {
                        val y = size.height * (1f - average.toFloat() / scaleMax)
                        drawLine(
                            color = palette.primary,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(6.dp.toPx(), 5.dp.toPx())
                            )
                        )
                    }
                }

                // 平均线标签：贴绘图区左端（带底色，压住虚线也读得清）。
                // 放右端会撞上最近几天的柱子（真机实测）。
                if (average != null) {
                    val avgY = plotHeight * (1f - average.toFloat() / scaleMax)
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(y = (avgY - 9.dp).coerceAtLeast(0.dp))
                            .clip(RoundedCornerShape(6.dp))
                            .background(palette.surface)
                            .padding(horizontal = 3.dp)
                    ) {
                        Text(text = "平均", style = FreshType.label, color = palette.primary)
                    }
                }

                // 柱与标签作为一整块内容参与滚动（否则 31 天以上标签与柱会错位）
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
                            modifier = Modifier
                                .width(contentWidth)
                                .height(plotHeight),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            points.forEachIndexed { index, point ->
                                val selected = selectedIndex == index
                                Box(
                                    modifier = Modifier
                                        .width(slotDp)
                                        .fillMaxHeight()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            selectedIndex = if (selected) null else index
                                        },
                                    contentAlignment = Alignment.BottomCenter
                                ) {
                                    // 零使用与无记录都不画实体柱（§5.7），但整列仍是点击区
                                    if (point.minutes > 0) {
                                        Box(
                                            modifier = Modifier
                                                .width(barWidth)
                                                .height(plotHeight * (point.minutes.toFloat() / scaleMax))
                                                .clip(CircleShape)
                                                .background(if (selected) palette.primary else palette.chart1)
                                        )
                                    }
                                }
                            }
                        }
                        // 独立标签层：按槽心定位、不按槽宽裁切
                        DayLabelRow(
                            points = points,
                            labelIndices = labelIndices,
                            slotDp = slotDp.value,
                            color = palette.onSurfaceVariant,
                            modifier = Modifier
                                .width(contentWidth)
                                .height(labelRowHeight)
                        )
                    }
                }

                // 浮层：落在选中柱的对侧，绝不覆盖该柱
                selectedIndex?.let { index ->
                    val point = points[index]
                    val centerDp = TrendChartMath.slotCenterDp(index, count, plotAreaDp.value)
                    val halfSlot = (plotAreaDp.value / count) / 2f
                    val wanted = if (centerDp <= plotAreaDp.value / 2f) {
                        centerDp + halfSlot + 6f
                    } else {
                        centerDp - halfSlot - 6f - TOOLTIP_WIDTH_DP
                    }
                    val x = wanted.coerceIn(0f, (plotAreaDp.value - TOOLTIP_WIDTH_DP).coerceAtLeast(0f))
                    TrendTooltip(
                        point = point,
                        apps = topAppsPerDay[point.date].orEmpty(),
                        modifier = Modifier.offset(x = x.dp, y = 0.dp)
                    )
                }
            }
        }
    }
}

private const val TOOLTIP_WIDTH_DP = 176f

/**
 * 日期标签层：每个标签按自己的实际宽度居中在槽心上，允许超出槽宽。
 *
 * 为什么不用 `Row { slot -> Box(width = slot) { Text(maxLines = 1) } }`：
 * 30 天时槽宽只有 ~9dp，`10/13` 会被父约束压成 `1`（真机截图证据）。
 * 这里用 `Layout` 以无约束测量文本，再按槽心摆放并夹在两侧边界内。
 */
@Composable
private fun DayLabelRow(
    points: List<DailyPoint>,
    labelIndices: Set<Int>,
    slotDp: Float,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    val labels = labelIndices.sorted().map { it to points[it].date.format(DATE_LABEL) }
    Layout(
        content = {
            labels.forEach { (_, text) ->
                Text(text = text, style = FreshType.label, color = color, maxLines = 1)
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

/** 点柱浮层（§5.7-5 / §7-21）：时长 + 星期与日期 + 前三名应用。 */
@Composable
private fun TrendTooltip(
    point: DailyPoint,
    apps: List<AppUsage>,
    modifier: Modifier = Modifier
) {
    val palette = LocalFreshPalette.current
    Column(
        modifier = modifier
            .width(TOOLTIP_WIDTH_DP.dp)
            .shadow(10.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(palette.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            // 真实 0 与无记录给不同文案：前者是「这天确实没用」，后者是「没采集到」
            text = if (point.recorded) {
                UsageCalculator.formatDuration(point.minutes)
            } else {
                "无记录"
            },
            style = FreshType.sectionTitle,
            color = palette.onSurface
        )
        Text(
            text = "${weekdayLabel(point.date)} ${point.date.monthValue}月${point.date.dayOfMonth}日",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
        if (point.recorded && point.minutes > 0) {
            if (apps.isEmpty()) {
                Text(
                    text = "（该日无应用明细）",
                    style = FreshType.label,
                    color = palette.onSurfaceMuted
                )
            } else {
                apps.take(3).forEach { app ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AppIcon(pkg = app.pkg, name = app.name, size = 18.dp)
                        Text(
                            text = app.name,
                            style = FreshType.label,
                            color = palette.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = UsageCalculator.formatDuration(app.minutes),
                            style = FreshType.label,
                            color = palette.onSurfaceVariant
                        )
                    }
                }
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
    minutes % 15 == 0 -> {
        val hours = minutes / 60.0
        val text = "%.2f".format(hours).trimEnd('0').trimEnd('.')
        "${text}h"
    }
    else -> "${minutes}min"
}

private fun previewPoints(days: Int): List<DailyPoint> =
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
        Column(
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
