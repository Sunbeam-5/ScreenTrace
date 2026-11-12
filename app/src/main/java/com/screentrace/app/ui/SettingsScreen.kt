package com.screentrace.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ThemeVariant
import com.screentrace.app.uikit.PrimaryButton
import com.screentrace.app.uikit.SecondaryButton
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 阈值滑杆的小时范围（§7-23：1–24 小时连续可调，不再是 1/2/3/4 四档）。 */
private val THRESHOLD_HOURS = 1f..24f

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
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    lowThresholdMin: Int = DEFAULT_LOW_THRESHOLD_MIN,
    onLowThresholdChange: (Int) -> Unit = {},
    appVersion: String,
    /** 生成报告：入参 = 是否隐藏应用名称，返回（建议文件名, HTML 全文）。 */
    buildReport: suspend (Boolean) -> Pair<String, String>?,
    onNotice: (String) -> Unit = {}
) {
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

    // 诚实待办：功能未实现前不画可点击控件（§6），但要让用户知道它在路上
    SettingsGroup("提醒与限额") {
        val palette = LocalFreshPalette.current
        Text(text = "尚未开放", style = FreshType.body, color = palette.onSurfaceVariant)
        Text(
            text = "到量提醒与应用每日限额会在后续版本提供，届时才申请通知权限。" +
                "本应用不做强制锁机或拦截。",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
    }

    SettingsGroup("备份与同步") {
        val palette = LocalFreshPalette.current
        Text(text = "尚未开放", style = FreshType.body, color = palette.onSurfaceVariant)
        Text(
            text = "JSON 完整备份、CSV 明细、本地恢复与 WebDAV / Google Drive 云同步会一起上线。" +
                "现在可以先导出上面的 HTML 报告留档。",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
    }

    SettingsGroup("AI 分析") {
        val palette = LocalFreshPalette.current
        Text(text = "尚未开放 · 默认关闭", style = FreshType.body, color = palette.onSurfaceVariant)
        Text(
            text = "上线后可自行填写 endpoint、模型名与 API Key，只把聚合统计发出去，" +
                "不发送事件明细与包名。当前版本没有任何联网分析能力。",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
    }

    ThresholdGroup(
        lowThresholdMin = lowThresholdMin,
        onLowThresholdChange = onLowThresholdChange
    )

    AppearanceGroup(
        themeVariant = themeVariant,
        onOpenStyleSheet = onOpenStyleSheet,
        darkTheme = darkTheme,
        onDarkThemeChange = onDarkThemeChange,
        dynamicColor = dynamicColor,
        onDynamicColorChange = onDynamicColorChange
    )

    AboutGroup(coverage = coverage, appVersion = appVersion)
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
        Text(text = "每日目标", style = FreshType.body, color = palette.onSurface)

        // 与低使用阈值同一套交互：拖动只改草稿，松手才落盘（拖动中每次落地都会
        // 重算今日页圆环与进度文案，手感会变卡）。
        var draftHours by remember(dailyGoalMin) {
            mutableFloatStateOf((dailyGoalMin / 60f).coerceIn(GOAL_HOURS.start, GOAL_HOURS.endInclusive))
        }
        var numberText by remember(dailyGoalMin) {
            mutableStateOf((dailyGoalMin / 60).toString())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = numberText,
                onValueChange = { text ->
                    numberText = text
                    val hours = text.trim().toIntOrNull()
                    if (hours != null && hours in GOAL_HOURS.start.roundToInt()..GOAL_HOURS.endInclusive.roundToInt()) {
                        draftHours = hours.toFloat()
                        onDailyGoalChange(hours * 60)
                    }
                },
                label = { Text("小时", style = FreshType.label) },
                singleLine = true,
                isError = numberText.isNotBlank() &&
                    (numberText.trim().toIntOrNull()
                        ?: 0) !in GOAL_HOURS.start.roundToInt()..GOAL_HOURS.endInclusive.roundToInt(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.width(112.dp)
            )
            Text(
                text = "当前 ${draftHours.roundToInt()} 小时",
                style = FreshType.label,
                color = palette.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
        Slider(
            value = draftHours.coerceIn(GOAL_HOURS.start, GOAL_HOURS.endInclusive),
            onValueChange = {
                draftHours = it
                numberText = it.roundToInt().toString()
            },
            onValueChangeFinished = { onDailyGoalChange(draftHours.roundToInt() * 60) },
            valueRange = GOAL_HOURS,
            steps = GOAL_HOURS.endInclusive.roundToInt() - GOAL_HOURS.start.roundToInt() - 1
        )
        Text(
            text = "今日页圆环按这个目标算进度；到达目标只做提示，不会锁机。",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )

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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(text = "重新检测", onClick = onRefreshStatuses)
            if (shizuku != ProviderStatus.AVAILABLE) {
                SecondaryButton(
                    text = "请求授权",
                    onClick = {
                        val requested = ShizukuAuth.request()
                        if (!requested) {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "隐藏应用名称", style = FreshType.body, color = palette.onSurface)
                Switch(checked = hideNames, onCheckedChange = { hideNames = it })
            }
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
    SettingsGroup("统计口径") {
        Text(
            text = "日历里「低于 x 小时」的判断标准",
            style = FreshType.body,
            color = palette.onSurface
        )
        // 拖动时只改本地草稿，松手才写 DataStore：setLowThresholdMin 会触发整页
        // 重算（日历色档、低于 x 小时计数都跟着变），拖动中每次落地都会卡。
        var draftHours by remember(lowThresholdMin) {
            mutableFloatStateOf(LowUsageThreshold.hoursOf(lowThresholdMin).toFloat())
        }
        // §5.8「滑杆 + 数字双向输入」：拖滑杆回填数字，改数字移动滑杆。
        var numberText by remember(lowThresholdMin) {
            mutableStateOf(LowUsageThreshold.hoursOf(lowThresholdMin).toString())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = numberText,
                onValueChange = { text ->
                    numberText = text
                    val hours = text.trim().toIntOrNull()
                    if (hours != null && hours in LowUsageThreshold.MIN_HOURS..LowUsageThreshold.MAX_HOURS) {
                        draftHours = hours.toFloat()
                        onLowThresholdChange(LowUsageThreshold.minutesOf(hours))
                    }
                },
                label = { Text("小时", style = FreshType.label) },
                singleLine = true,
                isError = numberText.isNotBlank() &&
                    (numberText.trim().toIntOrNull() ?: 0) !in LowUsageThreshold.MIN_HOURS..LowUsageThreshold.MAX_HOURS,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.width(112.dp)
            )
            Text(
                text = "当前 ${draftHours.roundToInt()} 小时",
                style = FreshType.label,
                color = palette.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
        Slider(
            value = draftHours.coerceIn(THRESHOLD_HOURS.start, THRESHOLD_HOURS.endInclusive),
            onValueChange = {
                draftHours = it
                numberText = it.roundToInt().toString()
            },
            onValueChangeFinished = {
                onLowThresholdChange(LowUsageThreshold.minutesOf(draftHours.roundToInt()))
            },
            valueRange = THRESHOLD_HOURS,
            // 24 档（1..24）之间有 22 个刻度，滑杆只会停在整小时上
            steps = LowUsageThreshold.MAX_HOURS - LowUsageThreshold.MIN_HOURS - 1
        )
        Text(
            text = "低于这个时长的天数会在日历页单独计数。可在 1–24 小时之间调整，作息不同，这条线因人而异。",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------- 外观

@Composable
private fun AppearanceGroup(
    themeVariant: ThemeVariant,
    onOpenStyleSheet: () -> Unit,
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit
) {
    SettingsGroup("外观") {
        ActionRow(
            label = "界面风格",
            value = themeVariant.label,
            onClick = onOpenStyleSheet
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
            text = "动态取色只在系统支持时生效（Android 12+），否则用当前风格的颜色。",
            style = FreshType.label,
            color = LocalFreshPalette.current.onSurfaceVariant
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
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
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
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = FreshType.body, color = palette.onSurface)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 「标签 + 当前值 →」的可点击行（≥48dp 点击区，§5.9）。 */
@Composable
private fun ActionRow(label: String, value: String, onClick: () -> Unit) {
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
private fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalFreshPalette.current
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(CircleShape)
            .background(if (selected) palette.primary else palette.track)
            .semantics { role = Role.Button }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = FreshType.label,
            color = if (selected) palette.onPrimary else palette.onSurfaceVariant
        )
    }
}
