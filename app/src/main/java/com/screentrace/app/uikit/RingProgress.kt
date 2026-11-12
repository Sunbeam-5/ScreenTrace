package com.screentrace.app.uikit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.screentrace.app.contract.UsageCalculator
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme

/**
 * §5.3 / §5.7 hero 圆环（CR-06 **24 小时全天环**，负责人勘误版）：
 * - **整圈 = 24 小时**，弧长 = 当日使用 ÷ 24h（15 小时占 15/24），从 12 点顺时针画起；
 *   未用部分为 track 底色。目标**不画进环**——只做中心下行动文字与「超出目标」chip 依据；
 * - 中心三行：caption（label）/ `H:MM`（heroNumber 44sp，传 usageMinutes 时数字滚动）/
 *   note（label，如「目标 4:00」）。
 *
 * 几何在 [RingMath]（纯函数可单测）；进场动画弧长 0→终值（700ms FastOutSlowIn），
 * 减少动画（[rememberReduceMotion]）直接落终值——不是缩短时长。
 */
@Composable
fun RingProgress(
    usageMinutes: Int,
    modifier: Modifier = Modifier,
    caption: String? = null,
    note: String? = null
) {
    val palette = LocalFreshPalette.current
    val reduceMotion = rememberReduceMotion()

    // 进场：弧长从 0 起（不许先闪一帧满圈）；减少动画直接落终值
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(usageMinutes) {
        val target = RingMath.daySweep(usageMinutes)
        if (reduceMotion) sweep.snapTo(target) else sweep.animateTo(target, tween(700, easing = FastOutSlowInEasing))
    }
    val animatedMinutes by animateIntAsState(
        targetValue = usageMinutes,
        animationSpec = if (reduceMotion) tween(0) else tween(700, easing = FastOutSlowInEasing),
        label = "ringMinutes"
    )
    val displayTime = UsageCalculator.formatClock(animatedMinutes)

    Box(
        modifier = modifier.size(184.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val strokeWidth = 9.dp.toPx()
            val half = strokeWidth / 2f
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            // track：整圈 24 小时的底
            drawArc(
                color = palette.track,
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(half, half), size = arcSize, style = stroke
            )
            // 使用弧：从 12 点顺时针，弧长 = 使用/24h
            if (sweep.value > 0f) {
                drawArc(
                    color = palette.primary,
                    startAngle = -90f, sweepAngle = 360f * sweep.value, useCenter = false,
                    topLeft = Offset(half, half), size = arcSize, style = stroke
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (caption != null) {
                Text(text = caption, style = FreshType.label, color = palette.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
            }
            Text(text = displayTime, style = FreshType.heroNumber, color = palette.onSurface)
            if (note != null) {
                Spacer(Modifier.height(4.dp))
                Text(text = note, style = FreshType.label, color = palette.onSurfaceVariant)
            }
        }
    }
}

/** 「超出目标」chip 文案（页面按 [RingMath.shouldShowOverChip] 决定是否渲染）。 */
fun overTargetChipText(overMinutes: Int): String =
    "超出目标 ${UsageCalculator.formatDuration(overMinutes)}"

@Preview(showBackground = true)
@Composable
private fun RingProgressPreview() {
    ScreenTimeTheme {
        RingProgress(
            usageMinutes = 342, // 5:42，全天环占 5.7/24
            caption = "今日亮屏",
            note = "目标 4:00"
        )
    }
}
