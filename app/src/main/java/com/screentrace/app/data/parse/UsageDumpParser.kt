package com.screentrace.app.data.parse

import com.screentrace.app.contract.NotificationEvent
import com.screentrace.app.contract.NotificationType
import com.screentrace.app.contract.UnlockEvent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

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
 * 亮屏/锁屏**会话区间**（毫秒，批 6g 三轮）：SCREEN_INTERACTIVE→NON_INTERACTIVE
 * （或 KEYGUARD_SHOWN→HIDDEN）的配对结果。入库后跨拉取做扫线真并集
 * （见 [com.screentrace.app.data.SessionUnion]），治 MIUI 滚动缓冲的少算。
 */
data class SessionSpan(val startMs: Long, val endMs: Long)

/**
 * 解析输出（两段 dump 文本 → 数据类，§6 纯 Kotlin 模块）。
 * hourMinutes：24 桶分钟，**screen_unlock 口径**（§6 红线）。
 */
data class ParseResult(
    val days: Map<LocalDate, DayMetrics>,
    val hourMinutes: Map<LocalDate, List<Int>>,
    val appUsage: List<AppUsageRow>,
    val unlockEvents: List<UnlockEvent>,
    val notificationEvents: List<NotificationEvent>,
    /** 本次拉取见到的亮屏会话（毫秒）；降级通道为空。 */
    val screenSessions: List<SessionSpan> = emptyList(),
    /** 本次拉取见到的锁屏会话（毫秒）；降级通道为空。 */
    val keyguardSessions: List<SessionSpan> = emptyList()
) {
    /**
     * 「空 dump」判定（§5.8 独立状态）：五类产物**全空**才算空。
     *
     * 必须是**单一出处**——入库管道用它决定「不记成功」，拉取协调器用它返回
     * [com.screentrace.app.data.pull.PullOutcome.EmptyDump]。两处各写一份的话，
     * 一旦某天补了新产物类型（如未来的亮度事件），就会出现「管道认为非空、界面认为空」
     * 的状态错乱。
     */
    val isEmpty: Boolean
        get() = days.isEmpty() && hourMinutes.isEmpty() && appUsage.isEmpty() &&
            unlockEvents.isEmpty() && notificationEvents.isEmpty() &&
            screenSessions.isEmpty() && keyguardSessions.isEmpty()
}

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
 * - 应用时长按区间归属日（end-60s 同对账参照）；In-memory **按其 `timeRange` 分摊到多日**
 *   （真机实测窗口常跨 2 天、约 23.7 小时，全归一天会让单日应用合计超过当日屏幕时长，
 *   进而被入库自洽性校验整日丢弃——详见 [dayWeights]）；
 * - 事件流（CR-01）：收敛后 KEYGUARD_HIDDEN → UnlockEvent；通知 → NotificationEvent。
 */
object UsageDumpParser {

