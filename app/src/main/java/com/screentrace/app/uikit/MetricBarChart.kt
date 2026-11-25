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
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    modifier: Modifier = Modifier,
    barColor: Color? = null
) {
    val palette = LocalFreshPalette.current
    val resolvedBarColor = barColor ?: palette.chart1
    if (points.isEmpty()) {
        EmptyState(title = "暂无数据", modifier = modifier)
        return
    }

    val maxValue = points.maxOfOrNull { it.value } ?: 0
    // 量程向上取整到 4 的倍数，保证 4 档刻度都是整数；最小量程 4 次
    val scaleMax = ((maxValue + 3) / 4).coerceAtLeast(1) * 4

    val thick = points.size <= 7
    val plotHeight = if (thick) 184.dp else 168.dp
    val barWidth = if (thick) 24.dp else 10.dp
    val slotGap = if (thick) 10.dp else 5.dp
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
    /**
     * 滚动内容左右留白 = **半个标签宽**，而不是「标签与槽宽的差的一半」。
     *
     * 后者在细柱时算出来是 (48-11)/2 ≈ 18.5dp，但最右那个标签的中心在
     * `edgePad + slotWidth × n - barWidth/2` 处，加上半个标签宽后仍会顶到内容框边界，
     * horizontalScroll 一裁就变成「2:」这种半截数字（真机实测）。
     * 留满半个标签宽后，首尾标签都完整落在内容框内。
     */
    val edgePad = labelWidth / 2
    // 打点间隔按**标签宽度**算而不是按 28dp 常数：否则细柱每 2 槽打一个点，
    // 相邻两个 26dp 宽的标签会叠在一起。
    val labelEvery = ((labelWidth.value / slotWidth.value).toInt()).coerceAtLeast(1)
    // 最后一个标签必须落在「对齐点」上。以前无条件强制显示 lastIndex，
    // 24 个点时最后两个标签是 20 和 23——相距仅 33dp 而标签宽 48dp，必然互相压住
    // （真机上表现为「20」和「2:」挤在一起）。宁可少画最后一个，也不要重叠。
    val lastLabelIndex = points.lastIndex - points.lastIndex % labelEvery

    val reduceMotion = rememberReduceMotion()
    val totalBars = points.size
    val staggerMs = (360 / totalBars).coerceAtMost(8).toFloat()
    val growMs = if (thick) 260f else 220f
    val totalMs = growMs + staggerMs * (totalBars - 1).coerceAtLeast(0)
    // CR-15 评审 P2-1 收口：progress **不随 points 重建**。原 `remember(points, ...)`
    // 会让每次切区间把 Animatable 重置成 0、柱图从零重新长一遍（真机抽帧里表现为
    // 中间几帧柱图几乎空白），与今日页 RingProgress 被整页淡变重播是同一类问题。
    // 现在只有首次组合播入场动画；之后换区间柱高直接由旧值过渡到新值，
    // 「换了区间」的反馈由日均数字逐位滚动与柱形变化承担。
    val progress = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(points, reduceMotion) {
        if (reduceMotion) progress.snapTo(1f)
        else progress.animateTo(1f, tween(totalMs.toInt(), easing = FastOutSlowInEasing))
    }

    // 手指按住或轻点固定的槽位（null = 无气泡）
    var pressedIndex by remember(points) { mutableStateOf<Int?>(null) }
    var pinnedIndex by remember(points) { mutableStateOf<Int?>(null) }
    val density = LocalDensity.current
    val slotWidthPx = with(density) { slotWidth.toPx() }
    val edgePadPx = with(density) { edgePad.toPx() }
    val scroll = rememberScrollState()
    LaunchedEffect(scroll.isScrollInProgress) {
        if (scroll.isScrollInProgress) {
            pinnedIndex = null
        }
    }
    val activeIndex = pressedIndex ?: pinnedIndex
    val peakPoint = remember(points) {
        points.filter { it.coverage == Coverage.RECORDED }.maxByOrNull { it.value }
    }
    val chartDesc = remember(points, peakPoint) {
        val recordedCount = points.count { it.coverage == Coverage.RECORDED }
        if (recordedCount == 0) {
            "柱状图，共 ${points.size} 个时段，当前区间暂无记录"
        } else {
            val peakText = peakPoint?.let { bubbleLabel(it) } ?: "0"
            "柱状图，共 ${points.size} 个时段，其中 $recordedCount 个时段有记录，最高为 $peakText"
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = chartDesc }
    ) {
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
                                val downIdx = ((down.position.x - edgePadPx) / slotWidthPx).toInt()
                                    .coerceIn(0, points.lastIndex)
                                pressedIndex = downIdx
                                var dragged = false
                                var abortedByScroll = false
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    // 滚动接管（父容器消费了拖动）→ 收起气泡，别让人边滚边看旧值
                                    if (change.isConsumed) {
                                        abortedByScroll = true
                                        break
                                    }
                                    if (!change.pressed) break
                                    val moved = kotlin.math.abs(change.position.x - down.position.x) > 8f ||
                                        kotlin.math.abs(change.position.y - down.position.y) > 8f
                                    if (moved) dragged = true
                                    pressedIndex = ((change.position.x - edgePadPx) / slotWidthPx)
                                        .toInt()
                                        .coerceIn(0, points.lastIndex)
                                }
                                // 轻点柱子可固定/取消气泡以便读准数值（§7-66），拖动或滚动接管则收起
                                pinnedIndex = if (!abortedByScroll && !dragged) {
                                    if (pinnedIndex == downIdx) null else downIdx
                                } else {
                                    null
                                }
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
                                showLabel = index % labelEvery == 0 && index <= lastLabelIndex,
                                xLabel = xLabel(point),
                                factor = if (reduceMotion) 1f else local,
                                barColor = resolvedBarColor
                            )
                        }
                    }

                    activeIndex?.let { index ->
                        val point = points.getOrNull(index) ?: return@let
                        val bubbleText = when (point.coverage) {
                            Coverage.RECORDED -> bubbleLabel(point)
                            Coverage.GAP -> "${xLabel(point)} · 采集缺口"
                            Coverage.NO_RECORD -> "${xLabel(point)} · 无记录"
                        }
                        val bubbleWidth = 140.dp
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
                                .semantics { contentDescription = bubbleText }
                        ) {
                            Text(
                                text = bubbleText,
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

/**
 * 单个槽位：柱区（固定 plotHeight）+ 标签行（固定 labelRowHeight）上下堆叠。
 *
 * **为什么不用 offset 把标签「贴」到底部外面**：之前是给标签
 * `align(BottomCenter).offset(y = labelRowHeight - 12.dp)`，但 offset 只改绘制位置、
 * 不改测量尺寸——外层 Box 高度仍是 plotHeight，标签被画到柱区里面，
 * 于是「柱子压到文字上、还整体偏了一截」（bug 8）。
 * 换成两个等高槽位后，柱底与标签各自待在属于自己的那一段，互不重叠。
 */
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
    factor: Float,
    barColor: Color
) {
    val palette = LocalFreshPalette.current
    Column(
        modifier = Modifier.width(slotWidth),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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
                            .background(barColor)
                    )
                }
            }
        }
        // 标签行：固定高度 + 顶部对齐，标签宽度可以溢出槽位（requiredWidth 解除
        // 槽宽约束），但**高度**不外溢，所以不会压到柱子上
        Box(
            modifier = Modifier.height(labelRowHeight),
            contentAlignment = Alignment.TopCenter
        ) {
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
                    // requiredWidth 而不是 width：普通 width 会被父 Box 的 maxWidth(=槽宽)
                    // 反过来夹住，标签又变回「被裁」（这就是评审 P1-2 的成因）
                    modifier = Modifier.requiredWidth(labelWidth)
                )
            }
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
