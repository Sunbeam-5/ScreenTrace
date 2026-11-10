package com.screentrace.app.data

import com.screentrace.app.contract.AppUsage
import com.screentrace.app.contract.CalendarLevel
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.DailyPoint
import com.screentrace.app.contract.DaySummary
import com.screentrace.app.contract.ExtremesInfo
import com.screentrace.app.contract.HourBucket
import com.screentrace.app.contract.MetricDetail
import com.screentrace.app.contract.MetricKind
import com.screentrace.app.contract.NotificationEvent
import com.screentrace.app.contract.PullResult
import com.screentrace.app.contract.StreakInfo
import com.screentrace.app.contract.Summary
import com.screentrace.app.contract.UnlockEvent
import com.screentrace.app.contract.UsageCalculator
import com.screentrace.app.contract.UsageRepository
import com.screentrace.app.data.db.DaySummaryEntity
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.parse.AppNames
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Room 实现（批 6a）：查询 + 聚合全部经 [UsageCalculator]（§6：聚合口径唯一出处）。
 * 展示名按 §3-8：内置映射表回退包名（PackageManager 优先版由调用方/批 6b 回填）。
 * pullLatest 的 Shizuku 拉取通道在批 6b 接入。
 */
class RoomUsageRepository(private val db: ScreenTraceDatabase) : UsageRepository {

    private suspend fun recordsBetween(start: LocalDate, end: LocalDate): List<DaySummary> =
        db.daySummaryDao().getBetween(start.toString(), end.toString()).map { it.toDomain() }

    override suspend fun summary(selection: DateSelection): Summary =
        UsageCalculator.summarize(selection, recordsBetween(selection.startDate, selection.endDate))

    override suspend fun dailyTrend(selection: DateSelection): List<DailyPoint> =
        UsageCalculator.dailyPoints(selection, recordsBetween(selection.startDate, selection.endDate))

    override suspend fun appRanking(selection: DateSelection, limit: Int): List<AppUsage> =
        db.appUsageDao()
            .sumBetween(selection.startDate.toString(), selection.endDate.toString())
            .take(limit)
            .map {
                AppUsage(
                    date = selection.endDate, // 区间聚合行（§5.7 排行显示区间累计）
                    pkg = it.pkg,
                    name = AppNames.nameOf(it.pkg),
                    minutes = it.minutes,
                    launches = it.launches,
                    category = runCatching {
                        com.screentrace.app.contract.AppCategory.valueOf(it.category)
                    }.getOrDefault(com.screentrace.app.contract.AppCategory.OTHER)
                )
            }

    override suspend fun hourlyDistribution(selection: DateSelection): List<HourBucket> =
        db.hourUsageDao()
            .sumBetween(selection.startDate.toString(), selection.endDate.toString())
            .map { HourBucket(it.hour, it.minutes) }

    override suspend fun monthLevels(yearMonth: java.time.YearMonth): List<CalendarLevel> {
        val start = yearMonth.atDay(1)
        val end = yearMonth.atEndOfMonth()
        return UsageCalculator.monthLevels(yearMonth, recordsBetween(start, end))
    }

    override suspend fun yearLevels(anchor: LocalDate, weeks: Int): List<CalendarLevel> {
        val anchorMonday = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val start = anchorMonday.minusDays((weeks.toLong() - 1) * 7)
        val end = anchorMonday.plusDays(6)
        return UsageCalculator.yearLevels(anchor, weeks, recordsBetween(start, end))
    }

    /** §5.6：streak 是截至锚点的**全量历史**，查全部 day_summary。 */
    override suspend fun streak(anchor: LocalDate): StreakInfo =
        UsageCalculator.streak(db.daySummaryDao().getAll().map { it.toDomain() }, anchor)

    override suspend fun extremes(selection: DateSelection): ExtremesInfo =
        UsageCalculator.extremes(selection, recordsBetween(selection.startDate, selection.endDate))

    /**
     * CR-01（C04）：解锁/通知详情统一查询——图表/表格/卡片同源于此（§7-16）。
     * 事件覆盖窗口起点 = 事件表最早事件日期（§3-7 累积窗口）；
     * gapDays 暂空（批 6b 起从 pull_log 拉取失败记录推导，批 9 细化）。
     */
    override suspend fun metricDetail(metric: MetricKind, selection: DateSelection): MetricDetail {
        val zone = ZoneId.systemDefault()
        val startMs = selection.startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = selection.endDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val unlockMs = db.unlockEventDao().msBetween(startMs, endMs)
        val notifs = db.notificationEventDao().between(startMs, endMs)
        val windowStartMs = minOfNotNull(db.unlockEventDao().minMs(), db.notificationEventDao().minMs())
        val windowStart = windowStartMs?.let {
            java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate()
        }
        val appLabels = db.appUsageDao().allNames().associate { it.pkg to AppNames.nameOf(it.pkg) }
        return UsageCalculator.metricDetail(
            metric = metric,
            selection = selection,
            unlockEvents = unlockMs.map { UnlockEvent(it) },
            notificationEvents = notifs.map {
                NotificationEvent(
                    occurredAtMs = it.occurredAtMs,
                    type = com.screentrace.app.contract.NotificationType.valueOf(it.type),
                    pkg = it.pkg,
                    channelId = it.channelId
                )
            },
            windowStart = windowStart,
            gapDays = emptySet(),
            appLabels = appLabels
        )
    }

    override suspend fun pullLatest(): PullResult = PullResult(
        ok = false,
        message = "拉取通道批 6b 接线（Shizuku 与 WorkManager）"
    )

    private fun minOfNotNull(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> minOf(a, b)
    }

    private fun DaySummaryEntity.toDomain() = DaySummary(
        date = LocalDate.parse(date),
        screenMin = screenMin,
        screenUnlockMin = screenUnlockMin,
        unlocks = unlocks,
        launches = launches,
        longestSessionMin = longestSessionMin,
        pulledAt = pulledAt
    )
}
