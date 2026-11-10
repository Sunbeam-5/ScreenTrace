// LEGACY / 参考起点 —— 来自 compose-uikit/，尚未按需求文档 v2.3 §5.4 / §6 整改。
// 已知差距：
//  1) 硬编码 FreshColors.* / Color.White / 十六进制色，须改为 LocalFreshPalette 语义 token；
//  2) 未支持九套主题与深色模式；
//  3) CapsuleBarChart 缺固定 32dp 刻度列、动态量程、零值区分与横向滚动（§5.7 / §7-8）；
//  4) HeatmapCalendar 仍是 12dp/4dp 且无点击详情，应为 9dp/3dp（§5.7）；
//  5) MonthCalendar 未保留连续组内每日独立色档，且有 week[i+k]!! 非空断言。
// 新页面请使用 theme/ 下的语义色板，不要直接把本包组件当作完成品。
//
package com.screentrace.app.uikit.legacy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class Segment(
    val weight: Float,
    val color: Color,
    val label: String = ""
)

@Composable
fun SegmentedBar(
    segments: List<Segment>,
    modifier: Modifier = Modifier,
    height: Dp = 34.dp,
    showLabels: Boolean = true
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().height(height),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            segments.forEach { segment ->
                Box(
                    modifier = Modifier
                        .weight(segment.weight)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50.dp))
                        .background(segment.color)
                )
            }
        }
        if (showLabels) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                segments.forEach { segment ->
                    Text(
                        text = segment.label,
                        fontSize = 11.sp,
                        color = FreshColors.InkSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun RatioBar(
    leftPercent: Int,
    modifier: Modifier = Modifier
) {
    val right = (100 - leftPercent).coerceIn(0, 100)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "$leftPercent%",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = FreshColors.GreenDeep
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .height(24.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(leftPercent.coerceAtLeast(1).toFloat())
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50.dp))
                    .background(FreshColors.GreenDeep)
            )
            Box(
                modifier = Modifier
                    .weight(right.coerceAtLeast(1).toFloat())
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50.dp))
                    .background(FreshColors.BlueDeep)
            )
        }
        Text(
            text = "$right%",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = FreshColors.GreenDeep
        )
    }
}
