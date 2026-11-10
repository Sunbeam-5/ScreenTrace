package com.screentrace.app.contract

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/**
 * §6：单日、区间、上一等长周期、记录覆盖数由统一领域计算器产生，
 * 避免四个页面各自实现出不同结果。纯 Kotlin / java.time，可直接跑 JVM 单测。
 */
object UsageCalculator {

    /** 生成所选区间内的全部自然日；上限由 DateSelection.MAX_DAYS 约束。 */
    fun daysOf(selection: DateSelection): List<LocalDate> = buildList {
        var cursor = selection.startDate
        val end = selection.endDate
        while (!cursor.isAfter(end)) {
            add(cursor)
            cursor = cursor.plusDays(1)
        }
    }

    /** §5.6：对比此前「等长」区间。 */
    fun previousPeriod(selection: DateSelection): DateSelection {
        val length = selection.days.toLong()
        val end = selection.startDate.minusDays(1)
        return DateSelection.Range(end.minusDays(length - 1), end)
    }

    /**
     * SUM 含全部有记录行；AVG 的分母只含有记录日。
     * 「有记录」= 数据库存在该日 day_summary，真实 0 分钟仍算有记录。
     */
    fun summarize(selection: DateSelection, records: List<DaySummary>): Summary {
        val inRange = records.filter { !it.date.isBefore(selection.startDate) && !it.date.isAfter(selection.endDate) }
        val recordedDays = inRange.size
        val total = inRange.sumOf { it.screenUnlockMin }
        val unlocks = inRange.sumOf { it.unlocks }
        return Summary(
            selection = selection,
            totalDays = selection.days,
            recordedDays = recordedDays,
            hasAnyRecord = recordedDays > 0,
            totalScreenUnlockMin = total,
            averageScreenUnlockMin = if (recordedDays == 0) 0 else total / recordedDays,
            averageUnlocks = if (recordedDays == 0) 0 else unlocks / recordedDays,
            longestSessionMin = inRange.maxOfOrNull { it.longestSessionMin } ?: 0
        )
    }

    /** 每个自然日都有数据点：有记录按真实值，无记录高度为 0 且标「无记录」。 */
    fun dailyPoints(selection: DateSelection, records: List<DaySummary>): List<DailyPoint> {
        val byDate = records.associateBy { it.date }
        return daysOf(selection).map { date ->
            val record = byDate[date]
            DailyPoint(date = date, minutes = record?.screenUnlockMin ?: 0, recorded = record != null)
        }
    }

    fun hourlyBuckets(hours: List<HourUsage>): List<HourBucket> =
        (0..23).map { hour -> HourBucket(hour, hours.filter { it.hour == hour }.sumOf { it.minutes }) }

    /** §5.7 时段：06–12 / 12–18 / 18–22 / 22–06（22:00 含、次日 06:00 不含）。 */
    fun periodBuckets(hours: List<HourUsage>): List<Int> = listOf(
        hours.filter { it.hour in 6..11 }.sumOf { it.minutes },
        hours.filter { it.hour in 12..17 }.sumOf { it.minutes },
        hours.filter { it.hour in 18..21 }.sumOf { it.minutes },
        hours.filter { it.hour >= 22 || it.hour < 6 }.sumOf { it.minutes }
    )

    fun nightMinutes(hours: List<HourUsage>): Int =
        hours.filter { it.hour >= 22 || it.hour < 6 }.sumOf { it.minutes }

    /** §5.7 月视图三档：(0,2h) / [2h,4h) / ≥4h；0 分钟与无记录均不着色。 */
    fun monthLevel(minutes: Int, recorded: Boolean): Int = when {
        !recorded || minutes <= 0 -> 0
        minutes < 120 -> 1
        minutes < 240 -> 2
        else -> 3
    }

    /** §5.7 年视图五档：空档 / (0,2h) / [2h,4h) / [4h,6h) / ≥6h。 */
    fun yearLevel(minutes: Int, recorded: Boolean): Int = when {
        !recorded || minutes <= 0 -> 0
        minutes < 120 -> 1
        minutes < 240 -> 2
        minutes < 360 -> 3
        else -> 4
    }

    /** §5.3 圆环中心固定 H:MM。 */
    fun formatClock(totalMinutes: Int): String {
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return "$h:${m.toString().padStart(2, '0')}"
    }

