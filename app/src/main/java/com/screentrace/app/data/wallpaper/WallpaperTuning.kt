package com.screentrace.app.data.wallpaper

/**
 * §5.4 批 E：壁纸参数与遮罩口径的**唯一来源**（纯函数，JVM 单测可打靶）。
 *
 * 绘制顺序与这里一致：壁纸 → 用户暗化（黑）→ 主题底色（浅色叠白 / 深色叠黑）。
 * 状态栏图标按「遮罩后实际背景亮度」判定，而不是直接跟系统深浅色。
 */
data class WallpaperConfig(
    val enabled: Boolean = false,
    val dimPercent: Int = DEFAULT_DIM,
    val blurDp: Int = 0,
    val brightness: Int = 128,
    /** CR-19 ③：裁切缩放（相对「完整显示」的倍数）；1.0 时绘制端自动抬到恰好盖满。 */
    val cropScale: Float = 1f,
    /** CR-19 ③：归一化平移（相对屏幕宽高），旧数据缺省即等比盖满居中。 */
    val cropOffsetX: Float = 0f,
    val cropOffsetY: Float = 0f,
    /** CR-20 ②：壁纸主色（ARGB）；0 = 尚未取色（旧数据会在载入时补算）。 */
    val seedColor: Int = 0,
    /** CR-20 ②：哪些颜色跟随壁纸——两套方案由用户在设置页切换对比。 */
    val colorScope: WallpaperColorScope = WallpaperColorScope.CARDS_ONLY
) {
    companion object {
        const val DEFAULT_DIM = 30
    }
}

/**
 * §5.4 CR-20 ②：壁纸模式下「哪些颜色跟随壁纸」的**两套方案**。
 *
 * 负责人要看真机对比，所以做成可切换而不是二选一写死：
 * - [CARDS_ONLY]：只有卡片底色跟随壁纸派生，强调色 / 图表色仍取当前主题；
 * - [FULL_THEME]：强调色、主色、图表色、月历色阶一并按壁纸主色派生。
 */
enum class WallpaperColorScope(val label: String) {
    CARDS_ONLY("仅卡片"),
    FULL_THEME("整套主题")
}

object WallpaperTuning {
    /** 下采样硬上限：解码后长边不得超过它（§5.4）。 */
    const val MAX_EDGE = 1920
    const val DIM_MIN = 0
    const val DIM_MAX = 60
    const val BLUR_MIN = 0
    const val BLUR_MAX = 16
    /** `Modifier.blur` 依赖 RenderEffect，只有 Android 12+ 生效。 */
    const val MIN_API_FOR_BLUR = 31

    /**
     * CR-20 ②：没取到主色时的兜底种子（中性灰）。
     * 灰种子派生出的卡片色是中性浅灰 / 深灰——不偏色，也不会凭空造一个「壁纸风格」。
     *
     * 注意：`0xFF808080` 超出 Int 正数范围，字面量会被推成 Long，所以这里显式 `.toInt()`
     * （ARGB 存进 Int 就是同一个位模式）。
     */
    val DEFAULT_SEED: Int = 0xFF808080.toInt()

    /** 主色分桶数（色相 12 桶，每桶 30°）。 */
    private const val HUE_BUCKETS = 12

    /** 饱和度低于此值的像素视为灰色，不参与分桶（否则整张灰图会取到一个无意义的桶）。 */
    private const val MIN_SATURATION = 0.08f

    fun clampDim(value: Int): Int = value.coerceIn(DIM_MIN, DIM_MAX)
    fun clampBlur(value: Int): Int = value.coerceIn(BLUR_MIN, BLUR_MAX)
    fun clampBrightness(value: Int): Int = value.coerceIn(0, 255)

    // ---- CR-20 ②：壁纸取色与卡片半透明 ----

    /**
     * 卡片底色保留的不透明度：浅色 60%、深色 55%（§5.4 CR-20 ②）。
     *
     * CR-19 ② 的 92% / 88% 几乎不透（负责人反馈「壁纸快被挡完」），
     * 这里大幅下调，让壁纸真正透出来。
     */
    fun cardAlpha(darkTheme: Boolean): Float = if (darkTheme) 0.55f else 0.60f

    /** 半透明卡片对应的遮罩加深量，保住文字对比度。 */
    fun translucentCardBoost(darkTheme: Boolean): Float = if (darkTheme) 0.12f else 0.16f

