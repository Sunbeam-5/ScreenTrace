package com.screentrace.app.uikit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.screentrace.app.theme.LocalFreshPalette

/**
 * 顶栏按钮的**目标入口**（CR-20 ①）。
 *
 * 枚举本身携带目标，是为了让「不设无动作的假按钮」这条红线在**编译期**就成立：
 * `AppNav` 按 [HeaderAction] 穷举接线，少接一个分支就编译不过；单测再钉住
 * 「四枚动作 → 四个互不相同的真实入口」。
 */
enum class HeaderTarget {
    /** 设备列表面板浮层（只读，不写本机库）。 */
    DEVICE_PANEL,

    /** 按归属状态的日期弹层（`DateSheet`）。 */
    DATE_SHEET,

    /** 全局界面风格弹层（`StyleSheet`）。 */
    STYLE_SHEET,

    /** 设置页（容器变换进入）。 */
    SETTINGS_PAGE
}

/**
 * §5.2 CR-20 ①：主页面顶栏**四枚独立圆按钮**的动作集合。
 *
 * 顺序即顶栏从左到右的顺序（切换设备 / 日期 / 主题 / 设置）。
 * 这里只定义 UI 侧的枚举与图标，不依赖 ui / data 包。
 *
 * 批 D 的「单枚设置按钮 + 下拉面板」与 CR-19 ① 的竖排面板已按 CR-20 撤销——
 * 动作直接摊在顶栏，每枚按钮自己接一个真实动作。
 */
enum class HeaderAction(val description: String, val target: HeaderTarget) {
    Devices("切换设备", HeaderTarget.DEVICE_PANEL),
    Date("选择日期", HeaderTarget.DATE_SHEET),
    Theme("界面风格", HeaderTarget.STYLE_SHEET),
    Settings("设置", HeaderTarget.SETTINGS_PAGE)
}

/**
 * 顶栏按钮的图标分派。统一走 [HeaderActionButton]（同一套 40dp 圆 / 48dp 点击区 /
 * contentDescription），这里只负责「哪枚动作画哪个图标」。
 */
@Composable
internal fun HeaderActionIcon(action: HeaderAction) {
    val palette = LocalFreshPalette.current
    when (action) {
        HeaderAction.Settings -> Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = null,
            tint = palette.onSurface,
            modifier = Modifier.size(22.dp)
        )
        HeaderAction.Theme -> ThemeDotsIcon()
        HeaderAction.Date -> Icon(
            imageVector = Icons.Default.DateRange,
            contentDescription = null,
            tint = palette.onSurface,
            modifier = Modifier.size(22.dp)
        )
        HeaderAction.Devices -> DevicesIcon()
    }
}

/**
 * 「切换设备」自绘图标：前后两台设备的圆角轮廓。
 * icons-core 没有设备组图标，按 §5.2「不引 icons-extended」自绘；
 * 用描边而不是实心，浅/深主题都只吃 `onSurface`，不新增 token。
 */
@Composable
internal fun DevicesIcon() {
    val palette = LocalFreshPalette.current
    Canvas(modifier = Modifier.size(22.dp)) {
        val stroke = 2.dp.toPx()
        val radius = CornerRadius(2.5.dp.toPx())
        val w = size.width
        val h = size.height
        // 后置（右上、稍淡）
        drawRoundRect(
            color = palette.onSurface.copy(alpha = 0.55f),
            topLeft = Offset(w * 0.42f, h * 0.10f),
            size = Size(w * 0.44f, h * 0.58f),
            cornerRadius = radius,
            style = Stroke(width = stroke)
        )
        // 前置（左下、实色）
        drawRoundRect(
            color = palette.onSurface,
            topLeft = Offset(w * 0.14f, h * 0.32f),
            size = Size(w * 0.44f, h * 0.58f),
            cornerRadius = radius,
            style = Stroke(width = stroke)
        )
    }
}
