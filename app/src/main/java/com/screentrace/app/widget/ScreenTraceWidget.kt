package com.screentrace.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.screentrace.app.MainActivity
import com.screentrace.app.ScreenTraceApp
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.MetricKind
import com.screentrace.app.theme.ThemeVariant
import kotlinx.coroutines.flow.first

private data class WidgetUiState(
    val snapshot: WidgetSnapshot,
    val colors: WidgetColors
)

/**
 * §5.12 屏迹桌面小组件（纯离线只读 Room 最近一次拉取结果，不触发拉取、不发起网络、绝不展示应用名）。
 */
class ScreenTraceWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(130.dp, 110.dp),
            DpSize(250.dp, 110.dp),
            DpSize(250.dp, 220.dp)
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = loadWidgetState(context)
        provideContent {
            WidgetContent(
                snapshot = state.snapshot,
                colors = state.colors
            )
        }
    }

    private suspend fun loadWidgetState(context: Context): WidgetUiState {
        val app = context.applicationContext as? ScreenTraceApp
            ?: return WidgetUiState(emptySnapshot(), defaultColors())
        val colors = runCatching {
            val s = app.container.settings
            val variant = s.themeVariant.first()
            val dark = s.darkTheme.first()
            val dynamic = s.dynamicColor.first()
            val customSeed = s.customThemeSeed.first()
            val wallpaper = s.wallpaper.first()
            val dynamicSeedArgb = if (dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val resId = if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600
                runCatching { context.getColor(resId) }.getOrNull()
            } else {
                null
            }
            resolveWidgetPalette(
                variant = variant,
                darkTheme = dark,
                customThemeSeed = customSeed,
                dynamicSeedArgb = dynamicSeedArgb,
                wallpaperConfig = wallpaper
            )
        }.getOrElse { defaultColors() }

        val snapshot = runCatching {
            val today = DateSelection.today()
            val repo = app.container.repository
            val summary = repo.summary(today)
            val unlock = repo.metricDetail(MetricKind.UNLOCK, today)
            val notification = repo.metricDetail(MetricKind.NOTIFICATION, today)
            val hourly = repo.hourlyDistribution(today)
            val goalMin = app.container.settings.dailyGoalMin.first()
            buildWidgetSnapshot(
                summary = summary,
                unlockDetail = unlock,
                notificationDetail = notification,
                dailyGoalMin = goalMin,
                hourlyBuckets = hourly
            )
        }.getOrElse { emptySnapshot() }

        return WidgetUiState(snapshot = snapshot, colors = colors)
    }

    private fun defaultColors(): WidgetColors =
        resolveWidgetPalette(variant = ThemeVariant.MINT, darkTheme = false)

    private fun emptySnapshot(): WidgetSnapshot = WidgetSnapshot(
        hasData = false,
        usageText = "—",
        goalProgress = 0f,
        goalCaption = "打开 App 拉取数据",
        overTarget = false,
        unlockText = "—",
        notificationText = "—"
    )
}

