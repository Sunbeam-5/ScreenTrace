package com.screentrace.app.contract

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CR-14「本周」快捷项的区间计算：周一为起点、周日为终点（ISO 周）。
 * 变异锚点：起点若误用周日 / 或按周日为起点 +6 天，三个用例都会变红。
 */
class DateSelectionTest {

    @Test
    fun `thisWeek from Wednesday spans Monday to Sunday`() {
        // 2026-11-11 是周三
        val range = DateSelection.thisWeek(LocalDate.of(2026, 11, 11))
        assertEquals(LocalDate.of(2026, 11, 9), range.start)
        assertEquals(LocalDate.of(2026, 11, 15), range.end)
    }

    @Test
    fun `thisWeek on Monday returns the same week`() {
        val range = DateSelection.thisWeek(LocalDate.of(2026, 11, 9))
        assertEquals(LocalDate.of(2026, 11, 9), range.start)
        assertEquals(LocalDate.of(2026, 11, 15), range.end)
    }

    @Test
    fun `thisWeek on Sunday starts at previous Monday`() {
        val range = DateSelection.thisWeek(LocalDate.of(2026, 11, 15))
        assertEquals(LocalDate.of(2026, 11, 9), range.start)
        assertEquals(LocalDate.of(2026, 11, 15), range.end)
    }

    @Test
    fun `thisWeek always spans exactly 7 days`() {
        assertEquals(7, DateSelection.thisWeek(LocalDate.of(2026, 1, 1)).days)
        assertEquals(7, DateSelection.thisWeek(LocalDate.of(2027, 2, 28)).days)
    }
}
