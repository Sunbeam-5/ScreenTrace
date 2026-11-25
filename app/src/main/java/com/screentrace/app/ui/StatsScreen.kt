package com.screentrace.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import com.screentrace.app.uikit.rememberReduceMotion
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.screentrace.app.contract.DailyPoint
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.ExtremesInfo
import com.screentrace.app.contract.Summary
import com.screentrace.app.contract.UsageCalculator
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.uikit.FreshCard
import com.screentrace.app.theme.ScreenTimeTheme
import com.screentrace.app.uikit.CapsuleBarChart
import com.screentrace.app.uikit.CompareLine
import com.screentrace.app.uikit.EmptyState
import com.screentrace.app.uikit.ErrorRetry
import com.screentrace.app.uikit.LoadingSkeleton
import com.screentrace.app.uikit.MetricCardPair
import com.screentrace.app.uikit.MetricValue
import com.screentrace.app.uikit.MetricValueText
import com.screentrace.app.uikit.PeriodDistribution
import com.screentrace.app.uikit.TrendChartMath
import com.screentrace.app.uikit.shouldShowCompare
import com.screentrace.app.uikit.transformSource
import com.screentrace.app.uikit.SegmentedControl
import java.time.LocalDate

/**
 * 统计页（§5.7）：① 趋势卡一体（日均大数字 + 对比 + CapsuleBarChart，
 * 近 7/30 天 segmented 与顶部快捷共用 DateSelection）→ ② 时段分布 SegmentedBar
 * （06–12 / 12–18 / 18–22 / 22–06）→ ③ 最久/最少的有记录日双卡 → 洞察
 * （数据足够才显示）。空数据用统一 EmptyState（§5.5）。
 */
@Composable
fun StatsScreen(
    state: LoadState<StatsData>,
    selection: DateSelection,
    onApplySelection: (DateSelection) -> Unit,
    onRetry: () -> Unit,
    /** 切主页面时变化，用于收起柱图浮层（Pager 常驻，组合不销毁）。 */
    resetKey: Any? = null
) {
    com.screentrace.app.uikit.PerfProbe.tick("Compose/Stats")
    // 分支顺序 = 「有数据就画数据」（批 6g）：切「近 7/30 天」时 ViewModel 带着旧
    // data 进入 loading，这里必须先渲染旧内容，等新数据落地直接换值，不闪骨架。
    //
    // CR-15 评审 P2-1：**不套 Crossfade / AnimatedContent**。真机已证实整页淡变会
    // 重建内容分支，MetricBarChart 的 `remember(points) { Animatable(0f) }` 跟着
    // 重建、柱图入场动画从 0 重播，叠加交叉帧半透明就是「闪一帧」——与今日页
    // RingProgress 是同一个成因（见 TodayScreen 的 A1-④ 修订）。去掉后组件不重建，
    // 柱图由自身 Animatable 从旧值平滑变到新值，数字由 RollingNumberText 逐位滚动。
    // ScrollablePage 内层 Column 已是 spacedBy(12.dp)，多根节点间距不需要额外包 Column。
    when {
        state.data != null -> StatsContent(
            data = state.data,
            selection = selection,
            onApplySelection = onApplySelection,
            resetKey = resetKey
        )
        state.loading -> LoadingSkeleton()
        state.error != null -> ErrorRetry(message = state.error, onRetry = onRetry)
        else -> DataEmptyState(
            reason = state.empty,
            noDataTitle = "暂无统计数据",
            noDataDescription = "完成首次拉取后，这里会显示使用趋势与时段分布"
        )
    }
}

