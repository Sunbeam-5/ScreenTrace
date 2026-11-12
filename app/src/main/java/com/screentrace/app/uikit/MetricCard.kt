package com.screentrace.app.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.graphics.vector.ImageVector
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
    clickDescription: String? = null,
    /** CR-07/§4.1：左上 34×34 白 55% 透明图标底块（可下钻卡：解锁挂锁 / 通知铃铛）。 */
    icon: ImageVector? = null,
    /** 统计型卡（最久/最少/低于阈值）：数字 28sp、最小高 104（§4.1）。 */
    compact: Boolean = false
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
    // CR-07/§4.1：可下钻卡最小高 124、统计型 104（heightIn 不写死，1.3 倍字体可增高）
    val minH = if (onClick != null) 124.dp else if (compact) 104.dp else 0.dp
    val base = if (onClick == null) {
        modifier
            .heightIn(min = minH)
            .clip(RoundedCornerShape(24.dp))
            .background(background)
    } else {
        modifier
            .heightIn(min = minH)
            // 容器变换起点：整卡的矩形与圆角（§5.5）。只在主页面层生效力，
            // 覆盖层里 CompositionLocal 为 null 时这个修饰符是空操作。
            .transformSource(cornerRadius = 24.dp)
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

    Column(
        modifier = base.padding(16.dp), // §5.2：tonal 卡内边距 16dp（返修 P2-3）
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // CR-07/§4.1：可下钻卡顶行 = 左上图标底块 + 右上 18dp 箭头
        if (icon != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.onSurface.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = palette.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (onClick != null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = palette.mutedOn(background),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Text(text = label, style = FreshType.label, color = contentColor)
        MetricValueText(
            value = value,
            numberColor = palette.onSurface,
            unitColor = palette.mutedOn(background),
            numberFontSize = if (compact) 28 else 0
        )
        if (note != null) {
            if (noteAsCompare) {
                CompareLine(text = note, up = noteUp)
            } else {
                Text(text = note, style = FreshType.label, color = contentColor)
            }
        }
        if (icon == null && onClick != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = palette.mutedOn(background),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * §5.7 tonal 双卡：等宽等高并排（Secondary 左 + Primary 右），间距 12dp（§5.2）。
 * 高度取两卡最大值，避免长说明把两卡拉得一高一低。
 *
 * CR-01：两卡可独立下钻（[leftOnClick] / [rightOnClick]），样式必须一致——
 * 「同类能力必须有相同表现」（§5.5）；任一为 null 时该卡保持纯摘要样式。
 *
 * **等高不能用 `Modifier.height(IntrinsicSize.Min)`**（真机首装崩溃的根因）：
 * 卡片内的 [MetricValueText] 走 `BoxWithConstraints` 做字号自适应，而
 * `BoxWithConstraints` 本身是 `SubcomposeLayout`，Compose **禁止**对它求 intrinsic
 * 尺寸，一量就抛 `IllegalStateException: Asking for intrinsic measurements of
 * SubcomposeLayout layouts is not supported`。这个组合只在「有数据渲染双卡」时触发，
 * 空态不渲染双卡，所以单测与空库冒烟都发现不了；修法在 [MetricText] 一侧（让它支持 intrinsic 查询）。
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
    rightClickDescription: String? = null,
    leftIcon: ImageVector? = null,
    rightIcon: ImageVector? = null
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
            icon = leftIcon,
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
            icon = rightIcon,
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
