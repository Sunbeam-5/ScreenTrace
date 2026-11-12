package com.screentrace.app.data.export

import com.screentrace.app.contract.AppCategory
import com.screentrace.app.contract.AppUsage
import com.screentrace.app.contract.DailyPoint
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.HourBucket
import com.screentrace.app.contract.Summary
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §7-12：导出 HTML 必须**自包含**且与页内数字同源。
 *
 * 这里的断言刻意选在「改坏实现就会变红」的点上：
 * 1. 不转义应用名 → 标签被写坏（真实应用名里确实有 `&` 与 `<`）；
 * 2. 引入 CDN / 在线字体 → 断网打开就白屏，`http` 断言立刻红；
 * 3. 报告自己再算一遍占比 → 与页面算法不同源，数字对不上。
 */
class HtmlReportExporterTest {

    private val day = LocalDate.of(2026, 11, 11)

    private fun summary(total: Int, recordedDays: Int = 1, avg: Int = total) = Summary(
        selection = DateSelection.Day(day),
        totalDays = 1,
        recordedDays = recordedDays,
        hasAnyRecord = true,
        totalScreenUnlockMin = total,
        averageScreenUnlockMin = avg,
        averageUnlocks = 12,
        longestSessionMin = 90
    )

    private fun input(
        summary: Summary = summary(372),
        apps: List<AppUsage> = listOf(
            AppUsage(day, "com.tencent.mm", "微信", 100, 5, AppCategory.SOCIAL),
            AppUsage(day, "com.xingin", "小红书", 100, 9, AppCategory.SOCIAL)
        ),
        hideAppNames: Boolean = false
    ) = HtmlReportInput(
        rangeLabel = "11月11日 · 1天",
        exportedAt = "2026-11-12 08:10",
        summary = summary,
        daily = listOf(DailyPoint(day, summary.totalScreenUnlockMin, true)),
        apps = apps,
        hours = listOf(HourBucket(9, 40), HourBucket(10, 20)),
        appVersion = "1.0.0",
        hideAppNames = hideAppNames,
        dailyGoalMin = 240
    )

    @Test
    fun `转义覆盖五种字符`() {
        assertEquals("&amp;&lt;&gt;&quot;&#39;", HtmlReportExporter.escape("&<>\"'"))
    }

    @Test
    fun `报告不含任何外部资源与脚本`() {
        val html = HtmlReportExporter.build(input())
        assertFalse("不得引用 http 资源", html.contains("http://"))
        assertFalse("不得引用 https 资源", html.contains("https://"))
        assertFalse("不得内联脚本", html.contains("<script", ignoreCase = true))
        assertFalse("不得引外部样式表", html.contains("<link", ignoreCase = true))
        assertFalse("CSS 不得 url() 引用外部文件", html.contains("url("))
        assertFalse("CSS 不得 @import", html.contains("@import"))
        assertFalse("不得引图片", html.contains("<img", ignoreCase = true))
    }

    @Test
    fun `概览数字与仓库结果同源`() {
        val html = HtmlReportExporter.build(input())
        // 372 分钟 → 6小时12分钟（与页面 UsageCalculator.formatDuration 同一函数）
        assertTrue(html.contains("6小时12分钟"))
        assertTrue(html.contains("应用时长合计 3小时20分钟"))
        assertTrue(html.contains("12 次"))
    }

    @Test
    fun `应用占比按应用合计为分母`() {
        val html = HtmlReportExporter.build(input())
        // 100 / 200 = 50%
        assertTrue(html.contains("<td>50%</td>"))
    }

    @Test
    fun `隐藏应用名称时原名不出现`() {
        val html = HtmlReportExporter.build(input(hideAppNames = true))
        assertFalse(html.contains("微信"))
        assertFalse(html.contains("小红书"))
        assertTrue(html.contains("应用 1"))
        assertTrue(html.contains("应用 2"))
        assertTrue(html.contains("已隐藏应用名称"))
    }

    @Test
    fun `应用名里的尖括号被转义而不是写坏标签`() {
        val evil = AppUsage(day, "com.evil", "<script>alert(1)</script>", 10, 1, AppCategory.OTHER)
        val html = HtmlReportExporter.build(input(apps = listOf(evil)))
        assertFalse(html.contains("<script"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test
    fun `无记录日写无记录而不是零分钟`() {
        val withGap = input().copy(
            daily = listOf(
                DailyPoint(day, 100, true),
                DailyPoint(day.plusDays(1), 0, false)
            )
        )
        val html = HtmlReportExporter.build(withGap)
        assertTrue(html.contains("无记录"))
    }

    @Test
    fun `完全没有记录时给说明而不是空表格`() {
        val empty = input(
            summary = summary(0).copy(hasAnyRecord = false, recordedDays = 0, averageUnlocks = 0),
            apps = emptyList()
        )
        val html = HtmlReportExporter.build(empty)
        assertTrue(html.contains("该区间没有可用记录"))
        assertFalse(html.contains("应用排行"))
    }

    @Test
    fun `文件名带时间戳且为 html`() {
        val name = HtmlReportExporter.fileName(LocalDateTime.of(2026, 11, 12, 8, 5))
        assertEquals("screen-time-report-20261112-0805.html", name)
    }
}
