package com.screentrace.app.contract

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * §5.11 / §7-27：周期总结的周期类型。
 *
 * 三个周期一律按**设备本地时区**切分：周 = 周一至周日、月 = 自然月、年 = 自然年。
 * `label` 是标题用的「本周 / 本月 / 本年」，`previousLabel` 是对比行里的「上周 / 上月 / 去年」。
 */
enum class PeriodType(val label: String, val previousLabel: String, val unit: String) {
    WEEK("本周", "上周", "周"),
    MONTH("本月", "上月", "月"),
    YEAR("本年", "去年", "年");

    /** 当前日期所在的本周期（自然周期，dataEnd 裁到今天）。 */
    fun natural(today: LocalDate): PeriodRange = when (this) {
        WEEK -> {
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            PeriodRange(this, monday, monday.plusDays(6), minOf(today, monday.plusDays(6)))
        }
        MONTH -> {
            val ym = YearMonth.from(today)
            PeriodRange(this, ym.atDay(1), ym.atEndOfMonth(), today)
        }
        YEAR -> PeriodRange(this, today.withDayOfYear(1), today.withDayOfYear(today.lengthOfYear()), today)
    }
}

/**
 * 一个周期的时间窗。
 *
 * - [start]/[end] 是**自然周期**的首尾（周日永远到周日，月永远到月末）；
 * - [dataEnd] 是**实际数据截止日** = min(今天, end)；`unfinished = dataEnd < end`。
 *
 * 为什么要把「自然周期」和「数据窗口」分开：当期未结束时标题必须写「截至 M月D日」
 * 并标「未结束」，而「共几天 / 有记录几天」只能按**已过去的天数**算——
 * 拿 30 天当分母去算 12 号的覆盖率，会把「还没到」误报成「缺口」。
 */
data class PeriodRange(
    val type: PeriodType,
    val start: LocalDate,
    val end: LocalDate,
    val dataEnd: LocalDate
) {
    /** 数据窗口的自然日数（当期未结束时只算到今天）。 */
    val dayCount: Int get() = (ChronoUnit.DAYS.between(start, dataEnd) + 1).toInt()

    /** 整个自然周期的天数（周 7 / 月 28–31 / 年 365–366）。 */
    val fullDayCount: Int get() = (ChronoUnit.DAYS.between(start, end) + 1).toInt()

    val unfinished: Boolean get() = dataEnd.isBefore(end)

    /** 数据窗口的 DateSelection（复用仓库既有查询，不再新写一套 SQL）。 */
    val selection: DateSelection get() = DateSelection.Range(start, dataEnd)

    /**
     * 上一周期的**等长**窗口：未结束时取上一自然周期的前 N 天（N = 已过去天数）。
     *
     * 这就是需求里「与此前等长周期比较」的落地：8 月只有 31 天、9 月只有 30 天，
     * 拿总时长直接比会天然偏差；按「已过去的同等天数」截断后，两边天数完全一致，
     * 日均与总量都可以比。整周期已结束（dayCount == fullDayCount）时就是上一整个自然周期。
     */
    fun previousEqualLength(): PeriodRange {
        val natural = previousNatural()
        val length = dayCount.toLong()
        if (length == natural.dayCount.toLong()) return natural
        // 取上一周期的**前 N 天**而不是最后 N 天：周三对比的应该是「上周一~上周三」，
        // 不是「上周五~上周日」——后者对上的是完全不同的作息。
        return PeriodRange(type, natural.start, natural.start.plusDays(length - 1), natural.start.plusDays(length - 1))
    }

    /** 上一整个自然周期（周 → 上周；月 → 上月；年 → 去年）。 */
    private fun previousNatural(): PeriodRange {
        val prevEnd = start.minusDays(1)
        val prevStart = when (type) {
            PeriodType.WEEK -> prevEnd.minusDays(6)
            PeriodType.MONTH -> YearMonth.from(prevEnd).atDay(1)
            PeriodType.YEAR -> prevEnd.withDayOfYear(1)
        }
        return PeriodRange(type, prevStart, prevEnd, prevEnd)
    }
}