@Composable
private fun WidgetContent(
    snapshot: WidgetSnapshot,
    colors: WidgetColors
) {
    val size = LocalSize.current
    val layoutSize = resolveWidgetLayoutSize(size.width.value, size.height.value)
    val density = LocalContext.current.resources.displayMetrics.density

    val surfaceColor = ColorProvider(Color(colors.cardSurface))
    val tonalColor = ColorProvider(Color(colors.tonalCardSurface))
    val onSurface = ColorProvider(Color(colors.onSurface))
    val onVariant = ColorProvider(Color(colors.onSurfaceVariant))
    val accent = ColorProvider(Color(colors.primary))
    val track = ColorProvider(Color(colors.progressTrack))
    val over = ColorProvider(Color(colors.overTarget))

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(surfaceColor)
            .cornerRadius(20.dp)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>())
    ) {
        if (!snapshot.hasData) {
            HeaderLine(onVariant = onVariant, accent = accent)
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = snapshot.usageText,
                    style = TextStyle(color = onSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = GlanceModifier.height(4.dp))
                Text(
                    text = snapshot.goalCaption,
                    style = TextStyle(color = onVariant, fontSize = 11.sp)
                )
            }
            return@Column
        }

        when (layoutSize) {
            WidgetLayoutSize.COMPACT_2X2 -> {
                val chartBmp = HourlyChartRenderer.renderHourlyChartBitmap(
                    model = snapshot.chartModel,
                    colors = colors,
                    widthPx = (size.width.value * density).toInt().coerceAtLeast(220),
                    heightPx = (58f * density).toInt().coerceAtLeast(96),
                    density = density
                )
                HeaderLine(onVariant = onVariant, accent = accent)
                Spacer(modifier = GlanceModifier.height(4.dp))
                Image(
                    provider = ImageProvider(chartBmp),
                    contentDescription = "24小时使用分布",
                    contentScale = ContentScale.FillBounds,
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight()
                )
                Spacer(modifier = GlanceModifier.height(4.dp))
                UsageGoalFooter(
                    snapshot = snapshot,
                    onSurface = onSurface,
                    onVariant = onVariant,
                    accent = accent,
                    track = track,
                    over = over
                )
            }

            WidgetLayoutSize.WIDE_4X2 -> {
                val chartBmp = HourlyChartRenderer.renderHourlyChartBitmap(
                    model = snapshot.chartModel,
                    colors = colors,
                    widthPx = (size.width.value * 0.54f * density).toInt().coerceAtLeast(240),
                    heightPx = (56f * density).toInt().coerceAtLeast(96),
                    density = density
                )
                val dayNightBmp = HourlyChartRenderer.renderDayNightBarBitmap(
                    dayPercent = snapshot.dayPercent,
                    colors = colors,
                    widthPx = (104f * density).toInt().coerceAtLeast(140),
                    heightPx = (6f * density).toInt().coerceAtLeast(10)
                )
                Row(
                    modifier = GlanceModifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = GlanceModifier.defaultWeight().fillMaxHeight().padding(end = 8.dp)
                    ) {
                        HeaderLine(onVariant = onVariant, accent = accent)
                        Spacer(modifier = GlanceModifier.height(4.dp))
                        Image(
                            provider = ImageProvider(chartBmp),
                            contentDescription = "24小时使用分布",
                            contentScale = ContentScale.FillBounds,
                            modifier = GlanceModifier.fillMaxWidth().defaultWeight()
                        )
                        Spacer(modifier = GlanceModifier.height(4.dp))
                        UsageGoalFooter(
                            snapshot = snapshot,
                            onSurface = onSurface,
                            onVariant = onVariant,
                            accent = accent,
                            track = track,
                            over = over
                        )
                    }
                    Column(
                        modifier = GlanceModifier.width(114.dp).fillMaxHeight()
                    ) {
                        MetricTonalCard(
                            label = "解锁次数",
                            value = snapshot.unlockText,
                            tonalColor = tonalColor,
                            onSurface = onSurface,
                            onVariant = onVariant,
                            modifier = GlanceModifier.fillMaxWidth().defaultWeight()
                        )
                        Spacer(modifier = GlanceModifier.height(4.dp))
                        MetricTonalCard(
                            label = "通知打扰",
                            value = snapshot.notificationText,
                            tonalColor = tonalColor,
                            onSurface = onSurface,
                            onVariant = onVariant,
                            modifier = GlanceModifier.fillMaxWidth().defaultWeight()
                        )
                        Spacer(modifier = GlanceModifier.height(4.dp))
                        DayNightTonalCard(
                            caption = snapshot.dayNightCaption,
                            barBitmap = dayNightBmp,
                            tonalColor = tonalColor,
                            onVariant = onVariant,
                            modifier = GlanceModifier.fillMaxWidth().defaultWeight()
                        )
                    }
                }
            }

            WidgetLayoutSize.LARGE_4X4 -> {
                val chartBmp = HourlyChartRenderer.renderHourlyChartBitmap(
                    model = snapshot.chartModel,
                    colors = colors,
                    widthPx = (size.width.value * density).toInt().coerceAtLeast(360),
                    heightPx = (108f * density).toInt().coerceAtLeast(180),
                    density = density
                )
                val dayNightBmp = HourlyChartRenderer.renderDayNightBarBitmap(
                    dayPercent = snapshot.dayPercent,
                    colors = colors,
                    widthPx = (104f * density).toInt().coerceAtLeast(140),
                    heightPx = (6f * density).toInt().coerceAtLeast(10)
                )
                HeaderLine(onVariant = onVariant, accent = accent)
                Spacer(modifier = GlanceModifier.height(6.dp))
                Image(
                    provider = ImageProvider(chartBmp),
                    contentDescription = "24小时使用分布",
                    contentScale = ContentScale.FillBounds,
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight()
                )
                Spacer(modifier = GlanceModifier.height(8.dp))
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MetricTonalCard(
                        label = "解锁次数",
                        value = snapshot.unlockText,
                        tonalColor = tonalColor,
                        onSurface = onSurface,
                        onVariant = onVariant,
                        modifier = GlanceModifier.defaultWeight().height(54.dp)
                    )
                    Spacer(modifier = GlanceModifier.width(6.dp))
                    MetricTonalCard(
                        label = "通知打扰",
                        value = snapshot.notificationText,
                        tonalColor = tonalColor,
                        onSurface = onSurface,
                        onVariant = onVariant,
                        modifier = GlanceModifier.defaultWeight().height(54.dp)
                    )
                    Spacer(modifier = GlanceModifier.width(6.dp))
                    DayNightTonalCard(
                        caption = snapshot.dayNightCaption,
                        barBitmap = dayNightBmp,
                        tonalColor = tonalColor,
                        onVariant = onVariant,
                        modifier = GlanceModifier.defaultWeight().height(54.dp)
                    )
                }
                Spacer(modifier = GlanceModifier.height(8.dp))
                UsageGoalFooter(
                    snapshot = snapshot,
                    onSurface = onSurface,
                    onVariant = onVariant,
                    accent = accent,
                    track = track,
                    over = over
                )
            }
        }
    }
}

