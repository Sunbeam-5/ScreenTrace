package com.screentrace.app.data.backup

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * §4 CR-21：跨设备**累加**合并的口径（纯函数，JVM 可测）。
 *
 * ## 为什么不能复用 [BackupMerge]
 *
 * 仓库里已有的 [BackupMerge] 是「**同一台设备**重拉 / 恢复」的口径：同日取较大值，
 * 目的是「同一份事实被反复导入时不能翻倍」。CR-21 的「合并到本机」是**两台不同设备**
 * 的数据汇合，口径必须是**累加**——两台设备当天的使用量本来就应该相加。
 *
 * 两套口径的差别不是参数，而是语义，因此必须是两套明确实现（§6 红线）：
 * 一旦复用，「同日取较大」会把旧机数据整段丢掉（用户看到的现象是"合并不起作用"），
 * 「累加」复用到同设备恢复上则会让重复导入翻倍。
 *
 * 逐项口径（与需求文档 §4「跨设备累加合并」一致）：
 * - `day_summary`：同日 `screen_min / screen_unlock_min / unlocks / launches` **相加**；
 *   `longest_session_min` 取较大（它是 max 型指标，相加没有意义）；`pulled_at` 取较晚。
 * - `app_usage`：同 `(date,pkg)` 的 `minutes / launches` **相加**；`name / category`
 *   以本机为准（本机没有这个包时用备份里的）。
 * - `hour_usage`：同 `(date,hour)` 的 `minutes` **相加**。
 * - 事件表：主键去重（见 [BackupRepository.mergeAccumulate]）。
 * - `screen_session`：区间**并集**（不累加），见 [BackupRepository.mergeAccumulate]。
 * - `app_limit`：**完全不写**——限额是本机用户意图，跨设备合并不搬运（§4）。
 */
object CrossDeviceMerge {

    /** [rows] 是**合并后**要写回库里的整行（累加值），不是备份原样。 */
    data class Plan<T>(val rows: List<T>, val report: BackupMerge.Report)

    /**
     * 同日累加。
     *
     * 备份里同一个日期出现多行时取「主指标最大」的那一行再累加——手搓的备份文件
     * 完全可能有重复日期，直接累加会把那天翻倍。
     */
    fun daySummaries(
        local: List<DaySummaryDto>,
        backup: List<DaySummaryDto>
    ): Plan<DaySummaryDto> {
        val localByDate = local.associateBy { it.date }
        val rows = mutableListOf<DaySummaryDto>()
        var added = 0
        var updated = 0
        backup.groupBy { it.date }.forEach { (date, sameDay) ->
            // 主指标与 [BackupMerge] 同口径：先比 screen_unlock_min，平手再比 screen_min
            val incoming = sameDay.maxWithOrNull(
                compareBy<DaySummaryDto> { it.screenUnlockMin }.thenBy { it.screenMin }
            )!!
            val current = localByDate[date]
            if (current == null) {
                rows += incoming
                added++
            } else {
                rows += current.copy(
                    screenMin = current.screenMin + incoming.screenMin,
                    screenUnlockMin = current.screenUnlockMin + incoming.screenUnlockMin,
                    unlocks = current.unlocks + incoming.unlocks,
                    launches = current.launches + incoming.launches,
                    longestSessionMin = maxOf(current.longestSessionMin, incoming.longestSessionMin),
                    pulledAt = maxOf(current.pulledAt, incoming.pulledAt)
                )
                updated++
            }
        }
        return Plan(rows, BackupMerge.Report(added = added, updated = updated))
    }

    /** 同 `(date,pkg)` 的 `minutes / launches` 相加；`name / category` 以本机为准。 */
    fun appUsage(
        local: List<AppUsageDto>,
        backup: List<AppUsageDto>
    ): Plan<AppUsageDto> {
        val localByKey = local.associateBy { it.date to it.pkg }
        val rows = mutableListOf<AppUsageDto>()
        var added = 0
        var updated = 0
        backup.groupBy { it.date to it.pkg }.forEach { (key, sameApp) ->
            val incoming = sameApp.maxBy { it.minutes }
            val current = localByKey[key]
            if (current == null) {
                rows += incoming
                added++
            } else {
                rows += current.copy(
                    minutes = current.minutes + incoming.minutes,
                    launches = current.launches + incoming.launches
                )
                updated++
            }
        }
        return Plan(rows, BackupMerge.Report(added = added, updated = updated))
    }

    /** 同 `(date,hour)` 的 `minutes` 相加。 */
    fun hourUsage(
        local: List<HourUsageDto>,
        backup: List<HourUsageDto>
    ): Plan<HourUsageDto> {
        val localByKey = local.associateBy { it.date to it.hour }
        val rows = mutableListOf<HourUsageDto>()
        var added = 0
        var updated = 0
        backup.groupBy { it.date to it.hour }.forEach { (key, sameHour) ->
            val incoming = sameHour.maxBy { it.minutes }
            val current = localByKey[key]
            if (current == null) {
                rows += incoming
                added++
            } else {
                rows += HourUsageDto(key.first, key.second, current.minutes + incoming.minutes)
                updated++
            }
        }
        return Plan(rows, BackupMerge.Report(added = added, updated = updated))
    }

    // ------------------------------------------------------------ 日期口径

    /** 毫秒时间戳归属的本地日期（§2.2：事件按设备本地时区归属自然日）。 */
    fun dateOf(occurredAtMs: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(occurredAtMs).atZone(zone).toLocalDate().toString()

    /** 会话区间**跨到的**所有日期（跨零点会有两天）。 */
    fun datesOfSession(startMs: Long, endMs: Long, zone: ZoneId): Set<String> {
        val start = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
        val end = Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate()
        val out = mutableSetOf<String>()
        var cursor = start
        while (!cursor.isAfter(end)) {
            out += cursor.toString()
            cursor = cursor.plusDays(1)
        }
        return out
    }

    /**
     * 一份备份里出现的**全部日期**（防重复记账的粒度）。
     *
     * 日聚合 / 应用 / 时段直接带日期串；事件与会话按本地时区换算——
     * 只有把事件与会话的日期也算进来，记账才不会「合并过一次的事件在下次合并时
     * 因为日期不在集合里而被重复并入」。
     */
    fun datesOf(file: BackupFile, zone: ZoneId): Set<String> {
        val out = mutableSetOf<String>()
        out += file.daySummary.map { it.date }
        out += file.appUsage.map { it.date }
        out += file.hourUsage.map { it.date }
        out += file.unlockEvent.map { dateOf(it.occurredAtMs, zone) }
        out += file.notificationEvent.map { dateOf(it.occurredAtMs, zone) }
        out += file.screenSession.flatMap { datesOfSession(it.startMs, it.endMs, zone) }
        return out
    }

    /** 备份里属于 [allowed] 的日期（防重复：只并入尚未合并过的新日期）。 */
    fun allowedDatesOf(file: BackupFile, alreadyMerged: Set<String>, zone: ZoneId): Set<String> =
        datesOf(file, zone) - alreadyMerged

    /** 防重复记账的纯函数：合并成功后应该记下的日期集合。 */
    fun ledgerAfterMerge(alreadyMerged: Set<String>, mergedNow: Set<String>): Set<String> =
        alreadyMerged + mergedNow

    /** 事件行的日期归属（供落库前过滤）。 */
    fun eventDate(occurredAtMs: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(occurredAtMs).atZone(zone).toLocalDate()
}
