package com.screentrace.app.uikit

import com.screentrace.app.contract.AppUsage

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

        // 与时段分布共用同一套最大余数法（PercentMath），避免两处各写一份、口径慢慢漂移。
        val percents = PercentMath.distribute(visible.map { it.third })

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

    /**
     * CR-30-R11 第 0 步（UI 诚实化）：日间占比纯计算。
     *
     * 分母（当日时段总分钟数）<= 0 时返回 `null` 而非 0——`Int` 无法区分「缺分母」与
     * 「真实全夜间 0%」，下游 `right = 100 - left` 会把缺分母伪造成「日间 0% / 夜间 100%」。
     */
    fun dayPercent(totalMinutes: Int, nightMinutes: Int): Int? {
        if (totalMinutes <= 0) return null
        val clampedNight = nightMinutes.coerceIn(0, totalMinutes)
        return ((totalMinutes - clampedNight) * 100 / totalMinutes).coerceIn(0, 100)
    }

    /** 日间 / 夜间双侧展示百分比；缺分母时返回 `null`，不得补出 0% / 100%。 */
    data class DayNightSplit(val dayPercent: Int, val nightPercent: Int)

    fun dayNightSplit(dayPercent: Int?): DayNightSplit? {
        if (dayPercent == null) return null
        val left = dayPercent.coerceIn(0, 100)
        return DayNightSplit(dayPercent = left, nightPercent = 100 - left)
    }
}
