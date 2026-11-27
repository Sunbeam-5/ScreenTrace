package com.screentrace.app.data

import com.screentrace.app.contract.AppUsage
import com.screentrace.app.contract.AppCategoryRow
import com.screentrace.app.contract.CalendarLevel
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.DailyPoint
import com.screentrace.app.contract.DaySummary
import com.screentrace.app.contract.ExtremesInfo
import com.screentrace.app.contract.HourBucket
import com.screentrace.app.contract.QualifiedHourlyDistribution
import com.screentrace.app.contract.MetricDetail
import com.screentrace.app.contract.MetricKind
import com.screentrace.app.contract.NotificationEvent
import com.screentrace.app.contract.PullResult
import com.screentrace.app.contract.StreakInfo
import com.screentrace.app.contract.Summary
import com.screentrace.app.contract.UnlockEvent
import com.screentrace.app.contract.UsageCalculator
import com.screentrace.app.contract.UsageRepository
import com.screentrace.app.data.db.DaySummaryAggregated
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
    /**
     * §4 CR-21 ③：**当前库**的取法。切换设备后必须读到那台设备自己的库，
     * 所以这里存的是「取当前库」的函数而不是某个库的实例——
     * 持有实例的写法切换后会继续读旧设备的数据（页面看着没变，其实库没换）。
     */
    private val dbProvider: () -> ScreenTraceDatabase,
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
    private val labelResolver: ((String) -> String?)? = null,
    /**
     * D-07 Review 修复（P1-1）：当前库归属设备 ID（用于隔离 `pull_log.latestOkPulledAtForDevice`）。
     * 保持默认参数 [com.screentrace.app.data.db.SourceIds.UNSPECIFIED] 兼容既有单测。
     */
    private val ownerDeviceId: () -> String = { com.screentrace.app.data.db.SourceIds.UNSPECIFIED }
) : UsageRepository {

    /** 当前库（每次取都重新解析，切换设备后立即生效）。 */
    private val db: ScreenTraceDatabase get() = dbProvider()

    /**
     * 兼容写法：直接绑定一个固定的库（单测与「只读本机库」的调用点用）。
     *
     * 生产路径请用 [dbProvider] —— 绑定实例的写法在切换设备后会继续读旧设备的数据。
     */
    constructor(
        db: ScreenTraceDatabase,
        settings: com.screentrace.app.data.prefs.SettingsStore? = null,
        pull: (suspend () -> PullResult)? = null,
        labelResolver: ((String) -> String?)? = null,
        ownerDeviceId: () -> String = { com.screentrace.app.data.db.SourceIds.UNSPECIFIED }
    ) : this({ db }, settings, pull, labelResolver, ownerDeviceId)

    /**
     * CR-28：读侧**跨来源合计**（§4 逐指标聚合：总使用时长/解锁/启动 SUM、最长连续 MAX）。
     * 同一天可能有多来源行（legacy + 各设备分量），取原始行会让 UsageCalculator 双算。
     */
    private suspend fun recordsBetween(start: LocalDate, end: LocalDate): List<DaySummary> =
        com.screentrace.app.uikit.PerfProbe.measure("Room/query") {
            db.daySummaryDao().sumRange(start.toString(), end.toString()).map { it.toDomain() }
        }

    /**
     * 评审 P0-1：三表任一非空。**不能只看 `day_summary`**——降级通道按设计只写
     * `app_usage` / `hour_usage`，只看日聚合会把「有排行数据」误判成「完全无数据」。
     */
    override suspend fun hasAnyData(): Boolean {
        // 一次查询里只认**一个**库：中途切换设备也不能一半读旧库一半读新库
        val database = db
        return database.daySummaryDao().count() > 0 ||
            database.appUsageDao().count() > 0 ||
            database.hourUsageDao().count() > 0
    }

    override suspend fun hasDataIn(selection: DateSelection): Boolean {
        val start = selection.startDate.toString()
        val end = selection.endDate.toString()
        val database = db
        // 评审 P2-2：按「行数」判会把 0 分钟行也算有记录（假 0 口径）——
        // hour_usage 用分钟合计判，0 行只说明「建过表结构」不说明「有使用」
        return database.daySummaryDao().countBetween(start, end) > 0 ||
            database.appUsageDao().countBetween(start, end) > 0 ||
            database.hourUsageDao().sumMinutesBetween(start, end) > 0
    }

    override suspend fun summary(selection: DateSelection): Summary =
        com.screentrace.app.uikit.PerfProbe.measure("Room/summary") {
            val latestPulledAt = latestOkPulledAt()
            UsageCalculator.summarize(
                selection = selection,
                records = recordsBetween(selection.startDate, selection.endDate),
                lastUpdatedAtMs = latestPulledAt
            )
        }

    override suspend fun hourlyDistributionForDates(dates: List<LocalDate>): List<HourBucket> =
        qualifiedHourlyDistribution(dates).buckets

    /**
     * D-07 Review 修复（P1-3）：对候选完整日执行四类小时日资格仲裁：
     * 1. 有 `hour_usage` 且小时日质量 ∈ {EXACT, LEGACY} → 进分子且 eligibleDays++；
     * 2. 有 `hour_usage` 但质量 ∈ {PARTIAL, UNKNOWN} → 分子分母双剔除；
     * 3. 无 `hour_usage`，且 `day_summary` 为 EXACT/LEGACY 且 screenUnlockMin == 0 && screenMin == 0 → 完整零使用日，eligibleDays++、分子贡献 0；
     * 4. 无 `hour_usage`，但 screenUnlockMin > 0 || screenMin > 0（小时明细缺失）→ 分子分母双剔除，绝不推断为 0。
     */
    override suspend fun qualifiedHourlyDistribution(dates: List<LocalDate>): QualifiedHourlyDistribution {
        val uniqueDates = dates.distinct()
        if (uniqueDates.isEmpty()) {
            return QualifiedHourlyDistribution(
                buckets = (0..23).map { HourBucket(it, 0) },
                eligibleDays = 0
            )
        }
        val database = db
        val dateKeys = uniqueDates.map { it.toString() }
        val hourQualityByDate = dateKeys
            .chunked(500)
            .flatMap { database.hourUsageDao().dayQualitiesForDates(it) }
            .associate { it.date to com.screentrace.app.contract.DayQuality.fromSql(it.dayQuality) }

        val minKey = dateKeys.min()
        val maxKey = dateKeys.max()
        val daySummaryByDate = database.daySummaryDao()
            .sumRange(minKey, maxKey)
            .associateBy { it.date }

        val qualifiedHourDates = mutableListOf<String>()
        var eligibleDays = 0
        for (key in dateKeys) {
            val hourQuality = hourQualityByDate[key]
            if (hourQuality != null) {
                if (hourQuality == com.screentrace.app.contract.DayQuality.EXACT ||
                    hourQuality == com.screentrace.app.contract.DayQuality.LEGACY
                ) {
                    qualifiedHourDates.add(key)
                    eligibleDays++
                }
            } else {
                val day = daySummaryByDate[key]
                val dayQuality = day?.let { com.screentrace.app.contract.DayQuality.fromSql(it.dayQuality) }
                val dayComplete = dayQuality == com.screentrace.app.contract.DayQuality.EXACT ||
                    dayQuality == com.screentrace.app.contract.DayQuality.LEGACY
                if (day != null && dayComplete && day.screenUnlockMin == 0 && day.screenMin == 0) {
                    eligibleDays++
                }
            }
        }

        val byHour = if (qualifiedHourDates.isEmpty()) {
            emptyMap()
        } else {
            qualifiedHourDates
                .chunked(500)
                .flatMap { database.hourUsageDao().sumForDates(it) }
                .groupBy { it.hour }
                .mapValues { (_, rows) -> rows.sumOf { it.minutes } }
        }
        val buckets = (0..23).map { hour -> HourBucket(hour, byHour[hour] ?: 0) }
        return QualifiedHourlyDistribution(buckets = buckets, eligibleDays = eligibleDays)
    }

    override suspend fun latestOkPulledAt(): Long? {
        val owner = ownerDeviceId()
        if (owner.isEmpty()) return null
        return db.pullLogDao().latestOkPulledAtForDevice(owner)
    }

    override suspend fun dailyTrend(selection: DateSelection): List<DailyPoint> =
        com.screentrace.app.uikit.PerfProbe.measure("Room/dailyTrend") {
            UsageCalculator.dailyPoints(selection, recordsBetween(selection.startDate, selection.endDate))
        }

    override suspend fun topAppsPerDay(
        selection: DateSelection,
        limit: Int
    ): Map<LocalDate, List<AppUsage>> {
        val overrides = settings?.categoryOverrides?.first() ?: emptyMap()
        return com.screentrace.app.uikit.PerfProbe.measure("Room/topAppsPerDay") {
            db.appUsageDao()
                .rowsBetween(selection.startDate.toString(), selection.endDate.toString())
            .groupBy { it.date }
            .mapNotNull { (dateKey, rows) ->
                val date = runCatching { LocalDate.parse(dateKey) }.getOrNull() ?: return@mapNotNull null
                // CR-28：同 (date,pkg) 跨来源合计（分钟/启动数 SUM）；名字取真名
                // （本机名优先于包名占位——「名字以本机为准」从写路径移到读路径）。
                val merged = rows.groupBy { it.pkg }.map { (_, samePkg) ->
                    val best = samePkg.maxBy { it.minutes }
                    val named = samePkg.firstOrNull { it.name.isNotBlank() && it.name != it.pkg } ?: best
                    best.copy(
                        name = named.name,
                        minutes = samePkg.sumOf { it.minutes },
                        launches = samePkg.sumOf { it.launches }
                    )
                }
                val top = merged.sortedByDescending { it.minutes }.take(limit).map {
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
    }

    override suspend fun appRanking(selection: DateSelection, limit: Int): List<AppUsage> {
        // 分类覆盖（用户修改）读取层叠加，优先于库内默认（§4，6a 评审 P1-1）
        val overrides = settings?.categoryOverrides?.first() ?: emptyMap()
        return com.screentrace.app.uikit.PerfProbe.measure("Room/appRanking") {
            db.appUsageDao()
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
    }

    override suspend fun hourlyDistribution(selection: DateSelection): List<HourBucket> =
        com.screentrace.app.uikit.PerfProbe.measure("Room/hourly") {
            db.hourUsageDao()
                .sumBetween(selection.startDate.toString(), selection.endDate.toString())
                .map { HourBucket(it.hour, it.minutes) }
        }

    /**
     * CR-28：「占用时钟时长」（UNION）——区间内逐日全来源 SCREEN 会话的裁剪并集分钟。
     * 存储永不合并跨来源区间，这里在读侧做并集（§4.2：跨午夜按自然日裁剪、不跨日摊派）。
     */
    override suspend fun clockOccupied(selection: DateSelection): Map<LocalDate, Int> =
        com.screentrace.app.uikit.PerfProbe.measure("Room/clockOccupied") {
            val rows = db.screenSessionDao().getAll()
            if (rows.isEmpty()) return@measure emptyMap()
            val zone = ZoneId.systemDefault()
            var day = selection.startDate
            val out = mutableMapOf<LocalDate, Int>()
            while (!day.isAfter(selection.endDate)) {
                out[day] = SessionUnion.occupiedMinutes(rows, day, zone)
                day = day.plusDays(1)
            }
            out
        }

    override suspend fun monthLevels(yearMonth: java.time.YearMonth): List<CalendarLevel> {
        val start = yearMonth.atDay(1)
        val end = yearMonth.atEndOfMonth()
        return com.screentrace.app.uikit.PerfProbe.measure("Calc/monthLevels") {
            UsageCalculator.monthLevels(yearMonth, recordsBetween(start, end))
        }
    }

    override suspend fun yearLevels(
        anchor: LocalDate,
        weeks: Int,
        lowThresholdMin: Int
    ): List<CalendarLevel> {
        val anchorMonday = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val start = anchorMonday.minusDays((weeks.toLong() - 1) * 7)
        val end = anchorMonday.plusDays(6)
        return com.screentrace.app.uikit.PerfProbe.measure("Calc/yearLevels") {
            UsageCalculator.yearLevels(anchor, weeks, recordsBetween(start, end), lowThresholdMin)
        }
    }

    /** §5.6：streak 是截至锚点的**全量历史**，查全部 day_summary（CR-28：跨来源合计）。 */
    override suspend fun streak(anchor: LocalDate): StreakInfo =
        com.screentrace.app.uikit.PerfProbe.measure("Calc/streak") {
            UsageCalculator.streak(db.daySummaryDao().sumAll().map { it.toDomain() }, anchor)
        }

    override suspend fun extremes(selection: DateSelection, lowThresholdMin: Int): ExtremesInfo =
        com.screentrace.app.uikit.PerfProbe.measure("Calc/extremes") {
            UsageCalculator.extremes(
                selection,
                recordsBetween(selection.startDate, selection.endDate),
                lowThresholdMin
            )
        }

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
    override suspend fun metricDetail(metric: MetricKind, selection: DateSelection): MetricDetail =
        com.screentrace.app.uikit.PerfProbe.measure("Room/metricDetail") {
        // 同一次详情查询固定用一个库（中途切换设备会读出半新半旧的两套数字）
        val database = db
        val zone = ZoneId.systemDefault()
        val startMs = selection.startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = selection.endDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        // 只读本指标要用的那张事件表（评审 P2-2）：解锁详情以前顺手把区间内全部通知
        // 也读出来，5k 行白读一次；两个分支各自只查自己那张表，结果与之前等价。
        val unlockMs = if (metric == MetricKind.UNLOCK) {
            database.unlockEventDao().msBetween(startMs, endMs)
        } else {
            emptyList()
        }
        val notifs = if (metric == MetricKind.NOTIFICATION) {
            database.notificationEventDao().between(startMs, endMs)
        } else {
            emptyList()
        }
        val windowStartMs = when (metric) {
            MetricKind.UNLOCK -> database.unlockEventDao().minMs()
            MetricKind.NOTIFICATION -> database.notificationEventDao().minMs()
        }
        val windowStart = windowStartMs?.let {
            java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate()
        }
        val gapDays = computeGapDays(zone, database)
        // 「不灭屏日」判定（真机反馈：开了不灭屏的设备本来就不会有 KEYGUARD 事件，
        // 0 解锁是真实值，不能因为「从没见过解锁事件」就一直画「—」）：
        // 不灭屏日 = 整日亮屏只有**一条连续会话**（longestSessionMin >= screenUnlockMin）
        // 且 0 解锁。区间内所有有亮屏的日子都满足 → 窗口内的 0 解锁可信。
        // 反例：屏幕每 UnlockEvent 天开关几十次却 0 解锁事件（ROM 不上报），此时仍画「—」。
        val neverSleepsWindow = if (metric == MetricKind.UNLOCK) {
            val days = database.daySummaryDao()
                .sumRange(selection.startDate.toString(), selection.endDate.toString())
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
            database.appUsageDao().allNames().forEach { put(it.pkg, displayName(it.pkg, it.name)) }
            notifs.map { it.pkg }.distinct().forEach { pkg ->
                if (pkg !in this) put(pkg, displayName(pkg, pkg))
            }
        }
        UsageCalculator.metricDetail(
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
    private suspend fun computeGapDays(
        zone: ZoneId,
        database: ScreenTraceDatabase = db
    ): Set<LocalDate> {
        val toDay: (Long) -> LocalDate = { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val failed = database.pullLogDao().failedMs().map(toDay).toSet()
        val ok = database.pullLogDao().okMs().map(toDay).toSet()
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

    private fun DaySummaryAggregated.toDomain() = DaySummary(
        date = LocalDate.parse(date),
        screenMin = screenMin,
        screenUnlockMin = screenUnlockMin,
        unlocks = unlocks,
        launches = launches,
        longestSessionMin = longestSessionMin,
        pulledAt = pulledAt,
        screenQuality = if (dayQuality == "LEGACY") null else dayQuality,
        dayQuality = com.screentrace.app.contract.DayQuality.fromSql(dayQuality)
    )
}
