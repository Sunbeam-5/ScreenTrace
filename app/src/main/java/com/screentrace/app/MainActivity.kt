package com.screentrace.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.EmptyUsageRepository
import com.screentrace.app.contract.Summary
import com.screentrace.app.contract.UsageCalculator
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme
import com.screentrace.app.theme.ThemeVariant
import com.screentrace.app.uikit.AppHeader
import com.screentrace.app.uikit.ScreenScaffold

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ScreenTraceApp() }
    }
}

/**
 * 骨架首页：验证「语义色板 + 九套主题 × 深浅 + 字体/卡片 token + 页面骨架 + 日期契约 + 空态」链路。
 *
 * 按需求文档 §6 与 §7-3，这里刻意不画任何假数据的图表或业务数值，也不放未接线的按钮：
 * 日期弹层（批次 3）、设置页（批次 7）、四个数据页面（批次 4）、悬浮导航路由（批次 3）
 * 接线前，AppHeader 只呈现已真实可用的「界面风格」按钮（当前临时行为：切换深浅色，
 * 批次 3 改为打开风格弹层）。
 */
@Composable
fun ScreenTraceApp() {
    val variant = remember { mutableStateOf(ThemeVariant.MINT) }
    val darkTheme = remember { mutableStateOf(false) }

    ScreenTimeTheme(
        variant = variant.value,
        darkTheme = darkTheme.value,
        dynamicColor = false
    ) {
        val palette = LocalFreshPalette.current
        val summaryState = remember { mutableStateOf<Summary?>(null) }
        LaunchedEffect(Unit) {
            summaryState.value = EmptyUsageRepository().summary(DateSelection.today())
        }

        ScreenScaffold(
            header = {
                AppHeader(
                    title = "屏迹",
                    subtitle = "ScreenTrace · 契约骨架 v0.2"
                    // 批次 1 评审 P2-6：风格按钮的真实行为（打开风格弹层）尚不存在，
                    // 用切换深浅色冒充会造成描述与行为不符 —— 暂不渲染，批次 3 接入后恢复。
                    // 深浅色切换暂用下方「外观」卡片的 Switch（真实可用）。
                )
            }
        ) {
            InfoCard("工程状态") {
                Text("Gradle / Compose 工程已就绪，可直接 Sync 与 Run。", style = FreshType.body, color = palette.onSurface)
                Text(
                    text = "主题：${variant.value.label} · ${if (darkTheme.value) "深色" else "浅色"}",
                    style = FreshType.body,
                    color = palette.onSurfaceVariant
                )
            }

            InfoCard("数据契约（空态演示）") {
                val s = summaryState.value
                Text(
                    text = "DateSelection：${s?.selection?.days ?: "—"} 天 · 有记录 ${s?.recordedDays ?: "—"} 天",
                    style = FreshType.body,
                    color = palette.onSurface
                )
                Text(
                    text = if (s?.hasAnyRecord == true) "已接线真实数据" else "尚无数据 · 空态（未使用假数据）",
                    style = FreshType.label,
                    color = palette.onSurfaceVariant
                )
                Text(
                    text = "已定义：UsageRepository（含 streak/extremes/monthLevels）/ DateSelection / UsageCalculator",
                    style = FreshType.label,
                    color = palette.onSurfaceVariant
                )
            }

            InfoCard("界面风格 · 九套（点色块立即切换）") {
                ThemeVariant.values().toList().chunked(3).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEach { item ->
                            val selected = item == variant.value
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (selected) palette.primaryContainer else palette.surfaceVariant)
                                    .clickable { variant.value = item }
                                    .padding(vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(RoundedCornerShape(50.dp))
                                        .background(item.seed)
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = item.label,
                                    style = FreshType.label,
                                    textAlign = TextAlign.Center,
                                    color = if (selected) palette.onPrimaryContainer else palette.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            InfoCard("外观") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("深色模式", style = FreshType.body, color = palette.onSurface)
                    Switch(checked = darkTheme.value, onCheckedChange = { darkTheme.value = it })
                }
            }

            InfoCard("待接线（按 §6 实施顺序）") {
                listOf(
                    "① 语义色板与字体/间距 token —— 已完成",
                    "② 共享 DateSelection 与领域计算器 —— 已完成（含契约 v0.2 与 JVM 单测）",
                    "③ 通用组件 —— 已完成：uikit/ 骨架与基础组件（本页即其载体）",
                    "④ 四个数据页面 —— 待办：今日 / 统计 / 日历 / 排行（批次 4）",
                    "⑤ 设置与系统状态 —— 待办：Shizuku、Room、导出、§5.10 云同步（批次 6–8）",
                    "⑥ 测试 —— 待办：golden fixtures（M:SS、去重、跨零点）（批次 5）"
                ).forEach { line ->
                    Text(text = line, style = FreshType.body, color = palette.onSurfaceVariant)
                }
                Text(
                    text = "口径示例：272 分钟 → ${UsageCalculator.formatClock(272)} / ${UsageCalculator.formatDuration(272)}",
                    style = FreshType.label,
                    color = palette.onSurface
                )
            }
        }
    }
}

/** §5.2：统一 24dp 无描边卡片、20dp 内边距。 */
@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalFreshPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(palette.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text = title, style = FreshType.sectionTitle, color = palette.onSurface)
        content()
    }
}

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun ScreenTraceAppPreview() {
    ScreenTraceApp()
}
