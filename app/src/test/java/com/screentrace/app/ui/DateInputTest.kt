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
        // CR-09：autoFormat 之后纯垃圾输入（abc）会被滤成空串，不再报「格式错」；
        // 该分支只在超过 8 位数字的非掩码形状下兜底
        assertEquals("", DateInput.error("abc"))
        assertTrue(DateInput.error("2026-02-30").contains("不存在"))
    }

    @Test
    fun `格式化与解析往返一致`() {
        val date = LocalDate.of(2026, 3, 7)
        assertEquals(date, DateInput.parse(DateInput.format(date)))
    }

    // ---- CR-09 验收 41：显示掩码 + 8 位纯数字解析 ----

    @Test
    fun `8位纯数字自动补全为掩码形`() {
        assertEquals("2026-11-11", DateInput.normalize("20261111"))
        assertEquals("2026-01-05", DateInput.normalize("20260105"))
        // 含 `-` 的文本原样返回；中间态原样返回
        assertEquals("2026-11-11", DateInput.normalize("2026-11-11"))
        assertEquals("202611", DateInput.normalize("202611"))
        assertEquals("", DateInput.normalize(""))
    }

    @Test
    fun `纯数字输入能直接解析`() {
        assertEquals(LocalDate.of(2026, 11, 13), DateInput.parse("20261113"))
        assertEquals(LocalDate.of(2026, 1, 5), DateInput.parse("20260105"))
        assertNull(DateInput.parse("20261313")) // 13 月
        assertNull(DateInput.parse("2026113"))  // 不足 8 位且无分隔
    }

    @Test
    fun `format 回填数字流且两位补零`() {
        assertEquals("20261111", DateInput.format(LocalDate.of(2026, 11, 11)))
        assertEquals("20260105", DateInput.format(LocalDate.of(2026, 1, 5)))
        // 回填值必须能被 parse 原样解析（数字流往返一致）
        assertEquals(LocalDate.of(2026, 1, 5), DateInput.parse(DateInput.format(LocalDate.of(2026, 1, 5))))
    }

    @Test
    fun `显示掩码补分隔符且透传已有分隔的文本`() {
        assertEquals("2026-11-13" to setOf(4, 7), DateInput.maskDigits("20261113"))
        assertEquals("2026-11-13" to emptySet<Int>(), DateInput.maskDigits("2026-11-13"))
        // 中间态：年份输满立即出分隔符（分隔符在 offset 4 处，映射同步提供）
        assertEquals("2026-" to setOf(4), DateInput.maskDigits("2026"))
        assertEquals("2026-11" to setOf(4), DateInput.maskDigits("202611"))
    }

    @Test
    fun `掩码偏移映射与光标对齐`() {
        val (_, dashes) = DateInput.maskDigits("20261113")
        // 原始 offset 5（第 5 个数字前）→ 前面插了 1 个 `-` → 6
        var t = 5; dashes.forEach { if (it < t) t++ }
        assertEquals(6, t)
        t = 8; dashes.forEach { if (it < t) t++ }
        assertEquals(10, t) // 8 个数字 + 2 个分隔符
    }

    // ---- CR-09：中间态不报错 ----

    @Test
    fun `输满 8 位前的中间态不显示错误`() {
        assertEquals("", DateInput.error("202611"))
        assertEquals("", DateInput.error("2026-11-"))
        assertEquals("", DateInput.error("2026-11-1"))
        // 输满仍是非法 → 提示
        assertTrue(DateInput.error("20260230").contains("不存在"))
        assertTrue(DateInput.error("2026-13-99").contains("不存在"))
    }
}
