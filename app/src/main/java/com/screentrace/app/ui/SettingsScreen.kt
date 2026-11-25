package com.screentrace.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.screentrace.app.contract.DEFAULT_LOW_THRESHOLD_MIN
import com.screentrace.app.data.prefs.LowUsageThreshold
import com.screentrace.app.data.prefs.PullFrequency
import com.screentrace.app.data.pull.HistoryCoverage
import com.screentrace.app.data.pull.PullChannel
import com.screentrace.app.data.pull.ProviderStatus
import com.screentrace.app.data.pull.ShizukuAuth
import com.screentrace.app.data.wallpaper.WallpaperColorScope
import com.screentrace.app.data.wallpaper.WallpaperConfig
import com.screentrace.app.data.wallpaper.WallpaperTuning
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ThemeVariant
import com.screentrace.app.uikit.NumberWheelSheet
import com.screentrace.app.uikit.PrimaryButton
import com.screentrace.app.uikit.SecondaryButton
import com.screentrace.app.uikit.SegmentedControl
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 每日目标滑杆的小时范围（1–16 小时，1 小时一档）。 */
private val GOAL_HOURS = 1f..16f

private val PULL_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")

/**
 * 设置页（§5.8）：七组卡片，全部接真实状态。
 *
 * 原则（§6 红线）：**没有实现的功能不画可点击控件**。所以「备份与同步」（批 8）、
 * 「提醒与限额」（批 11）、「AI 分析」（14b）三组在这里是灰显说明而不是开关——
 * 让用户点到一个什么都不发生的开关，比没有这个开关更糟。
 */
