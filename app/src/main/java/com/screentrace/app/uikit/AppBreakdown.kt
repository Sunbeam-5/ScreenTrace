package com.screentrace.app.uikit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.screentrace.app.contract.UsageCalculator
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * CR-07 §4.4「应用构成」：色带 + 行式图例 + 日间/夜间条。
 *
 * 与 [SegmentedBar] 的区别：SegmentedBar 被统计页/排行页/周期页共用（自带文字图例），
 * 不能为了今日页改它。这里另起一套专用组件，色带与列表**共用同一份 [BreakdownRow]**，
 * 保证每个应用行的 8dp 圆点与色带对应段永远同色。
 */
@Composable
fun AppBreakdownSection(
    rows: List<BreakdownRow>,
    dayPercent: Int,
    modifier: Modifier = Modifier
) {
    if (rows.isEmpty()) return
    val palette = LocalFreshPalette.current
    val colors = listOf(palette.chart1, palette.chart2, palette.chart3, palette.chart4, palette.chart5)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 色带：高 14、每段圆角 7、段间距 3，按分钟权重
        Row(
            modifier = Modifier.fillMaxWidth().height(14.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            rows.forEach { row ->
                Box(
                    modifier = Modifier
                        .weight(row.minutes.toFloat())
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(7.dp))
                        .background(colors[row.colorIndex])
                )
            }
        }

        // 行式图例：高 34，圆点 → 真应用图标 → 名称 → 时长 → 占比
        Column(modifier = Modifier.fillMaxWidth()) {
            rows.forEach { row -> BreakdownRowItem(row = row, dotColor = colors[row.colorIndex]) }
        }

        DayNightBar(dayPercent = dayPercent)
    }
}

@Composable
private fun BreakdownRowItem(row: BreakdownRow, dotColor: Color) {
    val palette = LocalFreshPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().height(34.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        // AppIcon 自带 size×0.36 圆角（28dp → 约 10dp），与规格 9dp 同量级；
        // 取不到图标时回退首字母占位，不因包可见性限制崩页面。
        AppIcon(pkg = row.pkg, name = row.name, size = 28.dp)
        Text(
            text = row.name,
            style = FreshType.body,
            color = palette.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = UsageCalculator.formatDuration(row.minutes),
            style = FreshType.numberBody,
            color = palette.onSurface,
            maxLines = 1
        )
        Text(
            text = "${row.percent}%",
            style = FreshType.label,
            color = palette.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(36.dp)
        )
    }
}

/** §4.4：太阳/月亮 + 13sp 文字（百分数加粗用主文字色）+ 高 10 双色条。 */
@Composable
private fun DayNightBar(dayPercent: Int) {
    val palette = LocalFreshPalette.current
    val left = dayPercent.coerceIn(0, 100)
    val right = 100 - left
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SunIcon(tint = palette.primary)
                Text(text = "日间", style = FreshType.bodySmall, color = palette.onSurfaceVariant)
                Text(text = "$left%", style = FreshType.bodySmallBold, color = palette.onSurface)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(text = "$right%", style = FreshType.bodySmallBold, color = palette.onSurface)
                Text(text = "夜间", style = FreshType.bodySmall, color = palette.onSurfaceVariant)
                MoonIcon(tint = palette.nightIcon, cutout = palette.surface)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(10.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // 0% 的一侧不绘制，避免留下 1px 假条
            if (left > 0) {
                Box(
                    modifier = Modifier
                        .weight(left.toFloat())
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(5.dp))
                        .background(palette.primary)
                )
            }
            if (right > 0) {
                Box(
                    modifier = Modifier
                        .weight(right.toFloat())
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(5.dp))
                        .background(palette.secondaryContainer)
                )
            }
        }
    }
}

/**
 * 线性太阳图标（16dp）。项目只依赖 material-icons-core，WbSunny 在 extended 里，
 * 不为一个图标加运行时依赖——自绘圆 + 8 条射线。
 */
@Composable
private fun SunIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(16.dp)) {
        val r = size.minDimension / 2f
        val c = center
        val stroke = 1.5.dp.toPx()
        drawCircle(color = tint, radius = r * 0.34f, center = c, style = Stroke(width = stroke))
        repeat(8) { i ->
            val angle = (i * 45.0).toRadians()
            val inner = r * 0.58f
            val outer = r * 0.90f
            drawLine(
                color = tint,
                start = Offset(c.x + cos(angle).toFloat() * inner, c.y + sin(angle).toFloat() * inner),
                end = Offset(c.x + cos(angle).toFloat() * outer, c.y + sin(angle).toFloat() * outer),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * 月牙图标（16dp）：大圆减去偏移的小圆。cutout 传所在卡片的底色，
 * 所以组件只用于纯色白卡/tonal 卡内部（§4.4 的卡片是纯 surface）。
 */
@Composable
private fun MoonIcon(tint: Color, cutout: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(16.dp)) {
        val r = size.minDimension / 2f
        val c = center
        drawCircle(color = tint, radius = r * 0.88f, center = c)
        drawCircle(
            color = cutout,
            radius = r * 0.74f,
            center = Offset(c.x + r * 0.52f, c.y - r * 0.30f)
        )
    }
}

/** §4.4「查看全部应用」：高 48、圆角 18、主色文字 + 16dp 箭头；transformSource 由调用方传。 */
@Composable
fun ViewAllButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalFreshPalette.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(palette.viewAllSurface)
            .clickable(onClickLabel = text, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = text, style = FreshType.buttonBold, color = palette.primary)
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = palette.primary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

private fun Double.toRadians(): Double = this * Math.PI / 180.0

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun AppBreakdownPreview() {
    ScreenTimeTheme {
        val rows = listOf(
            BreakdownRow("米家", "com.xiaomi.smarthome", 310, 42, 0),
            BreakdownRow("屏迹", "com.screentrace.app", 180, 24, 1),
            BreakdownRow("桌面", "com.miui.home", 120, 16, 2),
            BreakdownRow("微信", "com.tencent.mm", 80, 11, 3),
            BreakdownRow("其他", "_other_", 50, 7, 4)
        )
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppBreakdownSection(rows = rows, dayPercent = 72)
            Spacer(Modifier.height(4.dp))
            ViewAllButton(text = "查看全部应用", onClick = {})
        }
    }
}
