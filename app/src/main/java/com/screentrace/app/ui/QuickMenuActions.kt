package com.screentrace.app.ui

import com.screentrace.app.uikit.QuickMenuAction

/**
 * §5.2 批 D：按主页面推导下拉面板里的按钮集合。
 *
 * 纯函数、无 Compose 依赖，便于单测「日历 tab 三枚且不含选择日期」。
 * 判据复用 `dateScopeFor`（CR-15 B 双状态口径）——该页不参与日期筛选就不给日期按钮，
 * 不再额外维护一套「哪些页面有日期」的判断，避免两处漂移。
 */
fun quickMenuActionsFor(page: AppPage): List<QuickMenuAction> = buildList {
    add(QuickMenuAction.Settings)
    add(QuickMenuAction.Theme)
    if (dateScopeFor(page) != null) add(QuickMenuAction.Date)
    add(QuickMenuAction.Devices)
}

/**
 * 点某枚按钮后的**状态变更**（纯数据，不含副作用）。
 *
 * 抽出来的目的：四枚按钮「都要有真实动作、不放假按钮」这条红线能单测 + 变异验证，
 * 而不是只能靠真机点一遍。`AppNav` 只负责按这份描述改状态 / 调 ViewModel。
 */
data class QuickMenuTransition(
    val closeMenu: Boolean = false,
    val showDevices: Boolean = false,
    val openSettings: Boolean = false,
    val openStyleSheet: Boolean = false,
    val openDateSheet: Boolean = false
)

fun quickMenuTransitionFor(action: QuickMenuAction): QuickMenuTransition = when (action) {
    QuickMenuAction.Settings -> QuickMenuTransition(closeMenu = true, openSettings = true)
    QuickMenuAction.Theme -> QuickMenuTransition(closeMenu = true, openStyleSheet = true)
    QuickMenuAction.Date -> QuickMenuTransition(closeMenu = true, openDateSheet = true)
    // 设备面板是下拉内的第二层，面板本身保持打开
    QuickMenuAction.Devices -> QuickMenuTransition(showDevices = true)
}