    /**
     * 壁纸主色提取（CR-20 ②）：从缩略图采样像素里挑一个能代表整张图的主色（ARGB）。
     *
     * 做法：按色相分 12 个桶，各桶记像素数与饱和度均值，取「像素数 × (1 + 平均饱和度)」
     * 最高的一桶，用它的平均色作种子——这样一张以蓝天为主、落日占少数的图会取到蓝，
     * 而不是被少数高饱和像素带跑。整张图接近灰色时退回整体平均色。
     *
     * 纯函数、只吃整数像素，JVM 单测可打靶。
     */
    fun dominantSeedOf(pixels: IntArray): Int {
        if (pixels.isEmpty()) return DEFAULT_SEED
        val counts = IntArray(HUE_BUCKETS)
        val satSum = FloatArray(HUE_BUCKETS)
        // 采样最多 32×32 = 1024 像素，每通道总和 ≤ 261120，Int 足够
        val sumR = IntArray(HUE_BUCKETS)
        val sumG = IntArray(HUE_BUCKETS)
        val sumB = IntArray(HUE_BUCKETS)
        var totalR = 0
        var totalG = 0
        var totalB = 0
        var total = 0
        for (p in pixels) {
            if ((p ushr 24) and 0xFF < 128) continue // 半透明像素不算
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            totalR += r
            totalG += g
            totalB += b
            total++
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val sat = if (max == 0) 0f else (max - min).toFloat() / max.toFloat()
            if (sat < MIN_SATURATION) continue
            val idx = hueBucket(r, g, b)
            counts[idx]++
            satSum[idx] += sat
            sumR[idx] += r
            sumG[idx] += g
            sumB[idx] += b
        }
        var best = -1
        var bestScore = -1f
        for (i in 0 until HUE_BUCKETS) {
            if (counts[i] == 0) continue
            val score = counts[i] * (1f + satSum[i] / counts[i])
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        if (best < 0) {
            // 全灰（没有可分桶的像素）：退回整体平均色
            if (total == 0) return DEFAULT_SEED
            return argb(totalR / total, totalG / total, totalB / total)
        }
        val n = counts[best]
        return argb(sumR[best] / n, sumG[best] / n, sumB[best] / n)
    }

    /** 色相分桶：0–11，每桶 30°。灰度像素（d == 0）归到 0 桶。 */
    private fun hueBucket(r: Int, g: Int, b: Int): Int {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        if (d == 0) return 0
        val hue = when (max) {
            r -> 60f * (((g - b).toFloat() / d) + if (g < b) 6f else 0f)
            g -> 60f * (((b - r).toFloat() / d) + 2f)
            else -> 60f * (((r - g).toFloat() / d) + 4f)
        }
        return (hue / 30f).toInt().coerceIn(0, HUE_BUCKETS - 1)
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun clampCropScale(value: Float): Float =
        if (value.isNaN()) 1f else value.coerceIn(1f, WallpaperCropMath.ZOOM_MAX)

    fun clampCropOffset(value: Float): Float =
        if (value.isNaN()) 0f else value.coerceIn(-1f, 1f)

    /**
     * `BitmapFactory.Options.inSampleSize`：取 2 的幂，保证解码后长边 ≤ [MAX_EDGE]。
     * 宁可略小也不超，避免一张 8000px 长图直接解码把内存打爆。
     */
    fun sampleSize(width: Int, height: Int): Int {
        val longest = maxOf(width, height)
        if (longest <= 0) return 1
        var sample = 1
        while (longest / sample > MAX_EDGE) sample *= 2
        return sample
    }

    /** 缩略图像素 → 平均亮度（0–255，Rec.601 加权）。空输入按中性灰。 */
    fun brightnessOf(pixels: IntArray): Int {
        if (pixels.isEmpty()) return 128
        var sum = 0L
        for (c in pixels) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            sum += (299L * r + 587L * g + 114L * b) / 1000L
        }
        return (sum / pixels.size).toInt().coerceIn(0, 255)
    }

    /** 用户「暗化」滑杆（0–60%）→ 黑色遮罩不透明度（上限 0.6，留一点壁纸细节）。 */
    fun dimAlpha(dimPercent: Int): Float = clampDim(dimPercent) / 100f

    /**
     * 主题底色不透明度：浅色主题遇到暗壁纸要多叠白，深色主题遇到亮壁纸要多叠黑，
     * 始终保留一层 0.15 的兜底，保证 §5.4 正文 ≥4.5:1 的对比度红线。
     */
    fun themeScrimAlpha(
        brightness: Int,
        darkTheme: Boolean,
        /** CR-19 ②：卡片半透明时自动加深，把文字压回对比度红线之上。 */
        cardTranslucent: Boolean = false
    ): Float {
        val b = clampBrightness(brightness)
        val auto = if (darkTheme) {
            (b - 96).coerceAtLeast(0) / 159f
        } else {
            (128 - b).coerceAtLeast(0) / 128f
        }
        val base = (0.15f + auto * 0.40f).coerceIn(0.15f, 0.55f)
        return if (cardTranslucent) {
            (base + translucentCardBoost(darkTheme)).coerceIn(0.15f, 0.75f)
        } else {
            base
        }
    }

    /** 遮罩后实际背景亮度（0–255）：先给壁纸压暗，再与主题底色混合。 */
    fun effectiveBrightness(
        wallpaperBrightness: Int,
        dimAlpha: Float,
        themeScrimAlpha: Float,
        darkTheme: Boolean
    ): Int {
        val dimmed = clampBrightness(wallpaperBrightness) * (1f - dimAlpha.coerceIn(0f, 1f))
        val scrim = if (darkTheme) 0f else 255f
        val alpha = themeScrimAlpha.coerceIn(0f, 1f)
        return (dimmed * (1f - alpha) + scrim * alpha).toInt().coerceIn(0, 255)
    }

    /**
     * true = 深色状态栏图标（亮背景）。阈值取 140 而非 128：真机复核发现暗化 58%
     * 时有效亮度恰好落在 128 附近，深色图标压在变暗的壁纸上对比度不足；
     * 稍微保守一点，背景不够亮就切浅色图标。
     */
    fun lightStatusIcons(effectiveBrightness: Int): Boolean =
        clampBrightness(effectiveBrightness) >= 140

    fun blurSupported(sdkInt: Int): Boolean = sdkInt >= MIN_API_FOR_BLUR

    /** 低版本一律返回 0，UI 同时把滑杆禁用并给出提示，不静默假装生效。 */
    fun effectiveBlurDp(blurDp: Int, sdkInt: Int): Float =
        if (blurSupported(sdkInt)) clampBlur(blurDp).toFloat() else 0f
}
