package com.screentrace.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.screentrace.app.contract.AppCategory
import com.screentrace.app.contract.AppUsage
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.Summary
import com.screentrace.app.contract.UsageCalculator
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.uikit.FreshCard
import com.screentrace.app.theme.ScreenTimeTheme
import com.screentrace.app.uikit.EmptyState
import com.screentrace.app.uikit.ErrorRetry
import com.screentrace.app.uikit.LoadingSkeleton
import com.screentrace.app.uikit.MetricValue
import com.screentrace.app.uikit.MetricValueText
import com.screentrace.app.uikit.SecondaryButton
import com.screentrace.app.uikit.Segment
import com.screentrace.app.uikit.SegmentedBar

/**
 * 排行页（§5.7，二级页）：① 摘要卡（单日/区间累计、应用数、分类构成 + 名称/百分比图例）
 * → ② 列表按 minutes 降序默认 top 20（42dp 首字母占位图标——真实图标批 6b 起由
 * PackageManager 提供、9dp 进度条）→ ③「显示全部 N 个应用」真实展开↔收起。
 * 应用详情为可选增强（§5.7），首版不做——列表行不带点击（§6 禁假按钮）。
 */
@Composable
fun RankingScreen(
    state: LoadState<RankingData>,
    onRetry: () -> Unit
) {
    // 「有数据就画数据」（批 6g，理由见 TodayScreen）：换区间重算不闪骨架
    when {
        state.data != null && state.data.apps.isNotEmpty() -> RankingContent(data = state.data)
        state.loading -> LoadingSkeleton()
        state.error != null -> ErrorRetry(message = state.error, onRetry = onRetry)
        else -> EmptyState(
            title = "暂无应用数据",
            description = "完成首次拉取后，这里会显示应用使用排行"
        )
    }
}

@Composable
private fun RankingContent(data: RankingData) {
    val palette = LocalFreshPalette.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val apps = data.apps
    val shown = if (expanded) apps else apps.take(20)
    val maxMinutes = apps.maxOf { it.minutes }.coerceAtLeast(1)

    // ① 摘要卡
    // 评审 P0-1 连带项：降级通道没有 day_summary，summary.totalScreenUnlockMin 恒为 0。
    // 照读它就是在屏幕上画一个「当天 0 分钟」的假 0（§5.5 红线），
    // 所以这时换成真实可算的「应用使用合计」并换标题说明口径。
    val dayAggregatesAvailable = data.summary.hasAnyRecord
    FreshCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = when {
                // 降级通道的三条边界都要写在标题里，否则用户会把「少」当成「算错」：
                // ① 没有锁屏/通知事件；② 只有应用级数据没有日聚合；
                // ③ **只含当前空间**——系统 `UsageStatsManager` 查不到分身/隐私空间，
                //    真机实测 11-10 本空间 2 分钟 vs 分身整天 765 分钟。
                !dayAggregatesAvailable -> "应用使用累计（降级通道，不含锁屏统计，仅当前空间）"
                data.summary.selection is DateSelection.Day -> "当日累计"
                else -> "区间累计"
            },
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
        MetricValueText(
            value = MetricValue.Duration(
                if (dayAggregatesAvailable) data.summary.totalScreenUnlockMin else data.appsTotalMinutes
            ),
            numberColor = palette.onSurface,
            unitColor = palette.onSurfaceMuted
        )
        Text(
            text = "共 ${apps.size} 个应用",
            style = FreshType.body,
            color = palette.onSurface
        )
        // 分类构成：名称 + 百分比图例（颜色非唯一区分，§5.4）
        val byCategory = apps
            .groupBy { it.category }
            .mapValues { (_, rows) -> rows.sumOf { it.minutes } }
            .toList()
            .sortedByDescending { it.second }
        val total = byCategory.sumOf { it.second }.coerceAtLeast(1)
        val segments = byCategory.mapIndexed { index, (category, minutes) ->
            Segment(
                weight = minutes.toFloat(),
                color = palette.chartList()[index % 5],
                label = "${category.label} ${minutes * 100 / total}%"
            )
        }
        SegmentedBar(segments = segments)
    }

    // ② 列表
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(palette.surface)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        shown.forEach { app ->
            AppRow(app = app, maxMinutes = maxMinutes)
        }
    }

    // ③ 显示全部 / 收起（真实展开）
    if (apps.size > 20) {
        SecondaryButton(
            text = if (expanded) "收起" else "显示全部 ${apps.size} 个应用",
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun AppRow(app: AppUsage, maxMinutes: Int) {
    val palette = LocalFreshPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp), // 行高 ≥48dp（含 42dp 图标）
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 首字母占位图标（§5.3：真实图标批 6b 起优先，失败回退首字母）
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(palette.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = app.name.firstOrNull()?.uppercase() ?: "?",
                style = FreshType.button,
                color = palette.onSurfaceVariant
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = app.name,
                style = FreshType.body,
                color = palette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(9.dp)
                    .clip(RoundedCornerShape(50.dp))
                    .background(palette.track)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(app.minutes.toFloat() / maxMinutes)
                        .height(9.dp)
                        .clip(RoundedCornerShape(50.dp))
                        .background(palette.chart1)
                )
            }
        }
        Text(
            text = UsageCalculator.formatDuration(app.minutes),
            style = FreshType.label,
            color = palette.onSurfaceVariant,
            textAlign = TextAlign.End
        )
    }
}

private fun com.screentrace.app.theme.FreshPalette.chartList() =
    listOf(chart1, chart2, chart3, chart4, chart5)

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun RankingScreenPreview() {
    ScreenTimeTheme {
        val today = java.time.LocalDate.of(2026, 11, 10)
        val apps = (1..25).map { i ->
            AppUsage(
                date = today,
                pkg = "com.example.app$i",
                name = if (i % 3 == 0) "哔哩哔哩哔哩超长应用名称测试" else "应用$i",
                minutes = 300 - i * 10,
                launches = i * 3,
                category = AppCategory.values()[i % AppCategory.values().size]
            )
        }
        RankingScreen(
            state = LoadState(
                data = RankingData(
                    summary = Summary(
                        selection = DateSelection.lastDays(7, today),
                        totalDays = 7,
                        recordedDays = 7,
                        hasAnyRecord = true,
                        totalScreenUnlockMin = 2100,
                        averageScreenUnlockMin = 300,
                        averageUnlocks = 95,
                        longestSessionMin = 80
                    ),
                    apps = apps,
                    appsTotalMinutes = apps.sumOf { it.minutes }
                )
            ),
            onRetry = {}
        )
    }
}
