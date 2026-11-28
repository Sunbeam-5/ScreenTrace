package com.screentrace.app.uikit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 数字滚轮选择器（Wheel Picker / Number Picker）。
 *
 * **为什么不用 Material3 的 `Slider`**：24 档的线性滑杆在手机宽度上每档只有约 12dp，
 * 想从 2 小时调到 6 小时要靠拖 + 看数字，指下没有刻度感；系统原生的同类控件
 * （Android `NumberPicker`、iOS `UIPickerView`）都是**滚轮**：一屏露出 5 个候选、
 * 中间一格就是当前值，滚动带**吸附（snap fling）**，停下必然对齐一整格。
 *
 * Compose Material3 没有内置滚轮，标准做法是「`LazyColumn` + `rememberSnapFlingBehavior`」：
 * - 每项固定 [ItemHeight]，上下 `contentPadding` 各留 (可见数-1)/2 格，
 *   于是**滚动停止时 `firstVisibleItemIndex` 就是居中那一格**，不需要额外的物理计算；
 * - 吸附交给 `FlingBehavior`，不自己写 `animateScrollToItem` 的收尾逻辑（那种写法在
 *   快速甩动时会先甩过头再回弹，手上感觉是「打滑」）。
 *
 * 无障碍：整块滚轮是一个可调控件（`stateDescription` 报当前值），单个数字项不让读屏
 * 逐个聚焦——24 个可聚焦项会让读屏用户滑半天才出得去。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NumberWheelSheet(
    title: String,
    range: IntRange,
    value: Int,
    unit: String,
    /** 说明文案（放在滚轮下方，例如「低于这个时长的天数会在日历页单独计数」）。 */
    hint: String? = null,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalFreshPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val values = remember(range) { range.toList() }
    val startIndex = (value - range.first).coerceIn(0, (values.size - 1).coerceAtLeast(0))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    val fling = rememberSnapFlingBehavior(lazyListState = listState)
    val scope = rememberCoroutineScope()
    val itemPx = with(LocalDensity.current) { ItemHeight.toPx() }
    var picked by remember(range, value) { mutableIntStateOf(value) }

    // 滚动过程中实时反推「中间那格」：越过半格就算下一项，停下时与吸附结果一致
    LaunchedEffect(listState, itemPx) {
        snapshotFlow {
            val index = listState.firstVisibleItemIndex
            val offset = listState.firstVisibleItemScrollOffset
            index + if (offset > itemPx / 2f) 1 else 0
        }
            .distinctUntilChanged()
            .collect { index -> picked = values.getOrElse(index) { value } }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = palette.panelSurface,
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
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = title, style = FreshType.sectionTitle, color = palette.onSurface)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = title
                        stateDescription = "$picked $unit"
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ItemHeight * VisibleItems),
                    contentAlignment = Alignment.Center
                ) {
                    // 中线：两道光带夹住当前值，替代「选中项背景色」——滚轮里背景块会跟着
                    // 移动的内容一起抖，静态双线是静止的，视觉上更稳
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Line()
                        Box(Modifier.height(ItemHeight))
                        Line()
                    }
                    LazyColumn(
                        state = listState,
                        flingBehavior = fling,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            vertical = ItemHeight * ((VisibleItems - 1) / 2)
                        )
                    ) {
                        itemsIndexed(values) { index, number ->
                            val selected = number == picked
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(ItemHeight)
                                    // 点一下就滑到那一格：滚轮也能当列表用
                                    .clickable { scope.launch { listState.animateScrollToItem(index) } },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$number $unit",
                                    style = if (selected) FreshType.metricNumber else FreshType.body,
                                    color = if (selected) palette.primary else palette.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                hint?.let {
                    Text(
                        text = it,
                        style = FreshType.label,
                        color = palette.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                PrimaryButton(
                    text = "完成",
                    onClick = { onConfirm(picked); onDismiss() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp)
                )
            }
        }
    }
}

@Composable
private fun Line() {
    val palette = LocalFreshPalette.current
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.track)
        )
    }
}

/** 滚轮固定几何：5 格可见，单格 44dp（≥48dp 的可点区在 `clickable` 上补足到整格）。 */
private val ItemHeight = 44.dp
private const val VisibleItems = 5


