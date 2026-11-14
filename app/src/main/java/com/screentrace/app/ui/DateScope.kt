package com.screentrace.app.ui

import com.screentrace.app.contract.DateSelection
import java.time.LocalDate

/**
 * CR-15 B：日期筛选状态的归属（§5.6）。
 *
 * 原先四个页面共享一个全局 `DateSelection`，负责人在真机上明确要求「今日 tab 与
 * 统计 tab 各自单独生效、互不影响」。现在 ViewModel 持有两份独立状态：
 * [TODAY] 管今日页与它的两个下钻详情页，[STATS] 管统计页与周期总结。
 */
enum class DateScope { TODAY, STATS }

/**
 * 页面 → 日期状态归属；返回 null 表示该页不提供日期筛选入口（日历 tab，以及
 * 设置 / 云同步 / 应用分类等本来就没有日期按钮的二级页）。
 *
 * 二级页按 [AppPage.mainOf]（`returnTo`）归到**进入它的那个主页面**，因此从
 * 今日页点进排行 / 解锁详情 / 通知详情，弹层写回的仍是今日页状态；从统计页
 * 经周期总结进入排行，写回的则是统计页状态。
 */
fun dateScopeFor(page: AppPage?): DateScope? = when (page?.let { AppPage.mainOf(it) }) {
    AppPage.Today -> DateScope.TODAY
    AppPage.Stats -> DateScope.STATS
    else -> null
}

/**
 * 日历页专用「全量历史」窗口：锚点固定今天，向前覆盖 20 年（App 不可能有更早数据）。
 *
 * 只用于需要 `DateSelection` 参数的仓库查询（如 extremes）；`streak(anchor)` 与
 * `yearLevels(anchor)` 本身就是全量历史 / 过去 20 周，锚点直接传今天。
 * 日历因此完全不受今日、统计两份筛选影响。
 */
fun historySelection(now: LocalDate = LocalDate.now()): DateSelection.Range =
    DateSelection.Range(now.minusYears(20), now)
