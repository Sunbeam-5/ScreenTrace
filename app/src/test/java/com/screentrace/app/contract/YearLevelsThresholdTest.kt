package com.screentrace.app.contract

import com.screentrace.app.ui.AppPage
import com.screentrace.app.ui.AppViewModel
import com.screentrace.app.uikit.heatmapLegendDescription
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 批 10b（CR-10）：年视图色阶跟随低使用阈值 `x` 单测。
 * 覆盖行为变异锚点 M-10B1、M-10B2、M-10B3。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class YearLevelsThresholdTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `M_10B1_yearLevel按x_2x_3x动态切分五档_默认120分钟与原口径一致`() {
        // 默认 x = 120 (2h / 4h / 6h)
        assertEquals(0, UsageCalculator.yearLevel(0, recorded = false))
        assertEquals(0, UsageCalculator.yearLevel(0, recorded = true))
        assertEquals(1, UsageCalculator.yearLevel(119, recorded = true))
        assertEquals(2, UsageCalculator.yearLevel(120, recorded = true))
        assertEquals(2, UsageCalculator.yearLevel(239, recorded = true))
        assertEquals(3, UsageCalculator.yearLevel(240, recorded = true))
        assertEquals(3, UsageCalculator.yearLevel(359, recorded = true))
        assertEquals(4, UsageCalculator.yearLevel(360, recorded = true))

        // 自定义 x = 60 (1h / 2h / 3h)
        assertEquals(0, UsageCalculator.yearLevel(0, recorded = true, lowThresholdMin = 60))
        assertEquals(1, UsageCalculator.yearLevel(59, recorded = true, lowThresholdMin = 60))
        assertEquals(2, UsageCalculator.yearLevel(60, recorded = true, lowThresholdMin = 60))
        assertEquals(3, UsageCalculator.yearLevel(120, recorded = true, lowThresholdMin = 60))
        assertEquals(4, UsageCalculator.yearLevel(180, recorded = true, lowThresholdMin = 60))

        // 自定义 x = 180 (3h / 6h / 9h)
        assertEquals(1, UsageCalculator.yearLevel(179, recorded = true, lowThresholdMin = 180))
        assertEquals(2, UsageCalculator.yearLevel(180, recorded = true, lowThresholdMin = 180))
        assertEquals(3, UsageCalculator.yearLevel(360, recorded = true, lowThresholdMin = 180))
        assertEquals(4, UsageCalculator.yearLevel(540, recorded = true, lowThresholdMin = 180))
    }

    @Test
    fun `M_10B2_AppViewModel修改低使用阈值后年视图yearLevels联动重算`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val records = listOf(
            DaySummary(
                date = today,
                screenMin = 150,
                screenUnlockMin = 150,
                unlocks = 10,
                launches = 20,
                longestSessionMin = 40,
                pulledAt = 1_700_000_000_000L
            )
        )
        val fakeRepo = object : UsageRepository by EmptyUsageRepository() {
            override suspend fun hasAnyData(): Boolean = true
            override suspend fun hasDataIn(selection: DateSelection): Boolean = true
            override suspend fun summary(selection: DateSelection): Summary =
                UsageCalculator.summarize(selection, records)
            override suspend fun extremes(selection: DateSelection, lowThresholdMin: Int): ExtremesInfo =
                UsageCalculator.extremes(selection, records, lowThresholdMin)
            override suspend fun yearLevels(
                anchor: LocalDate,
                weeks: Int,
                lowThresholdMin: Int
            ): List<CalendarLevel> = UsageCalculator.yearLevels(anchor, weeks, records, lowThresholdMin)
        }

        val vm = AppViewModel(repository = fakeRepo)
        vm.selectMain(AppPage.Calendar)
        advanceUntilIdle()

        // 默认 120 分钟下，150 分钟处于 [120, 240) → level 2
        val levelAt120 = vm.calendarState.value.data?.yearLevels?.firstOrNull { it.date == today }?.level
        assertEquals(2, levelAt120)

        // 阈值调低为 60 分钟，150 分钟处于 [120, 180) → level 3
        vm.setLowThresholdMin(60)
        advanceUntilIdle()
        val levelAt60 = vm.calendarState.value.data?.yearLevels?.firstOrNull { it.date == today }?.level
        assertEquals(3, levelAt60)

        // 阈值调高为 180 分钟，150 分钟处于 (0, 180) → level 1
        vm.setLowThresholdMin(180)
        advanceUntilIdle()
        val levelAt180 = vm.calendarState.value.data?.yearLevels?.firstOrNull { it.date == today }?.level
        assertEquals(1, levelAt180)
    }

    @Test
    fun `M_10B3_heatmapLegendDescription与当前低使用阈值同源`() {
        val desc180 = heatmapLegendDescription(180)
        assertTrue(desc180.contains("3 小时") || desc180.contains("3小时"))
        assertTrue(desc180.contains("6小时"))
        assertTrue(desc180.contains("9小时"))
    }
}
