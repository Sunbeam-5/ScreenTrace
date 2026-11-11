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
     * 两层口径差叠加，决定了这个值：
     *
     * 1. `totalTimeUsed` 含前台服务、画中画、灭屏但可见的 Activity，正常会比「亮屏时长」
     *    多一截（真机实测米家 +6.5%、当天合计 +8.5%）；
     * 2. **手机分身 / 隐私空间与主空间可以并行使用**（真机 user=0 与 user=999 的事件
     *    时间重叠）。应用时长按账户分别统计后跨账户求和，合计自然会到屏幕时长的
     *    两倍——这是真实情况，不是脏数据。
     *
     * 所以上限放到 3 倍（`reference × 3`）：容得下「主空间 + 分身」这种最常见的两路并行，
     * 又仍能挡住真正的荒谬值（比如 In-memory 窗口重置后某个应用凭空多出十几小时）。
     * 单应用超过 24 小时另有硬判据，见 [check]。
     */
    private const val SLACK_RATIO = 2.0

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
        val bad = mutableSetOf<LocalDate>()
        // ① 单个应用超过一天：物理上不可能，哪怕主空间与分身同时用同一个 App。
        //    这条最硬，且不受屏幕时长参照物的影响——是 In-memory 窗口失准的典型症状。
        appUsage.filter { it.totalMs > MINUTES_PER_DAY * 60_000L }.forEach { bad.add(it.date) }
        // ② 当天应用合计超参照上限（见 [SLACK_RATIO]）
        minutesByDate.forEach { (date, used) ->
            if (used.roundToLong() > limitOf(date, days, hourMinutes)) bad.add(date)
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
