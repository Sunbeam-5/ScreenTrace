package com.screentrace.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.uikit.EmptyState

/**
 * 「整页没东西可画」的两种原因对应两套文案（6b-6d 评审 P0-1）。
 *
 * 为什么必须分开：降级通道（`UsageStats`，也是没有 Shizuku 的普通用户的唯一通道）
 * 按设计只写 `app_usage` / `hour_usage`，不写 `day_summary`。此时屏幕时长、解锁、
 * 通知**确实没有事件源**，页面只能空着——但用户刚刚看到「正在拉取…」然后又看到
 * 「尚无使用记录 / 连接 Shizuku 并完成首次拉取」，只会认为 App 坏了。
 * 两种情况的区别对用户是有意义的：一种是「去装 Shizuku 就能看到」，一种是「真的还没数据」。
 *
 * 文案只在这里写一份，四个页面共用，避免各页各写一遍又慢慢漂移。
 */
@Composable
internal fun DataEmptyState(
    reason: EmptyReason?,
    noDataTitle: String,
    noDataDescription: String,
    modifier: Modifier = Modifier,
    /** [EmptyReason.NO_EVENT_DATA] 时的额外出口（例如「查看应用排行」），可为空。 */
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    if (reason == EmptyReason.NO_EVENT_DATA) {
        EmptyState(
            title = "暂无屏幕使用统计",
            description = "已拉取到应用使用数据，但屏幕时长、解锁与通知需要 Shizuku 授权" +
                "（当前为系统「使用情况访问」降级通道）。应用排行不受影响。",
            modifier = modifier,
            actionText = actionText,
            onAction = onAction
        )
    } else {
        EmptyState(
            title = noDataTitle,
            description = noDataDescription,
            modifier = modifier
        )
    }
}

/**
 * 「还没数据，但正在拉取」——必须和「真的没数据」分开画。
 *
 * 真机首装实测：`dumpsys usagestats` 的 dump 有 4MB，经 Shizuku 流式读回再解析要
 * 十几秒。这段时间库里是空的，如果直接落到 [DataEmptyState]，用户第一眼看到的是
 * 「尚无使用记录 / 连接 Shizuku 并完成首次拉取」，而此时他刚授权完、App 正在干活——
 * 看上去就是「授权了也没用」。
 */
@Composable
internal fun PullingState(modifier: Modifier = Modifier) {
    val palette = LocalFreshPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = palette.primary,
            strokeWidth = 3.dp
        )
        Text(
            text = "正在读取使用记录",
            style = FreshType.sectionTitle,
            color = palette.onSurface
        )
        Text(
            text = "首次读取需要十几秒，请稍候",
            style = FreshType.body,
            color = palette.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
