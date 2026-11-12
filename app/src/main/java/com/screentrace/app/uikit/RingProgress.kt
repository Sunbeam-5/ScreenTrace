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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.screentrace.app.contract.UsageCalculator
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme

/**
 * §5.3 / §5.7 hero 圆环（CR-07 **目标环双层**，新规格 §4.2）：
 * - `progress = 当日使用 / 每日目标`，允许超过 1、**上限 2**；
 * - **第一圈**：track 底 + 线性渐变主色（左上→右下），`min(p,1)` 满圈即止；
 * - **第二圈**（超目标才有）：从 12 点起按 `(p-1)×360°` 叠画更深主色（primary 向黑混 35%）；
 * - 中心三行：caption（12sp，「今日亮屏 / 日均亮屏」）/ `H:MM`（heroNumber 46sp
 *   滚动数字）/ 「目标 X:XX」（12sp）；**目标 ≤0 时不画进度、不显示目标行**（§5.7-4）。
 *
 * 进场动画：单一动画值 0→progress（900ms FastOutSlowIn），两圈与数字同源驱动；
 * 减少动画（[rememberReduceMotion]）直接落终值。几何在 [RingMath]（JVM 可测）。
 */
@Composable
fun RingProgress(
    progress: Float,
    usageMinutes: Int,
    goalMinutes: Int,
    caption: String,
    modifier: Modifier = Modifier
) {
    val palette = LocalFreshPalette.current
    val reduceMotion = rememberReduceMotion()
    val hasGoal = goalMinutes > 0
    val clamped = RingMath.clampProgress(progress)

    // 单一动画值驱动两圈与数字（§6.1）：0→progress 一次，不循环
    val animated = remember { Animatable(0f) }
    LaunchedEffect(clamped) {
        if (reduceMotion) animated.snapTo(clamped)
        else animated.animateTo(clamped, tween(900, easing = FastOutSlowInEasing))
    }
    val animatedMinutes by animateIntAsState(
        targetValue = usageMinutes,
        animationSpec = if (reduceMotion) tween(0) else tween(900, easing = FastOutSlowInEasing),
        label = "ringMinutes"
    )

    Box(
        modifier = modifier.size(184.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(184.dp)) {
            val strokeWidth = 14.dp.toPx()
            val half = strokeWidth / 2f
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            // track：整圈底
            drawArc(
                color = palette.track,
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(half, half), size = arcSize, style = stroke
            )
            if (hasGoal && animated.value > 0f) {
                // 第一圈：线性渐变主色（左上→右下），min(p,1) 满圈即止
                drawArc(
                    brush = Brush.linearGradient(
                        colors = listOf(palette.primary, palette.ringGradientEnd),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height)
                    ),
                    startAngle = -90f,
                    sweepAngle = 360f * RingMath.firstSweep(animated.value),
                    useCenter = false,
                    topLeft = Offset(half, half), size = arcSize, style = stroke
                )
                // 第二圈：超目标部分，更深主色，从 12 点叠画
                if (RingMath.shouldShowSecond(animated.value)) {
                    drawArc(
                        color = palette.ringOver,
                        startAngle = -90f,
                        sweepAngle = 360f * RingMath.secondSweep(animated.value),
                        useCenter = false,
                        topLeft = Offset(half, half), size = arcSize, style = stroke
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = caption, style = FreshType.label, color = palette.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                text = UsageCalculator.formatClock(animatedMinutes),
                style = FreshType.heroNumber,
                color = palette.onSurface,
                maxLines = 1
            )
            if (hasGoal) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "目标 ${UsageCalculator.formatClock(goalMinutes)}",
                    style = FreshType.label,
                    color = palette.onSurfaceVariant
                )
            }
        }
    }
}

/** 「超出目标」chip 文案（§5.7-4：目标 ≤0 或未超目标时不显示）。 */
fun overTargetChipText(overMinutes: Int): String =
    "超出目标 ${UsageCalculator.formatDuration(overMinutes)}"

@Preview(showBackground = true)
@Composable
private fun RingOverPreview() {
    ScreenTimeTheme {
        // 设计稿示例：目标 4:00、已用 5:42 → progress ≈ 1.44，第二圈 ≈ 157°
        RingProgress(progress = 1.4375f, usageMinutes = 342, goalMinutes = 240, caption = "今日亮屏")
    }
}

@Preview(showBackground = true)
@Composable
private fun RingNormalPreview() {
    ScreenTimeTheme {
        RingProgress(progress = 0.62f, usageMinutes = 149, goalMinutes = 240, caption = "今日亮屏")
    }
}