@Composable
fun SettingsScreen(
    dailyGoalMin: Int,
    onDailyGoalChange: (Int) -> Unit,
    pullFrequency: PullFrequency,
    onPullFrequencyChange: (PullFrequency) -> Unit,
    lastPulledAt: Long?,
    pulling: Boolean,
    onPullNow: () -> Unit,
    channelStatuses: List<Pair<PullChannel, ProviderStatus>>,
    onRefreshStatuses: () -> Unit,
    coverage: HistoryCoverage?,
    onOpenAppCategories: () -> Unit,
    themeVariant: ThemeVariant,
    onOpenStyleSheet: () -> Unit,
    /** §5.4 CR-04：自定义主题入口（种子色取色器）。 */
    customThemeEnabled: Boolean,
    onOpenCustomTheme: () -> Unit,
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    /** §5.4 批 E：自定义壁纸（全部给默认值，Preview / 单测零改动）。 */
    /** 当前壁纸位图（预览缩略图用；null = 未设置）。 */
    wallpaperBitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
    wallpaperConfig: WallpaperConfig = WallpaperConfig(),
    wallpaperMessage: String? = null,
    blurSupported: Boolean = false,
    onWallpaperPicked: (Uri?) -> Unit = {},
    onWallpaperDimChange: suspend (Int) -> Unit = {},
    onWallpaperBlurChange: suspend (Int) -> Unit = {},
    /** §5.4 CR-20 ②：壁纸配色范围两套方案（仅卡片 / 整套主题）。 */
    onWallpaperColorScopeChange: suspend (WallpaperColorScope) -> Unit = {},
    onClearWallpaper: () -> Unit = {},
    onConsumeWallpaperMessage: () -> Unit = {},
    onOpenWallpaperEditor: () -> Unit = {},
    lowThresholdMin: Int = DEFAULT_LOW_THRESHOLD_MIN,
    onLowThresholdChange: (Int) -> Unit = {},
    appVersion: String,
    /** 生成报告：入参 = 是否隐藏应用名称，返回（建议文件名, HTML 全文）。 */
    buildReport: suspend (Boolean) -> Pair<String, String>?,
    /** 批 8：备份导出/恢复（§5.10）。 */
    onBuildBackupJson: suspend () -> Pair<String, String>? = { null },
    /** CR-28 三层兼容：导出含多来源分量时的旧版本兼容警告。 */
    onBackupCompatWarning: (suspend () -> String?)? = null,
    onBuildCsv: suspend () -> List<Pair<String, String>> = { emptyList() },
    onDecodeBackup: suspend (String) -> com.screentrace.app.data.backup.BackupCodec.DecodeResult =
        { com.screentrace.app.data.backup.BackupCodec.decode(it) },
    onRestoreBackup: suspend (
        com.screentrace.app.data.backup.BackupFile,
        Boolean,
        Boolean
    ) -> com.screentrace.app.data.backup.BackupRepository.RestoreResult? = { _, _, _ -> null },
    /** 批 8b：云端同步入口（真机反馈 #3：配置挪二级页，本页一行入口）。 */
    cloudSummary: String = "",
    onOpenCloudSync: () -> Unit = {},
    onNotice: (String) -> Unit = {},
    /** CR-30 §7-64：设置分类首页 + 三级子页（null = 分类首页）。 */
    selectedCategory: SettingsCategory? = null,
    onSelectCategory: (SettingsCategory) -> Unit = {},
    /** CR-30 §7-30：周期总结提醒（每周一 / 每月 1 日，默认关闭）。 */
    weeklyReminderEnabled: Boolean = false,
    onWeeklyReminderChange: (Boolean) -> Unit = {},
    monthlyReminderEnabled: Boolean = false,
    onMonthlyReminderChange: (Boolean) -> Unit = {},
    reminderHour: Int = 20,
    /** CR-30 阶段 E（§7-67）：「关于与诊断」所需的八态结论与诊断快照（默认 null 兼容既有调用）。 */
    collectionVerdict: com.screentrace.app.data.pull.DataCollectionVerdict? = null,
    diagnosticSnapshot: com.screentrace.app.data.pull.PullDiagnosticSnapshot? = null
) {
    if (selectedCategory == null) {
        SettingsCategoryHome(
            dailyGoalMin = dailyGoalMin,
            pullFrequency = pullFrequency,
            cloudSummary = cloudSummary,
            weeklyReminderEnabled = weeklyReminderEnabled,
            monthlyReminderEnabled = monthlyReminderEnabled,
            reminderHour = reminderHour,
            themeVariant = themeVariant,
            customThemeEnabled = customThemeEnabled,
            dynamicColor = dynamicColor,
            wallpaperConfig = wallpaperConfig,
            darkTheme = darkTheme,
            appVersion = appVersion,
            onSelectCategory = onSelectCategory
        )
        return
    }

    when (selectedCategory) {
        SettingsCategory.DATA -> {
            GoalGroup(
                dailyGoalMin = dailyGoalMin,
                onDailyGoalChange = onDailyGoalChange,
                pullFrequency = pullFrequency,
                onPullFrequencyChange = onPullFrequencyChange,
                lastPulledAt = lastPulledAt,
                pulling = pulling,
                onPullNow = onPullNow
            )
            PermissionGroup(
                channelStatuses = channelStatuses,
                onRefreshStatuses = onRefreshStatuses,
                coverage = coverage,
                onOpenAppCategories = onOpenAppCategories,
                appVersion = appVersion,
                buildReport = buildReport,
                onNotice = onNotice
            )
        }

        SettingsCategory.BACKUP -> {
            BackupGroup(
                appVersion = appVersion,
                onBuildJson = onBuildBackupJson,
                onBuildCsv = onBuildCsv,
                onExportWarning = onBackupCompatWarning,
                onDecode = onDecodeBackup,
                onRestore = onRestoreBackup,
                cloudSummary = cloudSummary,
                onOpenCloudSync = onOpenCloudSync,
                onNotice = onNotice
            )
        }

        SettingsCategory.REMINDERS -> {
            ReminderGroup(
                weeklyEnabled = weeklyReminderEnabled,
                onWeeklyChange = onWeeklyReminderChange,
                monthlyEnabled = monthlyReminderEnabled,
                onMonthlyChange = onMonthlyReminderChange,
                reminderHour = reminderHour,
                onNotice = onNotice
            )
        }

        SettingsCategory.APPEARANCE -> {
            ThresholdGroup(
                lowThresholdMin = lowThresholdMin,
                onLowThresholdChange = onLowThresholdChange
            )
            AppearanceGroup(
                themeVariant = themeVariant,
                onOpenStyleSheet = onOpenStyleSheet,
                customThemeEnabled = customThemeEnabled,
                onOpenCustomTheme = onOpenCustomTheme,
                darkTheme = darkTheme,
                onDarkThemeChange = onDarkThemeChange,
                dynamicColor = dynamicColor,
                onDynamicColorChange = onDynamicColorChange,
                wallpaperConfig = wallpaperConfig,
                wallpaperMessage = wallpaperMessage,
                blurSupported = blurSupported,
                onWallpaperPicked = onWallpaperPicked,
                onWallpaperDimChange = onWallpaperDimChange,
                onWallpaperBlurChange = onWallpaperBlurChange,
                onWallpaperColorScopeChange = onWallpaperColorScopeChange,
                onClearWallpaper = onClearWallpaper,
                onConsumeWallpaperMessage = onConsumeWallpaperMessage,
                wallpaperBitmap = wallpaperBitmap,
                onOpenWallpaperEditor = onOpenWallpaperEditor
            )
        }

        SettingsCategory.ABOUT -> {
            CollectionDiagnosticsGroup(
                verdict = collectionVerdict,
                snapshot = diagnosticSnapshot,
                channelStatuses = channelStatuses,
                coverage = coverage,
                lastPulledAt = lastPulledAt,
                pulling = pulling,
                onPullNow = onPullNow,
                onRefreshStatuses = onRefreshStatuses,
                onNotice = onNotice
            )
            AboutGroup(coverage = coverage, appVersion = appVersion)
            SettingsGroup("AI 分析") {
                val palette = LocalFreshPalette.current
                Text(text = "默认关闭 · 当前无联网分析", style = FreshType.body, color = palette.onSurfaceVariant)
                Text(
                    text = "后续版本若启用需由你自行配置模型端点与 API Key，且仅发送聚合统计。",
                    style = FreshType.label,
                    color = palette.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsCategoryHome(
    dailyGoalMin: Int,
    pullFrequency: PullFrequency,
    cloudSummary: String,
    weeklyReminderEnabled: Boolean,
    monthlyReminderEnabled: Boolean,
    reminderHour: Int,
    themeVariant: ThemeVariant,
    customThemeEnabled: Boolean,
    dynamicColor: Boolean,
    wallpaperConfig: WallpaperConfig,
    darkTheme: Boolean,
    appVersion: String,
    onSelectCategory: (SettingsCategory) -> Unit
) {
    val palette = LocalFreshPalette.current
    val reminderSummary = when {
        weeklyReminderEnabled && monthlyReminderEnabled -> "周/月均已开启 · $reminderHour:00"
        weeklyReminderEnabled -> "每周一 $reminderHour:00"
        monthlyReminderEnabled -> "每月 1 日 $reminderHour:00"
        else -> "全部关闭"
    }
    val themeSource = com.screentrace.app.theme.WallpaperPalette.resolveThemeSource(
        wallpaperEnabled = wallpaperConfig.enabled,
        wallpaperScope = wallpaperConfig.colorScope,
        customSeedActive = customThemeEnabled,
        dynamicColorActive = dynamicColor && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    )
    val themeLabel = when (themeSource) {
        com.screentrace.app.theme.ThemeColorSource.WALLPAPER_FULL_THEME -> "壁纸驱动"
        com.screentrace.app.theme.ThemeColorSource.CUSTOM_SEED -> "自定义"
        com.screentrace.app.theme.ThemeColorSource.DYNAMIC_COLOR -> "动态取色"
        com.screentrace.app.theme.ThemeColorSource.PRESET -> themeVariant.label
    }
    val summaries = mapOf(
        SettingsCategory.DATA to "目标 ${dailyGoalMin / 60}h · ${pullFrequency.label}",
        SettingsCategory.BACKUP to cloudSummary.ifEmpty { "本地导出 / 恢复" },
        SettingsCategory.REMINDERS to reminderSummary,
        SettingsCategory.APPEARANCE to "$themeLabel · ${if (darkTheme) "深色" else "浅色"}",
        SettingsCategory.ABOUT to "v$appVersion"
    )
    SettingsGroup("设置分类") {
        SettingsCategory.entries.forEach { cat ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .semantics {
                        role = Role.Button
                        contentDescription = "${cat.title}，${summaries[cat]}"
                    }
                    .clickable { onSelectCategory(cat) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = cat.title, style = FreshType.body, color = palette.onSurface)
                    Text(text = cat.subtitle, style = FreshType.label, color = palette.onSurfaceVariant)
                }
                Text(
                    text = "${summaries[cat]} ›",
                    style = FreshType.label,
                    color = palette.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 目标与同步

@Composable
private fun GoalGroup(
    dailyGoalMin: Int,
    onDailyGoalChange: (Int) -> Unit,
    pullFrequency: PullFrequency,
    onPullFrequencyChange: (PullFrequency) -> Unit,
    lastPulledAt: Long?,
    pulling: Boolean,
    onPullNow: () -> Unit
) {
    val palette = LocalFreshPalette.current
    SettingsGroup("目标与同步") {
        // CR-05 连带项（负责人确认统一）：每日目标与统计口径阈值共用同一套
        // 「一行状态 + 滚轮弹层」交互——此前「滑杆 + 数字输入框」与滚轮在同一屏
        // 并存，两套交互观感不统一。
        var showGoalWheel by remember { mutableStateOf(false) }
        val goalHours = (dailyGoalMin / 60).coerceIn(
            GOAL_HOURS.start.roundToInt(),
            GOAL_HOURS.endInclusive.roundToInt()
        )
        ActionRow(
            label = "每日目标",
            value = "$goalHours 小时",
            onClick = { showGoalWheel = true }
        )
        Text(
            text = "今日页圆环按这个目标算进度；到达目标只做提示，不会锁机。",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
        if (showGoalWheel) {
            NumberWheelSheet(
                title = "每日目标",
                range = GOAL_HOURS.start.roundToInt()..GOAL_HOURS.endInclusive.roundToInt(),
                value = goalHours,
                unit = "小时",
                hint = "到达目标只做提示，不会锁机",
                onConfirm = { onDailyGoalChange(it * 60) },
                onDismiss = { showGoalWheel = false }
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.track)
        )

        Text(text = "后台拉取频率", style = FreshType.body, color = palette.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PullFrequency.entries.forEach { frequency ->
                ChoiceChip(
                    label = frequency.label,
                    selected = frequency == pullFrequency,
                    onClick = { onPullFrequencyChange(frequency) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Text(
            text = "系统只保留约 9–10 天的使用记录，拉取越勤越不容易丢历史；默认每天一次。",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )

        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.track)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(text = "最近成功拉取", style = FreshType.body, color = palette.onSurface)
                Text(
                    text = pullTimeText(lastPulledAt),
                    style = FreshType.label,
                    color = palette.onSurfaceVariant
                )
            }
            SecondaryButton(
                text = if (pulling) "正在拉取…" else "立即拉取",
                onClick = onPullNow,
                enabled = !pulling
            )
        }
    }
}

/** 最近一次成功拉取：今天/昨天用相对说法，更早给具体日期。 */
private fun pullTimeText(atMs: Long?): String {
    if (atMs == null || atMs <= 0L) return "还没有成功拉取过"
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDateTime()
    val date = time.toLocalDate()
    val today = LocalDate.now()
    return when {
        date == today -> "今天 ${time.format(DateTimeFormatter.ofPattern("HH:mm"))}"
        date == today.minusDays(1) -> "昨天 ${time.format(DateTimeFormatter.ofPattern("HH:mm"))}"
        else -> time.format(PULL_TIME_FORMAT)
    }
}

// ---------------------------------------------------------------- 权限与数据

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionGroup(
    channelStatuses: List<Pair<PullChannel, ProviderStatus>>,
    onRefreshStatuses: () -> Unit,
    coverage: HistoryCoverage?,
    onOpenAppCategories: () -> Unit,
    appVersion: String,
    buildReport: suspend (Boolean) -> Pair<String, String>?,
    onNotice: (String) -> Unit
) {
    val palette = LocalFreshPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val shizuku = channelStatuses.firstOrNull { it.first == PullChannel.SHIZUKU }?.second
    val usageStats = channelStatuses.firstOrNull { it.first == PullChannel.USAGE_STATS }?.second

    // 导出：先备好内容，再由系统文件选择器决定落盘位置（SAF，不需要存储权限）
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showExportSheet by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/html")
    ) { uri ->
        val payload = pending
        pending = null
        if (uri == null || payload == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(payload.second.toByteArray(Charsets.UTF_8))
            } ?: error("无法写入所选位置")
        }.isSuccess
        onNotice(if (ok) "已导出 ${payload.first}" else "导出失败，请换个位置再试")
    }

    SettingsGroup("权限与数据") {
        Text(text = "Shizuku（主通道）", style = FreshType.body, color = palette.onSurface)
        StatusLine(
            text = when (shizuku) {
                ProviderStatus.AVAILABLE -> "已授权 · 可读取解锁与通知事件"
                ProviderStatus.NOT_INSTALLED -> "未检测到 Shizuku 服务"
                ProviderStatus.PERMISSION_DENIED -> "已安装 · 尚未授权"
                ProviderStatus.TEMPORARILY_UNAVAILABLE, null -> "暂时不可用，可稍后重试"
                ProviderStatus.USAGE_ACCESS_DENIED -> "未授权"
            },
            ok = shizuku == ProviderStatus.AVAILABLE
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SecondaryButton(text = "重新检测", onClick = onRefreshStatuses)
            if (shizuku != ProviderStatus.AVAILABLE) {
                SecondaryButton(
                    text = if (shizuku == ProviderStatus.NOT_INSTALLED) "打开 Shizuku" else "请求授权",
                    onClick = {
                        val requested = ShizukuAuth.request()
                        if (!requested && !ShizukuAuth.launchShizukuApp(context)) {
                            onNotice("Shizuku 服务未运行，请先安装并启动 Shizuku")
                        }
                    }
                )
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.track))

        Text(text = "使用情况访问（降级通道）", style = FreshType.body, color = palette.onSurface)
        StatusLine(
            text = when (usageStats) {
                ProviderStatus.AVAILABLE -> "已授权 · 仅提供应用与时段数据"
                ProviderStatus.USAGE_ACCESS_DENIED, ProviderStatus.PERMISSION_DENIED ->
                    "未授权 · 无法读取任何数据"
                ProviderStatus.NOT_INSTALLED -> "当前设备不提供该入口"
                ProviderStatus.TEMPORARILY_UNAVAILABLE, null -> "暂时不可用"
            },
            ok = usageStats == ProviderStatus.AVAILABLE
        )
        if (usageStats != ProviderStatus.AVAILABLE) {
            SecondaryButton(
                text = "去系统授权",
                onClick = {
                    // 直接落到本 App 的「使用情况访问」页；部分 ROM 没有该子页，
                    // 退回应用详情页，不让按钮变成死链。
                    val intents = listOf(
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:${context.packageName}")
                        )
                    )
                    val launched = intents.any { intent ->
                        runCatching {
                            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.isSuccess
                    }
                    if (!launched) onNotice("没有找到系统授权页，请手动到系统设置里开启")
                }
            )
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.track))

        ActionRow(
            label = "应用分类",
            value = "按包名查看与修改",
            onClick = onOpenAppCategories
        )

        Text(text = "数据覆盖", style = FreshType.body, color = palette.onSurface)
        Text(
            text = coverageText(coverage),
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )

        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.track))

        Text(text = "导出 HTML 报告", style = FreshType.body, color = palette.onSurface)
        Text(
            text = "导出当前筛选区间的使用概览，单文件、断网可看。",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
        SecondaryButton(
            text = "选择保存位置",
            onClick = { showExportSheet = true }
        )
    }

    if (showExportSheet) {
        ReportExportSheet(
            appVersion = appVersion,
            onDismiss = { showExportSheet = false },
            onExport = { hideNames ->
                scope.launch {
                    val payload = buildReport(hideNames)
                    showExportSheet = false
                    if (payload == null) {
                        onNotice("报告生成失败，请稍后再试")
                        return@launch
                    }
                    pending = payload
                    launcher.launch(payload.first)
                }
            }
        )
    }
}

/** 导出前的选项：隐藏应用名称（§5.11「导出前可选」）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportExportSheet(
    appVersion: String,
    onDismiss: () -> Unit,
    onExport: (hideAppNames: Boolean) -> Unit
) {
    val palette = LocalFreshPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var hideNames by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = palette.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(text = "导出 HTML 报告", style = FreshType.sectionTitle, color = palette.onSurface)
            Text(
                text = "报告使用当前日期筛选的区间，数字与页面同源；v$appVersion。",
                style = FreshType.label,
                color = palette.onSurfaceVariant
            )
            SettingSwitchRow(
                label = "隐藏应用名称",
                checked = hideNames,
                onCheckedChange = { hideNames = it }
            )
            Text(
                text = "开启后应用只显示为「应用 1」「应用 2」，保留占比与颜色。",
                style = FreshType.label,
                color = palette.onSurfaceVariant
            )
            PrimaryButton(
                text = "导出",
                onClick = { onExport(hideNames) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun coverageText(coverage: HistoryCoverage?): String {
    if (coverage == null || !coverage.hasAny) {
        return "本机还没有入库的历史数据。"
    }
    val first = coverage.firstDate
    val last = coverage.lastDate
    if (first == null || last == null) return "本机还没有入库的历史数据。"
    return "已入库 ${first.monthValue}月${first.dayOfMonth}日 – " +
        "${last.monthValue}月${last.dayOfMonth}日，共 ${coverage.dayCount} 天。" +
        "系统只保留约 9–10 天记录，越早拉取越完整。"
}

// ---------------------------------------------------------------- 统计口径

@Composable
private fun ThresholdGroup(
    lowThresholdMin: Int,
    onLowThresholdChange: (Int) -> Unit
) {
    val palette = LocalFreshPalette.current
    var showWheel by remember { mutableStateOf(false) }
    val hours = LowUsageThreshold.hoursOf(lowThresholdMin)
    SettingsGroup("统计口径") {
        // §5.8（CR-05 修订）：入口是一行状态，点开底部弹层的**滚轮选择器**。
        // 以前是「滑杆 + 数字输入框」并列：24 档均摊到屏宽后每档约 12dp，
        // 拖到 6 小时要靠看数字确认，指下没有刻度感；改成滚轮后中间一格即当前值，
        // 吸附（snap）保证停下必然对齐整小时，也不需要再维护「滑杆↔输入框」两份草稿。
        ActionRow(
            label = "日历里「低于 x 小时」的判断标准",
            value = "$hours 小时",
            onClick = { showWheel = true }
        )
        Text(
            text = "低于这个时长的天数会在日历页单独计数。可在 1–24 小时之间调整，作息不同，这条线因人而异。",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
        if (showWheel) {
            NumberWheelSheet(
                title = "低于多少小时算少",
                range = LowUsageThreshold.MIN_HOURS..LowUsageThreshold.MAX_HOURS,
                value = hours,
                unit = "小时",
                hint = "低于这个时长的天数会在日历页单独计数",
                onConfirm = { onLowThresholdChange(LowUsageThreshold.minutesOf(it)) },
                onDismiss = { showWheel = false }
            )
        }
    }
}

// ---------------------------------------------------------------- 外观

@Composable
private fun AppearanceGroup(
    themeVariant: ThemeVariant,
    onOpenStyleSheet: () -> Unit,
    customThemeEnabled: Boolean,
    onOpenCustomTheme: () -> Unit,
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    wallpaperBitmap: androidx.compose.ui.graphics.ImageBitmap?,
    wallpaperConfig: WallpaperConfig,
    wallpaperMessage: String?,
    blurSupported: Boolean,
    onWallpaperPicked: (Uri?) -> Unit,
    onWallpaperDimChange: suspend (Int) -> Unit,
    onWallpaperBlurChange: suspend (Int) -> Unit,
    onWallpaperColorScopeChange: suspend (WallpaperColorScope) -> Unit,
    onClearWallpaper: () -> Unit,
    onConsumeWallpaperMessage: () -> Unit,
    onOpenWallpaperEditor: () -> Unit
) {
    val palette = LocalFreshPalette.current
    val scope = rememberCoroutineScope()
    val themeSource = com.screentrace.app.theme.WallpaperPalette.resolveThemeSource(
        wallpaperEnabled = wallpaperConfig.enabled,
        wallpaperScope = wallpaperConfig.colorScope,
        customSeedActive = customThemeEnabled,
        dynamicColorActive = dynamicColor && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    )
    val styleValue = when (themeSource) {
        com.screentrace.app.theme.ThemeColorSource.WALLPAPER_FULL_THEME -> "由壁纸驱动"
        com.screentrace.app.theme.ThemeColorSource.CUSTOM_SEED -> "预设（已自定义）"
        com.screentrace.app.theme.ThemeColorSource.DYNAMIC_COLOR -> "系统动态取色"
        com.screentrace.app.theme.ThemeColorSource.PRESET -> themeVariant.label
    }
    SettingsGroup("外观") {
        ActionRow(
            label = "界面风格",
            value = styleValue,
            onClick = onOpenStyleSheet
        )
        ActionRow(
            label = "自定义主题",
            value = when {
                themeSource == com.screentrace.app.theme.ThemeColorSource.WALLPAPER_FULL_THEME -> "当前由壁纸驱动"
                customThemeEnabled -> "已启用"
                else -> "未启用"
            },
            onClick = onOpenCustomTheme
        )
        SettingSwitchRow(
            label = "深色模式",
            checked = darkTheme,
            onCheckedChange = onDarkThemeChange
        )
        SettingSwitchRow(
            label = "跟随壁纸动态取色",
            checked = dynamicColor,
            onCheckedChange = onDynamicColorChange
        )
        Text(
            text = "仅在 Android 12+ 生效并同步驱动图表色板；自定义主题与壁纸「整套主题」优先级更高。",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )

        // §5.4 批 E：自定义壁纸（入口就在「外观」组内，不新增页面、不并入 StyleSheet）
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.track)
        )
        Text(text = "自定义壁纸", style = FreshType.body, color = palette.onSurface)
        Text(
            text = if (wallpaperConfig.enabled) "已设置 · 全 App 使用同一张" else "未设置 · 使用默认渐变背景",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
        val pickWallpaper = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri -> onWallpaperPicked(uri) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                text = "选择图片",
                onClick = {
                    pickWallpaper.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier.weight(1f)
            )
            SecondaryButton(
                text = "恢复默认",
                enabled = wallpaperConfig.enabled,
                onClick = onClearWallpaper,
                modifier = Modifier.weight(1f)
            )
        }
        // §5.4 CR-19 ②：当前壁纸缩略预览（启用时才显示）
        if (wallpaperBitmap != null && wallpaperConfig.enabled) {
            Image(
                bitmap = wallpaperBitmap,
                contentDescription = "当前壁纸预览",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(RoundedCornerShape(18.dp))
            )
            SecondaryButton(
                text = "调整裁切",
                enabled = wallpaperConfig.enabled,
                onClick = onOpenWallpaperEditor,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Text(
            text = "暗化 ${wallpaperConfig.dimPercent}%",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
        Slider(
            value = wallpaperConfig.dimPercent.toFloat(),
            onValueChange = { value -> scope.launch { onWallpaperDimChange(value.toInt()) } },
            valueRange = WallpaperTuning.DIM_MIN.toFloat()..WallpaperTuning.DIM_MAX.toFloat(),
            enabled = wallpaperConfig.enabled,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = if (blurSupported) "模糊 ${wallpaperConfig.blurDp}dp"
            else "当前系统版本不支持模糊（需 Android 12 及以上）",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
        Slider(
            value = wallpaperConfig.blurDp.toFloat(),
            onValueChange = { value -> scope.launch { onWallpaperBlurChange(value.toInt()) } },
            valueRange = WallpaperTuning.BLUR_MIN.toFloat()..WallpaperTuning.BLUR_MAX.toFloat(),
            enabled = wallpaperConfig.enabled && blurSupported,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "图片只存在本机私有目录，不进备份、不上传云端；浅色/深色主题会自动调整遮罩。",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
        // §5.4 CR-20 ②：壁纸配色范围两套方案——负责人要看真机对比，所以做成可切换。
        // 只在启用壁纸时出现（没壁纸时卡片走原主题色，这个开关没有意义）。
        if (wallpaperConfig.enabled) {
            Text(text = "卡片配色", style = FreshType.body, color = palette.onSurface)
            SegmentedControl(
                items = WallpaperColorScope.values().map { it.label },
                selectedIndex = WallpaperColorScope.values().indexOf(wallpaperConfig.colorScope),
                onSelect = { index ->
                    scope.launch {
                        onWallpaperColorScopeChange(WallpaperColorScope.values()[index])
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "「仅卡片」只有卡片底色跟着壁纸走，强调色与图表色仍用当前主题；" +
                    "「整套主题」连主色、图表色、月历色阶一起跟着壁纸走，风格更统一但图表色更容易和壁纸撞色。",
                style = FreshType.label,
                color = palette.onSurfaceVariant
            )
        }
        wallpaperMessage?.let { message ->
            Text(text = message, style = FreshType.label, color = palette.primary)
            LaunchedEffect(message) {
                kotlinx.coroutines.delay(2500L)
                onConsumeWallpaperMessage()
            }
        }
    }
}

// ---------------------------------------------------------------- 周期总结提醒（CR-30 §7-30）

@Composable
private fun ReminderGroup(
    weeklyEnabled: Boolean,
    onWeeklyChange: (Boolean) -> Unit,
    monthlyEnabled: Boolean,
    onMonthlyChange: (Boolean) -> Unit,
    reminderHour: Int,
    onNotice: (String) -> Unit
) {
    val context = LocalContext.current
    val palette = LocalFreshPalette.current
    var hasPermission by remember {
        mutableStateOf(
            com.screentrace.app.data.reminder.PeriodReminderScheduler.hasNotificationPermission(context)
        )
    }
    var pendingEnableTarget by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) {
            when (pendingEnableTarget) {
                "WEEK" -> onWeeklyChange(true)
                "MONTH" -> onMonthlyChange(true)
            }
        } else {
            onNotice("未授予通知权限，无法发送周期总结提醒")
        }
        pendingEnableTarget = null
    }

    fun toggleWithPermission(target: String, nextValue: Boolean, apply: (Boolean) -> Unit) {
        if (!nextValue) {
            apply(false)
            return
        }
        val granted = com.screentrace.app.data.reminder.PeriodReminderScheduler
            .hasNotificationPermission(context)
        hasPermission = granted
        if (granted) {
            apply(true)
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pendingEnableTarget = target
            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    SettingsGroup("周期总结提醒") {
        Text(
            text = "仅在周期完整结束后提醒查看统计总结；无有效数据时不发送通知。",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
        SettingSwitchRow(
            label = "每周总结提醒（周一 $reminderHour:00）",
            checked = weeklyEnabled,
            onCheckedChange = { toggleWithPermission("WEEK", it, onWeeklyChange) }
        )
        SettingSwitchRow(
            label = "每月总结提醒（每月 1 日 $reminderHour:00）",
            checked = monthlyEnabled,
            onCheckedChange = { toggleWithPermission("MONTH", it, onMonthlyChange) }
        )
        StatusLine(
            text = if (hasPermission) "通知权限已开启" else "通知权限未开启（开启开关时申请）",
            ok = hasPermission
        )
        if (!hasPermission) {
            SecondaryButton(
                text = "去系统设置开启通知权限",
                onClick = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(intent) }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ---------------------------------------------------------------- 数据采集诊断（CR-30 阶段 E）

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollectionDiagnosticsGroup(
    verdict: com.screentrace.app.data.pull.DataCollectionVerdict?,
    snapshot: com.screentrace.app.data.pull.PullDiagnosticSnapshot?,
    channelStatuses: List<Pair<PullChannel, ProviderStatus>>,
    coverage: HistoryCoverage?,
    lastPulledAt: Long?,
    pulling: Boolean,
    onPullNow: () -> Unit,
    onRefreshStatuses: () -> Unit,
    onNotice: (String) -> Unit
) {
    val palette = LocalFreshPalette.current
    val context = LocalContext.current
    val statuses = snapshot?.channelStatuses ?: channelStatuses
    val shizuku = statuses.firstOrNull { it.first == PullChannel.SHIZUKU }?.second
    val usageStats = statuses.firstOrNull { it.first == PullChannel.USAGE_STATS }?.second
    val activeCoverage = snapshot?.coverage ?: coverage
    val effectiveLastOk = snapshot?.lastOkAtMs ?: lastPulledAt
    val latestLog = snapshot?.latestLog

    SettingsGroup("数据采集诊断") {
        val stateTitle = verdict?.title ?: if (pulling) "采集中" else "检测中"
        val stateOk = verdict?.state == com.screentrace.app.data.pull.DataCollectionState.NORMAL
        StatusLine(
            text = "当前状态：$stateTitle",
            ok = stateOk
        )
        verdict?.detailReason?.let { reason ->
            Text(
                text = reason,
                style = FreshType.label,
                color = palette.onSurfaceVariant
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SecondaryButton(
                text = if (pulling) "正在拉取…" else "立即拉取",
                enabled = !pulling,
                onClick = onPullNow
            )
            SecondaryButton(text = "重新检测通道", onClick = onRefreshStatuses)
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.track))

        Text(text = "Shizuku（主通道 · 完整事件流）", style = FreshType.body, color = palette.onSurface)
        StatusLine(
            text = when (shizuku) {
                ProviderStatus.AVAILABLE -> "已连接并授权 · 亮屏会话、解锁与通知完整可用"
                ProviderStatus.NOT_INSTALLED -> "未检测到运行中的 Shizuku 服务"
                ProviderStatus.PERMISSION_DENIED -> "服务已运行，但尚未授予屏迹权限"
                ProviderStatus.TEMPORARILY_UNAVAILABLE, null -> "服务暂时不可用（可重新检测或重启服务）"
                ProviderStatus.USAGE_ACCESS_DENIED -> "未授权"
            },
            ok = shizuku == ProviderStatus.AVAILABLE
        )
        if (shizuku != ProviderStatus.AVAILABLE) {
            SecondaryButton(
                text = if (shizuku == ProviderStatus.NOT_INSTALLED) "打开 Shizuku" else "请求 Shizuku 授权",
                onClick = {
                    val requested = ShizukuAuth.request()
                    if (!requested && !ShizukuAuth.launchShizukuApp(context)) {
                        onNotice("Shizuku 服务未运行，请先安装并启动 Shizuku")
                    }
                }
            )
        }

        Text(text = "使用情况访问（降级通道 · 仅应用与时段）", style = FreshType.body, color = palette.onSurface)
        StatusLine(
            text = when (usageStats) {
                ProviderStatus.AVAILABLE -> "已授权 · 主通道断开时自动兜底应用与时段"
                ProviderStatus.USAGE_ACCESS_DENIED, ProviderStatus.PERMISSION_DENIED ->
                    "未授权 · 无法作为降级兜底通道"
                ProviderStatus.NOT_INSTALLED -> "当前系统未提供该入口"
                ProviderStatus.TEMPORARILY_UNAVAILABLE, null -> "暂时不可用"
            },
            ok = usageStats == ProviderStatus.AVAILABLE
        )
        if (usageStats != ProviderStatus.AVAILABLE) {
            SecondaryButton(
                text = "去系统开启使用情况访问",
                onClick = {
                    val intents = listOf(
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:${context.packageName}")
                        )
                    )
                    val launched = intents.any { intent ->
                        runCatching {
                            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.isSuccess
                    }
                    if (!launched) onNotice("没有找到系统授权页，请手动到系统设置里开启")
                }
            )
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.track))

        Text(text = "最近拉取与缺口", style = FreshType.body, color = palette.onSurface)
        Text(
            text = "最近成功拉取：${pullTimeText(effectiveLastOk)}",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
        if (latestLog != null) {
            val statusTag = latestLog.collectionStatus ?: if (latestLog.ok) "OK" else "FAILED"
            val noteSuffix = latestLog.note?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            Text(
                text = "最近记录：来源 ${latestLog.source} · 状态 $statusTag$noteSuffix",
                style = FreshType.label,
                color = palette.onSurfaceVariant
            )
        }
        Text(
            text = "已入库覆盖：${coverageText(activeCoverage)}",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
        val gapDates = snapshot?.recentGapDates.orEmpty()
        val gapText = if (gapDates.isEmpty()) {
            "近 7 天无拉取缺口（失败记录 ${snapshot?.recentFailedCount7d ?: 0} 次）"
        } else {
            val formatted = gapDates.joinToString("、") { "${it.monthValue}/${it.dayOfMonth}" }
            "近 7 天存在 ${gapDates.size} 天缺口（$formatted）"
        }
        Text(
            text = gapText,
            style = FreshType.label,
            color = if (gapDates.isEmpty()) palette.onSurfaceVariant else palette.overTarget
        )
    }
}

// ---------------------------------------------------------------- 关于

@Composable
private fun AboutGroup(coverage: HistoryCoverage?, appVersion: String) {
    val palette = LocalFreshPalette.current
    SettingsGroup("关于") {
        Text(text = "屏迹 v$appVersion", style = FreshType.body, color = palette.onSurface)
        AboutNote(
            title = "为什么和系统自带的数字有差别",
            body = "屏迹以系统使用记录的屏幕会话为基础，按「亮屏且未锁屏」的并集计算；" +
                "系统自带统计的切分口径不同，通常相差几分钟到十几分钟。"
        )
        AboutNote(
            title = "应用时长相加会大于总时长",
            body = "同一时段里分屏、画中画、后台播放会让多个应用各自计时，所以应用合计可能超过屏幕时长。"
        )
        AboutNote(
            title = "解锁与通知的口径",
            body = "「通知打扰」只统计会产生视觉或声音打扰的通知，静音与静默渠道不计入；" +
                "「已查看」是点开通知的次数。"
        )
        AboutNote(
            title = "事件明细从首次拉取开始累积",
            body = "系统事件缓冲区是滚动的，越早的解锁与通知事件越容易被系统清掉；" +
                "事件明细只从第一次成功拉取那一刻开始累积，不能追溯到更早。"
        )
        AboutNote(
            title = "数据存在本机",
            body = "统计与明细全部保存在本机数据库，不上传；只有你主动导出报告时才会写文件。"
        )
        Text(
            text = "已入库覆盖：" + coverageText(coverage),
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
    }
}

@Composable
private fun AboutNote(title: String, body: String) {
    val palette = LocalFreshPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = title, style = FreshType.label, color = palette.onSurface)
        Text(text = body, style = FreshType.label, color = palette.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- 通用小件

@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalFreshPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(palette.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = title, style = FreshType.sectionTitle, color = palette.onSurface)
        content()
    }
}

@Composable
private fun SettingSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val palette = LocalFreshPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = FreshType.body,
            color = palette.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}

/** 「标签 + 当前值 →」的可点击行（≥48dp 点击区，§5.9）。 */
@Composable
internal fun ActionRow(label: String, value: String, onClick: () -> Unit) {
    val palette = LocalFreshPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .semantics {
                role = Role.Button
                contentDescription = "$label，当前 $value"
            }
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = FreshType.body, color = palette.onSurface)
        Text(text = "$value ›", style = FreshType.label, color = palette.onSurfaceVariant)
    }
}

/** 状态行：圆点 + 文案（颜色只是辅助，文案本身说清状态，§5.9）。 */
@Composable
private fun StatusLine(text: String, ok: Boolean) {
    val palette = LocalFreshPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .width(8.dp)
                .height(8.dp)
                .clip(CircleShape)
                .background(if (ok) palette.primary else palette.track)
        )
        Text(text = text, style = FreshType.label, color = palette.onSurfaceVariant)
    }
}

/** 三选一的胶囊按钮（拉取频率用；与 SegmentedControl 视觉一致但支持等宽均分）。 */
@Composable
internal fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalFreshPalette.current
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(CircleShape)
            .background(if (selected) palette.primary else palette.track)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick
            )
            // 真机反馈：无内边距时文字顶满胶囊、「每日」被裁——weight 布局时代
            // 宽度被撑开看不出来，自适应宽度后必须 padding 撑开背景
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = FreshType.label,
            color = if (selected) palette.onPrimary else palette.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
