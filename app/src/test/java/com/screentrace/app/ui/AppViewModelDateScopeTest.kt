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
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CR-15 B 端到端回归：真 Room 入库 → 真 AppViewModel → 读两份日期状态与四个页面数据。
 *
 * 负责人的原始诉求是「今日 tab 与统计 tab 各自单独生效、互不影响，日历 tab 无按钮」。
 * 单测必须能在「applySelection 又写回同一个全局状态」时变红，所以断言同时打在
 * 状态本身和 `summary.selection`（页面实际用的窗口）上，而不只测中间变量。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppViewModelDateScopeTest {

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

    @Test
    fun `冷启动两份状态各自取默认值`() = runTest(dispatcher) {
        ingestThreeDays()
        val vm = AppViewModel(repository = repository)
        advanceUntilIdle()

        assertEquals(DateSelection.today(), vm.todaySelection.value)
        assertEquals(DateSelection.lastDays(7), vm.statsSelection.value)
        assertEquals(DateSelection.today(), vm.todayState.value.data!!.summary.selection)
        assertEquals(DateSelection.lastDays(7), vm.statsState.value.data!!.summary.selection)
    }

    @Test
    fun `今日页选单日不影响统计页`() = runTest(dispatcher) {
        ingestThreeDays()
        val vm = AppViewModel(repository = repository)
        advanceUntilIdle()

        val yesterday = DateSelection.Day(day.minusDays(1))
        vm.applySelection(yesterday)
        advanceUntilIdle()

        assertEquals("今日状态必须变成昨天", yesterday, vm.todaySelection.value)
        assertEquals("统计状态必须仍近 7 天", DateSelection.lastDays(7), vm.statsSelection.value)
        assertEquals(yesterday, vm.todayState.value.data!!.summary.selection)
        assertEquals(DateSelection.lastDays(7), vm.statsState.value.data!!.summary.selection)
    }

    @Test
    fun `统计页选区间不影响今日页`() = runTest(dispatcher) {
        ingestThreeDays()
        val vm = AppViewModel(repository = repository)
        advanceUntilIdle()

        vm.selectMain(AppPage.Stats)
        val range = DateSelection.lastDays(30)
        vm.applySelection(range)
        advanceUntilIdle()

        assertEquals("今日状态必须仍是今天", DateSelection.today(), vm.todaySelection.value)
        assertEquals("统计状态必须变成近 30 天", range, vm.statsSelection.value)
        assertEquals(DateSelection.today(), vm.todayState.value.data!!.summary.selection)
        assertEquals(range, vm.statsState.value.data!!.summary.selection)
    }

    @Test
    fun `切 tab 往返后两份状态各自保留`() = runTest(dispatcher) {
        ingestThreeDays()
        val vm = AppViewModel(repository = repository)
        advanceUntilIdle()

        val yesterday = DateSelection.Day(day.minusDays(1))
        vm.applySelection(yesterday)
        advanceUntilIdle()
        vm.selectMain(AppPage.Stats)
        val range = DateSelection.lastDays(30)
        vm.applySelection(range)
        advanceUntilIdle()
        vm.selectMain(AppPage.Today)
        advanceUntilIdle()

        assertEquals(yesterday, vm.todaySelection.value)
        assertEquals(range, vm.statsSelection.value)
        assertEquals(yesterday, vm.todayState.value.data!!.summary.selection)
        assertEquals(range, vm.statsState.value.data!!.summary.selection)
    }

    @Test
    fun `二级页日期弹层写回进入它的主页面状态`() = runTest(dispatcher) {
        ingestThreeDays()
        val vm = AppViewModel(repository = repository)
        advanceUntilIdle()

        vm.selectMain(AppPage.Stats)
        vm.openRanking()
        advanceUntilIdle()
        val older = DateSelection.Day(day.minusDays(3))
        vm.applySelection(older)
        advanceUntilIdle()

        assertEquals("从统计页进排行，不该改今日状态", DateSelection.today(), vm.todaySelection.value)
        assertEquals("应写回统计状态", older, vm.statsSelection.value)
    }

    @Test
    fun `日历页按全量历史且不随统计筛选改变`() = runTest(dispatcher) {
        ingestThreeDays()
        val vm = AppViewModel(repository = repository)
        advanceUntilIdle()

        val longestBefore = vm.calendarState.value.data!!.extremes.longestDate
        assertEquals("全量历史里最久的一天是昨天（400 分钟）", day.minusDays(1), longestBefore)

        vm.selectMain(AppPage.Stats)
        vm.applySelection(DateSelection.Day(day))
        advanceUntilIdle()

        assertNotNull(vm.calendarState.value.data)
        assertEquals(
            "日历不参与筛选，统计页切到今天也不该改变全量历史极值",
            longestBefore,
            vm.calendarState.value.data!!.extremes.longestDate
        )
    }

    // ---------- helpers ----------

    /** 今天 / 昨天 / 3 天前各有 day_summary + hour_usage + app_usage。 */
    private suspend fun ingestThreeDays() {
        val d0 = day
        val d1 = day.minusDays(1)
        val d2 = day.minusDays(3)
        val result = ParseResult(
            days = mapOf(
                d0 to DayMetrics(300, 280, 10, 20, 30),
                d1 to DayMetrics(420, 400, 22, 61, 35),
                d2 to DayMetrics(200, 180, 8, 15, 25)
            ),
            hourMinutes = mapOf(
                d0 to List(24) { 5 },
                d1 to List(24) { 10 },
                d2 to List(24) { 3 }
            ),
            appUsage = listOf(
                AppUsageRow(d0, "com.tencent.mm", 60 * 60_000L, 10),
                AppUsageRow(d1, "com.tencent.mm", 120 * 60_000L, 20),
                AppUsageRow(d2, "com.tencent.mm", 30 * 60_000L, 5)
            ),
            unlockEvents = emptyList(),
            notificationEvents = emptyList()
        )
        org.junit.Assert.assertTrue(
            "三天数据必须能正常入库",
            IngestPipeline(db).ingest(result, "shizuku", nowMs()).ok
        )
    }

    private fun nowMs(): Long = day.atTime(20, 0).atZone(zone).toInstant().toEpochMilli()
}
