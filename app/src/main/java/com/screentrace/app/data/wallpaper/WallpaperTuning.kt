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
    val brightness: Int = 128
) {
    companion object {
        const val DEFAULT_DIM = 30
    }
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

    fun clampDim(value: Int): Int = value.coerceIn(DIM_MIN, DIM_MAX)
    fun clampBlur(value: Int): Int = value.coerceIn(BLUR_MIN, BLUR_MAX)
    fun clampBrightness(value: Int): Int = value.coerceIn(0, 255)

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
    fun themeScrimAlpha(brightness: Int, darkTheme: Boolean): Float {
        val b = clampBrightness(brightness)
        val auto = if (darkTheme) {
            (b - 96).coerceAtLeast(0) / 159f
        } else {
            (128 - b).coerceAtLeast(0) / 128f
        }
        return (0.15f + auto * 0.40f).coerceIn(0.15f, 0.55f)
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
