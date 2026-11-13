package com.screentrace.app.uikit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme

/** CR-07 / Step 7：线性导航图标种类（自绘，不引入 icons-extended）。 */
enum class NavIconKind { TODAY, STATS, CALENDAR }

data class NavItem(
    val label: String,
    val kind: NavIconKind
)

/**
 * §5.5 / 规格 §4.6：底部悬浮胶囊导航。结构、尺寸、spring 展开动画、点击区域全部保留
 * 原实现（52dp 图标槽、选中项展开到「图标+文字实测宽度」、项间距 4、容器内边距 6），
 * Step 7 只换质感：白色 92% 容器 + 1dp 主色 10% 描边 + 淡主色阴影 + 线性图标。
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
            // §4.6：阴影 `0 10 30 rgba(20,38,47,.14)` 的近似值（用既有 shadow token，
            // 不引入模糊库）；形状与内层的胶囊裁切保持一致
            .shadow(
                elevation = 10.dp,
                shape = CircleShape,
                ambientColor = palette.shadow,
                spotColor = palette.shadow
            )
            .clip(CircleShape)
            // 白色 92%（深色模式退化为 surface 92%，Step 9 再逐套巡检）
            .background(palette.surface.copy(alpha = 0.92f))
            // 1dp 主色 10% 描边：让半透明胶囊在浅色卡片上也有清晰边界
            .border(1.dp, palette.primary.copy(alpha = 0.1f), CircleShape)
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
                    // 选中时名称由 Text 播报，图标不再重复（§5.9）
                    LinearNavIcon(
                        kind = item.kind,
                        tint = content,
                        contentDescription = if (selected) null else item.label
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

/**
 * 规格 §4.6 的线性图标：今日=房子、统计=三根柱、日历=日历。
 * 24 视口路径按 Canvas 尺寸等比缩放（与 FlameIcon 同一策略），描边随缩放同步变粗。
 */
@Composable
private fun LinearNavIcon(
    kind: NavIconKind,
    tint: Color,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .size(22.dp)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            )
    ) {
        val s = size.width / 24f
        val stroke = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        scale(s, s, pivot = Offset.Zero) {
            when (kind) {
                NavIconKind.TODAY -> {
                    // 屋顶 + 墙体 + 门
                    drawLine(tint, Offset(3f, 11f), Offset(12f, 4f), 1.8f, StrokeCap.Round)
                    drawLine(tint, Offset(12f, 4f), Offset(21f, 11f), 1.8f, StrokeCap.Round)
                    drawLine(tint, Offset(5.5f, 10f), Offset(5.5f, 20f), 1.8f, StrokeCap.Round)
                    drawLine(tint, Offset(18.5f, 10f), Offset(18.5f, 20f), 1.8f, StrokeCap.Round)
                    drawLine(tint, Offset(5.5f, 20f), Offset(18.5f, 20f), 1.8f, StrokeCap.Round)
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(10f, 14f),
                        size = Size(4f, 6f),
                        cornerRadius = CornerRadius(0.8f, 0.8f),
                        style = stroke
                    )
                }
                NavIconKind.STATS -> {
                    // 三根不同高度的柱（描边矩形，线性图标）
                    listOf(5f to 7f, 11f to 11f, 17f to 15f).forEach { (x, h) ->
                        drawRoundRect(
                            color = tint,
                            topLeft = Offset(x - 1.8f, 20f - h),
                            size = Size(3.6f, h),
                            cornerRadius = CornerRadius(0.8f, 0.8f),
                            style = stroke
                        )
                    }
                    drawLine(tint, Offset(3f, 20f), Offset(21f, 20f), 1.8f, StrokeCap.Round)
                }
                NavIconKind.CALENDAR -> {
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(3f, 5f),
                        size = Size(18f, 15f),
                        cornerRadius = CornerRadius(2f, 2f),
                        style = stroke
                    )
                    drawLine(tint, Offset(3f, 9.5f), Offset(21f, 9.5f), 1.8f, StrokeCap.Round)
                    drawLine(tint, Offset(8f, 3f), Offset(8f, 6f), 1.8f, StrokeCap.Round)
                    drawLine(tint, Offset(16f, 3f), Offset(16f, 6f), 1.8f, StrokeCap.Round)
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
                NavItem("今日", NavIconKind.TODAY),
                NavItem("统计", NavIconKind.STATS),
                NavItem("日历", NavIconKind.CALENDAR)
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
                NavItem("今日", NavIconKind.TODAY),
                NavItem("统计", NavIconKind.STATS),
                NavItem("日历", NavIconKind.CALENDAR)
            ),
            selectedIndex = 1,
            onSelect = {},
            modifier = Modifier.padding(20.dp)
        )
    }
}
