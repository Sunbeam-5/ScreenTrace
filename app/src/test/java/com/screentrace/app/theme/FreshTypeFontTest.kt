package com.screentrace.app.theme

import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * CR-06 / VI-1-1：数字 token 用 Manrope + tnum，中文 token 留系统字体。
 * 变异验证过：把 heroNumber.fontFamily 改回 Default 时本测试变红。
 */
class FreshTypeFontTest {

    @Test
    fun `数字 token 使用 Manrope 并开等宽数字`() {
        assertNotEquals(FontFamily.Default, FreshType.heroNumber.fontFamily)
        assertNotEquals(FontFamily.Default, FreshType.metricNumber.fontFamily)
        assertEquals("'tnum'", FreshType.heroNumber.fontFeatureSettings)
        assertEquals("'tnum'", FreshType.metricNumber.fontFeatureSettings)
    }

    @Test
    fun `中文文本 token 保持系统字体`() {
        // 中文走系统（MIUI = MiSans），不打包完整中文字体（§5.3 CR-06 取舍）
        assertEquals(FontFamily.Default, FreshType.pageTitle.fontFamily)
        assertEquals(FontFamily.Default, FreshType.sectionTitle.fontFamily)
        assertEquals(FontFamily.Default, FreshType.body.fontFamily)
        assertEquals(FontFamily.Default, FreshType.label.fontFamily)
        assertEquals(FontFamily.Default, FreshType.button.fontFamily)
    }
}
