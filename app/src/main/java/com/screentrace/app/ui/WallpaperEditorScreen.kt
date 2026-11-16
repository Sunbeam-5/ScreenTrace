package com.screentrace.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.screentrace.app.data.wallpaper.WallpaperCropMath
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.uikit.PrimaryButton
import com.screentrace.app.uikit.SecondaryButton

/**
 * §5.4 CR-19 ③：壁纸全屏裁切编辑（所见即所得）。
 *
 * 图片按「完整显示（Fit）+ graphicsLayer 变换」绘制，与 `PageScaffold` 的正式绘制
 * 用同一套几何（[WallpaperCropMath]）：单指拖动平移、双指捏合缩放（围绕手指中心、
 * 最小抬到恰好盖满、平移钳制在图片范围内）。确认回调给的是**归一化**偏移
 * （相对屏幕宽高），与 DataStore 存储口径一致，换机分辨率不同不吃亏。
 *
 * 不是新页面、不进路由：由 `AppNav` 在根层按开关直接渲染，系统返回键收起浮层。
 */
@Composable
fun WallpaperEditorScreen(
    bitmap: ImageBitmap,
    initialScale: Float,
    initialOffsetX: Float,
    initialOffsetY: Float,
    onApply: (zoom: Float, offsetX: Float, offsetY: Float) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalFreshPalette.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
    ) {
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        val geo = remember(bitmap.width, bitmap.height) {
            WallpaperCropMath.geometry(bitmap.width.toFloat(), bitmap.height.toFloat(), screenW, screenH)
        }
        var zoom by remember { mutableFloatStateOf(initialScale) }
        var tx by remember { mutableFloatStateOf(initialOffsetX * screenW) }
        var ty by remember { mutableFloatStateOf(initialOffsetY * screenH) }

        // 初次进入把旧参数钳回合法区间（保证盖满 + 平移在图片内）
        LaunchedEffect(geo) {
            val g = geo ?: return@LaunchedEffect
            zoom = WallpaperCropMath.clampedZoom(zoom, g)
            val (nx, ny) = WallpaperCropMath.clampedTranslation(tx, ty, zoom, g, screenW, screenH)
            tx = nx
            ty = ny
        }

        Image(
            bitmap = bitmap,
            contentDescription = "壁纸裁切预览",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = tx
                    translationY = ty
                }
                .pointerInput(geo) {
                    detectTransformGestures { centroid, pan, zoomDelta, _ ->
                        val g = geo ?: return@detectTransformGestures
                        val newZoom = WallpaperCropMath.clampedZoom(zoom * zoomDelta, g)
                        // 围绕手指中心缩放：焦点下的图像点保持不动
                        val fx = centroid.x - screenW / 2f
                        val fy = centroid.y - screenH / 2f
                        val (rx, ry) = WallpaperCropMath.focusedTranslation(tx, ty, zoom, newZoom, fx, fy)
                        zoom = newZoom
                        val (cx, cy) = WallpaperCropMath.clampedTranslation(
                            rx + pan.x, ry + pan.y, zoom, g, screenW, screenH
                        )
                        tx = cx
                        ty = cy
                    }
                }
        )

        Text(
            text = "拖动平移 · 双指缩放 · 所见即所得",
            style = FreshType.label,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 56.dp)
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp, vertical = 20.dp)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SecondaryButton(
                text = "还原默认",
                onClick = onReset,
                modifier = Modifier.weight(1f)
            )
            SecondaryButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f)
            )
            PrimaryButton(
                text = "使用此裁切",
                onClick = {
                    onApply(zoom, tx / screenW, ty / screenH)
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}