@Composable
private fun HeaderLine(
    onVariant: ColorProvider,
    accent: ColorProvider
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            modifier = GlanceModifier
                .width(6.dp)
                .height(6.dp)
                .background(accent)
                .cornerRadius(3.dp)
        )
        Spacer(modifier = GlanceModifier.width(6.dp))
        Text(
            text = "屏迹 · 今日",
            style = TextStyle(color = onVariant, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        )
    }
}

@Composable
private fun UsageGoalFooter(
    snapshot: WidgetSnapshot,
    onSurface: ColorProvider,
    onVariant: ColorProvider,
    accent: ColorProvider,
    track: ColorProvider,
    over: ColorProvider
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            text = snapshot.usageText,
            style = TextStyle(color = onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        )
        Spacer(modifier = GlanceModifier.width(6.dp))
        Text(
            text = snapshot.goalCaption,
            style = TextStyle(
                color = if (snapshot.overTarget) over else onVariant,
                fontSize = 10.sp
            )
        )
    }
    Spacer(modifier = GlanceModifier.height(4.dp))
    LinearProgressIndicator(
        progress = snapshot.goalProgress,
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(5.dp)
            .cornerRadius(2.5.dp),
        color = if (snapshot.overTarget) over else accent,
        backgroundColor = track
    )
}

@Composable
private fun MetricTonalCard(
    label: String,
    value: String,
    tonalColor: ColorProvider,
    onSurface: ColorProvider,
    onVariant: ColorProvider,
    modifier: GlanceModifier = GlanceModifier
) {
    Column(
        modifier = modifier
            .background(tonalColor)
            .cornerRadius(12.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = TextStyle(color = onVariant, fontSize = 10.sp)
        )
        Text(
            text = value,
            style = TextStyle(color = onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        )
    }
}

@Composable
private fun DayNightTonalCard(
    caption: String,
    barBitmap: Bitmap,
    tonalColor: ColorProvider,
    onVariant: ColorProvider,
    modifier: GlanceModifier = GlanceModifier
) {
    Column(
        modifier = modifier
            .background(tonalColor)
            .cornerRadius(12.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            provider = ImageProvider(barBitmap),
            contentDescription = caption,
            contentScale = ContentScale.FillBounds,
            modifier = GlanceModifier.fillMaxWidth().height(6.dp)
        )
        Spacer(modifier = GlanceModifier.height(3.dp))
        Text(
            text = caption,
            style = TextStyle(color = onVariant, fontSize = 10.sp)
        )
    }
}
