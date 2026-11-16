package com.screentrace.app.ui

import com.screentrace.app.uikit.HeaderAction

/**
 * §5.2 CR-20 ①：按主页面推导**顶栏按钮集合**。
 *
 * 纯函数、无 Compose 依赖，便于单测「日历 tab 三枚且不含选择日期」。
 * 判据复用 `dateScopeFor`（CR-15 B 双状态口径）——该页不参与日期筛选就不给日期按钮，
 * 不再额外维护一套「哪些页面有日期」的判断，避免两处漂移。
 *
 * 顺序即顶栏从左到右的顺序：切换设备 / 日期 / 主题 / 设置。
 */
fun headerActionsFor(page: AppPage): List<HeaderAction> = buildList {
    add(HeaderAction.Devices)
    if (dateScopeFor(page) != null) add(HeaderAction.Date)
    add(HeaderAction.Theme)
    add(HeaderAction.Settings)
}
