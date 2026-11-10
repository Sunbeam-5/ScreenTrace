package com.screentrace.app.ui

import androidx.compose.runtime.Composable
import com.screentrace.app.uikit.EmptyState

/**
 * 排行页占位（批次 3，二级页：返回 + 标题，底部导航保持「今日」选中，§5.2/§5.5）。
 * 批次 4 按 §5.7 填充：摘要卡（累计、应用数、分类构成图例）→ top20 列表
 * （42dp 图标 / 9dp 进度条 / ≥48dp 点击区）→「显示全部 N 个应用」真实展开。
 */
@Composable
fun RankingScreen() {
    EmptyState(
        title = "暂无应用数据",
        description = "完成首次拉取后，这里会显示应用使用排行"
    )
}
