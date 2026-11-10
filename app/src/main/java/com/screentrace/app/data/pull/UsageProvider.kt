package com.screentrace.app.data.pull

/**
 * 数据通道抽象（§2.1）：主通道 Shizuku 与降级通道 UsageStatsManager 都必须实现它，
 * 这样 [PullCoordinator] 的通道优先级/降级逻辑可以被 JVM 单测覆盖，不需要真机。
 */
interface UsageProvider {

    val channel: PullChannel

    /** 授权状态探测。**不得抛异常**——探测失败一律返回 [ProviderStatus.TEMPORARILY_UNAVAILABLE]。 */
    suspend fun status(): ProviderStatus

    /**
     * 读取全部可用数据。返回 [LoadedUsage.Failed] 时调用方**不得**改动本地库；
     * 返回 [LoadedUsage.Parsed] 时由 [PullCoordinator] 交给入库管道合并。
     */
    suspend fun load(): LoadedUsage
}
