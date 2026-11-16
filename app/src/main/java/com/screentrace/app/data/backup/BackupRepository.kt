package com.screentrace.app.data.backup

import androidx.room.withTransaction
import com.screentrace.app.contract.AppCategory
import com.screentrace.app.data.IngestSanity
import com.screentrace.app.data.SessionUnion
import com.screentrace.app.data.parse.AppUsageRow
import com.screentrace.app.data.parse.DayMetrics
import com.screentrace.app.data.db.AppLimitEntity
import com.screentrace.app.data.db.AppUsageEntity
import com.screentrace.app.data.db.DaySummaryEntity
import com.screentrace.app.data.db.HourUsageEntity
import com.screentrace.app.data.db.NotificationEventEntity
import com.screentrace.app.data.db.PullLogEntity
import com.screentrace.app.data.db.ScreenSessionEntity
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.db.UnlockEventEntity
import com.screentrace.app.data.prefs.PullFrequency
import com.screentrace.app.data.prefs.SettingsStore
import com.screentrace.app.theme.ThemeVariant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first

/**
 * §5.10：本地备份导出与恢复（JSON / CSV）。
 *
 * 两条纪律：
 * 1. **导出的数字就是库里的数字**——不重算、不汇总，逐行照抄，恢复后与原机一致；
 * 2. **恢复在单事务里完成**（[ScreenTraceDatabase.withTransaction]），任何一步抛异常整体回滚，
 *    原数据不变（§7-13）。所以这里绝不「先清空再慢慢插入」而不包事务。
 */
