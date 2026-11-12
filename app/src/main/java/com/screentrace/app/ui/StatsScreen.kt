package com.screentrace.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.screentrace.app.contract.DailyPoint
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.ExtremesInfo
import com.screentrace.app.contract.PeriodType
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
    /** §5.11 入口：进入周期总结（周/月/年）二级页。 */
    onOpenPeriod: (PeriodType) -> Unit = {},
    /** 切主页面时变化，用于收起柱图浮层（Pager 常驻，组合不销毁）。 */
    resetKey: Any? = null
) {
    // 「有数据就画数据」（批 6g，理由见 TodayScreen）：切「近 7/30 天」不闪骨架。
    // 换区间时整卡内容（柱图几何/大数字/时段分布）一帧内替换会被看成「闪」，
    // 用 200ms 交叉淡入淡出把这次替换软化（Crossfade 只在 data 引用真正变化时触发，
    // stale-while-revalidate 期间 data 引用不变，不会白白重播）。
    when {
        state.data != null -> Crossfade(
            targetState = state.data,
            animationSpec = tween(200),
            label = "statsDataSwap",
            modifier = Modifier.fillMaxWidth()
        ) { data ->
            // Crossfade 的内容 lambda 是**单插槽**（内部一个 Box，多个并列子节点全部
            // 叠在 top-start）——StatsContent 有 4 个并列根节点（趋势卡/时段卡/双卡/
            // 洞察），直接放进去四张卡会全部叠在一起（批 6g 真机「统计页被修坏」，
            // 与 6f 时 TodayContent 包 AnimatedContent 是同一个坑）。
            // 这里包一层 Column 复刻 ScrollablePage 的 12dp 卡片间距。
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatsContent(
                    data = data,
                    selection = selection,
                    onApplySelection = onApplySelection,
                    onOpenPeriod = onOpenPeriod,
                    resetKey = resetKey
                )
            }
        }
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
    onOpenPeriod: (PeriodType) -> Unit = {},
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
                    numberFontSize = 40
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
        compact = true
    )

    // 洞察：数据足够才显示（§5.7）
    if (data.summary.recordedDays >= 3 && data.extremes.longestDate != null) {
        FreshCard(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "洞察", style = FreshType.sectionTitle, color = palette.onSurface)
            Text(
                text = "共 ${data.summary.recordedDays} 天有记录，日均 " +
                    "${UsageCalculator.formatDuration(data.summary.averageScreenUnlockMin)}；" +
                    "最久的一天是 ${data.extremes.longestDate.monthValue}月${data.extremes.longestDate.dayOfMonth}日" +
                    "（${UsageCalculator.formatDuration(data.extremes.longestMinutes)}）。",
                style = FreshType.body,
                color = palette.onSurfaceVariant
            )
        }
    }

    // §5.11 入口：本周 / 本月 / 本年总结（按当前日期给出周期名，三段都能直接进）
    PeriodEntryCard(onOpenPeriod = onOpenPeriod)
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
        Text(
            text = "日均 ${UsageCalculator.formatDuration(averageMinutes)}",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
    }
}

private val STATS_WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private fun dateWithWeekday(date: java.time.LocalDate): String =
    "${date.monthValue}月${date.dayOfMonth}日 · ${STATS_WEEKDAYS[date.dayOfWeek.value - 1]}"

/**
 * 周期总结入口卡：一行一个周期，右侧写明当前周期窗口。
 *
 * 为什么把三个入口都摆出来而不是只给「本周总结」：用户想看的往往是「本月怎么样」，
 * 进去再切一次周期就是多余的一步；三行各自写明日期范围，也不会点错。
 */
@Composable
private fun PeriodEntryCard(onOpenPeriod: (PeriodType) -> Unit) {
    val palette = LocalFreshPalette.current
    val today = LocalDate.now()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(palette.surface)
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = "周期总结",
            style = FreshType.sectionTitle,
            color = palette.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        PeriodType.entries.forEach { type ->
            val range = type.natural(today)
            val span = "${range.start.monthValue}月${range.start.dayOfMonth}日–" +
                "${range.end.monthValue}月${range.end.dayOfMonth}日"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenPeriod(type) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "${type.label}总结",
                    style = FreshType.body,
                    color = palette.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(text = span, style = FreshType.label, color = palette.onSurfaceVariant)
                Text(text = "›", style = FreshType.sectionTitle, color = palette.onSurfaceVariant)
            }
        }
    }
}

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
                    periods = listOf(300, 600, 420, 360),
                    extremes = ExtremesInfo(today.minusDays(2), 420, today.minusDays(4), 60, 1)
                )
            ),
            selection = DateSelection.lastDays(7, today),
            onApplySelection = {},
            onRetry = {}
        )
    }
}
