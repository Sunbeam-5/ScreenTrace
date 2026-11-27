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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 14a 余项（Fresh UI 风格 PNG 长图导出）回归测试，覆盖行为变异锚点 M-14A1 ~ M-14A5。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
        hours: List<HourBucket> = listOf(HourBucket(9, 45), HourBucket(21, 90), HourBucket(23, 45)),
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
        // averageScreenUnlockMin == null（如仅含 PARTIAL/进行中日）Hero 主值必须为 "—"，无记录日不伪造柱高
        val partialInput = sampleInput(
            summary = sampleSummary(totalMin = 125, avgMin = null, recordedDays = 1, totalDays = 3, avgUnlocks = 0)
        )
        val model = PngReportExporter.buildModel(partialInput)

        assertFalse(model.isEmpty)
        assertNotNull(model.hero)
        assertEquals("完整日日均", model.hero!!.caption)
        assertEquals("—", model.hero!!.valueText)
        assertEquals(0f, model.hero!!.ringProgress, 0.0001f)
        assertEquals("2小时5分钟", model.tonalCards[0].value)
        assertEquals("—", model.tonalCards[1].value)
        assertNull(model.trendAvgFraction)
        assertTrue(model.hasUnrecordedTrendDays)

        val unrecordedBar = model.trendBars[1]
        assertFalse(unrecordedBar.recorded)
        assertEquals(0f, unrecordedBar.heightFraction, 0.0001f)

        // 空区间直接进入空态，不伪造 Hero 与卡片
        val emptyInput = sampleInput(
            summary = sampleSummary(totalMin = 0, avgMin = null, recordedDays = 0, totalDays = 3, hasAnyRecord = false),
            daily = emptyList(),
            apps = emptyList(),
            hours = emptyList()
        )
        val emptyModel = PngReportExporter.buildModel(emptyInput)
        assertTrue(emptyModel.isEmpty)
        assertNull(emptyModel.hero)
        assertTrue(emptyModel.tonalCards.isEmpty())
        assertTrue(emptyModel.appSlices.isEmpty())
        assertTrue(emptyModel.emptyMessage!!.contains("暂无可用记录"))
    }

    @Test
    fun `M-14A2_隐藏应用名称彻底脱敏且保留占比与色点`() {
        val model = PngReportExporter.buildModel(sampleInput(hideAppNames = true))

        assertTrue(model.hideAppNames)
        assertEquals("已隐藏应用名称", model.hideAppNamesNotice)
        assertEquals(listOf("应用 1", "应用 2", "应用 3"), model.appSlices.map { it.displayName })
        assertEquals(listOf("60%", "30%", "10%"), model.appSlices.map { it.percentText })
        assertEquals(3, model.appSlices.map { it.colorArgb }.distinct().size)
        // 日间 (9点45分 + 21点90分 = 135) / 180 = 75%
        assertEquals(75, model.dayPercent)

        val allText = buildList {
            add(model.brandTitle)
            add(model.rangeLabel)
            add(model.footerText)
            model.hero?.let {
                add(it.caption)
                add(it.valueText)
                it.goalText?.let(::add)
                it.statusChipText?.let(::add)
                add(it.longestSessionText)
            }
            model.hideAppNamesNotice?.let(::add)
            model.appsTotalText?.let(::add)
            addAll(model.tonalCards.flatMap { listOf(it.label, it.value, it.note) })
            addAll(model.appSlices.flatMap { listOf(it.displayName, it.durationText, it.percentText) })
        }.joinToString("\n")
        assertFalse("隐藏应用名称后不得出现微信", allText.contains("微信"))
        assertFalse("隐藏应用名称后不得出现哔哩哔哩", allText.contains("哔哩哔哩"))
        assertFalse("隐藏应用名称后不得出现秘密工作流App", allText.contains("秘密工作流App"))
    }

    @Test
    fun `M-14A3_离线Canvas渲染合法PNG字节流且高度随区块动态伸缩并防OOM`() {
        assertEquals(
            "screen-time-report-20261127-0905.png",
            PngReportExporter.fileName(LocalDateTime.of(2026, 11, 27, 9, 5))
        )

        val singleDayInput = sampleInput(
            summary = sampleSummary(totalMin = 300, avgMin = 300, recordedDays = 1, totalDays = 1),
            daily = listOf(DailyPoint(day, 300, recorded = true)),
            apps = listOf(AppUsage(day, "com.tencent.mm", "微信", 120, 5, AppCategory.SOCIAL))
        )
        val multiDayInput = sampleInput()

        val singleBytes = PngReportExporter.renderPngBytes(singleDayInput)
        val multiBytes = PngReportExporter.renderPngBytes(multiDayInput)

        assertTrue("PNG 字节流不得为空", singleBytes.size > 32)
        assertArrayEquals("必须具备标准 PNG 文件头", pngSignature, singleBytes.copyOfRange(0, 8))
        assertArrayEquals("多日长图必须具备标准 PNG 文件头", pngSignature, multiBytes.copyOfRange(0, 8))

        val (singleW, singleH) = readPngDimensions(singleBytes)
        val (multiW, multiH) = readPngDimensions(multiBytes)
        assertEquals(PngReportExporter.DEFAULT_WIDTH_PX, singleW)
        assertEquals(PngReportExporter.DEFAULT_WIDTH_PX, multiW)
        assertEquals(PngReportExporter.estimateHeightPx(PngReportExporter.buildModel(singleDayInput)), singleH)
        assertTrue("多日带趋势卡与更多应用时画布应更高 ($multiH > $singleH)", multiH > singleH)

        // 超长 100 天与 12 款应用安全收敛至 MAX_TREND_DAYS(14) 与 Top 8 + 其他，防 OOM
        val hundredDays = (0L until 100L).map { offset ->
            DailyPoint(day.minusDays(offset), 60, recorded = true)
        }.reversed()
        val twelveApps = (1..12).map { i ->
            AppUsage(day, "com.app.$i", "应用测试$i", 100 - i * 5, i, AppCategory.TOOLS)
        }
        val cappedModel = PngReportExporter.buildModel(sampleInput(daily = hundredDays, apps = twelveApps))
        assertEquals(PngReportExporter.MAX_TREND_DAYS, cappedModel.trendBars.size)
        assertEquals("近 14 天", cappedModel.trendSubtitle)
        assertEquals(PngReportExporter.MAX_TOP_APPS + 1, cappedModel.appSlices.size)
        assertTrue(cappedModel.appSlices.last().displayName.startsWith("其他 4"))
    }

    @Test
    fun `M-14A4_AppViewModel同源构建PNG与HTML报告并透传隐藏应用名与主题`() = runTest {
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
        val pngResult = vm.buildPngReport(hideAppNames = true, appVersion = "1.0.0", theme = PngReportTheme.Default)
        val htmlResult = vm.buildHtmlReport(hideAppNames = true, appVersion = "1.0.0")

        assertNotNull("buildPngReport 应成功返回文件名与 PNG 字节", pngResult)
        assertNotNull("buildHtmlReport 应成功返回文件名与 HTML", htmlResult)
        assertTrue(pngResult!!.first.startsWith("screen-time-report-"))
        assertTrue(pngResult.first.endsWith(".png"))
        assertArrayEquals(pngSignature, pngResult.second.copyOfRange(0, 8))

        val (w, h) = readPngDimensions(pngResult.second)
        assertEquals(PngReportExporter.DEFAULT_WIDTH_PX, w)
        assertTrue(h > 500)

        assertTrue(htmlResult!!.second.contains("应用 1"))
        assertFalse(htmlResult.second.contains("私有银行"))
    }

    @Test
    fun `M-14A5_导出PNG严格同步当前深浅色与主题配色`() {
        val input = sampleInput()
        val lightTheme = PngReportTheme.Default
        val darkIndigoTheme = PngReportTheme(
            darkTheme = true,
            background = 0xFF10111A.toInt(),
            gradientTop = 0xFF1B1C2E.toInt(),
            gradientMid = 0xFF151624.toInt(),
            cardSurface = 0xFF1C1E2E.toInt(),
            surfaceVariant = 0xFF25273B.toInt(),
            primary = 0xFF9E9DF0.toInt(),
            ringGradientEnd = 0xFFC5C4F8.toInt(),
            ringOver = 0xFF6F6ED6.toInt(),
            primaryContainer = 0xFF2A2B4D.toInt(),
            onPrimaryContainer = 0xFFE6E5FF.toInt(),
            secondaryContainer = 0xFF33284A.toInt(),
            onSecondaryContainer = 0xFFF0E4FF.toInt(),
            onSurface = 0xFFF2F3FA.toInt(),
            onSurfaceVariant = 0xFFB8BACE.toInt(),
            onSurfaceMuted = 0xFF8C8FA8.toInt(),
            track = 0xFF2D3047.toInt(),
            barSurface = 0xFF4C4E78.toInt(),
            outline = 0xFF333650.toInt()
        )

        val lightBmp = PngReportExporter.renderBitmap(PngReportExporter.buildModel(input, lightTheme), lightTheme)
        val darkBmp = PngReportExporter.renderBitmap(PngReportExporter.buildModel(input, darkIndigoTheme), darkIndigoTheme)
        try {
            val lightBgPixel = lightBmp.getPixel(4, 0)
            val darkBgPixel = darkBmp.getPixel(4, 0)
            assertNotEquals("深浅色主题渲染的背景像素必须不同", lightBgPixel, darkBgPixel)
            assertColorClose("浅色顶部背景应贴近 lightTheme.gradientTop", lightTheme.gradientTop, lightBgPixel)
            assertColorClose("深色顶部背景应贴近 darkIndigoTheme.gradientTop", darkIndigoTheme.gradientTop, darkBgPixel)
        } finally {
            lightBmp.recycle()
            darkBmp.recycle()
        }

        val lightBytes = PngReportExporter.renderPngBytes(input, lightTheme)
        val darkBytes = PngReportExporter.renderPngBytes(input, darkIndigoTheme)
        assertFalse("不同主题导出的 PNG 字节流必须不同", lightBytes.contentEquals(darkBytes))
    }

    private fun assertColorClose(msg: String, expected: Int, actual: Int, maxChannelDelta: Int = 2) {
        val dr = kotlin.math.abs(android.graphics.Color.red(expected) - android.graphics.Color.red(actual))
        val dg = kotlin.math.abs(android.graphics.Color.green(expected) - android.graphics.Color.green(actual))
        val db = kotlin.math.abs(android.graphics.Color.blue(expected) - android.graphics.Color.blue(actual))
        assertTrue(
            "$msg (expected=#${Integer.toHexString(expected)}, actual=#${Integer.toHexString(actual)})",
            dr <= maxChannelDelta && dg <= maxChannelDelta && db <= maxChannelDelta
        )
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
