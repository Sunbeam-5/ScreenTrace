package com.screentrace.app.data.backup

/**
 * §4 / §5.10「合并本地」的合并规则（纯函数，JVM 可测）。
 *
 * 这里只回答「备份里哪些行该写进本地、写多少条」，不碰 Room：
 * 落库由 [BackupImporter] 在单事务里执行，失败整体回滚（§7-13）。
 *
 * 三条容易写错、因此单测钉死的规则：
 * 1. `day_summary` 同日以 **screen_unlock_min 较大**者**整行**保留（不是逐字段取大——
 *    逐字段会把 A 的屏幕时长和 B 的解锁数拼成一行，出现「解锁次数比屏幕时长还离谱」的组合）；
 * 2. `notification_event` 按 `(秒, type, pkg)` 分组后**比条数**，取较大值，
 *    而不是简单按主键去重——同秒两条真实通知被折叠成一条，通知数就永远少一次；
 * 3. `app_limit` 以备份为准**覆盖**（限额是用户意图，不是统计结果，没有「取大」的道理）。
 */
object BackupMerge {

    data class Report(val added: Int = 0, val updated: Int = 0, val skipped: Int = 0) {
        operator fun plus(other: Report) = Report(
            added = added + other.added,
            updated = updated + other.updated,
            skipped = skipped + other.skipped
        )
    }

    data class Plan<T>(val rows: List<T>, val report: Report)

    /** 同日取 `screenUnlockMin` 较大的一行**整体保留**。 */
    fun daySummaries(
        local: List<DaySummaryDto>,
        backup: List<DaySummaryDto>
    ): Plan<DaySummaryDto> {
        val localByDate = local.associateBy { it.date }
        val rows = mutableListOf<DaySummaryDto>()
        var added = 0
        var updated = 0
        var skipped = 0
        backup.forEach { row ->
            val current = localByDate[row.date]
            when {
                current == null -> {
                    rows += row
                    added++
                }
                // §4「取 screen_unlock_min 较大的一行整体保留」；评审 P2-3：平手时
                // 次级比较 screenMin——两台设备 unlock 相同而 screen 差一倍时，
                // 只比 unlock 会保留屏幕时长残缺的那行
                row.screenUnlockMin > current.screenUnlockMin ||
                    (row.screenUnlockMin == current.screenUnlockMin && row.screenMin > current.screenMin) -> {
                    rows += row
                    updated++
                }
                else -> skipped++
            }
        }
        return Plan(rows, Report(added, updated, skipped))
    }

    /** 同 `(date,pkg)` 取 `minutes` 较大值，并保留该较大值那一行的 `name/category`。 */
    fun appUsage(
        local: List<AppUsageDto>,
        backup: List<AppUsageDto>
    ): Plan<AppUsageDto> {
        val localByKey = local.associateBy { it.date to it.pkg }
        val rows = mutableListOf<AppUsageDto>()
        var added = 0
        var updated = 0
        var skipped = 0
        backup.forEach { row ->
            val current = localByKey[row.date to row.pkg]
            when {
                current == null -> {
                    rows += row
                    added++
                }
                row.minutes > current.minutes -> {
                    rows += row
                    updated++
                }
                else -> skipped++
            }
        }
        return Plan(rows, Report(added, updated, skipped))
    }

    /** 同 `(date,hour)` 取较大值。 */
    fun hourUsage(
        local: List<HourUsageDto>,
        backup: List<HourUsageDto>
    ): Plan<HourUsageDto> {
        val localByKey = local.associateBy { it.date to it.hour }
        val rows = mutableListOf<HourUsageDto>()
        var added = 0
        var updated = 0
        var skipped = 0
        backup.forEach { row ->
            val current = localByKey[row.date to row.hour]
            when {
                current == null -> {
                    rows += row
                    added++
                }
                row.minutes > current.minutes -> {
                    rows += row
                    updated++
                }
                else -> skipped++
            }
        }
        return Plan(rows, Report(added, updated, skipped))
    }

    /** `unlock_event` 按主键去重：备份里本地没有的才写。 */
    fun unlockEvents(
        localMs: Set<Long>,
        backup: List<UnlockEventDto>
    ): Plan<UnlockEventDto> {
        val rows = backup.filter { it.occurredAtMs !in localMs }.distinctBy { it.occurredAtMs }
        return Plan(rows, Report(added = rows.size, updated = 0, skipped = backup.size - rows.size))
    }

    /**
     * `notification_event` 秒级分组取较大条数（§2.2 坑 2 / §4）。
     *
     * 做法：按 `(ms/1000, type, pkg)` 分组，比较两侧条数，把「多出来的那几条」写进去。
     * 不去逐条比内容——同一秒内的两条通知在数据库里除了毫秒没有别的区分字段，
     * 按主键去重的话先到先得，反而可能丢掉备份里真实存在的那条。
     */
    fun notificationEvents(
        local: List<NotificationEventDto>,
        backup: List<NotificationEventDto>
    ): Plan<NotificationEventDto> {
        fun key(row: NotificationEventDto) = Triple(row.occurredAtMs / 1000, row.type, row.pkg)
        val localGroups = local.groupBy(::key).mapValues { it.value.size }
        val localKeys = local.map { Triple(it.occurredAtMs, it.type, it.pkg) }.toSet()

        val rows = mutableListOf<NotificationEventDto>()
        var skipped = 0
        backup.groupBy(::key).forEach { (groupKey, backupRows) ->
            val localCount = localGroups[groupKey] ?: 0
            val extra = backupRows.size - localCount
            if (extra <= 0) {
                skipped += backupRows.size
                return@forEach
            }
            // 优先写本地没有的毫秒，避免撞主键后被 IGNORE 吞掉，白白少算一条
            val fresh = backupRows.sortedBy { it.occurredAtMs }
                .filter { Triple(it.occurredAtMs, it.type, it.pkg) !in localKeys }
            rows += fresh.take(extra)
            skipped += backupRows.size - fresh.take(extra).size
        }
        return Plan(rows, Report(added = rows.size, updated = 0, skipped = skipped))
    }

    /** `app_limit`：同 pkg 以备份值覆盖（备份为准），本地独有限额保留。 */
    fun appLimits(
        local: List<AppLimitDto>,
        backup: List<AppLimitDto>
    ): Plan<AppLimitDto> {
        val localByPkg = local.associateBy { it.pkg }
        var added = 0
        var updated = 0
        var skipped = 0
        backup.forEach { row ->
            val current = localByPkg[row.pkg]
            when {
                current == null -> added++
                current != row -> updated++
                else -> skipped++
            }
        }
        // 全部备份行都写（REPLACE 语义），统计只用于结果摘要
        return Plan(backup.distinctBy { it.pkg }, Report(added, updated, skipped))
    }
}
