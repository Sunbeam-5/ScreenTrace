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
     * 降级通道的前后台事件。`enter = true` 对应 `ACTIVITY_RESUMED`，
     * `false` 对应 `ACTIVITY_PAUSED` / `ACTIVITY_STOPPED`。
     */
    data class ForegroundEvent(val atMs: Long, val pkg: String, val enter: Boolean)

    /** 一个应用的一段前台区间 `[startMs, endMs)`。 */
    data class Span(val pkg: String, val startMs: Long, val endMs: Long)

    /**
     * 把事件序列配成**互不重叠**的前台区间。
     *
     * **为什么不能只靠 `ACTIVITY_PAUSED` 收尾**：应用进程被系统杀掉 / 被强行停止时
     * 不会有 paused 事件。若只信 paused，那段区间会一路延到拉取时刻（`endMs`），
     * 把之后所有应用的时间都算到它头上——排行页与时段分布会整页虚高，而且每次拉取的
     * 结果还不一样（虚高多少取决于拉取时刻），这种错极难在真机上被肉眼发现。
     *
     * 所以以「**下一个应用进入前台**」作为上一段的结束：这是 Android 前台语义的事实
     * （同一时刻只有一个应用在前台），即使中间丢了 paused 也不会错。
     *
     * 同一应用连续多次 RESUMED（切换 Activity）不重开区间，避免把一段切成多段。
     *
     * @param endMs 拉取时刻：仍在最前台的应用在这里收尾
     */
    fun spansOf(events: List<ForegroundEvent>, endMs: Long): List<Span> {
        if (events.isEmpty()) return emptyList()
        val out = mutableListOf<Span>()
        var pkg: String? = null
        var start = 0L

        fun close(at: Long) {
            val current = pkg ?: return
            if (at > start) out += Span(current, start, at)
            pkg = null
        }

        for (event in events.sortedBy { it.atMs }) {
            if (event.atMs < 0) continue
            if (event.enter) {
                // 换了应用：先在切换时刻关掉上一段（不依赖上段是否收到 paused）
                if (pkg != null && pkg != event.pkg) close(event.atMs)
                if (pkg == null) {
                    pkg = event.pkg
                    start = event.atMs
                }
            } else if (pkg == event.pkg) {
                close(event.atMs)
            }
        }
        close(endMs) // 仍在最前台的截到拉取时刻
        return out
    }

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
