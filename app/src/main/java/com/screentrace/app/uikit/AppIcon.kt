package com.screentrace.app.uikit

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette

/**
 * §5.7 应用行真图标：`PackageManager.getApplicationIcon` 优先，失败回退首字母占位。
 *
 * 失败是**正常路径**而不是异常路径：Android 11+ 受包可见性限制（清单里声明了
 * `MAIN/LAUNCHER` 的 `<queries>`，覆盖有桌面入口的应用），应用被卸载、图标资源损坏、
 * 或包名是系统伪条目（如 `android`）时都会抛，这里一律回退首字母，绝不因此崩页面。
 *
 * 图标按包名缓存到组合作用域；列表行数有限（排行 / 通知排行都 ≤ 全部应用数），
 * 不必做全局 LRU。
 */
@Composable
fun AppIcon(
    pkg: String,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp
) {
    val palette = LocalFreshPalette.current
    val context = LocalContext.current
    val bitmap = remember(pkg, size) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(pkg)
            val px = (size.value * 2).toInt().coerceIn(48, 192)
            Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { bmp ->
                drawable.setBounds(0, 0, px, px)
                drawable.draw(Canvas(bmp))
            }.asImageBitmap()
        }.getOrNull()
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.36f))
            .background(palette.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(size))
        } else {
            Text(
                text = name.firstOrNull()?.uppercase() ?: "?",
                style = FreshType.button,
                color = palette.onSurfaceVariant
            )
        }
    }
}
