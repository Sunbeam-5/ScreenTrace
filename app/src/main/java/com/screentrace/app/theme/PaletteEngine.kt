package com.screentrace.app.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * §5.4 CR-04：自定义主题派生引擎（种子色 → 整套语义色板）。
 *
 * 三条硬规则：
 * 1. **纯函数**：同一个 (seed, dark) 永远得到同一份色板，没有随机、没有读盘、没有 Compose 状态；
 * 2. **复用预设的混合比例**：色板主体走 [derivePalette]（九套主题用的同一套），
 *    只有 chart / month / heat 的色相与明度按种子重新派生——两边各写一套迟早会分成两种观感；
 * 3. **自动对比度校正**：派生完必须过与 `FreshPaletteContrastTest` 同一组断言
 *    （正文 ≥4.5:1、图形 ≥3:1），不达标按 ±2% 明度步进收敛；
 *    极端种子（近纯黑/纯白/高饱和）先落进安全明度窗口再派生。
 *
 * 壁纸动态取色是它的特例（系统给的种子色），只是动态取色走 Material 的 ColorScheme，
 * 这里产出的是自绘图表要用的 FreshPalette。
 */
object PaletteEngine {

    /** 明度步进（§5.4「±2% 明度步进」）。 */
    private const val STEP = 0.02f

    /** 单方向最多走 50 步（0.02 × 50 = 100% 明度，收敛有保证）。 */
    private const val MAX_STEPS = 50

    /** 种子色安全窗口：太黑看不清、太白压不住，先夹进来再谈派生。 */
    fun safeSeed(seed: Color): Color {
        val (h, s, l) = seed.toHsl()
        val luminance = relativeLuminance(seed)
        return when {
            // 近纯黑：提到有足够色相的深度，否则整套派生全是灰
            luminance < 0.02 || l < 0.14f -> hslColor(h, s.coerceAtMost(0.85f), 0.26f)
            // 近纯白：压到中深，否则 onPrimary/白卡全部扑街
            luminance > 0.82 || l > 0.90f -> hslColor(h, s, 0.46f)
            // 极端饱和：降一点饱和，避免 chart 系列色相互吞掉
            s > 0.95f -> hslColor(h, 0.86f, l)
            else -> seed
        }
    }

    fun paletteFromSeed(seed: Color, dark: Boolean): FreshPalette {
        val base = safeSeed(seed)
        // tonal 与导航底按 §5.4 既有规则从种子派生（预设是手写锚点，自定义只能算）
        val hue = base.toHsl().first
        val tonalA = hslColor(hue, base.toHsl().second * 0.42f, if (dark) 0.30f else 0.78f)
        val tonalB = hslColor(hue + 32f, base.toHsl().second * 0.34f, if (dark) 0.34f else 0.84f)
        val navLight = hslColor(hue, base.toHsl().second * 0.40f, 0.84f)
        val backgroundLight = hslColor(hue, base.toHsl().second * 0.10f, 0.965f)

        val derived = derivePalette(
            seed = base,
            tonalA = tonalA,
            tonalB = tonalB,
            navLight = navLight,
            backgroundLight = backgroundLight,
            darkTheme = dark
        )
        return correct(derived, base, dark)
    }

    /**
     * 校正阶段：只动**文字/主色/容器**三族，背景与卡片保持派生结果
     * （背景一动，整页布局的「参考面」就变了，视觉上会比文字偏色更明显）。
     */
    private fun correct(p: FreshPalette, seed: Color, dark: Boolean): FreshPalette {
        // ① 卡片底色：浅色主题下若种子太深，surfaceVariant 会深到深色正文看不清
        val surfaceVariant = if (dark) {
            p.surfaceVariant
        } else {
            fit(p.surfaceVariant, darkenFirst = false) { candidate ->
                contrastRatio(p.onSurface, candidate) >= 4.5
            }
        }

        // ② 次要正文：对 background / surface / surfaceVariant 三处都要 ≥4.5
        val bgs = listOf(p.background, p.surface, surfaceVariant)
        val onSurfaceVariant = fit(p.onSurfaceVariant, darkenFirst = !dark) { candidate ->
            bgs.all { contrastRatio(candidate, it) >= 4.5 }
        }

        // ③ 深色容器：预设主题的种子都很深，容器自然够暗；随机种子（尤其是亮色）会让
        //    `seed.mix(DarkNeutral, 0.42)` 停在中间灰，深色主题里「数字/单位/对比行」三处
        //    一起掉到 3.1–4.4，正是 §5.4 说的「必须自动校正」的场景。先压暗容器，
        //    后面的主色与容器文字都基于压暗后的容器再校正。
        val primaryContainer = if (dark) {
            fit(p.primaryContainer, darkenFirst = true) { candidate ->
                contrastRatio(p.onSurface, candidate) >= 4.5
            }
        } else {
            p.primaryContainer
        }
        val secondaryContainer = if (dark) {
            fit(p.secondaryContainer, darkenFirst = true) { candidate ->
                contrastRatio(p.onSurface, candidate) >= 4.5
            }
        } else {
            p.secondaryContainer
        }

        // ④ 主色：图形对底 ≥3:1；对比行（14sp/600 + primary）落在两张 tonal 卡上要 ≥4.5；
        //    并且必须存在一个能达 4.5 的 onPrimary（白或深墨）。
        val primary = fit(p.primary, darkenFirst = !dark) { candidate ->
            contrastRatio(candidate, p.background) >= 3.0 &&
                contrastRatio(candidate, p.surface) >= 3.0 &&
                contrastRatio(candidate, p.navSurface) >= 3.0 &&
                contrastRatio(candidate, primaryContainer) >= 4.5 &&
                contrastRatio(candidate, secondaryContainer) >= 4.5 &&
                bestOn(candidate) != null
        }
        val onPrimary = bestOn(primary) ?: p.onPrimary

        // ⑤ 容器上的文字：容器颜色不动，文字往远离容器的方向走
        val onPrimaryContainer = fit(p.onPrimaryContainer, darkenFirst = !dark) { candidate ->
            contrastRatio(candidate, primaryContainer) >= 4.5
        }
        val onSecondaryContainer = fit(p.onSecondaryContainer, darkenFirst = !dark) { candidate ->
            contrastRatio(candidate, secondaryContainer) >= 4.5
        }

        // ⑥ 月历色档：日号取 onSurface / onPrimary 中较优者，必须 ≥4.5
        val month1 = fitMonth(p.month1, p, dark)
        val month2 = fitMonth(p.month2, p, dark)
        val month3 = fitMonth(p.month3, p, dark)

        // ⑥ chart 系列：§5.4「chart1~5 由种子色相在色轮上等距旋转派生」，
        //    明度同时按 0/0.18/0.36/0.54/0.70 阶梯递增（与预设的观感一致），
        //    再逐个兜到对卡片 ≥3:1（必要图形）。
        val seedHsl = seed.toHsl()
        val charts = (0 until 5).map { index ->
            val rotated = hslColor(
                seedHsl.first + CHART_HUE_STEP * index,
                seedHsl.second,
                seedHsl.third + if (dark) index * 0.07f else index * 0.18f
            )
            fit(rotated, darkenFirst = !dark) { candidate ->
                contrastRatio(candidate, p.surface) >= 3.0
            }
        }

        return p.copy(
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            primaryContainer = primaryContainer,
            secondaryContainer = secondaryContainer,
            primary = primary,
            onPrimary = onPrimary,
            onPrimaryContainer = onPrimaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            month1 = month1,
            month2 = month2,
            month3 = month3,
            chart1 = charts[0],
            chart2 = charts[1],
            chart3 = charts[2],
            chart4 = charts[3],
            chart5 = charts[4],
            // 单位色与主色同源重新派生：主色被校正过，旧的 muted 可能又不够了
            onSurfaceMuted = muteForeground(p.onSurface, p.surface, dark)
        )
    }

