package com.screentrace.app.data.pull

import com.screentrace.app.data.db.CollectionStatusValues
import com.screentrace.app.data.prefs.PullFrequency

/**
 * CR-30 阶段 E（§7-67）：数据采集八态枚举。
 *
 * 严格守住两条核心语义红线：
 * 1. **零分钟 ≠ 无数据**：存在记录（`hasAnyData == true`）且当日真实 0 分钟时不得判为 [NO_RECORD]；
 * 2. **无记录 ≠ 采集失败**：空 dump（[CollectionStatusValues.EMPTY]）不得判为 [FAILED]。
 */
enum class DataCollectionState {
    /** 正常：主通道可用、有数据、未过期、无缺口。 */
    NORMAL,

    /** 采集中：拉取任务正在执行。 */
    PULLING,

    /** 部分：降级通道 / R11 PARTIAL / 近 7 天存在拉取缺口 / 仅部分历史。 */
    PARTIAL,

    /** 未授权：Shizuku 与 UsageStats 均未获授权。 */
    UNAUTHORIZED,

    /** 暂无记录：授权正常且未失败，但库内尚无有效记录（区别于真实 0 分钟）。 */
    NO_RECORD,

    /** 过期：距上次成功拉取已超过阈值。 */
    STALE,

    /** 失败：最近一次拉取执行异常或入库失败（不含空 dump）。 */
    FAILED,

    /** 服务不可用：双通道服务均不可用 / Binder 挂起 / 系统服务异常。 */
    SERVICE_UNAVAILABLE
}

/** 主页面轻量状态条的建议快捷动作类型。 */
enum class CollectionQuickAction {
    NONE,
    OPEN_DIAGNOSTICS,
    REQUEST_AUTHORIZATION,
    RETRY_PULL
}

/** 八态仲裁器的全部输入信号（纯值对象，无 Android 依赖，便于 JVM 单测）。 */
data class DataCollectionSignal(
    val pulling: Boolean = false,
    val shizukuStatus: ProviderStatus? = null,
    val usageStatsStatus: ProviderStatus? = null,
    /** 三表（day_summary / app_usage / hour_usage）任一非空；真实 0 分钟有记录日此项为 true。 */
    val hasAnyData: Boolean = false,
    /** 是否存在日聚合（day_summary 非空）；降级通道下为 false。 */
    val hasDaySummary: Boolean = false,
    val latestPullOk: Boolean? = null,
    /** 对应 `PullLogEntity.collectionStatus`（OK / EMPTY / PARTIAL / FAILED / UNAVAILABLE）。 */
    val latestCollectionStatus: String? = null,
    val latestPullNote: String? = null,
    val lastOkPullAtMs: Long? = null,
    val nowMs: Long = 0L,
    val staleThresholdMs: Long = DEFAULT_STALE_THRESHOLD_MS,
    val isPartialHistory: Boolean = false,
    val recentGapDays: Int = 0
) {
    companion object {
        const val DEFAULT_STALE_THRESHOLD_MS: Long = 36L * 60 * 60 * 1000
    }
}

/** 仲裁输出：同时驱动主页面轻量状态条与设置页「关于与诊断」。 */
data class DataCollectionVerdict(
    val state: DataCollectionState,
    val title: String,
    val bannerText: String,
    val detailReason: String,
    /** true = 主页面顶部轻量槽展示；false = 主页面隐藏（仅在设置页「关于与诊断」展示）。 */
    val showOnMainScreen: Boolean,
    val quickAction: CollectionQuickAction,
    val quickActionLabel: String? = null
)

/**
 * 按后台拉取频率动态计算数据「过期」阈值（留足 WorkManager 省电延迟余量）：
 * - 每 6 小时 → 18 小时；
 * - 每 12 小时 → 24 小时；
 * - 每天 → 36 小时。
 */
fun staleThresholdMsFor(frequency: PullFrequency): Long = when (frequency) {
    PullFrequency.SIX_HOURS -> 18L * 60 * 60 * 1000
    PullFrequency.TWELVE_HOURS -> 24L * 60 * 60 * 1000
    PullFrequency.DAILY -> 36L * 60 * 60 * 1000
}