/**
 * 批 14c：1~16 小时横向精密刻度尺目标选择器（方案 A：大号读数 + 横向标尺 + 线性马达 + 快捷胶囊）。
 *
 * 与原有 [NumberWheelSheet] 互补：每日目标用精密横向标尺（1..16 小时，每格 4 道细刻度），
 * 单手左右滑动即可带 `CLOCK_TICK` 线性马达轻震精准落档，下方附常用 2h/4h/6h/8h 一键胶囊。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RulerGoalSheet(
    title: String,
    range: IntRange = 1..16,
    value: Int,
    unit: String = "小时",
    quickPresets: List<Int> = listOf(2, 4, 6, 8),
    hint: String? = null,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalFreshPalette.current
    val view = androidx.compose.ui.platform.LocalView.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val values = remember(range) { range.toList() }
    val clampedInitial = value.coerceIn(range.first, range.last)
    val startIndex = (clampedInitial - range.first).coerceIn(0, (values.size - 1).coerceAtLeast(0))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    val fling = rememberSnapFlingBehavior(lazyListState = listState)
    val scope = rememberCoroutineScope()
    val slotDp = 56.dp
    val slotPx = with(LocalDensity.current) { slotDp.toPx() }
    var picked by remember(range, clampedInitial) { mutableIntStateOf(clampedInitial) }

    LaunchedEffect(listState, slotPx) {
        snapshotFlow {
            val idx = listState.firstVisibleItemIndex
            val off = listState.firstVisibleItemScrollOffset
            (idx + if (off > slotPx / 2f) 1 else 0).coerceIn(0, (values.size - 1).coerceAtLeast(0))
        }
            .distinctUntilChanged()
            .collect { idx ->
                val next = values.getOrElse(idx) { clampedInitial }
                if (next != picked) {
                    picked = next
                    performTickHaptic(view)
                }
            }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = palette.panelSurface,
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
                .padding(horizontal = 20.dp)
                .padding(bottom = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = title, style = FreshType.sectionTitle, color = palette.onSurface)

            // 大号实时读数
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "$picked",
                    style = FreshType.heroNumber,
                    color = palette.primary
                )
                Text(
                    text = unit,
                    style = FreshType.sectionTitle,
                    color = palette.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // 横向精密刻度尺（中央固定主色指针 + 左右自由滑动吸附）
            androidx.compose.foundation.layout.BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(palette.surfaceVariant)
                    .semantics {
                        contentDescription = title
                        stateDescription = "$picked $unit"
                    }
            ) {
                val sidePad = ((maxWidth - slotDp) / 2f).coerceAtLeast(0.dp)
                androidx.compose.foundation.lazy.LazyRow(
                    state = listState,
                    flingBehavior = fling,
                    contentPadding = PaddingValues(horizontal = sidePad),
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    itemsIndexed(values) { index, item ->
                        val isSelected = item == picked
                        Column(
                            modifier = Modifier
                                .width(slotDp)
                                .height(86.dp)
                                .clickable {
                                    scope.launch { listState.animateScrollToItem(index) }
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "$item",
                                style = if (isSelected) FreshType.bodySmallBold else FreshType.label,
                                color = if (isSelected) palette.primary else palette.onSurfaceMuted,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.Bottom
                            ) {
                                // 左侧 2 道细刻度 + 中央主刻度 + 右侧 2 道细刻度
                                Box(
                                    Modifier
                                        .width(1.5.dp)
                                        .height(10.dp)
                                        .clip(CircleShape)
                                        .background(palette.outline)
                                )
                                Box(
                                    Modifier
                                        .width(1.5.dp)
                                        .height(14.dp)
                                        .clip(CircleShape)
                                        .background(palette.outline)
                                )
                                Box(
                                    Modifier
                                        .width(if (isSelected) 3.dp else 2.dp)
                                        .height(if (isSelected) 28.dp else 22.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) palette.primary else palette.onSurfaceVariant)
                                )
                                Box(
                                    Modifier
                                        .width(1.5.dp)
                                        .height(14.dp)
                                        .clip(CircleShape)
                                        .background(palette.outline)
                                )
                                Box(
                                    Modifier
                                        .width(1.5.dp)
                                        .height(10.dp)
                                        .clip(CircleShape)
                                        .background(palette.outline)
                                )
                            }
                        }
                    }
                }
                // 中央固定指针（主色胶囊）
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .width(4.dp)
                        .height(34.dp)
                        .clip(CircleShape)
                        .background(palette.primary)
                )
            }

            // 常用快捷胶囊
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickPresets.filter { it in range }.forEach { preset ->
                    val active = preset == picked
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .background(if (active) palette.primary else palette.surfaceVariant)
                            .clickable {
                                val targetIdx = (preset - range.first).coerceIn(0, values.lastIndex)
                                performTickHaptic(view)
                                scope.launch { listState.animateScrollToItem(targetIdx) }
                            }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${preset}h",
                            style = FreshType.bodySmallBold,
                            color = if (active) palette.onPrimary else palette.onSurfaceVariant
                        )
                    }
                }
            }

            if (hint != null) {
                Text(text = hint, style = FreshType.label, color = palette.onSurfaceVariant)
            }

            PrimaryButton(
                text = "确定",
                onClick = {
                    onConfirm(picked)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
