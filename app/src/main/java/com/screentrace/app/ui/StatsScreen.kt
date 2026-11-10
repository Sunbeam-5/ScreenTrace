package com.screentrace.app.ui

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
    onRetry: () -> Unit
) {
    when {
        state.loading -> LoadingSkeleton()
        state.error != null -> ErrorRetry(message = state.error, onRetry = onRetry)
        state.data == null -> EmptyState(
            title = "暂无统计数据",
            description = "完成首次拉取后，这里会显示使用趋势与时段分布"
        )
        else -> StatsContent(data = state.data, selection = selection, onApplySelection = onApplySelection)
    }
}

@Composable
private fun StatsContent(
    data: StatsData,
    selection: DateSelection,
    onApplySelection: (DateSelection) -> Unit
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
        CapsuleBarChart(points = data.trend)
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
