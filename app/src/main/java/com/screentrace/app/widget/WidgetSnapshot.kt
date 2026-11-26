package com.screentrace.app.widget

import com.screentrace.app.contract.MetricDetail
import com.screentrace.app.contract.Summary
import com.screentrace.app.contract.UsageCalculator

enum class WidgetLayoutSize {
    COMPACT_2X2,
    WIDE_4X2
}

/** 响应式尺寸阈值：宽度 ≥ 220dp 展示 4×2（含解锁与通知），否则展示 2×2（仅时长与目标进度）。 */
fun resolveWidgetLayoutSize(widthDp: Float): WidgetLayoutSize =
    if (widthDp >= 220f) WidgetLayoutSize.WIDE_4X2 else WidgetLayoutSize.COMPACT_2X2

/**
 * §5.12 桌面小组件纯展示快照（隐私红线：本模型不含任何应用名或包名字段）。
 */
data class WidgetSnapshot(
    val hasData: Boolean,
    val usageText: String,
    val goalProgress: Float,
    val goalCaption: String,
    val overTarget: Boolean,
    val unlockText: String,
    val notificationText: String
)

fun buildWidgetSnapshot(
    summary: Summary,
    unlockDetail: MetricDetail?,
    notificationDetail: MetricDetail?,
    dailyGoalMin: Int
): WidgetSnapshot {
    if (!summary.hasAnyRecord) {
        return WidgetSnapshot(
            hasData = false,
            usageText = "—",
            goalProgress = 0f,
            goalCaption = "打开 App 拉取数据",
            overTarget = false,
            unlockText = "—",
            notificationText = "—"
        )
    }
    val usageMin = summary.totalScreenUnlockMin.coerceAtLeast(0)
    val usageText = UsageCalculator.formatClock(usageMin)
    val hasGoal = dailyGoalMin > 0
    val rawProgress = if (hasGoal) usageMin.toFloat() / dailyGoalMin.toFloat() else 0f
    val overTarget = hasGoal && usageMin > dailyGoalMin
    val goalCaption = when {
        !hasGoal -> "未设置目标"
        overTarget -> "超目标 ${UsageCalculator.formatDuration(usageMin - dailyGoalMin)}"
        else -> "目标 ${UsageCalculator.formatClock(dailyGoalMin)}"
    }
    val unlockText = if (unlockDetail?.hasEventSource == true) "${unlockDetail.total} 次" else "—"
    val notificationText = if (notificationDetail?.hasEventSource == true) "${notificationDetail.total} 次" else "—"

    return WidgetSnapshot(
        hasData = true,
        usageText = usageText,
        goalProgress = rawProgress.coerceIn(0f, 1f),
        goalCaption = goalCaption,
        overTarget = overTarget,
        unlockText = unlockText,
        notificationText = notificationText
    )
}
