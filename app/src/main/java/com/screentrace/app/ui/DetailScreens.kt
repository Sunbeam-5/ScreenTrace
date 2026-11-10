package com.screentrace.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.MetricAppCount
import com.screentrace.app.contract.MetricDetail
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.ui.detail.MetricDetailPage
import com.screentrace.app.ui.detail.MetricTable
import com.screentrace.app.uikit.AppIcon
import com.screentrace.app.uikit.EmptyState
import com.screentrace.app.uikit.ErrorRetry
import com.screentrace.app.uikit.LoadingSkeleton
import com.screentrace.app.uikit.NumberEnter
import com.screentrace.app.uikit.PageEnter
import com.screentrace.app.uikit.SegmentedControl
import kotlinx.coroutines.delay

/**
 * CR-01 / §5.7 解锁详情页：单日逐小时 / 区间逐日柱图 + 数据表 +
 * 「最高频时段 / 每小时平均 / 最活跃日期」三项摘要。
 *
 * **不显示应用排行**：现有事件流无法把解锁可靠归因到应用，画出来就是伪造（§5.7 明文禁止）。
 */
@Composable
fun UnlockDetailScreen(state: LoadState<MetricDetail>, onRetry: () -> Unit) {
    DetailStateHost(state = state, onRetry = onRetry) { detail ->
        MetricDetailPage(
            detail = detail,
            summary = { UnlockSummary(detail) },
            detailSection = { UnlockInsights(detail) }
        )
    }
}

/**
 * CR-01 / §5.7 通知详情页：打扰 / 已查看双指标 + 柱图 + 数据表 + 应用排行（可切换）。
 *
 * 应用归因来自 UsageStats 事件，**不申请通知监听权限**（§5.7 明文要求）。
 */
@Composable
fun NotificationDetailScreen(state: LoadState<MetricDetail>, onRetry: () -> Unit) {
    DetailStateHost(state = state, onRetry = onRetry) { detail ->
        MetricDetailPage(
            detail = detail,
            summary = { NotificationSummary(detail) },
            detailSection = { NotificationRanking(detail) }
        )
    }
}

/**
 * 状态宿主：加载 / 错误 / 无记录；「一次记录都没有」不当成数据渲染（§5.5 红线）。
 *
 * 两个 §5.7 细节：
 * - **已有缓存直接渲染**：切日期时 ViewModel 保留上一条 [MetricDetail]，这里就继续显示它，
 *   不闪骨架、也不重播入场动画；
 * - **加载超过约 150ms 才显示骨架**：本地库查询通常几十毫秒，先闪一下骨架比空白更糟。
 */
@Composable
private fun DetailStateHost(
    state: LoadState<MetricDetail>,
    onRetry: () -> Unit,
    content: @Composable (MetricDetail) -> Unit
) {
    var skeletonVisible by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading, state.data) {
        if (state.loading && state.data == null) {
            delay(SKELETON_DELAY_MS)
            skeletonVisible = true
        } else {
            skeletonVisible = false
        }
    }

    val detail = state.data
    when {
        detail != null -> if (MetricTable.hasAnyRecordedPoint(detail)) {
            PageEnter { content(detail) }
        } else {
            EmptyState(
                title = "该时段没有记录",
                description = "所选时段在事件累积窗口之外，或当天拉取存在缺口"
            )
        }

        state.error != null -> ErrorRetry(message = state.error, onRetry = onRetry)
        skeletonVisible -> LoadingSkeleton()
        // <150ms 的等待期：什么都不画（骨架一出现就消失比空白更刺眼）
        else -> Box(Modifier.fillMaxWidth().height(1.dp))
    }
}

/** §5.7：骨架延迟阈值（毫秒）。 */
private const val SKELETON_DELAY_MS = 150L

// ---------------- 解锁 ----------------

