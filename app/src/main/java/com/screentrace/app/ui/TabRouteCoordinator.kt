package com.screentrace.app.ui

import com.screentrace.app.uikit.PerfProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * OBS-2 §6.4.5 P1：路由写入审计钩子（只旁路上报，不参与任何判定）。
 * 生产环境默认使用 [NoopRouteAuditSink]，零行为差异、不进持久化。
 */
internal interface RouteAuditSink {
    fun onEntryInvoked(
        source: String,
        targetIndex: Int,
        gen: Long,
        genSource: GenSource,
        genBasis: String
    )

    fun onCommitAttempt(
        targetIndex: Int,
        routeBefore: AppPage,
        routeAfter: AppPage,
        stable: Boolean,
        currentPage: Int,
        settledPage: Int,
        gen: Long,
        genValid: Boolean
    )

    fun onCommitAccepted(
        targetIndex: Int,
        gen: Long,
        genSource: GenSource,
        genBasis: String
    )

    fun onCommitSkipped(reason: String)
}

internal object NoopRouteAuditSink : RouteAuditSink {
    override fun onEntryInvoked(
        source: String,
        targetIndex: Int,
        gen: Long,
        genSource: GenSource,
        genBasis: String
    ) = Unit

    override fun onCommitAttempt(
        targetIndex: Int,
        routeBefore: AppPage,
        routeAfter: AppPage,
        stable: Boolean,
        currentPage: Int,
        settledPage: Int,
        gen: Long,
        genValid: Boolean
    ) = Unit

    override fun onCommitAccepted(
        targetIndex: Int,
        gen: Long,
        genSource: GenSource,
        genBasis: String
    ) = Unit

    override fun onCommitSkipped(reason: String) = Unit
}

/**
 * OBS-2 最小 Pager 适配接口：生产环境绑定真实 `PagerState` + `interactionSource`，
 * L3 确定性竞态测试绑定可控假 Pager（无 `Thread.sleep`，纯 `TestDispatcher` 驱动）。
 */
internal interface TabPagerAdapter {
    val currentPage: Int
    val settledPage: Int
    val isScrollInProgress: Boolean
    val isUserDragging: Boolean
    suspend fun scrollToPage(index: Int)
    suspend fun animateScrollToPage(index: Int)
    /** 挂起直到 `!isScrollInProgress && currentPage == settledPage`。 */
    suspend fun awaitSettled()
}

/**
 * CR-30-R10-OBS2 生产导航协调器：
 * - 唯一路由写入口 [commitRouteFromPager]（三个触发源：`snapshot` / `finally` / `settle` 全部经此写入）；
 * - 取消原因两段式判定（`observedCause` 仅日志、`effectiveAction` 为唯一行为判据）；
 * - 取消路径专属的一次性稳定后对齐器（未落定 = 继续等待，代际/超时/作用域取消 = 安静退出）；
 * - Pager 仍是唯一真相源，只写路由、绝不反向驱动 Pager（CR-17 / α-1~α-8）。
 */
