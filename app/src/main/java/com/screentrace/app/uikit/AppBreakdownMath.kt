package com.screentrace.app.uikit

import com.screentrace.app.contract.AppUsage
import kotlin.math.floor

/**
 * CR-07 §4.4：应用构成卡的**纯数据部分**（色带与行式图例共用的同一份行模型）。
 *
 * 单独抽出来是为了能在 JVM 单测里钉死两件容易写错的事：
 * ① 颜色按「可见段顺序」分配 1→5，而不是让「其他」永远拿第 5 色（应用不足 4 个时会跳色，
 *    色带与列表的圆点就对不上了）；② 整数百分比用最大余数法，合计必须正好 100。
 */
data class BreakdownRow(
    val name: String,
    val pkg: String,
    val minutes: Int,
    val percent: Int,
    /** 对应 palette.chart1~5 的下标（0 基）。 */
    val colorIndex: Int
)

object AppBreakdownMath {

    /** 规格 §4.4：前 4 个应用 + 其他，最多 5 段。 */
    const val MAX_SEGMENTS = 5

    fun rows(topApps: List<AppUsage>, otherMinutes: Int): List<BreakdownRow> {
        val visible = buildList {
            topApps.forEach { app ->
                if (app.minutes > 0) add(Triple(app.name, app.pkg, app.minutes))
            }
            // 「其他」用固定伪包名，AppIcon 取不到图标时会回退首字母「其」。
            if (otherMinutes > 0) add(Triple("其他", "_other_", otherMinutes))
        }
        if (visible.isEmpty()) return emptyList()
        val total = visible.sumOf { it.third }
        if (total <= 0) return emptyList()

        val exact = visible.map { it.third * 100.0 / total }
        val percents = exact.map { floor(it).toInt() }.toMutableList()
        // 最大余数法：余数大的段优先补 1，直到合计 100；并列时保持原始顺序。
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

        return visible.mapIndexed { index, (name, pkg, minutes) ->
            BreakdownRow(
                name = name,
                pkg = pkg,
                minutes = minutes,
                percent = percents[index],
                colorIndex = index.coerceAtMost(MAX_SEGMENTS - 1)
            )
        }
    }
}
