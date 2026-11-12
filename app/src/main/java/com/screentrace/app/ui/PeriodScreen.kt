package com.screentrace.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.screentrace.app.contract.AppUsage
import com.screentrace.app.contract.DailyPoint
import com.screentrace.app.contract.PeriodSummary
import com.screentrace.app.contract.PeriodSummaryCalculator
import com.screentrace.app.contract.PeriodType
import com.screentrace.app.contract.UsageCalculator
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.uikit.FreshCard
import com.screentrace.app.theme.ScreenTimeTheme
import com.screentrace.app.uikit.AppIcon
import com.screentrace.app.uikit.CapsuleBarChart
import com.screentrace.app.uikit.CompareLine
import com.screentrace.app.uikit.EmptyState
import com.screentrace.app.uikit.ErrorRetry
import com.screentrace.app.uikit.LoadingSkeleton
import com.screentrace.app.uikit.MetricCardPair
import com.screentrace.app.uikit.MetricValue
import com.screentrace.app.uikit.MetricValueText
import com.screentrace.app.uikit.MonthlyBarChart
import com.screentrace.app.uikit.Segment
import com.screentrace.app.uikit.SegmentedBar
import com.screentrace.app.uikit.SegmentedControl
import kotlinx.coroutines.delay
import java.time.LocalDate

/**
 * §5.11 / §7-27 周期总结页（二级页，容器变换转场由 AppNav 统一处理）。
 *
 * 顺序固定（一页读完，不堆卡片）：
 * ① 周/月/年切换 → ② hero（总时长 + 日均 + 对比 + 「未结束」标识）→ ③ 趋势图
 * （周 7 根 / 月 28–31 根 / 年 12 根月柱）→ ④ 最长/最短双卡 → ⑤ Top5 应用
 * → ⑥ 时段占比 → ⑦ 规则生成的结论 → ⑧ 覆盖说明 + 复制文本。
 *
 * 页面**不自己算数字**：所有值都来自 [PeriodSummary]（单一来源），导出文本也由
 * `PeriodSummaryCalculator.toPlainText` 生成，保证「页内数字 = 导出数字」（§7-27）。
 */
@Composable
fun PeriodScreen(
    state: LoadState<PeriodSummary>,
    onTypeChange: (PeriodType) -> Unit,
    onOpenAllApps: (PeriodSummary) -> Unit,
    onRetry: () -> Unit,
    /** 切周期 / 切页时清掉柱图浮层（Pager 常驻，组合不销毁）。 */
    resetKey: Any? = null
) {
    when {
        state.data != null -> Crossfade(
            targetState = state.data,
            animationSpec = tween(200),
            label = "periodDataSwap",
            modifier = Modifier.fillMaxWidth()
        ) { period ->
            // Crossfade 内容是**单插槽**（内部一个 Box），多根节点会全部叠在 top-start
            // （本项目已踩三次：TodayContent / StatsContent / DateSheet）。必须包 Column。
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PeriodContent(
                    period = period,
                    onTypeChange = onTypeChange,
                    onOpenAllApps = onOpenAllApps,
                    resetKey = resetKey
                )
            }
        }
        state.loading -> LoadingSkeleton()
        state.error != null -> ErrorRetry(message = state.error, onRetry = onRetry)
        else -> EmptyState(
            title = "暂无周期数据",
            description = "完成首次拉取后，这里会按周/月/年汇总使用情况"
        )
    }
}

