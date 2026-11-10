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
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.uikit.EmptyState

/**
 * 设置页占位（批次 3）。§5.8 四组卡片 + 关于在批次 7 全量接线；
 * 「外观」组的深浅色与动态取色是真实可用功能（批 7 迁入正式分组），
 * 其余组诚实待办——不画假按钮（§6）。
 */
@Composable
fun SettingsScreen(
    darkTheme: Boolean,
    dynamicColor: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit
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
    EmptyState(
        title = "更多设置建设中",
        description = "目标与同步、权限与数据、备份与同步、关于将在批次 7 接线"
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
