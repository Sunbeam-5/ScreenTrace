package com.screentrace.app.data.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.screentrace.app.contract.DailyPoint
import com.screentrace.app.contract.UsageCalculator
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PNG 长图视觉主题（与当前 App 的 [com.screentrace.app.theme.FreshPalette] 同步）。
 *
 * 保持纯 Android ARGB Int 令牌，不依赖 Compose，既支持 9 套内置主题 / 深浅色 / 动态取色 /
 * 自定义种子色 / 壁纸配色同步，也便于纯单测直接构造深浅色主题对比。
 */
data class PngReportTheme(
    val darkTheme: Boolean = false,
    val background: Int = 0xFFF4F7F3.toInt(),
    val gradientTop: Int = 0xFFD9ECE3.toInt(),
    val gradientMid: Int = 0xFFEAF3EE.toInt(),
    val cardSurface: Int = 0xFFFFFFFF.toInt(),
    val surfaceVariant: Int = 0xFFF1F6F2.toInt(),
    val primary: Int = 0xFF1B6B5A.toInt(),
    val ringGradientEnd: Int = 0xFF5FA595.toInt(),
    val ringOver: Int = 0xFF0E4539.toInt(),
    val primaryContainer: Int = 0xFFCDEEE0.toInt(),
    val onPrimaryContainer: Int = 0xFF113F35.toInt(),
    val secondaryContainer: Int = 0xFFD8EEF9.toInt(),
    val onSecondaryContainer: Int = 0xFF163D4D.toInt(),
    val onSurface: Int = 0xFF16241E.toInt(),
    val onSurfaceVariant: Int = 0xFF47574F.toInt(),
    val onSurfaceMuted: Int = 0xFF6B7B73.toInt(),
    val track: Int = 0xFFE3ECE6.toInt(),
    val barSurface: Int = 0xFFBBDCD0.toInt(),
    val outline: Int = 0xFFDCE5DF.toInt(),
    val shadow: Int = 0x161B6B5A,
    val overTarget: Int = 0xFFB04A28.toInt(),
    val overTargetSurface: Int = 0xFFFBE9E1.toInt(),
    val chipSurface: Int = 0xFFE6F1EC.toInt(),
    val chipOn: Int = 0xFF1B5B4D.toInt(),
    val nightIcon: Int = 0xFF5B5A8C.toInt(),
    val chartColors: List<Int> = DEFAULT_CHART_COLORS
) {
    companion object {
        val DEFAULT_CHART_COLORS: List<Int> = listOf(
            0xFF1B6B5A.toInt(),
            0xFF2C576D.toInt(),
            0xFF542F6A.toInt(),
            0xFF6D2C4F.toInt(),
            0xFF703529.toInt(),
            0xFF705829.toInt(),
            0xFF466732.toInt(),
            0xFF424C57.toInt(),
            0xFF1F7A8C.toInt(),
            0xFF8A5A2B.toInt()
        )

        val Default = PngReportTheme()
    }
}

/** Hero 圆环卡数据模型（复刻「今日」页 ① RingProgress 视觉语言）。 */
data class PngHeroModel(
    val caption: String,
    val valueText: String,
    val goalText: String?,
    val ringProgress: Float,
    val statusChipText: String?,
    val isOverTarget: Boolean,
    val longestSessionText: String
)

/** Tonal 双卡模型（复刻「今日」页 ② MetricCardPair）。 */
data class PngTonalCard(
    val label: String,
    val value: String,
    val note: String
)

/** 应用构成行（复刻「今日」页 ③ AppBreakdownSection：色带 + 精简图例）。 */
data class PngAppSlice(
    val displayName: String,
    val durationText: String,
    val percentText: String,
    val fraction: Float,
    val colorArgb: Int
)

/** 每日趋势柱图项（复刻「统计」页 ① CapsuleBarChart）。 */
data class PngTrendBar(
    val axisLabel: String,
    val minutes: Int,
    val heightFraction: Float,
    val recorded: Boolean,
    val isPeak: Boolean
)

/** 24 小时时段胶囊柱项（复刻「统计」页 / 小组件 24h 时段分布）。 */
data class PngHourBar(
    val hour: Int,
    val heightFraction: Float,
    val isPeak: Boolean
)

/**
 * PNG 长图中间排版模型（纯数据，不依赖 Android Framework，供单测与 Canvas 渲染器共用）。
 */
data class PngReportModel(
    val brandTitle: String = "屏迹",
    val rangeLabel: String,
    val exportedAt: String,
    val appVersion: String,
    val isEmpty: Boolean,
    val emptyMessage: String?,
    val hero: PngHeroModel?,
    val tonalCards: List<PngTonalCard>,
    val hideAppNames: Boolean,
    val hideAppNamesNotice: String?,
    val appsTotalText: String?,
    val appSlices: List<PngAppSlice>,
    val dayPercent: Int?,
    val trendSubtitle: String?,
    val trendPeakText: String?,
    val trendBars: List<PngTrendBar>,
    val trendAvgFraction: Float?,
    val trendAvgLabel: String?,
    val hasUnrecordedTrendDays: Boolean,
    val hourPeakText: String?,
    val hourBars: List<PngHourBar>,
    val footerText: String
)

