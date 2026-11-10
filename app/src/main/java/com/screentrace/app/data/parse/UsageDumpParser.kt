package com.screentrace.app.data.parse

import com.screentrace.app.contract.NotificationEvent
import com.screentrace.app.contract.NotificationType
import com.screentrace.app.contract.UnlockEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.roundToInt

/** 逐日指标（§3-5）。 */
data class DayMetrics(
    val screenMin: Int,
    val screenUnlockMin: Int,
    val unlocks: Int,
    val launches: Int,
    val longestSessionMin: Int
)

/** 应用时长行（§3-6 系统聚合口径；date=区间归属日）。 */
data class AppUsageRow(val date: LocalDate, val pkg: String, val totalMs: Long, val launches: Int)

/**
 * 解析输出（两段 dump 文本 → 数据类，§6 纯 Kotlin 模块）。
 * hourMinutes：24 桶分钟，**screen_unlock 口径**（§6 红线，不得直加亮屏片长）。
 */
data class ParseResult(
    val days: Map<LocalDate, DayMetrics>,
    val hourMinutes: Map<LocalDate, List<Int>>,
    val appUsage: List<AppUsageRow>,
    val unlockEvents: List<UnlockEvent>,
    val notificationEvents: List<NotificationEvent>
)

/**
 * §2–§3 解析器（照抄 `parse_usage3.py` 已对账口径 + CR-01 修正）：
 * - **两级事件**（§2.2 坑 2）：区间层（亮屏/锁屏配对）秒级去重；
 *   计数层（解锁/通知）保留毫秒，checkin/明文双源按 `(秒, type, pkg)` 分组取条数较大的一边；
 * - `totalTimeUsed="54:01"` 两段式是 **分:秒**，三段式才是时:分:秒（§2.2 坑 1）；
 * - 真人区间过滤：区间内包数 > 50（§2.2，user=666/999 后台账户排除）；
 * - 自然日切分 `YYYY-MM-DD` + 设备本地时区（§3-4；`parse_usage_v2.py` 的 `MM-DD`
 *   与硬编码时区为遗留，不得照抄）；
 * - `screen_unlock` = 亮屏区间扣除与锁屏区间重叠部分（sub_kg，对外口径 §3-5）；
 * - 最长连续 = 单个亮屏区间的 sub_kg 分钟最大值（§3-5）；
 * - 小时矩阵用 **sub_kg 口径**（§6 红线）：小时合计与同日 screen_unlock 取整误差内一致；
 * - 应用时长按区间归属日汇总：归属日 = 区间结束前 1 分钟所在自然日（对账参照同口径），
 *   当天数据由明文 In-memory 段补充；
 * - 事件流（CR-01）：KEYGUARD_HIDDEN → 解锁事件；NOTIFICATION_INTERRUPTION/SEEN
 *   → 通知事件（毫秒、类型、包名、channelId 可空），毫秒逐条保留。
 */
object UsageDumpParser {

