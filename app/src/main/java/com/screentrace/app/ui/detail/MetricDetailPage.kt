package com.screentrace.app.ui.detail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.screentrace.app.contract.Coverage
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.MetricDetail
import com.screentrace.app.contract.MetricKind
import com.screentrace.app.contract.MetricPoint
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme
import com.screentrace.app.uikit.MetricBarChart
import com.screentrace.app.uikit.SegmentedControl
import java.time.LocalDate

/**
 * §5.7 指标详情页共用骨架（解锁 / 通知两页只替换摘要与明细区）：
 * 摘要卡 →「图表 / 数据表」SegmentedControl → 图表或数据表 → 明细区。
 *
 * 三处刻意的实现选择：
 * 1. **图表与表格吃同一份 [MetricDetail]**（同一查询结果），不各算一套，数字不可能对不上；
 * 2. 切换用「旧淡出 120ms → 换内容 → 新淡入 160ms」，**容器高度固定**不随内容跳动
 *    （表格在固定高度内自己滚动，不用弹窗承载大表格——§5.7 明确禁止）；
 * 3. 减少动画时直接切，不播淡入淡出（§5.7 第 3 条）。
 */
@Composable
fun MetricDetailPage(
    detail: MetricDetail,
    summary: @Composable () -> Unit,
    detailSection: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalFreshPalette.current
    var tab by rememberSaveable(detail.metric) { mutableStateOf(0) }
    // 实际渲染的 tab 与目标 tab 分开：淡出结束后才换内容，避免「半透明时已经换了数据」
    var shownTab by remember(detail.metric) { mutableStateOf(tab) }
    val alpha = remember(detail.metric) { Animatable(1f) }
    val reduceMotion = com.screentrace.app.uikit.rememberReduceMotion()

    LaunchedEffect(tab) {
        if (tab == shownTab) return@LaunchedEffect
        if (reduceMotion) {
            shownTab = tab
            alpha.snapTo(1f)
            return@LaunchedEffect
        }
        alpha.animateTo(0f, tween(120))
        shownTab = tab
        alpha.animateTo(1f, tween(160))
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 摘要区
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(palette.surface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            summary()
        }

        SegmentedControl(
            items = listOf("图表", "数据表"),
            selectedIndex = tab,
            onSelect = { tab = it }
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(CONTENT_HEIGHT)
                .clip(RoundedCornerShape(24.dp))
                .background(palette.surface)
                .padding(horizontal = 12.dp, vertical = 16.dp)
        ) {
            Box(Modifier.alpha(alpha.value)) {
                when (shownTab) {
                    0 -> MetricBarChart(
                        points = detail.points,
                        bubbleLabel = { bubbleLabel(detail.metric, it) },
                        xLabel = { xLabel(it) },
                        modifier = Modifier.align(Alignment.Center)
                    )

                    else -> MetricDataTable(
                        detail = detail,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // 明细区
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(palette.surface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "明细", style = FreshType.sectionTitle, color = palette.onSurface)
            detailSection()
        }
    }
}

/** 表格：三列「时间段 / 次数 / 占比」，时间升序，占比分母 = 区间合计（§5.7）。 */
@Composable
private fun MetricDataTable(detail: MetricDetail, modifier: Modifier = Modifier) {
    val palette = LocalFreshPalette.current
    val rows = MetricTable.rows(detail)
    val single = detail.selection is DateSelection.Day
    val emptyHint = if (single) "该时段无记录" else "该日无记录"

    Column(modifier = modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "时间段",
                style = FreshType.label,
                color = palette.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "次数",
                style = FreshType.label,
                color = palette.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(0.9f)
            )
            Text(
                text = "占比",
                style = FreshType.label,
                color = palette.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(0.8f)
            )
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            rows.forEach { row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = row.label,
                        style = FreshType.body,
                        color = if (row.hasRecord) palette.onSurface else palette.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = row.count?.toString() ?: emptyHint,
                        style = FreshType.body,
                        color = if (row.hasRecord) palette.onSurface else palette.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.weight(0.9f)
                    )
                    Text(
                        text = row.percentText,
                        style = FreshType.body,
                        color = palette.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(0.8f)
                    )
                }
            }
        }
    }
}

/** 图表横轴标签：单日小时「0…23」，区间「11月8日」。 */
internal fun xLabel(point: MetricPoint): String =
    point.hour?.toString() ?: "${point.date.monthValue}/${point.date.dayOfMonth}"

/** 气泡文案：通知按「条」，解锁按「次」（§5.7 示例口径）。 */
internal fun bubbleLabel(metric: MetricKind, point: MetricPoint): String {
    val unit = if (metric == MetricKind.NOTIFICATION) "条" else "次"
    val whenText = point.hour?.let { h ->
        if (h == 23) "23–24时" else "$h–${h + 1}时"
    } ?: MetricTable.dateLabel(point.date)
    return "$whenText · ${point.value}$unit"
}

/** 内容容器固定高度：图表与表格切换时页面不跳动（§5.7 第 3 条）。 */
private val CONTENT_HEIGHT = 264.dp

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun MetricDetailPagePreview() {
    ScreenTimeTheme {
        val day = LocalDate.of(2026, 11, 8)
        val points = (0..23).map { h ->
            MetricPoint(day, h, if (h in 8..23) (h * 5) % 23 else 0, Coverage.RECORDED)
        }
        val detail = MetricDetail(
            metric = MetricKind.UNLOCK,
            selection = DateSelection.Day(day),
            hasEventSource = true,
            total = points.sumOf { it.value },
            dailyAverage = null,
            notification = null,
            points = points,
            appRanking = emptyList(),
            peakHour = 21,
            hourlyAverage = 3.4,
            busiestDate = day
        )
        Box(Modifier.padding(20.dp)) {
            MetricDetailPage(
                detail = detail,
                summary = {
                    Text("38", style = FreshType.heroNumber, color = LocalFreshPalette.current.onSurface)
                    Text("当日累计", style = FreshType.label, color = LocalFreshPalette.current.onSurfaceVariant)
                },
                detailSection = {
                    Text("最高频时段 21–22时", style = FreshType.body, color = LocalFreshPalette.current.onSurface)
                }
            )
        }
    }
}
