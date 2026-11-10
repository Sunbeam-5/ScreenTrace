package com.screentrace.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * §5.5 界面风格 Modal Bottom Sheet：3×3 色点网格，色点下显示名称，
 * 当前项用「勾选 + 文字」双标识；切换只替换调色板（§5.4：不重拉数据、不改筛选）。
 * 深浅色与动态取色在设置页配置（§5.5），本弹层只管九套主题。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyleSheet(
    current: ThemeVariant,
    onSelect: (ThemeVariant) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalFreshPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = palette.surface,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 12.dp)
                    .width(32.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(palette.onSurfaceVariant)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = "界面风格", style = FreshType.sectionTitle, color = palette.onSurface)
            ThemeVariant.values().toList().chunked(3).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowItems.forEach { variant ->
                        StyleDot(
                            variant = variant,
                            selected = variant == current,
                            onClick = { onSelect(variant) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/** 单个色点项：色点 + 名称；当前项勾选 + 主色文字（双标识，§5.5）。 */
@Composable
private fun StyleDot(
    variant: ThemeVariant,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalFreshPalette.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) palette.primaryContainer else palette.surfaceVariant)
            .semantics {
                contentDescription = if (selected) "${variant.label}，当前风格" else variant.label
                role = Role.Button
            }
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(variant.seed)
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = palette.onPrimaryContainer,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(16.dp)
                )
            }
        }
        Text(
            text = variant.label,
            style = FreshType.label,
            color = if (selected) palette.onPrimaryContainer else palette.onSurfaceVariant
        )
    }
}

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun StyleSheetPreview() {
    ScreenTimeTheme {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 3×3 网格预览（弹层内容等价）
            ThemeVariant.values().toList().chunked(3).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowItems.forEach { variant ->
                        StyleDot(
                            variant = variant,
                            selected = variant == ThemeVariant.MINT,
                            onClick = {},
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
