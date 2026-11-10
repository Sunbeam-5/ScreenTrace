package com.screentrace.app.contract

import java.time.LocalDate

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
}
