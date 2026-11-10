package com.screentrace.app.ui.detail

import com.screentrace.app.contract.Coverage
import com.screentrace.app.contract.MetricDetail
import com.screentrace.app.contract.MetricPoint
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 详情页「时间段 / 次数 / 占比」表格的一行（§5.7 第 1 条）。
 *
 * [count] 为 null 表示**该时段没有记录**（窗口外 / 拉取缺口），不是 0——
 * 这一区分是 §7-16 的红线：把无记录画成 0 就是伪造数据。
 */
data class MetricTableRow(
    val label: String,
    val count: Int?,
    val percentText: String,
    val coverage: Coverage
) {
    /** 无记录行不显示 0，交给 UI 显示「该时段无记录」。 */
    val hasRecord: Boolean get() = count != null
}

/**
 * 详情页表格与摘要的纯函数层（无 Android 依赖，可直接 JVM 单测）。
 *
 * 与图表**同源**：两者都吃同一个 [MetricDetail]，不各算一套（§7-16「图表与数据表
 * 渲染同一查询结果」）。占比分母 = 区间合计 `detail.total`，与摘要大数字同源，
 * 因此表格占比之和恒为 100%（可测）。
 */
object MetricTable {

    /** 表格行（时间升序）；单日 = 24 行逐小时，区间 = 逐日。 */
    fun rows(detail: MetricDetail): List<MetricTableRow> =
        detail.points.map { point ->
            val recorded = point.coverage == Coverage.RECORDED
            MetricTableRow(
                label = pointLabel(point),
                count = if (recorded) point.value else null,
                percentText = if (recorded) percentText(point.value, detail.total) else "—",
                coverage = point.coverage
            )
        }

    /** 单点标签：单日「9–10时」，区间「11月8日」。 */
    fun pointLabel(point: MetricPoint): String = point.hour?.let { h ->
        if (h == 23) "23–24时" else "$h–${h + 1}时"
    } ?: dateLabel(point.date)

    fun dateLabel(date: LocalDate): String = "${date.monthValue}月${date.dayOfMonth}日"

    /**
     * 占比文本：`value / total`，保留 1 位小数。
     * 分母为 0（区间内一次都没有）时返回「—」——不写 0.0%，那是把「没有数据」当「占比为零」。
     */
    fun percentText(value: Int, total: Int): String {
        if (total <= 0) return "—"
        val percent = value * 1000.0 / total / 10.0
        return "${String.format(Locale.ROOT, "%.1f", percent)}%"
    }

    /** 「最高频时段」文案（§5.7 解锁明细区）：无数据时诚实显示「—」。 */
    fun peakHourText(peakHour: Int?): String = peakHour?.let { h ->
        if (h == 23) "23–24时" else "$h–${h + 1}时"
    } ?: "—"

    /** 「每小时平均」文案：保留 1 位小数，单位次数/小时。 */
    fun hourlyAverageText(average: Double?): String =
        average?.let { "${String.format(Locale.ROOT, "%.1f", it)} 次/小时" } ?: "—"

    /** 「最活跃日期」文案。 */
    fun busiestDateText(date: LocalDate?): String = date?.let { dateLabel(it) } ?: "—"

    /** 摘要副行：区间态给「合计 N 次」，单日态给「当日累计」。 */
    fun summaryCaption(detail: MetricDetail): String {
        val single = detail.selection is com.screentrace.app.contract.DateSelection.Day
        return if (single) {
            "当日累计"
        } else {
            val average = detail.dailyAverage ?: 0
            "日均 $average 次 · 区间合计 ${detail.total} 次"
        }
    }

    /** 图表/表格的空态判定：整段区间一次都没有（且全是无记录）时不该画一排空框。 */
    fun hasAnyRecordedPoint(detail: MetricDetail): Boolean =
        detail.points.any { it.coverage == Coverage.RECORDED }

    /** 百分比整数形式（图表刻度/进度条用），避免 UI 各写一套取整。 */
    fun percentInt(value: Int, total: Int): Int =
        if (total <= 0) 0 else ((value * 100.0) / total).roundToInt()
}
