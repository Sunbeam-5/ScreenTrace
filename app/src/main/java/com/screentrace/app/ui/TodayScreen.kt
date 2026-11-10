package com.screentrace.app.ui

import androidx.compose.runtime.Composable
import com.screentrace.app.uikit.EmptyState

/**
 * 今日页占位（批次 3）：EmptyUsageRepository 空态即产品真实空态（§5.5 禁止假数据）。
 * 批次 4 按 §5.7 填充：hero 圆环卡（副信息含最长连续）→ 可下钻双卡
 * 「解锁次数 / 通知打扰」（6d 前按纯摘要卡渲染）→ 应用构成卡。
 */
@Composable
fun TodayScreen() {
    EmptyState(
        title = "尚无使用记录",
        description = "连接 Shizuku 并完成首次拉取后，这里会显示今日的使用数据"
    )
}