@Composable
private fun UnlockSummary(detail: MetricDetail) {
    val palette = LocalFreshPalette.current
    val single = detail.selection is DateSelection.Day
    // 区间态主数字用日均（§5.7 摘要区：区间显示「日均 + 区间合计」）
    val main = if (single) detail.total else (detail.dailyAverage ?: 0)
    NumberEnter { SummaryNumber(value = "$main", unit = "次") }
    Text(text = "解锁次数", style = FreshType.label, color = palette.onSurfaceVariant)
    Text(
        text = MetricTable.summaryCaption(detail),
        style = FreshType.body,
        color = palette.onSurfaceVariant
    )
    // 「拿不到事件」与「真的是 0」必须区分，否则不锁屏机型会显示成假 0（§7-16）
    if (detail.total == 0) {
        Text(
            text = "本次区间内没有解锁事件记录。部分机型不产生锁屏事件，或事件流尚未拉取到。",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
    }
}

@Composable
private fun UnlockInsights(detail: MetricDetail) {
    val palette = LocalFreshPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        InsightRow("最高频时段", MetricTable.peakHourText(detail.peakHour))
        InsightRow("每小时平均", MetricTable.hourlyAverageText(detail.hourlyAverage))
        InsightRow("最活跃日期", MetricTable.busiestDateText(detail.busiestDate))
        Text(
            text = "解锁事件无法可靠归因到具体应用，因此这里不提供应用排行。",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
    }
}

@Composable
private fun InsightRow(label: String, value: String) {
    val palette = LocalFreshPalette.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = FreshType.body,
            color = palette.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(text = value, style = FreshType.body, color = palette.onSurface)
    }
}

// ---------------- 通知 ----------------

@Composable
private fun NotificationSummary(detail: MetricDetail) {
    val palette = LocalFreshPalette.current
    val counts = detail.notification
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            NumberEnter { SummaryNumber(value = "${counts?.interruptions ?: detail.total}", unit = "次") }
            Text(text = "打扰", style = FreshType.label, color = palette.onSurfaceVariant)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            NumberEnter { SummaryNumber(value = "${counts?.seen ?: 0}", unit = "次") }
            Text(text = "已查看", style = FreshType.label, color = palette.onSurfaceVariant)
        }
    }
    Text(
        text = MetricTable.summaryCaption(detail),
        style = FreshType.body,
        color = palette.onSurfaceVariant
    )
}

@Composable
private fun NotificationRanking(detail: MetricDetail) {
    val palette = LocalFreshPalette.current
    var bySeen by rememberSaveable { mutableStateOf(false) }
    val sorted = detail.appRanking.sortedByDescending { if (bySeen) it.seen else it.interruptions }
    val maxCount = sorted.maxOfOrNull { if (bySeen) it.seen else it.interruptions } ?: 0

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SegmentedControl(
            items = listOf("打扰", "已查看"),
            selectedIndex = if (bySeen) 1 else 0,
            onSelect = { bySeen = it == 1 }
        )
        if (sorted.isEmpty()) {
            EmptyState(title = "该时段没有通知记录")
        } else {
            sorted.forEach { app ->
                NotificationAppRow(
                    app = app,
                    count = if (bySeen) app.seen else app.interruptions,
                    maxCount = maxCount
                )
            }
            Text(
                text = "统计口径：系统 UsageStats 的通知打扰 / 已查看事件，不申请通知监听权限。",
                style = FreshType.label,
                color = palette.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NotificationAppRow(app: MetricAppCount, count: Int, maxCount: Int) {
    val palette = LocalFreshPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppIcon(pkg = app.pkg, name = app.name, size = 38.dp)
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
                if (maxCount > 0 && count > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(count.toFloat() / maxCount)
                            .height(9.dp)
                            .clip(RoundedCornerShape(50.dp))
                            .background(palette.chart1)
                    )
                }
            }
        }
        Text(text = "$count 次", style = FreshType.label, color = palette.onSurfaceVariant)
    }
}

// ---------------- 共用 ----------------

/** §5.3「数字 + 小单位」：同一段 AnnotatedString，数字 40sp / 单位 14sp。 */
@Composable
private fun SummaryNumber(value: String, unit: String) {
    val palette = LocalFreshPalette.current
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(fontSize = 40.sp, color = palette.onSurface)) { append(value) }
            append(" ")
            withStyle(SpanStyle(fontSize = 14.sp, color = palette.onSurfaceVariant)) { append(unit) }
        },
        style = FreshType.heroNumber,
        maxLines = 1
    )
}
