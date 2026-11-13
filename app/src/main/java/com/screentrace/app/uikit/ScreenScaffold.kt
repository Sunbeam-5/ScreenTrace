package com.screentrace.app.uikit

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme

/**
 * 页面骨架（批 6g 重构）：背景 + 状态栏内边距 + 顶部栏 + **占满剩余高度的内容区**。
 *
 * 与旧版的关键区别：这里**不再自带纵向滚动**。旧版把「顶部栏以下的所有东西」包在
 * 一个 `verticalScroll` 里，于是三个主页面只能共用一个 `ScrollState`——
 * 从「今日」滚到一半切到「统计」，统计页会直接从同样的偏移开始画，看起来就像
 * 内容突然蹦出来。现在滚动下沉到 [ScrollablePage]，每个页面自己一份滚动状态，
 * Pager 里多页并存时互不影响，返回上级页也天然保住原位置。
 *
 * 底部悬浮导航不在这里，由 `AppNav` 画在最外层（要压在详情覆盖层之上）。
 */
@Composable
fun PageScaffold(
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val palette = LocalFreshPalette.current
    // 背景铺满整个 Box（含状态栏后面），内容侧才做 statusBarsPadding：
    // 否则状态栏那一条会露出系统底色（§「上面有一条黑边」的根因）。
    Box(
        modifier = modifier
            .fillMaxSize()
            // CR-07：三段渐变（0% 顶部 / 18% 中段 / 40% 起落回 background），
            // 背景固定在屏幕后，不随内容滚动
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to palette.gradientTop,
                    0.18f to palette.gradientMid,
                    0.40f to palette.background
                )
            )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            header()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                content()
            }
        }
    }
}

/**
 * 可滚动内容页：左右边距 20dp、卡片间距 12dp、底部预留 112dp 给悬浮导航；
 * 宽屏内容最大 520dp 居中（§5.2）。
 *
 * `fillMaxSize` + `verticalScroll` 的组合让页面高度等于视口、内容超出才滚，
 * 这样它放进 Pager 的每一页时尺寸稳定，不会因为内容长短把 Pager 撑变形。
 */
@Composable
fun ScrollablePage(
    modifier: Modifier = Modifier,
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
            // 底部预留 = 悬浮导航高度 + 系统手势条。只留 112dp 时，全面屏机型上
            // 手势条会把「查看全部应用」这类最后一屏内容压住（真机可见）。
            // 【Step 7 复核结论 2026-11-13，Redmi 1220×2712】此值**足够，不要再加大**：
            // 滚到底时「查看全部应用」bottom=2181px，导航胶囊 top≈2400px，末尾内容已在胶囊上方；
            // 首屏看到日间/夜间行与胶囊重叠，是「固定悬浮覆盖层 + 长内容滚动前自然交叠」，
            // 不是 viewport / padding 缺陷。加留白只会让首屏更空、滚到底拖出一大片空白。
            // 观感由 §4.6 的胶囊质感（92% 白 + 描边 + 阴影）解决，不靠堆 padding。
            .padding(bottom = 112.dp)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

/**
 * §5.2（CR-10 终态）：左标题 + 日期副标题；右侧固定「日期 / 主题 / 设置」三枚圆按钮，
 * 视觉 40dp、点击区 ≥48dp。主题按钮居中，三色圆点自绘（不引 icons-extended）；
 * 两处主题入口（顶栏 / 设置页）共用 AppNav 全局 `showStyleSheet` 的同一个 `StyleSheet`。
 * 主页面无返回键；二级页传 onBack 显示「返回 + 标题」，且不传 `onStyleClick`（§5.2）。
 * 未传入的按钮不渲染——调用方只接线真实可用的动作，避免假按钮（§6）。
 *
 * 标题与副标题走 `Crossfade`：主页面之间横滑时标题是跟着换的，
 * 硬切会让顶部像"被抽掉再贴上一张"，交叉淡入淡出才跟得上页面滑动。
 */
@Composable
fun AppHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    onDateClick: (() -> Unit)? = null,
    onStyleClick: (() -> Unit)? = null,
    onSettingsClick: (() -> Unit)? = null
) {
    val palette = LocalFreshPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 8.dp), // §5.2：与内容间隔 8dp
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            HeaderActionButton(contentDescription = "返回", onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = palette.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (onBack != null) 4.dp else 0.dp)
        ) {
            Crossfade(
                targetState = title,
                animationSpec = tween(180),
                label = "headerTitle"
            ) { text ->
                Text(text = text, style = FreshType.pageTitle, color = palette.onSurface)
            }
            if (subtitle != null) {
                Crossfade(
                    targetState = subtitle,
                    animationSpec = tween(180),
                    label = "headerSubtitle"
                ) { text ->
                    Text(text = text, style = FreshType.label, color = palette.onSurfaceVariant)
                }
            }
        }
        if (onDateClick != null) {
            HeaderActionButton(contentDescription = "选择日期", onClick = onDateClick) {
                Icon(
                    imageVector = Icons.Default.DateRange,
                    contentDescription = null,
                    tint = palette.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        if (onStyleClick != null) {
            HeaderActionButton(contentDescription = "界面风格", onClick = onStyleClick) {
                ThemeDotsIcon()
            }
        }
        if (onSettingsClick != null) {
            HeaderActionButton(contentDescription = "设置", onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = palette.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * CR-10 / §4.9：主题按钮的「三色圆点」线性图标。
 * icons-core 没有调色板图标，不为此引入 icons-extended；三个点取主色色阶的深/中/浅，
 * 与「一个主色派生全套色阶」的既有原则一致。
 */
@Composable
private fun ThemeDotsIcon() {
    val palette = LocalFreshPalette.current
    Canvas(modifier = Modifier.size(22.dp)) {
        val radius = 3.4.dp.toPx()
        val cy = size.height / 2f
        val colors = listOf(palette.primary, palette.chart2, palette.chart4)
        val xs = listOf(size.width * 0.2f, size.width * 0.5f, size.width * 0.8f)
        xs.forEachIndexed { i, x ->
            drawCircle(color = colors[i], radius = radius, center = Offset(x, cy))
        }
    }
}

/** §5.2：视觉 40dp 圆形按钮、点击区 48dp；语义 token 无硬编码色。 */
@Composable
private fun HeaderActionButton(
    contentDescription: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    val palette = LocalFreshPalette.current
    Box(
        modifier = Modifier
            .size(48.dp)
            // 容器变换起点：这个按钮的矩形与圆形圆角（§5.5）。覆盖层里的按钮
            // 读不到 Provider（为 null），因此不会把自己登记成新起点。
            .transformSource(cornerRadius = 24.dp)
            .clip(CircleShape) // ripple 与点击区同为圆形
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(palette.buttonSurface),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
    }
}


@Preview(widthDp = 360, showBackground = true)
@Composable
private fun AppHeaderPreview() {
    ScreenTimeTheme {
        Column {
            AppHeader(
                title = "屏迹",
                subtitle = "今天 · 11 月 10 日",
                onDateClick = {},
                onStyleClick = {},
                onSettingsClick = {}
            )
            AppHeader(title = "全部应用", subtitle = null, onBack = {}, onDateClick = {})
        }
    }
}
