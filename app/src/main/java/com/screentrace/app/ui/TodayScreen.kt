package com.screentrace.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.screentrace.app.contract.AppCategory
import com.screentrace.app.contract.AppUsage
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.MetricDetail
import com.screentrace.app.contract.Summary
import com.screentrace.app.contract.UsageCalculator
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme
import com.screentrace.app.uikit.CompareLine
import com.screentrace.app.uikit.EmptyState
import com.screentrace.app.uikit.ErrorRetry
import com.screentrace.app.uikit.LoadingSkeleton
import com.screentrace.app.uikit.MetricCardPair
import com.screentrace.app.uikit.MetricValue
import com.screentrace.app.uikit.RatioBar
import com.screentrace.app.uikit.RingProgress
import com.screentrace.app.uikit.SecondaryButton
import com.screentrace.app.uikit.Segment
import com.screentrace.app.uikit.SegmentedBar

/**
 * 今日页（§5.7 CR-01 版）：① hero 圆环卡（H:MM / 目标进度 / 对比，副信息含最长连续）
 * → ② tonal 双卡「解锁次数 / 通知打扰」（区间态「日均解锁 / 日均通知打扰」，
 * 通知卡副行「已查看 X 次」）→ ③ 应用构成卡（top4+其他、日间/夜间比值条、
 * 「查看全部应用」进排行）。双卡按 CR-01 **统一样式的可下钻卡**渲染（箭头 + ripple +
 * 读屏「标签 + 当前值 + 查看详情」），分别进入解锁详情 / 通知详情（§7-16）；
 * 其余卡片（hero 圆环、应用构成）保持纯摘要样式，不显示箭头。
 * 空数据用统一 EmptyState（§5.5），不渲染假 0 值。
 */
@Composable
fun TodayScreen(
    state: LoadState<TodayData>,
    dailyGoalMin: Int,
    onOpenRanking: () -> Unit,
    onRetry: () -> Unit,
    onOpenUnlock: () -> Unit = {},
    onOpenNotification: () -> Unit = {},
    /** 正在拉取（首次 dump 要十几秒）。此时「没数据」是暂时的，不能显示「尚无使用记录」。 */
    pulling: Boolean = false
) {
    when {
        state.loading -> LoadingSkeleton()
        state.error != null -> ErrorRetry(message = state.error, onRetry = onRetry)
        state.data == null && pulling -> PullingState()
        state.data == null -> DataEmptyState(
            reason = state.empty,
            noDataTitle = "尚无使用记录",
            noDataDescription = "连接 Shizuku 并完成首次拉取后，这里会显示今日的使用数据",
            actionText = "查看应用排行",
            onAction = onOpenRanking
        )
        else -> TodayContent(
            data = state.data,
            dailyGoalMin = dailyGoalMin,
            onOpenRanking = onOpenRanking,
            onOpenUnlock = onOpenUnlock,
            onOpenNotification = onOpenNotification
        )
    }
}