/**
 * 14a 余项：纯离线 Fresh UI 风格 PNG 长图导出器（§5.11 / §7-12）。
 *
 * 视觉与数据原则：
 * 1. **与 App 当前主题 1:1 同步**：接收 [PngReportTheme]（三段渐变背景、不透明卡片、tonal 双卡、
 *    主题色带与深浅色模式）。
 * 2. **复刻「今日 + 统计」界面质感**：Hero 双圈圆环进度卡 → Tonal 双指标卡 → 应用构成色带 +
 *    Top 8 精简图例 + 日/夜占比条 → 统计页风格胶囊趋势柱图（含虚线日均线）→ 24 小时胶囊柱图 →
 *    极简页脚，去除笨重表格与冗长免责区块。
 * 3. **与 HTML 及页内数字 100% 同源**：直接消费 [HtmlReportInput]，零二次聚合；严格遵循 D-07
 *    （`averageScreenUnlockMin == null` 显示 `—`，无记录日不伪造 0 分钟），支持「隐藏应用名称」脱敏。
 * 4. **安全防 OOM**：趋势图最多展示最近 [MAX_TREND_DAYS] 天、应用榜聚合 Top [MAX_TOP_APPS] + 「其他」，
 *    并在 [renderPngBytes] 的 `finally` 中立即回收 `Bitmap`。
 */
object PngReportExporter {

    const val DEFAULT_WIDTH_PX = 1080
    const val MAX_TREND_DAYS = 14
    const val MAX_TOP_APPS = 8

    /** SAF 建议文件名：`screen-time-report-YYYYMMDD-HHmm.png`。 */
    fun fileName(at: LocalDateTime): String =
        "screen-time-report-${at.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.png"

    // 画布排版度量（1080px 基准）
    private const val OUTER_PAD = 48f
    private const val CARD_GAP = 28f
    private const val CARD_PAD = 38f
    private const val CARD_RADIUS = 36f
    private const val TONAL_RADIUS = 30f
    private const val HEADER_H = 128f
    private const val HERO_RING_DIAMETER = 336f
    private const val HERO_CARD_H = 520f
    private const val TONAL_CARD_H = 178f
    private const val SECTION_HEADER_H = 50f
    private const val RIBBON_H = 30f
    private const val APP_ROW_H = 58f
    private const val DAY_NIGHT_BLOCK_H = 74f
    private const val TREND_CHART_H = 220f
    private const val TREND_AXIS_H = 38f
    private const val TREND_LEGEND_H = 40f
    private const val HOUR_CHART_H = 168f
    private const val HOUR_AXIS_H = 34f
    private const val FOOTER_H = 68f

