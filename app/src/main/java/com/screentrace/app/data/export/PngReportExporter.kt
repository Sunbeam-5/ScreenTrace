package com.screentrace.app.data.export

import android.graphics.Bitmap
import com.screentrace.app.contract.AppUsage
import com.screentrace.app.contract.CalendarLevel
import com.screentrace.app.contract.DailyPoint
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.HourBucket
import com.screentrace.app.contract.MetricDetail
import com.screentrace.app.contract.Summary
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * 14a 余项（v4）：主流手机分享长图规格常量。
 *
 * 390dp × 3.0 density = 1170px 宽，与主流全面屏手机原生页面长截图比例一致，
 * 不受平板宽屏或超大系统字体缩放影响；高度按勾选模块自然展开，设上限防 OOM。
 */
object PngPosterSpec {
    const val POSTER_WIDTH_DP = 390
    const val POSTER_DENSITY = 3f
    const val POSTER_WIDTH_PX = 1170
    const val MAX_POSTER_HEIGHT_PX = 6400
    const val TOP_WALLPAPER_HEIGHT_DP = 780
    const val ALBUM_SUBDIR = "屏迹"
}

/** 预览页内置时间范围切换（解决单日「今日」下趋势只有 1 天且日均为 — 的问题）。 */
enum class PngExportRange(val label: String) {
    TODAY("今日"),
    LAST_7_DAYS("近7天"),
    LAST_30_DAYS("近30天");

    fun toDateSelection(today: LocalDate): DateSelection = when (this) {
        TODAY -> DateSelection.Day(today)
        LAST_7_DAYS -> DateSelection.Range(today.minusDays(6), today)
        LAST_30_DAYS -> DateSelection.Range(today.minusDays(29), today)
    }

    /** 每个周期的默认推荐模块组合，切周期后也可继续逐项自由勾选。 */
    fun defaultModules(): Set<PngExportModule> = when (this) {
        TODAY -> setOf(
            PngExportModule.HERO_RING,
            PngExportModule.UNLOCK_NOTIFICATION,
            PngExportModule.APP_BREAKDOWN,
            PngExportModule.PERIOD_DISTRIBUTION
        )
        LAST_7_DAYS -> setOf(
            PngExportModule.HERO_RING,
            PngExportModule.UNLOCK_NOTIFICATION,
            PngExportModule.APP_BREAKDOWN,
            PngExportModule.DAILY_TREND,
            PngExportModule.PERIOD_DISTRIBUTION
        )
        LAST_30_DAYS -> setOf(
            PngExportModule.HERO_RING,
            PngExportModule.APP_BREAKDOWN,
            PngExportModule.DAILY_TREND,
            PngExportModule.PERIOD_DISTRIBUTION,
            PngExportModule.MONTH_CALENDAR
        )
    }
}

/** 自定义壁纸在超长图上的铺底模式。 */
enum class PngBackgroundMode(val label: String) {
    TOP_FADE("顶部渐隐"),
    FULL_FILL("整图铺满")
}

/** 用户可在预览页自由开关的 6 大原生模块（即时显隐）。 */
enum class PngExportModule(val label: String) {
    HERO_RING("目标圆环"),
    UNLOCK_NOTIFICATION("解锁通知"),
    APP_BREAKDOWN("应用构成"),
    DAILY_TREND("每日趋势"),
    PERIOD_DISTRIBUTION("时段分布"),
    MONTH_CALENDAR("月度日历")
}

/**
 * 长图渲染与实时预览共用的同源数据快照（直接来自 [com.screentrace.app.contract.UsageRepository]）。
 */
data class PngPosterData(
    val range: PngExportRange,
    val selection: DateSelection,
    val rangeLabel: String,
    val exportedAt: String,
    val appVersion: String,
    val summary: Summary,
    val dailyGoalMin: Int,
    /** 每日趋势点集：选「今日」时自动补取含今日的近 7 天趋势，避免出现单根柱子。 */
    val trendDaily: List<DailyPoint>,
    val trendTitle: String,
    val trendAverageMin: Int?,
    val apps: List<AppUsage>,
    val topAppsPerDay: Map<LocalDate, List<AppUsage>>,
    val hours: List<HourBucket>,
    /** 时段分布四段分钟数：选「今日」时为今日累计，选「近7天/近30天」时为 D-07 合格日日均。 */
    val periods: List<Int>,
    val periodSubtitle: String,
    val unlock: MetricDetail?,
    val notification: MetricDetail?,
    val yearMonth: YearMonth,
    val monthLevels: List<CalendarLevel>,
    val today: LocalDate,
    val hideAppNames: Boolean = false,
    val showWatermark: Boolean = true,
    val backgroundMode: PngBackgroundMode = PngBackgroundMode.TOP_FADE,
    val modules: Set<PngExportModule> = range.defaultModules()
) {
    val hasAnyContent: Boolean
        get() = summary.hasAnyRecord || apps.isNotEmpty() || monthLevels.any { it.recorded }

    /** 日间 (06:00–21:59) 占比；无小时数据时返回 null（绝不伪造 0%）。 */
    val dayPercent: Int?
        get() {
            val total = hours.sumOf { it.minutes }
            if (total <= 0) return null
            val night = hours.filter { it.hour < 6 || it.hour >= 22 }.sumOf { it.minutes }
            return ((total - night) * 100 / total).coerceIn(0, 100)
        }

    val topBreakdownApps: List<AppUsage>
        get() = apps.take(4)

    val otherAppsMinutes: Int
        get() = apps.drop(4).sumOf { it.minutes }
}

/**
 * 14a 余项 PNG 导出纯逻辑工具（文件名、隐私脱敏与位图编码回收）。
 */
object PngReportExporter {

    /** 建议文件名：`screen-time-report-YYYYMMDD-HHmm.png`。 */
    fun fileName(at: LocalDateTime): String =
        "screen-time-report-${at.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.png"

    /**
     * 隐私脱敏：开启 `hideAppNames` 时，把 [PngPosterData.apps] 与 [PngPosterData.topAppsPerDay]
     * 中的应用名替换为「应用 1」「应用 2」，并同步脱敏 `pkg`（`_masked_N_`），避免应用图标泄露真实应用。
     */
    fun sanitize(data: PngPosterData): PngPosterData {
        if (!data.hideAppNames) return data
        val pkgToRank = LinkedHashMap<String, Int>()
        data.apps.forEach { app ->
            if (app.pkg !in pkgToRank) pkgToRank[app.pkg] = pkgToRank.size + 1
        }
        data.topAppsPerDay.values.flatten().forEach { app ->
            if (app.pkg !in pkgToRank) pkgToRank[app.pkg] = pkgToRank.size + 1
        }
        fun mask(app: AppUsage): AppUsage {
            val rank = pkgToRank[app.pkg] ?: 1
            return app.copy(
                pkg = "_masked_${rank}_",
                name = "应用 $rank"
            )
        }
        return data.copy(
            apps = data.apps.map(::mask),
            topAppsPerDay = data.topAppsPerDay.mapValues { (_, list) -> list.map(::mask) }
        )
    }

    /** 将渲染完成的 [Bitmap] 编码为无损 PNG 字节流，并在 `finally` 中立即回收位图。 */
    fun encodeBitmapToPng(bitmap: Bitmap): ByteArray {
        return try {
            ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG 编码失败" }
                out.toByteArray()
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}
