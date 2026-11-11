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

    /** §4：day_summary 同日取 `screen_unlock_min` 较大的一行**整体保留**。 */
    fun mergeDay(existing: DaySummaryEntity?, incoming: DaySummaryEntity): DaySummaryEntity =
        if (existing == null || incoming.screenUnlockMin >= existing.screenUnlockMin) incoming else existing

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

    /** §4：hour_usage 同 (date,hour) 取较大值。 */
    fun mergeHour(existing: HourUsageEntity?, incoming: HourUsageEntity): HourUsageEntity =
        if (existing == null || incoming.minutes >= existing.minutes) incoming else existing

    /** §4：pull_log 仅追加（无合并）。 */

    /**
     * 批 6g 三轮：会话区间**扫线真并集**（SCREEN / KEYGUARD 各自独立合并）。
     * 旧行 ∪ 新拉取区间 → 互不重叠的规范段集；调用方删除旧重叠行后整集写回。
     * 实现委托 [SessionUnion.union]（纯函数，单测覆盖重叠/相邻/含入）。
     */
    fun mergeSessions(
        existing: List<ScreenSessionEntity>,
        incoming: List<SessionSpan>,
        kind: String
    ): List<ScreenSessionEntity> =
        SessionUnion.union(
            // 必须按 kind 过滤：overlapping 查询返回的是全 kind 混合行，
            // 不过滤会把 SCREEN 区间并进 KEYGUARD 输出（假锁屏会吞掉真实亮屏时长）
            existing.filter { it.kind == kind }.map { SessionUnion.Span(it.startMs, it.endMs) } +
                incoming.map { SessionUnion.Span(it.startMs, it.endMs) }
        ).map { ScreenSessionEntity(it.startMs, it.endMs, kind) }

    /**
     * CR-01（§2.2 坑 2 / §4 事件合并）：notification_event 按
     * `(occurredAtMs/1000, type, pkg)` 秒级分组，取两侧条数的**较大值**（整边）——
     * 既不吞掉同秒真实通知（组内多条全保留），也不因双源/重拉重复计数。
     * 返回涉及分组的最终行集；调用方删除旧行后写回（单事务）。
     */
    fun mergeNotifications(
        existing: List<NotificationEventEntity>,
        incoming: List<NotificationEventEntity>
    ): List<NotificationEventEntity> {
        val key: (NotificationEventEntity) -> Triple<Long, String, String> = {
            Triple(it.occurredAtMs / 1000, it.type, it.pkg)
        }
        val ga = existing.groupBy(key)
        val gb = incoming.groupBy(key)
        return (ga.keys + gb.keys).flatMap { k ->
            val la = ga[k].orEmpty().sortedBy { it.occurredAtMs }
            val lb = gb[k].orEmpty().sortedBy { it.occurredAtMs }
            if (la.size >= lb.size) la else lb
        }.sortedBy { it.occurredAtMs }
    }
}
