package com.screentrace.app.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.screentrace.app.contract.MonthPoint
import com.screentrace.app.contract.UsageCalculator
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import kotlin.math.roundToInt

/**
 * §5.11 年视图的 12 根月柱（每月**日均**，不是月总量）。
 *
 * 与 [CapsuleBarChart] 同一套视觉规范（统一量程 / 0-中-顶三档刻度 / 虚线平均线 /
 * 只画有数据的高度），但横轴是「月」而不是日期——所以不能直接复用后者：
 * 后者的标签层按 `DailyPoint.date` 格式化，年视图要写「1月…12月」。
 *
 * 只画**有记录**的月份（`monthTrend` 里没有的月份整列留空），因此「没采集到的月份」
 * 不会被画成 0 高柱假装「这个月没怎么用手机」（§5.7 红线，§7-27 覆盖说明）。
 * 柱下只标首 / 中 / 末三个月，避免 12 个标签挤成一片。
 */
@Composable
fun MonthlyBarChart(
    months: List<MonthPoint>,
    modifier: Modifier = Modifier,
    /** 置位键变化时清掉选中月（切周期 / 切页）。 */
    resetKey: Any? = null
) {
    val palette = LocalFreshPalette.current
    if (months.isEmpty()) {
        EmptyState(title = "暂无月度数据", modifier = modifier)
        return
    }
    val byMonth = months.associateBy { it.month }
    val values = (1..12).map { byMonth[it] ?: MonthPoint(it, 0, 0, finished = false) }
    val scaleMax = TrendChartMath.scaleMaxMinutes(values.maxOfOrNull { it.averageMinutes } ?: 0)
    val recorded = values.filter { it.recordedDays > 0 }
    val average = if (recorded.isEmpty()) 0 else recorded.sumOf { it.averageMinutes } / recorded.size
    var selectedMonth by remember(months, resetKey) { mutableStateOf<Int?>(null) }

    val plotHeight = 120.dp
    val labelRowHeight = 20.dp
    val axisWidth = 32.dp

    Column(modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val plotAreaDp = (maxWidth - axisWidth).coerceAtLeast(1.dp)
            val slotDp = plotAreaDp / 12

            Row(modifier = Modifier.fillMaxWidth()) {
                // 左侧刻度列（0 / 中 / 顶），与 CapsuleBarChart 一致
                Box(modifier = Modifier.width(axisWidth).height(plotHeight)) {
                    Text(
                        text = axisLabelOf(scaleMax),
                        style = FreshType.label,
                        color = palette.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.TopStart)
                    )
                    Text(
                        text = axisLabelOf(scaleMax / 2),
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

                Column(modifier = Modifier.weight(1f)) {
                    Box(modifier = Modifier.fillMaxWidth().height(plotHeight)) {
                        // 平均线（只算有记录的月份；缺月不拉低平均）
                        if (average > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .offset(y = plotHeight * (1f - average.toFloat() / scaleMax))
                            ) {
                                androidx.compose.foundation.Canvas(
                                    modifier = Modifier.fillMaxWidth().height(1.dp)
                                ) {
                                    drawLine(
                                        color = palette.primary.copy(alpha = 0.5f),
                                        start = androidx.compose.ui.geometry.Offset(0f, 0f),
                                        end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                                        strokeWidth = 1.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(
                                            floatArrayOf(6.dp.toPx(), 5.dp.toPx())
                                        )
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().height(plotHeight),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            values.forEach { point ->
                                val barHeight = if (point.averageMinutes > 0) {
                                    plotHeight * (point.averageMinutes.toFloat() / scaleMax)
                                } else {
                                    0.dp
                                }
                                Box(
                                    modifier = Modifier
                                        .width(slotDp)
                                        .fillMaxHeight()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            selectedMonth = if (selectedMonth == point.month) {
                                                null
                                            } else {
                                                point.month
                                            }
                                        },
                                    contentAlignment = Alignment.BottomCenter
                                ) {
                                    if (point.averageMinutes > 0) {
                                        Box(
                                            modifier = Modifier
                                                .width(barWidthFor(slotDp.value).dp)
                                                .height(barHeight)
                                                .clip(CircleShape)
                                                .background(
                                                    if (selectedMonth == point.month) {
                                                        palette.primary
                                                    } else {
                                                        palette.chart1
                                                    }
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // 只标首 / 中 / 末三个月
                    MonthLabelRow(
                        slotDp = slotDp.value,
                        color = palette.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().height(labelRowHeight)
                    )
                }
            }
        }

        // 选中月的一句话详情（比浮层简单可靠：年视图一年只换一次视角，不需要悬浮卡片）
        val selected = selectedMonth?.let { byMonth[it] }
        Text(
            text = when {
                selected != null && selected.recordedDays > 0 ->
                    "${selected.month}月 · 日均 ${UsageCalculator.formatDuration(selected.averageMinutes)}" +
                        " · 有记录 ${selected.recordedDays} 天" +
                        if (selected.finished) "" else "（本月未结束）"
                selected != null -> "${selected.month}月 · 无记录"
                else -> "点柱查看某月日均（按有记录日计算）"
            },
            style = FreshType.label,
            color = if (selected != null) palette.onSurface else palette.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/** 细柱宽度：12 根柱时可读性优先，但不超过槽宽的 55%。 */
private fun barWidthFor(slotDp: Float): Float = (slotDp * 0.55f).coerceIn(6f, 28f)

/** 刻度文案：与 CapsuleBarChart 的 axisLabel 同规则（整点小时 / 分钟）。 */
private fun axisLabelOf(minutes: Int): String = when {
    minutes <= 0 -> "0"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes}m"
}

/**
 * 月份标签层：把「1月」「6月」「12月」按槽心摆放并夹在边界内。
 * 用无约束测量 + 手动摆放，避免 12 个标签被压进 ~25dp 的槽里截断。
 */
@Composable
private fun MonthLabelRow(
    slotDp: Float,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    val labels = listOf(0 to "1月", 5 to "6月", 11 to "12月")
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
            placeables.forEachIndexed { index, placeable ->
                val slot = labels[index].first
                val center = slotDp.dp.toPx() * (slot + 0.5f)
                val x = (center - placeable.width / 2f).roundToInt()
                    .coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
                placeable.placeRelative(x, 0)
            }
        }
    }
}
