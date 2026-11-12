package com.screentrace.app.uikit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
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
    modifier: Modifier = Modifier,
    /** CR-07/§4.1：统计型卡数字 28sp 覆盖（>0 生效；默认走常规档 30sp）。 */
    numberFontSize: Int = 0
) {
    val parts = when (value) {
        is MetricValue.Number -> listOf(MetricPart(value.text, isUnit = false))
        is MetricValue.Duration -> durationParts(value.minutes)
    }
    MetricText(parts, numberColor, unitColor, modifier, numberFontSize)
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
    modifier: Modifier = Modifier,
    numberFontSize: Int = 0
) {
    val regNum = if (numberFontSize > 0) numberFontSize else REGULAR_NUM
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    // 常规档 26/13、紧凑档 24/12（§5.3）。两档都先组好，测量时只用得上一个。
    //
    // **这里不能用 `BoxWithConstraints`**（真机首装崩溃的根因）：
    // `BoxWithConstraints` 是 `SubcomposeLayout`，而 Compose 禁止对 SubcomposeLayout
    // 求 intrinsic 尺寸。卡片又在 `Row(height(IntrinsicSize.Min))` 里做等高，
    // 于是「有数据渲染双卡」时必然抛
    // `IllegalStateException: Asking for intrinsic measurements of SubcomposeLayout...`。
    // 换成自带 intrinsic 实现的 [MetricTextPolicy]：既能拿到宽度决定字号，
    // 又能让外层照常做 intrinsic 等高。
    Layout(
        modifier = modifier,
        content = {
            Text(
                text = buildMetricAnnotated(parts, regNum, REGULAR_UNIT, numberColor, unitColor),
                style = FreshType.metricNumber,
                maxLines = 1
            )
            Text(
                text = buildMetricAnnotated(parts, COMPACT_NUM, COMPACT_UNIT, numberColor, unitColor),
                style = FreshType.metricNumber,
                maxLines = 1
            )
        },
        measurePolicy = MetricTextPolicy(parts, fontScale)
    )
}

private const val REGULAR_NUM = 30 // CR-07：指标卡数字 30sp（统计型卡用 numberFontSize=28 覆盖）
private const val REGULAR_UNIT = 13
private const val COMPACT_NUM = 26 // 宽度不足降一档（§5.3 兜底）
private const val COMPACT_UNIT = 12

/**
 * 「按可用宽度选字号」的测量策略。
 *
 * 只测**选中的那一档**：Compose 规定同一个 Measurable 在一次布局里只能测一次，
 * 所以不能用「先量一遍看看多宽、再按新约束量一遍」的两趟写法（那会抛
 * `measure() may not be called multiple times on the same Measurable`）。
 * 宽度判定走 [fits] 的纯算术估算，不依赖任何测量结果。
 *
 * intrinsic 四个方法都委托给常规档：略偏保守——卡片可能高一点点，但绝不会把数字裁掉。
 */
private class MetricTextPolicy(
    private val parts: List<MetricPart>,
    private val fontScale: Float
) : MeasurePolicy {

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints
    ): MeasureResult {
        val useRegular = fits(
            parts = parts,
            numSp = REGULAR_NUM,
            unitSp = REGULAR_UNIT,
            fontScale = fontScale,
            maxWidthDp = constraints.maxWidth.toDp().value
        )
        val placeable = measurables[if (useRegular) 0 else 1]
            .measure(constraints.copy(minWidth = 0, minHeight = 0))
        return layout(placeable.width, placeable.height) {
            placeable.place(0, 0)
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(
        measurables: List<IntrinsicMeasurable>,
        height: Int
    ): Int = measurables[0].maxIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(
        measurables: List<IntrinsicMeasurable>,
        height: Int
    ): Int = measurables[0].maxIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.minIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int
    ): Int = measurables[0].maxIntrinsicHeight(width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int
    ): Int = measurables[0].maxIntrinsicHeight(width)
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
