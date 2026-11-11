package com.screentrace.app.data

import com.screentrace.app.data.db.ScreenSessionEntity
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 批 6g 三轮：会话区间扫线并集与逐日重算的纯函数验收。
 *
 * 场景对应真机痛点：MIUI 事件缓冲是滚动窗口，单次拉取只见几小时；
 * 两次拉取各见一段 → 并集必须拼出全天（「跨拉取取并」而非「单次取大」）。
 */
class SessionUnionTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    // ---------- union ----------

    @Test
    fun `重叠区间合并`() {
        val out = SessionUnion.union(
            listOf(
                SessionUnion.Span(0, 100),
                SessionUnion.Span(50, 150)
            )
        )
        assertEquals(listOf(SessionUnion.Span(0, 150)), out)
    }

    @Test
    fun `相邻区间合并（端点相接）`() {
        val out = SessionUnion.union(
            listOf(
                SessionUnion.Span(0, 100),
                SessionUnion.Span(100, 200)
            )
        )
        assertEquals(listOf(SessionUnion.Span(0, 200)), out)
    }

    @Test
    fun `完全被包含的区间折叠不残留`() {
        val out = SessionUnion.union(
            listOf(
                SessionUnion.Span(0, 1000),
                SessionUnion.Span(100, 200),
                SessionUnion.Span(300, 400)
            )
        )
        assertEquals(listOf(SessionUnion.Span(0, 1000)), out)
    }

    @Test
    fun `乱序输入与退化区间被丢弃`() {
        val out = SessionUnion.union(
            listOf(
                SessionUnion.Span(500, 600),
                SessionUnion.Span(0, 100),
                SessionUnion.Span(200, 200),
                SessionUnion.Span(300, 100)
            )
        )
        assertEquals(
            listOf(SessionUnion.Span(0, 100), SessionUnion.Span(500, 600)),
            out
        )
    }

    @Test
    fun `两次拉取各见一段拼出全天（滚动缓冲场景）`() {
        // 拉取 A 只覆盖 08:00–12:00，拉取 B 只覆盖 14:00–18:00
        val pullA = listOf(SessionUnion.Span(ms(8, 0), ms(12, 0)))
        val pullB = listOf(SessionUnion.Span(ms(14, 0), ms(18, 0)))
        val out = SessionUnion.union(pullA + pullB)
        assertEquals(480L * 60_000, out.sumOf { it.endMs - it.startMs })
    }

    // ---------- daySlices ----------

    @Test
    fun `逐日切片：跨零点会话按日分摊，不跨日复制整段`() {
        val screen = listOf(
            ScreenSessionEntity(ms(23, 0), ms(1, 0) + 24 * 3600_000L, ScreenSessionEntity.KIND_SCREEN)
        )
        val slices = SessionUnion.daySlices(screen, emptyList(), zone)
        assertEquals(2, slices.size)
        val d0 = LocalDate.of(2026, 11, 10)
        val d1 = d0.plusDays(1)
        assertEquals(60, slices.getValue(d0).screenMin)
        assertEquals(60, slices.getValue(d1).screenMin)
        // longest 也逐日切片（返修 P0-2 口径）
        assertEquals(60, slices.getValue(d0).longestMin)
        assertEquals(60, slices.getValue(d1).longestMin)
    }

    @Test
    fun `锁屏重叠扣除（screenUnlockMin = screenMin - kg）`() {
        // 10:00–11:00 亮屏，其中 10:20–10:40 锁屏
        val screen = listOf(ScreenSessionEntity(ms(10, 0), ms(11, 0), ScreenSessionEntity.KIND_SCREEN))
        val kg = listOf(ScreenSessionEntity(ms(10, 20), ms(10, 40), ScreenSessionEntity.KIND_KEYGUARD))
        val slice = SessionUnion.daySlices(screen, kg, zone).values.single()
        assertEquals(60, slice.screenMin)
        assertEquals(40, slice.unlockMin)
        assertEquals(40, slice.longestMin)
    }

    @Test
    fun `小时矩阵按 unlock 口径落桶`() {
        val screen = listOf(ScreenSessionEntity(ms(10, 0), ms(11, 30), ScreenSessionEntity.KIND_SCREEN))
        val kg = listOf(ScreenSessionEntity(ms(10, 50), ms(11, 10), ScreenSessionEntity.KIND_KEYGUARD))
        val slice = SessionUnion.daySlices(screen, kg, zone).values.single()
        assertEquals(50, slice.hours[10]) // 60 - 10
        assertEquals(20, slice.hours[11]) // 30 - 10
        assertEquals(0, slice.hours[9])
    }

    @Test
    fun `空亮屏输入返回空映射`() {
        assertEquals(emptyMap<LocalDate, SessionUnion.DaySlice>(), SessionUnion.daySlices(emptyList(), emptyList(), zone))
    }

    // ---------- helpers ----------

    /** 2026-11-10 当天 hour:00 的毫秒时间戳（Asia/Shanghai）。 */
    private fun ms(hour: Int, minute: Int): Long =
        LocalDate.of(2026, 11, 10).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
}
