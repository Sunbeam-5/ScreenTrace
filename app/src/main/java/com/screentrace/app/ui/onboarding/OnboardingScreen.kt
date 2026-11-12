package com.screentrace.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.screentrace.app.data.prefs.PullFrequency
import com.screentrace.app.data.pull.PullChannel
import com.screentrace.app.data.pull.ProviderStatus
import com.screentrace.app.data.pull.ShizukuAuth
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ThemeVariant
import com.screentrace.app.uikit.PrimaryButton
import com.screentrace.app.uikit.SecondaryButton
import kotlin.math.roundToInt

/** 向导步数（§5.8：欢迎与主题 → Shizuku → 目标与频率 → 备份与云端）。 */
private const val STEP_COUNT = 4

/**
 * 首启向导（§5.8）。四步状态卡片，任何一步都能「稍后在设置中配置」。
 *
 * 设计取舍：**不阻断使用**。每一步都有跳过，最后一步的「开始使用」才是主按钮——
 * 屏幕使用统计的核心数据来自 Shizuku 授权，但没授权也应该能进 App 看到
 * 「未授权」的诚实状态（§5.8 状态机），而不是被一个向导卡死在门口。
 */
@Composable
fun OnboardingScreen(
    themeVariant: ThemeVariant,
    onThemeVariantChange: (ThemeVariant) -> Unit,
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    dailyGoalMin: Int,
    onDailyGoalChange: (Int) -> Unit,
    pullFrequency: PullFrequency,
    onPullFrequencyChange: (PullFrequency) -> Unit,
    channelStatuses: List<Pair<PullChannel, ProviderStatus>>,
    onRefreshStatuses: () -> Unit,
    onFinish: () -> Unit
) {
    val palette = LocalFreshPalette.current
    var step by remember { mutableIntStateOf(0) }
    val scrollState = rememberScrollState()

    val shizuku = channelStatuses.firstOrNull { it.first == PullChannel.SHIZUKU }?.second

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .systemBarsPadding()
            .padding(horizontal = 20.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(16.dp))

        // 进度点：一眼能看出还剩几步
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(STEP_COUNT) { index ->
                Box(
                    Modifier
                        .width(if (index == step) 22.dp else 8.dp)
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (index <= step) palette.primary else palette.track
                        )
                )
            }
        }

        when (step) {
            0 -> WelcomeStep(
                themeVariant = themeVariant,
                onThemeVariantChange = onThemeVariantChange,
                darkTheme = darkTheme,
                onDarkThemeChange = onDarkThemeChange
            )
            1 -> ShizukuStep(
                status = shizuku,
                onRefresh = onRefreshStatuses
            )
            2 -> GoalStep(
                dailyGoalMin = dailyGoalMin,
                onDailyGoalChange = onDailyGoalChange,
                pullFrequency = pullFrequency,
                onPullFrequencyChange = onPullFrequencyChange
            )
            else -> SyncStep()
        }

        Spacer(Modifier.height(8.dp))

        // 主按钮每屏只有一个（§5.5）：最后一步是「开始使用」，其余是「下一步」
        PrimaryButton(
            text = if (step == STEP_COUNT - 1) "开始使用" else "下一步",
            onClick = {
                if (step == STEP_COUNT - 1) onFinish() else step++
            },
            modifier = Modifier.fillMaxWidth()
        )
        if (step < STEP_COUNT - 1) {
            SecondaryButton(
                text = "稍后在设置中配置",
                onClick = { step++ },
                modifier = Modifier.fillMaxWidth()
            )
        }
        SecondaryButton(
            text = "跳过向导",
            onClick = onFinish,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StepTitle(title: String, subtitle: String) {
    val palette = LocalFreshPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = title, style = FreshType.pageTitle, color = palette.onSurface)
        Text(text = subtitle, style = FreshType.body, color = palette.onSurfaceVariant)
    }
}

@Composable
private fun StepCard(content: @Composable () -> Unit) {
    val palette = LocalFreshPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(palette.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        content()
    }
}

// ---------------------------------------------------------------- ① 欢迎与主题

