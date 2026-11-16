package com.screentrace.app.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import com.screentrace.app.data.wallpaper.WallpaperColorScope

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
    val blurDp: Float,
    /**
     * §5.4 CR-24：**已经模糊好的**壁纸位图（降采样，1px ≈ 1dp）。
     *
     * 只在壁纸位图或 `blurDp` 变化时算一次（`WallpaperBlur`），绘制端直接贴它、
     * **不再挂 `Modifier.blur`**——那玩意儿是每帧 RenderEffect，三页常驻时被乘以 3。
     * `blurDp <= 0` 时它就是 [bitmap] 本身。
     */
    val blurred: ImageBitmap,
    /** CR-19 ③：裁切变换（相对「完整显示」的缩放与归一化平移）。 */
    val cropScale: Float = 1f,
    val cropOffsetX: Float = 0f,
    val cropOffsetY: Float = 0f,
    /** CR-20 ②：壁纸主色（ARGB），卡片底色按它派生；0 = 未取色（退化成中性灰）。 */
    val seedColor: Int = 0,
    /** CR-20 ②：哪些颜色跟随壁纸——两套方案。 */
    val colorScope: WallpaperColorScope = WallpaperColorScope.CARDS_ONLY
)

val LocalWallpaper = staticCompositionLocalOf<WallpaperState?> { null }
