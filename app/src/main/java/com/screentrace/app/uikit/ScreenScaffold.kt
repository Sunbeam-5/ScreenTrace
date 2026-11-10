package com.screentrace.app.uikit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
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
 * §5.2 页面骨架：ScreenScaffold + AppHeader + ContentColumn。
 * 左右边距 20dp、卡片间距 12dp、底部预留 112dp 给悬浮导航；宽屏内容最大 520dp 居中。
 * 悬浮导航经 floatingNav 槽位放入（由 ScreenScaffold 固定在底部中央）。
 */
@Composable
fun ScreenScaffold(
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    floatingNav: (@Composable () -> Unit)? = null,
    /** 由调用方持有滚动状态：二级页返回时才能把主页面滚回原处（§5.7）。 */
    scrollState: androidx.compose.foundation.ScrollState =
        androidx.compose.foundation.rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = LocalFreshPalette.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        Column(Modifier.fillMaxSize()) {
            header()
            ContentColumn(scrollState = scrollState, content = content)
        }
        if (floatingNav != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
            ) {
                floatingNav()
            }
        }
    }
}

/** §5.2：可滚动内容列，卡片间距 12dp，宽屏 520dp 居中，底部预留 112dp。 */
@Composable
fun ContentColumn(
    modifier: Modifier = Modifier,
    scrollState: androidx.compose.foundation.ScrollState =
        androidx.compose.foundation.rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
            .padding(bottom = 112.dp),
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
 * §5.2：左标题 + 日期副标题；右侧动作按钮「日期 / 界面风格 / 设置」视觉 40dp、点击区 ≥48dp。
 * 主页面无返回键；二级页传 onBack 显示「返回 + 标题」（§5.2）。
 * 未传入的按钮不渲染——调用方只接线真实可用的动作，避免假按钮（§6）。
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
            Text(text = title, style = FreshType.pageTitle, color = palette.onSurface)
            if (subtitle != null) {
                Text(text = subtitle, style = FreshType.label, color = palette.onSurfaceVariant)
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
                StyleDotsIcon()
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

/**
 * 「界面风格」图标：三色点（呼应 §5.5 风格面板的 3×3 色点），
 * material-icons-core 无 Palette 图标，且 §6 依赖白名单不允许加图标库，故自绘。
 */
@Composable
private fun StyleDotsIcon() {
    val palette = LocalFreshPalette.current
    Canvas(Modifier.size(22.dp)) {
        val r = size.minDimension * 0.19f
        drawCircle(palette.chart1, r, Offset(size.width * 0.30f, size.height * 0.32f))
        drawCircle(palette.chart3, r, Offset(size.width * 0.70f, size.height * 0.32f))
        drawCircle(palette.chart5, r, Offset(size.width * 0.30f, size.height * 0.70f))
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