/** 年视图的 12 根月柱：只有「有记录」的月份才有值，未到的月份不出现在列表里（不画假 0）。 */
data class MonthPoint(
    val month: Int,
    /** 该月**有记录日**的日均分钟数。 */
    val averageMinutes: Int,
    val recordedDays: Int,
    /** 该月是否已结束（未结束月份标题写「截至 M月D日」，柱下仍写月份）。 */
    val finished: Boolean
)

/**
 * 上一周期（用于对比行）。[summary] 的 recordedDays 为 0 时 UI 显示「暂无上一周期数据」。
 */
data class PeriodComparison(
    val range: PeriodRange,
    val summary: Summary
)

/**
 * §5.11 周期总结的完整数据（页面所有数字的唯一来源）。
 *
 * 硬约束（§7-27）：
 * - 未记录日**不进日均分母**、也不画成 0 柱（[trend] 里 `recorded = false`）；
 * - 覆盖率按**数据窗口**算：`summary.totalDays` = 已过去天数，`gapDays` = 无数据天数；
 * - 结论文案只来自 [PeriodSummaryCalculator.insights]，规则生成、只陈述事实。
 */
data class PeriodSummary(
    val type: PeriodType,
    val range: PeriodRange,
    val summary: Summary,
    val previous: PeriodComparison?,
    val compare: UsageCalculator.CompareInfo,
    /** 周 / 月视图的逐日柱（周 7 根、月 28–31 根）。 */
    val trend: List<DailyPoint>,
    /** 年视图的 12 根月柱（每月日均）；其他周期为空列表。 */
    val monthTrend: List<MonthPoint>,
    val extremes: ExtremesInfo,
    /** 周期 top5（按分钟降序）。 */
    val topApps: List<AppUsage>,
    /** 本周期应用时长合计，用作「占比」分母。 */
    val appTotalMinutes: Int,
    /** 06–12 / 12–18 / 18–22 / 22–06 四段分钟数。 */
    val periods: List<Int>,
    /** 夜间（22:00–06:00）占比，% 取整；无数据时为 0。 */
    val nightPercent: Int,
    val insights: List<String>,
    /**
     * 数据不足（有记录天数 < [PeriodSummaryCalculator.MIN_RECORDED_DAYS_FOR_INSIGHT]）：
     * 页面照常显示数字与覆盖说明，但**不出结论**（§7-27「数据不足时不出结论」）。
     */
    val insufficient: Boolean,
    val title: String,
    val subtitle: String
) {
    val gapDays: Int get() = (summary.totalDays - summary.recordedDays).coerceAtLeast(0)
}

/**
 * §5.11 周期总结的领域计算器：**纯函数**，不依赖 Compose / Room，可直接 JVM 单测
 * （§6「周期切分、上一周期对比、结论文案全部由纯函数生成」）。
 *
 * 输入刻意只用仓库已有的查询结果形状（[DailyPoint] / [AppUsage] / [HourBucket]），
 * 这样 ViewModel 不必为周期总结新增一套 DAO；换周期只是换一个 [PeriodRange]。
 */
object PeriodSummaryCalculator {

    /** 少于 2 天有记录就不出结论：一天的数据讲不出「本周」的任何规律。 */
    const val MIN_RECORDED_DAYS_FOR_INSIGHT = 2

    private val MONTH_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")
    private const val WEEKDAY_LABELS = "一二三四五六日"

    /** 周几（周一=1 … 周日=7）→「周三」。 */
    fun weekdayLabel(date: LocalDate): String = "周${WEEKDAY_LABELS[date.dayOfWeek.value - 1]}"

