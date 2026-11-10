package com.screentrace.app.ui

import androidx.compose.runtime.Composable
import com.screentrace.app.uikit.EmptyState

/**
 * 统计页占位（批次 3）。批次 4 按 §5.7 填充：趋势卡（日均大数字 + 对比 +
 * CapsuleBarChart）→ 时段分布 SegmentedBar → 最久/最少双卡 → 洞察（数据足够才显示）。
 */
@Composable
fun StatsScreen() {
    EmptyState(
        title = "暂无统计数据",
        description = "完成首次拉取后，这里会显示使用趋势与时段分布"
    )
}
