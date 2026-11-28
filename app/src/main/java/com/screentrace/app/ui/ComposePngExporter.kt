package com.screentrace.app.ui

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.MetricDetail
import com.screentrace.app.contract.UsageCalculator
import com.screentrace.app.data.export.PngBackgroundMode
import com.screentrace.app.data.export.PngExportModule
import com.screentrace.app.data.export.PngPosterData
import com.screentrace.app.data.export.PngPosterSpec
import com.screentrace.app.data.export.PngReportExporter
import com.screentrace.app.data.wallpaper.WallpaperCropMath
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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 14a 余项（v4）：离屏渲染真实 Compose Tab 卡片为高分长截图 PNG。
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

/**
 * Android 10+（API 29+）免权限直接写入专属相册 `Pictures/屏迹`；
 * 返回 true 表示已保存，false 表示需在旧系统（API 26–28）回退 SAF 选择位置。
 */
suspend fun savePosterToGallery(
    context: Context,
    fileName: String,
    pngBytes: ByteArray
): android.net.Uri? = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext null
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(
            MediaStore.Images.Media.RELATIVE_PATH,
            "${Environment.DIRECTORY_PICTURES}/${PngPosterSpec.ALBUM_SUBDIR}"
        )
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: return@withContext null
    runCatching {
        resolver.openOutputStream(uri)?.use { out ->
            out.write(pngBytes)
        } ?: error("无法写入相册")
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri
    }.getOrElse {
        runCatching { resolver.delete(uri, null, null) }
        null
    }
}

