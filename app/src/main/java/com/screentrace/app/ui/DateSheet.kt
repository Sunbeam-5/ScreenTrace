package com.screentrace.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme
import com.screentrace.app.uikit.PrimaryButton
import com.screentrace.app.uikit.SegmentedControl
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")

/** M3 DatePicker 以 UTC 日界存取 millis（与设备本地自然日的换算集中在此）。 */
private fun utcMillis(date: LocalDate): Long =
    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun localDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

/**
 * §5.5/§5.6 日期 Modal Bottom Sheet：28dp 顶圆角、拖动条、点内部不误关（M3 默认行为）。
 * 快捷项（今天/昨天/近 7 天/近 30 天/本月）点击即应用并关闭；指定单日 DatePicker、
 * 自定义区间 DateRangePicker（366 天上限拦截后禁用应用按钮）。
 * ModalBottomSheet / DatePicker / DateRangePicker 在 BOM 2024.06.00 下为实验 API，
 * 按 S04 决议接受 @OptIn、不为此升级 BOM。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateSheet(
    current: DateSelection,
    onApply: (DateSelection) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalFreshPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by rememberSaveable { mutableStateOf(0) }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = palette.surface,
        // 系统栏 inset 由我们自己处理（内容末尾的 navigationBarsPadding）。
        // 用默认值（BottomSheetDefaults.windowInsets）时，sheet 的 surface 会在
        // 导航栏上方就结束，而窗口已经 edge-to-edge → 底部露出一条背景色的缝，
        // 像 sheet 没铺满。清零后 surface 铺到屏幕底，避让交给内容。
        windowInsets = WindowInsets(0, 0, 0, 0),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 12.dp)
                    .width(32.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(palette.onSurfaceVariant)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 限高 86%：不限的话内容一高（DateRangePicker 尤其）sheet 会撑满整屏，
                // 顶部直接顶到状态栏与挖孔摄像头下面——真机上「选择日期」四个字被
                // 状态栏的时间和信号图标压住，顶圆角也看不见（bug 3）。
                // heightIn 必须写在 verticalScroll **之前**：先约束滚动容器本身的高度，
                // 滚动容器才会把内部内容按无限高测量并自己滚。
                // heightIn 必须写在 verticalScroll **之前**：先约束滚动容器本身的高度，
                // 滚动容器才会把内部内容按无限高测量并自己滚。
                .heightIn(max = screenHeight * 0.86f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                // 避让全面屏手势条（sheet 的 windowInsets 已清零，见上方注释）
                .navigationBarsPadding(),
            // 12dp 而不是 16dp：弹层可视高度有限（0.86 屏高），
            // 间距收紧后「应用」按钮不用滚动就能露出来
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "选择日期", style = FreshType.sectionTitle, color = palette.onSurface)

            // 快捷项：点击即应用并关闭（§5.6）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickChip("今天") { onApply(DateSelection.today()) }
                QuickChip("昨天") { onApply(DateSelection.Day(LocalDate.now().minusDays(1))) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickChip("近 7 天") { onApply(DateSelection.lastDays(7)) }
                QuickChip("近 30 天") { onApply(DateSelection.lastDays(30)) }
                QuickChip("本月") {
                    val today = LocalDate.now()
                    onApply(
                        DateSelection.Range(
                            YearMonth.from(today).atDay(1),
                            YearMonth.from(today).atEndOfMonth()
                        )
                    )
                }
            }

            SegmentedControl(items = listOf("指定单日", "自定义区间"), selectedIndex = tab, onSelect = { tab = it })

            // 指定单日 ↔ 自定义区间：滑动+淡入淡出切换（真机反馈 #3「过渡不丝滑」）。
            // 直接 if/else 换内容会瞬间整块替换，高度还突跳；AnimatedContent 会连
            // 容器高度一起动画，两个面板高度不同也不会跳。
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val forward = targetState > initialState
                    (
                        slideInHorizontally(tween(200, easing = FastOutSlowInEasing)) { w ->
                            if (forward) w / 4 else -w / 4
                        } + fadeIn(tween(160))
                    ).togetherWith(
                        slideOutHorizontally(tween(160)) { w ->
                            if (forward) -w / 4 else w / 4
                        } + fadeOut(tween(120))
                    )
                },
                label = "pickerMode"
            ) { t ->
                if (t == 0) {
                    SingleDayPicker(current = current, onApply = onApply)
                } else {
                    RangePicker(onApply = onApply)
                }
            }
        }
    }
}

@Composable
private fun QuickChip(label: String, onClick: () -> Unit) {
    val palette = LocalFreshPalette.current
    Box(
        modifier = Modifier
            // minimumInteractiveComponentSize：视觉高度按内容走（比原来的 48dp 矮一点、
            // 更像 M3 的 chip），但**点击区**仍保证 ≥48dp（§5.9）。
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .background(palette.buttonSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        // 文字偏上的根因：Box 默认 contentAlignment = TopStart，靠 padding 撑高度时
        // 文字贴在顶部，视觉上「卡片下半截是空的」。必须显式居中。
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, style = FreshType.button, color = palette.onSurface)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleDayPicker(current: DateSelection, onApply: (DateSelection) -> Unit) {
    val initial = utcMillis(if (current is DateSelection.Day) current.date else LocalDate.now())
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                !localDate(utcTimeMillis).isAfter(LocalDate.now())
        }
    )
    // title 传 null：弹层的「选择日期」标题已经在上面画过一次了，
    // DatePicker 自带的再画一遍就是同一个标题连着出现两次。
    DatePicker(state = state, title = null, showModeToggle = false)
    val selected = state.selectedDateMillis
    PrimaryButton(
        text = "应用",
        onClick = {
            if (selected != null) onApply(DateSelection.Day(localDate(selected)))
        },
        enabled = selected != null,
        modifier = Modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangePicker(onApply: (DateSelection) -> Unit) {
    val palette = LocalFreshPalette.current
    val state = rememberDateRangePickerState()
    DateRangePicker(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            // **必须**给一个有界高度。DateRangePicker 内部是 LazyColumn，被外层
            // verticalScroll 按无限高测量时直接抛：
            //   IllegalStateException: Vertically scrollable component was measured
            //   with an infinity maximum height constraints
            // 真机上点「自定义区间」100% 闪退（bug 11）。给固定高度后内部自己滚，
            // 400dp 约等于 6 周日历 + 星期表头，再高也没必要。
            .height(RANGE_PICKER_HEIGHT),
        // 其余两项（title / headline）也置空：省下来的高度留给日历，
        // 同时避免和外层标题重复。
        title = null,
        headline = null,
        showModeToggle = false
    )
    val start = state.selectedStartDateMillis?.let { localDate(it) }
    val end = state.selectedEndDateMillis?.let { localDate(it) }
    val complete = start != null && end != null
    val days = if (start != null && end != null) {
        ChronoUnit.DAYS.between(start, end) + 1
    } else {
        0L
    }
    val overLimit = complete && days > DateSelection.MAX_DAYS
    if (overLimit) {
        Text(
            text = "区间最多 ${DateSelection.MAX_DAYS} 个自然日，当前 $days 天",
            style = FreshType.label,
            color = palette.error
        )
    }
    PrimaryButton(
        text = "应用",
        onClick = {
            if (start != null && end != null && !overLimit) {
                onApply(DateSelection.Range.normalized(start, end))
            }
        },
        enabled = complete && !overLimit,
        modifier = Modifier.fillMaxWidth()
    )
}

/** 区间日历容器高度：约 5 周可见 + 星期表头。内部自己滚动，不再依赖外层高度。 */
private val RANGE_PICKER_HEIGHT = 340.dp

