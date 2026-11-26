package com.screentrace.app.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CR-30-R10-OBS2 L1/L2 确定性竞态与协调器契约测试（FakeTabPagerAdapter 虚拟时间驱动；真实 Compose HorizontalPager/MutatorMutex 归 L3 设备/Compose 集成测试）：
 * - L1：CancelCauseResolver 两段式判定 + ScrollAlignPolicy 纯函数表驱动测试；
 * - L2/L3：真实 TabRouteCoordinator + 三通道 RouteAudit（生产上报 + 独立状态观察 + 归因）
 *   覆盖 OBS-2 主场景 S2、R1（新导航失效）、R2（新手势失效）、R4（作用域取消）、
 *   R6/R7（未落定继续等待与虚拟超时）、R10（幂等）、R12-A（瞬时滚动）、R12-B（UNKNOWN 保守补偿交接）、
 *   以及 CR-17 正常横滑跟手回写。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TabRouteCoordinatorTest {

    private val mainPages = listOf(AppPage.Today, AppPage.Stats, AppPage.Calendar)

    /** 三通道可信审计记录器（OBS-2 v2.3 §7.4.3）：不替生产代码自报成功，独立观察状态变化。 */
    private class RouteAuditRecorder(initialRoute: AppPage = AppPage.Today) : RouteAuditSink {
        data class EntryEvent(val commitId: Long, val source: String, val targetIndex: Int, val gen: Long, val genBasis: String)
        data class AcceptedEvent(val commitId: Long, val targetIndex: Int, val targetPage: AppPage, val gen: Long, val genSource: GenSource, val genBasis: String)

        var route: AppPage = initialRoute
            private set

        val entries = mutableListOf<EntryEvent>()
        val accepted = mutableListOf<AcceptedEvent>()
        val skipped = mutableListOf<String>()
        val alignerSkipped = mutableListOf<String>()
        val stateMutations = mutableListOf<AppPage>()
        val correlatedCommits = mutableListOf<Pair<Long, AppPage>>()

        private var pendingCommit: Pair<Long, AppPage>? = null
        var unattributedMutations: Int = 0
            private set
        var mismatchedTargetMutations: Int = 0
            private set

        fun writeRoute(page: AppPage) {
            if (page != route) {
                val pending = pendingCommit
                when {
                    pending == null -> unattributedMutations++
                    pending.second != page -> {
                        mismatchedTargetMutations++
                        unattributedMutations++
                    }
                    else -> correlatedCommits.add(pending)
                }
                pendingCommit = null
                route = page
                stateMutations.add(page)
            }
        }

        override fun onEntryInvoked(
            commitId: Long,
            source: String,
            targetIndex: Int,
            gen: Long,
            genSource: GenSource,
            genBasis: String
        ) {
            entries.add(EntryEvent(commitId, source, targetIndex, gen, genBasis))
        }

        override fun onCommitAttempt(
            commitId: Long,
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
            commitId: Long,
            targetIndex: Int,
            targetPage: AppPage,
            gen: Long,
            genSource: GenSource,
            genBasis: String
        ) {
            pendingCommit = commitId to targetPage
            accepted.add(AcceptedEvent(commitId, targetIndex, targetPage, gen, genSource, genBasis))
        }

        override fun onCommitSkipped(commitId: Long, reason: String) {
            skipped.add(reason)
        }

        override fun onAlignerSkipped(target: Int, seq: Int, reason: String) {
            alignerSkipped.add(reason)
        }
    }

    /** 确定性可控假 Pager：通过 CompletableDeferred 精确控制滚动挂起、取消与落定。 */
    private class FakeTabPagerAdapter(
        initialPage: Int = 0
    ) : TabPagerAdapter {
        private data class Snap(val scrolling: Boolean, val current: Int, val settled: Int)
        private val snapFlow = MutableStateFlow(Snap(false, initialPage, initialPage))

        override val currentPage: Int get() = snapFlow.value.current
        override val settledPage: Int get() = snapFlow.value.settled
        override val isScrollInProgress: Boolean get() = snapFlow.value.scrolling
        override var isUserDragging: Boolean = false

        var nextAnimateGate: CompletableDeferred<Unit>? = null
        var onAnimateStart: ((Int) -> Unit)? = null

        fun setState(scrolling: Boolean, current: Int, settled: Int) {
            snapFlow.value = Snap(scrolling, current, settled)
        }

        override suspend fun scrollToPage(index: Int) {
            setState(scrolling = false, current = index, settled = index)
        }

        override suspend fun animateScrollToPage(index: Int) {
            setState(scrolling = true, current = snapFlow.value.current, settled = snapFlow.value.settled)
            onAnimateStart?.invoke(index)
            val gate = nextAnimateGate
            if (gate != null) {
                gate.await()
            }
            setState(scrolling = false, current = index, settled = index)
        }

        override suspend fun awaitSettled() {
            snapFlow.first { !it.scrolling && it.current == it.settled }
        }
    }

    // ---- L1：纯函数表驱动测试 ----

    @Test
    fun `L1 CancelCauseResolver 两段式优先级与 UNKNOWN 保守补偿`() {
        // 1. scopeActive == false 最高优先
        assertEquals(ObservedCause.SCOPE, CancelCauseResolver.observe(scopeActive = false, tokenOwned = false, userDragging = true))
        assertEquals(EffectiveAction.NO_ALIGN_SCOPE, CancelCauseResolver.actionFor(ObservedCause.SCOPE))

        // 2. tokenOwned == false 次之
        assertEquals(ObservedCause.SUPERSEDED, CancelCauseResolver.observe(scopeActive = true, tokenOwned = false, userDragging = true))
        assertEquals(EffectiveAction.NO_ALIGN_SUPERSEDED, CancelCauseResolver.actionFor(ObservedCause.SUPERSEDED))

        // 3. userDragging == true -> GESTURE
        assertEquals(ObservedCause.GESTURE, CancelCauseResolver.observe(scopeActive = true, tokenOwned = true, userDragging = true))
        assertEquals(EffectiveAction.GESTURE_COMPENSATE, CancelCauseResolver.actionFor(ObservedCause.GESTURE))

        // 4. 其余 -> UNKNOWN -> CONSERVATIVE_COMPENSATE（核心不变量：UNKNOWN 必须触发补偿）
        assertEquals(ObservedCause.UNKNOWN, CancelCauseResolver.observe(scopeActive = true, tokenOwned = true, userDragging = false))
        assertEquals(EffectiveAction.CONSERVATIVE_COMPENSATE, CancelCauseResolver.actionFor(ObservedCause.UNKNOWN))

        // 5. NONE -> NO_ACTION
        assertEquals(EffectiveAction.NO_ACTION, CancelCauseResolver.actionFor(ObservedCause.NONE))
    }

    @Test
    fun `L1 ScrollAlignPolicy 两条路径纯函数门禁`() {
        assertTrue(
            ScrollAlignPolicy.shouldAlignAtFinally(
                tokenOwned = true,
                outcome = ScrollOutcome.SETTLED,
                isScrollInProgress = false,
                currentPage = 2,
                settledPage = 2,
                routePage = 0
            )
        )
        assertFalse(
            "取消路径绝不允许在 finally 对齐",
            ScrollAlignPolicy.shouldAlignAtFinally(
                tokenOwned = true,
                outcome = ScrollOutcome.CANCELLED,
                isScrollInProgress = false,
                currentPage = 1,
                settledPage = 1,
                routePage = 0
            )
        )
        assertFalse(
            "currentPage != settledPage 未落定时不允许对齐",
            ScrollAlignPolicy.shouldAlignAtFinally(
                tokenOwned = true,
                outcome = ScrollOutcome.SETTLED,
                isScrollInProgress = false,
                currentPage = 1,
                settledPage = 0,
                routePage = 0
            )
        )
        assertTrue(
            ScrollAlignPolicy.shouldAlignAtSettle(
                genValid = true,
                isScrollInProgress = false,
                currentPage = 1,
                settledPage = 1,
                routePage = 0
            )
        )
        assertFalse(
            "代际过期时对齐器不得写入",
            ScrollAlignPolicy.shouldAlignAtSettle(
                genValid = false,
                isScrollInProgress = false,
                currentPage = 1,
                settledPage = 1,
                routePage = 0
            )
        )
    }

    // ---- L2/L3：确定性异步竞态集成测试 ----

    @Test
    fun `L3 S2 点击跨页途中被手势打断 不得在取消瞬间写入中间页 落定后精准对齐`() = runTest {
        val pager = FakeTabPagerAdapter(initialPage = 0)
        val audit = RouteAuditRecorder(initialRoute = AppPage.Today)
        val guard = TabScrollGuard()
        val coord = TabRouteCoordinator(
            mainPages = mainPages,
            tabGuard = guard,
            pager = pager,
            currentRoute = { audit.route },
            writeRoute = audit::writeRoute,
            scope = this,
            audit = audit
        )

        val gate = CompletableDeferred<Unit>()
        pager.nextAnimateGate = gate

        // 发起 Today(0) -> Calendar(2) 程序化滚动
        val job = launch { coord.runProgrammaticScroll(target = 2, reduceMotion = false, seq = 1) }
        runCurrent()

        // 滚动途中经过中间页 Stats(1)，被 guard 拦截，不写路由
        pager.setState(scrolling = true, current = 1, settled = 0)
        coord.onSnapshotPage(1)
        assertEquals("程序化滚动途中中间页不得写入路由", AppPage.Today, audit.route)
        assertTrue(audit.stateMutations.isEmpty())

        // 用户手势打断：isUserDragging = true，旧滚动协程取消
        pager.isUserDragging = true
        coord.onUserDraggingChanged(true)
        gate.cancel(CancellationException("user gesture interrupt"))
        runCurrent()

        // 取消瞬间 Pager 仍停在滚动中的中间页 1 —— OBS-2 核心：绝不写入中间页！
        assertEquals("手势打断瞬间不得写入中间页 Stats", AppPage.Today, audit.route)
        assertTrue("取消瞬间不得有任何状态变更", audit.stateMutations.isEmpty())

        // 用户松手，Pager 最终落定到 Today(0)（例如拉回原页）→ 幂等不写
        pager.isUserDragging = false
        coord.onUserDraggingChanged(false)
        pager.setState(scrolling = false, current = 0, settled = 0)
        advanceUntilIdle()

        assertEquals(AppPage.Today, audit.route)
        assertTrue(audit.stateMutations.isEmpty())
        assertEquals("不得存在绕过统一入口的状态变更", 0, audit.unattributedMutations)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `L3 R1 对齐器等待期间新程序化导航接管 旧对齐器因代际失效安静退出`() = runTest {
        val pager = FakeTabPagerAdapter(initialPage = 0)
        val audit = RouteAuditRecorder(initialRoute = AppPage.Today)
        val guard = TabScrollGuard()
        val coord = TabRouteCoordinator(
            mainPages = mainPages,
            tabGuard = guard,
            pager = pager,
            currentRoute = { audit.route },
            writeRoute = audit::writeRoute,
            scope = this,
            audit = audit
        )

        val gate1 = CompletableDeferred<Unit>()
        pager.nextAnimateGate = gate1
        val job1 = launch { coord.runProgrammaticScroll(target = 2, reduceMotion = false, seq = 1) }
        runCurrent()

        // 手势取消 job1，派发旧对齐器（捕获 gen0），此时 Pager 仍在滚动未落定
        pager.setState(scrolling = true, current = 1, settled = 0)
        pager.isUserDragging = true
        gate1.cancel(CancellationException("gesture"))
        runCurrent()
        assertTrue(job1.isCancelled)

        // 旧对齐器仍在等待落定时，用户点了新 tab（Stats=1，瞬时滚动完成）→ 推进代际 PROGRAMMATIC
        pager.isUserDragging = false
        pager.nextAnimateGate = null
        val job2 = launch { coord.runProgrammaticScroll(target = 1, reduceMotion = true, seq = 2) }
        advanceUntilIdle()
        job2.join()

        assertEquals(AppPage.Stats, audit.route)
        assertEquals("应只有新导航写入一次，旧对齐器不得重复或覆盖写入", listOf(AppPage.Stats), audit.stateMutations)
        assertEquals(0, audit.unattributedMutations)
    }

    @Test
    fun `L3 R2 对齐器等待期间再次用户手势接管 旧对齐器因 GESTURE 代际推进失效`() = runTest {
        val pager = FakeTabPagerAdapter(initialPage = 0)
        val audit = RouteAuditRecorder(initialRoute = AppPage.Today)
        val guard = TabScrollGuard()
        val coord = TabRouteCoordinator(
            mainPages = mainPages,
            tabGuard = guard,
            pager = pager,
            currentRoute = { audit.route },
            writeRoute = audit::writeRoute,
            scope = this,
            audit = audit
        )

        val gate = CompletableDeferred<Unit>()
        pager.nextAnimateGate = gate
        launch { coord.runProgrammaticScroll(target = 2, reduceMotion = false, seq = 1) }
        runCurrent()

        // 第一次取消启动对齐器（此时 Pager 仍在滚动）
        pager.setState(scrolling = true, current = 1, settled = 0)
        pager.isUserDragging = false // 走 UNKNOWN -> CONSERVATIVE_COMPENSATE
        gate.cancel(CancellationException("cancel"))
        runCurrent()

        // 对齐器等待期间，用户发起新一轮拖动（false -> true 推进代际 GESTURE）
        pager.isUserDragging = true
        coord.onUserDraggingChanged(true)
        runCurrent()

        // Pager 落定到 1，但旧对齐器代际已过期，不得写入
        pager.isUserDragging = false
        coord.onUserDraggingChanged(false)
        pager.setState(scrolling = false, current = 1, settled = 1)
        advanceUntilIdle()

        assertTrue("旧对齐器代际失效后不得写入路由", audit.stateMutations.isEmpty())
        assertEquals(AppPage.Today, audit.route)
        assertEquals(0, audit.unattributedMutations)
    }

    @Test
    fun `L3 R4 作用域取消时不启动对齐器也不产生任何写入`() = runTest {
        val childScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val pager = FakeTabPagerAdapter(initialPage = 0)
        val audit = RouteAuditRecorder(initialRoute = AppPage.Today)
        val guard = TabScrollGuard()
        val coord = TabRouteCoordinator(
            mainPages = mainPages,
            tabGuard = guard,
            pager = pager,
            currentRoute = { audit.route },
            writeRoute = audit::writeRoute,
            scope = childScope,
            audit = audit
        )

        val gate = CompletableDeferred<Unit>()
        pager.nextAnimateGate = gate
        childScope.launch { coord.runProgrammaticScroll(target = 2, reduceMotion = false, seq = 1) }
        runCurrent()

        // 整个组合作用域取消
        pager.setState(scrolling = false, current = 1, settled = 1)
        childScope.cancel()
        advanceUntilIdle()

        assertTrue("作用域取消后不得有任何入口调用或状态变更", audit.entries.isEmpty())
        assertTrue(audit.stateMutations.isEmpty())
        assertEquals(AppPage.Today, audit.route)
    }

    @Test
    fun `L3 R6 未落定窗口继续等待而非提前退出 落定后写入 且超时不写`() = runTest {
        // Part 1: 未落定 -> 继续等 -> 落定后成功写入
        val pager1 = FakeTabPagerAdapter(initialPage = 0)
        val audit1 = RouteAuditRecorder(initialRoute = AppPage.Today)
        val coord1 = TabRouteCoordinator(
            mainPages = mainPages,
            tabGuard = TabScrollGuard(),
            pager = pager1,
            currentRoute = { audit1.route },
            writeRoute = audit1::writeRoute,
            scope = this,
            settleTimeoutMs = 1_000L,
            audit = audit1
        )
        val gate1 = CompletableDeferred<Unit>()
        pager1.nextAnimateGate = gate1
        launch { coord1.runProgrammaticScroll(target = 2, reduceMotion = false, seq = 1) }
        runCurrent()

        // 取消时处于 S10 未稳定窗口：isScrollInProgress=false 但 currentPage(1) != settledPage(0)
        pager1.setState(scrolling = false, current = 1, settled = 0)
        gate1.cancel(CancellationException("interrupt"))
        advanceTimeBy(400L)
        runCurrent()
        assertTrue("未落定期间不得提前写入", audit1.stateMutations.isEmpty())

        // 600ms 时落定到 Stats(1) -> 对齐器完成写入
        pager1.setState(scrolling = false, current = 1, settled = 1)
        advanceUntilIdle()
        assertEquals(AppPage.Stats, audit1.route)
        assertEquals(listOf(AppPage.Stats), audit1.stateMutations)
        assertEquals(0, audit1.unattributedMutations)

        // Part 2: 始终未落定 -> 虚拟超时后安静退出，零写入
        val pager2 = FakeTabPagerAdapter(initialPage = 0)
        val audit2 = RouteAuditRecorder(initialRoute = AppPage.Today)
        val coord2 = TabRouteCoordinator(
            mainPages = mainPages,
            tabGuard = TabScrollGuard(),
            pager = pager2,
            currentRoute = { audit2.route },
            writeRoute = audit2::writeRoute,
            scope = this,
            settleTimeoutMs = 1_000L,
            audit = audit2
        )
        val gate2 = CompletableDeferred<Unit>()
        pager2.nextAnimateGate = gate2
        launch { coord2.runProgrammaticScroll(target = 2, reduceMotion = false, seq = 2) }
        runCurrent()
        pager2.setState(scrolling = true, current = 1, settled = 0)
        gate2.cancel(CancellationException("interrupt"))
        advanceTimeBy(1_500L)
        advanceUntilIdle()

        assertTrue("超时后必须放弃，不得写入任何路由", audit2.stateMutations.isEmpty())
        assertEquals(AppPage.Today, audit2.route)
    }

    @Test
    fun `L3 R12-A 瞬时滚动直达目标页 不经过中间页 且经统一入口归因完整`() = runTest {
        val pager = FakeTabPagerAdapter(initialPage = 0)
        val audit = RouteAuditRecorder(initialRoute = AppPage.Today)
        val coord = TabRouteCoordinator(
            mainPages = mainPages,
            tabGuard = TabScrollGuard(),
            pager = pager,
            currentRoute = { audit.route },
            writeRoute = audit::writeRoute,
            scope = this,
            audit = audit
        )

        coord.runProgrammaticScroll(target = 2, reduceMotion = true, seq = 1)
        advanceUntilIdle()

        assertEquals(AppPage.Calendar, audit.route)
        assertEquals("不得写入中间页 Stats", listOf(AppPage.Calendar), audit.stateMutations)
        assertEquals(1, audit.accepted.size)
        assertEquals(TabRouteCoordinator.BASIS_CAPTURED_AT_BEGIN, audit.accepted.single().genBasis)
        assertEquals(0, audit.unattributedMutations)
    }

    @Test
    fun `L3 R12-B 正常动画取消交接 UNKNOWN 走保守补偿 落定后无抖动直达最终页`() = runTest {
        val pager = FakeTabPagerAdapter(initialPage = 0)
        val audit = RouteAuditRecorder(initialRoute = AppPage.Today)
        val coord = TabRouteCoordinator(
            mainPages = mainPages,
            tabGuard = TabScrollGuard(),
            pager = pager,
            currentRoute = { audit.route },
            writeRoute = audit::writeRoute,
            scope = this,
            audit = audit
        )

        val gate = CompletableDeferred<Unit>()
        pager.nextAnimateGate = gate
        launch { coord.runProgrammaticScroll(target = 2, reduceMotion = false, seq = 1) }
        runCurrent()

        // 模拟 userDragging 未能观测到的未知取消（observed=UNKNOWN -> action=CONSERVATIVE_COMPENSATE）
        pager.isUserDragging = false
        pager.setState(scrolling = true, current = 1, settled = 0)
        gate.cancel(CancellationException("unknown cancel"))
        runCurrent()

        // 取消瞬间零写入
        assertTrue(audit.stateMutations.isEmpty())

        // 最终落定到 Stats(1)
        pager.setState(scrolling = false, current = 1, settled = 1)
        advanceUntilIdle()

        assertEquals(AppPage.Stats, audit.route)
        assertEquals("应精确只写一次最终落定页，无先写错再写对", listOf(AppPage.Stats), audit.stateMutations)
        assertEquals(TabRouteCoordinator.BASIS_CAPTURED_AT_START, audit.accepted.single().genBasis)
        assertEquals(0, audit.unattributedMutations)
    }

    @Test
    fun `L3 R10 幂等性与 CR-17 横滑跟手回写经统一入口`() = runTest {
        val pager = FakeTabPagerAdapter(initialPage = 0)
        val audit = RouteAuditRecorder(initialRoute = AppPage.Today)
        val coord = TabRouteCoordinator(
            mainPages = mainPages,
            tabGuard = TabScrollGuard(),
            pager = pager,
            currentRoute = { audit.route },
            writeRoute = audit::writeRoute,
            scope = this,
            audit = audit
        )

        // CR-17：正常横滑期间 isScrollInProgress=true，过半即翻（currentPage=1）必须允许提交
        pager.setState(scrolling = true, current = 1, settled = 0)
        coord.onSnapshotPage(1)
        assertEquals(AppPage.Stats, audit.route)
        assertEquals(listOf(AppPage.Stats), audit.stateMutations)
        assertEquals(TabRouteCoordinator.BASIS_OBSERVED_AT_EMIT, audit.accepted.single().genBasis)

        // 重复回写同页 -> 幂等短路，不产生第二次状态变化
        coord.onSnapshotPage(1)
        assertEquals(1, audit.stateMutations.size)
        assertTrue(audit.skipped.contains("idem"))
        assertEquals(0, audit.unattributedMutations)
    }

    @Test
    fun `M-FIX4 对齐器父作用域取消必须记录 scope-cancelled 跳过事件`() = runTest {
        val pager = FakeTabPagerAdapter(initialPage = 0)
        val audit = RouteAuditRecorder(initialRoute = AppPage.Today)
        val childScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val coord = TabRouteCoordinator(
            mainPages = mainPages,
            tabGuard = TabScrollGuard(),
            pager = pager,
            currentRoute = { audit.route },
            writeRoute = audit::writeRoute,
            scope = childScope,
            audit = audit
        )

        val gate = CompletableDeferred<Unit>()
        pager.nextAnimateGate = gate
        val navJob = childScope.launch { coord.runProgrammaticScroll(target = 2, reduceMotion = false, seq = 1) }
        runCurrent()
        // 触发取消进入 settle 对齐器等待态
        navJob.cancel(CancellationException("interrupted"))
        runCurrent()
        // 取消父作用域，验证 scope-cancelled 日志回调
        childScope.cancel()
        runCurrent()
        assertTrue("父作用域取消应记录 scope-cancelled", audit.alignerSkipped.contains("scope-cancelled"))
    }

    @Test
    fun `M-FIX5 审计记录器按 commitId 与目标页强绑定，错页写入必须被捕获`() {
        val audit = RouteAuditRecorder(initialRoute = AppPage.Today)
        audit.onCommitAccepted(
            commitId = 42L,
            targetIndex = 1,
            targetPage = AppPage.Stats,
            gen = 1L,
            genSource = GenSource.NONE,
            genBasis = TabRouteCoordinator.BASIS_OBSERVED_AT_EMIT
        )
        // 模拟错写 Calendar 而非批准的 Stats
        audit.writeRoute(AppPage.Calendar)
        assertEquals(1, audit.mismatchedTargetMutations)
        assertEquals(1, audit.unattributedMutations)
        assertTrue(audit.correlatedCommits.isEmpty())
    }
}
