package com.screentrace.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = palette.surface,
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
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

            if (tab == 0) {
                SingleDayPicker(current = current, onApply = onApply)
            } else {
                RangePicker(onApply = onApply)
            }
        }
    }
}

@Composable
private fun QuickChip(label: String, onClick: () -> Unit) {
    val palette = LocalFreshPalette.current
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(palette.buttonSurface)
            .defaultMinSize(minHeight = 48.dp) // §5.9 点击区 ≥48dp（返修 P2-4）
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(text = label, style = FreshType.button, color = palette.onSurface)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleDayPicker(current: DateSelection, onApply: (DateSelection) -> Unit) {
    val palette = LocalFreshPalette.current
    val initial = utcMillis(if (current is DateSelection.Day) current.date else LocalDate.now())
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                !localDate(utcTimeMillis).isAfter(LocalDate.now())
        }
    )
    DatePicker(state = state, showModeToggle = false)
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
    DateRangePicker(state = state, showModeToggle = false)
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

/**
 * §5.6 筛选胶囊条「范围 · 共 N 天 · 有记录 X 天」+ 清除按钮。
 * 仅在用户应用了非默认筛选后显示；清除恢复今天。
 */
@Composable
fun FilterChipBar(
    selection: DateSelection,
    recordedDays: Int?,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalFreshPalette.current
    if (selection is DateSelection.Day && selection.date == LocalDate.now()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(palette.surfaceVariant)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val rangeText = when (selection) {
            is DateSelection.Day -> selection.date.format(DAY_FORMAT)
            is DateSelection.Range ->
                "${selection.start.format(DAY_FORMAT)} – ${selection.end.format(DAY_FORMAT)}"
        }
        val recordedText = recordedDays?.let { "有记录 $it 天" } ?: "有记录 —"
        Text(
            text = "$rangeText · 共 ${selection.days} 天 · $recordedText",
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
