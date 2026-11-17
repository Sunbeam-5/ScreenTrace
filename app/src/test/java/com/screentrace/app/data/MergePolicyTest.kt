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
    fun appUsageBackfillsRealNameOverPackagePlaceholder() {
        // 旧行 name 还是包名占位（回填前入库的），新行数值更小但带真名
        val placeholder = AppUsageEntity("2026-11-03", "com.miui.home", "com.miui.home", 200, 5, "OTHER")
        val named = AppUsageEntity("2026-11-03", "com.miui.home", "系统桌面", 100, 3, "TOOLS")

        // 两个方向都要收敛到「数值取大 + 名字取真名」，否则已回填的名字会被冲回包名
        val forward = MergePolicy.mergeApp(placeholder, named)
        assertEquals(200, forward.minutes)
        assertEquals("系统桌面", forward.name)

        val backward = MergePolicy.mergeApp(named, placeholder)
        assertEquals(200, backward.minutes)
        assertEquals("系统桌面", backward.name)
    }

    @Test
    fun hourUsageKeepsLarger() {
        val a = HourUsageEntity("2026-11-03", 9, 30)
        val b = HourUsageEntity("2026-11-03", 9, 50)
        assertEquals(b, MergePolicy.mergeHour(a, b))
    }

    // 通知合并用例已随 CR-28 批次 E 移除：`mergeNotifications`（秒级分组取大条数、
    // 整边替换）废除——它会把同秒组里条数少的一边整组丢掉（CR-01「同秒多条真实
    // 通知不丢」的 DB 层缺口）。现语义＝完整主键 `insertIgnore` 并集，实测见
    // `SameMillisecondEventTest` 的「同秒组不同内容两批合并不丢行」。
}