    /** 月历色档：保证「日号文字」始终有 ≥4.5 的选择（浅色往亮走、深色往暗走）。 */
    private fun fitMonth(fill: Color, p: FreshPalette, dark: Boolean): Color =
        fit(fill, darkenFirst = dark) { candidate ->
            val best = maxOf(
                contrastRatio(p.onSurface, candidate),
                contrastRatio(p.onPrimary, candidate)
            )
            best >= 4.5
        }

    /** 白 / 深墨里挑一个能对 [bg] 达到 4.5 的；都不行返回 null（调用方据此继续调色调）。 */
    private fun bestOn(bg: Color): Color? {
        val candidates = listOf(White, DeepInk)
        val passing = candidates.filter { contrastRatio(it, bg) >= 4.5 }
        if (passing.isEmpty()) return null
        return passing.maxBy { contrastRatio(it, bg) }
    }

    /**
     * 明度方向性搜索：先按 [darkenFirst] 指定的方向走，走到底仍不满足再反向。
     * 返回第一个满足 [predicate] 的颜色；都不满足则原样返回（不制造假达标）。
     */
    private fun fit(start: Color, darkenFirst: Boolean, predicate: (Color) -> Boolean): Color {
        if (predicate(start)) return start
        val (h, s, l) = start.toHsl()
        val directions = if (darkenFirst) listOf(-1f, 1f) else listOf(1f, -1f)
        for (dir in directions) {
            var current = l
            repeat(MAX_STEPS) {
                current += dir * STEP
                if (current !in 0f..1f) return@repeat
                val candidate = hslColor(h, s, current)
                if (predicate(candidate)) return candidate
            }
        }
        return start
    }

    /** chart 与 month 的色相旋转量（等距 24°，§5.4「色轮上等距旋转」）。 */
    const val CHART_HUE_STEP = 24f
}

// ---- HSL 工具（Compose Color 无公开 HSL API，自己算；顶层 internal 便于单测直接调用） ----

internal fun Color.toHsl(): Triple<Float, Float, Float> {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    if (max - min < 1e-6f) return Triple(0f, 0f, l)
    val d = max - min
    val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
    val hue = when (max) {
        r -> ((g - b) / d + if (g < b) 6f else 0f) * 60f
        g -> ((b - r) / d + 2f) * 60f
        else -> ((r - g) / d + 4f) * 60f
    }
    return Triple(hue, s, l)
}

internal fun hslColor(h: Float, s: Float, l: Float): Color {
    val hue = ((h % 360f) + 360f) % 360f
    val sat = s.coerceIn(0f, 1f)
    val light = l.coerceIn(0f, 1f)
    val c = (1f - abs(2f * light - 1f)) * sat
    val x = c * (1f - abs((hue / 60f) % 2f - 1f))
    val m = light - c / 2f
    val (r, g, b) = when {
        hue < 60f -> Triple(c, x, 0f)
        hue < 120f -> Triple(x, c, 0f)
        hue < 180f -> Triple(0f, c, x)
        hue < 240f -> Triple(0f, x, c)
        hue < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(
        red = (r + m).coerceIn(0f, 1f),
        green = (g + m).coerceIn(0f, 1f),
        blue = (b + m).coerceIn(0f, 1f),
        alpha = 1f
    )
}
