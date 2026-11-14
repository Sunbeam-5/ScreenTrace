package com.screentrace.app.data.prefs

/**
 * §5.10 批 C：本机设备身份，导出 JSON 顶层 `device` 字段的唯一来源。
 *
 * 红线（CR-15 ③ 裁定）：`id` 只在 DataStore 首启生成，不写 Room、不进备份 settings、
 * **不随恢复写回本机**——否则换机恢复备份后两台手机会共用同一个 deviceId，
 * 又回到「远端互相覆盖」的老问题。昵称同理，只是给人看的标签。
 */
data class DeviceInfo(val id: String, val nickname: String, val model: String)

object DeviceIds {
    /** 自动备份文件名里的设备短 ID：只留字母数字、取前 8 位小写，保证文件名路径安全。 */
    fun short(id: String): String =
        id.filter { it.isLetterOrDigit() }.take(8).lowercase().ifEmpty { "unknown" }

    /** 设备目录名：只允许字母数字与 - _，长度封顶，空串回落 "unknown"。 */
    fun safe(id: String): String =
        id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .take(64)
            .ifEmpty { "unknown" }
}
