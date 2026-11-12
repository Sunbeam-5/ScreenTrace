package com.screentrace.app.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.screentrace.app.contract.UsageCalculator
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme

/**
 * CR-07 / 新规格 §4.3 状态标签（Chip）：高 28、完全圆角、水平内边距 10、12sp SemiBold。
 * 三种：超目标（暖珊瑚）、中性对比（带箭头）、下降（绿、带箭头）。
 * **箭头方向代表增减，颜色只是辅助**（§5.4：不能只靠颜色区分）。
 */
enum class ChipKind { OVER, NEUTRAL, DOWN }

@Composable
fun StatusChip(
    text: String,
    kind: ChipKind,
    arrowUp: Boolean? = null,
    modifier: Modifier = Modifier
) {
    val palette = LocalFreshPalette.current
    val (bg, fg) = when (kind) {
        ChipKind.OVER -> palette.overTargetSurface to palette.overTarget
        ChipKind.NEUTRAL -> palette.chipSurface to palette.chipOn
        ChipKind.DOWN -> palette.downSurface to palette.downOn
    }
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 28.dp)
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (arrowUp != null) {
            Icon(
                imageVector = if (arrowUp) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null, // 方向由文案「增加/减少」承载（§5.9）
                tint = fg,
                modifier = Modifier.size(12.dp)
            )
        }
        Text(text = text, style = FreshType.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = fg)
    }
}

/**
 * §5.3 对比行（CR-07 改为 chip 形态）：increase=true 中性 chip + ↑；false 下降绿 chip + ↓；
 * null（持平）中性 chip 无箭头。**没有对比数据时不显示**——调用方先问 [shouldShowCompare]。
 */
@Composable
fun CompareLine(
    text: String,
    up: Boolean?,
    modifier: Modifier = Modifier
) {
    val kind = when (up) {
        true -> ChipKind.NEUTRAL
        false -> ChipKind.DOWN
        null -> ChipKind.NEUTRAL
    }
    StatusChip(text = text, kind = kind, arrowUp = up, modifier = modifier)
}

/** VI-3.1：无对比数据时整个标签不显示（不占行、不写「暂无上一周期数据」）。 */
fun shouldShowCompare(info: UsageCalculator.CompareInfo): Boolean =
    info.text != "暂无上一周期数据"

/** 「超出目标」chip 文案与显隐（§5.7-4：目标 ≤0 或未超目标时不显示）。 */
fun shouldShowOverChip(usageMinutes: Int, goalMinutes: Int): Boolean =
    goalMinutes > 0 && usageMinutes > goalMinutes

fun overTargetChipText(overMinutes: Int): String =
    "超出目标 ${UsageCalculator.formatDuration(overMinutes)}"

@Preview(showBackground = true)
@Composable
private fun StatusChipPreview() {
    ScreenTimeTheme {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusChip("超出目标 1小时42分", ChipKind.OVER)
            StatusChip("较昨日 12 分钟", ChipKind.NEUTRAL, arrowUp = true)
            StatusChip("较上周 24 分钟", ChipKind.DOWN, arrowUp = false)
        }
    }
}
