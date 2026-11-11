package com.screentrace.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.screentrace.app.contract.DEFAULT_LOW_THRESHOLD_MIN
import com.screentrace.app.data.prefs.LowUsageThreshold
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.uikit.EmptyState
import kotlin.math.roundToInt

/** 阈值滑杆的小时范围（§7-23：1–24 小时连续可调，不再是 1/2/3/4 四档）。 */
private val THRESHOLD_HOURS = 1f..24f

/**
 * 设置页：目前落地「外观」「统计口径」两组实体，其余组随版本补齐（§5.8）；
 * 「外观」与「统计口径」两组是真实可用功能，其余组诚实待办——不画假按钮（§6）。
 */
@Composable
fun SettingsScreen(
    darkTheme: Boolean,
    dynamicColor: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    /** 日历页「低于 x 小时」的阈值（分钟）。 */
    lowThresholdMin: Int = DEFAULT_LOW_THRESHOLD_MIN,
    onLowThresholdChange: (Int) -> Unit = {}
) {
    SettingsGroup("外观") {
        SettingSwitchRow(
            label = "深色模式",
            checked = darkTheme,
            onCheckedChange = onDarkThemeChange
        )
        SettingSwitchRow(
            label = "跟随壁纸动态取色",
            checked = dynamicColor,
            onCheckedChange = onDynamicColorChange
        )
    }

    SettingsGroup("统计口径") {
        val palette = LocalFreshPalette.current
        Text(
            text = "日历里「低于 x 小时」的判断标准",
            style = FreshType.body,
            color = palette.onSurface
        )
        // 拖动时只改本地草稿，松手才写 DataStore：setLowThresholdMin 会触发整页
        // 重算（日历色档、低于 x 小时计数都跟着变），拖动中每次落地都会卡。
        var draftHours by remember(lowThresholdMin) {
            mutableFloatStateOf(LowUsageThreshold.hoursOf(lowThresholdMin).toFloat())
        }
        // §5.8「滑杆 + 数字双向输入」：拖滑杆回填数字，改数字移动滑杆。
        var numberText by remember(lowThresholdMin) {
            mutableStateOf(LowUsageThreshold.hoursOf(lowThresholdMin).toString())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = numberText,
                onValueChange = { text ->
                    numberText = text
                    val hours = text.trim().toIntOrNull()
                    if (hours != null && hours in LowUsageThreshold.MIN_HOURS..LowUsageThreshold.MAX_HOURS) {
                        draftHours = hours.toFloat()
                        onLowThresholdChange(LowUsageThreshold.minutesOf(hours))
                    }
                },
                label = { Text("小时", style = FreshType.label) },
                singleLine = true,
                isError = numberText.isNotBlank() &&
                    (numberText.trim().toIntOrNull() ?: 0) !in LowUsageThreshold.MIN_HOURS..LowUsageThreshold.MAX_HOURS,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.width(112.dp)
            )
            Text(
                text = "当前 ${draftHours.roundToInt()} 小时",
                style = FreshType.label,
                color = palette.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
        Slider(
            value = draftHours.coerceIn(THRESHOLD_HOURS.start, THRESHOLD_HOURS.endInclusive),
            onValueChange = {
                draftHours = it
                numberText = it.roundToInt().toString()
            },
            onValueChangeFinished = {
                onLowThresholdChange(LowUsageThreshold.minutesOf(draftHours.roundToInt()))
            },
            valueRange = THRESHOLD_HOURS,
            // 24 档（1..24）之间有 22 个刻度，滑杆只会停在整小时上
            steps = LowUsageThreshold.MAX_HOURS - LowUsageThreshold.MIN_HOURS - 1
        )
        Text(
            text = "低于这个时长的天数会在日历页单独计数。可在 1–24 小时之间调整，作息不同，这条线因人而异。",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
    }

    EmptyState(
        title = "更多设置建设中",
        description = "数据备份与云同步、应用分类管理等功能还在开发中，会随版本更新陆续开放"
    )
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalFreshPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(palette.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = title, style = FreshType.sectionTitle, color = palette.onSurface)
        content()
    }
}

@Composable
private fun SettingSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val palette = LocalFreshPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = FreshType.body, color = palette.onSurface)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