/**
 * §5.6 筛选胶囊条「范围 · 共 N 天 · 有记录 X 天」+ 清除按钮。
 * 非今天筛选时显示，清除恢复今天。
 *
 * ### 批 6g 二轮（真机反馈 a/b/c）：出现/消失必须有高度动画
 *
 * 这条在「顶部提示区」里占一行高度，原来用 `if (今天) return` 瞬间出没——
 * 它一出现/消失，下方整个 Pager 内容就在**一帧内**跳一格，这就是：
 * a) 点 x 清除筛选闪；b) 弹层里点近 7/30 天闪；c) 「有记录 x 天」条弹出来闪。
 * 用 AnimatedVisibility 收展，下方内容被平滑推下/收回。
 *
 * 还有一个陷阱：退场动画期间 selection 已经变成「今天」，若此时直接渲染当前
 * selection，内容为空、收缩动画白做。所以退场期间冻结最后一次非今天的内容
 * （shownSelection/shownDays），动画播完节点移除，无跳变。
 */
@Composable
fun FilterChipBar(
    selection: DateSelection,
    recordedDays: Int?,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalFreshPalette.current
    val visible = selection !is DateSelection.Day || selection.date != LocalDate.now()
    var shownSelection by remember { mutableStateOf(selection) }
    var shownDays by remember { mutableStateOf(recordedDays) }
    if (visible) {
        shownSelection = selection
        shownDays = recordedDays
    }
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(140)) + fadeIn(tween(140)),
        exit = shrinkVertically(tween(140)) + fadeOut(tween(100)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(palette.surfaceVariant)
                .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val rangeText = when (val shown = shownSelection) {
                is DateSelection.Day -> shown.date.format(DAY_FORMAT)
                is DateSelection.Range ->
                    "${shown.start.format(DAY_FORMAT)} – ${shown.end.format(DAY_FORMAT)}"
            }
            val recordedText = shownDays?.let { "有记录 $it 天" } ?: "有记录 —"
            Text(
                text = "$rangeText · 共 ${shownSelection.days} 天 · $recordedText",
                style = FreshType.label,
                color = palette.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .semantics {
                        contentDescription = "清除日期筛选"
                        role = Role.Button
                    }
                    .clickable(onClick = onClear),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = palette.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun FilterChipBarPreview() {
    ScreenTimeTheme {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChipBar(
                selection = DateSelection.Range(LocalDate.now().minusDays(6), LocalDate.now()),
                recordedDays = 5,
                onClear = {}
            )
        }
    }
}
