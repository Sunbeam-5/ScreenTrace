package com.screentrace.app.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme

/**
 * §5.5：全 App 统一空态。禁止白屏、禁止把无数据伪装成真实的 0 分钟——
 * 空态文案必须让人看懂「没有数据」而不是「数据是零」。
 *
 * [actionText] + [onAction] 只给「空态里其实还有别处能看的东西」用
 * （如降级通道下今日页空态、指向应用排行），默认不显示按钮。
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    val palette = LocalFreshPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text = title, style = FreshType.sectionTitle, color = palette.onSurface)
        if (description != null) {
            Text(
                text = description,
                style = FreshType.body,
                color = palette.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        if (actionText != null && onAction != null) {
            SecondaryButton(text = actionText, onClick = onAction)
        }
    }
}

/**
 * §5.5：加载用骨架（保留原布局占位）。静态色块，不播放无限动画
 * （§5.9 尊重减少动画；如需动态 shimmer 另行评审）。
 */
@Composable
fun LoadingSkeleton(
    modifier: Modifier = Modifier,
    blocks: Int = 3
) {
    val palette = LocalFreshPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(blocks) { index ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (index == blocks - 1) 0.55f else 1f)
                    .height(20.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.surfaceVariant)
            )
        }
    }
}

/** §5.5：错误用文字 + 重试按钮，不用只弹 Toast 的形式。 */
@Composable
fun ErrorRetry(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalFreshPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = message,
            style = FreshType.body,
            color = palette.error,
            textAlign = TextAlign.Center
        )
        SecondaryButton(text = "重试", onClick = onRetry)
    }
}

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun StateViewsPreview() {
    ScreenTimeTheme {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EmptyState(
                title = "尚无使用记录",
                description = "连接 Shizuku 并完成首次拉取后，这里会显示真实的使用数据"
            )
            LoadingSkeleton()
            ErrorRetry(message = "拉取失败：Shizuku 未授权", onRetry = {})
        }
    }
}
