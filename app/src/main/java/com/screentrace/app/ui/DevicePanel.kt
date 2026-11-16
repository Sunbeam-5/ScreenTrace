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
 * §5.2 CR-20 ①：顶栏「切换设备」点开的**设备列表面板**浮层。
 *
 * 不是新页面、不写路由：只是顶栏下方浮出一块面板，列出各设备并按需展开只读摘要。
 * 数据来源是批 C 的两个只读 API——**只在面板被点开时**发一次网络请求（`LaunchedEffect(Unit)`），
 * 之后点设备才下载摘要；不写本机采集库、不自动合并、不提供跨设备恢复入口。
 *
 * 批 D 时期它是下拉面板里的第二层（带「返回快捷菜单」箭头）；CR-20 把动作拆回顶栏后
 * 面板升为顶层浮层，**不再有上一层可返回**，改为标题行右侧的关闭按钮；
 * 点面板外部或系统返回键同样收起。
 */
@Composable
fun DevicePanel(
    onClose: () -> Unit,
    onListDevices: suspend () -> SyncManager.DevicesResult,
    onInspectDevice: suspend (String) -> SyncManager.DownloadedBackup,
    modifier: Modifier = Modifier
) {
    val palette = LocalFreshPalette.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<SyncManager.DeviceBackup>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }

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
                        // 与云同步页设备卡片同一套标签：label 本身已写「未知设备（旧备份）」，
                        // 不再叠后缀（批 C 真机收口去掉过那个重复文案）。
                        text = device.label + (if (device.isLocal) " · 本机" else ""),
                        style = FreshType.body,
                        color = palette.onSurface
                    )
                    Text(
                        text = "${device.fileCount} 份备份 · ${device.latestName ?: "尚未上传"}",
                        style = FreshType.label,
                        color = palette.onSurfaceVariant
                    )
                }
            }
        }

        summary?.let {
            Text(text = it, style = FreshType.label, color = palette.onSurfaceVariant)
        }
        Text(
            text = "只可查看摘要，不会写入本机数据或自动合并。",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
    }
}
