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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HeatmapCalendar(
    levels: List<Int>,
    modifier: Modifier = Modifier,
    weeks: Int = 20
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        repeat(weeks) { week ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(7) { day ->
                    val level = levels.getOrElse(week * 7 + day) { 0 }.coerceIn(0, 4)
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(FreshColors.HeatRamp[level])
                    )
                }
            }
        }
    }
}

@Composable
fun HeatmapLegend(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(text = "少", fontSize = 11.sp, color = FreshColors.InkSecondary)
        FreshColors.HeatRamp.forEach { color ->
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
        Spacer(Modifier.width(1.dp))
        Text(text = "多", fontSize = 11.sp, color = FreshColors.InkSecondary)
    }
}
