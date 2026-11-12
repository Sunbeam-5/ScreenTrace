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
}
