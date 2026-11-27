package com.screentrace.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.MetricDetail
import com.screentrace.app.contract.UsageCalculator
import com.screentrace.app.data.export.PngExportModule
import com.screentrace.app.data.export.PngPosterData
import com.screentrace.app.data.export.PngPosterSpec
import com.screentrace.app.data.export.PngReportExporter
import com.screentrace.app.theme.FreshPalette
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.LocalWallpaper
import com.screentrace.app.theme.WallpaperState
import com.screentrace.app.uikit.AppBreakdownMath
import com.screentrace.app.uikit.AppBreakdownSection
import com.screentrace.app.uikit.CapsuleBarChart
import com.screentrace.app.uikit.ChipKind
import com.screentrace.app.uikit.EmptyState
import com.screentrace.app.uikit.FreshCard
import com.screentrace.app.uikit.LocalForceReduceMotion
import com.screentrace.app.uikit.MetricCardPair
import com.screentrace.app.uikit.MetricValue
import com.screentrace.app.uikit.MetricValueText
import com.screentrace.app.uikit.MonthCalendar
import com.screentrace.app.uikit.MonthCalendarLegend
import com.screentrace.app.uikit.PeriodDistribution
import com.screentrace.app.uikit.RingProgress
import com.screentrace.app.uikit.StatusChip
import com.screentrace.app.uikit.TrendChartMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 14a 余项（v3）：离屏渲染真实 Compose Tab 卡片为高分长截图 PNG。
 *
 * 直接复用「今日 / 统计 / 日历」页的真实 UIKit 组件与当前 [FreshPalette] / [WallpaperState]，
 * 宽度锁定为主流手机分享尺寸（390dp @ 3x = 1170px），通过临时隐形 [ScrollView] 容器让
 * [ComposeView] 自然展开到完整高度，避免被屏幕可视高度截断。
 */
