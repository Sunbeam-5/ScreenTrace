package com.screentrace.app.uikit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
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
 * §5.5：底部悬浮胶囊导航。选中项 spring 展开 52dp→「图标+文字实测宽度」，未选中 52dp 仅图标；
 * 项间距 4dp、容器内边距 6dp、点击区 ≥48dp（高 50 / 宽 ≥52）。
 *
 * ### 批 6g：为什么不再用「宽度阈值切文字」
 *
 * 上一版是 `if (expand > 0.45f) { Text(...) }` —— 二元判断，文字在某一帧**整块出现**，
 * 宽度还在继续弹，于是看起来像文字"啪"地贴上去，这是「切换僵硬」的一半来源。
 * 另一半是选中底色 `if (selected) primary else Transparent` 硬切，没有任何过渡。
 *
 * 现在：
 * - 图标放在**固定 52dp 槽位**（`width`），标签放在**文字实测宽度**的槽位
 *   （`requiredWidth`，故意不受父约束，多出来的部分被外层 `clip(CircleShape)` 裁掉）——
 *   胶囊变宽时标签是"被擦出来"的，不是"跳出来"的，且图标不会横向乱跑。
 *   之前标签槽固定 98dp，「今日」两个字只占一半，胶囊右侧一大截空白（真机反馈 #1）。
 * - 标签 alpha 由同一个 `expand` 驱动（0.35→1 区间渐变），文字和宽度同步，不会先宽后亮。
 * - 底色/前景色走 `animateColorAsState`，选中态换色也是渐变的。
 *
 * 系统「减少动画」（`ANIMATOR_DURATION_SCALE = 0`）时宽度与颜色都直接到终态（§5.7）。
 */
@Composable
fun FreshNavBar(
    items: List<NavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalFreshPalette.current
    val reduceMotion = rememberReduceMotion()
    val widthSpec: AnimationSpec<Float> = if (reduceMotion) tween(0) else spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
    val colorSpec = tween<Color>(if (reduceMotion) 0 else 200)
    // 每个标签的文字实测宽度：胶囊展开宽度 = 图标槽 + 起始间距 + 文字宽 + 尾部留白，
    // 跟着字数走，「今日」和「日历」展开后一样紧凑，不会有一边特别空
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelWidths = remember(items, density.fontScale) {
        items.map { item ->
            with(density) {
                textMeasurer
                    .measure(AnnotatedString(item.label), style = FreshType.button, maxLines = 1)
                    .size
                    .width
                    .toDp()
            }
        }
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
                targetValue = if (selected) 1f else 0f,
                animationSpec = widthSpec,
                label = "navExpand"
            )
            val container by animateColorAsState(
                targetValue = if (selected) palette.primary else Color.Transparent,
                animationSpec = colorSpec,
                label = "navContainer"
            )
            val content by animateColorAsState(
                targetValue = if (selected) palette.onPrimary else palette.primary,
                animationSpec = colorSpec,
                label = "navContent"
            )
            // 标签 0.35→1 区间淡入：等胶囊张开一点再显字，开头那一下不会有半个字挤在边上
            val labelAlpha = ((expand - 0.35f) / 0.65f).coerceIn(0f, 1f)
            // 展开总宽 = 52(图标槽) + 10(图标与文字间距) + 文字宽 + 16(尾部留白)
            val expandedWidth = 52.dp + 10.dp + labelWidths[index] + 16.dp

            Row(
                modifier = Modifier
                    .height(50.dp)
                    .width(52.dp + (expandedWidth - 52.dp) * expand)
                    .clip(CircleShape) // 兼作标签的裁剪框
                    .background(container)
                    .selectable(selected = selected, role = Role.Tab) { onSelect(index) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(52.dp)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = item.icon,
                        // 选中时名称由 Text 播报，图标不再重复（§5.9）
                        contentDescription = if (selected) null else item.label,
                        tint = content,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        // requiredWidth 而不是 width：胶囊此刻可能只有 52dp 宽，普通 width
                        // 会被父约束压小，文字就会在窄框里折行；这里要的是"溢出后被裁掉"
                        .requiredWidth(10.dp + labelWidths[index])
                        .fillMaxHeight()
                        .graphicsLayer { alpha = labelAlpha },
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = item.label,
                        color = content,
                        style = FreshType.button,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
            }
        }
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