@Composable
private fun PeriodContent(
    period: PeriodSummary,
    onTypeChange: (PeriodType) -> Unit,
    onOpenAllApps: (PeriodSummary) -> Unit,
    resetKey: Any? = null
) {
    val palette = LocalFreshPalette.current

    // ① 周期切换。选中态读 **period.type**（数据自带的类型）而不是入参 `type`：
    // 切周期时数据有一小段时间还是旧的，若选中态先跳过去、图表还是旧数据，
    // 就会出现「选中年、画的却是月柱」的半帧错位。等新数据落地两者自然一致。
    SegmentedControl(
        items = listOf("周", "月", "年"),
        selectedIndex = period.type.ordinal,
        onSelect = { index -> onTypeChange(PeriodType.entries[index]) }
    )

    // ② hero：总时长 + 日均 + 对比 + 未结束标识
    FreshCard(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = period.title,
                style = FreshType.sectionTitle,
                color = palette.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (period.range.unfinished) {
                Text(
                    text = "未结束",
                    style = FreshType.label,
                    color = palette.onSecondaryContainer,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(palette.secondaryContainer)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
        Text(
            text = period.subtitle,
            style = FreshType.label,
            color = palette.onSurfaceMuted
        )
        MetricValueText(
            value = MetricValue.Duration(period.summary.totalScreenUnlockMin),
            numberColor = palette.onSurface,
            unitColor = palette.onSurfaceMuted
        )
        Text(
            text = "日均 ${UsageCalculator.formatDuration(period.summary.averageScreenUnlockMin)}",
            style = FreshType.body,
            color = palette.onSurface
        )
        CompareLine(text = period.compare.text, up = period.compare.increase)
    }

    // ③ 趋势：年视图是 12 根「月日均」柱，周/月复用统一量程的日柱图
    FreshCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = if (period.type == PeriodType.YEAR) "每月日均" else "每日使用",
            style = FreshType.sectionTitle,
            color = palette.onSurface
        )
        if (period.type == PeriodType.YEAR) {
            MonthlyBarChart(months = period.monthTrend, resetKey = resetKey)
        } else {
            CapsuleBarChart(points = period.trend, resetKey = resetKey)
        }
    }

    // ④ 最长 / 最短的一天
    MetricCardPair(
        leftLabel = "最长的一天",
        leftValue = MetricValue.Duration(period.extremes.longestMinutes),
        leftNote = period.extremes.longestDate?.let {
            "${it.monthValue}月${it.dayOfMonth}日 · ${PeriodSummaryCalculator.weekdayLabel(it)}"
        },
        rightLabel = "最短的一天",
        rightValue = MetricValue.Duration(period.extremes.shortestMinutes),
        rightNote = period.extremes.shortestDate?.let {
            "${it.monthValue}月${it.dayOfMonth}日 · ${PeriodSummaryCalculator.weekdayLabel(it)}"
        }
    )

    // ⑤ 应用 Top5（点击「查看全部」带周期口径进排行，§7-26）
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(palette.surface)
            .padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "应用 Top${period.topApps.size.coerceAtLeast(1)}",
                style = FreshType.sectionTitle,
                color = palette.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "查看全部",
                style = FreshType.button,
                color = palette.primary,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onOpenAllApps(period) }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
        if (period.topApps.isEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                EmptyState(title = "本周期暂无应用数据")
            }
        } else {
            val maxMinutes = period.topApps.maxOf { it.minutes }.coerceAtLeast(1)
            period.topApps.forEach { app ->
                PeriodAppRow(
                    app = app,
                    maxMinutes = maxMinutes,
                    percent = if (period.appTotalMinutes <= 0) {
                        0
                    } else {
                        app.minutes * 100 / period.appTotalMinutes
                    }
                )
            }
        }
    }

    // ⑥ 时段占比
    FreshCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "时段分布", style = FreshType.sectionTitle, color = palette.onSurface)
        val labels = listOf("06–12", "12–18", "18–22", "22–06")
        val colors = listOf(palette.chart1, palette.chart2, palette.chart3, palette.chart4)
        val segments = period.periods.mapIndexed { index, minutes ->
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

    // ⑦ 结论：规则生成的事实陈述；数据不足时明确说「不出结论」，不给假洞察
    FreshCard(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "结论", style = FreshType.sectionTitle, color = palette.onSurface)
        if (period.insufficient) {
            Text(
                text = "本周期只有 ${period.summary.recordedDays} 天有记录，数据不足以得出结论。" +
                    "继续使用几天后这里会自动出现小结。",
                style = FreshType.body,
                color = palette.onSurfaceVariant
            )
        } else {
            period.insights.forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "·", style = FreshType.body, color = palette.primary)
                    Text(text = line, style = FreshType.body, color = palette.onSurfaceVariant)
                }
            }
        }
    }

    // ⑧ 覆盖说明 + 复制文本（导出与页内同源）
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(palette.surfaceVariant)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(text = "数据覆盖", style = FreshType.sectionTitle, color = palette.onSurface)
        Text(
            text = "有记录 ${period.summary.recordedDays} 天 / 共 ${period.summary.totalDays} 天" +
                if (period.gapDays > 0) {
                    "（${period.gapDays} 天无数据，未计入日均）"
                } else {
                    ""
                },
            style = FreshType.body,
            color = palette.onSurfaceVariant
        )
        CopySummaryButton(period = period)
    }
}

/** 行内进度条与图标复用排行页同款规格（42dp → 34dp，Top5 更紧凑）。 */
@Composable
private fun PeriodAppRow(app: AppUsage, maxMinutes: Int, percent: Int) {
    val palette = LocalFreshPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppIcon(pkg = app.pkg, name = app.name, size = 34.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    .height(7.dp)
                    .clip(RoundedCornerShape(50.dp))
                    .background(palette.track)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(app.minutes.toFloat() / maxMinutes)
                        .height(7.dp)
                        .clip(RoundedCornerShape(50.dp))
                        .background(palette.chart1)
                )
            }
        }
        Text(
            text = "${UsageCalculator.formatDuration(app.minutes)} · $percent%",
            style = FreshType.label,
            color = palette.onSurfaceVariant,
            textAlign = TextAlign.End
        )
    }
}

/** 「复制总结」：写剪贴板后 2 秒内把按钮文案改成「已复制」，不弹 Toast（§5.5）。 */
@Composable
private fun CopySummaryButton(period: PeriodSummary) {
    val palette = LocalFreshPalette.current
    val context = LocalContext.current
    var copied by remember(period) { mutableStateOf(false) }
    if (copied) {
        LaunchedEffect(period) {
            delay(2_000)
            copied = false
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(if (copied) palette.secondaryContainer else palette.surface)
            .clickable {
                val text = PeriodSummaryCalculator.toPlainText(period)
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("屏迹 · ${period.title}总结", text))
                copied = true
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (copied) "已复制到剪贴板" else "复制总结文本",
            style = FreshType.button,
            color = if (copied) palette.onSecondaryContainer else palette.primary
        )
    }
}

/** 空态的小内边距容器（避免把 EmptyState 直接塞进 Row 约束里）。 */
@Composable
private fun Box_Padding(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { content() }
}

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun PeriodScreenPreview() {
    ScreenTimeTheme {
        val today = LocalDate.of(2026, 11, 12)
        val trend = (0 until 4).map { i ->
            DailyPoint(today.minusDays(3 - i.toLong()), 120 + i * 60, recorded = true)
        }
        val apps = (1..5).map {
            AppUsage(today, "com.example.$it", "应用$it", 200 - it * 20, 3, com.screentrace.app.contract.AppCategory.OTHER)
        }
        val period = PeriodSummaryCalculator.build(
            type = PeriodType.WEEK,
            today = today,
            trend = trend,
            previousSummary = null,
            apps = apps,
            hours = emptyList()
        )
        PeriodScreen(
            state = LoadState(data = period),
            onTypeChange = {},
            onOpenAllApps = {},
            onRetry = {}
        )
    }
}
