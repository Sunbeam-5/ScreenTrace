package com.screentrace.app.data.reminder

import com.screentrace.app.contract.PeriodType
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/**
 * CR-30 §7-30 周期总结提醒纯策略（JVM 可测，与 WorkManager / NotificationManager 解耦）。
 *
 * 规则：
 * - 仅保留每周 + 每月总结提醒（双独立开关，默认全关，默认 20:00）；
 * - 每周一 `hour:minute` 及之后提醒「上一完整自然周」（周一..周日）；
 * - 每月 1 日 `hour:minute` 及之后提醒「上一完整自然月」（1 日..月末）；
 * - 不给未结束周期发通知；同周期去重（`periodKey` 已发则跳过）。
 */
data class ReminderConfig(
    val weeklyEnabled: Boolean = false,
    val monthlyEnabled: Boolean = false,
    val hour: Int = DEFAULT_HOUR,
    val minute: Int = DEFAULT_MINUTE
) {
    val anyEnabled: Boolean get() = weeklyEnabled || monthlyEnabled

    companion object {
        const val DEFAULT_HOUR = 20
        const val DEFAULT_MINUTE = 0
    }
}

data class ReminderDue(
    val periodType: PeriodType,
    val periodKey: String,
    val startDate: LocalDate,
    val endDate: LocalDate
)

object PeriodReminderPolicy {

    /**
     * 判定当前时刻应触发哪些周期总结提醒（最多周/月各一条，且不重复）。
     */
    fun evaluateDueReminders(
        now: LocalDateTime,
        config: ReminderConfig,
        lastWeeklyKey: String? = null,
        lastMonthlyKey: String? = null
    ): List<ReminderDue> {
        val out = mutableListOf<ReminderDue>()
        val triggerTime = LocalTime.of(config.hour.coerceIn(0, 23), config.minute.coerceIn(0, 59))
        val today = now.toLocalDate()
        val timeReached = !now.toLocalTime().isBefore(triggerTime)

        if (config.weeklyEnabled && today.dayOfWeek == DayOfWeek.MONDAY && timeReached) {
            val prevWeekEnd = today.minusDays(1)
            val prevWeekStart = prevWeekEnd.minusDays(6)
            val key = "W:$prevWeekStart"
            if (lastWeeklyKey != key) {
                out += ReminderDue(
                    periodType = PeriodType.WEEK,
                    periodKey = key,
                    startDate = prevWeekStart,
                    endDate = prevWeekEnd
                )
            }
        }

        if (config.monthlyEnabled && today.dayOfMonth == 1 && timeReached) {
            val prevYm = YearMonth.from(today).minusMonths(1)
            val key = "M:$prevYm"
            if (lastMonthlyKey != key) {
                out += ReminderDue(
                    periodType = PeriodType.MONTH,
                    periodKey = key,
                    startDate = prevYm.atDay(1),
                    endDate = prevYm.atEndOfMonth()
                )
            }
        }

        return out
    }

    /**
     * 计算下一次非精确调度延迟（毫秒）；两项均关时返回 `null`。
     */
    fun nextTriggerDelayMillis(now: LocalDateTime, config: ReminderConfig): Long? {
        if (!config.anyEnabled) return null
        val triggerTime = LocalTime.of(config.hour.coerceIn(0, 23), config.minute.coerceIn(0, 59))
        val candidates = mutableListOf<LocalDateTime>()

        if (config.weeklyEnabled) {
            val todayTrigger = now.toLocalDate().atTime(triggerTime)
            val nextMon = if (now.dayOfWeek == DayOfWeek.MONDAY && now.isBefore(todayTrigger)) {
                todayTrigger
            } else {
                now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atTime(triggerTime)
            }
            candidates += nextMon
        }

        if (config.monthlyEnabled) {
            val firstThisMonth = now.toLocalDate().withDayOfMonth(1).atTime(triggerTime)
            val nextFirst = if (now.dayOfMonth == 1 && now.isBefore(firstThisMonth)) {
                firstThisMonth
            } else {
                YearMonth.from(now.toLocalDate()).plusMonths(1).atDay(1).atTime(triggerTime)
            }
            candidates += nextFirst
        }

        val earliest = candidates.minOrNull() ?: return null
        return Duration.between(now, earliest).toMillis().coerceAtLeast(60_000L)
    }
}