    /**
     * 生成一个周期的总结。
     *
     * @param trend 数据窗口内**逐日**的点（必须覆盖 `range.start..range.dataEnd` 每一天，
     *   包含 `recorded = false` 的日子；缺日会被当成无记录，而不是 0）
     * @param previousSummary 上一等长窗口的摘要（可为 null / 无记录）
     * @param apps 周期内应用聚合排行（仓库已按包名合并）
     * @param hours 周期内小时聚合
     */
    fun build(
        type: PeriodType,
        today: LocalDate,
        trend: List<DailyPoint>,
        previousSummary: Summary?,
        apps: List<AppUsage>,
        hours: List<HourBucket>
    ): PeriodSummary {
        val range = type.natural(today)
        val inWindow = trend.filter { it.date in range.start..range.dataEnd }
        val summary = UsageCalculator.summarizePoints(range.selection, inWindow)
        val previous = previousSummary?.let {
            PeriodComparison(range.previousEqualLength(), it)
        }
        val compare = UsageCalculator.compareAverage(summary, previousSummary, type.previousLabel)
        val longestShortest = UsageCalculator.longestShortest(inWindow)
        val appTotal = apps.sumOf { it.minutes }
        val periods = UsageCalculator.periodBuckets(hours)
        val totalHourMinutes = periods.sum()
        val nightPercent =
            if (totalHourMinutes <= 0) 0 else periods[3] * 100 / totalHourMinutes

        val monthTrend =
            if (type == PeriodType.YEAR) monthlyPoints(range, inWindow) else emptyList()

        val top5 = apps.sortedWith(
            compareByDescending<AppUsage> { it.minutes }.thenBy { it.name }
        ).take(5)

        val insufficient = summary.recordedDays < MIN_RECORDED_DAYS_FOR_INSIGHT
        val insights = if (insufficient) {
            emptyList()
        } else {
            insights(
                type = type,
                range = range,
                summary = summary,
                previous = previousSummary,
                extremes = longestShortest,
                periods = periods,
                nightPercent = nightPercent,
                topApps = top5,
                appTotalMinutes = appTotal
            )
        }

        return PeriodSummary(
            type = type,
            range = range,
            summary = summary,
            previous = previous,
            compare = compare,
            trend = if (type == PeriodType.YEAR) emptyList() else inWindow,
            monthTrend = monthTrend,
            extremes = longestShortest,
            topApps = top5,
            appTotalMinutes = appTotal,
            periods = periods,
            nightPercent = nightPercent,
            insights = insights,
            insufficient = insufficient,
            title = titleOf(type),
            subtitle = subtitleOf(range)
        )
    }

    /** 标题：本周 / 本月 / 本年（当期未结束由副标题的「未结束」承担，不污染标题）。 */
    fun titleOf(type: PeriodType): String = type.label

    /** 副标题：`11月9日–11月15日 · 未结束（截至 11月12日）`。 */
    fun subtitleOf(range: PeriodRange): String {
        val span = "${range.start.format(MONTH_DAY)}–${range.end.format(MONTH_DAY)}"
        return if (range.unfinished) {
            "$span · 未结束（截至 ${range.dataEnd.format(MONTH_DAY)}）"
        } else {
            span
        }
    }

    /**
     * 年视图：12 根月柱，每根 = 该月**有记录日**的日均。
     *
     * 为什么是日均而不是月总量：年视图要回答「哪几个月用得多久」，自然月长度不同，
     * 总量会把 31 天的月份天然抬高；日均与 §5.3 的「日均」口径一致，且未记录的月份
     * 整根不出现（不画成 0，也就不会假装「这个月没怎么用手机」）。
     */
    private fun monthlyPoints(range: PeriodRange, trend: List<DailyPoint>): List<MonthPoint> {
        val byMonth = trend.filter { it.recorded }.groupBy { it.date.monthValue }
        return (1..12).mapNotNull { month ->
            val days = byMonth[month] ?: return@mapNotNull null
            MonthPoint(
                month = month,
                averageMinutes = days.sumOf { it.minutes } / days.size,
                recordedDays = days.size,
                finished = YearMonth.of(range.start.year, month).atEndOfMonth() <= range.dataEnd
            )
        }
    }

