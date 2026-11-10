package com.screentrace.app.contract

import java.time.LocalDate

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
 * 数据层契约（§6「定契约」第一步）。UI / ViewModel 只依赖这个接口，
 * 不直接执行 Room 查询、日期聚合或业务计算。
 *
 * TODO(§6)：真实实现为 RoomUsageRepository（Room + Shizuku + WorkManager），
 * 查询按 §5.6 的设备本地自然日；所有聚合统一走 UsageCalculator。
 */
interface UsageRepository {
    suspend fun summary(selection: DateSelection): Summary
    suspend fun dailyTrend(selection: DateSelection): List<DailyPoint>
    suspend fun appRanking(selection: DateSelection, limit: Int = 20): List<AppUsage>
    suspend fun hourlyDistribution(selection: DateSelection): List<HourBucket>
    suspend fun calendarLevels(anchor: LocalDate, weeks: Int = 20): List<CalendarLevel>
    suspend fun pullLatest(): PullResult
}
