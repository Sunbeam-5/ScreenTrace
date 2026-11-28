package com.screentrace.app.ui

import androidx.compose.ui.graphics.Color
import com.screentrace.app.contract.DailyPoint
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.EmptyUsageRepository
import com.screentrace.app.contract.ExtremesInfo
import com.screentrace.app.contract.HourBucket
import com.screentrace.app.contract.QualifiedHourlyDistribution
import com.screentrace.app.contract.Summary
import com.screentrace.app.contract.UsageRepository
import com.screentrace.app.data.prefs.ThemeMode
import com.screentrace.app.data.wallpaper.WallpaperColorScope
import com.screentrace.app.theme.PaletteEngine
import com.screentrace.app.theme.ThemeVariant
import com.screentrace.app.theme.WallpaperPalette
import com.screentrace.app.theme.contrastRatio
import com.screentrace.app.theme.palette
import com.screentrace.app.uikit.TrendSelectionMath
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 批 14c（UI 视觉、动效与交互体验打磨）回归测试，覆盖行为变异锚点 M-14C1 ~ M-14C6。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Batch14cUiPolishTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `M-14C1_柱状图绝对坐标跟手命中方向正确且两端安全夹取`() {
        // 7 根柱子、每槽 100px：手指从左向右滑动，命中下标必须严格递增（绝不反向）
        assertEquals(0, TrendSelectionMath.indexForTouchX(touchX = 10f, slotWidthPx = 100f, count = 7))
        assertEquals(1, TrendSelectionMath.indexForTouchX(touchX = 120f, slotWidthPx = 100f, count = 7))
        assertEquals(3, TrendSelectionMath.indexForTouchX(touchX = 350f, slotWidthPx = 100f, count = 7))
        assertEquals(6, TrendSelectionMath.indexForTouchX(touchX = 699f, slotWidthPx = 100f, count = 7))

        // 越界夹取保护
        assertEquals(0, TrendSelectionMath.indexForTouchX(touchX = -50f, slotWidthPx = 100f, count = 7))
        assertEquals(6, TrendSelectionMath.indexForTouchX(touchX = 999f, slotWidthPx = 100f, count = 7))
        assertEquals(-1, TrendSelectionMath.indexForTouchX(touchX = 50f, slotWidthPx = 100f, count = 0))
    }

    @Test
    fun `M-14C2_日夜间模式三态解析正确跟随系统与显式覆盖`() {
        assertTrue("跟随系统 + 系统深色 => 深色", ThemeMode.SYSTEM.resolveDark(systemDark = true))
        assertFalse("跟随系统 + 系统浅色 => 浅色", ThemeMode.SYSTEM.resolveDark(systemDark = false))
        assertFalse("显式浅色不受系统深色影响", ThemeMode.LIGHT.resolveDark(systemDark = true))
        assertTrue("显式深色不受系统浅色影响", ThemeMode.DARK.resolveDark(systemDark = false))
    }

    @Test
    fun `M-14C3_自定义浅色与深色主题barSurface对比度不与背景重合`() {
        // 极浅自定义种子（浅黄/浅粉）在浅色白卡上不能融进背景
        val pastelYellow = Color(0xFFF5E99B)
        val lightPalette = PaletteEngine.paletteFromSeed(pastelYellow, dark = false)
        val lightRatio = contrastRatio(lightPalette.barSurface, lightPalette.surface)
        assertTrue(
            "浅色自定义主题未选中柱体相对白卡对比度应 ≥ 1.65 (实测=$lightRatio)",
            lightRatio >= 1.65
        )

        // 极暗自定义种子在深色卡片上不能融进深灰背景
        val deepNavy = Color(0xFF1A1E2E)
        val darkPalette = PaletteEngine.paletteFromSeed(deepNavy, dark = true)
        val darkRatio = contrastRatio(darkPalette.barSurface, darkPalette.surface)
        assertTrue(
            "深色自定义主题未选中柱体相对深卡对比度应 ≥ 1.65 (实测=$darkRatio)",
            darkRatio >= 1.65
        )
    }

    @Test
    fun `M-14C4_壁纸模式下barSurface相对合成卡片底色保持清晰对比度`() {
        val wpSeed = Color(0xFFE8EEF2)
        val themePal = ThemeVariant.MINT.palette(darkTheme = false)
        val wpPal = PaletteEngine.paletteFromSeed(wpSeed, dark = false)
        val eff = WallpaperPalette.effectivePalette(
            scope = WallpaperColorScope.FULL_THEME,
            themePalette = themePal,
            wallpaperPalette = wpPal,
            dark = false,
            wallpaperSeed = wpSeed,
            dimAlpha = 0.1f,
            themeScrimAlpha = 0.2f
        )
        val bg = WallpaperPalette.backgroundColor(wpSeed, 0.1f, 0.2f, wpPal.background)
        val effCard = WallpaperPalette.composite(eff.surface.copy(alpha = 1f), eff.surface.alpha, bg)
        val ratio = contrastRatio(eff.barSurface, effCard)
        assertTrue("壁纸模式柱体相对合成卡片对比度应 ≥ 1.65 (实测=$ratio)", ratio >= 1.65)

        // 即使上游传入的 barSurface 恰好与壁纸合成卡片同色，effectivePalette 也必须重新兜底拉高对比度
        val lowContrastInput = wpPal.copy(barSurface = effCard)
        val rescued = WallpaperPalette.effectivePalette(
            scope = WallpaperColorScope.FULL_THEME,
            themePalette = themePal,
            wallpaperPalette = lowContrastInput,
            dark = false,
            wallpaperSeed = wpSeed,
            dimAlpha = 0.1f,
            themeScrimAlpha = 0.2f
        )
        val rescuedRatio = contrastRatio(rescued.barSurface, effCard)
        assertTrue("壁纸合成后必须再次校正 barSurface 对比度 ≥ 1.65 (实测=$rescuedRatio)", rescuedRatio >= 1.65)
    }

    @Test
    fun `M-14C5_统计页切换近7天至近30天仅精准重查统计不惊动日历月视图`() = runTest {
        val today = LocalDate.of(2026, 11, 27)
        var calendarStreakCalls = 0
        var statsDailyCalls = 0
        val fakeRepo = object : UsageRepository by EmptyUsageRepository() {
            override suspend fun hasAnyData(): Boolean = true
            override suspend fun hasDataIn(selection: DateSelection): Boolean = true
            override suspend fun summary(selection: DateSelection): Summary = Summary(
                selection = selection,
                totalDays = selection.days,
                recordedDays = selection.days,
                hasAnyRecord = true,
                totalScreenUnlockMin = 200 * selection.days,
                averageScreenUnlockMin = 200,
                averageUnlocks = 20,
                longestSessionMin = 60
            )
            override suspend fun dailyTrend(selection: DateSelection): List<DailyPoint> {
                statsDailyCalls++
                return (0 until selection.days).map { i ->
                    DailyPoint(selection.startDate.plusDays(i.toLong()), 200, recorded = true)
                }
            }
            override suspend fun qualifiedHourlyDistribution(dates: List<LocalDate>): QualifiedHourlyDistribution =
                QualifiedHourlyDistribution(listOf(HourBucket(10, 120)), eligibleDays = 1)
            override suspend fun extremes(selection: DateSelection, lowThresholdMin: Int): ExtremesInfo =
                ExtremesInfo(today, 200, today, 200, 0)
            override suspend fun streak(anchor: LocalDate): com.screentrace.app.contract.StreakInfo {
                calendarStreakCalls++
                return com.screentrace.app.contract.StreakInfo(1, 1, today)
            }
        }

        val vm = AppViewModel(repository = fakeRepo, todayProvider = { today })
        vm.selectMain(AppPage.Stats)
        // 清零计数，验证在统计页切近 30 天仅精准走 loadStatsOnly()，不重查日历 streak
        calendarStreakCalls = 0
        statsDailyCalls = 0

        vm.applySelection(DateScope.STATS, DateSelection.lastDays(30, today))
        val deadline = System.currentTimeMillis() + 2_000L
        while (statsDailyCalls == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }

        assertTrue("切近30天必须刷新统计趋势", statsDailyCalls >= 1)
        assertEquals("切统计周期不得触发日历全量重查", 0, calendarStreakCalls)
        assertEquals(30, vm.statsSelection.value.days)
    }

    @Test
    fun `M-14C6_AppViewModel三态主题切换与旧setDarkTheme向后兼容`() {
        val vm = AppViewModel(repository = EmptyUsageRepository())
        assertEquals(ThemeMode.SYSTEM, vm.themeMode.value)

        vm.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, vm.themeMode.value)
        assertTrue(vm.darkTheme.value)

        vm.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, vm.themeMode.value)
        assertFalse(vm.darkTheme.value)

        // 旧 API setDarkTheme 仍兼容映射到 DARK/LIGHT
        vm.setDarkTheme(true)
        assertEquals(ThemeMode.DARK, vm.themeMode.value)
    }
}
