package com.screentrace.app.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.data.IngestPipeline
import com.screentrace.app.data.RoomUsageRepository
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.parse.AppUsageRow
import com.screentrace.app.data.parse.DayMetrics
import com.screentrace.app.data.parse.ParseResult
import com.screentrace.app.data.parse.SessionSpan
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
 * CR-15 评审 P1-2：统计页「时段分布」的**接线**回归。
 *
 * `DailyAveragePeriodsTest` 只打靶 `dailyAveragePeriods` 纯函数，把 `AppViewModel`
 * 里的调用改回 `periodBuckets(statsHours)`（区间累计）时 360 测仍全绿。这里走
 * 「真 Room 入库 7 天 → 真 ViewModel.loadAll → 读 statsState.periods」，把接线钉住：
 * 去掉日均除法必须变红。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StatsPeriodsWiringTest {

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
    fun `统计页时段分布用有记录日日均而不是区间累计`() = runTest(dispatcher) {
        ingestSevenDays()
        val vm = AppViewModel(repository = repository)
        advanceUntilIdle()

        val data = vm.statsState.value.data
        assertNotNull("7 天都有 day_summary，统计页必须有数据", data)
        assertEquals(7, data!!.summary.recordedDays)
        // 每天 06–12 段 6 小时 × 10 分钟 = 60 分钟；7 天累计 420 分钟。
        // 日均 = 420 / 7 = 60；若接线退回累计值，这里会是 420。
        assertEquals("接线必须走 dailyAveragePeriods", 60, data.periods[0])
        assertEquals(60, data.periods.sum())
    }

    // ---------- helpers ----------

    /** 连续 7 天：每天都有 day_summary，且 06–11 时每小时 10 分钟。 */
    private suspend fun ingestSevenDays() {
        val days = (0L..6L).map { day.minusDays(it) }
        val result = ParseResult(
            days = days.associateWith { DayMetrics(120, 100, 5, 10, 20) },
            hourMinutes = days.associateWith {
                List(24) { hour -> if (hour in 6..11) 10 else 0 }
            },
            appUsage = days.map { AppUsageRow(it, "com.tencent.mm", 60 * 60_000L, 5) },
            unlockEvents = emptyList(),
            notificationEvents = emptyList(),
            screenSessions = days.map { d ->
                val start = d.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
                SessionSpan(start, start + 600_000L)
            }
        )
        org.junit.Assert.assertTrue(
            "七天数据必须能正常入库",
            IngestPipeline(db, zone = zone).ingest(result, "shizuku", nowMs()).ok
        )
    }

    private fun nowMs(): Long = day.atTime(20, 0).atZone(zone).toInstant().toEpochMilli()
}
