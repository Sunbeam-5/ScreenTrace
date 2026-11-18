package com.screentrace.app.data.wallpaper

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * §5.4 CR-24：壁纸模糊的「一次性位图缓存」。
 *
 * 旧实现把 `Modifier.blur` 挂在壁纸 `Image` 上——那是**每帧** RenderEffect，
 * 而 `beyondBoundsPageCount = 3` 让三页常驻，等于把它乘以 3，是壁纸模式下
 * 唯一确定的 GPU 开销（vivo 实测约 1ms GPU/帧）。现在改成：**只在壁纸位图或
 * `blurDp` 变化时算一次**，绘制端只贴这张已模糊位图。
 *
 * ## 半径口径（1px ≈ 1dp）
 *
 * 模糊前先按 **density 降采样**，于是降采样位图上 1px ≈ 屏幕 1dp，
 * `blurDp` 就直接是半径像素数，与旧的 `Modifier.blur(blurDp.dp)` 同语义；
 * 长边再钳到 [WallpaperTuning.BLUR_BITMAP_MAX_EDGE]（手机上天然落在 400×800 量级）。
 * 后续裁切缩放会等比放大模糊半径——与旧口径一致（旧的也是先模糊后缩放）。
 *
 * ## 为什么不是逐像素等价
 *
 * 降采样 + 三趟盒式模糊与 RenderEffect 的高斯并非同一算法，视觉上是「同量级」
 * 而不是逐像素相同；用户本来也是拖滑杆看效果，口径写在这里即可。
 */
object WallpaperBlur {

    /**
     * 把 [src] 模糊成一张可直接贴上屏的位图。
     *
     * @param blurDp 模糊半径（dp）；`<= 0` 直接返回 [src]（不复制、不模糊）。
     * @param density 屏幕 density（px / dp），用来做「1px ≈ 1dp」的降采样。
     */
    fun blurredImage(src: ImageBitmap, blurDp: Int, density: Float): ImageBitmap =
        com.screentrace.app.uikit.PerfProbe.measure("Wallpaper/blur") { blurImpl(src, blurDp, density) }

    private fun blurImpl(src: ImageBitmap, blurDp: Int, density: Float): ImageBitmap {
        val radius = WallpaperTuning.clampBlur(blurDp)
        if (radius <= 0) return src
        val scale = if (density > 1f) 1f / density else 1f
        // 长边钳到上限：低密度平板上按 density 降采样后仍可能很大
        val maxEdge = WallpaperTuning.BLUR_BITMAP_MAX_EDGE
        val fit = minOf(scale, maxEdge.toFloat() / maxOf(src.width, src.height))
        val w = (src.width * fit).toInt().coerceAtLeast(1)
        val h = (src.height * fit).toInt().coerceAtLeast(1)

        val sampled = Bitmap.createScaledBitmap(src.asAndroidBitmap(), w, h, true)
        val pixels = IntArray(w * h)
        sampled.getPixels(pixels, 0, w, 0, 0, w, h)
        val blurred = WallpaperTuning.blurArgb(w, h, pixels, radius)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(blurred, 0, w, 0, 0, w, h)
        if (sampled !== src.asAndroidBitmap()) sampled.recycle()
        return out.asImageBitmap()
    }
}
