package com.screentrace.app.data

import com.screentrace.app.data.parse.AppUsageRow
import com.screentrace.app.data.parse.DayMetrics
import java.time.LocalDate
import kotlin.math.roundToLong

/**
 * 入库前的**自洽性校验**：把物理上不可能的应用时长挡在库外。
 *
 * 起因是真机首装的一条数据：`com.xiaomi.smarthome` 当天 **1386 分钟**，而同日
 * `day_summary.screenMin` 只有 **510 分钟**——单个应用比全天屏幕时间还长 2.7 倍。
 *
 * 根因不在聚合算法，而在**上游事件过稀**：`ForegroundBucketer` 的设计是「以
 * 下一个应用进入前台作为收尾」，这样进程被杀丢掉 PAUSED 时不会虚高；代价是
 * 只有一条 `ACTIVITY_RESUMED` 时，那条区间会一路延伸到 `endMs`（现在），
 * 于是「23 小时的前台」就这么产生了。逐小时分布也跟着变成「0–7 点各 60 分钟」
 * 这种一眼假的形状。
 *
 * **为什么是丢弃而不是裁剪**：把 1386 分钟裁到 510 分钟，会造出一个看起来合理、
 * 实际仍然是编的分布（谁知道那 510 分钟该怎么摊到 24 个桶里）。宁可这一天的
 * 应用/时段维度不出数，再在 `pull_log.note` 里写明丢的是哪天、差多少——
 * 「没有数据」是诚实的，「编一个看起来像的数据」不是（§5.5 / §7-16 红线）。
 *
 * 校验本身是纯函数，便于用真实脏数据做回归。
 */
internal object IngestSanity {

    /**
     * 允许的超出幅度（相对当天屏幕时长）。
     *
     * **不是 2% 的原因（真机实测打的补丁）**：手机上「应用前台时长」并不严格小于
     * 「屏幕亮着时长」——`totalTimeUsed` 还包含前台服务、画中画、灭屏但可见的
     * Activity 等。小米 Android 16 的真实数据里，米家 573 分钟 / 屏幕 538 分钟
     * （+6.5%）、当天应用合计 584 / 538（+8.5%）都是**正常值**；按 2% 判会把这天
     * 的应用与时段数据整日丢光，界面表现为「屏幕时长有数、应用排行空白」。
     *
     * 取 50%：既容得下这类口径差（真机最大约 +9%），又仍能挡住「1386 vs 510」
     * 那种 2.7 倍的量级错误（上限 = 510 × 1.5 = 765 < 1386）。
     */
    private const val SLACK_RATIO = 0.5

    /** 一天的分钟数：参照物缺失或量级太小时用它兜底做上限。 */
    private const val MINUTES_PER_DAY = 24 * 60

    /**
     * 做相对比较所需的最小参照量级（分钟）。
     *
     * 低于这个值时只按「一天 24 小时」的物理上限判——样本
     * `reference/sample-checkin.txt` 里有一天屏幕时长 1 分钟、应用合计 319 分钟，
     * 那是归属口径差异而非脏数据，用它当分母会误杀整页排行。
     */
    private const val MIN_REFERENCE_MIN = 60

    data class Verdict(
        val appUsage: List<AppUsageRow>,
        val hourMinutes: Map<LocalDate, List<Int>>,
        /** 被判为不可信而丢弃的日期（升序），用于写 `pull_log.note`。 */
        val droppedDates: List<LocalDate>
    ) {
        val hasDrops: Boolean get() = droppedDates.isNotEmpty()
    }

    fun check(
        days: Map<LocalDate, DayMetrics>,
        appUsage: List<AppUsageRow>,
        hourMinutes: Map<LocalDate, List<Int>>
    ): Verdict {
        if (appUsage.isEmpty() && hourMinutes.isEmpty()) {
            return Verdict(appUsage, hourMinutes, emptyList())
        }
        val minutesByDate = appUsage.groupBy { it.date }.mapValues { (_, rows) ->
            rows.sumOf { it.totalMs }.toDouble() / 60_000.0
        }
        val bad = minutesByDate.keys.filter { date ->
            val used = minutesByDate.getValue(date)
            used.roundToLong() > limitOf(date, days, hourMinutes)
        }
        if (bad.isEmpty()) return Verdict(appUsage, hourMinutes, emptyList())
        val drop = bad.toSet()
        return Verdict(
            appUsage = appUsage.filterNot { it.date in drop },
            // 小时分布与应用时长同源（同一批前台区间），应用合计不可信时它必然也是假的
            hourMinutes = hourMinutes.filterKeys { it !in drop },
            droppedDates = bad.sorted()
        )
    }

    /**
     * 当天的可信上限（分钟）。
     *
     * **参照物必须取「屏幕时长」与「时段合计」的较大者**，不能只看 `day_summary`：
     * 屏幕时长按**区间归属日**（endTime 那天）落账，应用时长按**跨零点切分**落账，
     * 两者本来就不同源。参照 `reference/sample-checkin.txt`（真机 vivo dump）实测：
     * 某天 `screenMin=1` 而应用合计 319 分钟——那不是脏数据，是归属口径差异。
     * 用它做上限会把整个排行页清空。
     *
     * 而米家那条（应用 1392 分钟 / 屏幕 510 分钟）在两种参照下都超标，照样会被拦下。
     */
    private fun limitOf(
        date: LocalDate,
        days: Map<LocalDate, DayMetrics>,
        hourMinutes: Map<LocalDate, List<Int>>
    ): Double {
        val screenMin = days[date]?.screenMin ?: 0
        val hourSum = hourMinutes[date]?.sum() ?: 0
        val reference = maxOf(screenMin, hourSum)
        // 小分母不做相对比较：样本里出现过「屏幕 1 分钟 / 应用 319 分钟」的一天，
        // 那是区间归属日与跨零点切分的口径差，不是脏数据。量级够大才比。
        if (reference < MIN_REFERENCE_MIN) return MINUTES_PER_DAY.toDouble()
        return reference * (1 + SLACK_RATIO) + 1
    }
}
