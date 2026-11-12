package com.screentrace.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.screentrace.app.data.backup.BackupCodec
import com.screentrace.app.data.backup.BackupFile
import com.screentrace.app.data.backup.BackupRepository
import com.screentrace.app.data.backup.BackupSummary
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.uikit.PrimaryButton
import com.screentrace.app.uikit.SecondaryButton
import kotlinx.coroutines.launch

/**
 * §5.10 设置页「备份与同步」组（本地部分）：导出 JSON / CSV、从文件恢复。
 *
 * 恢复流程严格按文档四步走：校验 → 摘要 → 选策略（合并默认、覆盖带红色警示与二次确认）
 * → 单事务执行并回报结果。**不做静默默认**——覆盖会清空八张表，这是不可逆操作。
 */
@Composable
fun BackupGroup(
    appVersion: String,
    onBuildJson: suspend () -> Pair<String, String>?,
    onBuildCsv: suspend () -> List<Pair<String, String>>,
    onDecode: suspend (String) -> BackupCodec.DecodeResult,
    onRestore: suspend (BackupFile, Boolean, Boolean) -> BackupRepository.RestoreResult?,
    onNotice: (String) -> Unit
) {
    val palette = LocalFreshPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingJson by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingCsv by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var restoreCandidate by remember { mutableStateOf<Pair<BackupFile, BackupSummary>?>(null) }
    var busy by remember { mutableStateOf(false) }

    // ① JSON：单文件走 CreateDocument
    val jsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val payload = pendingJson
        pendingJson = null
        if (uri == null || payload == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(payload.second.toByteArray(Charsets.UTF_8))
            } ?: error("写入失败")
        }.isSuccess
        onNotice(if (ok) "已导出 ${payload.first}" else "导出失败，请换个位置再试")
    }

    // ② CSV：七张表各一个文件，只能选目录（SAF 目录树）
    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val files = pendingCsv
        pendingCsv = emptyList()
        if (uri == null || files.isEmpty()) return@rememberLauncherForActivityResult
        val written = runCatching {
            // 需要持久化权限才能在目录里反复写；拿不到也不算失败，本次仍可写
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val dir = DocumentFile.fromTreeUri(context, uri) ?: error("无法访问所选目录")
            var count = 0
            files.forEach { (name, content) ->
                // 同名文件先删再建，避免重复导出时留下两份
                dir.findFile(name)?.delete()
                val doc = dir.createFile("text/csv", name) ?: return@forEach
                context.contentResolver.openOutputStream(doc.uri)?.use {
                    it.write(content.toByteArray(Charsets.UTF_8))
                }
                count++
            }
            count
        }.getOrNull()
        onNotice(
            if (written == files.size) "已导出 $written 个 CSV 文件"
            else "CSV 导出未完成（$written/${files.size}），请重试"
        )
    }

    // ③ 恢复：读文件（文本）
    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text == null) {
                onNotice("读取文件失败")
                return@launch
            }
            when (val decoded = onDecode(text)) {
                is BackupCodec.DecodeResult.Invalid -> onNotice("无法导入：${decoded.reason}")
                is BackupCodec.DecodeResult.Ok -> restoreCandidate = decoded.file to decoded.summary
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(palette.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "备份与同步", style = FreshType.sectionTitle, color = palette.onSurface)

        Text(text = "导出数据", style = FreshType.body, color = palette.onSurface)
        Text(
            text = "JSON 含八张表与设置，用于换机恢复；CSV 是七张明细表，给 Excel 用。",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                text = "导出 JSON",
                enabled = !busy,
                onClick = {
                    if (busy) return@SecondaryButton
                    busy = true
                    scope.launch {
                        val payload = onBuildJson()
                        busy = false
                        if (payload == null) {
                            onNotice("导出失败：数据读取异常")
                        } else {
                            pendingJson = payload
                            jsonLauncher.launch(payload.first)
                        }
                    }
                }
            )
            SecondaryButton(
                text = "导出 CSV",
                enabled = !busy,
                onClick = {
                    if (busy) return@SecondaryButton
                    busy = true
                    scope.launch {
                        val files = onBuildCsv()
                        busy = false
                        if (files.isEmpty()) {
                            onNotice("导出失败：数据读取异常")
                        } else {
                            pendingCsv = files
                            treeLauncher.launch(null)
                        }
                    }
                }
            )
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.track))

        Text(text = "从文件恢复", style = FreshType.body, color = palette.onSurface)
        Text(
            text = "恢复前会先显示备份摘要，再由你选择「合并」或「覆盖」。",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
        SecondaryButton(
            text = "选择备份文件",
            enabled = !busy,
            onClick = { openLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
        )

        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.track))
        Text(
            text = "云同步（WebDAV / Google Drive）尚未开放，随下一版提供。",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
    }

    val candidate = restoreCandidate
    if (candidate != null) {
        RestoreSheet(
            summary = candidate.second,
            appVersion = appVersion,
            onDismiss = { restoreCandidate = null },
            onConfirm = { overwrite, importSettings ->
                scope.launch {
                    busy = true
                    val result = onRestore(candidate.first, overwrite, importSettings)
                    busy = false
                    restoreCandidate = null
                    onNotice(
                        if (result == null) {
                            "恢复失败，本地数据未改动"
                        } else {
                            "已恢复：新增 ${result.added} 条、更新 ${result.updated} 条、跳过 ${result.skipped} 条"
                        }
                    )
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RestoreSheet(
    summary: BackupSummary,
    appVersion: String,
    onDismiss: () -> Unit,
    onConfirm: (overwrite: Boolean, importSettings: Boolean) -> Unit
) {
    val palette = LocalFreshPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var overwrite by remember { mutableStateOf(false) }
    var importSettings by remember { mutableStateOf(false) }
    // 覆盖需要二次确认：第一次点只是把警示亮出来，第二次才真的执行
    var overwriteArmed by remember { mutableStateOf(false) }

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
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = "备份摘要", style = FreshType.sectionTitle, color = palette.onSurface)
            SummaryLine("导出时间", summary.exportedAt)
            SummaryLine("App 版本", summary.appVersion)
            SummaryLine("数据范围", summary.rangeText)
            SummaryLine(
                "记录条数",
                "日 ${summary.dayCount} · 应用 ${summary.appRows} · 时段 ${summary.hourRows} · " +
                    "会话 ${summary.sessionRows} · 解锁 ${summary.unlockRows} · 通知 ${summary.notificationRows} · " +
                    "限额 ${summary.limitRows}"
            )
            SummaryLine("是否含设置", if (summary.hasSettings) "是" else "否")
            if (summary.missingTables.isNotEmpty()) {
                Text(
                    text = "该备份版本较旧：" + summary.missingTables.joinToString("、") + " 不包含。",
                    style = FreshType.label,
                    color = palette.onSurfaceVariant
                )
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.track))

            StrategyOption(
                title = "合并本地（推荐）",
                description = "同日数据取较大值、事件按秒去重、限额以备份为准，不清空现有数据。",
                selected = !overwrite,
                onClick = {
                    overwrite = false
                    overwriteArmed = false
                }
            )
            StrategyOption(
                title = "覆盖本地",
                description = "将删除本机全部统计数据后导入，无法撤销。",
                selected = overwrite,
                danger = true,
                onClick = { overwrite = true }
            )
            if (overwrite) {
                Text(
                    text = "⚠ 覆盖会清空本机八张表（含会话区间与限额）。确认前请先导出一份当前备份。",
                    style = FreshType.label,
                    color = palette.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "同时恢复设置", style = FreshType.body, color = palette.onSurface)
                Switch(
                    checked = importSettings,
                    onCheckedChange = { importSettings = it },
                    enabled = summary.hasSettings
                )
            }
            Text(
                text = if (summary.hasSettings) {
                    "目标 / 主题 / 分类 / 阈值 / 拉取频率会一起恢复；不勾选则只恢复统计数据。"
                } else {
                    "该备份不含设置，只能恢复统计数据。"
                },
                style = FreshType.label,
                color = palette.onSurfaceVariant
            )

            PrimaryButton(
                text = when {
                    !overwrite -> "开始合并"
                    !overwriteArmed -> "我要覆盖（下一步确认）"
                    else -> "确认覆盖并导入"
                },
                onClick = {
                    if (overwrite && !overwriteArmed) {
                        overwriteArmed = true
                    } else {
                        onConfirm(overwrite, importSettings)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            SecondaryButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "当前 App v$appVersion",
                style = FreshType.label,
                color = palette.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    val palette = LocalFreshPalette.current
    Column {
        Text(text = label, style = FreshType.label, color = palette.onSurfaceVariant)
        Text(text = value, style = FreshType.body, color = palette.onSurface)
    }
}

@Composable
private fun StrategyOption(
    title: String,
    description: String,
    selected: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val palette = LocalFreshPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) palette.surfaceVariant else palette.track)
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .size(if (selected) 12.dp else 8.dp)
                .clip(CircleShape)
                .background(
                    when {
                        selected && danger -> palette.error
                        selected -> palette.primary
                        else -> palette.onSurfaceVariant
                    }
                )
        )
        Column {
            Text(
                text = title,
                style = FreshType.body,
                color = when {
                    danger && selected -> palette.error
                    else -> palette.onSurface
                }
            )
            Text(text = description, style = FreshType.label, color = palette.onSurfaceVariant)
        }
    }
}
