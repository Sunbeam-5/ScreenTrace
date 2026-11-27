package com.screentrace.app.ui

import com.screentrace.app.contract.DailyPoint
import com.screentrace.app.contract.DayQuality
import com.screentrace.app.contract.PeriodSummaryCalculator
import com.screentrace.app.contract.PeriodType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-07 Review 修复（P2）：周期总结不足文案与 `completeDays` 严格对齐（覆盖 M-CR6）。
 */
class PeriodScreenCopyTest {

    @Test
    fun mCr6_insufficientCopyReportsCompleteDaysNotRecordedDays() {
        // 6 天有记录（1 天 EXACT + 4 天 PARTIAL + 1 天今日进行中）-> recordedDays=6, completeDays=1
        val today = LocalDate.of(2026, 11, 28) // 周六（本周 11/23~11/28 共 6 天）
        val trend = listOf(
            DailyPoint(LocalDate.of(2026, 11, 23), 240, recorded = true, dayQuality = DayQuality.EXACT),
            DailyPoint(LocalDate.of(2026, 11, 24), 120, recorded = true, dayQuality = DayQuality.PARTIAL),
            DailyPoint(LocalDate.of(2026, 11, 25), 110, recorded = true, dayQuality = DayQuality.PARTIAL),
            DailyPoint(LocalDate.of(2026, 11, 26), 100, recorded = true, dayQuality = DayQuality.PARTIAL),
            DailyPoint(LocalDate.of(2026, 11, 27), 90, recorded = true, dayQuality = DayQuality.PARTIAL),
            DailyPoint(today, 60, recorded = true, dayQuality = DayQuality.PARTIAL, isToday = true)
        )

        val period = PeriodSummaryCalculator.build(
            type = PeriodType.WEEK,
            today = today,
            trend = trend,
            previousSummary = null,
            apps = emptyList(),
            hours = emptyList()
        )
        assertEquals(6, period.summary.recordedDays)
        assertEquals(1, period.summary.completeDays)
        assertTrue(period.insufficient)

        val copy = periodInsufficientCopy(period.summary.completeDays)
        assertEquals("本周期只有 1 个完整日，数据不足以得出结论。", copy)

        // 纯文本导出清晰区分 recordedDays 与 completeDays
        val plain = PeriodSummaryCalculator.toPlainText(period)
        assertTrue(plain.contains("有记录 6 天 / 共 6 天（完整日 1 天）"))
    }
}
