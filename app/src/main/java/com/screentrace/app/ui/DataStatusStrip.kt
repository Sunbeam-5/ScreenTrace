package com.screentrace.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.screentrace.app.data.pull.CollectionQuickAction
import com.screentrace.app.data.pull.DataCollectionState
import com.screentrace.app.data.pull.DataCollectionVerdict
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette

/**
 * CR-30 阶段 E（§7-67）：主页面顶部轻量数据状态条。
 *
 * 设计约束：
 * - 仅在 [DataCollectionVerdict.showOnMainScreen] 为 true 时由外层 `AnimatedVisibility` 展开，
 *   绝不对主页面内容套 `Crossfade`/`AnimatedContent`，避免触发圆环/图表重建闪帧；
 * - 正常态（NORMAL）与整页空态已承载的 NO_RECORD 不显示，保持主屏干净不喧宾夺主；
 * - 支持 1.3x–1.5x 大字号（文案 `weight(1f)` + `maxLines = 2`，高度不写死）与 TalkBack 合并语义。
 */
@Composable
internal fun DataStatusStrip(
    verdict: DataCollectionVerdict,
    onAction: (CollectionQuickAction) -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalFreshPalette.current
    val isError = verdict.state == DataCollectionState.FAILED ||
        verdict.state == DataCollectionState.UNAUTHORIZED ||
        verdict.state == DataCollectionState.SERVICE_UNAVAILABLE
    val isWarning = verdict.state == DataCollectionState.PARTIAL ||
        verdict.state == DataCollectionState.STALE

    val bgColor = when {
        isError -> palette.overTargetSurface
        isWarning -> palette.secondaryContainer
        else -> palette.panelSurface
    }
    val fgColor = when {
        isError -> palette.overTarget
        isWarning -> palette.onSecondaryContainer
        else -> palette.onSurfaceVariant
    }
    val dotColor = when {
        isError -> palette.overTarget
        else -> palette.primary
    }

    val clickableRow = verdict.state != DataCollectionState.PULLING
    val actionLabel = verdict.quickActionLabel
    val semanticDesc = buildString {
        append("数据状态：")
        append(verdict.title)
        append("，")
        append(verdict.bannerText)
        if (!actionLabel.isNullOrBlank()) {
            append("，点击")
            append(actionLabel)
        } else if (clickableRow) {
            append("，点击查看诊断")
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .semantics(mergeDescendants = true) {
                contentDescription = semanticDesc
                if (clickableRow) role = Role.Button
            }
            .then(
                if (clickableRow) {
                    Modifier.clickable {
                        if (verdict.quickAction != CollectionQuickAction.NONE) {
                            onAction(verdict.quickAction)
                        } else {
                            onOpenDiagnostics()
                        }
                    }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (verdict.state == DataCollectionState.PULLING) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = palette.primary,
                strokeWidth = 2.dp
            )
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
        Text(
            text = verdict.bannerText,
            style = FreshType.label,
            color = fgColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!actionLabel.isNullOrBlank()) {
            Text(
                text = actionLabel,
                style = FreshType.button,
                color = if (isError) palette.overTarget else palette.primary,
                maxLines = 1
            )
        }
    }
}
