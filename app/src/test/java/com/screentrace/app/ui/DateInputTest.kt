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
        // CR-15 A0：月输满（6 位）即给第二个 `-`，提示下一段开始
        assertEquals("2026-11-" to setOf(4, 7), DateInput.maskDigits("202611"))
    }

    @Test
    fun `掩码偏移映射与光标对齐`() {
        // 直接打靶生产纯函数，不再复刻测试自己的公式（防假绿）
        assertEquals(5, DateInput.mapToTransformed("2026", 5, 4)) // 光标在 `-` 之后
        assertEquals(6, DateInput.mapToTransformed("20261113", 10, 5))
        assertEquals(10, DateInput.mapToTransformed("20261113", 10, 8))
        assertEquals(8, DateInput.mapToOriginal("20261113", 10))
        assertEquals(5, DateInput.mapToOriginal("20261113", 6))
    }

    // ---- CR-09：中间态不报错 ----

    @Test
    fun `输满 8 位前的中间态不显示错误`() {
        assertEquals("", DateInput.error("202611"))
        assertEquals("", DateInput.error("2026-11-"))
        assertEquals("", DateInput.error("2026-11-1"))
        // 输满仍是非法 → 提示
        assertTrue(DateInput.error("20260230").contains("不存在"))
        // CR-15 A0：月段非法即时提示（不再一律说「日期不存在」）
        assertTrue(DateInput.error("2026-13-99").contains("月份"))
    }

    // ---- CR-15 A0：两次真机闪退 + 分段智能输入的回归 ----

    @Test
    fun `第9位输入不再让偏移映射越界`() {
        // vivo 10:16:05 FATAL: "9 -> 11 is not in range of transformed text [0, 10]"
        val raw = "202611130"
        val masked = DateInput.maskDigits(raw).first
        assertEquals("2026-11-13", masked)
        val t = DateInput.mapToTransformed(raw, masked.length, raw.length)
        assertEquals(masked.length, t) // 钳制到 10，绝不返回 11
        assertTrue(DateInput.mapToOriginal(raw, t) in 0..raw.length)
    }

    @Test
    fun `年份1234被拒绝不会进M3日历`() {
        // vivo 10:13:08 FATAL: "year (1234) is out of the years range of 1900..2100"
        assertNull(DateInput.parse("12340101"))
        assertNull(DateInput.parse("1234-01-01"))
        assertNull(DateInput.parse("22000101"))
        assertTrue(DateInput.error("12340101").contains("年份"))
        assertEquals(LocalDate.of(2100, 12, 31), DateInput.parse("21001231"))
        assertEquals(LocalDate.of(1900, 1, 1), DateInput.parse("19000101"))
    }

    @Test
    fun `月日首位过大自动补零`() {
        assertEquals("202603" to 6, DateInput.normalizeInput("20263"))
        assertEquals("20260305" to 8, DateInput.normalizeInput("202635"))
        assertEquals("20261113" to 8, DateInput.normalizeInput("20261113"))
        assertEquals("20260105" to 8, DateInput.normalizeInput("2026-1-5"))
        // 0/1 开头要等第二位才能组成 10–12
        assertEquals("20261" to 5, DateInput.normalizeInput("20261"))
        assertEquals("202612" to 6, DateInput.normalizeInput("202612"))
    }

    @Test
    fun `硬截8位且过滤非数字`() {
        assertEquals("20261113" to 8, DateInput.normalizeInput("202611139999"))
        assertEquals("20261113" to 8, DateInput.normalizeInput("2026-11-13abc"))
        assertEquals("" to 0, DateInput.normalizeInput("abc"))
    }

    @Test
    fun `月份越界即时提示且输满仍非法`() {
        assertEquals("月份需在 01–12 之间", DateInput.error("202613"))
        assertEquals("月份需在 01–12 之间", DateInput.error("202600"))
        assertTrue(DateInput.error("20260230").contains("不存在"))
        assertEquals("", DateInput.error("202611")) // 中间态不打扰
    }

    @Test
    fun `月输满显示第二个分隔符提示下一段`() {
        assertEquals("2026-03-" to setOf(4, 7), DateInput.maskDigits("202603"))
        assertEquals("2026-03-05" to setOf(4, 7), DateInput.maskDigits("20260305"))
    }

    @Test
    fun `规范化后的文本可被parse原样解析`() {
        val (text, _) = DateInput.normalizeInput("202635")
        assertEquals(LocalDate.of(2026, 3, 5), DateInput.parse(text))
        assertEquals("", DateInput.error(text))
    }
}
