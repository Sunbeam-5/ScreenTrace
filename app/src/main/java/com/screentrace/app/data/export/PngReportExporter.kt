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
 * 14a 余项（v3）：主流手机分享长图规格常量。
 *
 * 390dp × 3.0 density = 1170px 宽，与主流全面屏手机原生页面长截图比例一致，
 * 不受平板宽屏或超大系统字体缩放影响；高度按勾选模块自然展开，设上限防 OOM。
 */
object PngPosterSpec {
    const val POSTER_WIDTH_DP = 390
    const val POSTER_DENSITY = 3f
    const val POSTER_WIDTH_PX = 1170
    const val MAX_POSTER_HEIGHT_PX = 6400
}

/**
 * 用户可在导出前自由勾选的长图模块（1:1 复刻「今日 / 统计 / 日历」真实 Compose 卡片）。
 */
enum class PngExportModule(
    val label: String,
    val defaultSelected: Boolean
) {
    HERO_RING("目标圆环", true),
    UNLOCK_NOTIFICATION("解锁与通知", true),
    APP_BREAKDOWN("应用构成", true),
    DAILY_TREND("每日趋势", true),
    PERIOD_DISTRIBUTION("时段分布", true),
    MONTH_CALENDAR("月度日历", false);

    companion object {
        val DEFAULT_SET: Set<PngExportModule> = entries.filter { it.defaultSelected }.toSet()
    }
}

/**
 * 长图渲染所需的完整同源数据快照（直接来自 [com.screentrace.app.contract.UsageRepository]，零二次聚合）。
 */
data class PngPosterData(
    val selection: DateSelection,
    val rangeLabel: String,
    val exportedAt: String,
    val appVersion: String,
    val summary: Summary,
    val dailyGoalMin: Int,
    val daily: List<DailyPoint>,
    val apps: List<AppUsage>,
    val topAppsPerDay: Map<LocalDate, List<AppUsage>>,
    val hours: List<HourBucket>,
    val periods: List<Int>,
    val unlock: MetricDetail?,
    val notification: MetricDetail?,
    val yearMonth: YearMonth,
    val monthLevels: List<CalendarLevel>,
    val today: LocalDate,
    val hideAppNames: Boolean,
    val modules: Set<PngExportModule>
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

    /** 今日页应用构成卡使用的 Top 4 应用与其余合计分钟数。 */
    val topBreakdownApps: List<AppUsage>
        get() = apps.take(4)

    val otherAppsMinutes: Int
        get() = apps.drop(4).sumOf { it.minutes }
}

/**
 * 14a 余项 PNG 导出纯逻辑工具（文件名、隐私脱敏与位图编码回收）。
 */
object PngReportExporter {

    /** SAF 建议文件名：`screen-time-report-YYYYMMDD-HHmm.png`。 */
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
