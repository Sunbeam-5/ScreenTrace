package com.screentrace.app.uikit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme

/**
 * §5.5：SegmentedControl 40dp。选中项 primary + onPrimary，未选中 onSurfaceVariant。
 * 选项等宽；宽度由调用方经 modifier 控制（默认占满内容列）。
 *
 * **指示器是滑动的**（bug 5）：以前选中态是每项各自 `background(primary)` 硬切，
 * 点「近 30 天」时整块颜色瞬间跳到另一项，观感非常生硬。现在改成一块独立的
 * 圆角指示器按 `offset` 平移（spring），文字颜色同步做 color 动画，
 * 切换过程是连续的——这是 Material 3 分段按钮的标准手感。
 *
 * 用 `BoxWithConstraints` 只是为了拿到可用宽度算单项宽度：本组件从不放进
 * `IntrinsicSize` 的父容器，所以不会踩 SubcomposeLayout 求 intrinsic 的坑。
 */
@Composable
fun SegmentedControl(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalFreshPalette.current
    if (items.isEmpty()) return
    val reduceMotion = rememberReduceMotion()
    // 显式标注 AnimationSpec<Dp>：`tween(0)` 单独出现在 if 分支里推不出 T，
    // 编译器会报 "Not enough information to infer type variable T"
    val spec: AnimationSpec<Dp> = if (reduceMotion) {
        tween(0)
    } else {
        spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(CircleShape)
            .background(palette.surfaceVariant)
    ) {
        val itemWidth = maxWidth / items.size
        val safeIndex = selectedIndex.coerceIn(0, items.lastIndex)
        val indicatorX by animateDpAsState(
            targetValue = itemWidth * safeIndex,
            animationSpec = spec,
            label = "segIndicatorX"
        )
        // 只有真正命中某一项时才画指示器。统计页「近 7/30 天」和顶部日期快捷共用
        // 同一份 DateSelection：用户选的是「今天」这种单日时 selectedIndex = -1，
        // 之前被 coerce 成 0 会让「近 7 天」显示成一个谁也点不中、文字还是灰的胶囊，
        // 看起来像「明明选中了却不生效」。此时干脆不画，才和真实筛选状态一致。
        if (selectedIndex in items.indices) {
            Box(
                modifier = Modifier
                    .offset(x = indicatorX)
                    .width(itemWidth)
                    .fillMaxHeight()
                    // 2dp 内缩：指示器比轨道略小一圈，视觉上「浮」在轨道里而不是贴边
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(palette.primary)
            )
        }
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val textColor by animateColorAsState(
                    targetValue = if (selected) palette.onPrimary else palette.onSurfaceVariant,
                    animationSpec = if (reduceMotion) tween(0) else tween(180),
                    label = "segTextColor"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .selectable(selected = selected, role = Role.Tab) { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = label, style = FreshType.button, color = textColor)
                }
            }
        }
    }
}

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun SegmentedControlPreview() {
    ScreenTimeTheme {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
        ) {
            SegmentedControl(items = listOf("月视图", "年视图"), selectedIndex = 0, onSelect = {})
            SegmentedControl(items = listOf("近 7 天", "近 30 天"), selectedIndex = 1, onSelect = {})
            SegmentedControl(
                items = listOf("图表", "数据表"),
                selectedIndex = 0,
                onSelect = {}
            )
        }
    }
}
