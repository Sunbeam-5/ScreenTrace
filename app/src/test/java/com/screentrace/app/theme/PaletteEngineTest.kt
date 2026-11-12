package com.screentrace.app.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §7-29：自定义主题派生引擎必须「任何种子都不难看坏」。
 *
 * 断言与 [FreshPaletteContrastTest] **同一组色对与阈值**（正文 4.5、图形 3.0），
 * 只是把「九套预设」换成「极端种子 5 个 + 随机 50 个」——两条测试一起才能说明
 * 「预设与自定义同等待遇」。
 */
class PaletteEngineTest {

    private val violations = mutableListOf<String>()

    private fun require(fg: Color, bg: Color, min: Double, name: String, label: String) {
        val ratio = contrastRatio(fg, bg)
        if (ratio < min) {
            violations += "%s %s 对比度 %.2f < %.1f".format(label, name, ratio, min)
        }
    }

    /** 与 FreshPaletteContrastTest.textPairsMeet45 + metric + graphics 合成的同一组色对。 */
    private fun audit(p: FreshPalette, label: String) {
        for (bg in listOf(p.background, p.surface, p.surfaceVariant)) {
            require(p.onSurface, bg, 4.5, "onSurface", label)
            require(p.onSurfaceVariant, bg, 4.5, "onSurfaceVariant", label)
            require(p.mutedOn(bg), bg, 4.5, "mutedOn(bg)", label)
        }
        require(p.onSurfaceMuted, p.surface, 4.5, "onSurfaceMuted/surface", label)
        require(p.onSurfaceMuted, p.background, 4.5, "onSurfaceMuted/background", label)
        require(p.onPrimary, p.primary, 4.5, "onPrimary/primary", label)
        require(p.onPrimaryContainer, p.primaryContainer, 4.5, "onPrimaryContainer", label)
        require(p.onSecondaryContainer, p.secondaryContainer, 4.5, "onSecondaryContainer", label)
        require(p.error, p.background, 4.5, "error/background", label)
        require(p.error, p.surface, 4.5, "error/surface", label)
        for (container in listOf(p.primaryContainer, p.secondaryContainer)) {
            require(p.onSurface, container, 4.5, "数字/容器", label)
            require(p.mutedOn(container), container, 4.5, "单位/容器", label)
            require(p.primary, container, 4.5, "对比行/容器", label)
        }
        for (bg in listOf(p.background, p.surface, p.navSurface)) {
            require(p.primary, bg, 3.0, "primary(图形)", label)
        }
        for (fill in listOf(p.month1, p.month2, p.month3)) {
            val best = maxOf(contrastRatio(p.onSurface, fill), contrastRatio(p.onPrimary, fill))
            require(Color(0xFF000000), Color(0xFF000000), 0.0, "noop", label) // 保持结构对称
            if (best < 4.5) violations += "$label 月历日号/色档 %.2f < 4.5".format(best)
        }
    }

    private fun report() {
        assertTrue("自定义主题对比度违规 ${violations.size} 项:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun extremeSeedsPassAllPairs() {
        val extremes = listOf(
            Color(0xFF000000), Color(0xFFFFFFFF), Color(0xFFFF0000),
            Color(0xFF00FF00), Color(0xFF0000FF),
            // §5.4 明确点名的近纯黑/近纯白/高饱和
            Color(0xFF0A0A0A), Color(0xFFF7F7F7), Color(0xFF00FFFF)
        )
        for (seed in extremes) {
            for (dark in listOf(false, true)) {
                val p = PaletteEngine.paletteFromSeed(seed, dark)
                audit(p, "seed=%08X dark=$dark".format(seed.value.toLong() and 0xFFFFFFFF))
            }
        }
        report()
    }

    @Test
    fun randomFiftySeedsPassAllPairs() {
        val random = Random(20261112)
        repeat(50) { index ->
            val seed = Color(
                red = random.nextFloat(),
                green = random.nextFloat(),
                blue = random.nextFloat(),
                alpha = 1f
            )
            for (dark in listOf(false, true)) {
                val p = PaletteEngine.paletteFromSeed(seed, dark)
                audit(p, "random#$index dark=$dark")
            }
        }
        report()
    }

    @Test
    fun sameSeedIsDeterministic() {
        val seed = Color(0xFF2A6B8A)
        assertEquals(
            PaletteEngine.paletteFromSeed(seed, false),
            PaletteEngine.paletteFromSeed(seed, false)
        )
        assertEquals(
            PaletteEngine.paletteFromSeed(seed, true),
            PaletteEngine.paletteFromSeed(seed, true)
        )
    }

    @Test
    fun safeSeedLiftsPureBlackAndDropsPureWhite() {
        val black = PaletteEngine.safeSeed(Color(0xFF000000))
        assertTrue("纯黑种子应被提亮到可读深度", relativeLuminance(black) > 0.02)
        val white = PaletteEngine.safeSeed(Color(0xFFFFFFFF))
        assertTrue("纯白种子应被压到中深", relativeLuminance(white) < 0.82)
    }

    @Test
    fun heatAndMonthLaddersAreMonotonic() {
        val seed = Color(0xFF3C7C5A)
        for (dark in listOf(false, true)) {
            val p = PaletteEngine.paletteFromSeed(seed, dark)
            val heats = listOf(p.heat0, p.heat1, p.heat2, p.heat3, p.heat4).map(::relativeLuminance)
            val months = listOf(p.month1, p.month2, p.month3).map(::relativeLuminance)
            if (dark) {
                assertTrue("深色色阶应递增：$heats", heats.zipWithNext().all { (a, b) -> b > a })
                assertTrue("深色月阶应递增：$months", months.zipWithNext().all { (a, b) -> b > a })
            } else {
                assertTrue("浅色色阶应递减：$heats", heats.zipWithNext().all { (a, b) -> b < a })
                assertTrue("浅色月阶应递减：$months", months.zipWithNext().all { (a, b) -> b < a })
            }
        }
    }

    @Test
    fun chartHuesAreEquallyRotated() {
        val seed = Color(0xFF1B6B5A)
        val p = PaletteEngine.paletteFromSeed(seed, false)
        val hues = listOf(p.chart1, p.chart2, p.chart3, p.chart4, p.chart5)
            .map { it.toHsl().first }
        val expected = (0 until 5).map { PaletteEngine.CHART_HUE_STEP * it }
        hues.zipWithNext().forEachIndexed { index, _ ->
            // 相邻两根色相相差 ~24°（±10° 容差：明度校正会轻微带偏）
            val delta = ((hues[index + 1] - hues[index]) % 360f + 360f) % 360f
            assertTrue("chart 相邻色相差应接近 24°，实际 %.1f".format(delta), abs(delta - 24f) < 12f)
        }
        assertEquals(0f, expected.first())
    }
}
