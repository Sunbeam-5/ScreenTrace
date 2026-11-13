package com.screentrace.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CR-14：日期弹层选项按入口页裁剪的映射规则。
 * 变异锚点：任何一档映射写错（如 Stats 误回 DAY、二级页误回 RANGE）都会变红。
 */
class DateSheetModeTest {

    @Test
    fun `today and calendar pages get day-only sheet`() {
        assertEquals(DateSheetMode.DAY, dateSheetModeFor(AppPage.Today))
        assertEquals(DateSheetMode.DAY, dateSheetModeFor(AppPage.Calendar))
    }

    @Test
    fun `stats page gets range-only sheet`() {
        assertEquals(DateSheetMode.RANGE, dateSheetModeFor(AppPage.Stats))
    }

    @Test
    fun `detail pages keep the full sheet`() {
        assertEquals(DateSheetMode.FULL, dateSheetModeFor(null))
        assertEquals(DateSheetMode.FULL, dateSheetModeFor(AppPage.Ranking()))
        assertEquals(DateSheetMode.FULL, dateSheetModeFor(AppPage.UnlockDetail()))
        assertEquals(DateSheetMode.FULL, dateSheetModeFor(AppPage.NotificationDetail()))
        assertEquals(DateSheetMode.FULL, dateSheetModeFor(AppPage.Period()))
    }
}
