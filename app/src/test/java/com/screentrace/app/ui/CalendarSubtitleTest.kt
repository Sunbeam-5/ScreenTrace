package com.screentrace.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CR-15 评审 P2-3：日历 tab 不参与日期筛选，副标题不该回落到今日状态的「今天」。
 * 变异锚点：把 `calendarSubtitle(null)` 的兜底改回 `selectionSubtitle(today)`，
 * 第一条必须变红。
 */
class CalendarSubtitleTest {

    @Test
    fun `加载中或无数据时显示全量历史而不是今天`() {
        assertEquals("全量历史", calendarSubtitle(null))
    }

    @Test
    fun `有数据时显示已记录天数`() {
        assertEquals("已记录 5 天", calendarSubtitle(5))
        // 真实 0 天也要如实显示，不能假装有数据
        assertEquals("已记录 0 天", calendarSubtitle(0))
    }
}
