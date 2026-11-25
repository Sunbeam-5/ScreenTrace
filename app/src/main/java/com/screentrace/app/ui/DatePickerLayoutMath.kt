package com.screentrace.app.ui

/**
 * CR-30 阶段 D（§7-68 / 审计项 #8）：窄屏 / vivo `DatePicker` 周日列不截断的自适应密度缩放计算。
 *
 * Material3 `DatePicker`（BOM 2024.06.00）内置 48dp 最小单元格 + 12dp×2 水平内边距，
 * 7 列日历至少需要 `48 * 7 + 24 = 360dp` 才不会裁掉最右侧周日列。
 * 不写死任何机型宽度：按实测可用宽度动态决定是否等比缩放密度，保留 0.75x 下限防止触控区过小。
 */
object DatePickerLayoutMath {
    const val MIN_CALENDAR_WIDTH_DP = 360f
    const val MIN_DENSITY_SCALE = 0.75f

    data class CalendarScaleSpec(
        val densityScale: Float,
        val minRequiredWidthDp: Float
    )

    fun compute(availableWidthDp: Float): CalendarScaleSpec {
        if (availableWidthDp <= 0f) {
            return CalendarScaleSpec(
                densityScale = MIN_DENSITY_SCALE,
                minRequiredWidthDp = MIN_CALENDAR_WIDTH_DP
            )
        }
        if (availableWidthDp >= MIN_CALENDAR_WIDTH_DP) {
            return CalendarScaleSpec(
                densityScale = 1f,
                minRequiredWidthDp = availableWidthDp
            )
        }
        val ratio = (availableWidthDp / MIN_CALENDAR_WIDTH_DP).coerceIn(MIN_DENSITY_SCALE, 1f)
        return CalendarScaleSpec(
            densityScale = ratio,
            minRequiredWidthDp = MIN_CALENDAR_WIDTH_DP
        )
    }
}
