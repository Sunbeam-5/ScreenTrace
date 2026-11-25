package com.screentrace.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CR-30 阶段 D：窄屏 DatePicker 密度缩放计算单测。
 * 变异锚点（M-D4）：当 availableWidthDp < 360f 时固定返回 1f，第一、二条测试必须变红。
 */
class DatePickerLayoutMathTest {

    @Test
    fun `可用宽度大于等于 360dp 时不缩放`() {
        val exact = DatePickerLayoutMath.compute(360f)
        assertEquals(1f, exact.densityScale, 0.0001f)
        assertEquals(360f, exact.minRequiredWidthDp, 0.0001f)

        val wide = DatePickerLayoutMath.compute(412f)
        assertEquals(1f, wide.densityScale, 0.0001f)
        assertEquals(412f, wide.minRequiredWidthDp, 0.0001f)
    }

    @Test
    fun `窄于 360dp 时等比缩放到正好容纳 7 列`() {
        val narrow = DatePickerLayoutMath.compute(324f)
        assertEquals(0.9f, narrow.densityScale, 0.0001f)
        assertEquals(360f, narrow.minRequiredWidthDp, 0.0001f)
        assertTrue(narrow.densityScale < 1f)
    }

    @Test
    fun `极窄或非正宽度兜底到 0_75 下限`() {
        val tiny = DatePickerLayoutMath.compute(200f)
        assertEquals(DatePickerLayoutMath.MIN_DENSITY_SCALE, tiny.densityScale, 0.0001f)

        val zero = DatePickerLayoutMath.compute(0f)
        assertEquals(DatePickerLayoutMath.MIN_DENSITY_SCALE, zero.densityScale, 0.0001f)
    }
}
