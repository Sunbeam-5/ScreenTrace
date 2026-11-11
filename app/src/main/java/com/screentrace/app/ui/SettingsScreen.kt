package com.screentrace.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.screentrace.app.contract.DEFAULT_LOW_THRESHOLD_MIN
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.uikit.EmptyState
import com.screentrace.app.uikit.SegmentedControl

/** 「低于 x 小时」的可选阈值：1 / 2 / 3 / 4 小时。 */
private val THRESHOLD_OPTIONS = listOf(60, 120, 180, 240)

/**
 * 设置页：目前落地「外观」「统计口径」两组实体，其余组随版本补齐（§5.8）；
 * 「外观」与「统计口径」两组是真实可用功能，其余组诚实待办——不画假按钮（§6）。
 */
@Composable
fun SettingsScreen(
    darkTheme: Boolean,
    dynamicColor: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    /** 日历页「低于 x 小时」的阈值（分钟）。 */
    lowThresholdMin: Int = DEFAULT_LOW_THRESHOLD_MIN,
    onLowThresholdChange: (Int) -> Unit = {}
) {
    SettingsGroup("外观") {
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
    }

    SettingsGroup("统计口径") {
        val palette = LocalFreshPalette.current
        Text(
            text = "日历里「低于 x 小时」的判断标准",
            style = FreshType.body,
            color = palette.onSurface
        )
        SegmentedControl(
            items = THRESHOLD_OPTIONS.map { "${it / 60} 小时" },
            // 存进来的值不在预设里（旧版本数据 / 手改配置）时高亮默认档，
            // 而不是硬夹到第 0 项「1 小时」——那会让人以为设置被改过。
            selectedIndex = THRESHOLD_OPTIONS.indexOf(lowThresholdMin).takeIf { it >= 0 } ?: 1,
            onSelect = { onLowThresholdChange(THRESHOLD_OPTIONS[it]) }
        )
        Text(
            text = "低于这个时长的天数会在日历页单独计数。作息不同，这条线因人而异。",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
    }

    EmptyState(
        title = "更多设置建设中",
        description = "数据备份与云同步、应用分类管理等功能还在开发中，会随版本更新陆续开放"
    )
}

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
