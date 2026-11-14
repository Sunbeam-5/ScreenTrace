package com.screentrace.app.uikit

import com.screentrace.app.contract.UsageCalculator

/**
 * CR-07（新规格 §4.2）：**目标环双层**几何——`progress = 当日使用 / 每日目标`，
 * 允许超过 1、上限 2；第一圈 `min(p,1)` 满圈即止，超目标部分由**第二圈**
 * 从 12 点起按 `(p-1)×360°` 叠画更深主色。
 *
 * 几何抽成纯函数是为了直接 JVM 单测（变异验证过：去掉上限封顶后
 * 「超过两倍目标」用例变红）。
 */
object RingMath {

    /** progress 封顶：允许超 1，上限 2（§4.2）。 */
    fun clampProgress(progress: Float): Float = progress.coerceIn(0f, 2f)

    /** 第一圈扫过比例（0..1）：满圈即止。 */
    fun firstSweep(progress: Float): Float = clampProgress(progress).coerceAtMost(1f)

    /** 是否画第二圈：只有真正超目标才有内容可画。 */
    fun shouldShowSecond(progress: Float): Boolean = progress > 1f

    /** 第二圈扫过比例（0..1）：(progress-1)，progress 2 倍时满圈。 */
    fun secondSweep(progress: Float): Float =
        (clampProgress(progress) - 1f).coerceIn(0f, 1f)

    /**
     * §7.3 读屏：圆环**整体一句话**（RingProgress 用 clearAndSetSemantics 替换内部
     * 三行的逐条朗读，否则「今日亮屏 1:56 目标 6:00」会被念两遍）。
     * 时长文案与页面同源（[UsageCalculator.formatDuration]）；超目标单说超出多少，
     * 未设目标如实说「未设置目标」，不编造百分比。
     */
    fun description(caption: String, usageMinutes: Int, goalMinutes: Int): String {
        val usage = UsageCalculator.formatDuration(usageMinutes)
        return when {
            goalMinutes <= 0 -> "$caption $usage，未设置目标"
            usageMinutes > goalMinutes ->
                "$caption $usage，超目标 ${UsageCalculator.formatDuration(usageMinutes - goalMinutes)}"
            else ->
                "$caption $usage，目标 ${UsageCalculator.formatDuration(goalMinutes)}，完成 ${usageMinutes * 100 / goalMinutes}%"
        }
    }
}
