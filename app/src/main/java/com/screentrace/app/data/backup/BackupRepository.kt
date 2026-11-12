package com.screentrace.app.data.backup

import androidx.room.withTransaction
import com.screentrace.app.contract.AppCategory
import com.screentrace.app.data.SessionUnion
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
    private val zone: ZoneId = ZoneId.systemDefault()
) {

    enum class Strategy { OVERWRITE, MERGE }

    data class RestoreResult(
        val added: Int,
        val updated: Int,
        val skipped: Int,
        val settingsRestored: Boolean
    )

    // ---------------------------------------------------------------- 导出

    suspend fun buildFile(appVersion: String, at: LocalDateTime = LocalDateTime.now()): BackupFile =
        BackupFile(
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
            }
        )

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

        val appPlan = BackupMerge.appUsage(
            db.appUsageDao().getAll().map {
                AppUsageDto(it.date, it.pkg, it.name, it.minutes, it.launches, it.category)
            },
            file.appUsage
        )
        insertAppUsage(appPlan.rows)
        report += appPlan.report

        val hourPlan = BackupMerge.hourUsage(
            db.hourUsageDao().getAll().map { HourUsageDto(it.date, it.hour, it.minutes) },
            file.hourUsage
        )
        insertHourUsage(hourPlan.rows)
        report += hourPlan.report

        val pullRows = file.pullLog.map {
            PullLogEntity(pulledAt = it.pulledAt, source = it.source, ok = it.ok, note = it.note)
        }
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
                db.hourUsageDao().insert(HourUsageEntity(date.toString(), hour, minutes))
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