    private val EV_CHECKIN = Regex("""time=(\d+) type=(\w+) package=(\S+)""")
    private val EV_HUMAN = Regex("""time="(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})" type=(\w+) package=(\S+)""")
    private val RANGE = Regex("""beginTime=(\d+) endTime=(\d+)""")
    private val PKG_CHECKIN = Regex("""package=(\S+) totalTimeUsed=(\d+) lastTimeUsed=(\d+).*?appLaunchCount=(\d+)""")
    private val PKG_HUMAN = Regex("""package=(\S+) totalTimeUsed="([\d:]+)" .*?appLaunchCount=(\d+)""")
    private val SCREEN_CHECKIN = Regex("""screen-interactive: (\d+)x for (\d+)""")
    private val SCREEN_HUMAN = Regex("""screen-interactive: (\d+)x for "([\d:]+)"""")
    private val CHANNEL = Regex("""channelId=(\S+)""")
    private val HUMAN_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private data class RawEvent(val ms: Long, val type: String, val pkg: String, val channelId: String?)
    private data class PkgAgg(val pkg: String, val totalMs: Long, val launches: Int)
    private data class RawInterval(val beginMs: Long, val endMs: Long, val pkgs: MutableList<PkgAgg>, var screenAggMs: Long)

    fun parse(
        checkinText: String,
        humanText: String,
        zone: ZoneId = ZoneId.systemDefault()
    ): ParseResult {
        val checkinEvents = mutableListOf<RawEvent>()
        val intervals = mutableListOf<RawInterval>()
        var current: RawInterval? = null

        for (line in checkinText.lineSequence()) {
            val rangeMatch = RANGE.find(line)
            val evMatch = EV_CHECKIN.find(line)
            val screenMatch = SCREEN_CHECKIN.find(line)
            val pkgMatch = PKG_CHECKIN.find(line)
            when {
                rangeMatch != null -> {
                    current = RawInterval(
                        rangeMatch.groupValues[1].toLong(),
                        rangeMatch.groupValues[2].toLong(),
                        mutableListOf(),
                        0L
                    )
                    intervals.add(current!!)
                }
                // 尾部 Broadcast/Notification/安装包列表段不混读：只认上述三种行的正则（§2.2）
                evMatch != null -> {
                    val type = EventTypes.normalize(evMatch.groupValues[2])
                    if (type != null) {
                        checkinEvents.add(
                            RawEvent(
                                evMatch.groupValues[1].toLong(),
                                type,
                                evMatch.groupValues[3],
                                CHANNEL.find(line)?.groupValues?.get(1)
                            )
                        )
                    }
                }
                screenMatch != null -> current?.screenAggMs = screenMatch.groupValues[2].toLong()
                pkgMatch != null -> current?.pkgs?.add(
                    PkgAgg(pkgMatch.groupValues[1], pkgMatch.groupValues[2].toLong(), pkgMatch.groupValues[4].toInt())
                )
            }
        }

        // 明文：In-memory daily stats 段（当天包聚合 + 屏幕聚合）+ Last 24 hour events（秒级事件）
        val todayPkgs = mutableListOf<PkgAgg>()
        var todayScreenMs = 0L
        val humanEvents = mutableListOf<RawEvent>()
        var inDaily = false
        for (line in humanText.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed == "In-memory daily stats") {
                inDaily = true
                continue
            }
            if (inDaily && trimmed.startsWith("In-memory weekly")) inDaily = false
            if (inDaily) {
                val pkgMatch = PKG_HUMAN.find(line)
                val screenMatch = SCREEN_HUMAN.find(line)
                when {
                    pkgMatch != null -> todayPkgs.add(
                        PkgAgg(pkgMatch.groupValues[1], durMs(pkgMatch.groupValues[2]), pkgMatch.groupValues[3].toInt())
                    )
                    screenMatch != null -> todayScreenMs = durMs(screenMatch.groupValues[2])
                }
            }
            // 事件行全文件扫（与对账参照同口径：明文两段是独立循环，事件段不在 In-memory 之后才开始）
            val evMatch = EV_HUMAN.find(line)
            if (evMatch != null) {
                val type = EventTypes.normalize(evMatch.groupValues[2])
                if (type != null) {
                    humanEvents.add(
                        RawEvent(
                            LocalDateTimeMs(evMatch.groupValues[1], zone),
                            type,
                            evMatch.groupValues[3],
                            CHANNEL.find(line)?.groupValues?.get(1)
                        )
                    )
                }
            }
        }

        // ---- 计数层（CR-01）：毫秒保留，双源按 (秒,type,pkg) 分组取条数较大的一边 ----
        val counting = mergeCountingEvents(checkinEvents, humanEvents)

        // ---- 区间层：秒级去重后配对（§3-2/§3-3）----
        val intervalEvents = (checkinEvents + humanEvents)
            .map { it.copy(ms = it.ms / 1000) } // 秒级键
            .distinctBy { Triple(it.ms, it.type, it.pkg) }
            .sortedBy { it.ms }

        val kg = mutableListOf<Pair<Long, Long>>()
        var kgOn: Long? = null
        for (e in intervalEvents) {
            when {
                e.type == "KEYGUARD_SHOWN" && kgOn == null -> kgOn = e.ms
                e.type == "KEYGUARD_HIDDEN" && kgOn != null -> {
                    kg.add(kgOn!! to e.ms); kgOn = null
                }
            }
        }
        kgOn?.let { kg.add(it to intervalEvents.last().ms) } // 未闭合截断

        val screens = mutableListOf<Pair<Long, Long>>()
        var on: Long? = null
        for (e in intervalEvents) {
            when {
                e.type == "SCREEN_INTERACTIVE" && on == null -> on = e.ms
                e.type == "SCREEN_NON_INTERACTIVE" && on != null -> {
                    screens.add(on!! to e.ms); on = null
                }
            }
        }
        on?.let { screens.add(it to intervalEvents.last().ms) } // 未闭合截断

        fun subKg(a: Long, b: Long): Double {
            var res = (b - a).toDouble()
            for ((s, e) in kg) {
                val ov = minOf(b, e) - maxOf(a, s)
                if (ov > 0) res -= ov
            }
            return max(res, 0.0)
        }

        // ---- 自然日 / 小时切分（sub_kg 口径，§6 红线）----
        val screenDay = mutableMapOf<LocalDate, Double>()
        val unlockDay = mutableMapOf<LocalDate, Double>()
        val hourMatrix = mutableMapOf<LocalDate, MutableList<Double>>()
        val longestDay = mutableMapOf<LocalDate, Double>()
        for ((a, b) in screens) {
            var cur = a
            while (cur < b) {
                val date = Instant.ofEpochMilli(cur * 1000).atZone(zone).toLocalDate()
                val dayEnd = minOf(b, nextDayStartMs(cur, zone))
                screenDay[date] = (screenDay[date] ?: 0.0) + (dayEnd - cur) / 60.0
                val u = subKg(cur, dayEnd)
                unlockDay[date] = (unlockDay[date] ?: 0.0) + u / 60.0
                longestDay[date] = max(longestDay[date] ?: 0.0, subKg(a, b) / 60.0)
                var hCur = cur
                while (hCur < dayEnd) {
                    val hour = Instant.ofEpochMilli(hCur * 1000).atZone(zone).hour
                    val hEnd = minOf(dayEnd, nextHourStartMs(hCur, zone))
                    val buckets = hourMatrix.getOrPut(date) { MutableList(24) { 0.0 } }
                    buckets[hour] += subKg(hCur, hEnd) / 60.0
                    hCur = hEnd
                }
                cur = dayEnd
            }
        }

        // ---- 逐日计数（计数层事件，毫秒已保真）----
        val unlockCount = mutableMapOf<LocalDate, Int>()
        val launchCount = mutableMapOf<LocalDate, Int>()
        for (e in counting) {
            val date = Instant.ofEpochMilli(e.ms).atZone(zone).toLocalDate()
            if (e.type == "KEYGUARD_HIDDEN") unlockCount[date] = (unlockCount[date] ?: 0) + 1
            if (e.type == "ACTIVITY_RESUMED") launchCount[date] = (launchCount[date] ?: 0) + 1
        }

        // ---- 应用聚合（§3-6）：真人区间按归属日 + 明文当天补 ----
        val appRows = mutableListOf<AppUsageRow>()
        for (iv in intervals) {
            if (iv.pkgs.size <= 50) continue // 只取真人区间（§2.2）
            val date = Instant.ofEpochMilli((iv.endMs - 60_000)).atZone(zone).toLocalDate()
            for (p in iv.pkgs) appRows.add(AppUsageRow(date, p.pkg, p.totalMs, p.launches))
        }
        val humanDate = humanEvents.maxOfOrNull { it.ms }
            ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            ?: Instant.ofEpochMilli(checkinEvents.maxOfOrNull { it.ms } ?: 0L * 1000)
                .atZone(zone).toLocalDate()
        for (p in todayPkgs) appRows.add(AppUsageRow(humanDate, p.pkg, p.totalMs, p.launches))

        // ---- 组装输出 ----
        val dates = (screenDay.keys + unlockCount.keys + launchCount.keys).toSortedSet()
        val days = dates.associateWith { d ->
            DayMetrics(
                screenMin = (screenDay[d] ?: 0.0).roundToInt(),
                screenUnlockMin = (unlockDay[d] ?: 0.0).roundToInt(),
                unlocks = unlockCount[d] ?: 0,
                launches = launchCount[d] ?: 0,
                longestSessionMin = (longestDay[d] ?: 0.0).roundToInt()
            )
        }
        val hourMinutes = hourMatrix.mapValues { (_, v) -> v.map { it.roundToInt() } }

        val unlockEvents = counting
            .filter { it.type == "KEYGUARD_HIDDEN" }
            .map { UnlockEvent(it.ms) }
        val notificationEvents = counting
            .filter { it.type == "NOTIFICATION_INTERRUPTION" || it.type == "NOTIFICATION_SEEN" }
            .map {
                NotificationEvent(
                    occurredAtMs = it.ms,
                    type = if (it.type == "NOTIFICATION_SEEN") NotificationType.SEEN
                    else NotificationType.INTERRUPTION,
                    pkg = it.pkg,
                    channelId = it.channelId
                )
            }

        return ParseResult(
            days = days,
            hourMinutes = hourMinutes,
            appUsage = appRows,
            unlockEvents = unlockEvents,
            notificationEvents = notificationEvents
        )
    }

