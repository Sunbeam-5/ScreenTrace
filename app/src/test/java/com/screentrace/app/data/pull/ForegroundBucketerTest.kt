package com.screentrace.app.data.pull

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 降级通道的时段分桶（纯 JVM，不需要 Android）。
 *
 * 为什么值得单独测：`UsageStatsManager` 的 `INTERVAL_DAILY` 桶按设备时区切，
 * 但它给的是「应用级总量」，没有小时维度；我们只能用 `queryEvents` 的前后台区间
 * 自己切小时。切错（例如跨零点不拆日）会让统计页的时段分布与今日页对不上，
 * 而这种错在真机上肉眼极难发现——必须靠断言钉住。
 */
class ForegroundBucketerTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val day: LocalDate = LocalDate.of(2026, 11, 4)

    private fun ms(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `单个小时内 30 分钟只落一个桶`() {
        val out = ForegroundBucketer.bucket(listOf(ms(day, 10)..ms(day, 10, 30)), zone)
        assertEquals(setOf(day), out.keys)
        val buckets = out.getValue(day)
        assertEquals(24, buckets.size)
        assertEquals(30, buckets[10])
        assertEquals(0, buckets.sum() - 30)
    }

    @Test
    fun `跨小时按整点切开`() {
        val out = ForegroundBucketer.bucket(listOf(ms(day, 10, 50)..ms(day, 11, 10)), zone)
        val buckets = out.getValue(day)
        assertEquals(10, buckets[10])
        assertEquals(10, buckets[11])
        assertEquals(20, buckets.sum())
    }

    @Test
    fun `跨零点拆到两天且各算一半`() {
        val out = ForegroundBucketer.bucket(listOf(ms(day, 23, 50)..ms(day.plusDays(1), 0, 10)), zone)
        assertEquals(setOf(day, day.plusDays(1)), out.keys)
        assertEquals(10, out.getValue(day)[23])
        assertEquals(10, out.getValue(day.plusDays(1))[0])
    }

    @Test
    fun `区间合计等于总时长不留零头`() {
        // 09:07 → 09:52 = 45 分钟，应落在 9 点桶
        val out = ForegroundBucketer.bucket(listOf(ms(day, 9, 7)..ms(day, 9, 52)), zone)
        assertEquals(45, out.getValue(day).sum())
    }

    @Test
    fun `跨多小时的区间逐小时累加`() {
        val out = ForegroundBucketer.bucket(listOf(ms(day, 8)..ms(day, 12)), zone)
        val buckets = out.getValue(day)
        assertEquals(60, buckets[8])
        assertEquals(60, buckets[9])
        assertEquals(60, buckets[10])
        assertEquals(60, buckets[11])
        assertEquals(240, buckets.sum())
    }

    @Test
    fun `空输入返回空表而不是 24 个零桶`() {
        assertTrue(ForegroundBucketer.bucket(emptyList(), zone).isEmpty())
    }

    @Test
    fun `零长度与倒序区间被忽略`() {
        val spans = listOf(ms(day, 10)..ms(day, 10), ms(day, 11)..ms(day, 10))
        assertTrue(ForegroundBucketer.bucket(spans, zone).isEmpty())
    }

    // ---------- 前后台配对（降级通道的核心风险点） ----------

    @Test
    fun `正常的前后台配对成一段`() {
        val spans = ForegroundBucketer.spansOf(
            listOf(
                enter(ms(day, 10)),
                leave(ms(day, 10, 30))
            ),
            endMs = ms(day, 12)
        )
        assertEquals(1, spans.size)
        assertEquals(30 * 60_000L, spans[0].endMs - spans[0].startMs)
    }

    @Test
    fun `进程被杀丢掉 paused 时以切到下一个应用收尾`() {
        // A 进入前台后再没收到 paused（被杀），B 在 10:20 进入前台
        val spans = ForegroundBucketer.spansOf(
            listOf(
                enter(ms(day, 10), "A"),
                enter(ms(day, 10, 20), "B"),
                leave(ms(day, 10, 50), "B")
            ),
            endMs = ms(day, 23, 59)
        )
        assertEquals("两段，而不是 A 一路延到拉取时刻", 2, spans.size)
        val a = spans.first { it.pkg == "A" }
        val b = spans.first { it.pkg == "B" }
        assertEquals(20 * 60_000L, a.endMs - a.startMs)
        assertEquals(30 * 60_000L, b.endMs - b.startMs)
        assertTrue("A 的结束时间必须等于 B 的开始时间", a.endMs == b.startMs)
    }

    @Test
    fun `同一应用连续多次 resumed 不切碎区间`() {
        val spans = ForegroundBucketer.spansOf(
            listOf(
                enter(ms(day, 10)),
                enter(ms(day, 10, 10)), // 切换 Activity，包名不变
                enter(ms(day, 10, 20)),
                leave(ms(day, 10, 30))
            ),
            endMs = ms(day, 12)
        )
        assertEquals(1, spans.size)
        assertEquals(30 * 60_000L, spans[0].endMs - spans[0].startMs)
    }

    @Test
    fun `仍在最前台的应用截到拉取时刻`() {
        val spans = ForegroundBucketer.spansOf(listOf(enter(ms(day, 10))), endMs = ms(day, 10, 5))
        assertEquals(1, spans.size)
        assertEquals(5 * 60_000L, spans[0].endMs - spans[0].startMs)
    }

    @Test
    fun `乱序事件按时间排序后配对`() {
        val spans = ForegroundBucketer.spansOf(
            listOf(leave(ms(day, 10, 30)), enter(ms(day, 10))),
            endMs = ms(day, 12)
        )
        assertEquals(1, spans.size)
        assertEquals(30 * 60_000L, spans[0].endMs - spans[0].startMs)
    }

    private fun enter(atMs: Long, pkg: String = "com.demo") =
        ForegroundBucketer.ForegroundEvent(atMs, pkg, enter = true)

    private fun leave(atMs: Long, pkg: String = "com.demo") =
        ForegroundBucketer.ForegroundEvent(atMs, pkg, enter = false)
}
