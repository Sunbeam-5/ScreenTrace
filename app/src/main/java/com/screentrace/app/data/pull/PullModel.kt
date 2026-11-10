package com.screentrace.app.data.pull

/**
 * 拉取通道（§2.1）。`tag` 直接写进 `pull_log.source`，备份与对账排查都按它区分来源。
 *
 * - [SHIZUKU]：以 shell(uid 2000) 身份跑 `dumpsys usagestats`，**唯一能拿到事件流**
 *   （解锁 / 通知打扰 / 已查看）的通道；
 * - [USAGE_STATS]：`UsageStatsManager` 降级通道，只能拿到应用级与时段数据，
 *   **拿不到系统级事件**（`SCREEN_*` / `KEYGUARD_*` 与通知事件对普通 App 不可见）。
 */
enum class PullChannel(val tag: String) {
    SHIZUKU("shizuku"),
    USAGE_STATS("usage-stats")
}

/** 通道可用性（对应 §5.8「未启动 / 未授权」两态 + 临时不可用）。 */
enum class ProviderStatus {
    /** 可立即拉取。 */
    AVAILABLE,

    /** Shizuku 未安装 / 服务未启动。 */
    NOT_INSTALLED,

    /** 已安装但本 App 未获授权（需要走授权弹窗）。 */
    PERMISSION_DENIED,

    /** 降级通道：系统「使用情况访问」未授权。 */
    USAGE_ACCESS_DENIED,

    /** 其他临时故障（binder 挂掉、shell 超时等），可重试。 */
    TEMPORARILY_UNAVAILABLE
}

/** 一次通道读取的结果。失败时**不得产出任何 ParseResult**，避免半份数据入库。 */
sealed interface LoadedUsage {
    /**
     * [capturedAtMs] = 抓取时刻，作为 `pulled_at` 与合并基数；
     * [warning] 非空表示**部分源缺失**（例如明文 dump 失败、今天的数据没补上），
     * 但主源解析成功——数据可用，必须把原因写进 `pull_log.note`，不许静默。
     */
    data class Parsed(
        val result: com.screentrace.app.data.parse.ParseResult,
        val capturedAtMs: Long,
        val warning: String? = null
    ) : LoadedUsage

    data class Failed(
        val status: ProviderStatus,
        val message: String
    ) : LoadedUsage
}

/**
 * 一次拉取的最终结果（§5.8 状态机：未启动 / 未授权 / 拉取中 / 失败 / 空 dump /
 * 部分历史 / 完全无数据）。「部分历史」「完全无数据」是**界面层**根据库里已有数据
 * 推导的展示态，不属于单次拉取结果，故不在此枚举。
 */
sealed interface PullOutcome {
    data class Success(
        val channel: PullChannel,
        val days: Int,
        val unlockEvents: Int,
        val notificationEvents: Int,
        val pulledAtMs: Long
    ) : PullOutcome

    /** 通道读到了内容但解析后什么都没有（§6：dump 为空保留旧数据并记录）。 */
    data object EmptyDump : PullOutcome

    /** 节流命中，本次没有真正拉取（§2.1：打开 App 30 分钟内不重复）。 */
    data object Throttled : PullOutcome

    /** 已有拉取在跑（§5.8：拉取中禁止重复任务）。 */
    data object AlreadyRunning : PullOutcome

    data class Failure(
        val status: ProviderStatus,
        val message: String,
        /** 是否保留了库里的旧数据（成功/无数据都不算覆盖）。 */
        val keptOldData: Boolean
    ) : PullOutcome
}

/** 库里已有数据的覆盖范围，用于「部分历史 / 完全无数据」两态判定（§5.8）。 */
data class HistoryCoverage(
    val firstDate: java.time.LocalDate?,
    val lastDate: java.time.LocalDate?,
    val dayCount: Int
) {
    val hasAny: Boolean get() = dayCount > 0
}
