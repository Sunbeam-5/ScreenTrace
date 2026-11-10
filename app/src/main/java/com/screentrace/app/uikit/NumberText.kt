package com.screentrace.app.uikit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme

/**
 * §5.5 MetricCard 的数值形态（2026-11-10 增补）。
 * 时长用「数字 + 小单位」（§5.3）；纯数字（解锁次数等）用 Number。
 */
sealed interface MetricValue {
    data class Number(val text: String) : MetricValue
    data class Duration(val minutes: Int) : MetricValue
}

private data class MetricPart(val text: String, val isUnit: Boolean)

/**
 * §5.3「数字 + 小单位」排版：同一个 Text 的 AnnotatedString——
 * 数字 26sp/700（tnum、字距 -0.02em）+ 单位 13sp/500；数字→单位 4dp、单位→数字 12dp。
 * 禁止 Row 拼接两个 Text（基线不齐、1.3 倍缩放错位、中途断行）。
 * 宽度不足时整体降一档（24/12），不截断数字、不省略单位。
 */
@Composable
fun MetricValueText(
    value: MetricValue,
    numberColor: Color,
    unitColor: Color,
    modifier: Modifier = Modifier
) {
    val parts = when (value) {
        is MetricValue.Number -> listOf(MetricPart(value.text, isUnit = false))
        is MetricValue.Duration -> durationParts(value.minutes)
    }
    MetricText(parts, numberColor, unitColor, modifier)
}

/** §5.3：≥1h「X小时Y分钟」（整小时省略 0 分钟）、<1h「X分钟」、0「0分钟」。 */
private fun durationParts(minutes: Int): List<MetricPart> {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        minutes <= 0 -> listOf(MetricPart("0", false), MetricPart("分钟", true))
        h == 0 -> listOf(MetricPart("$m", false), MetricPart("分钟", true))
        m == 0 -> listOf(MetricPart("$h", false), MetricPart("小时", true))
        else -> listOf(
            MetricPart("$h", false), MetricPart("小时", true),
            MetricPart("$m", false), MetricPart("分钟", true)
        )
    }
}

@Composable
private fun MetricText(
    parts: List<MetricPart>,
    numberColor: Color,
    unitColor: Color,
    modifier: Modifier = Modifier
) {
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    BoxWithConstraints(modifier = modifier) {
        val regular = 26 to 13
        val compact = 24 to 12
        val (numSp, unitSp) = if (fits(parts, regular.first, regular.second, fontScale, maxWidth.value)) {
            regular
        } else {
            compact
        }
        Text(
            text = buildMetricAnnotated(parts, numSp, unitSp, numberColor, unitColor),
            style = FreshType.metricNumber,
            maxLines = 1
        )
    }
}

/** 粗略宽度估算：ASCII 数字 ≈0.55em、中文 ≈1em，加 4dp / 12dp 节奏间距。 */
private fun fits(parts: List<MetricPart>, numSp: Int, unitSp: Int, fontScale: Float, maxWidthDp: Float): Boolean {
    var width = 0f
    parts.forEachIndexed { index, part ->
        if (index > 0) width += if (part.isUnit) 4f else 12f
        val sp = if (part.isUnit) unitSp else numSp
        width += part.text.sumOf { ch -> if (ch.code < 128) 0.55 else 1.0 }.toFloat() * sp * fontScale
    }
    return width <= maxWidthDp
}

private fun buildMetricAnnotated(
    parts: List<MetricPart>,
    numSp: Int,
    unitSp: Int,
    numberColor: Color,
    unitColor: Color
): AnnotatedString = buildAnnotatedString {
    parts.forEachIndexed { index, part ->
        if (index > 0) {
            // 数字→单位 4dp：单位字号的一个空格 ≈3.4dp；单位→数字 12dp：数字字号的两个空格 ≈13.4dp
            if (part.isUnit) {
                withStyle(SpanStyle(fontSize = unitSp.sp)) { append(" ") }
            } else {
                withStyle(SpanStyle(fontSize = numSp.sp)) { append("  ") }
            }
        }
        withStyle(
            SpanStyle(
                color = if (part.isUnit) unitColor else numberColor,
                fontSize = (if (part.isUnit) unitSp else numSp).sp,
                fontWeight = if (part.isUnit) FontWeight.Medium else FontWeight.Bold,
                letterSpacing = if (part.isUnit) TextUnit.Unspecified else (-0.02).em,
                fontFeatureSettings = if (part.isUnit) null else "'tnum'"
            )
        ) {
            append(part.text)
        }
    }
}

/**
 * §5.3 对比行：14sp/600 文字 + 12dp 箭头图标，颜色用 primary；
 * 增减方向由箭头形状表达，不单靠颜色（§5.4）。
 */
@Composable
fun CompareLine(
    text: String,
    up: Boolean,
    modifier: Modifier = Modifier
) {
    val palette = LocalFreshPalette.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = if (up) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null, // 方向信息由文案「增加/减少」承载，图标不重复播报（§5.9）
            tint = palette.primary,
            modifier = Modifier.size(12.dp)
        )
        Text(text = text, style = FreshType.button, color = palette.primary)
    }
}

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun NumberTextPreview() {
    ScreenTimeTheme {
        val palette = LocalFreshPalette.current
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricValueText(
                value = MetricValue.Duration(232),
                numberColor = palette.onSurface,
                unitColor = palette.onSurfaceMuted
            )
            MetricValueText(
                value = MetricValue.Duration(45),
                numberColor = palette.onSurface,
                unitColor = palette.onSurfaceMuted
            )
            MetricValueText(
                value = MetricValue.Number("87"),
                numberColor = palette.onSurface,
                unitColor = palette.onSurfaceMuted
            )
            CompareLine(text = "较昨日增加 12 分钟", up = true)
            CompareLine(text = "较昨日减少 8 分钟", up = false)
        }
    }
}
