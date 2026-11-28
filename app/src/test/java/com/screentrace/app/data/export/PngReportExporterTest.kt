package com.screentrace.app.data.export

import android.graphics.Bitmap
import com.screentrace.app.contract.AppCategory
import com.screentrace.app.contract.AppUsage
import com.screentrace.app.contract.CalendarLevel
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
 * 14a 余项（v4：独立预览页 + 三周期联动 + 6 自选模块 + 顶部渐隐壁纸 + 专属相册保存与分享）
 * 回归测试，覆盖行为变异锚点 M-14A1 ~ M-14A6。
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
        selection: DateSelection = DateSelection.Range(day.minusDays(6), day),
        totalMin: Int = 372,
        avgMin: Int? = 186,
        recordedDays: Int = 6,
        totalDays: Int = 7,
        hasAnyRecord: Boolean = true
    ) = Summary(
        selection = selection,
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

    private fun buildFakeRepo(): UsageRepository = object : UsageRepository by EmptyUsageRepository() {
        override suspend fun summary(selection: DateSelection): Summary = when (selection) {
            is DateSelection.Day -> sampleSummary(
                selection = selection,
                totalMin = 195,
                avgMin = null, // 模拟 D-07 今日进行中日无完整日日均
                recordedDays = 1,
                totalDays = 1
            )
            is DateSelection.Range -> sampleSummary(
                selection = selection,
                totalMin = 1200,
                avgMin = 200,
                recordedDays = 6,
                totalDays = selection.days
            )
        }

        override suspend fun dailyTrend(selection: DateSelection): List<DailyPoint> {
            val count = selection.days
            return (0 until count).map { idx ->
                val d = selection.startDate.plusDays(idx.toLong())
                DailyPoint(d, if (d == day) 195 else 200, recorded = true)
            }
        }

        override suspend fun appRanking(selection: DateSelection, limit: Int): List<AppUsage> = listOf(
            AppUsage(day, "com.tencent.mm", "微信", 180, 10, AppCategory.SOCIAL),
            AppUsage(day, "com.secret.bank", "私有银行", 60, 2, AppCategory.OTHER)
        )

        override suspend fun topAppsPerDay(selection: DateSelection, limit: Int): Map<LocalDate, List<AppUsage>> =
            mapOf(day to listOf(AppUsage(day, "com.secret.bank", "私有银行", 60, 2, AppCategory.OTHER)))

        override suspend fun hourlyDistribution(selection: DateSelection): List<HourBucket> = listOf(
            HourBucket(9, 45),
            HourBucket(14, 60),
            HourBucket(20, 90)
        )

        override suspend fun qualifiedHourlyDistribution(dates: List<LocalDate>): QualifiedHourlyDistribution =
            QualifiedHourlyDistribution(
                buckets = listOf(HourBucket(9, 180), HourBucket(20, 360)),
                eligibleDays = dates.size.coerceAtLeast(1)
            )

        override suspend fun metricDetail(metric: MetricKind, selection: DateSelection): MetricDetail =
            sampleMetric(metric, hasEventSource = metric == MetricKind.UNLOCK, total = 18)

        override suspend fun monthLevels(yearMonth: YearMonth): List<CalendarLevel> = listOf(
            CalendarLevel(date = day, level = 2, recorded = true, minutes = 195)
        )
    }

    @Test
    fun `M-14A1_今日模式自动补7天趋势且时段分布展示今日累计不空态`() = runTest(mainDispatcher) {
        val vm = AppViewModel(repository = buildFakeRepo(), todayProvider = { day })
        val result = vm.buildPngPosterData(range = PngExportRange.TODAY, appVersion = "1.0.0")
        assertNotNull(result)
        val data = result!!.second

        // 今日模式下：时段分布必须展示今日累计（非全 0），且副标题为「今日」
        assertEquals("今日", data.periodSubtitle)
        assertTrue("今日有小时数据时时段分布不得为空", data.periods.sum() > 0)
        assertEquals(listOf(45, 60, 90, 0), data.periods)

        // 今日模式下若用户勾选「每日趋势」，自动取含今日的近 7 天趋势与 7 天完整日均，绝不是孤零零 1 天 + "—"
        assertEquals(7, data.trendDaily.size)
        assertEquals("近 7 天日均亮屏", data.trendTitle)
        assertEquals(200, data.trendAverageMin)
    }

    @Test
    fun `M-14A2_隐藏应用名称同时脱敏应用名与包名防图标泄露`() = runTest(mainDispatcher) {
        val vm = AppViewModel(repository = buildFakeRepo(), todayProvider = { day })
        val raw = vm.buildPngPosterData(range = PngExportRange.LAST_7_DAYS, appVersion = "1.0.0")!!.second
        val sanitized = PngReportExporter.sanitize(raw.copy(hideAppNames = true))

        assertEquals(listOf("应用 1", "应用 2"), sanitized.apps.map { it.name })
        assertEquals(listOf("_masked_1_", "_masked_2_"), sanitized.apps.map { it.pkg })

        val dailyTop = sanitized.topAppsPerDay[day]!!
        assertEquals(listOf("应用 2"), dailyTop.map { it.name })
        assertEquals(listOf("_masked_2_"), dailyTop.map { it.pkg })

        val dump = (sanitized.apps + dailyTop).joinToString("\n") { "${it.name}:${it.pkg}" }
        assertFalse(dump.contains("微信"))
        assertFalse(dump.contains("com.tencent.mm"))
        assertFalse(dump.contains("私有银行"))
        assertFalse(dump.contains("com.secret.bank"))
    }

    @Test
    fun `M-14A3_近7天与近30天区间长度与D07日均时段口径一致`() = runTest(mainDispatcher) {
        val vm = AppViewModel(repository = buildFakeRepo(), todayProvider = { day })

        val r7 = vm.buildPngPosterData(range = PngExportRange.LAST_7_DAYS, appVersion = "1.0.0")!!.second
        assertEquals(7, r7.trendDaily.size)
        assertEquals("日均", r7.periodSubtitle)
        assertEquals(200, r7.trendAverageMin)
        assertTrue(r7.periods.sum() > 0)

        val r30 = vm.buildPngPosterData(range = PngExportRange.LAST_30_DAYS, appVersion = "1.0.0")!!.second
        assertEquals(30, r30.trendDaily.size)
        assertEquals("日均", r30.periodSubtitle)
        assertEquals(1, r30.monthLevels.size)
    }

    @Test
    fun `M-14A4_主流手机分享尺寸规范与PNG编码后位图回收`() {
        assertEquals(390, PngPosterSpec.POSTER_WIDTH_DP)
        assertEquals(1170, PngPosterSpec.POSTER_WIDTH_PX)
        assertEquals("屏迹", PngPosterSpec.ALBUM_SUBDIR)
        assertEquals(
            "screen-time-report-20261127-0905.png",
            PngReportExporter.fileName(LocalDateTime.of(2026, 11, 27, 9, 5))
        )

        val bmp = Bitmap.createBitmap(1170, 2080, Bitmap.Config.ARGB_8888)
        val bytes = PngReportExporter.encodeBitmapToPng(bmp)
        assertTrue("编码后 Bitmap 必须立即 recycle 防内存泄漏", bmp.isRecycled)
        assertArrayEquals("必须具备标准 PNG 文件头", pngSignature, bytes.copyOfRange(0, 8))
        val (w, h) = readPngDimensions(bytes)
        assertEquals(1170, w)
        assertEquals(2080, h)
    }

    @Test
    fun `M-14A5_三周期默认推荐模块组合合理`() {
        assertFalse("今日默认不勾每日趋势", PngExportModule.DAILY_TREND in PngExportRange.TODAY.defaultModules())
        assertTrue("近7天默认勾选每日趋势", PngExportModule.DAILY_TREND in PngExportRange.LAST_7_DAYS.defaultModules())
        assertTrue("近30天默认勾选月度日历", PngExportModule.MONTH_CALENDAR in PngExportRange.LAST_30_DAYS.defaultModules())
        assertEquals(6, PngExportModule.entries.size)
    }

    @Test
    fun `M-14A6_空区间无小时数据时不伪造日间占比且判定空态`() {
        val empty = PngPosterData(
            range = PngExportRange.TODAY,
            selection = DateSelection.Day(day),
            rangeLabel = "11月27日 · 1天",
            exportedAt = "2026-11-27 09:05",
            appVersion = "1.0.0",
            summary = sampleSummary(selection = DateSelection.Day(day), totalMin = 0, avgMin = null, recordedDays = 0, totalDays = 1, hasAnyRecord = false),
            dailyGoalMin = 240,
            trendDaily = emptyList(),
            trendTitle = "日均亮屏",
            trendAverageMin = null,
            apps = emptyList(),
            topAppsPerDay = emptyMap(),
            hours = emptyList(),
            periods = listOf(0, 0, 0, 0),
            periodSubtitle = "今日",
            unlock = null,
            notification = null,
            yearMonth = YearMonth.from(day),
            monthLevels = emptyList(),
            today = day
        )
        assertFalse(empty.hasAnyContent)
        assertNull("无小时数据时 dayPercent 必须为 null", empty.dayPercent)
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
