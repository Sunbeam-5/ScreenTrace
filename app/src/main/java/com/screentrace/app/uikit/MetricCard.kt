package com.screentrace.app.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme

/** tonal 双卡的两个变体（§5.5 MetricCard / §5.7 tonal 双卡）。 */
enum class MetricCardVariant { Secondary, Primary }

/**
 * §5.5：MetricCard 固定「标签 / 数值 / 可选说明」结构，24dp 无描边卡片（§5.2）。
 * 数值用 FreshType.metricNumber；不设固定高度，1.3 倍字体允许增高（§5.9）。
 */
@Composable
fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    variant: MetricCardVariant = MetricCardVariant.Secondary
) {
    val palette = LocalFreshPalette.current
    val background = when (variant) {
        MetricCardVariant.Secondary -> palette.secondaryContainer
        MetricCardVariant.Primary -> palette.primaryContainer
    }
    val contentColor = when (variant) {
        MetricCardVariant.Secondary -> palette.onSecondaryContainer
        MetricCardVariant.Primary -> palette.onPrimaryContainer
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(background)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = label, style = FreshType.label, color = contentColor)
        Text(text = value, style = FreshType.metricNumber, color = contentColor)
        if (note != null) {
            Text(text = note, style = FreshType.label, color = contentColor)
        }
    }
}

/**
 * §5.7 tonal 双卡：等宽等高并排（Secondary 左 + Primary 右），间距 12dp（§5.2）。
 * 高度取两卡 intrinsic 最大值，避免长说明把两卡拉得一高一低。
 */
@Composable
fun MetricCardPair(
    leftLabel: String,
    leftValue: String,
    rightLabel: String,
    rightValue: String,
    modifier: Modifier = Modifier,
    leftNote: String? = null,
    rightNote: String? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MetricCard(
            label = leftLabel,
            value = leftValue,
            note = leftNote,
            variant = MetricCardVariant.Secondary,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
        MetricCard(
            label = rightLabel,
            value = rightValue,
            note = rightNote,
            variant = MetricCardVariant.Primary,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
    }
}

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun MetricCardPreview() {
    ScreenTimeTheme {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricCardPair(
                leftLabel = "解锁次数",
                leftValue = "—",
                rightLabel = "最长连续",
                rightValue = "—",
                leftNote = "空态示例：数值由数据层提供",
                rightNote = null
            )
        }
    }
}
