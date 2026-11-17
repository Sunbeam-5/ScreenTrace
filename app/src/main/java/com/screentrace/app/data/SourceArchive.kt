package com.screentrace.app.data

import androidx.room.withTransaction
import com.screentrace.app.data.db.SourceIds
import com.screentrace.app.data.db.ScreenTraceDatabase
import java.time.Instant
import java.time.ZoneId

/**
 * CR-28：来源归档（迁移后**首次启动**做，幂等）。
 *
 * [com.screentrace.app.data.db.MigrationsKt.MIGRATION_6_7] 把六表旧值的
 * `sourceDeviceId` 全置 [SourceIds.UNARCHIVED]（未归档）——迁移是纯 SQL、读不到
 * DataStore 的 deviceId 与合并账本，归属判定只能放在迁移后的启动步骤：
 *
 * - 该日**在合并账本里**（合并过外来数据，历史是混合总量）→ [SourceIds.LEGACY]
 *   （历史来源未知，只读保护，永不归属任何真实设备）；
 * - 该日**不在账本里**（纯本机日）→ [defaultSource]（本机 `deviceId`；副本库传
 *   该副本所属设备的 ID）。
 *
 * **幂等**：只处理 `sourceDeviceId = ''` 的行，跑两遍结果不变；绝不改写已归档行
 * （含 [SourceIds.LEGACY]——只读保护）。
 *
 * 事件 / 会话按**本地时区**归属日期（§2.2 同口径）；会话**跨到**的任一日在账本里
 * 即整段归 [SourceIds.LEGACY]（保守：宁可多标未知，不猜来源）。
 */
object SourceArchive {

    /**
     * @param legacyDates 历史混合日期（`yyyy-MM-dd`，合并账本 Room ⊕ 旧 DataStore 并集）。
     * @param defaultSource 纯本机日的归属（本机 `deviceId` 或副本库所属设备 ID）。
     * @return 实际归档的行数（六表合计）。
     */
    suspend fun archive(
        db: ScreenTraceDatabase,
        defaultSource: String,
        legacyDates: Set<String>,
        zone: ZoneId = ZoneId.systemDefault()
    ): Int = db.withTransaction {
        var moved = 0

        // ① 日期键表：先标 legacy、剩下的归 defaultSource
        for (dates in legacyDates.chunked(400)) {
            moved += db.daySummaryDao().archiveUnarchived(dates, SourceIds.LEGACY)
            moved += db.appUsageDao().archiveUnarchived(dates, SourceIds.LEGACY)
            moved += db.hourUsageDao().archiveUnarchived(dates, SourceIds.LEGACY)
        }
        moved += db.daySummaryDao().archiveRest(defaultSource)
        moved += db.appUsageDao().archiveRest(defaultSource)
        moved += db.hourUsageDao().archiveRest(defaultSource)

        // ② 事件表：按本地时区日期归属，逐日标 legacy（时间窗更新），剩下归 default
        for (date in legacyDates) {
            val (lo, hi) = dayWindow(date, zone) ?: continue
            moved += db.unlockEventDao().archiveOverlapping(lo, hi, SourceIds.LEGACY)
            moved += db.notificationEventDao().archiveOverlapping(lo, hi, SourceIds.LEGACY)
        }
        moved += db.unlockEventDao().archiveRest(defaultSource)
        moved += db.notificationEventDao().archiveRest(defaultSource)

        // ③ 会话表：跨到任一 legacy 日的整段归 legacy（保守），剩下归 default
        for (date in legacyDates) {
            val (lo, hi) = dayWindow(date, zone) ?: continue
            moved += db.screenSessionDao().archiveOverlapping(lo, hi, SourceIds.LEGACY)
        }
        moved += db.screenSessionDao().archiveRest(defaultSource)

        moved
    }

    /** `yyyy-MM-dd` → [lo, hi) 毫秒窗口；非法日期返回 null。 */
    private fun dayWindow(date: String, zone: ZoneId): Pair<Long, Long>? = runCatching {
        val day = java.time.LocalDate.parse(date)
        val lo = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val hi = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        lo to hi
    }.getOrNull()
}
