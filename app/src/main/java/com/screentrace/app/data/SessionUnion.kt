package com.screentrace.app.data

import com.screentrace.app.data.db.ScreenSessionEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 批 6g 三轮：会话区间**扫线并集**与逐日重算（纯函数，JVM 可测）。
 *
 * 动机（真机实测）：MIUI 的 usagestats 事件缓冲是滚动窗口，单次拉取只覆盖最近
 * 几小时；「同日取大合并」救不回任何一次拉取都没看到的时段。把每次拉取见到的
 * 亮屏/锁屏会话区间入库并做真并集，日聚合从并集重算——**跨拉取的观测取并**，
 * 而不是「单次观测取大」。
 *
 * 复杂度 O(n log n)，单日区间数在两位数，全表也就几百行，扫线足够。
 */
object SessionUnion {

    /** 毫秒区间（半开 [startMs, endMs)）。endMs <= startMs 的退化输入在合并时丢弃。 */
    data class Span(val startMs: Long, val endMs: Long)

    /** 扫线并集：排序后依次吞并重叠/相邻（end == 下一 start 视为相邻，可并）区间。 */
    fun union(spans: List<Span>): List<Span> {
        val sorted = spans
            .filter { it.endMs > it.startMs }
            .sortedBy { it.startMs }
        if (sorted.isEmpty()) return emptyList()
        val out = mutableListOf<Span>()
        var cur = sorted[0]
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.startMs <= cur.endMs) {
                // 重叠或相邻：延伸当前段（含入折叠：next 完全在 cur 内则 end 不变）
                cur = Span(cur.startMs, max(cur.endMs, next.endMs))
            } else {
                out.add(cur)
                cur = next
            }
        }
        out.add(cur)
        return out
    }

    /** kind 过滤后的实体 → Span。 */
    private fun spansOf(rows: List<ScreenSessionEntity>, kind: String): List<Span> =
        rows.filter { it.kind == kind }.map { Span(it.startMs, it.endMs) }

    /** 实体版并集（给管道复用：输出直接回写 screen_session 表）。 */
    fun unionEntities(rows: List<ScreenSessionEntity>, kind: String): List<ScreenSessionEntity> =
        union(spansOf(rows, kind)).map { ScreenSessionEntity(it.startMs, it.endMs, kind) }

    /**
     * 逐日重算（§3-5 口径，与解析器切片算法同源、毫秒域）：
     * - screenMin = 亮屏并集时长（不减锁屏）；
     * - unlockMin（= screenUnlockMin）= 亮屏并集 − 锁屏并集的重叠（sub_kg 口径）；
     * - longestMin = 当日最长「亮屏 − 锁屏」连续段（逐日切片，不跨零点复制）；
     * - hours = 24 桶分钟（unlock 口径，§6 红线）。
     */
    data class DaySlice(
        val screenMin: Int,
        val unlockMin: Int,
        val longestMin: Int,
        val hours: List<Int>
    )

    fun daySlices(
        screenRows: List<ScreenSessionEntity>,
        keyguardRows: List<ScreenSessionEntity>,
        zone: ZoneId
    ): Map<LocalDate, DaySlice> {
        val screen = union(spansOf(screenRows, ScreenSessionEntity.KIND_SCREEN))
        if (screen.isEmpty()) return emptyMap()
        val kg = union(spansOf(keyguardRows, ScreenSessionEntity.KIND_KEYGUARD))

        // kg 并集有序且互不重叠；区间数很小（单日两位数），线性扫足够、不易错
        fun kgOverlap(a: Long, b: Long): Long {
            var res = 0L
            for (k in kg) {
                if (k.endMs <= a) continue
                if (k.startMs >= b) break
                res += min(b, k.endMs) - max(a, k.startMs)
            }
            return res
        }

        class Acc {
            var screenMs = 0L
            var unlockMs = 0L
            var longestMs = 0L
            val hours = DoubleArray(24)
        }

        val acc = mutableMapOf<LocalDate, Acc>()
        for (s in screen) {
            var cur = s.startMs
            while (cur < s.endMs) {
                val date = Instant.ofEpochMilli(cur).atZone(zone).toLocalDate()
                val dayEnd = minOf(s.endMs, nextDayStart(cur, zone))
                val a = acc.getOrPut(date) { Acc() }
                a.screenMs += dayEnd - cur
                val unlocked = (dayEnd - cur - kgOverlap(cur, dayEnd)).coerceAtLeast(0L)
                a.unlockMs += unlocked
                a.longestMs = max(a.longestMs, unlocked)
                var h = cur
                while (h < dayEnd) {
                    val hour = Instant.ofEpochMilli(h).atZone(zone).hour
                    val hEnd = minOf(dayEnd, nextHourStart(h, zone))
                    a.hours[hour] += (hEnd - h - kgOverlap(h, hEnd)).coerceAtLeast(0L) / 60000.0
                    h = hEnd
                }
                cur = dayEnd
            }
        }

        return acc.mapValues { (_, a) ->
            DaySlice(
                screenMin = (a.screenMs / 60000.0).roundToInt(),
                unlockMin = (a.unlockMs / 60000.0).roundToInt(),
                longestMin = (a.longestMs / 60000.0).roundToInt(),
                hours = a.hours.map { it.roundToInt() }
            )
        }
    }

    private fun nextDayStart(ms: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().plusDays(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()

    private fun nextHourStart(ms: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()
            .truncatedTo(ChronoUnit.HOURS).plusHours(1)
            .atZone(zone).toInstant().toEpochMilli()
}
