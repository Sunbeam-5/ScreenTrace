package com.screentrace.app.uikit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.screentrace.app.contract.Coverage
import com.screentrace.app.contract.MetricPoint
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme
import java.time.LocalDate

/**
 * §5.7 详情页计数柱图（与 [CapsuleBarChart] 同一套规格，但消费次数而非分钟）：
 * - 左侧刻度列独占 32dp 不随滚动；量程 0 → 向上取整到 4 的倍数，均分 4 档；
 * - 1–7 个点用 24dp 粗胶囊，8 个及以上用 7dp 细柱；超宽仅绘图区横向滚动，不压缩不丢点；
 * - **三态不互相伪装**：`RECORDED` 且 >0 画实体柱；`RECORDED` 且 =0 **不画实体柱**
 *   （真实 0）；`NO_RECORD` / `GAP` 画虚线空档框，绝不画成 0 柱（§5.7 第 2 条红线）；
 * - 按住柱子显示跟随气泡（即时跟随，不做追赶动画）；横向可滚动时让位给滚动——
 *   滚动一开始就收起气泡，避免手指在滚动却显示「你以为你在看」的值；
 * - 柱体从基线 `scaleY 0→1` 生长 220ms，逐柱错开 8ms 且总错开封顶 360ms；
 *   减少动画时直接呈现终态（§5.7 第 3 条）。
 */
