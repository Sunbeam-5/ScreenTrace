package com.screentrace.app.data.backup

/**
 * §4 CR-22 ②：识别「来源备份是不是**本机自己**导出的」。
 *
 * ## 为什么需要
 *
 * §4 的「跨设备累加合并」是给**两台不同设备**的数据汇合用的：两台设备当天的使用量
 * 本来就应该相加。但**累加口径对「同一台机器的两份快照」是错的**——那份旧备份里
 * 已经有这天的数据，再累加一遍就是翻倍。
 *
 * 真机踩到的正是这个：Redmi 上升级前的根目录旧备份（v4，无 `device` 字段）其实就是
 * **这台机器自己**在 11-12 导出的，它与本机库的会话时间戳逐条一致；按累加口径并入后，
 * 11-10 的 765 分钟变成 1530 分钟。
 *
 * ## 三条判据
 *
 * - **信号①**（设备标识）：备份顶层 `device.id` 与本机 `deviceId` 相同（v5 备份必带；
 *   旧备份没有 `device` 字段，按「未知设备」处理，走信号②③）。
 * - **信号②**（日聚合完全一致）：来源备份与本机库的**重叠日期**上，`day_summary` 的
 *   `screen_min / screen_unlock_min / unlocks / launches / longest_session_min`
 *   **逐项完全一致**。同一份系统用量的两份快照必然逐项相同；两台**不同**设备几乎
 *   不可能全等（同款机型也不行）。
 * - **信号③**（事件主键子集）：该日备份里的**事件主键**全部是本机该日事件主键的
 *   **子集**（备份该日事件非空）。同一台机器的事件时间戳到毫秒一致——备份是本机
 *   **更早的快照**时，当天后来才发生的事件不在备份里，所以是子集而不是相等；
 *   两台不同手机的事件时间戳不可能重合。
 *   （真机实测：本机 11-12 有 72 条通知、旧备份 17 条，17 条全部是 72 条的子集。）
 *
 * ## 口径
 *
 * 命中判据的**重叠日期**一律「**以本机为准、不累加**」，只并入本机没有的日期；
 * 非重叠日期照常按累加口径并入（那部分确实是本机没有的历史）。
 */
object SameDeviceFingerprint {

    /** 一天的可比对指纹（`day_summary` 的关键字段）。 */
    data class DayFingerprint(
        val screenMin: Int,
        val screenUnlockMin: Int,
        val unlocks: Int,
        val launches: Int,
        val longestSessionMin: Int
    ) {
        companion object {
            fun of(row: DaySummaryDto): DayFingerprint = DayFingerprint(
                row.screenMin,
                row.screenUnlockMin,
                row.unlocks,
                row.launches,
                row.longestSessionMin
            )
        }
    }

    /** 判定结果。 */
    data class Verdict(
        /** 判定为「同一台机器」的重叠日期——这些日期不参与累加。 */
        val sameDeviceDates: Set<String>,
        /** 判据说明（给用户看的一句话）。 */
        val reason: String
    ) {
        /** 是不是判成了同一台机器。 */
        val isSameDevice: Boolean get() = sameDeviceDates.isNotEmpty()
    }

    /** 某一天的事件主键集合（通知与解锁用不同前缀，避免撞键）。 */
    data class DayEventKeys(val keys: Set<String>) {
        companion object {
            fun ofUnlock(ms: Long): String = "u|$ms"
            fun ofNotification(ms: Long, type: String, pkg: String): String =
                "n|$ms|$type|$pkg"
        }
    }

    /** 备份里某天的事件主键集合（按本机时区归属自然日）。 */
    fun eventKeysOf(file: BackupFile, date: String, zone: java.time.ZoneId): Set<String> {
        val out = mutableSetOf<String>()
        file.notificationEvent.forEach {
            if (CrossDeviceMerge.dateOf(it.occurredAtMs, zone) == date) {
                out += DayEventKeys.ofNotification(it.occurredAtMs, it.type, it.pkg)
            }
        }
        file.unlockEvent.forEach {
            if (CrossDeviceMerge.dateOf(it.occurredAtMs, zone) == date) {
                out += DayEventKeys.ofUnlock(it.occurredAtMs)
            }
        }
        return out
    }

    /**
     * 来源备份里某天的指纹。
     *
     * 同日多行时取「主指标最大」的那一行——与 [CrossDeviceMerge] 的同日多行口径一致
     * （先比 `screen_unlock_min`，平手再比 `screen_min`）。
     */
    fun fingerprintOf(file: BackupFile, date: String): DayFingerprint? {
        val row = file.daySummary.filter { it.date == date }
            .maxWithOrNull(
                compareBy<DaySummaryDto> { it.screenUnlockMin }.thenBy { it.screenMin }
            )
            ?: return null
        return DayFingerprint.of(row)
    }

    /**
     * 判定来源备份与本机重叠的哪些日期属于「同一台机器」。
     *
     * @param file 来源备份（远端设备最新的一份）
     * @param localDays 本机库在**重叠日期**上的日聚合指纹（date → 指纹）
     * @param localEvents 本机库在**重叠日期**上的事件主键集合（date → 主键）
     * @param localDeviceId 本机 `deviceId`（信号①用；空串＝本机 ID 还没读到）
     * @param zone 事件归属日期的时区（本机时区）
     */
    fun detect(
        file: BackupFile,
        localDays: Map<String, DayFingerprint>,
        localDeviceId: String,
        localEvents: Map<String, DayEventKeys> = emptyMap(),
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault()
    ): Verdict {
        if (localDays.isEmpty()) {
            return Verdict(emptySet(), "与本机没有重叠日期")
        }
        val sameId = localDeviceId.isNotEmpty() &&
            file.device?.id?.takeIf { it.isNotBlank() } == localDeviceId

        // 信号②：日聚合逐项完全一致
        val bySnapshot = localDays.filter { (date, local) ->
            fingerprintOf(file, date) == local
        }.keys

        // 信号③：该日备份事件主键全部是本机该日事件主键的子集（且备份该日事件非空）
        val byEvents = localDays.keys.filter { date ->
            val backupKeys = eventKeysOf(file, date, zone)
            backupKeys.isNotEmpty() &&
                backupKeys.all { it in (localEvents[date]?.keys ?: emptySet()) }
        }

        val sameDeviceDates = if (sameId) localDays.keys else (bySnapshot + byEvents)
        val reason = when {
            sameId && (bySnapshot + byEvents).isNotEmpty() ->
                "设备 ID 与本机相同，且有 ${bySnapshot.size + byEvents.size} 天的数据与本机重合"
            sameId -> "设备 ID 与本机相同"
            bySnapshot.isNotEmpty() && byEvents.isNotEmpty() ->
                "有 ${bySnapshot.size} 天的数据逐项完全一致、${byEvents.size} 天的事件完全包含于本机"
            bySnapshot.isNotEmpty() -> "有 ${bySnapshot.size} 天的数据与本机逐项完全一致"
            byEvents.isNotEmpty() -> "有 ${byEvents.size} 天的事件全部包含于本机（同机更早的快照）"
            else -> "与本机没有重合数据"
        }
        return Verdict(sameDeviceDates, reason)
    }
}
