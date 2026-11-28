package com.screentrace.app.uikit

/**
 * CR-07 §4.5：柱图选中日的定位规则（纯函数，便于 JVM 单测）。
 *
 * 默认选中最后一天（今天）；左右滑动按天步进并在 [0, count-1] 内夹取。
 * 抽出这两个小函数是为了让「默认不是第一天」「滑到两端不越界」能被测试钉住——
 * 这两处在真机上都是点几下才看得出来的错。
 */
object TrendSelectionMath {

    fun defaultIndex(count: Int): Int = if (count <= 0) -1 else count - 1

    fun step(current: Int, delta: Int, count: Int): Int =
        if (count <= 0) -1 else (current + delta).coerceIn(0, count - 1)

    /** 批 14c：按手指相对绘图区左边缘的 X 坐标直接命中对应柱体下标（真正跟手，不反向）。 */
    fun indexForTouchX(touchX: Float, slotWidthPx: Float, count: Int): Int {
        if (count <= 0 || slotWidthPx <= 0f) return -1
        return (touchX / slotWidthPx).toInt().coerceIn(0, count - 1)
    }

    /**
     * ReviewFix P2-1：统一跟手命中换算。
     *
     * 可滚动长图（>30 天，如 31 天月周期）必须使用实际槽宽 [scrollableSlotPx]（12dp）并加上
     * 当前横向滚动偏移 [scrollOffsetPx]；不可滚动图按 [viewportWidthPx] / [count] 均分。
     */
    fun indexForDrag(
        localX: Float,
        scrollOffsetPx: Float,
        viewportWidthPx: Float,
        scrollable: Boolean,
        scrollableSlotPx: Float,
        count: Int
    ): Int {
        if (count <= 0) return -1
        val slotPx = if (scrollable) scrollableSlotPx else viewportWidthPx / count
        if (slotPx <= 0f) return -1
        val contentX = if (scrollable) localX + scrollOffsetPx.coerceAtLeast(0f) else localX
        return (contentX / slotPx).toInt().coerceIn(0, count - 1)
    }
}
