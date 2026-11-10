package com.screentrace.app.contract

import java.time.LocalDate
import java.time.YearMonth

data class Summary(
    val selection: DateSelection,
    val totalDays: Int,
    val recordedDays: Int,
    val hasAnyRecord: Boolean,
    val totalScreenUnlockMin: Int,
    /** §5.6：日均只除以「有记录」日，缺失日不按 0 拉低平均。 */
    val averageScreenUnlockMin: Int,
    val averageUnlocks: Int,
    val longestSessionMin: Int
)

/** 趋势图每个自然日一个点；无记录日 minutes = 0 且 recorded = false（§5.6 / §7-8）。 */
data class DailyPoint(val date: LocalDate, val minutes: Int, val recorded: Boolean)

data class HourBucket(val hour: Int, val minutes: Int)

data class CalendarLevel(val date: LocalDate, val level: Int, val recorded: Boolean)

data class PullResult(val ok: Boolean, val message: String)

/**
 * §5.7 日历「连续记录」卡的数据。§5.6：streak 以 `screen_unlock_min > 0` 的自然日连续段
 * 计算截至锚点的全量历史（不限筛选区间）。currentDays = 以锚点日为末端的连续 >0 天数，
 * 锚点日非 >0（真实 0 分钟或无记录）时为 0；longest* 为截至锚点全量历史中的最长连续段，
 * 并列时取更早的一段。卡片展示「连续记录天数（currentDays）· 最长 N 天 · 委于 X」。
 */
data class StreakInfo(
    val currentDays: Int,
    val longestDays: Int,
    val longestStart: LocalDate?
)

/**
 * §5.6/§5.7：「最久的一天 / 最少的一天 / 低于 2 小时」只统计筛选区间内的有记录日；
 * 真实 0 分钟计入 under2hDays 并参与最短竞争，无记录日不计。并列时取更早日期。
 */
data class ExtremesInfo(
    val longestDate: LocalDate?,
    val longestMinutes: Int,
    val shortestDate: LocalDate?,
    val shortestMinutes: Int,
    val under2hDays: Int
)

/** CR-01（C04）：指标详情页的指标类型。 */
enum class MetricKind { UNLOCK, NOTIFICATION }

/**
 * CR-01（§4 事件覆盖范围）：数据点覆盖三态。
 * RECORDED = 事件累积窗口内（含真实 0 次的完整时段）；NO_RECORD = 窗口外无记录；
 * GAP = 拉取缺口。UI 不得把无记录画成真实的 0（§5.7）。
 */
enum class Coverage { RECORDED, NO_RECORD, GAP }

/**
 * CR-01：详情页数据点。单日逐小时（hour = 0..23）或区间逐日（hour = null）。
 * value 为主指标次数（UNLOCK=解锁数；NOTIFICATION=打扰数，与今日卡一致）。
 */
data class MetricPoint(
    val date: LocalDate,
    val hour: Int?,
    val value: Int,
    val coverage: Coverage
)

/** CR-01：通知应用归因行（解锁详情无可靠应用归因，返回空列表，§5.7 禁止伪造）。 */
data class MetricAppCount(
    val pkg: String,
    val name: String,
    val interruptions: Int,
    val seen: Int
)

/**
 * CR-01（C04）：解锁/通知详情共用的统一查询结果（§6：两个详情页共用同一
 * metricDetail 查询，禁止各写聚合逻辑；图表/数据表/卡片数字同源于此）。
 */
data class MetricDetail(
    val metric: MetricKind,
    val selection: DateSelection,
    /** 摘要：单日=当日合计；区间=区间合计（§5.7）。 */
    val total: Int,
    /** 区间态 = 有记录日日均（§5.6 AVG 分母只含记录日）；单日态为 null。 */
    val dailyAverage: Int?,
    /** 通知双指标（打扰 / 已查看）；UNLOCK 时为 null。 */
    val notification: NotificationCounts?,
    /** 数据点：单日 24 点逐小时；区间逐日。 */
    val points: List<MetricPoint>,
    /** 通知应用排行（打扰降序）；UNLOCK 恒为空列表。 */
    val appRanking: List<MetricAppCount>,
    // 解锁详情补充摘要（§5.7：最高频时段 / 每小时平均 / 最活跃日期）
    val peakHour: Int?,
    val hourlyAverage: Double?,
    val busiestDate: LocalDate?
)

/** CR-01：通知双指标（§5.7 详情页摘要「打扰 X 次 / 已查看 Y 次」）。 */
data class NotificationCounts(val interruptions: Int, val seen: Int)

/**
 * 数据层契约（§6「定契约」第一步）。UI / ViewModel 只依赖这个接口，
 * 不直接执行 Room 查询、日期聚合或业务计算。
 *
 * 契约扩展 v0.2（Kimi 评审 S01）：补 streak / extremes / monthLevels，
 * calendarLevels 更名 yearLevels——均为 App 侧接口扩展，不触碰 §1–§4 数据口径。
 *
 * TODO(§6)：真实实现为 RoomUsageRepository（Room + Shizuku + WorkManager），
 * 查询按 §5.6 的设备本地自然日；所有聚合统一走 UsageCalculator。
 */
interface UsageRepository {
    suspend fun summary(selection: DateSelection): Summary
    suspend fun dailyTrend(selection: DateSelection): List<DailyPoint>
    suspend fun appRanking(selection: DateSelection, limit: Int = 20): List<AppUsage>
    suspend fun hourlyDistribution(selection: DateSelection): List<HourBucket>

    /** 月视图：任意月份整月的色档（§5.6 锚点决定初始月份，允许前后翻月）。 */
    suspend fun monthLevels(yearMonth: YearMonth): List<CalendarLevel>

    /** 年视图：截至锚点的过去 weeks 周（默认 20 周，§5.6/§5.7）。 */
    suspend fun yearLevels(anchor: LocalDate, weeks: Int = 20): List<CalendarLevel>

    /** 连续记录卡：截至锚点的全量历史 streak（§5.6，筛选区间之外的历史也计入）。 */
    suspend fun streak(anchor: LocalDate): StreakInfo

    /** 统计/日历双卡：筛选区间内有记录日的极值（§5.6/§5.7）。 */
    suspend fun extremes(selection: DateSelection): ExtremesInfo

    /**
     * CR-01（C04）：解锁/通知详情统一查询。两详情页、卡片数字、图表与数据表
     * 必须同源于此（§7-16），禁止各写聚合逻辑。
     */
    suspend fun metricDetail(metric: MetricKind, selection: DateSelection): MetricDetail

    suspend fun pullLatest(): PullResult
}
