package com.screentrace.app.data.sync

import com.screentrace.app.data.prefs.DeviceIds

/**
 * §5.10 批 C：远端路径与文件名的**唯一口径**（纯函数，便于单测与变异验证）。
 *
 * 旧实现把手动固定文件名绑死在 `WebDavProvider.MANUAL_FILE` 上（CR-15 评审 P3-4）：
 * 接第二个 provider 时会被复制成「两个后端抢同一个远端名」。这里统一由所有 provider 共用。
 *
 * 布局：
 * ```
 * <remoteDir>/screen-time-backup.json                          ← 升级前的旧备份（原地保留，归未知设备）
 * <remoteDir>/devices/<deviceId>/screen-time-backup.json       ← 本设备的手动备份
 * <remoteDir>/devices/<deviceId>/screen-time-backup-auto-<shortId>-yyyyMMdd-HHmm.json
 * ```
 */
object SyncPaths {
    const val DEVICES_DIR = "devices"
    const val MANUAL_FILE = "screen-time-backup.json"
    const val AUTO_PREFIX = "screen-time-backup-auto-"
    const val LEGACY_DEVICE_ID = "unknown"
    const val LEGACY_LABEL = "未知设备（旧备份）"

    /**
     * CR-21 ①：设备目录里的机型元数据文件。
     *
     * 设备列表要显示机型，但不能为了一个机型把每台设备的整份备份都拉下来，
     * 所以上传时额外写一份很小的 `device.json`（见 [DeviceMeta]）。
     */
    const val DEVICE_META_FILE = "device.json"

    fun deviceDir(id: String): String = "$DEVICES_DIR/${DeviceIds.safe(id)}/"

    fun manualPath(id: String): String = deviceDir(id) + MANUAL_FILE

    fun deviceMetaPath(id: String): String = deviceDir(id) + DEVICE_META_FILE

    fun autoFileName(id: String, stamp: String): String =
        "$AUTO_PREFIX${DeviceIds.short(id)}-$stamp.json"

    fun autoPath(id: String, stamp: String): String = deviceDir(id) + autoFileName(id, stamp)

    fun isAuto(name: String): Boolean = name.startsWith(AUTO_PREFIX) && name.endsWith(".json")

    fun isBackup(name: String): Boolean = name == MANUAL_FILE || isAuto(name)

    /**
     * 设备目录里「最值得先看的那份」：手动固定文件优先，其次按自动文件名倒序（字典序＝时间序，
     * 因为同一设备的短 ID 定长、时间戳定长）。
     */
    fun latestFile(names: List<String>): String? = names
        .filter { isBackup(it) }
        .maxWithOrNull(
            compareBy<String> { if (it == MANUAL_FILE) 1 else 0 }.thenBy { it }
        )
}
