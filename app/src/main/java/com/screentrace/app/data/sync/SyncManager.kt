package com.screentrace.app.data.sync

import com.screentrace.app.data.backup.BackupCodec
import com.screentrace.app.data.backup.BackupFile
import com.screentrace.app.data.backup.BackupRepository
import com.screentrace.app.data.backup.BackupSummary
import com.screentrace.app.data.prefs.DeviceIds
import com.screentrace.app.data.prefs.DeviceInfo
import com.screentrace.app.data.prefs.SettingsStore
import com.screentrace.app.data.prefs.SyncFrequency
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first

/**
 * §5.10 云同步编排：上传 / 下载 / 恢复 / 自动备份清理 / 多设备只读查看（CR-15 ③ 批 C）。
 *
 * **8b 硬原则**：下载到的远端备份必须走 [BackupRepository.restore] 的统一恢复入口
 * （IngestSanity 自洽校验、单事务回滚都已内置），本类**绝不直写数据库**。
 * [inspectDevice] 更严格：只下载 + 解码，连恢复入口都不碰，绝不写本机采集库。
 *
 * 批 C 布局见 [SyncPaths]：每台设备写自己的 `devices/<deviceId>/`，不再互相覆盖；
 * 升级前根目录的旧备份原地保留并归「未知设备」，本机「恢复」可回落读取。
 */