    /**
     * §5.11 结论：3–6 条规则生成的事实陈述。
     *
     * 文案纪律（§7-27，写进单测）：只允许「事实 + 一句直接推导」，不出现
     * 因果归因（因为/所以）、不做价值评判与健康建议（应该/建议/注意）。
     */
    private fun insights(
        type: PeriodType,
        range: PeriodRange,
        summary: Summary,
        previous: Summary?,
        extremes: ExtremesInfo,
        periods: List<Int>,
        nightPercent: Int,
        topApps: List<AppUsage>,
        appTotalMinutes: Int
    ): List<String> = buildList {
        // ① 与上一等长周期对比（两边日均）
        if (previous != null && previous.hasAnyRecord) {
            val delta = summary.averageScreenUnlockMin - previous.averageScreenUnlockMin
            val direction = if (delta >= 0) "多" else "少"
            add(
                "${type.label}日均 ${UsageCalculator.formatDuration(summary.averageScreenUnlockMin)}，" +
                    "比${type.previousLabel}（${UsageCalculator.formatDuration(previous.averageScreenUnlockMin)}）" +
                    "$direction ${UsageCalculator.formatDuration(kotlin.math.abs(delta))}"
            )
        }
        // ② 最长的一天（周视图给星期几，月/年视图给日期）
        extremes.longestDate?.let { date ->
            val dayPart = if (type == PeriodType.WEEK) {
                "${weekdayLabel(date)}（${date.format(MONTH_DAY)}）"
            } else {
                date.format(MONTH_DAY)
            }
            add("$dayPart 使用最长，共 ${UsageCalculator.formatDuration(extremes.longestMinutes)}")
        }
        // ③ 时段结构：只在四段确实有数据时陈述
        if (periods.sum() > 0) {
            val maxIndex = periods.indices.maxBy { periods[it] }
            val labels = listOf("06–12", "12–18", "18–22", "22–06")
            add(
                if (maxIndex == 3) {
                    "夜间（22:00–06:00）占 ${nightPercent}%，是四段里最高的一段"
                } else {
                    "使用集中在 ${labels[maxIndex]}（占 " +
                        "${periods[maxIndex] * 100 / periods.sum()}%），夜间占 ${nightPercent}%"
                }
            )
        }
        // ④ 最常用的应用（占比分母 = 应用时长合计，避免与屏幕时长口径混淆）
        topApps.firstOrNull()?.let { top ->
            val percent = if (appTotalMinutes <= 0) 0 else top.minutes * 100 / appTotalMinutes
            add(
                "使用最多的是 ${top.name}，共 ${UsageCalculator.formatDuration(top.minutes)}，" +
                    "占应用时长 $percent%"
            )
        }
        // ⑤ 覆盖：有缺口才说（没缺口时日均就是真实日均，不需要解释）
        val gap = range.dayCount - summary.recordedDays
        if (gap > 0) {
            add(
                "时间窗内 ${summary.recordedDays} 天有记录、$gap 天无数据，" +
                    "日均只按有记录日计算"
            )
        }
    }.take(6)

    /** 纯文本导出（§5.11「复制总结」，与页内数字同源，不另算一套）。 */
    fun toPlainText(period: PeriodSummary, hideAppNames: Boolean = false): String = buildString {
        appendLine("${period.title} · ${period.subtitle}")
        appendLine("总时长 ${UsageCalculator.formatDuration(period.summary.totalScreenUnlockMin)}")
        appendLine("日均 ${UsageCalculator.formatDuration(period.summary.averageScreenUnlockMin)}")
        appendLine(period.compare.text)
        appendLine(
            "有记录 ${period.summary.recordedDays} 天 / 共 ${period.summary.totalDays} 天" +
                if (period.gapDays > 0) "（${period.gapDays} 天无数据）" else ""
        )
        if (period.periods.sum() > 0) {
            val labels = listOf("06–12", "12–18", "18–22", "22–06")
            appendLine("时段占比：" + labels.indices.joinToString("，") { index ->
                "${labels[index]} ${period.periods[index] * 100 / period.periods.sum()}%"
            })
        }
        if (period.topApps.isNotEmpty()) {
            append("应用 Top${period.topApps.size}：")
            appendLine(
                period.topApps.joinToString("，") { app ->
                    val percent =
                        if (period.appTotalMinutes <= 0) 0 else app.minutes * 100 / period.appTotalMinutes
                    val name = if (hideAppNames) "应用" else app.name
                    "$name ${UsageCalculator.formatDuration(app.minutes)}（$percent%）"
                }
            )
        }
        period.insights.forEach { line ->
            // 「隐藏应用名称」必须连结论里的应用名一起隐去：只遮榜单却让结论里
            // 原样写着「使用最多的是 微信」，等于没遮。
            val masked = if (hideAppNames) {
                period.topApps.fold(line) { acc, app -> acc.replace(app.name, "应用") }
            } else {
                line
            }
            appendLine("· $masked")
        }
    }.trim()
}