class BackupRepository(
    private val db: ScreenTraceDatabase,
    private val settings: SettingsStore? = null,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** §5.10 批 C：导出 JSON 顶层 `device` 的来源；null = 不带设备标识（旧行为）。 */
    private val device: suspend () -> com.screentrace.app.data.prefs.DeviceInfo? = { null }
) {

    enum class Strategy { OVERWRITE, MERGE }

    data class RestoreResult(
        val added: Int,
        val updated: Int,
        val skipped: Int,
        val settingsRestored: Boolean
    )

    // ---------------------------------------------------------------- 导出

    suspend fun buildFile(appVersion: String, at: LocalDateTime = LocalDateTime.now()): BackupFile {
        val info = runCatching { device() }.getOrNull()
        return BackupFile(
            schemaVersion = BackupCodec.SCHEMA_VERSION,
            exportedAt = at.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
            appVersion = appVersion,
            settings = currentSettings(),
            daySummary = db.daySummaryDao().getAll().map {
                DaySummaryDto(
                    it.date, it.screenMin, it.screenUnlockMin, it.unlocks,
                    it.launches, it.longestSessionMin, it.pulledAt
                )
            },
            appUsage = db.appUsageDao().getAll().map {
                AppUsageDto(it.date, it.pkg, it.name, it.minutes, it.launches, it.category)
            },
            hourUsage = db.hourUsageDao().getAll().map { HourUsageDto(it.date, it.hour, it.minutes) },
            pullLog = db.pullLogDao().getAll().map { PullLogDto(it.pulledAt, it.source, it.ok, it.note) },
            unlockEvent = db.unlockEventDao().getAll().map { UnlockEventDto(it.occurredAtMs) },
            notificationEvent = db.notificationEventDao().getAll().map {
                NotificationEventDto(it.occurredAtMs, it.type, it.pkg, it.channelId)
            },
            screenSession = db.screenSessionDao().getAll().map {
                ScreenSessionDto(it.startMs, it.endMs, it.kind)
            },
            appLimit = db.appLimitDao().getAll().map {
                AppLimitDto(it.pkg, it.limitMin, it.enabled)
            },
            device = info?.let { BackupDevice(it.id, it.nickname, it.model) }
        )
    }

    suspend fun exportJson(appVersion: String): Pair<String, String> {
        val file = buildFile(appVersion)
        return BackupCodec.fileName(LocalDateTime.now()) to BackupCodec.encode(file)
    }

    private suspend fun currentSettings(): BackupSettings? {
        val store = settings ?: return null
        val overrides = runCatching { store.categoryOverrides.first() }.getOrDefault(emptyMap())
        return BackupSettings(
            dailyGoalMin = runCatching { store.dailyGoalMin.first() }.getOrNull(),
            lowThresholdMin = runCatching { store.lowUsageThresholdMin.first() }.getOrNull(),
            themeVariant = runCatching { store.themeVariant.first().name }.getOrNull(),
            darkTheme = runCatching { store.darkTheme.first() }.getOrNull(),
            dynamicColor = runCatching { store.dynamicColor.first() }.getOrNull(),
            customThemeSeed = runCatching { store.customThemeSeed.first() }.getOrNull(),
            pullFrequency = runCatching { store.pullFrequency.first().name }.getOrNull(),
            categoryOverrides = overrides.mapValues { it.value.name },
            alertEnabled = null // 批 11 才有真实开关
        )
    }

    /** §5.10「导出 CSV 明细」：七张明细表各一个文本（UTF-8 带 BOM，Excel 直接打开不乱码）。 */
    suspend fun exportCsv(appVersion: String): List<Pair<String, String>> {
        val file = buildFile(appVersion)
        val stamp = LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
        )
        fun csv(name: String, header: List<String>, rows: List<List<String>>): Pair<String, String> {
            val body = buildString {
                append('\uFEFF')
                appendLine(header.joinToString(",") { escapeCsv(it) })
                rows.forEach { row -> appendLine(row.joinToString(",") { escapeCsv(it) }) }
            }
            return "screen-time-$name-$stamp.csv" to body
        }
        return listOf(
            csv(
                "day_summary",
                listOf("date", "screen_min", "screen_unlock_min", "unlocks", "launches", "longest_session_min", "pulled_at"),
                file.daySummary.map {
                    listOf(it.date, "${it.screenMin}", "${it.screenUnlockMin}", "${it.unlocks}", "${it.launches}", "${it.longestSessionMin}", "${it.pulledAt}")
                }
            ),
            csv(
                "app_usage",
                listOf("date", "pkg", "name", "minutes", "launches", "category"),
                file.appUsage.map {
                    listOf(it.date, it.pkg, it.name, "${it.minutes}", "${it.launches}", it.category)
                }
            ),
            csv(
                "hour_usage",
                listOf("date", "hour", "minutes"),
                file.hourUsage.map { listOf(it.date, "${it.hour}", "${it.minutes}") }
            ),
            csv(
                "unlock_event",
                listOf("occurred_at_ms"),
                file.unlockEvent.map { listOf("${it.occurredAtMs}") }
            ),
            csv(
                "notification_event",
                listOf("occurred_at_ms", "type", "pkg", "channel_id"),
                file.notificationEvent.map {
                    listOf("${it.occurredAtMs}", it.type, it.pkg, it.channelId ?: "")
                }
            ),
            csv(
                "screen_session",
                listOf("start_ms", "end_ms", "kind"),
                file.screenSession.map { listOf("${it.startMs}", "${it.endMs}", "${it.kind}") }
            ),
            csv(
                "app_limit",
                listOf("pkg", "limit_min", "enabled"),
                file.appLimit.map { listOf(it.pkg, "${it.limitMin}", "${it.enabled}") }
            )
        )
    }

    private fun escapeCsv(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    // ---------------------------------------------------------------- 恢复

    suspend fun restore(
        file: BackupFile,
        strategy: Strategy,
        importSettings: Boolean
    ): RestoreResult = db.withTransaction {
        val result = when (strategy) {
            Strategy.OVERWRITE -> overwrite(file)
            Strategy.MERGE -> merge(file)
        }
        val settingsRestored = if (importSettings) applySettings(file.settings) else false
        result.copy(settingsRestored = settingsRestored)
    }

    private suspend fun overwrite(file: BackupFile): RestoreResult {
        db.daySummaryDao().deleteAll()
        db.appUsageDao().deleteAll()
        db.hourUsageDao().deleteAll()
        db.pullLogDao().deleteAll()
        db.unlockEventDao().deleteAll()
        db.notificationEventDao().deleteAll()
        db.screenSessionDao().deleteAll()
        db.appLimitDao().deleteAll()

        insertDaySummaries(file.daySummary)
        insertAppUsage(file.appUsage)
        insertHourUsage(file.hourUsage)
        insertPullLog(file.pullLog)
        db.unlockEventDao().insertIgnore(file.unlockEvent.map { UnlockEventEntity(it.occurredAtMs) })
        db.notificationEventDao().insertIgnore(
            file.notificationEvent.map {
                NotificationEventEntity(it.occurredAtMs, it.type, it.pkg, it.channelId)
            }
        )
        db.screenSessionDao().insert(
            file.screenSession.map { ScreenSessionEntity(it.startMs, it.endMs, it.kind) }
        )
        db.appLimitDao().upsert(
            file.appLimit.map { AppLimitEntity(it.pkg, it.limitMin, it.enabled) }
        )

        val total = file.daySummary.size + file.appUsage.size + file.hourUsage.size +
            file.pullLog.size + file.unlockEvent.size + file.notificationEvent.size +
            file.screenSession.size + file.appLimit.size
        return RestoreResult(added = total, updated = 0, skipped = 0, settingsRestored = false)
    }

    private suspend fun merge(file: BackupFile): RestoreResult {
        var report = BackupMerge.Report()

        val dayPlan = BackupMerge.daySummaries(
            db.daySummaryDao().getAll().map {
                DaySummaryDto(it.date, it.screenMin, it.screenUnlockMin, it.unlocks, it.launches, it.longestSessionMin, it.pulledAt)
            },
            file.daySummary
        )
        insertDaySummaries(dayPlan.rows)
        report += dayPlan.report

        // 评审 P1-1：备份的应用/时段数据同样要过自洽校验（IngestSanity 与拉取入库同口径）。
        // 备份里可能带着 MIUI totalTimeUsed 的虚高行——「取大合并」会把它永久固化在库里，
        // 正确的新数据永远盖不掉，表现为「应用合计 2467 小时 > 屏幕 11 小时」。
        // 参照物用**合并后的 day_summary**（dayPlan 已插入）。
        val sanityDays = dayPlan.rows.associate {
            LocalDate.parse(it.date) to DayMetrics(
                screenMin = it.screenMin,
                screenUnlockMin = it.screenUnlockMin,
                unlocks = it.unlocks,
                launches = it.launches,
                longestSessionMin = it.longestSessionMin
            )
        }
        val sanityVerdict = IngestSanity.check(
            days = sanityDays,
            appUsage = file.appUsage.map {
                AppUsageRow(LocalDate.parse(it.date), it.pkg, it.minutes * 60_000L, it.launches)
            },
            hourMinutes = file.hourUsage
                .groupBy { it.date }
                .mapKeys { LocalDate.parse(it.key) }
                .mapValues { (_, rows) -> (0..23).map { h -> rows.firstOrNull { it.hour == h }?.minutes ?: 0 } }
        )
        val badDates = sanityVerdict.droppedDates.toSet()
        val safeAppUsage = file.appUsage.filterNot { LocalDate.parse(it.date) in badDates }
        val safeHourUsage = file.hourUsage.filterNot { LocalDate.parse(it.date) in badDates }
        val droppedRows = (file.appUsage.size - safeAppUsage.size) +
            (file.hourUsage.size - safeHourUsage.size)
        if (sanityVerdict.hasDrops) {
            report += BackupMerge.Report(skipped = droppedRows)
        }

        val appPlan = BackupMerge.appUsage(
            db.appUsageDao().getAll().map {
                AppUsageDto(it.date, it.pkg, it.name, it.minutes, it.launches, it.category)
            },
            safeAppUsage
        )
        insertAppUsage(appPlan.rows)
        report += appPlan.report

        val hourPlan = BackupMerge.hourUsage(
            db.hourUsageDao().getAll().map { HourUsageDto(it.date, it.hour, it.minutes) },
            safeHourUsage
        )
        insertHourUsage(hourPlan.rows)
        report += hourPlan.report

        // 评审 P2-1：按 (pulledAt, source) 去重——同一台设备的备份反复合并时日志不膨胀，
        // 且别台设备更晚的时间戳不会把「最近成功拉取」顶掉后还留一堆重复行
        val existingPullKeys = db.pullLogDao().getAll().map { it.pulledAt to it.source }.toSet()
        val pullRows = file.pullLog
            .filterNot { (it.pulledAt to it.source) in existingPullKeys }
            .map { PullLogEntity(pulledAt = it.pulledAt, source = it.source, ok = it.ok, note = it.note) }
        pullRows.forEach { db.pullLogDao().insert(it) }
        report += BackupMerge.Report(added = pullRows.size)

        val unlockPlan = BackupMerge.unlockEvents(
            localMs = db.unlockEventDao().getAll().map { it.occurredAtMs }.toSet(),
            backup = file.unlockEvent
        )
        db.unlockEventDao().insertIgnore(unlockPlan.rows.map { UnlockEventEntity(it.occurredAtMs) })
        report += unlockPlan.report

        val notificationPlan = BackupMerge.notificationEvents(
            local = db.notificationEventDao().getAll().map {
                NotificationEventDto(it.occurredAtMs, it.type, it.pkg, it.channelId)
            },
            backup = file.notificationEvent
        )
        db.notificationEventDao().insertIgnore(
            notificationPlan.rows.map {
                NotificationEventEntity(it.occurredAtMs, it.type, it.pkg, it.channelId)
            }
        )
        report += notificationPlan.report

        val sessionReport = mergeSessions(file.screenSession)
        report += sessionReport

        val limitPlan = BackupMerge.appLimits(
            local = db.appLimitDao().getAll().map { AppLimitDto(it.pkg, it.limitMin, it.enabled) },
            backup = file.appLimit
        )
        db.appLimitDao().upsert(limitPlan.rows.map { AppLimitEntity(it.pkg, it.limitMin, it.enabled) })
        report += limitPlan.report

        return RestoreResult(report.added, report.updated, report.skipped, settingsRestored = false)
    }

    /**
     * §4 CR-22 ②：本机库这些日期的日聚合**指纹**（供「是不是同一台机器」判定用）。
     *
     * 只读、不写库；`day_summary` 至多十几天，取全表也不贵。
     */
    suspend fun dayFingerprints(
        dates: Set<String>
    ): Map<String, SameDeviceFingerprint.DayFingerprint> {
        if (dates.isEmpty()) return emptyMap()
        return db.daySummaryDao().getAll()
            .filter { it.date in dates }
            .associate { it.date to SameDeviceFingerprint.DayFingerprint.of(
                DaySummaryDto(
                    it.date, it.screenMin, it.screenUnlockMin, it.unlocks,
                    it.launches, it.longestSessionMin, it.pulledAt
                )
            ) }
    }

    /**
     * §4 CR-22 ②：本机库这些日期的**事件主键集合**（供「是不是同一台机器」判定用）。
     *
     * 同一台机器的事件时间戳到毫秒一致，所以「备份该日事件 ⊆ 本机该日事件」是同机
     * 更早快照的强证据；两台不同手机的事件时间戳不可能重合。
     */
    suspend fun dayEventKeys(
        dates: Set<String>,
        zone: ZoneId
    ): Map<String, SameDeviceFingerprint.DayEventKeys> {
        if (dates.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, MutableSet<String>>()
        db.notificationEventDao().getAll().forEach {
            val date = CrossDeviceMerge.dateOf(it.occurredAtMs, zone)
            if (date in dates) {
                out.getOrPut(date) { mutableSetOf() } += SameDeviceFingerprint.DayEventKeys
                    .ofNotification(it.occurredAtMs, it.type, it.pkg)
            }
        }
        db.unlockEventDao().getAll().forEach {
            val date = CrossDeviceMerge.dateOf(it.occurredAtMs, zone)
            if (date in dates) {
                out.getOrPut(date) { mutableSetOf() } += SameDeviceFingerprint.DayEventKeys
                    .ofUnlock(it.occurredAtMs)
            }
        }
        return out.mapValues { (_, keys) -> SameDeviceFingerprint.DayEventKeys(keys) }
    }

    /**
     * §4 CR-21：**跨设备累加合并**（与 [merge] 的「同日取较大」是**两套口径**）。
     *
     * [merge] 服务于「同一台设备的重拉 / 恢复」——同一份事实反复导入不能翻倍；
     * 本方法服务于「两台不同设备的数据汇合」——两台设备当天的使用量本来就应该相加。
     * 两套口径的差别是语义而不是参数，因此必须是两套明确实现（§6 红线）：
     * 复用「取大」会把旧机数据整段丢掉（用户看到的是"合并不起作用"），
     * 复用「累加」到同设备恢复上则会让重复导入翻倍。
     *
     * @param allowedDates 只并入这些日期（防重复记账给出的「尚未合并过的新日期」）。
     *                    事件与会话按本地时区换算日期后同样受它约束。
     * @return 合并结果摘要；`skipped` 含被自洽校验丢弃的行。
     */
    suspend fun mergeAccumulate(
        file: BackupFile,
        allowedDates: Set<String>
    ): RestoreResult = db.withTransaction {
        var report = BackupMerge.Report()

        // ① 日聚合：累加（screen / unlock / launches 相加，longest 取大）
        val dayPlan = CrossDeviceMerge.daySummaries(
            db.daySummaryDao().getAll().map {
                DaySummaryDto(it.date, it.screenMin, it.screenUnlockMin, it.unlocks, it.launches, it.longestSessionMin, it.pulledAt)
            },
            file.daySummary.filter { it.date in allowedDates }
        )
        insertDaySummaries(dayPlan.rows)
        report += dayPlan.report

        // ② 应用 / 时段：先过自洽校验（与同设备合并同一道闸），再累加。
        // 参照物是**合并后的**日聚合（累加值），所以旧机那几天的应用时长有更宽的上限。
        val sanityDays = dayPlan.rows.associate {
            LocalDate.parse(it.date) to DayMetrics(
                screenMin = it.screenMin,
                screenUnlockMin = it.screenUnlockMin,
                unlocks = it.unlocks,
                launches = it.launches,
                longestSessionMin = it.longestSessionMin
            )
        }
        val incomingApp = file.appUsage.filter { it.date in allowedDates }
        val incomingHour = file.hourUsage.filter { it.date in allowedDates }
        val sanityVerdict = IngestSanity.check(
            days = sanityDays,
            appUsage = incomingApp.map {
                AppUsageRow(LocalDate.parse(it.date), it.pkg, it.minutes * 60_000L, it.launches)
            },
            hourMinutes = incomingHour
                .groupBy { it.date }
                .mapKeys { LocalDate.parse(it.key) }
                .mapValues { (_, rows) -> (0..23).map { h -> rows.firstOrNull { it.hour == h }?.minutes ?: 0 } }
        )
        val badDates = sanityVerdict.droppedDates.toSet()
        val safeApp = incomingApp.filterNot { LocalDate.parse(it.date) in badDates }
        val safeHour = incomingHour.filterNot { LocalDate.parse(it.date) in badDates }
        if (sanityVerdict.hasDrops) {
            report += BackupMerge.Report(
                skipped = (incomingApp.size - safeApp.size) + (incomingHour.size - safeHour.size)
            )
        }

        val appPlan = CrossDeviceMerge.appUsage(
            db.appUsageDao().getAll().map {
                AppUsageDto(it.date, it.pkg, it.name, it.minutes, it.launches, it.category)
            },
            safeApp
        )
        insertAppUsage(appPlan.rows)
        report += appPlan.report

        val hourPlan = CrossDeviceMerge.hourUsage(
            db.hourUsageDao().getAll().map { HourUsageDto(it.date, it.hour, it.minutes) },
            safeHour
        )
        insertHourUsage(hourPlan.rows)
        report += hourPlan.report

        // ③ 拉取日志：仅追加，按 (pulledAt, source) 去重（与同设备合并同一口径）
        val existingPullKeys = db.pullLogDao().getAll().map { it.pulledAt to it.source }.toSet()
        val pullRows = file.pullLog
            .filterNot { (it.pulledAt to it.source) in existingPullKeys }
            .map { PullLogEntity(pulledAt = it.pulledAt, source = it.source, ok = it.ok, note = it.note) }
        pullRows.forEach { db.pullLogDao().insert(it) }
        report += BackupMerge.Report(added = pullRows.size)

        // ④ 事件：主键去重。两机时间戳不同故各自保留、计数自然累加，同刻同包才去重。
        val unlockRows = file.unlockEvent.filter {
            CrossDeviceMerge.dateOf(it.occurredAtMs, zone) in allowedDates
        }
        db.unlockEventDao().insertIgnore(unlockRows.map { UnlockEventEntity(it.occurredAtMs) })
        report += BackupMerge.Report(added = unlockRows.size)

        val notificationRows = file.notificationEvent.filter {
            CrossDeviceMerge.dateOf(it.occurredAtMs, zone) in allowedDates
        }
        db.notificationEventDao().insertIgnore(
            notificationRows.map {
                NotificationEventEntity(it.occurredAtMs, it.type, it.pkg, it.channelId)
            }
        )
        report += BackupMerge.Report(added = notificationRows.size)

        // ⑤ 会话区间：并集（**不累加**——区间是时序表示，累加会把同一段时长计两次）。
        // 重算时与旧值取大，所以第 ① 步写进去的累加值不会被重算改小。
        // 备份里没有会话时不做并集（重写整张表再重算全部日期，纯属白干）。
        val sessionRows = file.screenSession.filter { session ->
            CrossDeviceMerge.datesOfSession(session.startMs, session.endMs, zone)
                .any { it in allowedDates }
        }
        if (sessionRows.isNotEmpty()) {
            report += mergeSessions(sessionRows)
        }

        // ⑥ 应用限额：**完全不写**——限额是本机用户意图，跨设备合并不搬运（§4）。
        RestoreResult(report.added, report.updated, report.skipped, settingsRestored = false)
    }

    /**
     * §4 / §5.10：会话区间按扫线并集合并，再**重算受影响日期**的日聚合与小时矩阵。
     *
     * 不重算的话会出现「区间表里有 16 小时、day_summary 还是 11 小时」的两套数字，
     * 后续拉取时并集重算又突然跳变。重算结果与既有值取大（§4 合并策略），
     * 保证「合并」不会让任何一天变少。
     */
    private suspend fun mergeSessions(backup: List<ScreenSessionDto>): BackupMerge.Report {
        val local = db.screenSessionDao().getAll()
        val allRows = local.map { ScreenSessionEntity(it.startMs, it.endMs, it.kind) } +
            backup.map { ScreenSessionEntity(it.startMs, it.endMs, it.kind) }
        if (allRows.isEmpty()) return BackupMerge.Report()

        val screen = SessionUnion.unionEntities(allRows, ScreenSessionEntity.KIND_SCREEN)
        val keyguard = SessionUnion.unionEntities(allRows, ScreenSessionEntity.KIND_KEYGUARD)

        // 全表重写：并集结果已经是「所有区间的最小无重叠表示」，先删后插不会丢信息
        db.screenSessionDao().deleteAll()
        db.screenSessionDao().insert(screen + keyguard)

        val slices = SessionUnion.daySlices(screen, keyguard, zone)
        if (slices.isEmpty()) return BackupMerge.Report(added = screen.size + keyguard.size)

        val existingDays = db.daySummaryDao()
            .getBetween("0000-00-00", "9999-99-99")
            .associateBy { it.date }
        var added = 0
        var updated = 0
        slices.forEach { (date, slice) ->
            val current = existingDays[date.toString()]
            val existingUnlock = current?.screenUnlockMin ?: -1
            val existingHours = db.hourUsageDao().getForDate(date.toString()).associate { it.hour to it.minutes }
            val mergedHours = (0..23).map { hour ->
                maxOf(slice.hours.getOrElse(hour) { 0 }, existingHours[hour] ?: 0)
            }
            if (current == null) added++ else if (slice.unlockMin > existingUnlock) updated++
            db.daySummaryDao().upsert(
                DaySummaryEntity(
                    date = date.toString(),
                    screenMin = maxOf(slice.screenMin, current?.screenMin ?: 0),
                    screenUnlockMin = maxOf(slice.unlockMin, current?.screenUnlockMin ?: 0),
                    unlocks = current?.unlocks ?: 0, // 解锁次数以事件表为准，不从会话推导
                    launches = current?.launches ?: 0,
                    longestSessionMin = maxOf(slice.longestMin, current?.longestSessionMin ?: 0),
                    pulledAt = current?.pulledAt ?: System.currentTimeMillis()
                )
            )
            mergedHours.forEachIndexed { hour, minutes ->
                // 评审 P2-2：0 分钟不写行——「有行」即「有记录」（hasDataIn 按行判断），
                // 写 0 行会把「只恢复了会话、无事件」的日子误判成有记录（假 0 口径相抵）
                if (minutes > 0) {
                    db.hourUsageDao().insert(HourUsageEntity(date.toString(), hour, minutes))
                }
            }
        }
        return BackupMerge.Report(added = added, updated = updated, skipped = 0)
    }

    private suspend fun insertDaySummaries(rows: List<DaySummaryDto>) {
        rows.forEach {
            db.daySummaryDao().upsert(
                DaySummaryEntity(
                    it.date, it.screenMin, it.screenUnlockMin, it.unlocks,
                    it.launches, it.longestSessionMin, it.pulledAt
                )
            )
        }
    }

    private suspend fun insertAppUsage(rows: List<AppUsageDto>) {
        rows.forEach {
            db.appUsageDao().insert(
                AppUsageEntity(it.date, it.pkg, it.name, it.minutes, it.launches, it.category)
            )
        }
    }

    private suspend fun insertHourUsage(rows: List<HourUsageDto>) {
        rows.forEach { db.hourUsageDao().insert(HourUsageEntity(it.date, it.hour, it.minutes)) }
    }

    private suspend fun insertPullLog(rows: List<PullLogDto>) {
        rows.forEach {
            db.pullLogDao().insert(PullLogEntity(pulledAt = it.pulledAt, source = it.source, ok = it.ok, note = it.note))
        }
    }

    /** 设置默认不导入；勾选「同时恢复设置」才写，且只写备份里真实存在的字段。 */
    private suspend fun applySettings(restored: BackupSettings?): Boolean {
        val store = settings ?: return false
        if (restored == null) return false
        restored.dailyGoalMin?.let { store.setDailyGoalMin(it) }
        restored.lowThresholdMin?.let { store.setLowUsageThresholdMin(it) }
        restored.darkTheme?.let { store.setDarkTheme(it) }
        restored.dynamicColor?.let { store.setDynamicColor(it) }
        store.setCustomThemeSeed(restored.customThemeSeed)
        restored.pullFrequency?.let { store.setPullFrequency(PullFrequency.of(it)) }
        restored.themeVariant?.let { raw ->
            runCatching { ThemeVariant.valueOf(raw) }.getOrNull()?.let { store.setThemeVariant(it) }
        }
        if (restored.categoryOverrides.isNotEmpty()) {
            val overrides = restored.categoryOverrides.mapNotNull { (pkg, name) ->
                runCatching { AppCategory.valueOf(name) }.getOrNull()?.let { pkg to it }
            }.toMap()
            store.setCategoryOverrides(overrides)
        }
        return true
    }

    /** 数据里最早 / 最晚日期（导出前「将包含的日期范围」用）。 */
    suspend fun dateRange(): Pair<LocalDate?, LocalDate?> {
        val days = db.daySummaryDao().getAll()
        return days.minOfOrNull { it.date }?.let(LocalDate::parse) to
            days.maxOfOrNull { it.date }?.let(LocalDate::parse)
    }
}
