package com.screentrace.app.uikit

import kotlin.math.floor

/**
 * 整数百分比分配（CR-07 §4.4 / §5.2 共用）。
 *
 * 直接对每个占比四舍五入会出现 99% 或 101%（三等分各 33%）；用**最大余数法**
 * 先向下取整，再把剩下的百分点按小数部分从大到小补出去，保证合计正好 100。
 * 并列时按原始顺序，结果确定、可复现。
 */
object PercentMath {

    fun distribute(values: List<Int>): List<Int> {
        if (values.isEmpty()) return emptyList()
        val total = values.sum()
        if (total <= 0) return List(values.size) { 0 }
        val exact = values.map { it * 100.0 / total }
        val percents = exact.map { floor(it).toInt() }.toMutableList()
        val byRemainder = exact.indices.sortedWith(
            compareByDescending<Int> { exact[it] - floor(exact[it]) }.thenBy { it }
        )
        var rest = 100 - percents.sum()
        var cursor = 0
        while (rest > 0) {
            percents[byRemainder[cursor % byRemainder.size]] += 1
            rest--
            cursor++
        }
        return percents
    }
}
