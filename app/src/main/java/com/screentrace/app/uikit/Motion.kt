package com.screentrace.app.uikit

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * §5.7 / §5.9：系统「减少动画」判定（`ANIMATOR_DURATION_SCALE <= 0`）。
 *
 * 与 [FreshNavBar] 内的判断同源，抽成公共件供详情页的柱体生长、页面转场、卡片按压复用——
 * 减少动画开启时必须**取消错开、位移与缩放**，只保留淡入（§5.7 第 3 条）。
 * 读不到（个别 ROM 限制）时按「未开启」处理，宁可多播动画也不要整页不动。
 */
@Composable
fun rememberReduceMotion(): Boolean {
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