@Composable
private fun StatsContent(
    data: StatsData,
    selection: DateSelection,
    onApplySelection: (DateSelection) -> Unit,
    resetKey: Any? = null
) {
    val palette = LocalFreshPalette.current

    // ① 趋势卡（CR-07 §5.2：分段控件 → 日均大数字 + 对比 chip → 固定详情面板/柱图 → 图例）
    FreshCard(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val selectedIndex = when (selection) {
            is DateSelection.Range -> when (selection.days) {
                7 -> 0
                30 -> 1
                else -> -1
            }
            else -> -1
        }
        SegmentedControl(
            items = listOf("近 7 天", "近 30 天"),
            selectedIndex = selectedIndex,
            onSelect = { onApplySelection(DateSelection.lastDays(if (it == 0) 7 else 30)) }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    text = "日均亮屏",
                    style = FreshType.label,
                    color = palette.onSurfaceVariant
                )
                MetricValueText(
                    value = MetricValue.Duration(data.summary.averageScreenUnlockMin),
                    numberColor = palette.onSurface,
                    unitColor = palette.onSurfaceMuted,
                    numberFontSize = 40,
                    // CR-17 ③：时长型在同一 Text 内平滑变值（§5.3 不拆数字与单位）
                    rolling = true
                )
            }
            // 没有上一周期数据时整枚 chip 不显示，不占行、不写「暂无上一周期数据」
            if (shouldShowCompare(data.compare)) {
                CompareLine(text = data.compare.text, up = data.compare.increase)
            }
        }
        CapsuleBarChart(
            points = data.trend,
            topAppsPerDay = data.topAppsPerDay,
            resetKey = resetKey
        )
        // 平均线图例移到图表下方：原先把「平均」二字压在绘图区里会遮柱
        TrendChartMath.averageMinutes(data.trend)?.let { avg ->
            AverageLegend(averageMinutes = avg)
        }
        // CR-28 §7-57：「占用时钟时长」（UNION）独立副指标——与上方「日均亮屏」
        // （总使用时长 SUM）明确区分：双机同时用 1h 时这里是 1h、总时长是 2h。
        if (data.clockOccupiedMin > 0) {
            Text(
                text = "占用时钟时长 ${UsageCalculator.formatDuration(data.clockOccupiedMin)}",
                style = FreshType.label,
                color = palette.onSurfaceVariant
            )
        }
    }

    // ② 时段分布卡（CR-07 §5.2：36dp 四段条 + 2×2 图例）
    FreshCard {
        PeriodDistribution(periods = data.periods)
    }

    // ③ 最久 / 最少的有记录日（§5.7）
    MetricCardPair(
        leftLabel = "最久的一天",
        leftValue = MetricValue.Duration(data.extremes.longestMinutes),
        leftNote = data.extremes.longestDate?.let { dateWithWeekday(it) },
        rightLabel = "最少的一天",
        rightValue = MetricValue.Duration(data.extremes.shortestMinutes),
        rightNote = data.extremes.shortestDate?.let { dateWithWeekday(it) },
        compact = true,
        rolling = true
    )

    // 洞察：数据足够才显示（§5.7）
    if (data.summary.recordedDays >= 3 && data.extremes.longestDate != null) {
        FreshCard(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "洞察", style = FreshType.sectionTitle, color = palette.onSurface)
            FadeText(
                text = "共 ${data.summary.recordedDays} 天有记录，日均 " +
                    "${UsageCalculator.formatDuration(data.summary.averageScreenUnlockMin)}；" +
                    "最久的一天是 ${data.extremes.longestDate.monthValue}月${data.extremes.longestDate.dayOfMonth}日" +
                    "（${UsageCalculator.formatDuration(data.extremes.longestMinutes)}）。",
                style = FreshType.body,
                color = palette.onSurfaceVariant
            )
        }
    }
    // CR-30 §7-64：周期总结入口已统一收敛至日历页，统计页不再重复摆放。
}

/** CR-07 §5.2：平均线图例（虚线示例 + 「日均 X小时Y分」），放在图表下方。 */
@Composable
private fun AverageLegend(averageMinutes: Int) {
    val palette = LocalFreshPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Canvas(modifier = Modifier.width(18.dp).height(2.dp)) {
            drawLine(
                color = palette.primary.copy(alpha = 0.55f),
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
            )
        }
        FadeText(
            text = "日均 ${UsageCalculator.formatDuration(averageMinutes)}",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
    }
}

/**
 * CR-17 ③：只包一个 Text 的轻淡入，用于对比/洞察/平均线这类整句文案。
 * 不套整页容器（A1-④ 已证实那会重播入场动画造成闪一帧）；减少动画直落终值。
 */
@Composable
private fun FadeText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    if (rememberReduceMotion()) {
        Text(text = text, style = style, color = color, modifier = modifier)
        return
    }
    AnimatedContent(
        targetState = text,
        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
        label = "statsFadeText"
    ) { value ->
        Text(text = value, style = style, color = color, modifier = modifier)
    }
}

private val STATS_WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private fun dateWithWeekday(date: java.time.LocalDate): String =
    "${date.monthValue}月${date.dayOfMonth}日 · ${STATS_WEEKDAYS[date.dayOfWeek.value - 1]}"

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun StatsScreenPreview() {
    ScreenTimeTheme {
        val today = LocalDate.of(2026, 11, 10)
        StatsScreen(
            state = LoadState(
                data = StatsData(
                    summary = Summary(
                        selection = DateSelection.lastDays(7, today),
                        totalDays = 7,
                        recordedDays = 6,
                        hasAnyRecord = true,
                        totalScreenUnlockMin = 1680,
                        averageScreenUnlockMin = 280,
                        averageUnlocks = 90,
                        longestSessionMin = 72
                    ),
                    compare = UsageCalculator.CompareInfo("较上一区间减少 8%", false),
                    trend = (0 until 7).map { i ->
                        DailyPoint(
                            today.minusDays(6 - i.toLong()),
                            if (i == 3) 0 else 120 + i * 40,
                            recorded = i != 5
                        )
                    },
                    periods = listOf(120, 180, 95, 210), // CR-15 A1：Preview 也用日均量纲（原 300/600/420/360 是累计量纲）
                    extremes = ExtremesInfo(today.minusDays(2), 420, today.minusDays(4), 60, 1)
                )
            ),
            selection = DateSelection.lastDays(7, today),
            onApplySelection = {},
            onRetry = {}
        )
    }
}
