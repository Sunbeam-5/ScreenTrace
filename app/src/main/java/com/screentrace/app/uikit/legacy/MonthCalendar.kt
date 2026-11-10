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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class CalendarDay(
    val day: Int,
    val level: Int = 0
)

private val MonthColors = listOf(
    Color.Transparent,
    Color(0xFFD3ECDD),
    Color(0xFFA9E7C9),
    Color(0xFF7FD3AE)
)

@Composable
fun MonthCalendarHeader(
    labels: List<String> = listOf("一", "二", "三", "四", "五", "六", "日"),
    cellSize: Dp = 34.dp,
    gap: Dp = 4.dp
) {
    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
        labels.forEach { label ->
            Box(
                modifier = Modifier.size(width = cellSize, height = cellSize),
                contentAlignment = Alignment.Center
            ) {
                Text(text = label, fontSize = 11.sp, color = FreshColors.InkSecondary)
            }
        }
    }
}

@Composable
fun MonthCalendar(
    weeks: List<List<CalendarDay?>>,
    modifier: Modifier = Modifier,
    cellSize: Dp = 34.dp,
    gap: Dp = 4.dp
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        weeks.forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                var i = 0
                while (i < 7) {
                    val day = week.getOrNull(i)
                    if (day != null && day.level > 0) {
                        var j = i
                        while (j < 7) {
                            val next = week.getOrNull(j)
                            if (next == null || next.level == 0) break
                            j++
                        }
                        val count = j - i
                        val groupWidth = cellSize * count + gap * (count - 1)
                        Row(
                            modifier = Modifier
                                .size(width = groupWidth, height = cellSize)
                                .clip(RoundedCornerShape(50.dp))
                                .background(MonthColors[day.level.coerceIn(0, 3)]),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            repeat(count) { k ->
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = week[i + k]!!.day.toString(),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = FreshColors.Ink
                                    )
                                }
                            }
                        }
                        i = j
                    } else {
                        Box(
                            modifier = Modifier.size(cellSize),
                            contentAlignment = Alignment.Center
                        ) {
                            if (day != null) {
                                Text(
                                    text = day.day.toString(),
                                    fontSize = 13.sp,
                                    color = FreshColors.Ink
                                )
                            }
                        }
                        i++
                    }
                }
            }
        }
    }
}
