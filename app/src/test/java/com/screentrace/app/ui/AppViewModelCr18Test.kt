package com.screentrace.app.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.PeriodSummaryCalculator
import com.screentrace.app.contract.PeriodType
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CR-18 端到端回归（负责人 2026-11-15 两条裁决）：
 * ① 排行弹层只给单日 + 显式选日退出周期窗口；
 * ② 解锁 / 通知详情取数跟随归属状态（今日侧 todayEffective、统计侧 statsSelection）。
 *
 * 断言打在 `MetricDetail.selection`（页面实际用的取数窗口）与排行
 * `summary.selection` 上，改坏取数窗口 / 不清 rankingScope 必须变红。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppViewModelCr18Test {

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
    fun `统计侧详情跟随 statsSelection 原窗口`() = runTest(dispatcher) {
        ingest()
        val vm = AppViewModel(repository = repository)
        advanceUntilIdle()
        vm.selectMain(AppPage.Stats)
        vm.applySelection(DateSelection.lastDays(3))
        advanceUntilIdle()
        vm.openUnlockDetail()
        advanceUntilIdle()

        val detail = vm.unlockDetail.value.data
        assertNotNull(detail)
        assertEquals("详情取数窗口必须是统计侧选的区间", DateSelection.lastDays(3), detail!!.selection)
        // 与修复前的差异钉死：旧实现恒用 todayEffective（单日），这里必须是区间窗口
        assertNotEquals(DateSelection.today(), detail.selection)
    }

    @Test
    fun `今日侧详情与双卡同源锚定单日`() = runTest(dispatcher) {
        ingest()
        val vm = AppViewModel(repository = repository)
        advanceUntilIdle()
        vm.openUnlockDetail()
        advanceUntilIdle()

        val detail = vm.unlockDetail.value.data
        assertNotNull(detail)
        assertEquals("归属今日必须用 todayEffective（单日）", DateSelection.today(), detail!!.selection)
    }

    @Test
    fun `切 tab 后打开详情立即按归属重算`() = runTest(dispatcher) {
        ingest()
        val vm = AppViewModel(repository = repository)
        advanceUntilIdle()
        // 详情先随今日侧载入
        assertEquals(DateSelection.today(), vm.unlockDetail.value.data!!.selection)

        // 切 tab 不触发重载；打开详情（open*Detail 带 loadAll）才按 returnTo 重算
        vm.selectMain(AppPage.Stats)
        vm.openNotificationDetail()
        advanceUntilIdle()
        assertEquals(
            "打开统计侧详情必须立即换成 statsSelection 窗口",
            vm.statsSelection.value,
            vm.notificationDetail.value.data!!.selection
        )
    }

    @Test
    fun `排行上显式选日退出周期窗口`() = runTest(dispatcher) {
        ingest()
        val vm = AppViewModel(repository = repository)
        advanceUntilIdle()

        // 周期口径进入排行（§7-26）：排行按周期聚合
        val type = PeriodType.WEEK
        val range = type.natural(day)
        val period = PeriodSummaryCalculator.build(
            type = type,
            today = day,
            trend = repository.dailyTrend(range.selection),
            previousSummary = repository.summary(range.previousEqualLength().selection),
            apps = repository.appRanking(range.selection, limit = Int.MAX_VALUE),
            hours = repository.hourlyDistribution(range.selection)
        )
        vm.openRankingFor(period)
        advanceUntilIdle()
        assertEquals(range.selection, vm.rankingState.value.data!!.summary.selection)

        // CR-18：显式选日 → 退出周期窗口，按所选单日排行（否则「选了没反应」重现）
        vm.applySelection(DateSelection.Day(day.minusDays(1)))
        advanceUntilIdle()
        assertEquals(
            "选日后排行必须切到该单日",
            DateSelection.Day(day.minusDays(1)),
            vm.rankingState.value.data!!.summary.selection
        )
    }

    private suspend fun ingest() {
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
