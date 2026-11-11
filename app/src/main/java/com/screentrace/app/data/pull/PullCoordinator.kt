package com.screentrace.app.data.pull

import com.screentrace.app.contract.PullResult
import com.screentrace.app.data.IngestPipeline
import com.screentrace.app.data.db.PullLogEntity
import com.screentrace.app.data.db.ScreenTraceDatabase
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex

/**
 * 拉取协调器（§2.1/§5.8）：通道优先级 → 读取 → 解析 → 入库 → 状态。
 *
 * 三条硬约束都在这里落地：
 * 1. **拉取中禁止重复任务**：`Mutex.tryLock()`，并发调用直接返回 [PullOutcome.AlreadyRunning]；
 * 2. **打开 App 节流**：以 `pull_log` 最近一次记录为基准，成功 30 分钟、失败 60 秒
 *    （分档理由见 [pullIfStale]）；
 * 3. **失败必须留痕且不动旧数据**：主通道失败 → 尝试降级通道；全不可用 →
 *    写一条 `pull_log(ok=false)`，库里的历史数据原样保留（§6）。
 *
 * 通道优先级由构造参数决定，默认 Shizuku 优先、UsageStats 兜底。
 */
class PullCoordinator(
    private val db: ScreenTraceDatabase,
    private val pipeline: IngestPipeline,
    private val providers: List<UsageProvider>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** 自动同步钩子（§5.10）：批 8 在这里链式触发上传，当前为空实现。 */
    private val onSuccess: suspend (PullOutcome.Success) -> Unit = {}
) {

    private val mutex = Mutex()

    /**
     * 「数据已变化」事件：每次**成功入库**后发一次，供界面重新读库。
     *
     * 为什么必须有这条通路：后台 WorkManager 拉取与前台 UI 是两条独立路径。
     * 冷启动时 Worker 常常先把 4MB dump 拉完入库，随后 UI 的 [pullIfStale] 因
     * 30 分钟节流返回 [PullOutcome.Throttled]——如果界面只在「自己拉成功」时
     * 重读，就会停在首帧的空态上：**数据明明在库里，用户看到的是「尚无使用记录」**。
     * 真机实测复现率 100%。
     *
     * 用 tryEmit + 容量 1：emit 不能阻塞拉取主流程；没有订阅者时丢事件也无所谓，
     * 界面自己拉取成功那条路径本来也会重读。
     */
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes: SharedFlow<Unit> = _changes

    /**
     * App 打开 / 页面回前台时调用：节流窗口内不重复拉取。
     *
     * **节流窗口按上次结果分档**（这是一个必须说清的设计取舍）：
     * - 上次**成功** → 30 分钟（§2.1 原文）；
     * - 上次**失败/空 dump** → 只冷却 60 秒。
     *
     * 为什么不能「失败也算 30 分钟」：用户看到「Shizuku 未授权」→ 去 Shizuku 里点允许 →
     * 切回 App，若此时被 30 分钟节流挡住，界面会静默无变化，用户只会认为功能坏了。
     * 而失败路径本来就不贵（权限探测不打 dump），60 秒冷却足以挡住「反复切前台把
     * 界面一直卡在拉取态」这种抖动。
     */
    suspend fun pullIfStale(): PullOutcome {
        val last = db.pullLogDao().latest() ?: return pull()
        val window = if (last.ok) minIntervalMs else FAILURE_RETRY_MS
        if (clock() - last.pulledAt in 0 until window) return PullOutcome.Throttled
        return pull()
    }

    /** 设置页「手动拉取」调用：**不节流**，但仍受「拉取中禁止重复任务」约束。 */
    suspend fun pull(): PullOutcome {
        if (!mutex.tryLock()) return PullOutcome.AlreadyRunning
        return try {
            runPull()
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun runPull(): PullOutcome {
        var firstFailure: PullOutcome.Failure? = null
        // 把「实际尝试到的最后一条通道」记下来（评审 P2-12）：失败留痕时 source 以前恒取
        // providers.first()，两条通道都不可用时会被记成 shizuku，看日志会去查错方向。
        var attempted: PullChannel? = null
        for (provider in providers) {
            attempted = provider.channel
            val status = provider.status()
            if (status != ProviderStatus.AVAILABLE) {
                if (firstFailure == null) {
                    firstFailure = PullOutcome.Failure(
                        status = status,
                        message = describe(status, provider.channel),
                        keptOldData = hasOldData()
                    )
                }
                continue
            }
            when (val loaded = provider.load()) {
                is LoadedUsage.Failed -> {
                    if (firstFailure == null) {
                        firstFailure = PullOutcome.Failure(
                            status = loaded.status,
                            message = loaded.message,
                            keptOldData = hasOldData()
                        )
                    }
                }

                is LoadedUsage.Parsed -> {
                    // §5.8「空 dump」是**独立状态**，不能混进「失败」：
                    // 依旧让管道跑一遍（它会补一条 ok=false 的 pull_log 留痕），
                    // 然后返回 EmptyDump，界面文案是「系统暂无可解析记录」而不是「拉取失败」。
                    if (loaded.result.isEmpty) {
                        pipeline.ingest(
                            result = loaded.result,
                            source = provider.channel.tag,
                            pulledAtMs = loaded.capturedAtMs,
                            note = loaded.warning
                        )
                        return PullOutcome.EmptyDump
                    }
                    // 入库管道自己会写 pull_log（成功/空 dump/异常回滚三种都写），这里不重复记
                    val ingested = pipeline.ingest(
                        result = loaded.result,
                        source = provider.channel.tag,
                        pulledAtMs = loaded.capturedAtMs,
                        note = loaded.warning
                    )
                    if (!ingested.ok) {
                        return PullOutcome.Failure(
                            status = ProviderStatus.TEMPORARILY_UNAVAILABLE,
                            message = ingested.message ?: "入库失败",
                            keptOldData = hasOldData()
                        )
                    }
                    val outcome = PullOutcome.Success(
                        channel = provider.channel,
                        days = loaded.result.days.size,
                        unlockEvents = loaded.result.unlockEvents.size,
                        notificationEvents = loaded.result.notificationEvents.size,
                        pulledAtMs = loaded.capturedAtMs
                    )
                    // 先通知界面重读，再跑同步钩子：后者可能很慢，不该挡住刷新
                    _changes.tryEmit(Unit)
                    // 同步钩子失败不得把「拉取成功」降级成失败（§5.10：同步失败只在设置页展示）
                    runCatching { onSuccess(outcome) }
                    return outcome
                }
            }
        }

        val failure = firstFailure ?: PullOutcome.Failure(
            status = ProviderStatus.TEMPORARILY_UNAVAILABLE,
            message = "没有可用的数据通道",
            keptOldData = hasOldData()
        )
        // 通道层面就失败了（根本没读到 dump），管道没机会记 pull_log —— 这里补记一条
        runCatching {
            db.pullLogDao().insert(
                PullLogEntity(
                    pulledAt = clock(),
                    source = attempted?.tag ?: providers.firstOrNull()?.channel?.tag ?: "none",
                    ok = false,
                    note = failure.message
                )
            )
        }
        return failure
    }

    /**
     * 是否有可用旧数据（决定失败时是「内联错误 + 旧数据」还是「全页引导」）。
     *
     * 必须同时看 `app_usage`：降级通道**只写应用级数据、不写 day_summary**（见
     * [UsageStatsUsageProvider] 类注释），只看日聚合会把「库里其实有排行数据」误判成
     * 完全无数据，界面就会错误地盖上全区空态。
     */
    private suspend fun hasOldData(): Boolean =
        db.daySummaryDao().getAll().isNotEmpty() || db.appUsageDao().allNames().isNotEmpty()

    /**
     * §5.8「部分历史 / 完全无数据」两态判定所需的覆盖范围。
     *
     * **必须把 `app_usage` 的日期并进来**（6b-6d 评审 P0-1 第 2 条）：降级通道只写
     * `app_usage` / `hour_usage`，只数 `day_summary` 会让「只有应用数据」的库返回
     * `coverage.hasAny = false`，批 7 首启向导就会把它当成「从未拉过」再引导一遍。
     */
    suspend fun historyCoverage(): HistoryCoverage {
        val dates = buildSet {
            db.daySummaryDao().getAll().forEach { row ->
                runCatching { LocalDate.parse(row.date) }.getOrNull()?.let(::add)
            }
            db.appUsageDao().dates().forEach { raw ->
                runCatching { LocalDate.parse(raw) }.getOrNull()?.let(::add)
            }
        }
        if (dates.isEmpty()) return HistoryCoverage(null, null, 0)
        return HistoryCoverage(
            firstDate = dates.min(),
            lastDate = dates.max(),
            dayCount = dates.size
        )
    }

    /** 当前通道可用性快照（设置页 / 首启向导用）。 */
    suspend fun channelStatuses(): List<Pair<PullChannel, ProviderStatus>> =
        providers.map { it.channel to it.status() }

    /** 系统只保留约 10 个滚动日（§2.1）：首次拉取窗口必然短于此，属「部分历史」。 */
    fun isPartialHistory(coverage: HistoryCoverage): Boolean {
        val first = coverage.firstDate ?: return false
        return first.isAfter(LocalDate.now(zone).minusDays(PARTIAL_HISTORY_DAYS))
    }

    private fun describe(status: ProviderStatus, channel: PullChannel): String = when (status) {
        ProviderStatus.AVAILABLE -> "可用"
        ProviderStatus.NOT_INSTALLED -> "Shizuku 未安装或未启动"
        ProviderStatus.PERMISSION_DENIED -> "Shizuku 未授权"
        ProviderStatus.USAGE_ACCESS_DENIED -> "未授予「使用情况访问」权限"
        ProviderStatus.TEMPORARILY_UNAVAILABLE -> "${channel.tag} 通道暂时不可用"
    }

    companion object {
        /** §2.1：打开 App 时节流 30 分钟（仅针对上次成功的情况）。 */
        const val DEFAULT_MIN_INTERVAL_MS = 30L * 60 * 1000

        /** 上次失败后的短冷却：挡住前台抖动，又不至于让「刚授权完」的用户等 30 分钟。 */
        const val FAILURE_RETRY_MS = 60L * 1000

        /** 系统滚动窗口约 10 天，首次拉取拿不满即「部分历史」。 */
        const val PARTIAL_HISTORY_DAYS = 9L
    }
}

/**
 * §5.8 设置页「立即拉取」的文案映射：把拉取状态机翻成**一句人话**。
 *
 * `ok` 决定按钮/提示的样式，所以这里必须如实反映结果：
 * [PullOutcome.Throttled] 不是失败（数据是刚拉过的），[PullOutcome.EmptyDump] 却要算
 * 未成功（用户点了按钮期待有新数据）。
 */
fun PullOutcome.asPullResult(): PullResult = when (this) {
    is PullOutcome.Success -> PullResult(
        ok = true,
        message = "已更新 $days 天 · 解锁 $unlockEvents 次 · 通知 $notificationEvents 条（来源 ${channel.tag}）"
    )

    PullOutcome.EmptyDump -> PullResult(ok = false, message = "系统暂无可解析的使用记录")
    PullOutcome.Throttled -> PullResult(ok = true, message = "刚拉取过，30 分钟内不重复拉取")
    PullOutcome.AlreadyRunning -> PullResult(ok = false, message = "正在拉取中，请稍候")
    is PullOutcome.Failure -> PullResult(ok = false, message = message)
}
