package com.screentrace.app.uikit

/**
 * CR-07 §4.8 / 需求 §5.7：热力月历四档按「当天使用 / 每日目标」比值切分。
 *
 * 与旧的 `UsageCalculator.monthLevel`（固定 2h/4h 三档）不同，这里必须读用户
 * **实际设置的每日目标**：目标改成 6 小时后，同样 3 小时的使用应从高档降到低档。
 * 放在 uikit 而不是 contract，是为了不动既有数据口径与 `UsageCalculatorTest`。
 *
 * 档位：≤100% → 1、≤140% → 2、≤175% → 3、>175% → 4；0 = 无记录或真实 0 分钟。
 */
object MonthLevelMath {

    fun levelOf(minutes: Int?, recorded: Boolean, goalMinutes: Int): Int {
        if (!recorded || minutes == null || minutes <= 0) return 0
        // 目标未设置（≤0）时不画 0 档，退化为最低有记录档，避免「有数据却全白」
        if (goalMinutes <= 0) return 1
        val ratio = minutes.toDouble() / goalMinutes
        return when {
            ratio <= 1.0 -> 1
            ratio <= 1.4 -> 2
            ratio <= 1.75 -> 3
            else -> 4
        }
    }
}