/**
 * 数据采集八态纯函数仲裁器（优先级 1 → 8 严格短路）。
 */
fun resolveDataCollectionState(signal: DataCollectionSignal): DataCollectionVerdict {
    // 1. 采集中（最高优：防止拉取中途闪出「暂无记录」或「失败」）
    if (signal.pulling) {
        return DataCollectionVerdict(
            state = DataCollectionState.PULLING,
            title = "采集中",
            bannerText = "正在同步使用记录…",
            detailReason = "正在从系统读取并解析使用记录，完成后自动刷新页面。",
            showOnMainScreen = true,
            quickAction = CollectionQuickAction.NONE
        )
    }

    val shizuku = signal.shizukuStatus
    val usageStats = signal.usageStatsStatus
    val shizukuOk = shizuku == ProviderStatus.AVAILABLE
    val usageOk = usageStats == ProviderStatus.AVAILABLE
    val anyChannelKnown = shizuku != null || usageStats != null

    val isUnauthorized = anyChannelKnown && !shizukuOk && !usageOk && (
        (shizuku == ProviderStatus.PERMISSION_DENIED || usageStats == ProviderStatus.USAGE_ACCESS_DENIED) &&
            shizuku != ProviderStatus.TEMPORARILY_UNAVAILABLE &&
            usageStats != ProviderStatus.TEMPORARILY_UNAVAILABLE
        )

    // 2. 未授权（双通道均无权限）
    if (isUnauthorized) {
        return DataCollectionVerdict(
            state = DataCollectionState.UNAUTHORIZED,
            title = "未授权",
            bannerText = "未授予数据读取权限",
            detailReason = "Shizuku 与系统「使用情况访问」均未授权，无法读取新的使用记录。",
            showOnMainScreen = true,
            quickAction = CollectionQuickAction.REQUEST_AUTHORIZATION,
            quickActionLabel = "去授权"
        )
    }

    val isServiceUnavailable = (
        anyChannelKnown && !shizukuOk && !usageOk &&
            (shizuku == ProviderStatus.NOT_INSTALLED || shizuku == ProviderStatus.TEMPORARILY_UNAVAILABLE || shizuku == null) &&
            (usageStats == ProviderStatus.NOT_INSTALLED || usageStats == ProviderStatus.TEMPORARILY_UNAVAILABLE || usageStats == null)
        ) || (
        signal.latestCollectionStatus == CollectionStatusValues.UNAVAILABLE && !shizukuOk && !usageOk
        )

    // 3. 服务不可用（非单纯拒绝授权，且双通道服务均不可用）
    if (isServiceUnavailable) {
        return DataCollectionVerdict(
            state = DataCollectionState.SERVICE_UNAVAILABLE,
            title = "服务不可用",
            bannerText = "数据采集服务暂不可用",
            detailReason = signal.latestPullNote?.takeIf { it.isNotBlank() }
                ?: "Shizuku 服务未启动且系统使用情况服务暂不可用，请检查服务状态后重试。",
            showOnMainScreen = true,
            quickAction = CollectionQuickAction.OPEN_DIAGNOSTICS,
            quickActionLabel = "查看诊断"
        )
    }

    // 4. 失败（严格排除 EMPTY：空 dump 属于无记录，绝不算采集失败）
    val isFailed = signal.latestCollectionStatus == CollectionStatusValues.FAILED ||
        (signal.latestPullOk == false && signal.latestCollectionStatus != CollectionStatusValues.EMPTY)
    if (isFailed) {
        val reason = signal.latestPullNote?.takeIf { it.isNotBlank() } ?: "最近一次拉取执行异常"
        val banner = if (signal.hasAnyData) {
            "最近拉取失败，已保留历史数据"
        } else {
            "拉取失败，请检查权限或重试"
        }
        return DataCollectionVerdict(
            state = DataCollectionState.FAILED,
            title = "失败",
            bannerText = banner,
            detailReason = if (signal.hasAnyData) "$reason（已有历史数据未受影响）" else reason,
            showOnMainScreen = true,
            quickAction = CollectionQuickAction.RETRY_PULL,
            quickActionLabel = "重试"
        )
    }

    // 5. 暂无记录（hasAnyData == false；真实 0 分钟有记录日 hasAnyData == true 不会落入此处）
    if (!signal.hasAnyData) {
        return DataCollectionVerdict(
            state = DataCollectionState.NO_RECORD,
            title = "暂无记录",
            bannerText = "暂无使用记录",
            detailReason = "权限状态正常，但本地数据库尚无有效使用记录（系统尚未产生或尚未完成首次有效入库）。",
            showOnMainScreen = false,
            quickAction = CollectionQuickAction.RETRY_PULL,
            quickActionLabel = "立即拉取"
        )
    }

    // 6. 过期（距上次成功拉取超过阈值）
    val lastOk = signal.lastOkPullAtMs
    if (lastOk != null && lastOk > 0L && signal.nowMs > lastOk &&
        signal.nowMs - lastOk > signal.staleThresholdMs
    ) {
        val staleHours = ((signal.nowMs - lastOk) / (60L * 60 * 1000)).coerceAtLeast(1L)
        return DataCollectionVerdict(
            state = DataCollectionState.STALE,
            title = "过期",
            bannerText = "数据已超过 $staleHours 小时未更新",
            detailReason = "距离最近一次成功拉取已超过 $staleHours 小时，建议立即拉取以防系统滚动缓冲覆盖旧记录。",
            showOnMainScreen = true,
            quickAction = CollectionQuickAction.RETRY_PULL,
            quickActionLabel = "立即拉取"
        )
    }

    // 7. 部分（降级通道 / R11 PARTIAL / 近 7 天缺口 / 仅部分历史）
    val degradedToUsageStats = (!shizukuOk && usageOk) || !signal.hasDaySummary
    val isR11Partial = signal.latestCollectionStatus == CollectionStatusValues.PARTIAL
    val hasGaps = signal.recentGapDays > 0
    if (degradedToUsageStats || isR11Partial || hasGaps || signal.isPartialHistory) {
        val (banner, reason, showOnMain) = when {
            degradedToUsageStats -> Triple(
                "已降级为系统使用情况访问（无解锁与通知）",
                "当前仅使用系统「使用情况访问」降级通道，可统计应用与时段，但屏幕会话、解锁与通知需要启用 Shizuku。",
                true
            )
            hasGaps -> Triple(
                "近 7 天存在 ${signal.recentGapDays} 天拉取缺口",
                "近期有拉取失败且当日未补齐的记录缺口，点击查看诊断与缺口日期。",
                true
            )
            isR11Partial -> Triple(
                "部分时段记录不完整，点击查看诊断",
                signal.latestPullNote?.takeIf { it.isNotBlank() }
                    ?: "最近一次拉取仅获取到部分来源数据，部分时段可能不完整。",
                true
            )
            else -> Triple(
                "历史覆盖仍在累积中",
                "系统仅保留约 9–10 天滚动记录，当前已入库天数仍在累积中；后续后台拉取会自动持续补齐。",
                false // 新装前 9 天且主通道正常时主屏隐藏，避免常驻打扰
            )
        }
        return DataCollectionVerdict(
            state = DataCollectionState.PARTIAL,
            title = "部分",
            bannerText = banner,
            detailReason = reason,
            showOnMainScreen = showOnMain,
            quickAction = CollectionQuickAction.OPEN_DIAGNOSTICS,
            quickActionLabel = "查看诊断"
        )
    }

    // 8. 正常
    return DataCollectionVerdict(
        state = DataCollectionState.NORMAL,
        title = "正常",
        bannerText = "数据采集正常",
        detailReason = "Shizuku 主通道与数据入库均正常，屏幕时长、解锁与通知事件完整可用。",
        showOnMainScreen = false,
        quickAction = CollectionQuickAction.NONE
    )
}
