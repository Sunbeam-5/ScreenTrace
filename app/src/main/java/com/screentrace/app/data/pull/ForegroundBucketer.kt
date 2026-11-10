package com.screentrace.app.data.pull

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 前台区间 → 逐日逐小时分钟数（降级通道用，§2.1）。
 *
 * 抽成纯函数的原因：`UsageStatsManager` 的 `INTERVAL_DAILY` 桶边界不保证落在设备本地
 * 零点，直接按桶聚合会把跨零点的一段算错日；改成**按事件区间切小时**，与主通道
 * 解析器的小时矩阵口径一致（§3：小时矩阵是 screen_unlock 口径的 24 桶）。
 */
object ForegroundBucketer {

    /**
     * @param spans 毫秒区间 `[first, last)`，可乱序、可重叠
     * @return date → 24 个桶的分钟数（四舍五入，跨小时/跨零点自动切分）
     */
    fun bucket(spans: List<LongRange>, zone: ZoneId): Map<LocalDate, List<Int>> {
        if (spans.isEmpty()) return emptyMap()
        val acc = HashMap<LocalDate, DoubleArray>()
        for (span in spans) {
            var cursor = max(span.first, 0L)
            val end = span.last
            if (end <= cursor) continue
            while (cursor < end) {
                val zoned = Instant.ofEpochMilli(cursor).atZone(zone)
                val nextHourMs = zoned.truncatedTo(ChronoUnit.HOURS).plusHours(1)
                    .toInstant().toEpochMilli()
                val segEnd = min(end, nextHourMs)
                if (segEnd <= cursor) break
                acc.getOrPut(zoned.toLocalDate()) { DoubleArray(24) }[zoned.hour] +=
                    (segEnd - cursor) / 60_000.0
                cursor = segEnd
            }
        }
        return acc.mapValues { (_, buckets) -> buckets.map { it.roundToInt() } }
    }
}
