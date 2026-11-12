package com.screentrace.app.data.export

import com.screentrace.app.contract.AppUsage
import com.screentrace.app.contract.DailyPoint
import com.screentrace.app.contract.HourBucket
import com.screentrace.app.contract.Summary
import com.screentrace.app.contract.UsageCalculator
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * §5.8 / §7-12：导出 HTML 报告所需的数据。
 *
 * 所有字段都由**仓库既有查询**产出（与页面同源，§7-12「导出内容与页内数字必须同源」），
 * 本类只负责排版，不做任何二次聚合——否则「页面 6h12m、报告 6h30m」的经典对不上又回来了。
 */
data class HtmlReportInput(
    /** 区间文案，例：`11月6日 – 11月12日 · 7天`。 */
    val rangeLabel: String,
    /** 导出时刻文案，例：`2026-11-12 08:10`。 */
    val exportedAt: String,
    val summary: Summary,
    val daily: List<DailyPoint>,
    val apps: List<AppUsage>,
    val hours: List<HourBucket>,
    val appVersion: String,
    /** 导出前可勾选「隐藏应用名称」：只留占比与颜色（§5.11 分享与导出）。 */
    val hideAppNames: Boolean = false,
    /** 每日目标（分钟），用于进度文案；null 表示不展示。 */
    val dailyGoalMin: Int? = null
)

/**
 * 自包含 HTML 报告（§7-12）。
 *
 * 硬约束——**不引用任何外部资源**：没有 CDN、没有在线字体、没有 `<script>`、
 * 没有 `<img src="http...">`。断网打开必须与联网打开完全一致，控制台不出现一次请求。
 * 因此样式全部内联在 `<style>` 里，图形全部用 `<div>` 宽度百分比画（不引图表库）。
 *
 * 文本一律过 [escape]，应用名来自系统（可能是 `R&D <Beta>`），不转义就会把标签写坏。
 */
object HtmlReportExporter {

    /** SAF 建议文件名：`screen-time-report-YYYYMMDD-HHmm.html`。 */
    fun fileName(at: LocalDateTime): String =
        "screen-time-report-${at.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.html"

    /** 报告里的图表配色（与 App 九套主题无关，保持单份报告在任何设备上同一观感）。 */
    private val CHART_COLORS = listOf(
        "#1B6B5A", "#2C576D", "#542F6A", "#6D2C4F", "#703529",
        "#705829", "#466732", "#424C57", "#1F7A8C", "#8A5A2B"
    )

    fun build(input: HtmlReportInput): String {
        val s = input.summary
        val appsTotal = input.apps.sumOf { it.minutes }
        val maxDaily = input.daily.maxOfOrNull { it.minutes } ?: 0
        val maxHour = input.hours.maxOfOrNull { it.minutes } ?: 0

        return buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html lang=\"zh-CN\">")
            appendLine("<head>")
            appendLine("<meta charset=\"utf-8\">")
            // 手机浏览器上按设备宽度排版，报告多半在手机上打开
            appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            appendLine("<title>屏迹 · 使用报告</title>")
            appendLine("<style>")
            appendLine(CSS)
            appendLine("</style>")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("<main>")

            // ---- 头部 ----
            appendLine("<header>")
            appendLine("<h1>屏迹 · 使用报告</h1>")
            appendLine("<p class=\"sub\">${escape(input.rangeLabel)}</p>")
            appendLine("<p class=\"sub\">导出时间 ${escape(input.exportedAt)} · v${escape(input.appVersion)}</p>")
            appendLine("</header>")

            if (!s.hasAnyRecord && appsTotal == 0) {
                appendLine("<section><p class=\"empty\">该区间没有可用记录。可能是尚未授权拉取，或系统未保留这段时间的数据。</p></section>")
                appendLine("</main></body></html>")
                return@buildString
            }

