package com.screentrace.app.data.wallpaper

/**
 * §5.4 CR-19 ③：壁纸裁切的几何纯函数（JVM 可测，不依赖 Compose）。
 *
 * 坐标约定：图片先按 [fitScale]「完整显示」居中（Fit），再整体缩放 [zoom] 并平移
 * `(tx, ty)`（相对屏幕中心的像素偏移）。`zoom` 的下限是 [Geometry.minZoom]——
 * 保证图片在任何参数下都盖满屏幕（旧数据没有裁切参数时即等比盖满居中，与原
 * `ContentScale.Crop` 观感一致）。
 *
 * 持久化按相对屏幕宽高的**归一化**值存储（换机分辨率不同不吃亏），本对象只处理像素。
 */
object WallpaperCropMath {

    /** 缩放上限：超过这个倍数的细节已经没有意义，还能防一手极端手势。 */
    const val ZOOM_MAX = 4f

    data class Geometry(
        /** Fit 模式下图片的缩放系数（图片像素 → 屏幕像素）。 */
        val fitScale: Float,
        /** 图片按 fit 显示后的尺寸。 */
        val displayWidth: Float,
        val displayHeight: Float,
        /** zoom 下限：恰好盖满屏幕。 */
        val minZoom: Float
    )

    /**
     * 由图片与屏幕尺寸推导几何。任一边 ≤0 视为无效，返回 null（调用方回落默认绘制）。
     */
    fun geometry(imgWidth: Float, imgHeight: Float, screenW: Float, screenH: Float): Geometry? {
        if (imgWidth <= 0f || imgHeight <= 0f || screenW <= 0f || screenH <= 0f) return null
        val fit = minOf(screenW / imgWidth, screenH / imgHeight)
        val dispW = imgWidth * fit
        val dispH = imgHeight * fit
        val minZoom = maxOf(screenW / dispW, screenH / dispH)
        return Geometry(fit, dispW, dispH, minZoom)
    }

    /** zoom 夹取：下限 = 恰好盖满，上限 = [ZOOM_MAX]。 */
    fun clampedZoom(zoom: Float, geo: Geometry): Float =
        if (zoom.isNaN()) geo.minZoom else zoom.coerceIn(geo.minZoom, ZOOM_MAX)

    /**
     * 平移钳制：图片缩放后尺寸 `disp * zoom` 必须始终盖住屏幕，
     * 因此 |tx| ≤ (dispW·zoom − screenW)/2、|ty| 同理。
     */
    fun clampedTranslation(
        tx: Float, ty: Float, zoom: Float, geo: Geometry, screenW: Float, screenH: Float
    ): Pair<Float, Float> {
        val rangeX = ((geo.displayWidth * zoom - screenW) / 2f).coerceAtLeast(0f)
        val rangeY = ((geo.displayHeight * zoom - screenH) / 2f).coerceAtLeast(0f)
        return tx.coerceIn(-rangeX, rangeX) to ty.coerceIn(-rangeY, rangeY)
    }

    /**
     * 围绕焦点缩放：保持焦点下的图像点在屏幕上不动。
     * 推导：screen(p) = center + t + (p − imgCenter)·z；要求 screen'(p) = screen(p)
     * 对「当前在焦点下的那个 p」成立，解得 `t' = f − (f − t)·(z'/z)`，
     * 其中 `f` = 焦点相对**屏幕中心**的坐标（不含平移）。
     */
    fun focusedTranslation(
        tx: Float, ty: Float, zoom: Float, newZoom: Float,
        focusX: Float, focusY: Float
    ): Pair<Float, Float> {
        if (zoom <= 0f || newZoom <= 0f) return tx to ty
        val ratio = newZoom / zoom
        return (focusX - (focusX - tx) * ratio) to (focusY - (focusY - ty) * ratio)
    }
}
