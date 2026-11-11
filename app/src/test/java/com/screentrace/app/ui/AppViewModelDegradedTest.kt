package com.screentrace.app.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.data.IngestPipeline
import com.screentrace.app.data.RoomUsageRepository
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.parse.AppUsageRow
import com.screentrace.app.data.parse.DayMetrics
import com.screentrace.app.data.parse.ParseResult
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 6b-6d 评审 **P0-1 的端到端回归**：降级通道（`UsageStats`，没有 Shizuku 的普通用户的
 * 唯一通道）拉取成功之后，四个页面到底显示什么。
 *
 * 这条 bug 的本质是「入口判据选错了表」：数据入库成功、仓库也读得出来，但
 * `loadAll` 用 `Summary.hasAnyRecord`（只数 `day_summary`）当整页空态的开关，
 * 而降级通道按设计**不写** `day_summary`——于是四个页面一起显示「尚无使用记录」，
 * 拉取日志还是 `ok=true`。用户看到的是「App 坏了」。
 *
 * 所以这里不测中间变量，直接走「真 Room 入库 → 真 AppViewModel.loadAll → 读 UiState」：
 * 1. 只有应用级数据时，排行页**必须**有数据（旧实现是 null）；
 * 2. 今日/统计/日历/详情页必须是 `NO_EVENT_DATA` 空态（不是 `NO_DATA`，也不是假 0）；
 * 3. 三表全空时仍然是 `NO_DATA`（别为了修 1 把 3 改坏）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppViewModelDegradedTest {

    private val dispatcher = StandardTestDispatcher()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val day: LocalDate = LocalDate.now(zone)

    private lateinit var db: ScreenTraceDatabase
    private lateinit var repository: RoomUsageRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ScreenTraceDatabase::class.java
        )
            .allowMainThreadQueries()
            // 直接执行器：让 Room 的挂起查询在调用线程内同步完成，
            // `advanceUntilIdle()` 才能真正等到 loadAll 整个跑完。
            // 用默认的异步执行器时，协程是「挂在真线程上」而不是「挂在测试调度器上」，
            // advanceUntilIdle 会立刻返回，读到的永远是 loading=true 的初始态（假绿）。
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        repository = RoomUsageRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    // ---------- 1. 降级通道：只有 app_usage / hour_usage ----------

    @Test
    fun `只有应用级数据时排行页出数据而不是空态`() = runTest(dispatcher) {
        ingestDegraded()
        val vm = AppViewModel(repository = repository)
        advanceUntilIdle()

        val ranking = vm.rankingState.value
        assertNull("不许报错", ranking.error)
        assertNotNull("P0-1：库里有应用数据，排行页必须有数据", ranking.data)
        assertEquals(listOf("com.tencent.mm", "tv.danmaku.bili"), ranking.data!!.apps.map { it.pkg })
        // 摘要不能用 summary.totalScreenUnlockMin（那是 0），必须换成应用合计
        assertEquals(45, ranking.data!!.appsTotalMinutes)
    }

    @Test
    fun `只有应用级数据时其余四页是「没有事件源」空态而不是「无数据」`() = runTest(dispatcher) {
        ingestDegraded()
        val vm = AppViewModel(repository = repository)
        advanceUntilIdle()

        for ((name, state) in listOf(
            "今日" to vm.todayState.value,
            "统计" to vm.statsState.value,
            "日历" to vm.calendarState.value,
            "解锁详情" to vm.unlockDetail.value,
            "通知详情" to vm.notificationDetail.value
        )) {
            assertNull("$name 不许把 0 当数据渲染", state.data)
            assertEquals(
                "$name 的空态原因必须是「有数据但没有事件源」，否则文案会骗人",
                EmptyReason.NO_EVENT_DATA,
                state.empty
            )
        }
    }

    @Test
    fun `只有应用级数据时日期筛选不被误拦`() = runTest(dispatcher) {
        ingestDegraded()
        val vm = AppViewModel(repository = repository)
        advanceUntilIdle()

        vm.applySelection(DateSelection.Day(day))
        advanceUntilIdle()

        // 旧判据（summary.hasAnyRecord）在降级通道下恒为 false，
        // 会让每一次选日期都被拦「该时段尚无数据」——用户只能看今天
        assertNull("降级通道下选日期不该被拦", vm.notice.value)
        assertNotNull("选中后排行仍应有数据", vm.rankingState.value.data)
    }

    @Test
    fun `覆盖率统计不把「只有应用数据」当成从未拉过`() = runTest(dispatcher) {
        ingestDegraded()
        val coordinator = com.screentrace.app.data.pull.PullCoordinator(
            db = db,
            pipeline = IngestPipeline(db),
            providers = emptyList()
        )

        val coverage = coordinator.historyCoverage()

        // 批 7 首启向导读 hasAny 决定要不要再引导一遍
        assertTrue("有应用数据就必须 hasAny = true", coverage.hasAny)
        assertEquals(day, coverage.firstDate)
        assertEquals(day, coverage.lastDate)
    }

    // ---------- 2. 三表全空：仍然是「无数据」 ----------

    @Test
    fun `三表全空时六处状态都是无数据`() = runTest(dispatcher) {
        val vm = AppViewModel(repository = repository)
        advanceUntilIdle()

        assertNull(vm.todayState.value.data)
        assertEquals(EmptyReason.NO_DATA, vm.todayState.value.empty)
        assertEquals(EmptyReason.NO_DATA, vm.statsState.value.empty)
        assertEquals(EmptyReason.NO_DATA, vm.calendarState.value.empty)
        assertEquals(EmptyReason.NO_DATA, vm.rankingState.value.empty)
        assertEquals(EmptyReason.NO_DATA, vm.unlockDetail.value.empty)
        assertEquals(EmptyReason.NO_DATA, vm.notificationDetail.value.empty)
    }

    @Test
    fun `三表全空时选日期仍然被拦并提示`() = runTest(dispatcher) {
        val vm = AppViewModel(repository = repository)
        advanceUntilIdle()

        vm.applySelection(DateSelection.Day(day.minusDays(3)))
        advanceUntilIdle()

        assertEquals("该时段尚无数据，请先拉取", vm.notice.value)
    }

    // ---------- 3. 主通道（有 day_summary）：不许被修坏 ----------

    @Test
    fun `主通道数据下四页照常有数据`() = runTest(dispatcher) {
        ingestFull()
        val vm = AppViewModel(repository = repository)
        advanceUntilIdle()

        assertNotNull(vm.todayState.value.data)
        assertNotNull(vm.statsState.value.data)
        assertNotNull(vm.calendarState.value.data)
        assertNotNull(vm.rankingState.value.data)
        assertNull(vm.todayState.value.empty)
        // 主通道下排行摘要仍读屏幕时长口径
        assertEquals(400, vm.rankingState.value.data!!.summary.totalScreenUnlockMin)
    }

    // ---------- helpers ----------

    /** 降级通道的产物：days 为空，只有应用与小时矩阵（与 UsageStatsUsageProvider 一致）。 */
    private suspend fun ingestDegraded() {
        val result = ParseResult(
            days = emptyMap(),
            hourMinutes = mapOf(day to List(24) { 3 }),
            appUsage = listOf(
                AppUsageRow(day, "com.tencent.mm", 30 * 60_000L, 12),
                AppUsageRow(day, "tv.danmaku.bili", 15 * 60_000L, 4)
            ),
            unlockEvents = emptyList(),
            notificationEvents = emptyList()
        )
        val ingested = IngestPipeline(db).ingest(result, "usage_stats", nowMs())
        assertTrue("降级产物必须能正常入库", ingested.ok)
        assertFalse("有应用数据就不是空 dump", result.isEmpty)
    }

    /** 主通道的产物：含 day_summary（因此四页都该有数据）。 */
    private suspend fun ingestFull() {
        val result = ParseResult(
            days = mapOf(
                day to DayMetrics(
                    screenMin = 420,
                    screenUnlockMin = 400,
                    unlocks = 22,
                    launches = 61,
                    longestSessionMin = 35
                )
            ),
            hourMinutes = mapOf(day to List(24) { 10 }),
            appUsage = listOf(AppUsageRow(day, "com.tencent.mm", 120 * 60_000L, 30)),
            unlockEvents = listOf(
                com.screentrace.app.contract.UnlockEvent(nowMs() - 3_600_000L)
            ),
            notificationEvents = emptyList()
        )
        assertTrue(IngestPipeline(db).ingest(result, "checkin", nowMs()).ok)
    }

    private fun nowMs(): Long = day.atTime(20, 0).atZone(zone).toInstant().toEpochMilli()

    // ---------- 5. 后台已入库、前台被节流 ----------

    @Test
    fun `后台已入库但前台被节流时界面必须补读一次`() = runTest(dispatcher) {
        // 真机首装必现：冷启动时 WorkManager 的 Worker 先把 4MB dump 拉完入库，
        // 界面随后的 pullOnOpen 被 30 分钟节流挡成 Throttled。旧实现这条分支只刷
        // coverage、不重读——于是「数据明明在库里，界面显示尚无使用记录」，且一直不变。
        val now = System.currentTimeMillis()
        val coordinator = com.screentrace.app.data.pull.PullCoordinator(
            db = db,
            pipeline = IngestPipeline(db),
            providers = emptyList(),
            clock = { now }
        )
        val vm = AppViewModel(repository = repository, pulls = coordinator)
        advanceUntilIdle()
        assertNull("前提：启动那一刻库里确实没数据", vm.todayState.value.data)

        // 模拟后台 Worker 完成拉取：入库，并写一条「刚才拉过」的日志
        val ingested = IngestPipeline(db).ingest(
            ParseResult(
                days = mapOf(day to DayMetrics(420, 400, 22, 61, 35)),
                hourMinutes = mapOf(day to List(24) { 17 }),
                appUsage = listOf(AppUsageRow(day, "com.tencent.mm", 120 * 60_000L, 30)),
                unlockEvents = emptyList(),
                notificationEvents = emptyList()
            ),
            "shizuku",
            now
        )
        assertTrue("前提：这次入库成功", ingested.ok)

        vm.pullOnOpen()
        advanceUntilIdle()

        assertNull("节流下不该报错", vm.pullError.value)
        assertNotNull(
            "节流只代表「这次没拉」，不代表库里没新数据——必须补读一次",
            vm.todayState.value.data
        )
        assertNotNull("排行页同样要有数据", vm.rankingState.value.data)
    }
}
