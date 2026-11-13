package com.screentrace.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme
import com.screentrace.app.theme.ThemeVariant

/**
 * §5.5 界面风格 Modal Bottom Sheet（VI-1-4 升级为**圆形色板选择器**）：
 * 九套预设各一格主色实心圆 + 自定义主题一格；选中态描边标识；**点选即生效**
 * （实时预览，无确认步骤）；深浅色与动态取色在设置页配置（§5.5）。
 *
 * 自定义格仅作展示与回显（种子的修改入口在设置页「自定义主题」取色器）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyleSheet(
    current: ThemeVariant,
    customSeedActive: Boolean = false,
    onSelect: (ThemeVariant) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalFreshPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = "界面风格", style = FreshType.sectionTitle, color = palette.onSurface)
            Text(
                text = "点选即生效；自定义主题的修改入口在「设置 → 自定义主题」。",
                style = FreshType.label,
                color = palette.onSurfaceVariant
            )
            val entries = ThemeVariant.values().toList()
            entries.chunked(5).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowItems.forEach { variant ->
                        StyleSwatch(
                            label = variant.label,
                            seed = variant.seed,
                            selected = variant == current && !customSeedActive,
                            onClick = { onSelect(variant) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            if (customSeedActive) {
                // 自定义主题格：显示为多彩圆点（种子色的派生示意），选中态描边
                Row(modifier = Modifier.fillMaxWidth()) {
                    StyleSwatch(
                        label = "自定义",
                        custom = true,
                        selected = true,
                        onClick = { /* 种子修改在设置页；此处仅回显 */ },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** 一格色板：主色实心圆（自定义为多彩圆）+ 选中描边 + 名称。 */
@Composable
private fun StyleSwatch(
    label: String,
    seed: Color? = null,
    custom: Boolean = false,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalFreshPalette.current
    Column(
        modifier = modifier
            .semantics {
                contentDescription = if (selected) "$label，当前风格" else label
                role = Role.Button
            }
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .border(
                    width = if (selected) 2.dp else 0.dp,
                    color = if (selected) palette.onSurface else Color.Transparent,
                    shape = CircleShape
                )
                .padding(3.dp) // 描边与色板之间留缝
                .clip(CircleShape)
                .background(
                    if (custom) {
                        Brush.linearGradient(listOf(palette.chart1, palette.chart3, palette.chart5))
                    } else {
                        // 预设格是「主色实心圆」。这里原来写的是
                        // Brush.linearGradient(listOf(seed))——单色渐变在 Compose 会抛
                        // "colors must have length of at least 2"，导致一打开主题弹层就崩
                        // （真机 11:41 复现，设置页入口同样受影响）。用 SolidColor 才符合规格。
                        SolidColor(seed ?: palette.primary)
                    }
                )
        )
        Text(
            text = label,
            style = FreshType.label,
            color = if (selected) palette.onSurface else palette.onSurfaceVariant
        )
    }
}

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun StyleSheetPreview() {
    ScreenTimeTheme {
        Column(modifier = Modifier.padding(20.dp)) {
            ThemeVariant.values().toList().chunked(5).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowItems.forEach { v ->
                        StyleSwatch(
                            label = v.label,
                            seed = v.seed,
                            selected = v == ThemeVariant.MINT,
                            onClick = {},
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
