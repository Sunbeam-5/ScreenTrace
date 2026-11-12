package com.screentrace.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.screentrace.app.theme.FreshPalette
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.PaletteEngine
import com.screentrace.app.theme.hslColor
import com.screentrace.app.theme.toHsl
import com.screentrace.app.uikit.PrimaryButton
import com.screentrace.app.uikit.SecondaryButton

/**
 * §5.4 CR-04：自定义主题取色器。
 *
 * 只暴露「色相 / 饱和度 / 明度」三个滑杆而不是一个十六进制输入框：
 * 明度滑杆限制在安全窗口内（0.25–0.72），从源头上避免用户选出一个近纯白种子，
 * 再让派生引擎去硬拉对比度——那样出来的颜色已经和用户选的不是一回事了。
 *
 * 面板里同时给出**派生结果预览**（主色 / 卡片 /  tonal 卡），
 * 用户在点「使用」之前就能看出「这套色板长什么样」，不用反复进出设置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSeedSheet(
    currentSeed: Long?,
    onApply: (Long) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalFreshPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val initial = currentSeed?.let { Color(it) } ?: Color(0xFF1B6B5A)
    val initialHsl = remember(initial) { initial.toHsl() }
    var hue by remember { mutableFloatStateOf(initialHsl.first) }
    var saturation by remember { mutableFloatStateOf((initialHsl.second).coerceIn(0.18f, 0.92f)) }
    var lightness by remember { mutableFloatStateOf(initialHsl.third.coerceIn(0.25f, 0.72f)) }

    val seed = hslColor(hue, saturation, lightness)
    val preview = remember(seed) { PaletteEngine.paletteFromSeed(seed, palette.darkTheme) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = palette.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(text = "自定义主题", style = FreshType.sectionTitle, color = palette.onSurface)
            Text(
                text = "选一个种子色，整套色板由它派生，并自动校正到可读的对比度。",
                style = FreshType.label,
                color = palette.onSurfaceVariant
            )

            PalettePreview(seed = seed, preview = preview)
            ColorChipRow(seed = seed, onPick = { picked ->
                val hsl = picked.toHsl()
                hue = hsl.first
                saturation = hsl.second.coerceIn(0.18f, 0.92f)
                lightness = hsl.third.coerceIn(0.25f, 0.72f)
            })

            SliderRow(label = "色相", value = hue, range = 0f..360f) { hue = it }
            SliderRow(
                label = "饱和度",
                value = saturation,
                range = 0.18f..0.92f
            ) { saturation = it }
            SliderRow(
                label = "明度",
                value = lightness,
                // 安全窗口：更低会变黑、更高会变白，两头都会让派生结果偏离用户所选
                range = 0.25f..0.72f
            ) { lightness = it }

            PrimaryButton(
                text = "使用这个颜色",
                onClick = { onApply(seed.value.toLong() and 0xFFFFFFFFL) },
                modifier = Modifier.fillMaxWidth()
            )
            SecondaryButton(
                text = "恢复预设风格",
                onClick = onReset,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    val palette = LocalFreshPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = "$label ${(value * if (range.endInclusive > 1f) 1f else 100f).toInt()}", style = FreshType.label, color = palette.onSurfaceVariant)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range
        )
    }
}

/** 派生结果预览：背景 / 卡片 / 主色 / tonal 双卡 / 图表五色。 */
@Composable
private fun PalettePreview(seed: Color, preview: FreshPalette) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(preview.surface),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier
                    .padding(start = 12.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(seed)
            )
            Text(text = "正文示例", style = FreshType.body, color = preview.onSurface)
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(preview.primary)
            )
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(preview.primaryContainer)
            )
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(preview.secondaryContainer)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                preview.chart1, preview.chart2, preview.chart3, preview.chart4, preview.chart5
            ).forEach { color ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(10.dp)
                        .clip(CircleShape)
                        .background(color)
                )
            }
        }
    }
}

/** 常用色相快捷点（省得每次都拖滑杆）。 */
@Composable
private fun ColorChipRow(seed: Color, onPick: (Color) -> Unit) {
    val palette = LocalFreshPalette.current
    val quick = listOf(
        Color(0xFF1B6B5A), Color(0xFF2C576D), Color(0xFF302E6B), Color(0xFF542F6A),
        Color(0xFF6D2C4F), Color(0xFF703529), Color(0xFF705829), Color(0xFF466732)
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        quick.forEach { color ->
            val selected = (seed.toHsl().first - color.toHsl().first).let { delta ->
                val d = Math.abs(delta) % 360f
                d < 6f
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (selected) 3.dp else 0.dp,
                        color = if (selected) palette.onSurface else Color.Transparent,
                        shape = CircleShape
                    )
                    .semantics {
                        role = Role.Button
                        contentDescription = "快捷色 ${color.value.toString(16)}"
                    }
                    .clickable { onPick(color) }
            )
        }
    }
}
