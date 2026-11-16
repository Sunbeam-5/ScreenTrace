package com.screentrace.app.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * §5.10 CR-21 ①：设备目录里的**机型元数据**。
 *
 * 设备列表要显示「这台设备是什么机型」而不是一串短 ID，但备份文件本身可能几百 KB，
 * 为了拿一个机型把每台设备的最新备份整份拉一遍是不可接受的（§6 红线：取机型不得
 * 批量下载整份备份）。所以上传时在设备目录里**额外**写一份很小的 `device.json`：
 *
 * ```
 * <remoteDir>/devices/<deviceId>/device.json   ← { id, nickname, model }
 * ```
 *
 * 升级前已上传的老设备目录里没有这个文件——那台设备的机型由「展开摘要」时从备份
 * 顶层 `device.model` 补出来（见 [SyncManager.inspectDevice]），下次上传后目录里
 * 就有了。
 */
@Serializable
data class DeviceMeta(
    val id: String,
    val nickname: String = "",
    val model: String = ""
) {
    /** 机型优先；没有机型时回落到昵称，都没有就 null（UI 显示「机型未知」）。 */
    val displayModel: String?
        get() = model.takeIf { it.isNotBlank() } ?: nickname.takeIf { it.isNotBlank() }
}

object DeviceMetaCodec {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(meta: DeviceMeta): String = json.encodeToString(DeviceMeta.serializer(), meta)

    /** 解析失败一律当「没有元数据」，不阻断设备列表（老设备 / 文件损坏都走这条路）。 */
    fun decode(text: String): DeviceMeta? =
        runCatching { json.decodeFromString(DeviceMeta.serializer(), text) }.getOrNull()
}
