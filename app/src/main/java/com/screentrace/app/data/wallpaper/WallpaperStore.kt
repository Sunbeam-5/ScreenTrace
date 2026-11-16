package com.screentrace.app.data.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * 壁纸分析结果：平均亮度（状态栏图标明暗用）+ **主色**（CR-20 ② 卡片配色派生用）。
 */
data class WallpaperAnalysis(
    val brightness: Int,
    val seedColor: Int
)

/**
 * §5.4 批 E：壁纸图片的唯一读写口。
 *
 * 红线：只写 `context.noBackupFilesDir`（随卸载/清数据消失，**不进 JSON 备份**）；
 * 不引第三方图片库、不申请媒体读取权限；选图取消/解码失败/写盘失败一律返回 null，
 * 调用方保留原壁纸与参数（不清空已有设置）。
 *
 * [open] 可注入，单测用 FileInputStream 替掉 ContentResolver，不必注册假 Provider。
 */
class WallpaperStore(
    private val context: Context,
    private val open: (Uri) -> InputStream? = { context.contentResolver.openInputStream(it) }
) {
    private val dir: File
        get() = File(context.noBackupFilesDir ?: context.filesDir, "wallpaper")

    val file: File
        get() = File(dir, "wallpaper.jpg")

    fun exists(): Boolean = file.exists() && file.length() > 0L

    /** 冷启动载入；UI 层只在这里解码一次，之后以 CompositionLocal 传同一份位图。 */
    fun loadBitmap(): Bitmap? = if (!exists()) null else runCatching {
        BitmapFactory.decodeFile(file.absolutePath)
    }.getOrNull()

    /**
     * 选图落盘，返回壁纸分析结果（平均亮度 + 主色）；任何一步失败返回 null。
     * 顺序：读 bounds → inSampleSize 解码 → 精确缩到 ≤1920 → EXIF 旋转 → 采样分析 → JPEG 写盘。
     */
    fun save(uri: Uri): WallpaperAnalysis? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // 注意：inJustDecodeBounds=true 时 decodeStream 本来就返回 null，
        // 不能用返回值判断成败，只看 bounds 有没有被填上。
        runCatching { open(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = WallpaperTuning.sampleSize(w, h)
        }
        val decoded = runCatching {
            open(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull() ?: return null

        val scaled = scaleToMaxEdge(decoded)
        val rotated = rotateByExif(uri, scaled)
        val analysis = analyze(rotated)
        val ok = runCatching {
            dir.mkdirs()
            FileOutputStream(file).use { out ->
                if (!rotated.compress(Bitmap.CompressFormat.JPEG, 88, out)) {
                    error("bitmap compress failed")
                }
            }
            true
        }.getOrDefault(false)
        rotated.recycle()
        return if (ok) analysis else null
    }

    /**
     * 采样分析：平均亮度（状态栏图标明暗用）+ **壁纸主色**（CR-20 ② 卡片配色派生用）。
     *
     * 与选图路径共用同一份 32×32 采样，只吃整数像素，JVM 单测可打靶。
     */
    fun analyze(bitmap: Bitmap): WallpaperAnalysis {
        val pixels = samplePixels(bitmap)
        return WallpaperAnalysis(
            brightness = WallpaperTuning.brightnessOf(pixels),
            seedColor = WallpaperTuning.dominantSeedOf(pixels)
        )
    }

    /** 恢复默认：删私有 JPEG；文件不存在也算成功（目标状态已达成）。 */
    fun clear(): Boolean = runCatching {
        !file.exists() || file.delete()
    }.getOrDefault(false)

    /** inSampleSize 只给 2 的幂，这里再精确收一次，保证长边严格 ≤1920。 */
    private fun scaleToMaxEdge(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= WallpaperTuning.MAX_EDGE || longest <= 0) return bitmap
        val ratio = WallpaperTuning.MAX_EDGE.toFloat() / longest
        val nw = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val nh = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, nw, nh, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /** 系统 API（非第三方库）；相册竖拍照片靠 EXIF 而不是靠文件像素方向。 */
    @Suppress("DEPRECATION")
    private fun rotateByExif(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            open(uri)?.use {
                android.media.ExifInterface(it).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL
                )
            } ?: android.media.ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        return runCatching {
            val matrix = Matrix().apply { postRotate(degrees) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it !== bitmap) bitmap.recycle() }
        }.getOrDefault(bitmap)
    }

    private fun samplePixels(bitmap: Bitmap): IntArray {
        val size = 32
        val thumb = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val pixels = IntArray(size * size)
        thumb.getPixels(pixels, 0, size, 0, 0, size, size)
        if (thumb !== bitmap) thumb.recycle()
        return pixels
    }
}
