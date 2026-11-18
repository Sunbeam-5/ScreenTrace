package com.screentrace.app.data

import androidx.room.withTransaction
import com.screentrace.app.data.backup.AppUsageDto
import com.screentrace.app.data.backup.BackupMerge
import com.screentrace.app.data.backup.CrossDeviceMerge
import com.screentrace.app.data.backup.DaySummaryDto
import com.screentrace.app.data.backup.HourUsageDto
import com.screentrace.app.data.backup.NotificationEventDto
import com.screentrace.app.data.backup.ScreenSessionDto
import com.screentrace.app.data.backup.UnlockEventDto
import com.screentrace.app.data.db.AppUsageEntity
import com.screentrace.app.data.db.DaySummaryEntity
import com.screentrace.app.data.db.HourUsageEntity
import com.screentrace.app.data.db.NotificationEventEntity
import com.screentrace.app.data.db.RebuildLogEntity
import com.screentrace.app.data.db.ScreenSessionEntity
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.db.SourceIds
import com.screentrace.app.data.db.UnlockEventEntity
import java.io.File
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * CR-28 批次 D：**历史重建来源**（需求 §4「历史重建」，设计审查 §2.4 四件套）。
 *
 * 把 `'__legacy__'`（历史来源未知）行按各来源**原始备份**重拆成来源分量：
 * - **快照**：重建前把受影响日期的六表全部行存 JSON（审计 + 人工恢复依据）；
 * - **只写有备份实证的行**：备份分量写 `sourceDeviceId = 备份 device.id`；
 *   **禁止按当前设备 ID 猜测历史来源**；
 * - **legacy 只留剩余**：`剩余 = legacy − Σ备份分量`（SUM 口径差减；`longestSessionMin`
 *   是 MAX 语义不差减）——剩余全 0 删 legacy 行，> 0 改写为剩余值；
 *   **Σ分量 > legacy 判「对不上」→ 该日整体不重建**；
 * - **事件/会话只做同键改归属**：与备份行主键完全一致的 legacy 行改写来源
 *   （同 CR-22 信号③的证据强度），不一致的一律留 `'__legacy__'`；
 * - **核对**：SUM 逐日硬断言（重建后合计 == 重建前），MAX / UNION 记录并做 sanity；
 * - **回滚**：核对不过或中途失败 → 数据库事务整体回滚 + 快照可恢复，
 *   `rebuild_log.rolled_back = true`。
 */
