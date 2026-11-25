package com.screentrace.app.data.pull

/**
 * CR-30 阶段 E（§7-67）：双通道状态变化回流纯函数。
 *
 * 负责对比前后两次通道状态快照，识别：
 * 1. Shizuku 断开并降级到 UsageStats（如实提示解锁/通知暂停采集）；
 * 2. 任一通道从不可用/未授权恢复为 [ProviderStatus.AVAILABLE]（自动触发一次补拉，绕过 60 秒失败冷却）。
 */
data class ChannelTransition(
    val shizukuDisconnected: Boolean,
    val degradedToUsageStats: Boolean,
    val recoveredForAutoPull: Boolean,
    val noticeMessage: String? = null
)

fun evaluateChannelTransition(
    previous: List<Pair<PullChannel, ProviderStatus>>,
    current: List<Pair<PullChannel, ProviderStatus>>,
    previousState: DataCollectionState?
): ChannelTransition {
    val prevShizuku = previous.firstOrNull { it.first == PullChannel.SHIZUKU }?.second
    val currShizuku = current.firstOrNull { it.first == PullChannel.SHIZUKU }?.second
    val prevUsage = previous.firstOrNull { it.first == PullChannel.USAGE_STATS }?.second
    val currUsage = current.firstOrNull { it.first == PullChannel.USAGE_STATS }?.second

    val shizukuDisconnected = prevShizuku == ProviderStatus.AVAILABLE &&
        currShizuku != null && currShizuku != ProviderStatus.AVAILABLE
    val degradedToUsageStats = shizukuDisconnected && currUsage == ProviderStatus.AVAILABLE

    val shizukuRecovered = prevShizuku != null &&
        prevShizuku != ProviderStatus.AVAILABLE &&
        currShizuku == ProviderStatus.AVAILABLE

    val usageRecovered = prevUsage != null &&
        prevUsage != ProviderStatus.AVAILABLE &&
        currUsage == ProviderStatus.AVAILABLE &&
        previousState in setOf(
            DataCollectionState.UNAUTHORIZED,
            DataCollectionState.SERVICE_UNAVAILABLE,
            DataCollectionState.NO_RECORD,
            DataCollectionState.FAILED
        )

    val recoveredForAutoPull = shizukuRecovered || usageRecovered

    val notice = when {
        degradedToUsageStats -> "Shizuku 已断开，已降级为系统使用情况访问（解锁与通知暂停采集）"
        shizukuDisconnected && currUsage != ProviderStatus.AVAILABLE ->
            "Shizuku 服务已断开，当前无可用数据通道"
        else -> null
    }

    return ChannelTransition(
        shizukuDisconnected = shizukuDisconnected,
        degradedToUsageStats = degradedToUsageStats,
        recoveredForAutoPull = recoveredForAutoPull,
        noticeMessage = notice
    )
}
