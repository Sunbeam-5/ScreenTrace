package com.screentrace.app.uikit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * §5.7 详情页入场：淡入 + 上移 16dp（约 240ms，两页同一转场）。
 *
 * 只做**入场**：返回时不做反向动画（列表已经滚回原处，足以表达「回来了」；
 * 再加一段退场会让一次往返超过 §5.7 的 ≤500ms 总预算）。
 * 减少动画时直接呈现终态。
 *
 * 用 `graphicsLayer`（绘制期位移）而不是 `offset`（布局期）：布局期位移会让
 * 每次帧回调都重新测量，长列表上白白掉帧。
 */
@Composable
fun PageEnter(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduceMotion = rememberReduceMotion()
    val progress = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(reduceMotion) {
        if (!reduceMotion) progress.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
    }
    Box(
        modifier = modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 16.dp.toPx()
        }
    ) {
        content()
    }
}

/**
 * §5.7 大数字入场：淡入并上移 12dp（约 180ms）。
 *
 * **不做从 0 滚动到目标值的数字动画**：滚动计数会让人误以为数据在变，
 * 而且把读数时间拉长了（§5.7 明文禁止）。
 */
@Composable
fun NumberEnter(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduceMotion = rememberReduceMotion()
    val progress = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(reduceMotion) {
        if (!reduceMotion) progress.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
    }
    Box(
        modifier = modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 12.dp.toPx()
        }
    ) {
        content()
    }
}
