package com.screentrace.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.screentrace.app.data.export.PngBackgroundMode
import com.screentrace.app.data.export.PngExportModule
import com.screentrace.app.data.export.PngExportRange
import com.screentrace.app.data.export.PngPosterData
import com.screentrace.app.data.export.PngPosterSpec
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.LocalWallpaper
import com.screentrace.app.uikit.LocalForceReduceMotion
import com.screentrace.app.uikit.PrimaryButton
import com.screentrace.app.uikit.SecondaryButton
import com.screentrace.app.uikit.SegmentedControl
import kotlinx.coroutines.launch

/**
 * 14a 余项（v4）：独立的「分享长图」实时预览与模块自选页。
 *
 * - 上半区：可上下滚动的实时预览画布（按 390dp 比例等比适配当前屏幕宽度，勾选模块 0ms 即时显隐）。
 * - 下半区：不透明固定操作面板（今日/近7天/近30天 + 6 模块自由开关 + 隐藏应用名/水印/壁纸模式 +
 *   「保存到相册」与「分享」双按钮）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SharePosterScreen(
    loadPosterData: suspend (PngExportRange) -> Pair<String, PngPosterData>?,
    onDismiss: () -> Unit,
    onNotice: (String) -> Unit
) {
    val palette = LocalFreshPalette.current
    val wallpaper = LocalWallpaper.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var range by remember { mutableStateOf(PngExportRange.LAST_7_DAYS) }
    var selectedModules by remember { mutableStateOf(PngExportRange.LAST_7_DAYS.defaultModules()) }
    var hideAppNames by remember { mutableStateOf(false) }
    var showWatermark by remember { mutableStateOf(true) }
    var backgroundMode by remember { mutableStateOf(PngBackgroundMode.TOP_FADE) }

    var loadedPair by remember { mutableStateOf<Pair<String, PngPosterData>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var exporting by remember { mutableStateOf(false) }
    var inlineMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(inlineMessage) {
        if (inlineMessage != null) {
            kotlinx.coroutines.delay(2_500L)
            inlineMessage = null
        }
    }

    // Android 8–9（API 26–28）回退 SAF 保存
    var pendingSafBytes by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    val safLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        val payload = pendingSafBytes
        pendingSafBytes = null
        if (uri == null || payload == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(payload.second)
            } ?: error("无法写入所选位置")
        }.isSuccess
        val msg = if (ok) "已保存 ${payload.first}" else "保存失败，请换个位置再试"
        inlineMessage = msg
        onNotice(msg)
    }

    LaunchedEffect(range) {
        loading = true
        val res = loadPosterData(range)
        if (res != null) {
            loadedPair = res
        }
        loading = false
    }

    val livePosterData: PngPosterData? = loadedPair?.second?.copy(
        modules = selectedModules,
        hideAppNames = hideAppNames,
        showWatermark = showWatermark,
        backgroundMode = backgroundMode
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            // 拦截底层穿透点击
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent()
                }
            }
    ) {
        // ---- 顶栏 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.panelSurface)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(palette.surfaceVariant)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = palette.onSurface
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "分享长图", style = FreshType.sectionTitle, color = palette.onSurface)
                Text(
                    text = "勾选模块即时预览 · 390dp 主流手机分享比例",
                    style = FreshType.label,
                    color = palette.onSurfaceVariant
                )
            }
        }

        // ---- 上半区：实时滚动预览画布 ----
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(palette.surfaceVariant)
        ) {
            val baseDensity = LocalDensity.current
            val horizontalPad = 18.dp
            val availWidthDp = (maxWidth - horizontalPad * 2).coerceAtLeast(240.dp)
            // 按 390dp 等比缩放预览密度，保证预览构图与最终导出 PNG 像素级一致
            val previewScale = (availWidthDp.value / PngPosterSpec.POSTER_WIDTH_DP.toFloat())
                .coerceIn(0.6f, 1.15f)
            val previewDensity = remember(baseDensity, previewScale) {
                Density(density = baseDensity.density * previewScale, fontScale = 1f)
            }

            if (livePosterData == null && loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = palette.primary)
                }
            } else if (livePosterData != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        shadowElevation = 6.dp,
                        color = palette.background
                    ) {
                        CompositionLocalProvider(
                            LocalDensity provides previewDensity,
                            LocalForceReduceMotion provides true
                        ) {
                            PngPosterContent(data = livePosterData)
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "暂无可预览的数据",
                        style = FreshType.body,
                        color = palette.onSurfaceVariant
                    )
                }
            }
            inlineMessage?.let { msg ->
                Surface(
                    shape = CircleShape,
                    color = palette.primary,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 14.dp, start = 20.dp, end = 20.dp)
                ) {
                    Text(
                        text = msg,
                        style = FreshType.label,
                        color = palette.onPrimary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                    )
                }
            }
        }

        // ---- 下半区：固定不透明操作面板 ----
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = palette.panelSurface,
            shadowElevation = 10.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. 周期切换（今日 / 近7天 / 近30天）
                SegmentedControl(
                    items = PngExportRange.entries.map { it.label },
                    selectedIndex = range.ordinal,
                    onSelect = { idx ->
                        val next = PngExportRange.entries[idx]
                        if (next != range) {
                            range = next
                            selectedModules = next.defaultModules()
                        }
                    }
                )

                // 2. 6 模块自由开关（0ms 即时显隐）
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PngExportModule.entries.forEach { module ->
                        val checked = module in selectedModules
                        TogglePillChip(
                            label = module.label,
                            checked = checked,
                            onToggle = { on ->
                                selectedModules = if (on) {
                                    selectedModules + module
                                } else {
                                    selectedModules - module
                                }
                            }
                        )
                    }
                }

                // 3. 轻选项行：隐藏应用名 + 水印 + 壁纸渐隐/铺满
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TogglePillChip(
                        label = "隐藏应用名",
                        checked = hideAppNames,
                        onToggle = { hideAppNames = it }
                    )
                    TogglePillChip(
                        label = "底部水印",
                        checked = showWatermark,
                        onToggle = { showWatermark = it }
                    )
                    if (wallpaper != null) {
                        TogglePillChip(
                            label = "壁纸：${backgroundMode.label}",
                            checked = backgroundMode == PngBackgroundMode.TOP_FADE,
                            onToggle = {
                                backgroundMode = if (backgroundMode == PngBackgroundMode.TOP_FADE) {
                                    PngBackgroundMode.FULL_FILL
                                } else {
                                    PngBackgroundMode.TOP_FADE
                                }
                            }
                        )
                    }
                }

                // 4. 保存到相册 + 系统分享双按钮
                val canExport = !exporting && livePosterData != null && selectedModules.isNotEmpty()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PrimaryButton(
                        text = if (exporting) "生成中…" else "保存到相册",
                        enabled = canExport,
                        onClick = {
                            val pair = loadedPair ?: return@PrimaryButton
                            val data = livePosterData ?: return@PrimaryButton
                            exporting = true
                            scope.launch {
                                val bytes = runCatching {
                                    capturePosterToPngBytes(context, palette, wallpaper, data)
                                }.getOrNull()
                                exporting = false
                                if (bytes == null) {
                                    inlineMessage = "长图生成失败，请稍后再试" 
                                    return@launch
                                }
                                val savedToAlbum = savePosterToGallery(context, pair.first, bytes)
                                if (savedToAlbum) {
                                    val msg = "已保存到相册「${PngPosterSpec.ALBUM_SUBDIR}」"
                                    inlineMessage = msg
                                    onNotice(msg)
                                } else {
                                    pendingSafBytes = pair.first to bytes
                                    safLauncher.launch(pair.first)
                                }
                            }
                        },
                        modifier = Modifier.weight(1.4f)
                    )
                    SecondaryButton(
                        text = "分享",
                        enabled = canExport,
                        onClick = {
                            val pair = loadedPair ?: return@SecondaryButton
                            val data = livePosterData ?: return@SecondaryButton
                            exporting = true
                            scope.launch {
                                val bytes = runCatching {
                                    capturePosterToPngBytes(context, palette, wallpaper, data)
                                }.getOrNull()
                                exporting = false
                                if (bytes == null) {
                                    inlineMessage = "长图生成失败，请稍后再试" 
                                    return@launch
                                }
                                val shared = sharePosterViaSystemSheet(context, pair.first, bytes)
                                if (!shared) inlineMessage = "无法调起系统分享，请先保存到相册" 
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun TogglePillChip(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val palette = LocalFreshPalette.current
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (checked) palette.primary else palette.surfaceVariant)
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onToggle
            )
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            style = FreshType.label,
            color = if (checked) palette.onPrimary else palette.onSurfaceVariant
        )
    }
}
