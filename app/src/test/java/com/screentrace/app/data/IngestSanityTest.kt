package com.screentrace.app.data

import com.screentrace.app.data.parse.AppUsageRow
import com.screentrace.app.data.parse.DayMetrics
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 入库自洽性校验的回归。
 *
 * **看住的是真机首装那条数据**：`com.xiaomi.smarthome` 当天 1386 分钟，
 * 而同日屏幕时长只有 510 分钟。单个应用比全天还长 2.7 倍，界面却照原样渲染成
 * 「用了 23 小时」，用户第一反应是「这 App 在瞎算」。
 *
 * 这一层的设计取舍：**宁可这一天不出应用/时段数据，也不出一份看起来合理其实是编的**。
 * 所以测试断言的重点不是「数字对不对」，而是「矛盾数据有没有被挡在库外」。
 */
class IngestSanityTest {

    private val day10 = LocalDate.of(2026, 11, 10)
    private val day11 = LocalDate.of(2026, 11, 11)

    private fun day(screenMin: Int) = DayMetrics(
        screenMin = screenMin,
        screenUnlockMin = screenMin,
        unlocks = 0,
        launches = 0,
        longestSessionMin = screenMin
    )

    /**
     * 造一组合计恰好为 [total] 分钟的 24 桶。
     *
     * 校验的参照物是「屏幕时长」与「时段合计」的较大者，所以测试里两者的相对大小
     * 必须还原真机形状：脏数据那天（11-11）的时段合计就是 510 分钟，不是满 24 小时。
     */
    private fun hours(total: Int): List<Int> {
        val base = total / 24
        val rest = total % 24
        return List(24) { base + if (it < rest) 1 else 0 }
    }

    private fun app(date: LocalDate, pkg: String, minutes: Int, launches: Int = 0) =
        AppUsageRow(date = date, pkg = pkg, totalMs = minutes * 60_000L, launches = launches)

    @Test
    fun `主空间与分身并行时应用合计可超屏幕时长`() {
        // 真机事实：user=0 与 user=999 可以同时在前台，应用时长按账户分别统计后求和，
        // 合计自然会到屏幕时长的两倍。这是真实使用情况，不是脏数据——不能丢。
        val verdict = IngestSanity.check(
            days = mapOf(day11 to day(600)),
            appUsage = listOf(
                app(day11, "com.main.space", 700),
                app(day11, "com.clone.space", 480)
            ),
            hourMinutes = mapOf(day11 to hours(600))
        )

        assertTrue("两路并行在 3 倍以内，保留", verdict.droppedDates.isEmpty())
        assertEquals(2, verdict.appUsage.size)
    }

    @Test
    fun `应用合计超过参照三倍时整日丢弃`() {
        // 上限 = 510 × 3 = 1530；1600 分钟超出，属于 In-memory 窗口失准的典型症状
        val verdict = IngestSanity.check(
            days = mapOf(day11 to day(510)),
            appUsage = listOf(app(day11, "com.xiaomi.smarthome", 1600)),
            hourMinutes = mapOf(day11 to hours(510))
        )

        assertTrue("这天的应用数据必须被丢掉", verdict.appUsage.isEmpty())
        assertTrue("小时分布与应用时长同源，也必须丢", verdict.hourMinutes.isEmpty())
        assertEquals(listOf(day11), verdict.droppedDates)
    }

    @Test
    fun `单个应用超过一天是硬错且不受参照物影响`() {
        // 必须有屏幕时长参照，否则走的是「24 小时兜底」那条路，硬判据被覆盖测不到
        // （第一版就是这么写的，变异测试发现去掉硬判据仍然全绿）。
        // 这里屏幕 600 分钟 → 参照上限 600×3=1800，1500 分钟在合计判据下合法，
        // 只有「单应用 ≤ 1440」这条硬判据能拦住它。
        val verdict = IngestSanity.check(
            days = mapOf(day11 to day(600)),
            appUsage = listOf(app(day11, "com.a", 1500)),
            hourMinutes = mapOf(day11 to hours(600))
        )
        assertEquals("单个应用超过 24 小时物理上不可能", listOf(day11), verdict.droppedDates)
    }

    @Test
    fun `只有一天矛盾时另一天照常保留`() {
        val verdict = IngestSanity.check(
            days = mapOf(day10 to day(765), day11 to day(510)),
            appUsage = listOf(
                app(day10, "com.tencent.mm", 300),
                app(day11, "com.xiaomi.smarthome", 1600)
            ),
            hourMinutes = mapOf(day10 to hours(765), day11 to hours(510))
        )

        assertEquals("好的一天不能受牵连", listOf(app(day10, "com.tencent.mm", 300)), verdict.appUsage)
        assertEquals(setOf(day10), verdict.hourMinutes.keys)
        assertEquals(listOf(day11), verdict.droppedDates)
    }

    @Test
    fun `四舍五入与双源取大的噪声不被误杀`() {
        // 上限 = 100 * 1.02 + 1 = 103 分钟；102 分钟属于正常噪声
        val verdict = IngestSanity.check(
            days = mapOf(day11 to day(100)),
            appUsage = listOf(app(day11, "a", 102)),
            hourMinutes = mapOf(day11 to hours(96))
        )

        assertEquals(1, verdict.appUsage.size)
        assertTrue("轻微超出是合并噪声，不该丢数据", verdict.droppedDates.isEmpty())
    }

    @Test
    fun `没有日聚合时按一天 24 小时兜底`() {
        // 降级通道不写 day_summary，只能用品格上限兜底
        val withinDay = IngestSanity.check(
            days = emptyMap(),
            appUsage = listOf(app(day11, "a", 1390)),
            hourMinutes = emptyMap()
        )
        assertTrue("1390 分钟仍在一天之内，保留", withinDay.droppedDates.isEmpty())

        // 两路参照都没有（无 day_summary、无时段分布）才走 24 小时兜底；
        // 一旦给了 hourMinutes，参照就变成时段合计，不再是「兜底」场景了。
        val beyondDay = IngestSanity.check(
            days = emptyMap(),
            appUsage = listOf(app(day11, "a", 1500)),
            hourMinutes = emptyMap()
        )
        assertEquals("超过 24 小时物理上不可能", listOf(day11), beyondDay.droppedDates)
    }

    @Test
    fun `空输入原样返回且不丢任何日期`() {
        val verdict = IngestSanity.check(emptyMap(), emptyList(), emptyMap())
        assertTrue(verdict.appUsage.isEmpty())
        assertTrue(verdict.hourMinutes.isEmpty())
        assertTrue(verdict.droppedDates.isEmpty())
    }
}
