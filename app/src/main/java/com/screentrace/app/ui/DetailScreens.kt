package com.screentrace.app.ui

import androidx.compose.runtime.Composable
import com.screentrace.app.uikit.EmptyState

/**
 * CR-01 指标详情页最小占位（批次 4 建路由，批 6d 按 §5.7 详情页规范填充：
 * 摘要区大数字小单位 +「图表/数据表」SegmentedControl + 柱图（按住气泡）+
 * 数据表 + 明细区——两页共用契约 v0.3 的同一 metricDetail 查询）。
 * 占位期诚实标注，不显示任何假数值（§6）。
 */
@Composable
fun UnlockDetailScreen() {
    EmptyState(
        title = "解锁详情建设中",
        description = "详情页将在批次 6d 实现：单日逐小时 / 区间逐日图表、数据表与时段摘要"
    )
}

@Composable
fun NotificationDetailScreen() {
    EmptyState(
        title = "通知详情建设中",
        description = "详情页将在批次 6d 实现：打扰 / 已查看双指标、逐时段图表与应用通知排行"
    )
}
