package com.screentrace.app.data.export

import android.graphics.Bitmap
import com.screentrace.app.contract.AppCategory
import com.screentrace.app.contract.AppUsage
import com.screentrace.app.contract.CalendarLevel
import com.screentrace.app.contract.Coverage
import com.screentrace.app.contract.DailyPoint
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.EmptyUsageRepository
import com.screentrace.app.contract.HourBucket
import com.screentrace.app.contract.MetricDetail
import com.screentrace.app.contract.MetricKind
import com.screentrace.app.contract.QualifiedHourlyDistribution
import com.screentrace.app.contract.Summary
import com.screentrace.app.contract.UsageRepository
import com.screentrace.app.ui.AppViewModel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
 * 14a 余项（v3：真实 Compose Tab 长截图 + 用户自选模块）回归测试，覆盖 M-14A1 ~ M-14A5。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PngReportExporterTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val pngSignature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    private val day = LocalDate.of(2026, 11, 27)

    private fun sampleSummary(
        totalMin: Int = 372,
        avgMin: Int? = 186,
        recordedDays: Int = 2,
        totalDays: Int = 3,
        hasAnyRecord: Boolean = true
    ) = Summary(
        selection = DateSelection.Range(day.minusDays(2), day),
        totalDays = totalDays,
        recordedDays = recordedDays,
        hasAnyRecord = hasAnyRecord,
        totalScreenUnlockMin = totalMin,
        averageScreenUnlockMin = avgMin,
        averageUnlocks = 24,
        longestSessionMin = 95
    )

    private fun sampleMetric(kind: MetricKind, hasEventSource: Boolean, total: Int) = MetricDetail(
        metric = kind,
        selection = DateSelection.Day(day),
        hasEventSource = hasEventSource,
        total = total,
        dailyAverage = total,
        notification = null,
        points = emptyList(),
        appRanking = emptyList(),
        peakHour = null,
        hourlyAverage = null,
        busiestDate = null
    )

    private fun samplePosterData(
        summary: Summary = sampleSummary(),
        apps: List<AppUsage> = listOf(
            AppUsage(day, "com.tencent.mm", "微信", 180, 12, AppCategory.SOCIAL),
            AppUsage(day, "tv.danmaku.bili", "哔哩哔哩", 90, 6, AppCategory.VIDEO),
            AppUsage(day, "com.secret.work", "秘密工作流App", 30, 3, AppCategory.TOOLS)
        ),
        hideAppNames: Boolean = false,
        modules: Set<PngExportModule> = PngExportModule.DEFAULT_SET
    ) = PngPosterData(
        selection = summary.selection,
        rangeLabel = "11月25日 – 11月27日 · 3天",
        exportedAt = "2026-11-27 09:05",
        appVersion = "1.0.0",
        summary = summary,
        dailyGoalMin = 240,
        daily = listOf(
            DailyPoint(day.minusDays(2), 200, recorded = true),
            DailyPoint(day.minusDays(1), 0, recorded = false),
            DailyPoint(day, 172, recorded = true)
        ),
        apps = apps,
        topAppsPerDay = mapOf(day to apps),
        hours = listOf(HourBucket(9, 45), HourBucket(21, 90), HourBucket(23, 45)),
        periods = listOf(45, 0, 90, 45),
        unlock = sampleMetric(MetricKind.UNLOCK, hasEventSource = true, total = 24),
        notification = sampleMetric(MetricKind.NOTIFICATION, hasEventSource = false, total = 0),
        yearMonth = YearMonth.from(day),
        monthLevels = emptyList(),
        today = day,
        hideAppNames = hideAppNames,
        modules = modules
    )

    @Test
    fun `M-14A1_同源口径与D07空日诚实边界`() {
        val partial = samplePosterData(
            summary = sampleSummary(totalMin = 120, avgMin = null, recordedDays = 1, totalDays = 3)
        )
        assertTrue(partial.hasAnyContent)
        assertNull("无完整日时 averageScreenUnlockMin 必须保持 null", partial.summary.averageScreenUnlockMin)
        assertEquals(75, partial.dayPercent)

        val empty = samplePosterData(
            summary = sampleSummary(totalMin = 0, avgMin = null, recordedDays = 0, totalDays = 3, hasAnyRecord = false),
            apps = emptyList()
        ).copy(hours = emptyList(), monthLevels = emptyList())
        assertFalse("无记录且无应用时 hasAnyContent 必须为 false", empty.hasAnyContent)
        assertNull("无小时数据时 dayPercent 必须为 null（不得伪造 0%）", empty.dayPercent)
    }

    @Test
    fun `M-14A2_隐藏应用名称同时脱敏应用名与包名防图标泄露`() {
        val sanitized = PngReportExporter.sanitize(samplePosterData(hideAppNames = true))

        assertEquals(listOf("应用 1", "应用 2", "应用 3"), sanitized.apps.map { it.name })
        assertEquals(listOf("_masked_1_", "_masked_2_", "_masked_3_"), sanitized.apps.map { it.pkg })

        val dailyTop = sanitized.topAppsPerDay[day]!!
        assertEquals(listOf("应用 1", "应用 2", "应用 3"), dailyTop.map { it.name })
        assertEquals(listOf("_masked_1_", "_masked_2_", "_masked_3_"), dailyTop.map { it.pkg })

        val dump = (sanitized.apps + dailyTop).joinToString("\n") { "${it.name}:${it.pkg}" }
        assertFalse(dump.contains("微信"))
        assertFalse(dump.contains("com.tencent.mm"))
        assertFalse(dump.contains("秘密工作流App"))
        assertFalse(dump.contains("com.secret.work"))
    }

    @Test
    fun `M-14A3_AppViewModel按自选模块组装同源长图数据并按需查询月历`() = runTest(mainDispatcher) {
        var monthLevelsQueries = 0
        val fakeRepo = object : UsageRepository by EmptyUsageRepository() {
            override suspend fun summary(selection: DateSelection): Summary = sampleSummary(totalMin = 240, avgMin = 240)
            override suspend fun dailyTrend(selection: DateSelection): List<DailyPoint> = listOf(
                DailyPoint(day, 240, recorded = true)
            )
            override suspend fun appRanking(selection: DateSelection, limit: Int): List<AppUsage> = listOf(
                AppUsage(day, "com.tencent.mm", "微信", 180, 10, AppCategory.SOCIAL),
                AppUsage(day, "com.secret.bank", "私有银行", 60, 2, AppCategory.OTHER)
            )
            override suspend fun topAppsPerDay(selection: DateSelection, limit: Int): Map<LocalDate, List<AppUsage>> =
                mapOf(day to listOf(AppUsage(day, "com.secret.bank", "私有银行", 60, 2, AppCategory.OTHER)))
            override suspend fun hourlyDistribution(selection: DateSelection): List<HourBucket> = listOf(
                HourBucket(10, 120),
                HourBucket(20, 120)
            )
            override suspend fun qualifiedHourlyDistribution(dates: List<LocalDate>): QualifiedHourlyDistribution =
                QualifiedHourlyDistribution(listOf(HourBucket(10, 120), HourBucket(20, 120)), eligibleDays = 1)
            override suspend fun metricDetail(metric: MetricKind, selection: DateSelection): MetricDetail =
                sampleMetric(metric, hasEventSource = metric == MetricKind.UNLOCK, total = 15)
            override suspend fun monthLevels(yearMonth: YearMonth): List<CalendarLevel> {
                monthLevelsQueries++
                return listOf(CalendarLevel(date = day, level = 2, recorded = true, minutes = 240))
            }
        }

        val vm = AppViewModel(repository = fakeRepo, todayProvider = { day })

        // 未选月度日历：不触发 monthLevels 查询，且返回脱敏后的同源数据
        val withoutCalendar = vm.buildPngPosterData(
            hideAppNames = true,
            appVersion = "1.0.0",
            modules = setOf(PngExportModule.HERO_RING, PngExportModule.APP_BREAKDOWN)
        )
        assertNotNull(withoutCalendar)
        assertEquals(0, monthLevelsQueries)
        assertTrue(withoutCalendar!!.first.endsWith(".png"))
        assertEquals(setOf(PngExportModule.HERO_RING, PngExportModule.APP_BREAKDOWN), withoutCalendar.second.modules)
        assertTrue(withoutCalendar.second.monthLevels.isEmpty())
        assertEquals(listOf("应用 1", "应用 2"), withoutCalendar.second.apps.map { it.name })

        // 勾选月度日历：触发 monthLevels 查询并透传
        val withCalendar = vm.buildPngPosterData(
            hideAppNames = false,
            appVersion = "1.0.0",
            modules = setOf(PngExportModule.MONTH_CALENDAR)
        )
        assertNotNull(withCalendar)
        assertEquals(1, monthLevelsQueries)
        assertEquals(1, withCalendar!!.second.monthLevels.size)
        assertEquals("微信", withCalendar.second.apps.first().name)
    }

    @Test
    fun `M-14A4_主流手机分享尺寸规范与PNG编码后位图回收`() {
        assertEquals(390, PngPosterSpec.POSTER_WIDTH_DP)
        assertEquals(1170, PngPosterSpec.POSTER_WIDTH_PX)
        assertEquals(
            "screen-time-report-20261127-0905.png",
            PngReportExporter.fileName(LocalDateTime.of(2026, 11, 27, 9, 5))
        )

        val bmp = Bitmap.createBitmap(1170, 2080, Bitmap.Config.ARGB_8888)
        val bytes = PngReportExporter.encodeBitmapToPng(bmp)
        assertTrue("编码后 Bitmap 必须立即 recycle 防内存泄漏", bmp.isRecycled)
        assertTrue("PNG 字节流不得为空", bytes.size > 32)
        assertArrayEquals("必须具备标准 PNG 文件头", pngSignature, bytes.copyOfRange(0, 8))
        val (w, h) = readPngDimensions(bytes)
        assertEquals(1170, w)
        assertEquals(2080, h)
    }

    @Test
    fun `M-14A5_默认模块组合符合分享直觉且覆盖三大Tab`() {
        assertEquals(
            setOf(
                PngExportModule.HERO_RING,
                PngExportModule.UNLOCK_NOTIFICATION,
                PngExportModule.APP_BREAKDOWN,
                PngExportModule.DAILY_TREND,
                PngExportModule.PERIOD_DISTRIBUTION
            ),
            PngExportModule.DEFAULT_SET
        )
        assertFalse("月度日历默认作为按需可选项", PngExportModule.MONTH_CALENDAR in PngExportModule.DEFAULT_SET)
        assertEquals(6, PngExportModule.entries.size)
    }

    private fun readPngDimensions(bytes: ByteArray): Pair<Int, Int> {
        fun u32(offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
        return u32(16) to u32(20)
    }
}
