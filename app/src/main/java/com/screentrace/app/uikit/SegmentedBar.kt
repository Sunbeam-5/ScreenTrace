package com.screentrace.app.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme

/**
 * §5.5：分段占比条。每段必须有图例（名称 + 时长或百分比），颜色仅作辅助——
 * 颜色不能是区分系列的唯一手段（§5.4）。
 * color 请传 LocalFreshPalette 的 chart1~5 等语义 token，禁止硬编码色。
 * 权重 ≤0 的段不参与绘制与图例；全部无效时路由到统一 EmptyState（§5.5）。
 */
data class Segment(
    val weight: Float,
    val color: Color,
    val label: String = ""
)

@Composable
fun SegmentedBar(
    segments: List<Segment>,
    modifier: Modifier = Modifier,
    height: Dp = 34.dp
) {
    val palette = LocalFreshPalette.current
    val visible = segments.filter { it.weight > 0f }
    if (visible.isEmpty()) {
        EmptyState(title = "暂无时段数据", modifier = modifier)
        return
    }
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(height),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            visible.forEach { segment ->
                Box(
                    modifier = Modifier
                        .weight(segment.weight)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(segment.color)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            visible.forEach { segment ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(segment.color)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = segment.label,
                        style = FreshType.label,
                        color = palette.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * §5.7 今日页「日间 / 夜间」比值条：左右百分比 + 双段条，16dp 分隔由调用方加。
 * 左右两条系列必须带文字标签（颜色不是唯一区分手段，§5.4）。
 */
@Composable
fun RatioBar(
    leftLabel: String,
    leftPercent: Int,
    rightLabel: String,
    modifier: Modifier = Modifier
) {
    val palette = LocalFreshPalette.current
    val left = leftPercent.coerceIn(0, 100)
    val right = 100 - left
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "$leftLabel $left%",
                style = FreshType.label,
                color = palette.onSurfaceVariant
            )
            Text(
                text = "$rightLabel $right%",
                style = FreshType.label,
                color = palette.onSurfaceVariant
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(left.coerceAtLeast(1).toFloat())
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(palette.chart1)
            )
            Box(
                modifier = Modifier
                    .weight(right.coerceAtLeast(1).toFloat())
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(palette.chart3)
            )
        }
    }
}

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun SegmentedBarPreview() {
    ScreenTimeTheme {
        val palette = LocalFreshPalette.current
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SegmentedBar(
                segments = listOf(
                    Segment(48f, palette.chart1, "视频娱乐 48% · 2小时10分钟"),
                    Segment(30f, palette.chart3, "社交沟通 30% · 1小时22分钟"),
                    Segment(22f, palette.chart5, "其他 22% · 59分钟")
                )
            )
            RatioBar(leftLabel = "日间", leftPercent = 72, rightLabel = "夜间")
            SegmentedBar(segments = emptyList())
        }
    }
}