class SourceRebuild(
    private val db: ScreenTraceDatabase,
    private val zone: ZoneId = ZoneId.systemDefault()
) {

    /** 一个来源设备的原始备份分量（重建的证据输入）。 */
    data class SourceBackup(
        val deviceId: String,
        val daySummaries: List<DaySummaryDto> = emptyList(),
        val appUsage: List<AppUsageDto> = emptyList(),
        val hourUsage: List<HourUsageDto> = emptyList(),
        val screenSession: List<ScreenSessionDto> = emptyList(),
        val unlockEvent: List<UnlockEventDto> = emptyList(),
        val notificationEvent: List<NotificationEventDto> = emptyList()
    )

    data class DayOutcome(val date: String, val status: String, val legacyRemainingMin: Int)

    data class Report(
        val days: List<DayOutcome>,
        val rowsBefore: Int,
        val rowsAfter: Int,
        val checkSumMin: Int,
        val checkUnionMin: Int,
        val checkMaxMin: Int,
        val snapshotPath: String,
        val rolledBack: Boolean
    )

    // ---------------------------------------------------------------- 快照

    @Serializable
    data class SnapDay(
        val source: String, val date: String, val screenMin: Int, val screenUnlockMin: Int,
        val unlocks: Int, val launches: Int, val longestSessionMin: Int, val pulledAt: Long
    )

    @Serializable
    data class SnapApp(
        val source: String, val date: String, val pkg: String, val name: String,
        val minutes: Int, val launches: Int, val category: String
    )

    @Serializable
    data class SnapHour(val source: String, val date: String, val hour: Int, val minutes: Int)

    @Serializable
    data class SnapUnlock(val source: String, val occurredAtMs: Long, val seq: Int)

    @Serializable
    data class SnapNotif(
        val source: String, val occurredAtMs: Long, val type: String, val pkg: String,
        val channelId: String?, val seq: Int
    )

    @Serializable
    data class SnapSession(val source: String, val startMs: Long, val endMs: Long, val kind: String)

    @Serializable
    data class Snapshot(
        val createdAtMs: Long,
        val dates: List<String>,
        val days: List<SnapDay>,
        val apps: List<SnapApp>,
        val hours: List<SnapHour>,
        val unlocks: List<SnapUnlock>,
        val notifs: List<SnapNotif>,
        val sessions: List<SnapSession>
    )

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** 受影响日期的六表全部行（含全部来源）→ 快照对象。 */
    private suspend fun snapshotOf(dates: Set<String>): Snapshot {
        val inDates: (String) -> Boolean = { it in dates }
        val dayStart = dates.minOrNull()?.let { java.time.LocalDate.parse(it).atStartOfDay(zone).toInstant().toEpochMilli() }
        val dayEnd = dates.maxOrNull()?.let {
            java.time.LocalDate.parse(it).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        }
        fun inWindow(ms: Long) = dayStart != null && dayEnd != null && ms >= dayStart && ms < dayEnd

        return Snapshot(
            createdAtMs = System.currentTimeMillis(),
            dates = dates.sorted(),
            days = db.daySummaryDao().getAll().filter { inDates(it.date) }.map {
                SnapDay(it.sourceDeviceId, it.date, it.screenMin, it.screenUnlockMin, it.unlocks, it.launches, it.longestSessionMin, it.pulledAt)
            },
            apps = db.appUsageDao().getAll().filter { inDates(it.date) }.map {
                SnapApp(it.sourceDeviceId, it.date, it.pkg, it.name, it.minutes, it.launches, it.category)
            },
            hours = db.hourUsageDao().getAll().filter { inDates(it.date) }.map {
                SnapHour(it.sourceDeviceId, it.date, it.hour, it.minutes)
            },
            unlocks = db.unlockEventDao().getAll().filter { inWindow(it.occurredAtMs) }.map {
                SnapUnlock(it.sourceDeviceId, it.occurredAtMs, it.seq)
            },
            notifs = db.notificationEventDao().getAll().filter { inWindow(it.occurredAtMs) }.map {
                SnapNotif(it.sourceDeviceId, it.occurredAtMs, it.type, it.pkg, it.channelId, it.seq)
            },
            sessions = db.screenSessionDao().getAll().filter {
                CrossDeviceMerge.datesOfSession(it.startMs, it.endMs, zone).any(inDates)
            }.map {
                SnapSession(it.sourceDeviceId, it.startMs, it.endMs, it.kind)
            }
        )
    }

    /** 按快照恢复受影响日期的六表全部行（删现插回）；返回恢复行数。 */
    suspend fun recover(snapshot: Snapshot): Int = db.withTransaction {
        val dates = snapshot.dates.toSet()
        val dayStart = java.time.LocalDate.parse(snapshot.dates.min()!!).atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = java.time.LocalDate.parse(snapshot.dates.max()!!).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        db.daySummaryDao().getAll().filter { it.date in dates }.forEach {
            db.openHelper.writableDatabase.execSQL(
                "DELETE FROM day_summary WHERE date = ? AND sourceDeviceId = ?", arrayOf(it.date, it.sourceDeviceId)
            )
        }
        db.appUsageDao().getAll().filter { it.date in dates }.forEach {
            db.openHelper.writableDatabase.execSQL(
                "DELETE FROM app_usage WHERE date = ? AND pkg = ? AND sourceDeviceId = ?",
                arrayOf(it.date, it.pkg, it.sourceDeviceId)
            )
        }
        db.hourUsageDao().getAll().filter { it.date in dates }.forEach {
            db.openHelper.writableDatabase.execSQL(
                "DELETE FROM hour_usage WHERE date = ? AND hour = ? AND sourceDeviceId = ?",
                arrayOf(it.date, it.hour, it.sourceDeviceId)
            )
        }
        db.unlockEventDao().getAll().filter { it.occurredAtMs in dayStart until dayEnd }.forEach {
            db.openHelper.writableDatabase.execSQL(
                "DELETE FROM unlock_event WHERE occurredAtMs = ? AND sourceDeviceId = ? AND seq = ?",
                arrayOf(it.occurredAtMs, it.sourceDeviceId, it.seq)
            )
        }
        db.notificationEventDao().getAll().filter { it.occurredAtMs in dayStart until dayEnd }.forEach {
            db.openHelper.writableDatabase.execSQL(
                "DELETE FROM notification_event WHERE occurredAtMs = ? AND type = ? AND pkg = ? AND sourceDeviceId = ? AND seq = ?",
                arrayOf(it.occurredAtMs, it.type, it.pkg, it.sourceDeviceId, it.seq)
            )
        }
        db.screenSessionDao().getAll().filter {
            CrossDeviceMerge.datesOfSession(it.startMs, it.endMs, zone).any { d -> d in dates }
        }.forEach {
            db.openHelper.writableDatabase.execSQL(
                "DELETE FROM screen_session WHERE startMs = ? AND kind = ? AND sourceDeviceId = ?",
                arrayOf(it.startMs, it.kind, it.sourceDeviceId)
            )
        }

        snapshot.days.forEach {
            db.daySummaryDao().insert(DaySummaryEntity(it.date, it.screenMin, it.screenUnlockMin, it.unlocks, it.launches, it.longestSessionMin, it.pulledAt, it.source))
        }
        snapshot.apps.forEach {
            db.appUsageDao().insert(AppUsageEntity(it.date, it.pkg, it.name, it.minutes, it.launches, it.category, it.source))
        }
        snapshot.hours.forEach {
            db.hourUsageDao().insert(HourUsageEntity(it.date, it.hour, it.minutes, it.source))
        }
        db.unlockEventDao().insertIgnore(snapshot.unlocks.map { u -> UnlockEventEntity(u.occurredAtMs, u.source, u.seq) })
        db.notificationEventDao().insertIgnore(snapshot.notifs.map { n ->
            NotificationEventEntity(n.occurredAtMs, n.type, n.pkg, n.channelId, n.source, n.seq)
        })
        db.screenSessionDao().insert(snapshot.sessions.map { s -> ScreenSessionEntity(s.startMs, s.endMs, s.kind, s.source) })

        snapshot.days.size + snapshot.apps.size + snapshot.hours.size +
            snapshot.unlocks.size + snapshot.notifs.size + snapshot.sessions.size
    }

    // ---------------------------------------------------------------- 重建

    suspend fun rebuild(
        targets: Set<String>,
        sources: List<SourceBackup>,
        snapshotDir: File,
        nowMs: Long = System.currentTimeMillis()
    ): Report {
        val dates = targets.sorted().toSet()
        val snapshot = snapshotOf(dates)
        snapshotDir.mkdirs()
        val snapFile = File(snapshotDir, "rebuild_snapshot_$nowMs.json")
        snapFile.writeText(json.encodeToString(Snapshot.serializer(), snapshot), Charsets.UTF_8)

        val rowsBefore = snapshot.days.size + snapshot.apps.size + snapshot.hours.size +
            snapshot.unlocks.size + snapshot.notifs.size + snapshot.sessions.size

        val outcomes = mutableListOf<DayOutcome>()
        var checkSum = 0
        var checkUnion = 0
        var checkMax = 0
        var rolledBack = false

        try {
            db.withTransaction {
                for (date in dates) {
                    outcomes += rebuildDay(date, sources)
                }
                // 核对：逐日 SUM 硬断言已在 rebuildDay；这里做批级合计 sanity（逐日取，不夹带无关日）
                val perDay = dates.map { db.daySummaryDao().sumRange(it, it) }
                checkSum = perDay.sumOf { rows -> rows.sumOf { it.screenMin } }
                checkMax = perDay.mapNotNull { rows -> rows.maxOfOrNull { it.longestSessionMin } }.maxOrNull() ?: 0
                checkUnion = dates.sumOf {
                    SessionUnion.occupiedMinutes(db.screenSessionDao().getAll(), java.time.LocalDate.parse(it), zone)
                }
                val beforeSum = snapshot.days.sumOf { it.screenMin }
                check(checkSum == beforeSum) { "SUM 核对不过：重建后 $checkSum != 重建前 $beforeSum" }
                check(checkUnion <= checkSum) { "UNION 核对不过：$checkUnion > $checkSum" }
                check(checkMax <= checkSum) { "MAX 核对不过：$checkMax > $checkSum" }
            }
        } catch (e: Throwable) {
            rolledBack = true
            // 事务已回滚；快照保留供人工核对/恢复
            runCatching {
                db.rebuildLogDao().insert(
                    RebuildLogEntity(
                        dateRangeStart = dates.first(), dateRangeEnd = dates.last(),
                        snapshotPath = snapFile.absolutePath,
                        touchedSources = sources.map { it.deviceId }.distinct().sorted().joinToString(","),
                        rowsBefore = rowsBefore, rowsAfter = rowsBefore,
                        checkSumMin = 0, checkUnionMin = 0, checkMaxMin = 0,
                        rolledBack = true, createdAtMs = nowMs
                    )
                )
            }
            return Report(outcomes, rowsBefore, rowsBefore, 0, 0, 0, snapFile.absolutePath, rolledBack = true)
        }

        val rowsAfter = db.daySummaryDao().getAll().count { it.date in dates } +
            db.appUsageDao().getAll().count { it.date in dates } +
            db.hourUsageDao().getAll().count { it.date in dates } +
            db.unlockEventDao().getAll().count { CrossDeviceMerge.dateOf(it.occurredAtMs, zone).toString() in dates } +
            db.notificationEventDao().getAll().count { CrossDeviceMerge.dateOf(it.occurredAtMs, zone).toString() in dates } +
            db.screenSessionDao().getAll().count { CrossDeviceMerge.datesOfSession(it.startMs, it.endMs, zone).any { d -> d in dates } }

        db.rebuildLogDao().insert(
            RebuildLogEntity(
                dateRangeStart = dates.first(), dateRangeEnd = dates.last(),
                snapshotPath = snapFile.absolutePath,
                touchedSources = sources.map { it.deviceId }.distinct().sorted().joinToString(","),
                rowsBefore = rowsBefore, rowsAfter = rowsAfter,
                checkSumMin = checkSum, checkUnionMin = checkUnion, checkMaxMin = checkMax,
                rolledBack = false, createdAtMs = nowMs
            )
        )
        return Report(outcomes, rowsBefore, rowsAfter, checkSum, checkUnion, checkMax, snapFile.absolutePath, rolledBack)
    }

    /**
     * 单日重拆：差减 legacy 日/应用/时段行 + 备份分量同源取大写入 + 事件/会话同键改归属。
     * **SUM 硬断言**：拆分后该日全来源合计 == 拆分前（`check` 不过抛异常 → 整体回滚）。
     */
    private suspend fun rebuildDay(date: String, sources: List<SourceBackup>): DayOutcome {
        val legacyRow = db.daySummaryDao().get(date, SourceIds.LEGACY)
            ?: return DayOutcome(date, "no_legacy", 0)

        // 每个来源内部去重（同来源同日多行取主指标最大）；**跨来源不去重**——
        // 不同来源的同日行是不同分量，去重会少算 Σ（真机前夜踩过：SUM 核对必炸）
        val backupDays = sources.flatMap { s ->
            CrossDeviceMerge.dedupeDaySummaries(s.daySummaries.filter { it.date == date })
        }
        val sumBackup = backupDays.sumOf { it.screenMin }
        // CR-28-R1 P1-3：**逐指标差减校验**——任一可减指标 Σ备份 > legacy 即「对不上」，
        // 整日不重建（不能 coerceAtLeast(0) 截断历史）；写入与差减同用去重后数据。
        val backupApps = sources.flatMap { s -> CrossDeviceMerge.dedupeAppUsage(s.appUsage.filter { it.date == date }) }
            .groupBy { it.pkg }.mapValues { (_, rows) -> rows.sumOf { it.minutes } }
        val backupHours = sources.flatMap { s -> CrossDeviceMerge.dedupeHourUsage(s.hourUsage.filter { it.date == date }) }
            .groupBy { it.hour }.mapValues { (_, rows) -> rows.sumOf { it.minutes } }
        val overDrawn =
            backupDays.sumOf { it.screenUnlockMin } > legacyRow.screenUnlockMin ||
                backupDays.sumOf { it.unlocks } > legacyRow.unlocks ||
                backupDays.sumOf { it.launches } > legacyRow.launches ||
                db.appUsageDao().getAll().any { a ->
                    a.date == date && a.sourceDeviceId == SourceIds.LEGACY &&
                        (backupApps[a.pkg] ?: 0) > a.minutes
                } ||
                db.hourUsageDao().getAll().any { h ->
                    h.date == date && h.sourceDeviceId == SourceIds.LEGACY &&
                        (backupHours[h.hour] ?: 0) > h.minutes
                }
        if (sumBackup > legacyRow.screenMin || overDrawn) {
            // 备份与总量对不上：该日整体不重建（不猜、不丢）
            return DayOutcome(date, "mismatch_skipped", legacyRow.screenMin)
        }
        val beforeSum = db.daySummaryDao().getAll().filter { it.date == date }.sumOf { it.screenMin }
        val beforeUnlocks = db.daySummaryDao().getAll().filter { it.date == date }.sumOf { it.unlocks }
        val beforeLaunches = db.daySummaryDao().getAll().filter { it.date == date }.sumOf { it.launches }
        val beforeUnlockMin = db.daySummaryDao().getAll().filter { it.date == date }.sumOf { it.screenUnlockMin }
        val beforeAppMinutes = db.appUsageDao().getAll().filter { it.date == date }.sumOf { it.minutes }
        val beforeHourMinutes = db.hourUsageDao().getAll().filter { it.date == date }.sumOf { it.minutes }

        // ① 备份日分量：同来源取大写入
        for (s in sources) {
            val row = s.daySummaries.firstOrNull { it.date == date } ?: continue
            val existing = db.daySummaryDao().get(date, s.deviceId)
            val winner = BackupMerge.daySummaries(listOfNotNull(existing?.toDto()), listOf(row)).rows.singleOrNull() ?: row
            db.daySummaryDao().upsert(
                DaySummaryEntity(date, winner.screenMin, winner.screenUnlockMin, winner.unlocks, winner.launches, winner.longestSessionMin, winner.pulledAt, s.deviceId)
            )
        }

        // ② legacy 日行差减（SUM 口径；longest 是 MAX 语义不差减）
        val remainScreen = legacyRow.screenMin - sumBackup
        val remainUnlockMin = legacyRow.screenUnlockMin - backupDays.sumOf { it.screenUnlockMin }
        val remainUnlocks = legacyRow.unlocks - backupDays.sumOf { it.unlocks }
        val remainLaunches = legacyRow.launches - backupDays.sumOf { it.launches }
        if (remainScreen <= 0 && remainUnlockMin <= 0 && remainUnlocks <= 0 && remainLaunches <= 0) {
            db.openHelper.writableDatabase.execSQL(
                "DELETE FROM day_summary WHERE date = ? AND sourceDeviceId = ?",
                arrayOf(date, SourceIds.LEGACY)
            )
        } else {
            db.daySummaryDao().upsert(
                DaySummaryEntity(
                    date, remainScreen.coerceAtLeast(0), remainUnlockMin.coerceAtLeast(0),
                    remainUnlocks.coerceAtLeast(0), remainLaunches.coerceAtLeast(0),
                    legacyRow.longestSessionMin, legacyRow.pulledAt, SourceIds.LEGACY
                )
            )
        }

        // ③ 应用 / 时段分量：同来源取大写入 + legacy 差减
        for (s in sources) {
            for (row in CrossDeviceMerge.dedupeAppUsage(s.appUsage.filter { it.date == date })) {
                val existing = db.appUsageDao().get(date, row.pkg, s.deviceId)
                val winner = BackupMerge.appUsage(listOfNotNull(existing?.toDto()), listOf(row)).rows.singleOrNull() ?: row
                db.appUsageDao().insert(AppUsageEntity(date, row.pkg, winner.name, winner.minutes, winner.launches, winner.category, s.deviceId))
            }
            for (row in CrossDeviceMerge.dedupeHourUsage(s.hourUsage.filter { it.date == date })) {
                val existing = db.hourUsageDao().getForDate(date, s.deviceId).firstOrNull { it.hour == row.hour }
                val winner = BackupMerge.hourUsage(listOfNotNull(existing?.toDto()), listOf(row)).rows.singleOrNull() ?: row
                db.hourUsageDao().insert(HourUsageEntity(date, row.hour, winner.minutes, s.deviceId))
            }
        }
        // legacy 应用行差减（CR-28-R1 P1-3：sumIn 用**去重后**数据，与写入同源）
        db.appUsageDao().getAll().filter { it.date == date && it.sourceDeviceId == SourceIds.LEGACY }.forEach { legacyApp ->
            val remain = legacyApp.minutes - (backupApps[legacyApp.pkg] ?: 0)
            if (remain <= 0) {
                db.openHelper.writableDatabase.execSQL(
                    "DELETE FROM app_usage WHERE date = ? AND pkg = ? AND sourceDeviceId = ?",
                    arrayOf(date, legacyApp.pkg, SourceIds.LEGACY)
                )
            } else {
                db.appUsageDao().insert(
                    AppUsageEntity(date, legacyApp.pkg, legacyApp.name, remain, legacyApp.launches, legacyApp.category, SourceIds.LEGACY)
                )
            }
        }
        db.hourUsageDao().getAll().filter { it.date == date && it.sourceDeviceId == SourceIds.LEGACY }.forEach { legacyHour ->
            val remain = legacyHour.minutes - (backupHours[legacyHour.hour] ?: 0)
            if (remain <= 0) {
                db.openHelper.writableDatabase.execSQL(
                    "DELETE FROM hour_usage WHERE date = ? AND hour = ? AND sourceDeviceId = ?",
                    arrayOf(date, legacyHour.hour, SourceIds.LEGACY)
                )
            } else {
                db.hourUsageDao().insert(HourUsageEntity(date, legacyHour.hour, remain, SourceIds.LEGACY))
            }
        }

        // ④ 事件 / 会话：同键改归属（CR-28-R1 P1-2：**完整主键**（含 seq）匹配才改；
        // 目标已有同完整主键时**保留 legacy 行不动**——先删后插会被 INSERT OR IGNORE
        // 吞掉、把归属证据弄丢）
        for (s in sources) {
            val unlockKeys = s.unlockEvent.map { it.occurredAtMs to it.seq }.toSet()
            db.unlockEventDao().getAll().filter {
                it.sourceDeviceId == SourceIds.LEGACY &&
                    (it.occurredAtMs to it.seq) in unlockKeys &&
                    CrossDeviceMerge.dateOf(it.occurredAtMs, zone).toString() == date
            }.forEach { row ->
                val exists = db.unlockEventDao().getAll().any {
                    it.sourceDeviceId == s.deviceId && it.occurredAtMs == row.occurredAtMs && it.seq == row.seq
                }
                if (!exists) {
                    db.openHelper.writableDatabase.execSQL(
                        "DELETE FROM unlock_event WHERE occurredAtMs = ? AND sourceDeviceId = ? AND seq = ?",
                        arrayOf(row.occurredAtMs, row.sourceDeviceId, row.seq)
                    )
                    db.unlockEventDao().insertIgnore(UnlockEventEntity(row.occurredAtMs, s.deviceId, row.seq))
                }
            }
            val notifKeys = s.notificationEvent.map { Triple(it.occurredAtMs, it.type, it.pkg) to it.seq }.toSet()
            db.notificationEventDao().getAll().filter {
                it.sourceDeviceId == SourceIds.LEGACY &&
                    (Triple(it.occurredAtMs, it.type, it.pkg) to it.seq) in notifKeys &&
                    CrossDeviceMerge.dateOf(it.occurredAtMs, zone).toString() == date
            }.forEach { row ->
                val exists = db.notificationEventDao().getAll().any {
                    it.sourceDeviceId == s.deviceId && it.occurredAtMs == row.occurredAtMs &&
                        it.type == row.type && it.pkg == row.pkg && it.seq == row.seq
                }
                if (!exists) {
                    db.openHelper.writableDatabase.execSQL(
                        "DELETE FROM notification_event WHERE occurredAtMs = ? AND type = ? AND pkg = ? AND sourceDeviceId = ? AND seq = ?",
                        arrayOf(row.occurredAtMs, row.type, row.pkg, row.sourceDeviceId, row.seq)
                    )
                    db.notificationEventDao().insertIgnore(
                        NotificationEventEntity(row.occurredAtMs, row.type, row.pkg, row.channelId, s.deviceId, row.seq)
                    )
                }
            }
            val sessionKeys = s.screenSession.map { Triple(it.startMs, it.endMs, it.kind) }.toSet()
            db.screenSessionDao().getAll().filter {
                it.sourceDeviceId == SourceIds.LEGACY &&
                    Triple(it.startMs, it.endMs, it.kind) in sessionKeys &&
                    CrossDeviceMerge.datesOfSession(it.startMs, it.endMs, zone).any { d -> d == date }
            }.forEach { row ->
                val exists = db.screenSessionDao().getAll().any {
                    it.sourceDeviceId == s.deviceId && it.startMs == row.startMs &&
                        it.kind == row.kind && it.endMs == row.endMs
                }
                if (!exists) {
                    db.openHelper.writableDatabase.execSQL(
                        "DELETE FROM screen_session WHERE startMs = ? AND kind = ? AND sourceDeviceId = ?",
                        arrayOf(row.startMs, row.kind, row.sourceDeviceId)
                    )
                    db.screenSessionDao().insertIgnoreIfAbsent(row.copy(sourceDeviceId = s.deviceId))
                }
            }
        }

        // ⑤ 单日**全部 SUM 指标**硬断言（CR-28-R1 P1-3：不止 screenMin——
        // screenUnlockMin/unlocks/launches/app.minutes/hour.minutes 都不得凭空增减）
        val afterRows = db.daySummaryDao().getAll().filter { it.date == date }
        val afterSum = afterRows.sumOf { it.screenMin }
        val afterUnlocks = afterRows.sumOf { it.unlocks }
        val afterLaunches = afterRows.sumOf { it.launches }
        val afterUnlockMin = afterRows.sumOf { it.screenUnlockMin }
        val afterAppMinutes = db.appUsageDao().getAll().filter { it.date == date }.sumOf { it.minutes }
        val afterHourMinutes = db.hourUsageDao().getAll().filter { it.date == date }.sumOf { it.minutes }
        check(afterSum == beforeSum) { "SUM 核对不过（$date screenMin）：$afterSum != $beforeSum" }
        check(afterUnlockMin == beforeUnlockMin) { "SUM 核对不过（$date screenUnlockMin）：$afterUnlockMin != $beforeUnlockMin" }
        check(afterUnlocks == beforeUnlocks) { "SUM 核对不过（$date unlocks）：$afterUnlocks != $beforeUnlocks" }
        check(afterLaunches == beforeLaunches) { "SUM 核对不过（$date launches）：$afterLaunches != $beforeLaunches" }
        check(afterAppMinutes == beforeAppMinutes) { "SUM 核对不过（$date app minutes）：$afterAppMinutes != $beforeAppMinutes" }
        check(afterHourMinutes == beforeHourMinutes) { "SUM 核对不过（$date hour minutes）：$afterHourMinutes != $beforeHourMinutes" }

        val remaining = db.daySummaryDao().get(date, SourceIds.LEGACY)?.screenMin ?: 0
        return DayOutcome(date, if (remaining > 0) "rebuilt_with_remainder" else "rebuilt", remaining)
    }

    private suspend fun com.screentrace.app.data.db.ScreenSessionDao.insertIgnoreIfAbsent(row: ScreenSessionEntity) {
        val exists = overlapping(row.startMs, row.endMs, row.sourceDeviceId).any {
            it.startMs == row.startMs && it.kind == row.kind && it.endMs == row.endMs
        }
        if (!exists) insert(listOf(row))
    }

    // ---------------------------------------------------- 超 24h 脏行清洗（批次 F）

    companion object {
        /** 一天最多 1440 分钟——超过即旧「累加合并」撑爆的脏行（真机 Redmi 11-10/11/12）。 */
        const val MAX_DAY_MIN = 1440
    }

    /**
     * CR-28 批次 F：**超 24h 脏行清洗**——`day_summary.screenMin > 1440` 的历史行按
     * §4「会话表是日聚合的唯一重算输入」以当日**会话并集重算**校正（重算值天然 ≤ 1440）；
     * `unlocks` 以事件表当日计数校正、`launches` 无实证保留原值；**只在重算值更小时
     * 改写**（收紧不放松）。快照 / 核对 / 回滚 / `rebuild_log` 沿用 [rebuild] 四件套。
     * 幂等：无脏行时零改动、不写 `rebuild_log`。
     */
    suspend fun rebuildOversizedDays(
        snapshotDir: File,
        nowMs: Long = System.currentTimeMillis()
    ): Report {
        val oversized = db.daySummaryDao().getAll().filter { it.screenMin > MAX_DAY_MIN }
        if (oversized.isEmpty()) {
            return Report(emptyList(), 0, 0, 0, 0, 0, "", rolledBack = false)
        }
        val dates = oversized.map { it.date }.toSet()
        val snapshot = snapshotOf(dates)
        snapshotDir.mkdirs()
        val snapFile = File(snapshotDir, "rebuild_oversized_$nowMs.json")
        snapFile.writeText(json.encodeToString(Snapshot.serializer(), snapshot), Charsets.UTF_8)
        val rowsBefore = snapshot.days.size + snapshot.apps.size + snapshot.hours.size +
            snapshot.unlocks.size + snapshot.notifs.size + snapshot.sessions.size

        val outcomes = mutableListOf<DayOutcome>()
        var checkSum = 0
        var checkUnion = 0
        var checkMax = 0
        try {
            db.withTransaction {
                for (row in oversized) {
                    val day = java.time.LocalDate.parse(row.date)
                    val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
                    val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    // CR-28-R1 P0-1：会话与事件都只取**当前来源**——跨来源混算会把
                    // 别的设备的会话并集写进本来源行（来源污染 + 跨来源 SUM 重复）。
                    val stored = db.screenSessionDao().getAll().filter {
                        it.sourceDeviceId == row.sourceDeviceId &&
                            CrossDeviceMerge.datesOfSession(it.startMs, it.endMs, zone).any { d -> d == row.date }
                    }
                    val slice = SessionUnion.daySlices(stored, stored, zone)[day]
                    if (slice == null) {
                        // CR-28-R1 P0-2：**无会话实证不改写**——缺证据不能把历史时长清零
                        outcomes += DayOutcome(row.date, "oversized_no_evidence", 0)
                        continue
                    }
                    val screen = slice.screenMin
                    val unlockMin = slice.unlockMin
                    val longest = slice.longestMin
                    val unlocks = db.unlockEventDao().msBetween(dayStart, dayEnd - 1, row.sourceDeviceId).size

                    // 收紧原则（时长）：只在重算值更小时改写（超 24h 行必被校正；防御未来误用）。
                    // unlocks 是事件表可证的**精确计数**、直接写实证值（不是「越小越好」）。
                    val newScreen = minOf(row.screenMin, screen)
                    val newUnlockMin = minOf(row.screenUnlockMin, unlockMin)
                    val newLongest = minOf(row.longestSessionMin, longest)
                    val newUnlocks = unlocks
                    val corrected = newScreen < row.screenMin
                    db.daySummaryDao().upsert(
                        row.copy(
                            screenMin = newScreen,
                            screenUnlockMin = newUnlockMin,
                            longestSessionMin = newLongest,
                            unlocks = newUnlocks
                        )
                    )
                    outcomes += DayOutcome(row.date, if (corrected) "oversized_corrected" else "oversized_kept", 0)
                }

                // 核对：目标行键集合不变（清洗不得增删行）；被校正的行全部 ≤ 1440
                // （no_evidence 行保留原值、豁免此条）
                val after = db.daySummaryDao().getAll().filter { it.date in dates }
                check(after.size == snapshot.days.size) { "清洗不得增删行（$dates）" }
                val correctedKeys = outcomes.filter { it.status == "oversized_corrected" }
                    .map { it.date }.toSet()
                for (row in after) {
                    if (row.date in correctedKeys) {
                        check(row.screenMin <= MAX_DAY_MIN) { "清洗后仍超 24h（${row.date}）" }
                    }
                }
                checkSum = after.sumOf { it.screenMin }
                checkMax = after.maxOf { it.longestSessionMin }
                checkUnion = dates.sumOf {
                    SessionUnion.occupiedMinutes(db.screenSessionDao().getAll(), java.time.LocalDate.parse(it), zone)
                }
                check(checkUnion <= checkSum) { "UNION 核对不过：$checkUnion > $checkSum" }
            }
        } catch (e: Throwable) {
            runCatching {
                db.rebuildLogDao().insert(
                    RebuildLogEntity(
                        dateRangeStart = dates.min(), dateRangeEnd = dates.max(),
                        snapshotPath = snapFile.absolutePath, touchedSources = "session-recompute",
                        rowsBefore = rowsBefore, rowsAfter = rowsBefore,
                        checkSumMin = 0, checkUnionMin = 0, checkMaxMin = 0,
                        rolledBack = true, createdAtMs = nowMs
                    )
                )
            }
            return Report(outcomes, rowsBefore, rowsBefore, 0, 0, 0, snapFile.absolutePath, rolledBack = true)
        }

        db.rebuildLogDao().insert(
            RebuildLogEntity(
                dateRangeStart = dates.min(), dateRangeEnd = dates.max(),
                snapshotPath = snapFile.absolutePath, touchedSources = "session-recompute",
                rowsBefore = rowsBefore, rowsAfter = rowsBefore,
                checkSumMin = checkSum, checkUnionMin = checkUnion, checkMaxMin = checkMax,
                rolledBack = false, createdAtMs = nowMs
            )
        )
        return Report(outcomes, rowsBefore, rowsBefore, checkSum, checkUnion, checkMax, snapFile.absolutePath, rolledBack = false)
    }

    private fun DaySummaryEntity.toDto() = DaySummaryDto(date, screenMin, screenUnlockMin, unlocks, launches, longestSessionMin, pulledAt)
    private fun AppUsageEntity.toDto() = AppUsageDto(date, pkg, name, minutes, launches, category)
    private fun HourUsageEntity.toDto() = HourUsageDto(date, hour, minutes)
}
