package com.screentrace.app.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap

/**
 * §5.4 批 E：全 App 单份壁纸位图 + 已经算好的遮罩参数。
 *
 * 由 [ScreenTimeTheme] 统一提供，两处 `PageScaffold`（主页面层 / 二级页覆盖层）
 * 都读同一个 CompositionLocal，**不各自解码**。所有计算都在 `WallpaperTuning`
 * 纯函数里完成，这里只携带结果。
 */
data class WallpaperState(
    val bitmap: ImageBitmap,
    val dimAlpha: Float,
    val themeScrimAlpha: Float,
    val blurDp: Float
)

val LocalWallpaper = staticCompositionLocalOf<WallpaperState?> { null }
