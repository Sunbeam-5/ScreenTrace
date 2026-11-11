package com.screentrace.app.uikit

import com.screentrace.app.contract.DailyPoint
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 趋势图几何验收（批 6g，需求 §7-20）。
 *
 * 这些规则在真机上才看得出错：量程算错 → 柱子溢出卡片；标签算错 → 日期叠字；
 * 平均线算错 → 「没采集到」被当成 0 把线拉低（假数据）。所以钉在单测里。
 */
class TrendChartMathTest {

    private val base = LocalDate.of(2026, 11, 5)

    private fun points(vararg minutes: Int): List<DailyPoint> =
        minutes.mapIndexed { i, m -> DailyPoint(base.plusDays(i.toLong()), m, recorded = true) }

    // ---------- 量程 ----------

    @Test
    fun `量程向上取整到整小时`() {
        assertEquals(60, TrendChartMath.scaleMaxMinutes(1))
        assertEquals(60, TrendChartMath.scaleMaxMinutes(60))
        assertEquals(120, TrendChartMath.scaleMaxMinutes(61))
        assertEquals(240, TrendChartMath.scaleMaxMinutes(181))
    }

    @Test
    fun `用了 23 小时 40 分钟时顶格是 24 小时`() {
        // 真机对照图的场景：23h40m(1420) → 顶格 1440
        assertEquals(1440, TrendChartMath.scaleMaxMinutes(1420))
    }

    @Test
    fun `量程永不超过 24 小时`() {
        // 异常数据（>24h）也不能把纵轴拉出屏幕
        assertEquals(1440, TrendChartMath.scaleMaxMinutes(2000))
        assertEquals(1440, TrendChartMath.scaleMaxMinutes(1441))
    }

    @Test
    fun `全零时最小量程 1 小时而不是 0`() {
        assertEquals(60, TrendChartMath.scaleMaxMinutes(0))
    }

    // ---------- 平均线 ----------

    @Test
    fun `平均只算有记录日`() {
        val list = listOf(
            DailyPoint(base, 100, recorded = true),
            DailyPoint(base.plusDays(1), 300, recorded = true),
            DailyPoint(base.plusDays(2), 0, recorded = false) // 无记录不计入
        )
        assertEquals(200, TrendChartMath.averageMinutes(list))
    }

    @Test
    fun `真实 0 分钟计入平均`() {
        val list = listOf(
            DailyPoint(base, 0, recorded = true),
            DailyPoint(base.plusDays(1), 120, recorded = true)
        )
        assertEquals(60, TrendChartMath.averageMinutes(list))
    }

    @Test
    fun `全无记录时不画平均线`() {
        assertNull(
            TrendChartMath.averageMinutes(
                listOf(DailyPoint(base, 0, recorded = false))
            )
        )
        assertNull(TrendChartMath.averageMinutes(emptyList()))
    }

    // ---------- 日期标签 ----------

    @Test
    fun `七天以内每天都写标签`() {
        assertEquals((0..6).toSet(), TrendChartMath.labelIndices(7))
    }

    @Test
    fun `三十天只写四个标签且落在首三分之末`() {
        assertEquals(setOf(0, 9, 19, 29), TrendChartMath.labelIndices(30))
    }

    @Test
    fun `八个点也恰四个标签且互不重复`() {
        val idx = TrendChartMath.labelIndices(8)
        assertEquals(4, idx.size)
        assertTrue(0 in idx && 7 in idx)
    }

    // ---------- 柱宽与滚动 ----------

    @Test
    fun `三十天不滚动且柱宽为正`() {
        assertTrue(!TrendChartMath.scrollable(30))
        assertTrue(TrendChartMath.scrollable(31))
        // 360dp 屏：卡片左右各 20dp → 320dp 内容区，再减 32dp 刻度列
        val bar = TrendChartMath.barWidthDp(30, 288f)
        assertTrue("30 天柱宽应 >1dp 才看得见，实际 $bar", bar > 1f)
        assertTrue("30 天柱宽不应超过细柱上限，实际 $bar", bar <= 7f)
        // 30 根柱必须都在一屏内：总宽（槽宽×30）不超过可用宽度
        assertEquals(288f, (288f / 30f) * 30f, 0.01f)
    }

    @Test
    fun `七天内用粗柱且不超过 24dp`() {
        val wide = TrendChartMath.barWidthDp(7, 600f)
        assertEquals(24f, wide, 0.01f)
        val narrow = TrendChartMath.barWidthDp(7, 288f)
        assertTrue(narrow in 4f..24f)
    }

    @Test
    fun `槽位中心用于浮层定位`() {
        assertEquals(14.4f, TrendChartMath.slotCenterDp(1, 30, 288f), 0.01f)
        assertEquals(0f, TrendChartMath.slotCenterDp(0, 0, 288f), 0.01f)
    }
}
