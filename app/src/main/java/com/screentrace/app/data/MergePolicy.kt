package com.screentrace.app.data

import com.screentrace.app.data.db.AppUsageEntity
import com.screentrace.app.data.db.DaySummaryEntity
import com.screentrace.app.data.db.HourUsageEntity
import com.screentrace.app.data.db.NotificationEventEntity
import com.screentrace.app.data.db.ScreenSessionEntity
import com.screentrace.app.data.parse.SessionSpan

/**
 * §4 合并策略（纯函数，JVM 可测；调用方在单事务中执行）：
 * - 同日重拉取较大值、历史只增不删；恢复「合并本地」复用同一策略（§5.10）；
 * - CR-01 事件合并：unlock_event 主键去重（DAO `INSERT OR IGNORE` 即可）；
 *   notification_event 按秒级分组取较大条数（多重集合，§2.2 坑 2）。
 */
object MergePolicy {

    /** D-07 方案 C+（§3.3）：同来源质量证据优先级（EXACT > PARTIAL > UNKNOWN > NULL）。 */
    fun sameSourceQualityRank(quality: String?): Int = when (quality) {
        com.screentrace.app.data.db.DataQualityValues.EXACT -> 4
        com.screentrace.app.data.db.DataQualityValues.PARTIAL -> 3
        null -> 1
        else -> 2
    }

    /**
     * §4 + D-07 方案 C+：同来源 (date, sourceDeviceId) 交换律全序合并。
     * 先比 screenUnlockMin、screenMin，平手比质量证据等级，再比 longestSessionMin / unlocks / launches，
     * pulledAt 恒取 max，彻底消除到达顺序依赖。
     */
    fun mergeDay(existing: DaySummaryEntity?, incoming: DaySummaryEntity): DaySummaryEntity {
        if (existing == null) return incoming
        val cmp = compareValuesBy(
            existing,
            incoming,
            { it.screenUnlockMin },
            { it.screenMin },
            { sameSourceQualityRank(it.screenQuality) },
            { it.longestSessionMin },
            { it.unlocks },
            { it.launches }
        )
        val winner = if (cmp >= 0) existing else incoming
        return winner.copy(pulledAt = maxOf(existing.pulledAt, incoming.pulledAt))
    }

    /**
     * §4：app_usage 同 (date,pkg) 取 `minutes` 较大值并保留对应 name/category。
     *
     * 名字与数值解耦：命中「保留旧值」时，如果旧行的名字还是包名占位、而另一侧有
     * 真实名字（PackageManager 回填成功过），把真名补上——否则一次「库内占位 +
     * 本次入库更大」就会把已回填的展示名冲回 `com.xxx`。
     */
    fun mergeApp(existing: AppUsageEntity?, incoming: AppUsageEntity): AppUsageEntity {
        if (existing == null) return incoming
        val winner = if (incoming.minutes >= existing.minutes) incoming else existing
        val other = if (winner === incoming) existing else incoming
        val winnerIsPlaceholder = isPlaceholderName(winner.name, winner.pkg)
        return if (winnerIsPlaceholder && !isPlaceholderName(other.name, other.pkg)) {
            winner.copy(name = other.name)
        } else {
            winner
        }
    }

    private fun isPlaceholderName(name: String, pkg: String): Boolean =
        name.isBlank() || name == pkg

    /**
     * §4 + D-07 Review 修复（P1-3）：同来源 (date, hour, sourceDeviceId) 交换律全序比较。
     * 比较键：`(quality == EXACT, minutes, sameSourceQualityRank(quality))`：
     * ① EXACT 绝不被降级通道 PARTIAL（即使分钟更大）覆盖；
     * ② 双 EXACT 取更大分钟（守 CR-28）；
     * ③ 历史 null 不被更小 PARTIAL 覆盖；
     * ④ 严格满足交换律。
     */
    fun compareHour(aMinutes: Int, aQuality: String?, bMinutes: Int, bQuality: String?): Int {
        val exactCmp = (aQuality == com.screentrace.app.data.db.DataQualityValues.EXACT)
            .compareTo(bQuality == com.screentrace.app.data.db.DataQualityValues.EXACT)
        if (exactCmp != 0) return exactCmp
        val minCmp = aMinutes.compareTo(bMinutes)
        if (minCmp != 0) return minCmp
        return sameSourceQualityRank(aQuality).compareTo(sameSourceQualityRank(bQuality))
    }

    /** §4 + D-07 Review 修复（P1-3）：hour_usage 同 (date, hour, sourceDeviceId) 交换律质量保护合并。 */
    fun mergeHour(existing: HourUsageEntity?, incoming: HourUsageEntity): HourUsageEntity {
        if (existing == null) return incoming
        val cmp = compareHour(existing.minutes, existing.quality, incoming.minutes, incoming.quality)
        return if (cmp >= 0) existing else incoming
    }

    /** §4：pull_log 仅追加（无合并）。 */

    /**
     * 批 6g 三轮：会话区间**扫线真并集**（SCREEN / KEYGUARD 各自独立合并）。
     * 旧行 ∪ 新拉取区间 → 互不重叠的规范段集；调用方删除旧重叠行后整集写回。
     * 实现委托 [SessionUnion.union]（纯函数，单测覆盖重叠/相邻/含入）。
     *
     * CR-28：输出行携带 [sourceDeviceId]；调用方必须**只传同一来源**的旧行
     * （跨来源区间永不合并——会破坏 UNION 的同时性信息）。
     */
    fun mergeSessions(
        existing: List<ScreenSessionEntity>,
        incoming: List<SessionSpan>,
        kind: String,
        sourceDeviceId: String = com.screentrace.app.data.db.SourceIds.UNSPECIFIED
    ): List<ScreenSessionEntity> =
        SessionUnion.union(
            // 必须按 kind 过滤：overlapping 查询返回的是全 kind 混合行，
            // 不过滤会把 SCREEN 区间并进 KEYGUARD 输出（假锁屏会吞掉真实亮屏时长）
            existing.filter { it.kind == kind }.map { SessionUnion.Span(it.startMs, it.endMs) } +
                incoming.map { SessionUnion.Span(it.startMs, it.endMs) }
        ).map { ScreenSessionEntity(it.startMs, it.endMs, kind, sourceDeviceId) }

    /**
     * ~~notification_event 秒级分组取大条数~~ **CR-28 批次 E 简化**：事件主键含
     * `sourceDeviceId` 与确定性 `seq` 后，合并一律 `insertIgnore` 全部（完整主键
     * 幂等、同毫秒多条与同秒组不同内容互不丢）。旧「取大条数、整边替换」会把
     * 同秒组里条数少的一边整组丢掉——CR-01「同秒多条真实通知不丢」的 DB 层缺口。
     */
}
