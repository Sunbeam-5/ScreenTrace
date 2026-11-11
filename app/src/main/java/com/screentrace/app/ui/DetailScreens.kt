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
import com.screentrace.app.contract.MetricKind
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.ui.detail.MetricDetailPage
import com.screentrace.app.ui.detail.MetricTable
import com.screentrace.app.uikit.AppIcon
import com.screentrace.app.uikit.EmptyState
import com.screentrace.app.uikit.ErrorRetry
import com.screentrace.app.uikit.LoadingSkeleton
import com.screentrace.app.uikit.NumberEnter
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
        // 评审 P1-1：两种空态必须分开说，否则「卡片说 —（拿不到）」跳进来却看到
        // 「该时段没有记录」（听起来像「这段真的 0 次」），同一指标两个说法（§7-16）。
        detail != null -> when {
            !detail.hasEventSource -> EmptyState(
                title = if (detail.metric == MetricKind.NOTIFICATION) {
                    "暂无通知事件"
                } else {
                    "暂无解锁事件"
                },
                // 文案不能说「连接 Shizuku 后再看」——真机上主通道已经在跑，锁屏事件
                // 依然为 0（设备无锁屏/ROM 不广播 KEYGUARD_*）。用户照文案去查授权，
                // 只会白折腾一圈。这里只陈述「当前数据源没抓到」，把可能原因列全。
                description = if (detail.metric == MetricKind.NOTIFICATION) {
                    "当前数据源没有抓到任何通知打扰事件。降级通道（仅「使用情况访问」授权）" +
                        "拿不到通知事件；系统关闭该应用的通知权限时也不会有记录。"
                } else {
                    "当前数据源没有抓到任何锁屏事件。部分机型不产生锁屏事件，" +
                        "设备未设锁屏时也不会有记录，因此无法统计解锁次数。" +
                        "屏幕时长与通知不受影响。"
                }
            )

            // 批 6g：转场交给 AppNav 的二级页覆盖层（右侧滑入 + 交叉淡入），
            // 这里不再套一层 PageEnter——两层淡入叠起来会让内容"慢慢浮上来"，比导航还慢。
            MetricTable.hasAnyRecordedPoint(detail) -> content(detail)

            // 事件源在，只是所选区间落在窗口外 / 当天拉取有缺口
            else -> EmptyState(
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
    // 「拿不到事件」与「真的是 0」必须区分（§7-16）。**走到这里的 `total == 0` 已经是
    // 真实的 0**：没有事件源的情况在 DetailStateHost 就被拦成「暂无解锁事件」了，
    // 所以这里只需要说明「这段时间确实没解锁」，不能再拿机型说事（评审 P1-1）。
    if (detail.total == 0) {
        Text(
            text = "本次区间内没有解锁记录（事件源正常，这段时间确实没有解锁）。",
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
