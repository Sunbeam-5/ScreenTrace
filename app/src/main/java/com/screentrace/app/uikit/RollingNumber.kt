package com.screentrace.app.uikit

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
/**
 * CR-09 验收 42：里程表式逐位滚动数字（odometer）。
 *
 * 为什么不用 `animateIntAsState` + 整体格式化：数值从 149 滚到 342 时中间值
 * （1:53、2:07…）是一串没有意义的假读数，所有字符每帧都在变，观感「僵硬且丑」
 * （真机反馈：每日目标等数字滚动场景）。逐位滚动只在**变化的位**上做方向性
 * 滚动：数值增大该位向上滚、减小向下滚，未变化的位纹丝不动。
 *
 * 现成库调研（2026-11-13）：RollingNumbers / Compose-Rolling-Number 均需引入
 * JitPack 运行时依赖，与项目「不添加运行时依赖」红线冲突，故自实现——核心
 * 就是逐字符 [AnimatedContent]，零依赖。
 *
 * 减少动画（[rememberReduceMotion]）时直接输出终态文本。
 */
@Composable
fun RollingNumberText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    rollDurationMillis: Int = 420,
    maxLines: Int = 1
) {
    if (rememberReduceMotion()) {
        Text(text = text, style = style, color = color, maxLines = maxLines, modifier = modifier)
        return
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        text.forEachIndexed { index, ch ->
            AnimatedContent(
                targetState = ch,
                transitionSpec = {
                    when (RollingNumberMath.rollDirection(initialState, targetState)) {
                        // 数值增大：新字符从下方滑入、旧字符向上滑出（里程表正向）
                        1 -> (
                            slideInVertically(tween(rollDurationMillis, easing = FastOutSlowInEasing)) { it } +
                                fadeIn(tween(rollDurationMillis / 2))
                            ).togetherWith(
                            slideOutVertically(tween(rollDurationMillis, easing = FastOutSlowInEasing)) { -it } +
                                fadeOut(tween(rollDurationMillis / 2))
                        )
                        // 数值减小：反向
                        -1 -> (
                            slideInVertically(tween(rollDurationMillis, easing = FastOutSlowInEasing)) { -it } +
                                fadeIn(tween(rollDurationMillis / 2))
                            ).togetherWith(
                            slideOutVertically(tween(rollDurationMillis, easing = FastOutSlowInEasing)) { it } +
                                fadeOut(tween(rollDurationMillis / 2))
                        )
                        // 非数字（冒号 / 单位 / 首次出现）：交叉淡入淡出
                        else -> fadeIn(tween(rollDurationMillis / 2)) togetherWith
                            fadeOut(tween(rollDurationMillis / 2))
                    }
                },
                label = "roll$index"
            ) { c ->
                // AnimatedContent 内容 lambda 是单插槽：这里只有一个 Text 根节点
                Text(text = c.toString(), style = style, color = color, maxLines = 1)
            }
        }
    }
}

/** 滚动方向判定（纯函数，JVM 可测）。 */
internal object RollingNumberMath {

    /**
     * 1 = 向上滚（数值增大）、-1 = 向下滚（数值减小）、0 = 交叉淡入淡出。
     * 只有两个字符**都是数字**时才有方向语义；其他情况（冒号、字母、新出现）
     * 一律 0。
     */
    fun rollDirection(initial: Char, target: Char): Int {
        if (!initial.isDigit() || !target.isDigit()) return 0
        return when {
            target > initial -> 1
            target < initial -> -1
            else -> 0
        }
    }
}
