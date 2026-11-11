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

    /** 聚合桶口径重载（Repository 的 hourlyDistribution 输出，HourBucket 已按小时合并）。 */
    @JvmName("periodBucketsOfAggregated")
    fun periodBuckets(buckets: List<HourBucket>): List<Int> = listOf(
        buckets.filter { it.hour in 6..11 }.sumOf { it.minutes },
        buckets.filter { it.hour in 12..17 }.sumOf { it.minutes },
        buckets.filter { it.hour in 18..21 }.sumOf { it.minutes },
        buckets.filter { it.hour >= 22 || it.hour < 6 }.sumOf { it.minutes }
    )

    /** 聚合桶口径重载。 */
    @JvmName("nightMinutesOfAggregated")
    fun nightMinutes(buckets: List<HourBucket>): Int =
        buckets.filter { it.hour >= 22 || it.hour < 6 }.sumOf { it.minutes }

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
     * §5.6：「最久的一天 / 最少的一天 / 低于阈值的一天」只统计筛选区间内的有记录日；
     * 真实 0 分钟计入 [underThresholdDays] 并参与最短竞争，无记录日不计。并列取更早日期。
     *
     * [lowThresholdMin] 由设置页配置（默认 [DEFAULT_LOW_THRESHOLD_MIN] = 2 小时）。
     */
    fun extremes(
        selection: DateSelection,
        records: List<DaySummary>,
        lowThresholdMin: Int = DEFAULT_LOW_THRESHOLD_MIN
    ): ExtremesInfo {
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
            underThresholdDays = inRange.count { it.screenUnlockMin < lowThresholdMin }
        )
    }

    /**
     * §5.3 对比文案口径（统一在计算器，禁止各页自算）：
     * 单日=「较昨日增加/减少 X 分钟」；区间=「较上一区间增加/减少 X%」；
     * 上一周期无记录 →「暂无上一周期数据」（increase=null，UI 不画箭头，不得显示 0%）。
     * 持平 →「较昨日/较上一区间持平」（increase=null）。
     */
    data class CompareInfo(val text: String, val increase: Boolean?)

    fun compare(current: Summary, previous: Summary?, single: Boolean): CompareInfo {
        if (previous == null || !previous.hasAnyRecord) return CompareInfo("暂无上一周期数据", null)
        return if (single) {
            val delta = current.totalScreenUnlockMin - previous.totalScreenUnlockMin
            when {
                delta > 0 -> CompareInfo("较昨日增加 ${formatDuration(delta)}", true)
                delta < 0 -> CompareInfo("较昨日减少 ${formatDuration(-delta)}", false)
                else -> CompareInfo("较昨日持平", null)
            }
        } else {
            val prevAvg = previous.averageScreenUnlockMin
            val curAvg = current.averageScreenUnlockMin
            when {
                prevAvg <= 0 -> {
                    // 上一区间日均为 0，百分比无意义——退化为分钟差文案
                    val delta = curAvg - prevAvg
                    when {
                        delta > 0 -> CompareInfo("较上一区间增加 ${formatDuration(delta)}", true)
                        delta < 0 -> CompareInfo("较上一区间减少 ${formatDuration(-delta)}", false)
                        else -> CompareInfo("较上一区间持平", null)
                    }
                }
                else -> {
                    val percent = (curAvg - prevAvg) * 100 / prevAvg
                    when {
                        percent > 0 -> CompareInfo("较上一区间增加 $percent%", true)
                        percent < 0 -> CompareInfo("较上一区间减少 ${-percent}%", false)
                        else -> CompareInfo("较上一区间持平", null)
                    }
                }
            }
        }
    }

    /**
     * CR-01（C04）：解锁/通知详情统一聚合。两详情页与图表/数据表/卡片数字同源于此。
     * - 计数层事件保留毫秒（§2.2 坑 2）；按设备本地时区归属自然日/小时（§3）；
     * - 单日 → 24 个逐小时点；区间 → 逐日点（主指标：UNLOCK=解锁数、
     *   NOTIFICATION=打扰数，与今日卡一致）；
     * - 覆盖三态（§4）：RECORDED 含真实 0 次，NO_RECORD=窗口外，GAP=拉取缺口；
     *   小时点的覆盖状态随所属日。
     */
    fun metricDetail(
        metric: MetricKind,
        selection: DateSelection,
        unlockEvents: List<UnlockEvent> = emptyList(),
        notificationEvents: List<NotificationEvent> = emptyList(),
        windowStart: LocalDate? = null,
        gapDays: Set<LocalDate> = emptySet(),
        appLabels: Map<String, String> = emptyMap(),
        /** 区间内每个有亮屏的日子都是「单条连续会话且 0 解锁」（设备从未锁屏）时为 true。 */
        neverSleepsWindow: Boolean = false
    ): MetricDetail = when (metric) {
        MetricKind.UNLOCK -> buildUnlockDetail(
            selection, unlockEvents, windowStart, gapDays, neverSleepsWindow
        )
        MetricKind.NOTIFICATION -> buildNotificationDetail(
            selection, notificationEvents, windowStart, gapDays, appLabels
        )
    }

    /** 覆盖三态判定（§4）：窗口外无记录 / 拉取缺口 / 有记录（含真实 0）。 */
    fun coverageOf(date: LocalDate, windowStart: LocalDate?, gapDays: Set<LocalDate>): Coverage =
        when {
            windowStart == null -> Coverage.NO_RECORD
            date.isBefore(windowStart) -> Coverage.NO_RECORD
            date in gapDays -> Coverage.GAP
            else -> Coverage.RECORDED
        }

    private fun localZone(): java.time.ZoneId = java.time.ZoneId.systemDefault()

    private fun localDateOf(ms: Long): LocalDate =
        java.time.Instant.ofEpochMilli(ms).atZone(localZone()).toLocalDate()

    private fun localHourOf(ms: Long): Int =
        java.time.Instant.ofEpochMilli(ms).atZone(localZone()).hour

    private fun inSelection(ms: Long, selection: DateSelection): Boolean {
        val date = localDateOf(ms)
        return !date.isBefore(selection.startDate) && !date.isAfter(selection.endDate)
    }

    private fun buildUnlockDetail(
        selection: DateSelection,
        events: List<UnlockEvent>,
        windowStart: LocalDate?,
        gapDays: Set<LocalDate>,
        /** 区间内全是「不灭屏日」（见 RoomUsageRepository 注释），0 解锁是真实值。 */
        neverSleepsWindow: Boolean = false
    ): MetricDetail {
        val inRange = events.filter { inSelection(it.occurredAtMs, selection) }
        val hourlyOf: (LocalDate) -> IntArray = { date ->
            IntArray(24).also { buckets ->
                inRange.forEach {
                    if (localDateOf(it.occurredAtMs) == date) buckets[localHourOf(it.occurredAtMs)]++
                }
            }
        }
        val base = aggregatePoints(selection, hourlyOf, windowStart, gapDays)
        val single = selection is DateSelection.Day
        val peakHour: Int? = if (single) {
            hourlyOf(selection.startDate).withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }?.index
        } else {
            val totals = IntArray(24)
            daysOf(selection).forEach { day ->
                hourlyOf(day).forEachIndexed { h, v -> totals[h] += v }
            }
            totals.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }?.index
        }
        val busiestDate = base.points
            .filter { it.coverage == Coverage.RECORDED }
            .maxByOrNull { it.value }
            ?.takeIf { it.value > 0 }
            ?.date
        val totalHours = if (single) 24 else selection.days * 24
        return MetricDetail(
            metric = MetricKind.UNLOCK,
            selection = selection,
            // 评审 P1-1：windowStart 是「本指标事件源是否存在」的第一判据；
            // 批 6g 补第二判据 neverSleepsWindow：设备开了不灭屏时整日只有一条
            // 连续亮屏会话、本来就不会有 KEYGUARD 事件，此时 0 解锁是真实值，
            // 不能再画「—」（真机：不灭屏日 longestSessionMin == screenUnlockMin）。
            hasEventSource = windowStart != null || neverSleepsWindow,
            total = base.total,
            dailyAverage = base.dailyAverage,
            notification = null,
            points = base.points,
            appRanking = emptyList(), // §5.7：解锁无可靠应用归因，禁止伪造
            peakHour = peakHour,
            hourlyAverage = if (base.total > 0) base.total.toDouble() / totalHours else null,
            busiestDate = busiestDate
        )
    }

    private fun buildNotificationDetail(
        selection: DateSelection,
        events: List<NotificationEvent>,
        windowStart: LocalDate?,
        gapDays: Set<LocalDate>,
        appLabels: Map<String, String>
    ): MetricDetail {
        val inRange = events.filter { inSelection(it.occurredAtMs, selection) }
        val interruptions = inRange.filter { it.type == NotificationType.INTERRUPTION }
        val seen = inRange.filter { it.type == NotificationType.SEEN }
        val hourlyOf: (LocalDate) -> IntArray = { date ->
            IntArray(24).also { buckets ->
                interruptions.forEach {
                    if (localDateOf(it.occurredAtMs) == date) buckets[localHourOf(it.occurredAtMs)]++
                }
            }
        }
        val base = aggregatePoints(selection, hourlyOf, windowStart, gapDays)
        val ranking = inRange
            .groupBy { it.pkg }
            .map { (pkg, rows) ->
                MetricAppCount(
                    pkg = pkg,
                    name = appLabels[pkg] ?: pkg,
                    interruptions = rows.count { it.type == NotificationType.INTERRUPTION },
                    seen = rows.count { it.type == NotificationType.SEEN }
                )
            }
            .sortedWith(compareByDescending<MetricAppCount> { it.interruptions }.thenBy { it.pkg })
        return MetricDetail(
            metric = MetricKind.NOTIFICATION,
            selection = selection,
            hasEventSource = windowStart != null,
            total = base.total,
            dailyAverage = base.dailyAverage,
            notification = NotificationCounts(interruptions.size, seen.size),
            points = base.points,
            appRanking = ranking,
            peakHour = null,
            hourlyAverage = null,
            busiestDate = null
        )
    }

    /** 单日 24 逐小时点 / 区间逐日点 + 摘要（total / 有记录日日均）。 */
    private fun aggregatePoints(
        selection: DateSelection,
        hourlyOf: (LocalDate) -> IntArray,
        windowStart: LocalDate?,
        gapDays: Set<LocalDate>
    ): Pair3 {
        val single = selection is DateSelection.Day
        val points: List<MetricPoint> = if (single) {
            val date = selection.startDate
            val coverage = coverageOf(date, windowStart, gapDays)
            val hours = hourlyOf(date)
            (0..23).map { hour ->
                MetricPoint(date, hour, hours[hour], coverage)
            }
        } else {
            daysOf(selection).map { date ->
                MetricPoint(date, null, hourlyOf(date).sum(), coverageOf(date, windowStart, gapDays))
            }
        }
        val total = points.sumOf { it.value }
        val recordedCount = points.count { it.coverage == Coverage.RECORDED }
        val dailyAverage = if (single || recordedCount == 0) {
            null
        } else {
            // §5.6：AVG 分母只含记录日（RECORDED 含真实 0），窗口外/缺口不拉低平均
            points.filter { it.coverage == Coverage.RECORDED }.sumOf { it.value } / recordedCount
        }
        return Pair3(total, dailyAverage, points)
    }

    /** 内部聚合载体（total / dailyAverage / points）。 */
    private data class Pair3(val total: Int, val dailyAverage: Int?, val points: List<MetricPoint>)

    /** 月视图任意月份：整月每个自然日一个色档点（§5.7 三档，真实 0 与无记录同为 0 档但 recorded 区分）。 */
    fun monthLevels(yearMonth: YearMonth, records: List<DaySummary>): List<CalendarLevel> {
        val byDate = records.associateBy { it.date }
        return (1..yearMonth.lengthOfMonth()).map { day ->
            val date = yearMonth.atDay(day)
            val record = byDate[date]
            CalendarLevel(
                date = date,
                level = monthLevel(record?.screenUnlockMin ?: 0, record != null),
                recorded = record != null,
                minutes = record?.screenUnlockMin
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
                        recorded = record != null,
                        minutes = record?.screenUnlockMin
                    )
                )
                cursor = cursor.plusDays(1)
            }
        }
    }
}
