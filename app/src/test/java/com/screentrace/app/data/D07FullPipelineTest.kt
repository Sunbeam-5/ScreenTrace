package com.screentrace.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.DayQuality
import com.screentrace.app.contract.PeriodSummaryCalculator
import com.screentrace.app.contract.PeriodType
import com.screentrace.app.contract.UsageCalculator
import com.screentrace.app.data.db.DataQualityValues
import com.screentrace.app.data.db.DaySummaryEntity
import com.screentrace.app.data.db.HourUsageEntity
import com.screentrace.app.data.db.PullLogEntity
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.ui.AppViewModel
import com.screentrace.app.uikit.TrendChartMath
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D-07 方案 C+ 第二批（C+b 领域、四大指标家族与 UI 契约）端到端测试（覆盖 M-CB1 ~ M-CB6）。
 * 全程走真实 Room 内存库 → RoomUsageRepository → UsageCalculator / PeriodSummaryCalculator / AppViewModel。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class D07FullPipelineTest {

    private lateinit var db: ScreenTraceDatabase
    private lateinit var repo: RoomUsageRepository
    private val dispatcher = UnconfinedTestDispatcher()
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

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
        repo = RoomUsageRepository(db = db)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun insertDay(date: LocalDate, minutes: Int, quality: String?, src: String = "dev-a") = runBlocking {
        db.daySummaryDao().insert(
            DaySummaryEntity(
                date = date.toString(),
                screenMin = minutes,
                screenUnlockMin = minutes,
                unlocks = 10,
                launches = 20,
                longestSessionMin = minutes,
                pulledAt = 1_700_000_000_000L,
                sourceDeviceId = src,
                screenQuality = quality
            )
        )
    }

    private fun insertHour(date: LocalDate, hour: Int, minutes: Int, quality: String? = DataQualityValues.EXACT) = runBlocking {
        db.hourUsageDao().insert(
            HourUsageEntity(
                date = date.toString(),
                hour = hour,
                minutes = minutes,
                sourceDeviceId = "dev-a",
                quality = quality
            )
        )
    }

    @Test
    fun firstInstallTodayOnly_averageIsNullNeverZero() = runBlocking {
        // M-CB1：拍板 2 —— 新装首日仅有今天 180m 数据，完整日为 0，日均必须为 null（UI 渲染 — 而非 0分钟）
        val today = LocalDate.of(2026, 11, 26)
        insertDay(today, 180, DataQualityValues.PARTIAL)
        db.pullLogDao().insert(PullLogEntity(pulledAt = 1_764_129_600_000L, source = "shizuku", ok = true, note = "ok"))

        val range = DateSelection.lastDays(7, today)
        val records = db.daySummaryDao().sumRange(range.startDate.toString(), range.endDate.toString()).map {
            com.screentrace.app.contract.DaySummary(
                date = LocalDate.parse(it.date),
                screenMin = it.screenMin,
                screenUnlockMin = it.screenUnlockMin,
                unlocks = it.unlocks,
                launches = it.launches,
                longestSessionMin = it.longestSessionMin,
                pulledAt = it.pulledAt,
                dayQuality = DayQuality.fromSql(it.dayQuality)
            )
        }
        val summary = UsageCalculator.summarize(range, records, today = today, lastUpdatedAtMs = db.pullLogDao().latestOkPulledAt())
        assertTrue(summary.hasAnyRecord)
        assertEquals(1, summary.recordedDays)
        assertEquals(0, summary.completeDays)
        assertEquals(1, summary.inProgressDays)
        assertNull(summary.averageScreenUnlockMin)
        assertNotNull(summary.lastUpdatedAtMs)

        // M-CB1 强化：即使 today 行质量被标为 EXACT，date.isBefore(today) 仍必须将其隔离于完整日之外
        val exactTodayOnly = listOf(
            com.screentrace.app.contract.DaySummary(
                date = today,
                screenMin = 180,
                screenUnlockMin = 180,
                unlocks = 10,
                launches = 20,
                longestSessionMin = 60,
                pulledAt = 1_700_000_000_000L,
                dayQuality = DayQuality.EXACT
            )
        )
        val exactTodaySummary = UsageCalculator.summarize(range, exactTodayOnly, today = today)
        assertEquals(0, exactTodaySummary.completeDays)
        assertNull(exactTodaySummary.averageScreenUnlockMin)
    }

    @Test
    fun statsAndPeriodAndTrendLine_shareExactSameFullDayEligibility() = runBlocking {
        // M-CB2 & M-CB4 & M-CB6：统计摘要、周期总结、趋势平均线与年视图月柱严格同源一致
        val today = LocalDate.of(2026, 11, 26) // 周四
        val mon = LocalDate.of(2026, 11, 23)
        val tue = LocalDate.of(2026, 11, 24)
        val wed = LocalDate.of(2026, 11, 25)

        insertDay(mon, 300, DataQualityValues.EXACT)
        insertDay(tue, 200, null) // 历史 LEGACY 兼容合格
        insertDay(wed, 20, DataQualityValues.PARTIAL) // 不完整日，排除
        insertDay(today, 60, DataQualityValues.PARTIAL) // 今天进行中，排除

        val weekRange = PeriodType.WEEK.natural(today)
        val trend = UsageCalculator.dailyPoints(
            weekRange.selection,
            db.daySummaryDao().sumRange(weekRange.start.toString(), weekRange.dataEnd.toString()).map {
                com.screentrace.app.contract.DaySummary(
                    date = LocalDate.parse(it.date),
                    screenMin = it.screenMin,
                    screenUnlockMin = it.screenUnlockMin,
                    unlocks = it.unlocks,
                    launches = it.launches,
                    longestSessionMin = it.longestSessionMin,
                    pulledAt = it.pulledAt,
                    dayQuality = DayQuality.fromSql(it.dayQuality)
                )
            },
            today = today
        )
        val directSummary = UsageCalculator.summarize(
            weekRange.selection,
            db.daySummaryDao().sumRange(weekRange.start.toString(), weekRange.dataEnd.toString()).map {
                com.screentrace.app.contract.DaySummary(
                    date = LocalDate.parse(it.date),
                    screenMin = it.screenMin,
                    screenUnlockMin = it.screenUnlockMin,
                    unlocks = it.unlocks,
                    launches = it.launches,
                    longestSessionMin = it.longestSessionMin,
                    pulledAt = it.pulledAt,
                    dayQuality = DayQuality.fromSql(it.dayQuality)
                )
            },
            today = today
        )
        val period = PeriodSummaryCalculator.build(
            type = PeriodType.WEEK,
            today = today,
            trend = trend,
            previousSummary = null,
            apps = emptyList(),
            hours = emptyList()
        )
        val yearPeriod = PeriodSummaryCalculator.build(
            type = PeriodType.YEAR,
            today = today,
            trend = trend,
            previousSummary = null,
            apps = emptyList(),
            hours = emptyList()
        )

        // 合格日仅 mon(300) + tue(200) -> 日均 250，completeDays=2，legacyCompleteDays=1，recordedDays=4
        assertEquals(250, directSummary.averageScreenUnlockMin)
        assertEquals(directSummary.averageScreenUnlockMin, period.summary.averageScreenUnlockMin)
        assertEquals(directSummary.completeDays, period.summary.completeDays)
        assertEquals(1, period.summary.legacyCompleteDays)
        assertEquals(4, period.summary.recordedDays)
        assertEquals(250, TrendChartMath.averageMinutes(trend, today = today))
        assertEquals(250, yearPeriod.monthTrend.single().averageMinutes)
    }

    @Test
    fun statsPeriodsAndCalendarEmptyState_useSameEligibleDateSetAndNeverBlankOnPartialHistory() = runBlocking {
        // M-CB3（时段日均分子分母同集合）& M-CB5（全库仅有 PARTIAL 日时日历非空）
        val today = LocalDate.of(2026, 11, 26)
        // 2 天 EXACT（每天 09:00 120m）+ 5 天 PARTIAL（每天 09:00 240m）
        val d1 = today.minusDays(6)
        val d2 = today.minusDays(5)
        insertDay(d1, 120, DataQualityValues.EXACT)
        insertHour(d1, 9, 120, DataQualityValues.EXACT)
        insertDay(d2, 120, DataQualityValues.EXACT)
        insertHour(d2, 9, 120, DataQualityValues.EXACT)
        for (offset in 4 downTo 0) {
            val d = today.minusDays(offset.toLong())
            insertDay(d, 240, DataQualityValues.PARTIAL)
            insertHour(d, 9, 240, DataQualityValues.PARTIAL)
        }

        val vm = AppViewModel(repository = repo, todayProvider = { today })
        val stats = vm.statsState.value.data
        assertNotNull(stats)
        // 06-12 时段日均必须等于 120m（2 天 EXACT 合计 240 / 2），绝不能把 5 天 PARTIAL 混入分子变成 720m
        assertEquals(120, stats!!.periods[0])

        // 清库后仅插 PARTIAL 日，验证日历页绝不误判空态（M-CB5）
        db.daySummaryDao().deleteAll()
        db.hourUsageDao().deleteAll()
        insertDay(today.minusDays(1), 45, DataQualityValues.PARTIAL)
        val vmPartialOnly = AppViewModel(repository = repo, todayProvider = { today })
        val cal = vmPartialOnly.calendarState.value.data
        assertNotNull("全库仅有 PARTIAL 日时日历页不得误空态", cal)
        assertTrue(cal!!.yearLevels.any { it.recorded && it.dayQuality == DayQuality.PARTIAL })
    }
}
