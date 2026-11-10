package com.screentrace.app.ui.detail

import com.screentrace.app.contract.Coverage
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.MetricDetail
import com.screentrace.app.contract.MetricKind
import com.screentrace.app.contract.MetricPoint
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批 6d：详情页表格/摘要的纯函数回归。
 *
 * 这些是 §7-16 里最容易假绿的部分——「图表与数据表渲染同一查询结果」「真实 0 / 无记录
 * 三态可区分」都不能靠肉眼在预览里看，必须用点集算出来的行去断言。
 */
class MetricTableTest {

    private val day = LocalDate.of(2026, 11, 8)

    private fun detail(
        points: List<MetricPoint>,
        selection: DateSelection = DateSelection.Day(day),
        dailyAverage: Int? = null,
        peakHour: Int? = null,
        hourlyAverage: Double? = null,
        busiestDate: LocalDate? = null
    ) = MetricDetail(
        metric = MetricKind.UNLOCK,
        selection = selection,
        total = points.filter { it.coverage == Coverage.RECORDED }.sumOf { it.value },
        dailyAverage = dailyAverage,
        notification = null,
        points = points,
        appRanking = emptyList(),
        peakHour = peakHour,
        hourlyAverage = hourlyAverage,
        busiestDate = busiestDate
    )

    private fun hourlyDay(values: IntArray, coverage: Coverage = Coverage.RECORDED) =
        (0..23).map { h -> MetricPoint(day, h, values[h], coverage) }

    @Test
    fun `单日表格是 24 行且时间段按小时升序`() {
        val values = IntArray(24).also { it[9] = 3; it[21] = 5 }
        val rows = MetricTable.rows(detail(hourlyDay(values)))

        assertEquals(24, rows.size)
        assertEquals("0–1时", rows.first().label)
        assertEquals("9–10时", rows[9].label)
        assertEquals("23–24时", rows.last().label)
    }

    @Test
    fun `真实 0 显示 0 而窗口外显示无记录`() {
        val values = IntArray(24)
        val recorded = detail(hourlyDay(values))
        val recordedRows = MetricTable.rows(recorded)
        assertTrue("有记录日的 0 次必须显示成 0 而不是无记录", recordedRows.all { it.hasRecord })
        assertEquals(0, recordedRows[3].count)
        assertEquals("—", recordedRows[3].percentText) // total=0，占比无分母

        val noRecord = detail(hourlyDay(values, Coverage.NO_RECORD))
        val noRecordRows = MetricTable.rows(noRecord)
        assertTrue(noRecordRows.all { !it.hasRecord })
        assertNull(noRecordRows[3].count)
        assertEquals("—", noRecordRows[3].percentText)
    }

    @Test
    fun `拉取缺口不画成 0`() {
        val points = (0..23).map { h ->
            MetricPoint(day, h, 2, if (h in 10..12) Coverage.GAP else Coverage.RECORDED)
        }
        val rows = MetricTable.rows(detail(points))
        assertFalse(rows[11].hasRecord)
        assertEquals(Coverage.GAP, rows[11].coverage)
        assertTrue(rows[9].hasRecord)
    }

    @Test
    fun `占比以区间合计为分母且求和约等于 100`() {
        val values = IntArray(24).also { it[8] = 10; it[9] = 20; it[20] = 30 }
        val d = detail(hourlyDay(values))
        val rows = MetricTable.rows(d)
        val sum = d.points.sumOf { it.value }
        assertEquals(60, sum)

        assertEquals("16.7%", rows[8].percentText) // 10/60
        assertEquals("33.3%", rows[9].percentText) // 20/60
        assertEquals("50.0%", rows[20].percentText) // 30/60

        val totalPercent = rows.sumOf { row ->
            row.percentText.removeSuffix("%").toDoubleOrNull() ?: 0.0
        }
        assertEquals(100.0, totalPercent, 0.2)
    }

    @Test
    fun `区间态按日出行且无记录日不参与占比`() {
        val start = day.minusDays(2)
        val points = listOf(
            MetricPoint(start, null, 5, Coverage.NO_RECORD),
            MetricPoint(start.plusDays(1), null, 7, Coverage.RECORDED),
            MetricPoint(start.plusDays(2), null, 13, Coverage.RECORDED)
        )
        val rows = MetricTable.rows(
            detail(points, selection = DateSelection.Range(start, start.plusDays(2)), dailyAverage = 10)
        )
        assertEquals(3, rows.size)
        assertEquals("11月6日", rows.first().label)
        assertFalse(rows.first().hasRecord)
        assertEquals("35.0%", rows[1].percentText) // 7/20
        assertEquals("65.0%", rows[2].percentText) // 13/20
    }

    @Test
    fun `摘要文案区分单日与区间`() {
        val single = detail(hourlyDay(IntArray(24)))
        assertEquals("当日累计", MetricTable.summaryCaption(single))

        val start = day.minusDays(1)
        val range = detail(
            points = listOf(
                MetricPoint(start, null, 4, Coverage.RECORDED),
                MetricPoint(day, null, 8, Coverage.RECORDED)
            ),
            selection = DateSelection.Range(start, day),
            dailyAverage = 6
        )
        assertEquals("日均 6 次 · 区间合计 12 次", MetricTable.summaryCaption(range))
    }

    @Test
    fun `明细区三项摘要无数据时显示破折号`() {
        assertEquals("21–22时", MetricTable.peakHourText(21))
        assertEquals("23–24时", MetricTable.peakHourText(23))
        assertEquals("—", MetricTable.peakHourText(null))

        assertEquals("3.4 次/小时", MetricTable.hourlyAverageText(3.44))
        assertEquals("—", MetricTable.hourlyAverageText(null))

        assertEquals("11月8日", MetricTable.busiestDateText(day))
        assertEquals("—", MetricTable.busiestDateText(null))
    }

    @Test
    fun `hasAnyRecordedPoint 判定窗口外与全缺口`() {
        val values = IntArray(24)
        assertTrue(MetricTable.hasAnyRecordedPoint(detail(hourlyDay(values))))
        assertFalse(
            MetricTable.hasAnyRecordedPoint(detail(hourlyDay(values, Coverage.NO_RECORD)))
        )
        assertFalse(
            MetricTable.hasAnyRecordedPoint(detail(hourlyDay(values, Coverage.GAP)))
        )
    }

    @Test
    fun `percentInt 对 0 分母不返回 NaN`() {
        assertEquals(0, MetricTable.percentInt(5, 0))
        assertEquals(50, MetricTable.percentInt(1, 2))
        assertEquals(67, MetricTable.percentInt(2, 3))
    }
}
