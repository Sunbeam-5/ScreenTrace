package com.screentrace.app.ui

import com.screentrace.app.contract.CalendarLevel
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.DaySummary
import com.screentrace.app.contract.EmptyUsageRepository
import com.screentrace.app.contract.ExtremesInfo
import com.screentrace.app.contract.Summary
import com.screentrace.app.contract.UsageCalculator
import com.screentrace.app.contract.UsageRepository
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 跨自然日日期窗口自动推进单测：覆盖长期亮屏常驻前台跨午夜场景
 * （2026-11-26 Redmi 实测前夜常驻导致今日页停留在 11-25 显示 23h46m）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DateRolloverTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun fakeRepo(
        recordsByDate: Map<LocalDate, DaySummary>,
        onSummaryQuery: () -> Unit = {}
    ): UsageRepository = object : UsageRepository by EmptyUsageRepository() {
        private val allRecords = recordsByDate.values.toList()
        override suspend fun hasAnyData(): Boolean = allRecords.isNotEmpty()
        override suspend fun hasDataIn(selection: DateSelection): Boolean = allRecords.any {
            !it.date.isBefore(selection.startDate) && !it.date.isAfter(selection.endDate)
        }
        override suspend fun summary(selection: DateSelection): Summary {
            onSummaryQuery()
            return UsageCalculator.summarize(selection, allRecords)
        }
        override suspend fun extremes(selection: DateSelection, lowThresholdMin: Int): ExtremesInfo =
            UsageCalculator.extremes(selection, allRecords, lowThresholdMin)
        override suspend fun yearLevels(
            anchor: LocalDate,
            weeks: Int,
            lowThresholdMin: Int
        ): List<CalendarLevel> = UsageCalculator.yearLevels(anchor, weeks, allRecords, lowThresholdMin)
    }

    @Test
    fun `M_DR1_常驻前台跨自然日时今日_统计_跨月日历自动推进`() = runTest(dispatcher) {
        val nov25 = LocalDate.of(2026, 11, 25)
        val nov26 = LocalDate.of(2026, 11, 26)
        val nov30 = LocalDate.of(2026, 11, 30)
        val dec01 = LocalDate.of(2026, 12, 1)

        var clockToday = nov25
        val records = mapOf(
            nov25 to DaySummary(nov25, screenMin = 1426, screenUnlockMin = 1426, unlocks = 0, launches = 121, longestSessionMin = 545, pulledAt = 1L),
            nov26 to DaySummary(nov26, screenMin = 499, screenUnlockMin = 499, unlocks = 0, launches = 10, longestSessionMin = 499, pulledAt = 2L),
            nov30 to DaySummary(nov30, screenMin = 300, screenUnlockMin = 300, unlocks = 5, launches = 20, longestSessionMin = 90, pulledAt = 3L),
            dec01 to DaySummary(dec01, screenMin = 120, screenUnlockMin = 120, unlocks = 2, launches = 8, longestSessionMin = 40, pulledAt = 4L)
        )

        val vm1 = AppViewModel(repository = fakeRepo(records), todayProvider = { clockToday })
        advanceUntilIdle()
        assertEquals(DateSelection.Day(nov25), vm1.todaySelection.value)
        assertEquals(1426, vm1.todayState.value.data?.summary?.totalScreenUnlockMin)

        // 模拟常驻亮屏跨过午夜到 11-26，分别验证 onDateChanged / reload / onResumeForeground
        clockToday = nov26
        vm1.onDateChanged()
        advanceUntilIdle()
        assertEquals(DateSelection.Day(nov26), vm1.todaySelection.value)
        assertEquals(499, vm1.todayState.value.data?.summary?.totalScreenUnlockMin)
        assertEquals(DateSelection.Range(LocalDate.of(2026, 11, 20), nov26), vm1.statsSelection.value)

        // 验证 onResumeForeground() 与 reload() 同样能自动推进跨日和跨月
        clockToday = nov30
        vm1.reload()
        advanceUntilIdle()
        assertEquals(DateSelection.Day(nov30), vm1.todaySelection.value)
        assertEquals(YearMonth.of(2026, 11), vm1.calendarMonth.value)

        clockToday = dec01
        vm1.onResumeForeground()
        advanceUntilIdle()
        assertEquals(DateSelection.Day(dec01), vm1.todaySelection.value)
        assertEquals(120, vm1.todayState.value.data?.summary?.totalScreenUnlockMin)
        assertEquals(DateSelection.lastDays(7, dec01), vm1.statsSelection.value)
        assertEquals(YearMonth.of(2026, 12), vm1.calendarMonth.value)
    }

    @Test
    fun `M_DR2_用户显式选择的历史日期与区间在跨午夜后不被覆盖`() = runTest(dispatcher) {
        val nov20 = LocalDate.of(2026, 11, 20)
        val nov10 = LocalDate.of(2026, 11, 10)
        val nov15 = LocalDate.of(2026, 11, 15)
        val nov25 = LocalDate.of(2026, 11, 25)
        val nov26 = LocalDate.of(2026, 11, 26)
        var clockToday = nov25
        val records = mapOf(
            nov10 to DaySummary(nov10, 100, 100, 1, 1, 30, 1L),
            nov15 to DaySummary(nov15, 110, 110, 1, 1, 30, 1L),
            nov20 to DaySummary(nov20, 200, 200, 2, 2, 50, 1L),
            nov25 to DaySummary(nov25, 1426, 1426, 0, 10, 500, 1L),
            nov26 to DaySummary(nov26, 499, 499, 0, 5, 499, 2L)
        )
        val vm = AppViewModel(repository = fakeRepo(records), todayProvider = { clockToday })
        advanceUntilIdle()

        vm.applySelection(DateScope.TODAY, DateSelection.Day(nov20))
        advanceUntilIdle()
        vm.applySelection(DateScope.STATS, DateSelection.Range(nov10, nov15))
        advanceUntilIdle()

        clockToday = nov26
        vm.onDateChanged()
        advanceUntilIdle()

        assertEquals(DateSelection.Day(nov20), vm.todaySelection.value)
        assertEquals(DateSelection.Range(nov10, nov15), vm.statsSelection.value)
    }

    @Test
    fun `M_DR3_同一自然日内重复收到日期广播为空操作`() = runTest(dispatcher) {
        val nov26 = LocalDate.of(2026, 11, 26)
        var summaryCalls = 0
        val records = mapOf(
            nov26 to DaySummary(nov26, 499, 499, 0, 5, 499, 1L)
        )
        val vm = AppViewModel(
            repository = fakeRepo(records, onSummaryQuery = { summaryCalls++ }),
            todayProvider = { nov26 }
        )
        advanceUntilIdle()
        val baselineCalls = summaryCalls

        vm.onDateChanged()
        advanceUntilIdle()

        assertEquals(baselineCalls, summaryCalls)
    }
}