suspend fun capturePosterToPngBytes(
    context: Context,
    palette: FreshPalette,
    wallpaper: WallpaperState?,
    rawData: PngPosterData
): ByteArray? {
    val activity = context.findActivity() ?: return null
    val data = PngReportExporter.sanitize(rawData)
    val bitmap = withContext(Dispatchers.Main) {
        val decor = activity.window.decorView as? ViewGroup ?: return@withContext null
        val scrollContainer = ScrollView(activity).apply {
            alpha = 0f
            translationX = 100_000f
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }
        val composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                CompositionLocalProvider(
                    LocalFreshPalette provides palette,
                    LocalWallpaper provides wallpaper,
                    LocalForceReduceMotion provides true,
                    LocalDensity provides Density(
                        density = PngPosterSpec.POSTER_DENSITY,
                        fontScale = 1f
                    )
                ) {
                    PngPosterContent(data = data)
                }
            }
        }
        scrollContainer.addView(
            composeView,
            FrameLayout.LayoutParams(
                PngPosterSpec.POSTER_WIDTH_PX,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        decor.addView(
            scrollContainer,
            FrameLayout.LayoutParams(
                PngPosterSpec.POSTER_WIDTH_PX,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        try {
            // 等待 3 帧，确保 Compose 首帧组合、LaunchedEffect（如 RingProgress.snapTo）与测量全部落定
            repeat(3) { withFrameNanos { } }
            val w = composeView.width.takeIf { it > 0 } ?: PngPosterSpec.POSTER_WIDTH_PX
            val h = composeView.height.coerceIn(1, PngPosterSpec.MAX_POSTER_HEIGHT_PX)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            composeView.draw(canvas)
            bmp
        } finally {
            decor.removeView(scrollContainer)
        }
    } ?: return null

    return withContext(Dispatchers.Default) {
        PngReportExporter.encodeBitmapToPng(bitmap)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 与 App 真实 Tab 视觉完全一致的可自选模块长图界面（无状态栏、无底栏、无交互按钮）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PngPosterContent(data: PngPosterData) {
    val palette = LocalFreshPalette.current
    val wallpaper = LocalWallpaper.current

    Box(
        modifier = Modifier
            .width(PngPosterSpec.POSTER_WIDTH_DP.dp)
            .wrapContentHeight()
            .then(
                if (wallpaper == null) {
                    Modifier.background(
                        Brush.verticalGradient(
                            0f to palette.gradientTop,
                            0.18f to palette.gradientMid,
                            0.40f to palette.background
                        )
                    )
                } else {
                    Modifier.background(palette.background)
                }
            )
    ) {
        if (wallpaper != null) {
            Image(
                bitmap = wallpaper.blurred,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
            if (wallpaper.dimAlpha > 0f) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(palette.scrim.copy(alpha = wallpaper.dimAlpha))
                )
            }
            Box(
                Modifier
                    .matchParentSize()
                    .background(palette.background.copy(alpha = wallpaper.themeScrimAlpha))
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- 顶部轻头部：品牌名 + 日期区间胶囊 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "屏迹",
                        style = FreshType.pageTitle,
                        color = palette.onSurface
                    )
                    Text(
                        text = data.rangeLabel,
                        style = FreshType.label,
                        color = palette.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (data.hideAppNames) {
                        PosterBadge(text = "已隐应用名")
                    }
                    PosterBadge(text = data.exportedAt)
                }
            }

            if (!data.hasAnyContent) {
                FreshCard {
                    EmptyState(title = "该区间暂无可用记录")
                }
            } else {
                val single = data.selection is DateSelection.Day
                val rawUsageMin: Int? = if (single) {
                    data.summary.totalScreenUnlockMin
                } else {
                    data.summary.averageScreenUnlockMin
                }

                // ① 目标圆环卡（复刻「今日」页 ①）
                if (PngExportModule.HERO_RING in data.modules) {
                    val displayDate = (data.selection as? DateSelection.Day)?.date
                    val caption = when {
                        !single -> "日均亮屏"
                        displayDate == data.today -> "今日亮屏"
                        else -> "${displayDate?.monthValue}月${displayDate?.dayOfMonth}日亮屏"
                    }
                    FreshCard(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (rawUsageMin != null) {
                            RingProgress(
                                progress = if (data.dailyGoalMin > 0) {
                                    rawUsageMin.toFloat() / data.dailyGoalMin
                                } else {
                                    0f
                                },
                                usageMinutes = rawUsageMin,
                                goalMinutes = data.dailyGoalMin,
                                caption = caption
                            )
                            val overMinutes = (rawUsageMin - data.dailyGoalMin).coerceAtLeast(0)
                            if (data.dailyGoalMin > 0 && overMinutes > 0) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    StatusChip(
                                        text = "超出目标 ${UsageCalculator.formatDuration(overMinutes)}",
                                        kind = ChipKind.OVER
                                    )
                                }
                            }
                        } else {
                            // D-07：无完整日时不伪造 0 分钟圆环读数
                            Text(text = caption, style = FreshType.label, color = palette.onSurfaceVariant)
                            Text(text = "—", style = FreshType.pageTitle, color = palette.onSurface)
                            Text(
                                text = "当前区间暂无完整日统计（合计 ${UsageCalculator.formatDuration(data.summary.totalScreenUnlockMin)}）",
                                style = FreshType.label,
                                color = palette.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "最长连续 ${UsageCalculator.formatDuration(data.summary.longestSessionMin)}",
                            style = FreshType.label,
                            color = palette.onSurfaceVariant
                        )
                    }
                }

                // ② 解锁与通知双卡（复刻「今日」页 ②，纯摘要态无箭头）
                if (PngExportModule.UNLOCK_NOTIFICATION in data.modules) {
                    val unlockAvailable = data.unlock?.hasEventSource == true
                    val notifAvailable = data.notification?.hasEventSource == true
                    val unlockValue = posterMetricCardValue(data.unlock, single)
                    val notifValue = posterMetricCardValue(data.notification, single)
                    MetricCardPair(
                        leftLabel = if (single) "解锁次数" else "日均解锁",
                        leftValue = MetricValue.Number(unlockValue),
                        rightLabel = if (single) "通知打扰" else "日均通知打扰",
                        rightValue = MetricValue.Number(notifValue),
                        leftNote = if (unlockAvailable) null else "未采集到解锁事件",
                        rightNote = if (notifAvailable) {
                            data.notification?.notification?.let { "已查看 ${it.seen} 次" }
                        } else {
                            "未采集到通知事件"
                        },
                        leftIcon = Icons.Default.Lock,
                        rightIcon = Icons.Default.Notifications,
                        rolling = false
                    )
                }

                // ③ 应用构成卡（复刻「今日」页 ③ 色带 + 图例 + 日间/夜间条）
                if (PngExportModule.APP_BREAKDOWN in data.modules) {
                    val rows = AppBreakdownMath.rows(data.topBreakdownApps, data.otherAppsMinutes)
                    val totalAppMinutes = data.apps.sumOf { it.minutes }
                    FreshCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "应用构成", style = FreshType.sectionTitle, color = palette.onSurface)
                            Text(
                                text = "共 ${UsageCalculator.formatDuration(totalAppMinutes)}",
                                style = FreshType.bodySmall,
                                color = palette.onSurfaceVariant
                            )
                        }
                        if (rows.isEmpty()) {
                            EmptyState(title = "暂无应用数据")
                        } else {
                            AppBreakdownSection(rows = rows, dayPercent = data.dayPercent)
                        }
                    }
                }

                // ④ 每日趋势卡（复刻「统计」页 ① 胶囊柱图 + 虚线日均线）
                if (PngExportModule.DAILY_TREND in data.modules && data.daily.isNotEmpty()) {
                    FreshCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column {
                            Text(
                                text = "日均亮屏",
                                style = FreshType.label,
                                color = palette.onSurfaceVariant
                            )
                            MetricValueText(
                                value = data.summary.averageScreenUnlockMin?.let { MetricValue.Duration(it) }
                                    ?: MetricValue.Number("—"),
                                numberColor = palette.onSurface,
                                unitColor = palette.onSurfaceMuted,
                                numberFontSize = 36,
                                rolling = false
                            )
                        }
                        CapsuleBarChart(
                            points = data.daily,
                            topAppsPerDay = data.topAppsPerDay
                        )
                        TrendChartMath.averageMinutes(data.daily)?.let { avg ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                ComposeCanvas(modifier = Modifier.width(18.dp).height(2.dp)) {
                                    drawLine(
                                        color = palette.primary.copy(alpha = 0.55f),
                                        start = Offset(0f, size.height / 2f),
                                        end = Offset(size.width, size.height / 2f),
                                        strokeWidth = 1.5.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                                    )
                                }
                                Text(
                                    text = "日均 ${UsageCalculator.formatDuration(avg)}",
                                    style = FreshType.label,
                                    color = palette.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // ⑤ 时段分布卡（复刻「统计」页 ② 36dp 四段条 + 2×2 图例）
                if (PngExportModule.PERIOD_DISTRIBUTION in data.modules) {
                    FreshCard {
                        PeriodDistribution(periods = data.periods)
                    }
                }

                // ⑥ 月度日历卡（复刻「日历」页月视图）
                if (PngExportModule.MONTH_CALENDAR in data.modules) {
                    FreshCard(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${data.yearMonth.year}年${data.yearMonth.monthValue}月",
                                style = FreshType.sectionTitle,
                                color = palette.onSurface
                            )
                            Text(
                                text = "月度使用日历",
                                style = FreshType.bodySmall,
                                color = palette.onSurfaceVariant
                            )
                        }
                        MonthCalendar(
                            yearMonth = data.yearMonth,
                            levels = data.monthLevels,
                            goalMinutes = data.dailyGoalMin,
                            today = data.today
                        )
                        MonthCalendarLegend()
                    }
                }
            }

            // ---- 极简底标 ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "屏迹 · v${data.appVersion} · 本机离线生成",
                    style = FreshType.label,
                    color = palette.onSurfaceMuted
                )
            }
        }
    }
}

@Composable
private fun PosterBadge(text: String) {
    val palette = LocalFreshPalette.current
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(palette.chipSurface)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = FreshType.label,
            color = palette.chipOn
        )
    }
}

private fun posterMetricCardValue(detail: MetricDetail?, single: Boolean): String {
    if (detail == null || !detail.hasEventSource) return "—"
    val value = if (single) detail.total else detail.dailyAverage
    return if (value == null) "—" else "$value 次"
}
