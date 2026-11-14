package com.screentrace.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CR-14 / CR-16：日期弹层选项按入口页裁剪；CR-16 起二级页跟随归属状态。
 *
 * 变异锚点（改坏实现必须变红）：
 * - `Stats` 误回 `DAY` / `Today` 误回 `RANGE`；
 * - 二级页误回 `FULL`（**CR-16 之前的实现，正是 P1-1 两个漏洞的来源**）；
 * - 二级页忽略 `returnTo`、一律按 `Today` 处理（从统计页进详情就能写出单日）；
 * - 弹层模式与写回归属分叉（`dateSheetModeFor` 与 `dateScopeFor` 不同源）。
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

    /**
     * CR-16 核心断言：二级页的选项集必须与它的写回目标同量纲。
     * 从今日页进 → 写 `todaySelection` → 只能是 DAY；从统计页进 → 写 `statsSelection` → 只能是 RANGE。
     * **CR-18 例外：排行不在本组**——排行恒按「锚点单日」聚合，弹层一律 DAY，见
     * [ranking always gets day-only sheet]。
     */
    @Test
    fun `detail pages follow the returnTo scope`() {
        listOf(
            AppPage.UnlockDetail(AppPage.Today),
            AppPage.NotificationDetail(AppPage.Today)
        ).forEach { page ->
            assertEquals("$page 从今日页侧写回，弹层必须只有单日", DateSheetMode.DAY, dateSheetModeFor(page))
        }

        listOf(
            AppPage.UnlockDetail(AppPage.Stats),
            AppPage.NotificationDetail(AppPage.Stats)
        ).forEach { page ->
            assertEquals("$page 从统计页侧写回，弹层必须只有区间", DateSheetMode.RANGE, dateSheetModeFor(page))
        }
    }

    /**
     * CR-18（负责人 2026-11-15 裁决）：排行弹层**恒为单日集合**——
     * 排行只做「锚点单日」聚合（§5.7 排行-2），不吃区间量纲，无论从哪个主页面
     * 进入（含经周期总结的统计侧入口）都给「今天 / 昨天 / 指定单日」。
     * 这是有意打破 CR-16「弹层量纲 = 写回状态量纲」的**唯一例外**：
     * 显式选日写回归属状态（排行上选日同时退出周期窗口）。
     */
    @Test
    fun `ranking always gets day-only sheet`() {
        assertEquals(DateSheetMode.DAY, dateSheetModeFor(AppPage.Ranking(AppPage.Today)))
        assertEquals(DateSheetMode.DAY, dateSheetModeFor(AppPage.Ranking(AppPage.Stats)))
    }

    /** 弹层模式与写回归属必须同源——防「改了 dateScopeFor 忘了 dateSheetModeFor」。
     *  排行是 CR-18 明文例外（恒 DAY），由上一用例单独钉住，不进本组。 */
    @Test
    fun `sheet mode never disagrees with the scope it writes to`() {
        listOf(
            AppPage.Today,
            AppPage.Stats,
            AppPage.UnlockDetail(AppPage.Today),
            AppPage.UnlockDetail(AppPage.Stats),
            AppPage.NotificationDetail(AppPage.Today),
            AppPage.NotificationDetail(AppPage.Stats)
        ).forEach { page ->
            val expected = when (dateScopeFor(page)) {
                DateScope.TODAY -> DateSheetMode.DAY
                DateScope.STATS -> DateSheetMode.RANGE
                null -> DateSheetMode.FULL
            }
            assertEquals("$page 的弹层模式与写回归属不一致", expected, dateSheetModeFor(page))
        }
    }

    /** 无归属页面的兜底集合：CR-16 后 FULL 只剩「传 null」这一条路径。 */
    @Test
    fun `null page falls back to the full sheet`() {
        assertEquals(DateSheetMode.FULL, dateSheetModeFor(null))
    }
}
