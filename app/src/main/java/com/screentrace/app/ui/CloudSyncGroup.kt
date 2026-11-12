package com.screentrace.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import android.widget.Toast
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.screentrace.app.data.backup.BackupFile
import com.screentrace.app.data.backup.BackupSummary
import com.screentrace.app.data.prefs.SyncFrequency
import com.screentrace.app.data.sync.SyncFailure
import com.screentrace.app.data.sync.SyncManager
import com.screentrace.app.data.sync.SyncResult
import com.screentrace.app.data.sync.WebDavProvider
import com.screentrace.app.data.sync.userMessage
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.uikit.SecondaryButton
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * §5.10 设置页「云端同步 · WebDAV」卡片：配置、测试连接、手动上传、从云端恢复、
 * 自动同步频率与仅 Wi-Fi、上次同步时间与结果（失败静默展示不弹窗）。
 *
 * 恢复走**统一恢复流程**：下载 → 摘要 → 复用本地恢复的策略二选一 UI（覆盖红色警示 /
 * 合并默认）→ `BackupRepository.restore` 单事务（8b 硬原则：远端数据必过
 * IngestSanity，本组不直写库）。坚果云密码为「第三方应用授权密码」，存
 * EncryptedSharedPreferences，不进日志/备份。
 */
@Composable
fun CloudSyncGroup(
    webdavUrl: String,
    webdavUsername: String,
    webdavRemoteDir: String,
    passwordSaved: Boolean,
    syncFrequency: SyncFrequency,
    syncWifiOnly: Boolean,
    lastAt: Long?,
    lastOk: Boolean?,
    lastMessage: String,
    onConfigChange: (String, String, String) -> Unit,
    onPasswordSet: (String) -> Unit,
    onFrequencyChange: (SyncFrequency) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onTestConnection: suspend () -> SyncResult,
    onUpload: suspend () -> SyncResult,
    onDownload: suspend () -> SyncManager.DownloadedBackup,
    /** 下载成功后交给本地恢复的策略二选一流程（同一套 UI、同一入口）。 */
    onDownloadedReady: (BackupFile, BackupSummary) -> Unit,
    onNotice: (String) -> Unit
) {
    val palette = LocalFreshPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    // 真机反馈 #1：全局提示条被二级页覆盖层遮挡、看不见——操作结果必须**就地**显示
    var lastResult by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    // 配置草稿：改动后需点「保存配置」落盘（避免每敲一个字符写 DataStore）
    var url by remember(webdavUrl) { mutableStateOf(webdavUrl) }
    var username by remember(webdavUsername) { mutableStateOf(webdavUsername) }
    var remoteDir by remember(webdavRemoteDir) { mutableStateOf(webdavRemoteDir) }
    var password by remember { mutableStateOf("") }

    SettingsGroup("云端同步 · WebDAV") {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("服务器地址", style = FreshType.label) },
            singleLine = true,
            supportingText = { Text("坚果云预置 ${WebDavProvider.JIANGUOYUN_URL}，可改任意 WebDAV", style = FreshType.label) },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("账号", style = FreshType.label) },
            singleLine = true,
            supportingText = { Text("坚果云为登录邮箱", style = FreshType.label) },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(if (passwordSaved) "授权密码（已保存，留空不改）" else "授权密码", style = FreshType.label) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = {
                Text("坚果云需在「安全选项」生成第三方应用授权密码，不是登录密码", style = FreshType.label)
            },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = remoteDir,
            onValueChange = { remoteDir = it },
            label = { Text("远端目录", style = FreshType.label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                text = "保存配置",
                onClick = {
                    onConfigChange(url, username, remoteDir)
                    if (password.isNotEmpty()) {
                        onPasswordSet(password)
                        password = ""
                    }
                    // 成功类反馈走 Toast（负责人：不必弹带 X 的提示条）
                    Toast.makeText(context, "云端配置已保存", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f)
            )
            SecondaryButton(
                text = if (busy) "请稍候…" else "测试连接",
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        onConfigChange(url, username, remoteDir)
                        if (password.isNotEmpty()) onPasswordSet(password)
                        val result = onTestConnection()
                        busy = false
                        if (result is SyncResult.Ok) {
                            lastResult = null
                            Toast.makeText(context, "连接成功", Toast.LENGTH_SHORT).show()
                        } else {
                            val msg = "测试失败：${(result as SyncResult.Err).kind.userMessage()}"
                            lastResult = msg to false
                            onNotice(msg)
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.track)
        )

        // 手动上传 / 从云端恢复
        Text(text = "手动同步", style = FreshType.body, color = palette.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                text = if (busy) "处理中…" else "上传备份",
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        val result = onUpload()
                        busy = false
                        if (result is SyncResult.Ok) {
                            lastResult = null
                            Toast.makeText(context, "已上传 screen-time-backup.json", Toast.LENGTH_SHORT).show()
                        } else {
                            val msg = "上传失败：${(result as SyncResult.Err).kind.userMessage()}"
                            lastResult = msg to false
                            onNotice(msg)
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            )
            SecondaryButton(
                text = "从云端恢复",
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        val downloaded = onDownload()
                        busy = false
                        val file = downloaded.file
                        if (file != null && downloaded.summary != null) {
                            lastResult = null // 恢复策略弹层本身就是反馈，不重复提示
                            onDownloadedReady(file, downloaded.summary!!)
                        } else {
                            val err = downloaded.error
                            val msg = if (err is SyncResult.Err) "下载失败：${err.kind.userMessage()}" else "远端备份不可用"
                            lastResult = msg to false
                            onNotice(msg)
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }

        // 失败详情就地保留（成功反馈已由 Toast 轻提示，不再留横幅）
        lastResult?.let { (message, _) ->
            Text(
                text = message,
                style = FreshType.label,
                color = palette.error
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.track)
        )

        // 自动同步（§5.10：默认关闭；仅 Wi-Fi 默认开）。真机反馈 #2：五个 chip
        // 强挤一行会把「每次成功拉取后」折成两行、卡片高矮不齐——改两行自适应宽度
        Text(text = "自动同步", style = FreshType.body, color = palette.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SyncFrequency.entries.take(3).forEach { frequency ->
                ChoiceChip(
                    label = frequency.label,
                    selected = frequency == syncFrequency,
                    onClick = { onFrequencyChange(frequency) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SyncFrequency.entries.drop(3).forEach { frequency ->
                ChoiceChip(
                    label = frequency.label,
                    selected = frequency == syncFrequency,
                    onClick = { onFrequencyChange(frequency) }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "仅 Wi-Fi 下同步", style = FreshType.body, color = palette.onSurface)
            Switch(checked = syncWifiOnly, onCheckedChange = onWifiOnlyChange)
        }
        Text(
            text = "自动备份带时间戳，仅保留最近 5 份；手动上传的固定文件不受清理影响。",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )

        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.track)
        )

        // 上次同步状态（失败静默展示，§5.10 不弹窗）
        Text(text = "上次同步", style = FreshType.body, color = palette.onSurface)
        Text(
            text = if (lastAt == null) "从未同步" else "${formatSyncTime(lastAt)} · ${lastMessage.ifEmpty { if (lastOk == true) "成功" else "失败" }}",
            style = FreshType.label,
            color = if (lastOk == false) palette.error else palette.onSurfaceVariant
        )
    }
}

private fun formatSyncTime(ms: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