    /** 将 [HtmlReportInput] 转换为纯 Kotlin 视觉排版模型。 */
    fun buildModel(
        input: HtmlReportInput,
        theme: PngReportTheme = PngReportTheme.Default
    ): PngReportModel {
        val s = input.summary
        val appsTotal = input.apps.sumOf { it.minutes }
        val footerText = "屏迹 · v${input.appVersion} · ${input.exportedAt} · 本机离线生成"

        if (!s.hasAnyRecord && appsTotal == 0) {
            return PngReportModel(
                rangeLabel = input.rangeLabel,
                exportedAt = input.exportedAt,
                appVersion = input.appVersion,
                isEmpty = true,
                emptyMessage = "该区间暂无可用记录，可先返回首页拉取数据后再导出。",
                hero = null,
                tonalCards = emptyList(),
                hideAppNames = input.hideAppNames,
                hideAppNamesNotice = null,
                appsTotalText = null,
                appSlices = emptyList(),
                dayPercent = null,
                trendSubtitle = null,
                trendPeakText = null,
                trendBars = emptyList(),
                trendAvgFraction = null,
                trendAvgLabel = null,
                hasUnrecordedTrendDays = false,
                hourPeakText = null,
                hourBars = emptyList(),
                footerText = footerText
            )
        }

        val isSingleDay = s.totalDays <= 1
        val heroRawMin: Int? = if (isSingleDay) s.totalScreenUnlockMin else s.averageScreenUnlockMin
        val heroCaption = if (isSingleDay) "当日亮屏" else "完整日日均"
        val heroValueText = heroRawMin?.let { UsageCalculator.formatDuration(it) } ?: "—"
        val goalMin = input.dailyGoalMin?.takeIf { it > 0 }
        val ringProgress = if (goalMin != null && heroRawMin != null) {
            (heroRawMin.toFloat() / goalMin.toFloat()).coerceIn(0f, 2f)
        } else {
            0f
        }
        val isOverTarget = goalMin != null && heroRawMin != null && heroRawMin > goalMin
        val statusChipText = when {
            isOverTarget -> "超出目标 ${UsageCalculator.formatDuration(heroRawMin!! - goalMin!!)}"
            goalMin != null && heroRawMin != null -> {
                val pct = (heroRawMin * 100 / goalMin).coerceAtLeast(0)
                "目标内 · 达成 $pct%"
            }
            else -> "有记录 ${s.recordedDays} / ${s.totalDays} 天"
        }
        val hero = PngHeroModel(
            caption = heroCaption,
            valueText = heroValueText,
            goalText = goalMin?.let { "目标 ${UsageCalculator.formatDuration(it)}" },
            ringProgress = ringProgress,
            statusChipText = statusChipText,
            isOverTarget = isOverTarget,
            longestSessionText = "最长连续 ${UsageCalculator.formatDuration(s.longestSessionMin)}"
        )

        // Tonal 双卡（复刻「今日」页 ②）
        val leftTonal = PngTonalCard(
            label = "屏幕使用合计",
            value = UsageCalculator.formatDuration(s.totalScreenUnlockMin),
            note = "有记录 ${s.recordedDays} / ${s.totalDays} 天"
        )
        val rightTonal = PngTonalCard(
            label = if (isSingleDay) "解锁次数" else "日均解锁",
            value = if (s.averageUnlocks > 0) "${s.averageUnlocks} 次" else "—",
            note = if (s.averageUnlocks > 0) "已采集解锁事件" else "暂无可用解锁事件"
        )

        // 应用构成（Top 8 + 「其他」归并，复刻「今日」页 ③ 色带 + 精简图例）
        val paletteColors = theme.chartColors.ifEmpty { PngReportTheme.DEFAULT_CHART_COLORS }
        val appSlices = if (input.apps.isNotEmpty() && appsTotal > 0) {
            val top = input.apps.take(MAX_TOP_APPS)
            val restMin = input.apps.drop(MAX_TOP_APPS).sumOf { it.minutes }
            buildList {
                top.forEachIndexed { idx, app ->
                    val pct = (app.minutes * 100 / appsTotal).coerceAtLeast(0)
                    val label = if (input.hideAppNames) "应用 ${idx + 1}" else app.name
                    add(
                        PngAppSlice(
                            displayName = label,
                            durationText = UsageCalculator.formatDuration(app.minutes),
                            percentText = "$pct%",
                            fraction = (app.minutes.toFloat() / appsTotal.toFloat()).coerceIn(0f, 1f),
                            colorArgb = paletteColors[idx % paletteColors.size]
                        )
                    )
                }
                if (restMin > 0) {
                    val pct = (restMin * 100 / appsTotal).coerceAtLeast(0)
                    add(
                        PngAppSlice(
                            displayName = "其他 ${input.apps.size - top.size} 款应用",
                            durationText = UsageCalculator.formatDuration(restMin),
                            percentText = "$pct%",
                            fraction = (restMin.toFloat() / appsTotal.toFloat()).coerceIn(0f, 1f),
                            colorArgb = theme.onSurfaceMuted
                        )
                    )
                }
            }
        } else {
            emptyList()
        }
        val hideNotice = if (input.hideAppNames && appSlices.isNotEmpty()) {
            "已隐藏应用名称"
        } else {
            null
        }
        val appsTotalText = if (appSlices.isNotEmpty()) {
            "共 ${UsageCalculator.formatDuration(appsTotal)}"
        } else {
            null
        }

        // 日间 (06:00–21:59) / 夜间 (22:00–05:59) 占比
        val hoursTotal = input.hours.sumOf { it.minutes }
        val dayPercent: Int? = if (hoursTotal > 0) {
            val dayMin = input.hours.filter { it.hour in 6..21 }.sumOf { it.minutes }
            ((dayMin * 100f) / hoursTotal.toFloat()).roundToInt().coerceIn(0, 100)
        } else {
            null
        }

        // 每日趋势（仅多日区间展示，复刻「统计」页 ① 胶囊柱图 + 虚线日均线）
        val showTrend = input.daily.size > 1
        val visibleDaily: List<DailyPoint> = if (showTrend) {
            if (input.daily.size > MAX_TREND_DAYS) input.daily.takeLast(MAX_TREND_DAYS) else input.daily
        } else {
            emptyList()
        }
        val trendSubtitle = if (showTrend && input.daily.size > MAX_TREND_DAYS) {
            "近 $MAX_TREND_DAYS 天"
        } else {
            null
        }
        val recordedPoints = visibleDaily.filter { it.recorded }
        val peakDailyMin = recordedPoints.maxOfOrNull { it.minutes } ?: 0
        val avgDailyMin = s.averageScreenUnlockMin
        val trendScaleMax = maxOf(peakDailyMin, avgDailyMin ?: 0).coerceAtLeast(1)
        val trendBars = if (showTrend) {
            visibleDaily.map { point ->
                val mins = if (point.recorded) point.minutes else 0
                val frac = if (point.recorded && mins > 0) {
                    (mins.toFloat() / trendScaleMax.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                PngTrendBar(
                    axisLabel = "${point.date.monthValue}/${point.date.dayOfMonth}",
                    minutes = mins,
                    heightFraction = frac,
                    recorded = point.recorded,
                    isPeak = point.recorded && peakDailyMin > 0 && mins == peakDailyMin
                )
            }
        } else {
            emptyList()
        }
        val trendPeakText = if (showTrend && peakDailyMin > 0) {
            "最高 ${UsageCalculator.formatDuration(peakDailyMin)}"
        } else {
            null
        }
        val trendAvgFraction = if (showTrend && avgDailyMin != null && avgDailyMin > 0) {
            (avgDailyMin.toFloat() / trendScaleMax.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }
        val trendAvgLabel = if (showTrend && avgDailyMin != null) {
            "日均 ${UsageCalculator.formatDuration(avgDailyMin)}"
        } else if (showTrend) {
            "暂无完整日日均"
        } else {
            null
        }
        val hasUnrecordedTrendDays = visibleDaily.any { !it.recorded }

        // 24 小时时段胶囊柱图
        val hourBars = if (hoursTotal > 0) {
            val byHour = input.hours.associateBy { it.hour }
            val maxHour = input.hours.maxOfOrNull { it.minutes } ?: 0
            (0..23).map { h ->
                val mins = byHour[h]?.minutes ?: 0
                val frac = if (maxHour > 0) (mins.toFloat() / maxHour.toFloat()).coerceIn(0f, 1f) else 0f
                PngHourBar(
                    hour = h,
                    heightFraction = frac,
                    isPeak = maxHour > 0 && mins == maxHour
                )
            }
        } else {
            emptyList()
        }
        val peakHour = if (hoursTotal > 0) input.hours.maxByOrNull { it.minutes }?.hour else null
        val hourPeakText = peakHour?.let { String.format("高峰 %02d:00", it) }

        return PngReportModel(
            rangeLabel = input.rangeLabel,
            exportedAt = input.exportedAt,
            appVersion = input.appVersion,
            isEmpty = false,
            emptyMessage = null,
            hero = hero,
            tonalCards = listOf(leftTonal, rightTonal),
            hideAppNames = input.hideAppNames,
            hideAppNamesNotice = hideNotice,
            appsTotalText = appsTotalText,
            appSlices = appSlices,
            dayPercent = dayPercent,
            trendSubtitle = trendSubtitle,
            trendPeakText = trendPeakText,
            trendBars = trendBars,
            trendAvgFraction = trendAvgFraction,
            trendAvgLabel = trendAvgLabel,
            hasUnrecordedTrendDays = hasUnrecordedTrendDays,
            hourPeakText = hourPeakText,
            hourBars = hourBars,
            footerText = footerText
        )
    }

    /** 精确计算长图画布总高度（像素）。 */
    fun estimateHeightPx(model: PngReportModel): Int {
        var y = OUTER_PAD + HEADER_H
        if (model.isEmpty) {
            y += CARD_PAD * 2 + 120f + FOOTER_H + OUTER_PAD
            return y.roundToInt()
        }

        // ① Hero 圆环卡
        y += HERO_CARD_H + CARD_GAP

        // ② Tonal 双卡
        y += TONAL_CARD_H + CARD_GAP

        // ③ 应用构成卡
        if (model.appSlices.isNotEmpty()) {
            val appsH = CARD_PAD * 2 + SECTION_HEADER_H + RIBBON_H + 28f +
                model.appSlices.size * APP_ROW_H +
                (if (model.dayPercent != null) DAY_NIGHT_BLOCK_H + 16f else 0f)
            y += appsH + CARD_GAP
        }

        // ④ 每日趋势柱图卡（多日）
        if (model.trendBars.isNotEmpty()) {
            val trendH = CARD_PAD * 2 + SECTION_HEADER_H + TREND_CHART_H + TREND_AXIS_H + TREND_LEGEND_H
            y += trendH + CARD_GAP
        }

        // ⑤ 24 小时时段分布卡
        if (model.hourBars.isNotEmpty()) {
            val hourH = CARD_PAD * 2 + SECTION_HEADER_H + HOUR_CHART_H + HOUR_AXIS_H
            y += hourH + CARD_GAP
        }

        // 页脚
        y += FOOTER_H + OUTER_PAD * 0.5f
        return y.roundToInt()
    }

    /** 按当前 [PngReportTheme] 绘制 Fresh UI 风格长图 [Bitmap]。 */
    fun renderBitmap(
        model: PngReportModel,
        theme: PngReportTheme = PngReportTheme.Default,
        widthPx: Int = DEFAULT_WIDTH_PX
    ): Bitmap {
        val safeWidth = widthPx.coerceAtLeast(640)
        val heightPx = estimateHeightPx(model)
        val bitmap = Bitmap.createBitmap(safeWidth, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(theme.gradientTop)

        // ---- 签名三段渐变背景（0% gradientTop → 18% gradientMid → 40% background）----
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                0f,
                min(heightPx.toFloat(), 1600f),
                intArrayOf(theme.gradientTop, theme.gradientMid, theme.background),
                floatArrayOf(0f, 0.28f, 0.68f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, safeWidth.toFloat(), heightPx.toFloat(), bgPaint)

        val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        val regular = Typeface.SANS_SERIF

        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.onSurface
            textSize = 48f
            typeface = bold
        }
        val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.onSurface
            textSize = 32f
            typeface = bold
        }
        val heroValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.onSurface
            textSize = 54f
            typeface = bold
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.onSurface
            textSize = 27f
            typeface = regular
        }
        val bodyBoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.onSurface
            textSize = 27f
            typeface = bold
        }
        val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.onSurfaceVariant
            textSize = 24f
            typeface = regular
        }
        val smallMutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.onSurfaceMuted
            textSize = 21f
            typeface = regular
        }
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.cardSurface
        }
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.shadow
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.outline
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.track }
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.barSurface }
        val primaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.primary }
        val nightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.nightIcon }

        val contentLeft = OUTER_PAD
        val contentRight = safeWidth - OUTER_PAD
        val contentWidth = contentRight - contentLeft
        val innerLeft = contentLeft + CARD_PAD
        val innerRight = contentRight - CARD_PAD
        val innerWidth = innerRight - innerLeft

        fun drawFreshCard(top: Float, height: Float) {
            val rect = RectF(contentLeft, top, contentRight, top + height)
            if (!theme.darkTheme) {
                val shadowRect = RectF(contentLeft + 2f, top + 8f, contentRight - 2f, top + height + 10f)
                canvas.drawRoundRect(shadowRect, CARD_RADIUS, CARD_RADIUS, shadowPaint)
            }
            canvas.drawRoundRect(rect, CARD_RADIUS, CARD_RADIUS, cardPaint)
            if (theme.darkTheme) {
                canvas.drawRoundRect(rect, CARD_RADIUS, CARD_RADIUS, borderPaint)
            }
        }

        // ---- 顶部轻头部（App 标题 + 日期区间胶囊）----
        var y = OUTER_PAD
        canvas.drawText(model.brandTitle, contentLeft, y + 48f, brandPaint)

        // 右侧日期范围胶囊
        val rangeText = ellipsize(model.rangeLabel, mutedPaint, contentWidth * 0.68f)
        val rangeChipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.chipSurface }
        val rangeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.chipOn
            textSize = 23f
            typeface = bold
        }
        val rangeW = rangeTextPaint.measureText(rangeText) + 38f
        val rangeLeft = contentRight - rangeW
        canvas.drawRoundRect(
            RectF(rangeLeft, y + 6f, contentRight, y + 58f),
            26f,
            26f,
            rangeChipPaint
        )
        canvas.drawText(rangeText, rangeLeft + 19f, y + 40f, rangeTextPaint)
        canvas.drawText("使用时间报告 · ${model.exportedAt}", contentLeft, y + 96f, smallMutedPaint)
        y += HEADER_H

        if (model.isEmpty) {
            val emptyH = CARD_PAD * 2 + 120f
            drawFreshCard(y, emptyH)
            val msg = model.emptyMessage ?: "该区间暂无可用记录。"
            val lines = wrapText(msg, bodyPaint, innerWidth)
            var ly = y + CARD_PAD + 44f
            lines.forEach { line ->
                canvas.drawText(line, innerLeft, ly, bodyPaint)
                ly += 42f
            }
            y += emptyH + CARD_GAP
            drawFooter(canvas, model.footerText, safeWidth / 2f, y + 36f, smallMutedPaint)
            return bitmap
        }

        // ---- ① Hero 圆环卡（复刻「今日」页 ① RingProgress）----
        model.hero?.let { hero ->
            drawFreshCard(y, HERO_CARD_H)
            val cx = safeWidth / 2f
            val ringTop = y + 40f
            val ringR = HERO_RING_DIAMETER / 2f
            val cy = ringTop + ringR
            val ringStroke = 28f
            val arcRect = RectF(cx - ringR, cy - ringR, cx + ringR, cy + ringR)

            // 底环轨道
            val ringTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = theme.track
                style = Paint.Style.STROKE
                strokeWidth = ringStroke
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawArc(arcRect, 0f, 360f, false, ringTrackPaint)

            // 第一圈（0..100% 目标，主色到渐变末端）
            val firstLap = hero.ringProgress.coerceIn(0f, 1f)
            if (firstLap > 0f) {
                val lap1Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = ringStroke
                    strokeCap = Paint.Cap.ROUND
                    shader = LinearGradient(
                        cx - ringR,
                        cy - ringR,
                        cx + ringR,
                        cy + ringR,
                        theme.primary,
                        theme.ringGradientEnd,
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawArc(arcRect, -90f, firstLap * 360f, false, lap1Paint)
            }

            // 第二圈（超目标 >100%，更深主色叠画）
            val secondLap = (hero.ringProgress - 1f).coerceIn(0f, 1f)
            if (secondLap > 0f) {
                val lap2Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = theme.ringOver
                    style = Paint.Style.STROKE
                    strokeWidth = ringStroke
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawArc(arcRect, -90f, secondLap * 360f, false, lap2Paint)
            }

            // 圆环中央文案
            val capW = mutedPaint.measureText(hero.caption)
            canvas.drawText(hero.caption, cx - capW / 2f, cy - 42f, mutedPaint)

            // 根据文案长度微调字号防溢出圆环
            val valPaint = Paint(heroValuePaint).apply {
                if (measureText(hero.valueText) > HERO_RING_DIAMETER - 74f) {
                    textSize = 42f
                }
            }
            val valW = valPaint.measureText(hero.valueText)
            canvas.drawText(hero.valueText, cx - valW / 2f, cy + 20f, valPaint)

            hero.goalText?.let { g ->
                val gw = smallMutedPaint.measureText(g)
                canvas.drawText(g, cx - gw / 2f, cy + 64f, smallMutedPaint)
            }

            // 圆环下方状态胶囊 + 最长连续
            var belowY = ringTop + HERO_RING_DIAMETER + 42f
            hero.statusChipText?.let { chip ->
                val chipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (hero.isOverTarget) theme.overTargetSurface else theme.chipSurface
                }
                val chipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (hero.isOverTarget) theme.overTarget else theme.chipOn
                    textSize = 23f
                    typeface = bold
                }
                val cw = chipTextPaint.measureText(chip) + 40f
                canvas.drawRoundRect(
                    RectF(cx - cw / 2f, belowY - 30f, cx + cw / 2f, belowY + 14f),
                    22f,
                    22f,
                    chipBgPaint
                )
                canvas.drawText(chip, cx - chipTextPaint.measureText(chip) / 2f, belowY, chipTextPaint)
                belowY += 48f
            }
            val lsW = smallMutedPaint.measureText(hero.longestSessionText)
            canvas.drawText(hero.longestSessionText, cx - lsW / 2f, belowY, smallMutedPaint)

            y += HERO_CARD_H + CARD_GAP
        }

        // ---- ② Tonal 双卡（复刻「今日」页 ② MetricCardPair）----
        if (model.tonalCards.size == 2) {
            val pairGap = 22f
            val cardW = (contentWidth - pairGap) / 2f
            val configs = listOf(
                Triple(model.tonalCards[0], theme.primaryContainer, theme.onPrimaryContainer),
                Triple(model.tonalCards[1], theme.secondaryContainer, theme.onSecondaryContainer)
            )
            configs.forEachIndexed { idx, (card, bg, fg) ->
                val left = contentLeft + idx * (cardW + pairGap)
                val tonalBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
                val tonalLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = fg
                    alpha = 205
                    textSize = 23f
                    typeface = regular
                }
                val tonalValPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = fg
                    textSize = 40f
                    typeface = bold
                }
                val tonalNotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = fg
                    alpha = 185
                    textSize = 21f
                    typeface = regular
                }
                canvas.drawRoundRect(
                    RectF(left, y, left + cardW, y + TONAL_CARD_H),
                    TONAL_RADIUS,
                    TONAL_RADIUS,
                    tonalBgPaint
                )
                canvas.drawText(card.label, left + 30f, y + 48f, tonalLabelPaint)
                canvas.drawText(
                    ellipsize(card.value, tonalValPaint, cardW - 60f),
                    left + 30f,
                    y + 108f,
                    tonalValPaint
                )
                canvas.drawText(
                    ellipsize(card.note, tonalNotePaint, cardW - 60f),
                    left + 30f,
                    y + 148f,
                    tonalNotePaint
                )
            }
            y += TONAL_CARD_H + CARD_GAP
        }

        // ---- ③ 应用构成卡（复刻「今日」页 ③ 色带 + Top 8 图例 + 日/夜条）----
        if (model.appSlices.isNotEmpty()) {
            val appsH = CARD_PAD * 2 + SECTION_HEADER_H + RIBBON_H + 28f +
                model.appSlices.size * APP_ROW_H +
                (if (model.dayPercent != null) DAY_NIGHT_BLOCK_H + 16f else 0f)
            drawFreshCard(y, appsH)
            var cy = y + CARD_PAD

            canvas.drawText("应用构成", innerLeft, cy + 32f, sectionPaint)
            model.hideAppNamesNotice?.let { notice ->
                val nw = smallMutedPaint.measureText(notice)
                val badgeLeft = innerLeft + sectionPaint.measureText("应用构成") + 18f
                val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.surfaceVariant }
                canvas.drawRoundRect(
                    RectF(badgeLeft, cy + 4f, badgeLeft + nw + 24f, cy + 38f),
                    17f,
                    17f,
                    badgePaint
                )
                canvas.drawText(notice, badgeLeft + 12f, cy + 28f, smallMutedPaint)
            }
            model.appsTotalText?.let { tot ->
                val tw = mutedPaint.measureText(tot)
                canvas.drawText(tot, innerRight - tw, cy + 30f, mutedPaint)
            }
            cy += SECTION_HEADER_H

            // 分段彩色色带
            val gapPx = 6f
            val totalGap = gapPx * (model.appSlices.size - 1).coerceAtLeast(0)
            val usableW = (innerWidth - totalGap).coerceAtLeast(100f)
            var rx = innerLeft
            val slicePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            model.appSlices.forEachIndexed { idx, slice ->
                val rawW = usableW * slice.fraction
                val segW = if (idx == model.appSlices.lastIndex) {
                    (innerRight - rx).coerceAtLeast(8f)
                } else {
                    rawW.coerceAtLeast(8f)
                }
                slicePaint.color = slice.colorArgb
                canvas.drawRoundRect(
                    RectF(rx, cy, (rx + segW).coerceAtMost(innerRight), cy + RIBBON_H),
                    12f,
                    12f,
                    slicePaint
                )
                rx += segW + gapPx
            }
            cy += RIBBON_H + 28f

            // 行式图例（圆点 + 应用名 + 右对齐时长 & 占比）
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            model.appSlices.forEach { slice ->
                val rowCenterY = cy + APP_ROW_H / 2f
                val textY = rowCenterY + 9f
                dotPaint.color = slice.colorArgb
                canvas.drawCircle(innerLeft + 10f, rowCenterY, 9f, dotPaint)

                val pctW = smallMutedPaint.measureText(slice.percentText)
                val durW = bodyBoldPaint.measureText(slice.durationText)
                val pctX = innerRight - pctW
                val durX = pctX - 18f - durW
                val maxNameW = (durX - (innerLeft + 34f) - 16f).coerceAtLeast(80f)

                canvas.drawText(ellipsize(slice.displayName, bodyPaint, maxNameW), innerLeft + 34f, textY, bodyPaint)
                canvas.drawText(slice.durationText, durX, textY, bodyBoldPaint)
                canvas.drawText(slice.percentText, pctX, textY, smallMutedPaint)
                cy += APP_ROW_H
            }

            // 日间 / 夜间双色占比条
            model.dayPercent?.let { dp ->
                val np = (100 - dp).coerceIn(0, 100)
                cy += 16f
                val labelY = cy + 22f
                canvas.drawText("日间 $dp%", innerLeft, labelY, smallMutedPaint)
                val nightText = "夜间 $np%"
                val nw = smallMutedPaint.measureText(nightText)
                canvas.drawText(nightText, innerRight - nw, labelY, smallMutedPaint)

                val barTop = cy + 38f
                val barBottom = barTop + 16f
                canvas.drawRoundRect(RectF(innerLeft, barTop, innerRight, barBottom), 8f, 8f, trackPaint)
                val splitX = innerLeft + innerWidth * (dp / 100f)
                if (dp > 0) {
                    canvas.drawRoundRect(
                        RectF(innerLeft, barTop, (splitX - 3f).coerceAtLeast(innerLeft + 8f), barBottom),
                        8f,
                        8f,
                        primaryPaint
                    )
                }
                if (np > 0) {
                    canvas.drawRoundRect(
                        RectF((splitX + 3f).coerceAtMost(innerRight - 8f), barTop, innerRight, barBottom),
                        8f,
                        8f,
                        nightPaint
                    )
                }
            }

            y += appsH + CARD_GAP
        }

        // ---- ④ 每日趋势柱图卡（复刻「统计」页 ① CapsuleBarChart + 虚线均值线）----
        if (model.trendBars.isNotEmpty()) {
            val trendH = CARD_PAD * 2 + SECTION_HEADER_H + TREND_CHART_H + TREND_AXIS_H + TREND_LEGEND_H
            drawFreshCard(y, trendH)
            var cy = y + CARD_PAD

            canvas.drawText("每日趋势", innerLeft, cy + 32f, sectionPaint)
            model.trendSubtitle?.let { sub ->
                canvas.drawText(sub, innerLeft + sectionPaint.measureText("每日趋势") + 16f, cy + 30f, smallMutedPaint)
            }
            model.trendPeakText?.let { peak ->
                val pw = mutedPaint.measureText(peak)
                canvas.drawText(peak, innerRight - pw, cy + 30f, mutedPaint)
            }
            cy += SECTION_HEADER_H

            val chartTop = cy + 12f
            val chartBottom = cy + TREND_CHART_H
            val usableH = chartBottom - chartTop
            val count = model.trendBars.size
            val slotW = innerWidth / count.toFloat()
            val barW = (slotW * 0.48f).coerceIn(14f, 44f)

            // 虚线平均线
            model.trendAvgFraction?.let { avgFrac ->
                val avgY = chartBottom - usableH * avgFrac
                val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = theme.primary
                    alpha = 140
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                    pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
                }
                canvas.drawLine(innerLeft, avgY, innerRight, avgY, dashPaint)
            }

            // 柱体与横轴日期
            val labelStep = when {
                count <= 7 -> 1
                count <= 14 -> 2
                else -> 3
            }
            model.trendBars.forEachIndexed { idx, bar ->
                val centerX = innerLeft + (idx + 0.5f) * slotW
                val left = centerX - barW / 2f
                val right = centerX + barW / 2f
                if (bar.recorded && bar.heightFraction > 0f) {
                    val h = (usableH * bar.heightFraction).coerceAtLeast(16f)
                    val p = if (bar.isPeak) primaryPaint else barPaint
                    canvas.drawRoundRect(
                        RectF(left, chartBottom - h, right, chartBottom),
                        barW / 2f,
                        barW / 2f,
                        p
                    )
                } else if (bar.recorded) {
                    // 真实 0 分钟有记录日：贴底极短胶囊
                    canvas.drawRoundRect(
                        RectF(left, chartBottom - 8f, right, chartBottom),
                        4f,
                        4f,
                        barPaint
                    )
                } else {
                    // 无记录日：低对比基线小圆点，绝不画柱
                    canvas.drawCircle(centerX, chartBottom - 5f, 4f, trackPaint)
                }

                if (idx % labelStep == 0 || idx == count - 1) {
                    val lw = smallMutedPaint.measureText(bar.axisLabel)
                    canvas.drawText(bar.axisLabel, centerX - lw / 2f, chartBottom + 30f, smallMutedPaint)
                }
            }

            // 底部图例：虚线日均 + 无记录说明
            val legendY = chartBottom + TREND_AXIS_H + 24f
            var lx = innerLeft
            if (model.trendAvgFraction != null) {
                val legDashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = theme.primary
                    alpha = 160
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                    pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
                }
                canvas.drawLine(lx, legendY - 7f, lx + 28f, legendY - 7f, legDashPaint)
                lx += 38f
            }
            val legendText = buildString {
                model.trendAvgLabel?.let { append(it) }
                if (model.hasUnrecordedTrendDays) {
                    if (isNotEmpty()) append(" · ")
                    append("空点表示无记录日（不计入平均）")
                }
            }
            if (legendText.isNotEmpty()) {
                canvas.drawText(ellipsize(legendText, smallMutedPaint, innerRight - lx), lx, legendY, smallMutedPaint)
            }

            y += trendH + CARD_GAP
        }

        // ---- ⑤ 24 小时时段胶囊柱图卡（复刻「统计」页 / 小组件）----
        if (model.hourBars.isNotEmpty()) {
            val hourH = CARD_PAD * 2 + SECTION_HEADER_H + HOUR_CHART_H + HOUR_AXIS_H
            drawFreshCard(y, hourH)
            var cy = y + CARD_PAD

            canvas.drawText("时段分布", innerLeft, cy + 32f, sectionPaint)
            model.hourPeakText?.let { peak ->
                val pw = mutedPaint.measureText(peak)
                canvas.drawText(peak, innerRight - pw, cy + 30f, mutedPaint)
            }
            cy += SECTION_HEADER_H

            val chartTop = cy + 10f
            val chartBottom = cy + HOUR_CHART_H
            val usableH = chartBottom - chartTop

            // 中位虚线参考线
            val midDash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = theme.track
                style = Paint.Style.STROKE
                strokeWidth = 2f
                pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
            }
            canvas.drawLine(innerLeft, chartTop + usableH * 0.5f, innerRight, chartTop + usableH * 0.5f, midDash)

            val slotW = innerWidth / 24f
            val barW = (slotW * 0.54f).coerceIn(10f, 20f)
            model.hourBars.forEachIndexed { idx, bar ->
                val centerX = innerLeft + (idx + 0.5f) * slotW
                val left = centerX - barW / 2f
                val right = centerX + barW / 2f
                canvas.drawRoundRect(
                    RectF(left, chartTop, right, chartBottom),
                    barW / 2f,
                    barW / 2f,
                    trackPaint
                )
                if (bar.heightFraction > 0f) {
                    val h = (usableH * bar.heightFraction).coerceAtLeast(10f)
                    canvas.drawRoundRect(
                        RectF(left, chartBottom - h, right, chartBottom),
                        barW / 2f,
                        barW / 2f,
                        if (bar.isPeak) primaryPaint else barPaint
                    )
                }
                if (bar.hour % 6 == 0 || bar.hour == 23) {
                    val label = "${bar.hour}"
                    val lw = smallMutedPaint.measureText(label)
                    canvas.drawText(label, centerX - lw / 2f, chartBottom + 28f, smallMutedPaint)
                }
            }

            y += hourH + CARD_GAP
        }

        // ---- 极简页脚 ----
        drawFooter(canvas, model.footerText, safeWidth / 2f, y + 30f, smallMutedPaint)
        return bitmap
    }

    /** 端到端离线编码为标准 PNG 字节流，编码完成后立即回收 [Bitmap] 防内存泄漏。 */
    fun renderPngBytes(
        input: HtmlReportInput,
        theme: PngReportTheme = PngReportTheme.Default,
        widthPx: Int = DEFAULT_WIDTH_PX
    ): ByteArray {
        val model = buildModel(input, theme)
        val bitmap = renderBitmap(model, theme, widthPx)
        return try {
            ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG 编码失败" }
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawFooter(canvas: Canvas, text: String, centerX: Float, baselineY: Float, paint: Paint) {
        val w = paint.measureText(text)
        canvas.drawText(text, centerX - w / 2f, baselineY, paint)
    }

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
