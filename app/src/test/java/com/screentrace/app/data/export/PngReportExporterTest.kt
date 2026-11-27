package com.screentrace.app.data.export

import com.screentrace.app.contract.AppCategory
import com.screentrace.app.contract.AppUsage
import com.screentrace.app.contract.DailyPoint
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.EmptyUsageRepository
import com.screentrace.app.contract.HourBucket
import com.screentrace.app.contract.Summary
import com.screentrace.app.contract.UsageRepository
import com.screentrace.app.ui.AppViewModel
import java.time.LocalDate
import java.time.LocalDateTime
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 14a 余项（PNG 长图导出）回归测试，覆盖行为变异锚点 M-14A1 ~ M-14A4。
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
        hasAnyRecord: Boolean = true,
        avgUnlocks: Int = 24
    ) = Summary(
        selection = DateSelection.Range(day.minusDays(2), day),
        totalDays = totalDays,
        recordedDays = recordedDays,
        hasAnyRecord = hasAnyRecord,
        totalScreenUnlockMin = totalMin,
        averageScreenUnlockMin = avgMin,
        averageUnlocks = avgUnlocks,
        longestSessionMin = 95
    )

    private fun sampleInput(
        summary: Summary = sampleSummary(),
        daily: List<DailyPoint> = listOf(
            DailyPoint(day.minusDays(2), 200, recorded = true),
            DailyPoint(day.minusDays(1), 0, recorded = false),
            DailyPoint(day, 172, recorded = true)
        ),
        apps: List<AppUsage> = listOf(
            AppUsage(day, "com.tencent.mm", "微信", 180, 12, AppCategory.SOCIAL),
            AppUsage(day, "tv.danmaku.bili", "哔哩哔哩", 90, 6, AppCategory.VIDEO),
            AppUsage(day, "com.secret.work", "秘密工作流App", 30, 3, AppCategory.TOOLS)
        ),
        hours: List<HourBucket> = listOf(HourBucket(9, 45), HourBucket(21, 90)),
        hideAppNames: Boolean = false,
        dailyGoalMin: Int? = 240
    ) = HtmlReportInput(
        rangeLabel = "11月25日 – 11月27日 · 3天",
        exportedAt = "2026-11-27 09:05",
        summary = summary,
        daily = daily,
        apps = apps,
        hours = hours,
        appVersion = "1.0.0",
        hideAppNames = hideAppNames,
        dailyGoalMin = dailyGoalMin
    )

    @Test
    fun `M-14A1_同源口径与D07空日诚实渲染`() {
        // averageScreenUnlockMin == null（如仅含 PARTIAL/进行中日）必须渲染为 "—"，无记录日必须为 "无记录"
        val partialInput = sampleInput(
            summary = sampleSummary(totalMin = 125, avgMin = null, recordedDays = 1, totalDays = 3, avgUnlocks = 0)
        )
        val model = PngReportExporter.buildModel(partialInput)

        assertFalse(model.isEmpty)
        assertEquals("2小时5分钟", model.kpiCards[0].value)
        assertEquals("—", model.kpiCards[1].value)
        assertEquals("—", model.kpiCards[2].value)
        assertEquals("1 / 3 天", model.kpiCards[3].value)
        assertEquals("日均 — / 目标 4小时", model.goalLine)

        // 无记录日严禁伪造 "0分钟"
        val unrecordedRow = model.dailyRows[1]
        assertFalse(unrecordedRow.recorded)
        assertEquals("无记录", unrecordedRow.durationText)
        assertEquals(0f, unrecordedRow.barFraction, 0.0001f)

        // 空区间直接进入空态，不伪造概览与明细
        val emptyInput = sampleInput(
            summary = sampleSummary(totalMin = 0, avgMin = null, recordedDays = 0, totalDays = 3, hasAnyRecord = false),
            daily = emptyList(),
            apps = emptyList(),
            hours = emptyList()
        )
        val emptyModel = PngReportExporter.buildModel(emptyInput)
        assertTrue(emptyModel.isEmpty)
        assertTrue(emptyModel.kpiCards.isEmpty())
        assertTrue(emptyModel.dailyRows.isEmpty())
        assertTrue(emptyModel.appRows.isEmpty())
        assertTrue(emptyModel.emptyMessage!!.contains("没有可用记录"))
    }

    @Test
    fun `M-14A2_隐藏应用名称彻底脱敏且保留占比与色点`() {
        val model = PngReportExporter.buildModel(sampleInput(hideAppNames = true))

        assertTrue(model.hideAppNames)
        assertNotNull(model.hideAppNamesNotice)
        assertTrue(model.hideAppNamesNotice!!.contains("已隐藏应用名称"))
        assertEquals(listOf("应用 1", "应用 2", "应用 3"), model.appRows.map { it.displayName })
        assertEquals(listOf("60%", "30%", "10%"), model.appRows.map { it.percentText })
        assertEquals(3, model.appRows.map { it.colorHex }.distinct().size)

        // 任何可见文本字段均不得泄露真实应用名
        val allText = buildList {
            add(model.title)
            add(model.rangeLabel)
            add(model.exportedLine)
            model.goalLine?.let(::add)
            model.hideAppNamesNotice?.let(::add)
            model.appsTotalLine?.let(::add)
            addAll(model.kpiCards.flatMap { listOf(it.label, it.value) })
            addAll(model.dailyRows.flatMap { listOf(it.dateLabel, it.durationText) })
            addAll(model.appRows.flatMap { listOf(it.displayName, it.durationText, it.percentText) })
            addAll(model.methodologyNotes)
        }.joinToString("\n")
        assertFalse("隐藏应用名称后不得出现微信", allText.contains("微信"))
        assertFalse("隐藏应用名称后不得出现哔哩哔哩", allText.contains("哔哩哔哩"))
        assertFalse("隐藏应用名称后不得出现秘密工作流App", allText.contains("秘密工作流App"))
    }

    @Test
    fun `M-14A3_离线Canvas渲染合法PNG字节流且高度随内容动态伸缩并防OOM`() {
        assertEquals(
            "screen-time-report-20261127-0905.png",
            PngReportExporter.fileName(LocalDateTime.of(2026, 11, 27, 9, 5))
        )

        val shortInput = sampleInput(
            daily = listOf(DailyPoint(day, 120, recorded = true)),
            apps = listOf(AppUsage(day, "com.tencent.mm", "微信", 120, 5, AppCategory.SOCIAL))
        )
        val longDaily = (0L until 15L).map { offset ->
            DailyPoint(day.minusDays(offset), 100 + offset.toInt() * 5, recorded = true)
        }.reversed()
        val longInput = sampleInput(daily = longDaily)

        val shortBytes = PngReportExporter.renderPngBytes(shortInput)
        val longBytes = PngReportExporter.renderPngBytes(longInput)

        assertTrue("PNG 字节流不得为空", shortBytes.size > 32)
        assertArrayEquals("必须具备标准 PNG 文件头", pngSignature, shortBytes.copyOfRange(0, 8))
        assertArrayEquals("长图必须具备标准 PNG 文件头", pngSignature, longBytes.copyOfRange(0, 8))

        val (shortW, shortH) = readPngDimensions(shortBytes)
        val (longW, longH) = readPngDimensions(longBytes)
        assertEquals(PngReportExporter.DEFAULT_WIDTH_PX, shortW)
        assertEquals(PngReportExporter.DEFAULT_WIDTH_PX, longW)
        assertEquals(PngReportExporter.estimateHeightPx(PngReportExporter.buildModel(shortInput)), shortH)
        assertTrue("更多明细行必须拉高画布高度 ($longH > $shortH)", longH > shortH)

        // 超长 100 天区间必须安全截断至 MAX_DAILY_ROWS，防止低内存机型 OOM
        val hundredDays = (0L until 100L).map { offset ->
            DailyPoint(day.minusDays(offset), 60, recorded = true)
        }.reversed()
        val cappedModel = PngReportExporter.buildModel(sampleInput(daily = hundredDays))
        assertEquals(PngReportExporter.MAX_DAILY_ROWS, cappedModel.dailyRows.size)
        assertEquals(100 - PngReportExporter.MAX_DAILY_ROWS, cappedModel.dailyOmittedCount)
    }

    @Test
    fun `M-14A4_AppViewModel同源构建PNG与HTML报告并透传隐藏应用名`() = runTest {
        val fakeRepo = object : UsageRepository by EmptyUsageRepository() {
            override suspend fun summary(selection: DateSelection): Summary = sampleSummary(totalMin = 240, avgMin = 240)
            override suspend fun dailyTrend(selection: DateSelection): List<DailyPoint> = listOf(
                DailyPoint(day, 240, recorded = true)
            )
            override suspend fun appRanking(selection: DateSelection, limit: Int): List<AppUsage> = listOf(
                AppUsage(day, "com.tencent.mm", "微信", 180, 10, AppCategory.SOCIAL),
                AppUsage(day, "com.secret.bank", "私有银行", 60, 2, AppCategory.OTHER)
            )
            override suspend fun hourlyDistribution(selection: DateSelection): List<HourBucket> = listOf(
                HourBucket(10, 120),
                HourBucket(20, 120)
            )
        }

        val vm = AppViewModel(repository = fakeRepo, todayProvider = { day })
        val pngResult = vm.buildPngReport(hideAppNames = true, appVersion = "1.0.0")
        val htmlResult = vm.buildHtmlReport(hideAppNames = true, appVersion = "1.0.0")

        assertNotNull("buildPngReport 应成功返回文件名与 PNG 字节", pngResult)
        assertNotNull("buildHtmlReport 应成功返回文件名与 HTML", htmlResult)
        assertTrue(pngResult!!.first.startsWith("screen-time-report-"))
        assertTrue(pngResult.first.endsWith(".png"))
        assertArrayEquals(pngSignature, pngResult.second.copyOfRange(0, 8))

        val (w, h) = readPngDimensions(pngResult.second)
        assertEquals(PngReportExporter.DEFAULT_WIDTH_PX, w)
        assertTrue(h > 500)

        // 同源 HTML 报告亦正确脱敏
        assertTrue(htmlResult!!.second.contains("应用 1"))
        assertFalse(htmlResult.second.contains("私有银行"))
    }

    /** 直接按 PNG IHDR 规范读取宽高（字节 16..23 大端无符号整数）。 */
    private fun readPngDimensions(bytes: ByteArray): Pair<Int, Int> {
        fun u32(offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
        return u32(16) to u32(20)
    }
}