class SyncManager(
    private val provider: SyncProvider,
    private val backups: BackupRepository,
    private val settings: SettingsStore,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** 本机身份；单测可注入固定值。默认 unknown 保证无身份时也不写坏目录。 */
    private val device: suspend () -> DeviceInfo = {
        DeviceInfo(SyncPaths.LEGACY_DEVICE_ID, "未知设备", "")
    }
) {

    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    private suspend fun currentId(): String = DeviceIds.safe(
        runCatching { device().id }.getOrDefault(SyncPaths.LEGACY_DEVICE_ID)
    )

    suspend fun testConnection(): SyncResult {
        val result = provider.testConnection()
        record(result)
        return result
    }

    /** 手动上传：当前完整备份写本设备目录的固定文件（§5.10）。 */
    suspend fun uploadManual(appVersion: String): SyncResult {
        val id = currentId()
        val result = runCatching { backups.buildFile(appVersion) }
            .mapCatching { file ->
                provider.upload(
                    SyncPaths.manualPath(id),
                    BackupCodec.encode(file).toByteArray(Charsets.UTF_8)
                )
            }
            .getOrElse { SyncResult.Err(SyncFailure.UNKNOWN, it.message ?: "导出失败") }
        record(result)
        return result
    }

    /** 自动上传：文件名带设备短 ID + 时间戳，成功后再清理本设备目录的超额旧份。 */
    suspend fun uploadAuto(appVersion: String): SyncResult {
        val id = currentId()
        val stamp = LocalDateTime.now(zone).format(timeFormat)
        val result = runCatching { backups.buildFile(appVersion) }
            .mapCatching { file ->
                provider.upload(
                    SyncPaths.autoPath(id, stamp),
                    BackupCodec.encode(file).toByteArray(Charsets.UTF_8)
                )
            }
            .getOrElse { SyncResult.Err(SyncFailure.UNKNOWN, it.message ?: "导出失败") }
        if (result is SyncResult.Ok) {
            runCatching { pruneAutoBackups(id) }
        }
        record(result)
        return result
    }

    /** 仅保留最近 [AUTO_KEEP] 份自动备份；只清本设备目录，手动固定文件与其它设备一律不动。 */
    private suspend fun pruneAutoBackups(id: String) {
        val dir = SyncPaths.deviceDir(id)
        val autos = filesIn(dir).filter { SyncPaths.isAuto(it) }.sortedDescending()
        autos.drop(AUTO_KEEP).forEach { provider.delete(dir + it) }
    }

    /**
     * 下载本设备的固定备份并解码（恢复的**第一步**；策略二选一在 UI 层，执行仍走
     * [BackupRepository.restore] 统一入口）。本设备目录没有时回落读根目录旧固定文件，
     * 保证升级前的备份仍可恢复（批 C 兼容条款）。
     */
    suspend fun downloadManual(): DownloadedBackup {
        val primary = provider.download(SyncPaths.manualPath(currentId()))
        val result = if (primary is SyncResult.Err && primary.kind == SyncFailure.NOT_FOUND) {
            provider.download(SyncPaths.MANUAL_FILE)
        } else primary
        return decode(result)
    }

    /**
     * §5.10 批 C「切换查看只读」：下载指定设备的最新备份并解析摘要，**不写库、不恢复、不合并**。
     * 手动固定文件优先，缺失时退到该设备最新的自动备份；根目录旧备份走 [SyncPaths.LEGACY_DEVICE_ID]。
     */
    suspend fun inspectDevice(deviceId: String): DownloadedBackup {
        val isLegacy = deviceId == SyncPaths.LEGACY_DEVICE_ID
        val dir = if (isLegacy) "" else SyncPaths.deviceDir(deviceId)
        val manual = provider.download(dir + SyncPaths.MANUAL_FILE)
        val result = if (manual is SyncResult.Err && manual.kind == SyncFailure.NOT_FOUND) {
            val names = if (isLegacy) rootBackupNames() else filesIn(dir)
            val latest = names.filter { SyncPaths.isAuto(it) }.maxOrNull()
            if (latest == null) manual else provider.download(dir + latest)
        } else manual
        return decode(result)
    }

    /** 列出本机 + 所有远端设备目录 + 根目录旧备份，供云同步页只读展示。 */
    suspend fun listDevices(): DevicesResult {
        val local = runCatching { device() }.getOrDefault(
            DeviceInfo(SyncPaths.LEGACY_DEVICE_ID, "未知设备", "")
        )
        val localId = DeviceIds.safe(local.id)
        val out = mutableListOf<DeviceBackup>()

        when (val listed = provider.listIn("${SyncPaths.DEVICES_DIR}/")) {
            is SyncResult.Err -> if (listed.kind != SyncFailure.NOT_FOUND) {
                return DevicesResult(emptyList(), listed)
            }
            is SyncResult.Listed -> listed.entries
                .filter { it.isCollection && it.name != SyncPaths.DEVICES_DIR }
                .map { it.name }
                .distinct()
                .sorted()
                .forEach { id ->
                    val names = filesIn(SyncPaths.deviceDir(id)).filter { SyncPaths.isBackup(it) }
                    out += DeviceBackup(
                        deviceId = id,
                        label = if (id == localId) local.nickname else "设备 ${DeviceIds.short(id)}",
                        isLocal = id == localId,
                        isLegacy = false,
                        fileCount = names.size,
                        latestName = SyncPaths.latestFile(names)
                    )
                }
            else -> Unit
        }

        // 本机还没上传过也要有一条，让用户看得到自己的 deviceId 与昵称
        if (out.none { it.isLocal }) {
            out.add(0, DeviceBackup(localId, local.nickname, true, false, 0, null))
        }

        val legacy = rootBackupNames().filter { SyncPaths.isBackup(it) }
        if (legacy.isNotEmpty()) {
            out += DeviceBackup(
                deviceId = SyncPaths.LEGACY_DEVICE_ID,
                label = SyncPaths.LEGACY_LABEL,
                isLocal = false,
                isLegacy = true,
                fileCount = legacy.size,
                latestName = SyncPaths.latestFile(legacy)
            )
        }
        return DevicesResult(out)
    }

    private fun decode(result: SyncResult): DownloadedBackup = when (result) {
        is SyncResult.Err -> DownloadedBackup(error = result)
        is SyncResult.Listed -> DownloadedBackup(
            error = SyncResult.Err(SyncFailure.FORMAT, "远端响应不是文件内容")
        )
        is SyncResult.Ok -> {
            val bytes = result.content
            if (bytes == null) {
                DownloadedBackup(error = SyncResult.Err(SyncFailure.FORMAT, "远端文件为空"))
            } else {
                when (val decoded = BackupCodec.decode(bytes.decodeToString())) {
                    is BackupCodec.DecodeResult.Ok -> DownloadedBackup(
                        file = decoded.file,
                        summary = decoded.summary,
                        deviceLabel = decoded.file.device?.let { d ->
                            d.nickname.takeIf { it.isNotBlank() }
                                ?: d.model.takeIf { it.isNotBlank() }
                                ?: "设备 ${DeviceIds.short(d.id)}"
                        }
                    )
                    is BackupCodec.DecodeResult.Invalid -> DownloadedBackup(
                        error = SyncResult.Err(SyncFailure.FORMAT, decoded.reason)
                    )
                }
            }
        }
    }

    private suspend fun filesIn(dir: String): List<String> = when (val r = provider.listIn(dir)) {
        is SyncResult.Listed -> r.entries.filter { !it.isCollection }.map { it.name }
        is SyncResult.Ok -> r.files.map { it.name } // 兼容只实现 list() 的 provider
        else -> emptyList()
    }

    private suspend fun rootBackupNames(): List<String> = when (val r = provider.list()) {
        is SyncResult.Ok -> r.files.map { it.name }
        is SyncResult.Listed -> r.entries.filter { !it.isCollection }.map { it.name }
        else -> emptyList()
    }

    /** 恢复执行：与本地文件恢复同一入口（覆盖/合并二选一，单事务可回滚）。 */
    suspend fun restore(
        file: BackupFile,
        strategy: BackupRepository.Strategy,
        importSettings: Boolean
    ): BackupRepository.RestoreResult = backups.restore(file, strategy, importSettings)

    /** 当前频率是否应触发自动同步（「每次拉取后」/按天窗口，§5.10）。 */
    suspend fun shouldAutoSync(lastSyncAt: Long?): Boolean {
        val frequency = settings.syncFrequency.first()
        return when (frequency) {
            SyncFrequency.OFF -> false
            SyncFrequency.EVERY_PULL -> true
            else -> {
                val windowMs = frequency.days * 24 * 60 * 60 * 1000L
                lastSyncAt == null || System.currentTimeMillis() - lastSyncAt >= windowMs
            }
        }
    }

    private suspend fun record(result: SyncResult) {
        val message = when (result) {
            is SyncResult.Ok, is SyncResult.Listed -> "成功"
            is SyncResult.Err -> result.kind.userMessage()
        }
        settings.recordSync(System.currentTimeMillis(), result is SyncResult.Ok, message)
    }

    /** 设备列表里的一行；[latestName] 为 null 表示该设备还没有任何备份。 */
    data class DeviceBackup(
        val deviceId: String,
        val label: String,
        val isLocal: Boolean,
        val isLegacy: Boolean,
        val fileCount: Int,
        val latestName: String?
    )

    data class DevicesResult(
        val devices: List<DeviceBackup>,
        val error: SyncResult.Err? = null
    )

    data class DownloadedBackup(
        val file: BackupFile? = null,
        val summary: BackupSummary? = null,
        val error: SyncResult? = null,
        /** v5 备份的来源昵称；旧备份为 null，UI 显示「未知设备」。 */
        val deviceLabel: String? = null
    )

    companion object {
        const val AUTO_PREFIX = SyncPaths.AUTO_PREFIX
        const val AUTO_KEEP = 5 // §5.10：自动备份仅保留最近 5 份
    }
}
