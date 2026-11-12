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
 * §5.2（CR-06 修订）：左标题 + 日期副标题；右侧动作按钮「日期 / 设置」两枚，
 * 视觉 40dp、点击区 ≥48dp。风格切换曾占第三枚按钮但一个月点不了两次，
 * VI-1-4 收敛掉——入口保留在设置页「外观 → 界面风格」（色块网格）。
 * 主页面无返回键；二级页传 onBack 显示「返回 + 标题」（§5.2）。
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
                onSettingsClick = {}
            )
            AppHeader(title = "全部应用", subtitle = null, onBack = {}, onDateClick = {})
        }
    }
}