/** 写入应用私有缓存目录并通过 [FileProvider] 拉起系统分享面板（无需存储权限）。 */
suspend fun sharePosterViaSystemSheet(
    context: Context,
    fileName: String,
    pngBytes: ByteArray
): Boolean {
    val file = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "shared_posters").apply { mkdirs() }
            // 清理旧的临时分享图，避免缓存堆积
            dir.listFiles()?.forEach { old -> runCatching { old.delete() } }
            File(dir, fileName).apply { writeBytes(pngBytes) }
        }.getOrNull()
    } ?: return false

    return withContext(Dispatchers.Main) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "分享使用长图").apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        }.getOrDefault(false)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 与 App 真实 Tab 视觉一致的可自选模块长图内容（实时预览与离屏渲染共用同一份 Composable）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PngPosterContent(
    data: PngPosterData,
    modifier: Modifier = Modifier
) {
    val palette = LocalFreshPalette.current
    val wallpaper = LocalWallpaper.current
    val displayData = PngReportExporter.sanitize(data)

    Box(
        modifier = modifier
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
            when (displayData.backgroundMode) {
                PngBackgroundMode.TOP_FADE -> {
                    // 顶部约 1 屏区域严格按壁纸编辑器裁切构图清晰展示，底部平滑渐隐融入主题底色
                    val bmpW = wallpaper.blurred.width.toFloat()
                    val bmpH = wallpaper.blurred.height.toFloat()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(PngPosterSpec.TOP_WALLPAPER_HEIGHT_DP.dp)
                            .align(Alignment.TopCenter)
                            .clipToBounds()
                    ) {
                        Image(
                            bitmap = wallpaper.blurred,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val geo = WallpaperCropMath.geometry(bmpW, bmpH, size.width, size.height)
                                    val zoom = if (geo != null) {
                                        maxOf(wallpaper.cropScale, geo.minZoom)
                                    } else {
                                        wallpaper.cropScale
                                    }
                                    scaleX = zoom
                                    scaleY = zoom
                                    translationX = wallpaper.cropOffsetX * size.width
                                    translationY = wallpaper.cropOffsetY * size.height
                                }
                        )
                        if (wallpaper.dimAlpha > 0f) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(palette.scrim.copy(alpha = wallpaper.dimAlpha))
                            )
                        }
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(palette.background.copy(alpha = wallpaper.themeScrimAlpha))
                        )
                        // 底部平滑渐隐过渡带，无缝衔接下方主题底色
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0.0f to Color.Transparent,
                                        0.58f to Color.Transparent,
                                        1.0f to palette.background
                                    )
                                )
                        )
                    }
                }
                PngBackgroundMode.FULL_FILL -> {
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
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- 顶部轻头部：品牌名 + 日期区间 ----
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
                        text = displayData.rangeLabel,
                        style = FreshType.label,
                        color = palette.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (displayData.hideAppNames) {
                        PosterBadge(text = "已隐应用名")
                    }
                    PosterBadge(text = displayData.range.label)
                }
            }

            if (!displayData.hasAnyContent) {
                FreshCard {
                    EmptyState(title = "该区间暂无可用记录")
                }
            } else {
                val single = displayData.selection is DateSelection.Day
                val rawUsageMin: Int? = if (single) {
                    displayData.summary.totalScreenUnlockMin
                } else {
                    displayData.summary.averageScreenUnlockMin
                }

                // ① 目标圆环卡（复刻「今日」页 ①）
                if (PngExportModule.HERO_RING in displayData.modules) {
                    val displayDate = (displayData.selection as? DateSelection.Day)?.date
                    val caption = when {
                        !single -> "日均亮屏"
                        displayDate == displayData.today -> "今日亮屏"
                        else -> "${displayDate?.monthValue}月${displayDate?.dayOfMonth}日亮屏"
                    }
                    FreshCard(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (rawUsageMin != null) {
                            RingProgress(
                                progress = if (displayData.dailyGoalMin > 0) {
                                    rawUsageMin.toFloat() / displayData.dailyGoalMin
                                } else {
                                    0f
                                },
                                usageMinutes = rawUsageMin,
                                goalMinutes = displayData.dailyGoalMin,
                                caption = caption
                            )
                            val overMinutes = (rawUsageMin - displayData.dailyGoalMin).coerceAtLeast(0)
                            if (displayData.dailyGoalMin > 0 && overMinutes > 0) {
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
                            Text(text = caption, style = FreshType.label, color = palette.onSurfaceVariant)
                            Text(text = "—", style = FreshType.pageTitle, color = palette.onSurface)
                            Text(
                                text = "当前区间暂无完整日统计（合计 ${UsageCalculator.formatDuration(displayData.summary.totalScreenUnlockMin)}）",
                                style = FreshType.label,
                                color = palette.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "最长连续 ${UsageCalculator.formatDuration(displayData.summary.longestSessionMin)}",
                            style = FreshType.label,
                            color = palette.onSurfaceVariant
                        )
                    }
                }

                // ② 解锁与通知双卡（复刻「今日」页 ②，纯摘要态无箭头）
                if (PngExportModule.UNLOCK_NOTIFICATION in displayData.modules) {
                    val unlockAvailable = displayData.unlock?.hasEventSource == true
                    val notifAvailable = displayData.notification?.hasEventSource == true
                    val unlockValue = posterMetricCardValue(displayData.unlock, single)
                    val notifValue = posterMetricCardValue(displayData.notification, single)
                    MetricCardPair(
                        leftLabel = if (single) "解锁次数" else "日均解锁",
                        leftValue = MetricValue.Number(unlockValue),
                        rightLabel = if (single) "通知打扰" else "日均通知打扰",
                        rightValue = MetricValue.Number(notifValue),
                        leftNote = if (unlockAvailable) null else "未采集到解锁事件",
                        rightNote = if (notifAvailable) {
                            displayData.notification?.notification?.let { "已查看 ${it.seen} 次" }
                        } else {
                            "未采集到通知事件"
                        },
                        leftIcon = Icons.Default.Lock,
                        rightIcon = Icons.Default.Notifications,
                        rolling = false
                    )
                }

                // ③ 应用构成卡（复刻「今日」页 ③ 色带 + 图例 + 日间/夜间条）
                if (PngExportModule.APP_BREAKDOWN in displayData.modules) {
                    val rows = AppBreakdownMath.rows(displayData.topBreakdownApps, displayData.otherAppsMinutes)
                    val totalAppMinutes = displayData.apps.sumOf { it.minutes }
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
                            AppBreakdownSection(rows = rows, dayPercent = displayData.dayPercent)
                        }
                    }
                }

                // ④ 每日趋势卡（复刻「统计」页 ① 胶囊柱图 + 虚线日均线；选「今日」时自动取近 7 天）
                if (PngExportModule.DAILY_TREND in displayData.modules && displayData.trendDaily.isNotEmpty()) {
                    FreshCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column {
                            Text(
                                text = displayData.trendTitle,
                                style = FreshType.label,
                                color = palette.onSurfaceVariant
                            )
                            MetricValueText(
                                value = displayData.trendAverageMin?.let { MetricValue.Duration(it) }
                                    ?: MetricValue.Number("—"),
                                numberColor = palette.onSurface,
                                unitColor = palette.onSurfaceMuted,
                                numberFontSize = 36,
                                rolling = false
                            )
                        }
                        CapsuleBarChart(
                            points = displayData.trendDaily,
                            topAppsPerDay = displayData.topAppsPerDay
                        )
                        TrendChartMath.averageMinutes(displayData.trendDaily)?.let { avg ->
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

                // ⑤ 时段分布卡（复刻「统计」页 ②；选「今日」时标「今日」展示当日累计，不再空态）
                if (PngExportModule.PERIOD_DISTRIBUTION in displayData.modules) {
                    FreshCard {
                        PeriodDistribution(
                            periods = displayData.periods,
                            subtitle = displayData.periodSubtitle
                        )
                    }
                }

                // ⑥ 月度日历卡（复刻「日历」页月视图）
                if (PngExportModule.MONTH_CALENDAR in displayData.modules) {
                    FreshCard(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${displayData.yearMonth.year}年${displayData.yearMonth.monthValue}月",
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
                            yearMonth = displayData.yearMonth,
                            levels = displayData.monthLevels,
                            goalMinutes = displayData.dailyGoalMin,
                            today = displayData.today
                        )
                        MonthCalendarLegend()
                    }
                }
            }

            if (displayData.showWatermark) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "屏迹 · ${displayData.exportedAt}",
                        style = FreshType.label,
                        color = palette.onSurfaceMuted
                    )
                }
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
