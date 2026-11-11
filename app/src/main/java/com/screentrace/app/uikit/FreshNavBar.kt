package com.screentrace.app.uikit

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme

data class NavItem(
    val label: String,
    val icon: ImageVector
)

/**
 * §5.5：底部悬浮胶囊导航。选中项 spring 展开 52dp→150dp 显名称，未选中 52dp 仅图标；
 * 项间距 4dp、容器内边距 6dp、点击区 ≥48dp（高 50 / 宽 ≥52）。
 * 系统「减少动画」（ANIMATOR_DURATION_SCALE=0）时改为不展开宽度、名称直接显隐（淡入淡出由
 * Compose 默认切换完成），不再播放 spring。
 * 三项「今日 / 统计 / 日历」由调用方传入；颜色全部走语义 token（§5.4）。
 */
@Composable
fun FreshNavBar(
    items: List<NavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalFreshPalette.current
    val context = LocalContext.current
    val reduceMotion = remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) <= 0f
        }.getOrDefault(false)
    }
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(palette.navSurface)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEachIndexed { index, item ->
            val selected = index == selectedIndex
            val expand by animateFloatAsState(
                // 减少动画时宽度仍要到 150dp，只是不播 spring（§5.5「改淡入淡出」）
                targetValue = if (selected) 1f else 0f,
                animationSpec = if (reduceMotion) {
                    tween(0)
                } else {
                    spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                },
                label = "navExpand"
            )
            val tint = if (selected) palette.onPrimary else palette.primary
            Row(
                modifier = Modifier
                    .height(50.dp)
                    .width(52.dp + 98.dp * expand) // 选中恰为 150dp
                    .clip(CircleShape)
                    .background(if (selected) palette.primary else Color.Transparent)
                    .selectable(selected = selected, role = Role.Tab) { onSelect(index) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = item.icon,
                    // 选中时名称由 Text 播报，图标不再重复（§5.9）
                    contentDescription = if (selected) null else item.label,
                    tint = tint,
                    modifier = Modifier.size(22.dp)
                )
                if (reduceMotion) {
                    AnimatedVisibility(
                        visible = selected,
                        enter = fadeIn(tween(120)),
                        exit = fadeOut(tween(120))
                    ) {
                        NavBarLabel(item.label, tint)
                    }
                } else if (expand > 0.45f) {
                    NavBarLabel(item.label, tint)
                }
            }
        }
    }
}

@Composable
private fun NavBarLabel(label: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            color = tint,
            style = FreshType.button
        )
    }
}

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun FreshNavBarPreview() {
    ScreenTimeTheme {
        FreshNavBar(
            items = listOf(
                NavItem("今日", Icons.Default.Home),
                NavItem("统计", Icons.AutoMirrored.Filled.List),
                NavItem("日历", Icons.Default.DateRange)
            ),
            selectedIndex = 0,
            onSelect = {},
            modifier = Modifier.padding(20.dp)
        )
    }
}

@Preview(widthDp = 360, showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FreshNavBarDarkPreview() {
    ScreenTimeTheme(darkTheme = true) {
        FreshNavBar(
            items = listOf(
                NavItem("今日", Icons.Default.Home),
                NavItem("统计", Icons.AutoMirrored.Filled.List),
                NavItem("日历", Icons.Default.DateRange)
            ),
            selectedIndex = 1,
            onSelect = {},
            modifier = Modifier.padding(20.dp)
        )
    }
}
