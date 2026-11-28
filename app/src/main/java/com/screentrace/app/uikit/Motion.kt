package com.screentrace.app.uikit

import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * 批 14c：统一线性马达轻震（`CLOCK_TICK`）。
 *
 * Compose 自带的 `HapticFeedbackType.TextHandleMove` 底层走 `TEXT_HANDLE_MOVE`，
 * 在 vivo OriginOS 非文本控件上默认静默；`CLOCK_TICK` 为系统刻度/滚轮/图表专用震动，
 * 在小米 HyperOS 与 vivo OriginOS 上均可触发清脆线性马达反馈且无需额外权限。
 */
fun performTickHaptic(view: View) {
    runCatching {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
}

/**
 * 离屏静态导出与长图预览强制关闭入场动画的 CompositionLocal，
 * 让圆环、柱图、时段条在首帧直接落终值，不影响前台交互页面。
 */
val LocalForceReduceMotion = staticCompositionLocalOf { false }

/**
 * §5.7 / §5.9：系统「减少动画」判定（`ANIMATOR_DURATION_SCALE <= 0`）。
 *
 * 与 [FreshNavBar] 内的判断同源，抽成公共件供详情页的柱体生长、页面转场、卡片按压复用——
 * 减少动画开启时必须**取消错开、位移与缩放**，只保留淡入（§5.7 第 3 条）。
 * 读不到（个别 ROM 限制）时按「未开启」处理，宁可多播动画也不要整页不动。
 */
@Composable
fun rememberReduceMotion(): Boolean {
    if (LocalForceReduceMotion.current) return true
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) <= 0f
        }.getOrDefault(false)
    }
}
