package com.screentrace.app.data.backup

import androidx.room.withTransaction
import com.screentrace.app.contract.AppCategory
import com.screentrace.app.data.IngestSanity
import com.screentrace.app.data.SessionUnion
import com.screentrace.app.data.parse.AppUsageRow
import com.screentrace.app.data.parse.DayMetrics
import com.screentrace.app.data.db.AppLimitEntity
import com.screentrace.app.data.db.AppUsageEntity
import com.screentrace.app.data.db.DeviceMergeLedgerEntity
import com.screentrace.app.data.db.DaySummaryEntity
import com.screentrace.app.data.db.EventSeq
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
    private val device: suspend () -> com.screentrace.app.data.prefs.DeviceInfo? = { null },
    /**
     * CR-28：恢复/合并写行的来源标识。默认 [SourceIds.UNSPECIFIED]（与测试预置行、
     * 旧调用点同锅——行为与 CR-28 之前完全一致）；**生产构造必须显式传真实 ID**
     * （AppContainer：本机库传本机 `deviceId`、副本库传该副本所属设备 ID）。
     * 备份 schema 6 的「来源＝导出设备」身份规则在批次 C 落地。
     */
    private val sourceDeviceId: suspend () -> String = { com.screentrace.app.data.db.SourceIds.UNSPECIFIED }
) {

    enum class Strategy { OVERWRITE, MERGE }

    data class RestoreResult(
        val added: Int,
        val updated: Int,
        val skipped: Int,
        val settingsRestored: Boolean
    )

    // ---------------------------------------------------------------- 导出

    /** CR-28：一个来源的六表分量（导出/导入的中间形态）。 */
    private data class SourceRows(
        val daySummary: List<DaySummaryDto> = emptyList(),
        val appUsage: List<AppUsageDto> = emptyList(),
        val hourUsage: List<HourUsageDto> = emptyList(),
        val screenSession: List<ScreenSessionDto> = emptyList(),
        val unlockEvent: List<UnlockEventDto> = emptyList(),
        val notificationEvent: List<NotificationEventDto> = emptyList()
    )

    /** 只保留 [dates] 内的行（跨设备合并的防重复过滤；事件/会话按本地时区归属日期）。 */
    private fun SourceRows.allowedIn(dates: Set<String>): SourceRows = SourceRows(
        daySummary = daySummary.filter { it.date in dates },
        appUsage = appUsage.filter { it.date in dates },
        hourUsage = hourUsage.filter { it.date in dates },
        screenSession = screenSession.filter { s ->
            CrossDeviceMerge.datesOfSession(s.startMs, s.endMs, zone).any { it in dates }
        },
        unlockEvent = unlockEvent.filter { CrossDeviceMerge.dateOf(it.occurredAtMs, zone) in dates },
        notificationEvent = notificationEvent.filter {
            CrossDeviceMerge.dateOf(it.occurredAtMs, zone) in dates
        }
    )

    /** CR-28：来源标识 → 备份里的设备条目（`'__legacy__'` 标「历史来源未知」）。 */
    private fun sourceDeviceOf(id: String): com.screentrace.app.data.backup.BackupDevice =
        if (id == com.screentrace.app.data.db.SourceIds.LEGACY) {
            BackupDevice(id, "历史来源未知", "")
        } else {
            BackupDevice(id, "", "")
        }

    /**
     * CR-28：导出身份 = **备份导出设备**（本库的主体来源 `sourceDeviceId()`），
     * 与导入设备无关——A 导出 → B 导入 → B 再导出，A 的数据仍属 A（§5.1 往返不变式）。
     */
    private fun topSourceOf(file: BackupFile): String =
        file.device?.id ?: com.screentrace.app.data.db.SourceIds.LEGACY

    /** CR-28：文件 → [(来源 ID, 该来源六表分量)]；顶层一组 + `sources[]` 每项一组。 */
    private fun sourceGroupsOf(file: BackupFile): List<Pair<String, SourceRows>> {
        val groups = mutableListOf(
            topSourceOf(file) to SourceRows(
                daySummary = file.daySummary,
                appUsage = file.appUsage,
                hourUsage = file.hourUsage,
                screenSession = file.screenSession,
                unlockEvent = file.unlockEvent,
                notificationEvent = file.notificationEvent
            )
        )
        file.sources.forEach { s ->
            groups += (s.device?.id ?: com.screentrace.app.data.db.SourceIds.LEGACY) to SourceRows(
                daySummary = s.daySummary,
                appUsage = s.appUsage,
                hourUsage = s.hourUsage,
                screenSession = s.screenSession,
                unlockEvent = s.unlockEvent,
                notificationEvent = s.notificationEvent
            )
        }
        return groups
    }

    suspend fun buildFile(appVersion: String, at: LocalDateTime = LocalDateTime.now()): BackupFile {
        val info = runCatching { device() }.getOrNull()
        val src = sourceDeviceId()

        val days = db.daySummaryDao().getAll().groupBy { it.sourceDeviceId }
        val apps = db.appUsageDao().getAll().groupBy { it.sourceDeviceId }
        val hours = db.hourUsageDao().getAll().groupBy { it.sourceDeviceId }
        val sessions = db.screenSessionDao().getAll().groupBy { it.sourceDeviceId }
        val unlocks = db.unlockEventDao().getAll().groupBy { it.sourceDeviceId }
        val notifs = db.notificationEventDao().getAll().groupBy { it.sourceDeviceId }

        fun rowsOf(id: String) = SourceRows(
            daySummary = days[id].orEmpty().map {
                DaySummaryDto(it.date, it.screenMin, it.screenUnlockMin, it.unlocks, it.launches, it.longestSessionMin, it.pulledAt)
            },
            appUsage = apps[id].orEmpty().map {
                AppUsageDto(it.date, it.pkg, it.name, it.minutes, it.launches, it.category)
            },
            hourUsage = hours[id].orEmpty().map { HourUsageDto(it.date, it.hour, it.minutes) },
            screenSession = sessions[id].orEmpty().map { ScreenSessionDto(it.startMs, it.endMs, it.kind) },
            unlockEvent = unlocks[id].orEmpty().map { UnlockEventDto(it.occurredAtMs, it.seq) },
            notificationEvent = notifs[id].orEmpty().map {
                NotificationEventDto(it.occurredAtMs, it.type, it.pkg, it.channelId, it.seq)
            }
        )

        val allIds = days.keys + apps.keys + hours.keys + sessions.keys + unlocks.keys + notifs.keys
        val top = rowsOf(src)
        val sources = (allIds - src).sorted().map { id ->
            val r = rowsOf(id)
            BackupSource(
                device = sourceDeviceOf(id),
                daySummary = r.daySummary,
                appUsage = r.appUsage,
                hourUsage = r.hourUsage,
                screenSession = r.screenSession,
                unlockEvent = r.unlockEvent,
                notificationEvent = r.notificationEvent
            )
        }

        return BackupFile(
            schemaVersion = BackupCodec.SCHEMA_VERSION,
            exportedAt = at.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
            appVersion = appVersion,
            settings = currentSettings(),
            daySummary = top.daySummary,
            appUsage = top.appUsage,
            hourUsage = top.hourUsage,
            pullLog = db.pullLogDao().getAll().map { PullLogDto(it.pulledAt, it.source, it.ok, it.note) },
            unlockEvent = top.unlockEvent,
            notificationEvent = top.notificationEvent,
            screenSession = top.screenSession,
            appLimit = db.appLimitDao().getAll().map { AppLimitDto(it.pkg, it.limitMin, it.enabled) },
            // §5.1「顶层必须保留稳定 device.id」：device() 缺失时也要写出导出主体身份
            device = info?.let { BackupDevice(it.id, it.nickname, it.model) } ?: BackupDevice(src, "", ""),
            sources = sources
        )
    }

    suspend fun exportJson(appVersion: String): Pair<String, String> {
        val file = buildFile(appVersion)
        return BackupCodec.fileName(LocalDateTime.now()) to BackupCodec.encode(file)
    }

    /**
     * CR-28 三层兼容：备份含多来源分量时，旧版本导入会丢 `sources[]`。
     * 导出 UI 必须展示此警告（§5.10 / §7-58）；无分量时返回 null（无需提示）。
     */
    fun legacyCompatWarning(file: BackupFile): String? =
        if (file.sources.isEmpty()) {
            null
        } else {
            val names = file.sources.joinToString("、") {
                it.device?.nickname?.takeIf { n -> n.isNotBlank() }
                    ?: it.device?.id ?: "__legacy__"
            }
            "备份包含多设备分量（$names）。旧版本导入会丢失非本机来源数据，仅保留「${
                file.device?.nickname?.takeIf { n -> n.isNotBlank() } ?: file.device?.id ?: "本机"
            }」的部分。"
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

    /**
     * §5.10「导出 CSV 明细」：七张明细表各一个文本（UTF-8 带 BOM，Excel 直接打开不乱码）。
     * CR-28：六张统计/事件表含 `source_device_id` 列，`sources[]` 分量行展平并入（不丢任何来源）。
     */
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
        // CR-28：顶层 + sources[] 展平成 (来源 ID, 行) 列表
        val topId = topSourceOf(file)
        fun <T> flat(pickTop: (BackupFile) -> List<T>, pickSource: (BackupSource) -> List<T>): List<Pair<String, T>> =
            pickTop(file).map { topId to it } + file.sources.flatMap { s ->
                val id = s.device?.id ?: com.screentrace.app.data.db.SourceIds.LEGACY
                pickSource(s).map { id to it }
            }
        val flatDays = flat({ it.daySummary }, { it.daySummary })
        val flatApps = flat({ it.appUsage }, { it.appUsage })
        val flatHours = flat({ it.hourUsage }, { it.hourUsage })
        val flatUnlocks = flat({ it.unlockEvent }, { it.unlockEvent })
        val flatNotifs = flat({ it.notificationEvent }, { it.notificationEvent })
        val flatSessions = flat({ it.screenSession }, { it.screenSession })
        return listOf(
            csv(
                "day_summary",
                listOf("date", "screen_min", "screen_unlock_min", "unlocks", "launches", "longest_session_min", "pulled_at", "source_device_id"),
                flatDays.map { (src, it) ->
                    listOf(it.date, "${it.screenMin}", "${it.screenUnlockMin}", "${it.unlocks}", "${it.launches}", "${it.longestSessionMin}", "${it.pulledAt}", src)
                }
            ),
            csv(
                "app_usage",
                listOf("date", "pkg", "name", "minutes", "launches", "category", "source_device_id"),
                flatApps.map { (src, it) ->
                    listOf(it.date, it.pkg, it.name, "${it.minutes}", "${it.launches}", it.category, src)
                }
            ),
            csv(
                "hour_usage",
                listOf("date", "hour", "minutes", "source_device_id"),
                flatHours.map { (src, it) -> listOf(it.date, "${it.hour}", "${it.minutes}", src) }
            ),
            csv(
                "unlock_event",
                listOf("occurred_at_ms", "seq", "source_device_id"),
                flatUnlocks.map { (src, it) -> listOf("${it.occurredAtMs}", "${it.seq}", src) }
            ),
            csv(
                "notification_event",
                listOf("occurred_at_ms", "type", "pkg", "channel_id", "seq", "source_device_id"),
                flatNotifs.map { (src, it) ->
                    listOf("${it.occurredAtMs}", it.type, it.pkg, it.channelId ?: "", "${it.seq}", src)
                }
            ),
            csv(
                "screen_session",
                listOf("start_ms", "end_ms", "kind", "source_device_id"),
                flatSessions.map { (src, it) -> listOf("${it.startMs}", "${it.endMs}", it.kind, src) }
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
        // CR-28-R1 ⑦：**写入前先归档**——迁移遗留的未归档（''）行归属本库主体来源
        // （副本库＝该副本设备、本机库＝本机）；只动 '' 行、幂等。副本库没有独立的
        // 启动挂点，挂在这里覆盖全部恢复路径。
        runCatching {
            com.screentrace.app.data.SourceArchive.archive(db, sourceDeviceId(), emptySet(), zone)
        }
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

        // CR-28：按来源派生写行（顶层＝导出设备 device.id、sources[] 各归各的；
        // 无 device.id → '__legacy__'，绝不归导入设备）
        var total = 0
        for ((src, rows) in sourceGroupsOf(file)) {
            insertDaySummaries(rows.daySummary, src)
            insertAppUsage(rows.appUsage, src)
            insertHourUsage(rows.hourUsage, src)
            db.unlockEventDao().insertIgnore(
                rows.unlockEvent.map { UnlockEventEntity(it.occurredAtMs, src, it.seq) }
            )
            db.notificationEventDao().insertIgnore(
                rows.notificationEvent.map {
                    NotificationEventEntity(it.occurredAtMs, it.type, it.pkg, it.channelId, src, it.seq)
                }
            )
            db.screenSessionDao().insert(
                rows.screenSession.map { ScreenSessionEntity(it.startMs, it.endMs, it.kind, src) }
            )
            total += rows.daySummary.size + rows.appUsage.size + rows.hourUsage.size +
                rows.unlockEvent.size + rows.notificationEvent.size + rows.screenSession.size
        }
        insertPullLog(file.pullLog)
        db.appLimitDao().upsert(
            file.appLimit.map { AppLimitEntity(it.pkg, it.limitMin, it.enabled) }
        )

        total += file.pullLog.size + file.appLimit.size
        return RestoreResult(added = total, updated = 0, skipped = 0, settingsRestored = false)
    }

    /**
     * CR-28：把**一个来源**的六表分量并入本库——`merge` / `mergeAccumulate` / `sources[]`
     * 的公共核心。
     *
     * - 同来源取大（[BackupMerge]）；跨来源行互不参与对方的合并判定；
     * - **`'__legacy__'` 只增不改**（legacy 只读保护）：本地已有键保留原值，只补本地没有的；
     * - 事件按完整主键 `insertIgnore`（含来源 + `seq`：幂等、同毫秒多条不丢）；
     * - 会话同来源扫线并集（legacy 组不并集、不重算）。
     */
    private suspend fun mergeGroup(src: String, rows: SourceRows): BackupMerge.Report {
        var report = BackupMerge.Report()
        val legacy = src == com.screentrace.app.data.db.SourceIds.LEGACY

        val existingDays = db.daySummaryDao().getAll().filter { it.sourceDeviceId == src }.map {
            DaySummaryDto(it.date, it.screenMin, it.screenUnlockMin, it.unlocks, it.launches, it.longestSessionMin, it.pulledAt)
        }
        val dayPlan = if (legacy) {
            val have = existingDays.map { it.date }.toSet()
            val fresh = rows.daySummary.filterNot { it.date in have }
            BackupMerge.Plan(fresh, BackupMerge.Report(added = fresh.size))
        } else {
            BackupMerge.daySummaries(existingDays, CrossDeviceMerge.dedupeDaySummaries(rows.daySummary))
        }
        insertDaySummaries(dayPlan.rows, src)
        report += dayPlan.report

        // 评审 P1-1：备份的应用/时段数据同样要过自洽校验（IngestSanity 与拉取入库同口径）。
        // 参照物是**该来源合并后的**日聚合（既有行 + 本次胜出行）。
        val sanityDays = (existingDays + dayPlan.rows).associateBy { it.date }.map { (date, d) ->
            LocalDate.parse(date) to DayMetrics(
                screenMin = d.screenMin,
                screenUnlockMin = d.screenUnlockMin,
                unlocks = d.unlocks,
                launches = d.launches,
                longestSessionMin = d.longestSessionMin
            )
        }.toMap()
        val sanityVerdict = IngestSanity.check(
            days = sanityDays,
            appUsage = rows.appUsage.map {
                AppUsageRow(LocalDate.parse(it.date), it.pkg, it.minutes * 60_000L, it.launches)
            },
            hourMinutes = rows.hourUsage
                .groupBy { it.date }
                .mapKeys { LocalDate.parse(it.key) }
                .mapValues { (_, r) -> (0..23).map { h -> r.firstOrNull { it.hour == h }?.minutes ?: 0 } }
        )
        val badDates = sanityVerdict.droppedDates.toSet()
        val safeApp = rows.appUsage.filterNot { LocalDate.parse(it.date) in badDates }
        val safeHour = rows.hourUsage.filterNot { LocalDate.parse(it.date) in badDates }
        if (sanityVerdict.hasDrops) {
            report += BackupMerge.Report(
                skipped = (rows.appUsage.size - safeApp.size) + (rows.hourUsage.size - safeHour.size)
            )
        }

        if (legacy) {
            val haveApp = db.appUsageDao().getAll().filter { it.sourceDeviceId == src }
                .map { it.date to it.pkg }.toSet()
            val newApp = CrossDeviceMerge.dedupeAppUsage(safeApp).filterNot { (it.date to it.pkg) in haveApp }
            insertAppUsage(newApp, src)
            report += BackupMerge.Report(added = newApp.size)
            val haveHour = db.hourUsageDao().getAll().filter { it.sourceDeviceId == src }
                .map { it.date to it.hour }.toSet()
            val newHour = CrossDeviceMerge.dedupeHourUsage(safeHour).filterNot { (it.date to it.hour) in haveHour }
            insertHourUsage(newHour, src)
            report += BackupMerge.Report(added = newHour.size)
        } else {
            val appPlan = BackupMerge.appUsage(
                db.appUsageDao().getAll().filter { it.sourceDeviceId == src }.map {
                    AppUsageDto(it.date, it.pkg, it.name, it.minutes, it.launches, it.category)
                },
                CrossDeviceMerge.dedupeAppUsage(safeApp)
            )
            insertAppUsage(appPlan.rows, src)
            report += appPlan.report

            val hourPlan = BackupMerge.hourUsage(
                db.hourUsageDao().getAll().filter { it.sourceDeviceId == src }
                    .map { HourUsageDto(it.date, it.hour, it.minutes) },
                CrossDeviceMerge.dedupeHourUsage(safeHour)
            )
            insertHourUsage(hourPlan.rows, src)
            report += hourPlan.report
        }

        // 事件：insertIgnore 全部（主键含来源 + 确定性 seq：重复导入幂等、同毫秒多条不丢）
        val unlockRows = rows.unlockEvent.map { UnlockEventEntity(it.occurredAtMs, src, it.seq) }
        db.unlockEventDao().insertIgnore(unlockRows)
        report += BackupMerge.Report(added = unlockRows.size)
        val notifRows = rows.notificationEvent.map {
            NotificationEventEntity(it.occurredAtMs, it.type, it.pkg, it.channelId, src, it.seq)
        }
        db.notificationEventDao().insertIgnore(notifRows)
        report += BackupMerge.Report(added = notifRows.size)

        // 会话：同来源扫线并集；legacy 组只增不并（只读保护）
        if (rows.screenSession.isNotEmpty()) {
            if (legacy) {
                val haveKeys = db.screenSessionDao().getAll().filter { it.sourceDeviceId == src }
                    .map { it.startMs to it.kind }.toSet()
                val newRows = rows.screenSession
                    .map { ScreenSessionEntity(it.startMs, it.endMs, it.kind, src) }
                    .filterNot { (it.startMs to it.kind) in haveKeys }
                db.screenSessionDao().insert(newRows)
                report += BackupMerge.Report(added = newRows.size)
            } else {
                report += mergeSessions(rows.screenSession, src)
            }
        }
        return report
    }

    private suspend fun merge(file: BackupFile): RestoreResult {
        var report = BackupMerge.Report()

        // CR-28：按来源派生逐组并入（顶层＝导出设备、sources[] 各归各的）
        for ((src, rows) in sourceGroupsOf(file)) {
            report += mergeGroup(src, rows)
        }

        // 评审 P2-1：按 (pulledAt, source) 去重——同一台设备的备份反复合并时日志不膨胀，
        // 且别台设备更晚的时间戳不会把「最近成功拉取」顶掉后还留一堆重复行
        val existingPullKeys = db.pullLogDao().getAll().map { it.pulledAt to it.source }.toSet()
        val pullRows = file.pullLog
            .filterNot { (it.pulledAt to it.source) in existingPullKeys }
            .map { PullLogEntity(pulledAt = it.pulledAt, source = it.source, ok = it.ok, note = it.note) }
        pullRows.forEach { db.pullLogDao().insert(it) }
        report += BackupMerge.Report(added = pullRows.size)

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
        val src = sourceDeviceId()
        return db.daySummaryDao().getAll()
            .filter { it.date in dates }
            // CR-28：「本机库的重叠日期」＝**本机来源行**；该日没有本机行时回退
            // legacy 行（历史混合行＝升级前的本机锅）。其他来源的分量行不参与判定
            // （拿 B 的行比 B 的备份会误判「同一台机器」）。
            .groupBy { it.date }
            .mapValues { (_, rows) ->
                val pick = rows.firstOrNull { it.sourceDeviceId == src }
                    ?: rows.firstOrNull { it.sourceDeviceId == com.screentrace.app.data.db.SourceIds.LEGACY }
                    ?: rows.first()
                SameDeviceFingerprint.DayFingerprint.of(
                    DaySummaryDto(
                        pick.date, pick.screenMin, pick.screenUnlockMin, pick.unlocks,
                        pick.launches, pick.longestSessionMin, pick.pulledAt
                    )
                )
            }
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
        // CR-28：「本机该日事件」＝本机来源 ∪ legacy（历史混合）事件；
        // 其他来源的分量事件不参与判定（拿 B 的事件比 B 的备份会误判「同一台机器」）。
        val src = sourceDeviceId()
        val mine: (String) -> Boolean = { it == src || it == com.screentrace.app.data.db.SourceIds.LEGACY }
        val out = mutableMapOf<String, MutableSet<String>>()
        db.notificationEventDao().getAll().filter { mine(it.sourceDeviceId) }.forEach {
            val date = CrossDeviceMerge.dateOf(it.occurredAtMs, zone)
            if (date in dates) {
                out.getOrPut(date) { mutableSetOf() } += SameDeviceFingerprint.DayEventKeys
                    .ofNotification(it.occurredAtMs, it.type, it.pkg)
            }
        }
        db.unlockEventDao().getAll().filter { mine(it.sourceDeviceId) }.forEach {
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
    /**
     * §5.10 CR-27：某来源设备**已合并过**的日期（Room 账本）。
     *
     * 与旧 DataStore 账本的**并集**由调用方（[com.screentrace.app.data.sync.DeviceOperations]）
     * 合成——迁移期间两边都可能有历史，取并集才不会把老账丢掉、重新累加一遍。
     */
    suspend fun mergedDatesOf(deviceId: String): Set<String> =
        db.deviceMergeLedgerDao().datesOf(deviceId).toSet()

    suspend fun mergeAccumulate(
        file: BackupFile,
        allowedDates: Set<String>,
        /**
         * CR-27：来源设备 ID——防重复账本要与数据**同事务**写进 `device_merge_ledger`。
         * 空串 = 不记账（仅供测试/内部重放用）。
         */
        ledgerDeviceId: String = ""
    ): RestoreResult = db.withTransaction {
        var report = BackupMerge.Report()

        // ①②④⑤ CR-28：按**来源分量**写行（累加写入口径废除，跨来源求和只在读侧聚合）。
        // 顶层来源＝备份导出设备身份（旧备份无 device.id 时用账本的目录级来源证据
        // ledgerDeviceId——跨设备合并入口，不算伪造来源）；sources[] 各归各的。
        val legacyId = com.screentrace.app.data.db.SourceIds.LEGACY
        val topSrc = file.device?.id ?: ledgerDeviceId.ifEmpty { sourceDeviceId() }
        val groups = buildList {
            add(
                topSrc to SourceRows(
                    file.daySummary, file.appUsage, file.hourUsage,
                    file.screenSession, file.unlockEvent, file.notificationEvent
                )
            )
            file.sources.forEach { s ->
                add(
                    (s.device?.id ?: legacyId) to SourceRows(
                        s.daySummary, s.appUsage, s.hourUsage,
                        s.screenSession, s.unlockEvent, s.notificationEvent
                    )
                )
            }
        }
        for ((src, rows) in groups) {
            report += mergeGroup(src, rows.allowedIn(allowedDates))
        }

        // ③ 拉取日志：仅追加，按 (pulledAt, source) 去重（与同设备合并同一口径）
        val existingPullKeys = db.pullLogDao().getAll().map { it.pulledAt to it.source }.toSet()
        val pullRows = file.pullLog
            .filterNot { (it.pulledAt to it.source) in existingPullKeys }
            .map { PullLogEntity(pulledAt = it.pulledAt, source = it.source, ok = it.ok, note = it.note) }
        pullRows.forEach { db.pullLogDao().insert(it) }
        report += BackupMerge.Report(added = pullRows.size)

        // ⑥ 应用限额：**完全不写**——限额是本机用户意图，跨设备合并不搬运（§4）。

        // ⑦ 防重复记账（CR-27）：与上面的数据写入**同一个事务**。
        // 旧实现把账本放 DataStore、在 Room 事务**之后**才写，进程在两步之间被杀
        // 就会「数据已加、账没记」，下次合并把同一批日期**再累加一遍**（当天翻倍）。
        if (allowedDates.isNotEmpty() && ledgerDeviceId.isNotEmpty()) {
            db.deviceMergeLedgerDao().insert(
                allowedDates.map { DeviceMergeLedgerEntity(ledgerDeviceId, it) }
            )
        }
        RestoreResult(report.added, report.updated, report.skipped, settingsRestored = false)
    }

    /**
     * §4 / §5.10：会话区间按扫线并集合并，再**重算受影响日期**的日聚合与小时矩阵。
     *
     * 不重算的话会出现「区间表里有 16 小时、day_summary 还是 11 小时」的两套数字，
     * 后续拉取时并集重算又突然跳变。重算结果与既有值取大（§4 合并策略），
     * 保证「合并」不会让任何一天变少。
     */
    private suspend fun mergeSessions(
        backup: List<ScreenSessionDto>,
        sourceOverride: String? = null
    ): BackupMerge.Report {
        val src = sourceOverride ?: sourceDeviceId()
        val incoming = backup.map { ScreenSessionEntity(it.startMs, it.endMs, it.kind, src) }
        val grouped = (db.screenSessionDao().getAll() + incoming).groupBy { it.sourceDeviceId }
        if (grouped.isEmpty()) return BackupMerge.Report()

        var added = 0
        var updated = 0
        val out = mutableListOf<ScreenSessionEntity>()
        for ((groupSource, rows) in grouped) {
            // CR-28：同来源扫线并集（跨来源区间永不合并），输出行保留各自来源
            val screen = SessionUnion.unionEntities(rows, ScreenSessionEntity.KIND_SCREEN)
                .map { it.copy(sourceDeviceId = groupSource) }
            val keyguard = SessionUnion.unionEntities(rows, ScreenSessionEntity.KIND_KEYGUARD)
                .map { it.copy(sourceDeviceId = groupSource) }
            out += screen + keyguard

            // CR-28 legacy 只读保护：'__legacy__' 行不参与重算、不被改写
            if (groupSource == com.screentrace.app.data.db.SourceIds.LEGACY) continue

            val slices = SessionUnion.daySlices(screen, keyguard, zone)
            if (slices.isEmpty()) {
                added += screen.size + keyguard.size
                continue
            }

            val existingDays = db.daySummaryDao()
                .getBetween("0000-00-00", "9999-99-99")
                .filter { it.sourceDeviceId == groupSource }
                .associateBy { it.date }
            slices.forEach { (date, slice) ->
                val current = existingDays[date.toString()]
                val existingUnlock = current?.screenUnlockMin ?: -1
                val existingHours = db.hourUsageDao().getForDate(date.toString(), groupSource)
                    .associate { it.hour to it.minutes }
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
                        pulledAt = current?.pulledAt ?: System.currentTimeMillis(),
                        sourceDeviceId = groupSource
                    )
                )
                mergedHours.forEachIndexed { hour, minutes ->
                    // 评审 P2-2：0 分钟不写行——「有行」即「有记录」（hasDataIn 按行判断），
                    // 写 0 行会把「只恢复了会话、无事件」的日子误判成有记录（假 0 口径相抵）
                    if (minutes > 0) {
                        db.hourUsageDao().insert(HourUsageEntity(date.toString(), hour, minutes, groupSource))
                    }
                }
            }
        }

        // 全表重写：并集结果已经是「所有区间的最小无重叠表示」，先删后插不会丢信息
        db.screenSessionDao().deleteAll()
        db.screenSessionDao().insert(out)

        return BackupMerge.Report(added = added, updated = updated, skipped = 0)
    }

    private suspend fun insertDaySummaries(rows: List<DaySummaryDto>, source: String) {
        rows.forEach {
            db.daySummaryDao().upsert(
                DaySummaryEntity(
                    it.date, it.screenMin, it.screenUnlockMin, it.unlocks,
                    it.launches, it.longestSessionMin, it.pulledAt, source
                )
            )
        }
    }

    private suspend fun insertAppUsage(rows: List<AppUsageDto>, source: String) {
        rows.forEach {
            db.appUsageDao().insert(
                AppUsageEntity(it.date, it.pkg, it.name, it.minutes, it.launches, it.category, source)
            )
        }
    }

    private suspend fun insertHourUsage(rows: List<HourUsageDto>, source: String) {
        rows.forEach { db.hourUsageDao().insert(HourUsageEntity(it.date, it.hour, it.minutes, source)) }
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