internal class TabRouteCoordinator(
    private val mainPages: List<AppPage>,
    private val tabGuard: TabScrollGuard,
    private val pager: TabPagerAdapter,
    private val currentRoute: () -> AppPage,
    private val writeRoute: (AppPage) -> Unit,
    private val scope: CoroutineScope,
    private val settleTimeoutMs: Long = DEFAULT_SETTLE_TIMEOUT_MS,
    private val audit: RouteAuditSink = NoopRouteAuditSink
) {
    val generation = ScrollGenerationCounter()

    @Volatile
    private var lastDragging: Boolean = false

    /**
     * 代际推进点 ②：检测到用户手势接管（`dragging` 由 false → true）时推进代际，
     * 使等待中的旧对齐器立即失效。
     */
    fun onUserDraggingChanged(dragging: Boolean) {
        val prev = lastDragging
        lastDragging = dragging
        if (!prev && dragging) {
            generation.advance(GenSource.GESTURE)
        }
    }

    /**
     * 触发源 ①：`snapshotFlow { pagerState.currentPage }` 回写。
     * 观测发射瞬间代际（`observed-at-emit`），非程序化中间页则经统一入口提交。
     */
    fun onSnapshotPage(index: Int) {
        val observedGen = generation.current
        PerfProbe.event("Route/currentPage=$index")
        if (tabGuard.ignoresWrite(index)) {
            PerfProbe.event("Route/guardIgnore=$index")
            return
        }
        commitRouteFromPager(
            landed = index,
            source = SOURCE_SNAPSHOT,
            expectedGen = observedGen.value,
            genBasis = BASIS_OBSERVED_AT_EMIT,
            requireSettled = false
        )
    }

    /**
     * 执行一次程序化滚动（供点 tab 与 `LaunchedEffect(mainPage)` 共用）。
     * - 开始前调用 `tabGuard.begin(target)` 并立即推进代际（推进点 ①：`PROGRAMMATIC`）；
     * - 正常收尾仅在所有权、稳定双条件与代际均成立时经统一入口对齐；
     * - 取消收尾一律不在 finally 写过渡态，只按 `effectiveAction` 决定是否派发稳定后对齐器。
     */
    suspend fun runProgrammaticScroll(
        target: Int,
        reduceMotion: Boolean,
        seq: Int
    ) {
        val guardToken = tabGuard.begin(target)
        val gen0 = generation.advance(GenSource.PROGRAMMATIC).value
        var cancelled = false
        try {
            PerfProbe.event("Pager/scrollStart")
            PerfProbe.a5("scrollStart", target, seq)
            if (reduceMotion) pager.scrollToPage(target)
            else pager.animateScrollToPage(target)
            PerfProbe.event("Pager/scrollEnd")
            PerfProbe.a5("scrollEnd", target, seq)
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        } finally {
            val tokenOwned = tabGuard.end(guardToken)
            val isScrolling = pager.isScrollInProgress
            val current = pager.currentPage
            val settled = pager.settledPage
            val stable = !isScrolling && current == settled

            if (!cancelled) {
                val observed = ObservedCause.NONE
                val action = CancelCauseResolver.actionFor(observed)
                PerfProbe.a5Finally(
                    target = target,
                    seq = seq,
                    aligned = tokenOwned,
                    cause = observed.name.lowercase(),
                    act = action.name.lowercase(),
                    stable = !isScrolling,
                    settledMatch = current == settled
                )
                if (tokenOwned) {
                    val genNow = generation.current
                    if (genNow.value != gen0) {
                        PerfProbe.a5Skip(
                            target,
                            seq,
                            ScrollAlignPolicy.skipReasonForStaleGeneration(genNow.source)
                        )
                    } else if (stable) {
                        commitRouteFromPager(
                            landed = current,
                            source = SOURCE_FINALLY,
                            expectedGen = gen0,
                            genBasis = BASIS_CAPTURED_AT_BEGIN,
                            requireSettled = true
                        )
                    } else {
                        // 极少数瞬时/异常收尾时 Pager 尚未完全落定：派发对齐器等待落定而非写入过渡页
                        startSettleAligner(target = target, seq = seq, gen0 = genNow.value)
                    }
                }
            } else {
                val observed = CancelCauseResolver.observe(
                    scopeActive = scope.isActive,
                    tokenOwned = tokenOwned,
                    userDragging = pager.isUserDragging
                )
                val action = CancelCauseResolver.actionFor(observed)
                PerfProbe.a5Cancel(
                    target = target,
                    seq = seq,
                    cause = observed.name.lowercase(),
                    act = action.name.lowercase()
                )
                PerfProbe.a5Finally(
                    target = target,
                    seq = seq,
                    aligned = tokenOwned,
                    cause = observed.name.lowercase(),
                    act = action.name.lowercase(),
                    stable = !isScrolling,
                    settledMatch = current == settled
                )
                // OW-1：唯一行为判据只读 effectiveAction，禁止读 observedCause
                when (action) {
                    EffectiveAction.GESTURE_COMPENSATE,
                    EffectiveAction.CONSERVATIVE_COMPENSATE -> {
                        startSettleAligner(
                            target = target,
                            seq = seq,
                            gen0 = generation.current.value
                        )
                    }
                    EffectiveAction.NO_ACTION,
                    EffectiveAction.NO_ALIGN_SUPERSEDED,
                    EffectiveAction.NO_ALIGN_SCOPE -> Unit
                }
            }
        }
    }

    /**
     * 取消路径专属的一次性稳定后对齐器（OBS-2 §6.2 要点 2 / §6.3.5）。
     * 未落定 = 继续等待；只有 scope 取消 / 代际失效 / 等待超时才安静退出。
     */
    private fun startSettleAligner(target: Int, seq: Int, gen0: Long) {
        scope.launch {
            val settled = withTimeoutOrNull(settleTimeoutMs) {
                pager.awaitSettled()
                true
            } ?: false

            // 退出判定顺序固定：① scope -> ② generation -> ③ timeout
            if (!currentCoroutineContext().isActive) {
                PerfProbe.a5Skip(target, seq, "scope-cancelled")
                return@launch
            }
            val genNow = generation.current
            if (genNow.value != gen0) {
                PerfProbe.a5Skip(
                    target,
                    seq,
                    ScrollAlignPolicy.skipReasonForStaleGeneration(genNow.source)
                )
                return@launch
            }
            if (!settled) {
                PerfProbe.a5Skip(target, seq, "settle-timeout")
                return@launch
            }

            commitRouteFromPager(
                landed = pager.settledPage,
                source = SOURCE_SETTLE,
                expectedGen = gen0,
                genBasis = BASIS_CAPTURED_AT_START,
                requireSettled = true
            )
        }
    }

    /**
     * 唯一的「Pager 状态 → 路由」写入口（OBS-2 §6.4，α 方案）。
     * - 契约 1：幂等（目标页 == 当前路由页时短路不写）；
     * - 契约 2：只写路由，绝不写 Pager；
     * - 契约 3：不维护影子路由状态，直接读 [currentRoute]；
     * - 契约 4：来源与代际只进打点与审计钩子，不进业务持久化。
     */
    internal fun commitRouteFromPager(
        landed: Int,
        source: String,
        expectedGen: Long,
        genBasis: String,
        requireSettled: Boolean
    ) {
        val genAtEntry = generation.current
        audit.onEntryInvoked(source, landed, genAtEntry.value, genAtEntry.source, genBasis)

        val page = mainPages.getOrNull(landed)
        if (page == null) {
            audit.onCommitSkipped("no-page")
            return
        }
        // CB-4：保留历史语义——对齐打点落在幂等短路之前
        if (source == SOURCE_FINALLY || source == SOURCE_SETTLE) {
            PerfProbe.event("Route/align=$landed:$source")
        }

        val current = AppPage.mainOf(currentRoute())
        if (current == page) {
            audit.onCommitSkipped("idem")
            return
        }

        val genNow = generation.current
        val genValid = genNow.value == expectedGen
        val currentPage = pager.currentPage
        val settledPage = pager.settledPage
        val stable = !pager.isScrollInProgress && currentPage == settledPage

        audit.onCommitAttempt(
            targetIndex = landed,
            routeBefore = current,
            routeAfter = page,
            stable = stable,
            currentPage = currentPage,
            settledPage = settledPage,
            gen = genNow.value,
            genValid = genValid
        )

        if (!genValid) {
            audit.onCommitSkipped(ScrollAlignPolicy.skipReasonForStaleGeneration(genNow.source))
            return
        }
        if (requireSettled && !stable) {
            audit.onCommitSkipped("not-stable")
            return
        }

        audit.onCommitAccepted(landed, genNow.value, genNow.source, genBasis)
        PerfProbe.event("Route/selectMain=$page")
        PerfProbe.event("Route/commit=$landed:$source gen=${genNow.value} genSrc=${genNow.source} genBasis=$genBasis")
        writeRoute(page)
    }

    companion object {
        const val DEFAULT_SETTLE_TIMEOUT_MS = 2_000L
        const val SOURCE_SNAPSHOT = "snapshot"
        const val SOURCE_FINALLY = "finally"
        const val SOURCE_SETTLE = "settle"
        const val BASIS_OBSERVED_AT_EMIT = "observed-at-emit"
        const val BASIS_CAPTURED_AT_BEGIN = "captured-at-begin"
        const val BASIS_CAPTURED_AT_START = "captured-at-start"
    }
}
