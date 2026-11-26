package com.screentrace.app.widget

import com.screentrace.app.contract.HourBucket
import com.screentrace.app.contract.MetricDetail
import com.screentrace.app.contract.Summary
import com.screentrace.app.contract.UsageCalculator
import com.screentrace.app.uikit.AppBreakdownMath

enum class WidgetLayoutSize {
    COMPACT_2X2,
    WIDE_4X2,
    LARGE_4X4
}

/**
 * 响应式尺寸阈值：
 * - widthDp >= 220f && heightDp >= 200f → LARGE_4X4
 * - widthDp >= 220f → WIDE_4X2
 * - 其余 → COMPACT_2X2
 */
fun resolveWidgetLayoutSize(widthDp: Float, heightDp: Float = 110f): WidgetLayoutSize = when {
    widthDp >= 220f && heightDp >= 200f -> WidgetLayoutSize.LARGE_4X4
    widthDp >= 220f -> WidgetLayoutSize.WIDE_4X2
    else -> WidgetLayoutSize.COMPACT_2X2
}

/** 24 小时柱图纯几何模型（bars 恒为 24 根，取值 0f..1f）。 */
data class WidgetChartModel(
    val bars: List<Float>,
    val ceilingMinutes: Int,
    val midMinutes: Int
)

/**
 * 纯函数：把 0..23 小时桶归一化为 24 根胶囊柱高度（0f..1f）。
 * - 峰值为 0 时全柱 0f，刻度维持 60m / 30m 骨架；
 * - 峰值 <= 60m 时刻度为 60m / 30m；超过 60m（如跨来源叠加）按 30m 向上取整封顶，柱高严格钳制在 0f..1f。
 */
fun buildHourlyChartModel(hourlyBuckets: List<HourBucket>): WidgetChartModel {
    val minutesByHour = IntArray(24)
    hourlyBuckets.forEach { bucket ->
        if (bucket.hour in 0..23) {
            minutesByHour[bucket.hour] = (minutesByHour[bucket.hour] + bucket.minutes).coerceAtLeast(0)
        }
    }
    val peak = minutesByHour.maxOrNull() ?: 0
    val ceiling = if (peak <= 60) 60 else ((peak + 29) / 30) * 30
    val bars = if (peak <= 0) {
        List(24) { 0f }
    } else {
        minutesByHour.map { (it.toFloat() / ceiling.toFloat()).coerceIn(0f, 1f) }
    }
    return WidgetChartModel(
        bars = bars,
        ceilingMinutes = ceiling,
        midMinutes = ceiling / 2
    )
}

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
    val notificationText: String,
    val hourlyMinutes: List<Int> = List(24) { 0 },
    val chartModel: WidgetChartModel = buildHourlyChartModel(emptyList()),
    val dayPercent: Int? = null,
    val dayNightCaption: String = "日/夜比 —"
)

fun buildWidgetSnapshot(
    summary: Summary,
    unlockDetail: MetricDetail?,
    notificationDetail: MetricDetail?,
    dailyGoalMin: Int,
    hourlyBuckets: List<HourBucket> = emptyList()
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

    val minutesByHour = IntArray(24)
    hourlyBuckets.forEach { bucket ->
        if (bucket.hour in 0..23) {
            minutesByHour[bucket.hour] = (minutesByHour[bucket.hour] + bucket.minutes).coerceAtLeast(0)
        }
    }
    val totalHourlyMin = minutesByHour.sum()
    val nightMin = UsageCalculator.nightMinutes(hourlyBuckets)
    val dayPct = AppBreakdownMath.dayPercent(totalHourlyMin, nightMin)
    val split = AppBreakdownMath.dayNightSplit(dayPct)
    val dayNightCaption = if (split != null) {
        "日 ${split.dayPercent}% · 夜 ${split.nightPercent}%"
    } else {
        "日/夜比 —"
    }

    return WidgetSnapshot(
        hasData = true,
        usageText = usageText,
        goalProgress = rawProgress.coerceIn(0f, 1f),
        goalCaption = goalCaption,
        overTarget = overTarget,
        unlockText = unlockText,
        notificationText = notificationText,
        hourlyMinutes = minutesByHour.toList(),
        chartModel = buildHourlyChartModel(hourlyBuckets),
        dayPercent = dayPct,
        dayNightCaption = dayNightCaption
    )
}
