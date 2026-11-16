package com.screentrace.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.screentrace.app.data.sync.SyncManager
import com.screentrace.app.data.sync.deviceBackupSummary
import com.screentrace.app.data.sync.userMessage
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import kotlinx.coroutines.launch

/**
 * §5.2 CR-20 ① / CR-21 ①③：顶栏「切换设备」点开的**设备列表面板**浮层。
 *
 * 不是新页面、不写路由：只是顶栏下方浮出一块面板，列出各设备并按需展开只读摘要。
 * 数据来源是批 C 的两个只读 API——**只在面板被点开时**发一次网络请求（`LaunchedEffect(Unit)`），
 * 之后点设备才下载摘要。
 *
 * CR-21 给它加了两样：
 * - **机型**（§5.10 批 C 以来列表只显示短 ID，用户认不出哪台是哪台）；
 * - 两枚**真实动作**：「切换到此设备」（数据拉到本地、整个 App 切过去）与
 *   「合并到本机」（累加并入本机数据，二次确认）。
 */
@Composable
fun DevicePanel(
    onClose: () -> Unit,
    onListDevices: suspend () -> SyncManager.DevicesResult,
    onInspectDevice: suspend (String) -> SyncManager.DownloadedBackup,
    /** CR-21 ③：当前正在查看哪台设备（用于标「· 当前」）。 */
    currentDeviceId: String,
    /** CR-21 ③：切换设备；返回一句给用户看的结果文案。 */
    onSwitchDevice: suspend (String) -> String,
    /** CR-21 ②：合并到本机；返回一句给用户看的结果文案。 */
    onMergeDevice: suspend (String) -> String,
    modifier: Modifier = Modifier
) {
    val palette = LocalFreshPalette.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<SyncManager.DeviceBackup>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var mergeTarget by remember { mutableStateOf<SyncManager.DeviceBackup?>(null) }

    LaunchedEffect(Unit) {
        busy = true
        val result = onListDevices()
        busy = false
        val err = result.error
        if (err != null) {
            error = err.kind.userMessage()
        } else {
            error = null
            devices = result.devices
        }
    }

    Column(
        modifier = modifier
            .widthIn(min = 280.dp, max = 320.dp)
            .shadow(
                elevation = if (palette.darkTheme) 0.dp else 8.dp,
                shape = RoundedCornerShape(28.dp),
                ambientColor = palette.shadow,
                spotColor = palette.shadow
            )
            .clip(RoundedCornerShape(28.dp))
            // CR-20 ③：浮层底色用不透明的 panelSurface（否则壁纸透上来会与文字叠字）
            .background(palette.panelSurface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "切换设备",
                style = FreshType.body,
                color = palette.onSurface,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .semantics {
                        contentDescription = "关闭设备面板"
                        role = Role.Button
                    }
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = palette.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // CR-21 补记：动作结果提示**置于设备列表之前**。
        // 原先渲染在列表末尾，列表一长就被顶出屏幕——真机 dump 与截图都看不到
        // 「已并入 N 天的数据」，用户以为没生效（§5.10 / §7-46）。
        actionMessage?.let {
            Text(text = it, style = FreshType.label, color = palette.onSurface)
        }

        when {
            busy -> Text(
                text = "正在读取设备列表…",
                style = FreshType.label,
                color = palette.onSurfaceVariant
            )
            error != null -> Text(
                text = "读取失败：$error",
                style = FreshType.label,
                color = palette.error
            )
            devices.isNullOrEmpty() -> Text(
                text = "远端暂无其它设备的备份",
                style = FreshType.label,
                color = palette.onSurfaceVariant
            )
            else -> devices.orEmpty().forEach { device ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(
                            enabled = !busy && device.fileCount > 0
                        ) {
                            scope.launch {
                                busy = true
                                summary = deviceBackupSummary(onInspectDevice(device.deviceId))
                                busy = false
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        // CR-21 ①：label 已经是「机型优先」的显示名（拿不到机型才回落短 ID）。
                        // LEGACY_LABEL 已含「旧备份」，不再叠后缀。
                        text = buildString {
                            append(device.label)
                            if (device.isLocal) append(" · 本机")
                            if (device.deviceId == currentDeviceId) append(" · 当前")
                        },
                        style = FreshType.body,
                        color = palette.onSurface
                    )
                    Text(
                        text = buildString {
                            if (!device.isLegacy && device.model == null) append("机型未知 · ")
                            append("${device.fileCount} 份备份 · ${device.latestName ?: "尚未上传"}")
                        },
                        style = FreshType.label,
                        color = palette.onSurfaceVariant
                    )
                    // CR-21 ②③：两枚真实动作按钮（合并需二次确认，弹层在下面）
                    // 面板窄（280~320dp），纵向排一行一个，避免文案折行（真机实测）
                    DeviceActionRow(
                        switchText = if (device.isLocal) "切回本机" else "切换到此设备",
                        switchEnabled = !busy && (device.isLocal || device.fileCount > 0),
                        mergeEnabled = !busy && !device.isLocal && device.fileCount > 0,
                        onSwitch = {
                            scope.launch {
                                busy = true
                                actionMessage = onSwitchDevice(device.deviceId)
                                busy = false
                            }
                        },
                        onMerge = { mergeTarget = device },
                        stacked = true
                    )
                }
            }
        }

        summary?.let {
            Text(text = it, style = FreshType.label, color = palette.onSurfaceVariant)
        }
        Text(
            text = "点一行看摘要；「切换到此设备」把它的数据拉到本地并整页切换；「合并到本机」按累加口径并入本机数据。",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
    }

    // CR-21 ②：合并二次确认（与云同步页共用同一个弹层）
    mergeTarget?.let { target ->
        MergeConfirmSheet(
            deviceLabel = target.label,
            onDismiss = { mergeTarget = null },
            onConfirm = {
                mergeTarget = null
                scope.launch {
                    busy = true
                    actionMessage = onMergeDevice(target.deviceId)
                    busy = false
                }
            }
        )
    }
}
