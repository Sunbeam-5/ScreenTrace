package com.screentrace.app.ui

import com.screentrace.app.contract.Coverage
import com.screentrace.app.contract.DailyPoint
import com.screentrace.app.contract.MetricKind
import com.screentrace.app.contract.UsageCalculator
import com.screentrace.app.ui.detail.MetricTable
import com.screentrace.app.ui.detail.MetricTableRow
import com.screentrace.app.uikit.AppBreakdownMath
import com.screentrace.app.uikit.BreakdownRow

/**
 * CR-30 阶段 E（§7-68）：TalkBack 读屏语义纯函数集合（无 Android 依赖，可直接 JVM 单测）。
 *
 * 所有图表摘要与数据表行播报均守住核心红线：
 * - **无记录 / 采集缺口**绝不播报为「0 次」或「0 分钟」；
 * - **日夜占比**缺分母（`null`）时绝不播报为「日间 0%，夜间 100%」。
 */
object AccessibilityDescriptions {

    private val PERIOD_LABELS = listOf("6点到12点", "12点到18点", "18点到22点", "22点到6点")

    fun trendChartSummary(points: List<DailyPoint>, averageMinutes: Int?): String {
        val recorded = points.filter { it.recorded }
        if (recorded.isEmpty()) return "亮屏趋势图，当前区间暂无使用记录"
        val peak = recorded.maxByOrNull { it.minutes }
        val avgText = averageMinutes?.let { "，日均 ${UsageCalculator.formatDuration(it)}" }.orEmpty()
        val peakText = peak?.let {
            "，最高 ${it.date.monthValue}月${it.date.dayOfMonth}日 ${UsageCalculator.formatDuration(it.minutes)}"
        }.orEmpty()
        return "亮屏趋势图，共 ${recorded.size} 天有记录$avgText$peakText"
    }

    fun periodDistributionSummary(periods: List<Int>, percents: List<Int>): String {
        val total = periods.sumOf { it.coerceAtLeast(0) }
        if (total <= 0) return "时段分布图，暂无时段数据"
        val parts = (0 until 4).map { idx ->
            val mins = periods.getOrElse(idx) { 0 }.coerceAtLeast(0)
            val pct = percents.getOrElse(idx) { 0 }.coerceAtLeast(0)
            "${PERIOD_LABELS[idx]} ${UsageCalculator.formatDuration(mins)} 占 $pct%"
        }
        return "时段分布：${parts.joinToString("，")}"
    }

    fun dayNightBarSummary(dayPercent: Int?): String {
        val split = AppBreakdownMath.dayNightSplit(dayPercent)
            ?: return "日间与夜间分布暂无数据"
        return "日间占比 ${split.dayPercent}%，夜间占比 ${split.nightPercent}%"
    }

    fun appBreakdownSummary(rows: List<BreakdownRow>): String {
        if (rows.isEmpty()) return "应用构成，暂无应用数据"
        val topText = rows.take(3).joinToString("，") {
            "${it.name} ${it.percent}%"
        }
        return "应用构成色带，主要包含 $topText"
    }

    fun metricTableRowSummary(
        row: MetricTableRow,
        metric: MetricKind,
        singleDay: Boolean
    ): String {
        val unitLabel = when (metric) {
            MetricKind.UNLOCK -> "解锁"
            MetricKind.NOTIFICATION -> "通知"
        }
        if (!row.hasRecord || row.count == null) {
            val emptyDesc = when (row.coverage) {
                Coverage.GAP -> "拉取缺口"
                Coverage.NO_RECORD, Coverage.RECORDED -> MetricTable.emptyLabel(row.coverage, singleDay)
            }
            return "${row.label}，$emptyDesc"
        }
        val pctPart = if (row.percentText == "—") "" else "，占比 ${row.percentText}"
        return "${row.label}，$unitLabel ${row.count} 次$pctPart"
    }

    fun notificationAppRowSummary(name: String, interruptions: Int, seen: Int): String =
        "$name，打扰 $interruptions 次，已查看 $seen 次"
}
