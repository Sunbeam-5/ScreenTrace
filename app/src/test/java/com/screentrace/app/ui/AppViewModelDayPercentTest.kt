package com.screentrace.app.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CR-30-R11 第 0 步（UI 诚实化）端到端接线测试：
 * 当日有日聚合/应用记录但小时分布合计为 0（缺分母）时，`TodayData.dayPercent`
 * 必须为 `null`，禁止回落 0 导致下游渲染「日间 0% / 夜间 100%」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppViewModelDayPercentTest {

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
    fun `缺小时分母时 TodayData dayPercent 为 null 而非 0`() = runTest(dispatcher) {
        IngestPipeline(db).ingest(
            result = ParseResult(
                days = mapOf(day to DayMetrics(screenMin = 0, screenUnlockMin = 0, unlocks = 0, launches = 1, longestSessionMin = 0)),
                appUsage = listOf(AppUsageRow(day, "com.screentrace.app", totalMs = 60L * 60_000L, launches = 1)),
                hourMinutes = emptyMap(),
                unlockEvents = emptyList(),
                notificationEvents = emptyList()
            ),
            source = "test"
        )
        val vm = AppViewModel(repository = repository)
        advanceUntilIdle()

        val data = vm.todayState.value.data
        assertNotNull("有日聚合记录时今日页应加载出数据", data)
        assertNull("缺小时分布分母时 dayPercent 必须为 null，不得返回 0", data!!.dayPercent)
    }

    @Test
    fun `有小时分母时 TodayData dayPercent 按日夜占比计算`() = runTest(dispatcher) {
        val buckets = MutableList(24) { 0 }.apply {
            this[10] = 60 // 日间 60 分钟
            this[23] = 20 // 夜间 20 分钟
        }
        IngestPipeline(db).ingest(
            result = ParseResult(
                days = mapOf(day to DayMetrics(screenMin = 80, screenUnlockMin = 80, unlocks = 2, launches = 3, longestSessionMin = 30)),
                appUsage = listOf(AppUsageRow(day, "com.screentrace.app", totalMs = 80L * 60_000L, launches = 3)),
                hourMinutes = mapOf(day to buckets),
                unlockEvents = emptyList(),
                notificationEvents = emptyList()
            ),
            source = "test"
        )
        val vm = AppViewModel(repository = repository)
        advanceUntilIdle()

        val data = vm.todayState.value.data
        assertNotNull(data)
        assertEquals(75, data!!.dayPercent)
    }
}
