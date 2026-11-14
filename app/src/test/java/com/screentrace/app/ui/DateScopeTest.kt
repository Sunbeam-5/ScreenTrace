package com.screentrace.app.ui

import com.screentrace.app.contract.DateSelection
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CR-15 B：日期状态归属的纯函数回归。
 * 重点防「二级页弹层写错状态」——必须按 `returnTo` 归到进入它的主页面，
 * 而不是一律写今日页或一律写统计页。
 */
class DateScopeTest {

    @Test
    fun `三个主页面各自的日期归属`() {
        assertEquals(DateScope.TODAY, dateScopeFor(AppPage.Today))
        assertEquals(DateScope.STATS, dateScopeFor(AppPage.Stats))
        assertNull("日历 tab 不参与日期筛选，不该有归属", dateScopeFor(AppPage.Calendar))
    }

    @Test
    fun `二级页按 returnTo 归属进入它的主页面`() {
        assertEquals(DateScope.TODAY, dateScopeFor(AppPage.Ranking(returnTo = AppPage.Today)))
        assertEquals(DateScope.STATS, dateScopeFor(AppPage.Ranking(returnTo = AppPage.Stats)))
        assertEquals(DateScope.STATS, dateScopeFor(AppPage.Period(returnTo = AppPage.Stats)))
        assertEquals(DateScope.TODAY, dateScopeFor(AppPage.UnlockDetail(returnTo = AppPage.Today)))
        assertEquals(DateScope.TODAY, dateScopeFor(AppPage.NotificationDetail(returnTo = AppPage.Today)))
        assertNull("从日历页进周期总结也没有日期入口", dateScopeFor(AppPage.Period(returnTo = AppPage.Calendar)))
        assertNull(dateScopeFor(null))
    }

    @Test
    fun `全量历史窗口锚定今天且覆盖多年`() {
        val now = LocalDate.of(2026, 11, 14)
        val history = historySelection(now)
        assertEquals(now, history.endDate)
        assertEquals(now.minusYears(20), history.startDate)
        assertTrue("日历必须覆盖远超过 UI 366 天上限的历史", history.days > 366 * 19)
    }
}
