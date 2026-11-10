package com.screentrace.app.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme

/** tonal 双卡的两个变体（§5.5 MetricCard / §5.7 tonal 双卡）。 */
enum class MetricCardVariant { Secondary, Primary }

/**
 * §5.5：MetricCard 固定「标签 / 数值 / 可选一句说明」结构，24dp 无描边卡片（§5.2）。
 * 数值按 §5.3「数字 + 小单位」排版（[MetricValueText]）：数字用 onSurface，
 * 单位用 §5.4 规则相对卡片背景派生的单位色（[androidx.compose.ui.graphics.Color] 经
 * `palette.mutedOn(container)`）；说明为对比文案时（noteAsCompare）按 §5.3 对比行规格
 * 渲染（14sp/600 + 12dp 箭头 + primary）。不设固定高度，1.3 倍字体允许增高（§5.9）。
 *
 * CR-01 可下钻卡：传 [onClick] 才变成可点卡（右侧 12dp 箭头 + 整卡 ripple +
 * 按下缩放 0.98/80ms + `Role.Button` + 读屏「标签 + 当前值 + 查看详情」）。
 * **不传就是纯摘要卡**——没有详情页的卡片绝不能显示箭头或点击效果（§5.5 假按钮红线）。
 */
@Composable
fun MetricCard(
    label: String,
    value: MetricValue,
    modifier: Modifier = Modifier,
    note: String? = null,
    noteAsCompare: Boolean = false,
    noteUp: Boolean? = true,
    variant: MetricCardVariant = MetricCardVariant.Secondary,
    onClick: (() -> Unit)? = null,
    /** 读屏文案（「标签 + 当前值 + 查看详情」）；为 null 时退回默认拼接。 */
    clickDescription: String? = null
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

    val reduceMotion = rememberReduceMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) 0.98f else 1f,
        animationSpec = tween(durationMillis = 80),
        label = "metricCardScale"
    )

    // 缩放必须作用在**整卡**（含背景）：graphicsLayer 放在最外层，
    // 否则只有内容在缩、底板不动，按下去会露出边。
    val base = if (onClick == null) {
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(background)
    } else {
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(24.dp))
            .background(background)
            .semantics {
                role = Role.Button
                contentDescription = clickDescription ?: "$label 查看详情"
            }
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick
            )
    }

    Row(
        modifier = base.padding(16.dp), // §5.2：tonal 卡内边距 16dp（返修 P2-3）
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = label, style = FreshType.label, color = contentColor)
            MetricValueText(
                value = value,
                numberColor = palette.onSurface,
                unitColor = palette.mutedOn(background)
            )
            if (note != null) {
                if (noteAsCompare) {
                    CompareLine(text = note, up = noteUp)
                } else {
                    Text(text = note, style = FreshType.label, color = contentColor)
                }
            }
        }
        if (onClick != null) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = palette.mutedOn(background),
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(16.dp)
            )
        }
    }
}

/**
 * §5.7 tonal 双卡：等宽等高并排（Secondary 左 + Primary 右），间距 12dp（§5.2）。
 * 高度取两卡 intrinsic 最大值，避免长说明把两卡拉得一高一低。
 *
 * CR-01：两卡可独立下钻（[leftOnClick] / [rightOnClick]），样式必须一致——
 * 「同类能力必须有相同表现」（§5.5）；任一为 null 时该卡保持纯摘要样式。
 */
@Composable
fun MetricCardPair(
    leftLabel: String,
    leftValue: MetricValue,
    rightLabel: String,
    rightValue: MetricValue,
    modifier: Modifier = Modifier,
    leftNote: String? = null,
    rightNote: String? = null,
    leftNoteAsCompare: Boolean = false,
    rightNoteAsCompare: Boolean = false,
    leftNoteUp: Boolean? = true,
    rightNoteUp: Boolean? = true,
    leftOnClick: (() -> Unit)? = null,
    rightOnClick: (() -> Unit)? = null,
    leftClickDescription: String? = null,
    rightClickDescription: String? = null
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
            noteAsCompare = leftNoteAsCompare,
            noteUp = leftNoteUp,
            variant = MetricCardVariant.Secondary,
            onClick = leftOnClick,
            clickDescription = leftClickDescription,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
        MetricCard(
            label = rightLabel,
            value = rightValue,
            note = rightNote,
            noteAsCompare = rightNoteAsCompare,
            noteUp = rightNoteUp,
            variant = MetricCardVariant.Primary,
            onClick = rightOnClick,
            clickDescription = rightClickDescription,
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
                leftValue = MetricValue.Number("87"),
                rightLabel = "最长连续",
                rightValue = MetricValue.Duration(72),
                leftNote = "较昨日增加 6 次",
                leftNoteAsCompare = true,
                rightNote = "1小时12分钟",
                rightNoteAsCompare = true,
                rightNoteUp = true
            )
            MetricCard(
                label = "低于 2 小时",
                value = MetricValue.Number("4"),
                note = "共 7 个有记录日",
                variant = MetricCardVariant.Secondary
            )
        }
    }
}
