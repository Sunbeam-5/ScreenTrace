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

    suspend fun pullLatest(): PullResult
}
