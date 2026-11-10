package com.screentrace.app.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5.4 / §7-10：九套主题 × 深浅色的 WCAG 对比度回归（批次 1 评审 P1-2 / Kimi S07）。
 * 正文色对 ≥ 4.5:1；图形/大字色对 ≥ 3:1。逐套人工抽查改为自动断言，
 * 任何色板改动跑此测试即可复算，禁止凭感觉调色。
 *
 * 注：month1~3 / heat0~4 是需求 §5.4 明确规定的浅→深色阶填充，
 * 其含义靠相邻档位差与图例传达，不按图形 3:1 断言。
 */
class FreshPaletteContrastTest {

    private fun assertAtLeast(
        fg: Color,
        bg: Color,
        min: Double,
        name: String,
        variant: ThemeVariant,
        dark: Boolean
    ) {
        val ratio = contrastRatio(fg, bg)
        assertTrue(
            "%s%s %s 对比度 %.2f < %.1f".format(variant.label, if (dark) "深色" else "浅色", name, ratio, min),
            ratio >= min
        )
    }

    @Test
    fun textPairsMeet45InAllVariants() {
        for (variant in ThemeVariant.values()) {
            for (dark in listOf(false, true)) {
                val p = variant.palette(dark)
                for (bg in listOf(p.background, p.surface, p.surfaceVariant)) {
                    assertAtLeast(p.onSurface, bg, 4.5, "onSurface", variant, dark)
                    assertAtLeast(p.onSurfaceVariant, bg, 4.5, "onSurfaceVariant", variant, dark)
                    // §5.4 单位色规则：任意背景上经 mutedOn 派生后不得破 4.5
                    assertAtLeast(p.mutedOn(bg), bg, 4.5, "mutedOn(bg)", variant, dark)
                }
                // onSurfaceMuted 是白卡系（surface/background）上的固定取值
                assertAtLeast(p.onSurfaceMuted, p.surface, 4.5, "onSurfaceMuted/surface", variant, dark)
                assertAtLeast(p.onSurfaceMuted, p.background, 4.5, "onSurfaceMuted/background", variant, dark)
                assertAtLeast(p.onPrimary, p.primary, 4.5, "onPrimary/primary", variant, dark)
                assertAtLeast(
                    p.onPrimaryContainer, p.primaryContainer, 4.5, "onPrimaryContainer", variant, dark
                )
                assertAtLeast(
                    p.onSecondaryContainer, p.secondaryContainer, 4.5, "onSecondaryContainer", variant, dark
                )
                assertAtLeast(p.error, p.background, 4.5, "error/background", variant, dark)
                assertAtLeast(p.error, p.surface, 4.5, "error/surface", variant, dark)
            }
        }
    }

    /** §5.3/§5.5：MetricCard 数值（数字 onSurface）与单位色在 tonal 容器上的色对。 */
    @Test
    fun metricValuePairsMeet45OnTonalContainers() {
        for (variant in ThemeVariant.values()) {
            for (dark in listOf(false, true)) {
                val p = variant.palette(dark)
                for (container in listOf(p.primaryContainer, p.secondaryContainer)) {
                    val name = if (container == p.primaryContainer) "primaryContainer" else "secondaryContainer"
                    assertAtLeast(p.onSurface, container, 4.5, "数字/$name", variant, dark)
                    assertAtLeast(p.mutedOn(container), container, 4.5, "单位/$name", variant, dark)
                    // §5.3 对比行（14sp/600 + primary）落在 tonal 卡上
                    assertAtLeast(p.primary, container, 4.5, "对比行/$name", variant, dark)
                }
            }
        }
    }

    /** §5.4：onSurfaceMuted 白卡取值应与规格一致地「明显高于」4.5（浅 ≈5.2 / 深 ≈4.6）。 */
    @Test
    fun mutedTokenOnWhiteCardMatchesSpecLevel() {
        val light = ThemeVariant.MINT.palette(false)
        assertTrue(
            "浅色 onSurfaceMuted/白卡 %.2f 应 ≥5.0（规格 5.20）".format(contrastRatio(light.onSurfaceMuted, light.surface)),
            contrastRatio(light.onSurfaceMuted, light.surface) >= 5.0
        )
    }

    @Test
    fun graphicsPairsMeet3InAllVariants() {
        for (variant in ThemeVariant.values()) {
            for (dark in listOf(false, true)) {
                val p = variant.palette(dark)
                for (bg in listOf(p.background, p.surface, p.navSurface)) {
                    assertAtLeast(p.primary, bg, 3.0, "primary(图形)", variant, dark)
                }
            }
        }
    }
}
