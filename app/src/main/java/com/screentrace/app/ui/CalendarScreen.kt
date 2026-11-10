package com.screentrace.app.ui

import androidx.compose.runtime.Composable
import com.screentrace.app.uikit.EmptyState

/**
 * 日历页占位（批次 3）。批次 4 按 §5.7 填充：连续记录卡（streak）→ 月/年
 * SegmentedControl → MonthCalendar/HeatmapCalendar → 双卡（最久的一天/低于 2 小时）。
 */
@Composable
fun CalendarScreen() {
    EmptyState(
        title = "暂无日历数据",
        description = "完成首次拉取后，这里会按月/年展示每日使用记录"
    )
}
