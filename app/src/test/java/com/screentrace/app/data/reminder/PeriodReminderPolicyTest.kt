package com.screentrace.app.data.reminder

import com.screentrace.app.contract.PeriodType
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodReminderPolicyTest {

    @Test
    fun `默认双开关关闭时不触发任何提醒也不排期`() {
        val monNight = LocalDateTime.of(2026, 12, 7, 20, 5)
        val config = ReminderConfig()
        assertTrue(PeriodReminderPolicy.evaluateDueReminders(monNight, config).isEmpty())
        assertNull(PeriodReminderPolicy.nextTriggerDelayMillis(monNight, config))
    }

    @Test
    fun `每周提醒只在周一 20 点及之后触发上一完整自然周且同周不重复`() {
        val config = ReminderConfig(weeklyEnabled = true, monthlyEnabled = false, hour = 20, minute = 0)

        // 周一 19:59 尚未到点
        val before = LocalDateTime.of(2026, 12, 7, 19, 59)
        assertTrue(PeriodReminderPolicy.evaluateDueReminders(before, config).isEmpty())

        // 周二不触发
        val tuesday = LocalDateTime.of(2026, 12, 8, 20, 0)
        assertTrue(PeriodReminderPolicy.evaluateDueReminders(tuesday, config).isEmpty())

        // 周一 20:00 命中上一完整自然周（2026-11-30 ~ 2026-12-06）
        val exact = LocalDateTime.of(2026, 12, 7, 20, 0)
        val due = PeriodReminderPolicy.evaluateDueReminders(exact, config)
        assertEquals(1, due.size)
        assertEquals(PeriodType.WEEK, due.single().periodType)
        assertEquals(LocalDate.of(2026, 11, 30), due.single().startDate)
        assertEquals(LocalDate.of(2026, 12, 6), due.single().endDate)

        // 同周 key 去重
        assertTrue(
            PeriodReminderPolicy.evaluateDueReminders(exact, config, lastWeeklyKey = due.single().periodKey).isEmpty()
        )
    }

    @Test
    fun `每月提醒只在每月 1 日 20 点及之后触发上一完整自然月且同月不重复`() {
        val config = ReminderConfig(weeklyEnabled = false, monthlyEnabled = true, hour = 20, minute = 0)

        val day2 = LocalDateTime.of(2026, 12, 2, 20, 0)
        assertTrue(PeriodReminderPolicy.evaluateDueReminders(day2, config).isEmpty())

        val day1 = LocalDateTime.of(2026, 12, 1, 20, 0)
        val due = PeriodReminderPolicy.evaluateDueReminders(day1, config)
        assertEquals(1, due.size)
        assertEquals(PeriodType.MONTH, due.single().periodType)
        assertEquals(LocalDate.of(2026, 11, 1), due.single().startDate)
        assertEquals(LocalDate.of(2026, 11, 30), due.single().endDate)

        assertTrue(
            PeriodReminderPolicy.evaluateDueReminders(day1, config, lastMonthlyKey = due.single().periodKey).isEmpty()
        )
    }

    @Test
    fun `nextTriggerDelayMillis 返回到下一个有效周一或 1 日的正延迟`() {
        val config = ReminderConfig(weeklyEnabled = true, monthlyEnabled = false, hour = 20, minute = 0)
        val monMorning = LocalDateTime.of(2026, 12, 7, 10, 0)
        val delay = PeriodReminderPolicy.nextTriggerDelayMillis(monMorning, config)
        assertEquals(10L * 3600 * 1000, delay)
    }
}
