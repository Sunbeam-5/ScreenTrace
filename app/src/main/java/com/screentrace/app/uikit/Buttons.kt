package com.screentrace.app.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme

/**
 * §5.5：PrimaryButton / SecondaryButton 48dp 全胶囊。
 * 注意：每屏最多一个主按钮（§5.5），由调用方约束；次按钮不限。
 * 禁用态用 track 底 + onSurfaceVariant 字，不伪造可点击外观。
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val palette = LocalFreshPalette.current
    ButtonBox(
        text = text,
        onClick = onClick,
        enabled = enabled,
        background = if (enabled) palette.primary else palette.track,
        contentColor = if (enabled) palette.onPrimary else palette.onSurfaceVariant,
        modifier = modifier
    )
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val palette = LocalFreshPalette.current
    ButtonBox(
        text = text,
        onClick = onClick,
        enabled = enabled,
        background = if (enabled) palette.buttonSurface else palette.track,
        contentColor = if (enabled) palette.onSurface else palette.onSurfaceVariant,
        modifier = modifier
    )
}

@Composable
private fun ButtonBox(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    background: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier
) {
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp) // §5.5 48dp；1.3 倍字体允许增高（§5.9）
            .clip(CircleShape)
            .background(background)
            .semantics { role = Role.Button }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = FreshType.button,
            color = contentColor
        )
    }
}

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun ButtonsPreview() {
    ScreenTimeTheme {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
        ) {
            PrimaryButton(text = "开始使用", onClick = {}, modifier = Modifier.fillMaxWidth())
            SecondaryButton(text = "查看全部应用", onClick = {}, modifier = Modifier.fillMaxWidth())
            SecondaryButton(text = "禁用态", onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth())
        }
    }
}
