package com.screentrace.app.data

import com.screentrace.app.data.db.AppUsageEntity
import com.screentrace.app.data.db.DaySummaryEntity
import com.screentrace.app.data.db.HourUsageEntity
import com.screentrace.app.data.db.NotificationEventEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** §4 合并策略 + CR-01 事件合并的纯函数回归（§7-13/§7-17）。 */
class MergePolicyTest {

    private fun day(date: String, unlockMin: Int, unlocks: Int = 5) =
        DaySummaryEntity(date, unlockMin + 8, unlockMin, unlocks, 20, 25, 0L)

    @Test
    fun daySummaryKeepsLargerUnlockRowWholesale() {
        val small = day("2026-11-03", 100, unlocks = 99)
        val large = day("2026-11-03", 200, unlocks = 1)
        assertEquals(large, MergePolicy.mergeDay(small, large)) // 整行保留（unlocks 也随行）
        assertEquals(large, MergePolicy.mergeDay(large, small)) // 较小行不覆盖
        assertEquals(large, MergePolicy.mergeDay(null, large))
    }

    @Test
    fun appUsageKeepsLargerMinutes() {
        val a = AppUsageEntity("2026-11-03", "com.x", "X", 100, 5, "OTHER")
        val b = AppUsageEntity("2026-11-03", "com.x", "X新", 200, 1, "TOOLS")
        assertEquals(b, MergePolicy.mergeApp(a, b))
        assertEquals(b, MergePolicy.mergeApp(b, a))
    }

    @Test
    fun hourUsageKeepsLarger() {
        val a = HourUsageEntity("2026-11-03", 9, 30)
        val b = HourUsageEntity("2026-11-03", 9, 50)
        assertEquals(b, MergePolicy.mergeHour(a, b))
    }

    @Test
    fun notificationSameSecondMultiRowsSurviveMerge() {
        // 同秒同 type 同 pkg 的 3 条真实通知：重拉同数据 → 仍 3 条（不吞不多计）
        val base = 1793660400000L
        val three = listOf(
            NotificationEventEntity(base + 100, "INTERRUPTION", "com.a", null),
            NotificationEventEntity(base + 200, "INTERRUPTION", "com.a", null),
            NotificationEventEntity(base + 300, "INTERRUPTION", "com.a", null)
        )
        val merged = MergePolicy.mergeNotifications(three, three)
        assertEquals(3, merged.size) // 取大边=3，不是 6
        assertEquals(listOf(base + 100, base + 200, base + 300), merged.map { it.occurredAtMs })
    }

    @Test
    fun notificationTakesLargerSidePerSecondGroup() {
        val base = 1793660400000L
        // 库里 1 条 vs 本次拉取 3 条（同秒组）→ 取 3；另一秒组 2 vs 1 → 取 2
        val existing = listOf(
            NotificationEventEntity(base + 100, "INTERRUPTION", "com.a", null),
            NotificationEventEntity(base + 5000, "SEEN", "com.a", null)
        )
        val incoming = listOf(
            NotificationEventEntity(base + 150, "INTERRUPTION", "com.a", null),
            NotificationEventEntity(base + 250, "INTERRUPTION", "com.a", null),
            NotificationEventEntity(base + 350, "INTERRUPTION", "com.a", null),
            NotificationEventEntity(base + 5100, "SEEN", "com.a", null)
        )
        val merged = MergePolicy.mergeNotifications(existing, incoming)
        assertEquals(3, merged.count { it.type == "INTERRUPTION" }) // 秒组取大（3）
        assertEquals(1, merged.count { it.type == "SEEN" }) // 5s 附近两条差 100ms 但跨秒？
        // base+5000 与 base+5100 同秒（5000/1000==5100/1000==5）→ 同组 1 vs 1 → 取一边 1 条
    }

    @Test
    fun notificationDifferentSecondsAreIndependent() {
        val base = 1793660400000L
        val rows = listOf(
            NotificationEventEntity(base + 100, "INTERRUPTION", "com.a", null),
            NotificationEventEntity(base + 1100, "INTERRUPTION", "com.a", null),
            NotificationEventEntity(base + 2100, "INTERRUPTION", "com.a", null)
        )
        val merged = MergePolicy.mergeNotifications(emptyList(), rows)
        assertEquals(3, merged.size) // 不同秒各成组，全部保留
    }
}