    private val EV_CHECKIN = Regex("""time=(\d+) type=(\w+) package=(\S+)""")
    private val EV_HUMAN = Regex("""time="(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})" type=(\w+) package=(\S+)""")
    private val RANGE = Regex("""beginTime=(\d+) endTime=(\d+)""")
    private val PKG_CHECKIN = Regex("""package=(\S+) totalTimeUsed=(\d+) lastTimeUsed=(\d+).*?appLaunchCount=(\d+)""")
    /**
     * 明文 In-memory 的包行：`package=com.foo totalTimeUsed="23:06:08" lastTimeUsed="…"`
     *
     * **不把 `appLaunchCount` 写进这个正则**：真机上主空间那一段 223 个包行里只有 132 行
     * 带该字段（为 0 时系统省略），要求它会把 91 个包连同其真实时长一起丢掉——表现为
     * 「排行页只剩 4 个应用，米家以外全不见」。
     */
    private val PKG_HUMAN = Regex("""package=(\S+) totalTimeUsed="([\d:]+)"""")
    private val LAUNCH_COUNT = Regex("""appLaunchCount=(\d+)""")
    private val SCREEN_CHECKIN = Regex("""screen-interactive: (\d+)x for (\d+)""")
    private val SCREEN_HUMAN = Regex("""screen-interactive: (\d+)x for "([\d:]+)"""")
    private val CHANNEL = Regex("""channelId=(\S+)""")
    private val USER_HEADER = Regex("""^\s*user=(\d+)""")
    private val HUMAN_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * 明文 `In-memory daily stats` 段的时间窗：`timeRange="2026/11/10 09:15 – 2026/11/11 08:55"`。
     *
     * 分隔符在不同 ROM / Android 版本上见过 en dash（U+2013）、连字符、波浪号三种，
     * 一律按「任意非数字非空格符号」接受，别把解析挂在排版字符上。
     */
    private val HUMAN_RANGE = Regex(
        """timeRange="(\d{4}/\d{1,2}/\d{1,2} \d{1,2}:\d{2})\s*[^\d\s]\s*(\d{4}/\d{1,2}/\d{1,2} \d{1,2}:\d{2})""""
    )
    private val HUMAN_RANGE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/M/d H:mm")

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
        val humanRanges = mutableMapOf<Int, Pair<Long, Long>>() // user → In-memory 窗口
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
            // 窗口边界只认 In-memory 段内的那一行：`Last 24 hour events` 也带 timeRange，
            // 且写在前面，不过它的窗口对分摊同样有效——只是 daily 段会覆盖它，取更准的那个。
            if (inDaily) {
                HUMAN_RANGE.find(line)?.let {
                    val s = LocalDateTime.parse(it.groupValues[1], HUMAN_RANGE_TIME)
                        .atZone(zone).toInstant().toEpochMilli()
                    val e = LocalDateTime.parse(it.groupValues[2], HUMAN_RANGE_TIME)
                        .atZone(zone).toInstant().toEpochMilli()
                    if (e > s) humanRanges[humanUser] = s to e
                }
            }
            if (inDaily && trimmed.startsWith("In-memory weekly")) inDaily = false
            if (inDaily) {
                val pkgMatch = PKG_HUMAN.find(line)
                if (pkgMatch != null) {
                    humanTodayPkgs.getOrPut(humanUser) { mutableListOf() }.add(
                        PkgAgg(
                            pkgMatch.groupValues[1],
                            durMs(pkgMatch.groupValues[2]),
                            // 缺字段 = 系统记为 0（不是「拿不到」）：日级启动数另由
                            // ACTIVITY_RESUMED 计数给出，这里只用于应用级展示
                            LAUNCH_COUNT.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        )
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

        // ---------- 应用时长：事件流推导（bug 13）----------
        // 真机现象：圆环写 9:42，应用构成只有 28 分钟——差了一个数量级。
        // 根因不是「分身没算」，而是**两个口径的时间窗口根本不同**：
        //   应用时长原来只有两个来源 → ① checkin 的 `packages:` 段（本 ROM 的
        //   `--checkin` 只给元信息，没有这段）；② 明文 In-memory 段（系统会重置，
        //   真机上只剩 09:13–09:50 共 37 分钟）。
        //   屏幕时长来自 SCREEN_INTERACTIVE/NON_INTERACTIVE 事件流，覆盖整个 24 小时。
        //
        // 这里把它们同源化：用 ACTIVITY_RESUMED/PAUSED/STOPPED 配对出前台区间，
        // 与屏幕时长吃同一批事件、同一个窗口。分身（user=999）的事件天然带 user，
        // 按 user 分别配对后自动计入，不需要额外分支。
        val eventEndMs = maxOf(
            merged.maxOfOrNull { it.ms } ?: 0L,
            humanRanges.values.maxOfOrNull { it.second } ?: 0L
        )
        val (eventForegroundMs, eventLaunches) = foregroundFromEvents(counting, zone, eventEndMs)

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
        // 2) In-memory 按 (user, 归属日, pkg)：窗口跨天时**按天分摊**（见 [dayWeights]）；
        //    调用方显式传 today 时按旧口径全归当天（批 6b 的「今日」语义）。
        val inferredToday = today
            ?: humanEvents.maxOfOrNull { Instant.ofEpochMilli(it.ms).atZone(zone).toLocalDate() }
            ?: LocalDate.now(zone)
        val perUserHuman = mutableMapOf<Triple<Int, LocalDate, String>, PkgAgg>()
        for ((user, pkgs) in humanTodayPkgs) {
            val weights: Map<LocalDate, Double> = when {
                today != null -> mapOf(today to 1.0)
                humanRanges[user] != null ->
                    dayWeights(humanRanges.getValue(user), screenDay, zone, inferredToday)
                else -> mapOf(inferredToday to 1.0)
            }
            for (p in pkgs) {
                for ((date, w) in weights) {
                    val key = Triple(user, date, p.pkg)
                    val prev = perUserHuman[key]
                    perUserHuman[key] = PkgAgg(
                        p.pkg,
                        (prev?.totalMs ?: 0) + (p.totalMs * w).roundToLong(),
                        (prev?.launches ?: 0) + (p.launches * w).roundToInt()
                    )
                }
            }
        }
        // 3) 同 user 同 (date,pkg)：三个来源**取大**，不是相加——
        //    区间片段、In-memory 累计、事件流推导观测的是同一件事（这个包那天在前台多久），
        //    相加必然双计。
        //
        //    为什么是取大而不是「事件流优先」：真机对账样本（reference/sample-checkin.txt）
        //    里 In-memory 给 356.7 分钟、事件流只配出 104 分钟——事件流在窗口边界、
        //    进程被杀、前台服务等场景下会**系统性低估**。反过来，In-memory 在窗口被
        //    重置时又会只剩几十分钟。两者谁高谁更接近真实，没法事先判断，取大最稳：
        //    · 窗口完整时 In-memory 高 → 用它（样本情形）
        //    · 窗口被重置时事件流高 → 用它（真机「应用构成只有 28 分钟」情形）
        //    真正的荒谬值（单应用 > 24h、合计 > 参照 3 倍）由 [IngestSanity] 兜底。
        val perUser = mutableMapOf<Triple<Int, LocalDate, String>, PkgAgg>()
        for (key in perUserInterval.keys + perUserHuman.keys + eventForegroundMs.keys) {
            val iv = perUserInterval[key]
            val hm = perUserHuman[key]
            val fromEvents = eventForegroundMs[key] ?: 0L
            val launchesFromEvents = eventLaunches[key] ?: 0
            val bestParsed = listOfNotNull(iv, hm).maxByOrNull { it.totalMs }
            perUser[key] = when {
                bestParsed == null -> PkgAgg(key.third, fromEvents, launchesFromEvents)
                fromEvents > bestParsed.totalMs -> PkgAgg(
                    key.third,
                    fromEvents,
                    // 启动数取较大：事件流的 RESUMED 计数比跨天分摊过的 appLaunchCount 更贴近实际
                    maxOf(launchesFromEvents, bestParsed.launches)
                )
                else -> bestParsed
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
            // 区间层是秒粒度（intervalEvents 已 /1000），入会话表前回乘毫秒
            screenSessions = screens.map { SessionSpan(it.first * 1000, it.second * 1000) },
            keyguardSessions = kg.map { SessionSpan(it.first * 1000, it.second * 1000) },
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

    /**
     * In-memory 窗口跨天时，把包时长按天分摊的权重（和为 1）。
     *
     * 真机（小米 2407FRK8EC / Android 16）的 `In-memory daily stats` 窗口是
     * `2026/11/10 09:15 – 2026/11/11 08:55`，约 23.7 小时、横跨两天；而
     * `totalTimeUsed` 是**整段累计**，系统不告诉你怎么分到两天。
     *
     * 不分摊的后果（实测）：23 小时的累计全压到后一天 → 单日应用合计 1420 分钟，
     * 而当天屏幕时长只有 538 分钟 → 入库自洽性校验判定脏数据、整日丢弃 →
     * 「屏幕时长有数、应用排行空白」，同时前一天的应用明细凭空消失。
     *
     * 权重取**当天屏幕时长**：应用在前台的时间与屏幕亮着的时间强相关，比按窗口
     * 时长均摊更接近真实。某日没有屏幕数据时退回「窗口与该日的重叠时长」。
     */
    private fun dayWeights(
        range: Pair<Long, Long>,
        screenDay: Map<LocalDate, Double>,
        zone: ZoneId,
        fallbackDate: LocalDate
    ): Map<LocalDate, Double> {
        val (startMs, endMs) = range
        val last = Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate()
        var d = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
        val days = mutableListOf<LocalDate>()
        while (!d.isAfter(last)) {
            days.add(d)
            d = d.plusDays(1)
        }
        val raw = days.associateWith { date ->
            val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val overlapMin =
                (minOf(endMs, dayEnd) - maxOf(startMs, dayStart)).coerceAtLeast(0L) / 60_000.0
            val screen = screenDay[date] ?: 0.0
            if (screen > 0) minOf(screen, overlapMin) else overlapMin
        }
        val total = raw.values.sum()
        return if (total > 0) raw.mapValues { it.value / total } else mapOf(fallbackDate to 1.0)
    }

    /**
     * 从事件流推导「(user, 自然日, 包) → 前台毫秒数」与启动次数。
     *
     * 配对规则（与降级通道 [com.screentrace.app.data.pull.ForegroundBucketer] 同一套思路，
     * 但这里是解析器内部的毫秒级事件，不复用它的 Pull 模型）：
     * - 每个 user **各自**维护一个「当前前台应用」：分身与主空间是两套独立前台栈；
     * - `ACTIVITY_RESUMED` 换应用时，用**新应用进入前台的时刻**收尾上一段——
     *   不能等 PAUSED，真机上进程被杀时根本没有 PAUSED，等它会一路延伸到窗口末尾；
     * - `ACTIVITY_PAUSED` / `ACTIVITY_STOPPED` 只在该包正是当前前台时收尾；
     * - 最后仍未收尾的区间收束到 [endMs]（= dump 里最后一条事件的时刻）。
     *
     * 跨零点按自然日切开：23:50 用到 00:10 必须拆成两天的 10 分钟与 10 分钟，
     * 否则单日应用合计会凭空多出一截（与自洽性校验冲突）。
     */
    private fun foregroundFromEvents(
        events: List<RawEvent>,
        zone: ZoneId,
        endMs: Long
    ): Pair<Map<Triple<Int, LocalDate, String>, Long>, Map<Triple<Int, LocalDate, String>, Int>> {
        val ms = mutableMapOf<Triple<Int, LocalDate, String>, Long>()
        val launches = mutableMapOf<Triple<Int, LocalDate, String>, Int>()
        val sorted = events.asSequence()
            .filter {
                it.type == "ACTIVITY_RESUMED" ||
                    it.type == "ACTIVITY_PAUSED" ||
                    it.type == "ACTIVITY_STOPPED"
            }
            .sortedBy { it.ms }
            .toList()
        val open = mutableMapOf<Int, RawEvent>() // user → 当前前台的 RESUMED 事件

        for (e in sorted) {
            when (e.type) {
                "ACTIVITY_RESUMED" -> {
                    val prev = open[e.user]
                    if (prev != null && prev.pkg != e.pkg) {
                        addSpanToDay(ms, prev.user, prev.pkg, prev.ms, e.ms, zone)
                    }
                    // 同一应用重复 RESUMED（没有中间的 PAUSED）不重开区间，
                    // 否则会把「一直前台」切成若干段并各丢失一段
                    if (prev == null || prev.pkg != e.pkg) {
                        open[e.user] = e
                        val key = Triple(e.user, Instant.ofEpochMilli(e.ms).atZone(zone).toLocalDate(), e.pkg)
                        launches[key] = (launches[key] ?: 0) + 1
                    }
                }
                else -> {
                    val prev = open[e.user]
                    if (prev != null && prev.pkg == e.pkg) {
                        addSpanToDay(ms, prev.user, prev.pkg, prev.ms, e.ms, zone)
                        open.remove(e.user)
                    }
                }
            }
        }
        for (prev in open.values) {
            if (endMs > prev.ms) addSpanToDay(ms, prev.user, prev.pkg, prev.ms, endMs, zone)
        }
        return ms to launches
    }

    /** 把 [startMs, endMs) 按自然日切碎后累加到 [out]（毫秒）。 */
    private fun addSpanToDay(
        out: MutableMap<Triple<Int, LocalDate, String>, Long>,
        user: Int,
        pkg: String,
        startMs: Long,
        endMs: Long,
        zone: ZoneId
    ) {
        if (endMs <= startMs) return
        var cur = startMs
        while (cur < endMs) {
            val date = Instant.ofEpochMilli(cur).atZone(zone).toLocalDate()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val segEnd = minOf(endMs, dayEnd)
            val key = Triple(user, date, pkg)
            out[key] = (out[key] ?: 0L) + (segEnd - cur)
            cur = segEnd
        }
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