            // ---- 概览 ----
            appendLine("<section>")
            appendLine("<h2>概览</h2>")
            appendLine("<div class=\"cards\">")
            appendLine(card("屏幕使用合计", UsageCalculator.formatDuration(s.totalScreenUnlockMin)))
            appendLine(card("有记录日日均", UsageCalculator.formatDuration(s.averageScreenUnlockMin)))
            appendLine(card("日均解锁", if (s.averageUnlocks > 0) "${s.averageUnlocks} 次" else "—"))
            appendLine(card("有记录天数", "${s.recordedDays} / ${s.totalDays} 天"))
            appendLine("</div>")
            input.dailyGoalMin?.let { goal ->
                val goalText = UsageCalculator.formatDuration(goal)
                val avg = s.averageScreenUnlockMin
                val pct = if (goal > 0) (avg * 100 / goal).coerceAtLeast(0) else 0
                appendLine("<p class=\"sub\">日均 $avg 分钟 / 目标 ${escape(goalText)}（$pct%）</p>")
            }
            appendLine("</section>")

            // ---- 每日明细 ----
            if (input.daily.isNotEmpty()) {
                appendLine("<section>")
                appendLine("<h2>每日使用</h2>")
                appendLine("<table>")
                appendLine("<thead><tr><th>日期</th><th>时长</th><th class=\"bar-col\">占比</th></tr></thead>")
                appendLine("<tbody>")
                input.daily.forEach { point ->
                    val minutes = if (point.recorded) point.minutes else 0
                    val width = if (maxDaily > 0 && point.recorded) {
                        (minutes * 100 / maxDaily).coerceIn(0, 100)
                    } else 0
                    appendLine(
                        "<tr><td>${escape(dayLabel(point))}</td>" +
                            "<td>${if (point.recorded) escape(UsageCalculator.formatDuration(minutes)) else "无记录"}</td>" +
                            "<td class=\"bar-col\">" +
                            "<span class=\"track\"><span class=\"fill\" style=\"width:$width%\"></span></span>" +
                            "</td></tr>"
                    )
                }
                appendLine("</tbody></table>")
                appendLine("</section>")
            }

            // ---- 应用排行 ----
            if (input.apps.isNotEmpty()) {
                appendLine("<section>")
                appendLine("<h2>应用排行</h2>")
                if (input.hideAppNames) {
                    appendLine("<p class=\"sub\">已隐藏应用名称，仅显示占比与颜色。</p>")
                }
                appendLine("<table>")
                appendLine("<thead><tr><th>应用</th><th>时长</th><th>占比</th></tr></thead>")
                appendLine("<tbody>")
                input.apps.forEachIndexed { index, app ->
                    val percent = if (appsTotal > 0) app.minutes * 100 / appsTotal else 0
                    val color = CHART_COLORS[index % CHART_COLORS.size]
                    val label = if (input.hideAppNames) "应用 ${index + 1}" else app.name
                    appendLine(
                        "<tr><td><span class=\"dot\" style=\"background:$color\"></span>" +
                            "${escape(label)}</td>" +
                            "<td>${escape(UsageCalculator.formatDuration(app.minutes))}</td>" +
                            "<td>$percent%</td></tr>"
                    )
                }
                appendLine("</tbody></table>")
                appendLine("<p class=\"sub\">应用时长合计 ${escape(UsageCalculator.formatDuration(appsTotal))}；" +
                    "各应用相加可能大于屏幕使用合计（同一时段多个应用并行时各自计数）。</p>")
                appendLine("</section>")
            }

            // ---- 时段分布 ----
            if (input.hours.any { it.minutes > 0 }) {
                appendLine("<section>")
                appendLine("<h2>时段分布</h2>")
                appendLine("<div class=\"hours\">")
                input.hours.sortedBy { it.hour }.forEach { bucket ->
                    val height = if (maxHour > 0) {
                        (bucket.minutes * 100 / maxHour).coerceIn(0, 100)
                    } else 0
                    appendLine(
                        "<div class=\"hour\">" +
                            "<span class=\"hbar\"><span style=\"height:$height%\"></span></span>" +
                            "<span class=\"hlabel\">${bucket.hour}</span>" +
                            "</div>"
                    )
                }
                appendLine("</div>")
                appendLine("</section>")
            }

