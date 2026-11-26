package com.screentrace.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.DaySummary
import com.screentrace.app.contract.UsageCalculator
import com.screentrace.app.data.db.DataQualityValues
import com.screentrace.app.data.db.DaySummaryEntity
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.parse.DayMetrics
import com.screentrace.app.data.parse.ParseResult
import com.screentrace.app.data.parse.SessionSpan
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CR-30 专项整改（P1-1 / D-07）：R11 数据质量端到端链路测试（覆盖 M-FIX1 ~ M-FIX3）。
 * - M-FIX1：IngestPipeline 逐日独立按当天 screenSessions 判质，禁止整批全局误标 EXACT；
 * - M-FIX2：Daos.sumRange() 跨来源保守降级（EXACT + PARTIAL -> PARTIAL）；
 * - M-FIX3：UsageCalculator 排除 PARTIAL / UNKNOWN 参与日均分母与极值，同时兼容历史 NULL 行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class R11QualityPipelineTest {

    private lateinit var db: ScreenTraceDatabase
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ScreenTraceDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun ingestPipeline_marksQualityPerDateIndependently() = runBlocking {
        // M-FIX1：Day1 有亮屏会话 -> EXACT；同批次 Day2 无会话 -> 必须为 PARTIAL，不得被 Day1 污染
        val day1 = LocalDate.of(2026, 11, 20)
        val day2 = LocalDate.of(2026, 11, 21)
        val day1Start = day1.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val day1End = day1.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()

        val pipeline = IngestPipeline(db = db, localDeviceId = { "dev-a" }, zone = zone)
        val result = ParseResult(
            days = mapOf(
                day1 to DayMetrics(screenMin = 60, screenUnlockMin = 60, unlocks = 5, launches = 10, longestSessionMin = 60),
                day2 to DayMetrics(screenMin = 30, screenUnlockMin = 30, unlocks = 2, launches = 4, longestSessionMin = 30)
            ),
            hourMinutes = emptyMap(),
            appUsage = emptyList(),
            unlockEvents = emptyList(),
            notificationEvents = emptyList(),
            screenSessions = listOf(SessionSpan(day1Start, day1End))
        )

        val pulled = pipeline.ingest(result, source = "shizuku", pulledAtMs = day1End + 1000L)
        assertTrue(pulled.ok)

        val row1 = db.daySummaryDao().get(day1.toString(), "dev-a")!!
        val row2 = db.daySummaryDao().get(day2.toString(), "dev-a")!!
        assertEquals(DataQualityValues.EXACT, row1.screenQuality)
        assertEquals(DataQualityValues.PARTIAL, row2.screenQuality)
    }

    @Test
    fun daoSumRange_appliesD07ConservativeCrossSourceDowngrade() = runBlocking {
        // M-FIX2：同日跨来源合并（dev-a=EXACT, dev-b=PARTIAL）必须保守降级为 PARTIAL，
        // 同时保持 CR-28 SUM(screenUnlockMin) 口径不变
        val date = "2026-11-22"
        db.daySummaryDao().insert(
            DaySummaryEntity(
                date = date,
                screenMin = 120,
                screenUnlockMin = 120,
                unlocks = 10,
                launches = 20,
                longestSessionMin = 40,
                pulledAt = 1000L,
                sourceDeviceId = "dev-a",
                screenQuality = DataQualityValues.EXACT
            )
        )
        db.daySummaryDao().insert(
            DaySummaryEntity(
                date = date,
                screenMin = 60,
                screenUnlockMin = 60,
                unlocks = 4,
                launches = 8,
                longestSessionMin = 25,
                pulledAt = 2000L,
                sourceDeviceId = "dev-b",
                screenQuality = DataQualityValues.PARTIAL
            )
        )

        val agg = db.daySummaryDao().sumRange(date, date).single()
        assertEquals(180, agg.screenUnlockMin)
        assertEquals(40, agg.longestSessionMin)
        assertEquals(DataQualityValues.PARTIAL, agg.screenQuality)
    }

    @Test
    fun usageCalculator_excludesPartialAndUnknownFromAverageAndExtremesWhileKeepingLegacyNull() {
        // M-FIX3：EXACT(300m) + 历史 NULL(200m) + PARTIAL(10m) + UNKNOWN(5m)
        // 日均分母仅含 EXACT + NULL（2 天，均值 250m）；最短日为 NULL(200m)，绝不是 PARTIAL(10m)
        val d1 = LocalDate.of(2026, 11, 20)
        val d2 = LocalDate.of(2026, 11, 21)
        val d3 = LocalDate.of(2026, 11, 22)
        val d4 = LocalDate.of(2026, 11, 23)
        val range = DateSelection.Range(d1, d4)
        val records = listOf(
            DaySummary(d1, 300, 300, 20, 40, 60, 1L, screenQuality = DataQualityValues.EXACT),
            DaySummary(d2, 200, 200, 10, 20, 40, 2L, screenQuality = null),
            DaySummary(d3, 10, 10, 1, 2, 10, 3L, screenQuality = DataQualityValues.PARTIAL),
            DaySummary(d4, 5, 5, 0, 1, 5, 4L, screenQuality = DataQualityValues.UNKNOWN)
        )

        val summary = UsageCalculator.summarize(range, records)
        assertTrue(summary.hasAnyRecord)
        assertEquals(2, summary.recordedDays)
        assertEquals(250, summary.averageScreenUnlockMin)
        assertEquals(15, summary.averageUnlocks)

        val extremes = UsageCalculator.extremes(range, records, lowThresholdMin = 120)
        assertEquals(d1, extremes.longestDate)
        assertEquals(300, extremes.longestMinutes)
        assertEquals(d2, extremes.shortestDate)
        assertEquals(200, extremes.shortestMinutes)
        assertEquals(0, extremes.underThresholdDays)

        val points = UsageCalculator.dailyPoints(range, records)
        assertEquals(DataQualityValues.EXACT, points[0].quality)
        assertNull(points[1].quality)
        assertEquals(DataQualityValues.PARTIAL, points[2].quality)
        assertEquals(DataQualityValues.UNKNOWN, points[3].quality)

        val periodExtremes = UsageCalculator.longestShortest(points)
        assertEquals(d1, periodExtremes.longestDate)
        assertEquals(d2, periodExtremes.shortestDate)
    }
}
