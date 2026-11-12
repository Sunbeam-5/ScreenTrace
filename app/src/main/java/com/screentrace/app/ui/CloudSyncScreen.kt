package com.screentrace.app.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.screentrace.app.data.backup.BackupFile
import com.screentrace.app.data.backup.BackupRepository
import com.screentrace.app.data.backup.BackupSummary
import com.screentrace.app.data.prefs.SyncFrequency
import com.screentrace.app.data.sync.SyncManager
import com.screentrace.app.data.sync.SyncResult
import kotlinx.coroutines.launch

/**
 * §5.10 云端同步二级页（真机反馈 #3：配置项多，不平铺在设置页）。
 *
 * 页面 = WebDAV 配置与操作（[CloudSyncGroup]）+ 下载后的恢复策略（复用本地恢复的
 * 同一个 [RestoreSheet]，8b 硬原则：远端数据经 `BackupRepository.restore` 统一入口）。
 * 顶部「返回 + 标题」由 AppNav 的二级页壳提供；转场走容器变换（§5.5）。
 */
@Composable
fun CloudSyncScreen(
    webdavUrl: String,
    webdavUsername: String,
    webdavRemoteDir: String,
    passwordSaved: Boolean,
    syncFrequency: SyncFrequency,
    syncWifiOnly: Boolean,
    lastAt: Long?,
    lastOk: Boolean?,
    lastMessage: String,
    appVersion: String,
    onConfigChange: (String, String, String) -> Unit,
    onPasswordSet: (String) -> Unit,
    onFrequencyChange: (SyncFrequency) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onTestConnection: suspend () -> SyncResult,
    onUpload: suspend () -> SyncResult,
    onDownload: suspend () -> SyncManager.DownloadedBackup,
    onRestore: suspend (BackupFile, Boolean, Boolean) -> BackupRepository.RestoreResult?,
    onNotice: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var candidate by remember { mutableStateOf<Pair<BackupFile, BackupSummary>?>(null) }

    CloudSyncGroup(
        webdavUrl = webdavUrl,
        webdavUsername = webdavUsername,
        webdavRemoteDir = webdavRemoteDir,
        passwordSaved = passwordSaved,
        syncFrequency = syncFrequency,
        syncWifiOnly = syncWifiOnly,
        lastAt = lastAt,
        lastOk = lastOk,
        lastMessage = lastMessage,
        onConfigChange = onConfigChange,
        onPasswordSet = onPasswordSet,
        onFrequencyChange = onFrequencyChange,
        onWifiOnlyChange = onWifiOnlyChange,
        onTestConnection = onTestConnection,
        onUpload = onUpload,
        onDownload = onDownload,
        onDownloadedReady = { file, summary -> candidate = file to summary },
        onNotice = onNotice
    )

    // 云端下载候选 → 同一套「摘要 → 策略二选一」恢复流程（与本地文件恢复一致）
    candidate?.let { (file, summary) ->
        RestoreSheet(
            summary = summary,
            appVersion = appVersion,
            onDismiss = { candidate = null },
            onConfirm = { overwrite, importSettings ->
                scope.launch {
                    val result = onRestore(file, overwrite, importSettings)
                    candidate = null
                    if (result == null) {
                        onNotice("恢复失败，本地数据未改动")
                    } else {
                        // 成功反馈走 Toast（负责人）：恢复结果摘要一行带过，不弹带 X 的横幅
                        Toast.makeText(
                            context,
                            "已恢复：新增 ${result.added} 条、更新 ${result.updated} 条、跳过 ${result.skipped} 条",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }
}
