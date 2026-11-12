package com.screentrace.app.uikit

/**
 * CR-06（负责人勘误版）：**24 小时全天环**几何——整圈 = 24 小时，
 * 弧长 = 当日使用 ÷ 24h（如 15 小时占 15/24），从 12 点顺时针画起。
 *
 * 目标不画进环：只用于中心下行动文字与「超出目标」chip 的判定（[shouldShowOverChip]）。
 * 几何抽成纯函数是为了直接 JVM 单测。
 */
object RingMath {

    const val MINUTES_PER_DAY = 24 * 60

    /** 全天环弧长比例（0..1）：15h → 15/24 = 0.625；超过 24h 封顶满圈。 */
    fun daySweep(usageMinutes: Int): Float =
        (usageMinutes.toFloat() / MINUTES_PER_DAY).coerceIn(0f, 1f)

    /** 「超出目标」chip 是否出现：只有真正超出目标才有内容可说。 */
    fun shouldShowOverChip(usageMinutes: Int, goalMinutes: Int): Boolean =
        goalMinutes > 0 && usageMinutes > goalMinutes

    /** 超出目标的分钟数（chip 文案用）。 */
    fun overMinutes(usageMinutes: Int, goalMinutes: Int): Int =
        (usageMinutes - goalMinutes).coerceAtLeast(0)
}
