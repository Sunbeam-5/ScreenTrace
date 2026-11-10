package com.screentrace.app.data.parse

import com.screentrace.app.contract.NotificationEvent
import com.screentrace.app.contract.NotificationType
import com.screentrace.app.contract.UnlockEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

/**
 * 应用时长行（§3-6 系统聚合口径）。
 * **一行一日一包**：同日同包跨账户求和、In-memory 与区间取大（批 5 返修 P1-6）；
 * 入库「同 (date,pkg) 取较大值」语义只用于**重拉同日**合并，不适用于本列表内部。
 */
data class AppUsageRow(val date: LocalDate, val pkg: String, val totalMs: Long, val launches: Int)

/**
 * 解析输出（两段 dump 文本 → 数据类，§6 纯 Kotlin 模块）。
 * hourMinutes：24 桶分钟，**screen_unlock 口径**（§6 红线）。
 */
data class ParseResult(
    val days: Map<LocalDate, DayMetrics>,
    val hourMinutes: Map<LocalDate, List<Int>>,
    val appUsage: List<AppUsageRow>,
    val unlockEvents: List<UnlockEvent>,
    val notificationEvents: List<NotificationEvent>
)

/**
 * §2–§3 解析器（照抄 `parse_usage3.py` 已对账口径 + CR-01 修正 + 批 5 返修）：
 *
 * **账户模型（CR-02 / 返修 P0-1）**：checkin 与明文均按 `user=N` 分块。真机实测
 * user=666 为隐私空间、user=999 为手机分身，均为真人使用的独立空间——区间、
 * In-memory、应用事件**全账户保留**；但系统级事件（SCREEN_ 与 KEYGUARD_ 系列，各账户
 * 同源广播镜像）必须**跨账户收敛**（同 type+pkg、|Δt|≤1000ms 贪心合并），
 * 否则解锁/亮屏事件被三个账户各记一遍（实测 2782 vs 真实 ≈935）。
 * 应用级事件（ACTIVITY_RESUMED / 通知等）是各账户独立数据，保持毫秒多重集合
 * （同秒多条真实通知不丢——CR-01 红线），只做双源取大合并。
 *
 * 其余口径：
 * - 两级事件（§2.2 坑 2）：区间层（亮屏/锁屏配对）秒级去重；计数层保留毫秒；
 *   双源按 (user, 秒, type, pkg) 分组取条数较大的一边；
 * - `totalTimeUsed="54:01"` 两段式是 **分:秒**（§2.2 坑 1）；
 * - 自然日 `YYYY-MM-DD` + 设备本地时区（§3-4，不照抄 MM-DD/硬编码时区）；
 * - `screen_unlock` = 亮屏扣除锁屏重叠（sub_kg）；最长连续 = **逐日切片**的
 *   sub_kg 分钟最大值（返修 P0-2：整段值不跨零点复制）；
 * - 小时矩阵 sub_kg 口径（§6 红线）；
 * - 应用时长按区间归属日（end-60s 同对账参照）；In-memory 归属
 *   `today` 参数（默认取明文事件最新日期，批 6b 可显式传当天）；
 * - 事件流（CR-01）：收敛后 KEYGUARD_HIDDEN → UnlockEvent；通知 → NotificationEvent。
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
    private val USER_HEADER = Regex("""^\s*user=(\d+)""")
    private val HUMAN_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** 系统级镜像事件类型：各 profile 同源广播，需跨账户收敛（返修 P0-1）。 */
    private val MIRROR_TYPES = setOf(
        "SCREEN_INTERACTIVE", "SCREEN_NON_INTERACTIVE", "KEYGUARD_SHOWN", "KEYGUARD_HIDDEN"
    )

    /** 跨账户收敛容差（毫秒）。DeepSeek 实测 ±1000ms 后稳定（±2s/3s 无变化）。 */
    private const val MIRROR_TOLERANCE_MS = 1000L

    private data class RawEvent(
        val ms: Long,
        val type: String,
        val pkg: String,
        val channelId: String?,
        val user: Int
    )

    private data class PkgAgg(val pkg: String, val totalMs: Long, val launches: Int)

    private data class RawInterval(
        val user: Int,
        val beginMs: Long,
        val endMs: Long,
        val pkgs: MutableList<PkgAgg> = mutableListOf(),
        var screenAggMs: Long = 0L
    )

    fun parse(
        checkinText: String,
        humanText: String,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate? = null
    ): ParseResult {
        // ---------- checkin：user 块跟踪 + 区间/包聚合/事件 ----------
        val checkinEvents = mutableListOf<RawEvent>()
        val intervals = mutableListOf<RawInterval>()
        var currentUser = 0
        var current: RawInterval? = null
        for (line in checkinText.lineSequence()) {
            USER_HEADER.find(line)?.let { currentUser = it.groupValues[1].toInt() }
            val rangeMatch = RANGE.find(line)
            val evMatch = EV_CHECKIN.find(line)
            val screenMatch = SCREEN_CHECKIN.find(line)
            val pkgMatch = PKG_CHECKIN.find(line)
            when {
                rangeMatch != null -> {
                    current = RawInterval(
                        user = currentUser,
                        beginMs = rangeMatch.groupValues[1].toLong(),
                        endMs = rangeMatch.groupValues[2].toLong()
                    )
                    intervals.add(current!!)
                }
                // 尾部 Broadcast/Notification/安装包列表段不混读（§2.2）
                evMatch != null -> {
                    val type = EventTypes.normalize(evMatch.groupValues[2])
                    if (type != null) {
                        checkinEvents.add(
                            RawEvent(
                                evMatch.groupValues[1].toLong(),
                                type,
                                evMatch.groupValues[3],
                                CHANNEL.find(line)?.groupValues?.get(1),
                                currentUser
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

        // ---------- 明文：In-memory 段按 user 归属（返修 P1-5）+ 事件全文件扫 ----------
        val humanTodayPkgs = mutableMapOf<Int, MutableList<PkgAgg>>() // user → In-memory 包
        val humanEvents = mutableListOf<RawEvent>()
        var inDaily = false
        var humanUser = 0
        for (line in humanText.lineSequence()) {
            val trimmed = line.trim()
            USER_HEADER.find(line)?.let {
                humanUser = it.groupValues[1].toInt()
                inDaily = false // 新 user 块，旧 In-memory 段终止
            }
            if (trimmed == "In-memory daily stats") {
                inDaily = true
                humanTodayPkgs.getOrPut(humanUser) { mutableListOf() }
                continue
            }
            if (inDaily && trimmed.startsWith("In-memory weekly")) inDaily = false
            if (inDaily) {
                val pkgMatch = PKG_HUMAN.find(line)
                if (pkgMatch != null) {
                    humanTodayPkgs.getOrPut(humanUser) { mutableListOf() }.add(
                        PkgAgg(pkgMatch.groupValues[1], durMs(pkgMatch.groupValues[2]), pkgMatch.groupValues[3].toInt())
                    )
                }
            }
            val evMatch = EV_HUMAN.find(line)
            if (evMatch != null) {
                val type = EventTypes.normalize(evMatch.groupValues[2])
                if (type != null) {
                    humanEvents.add(
                        RawEvent(
                            LocalDateTimeMs(evMatch.groupValues[1], zone),
                            type,
                            evMatch.groupValues[3],
                            CHANNEL.find(line)?.groupValues?.get(1),
                            humanUser
                        )
                    )
                }
            }
        }

        // ---------- 计数层：双源取大（按 user,秒,type,pkg）→ 系统镜像跨账户收敛 ----------
        val merged = mergeCountingEvents(checkinEvents, humanEvents)
        val counting = convergeMirrorEvents(merged)

        // ---------- 区间层：秒级去重（跨账户天然去重，已对账时长口径）----------
        val intervalEvents = (checkinEvents + humanEvents)
            .map { it.copy(ms = it.ms / 1000) }
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
        kgOn?.let { kg.add(it to intervalEvents.last().ms) }

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
        on?.let { screens.add(it to intervalEvents.last().ms) }

        fun subKg(a: Long, b: Long): Double {
            var res = (b - a).toDouble()
            for ((s, e) in kg) {
                val ov = minOf(b, e) - maxOf(a, s)
                if (ov > 0) res -= ov
            }
            return max(res, 0.0)
        }

        // ---------- 自然日 / 小时切分（sub_kg 口径）----------
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
                val sliceUnlock = subKg(cur, dayEnd) // 返修 P0-2：逐日切片，不跨零点复制整段
                unlockDay[date] = (unlockDay[date] ?: 0.0) + sliceUnlock / 60.0
                longestDay[date] = max(longestDay[date] ?: 0.0, sliceUnlock / 60.0)
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

        // ---------- 逐日计数（收敛后事件）----------
        val unlockCount = mutableMapOf<LocalDate, Int>()
        val launchCount = mutableMapOf<LocalDate, Int>()
        for (e in counting) {
            val date = Instant.ofEpochMilli(e.ms).atZone(zone).toLocalDate()
            if (e.type == "KEYGUARD_HIDDEN") unlockCount[date] = (unlockCount[date] ?: 0) + 1
            if (e.type == "ACTIVITY_RESUMED") launchCount[date] = (launchCount[date] ?: 0) + 1
        }

        // ---------- 应用聚合（返修 P1-5/P1-6）----------
        // 1) 区间碎片按 (user, 归属日, pkg) 求和（全账户保留，不再 >50 包过滤——CR-02）
        val perUserInterval = mutableMapOf<Triple<Int, LocalDate, String>, PkgAgg>()
        for (iv in intervals) {
            val date = Instant.ofEpochMilli(iv.endMs - 60_000).atZone(zone).toLocalDate()
            for (p in iv.pkgs) {
                val key = Triple(iv.user, date, p.pkg)
                val prev = perUserInterval[key]
                perUserInterval[key] = PkgAgg(p.pkg, (prev?.totalMs ?: 0) + p.totalMs, (prev?.launches ?: 0) + p.launches)
            }
        }
        // 2) In-memory 按 (user, today, pkg)；归属日 = today 参数 / 明文事件最新日期
        val inferredToday = today
            ?: humanEvents.maxOfOrNull { Instant.ofEpochMilli(it.ms).atZone(zone).toLocalDate() }
            ?: LocalDate.now(zone)
        val perUserHuman = mutableMapOf<Triple<Int, LocalDate, String>, PkgAgg>()
        for ((user, pkgs) in humanTodayPkgs) {
            for (p in pkgs) {
                val key = Triple(user, inferredToday, p.pkg)
                val prev = perUserHuman[key]
                perUserHuman[key] = PkgAgg(p.pkg, (prev?.totalMs ?: 0) + p.totalMs, (prev?.launches ?: 0) + p.launches)
            }
        }
        // 3) 同 user 同 (date,pkg)：In-memory 与区间**取大**（补采不叠加）
        val perUser = mutableMapOf<Triple<Int, LocalDate, String>, PkgAgg>()
        for (key in perUserInterval.keys + perUserHuman.keys) {
            val iv = perUserInterval[key]
            val hm = perUserHuman[key]
            perUser[key] = when {
                iv == null -> hm!!
                hm == null -> iv
                hm.totalMs >= iv.totalMs -> hm
                else -> iv
            }
        }
        // 4) 跨账户求和 → 一行一日一包；滤 0 时长
        val appRows = perUser.entries
            .groupBy({ it.key.second to it.key.third }) { it.value }
            .map { (datePkg, rows) ->
                AppUsageRow(
                    date = datePkg.first,
                    pkg = datePkg.second,
                    totalMs = rows.sumOf { it.totalMs },
                    launches = rows.sumOf { it.launches }
                )
            }
            .filter { it.totalMs > 0 }

        // ---------- 组装 ----------
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

        return ParseResult(
            days = days,
            hourMinutes = hourMinutes,
            appUsage = appRows,
            unlockEvents = counting.filter { it.type == "KEYGUARD_HIDDEN" }.map { UnlockEvent(it.ms) },
            notificationEvents = counting
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
        )
    }

    /**
     * 双源多重集合合并（§2.2 坑 2）：按 (user, 秒, type, pkg) 分组，
     * 取两源条数的较大值（整边），毫秒逐条保留。checkin 条数相等时优先。
     */
    private fun mergeCountingEvents(a: List<RawEvent>, b: List<RawEvent>): List<RawEvent> {
        val key: (RawEvent) -> List<Any> = { listOf(it.user, it.ms / 1000, it.type, it.pkg) }
        val ga = a.groupBy(key)
        val gb = b.groupBy(key)
        return (ga.keys + gb.keys).flatMap { k ->
            val la = ga[k].orEmpty().sortedBy { it.ms }
            val lb = gb[k].orEmpty().sortedBy { it.ms }
            if (la.size >= lb.size) la else lb
        }.sortedBy { it.ms }
    }

    /**
     * 系统镜像事件**跨账户**收敛（返修 P0-1）：同 (type, pkg) 内不同 user、
     * |Δt| ≤ 1000ms 的视为同一物理事件（各 profile 同源广播），保留最早一条；
     * 同一 user 的相邻事件不合并（「逐条输出」语义，同账户快速重复解锁保留）。
     * **只作用于 MIRROR_TYPES**——应用级事件保持多重集合，同秒多条真实通知不丢（CR-01 红线）。
     */
    private fun convergeMirrorEvents(events: List<RawEvent>): List<RawEvent> {
        val (mirror, app) = events.partition { it.type in MIRROR_TYPES }
        val kept = mirror
            .groupBy { it.type to it.pkg }
            .flatMap { (_, group) ->
                val sorted = group.sortedBy { it.ms }
                val result = mutableListOf<RawEvent>()
                for (e in sorted) {
                    val duplicated = result.any {
                        it.user != e.user && kotlin.math.abs(it.ms - e.ms) <= MIRROR_TOLERANCE_MS
                    }
                    if (!duplicated) result.add(e)
                }
                result
            }
        return (kept + app).sortedBy { it.ms }
    }

    /** §2.2 坑 1：两段式 "MM:SS" 是分:秒，三段式才是时:分:秒。 */
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

    private fun nextDayStartMs(ms: Long, zone: ZoneId): Long {
        val date = Instant.ofEpochMilli(ms * 1000).atZone(zone).toLocalDate().plusDays(1)
        return date.atStartOfDay(zone).toInstant().toEpochMilli() / 1000
    }

    private fun nextHourStartMs(ms: Long, zone: ZoneId): Long {
        val t = Instant.ofEpochMilli(ms * 1000).atZone(zone)
        return t.toLocalDate().atTime(t.hour, 0).atZone(zone).toInstant().toEpochMilli() / 1000 + 3600
    }
}
