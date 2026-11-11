package com.screentrace.app.contract

import java.time.LocalDate
import java.time.YearMonth

/**
 * 骨架期的空实现：只返回「无记录」状态，让 App 和 @Preview 能跑起来并显示空态。
 *
 * 这里刻意不放任何示例业务数值 —— 需求文档 §6 明确禁止用假数据掩盖未完成的数据接线。
 * UI 迭代阶段如需临时假数据，请单独建 debug 源的 FakeUsageRepository，
 * 且交付前必须换回真实 Room 实现（§7 验收第 3 条）。
 *
 * TODO(§2/§3/§4/§6)：RoomUsageRepository，Shizuku 拉取 + 解析 + 合并进 Room。
 */
class EmptyUsageRepository : UsageRepository {

    override suspend fun hasAnyData(): Boolean = false

    override suspend fun hasDataIn(selection: DateSelection): Boolean = false

    override suspend fun summary(selection: DateSelection): Summary =
        UsageCalculator.summarize(selection, emptyList())

    override suspend fun dailyTrend(selection: DateSelection): List<DailyPoint> =
        UsageCalculator.dailyPoints(selection, emptyList())

    override suspend fun appRanking(selection: DateSelection, limit: Int): List<AppUsage> = emptyList()

    override suspend fun hourlyDistribution(selection: DateSelection): List<HourBucket> =
        UsageCalculator.hourlyBuckets(emptyList())

    override suspend fun monthLevels(yearMonth: YearMonth): List<CalendarLevel> =
        UsageCalculator.monthLevels(yearMonth, emptyList())

    override suspend fun yearLevels(anchor: LocalDate, weeks: Int): List<CalendarLevel> =
        UsageCalculator.yearLevels(anchor, weeks, emptyList())

    override suspend fun streak(anchor: LocalDate): StreakInfo =
        UsageCalculator.streak(emptyList(), anchor)

    override suspend fun extremes(selection: DateSelection, lowThresholdMin: Int): ExtremesInfo =
        UsageCalculator.extremes(selection, emptyList(), lowThresholdMin)

    override suspend fun metricDetail(metric: MetricKind, selection: DateSelection): MetricDetail =
        UsageCalculator.metricDetail(metric, selection) // 全空事件 → 覆盖三态全 NO_RECORD

    override suspend fun pullLatest(): PullResult = PullResult(
        ok = false,
        message = "数据拉取尚未接线：请先完成 Shizuku 授权与解析器（需求文档 §2–§3）"
    )
}