@Composable
private fun TodayContent(
    data: TodayData,
    dailyGoalMin: Int,
    onOpenRanking: () -> Unit,
    onOpenUnlock: () -> Unit,
    onOpenNotification: () -> Unit
) {
    val palette = LocalFreshPalette.current
    val single = data.summary.selection is DateSelection.Day
    val usageMin =
        if (single) data.summary.totalScreenUnlockMin else data.summary.averageScreenUnlockMin

    // ① hero 圆环卡
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(palette.surface)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RingProgress(
            progress = if (dailyGoalMin <= 0) 0f else usageMin.toFloat() / dailyGoalMin,
            timeText = UsageCalculator.formatClock(usageMin),
            caption = "小时 : 分钟"
        )
        CompareLine(text = data.compare.text, up = data.compare.increase)
        Text(
            text = "最长连续 ${UsageCalculator.formatDuration(data.summary.longestSessionMin)}",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
    }

    // ② tonal 双卡（CR-01 可下钻：箭头 + ripple + 读屏描述，两卡样式一致）
    val notification = data.notification
    // §7-16：卡片数字必须与详情页同源（读 metricDetail），不另用 Summary 算一套。
    // 评审 P1-1：`total == 0` 有两种含义，靠 `hasEventSource` 分开——
    // 没有事件源（降级通道 / 该机型不产生锁屏事件）时渲染「—」，绝不渲染「0 次」。
    val unlockValue = cardValue(data.unlock, single)
    val notificationValue = cardValue(notification, single)
    val unlockAvailable = data.unlock?.hasEventSource == true
    val notificationAvailable = notification?.hasEventSource == true
    val unlockLabel = if (single) "解锁次数" else "日均解锁"
    val notificationLabel = if (single) "通知打扰" else "日均通知打扰"
    MetricCardPair(
        leftLabel = unlockLabel,
        leftValue = MetricValue.Number(unlockValue),
        rightLabel = notificationLabel,
        rightValue = MetricValue.Number(notificationValue),
        // 没有事件源时「已查看 0 次」同样是假 0，一并收掉
        rightNote = notification?.notification
            ?.takeIf { notificationAvailable }
            ?.let { "已查看 ${it.seen} 次" },
        leftOnClick = onOpenUnlock,
        rightOnClick = onOpenNotification,
        leftClickDescription = if (unlockAvailable) {
            "$unlockLabel $unlockValue，查看详情"
        } else {
            "$unlockLabel，暂无解锁事件，查看详情"
        },
        rightClickDescription = if (notificationAvailable) {
            "$notificationLabel $notificationValue，查看详情"
        } else {
            "$notificationLabel，暂无通知事件，查看详情"
        }
    )

    // ③ 应用构成卡
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(palette.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "应用构成", style = FreshType.sectionTitle, color = palette.onSurface)
        // 口径说明（真机踩到的）：系统给的 `totalTimeUsed` 含前台服务、画中画、
        // 灭屏但可见的 Activity，会比「亮屏时长」多出一截——实测米家 583 分钟 vs
        // 全天 557 分钟。不解释的话，用户只会觉得「单个应用比全天还久，算错了」。
        val topMinutes = data.topApps.firstOrNull()?.minutes ?: 0
        if (topMinutes > data.summary.totalScreenUnlockMin && data.summary.totalScreenUnlockMin > 0) {
            Text(
                text = "应用时长含前台服务与画中画，可能略高于亮屏时长",
                style = FreshType.label,
                color = palette.onSurfaceMuted
            )
        }
        val segments = buildList {
            data.topApps.forEachIndexed { index, app ->
                add(
                    Segment(
                        weight = app.minutes.toFloat(),
                        color = palette.chartList()[index % 5],
                        label = "${app.name} · ${UsageCalculator.formatDuration(app.minutes)}"
                    )
                )
            }
            if (data.otherMinutes > 0) {
                add(
                    Segment(
                        weight = data.otherMinutes.toFloat(),
                        color = palette.chartList()[4],
                        label = "其他 · ${UsageCalculator.formatDuration(data.otherMinutes)}"
                    )
                )
            }
        }
        if (segments.isEmpty()) {
            EmptyState(title = "暂无应用数据")
        } else {
            SegmentedBar(segments = segments)
        }
        Spacer(Modifier.height(4.dp)) // 与比值条的 16dp 分隔（含列间距 12dp）
        RatioBar(leftLabel = "日间", leftPercent = data.dayPercent, rightLabel = "夜间")
        SecondaryButton(
            text = "查看全部应用",
            onClick = onOpenRanking,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun com.screentrace.app.theme.FreshPalette.chartList() =
    listOf(chart1, chart2, chart3, chart4, chart5)

/**
 * 双卡数字的统一取值（评审 P1-1）：**没有事件源就只能给「—」**。
 *
 * 以前 `unlock == null` 时还会退回 `summary.averageUnlocks`，那条回退路径在降级
 * 通道下正好是 0——看起来像「今天解锁 0 次」，实际上是「拿不到」。宁可不给数字。
 */
private fun cardValue(detail: MetricDetail?, single: Boolean): String = when {
    detail == null -> "—"
    !detail.hasEventSource -> "—"
    single -> "${detail.total}"
    else -> "${detail.dailyAverage ?: 0}"
}

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun TodayScreenPreview() {
    ScreenTimeTheme {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TodayScreen(
                state = LoadState(
                    data = TodayData(
                        summary = Summary(
                            selection = DateSelection.today(),
                            totalDays = 1,
                            recordedDays = 1,
                            hasAnyRecord = true,
                            totalScreenUnlockMin = 272,
                            averageScreenUnlockMin = 272,
                            averageUnlocks = 87,
                            longestSessionMin = 72
                        ),
                        compare = UsageCalculator.CompareInfo("较昨日增加 12 分钟", true),
                        notification = null,
                        unlock = null,
                        topApps = listOf(
                            AppUsage(
                                java.time.LocalDate.now(), "tv.danmaku.bili", "哔哩哔哩",
                                130, 20, AppCategory.VIDEO
                            ),
                            AppUsage(
                                java.time.LocalDate.now(), "com.tencent.mm", "微信",
                                82, 40, AppCategory.SOCIAL
                            )
                        ),
                        otherMinutes = 60,
                        dayPercent = 72
                    )
                ),
                dailyGoalMin = 240,
                onOpenRanking = {},
                onRetry = {}
            )
        }
    }
}

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun TodayScreenEmptyPreview() {
    ScreenTimeTheme {
        TodayScreen(state = LoadState(data = null), dailyGoalMin = 240, onOpenRanking = {}, onRetry = {})
    }
}