    /**
     * CR-01（§2.2 坑 2）双源多重集合合并：按 (秒, type, pkg) 分组，
     * 取两组来源各自条数的**较大值**（整边），毫秒逐条保留。
     * checkin 为主源（毫秒精度高），条数相等时取 checkin 边。
     */
    private fun mergeCountingEvents(a: List<RawEvent>, b: List<RawEvent>): List<RawEvent> {
        val key: (RawEvent) -> Triple<Long, String, String> = { Triple(it.ms / 1000, it.type, it.pkg) }
        val ga = a.groupBy(key)
        val gb = b.groupBy(key)
        return (ga.keys + gb.keys).flatMap { k ->
            val la = ga[k].orEmpty().sortedBy { it.ms }
            val lb = gb[k].orEmpty().sortedBy { it.ms }
            if (la.size >= lb.size) la else lb
        }.sortedBy { it.ms }
    }

    /** §2.2 坑 1：两段式 "MM:SS" 是分:秒，三段式 "H:MM:SS" 才是时:分:秒。 */
    private fun durMs(text: String): Long {
        val parts = text.split(":").map { it.toInt() }
        val (h, m, s) = when (parts.size) {
            3 -> Triple(parts[0], parts[1], parts[2])
            else -> Triple(0, parts[0], parts[1])
        }
        return (h * 3600L + m * 60L + s) * 1000L
    }

    private fun LocalDateTimeMs(text: String, zone: ZoneId): Long =
        java.time.LocalDateTime.parse(text, HUMAN_TIME).atZone(zone).toInstant().toEpochMilli()

    /** 下一自然日 00:00 的秒级毫秒（事件时间轴为秒×1000 的伪毫秒值）。 */
    private fun nextDayStartMs(ms: Long, zone: ZoneId): Long {
        val date = Instant.ofEpochMilli(ms * 1000).atZone(zone).toLocalDate().plusDays(1)
        return date.atStartOfDay(zone).toInstant().toEpochMilli() / 1000
    }

    private fun nextHourStartMs(ms: Long, zone: ZoneId): Long {
        val t = Instant.ofEpochMilli(ms * 1000).atZone(zone)
        return t.toLocalDate().atTime(t.hour, 0).atZone(zone).toInstant().toEpochMilli() / 1000 + 3600
    }
}
