package com.screentrace.app.data.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.screentrace.app.contract.DailyPoint
import com.screentrace.app.contract.UsageCalculator
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** 概览 2×2 卡片项（与 HTML 报告四张概览卡完全同源）。 */
data class PngKpiCard(
    val label: String,
    val value: String
)

/** 每日使用行项（无记录日必须显式标「无记录」，严禁伪造 0 分钟）。 */
data class PngDailyRow(
    val dateLabel: String,
    val durationText: String,
    val barFraction: Float,
    val recorded: Boolean
)

/** 应用排行行项（勾选隐藏应用名称时只留「应用 N」+ 占比 + 颜色）。 */
data class PngAppRow(
    val displayName: String,
    val durationText: String,
    val percentText: String,
    val colorHex: String
)

/** 24 小时时段分布柱项。 */
data class PngHourBar(
    val hour: Int,
    val heightFraction: Float
)

/**
 * PNG 长图中间排版模型（纯数据，不依赖 Android Framework，供单测与 Canvas 渲染器共用）。
 */
data class PngReportModel(
    val title: String = "屏迹 · 使用报告",
    val rangeLabel: String,
    val exportedLine: String,
    val isEmpty: Boolean,
    val emptyMessage: String?,
    val kpiCards: List<PngKpiCard>,
    val goalLine: String?,
    val dailyRows: List<PngDailyRow>,
    val dailyOmittedCount: Int,
    val hideAppNames: Boolean,
    val hideAppNamesNotice: String?,
    val appRows: List<PngAppRow>,
    val appsOmittedCount: Int,
    val appsTotalLine: String?,
    val hourBars: List<PngHourBar>,
    val methodologyNotes: List<String>
)

/**
 * 14a 余项：纯离线 PNG 长图导出器（§5.11 / §7-12）。
 *
 * 设计红线：
 * 1. **与 HTML 报告及页内数字 100% 同源**：直接消费 [HtmlReportInput]，不做二次聚合；
 *    `averageScreenUnlockMin == null` 显示 `—`，无记录日显示 `无记录`，绝不凭空造 0 分钟。
 * 2. **纯离线零依赖**：仅使用 Android 原生 [Canvas] / [Bitmap] 渲染，无网络/CDN/外链字体。
 * 3. **隐私脱敏**：`hideAppNames = true` 时将所有应用名替换为「应用 1」「应用 2」，保留占比与色点。
 * 4. **防长区间 OOM**：单日明细上限 [MAX_DAILY_ROWS] 行、应用排行上限 [MAX_APP_ROWS] 行，
 *    超出时保留最近明细并在底部注明省略天数，同时确保 `Bitmap` 在编码完成后立即 `recycle()`。
 */
object PngReportExporter {

    const val DEFAULT_WIDTH_PX = 1080
    const val MAX_DAILY_ROWS = 62
    const val MAX_APP_ROWS = 30

    /** SAF 建议文件名：`screen-time-report-YYYYMMDD-HHmm.png`。 */
    fun fileName(at: LocalDateTime): String =
        "screen-time-report-${at.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.png"

    /** 与 [HtmlReportExporter] 一致的 10 色固定色板，保证同一份数据导出 HTML/PNG 观感统一。 */
    private val CHART_COLORS = listOf(
        "#1B6B5A", "#2C576D", "#542F6A", "#6D2C4F", "#703529",
        "#705829", "#466732", "#424C57", "#1F7A8C", "#8A5A2B"
    )

    private const val COLOR_BG = 0xFFF4F7F3.toInt()
    private const val COLOR_CARD = 0xFFFFFFFF.toInt()
    private const val COLOR_SUB_CARD = 0xFFF1F6F2.toInt()
    private const val COLOR_TEXT_PRIMARY = 0xFF16241E.toInt()
    private const val COLOR_TEXT_SECTION = 0xFF3C4A43.toInt()
    private const val COLOR_TEXT_MUTED = 0xFF5C6B63.toInt()
    private const val COLOR_DIVIDER = 0xFFEDF1EE.toInt()
    private const val COLOR_TRACK = 0xFFE6EDE8.toInt()
    private const val COLOR_ACCENT = 0xFF1B6B5A.toInt()

