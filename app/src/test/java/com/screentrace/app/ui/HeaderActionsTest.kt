package com.screentrace.app.ui

import com.screentrace.app.uikit.HeaderAction
import com.screentrace.app.uikit.HeaderTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5.2 CR-20 ①：顶栏四枚独立圆按钮的集合与接线约定。
 *
 * 批 D 的下拉面板形态已撤销，这里钉的是新形态：
 * 顺序固定、历 tab 少一枚「选择日期」、四枚动作各有互不相同的真实入口。
 */
class HeaderActionsTest {

    private val full = listOf(
        HeaderAction.Devices,
        HeaderAction.Date,
        HeaderAction.Theme,
        HeaderAction.Settings
    )

    @Test
    fun `今日与统计页都是四枚且顺序固定`() {
        assertEquals(full, headerActionsFor(AppPage.Today))
        assertEquals(full, headerActionsFor(AppPage.Stats))
    }

    @Test
    fun `日历 tab 三枚且不含选择日期`() {
        val actions = headerActionsFor(AppPage.Calendar)
        assertEquals(3, actions.size)
        assertFalse(actions.contains(HeaderAction.Date))
        assertEquals(
            listOf(HeaderAction.Devices, HeaderAction.Theme, HeaderAction.Settings),
            actions
        )
    }

    @Test
    fun `二级页按 returnTo 归属决定按钮集合`() {
        assertEquals(4, headerActionsFor(AppPage.Ranking(returnTo = AppPage.Today)).size)
        val fromCalendar = headerActionsFor(AppPage.Ranking(returnTo = AppPage.Calendar))
        assertEquals(3, fromCalendar.size)
        assertFalse(fromCalendar.contains(HeaderAction.Date))
    }

    @Test
    fun `四枚动作各对应一个互不相同的真实入口 不放假按钮`() {
        val targets = HeaderAction.values().map { it.target }
        assertEquals("动作数与入口数必须一一对应", HeaderAction.values().size, targets.size)
        assertEquals("四个动作不能撞同一个入口", targets.size, targets.toSet().size)
        assertTrue(targets.contains(HeaderTarget.DEVICE_PANEL))
        assertTrue(targets.contains(HeaderTarget.DATE_SHEET))
        assertTrue(targets.contains(HeaderTarget.STYLE_SHEET))
        assertTrue(targets.contains(HeaderTarget.SETTINGS_PAGE))
    }

    @Test
    fun `每枚动作都有非空且互不相同的读屏描述`() {
        val descriptions = HeaderAction.values().map { it.description }
        assertTrue("读屏描述不能为空", descriptions.none { it.isBlank() })
        assertEquals("读屏描述不能重复", descriptions.size, descriptions.toSet().size)
    }
}
