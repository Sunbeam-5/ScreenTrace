package com.screentrace.app.data

import com.screentrace.app.contract.AppUsage
import com.screentrace.app.contract.AppCategoryRow
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
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Room 实现（批 6a）：查询 + 聚合全部经 [UsageCalculator]（§6：聚合口径唯一出处）。
 * 展示名按 §3-8：库内 name 优先（6b 用 PackageManager 回填后），回退内置映射表再回退包名；
 * 分类按 §4：库内存默认映射，用户覆盖（SettingsStore.categoryOverrides）读取层叠加。
 *
 * `pullLatest` 由批 6b 的 [com.screentrace.app.data.pull.PullCoordinator] 通过 [pull] 注入：
 * 仓库**不自己持有通道**，否则契约层会反向依赖数据通道实现；未注入时返回明确的
 * 「当前环境不支持拉取」而不是假的「成功」——§6 红线：不许有假按钮/假结果。
 */
class RoomUsageRepository(
    private val db: ScreenTraceDatabase,
    private val settings: com.screentrace.app.data.prefs.SettingsStore? = null,
    /** 手动拉取入口（设置页「立即拉取」）：由 AppContainer 接到 PullCoordinator。 */
    private val pull: (suspend () -> PullResult)? = null,
    /**
     * §3-8 实时展示名。库里存到的名字优先，没有时（包可见性受限、历史脏数据、
     * 刚装的包）现查一次。
     *
     * 为什么读取层也要查：入库时写的是当时的名字，而 `app_usage` 里可能压根没有
     * 这个包（只出现在通知事件里的系统服务，如 `com.xiaomi.mi_connect_service`），
     * 不在这里兜一层，通知详情就会显示成一串包名。
     */
    private val labelResolver: ((String) -> String?)? = null
) : UsageRepository {

    private suspend fun recordsBetween(start: LocalDate, end: LocalDate): List<DaySummary> =
        db.daySummaryDao().getBetween(start.toString(), end.toString()).map { it.toDomain() }

    /**
     * 评审 P0-1：三表任一非空。**不能只看 `day_summary`**——降级通道按设计只写
     * `app_usage` / `hour_usage`，只看日聚合会把「有排行数据」误判成「完全无数据」。
     */
    override suspend fun hasAnyData(): Boolean =
        db.daySummaryDao().count() > 0 ||
            db.appUsageDao().count() > 0 ||
            db.hourUsageDao().count() > 0

    override suspend fun hasDataIn(selection: DateSelection): Boolean {
        val start = selection.startDate.toString()
        val end = selection.endDate.toString()
        return db.daySummaryDao().countBetween(start, end) > 0 ||
            db.appUsageDao().countBetween(start, end) > 0 ||
            db.hourUsageDao().countBetween(start, end) > 0
    }

    override suspend fun summary(selection: DateSelection): Summary =
        UsageCalculator.summarize(selection, recordsBetween(selection.startDate, selection.endDate))

    override suspend fun dailyTrend(selection: DateSelection): List<DailyPoint> =
        UsageCalculator.dailyPoints(selection, recordsBetween(selection.startDate, selection.endDate))

    override suspend fun topAppsPerDay(
        selection: DateSelection,
        limit: Int
    ): Map<LocalDate, List<AppUsage>> {
        val overrides = settings?.categoryOverrides?.first() ?: emptyMap()
        return db.appUsageDao()
            .rowsBetween(selection.startDate.toString(), selection.endDate.toString())
            .groupBy { it.date }
            .mapNotNull { (dateKey, rows) ->
                val date = runCatching { LocalDate.parse(dateKey) }.getOrNull() ?: return@mapNotNull null
                val top = rows.take(limit).map {
                    AppUsage(
                        date = date,
                        pkg = it.pkg,
                        name = displayName(it.pkg, it.name),
                        minutes = it.minutes,
                        launches = it.launches,
                        category = overrides[it.pkg] ?: runCatching {
                            com.screentrace.app.contract.AppCategory.valueOf(it.category)
                        }.getOrDefault(com.screentrace.app.contract.AppCategory.OTHER)
                    )
                }
                date to top
            }
            .toMap()
    }

    override suspend fun appRanking(selection: DateSelection, limit: Int): List<AppUsage> {
        // 分类覆盖（用户修改）读取层叠加，优先于库内默认（§4，6a 评审 P1-1）
        val overrides = settings?.categoryOverrides?.first() ?: emptyMap()
        return db.appUsageDao()
            .sumBetween(selection.startDate.toString(), selection.endDate.toString())
            .take(limit)
            .map {
                AppUsage(
                    date = selection.endDate, // 区间聚合行（§5.7 排行显示区间累计）
                    pkg = it.pkg,
                    name = displayName(it.pkg, it.name),
                    minutes = it.minutes,
                    launches = it.launches,
                    category = overrides[it.pkg] ?: runCatching {
                        com.screentrace.app.contract.AppCategory.valueOf(it.category)
                    }.getOrDefault(com.screentrace.app.contract.AppCategory.OTHER)
                )
            }
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

    override suspend fun extremes(selection: DateSelection, lowThresholdMin: Int): ExtremesInfo =
        UsageCalculator.extremes(
            selection,
            recordsBetween(selection.startDate, selection.endDate),
            lowThresholdMin
        )

    /**
     * §5.8 应用分类管理：全库出现过的包（不限区间——分类是长期属性，
     * 按当前筛选区间列出来会让「上个月用过的应用」突然消失，用户改不了它的分类）。
     *
     * 分类取「用户覆盖 > 内置映射」，与排行页读取层完全同一套优先级（§4）。
     */
    override suspend fun appCategories(): List<AppCategoryRow> {
        val overrides = settings?.categoryOverrides?.first() ?: emptyMap()
        return db.appUsageDao().allNames()
            .map { row ->
                AppCategoryRow(
                    pkg = row.pkg,
                    name = displayName(row.pkg, row.name),
                    category = overrides[row.pkg] ?: AppNames.categoryOf(row.pkg)
                )
            }
            .sortedWith(compareBy({ it.category.ordinal }, { it.name }))
    }

    /**
     * CR-01（C04）：解锁/通知详情统一查询——图表/表格/卡片同源于此（§7-16）。
     * 事件覆盖窗口起点按指标分别取本表最早事件（6a 评审 P2-3）；
     * gapDays 从 pull_log 推导：有失败记录且当天无成功拉取的日期为 GAP（6a 评审 P1-3，
     * 覆盖三态不再退化）。
     */
    override suspend fun metricDetail(metric: MetricKind, selection: DateSelection): MetricDetail {
        val zone = ZoneId.systemDefault()
        val startMs = selection.startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = selection.endDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        // 只读本指标要用的那张事件表（评审 P2-2）：解锁详情以前顺手把区间内全部通知
        // 也读出来，5k 行白读一次；两个分支各自只查自己那张表，结果与之前等价。
        val unlockMs = if (metric == MetricKind.UNLOCK) {
            db.unlockEventDao().msBetween(startMs, endMs)
        } else {
            emptyList()
        }
        val notifs = if (metric == MetricKind.NOTIFICATION) {
            db.notificationEventDao().between(startMs, endMs)
        } else {
            emptyList()
        }
        val windowStartMs = when (metric) {
            MetricKind.UNLOCK -> db.unlockEventDao().minMs()
            MetricKind.NOTIFICATION -> db.notificationEventDao().minMs()
        }
        val windowStart = windowStartMs?.let {
            java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate()
        }
        val gapDays = computeGapDays(zone)
        // 「不灭屏日」判定（真机反馈：开了不灭屏的设备本来就不会有 KEYGUARD 事件，
        // 0 解锁是真实值，不能因为「从没见过解锁事件」就一直画「—」）：
        // 不灭屏日 = 整日亮屏只有**一条连续会话**（longestSessionMin >= screenUnlockMin）
        // 且 0 解锁。区间内所有有亮屏的日子都满足 → 窗口内的 0 解锁可信。
        // 反例：屏幕每 UnlockEvent 天开关几十次却 0 解锁事件（ROM 不上报），此时仍画「—」。
        val neverSleepsWindow = if (metric == MetricKind.UNLOCK) {
            val days = db.daySummaryDao()
                .getBetween(selection.startDate.toString(), selection.endDate.toString())
                .filter { it.screenUnlockMin > 0 }
            days.isNotEmpty() && days.all {
                it.unlocks == 0 && it.longestSessionMin >= it.screenUnlockMin
            }
        } else {
            false
        }
        // 名字表要覆盖「**只在通知事件里出现**的包」：`allNames()` 只查 `app_usage`，
        // 而系统服务（如 com.xiaomi.mi_connect_service）往往只在通知事件里有记录、
        // 从不进入应用使用排行。不补这一层，通知详情就会显示成一串 com.xxx 包名
        // ——真机上正是这么看到的（bug 8）。
        val appLabels = buildMap {
            db.appUsageDao().allNames().forEach { put(it.pkg, displayName(it.pkg, it.name)) }
            notifs.map { it.pkg }.distinct().forEach { pkg ->
                if (pkg !in this) put(pkg, displayName(pkg, pkg))
            }
        }
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
            gapDays = gapDays,
            neverSleepsWindow = neverSleepsWindow,
            appLabels = appLabels
        )
    }

    /** 覆盖三态的 GAP 日（§4）：拉取失败且当天没有成功拉取的日期。 */
    private suspend fun computeGapDays(zone: ZoneId): Set<LocalDate> {
        val toDay: (Long) -> LocalDate = { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val failed = db.pullLogDao().failedMs().map(toDay).toSet()
        val ok = db.pullLogDao().okMs().map(toDay).toSet()
        return failed - ok
    }

    /** §3-8 展示名：库内 name（6b 回填）优先 → 内置映射表 → 包名。 */
    private fun displayName(pkg: String, storedName: String): String =
        if (storedName.isNotBlank() && storedName != pkg) {
            storedName
        } else {
            // 库里没存到 → 现查（AppLabelResolver 自带缓存）→ 内置映射 → 包名兜底
            runCatching { labelResolver?.invoke(pkg) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: AppNames.nameOf(pkg)
        }

    override suspend fun pullLatest(): PullResult =
        pull?.invoke() ?: PullResult(ok = false, message = "当前环境未接入拉取通道")

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
