package com.screentrace.app.ui

import com.screentrace.app.uikit.QuickMenuAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickMenuActionsTest {

    private val full = listOf(
        QuickMenuAction.Settings,
        QuickMenuAction.Theme,
        QuickMenuAction.Date,
        QuickMenuAction.Devices
    )

    @Test
    fun `今日与统计页都是一行四枚且顺序固定`() {
        assertEquals(full, quickMenuActionsFor(AppPage.Today))
        assertEquals(full, quickMenuActionsFor(AppPage.Stats))
    }

    @Test
    fun `日历 tab 三枚且不含选择日期`() {
        val actions = quickMenuActionsFor(AppPage.Calendar)
        assertEquals(3, actions.size)
        assertFalse(actions.contains(QuickMenuAction.Date))
        assertEquals(
            listOf(QuickMenuAction.Settings, QuickMenuAction.Theme, QuickMenuAction.Devices),
            actions
        )
    }

    @Test
    fun `二级页按 returnTo 归属决定按钮集合`() {
        assertEquals(4, quickMenuActionsFor(AppPage.Ranking(returnTo = AppPage.Today)).size)
        val fromCalendar = quickMenuActionsFor(AppPage.Ranking(returnTo = AppPage.Calendar))
        assertEquals(3, fromCalendar.size)
        assertFalse(fromCalendar.contains(QuickMenuAction.Date))
    }

    @Test
    fun `四枚按钮都有真实动作 不放假按钮`() {
        QuickMenuAction.values().forEach { action ->
            val t = quickMenuTransitionFor(action)
            assertTrue(
                "$action 没有任何动作",
                t.openSettings || t.openStyleSheet || t.openDateSheet || t.showDevices
            )
        }
    }

    @Test
    fun `设置 主题 日期都收起面板且各开各的入口`() {
        val s = quickMenuTransitionFor(QuickMenuAction.Settings)
        assertTrue(s.closeMenu)
        assertTrue(s.openSettings)
        assertFalse(s.openStyleSheet)
        assertFalse(s.openDateSheet)
        assertFalse(s.showDevices)

        val t = quickMenuTransitionFor(QuickMenuAction.Theme)
        assertTrue(t.closeMenu)
        assertTrue(t.openStyleSheet)
        assertFalse(t.openSettings)
        assertFalse(t.openDateSheet)

        val d = quickMenuTransitionFor(QuickMenuAction.Date)
        assertTrue(d.closeMenu)
        assertTrue(d.openDateSheet)
        assertFalse(d.openSettings)
        assertFalse(d.openStyleSheet)
    }

    @Test
    fun `切换设备保持面板打开且只切到设备子面板`() {
        val t = quickMenuTransitionFor(QuickMenuAction.Devices)
        assertTrue(t.showDevices)
        assertFalse(t.closeMenu)
        assertFalse(t.openSettings)
        assertFalse(t.openStyleSheet)
        assertFalse(t.openDateSheet)
    }
}
