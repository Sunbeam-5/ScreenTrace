package com.screentrace.app.ui

import com.screentrace.app.contract.Coverage
import com.screentrace.app.contract.DailyPoint
import com.screentrace.app.contract.MetricKind
import com.screentrace.app.ui.detail.MetricTableRow
import com.screentrace.app.uikit.BreakdownRow
import com.screentrace.app.uikit.RingMath
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CR-30 阶段 E（§7-68）：TalkBack 无障碍读屏摘要与 1.3x–1.5x 大字号钳制单测。
 * 含行为变异锚点 M-E4。
 */
class AccessibilityAndFontScaleTest {

    @Test
    fun `M_E4_数据表行读屏严格区分真实0次与无记录和拉取缺口`() {
        val zeroRow = MetricTableRow("9–10时", count = 0, percentText = "0.0%", coverage = Coverage.RECORDED)
        val noRecordRow = MetricTableRow("10–11时", count = null, percentText = "—", coverage = Coverage.NO_RECORD)
        val gapRow = MetricTableRow("11–12时", count = null, percentText = "—", coverage = Coverage.GAP)

        val zeroDesc = AccessibilityDescriptions.metricTableRowSummary(zeroRow, MetricKind.UNLOCK, singleDay = true)
        assertTrue(zeroDesc.contains("解锁 0 次"))

        val noRecDesc = AccessibilityDescriptions.metricTableRowSummary(noRecordRow, MetricKind.UNLOCK, singleDay = true)
        assertTrue(noRecDesc.contains("无记录"))
        assertFalse("无记录行绝不得读成 0 次", noRecDesc.contains("0 次"))

        val gapDesc = AccessibilityDescriptions.metricTableRowSummary(gapRow, MetricKind.NOTIFICATION, singleDay = false)
        assertTrue(gapDesc.contains("拉取缺口"))
        assertFalse(gapDesc.contains("0 次"))
    }

    @Test
    fun `趋势图与时段分布与日夜条读屏摘要覆盖空数据与正常数据`() {
        assertTrue(AccessibilityDescriptions.trendChartSummary(emptyList(), null).contains("暂无"))
        val points = listOf(
            DailyPoint(LocalDate.of(2026, 11, 20), 120, recorded = true),
            DailyPoint(LocalDate.of(2026, 11, 21), 240, recorded = true),
            DailyPoint(LocalDate.of(2026, 11, 22), 0, recorded = false)
        )
        val trendDesc = AccessibilityDescriptions.trendChartSummary(points, averageMinutes = 180)
        assertTrue(trendDesc.contains("共 2 天有记录"))
        assertTrue(trendDesc.contains("11月21日"))

        assertTrue(AccessibilityDescriptions.periodDistributionSummary(listOf(0, 0, 0, 0), listOf(0, 0, 0, 0)).contains("暂无"))
        val periodDesc = AccessibilityDescriptions.periodDistributionSummary(
            listOf(60, 120, 60, 0),
            listOf(25, 50, 25, 0)
        )
        assertTrue(periodDesc.contains("6点到12点"))
        assertTrue(periodDesc.contains("50%"))

        assertTrue(AccessibilityDescriptions.dayNightBarSummary(null).contains("暂无"))
        assertEquals("日间占比 68%，夜间占比 32%", AccessibilityDescriptions.dayNightBarSummary(68))

        val breakdownDesc = AccessibilityDescriptions.appBreakdownSummary(
            listOf(BreakdownRow("微信", "com.tencent.mm", 90, 60, 0))
        )
        assertTrue(breakdownDesc.contains("微信 60%"))
    }

    @Test
    fun `M_E4_圆环中心字号在1_0x保持原值_在1_3x至1_5x柔和钳制不溢出`() {
        assertEquals(46f, RingMath.scaledNumberFontSp(46f, 1.0f), 0.01f)
        assertEquals(46f, RingMath.scaledNumberFontSp(46f, 1.15f), 0.01f)

        val at13 = RingMath.scaledNumberFontSp(46f, 1.3f)
        val at15 = RingMath.scaledNumberFontSp(46f, 1.5f)
        assertTrue("1.3x 视觉字号不应超过 1.22 倍基准", at13 * 1.3f <= 46f * 1.22f + 0.05f)
        assertTrue("1.5x 视觉字号不应超过 1.22 倍基准", at15 * 1.5f <= 46f * 1.22f + 0.05f)
        assertTrue("1.5x 下应明显小于未钳制时的 46sp", at15 < 40f)
    }
}