@Composable
fun MetricBarChart(
    points: List<MetricPoint>,
    bubbleLabel: (MetricPoint) -> String,
    xLabel: (MetricPoint) -> String,
    modifier: Modifier = Modifier
) {
    val palette = LocalFreshPalette.current
    if (points.isEmpty()) {
        EmptyState(title = "暂无数据", modifier = modifier)
        return
    }

    val maxValue = points.maxOfOrNull { it.value } ?: 0
    // 量程向上取整到 4 的倍数，保证 4 档刻度都是整数；最小量程 4 次
    val scaleMax = ((maxValue + 3) / 4).coerceAtLeast(1) * 4

    val thick = points.size <= 7
    val plotHeight = if (thick) 126.dp else 92.dp
    val barWidth = if (thick) 24.dp else 7.dp
    val slotGap = if (thick) 10.dp else 4.dp
    val labelRowHeight = 22.dp
    val slotWidth = barWidth + slotGap
    /**
     * 横轴标签的固定宽度（评审 P1-2）。标签以前直接被塞进 [slotWidth] 宽的槽里：
     * 细柱时槽只有 11dp，而「11/8」大约 26dp，结果只画出前一两个字符——
     * 1–7 点（34dp 槽）看不出来，**≥8 天区间全坏**，而近 7 天/30 天趋势正是主场景。
     *
     * 所以标签不再受槽宽约束：给固定 48dp 并居中画在槽中线上，两侧各溢出
     * `(labelWidth - slotWidth) / 2`，再用 [edgePad] 把滚动内容的左右各留出这段，
     * 首尾两个标签才不会贴着滚动区边缘被裁掉。
     */
    val labelWidth = 48.dp
    val edgePad = ((labelWidth - slotWidth) / 2).coerceAtLeast(0.dp)
    // 打点间隔按**标签宽度**算而不是按 28dp 常数：否则细柱每 2 槽打一个点，
    // 相邻两个 26dp 宽的标签会叠在一起。
    val labelEvery = ((labelWidth.value / slotWidth.value).toInt()).coerceAtLeast(1)

    val reduceMotion = rememberReduceMotion()
    val totalBars = points.size
    val staggerMs = (360 / totalBars).coerceAtMost(8).toFloat()
    val growMs = if (thick) 260f else 220f
    val totalMs = growMs + staggerMs * (totalBars - 1).coerceAtLeast(0)
    val progress = remember(points, reduceMotion) { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(points, reduceMotion) {
        if (!reduceMotion) progress.animateTo(1f, tween(totalMs.toInt(), easing = FastOutSlowInEasing))
    }

    // 手指按住的槽位（null = 无气泡）
    var pressedIndex by remember(points) { mutableStateOf<Int?>(null) }
    val density = LocalDensity.current
    val slotWidthPx = with(density) { slotWidth.toPx() }
    val edgePadPx = with(density) { edgePad.toPx() }
    val scroll = rememberScrollState()

    Row(modifier = modifier.fillMaxWidth()) {
        // 固定刻度列（独占 32dp，不参与滚动）
        Box(Modifier.width(32.dp).height(plotHeight)) {
            repeat(5) { i ->
                Text(
                    text = "${scaleMax * i / 4}",
                    style = FreshType.label,
                    color = palette.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(y = (plotHeight - 12.dp) * (1f - i / 4f))
                )
            }
        }

        Box(Modifier.weight(1f)) {
            Box(Modifier.horizontalScroll(scroll)) {
                Box(
                    Modifier
                        .width(slotWidth * points.size + edgePad * 2)
                        .height(plotHeight + labelRowHeight)
                        .pointerInput(points, slotWidthPx, edgePadPx) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                // 减去左端留白，否则整张图会偏半格
                                pressedIndex = ((down.position.x - edgePadPx) / slotWidthPx).toInt()
                                    .coerceIn(0, points.lastIndex)
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    // 滚动接管（父容器消费了拖动）→ 收起气泡，别让人边滚边看旧值
                                    if (change.isConsumed) break
                                    if (!change.pressed) break
                                    pressedIndex = ((change.position.x - edgePadPx) / slotWidthPx)
                                        .toInt()
                                        .coerceIn(0, points.lastIndex)
                                }
                                // 松手或滚动接管都收起气泡
                                pressedIndex = null
                            }
                        }
                ) {
                    Row(Modifier.padding(horizontal = edgePad)) {
                        points.forEachIndexed { index, point ->
                            val start = (index * staggerMs) / totalMs
                            val end = (index * staggerMs + growMs) / totalMs
                            val local = ((progress.value - start) / (end - start))
                                .coerceIn(0f, 1f)
                            BarSlot(
                                point = point,
                                scaleMax = scaleMax,
                                plotHeight = plotHeight,
                                barWidth = barWidth,
                                slotWidth = slotWidth,
                                labelWidth = labelWidth,
                                labelRowHeight = labelRowHeight,
                                showLabel = index % labelEvery == 0 || index == points.lastIndex,
                                xLabel = xLabel(point),
                                factor = if (reduceMotion) 1f else local
                            )
                        }
                    }

                    pressedIndex?.let { index ->
                        val point = points.getOrNull(index) ?: return@let
                        val bubbleWidth = 132.dp
                        val bubbleWidthPx = with(density) { bubbleWidth.toPx() }
                        val centerPx = edgePadPx + slotWidthPx * index +
                            with(density) { barWidth.toPx() } / 2f
                        val maxX = (slotWidth * points.size + edgePad * 2)
                            .let { with(density) { it.toPx() } } - bubbleWidthPx
                        val x = (centerPx - bubbleWidthPx / 2f).coerceIn(0f, maxX.coerceAtLeast(0f))
                        Box(
                            modifier = Modifier
                                .offset { IntOffset(x.toInt(), 0) }
                                .width(bubbleWidth)
                                .clip(RoundedCornerShape(12.dp))
                                .background(palette.primary)
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = bubbleLabel(point),
                                style = FreshType.label,
                                color = palette.onPrimary,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BarSlot(
    point: MetricPoint,
    scaleMax: Int,
    plotHeight: androidx.compose.ui.unit.Dp,
    barWidth: androidx.compose.ui.unit.Dp,
    slotWidth: androidx.compose.ui.unit.Dp,
    labelWidth: androidx.compose.ui.unit.Dp,
    labelRowHeight: androidx.compose.ui.unit.Dp,
    showLabel: Boolean,
    xLabel: String,
    factor: Float
) {
    val palette = LocalFreshPalette.current
    Box(Modifier.width(slotWidth)) {
        Box(
            modifier = Modifier
                .width(barWidth)
                .height(plotHeight),
            contentAlignment = Alignment.BottomCenter
        ) {
            when {
                // 窗口外 / 拉取缺口：虚线空档，绝不画成 0 柱
                point.coverage != Coverage.RECORDED -> Canvas(
                    Modifier.width(barWidth).height(plotHeight)
                ) {
                    drawRoundRect(
                        color = palette.onSurfaceVariant.copy(alpha = 0.28f),
                        size = Size(size.width, size.height),
                        cornerRadius = CornerRadius(size.width / 2f, size.width / 2f),
                        style = Stroke(
                            width = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(3.dp.toPx(), 4.dp.toPx())
                            )
                        )
                    )
                }

                // 真实 0：不画实体柱（靠表格里的「0」与文案区分）
                point.value <= 0 -> Unit

                else -> {
                    val fraction = (point.value.toFloat() / scaleMax).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .width(barWidth)
                            .height(plotHeight * fraction * factor)
                            .clip(RoundedCornerShape(50.dp))
                            .background(palette.chart1)
                    )
                }
            }
        }
        if (showLabel) {
            Text(
                text = xLabel,
                style = FreshType.label,
                color = palette.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                // 溢出可见：requiredWidth 已经把标签从槽宽约束里解放出来，
                // 万一标签比 48dp 还长（如「12/31」加大字号）也宁可画出去，不要切一半
                overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // requiredWidth 而不是 width：普通 width 会被父 Box 的 maxWidth(=槽宽)
                    // 反过来夹住，标签又变回「被裁」（这就是评审 P1-2 的成因）
                    .requiredWidth(labelWidth)
                    .offset(y = labelRowHeight - 12.dp)
            )
        }
    }
}

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun MetricBarChartPreview() {
    ScreenTimeTheme {
        val day = LocalDate.of(2026, 11, 8)
        val points = (0..23).map { hour ->
            MetricPoint(day, hour, if (hour in 9..22) (hour * 3) % 17 else 0, Coverage.RECORDED)
        }
        Box(Modifier.padding(20.dp)) {
            MetricBarChart(
                points = points,
                bubbleLabel = { "${it.hour}时 · ${it.value}次" },
                xLabel = { "${it.hour}" }
            )
        }
    }
}

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun MetricBarChartGapPreview() {
    ScreenTimeTheme {
        val start = LocalDate.of(2026, 11, 1)
        val points = (0..9).map { i ->
            val coverage = when {
                i < 2 -> Coverage.NO_RECORD
                i == 5 -> Coverage.GAP
                else -> Coverage.RECORDED
            }
            MetricPoint(start.plusDays(i.toLong()), null, i * 7, coverage)
        }
        Box(Modifier.padding(20.dp)) {
            MetricBarChart(
                points = points,
                bubbleLabel = { "${it.date} · ${it.value}次" },
                xLabel = { "${it.date.dayOfMonth}日" }
            )
        }
    }
}
