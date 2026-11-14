package com.screentrace.app.uikit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.screentrace.app.theme.LocalFreshPalette

/**
 * §5.2 批 D：主页面顶栏那枚「设置」按钮点开后的四枚快捷动作。
 *
 * 顺序即面板里从左到右的顺序；日历 tab 由 [com.screentrace.app.ui.quickMenuActionsFor]
 * 去掉 [Date] 变三枚。这里只定义 UI 侧的枚举与图标，不依赖 ui / data 包。
 */
enum class QuickMenuAction(val description: String) {
    Settings("设置"),
    Theme("界面风格"),
    Date("选择日期"),
    Devices("切换设备")
}

/**
 * 一行横排、无文字的圆按钮面板（负责人 20:05 裁定）。
 *
 * 四枚按钮复用 [HeaderActionButton]：同一套 40dp 圆 / 48dp 点击区 / contentDescription，
 * 并自带 `transformSource`——调用方给本面板下发 `LocalContainerTransformState` 后，
 * 点「设置」就会把这枚按钮登记为容器变换起点（§5.5 批 D 起点重登记）。
 */
@Composable
fun QuickMenuPanel(
    actions: List<QuickMenuAction>,
    onAction: (QuickMenuAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalFreshPalette.current
    Row(
        modifier = modifier
            .shadow(
                elevation = if (palette.darkTheme) 0.dp else 8.dp,
                shape = RoundedCornerShape(28.dp),
                ambientColor = palette.shadow,
                spotColor = palette.shadow
            )
            .clip(RoundedCornerShape(28.dp))
            .background(palette.surface)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        actions.forEach { action ->
            HeaderActionButton(
                contentDescription = action.description,
                onClick = { onAction(action) }
            ) {
                when (action) {
                    QuickMenuAction.Settings -> Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = palette.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                    QuickMenuAction.Theme -> ThemeDotsIcon()
                    QuickMenuAction.Date -> Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = null,
                        tint = palette.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                    QuickMenuAction.Devices -> DevicesIcon()
                }
            }
        }
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