    // 固定排版度量（1080px 画布宽度下的像素值）
    private const val OUTER_PAD = 44f
    private const val SECTION_GAP = 28f
    private const val CARD_PAD = 34f
    private const val CARD_RADIUS = 28f
    private const val SUB_CARD_RADIUS = 20f
    private const val HEADER_HEIGHT = 166f
    private const val SECTION_TITLE_H = 54f
    private const val KPI_CARD_H = 112f
    private const val KPI_ROW_GAP = 18f
    private const val GOAL_LINE_H = 44f
    private const val TABLE_HEADER_H = 44f
    private const val DATA_ROW_H = 52f
    private const val NOTE_LINE_H = 38f
    private const val HOUR_CHART_H = 190f
    private const val HOUR_LABEL_H = 34f
    private const val SUB_LINE_H = 40f

    /** 将 [HtmlReportInput] 转换为纯 Kotlin 排版模型（不触发 Android 图形调用）。 */
    fun buildModel(input: HtmlReportInput): PngReportModel {
        val s = input.summary
        val appsTotal = input.apps.sumOf { it.minutes }
        val exportedLine = "导出时间 ${input.exportedAt} · v${input.appVersion}"

        if (!s.hasAnyRecord && appsTotal == 0) {
            return PngReportModel(
                rangeLabel = input.rangeLabel,
                exportedLine = exportedLine,
                isEmpty = true,
                emptyMessage = "该区间没有可用记录。可能是尚未授权拉取，或系统未保留这段时间的数据。",
                kpiCards = emptyList(),
                goalLine = null,
                dailyRows = emptyList(),
                dailyOmittedCount = 0,
                hideAppNames = input.hideAppNames,
                hideAppNamesNotice = null,
                appRows = emptyList(),
                appsOmittedCount = 0,
                appsTotalLine = null,
                hourBars = emptyList(),
                methodologyNotes = emptyList()
            )
        }

        val kpiCards = listOf(
            PngKpiCard("屏幕使用合计", UsageCalculator.formatDuration(s.totalScreenUnlockMin)),
            PngKpiCard("完整日日均", s.averageScreenUnlockMin?.let { UsageCalculator.formatDuration(it) } ?: "—"),
            PngKpiCard("日均解锁", if (s.averageUnlocks > 0) "${s.averageUnlocks} 次" else "—"),
            PngKpiCard("有记录天数", "${s.recordedDays} / ${s.totalDays} 天")
        )

        val goalLine = input.dailyGoalMin?.let { goal ->
            val goalText = UsageCalculator.formatDuration(goal)
            val avg = s.averageScreenUnlockMin
            if (avg != null) {
                val pct = if (goal > 0) (avg * 100 / goal).coerceAtLeast(0) else 0
                "日均 $avg 分钟 / 目标 $goalText（$pct%）"
            } else {
                "日均 — / 目标 $goalText"
            }
        }

        val dailyOmitted = (input.daily.size - MAX_DAILY_ROWS).coerceAtLeast(0)
        val visibleDaily = if (dailyOmitted > 0) input.daily.takeLast(MAX_DAILY_ROWS) else input.daily
        val maxDaily = visibleDaily.filter { it.recorded }.maxOfOrNull { it.minutes } ?: 0
        val dailyRows = visibleDaily.map { point ->
            val minutes = if (point.recorded) point.minutes else 0
            val fraction = if (maxDaily > 0 && point.recorded) {
                (minutes.toFloat() / maxDaily.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            PngDailyRow(
                dateLabel = dayLabel(point),
                durationText = if (point.recorded) UsageCalculator.formatDuration(minutes) else "无记录",
                barFraction = fraction,
                recorded = point.recorded
            )
        }

        val appsOmitted = (input.apps.size - MAX_APP_ROWS).coerceAtLeast(0)
        val visibleApps = if (appsOmitted > 0) input.apps.take(MAX_APP_ROWS) else input.apps
        val appRows = visibleApps.mapIndexed { index, app ->
            val percent = if (appsTotal > 0) app.minutes * 100 / appsTotal else 0
            val label = if (input.hideAppNames) "应用 ${index + 1}" else app.name
            PngAppRow(
                displayName = label,
                durationText = UsageCalculator.formatDuration(app.minutes),
                percentText = "$percent%",
                colorHex = CHART_COLORS[index % CHART_COLORS.size]
            )
        }
        val hideNotice = if (input.hideAppNames && appRows.isNotEmpty()) {
            "已隐藏应用名称，仅显示占比与颜色。"
        } else {
            null
        }
        val appsTotalLine = if (appRows.isNotEmpty()) {
            "应用时长合计 ${UsageCalculator.formatDuration(appsTotal)}；各应用相加可能大于屏幕使用合计。"
        } else {
            null
        }

        val hourBars = if (input.hours.any { it.minutes > 0 }) {
            val byHour = input.hours.associateBy { it.hour }
            val maxHour = input.hours.maxOfOrNull { it.minutes } ?: 0
            (0..23).map { h ->
                val mins = byHour[h]?.minutes ?: 0
                val fraction = if (maxHour > 0) (mins.toFloat() / maxHour.toFloat()).coerceIn(0f, 1f) else 0f
                PngHourBar(hour = h, heightFraction = fraction)
            }
        } else {
            emptyList()
        }

        val notes = listOf(
            "· 时长取系统使用记录的屏幕会话，与应用自身统计存在分钟级差异。",
            "· 日均只除以有记录的完整自然日；无记录日不按 0 参与平均。",
            "· 本长图由本机离线渲染，不含任何外部资源，隐私数据不出设备。"
        )

        return PngReportModel(
            rangeLabel = input.rangeLabel,
            exportedLine = exportedLine,
            isEmpty = false,
            emptyMessage = null,
            kpiCards = kpiCards,
            goalLine = goalLine,
            dailyRows = dailyRows,
            dailyOmittedCount = dailyOmitted,
            hideAppNames = input.hideAppNames,
            hideAppNamesNotice = hideNotice,
            appRows = appRows,
            appsOmittedCount = appsOmitted,
            appsTotalLine = appsTotalLine,
            hourBars = hourBars,
            methodologyNotes = notes
        )
    }

    /** 根据排版模型精确计算长图总高度（像素），保证画布无截断且底部留白一致。 */
    fun estimateHeightPx(model: PngReportModel): Int {
        var y = OUTER_PAD + HEADER_HEIGHT
        if (model.isEmpty) {
            y += CARD_PAD * 2 + 96f
            y += OUTER_PAD
            return y.roundToInt()
        }

        // 1. 概览卡（2×2 网格 + 可选目标行）
        val overviewH = CARD_PAD * 2 + SECTION_TITLE_H + KPI_CARD_H * 2 + KPI_ROW_GAP +
            (if (model.goalLine != null) GOAL_LINE_H else 0f)
        y += overviewH + SECTION_GAP

        // 2. 每日使用
        if (model.dailyRows.isNotEmpty()) {
            val dailyH = CARD_PAD * 2 + SECTION_TITLE_H + TABLE_HEADER_H +
                model.dailyRows.size * DATA_ROW_H +
                (if (model.dailyOmittedCount > 0) SUB_LINE_H else 0f)
            y += dailyH + SECTION_GAP
        }

        // 3. 应用排行
        if (model.appRows.isNotEmpty()) {
            val appsH = CARD_PAD * 2 + SECTION_TITLE_H +
                (if (model.hideAppNamesNotice != null) SUB_LINE_H else 0f) +
                TABLE_HEADER_H + model.appRows.size * DATA_ROW_H +
                (if (model.appsOmittedCount > 0) SUB_LINE_H else 0f) +
                (if (model.appsTotalLine != null) SUB_LINE_H + 10f else 0f)
            y += appsH + SECTION_GAP
        }

        // 4. 时段分布
        if (model.hourBars.isNotEmpty()) {
            val hoursH = CARD_PAD * 2 + SECTION_TITLE_H + HOUR_CHART_H + HOUR_LABEL_H
            y += hoursH + SECTION_GAP
        }

        // 5. 口径说明
        val notesH = CARD_PAD * 2 + SECTION_TITLE_H + model.methodologyNotes.size * NOTE_LINE_H
        y += notesH + OUTER_PAD

        return y.roundToInt()
    }

    /** 将 [PngReportModel] 绘制为 Android [Bitmap]（ARGB_8888，纯离线 Canvas 渲染）。 */
    fun renderBitmap(model: PngReportModel, widthPx: Int = DEFAULT_WIDTH_PX): Bitmap {
        val safeWidth = widthPx.coerceAtLeast(640)
        val heightPx = estimateHeightPx(model)
        val bitmap = Bitmap.createBitmap(safeWidth, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(COLOR_BG)

        val boldTypeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        val regularTypeface = Typeface.SANS_SERIF

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_PRIMARY
            textSize = 46f
            typeface = boldTypeface
        }
        val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_SECTION
            textSize = 31f
            typeface = boldTypeface
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_PRIMARY
            textSize = 27f
            typeface = regularTypeface
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_PRIMARY
            textSize = 38f
            typeface = boldTypeface
        }
        val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_MUTED
            textSize = 24f
            typeface = regularTypeface
        }
        val smallMutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_MUTED
            textSize = 21f
            typeface = regularTypeface
        }
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_CARD }
        val subCardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_SUB_CARD }
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_DIVIDER
            strokeWidth = 2f
        }
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_TRACK }
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_ACCENT }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        val contentLeft = OUTER_PAD
        val contentRight = safeWidth - OUTER_PAD
        val contentWidth = contentRight - contentLeft
        val innerLeft = contentLeft + CARD_PAD
        val innerRight = contentRight - CARD_PAD
        val innerWidth = innerRight - innerLeft

        // ---- 头部 ----
        var y = OUTER_PAD
        canvas.drawText(model.title, contentLeft, y + 48f, titlePaint)
        canvas.drawText(
            ellipsize(model.rangeLabel, mutedPaint, contentWidth),
            contentLeft,
            y + 96f,
            mutedPaint
        )
        canvas.drawText(
            ellipsize(model.exportedLine, smallMutedPaint, contentWidth),
            contentLeft,
            y + 134f,
            smallMutedPaint
        )
        y += HEADER_HEIGHT

        if (model.isEmpty) {
            val emptyH = CARD_PAD * 2 + 96f
            canvas.drawRoundRect(
                RectF(contentLeft, y, contentRight, y + emptyH),
                CARD_RADIUS,
                CARD_RADIUS,
                cardPaint
            )
            val msg = model.emptyMessage ?: "该区间没有可用记录。"
            val lines = wrapText(msg, bodyPaint, innerWidth)
            var ly = y + CARD_PAD + 36f
            lines.forEach { line ->
                canvas.drawText(line, innerLeft, ly, bodyPaint)
                ly += 40f
            }
            return bitmap
        }

        // ---- 1. 概览卡 ----
        run {
            val overviewH = CARD_PAD * 2 + SECTION_TITLE_H + KPI_CARD_H * 2 + KPI_ROW_GAP +
                (if (model.goalLine != null) GOAL_LINE_H else 0f)
            canvas.drawRoundRect(
                RectF(contentLeft, y, contentRight, y + overviewH),
                CARD_RADIUS,
                CARD_RADIUS,
                cardPaint
            )
            var cy = y + CARD_PAD
            canvas.drawText("概览", innerLeft, cy + 32f, sectionPaint)
            cy += SECTION_TITLE_H

            val gap = 18f
            val subW = (innerWidth - gap) / 2f
            model.kpiCards.forEachIndexed { idx, card ->
                val col = idx % 2
                val row = idx / 2
                val left = innerLeft + col * (subW + gap)
                val top = cy + row * (KPI_CARD_H + KPI_ROW_GAP)
                canvas.drawRoundRect(
                    RectF(left, top, left + subW, top + KPI_CARD_H),
                    SUB_CARD_RADIUS,
                    SUB_CARD_RADIUS,
                    subCardPaint
                )
                canvas.drawText(card.label, left + 22f, top + 40f, smallMutedPaint)
                canvas.drawText(
                    ellipsize(card.value, valuePaint, subW - 44f),
                    left + 22f,
                    top + 90f,
                    valuePaint
                )
            }
            cy += KPI_CARD_H * 2 + KPI_ROW_GAP
            model.goalLine?.let { goal ->
                canvas.drawText(
                    ellipsize(goal, smallMutedPaint, innerWidth),
                    innerLeft,
                    cy + 34f,
                    smallMutedPaint
                )
            }
            y += overviewH + SECTION_GAP
        }

        // ---- 2. 每日使用 ----
        if (model.dailyRows.isNotEmpty()) {
            val dailyH = CARD_PAD * 2 + SECTION_TITLE_H + TABLE_HEADER_H +
                model.dailyRows.size * DATA_ROW_H +
                (if (model.dailyOmittedCount > 0) SUB_LINE_H else 0f)
            canvas.drawRoundRect(
                RectF(contentLeft, y, contentRight, y + dailyH),
                CARD_RADIUS,
                CARD_RADIUS,
                cardPaint
            )
            var cy = y + CARD_PAD
            canvas.drawText("每日使用", innerLeft, cy + 32f, sectionPaint)
            cy += SECTION_TITLE_H

            val dateX = innerLeft
            val durX = innerLeft + innerWidth * 0.28f
            val barLeft = innerLeft + innerWidth * 0.62f
            val barWidth = innerRight - barLeft

            canvas.drawText("日期", dateX, cy + 28f, smallMutedPaint)
            canvas.drawText("时长", durX, cy + 28f, smallMutedPaint)
            canvas.drawText("占比", barLeft, cy + 28f, smallMutedPaint)
            cy += TABLE_HEADER_H

            model.dailyRows.forEach { row ->
                canvas.drawLine(innerLeft, cy, innerRight, cy, dividerPaint)
                val textY = cy + 35f
                canvas.drawText(row.dateLabel, dateX, textY, bodyPaint)
                val durPaint = if (row.recorded) bodyPaint else mutedPaint
                canvas.drawText(row.durationText, durX, textY, durPaint)

                val barTop = cy + (DATA_ROW_H - 14f) / 2f
                val barBottom = barTop + 14f
                canvas.drawRoundRect(
                    RectF(barLeft, barTop, barLeft + barWidth, barBottom),
                    7f,
                    7f,
                    trackPaint
                )
                if (row.barFraction > 0f) {
                    val fillW = (barWidth * row.barFraction).coerceAtLeast(10f)
                    canvas.drawRoundRect(
                        RectF(barLeft, barTop, barLeft + fillW, barBottom),
                        7f,
                        7f,
                        accentPaint
                    )
                }
                cy += DATA_ROW_H
            }
            if (model.dailyOmittedCount > 0) {
                canvas.drawText(
                    "仅展示最近 $MAX_DAILY_ROWS 天明细，其余 ${model.dailyOmittedCount} 天已计入上方汇总。",
                    innerLeft,
                    cy + 30f,
                    smallMutedPaint
                )
            }
            y += dailyH + SECTION_GAP
        }

        // ---- 3. 应用排行 ----
        if (model.appRows.isNotEmpty()) {
            val appsH = CARD_PAD * 2 + SECTION_TITLE_H +
                (if (model.hideAppNamesNotice != null) SUB_LINE_H else 0f) +
                TABLE_HEADER_H + model.appRows.size * DATA_ROW_H +
                (if (model.appsOmittedCount > 0) SUB_LINE_H else 0f) +
                (if (model.appsTotalLine != null) SUB_LINE_H + 10f else 0f)
            canvas.drawRoundRect(
                RectF(contentLeft, y, contentRight, y + appsH),
                CARD_RADIUS,
                CARD_RADIUS,
                cardPaint
            )
            var cy = y + CARD_PAD
            canvas.drawText("应用排行", innerLeft, cy + 32f, sectionPaint)
            cy += SECTION_TITLE_H

            model.hideAppNamesNotice?.let { notice ->
                canvas.drawText(notice, innerLeft, cy + 26f, smallMutedPaint)
                cy += SUB_LINE_H
            }

            val nameX = innerLeft
            val durX = innerLeft + innerWidth * 0.62f
            val pctRightX = innerRight

            canvas.drawText("应用", nameX, cy + 28f, smallMutedPaint)
            canvas.drawText("时长", durX, cy + 28f, smallMutedPaint)
            val pctHeaderW = smallMutedPaint.measureText("占比")
            canvas.drawText("占比", pctRightX - pctHeaderW, cy + 28f, smallMutedPaint)
            cy += TABLE_HEADER_H

            val maxNameW = (durX - nameX) - 44f
            model.appRows.forEach { row ->
                canvas.drawLine(innerLeft, cy, innerRight, cy, dividerPaint)
                val textY = cy + 35f
                dotPaint.color = runCatching { Color.parseColor(row.colorHex) }.getOrDefault(COLOR_ACCENT)
                canvas.drawCircle(nameX + 9f, cy + DATA_ROW_H / 2f, 8f, dotPaint)
                canvas.drawText(
                    ellipsize(row.displayName, bodyPaint, maxNameW),
                    nameX + 28f,
                    textY,
                    bodyPaint
                )
                canvas.drawText(row.durationText, durX, textY, bodyPaint)
                val pctW = mutedPaint.measureText(row.percentText)
                canvas.drawText(row.percentText, pctRightX - pctW, textY, mutedPaint)
                cy += DATA_ROW_H
            }
            if (model.appsOmittedCount > 0) {
                canvas.drawText(
                    "仅展示前 $MAX_APP_ROWS 项应用，其余 ${model.appsOmittedCount} 项已计入合计。",
                    innerLeft,
                    cy + 30f,
                    smallMutedPaint
                )
                cy += SUB_LINE_H
            }
            model.appsTotalLine?.let { totalLine ->
                canvas.drawText(
                    ellipsize(totalLine, smallMutedPaint, innerWidth),
                    innerLeft,
                    cy + 34f,
                    smallMutedPaint
                )
            }
            y += appsH + SECTION_GAP
        }

        // ---- 4. 时段分布 ----
        if (model.hourBars.isNotEmpty()) {
            val hoursH = CARD_PAD * 2 + SECTION_TITLE_H + HOUR_CHART_H + HOUR_LABEL_H
            canvas.drawRoundRect(
                RectF(contentLeft, y, contentRight, y + hoursH),
                CARD_RADIUS,
                CARD_RADIUS,
                cardPaint
            )
            var cy = y + CARD_PAD
            canvas.drawText("时段分布（24 小时）", innerLeft, cy + 32f, sectionPaint)
            cy += SECTION_TITLE_H

            val barCount = model.hourBars.size.coerceAtLeast(1)
            val slotW = innerWidth / barCount.toFloat()
            val barW = (slotW * 0.56f).coerceIn(10f, 22f)
            val chartBottom = cy + HOUR_CHART_H

            // 底部基线
            canvas.drawLine(innerLeft, chartBottom, innerRight, chartBottom, dividerPaint)

            model.hourBars.forEachIndexed { idx, bar ->
                val centerX = innerLeft + (idx + 0.5f) * slotW
                val left = centerX - barW / 2f
                val right = centerX + barW / 2f
                // 背景槽
                canvas.drawRoundRect(
                    RectF(left, cy + 8f, right, chartBottom),
                    barW / 2f,
                    barW / 2f,
                    trackPaint
                )
                if (bar.heightFraction > 0f) {
                    val h = ((HOUR_CHART_H - 8f) * bar.heightFraction).coerceAtLeast(8f)
                    canvas.drawRoundRect(
                        RectF(left, chartBottom - h, right, chartBottom),
                        barW / 2f,
                        barW / 2f,
                        accentPaint
                    )
                }
                if (bar.hour % 3 == 0 || bar.hour == 23) {
                    val label = "${bar.hour}"
                    val lw = smallMutedPaint.measureText(label)
                    canvas.drawText(label, centerX - lw / 2f, chartBottom + 28f, smallMutedPaint)
                }
            }
            y += hoursH + SECTION_GAP
        }

        // ---- 5. 口径说明 ----
        run {
            val notesH = CARD_PAD * 2 + SECTION_TITLE_H + model.methodologyNotes.size * NOTE_LINE_H
            canvas.drawRoundRect(
                RectF(contentLeft, y, contentRight, y + notesH),
                CARD_RADIUS,
                CARD_RADIUS,
                cardPaint
            )
            var cy = y + CARD_PAD
            canvas.drawText("口径说明", innerLeft, cy + 32f, sectionPaint)
            cy += SECTION_TITLE_H
            model.methodologyNotes.forEach { note ->
                canvas.drawText(
                    ellipsize(note, smallMutedPaint, innerWidth),
                    innerLeft,
                    cy + 26f,
                    smallMutedPaint
                )
                cy += NOTE_LINE_H
            }
        }

        return bitmap
    }

    /** 端到端离线编码为标准 PNG 字节流，编码完成后立即回收 [Bitmap] 防内存泄漏。 */
    fun renderPngBytes(input: HtmlReportInput, widthPx: Int = DEFAULT_WIDTH_PX): ByteArray {
        val model = buildModel(input)
        val bitmap = renderBitmap(model, widthPx)
        return try {
            ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG 编码失败" }
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun dayLabel(point: DailyPoint): String =
        "${point.date.monthValue}月${point.date.dayOfMonth}日"

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (maxWidth <= 0f || paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val ellipsisW = paint.measureText(ellipsis)
        if (ellipsisW >= maxWidth) return ellipsis
        var end = text.length
        while (end > 0 && paint.measureText(text, 0, end) + ellipsisW > maxWidth) {
            end--
        }
        return text.substring(0, end) + ellipsis
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (maxWidth <= 0f || paint.measureText(text) <= maxWidth) return listOf(text)
        val lines = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = start + 1
            while (end <= text.length && paint.measureText(text, start, end) <= maxWidth) {
                end++
            }
            val cut = (end - 1).coerceAtLeast(start + 1)
            lines.add(text.substring(start, cut))
            start = cut
        }
        return lines
    }
}
