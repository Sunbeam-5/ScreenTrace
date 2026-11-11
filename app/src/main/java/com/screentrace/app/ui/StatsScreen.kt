package com.screentrace.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.screentrace.app.contract.DailyPoint
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.ExtremesInfo
import com.screentrace.app.contract.Summary
import com.screentrace.app.contract.UsageCalculator
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme
import com.screentrace.app.uikit.CapsuleBarChart
import com.screentrace.app.uikit.CompareLine
import com.screentrace.app.uikit.EmptyState
import com.screentrace.app.uikit.ErrorRetry
import com.screentrace.app.uikit.LoadingSkeleton
import com.screentrace.app.uikit.MetricCardPair
import com.screentrace.app.uikit.MetricValue
import com.screentrace.app.uikit.MetricValueText
import com.screentrace.app.uikit.Segment
import com.screentrace.app.uikit.SegmentedBar
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
    resetKey: Any? = null
) {
    val palette = LocalFreshPalette.current

    // ① 趋势卡（日均大数字 + 对比 + 柱图，§5.7 不拆两个头卡）
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(palette.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MetricValueText(
            value = MetricValue.Duration(data.summary.averageScreenUnlockMin),
            numberColor = palette.onSurface,
            unitColor = palette.onSurfaceMuted
        )
        CompareLine(text = data.compare.text, up = data.compare.increase)
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
        CapsuleBarChart(
            points = data.trend,
            topAppsPerDay = data.topAppsPerDay,
            resetKey = resetKey
        )
    }

    // ② 时段分布卡
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(palette.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "时段分布", style = FreshType.sectionTitle, color = palette.onSurface)
        val labels = listOf("06–12", "12–18", "18–22", "22–06")
        val colors = listOf(palette.chart1, palette.chart2, palette.chart3, palette.chart4)
        val segments = data.periods.mapIndexed { index, minutes ->
            Segment(
                weight = minutes.toFloat(),
                color = colors[index],
                label = "${labels[index]} · ${UsageCalculator.formatDuration(minutes)}"
            )
        }
        if (segments.all { it.weight <= 0f }) {
            EmptyState(title = "暂无时段数据")
        } else {
            SegmentedBar(segments = segments)
        }
    }

    // ③ 最久 / 最少的有记录日（§5.7）
    MetricCardPair(
        leftLabel = "最久的一天",
        leftValue = MetricValue.Duration(data.extremes.longestMinutes),
        leftNote = data.extremes.longestDate?.let { "${it.monthValue}月${it.dayOfMonth}日" },
        rightLabel = "最少的一天",
        rightValue = MetricValue.Duration(data.extremes.shortestMinutes),
        rightNote = data.extremes.shortestDate?.let { "${it.monthValue}月${it.dayOfMonth}日" }
    )

    // 洞察：数据足够才显示（§5.7）
    if (data.summary.recordedDays >= 3 && data.extremes.longestDate != null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(palette.surface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