@Composable
private fun WelcomeStep(
    themeVariant: ThemeVariant,
    onThemeVariantChange: (ThemeVariant) -> Unit,
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit
) {
    val palette = LocalFreshPalette.current
    StepTitle(
        title = "欢迎使用屏迹",
        subtitle = "统计屏幕使用时间、解锁次数与通知打扰，全部数据留在本机。"
    )
    StepCard {
        Text(text = "选一个界面风格", style = FreshType.body, color = palette.onSurface)
        ThemeVariant.entries.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { variant ->
                    OnboardingChoice(
                        label = variant.label,
                        selected = variant == themeVariant,
                        onClick = { onThemeVariantChange(variant) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // 最后一行不足 3 个时占位，避免被拉伸成不等宽
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "深色模式", style = FreshType.body, color = palette.onSurface)
            androidx.compose.material3.Switch(
                checked = darkTheme,
                onCheckedChange = onDarkThemeChange
            )
        }
    }
}

// ---------------------------------------------------------------- ② Shizuku

@Composable
private fun ShizukuStep(status: ProviderStatus?, onRefresh: () -> Unit) {
    val palette = LocalFreshPalette.current
    StepTitle(
        title = "授权读取使用记录",
        subtitle = "屏迹通过 Shizuku 以只读方式执行系统查询，不会修改任何系统设置。"
    )
    StepCard {
        Text(
            text = when (status) {
                ProviderStatus.AVAILABLE -> "已授权，可以直接开始"
                ProviderStatus.NOT_INSTALLED -> "没有检测到 Shizuku"
                ProviderStatus.PERMISSION_DENIED -> "Shizuku 在运行，但还没给屏迹授权"
                ProviderStatus.TEMPORARILY_UNAVAILABLE, null -> "暂时检测不到 Shizuku 服务"
                ProviderStatus.USAGE_ACCESS_DENIED -> "未授权"
            },
            style = FreshType.body,
            color = if (status == ProviderStatus.AVAILABLE) palette.primary else palette.onSurface
        )
        Text(
            text = when (status) {
                ProviderStatus.AVAILABLE ->
                    "拉取到的数据会存入本机数据库，只有你主动导出时才会写文件。"
                ProviderStatus.NOT_INSTALLED ->
                    "请先安装并启动 Shizuku（需要一次 ADB 或无线调试激活），回来点「重新检测」。"
                else ->
                    "在 Shizuku 里点「允许」即可；拒绝也没关系，App 会退到系统「使用情况访问」通道，" +
                        "只是拿不到解锁与通知明细。"
            },
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(text = "重新检测", onClick = onRefresh)
            if (status != ProviderStatus.AVAILABLE) {
                SecondaryButton(
                    text = "请求授权",
                    onClick = {
                        ShizukuAuth.request()
                        onRefresh()
                    }
                )
            }
        }
    }
}

// ---------------------------------------------------------------- ③ 目标与频率

@Composable
private fun GoalStep(
    dailyGoalMin: Int,
    onDailyGoalChange: (Int) -> Unit,
    pullFrequency: PullFrequency,
    onPullFrequencyChange: (PullFrequency) -> Unit
) {
    val palette = LocalFreshPalette.current
    StepTitle(
        title = "设一个每日目标",
        subtitle = "今日页的圆环按这个目标显示进度，随时可以在设置里改。"
    )
    StepCard {
        var draftHours by remember(dailyGoalMin) {
            mutableFloatStateOf((dailyGoalMin / 60f).coerceIn(1f, 16f))
        }
        Text(
            text = "${draftHours.roundToInt()} 小时 / 天",
            style = FreshType.metricNumber,
            color = palette.onSurface
        )
        Slider(
            value = draftHours,
            onValueChange = { draftHours = it },
            onValueChangeFinished = { onDailyGoalChange(draftHours.roundToInt() * 60) },
            valueRange = 1f..16f,
            steps = 14
        )
        Text(text = "后台拉取频率", style = FreshType.body, color = palette.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PullFrequency.entries.forEach { frequency ->
                OnboardingChoice(
                    label = frequency.label,
                    selected = frequency == pullFrequency,
                    onClick = { onPullFrequencyChange(frequency) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Text(
            text = "系统只保留约 9–10 天记录，默认每天拉一次；关机多的人可以调密一些。",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------- ④ 备份与云端

@Composable
private fun SyncStep() {
    val palette = LocalFreshPalette.current
    StepTitle(
        title = "备份与云端同步",
        subtitle = "这一组还在开发中，上线后可以备份到本地或坚果云 / Google Drive。"
    )
    StepCard {
        Text(
            text = "现在能做的",
            style = FreshType.body,
            color = palette.onSurface
        )
        Text(
            text = "设置 →「权限与数据」里可以导出 HTML 报告留档；数据本身始终在本机数据库里，" +
                "卸载应用前记得先导出报告。",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
        Text(
            text = "为什么先不做假开关",
            style = FreshType.body,
            color = palette.onSurface
        )
        Text(
            text = "云同步要处理凭据、冲突合并与失败重试，做一半就上线的话，用户会把「上传失败」" +
                "当成「数据丢了」。所以这一组等功能完整了再出现。",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
    }
}

@Composable
private fun OnboardingChoice(
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
            .semantics {
                role = Role.Button
                contentDescription = label
            }
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