    /** §5.3 正文时长：整小时省略 0 分钟，零显示「0分钟」。 */
    fun formatDuration(totalMinutes: Int): String = when {
        totalMinutes <= 0 -> "0分钟"
        totalMinutes < 60 -> "${totalMinutes}分钟"
        totalMinutes % 60 == 0 -> "${totalMinutes / 60}小时"
        else -> "${totalMinutes / 60}小时${totalMinutes % 60}分钟"
    }

    /**
     * §5.6：streak 以 `screen_unlock_min > 0` 的自然日连续段计算截至锚点的全量历史。
     * currentDays = 以锚点日为末端的连续 >0 天数，锚点日非 >0 时为 0；
     * longestDays/longestStart = 截至锚点全量历史中最长连续段，并列取更早的一段。
     */
    fun streak(records: List<DaySummary>, anchor: LocalDate): StreakInfo {
        val byDate = records.associateBy { it.date }
        var currentDays = 0
        var cursor = anchor
        while ((byDate[cursor]?.screenUnlockMin ?: 0) > 0) {
            currentDays++
            cursor = cursor.minusDays(1)
        }
        var longestDays = 0
        var longestStart: LocalDate? = null
        var runDays = 0
        var runStart: LocalDate? = null
        var prev: LocalDate? = null
        for (date in byDate.values.asSequence()
            .filter { !it.date.isAfter(anchor) && it.screenUnlockMin > 0 }
            .map { it.date }
            .toSortedSet()) {
            if (prev != null && date == prev.plusDays(1)) {
                runDays++
            } else {
                runDays = 1
                runStart = date
            }
            if (runDays > longestDays) {
                longestDays = runDays
                longestStart = runStart
            }
            prev = date
        }
        return StreakInfo(currentDays = currentDays, longestDays = longestDays, longestStart = longestStart)
    }

    /**
     * §5.6：「最久的一天 / 最少的一天 / 低于 2 小时」只统计筛选区间内的有记录日；
     * 真实 0 分钟计入 under2hDays 并参与最短竞争，无记录日不计。并列取更早日期。
     */
    fun extremes(selection: DateSelection, records: List<DaySummary>): ExtremesInfo {
        val inRange = records.associateBy { it.date }.values
            .filter { !it.date.isBefore(selection.startDate) && !it.date.isAfter(selection.endDate) }
            .sortedBy { it.date }
        if (inRange.isEmpty()) {
            return ExtremesInfo(null, 0, null, 0, 0)
        }
        // sortedBy 升序 + maxBy/minBy 取首个极值 → 并列时得更早日期
        val longest = inRange.maxBy { it.screenUnlockMin }
        val shortest = inRange.minBy { it.screenUnlockMin }
        return ExtremesInfo(
            longestDate = longest.date,
            longestMinutes = longest.screenUnlockMin,
            shortestDate = shortest.date,
            shortestMinutes = shortest.screenUnlockMin,
            under2hDays = inRange.count { it.screenUnlockMin < 120 }
        )
    }

    /** 月视图任意月份：整月每个自然日一个色档点（§5.7 三档，真实 0 与无记录同为 0 档但 recorded 区分）。 */
    fun monthLevels(yearMonth: YearMonth, records: List<DaySummary>): List<CalendarLevel> {
        val byDate = records.associateBy { it.date }
        return (1..yearMonth.lengthOfMonth()).map { day ->
            val date = yearMonth.atDay(day)
            val record = byDate[date]
            CalendarLevel(
                date = date,
                level = monthLevel(record?.screenUnlockMin ?: 0, record != null),
                recorded = record != null
            )
        }
    }

    /**
     * 年视图：截至锚点的过去 weeks 个整周——锚点所在周与其前 weeks-1 周，
     * 周一起始、每周 7 天（§5.6「截至该日的过去 20 周」按周语义对齐，
     * 保证 20 列 × 7 行热力网格与星期列严格对位；锚点之后的格子为「未来」空档）。
     */
    fun yearLevels(anchor: LocalDate, weeks: Int, records: List<DaySummary>): List<CalendarLevel> {
        val byDate = records.associateBy { it.date }
        val anchorMonday = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val start = anchorMonday.minusDays((weeks.toLong() - 1) * 7)
        var cursor = start
        return buildList {
            repeat(weeks * 7) {
                val record = byDate[cursor]
                add(
                    CalendarLevel(
                        date = cursor,
                        level = yearLevel(record?.screenUnlockMin ?: 0, record != null),
                        recorded = record != null
                    )
                )
                cursor = cursor.plusDays(1)
            }
        }
    }
}
