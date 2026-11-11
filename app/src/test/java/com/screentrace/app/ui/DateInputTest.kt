package com.screentrace.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** §7-25 手输日期的解析契约。 */
class DateInputTest {

    @Test
    fun `标准与单位数月日都能解析`() {
        assertEquals(LocalDate.of(2026, 11, 11), DateInput.parse("2026-11-11"))
        assertEquals(LocalDate.of(2026, 1, 5), DateInput.parse("2026-1-5"))
        assertEquals(LocalDate.of(2024, 2, 29), DateInput.parse(" 2024-2-29 "))
    }

    @Test
    fun `不存在的日期一律拒绝而不是悄悄进位`() {
        assertNull(DateInput.parse("2026-02-30"))
        assertNull(DateInput.parse("2026-13-01"))
        assertNull(DateInput.parse("2025-02-29"))
        assertNull(DateInput.parse("2026-00-10"))
    }

    @Test
    fun `空串与乱码不算错误也不算日期`() {
        assertNull(DateInput.parse(""))
        assertNull(DateInput.parse("   "))
        assertNull(DateInput.parse("2026/11/11"))
        assertEquals("", DateInput.error(""))
        assertEquals("", DateInput.error("2026-11-11"))
    }

    @Test
    fun `错误文案区分格式错与日期不存在`() {
        assertTrue(DateInput.error("abc").contains("年-月-日"))
        assertTrue(DateInput.error("2026-02-30").contains("不存在"))
    }

    @Test
    fun `格式化与解析往返一致`() {
        val date = LocalDate.of(2026, 3, 7)
        assertEquals(date, DateInput.parse(DateInput.format(date)))
    }
}