            // ---- 口径说明 ----
            appendLine("<section>")
            appendLine("<h2>口径说明</h2>")
            appendLine("<ul class=\"notes\">")
            appendLine("<li>时长取系统使用记录的屏幕会话，与应用自身统计存在分钟级差异。</li>")
            appendLine("<li>日均只除以有记录的自然日；无记录日不按 0 参与平均。</li>")
            appendLine("<li>本报告为静态文件，不含任何外部资源与脚本，断网可正常阅读。</li>")
            appendLine("</ul>")
            appendLine("</section>")

            appendLine("</main>")
            appendLine("</body>")
            appendLine("</html>")
        }
    }

    private fun card(label: String, value: String): String =
        "<div class=\"card\"><span class=\"k\">${escape(label)}</span>" +
            "<span class=\"v\">${escape(value)}</span></div>"

    private fun dayLabel(point: DailyPoint): String =
        "${point.date.monthValue}月${point.date.dayOfMonth}日"

    /** HTML 文本转义。报告里的每个动态字符串都必须过这里。 */
    fun escape(raw: String): String = buildString(raw.length) {
        raw.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    private val CSS = """
        :root { color-scheme: light; }
        * { box-sizing: border-box; }
        body {
            margin: 0; padding: 20px 16px 40px;
            font-family: -apple-system, "Noto Sans SC", "PingFang SC", "Microsoft YaHei", sans-serif;
            background: #F4F7F3; color: #16241E; line-height: 1.5;
        }
        main { max-width: 720px; margin: 0 auto; }
        h1 { font-size: 22px; margin: 0 0 6px; }
        h2 { font-size: 15px; margin: 0 0 12px; color: #3C4A43; }
        header { margin-bottom: 20px; }
        .sub { margin: 2px 0; font-size: 12px; color: #5C6B63; }
        section {
            background: #FFFFFF; border-radius: 18px; padding: 16px;
            margin-bottom: 14px;
        }
        .cards { display: flex; flex-wrap: wrap; gap: 10px; }
        .card {
            flex: 1 1 44%; min-width: 140px; background: #F1F6F2;
            border-radius: 14px; padding: 12px;
            display: flex; flex-direction: column; gap: 4px;
        }
        .card .k { font-size: 12px; color: #5C6B63; }
        .card .v { font-size: 19px; font-weight: 600; }
        table { width: 100%; border-collapse: collapse; font-size: 13px; }
        th { text-align: left; font-weight: 500; color: #5C6B63; padding: 6px 4px; }
        td { padding: 6px 4px; border-top: 1px solid #EDF1EE; }
        .bar-col { width: 34%; }
        .track {
            display: block; height: 8px; border-radius: 99px; background: #E6EDE8;
            overflow: hidden;
        }
        .fill { display: block; height: 8px; border-radius: 99px; background: #1B6B5A; }
        .dot {
            display: inline-block; width: 8px; height: 8px; border-radius: 99px;
            margin-right: 6px; vertical-align: middle;
        }
        .hours { display: flex; align-items: flex-end; gap: 3px; height: 120px; }
        .hour { flex: 1; display: flex; flex-direction: column; height: 100%; }
        .hbar {
            flex: 1; display: flex; align-items: flex-end;
            background: #F1F6F2; border-radius: 6px; overflow: hidden;
        }
        .hbar > span { display: block; width: 100%; background: #1B6B5A; border-radius: 6px; }
        .hlabel { font-size: 9px; color: #7A8880; text-align: center; margin-top: 4px; }
        .notes { margin: 0; padding-left: 18px; font-size: 12px; color: #5C6B63; }
        .notes li { margin-bottom: 4px; }
        .empty { font-size: 13px; color: #5C6B63; }
    """.trimIndent()
}
