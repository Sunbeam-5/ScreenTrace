package com.screentrace.app.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.screentrace.app.theme.LocalFreshPalette

/**
 * CR-06 / VI-1-2：统一的 surface 卡片容器——24dp 圆角 + surface 底 + 0–2dp 极淡阴影。
 *
 * 阴影**只在浅色生效**（深色 elevation 归零：深色下阴影只会糊，分层靠
 * surfaceVariant 与 surface 的色阶差，§5.2 CR-06 修订）。tonal 卡不在此列
 * （MetricCard 自管容器），这里只管普通 surface 卡。
 *
 * 抽出来的动机：这套样式此前在四页里重复 18+ 处，改阴影/圆角要改 18 处。
 */
@Composable
fun FreshCard(
    modifier: Modifier = Modifier,
    padding: Dp = 20.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: androidx.compose.ui.Alignment.Horizontal = androidx.compose.ui.Alignment.Start,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = LocalFreshPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (palette.darkTheme) 0.dp else 2.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = palette.shadow,
                spotColor = palette.shadow
            )
            .clip(RoundedCornerShape(24.dp))
            .background(palette.surface)
            .padding(padding),
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        content = content
    )
}
