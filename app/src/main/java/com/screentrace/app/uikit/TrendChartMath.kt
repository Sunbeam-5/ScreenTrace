package com.screentrace.app.uikit

import com.screentrace.app.contract.DailyPoint
import kotlin.math.roundToInt

/**
 * 趋势图几何（批 6g，需求 §5.7-2~5）：纯函数，JVM 可测。
 *
 * 之前量程是「0 → 向上取整到整小时，均分 4 档」，且 8 天以上一律横向滚动。
 * 现在按小米「屏幕使用时间」的对照图统一为：
 * - 顶部刻度 = `min(ceil(最大值到整小时), 24h)`，永远不超过一天；
 * - 只画 0 / 中 / 顶 三个刻度 + 一条平均线；
 * - 近 30 天细柱**一屏放下**（不滚动），日期标签只留 4 个。
 *
 * 放在独立 object 里而不是内联进 Composable，是为了这些规则能被单测钉住——
 * 量程算错会让柱子溢出卡片，标签算错会让日期叠字，都是真机上才看得出来的错。
 */
object TrendChartMath {

    /** 一天的分钟数：顶部刻度的硬上限。 */
    const val MAX_SCALE_MIN = 24 * 60

    /** 一屏放得下的最大天数：30 天必须不滚动，31 天起才允许横向滚动（§5.7-4）。 */
    const val SCROLL_THRESHOLD = 30

    /**
     * 顶部刻度（分钟）：向上取整到整小时，最小 1 小时，最大 24 小时。
     * 用了 23 小时 40 分钟 → 1440（24 小时）；全零 → 60（1 小时，不画 0 量程）。
     */
    fun scaleMaxMinutes(maxMinutes: Int): Int = (
        ((maxMinutes.coerceAtLeast(0) + 59) / 60).coerceAtLeast(1) * 60
        ).coerceAtMost(MAX_SCALE_MIN)

    /**
     * 平均线：**有记录日**柱值的算术平均，四舍五入到分钟。
     * 无记录日不计入（否则「没采集到」会被当成 0 拉低平均），全无记录返回 null（不画线）。
     */
    fun averageMinutes(points: List<DailyPoint>): Int? {
        val recorded = points.filter { it.recorded }
        if (recorded.isEmpty()) return null
        return (recorded.sumOf { it.minutes }.toDouble() / recorded.size).roundToInt()
    }

    /**
     * 需要写日期标签的下标：
     * - ≤7 天：每天都写（一屏 7 个放得下）；
     * - 8 天以上：恰 4 个（首、1/3、2/3、末），与对照图一致（如 `10/13 10/20 10/27 11/3`）。
     */
    fun labelIndices(count: Int): Set<Int> = when {
        count <= 0 -> emptySet()
        count <= 7 -> (0 until count).toSet()
        else -> setOf(0, (count - 1) / 3, ((count - 1) * 2) / 3, count - 1)
    }

    /** 是否横向滚动：30 天及以内必须一屏放下，31 天起才滚。 */
    fun scrollable(count: Int): Boolean = count > SCROLL_THRESHOLD

    /**
     * 柱宽（dp）：由「可用宽度 ÷ 天数」反推，保证 30 天也能一屏放下且不粘连。
     * 7 天以内是粗胶囊（上限 24dp），8 天以上是细柱（上限 7dp）。
     */
    fun barWidthDp(count: Int, availableDp: Float): Float {
        if (count <= 0 || availableDp <= 0f) return 0f
        val slot = availableDp / count
        return if (count <= 7) {
            (slot * 0.62f).coerceAtMost(24f).coerceAtLeast(4f)
        } else {
            (slot * 0.55f).coerceAtMost(7f).coerceAtLeast(1.5f)
        }
    }

    /** 第 [index] 根柱的中心横坐标（dp），供浮层定位。 */
    fun slotCenterDp(index: Int, count: Int, availableDp: Float): Float =
        if (count <= 0) 0f else availableDp / count * (index + 0.5f)
}
