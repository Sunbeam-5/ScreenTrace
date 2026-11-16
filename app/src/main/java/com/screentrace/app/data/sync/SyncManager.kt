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
        // CR-21 ①：顺手写一份机型元数据，让设备列表能显示「什么机型」而不是短 ID。
        // 元数据失败**不影响**备份上传的结果（备份已经传上去了）。
        if (result is SyncResult.Ok) writeDeviceMeta(id)
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
            writeDeviceMeta(id)
            runCatching { pruneAutoBackups(id) }
        }
        record(result)
        return result
    }

    /**
     * CR-21 ①：把本机机型写进设备目录的 `device.json`（小型、幂等覆盖）。
     *
     * 只为让设备列表显示机型；写失败不影响上传结果，也不影响备份本身。
     */
    private suspend fun writeDeviceMeta(id: String) {
        val info = runCatching { device() }.getOrNull() ?: return
        val meta = DeviceMeta(id = info.id, nickname = info.nickname, model = info.model)
        runCatching {
            provider.upload(
                SyncPaths.deviceMetaPath(id),
                DeviceMetaCodec.encode(meta).toByteArray(Charsets.UTF_8)
            )
        }
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
     *
     * CR-21：这个下载动作也被「切换设备」与「合并到本机」复用（[fetchDeviceBackup]）——
     * 下载与解码仍在这里，**写库**由 [BackupRepository.restore] 统一入口负责。
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

    /**
     * CR-21：取某台设备**最新的一份备份**（下载 + 解码，**不写库**）。
     *
     * 「切换到此设备」与「合并到本机」都从这里拿数据源；写库分别走
     * [BackupRepository.restore]（远端副本库）与 [BackupRepository.mergeAccumulate]（本机采集库）。
     */
    suspend fun fetchDeviceBackup(deviceId: String): DownloadedBackup = inspectDevice(deviceId)

    /** 列出本机 + 所有远端设备目录 + 根目录旧备份，供云同步页与顶栏设备面板展示。 */
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
                    val allNames = filesIn(SyncPaths.deviceDir(id))
                    val names = allNames.filter { SyncPaths.isBackup(it) }
                    // CR-21 ①：机型走 device.json 小文件；老设备目录里没有这个文件，
                    // 不去白跑一次 404，更**不**为了拿机型下载整份备份。
                    val meta = if (SyncPaths.DEVICE_META_FILE in allNames) fetchDeviceMeta(id) else null
                    val model = meta?.displayModel
                        ?: if (id == localId) local.model.takeIf { it.isNotBlank() } else null
                    out += DeviceBackup(
                        deviceId = id,
                        label = model
                            ?: if (id == localId) local.nickname else "设备 ${DeviceIds.short(id)}",
                        model = model,
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
            val model = local.model.takeIf { it.isNotBlank() }
            out.add(
                0,
                DeviceBackup(
                    deviceId = localId,
                    label = model ?: local.nickname,
                    model = model,
                    isLocal = true,
                    isLegacy = false,
                    fileCount = 0,
                    latestName = null
                )
            )
        }

        val legacy = rootBackupNames().filter { SyncPaths.isBackup(it) }
        if (legacy.isNotEmpty()) {
            out += DeviceBackup(
                deviceId = SyncPaths.LEGACY_DEVICE_ID,
                label = SyncPaths.LEGACY_LABEL,
                model = null,
                isLocal = false,
                isLegacy = true,
                fileCount = legacy.size,
                latestName = SyncPaths.latestFile(legacy)
            )
        }
        return DevicesResult(out)
    }

    /** CR-21 ①：读某台设备的机型元数据；没有 / 损坏都返回 null（列表回落短 ID）。 */
    private suspend fun fetchDeviceMeta(deviceId: String): DeviceMeta? {
        val result = provider.download(SyncPaths.deviceMetaPath(deviceId))
        if (result !is SyncResult.Ok) return null
        val content = result.content ?: return null
        return DeviceMetaCodec.decode(content.decodeToString())
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
                        },
                        // CR-21 ①：老设备目录里没有 device.json 时，机型从这里补出来
                        deviceModel = decoded.file.device?.model?.takeIf { it.isNotBlank() }
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
        /** 显示名：CR-21 ① 起**机型优先**，拿不到机型才回落昵称 / 短 ID。 */
        val label: String,
        val isLocal: Boolean,
        val isLegacy: Boolean,
        val fileCount: Int,
        val latestName: String?,
        /** 机型（device.json 或本机 Build.MODEL）；null = 未知机型。 */
        val model: String? = null
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
        val deviceLabel: String? = null,
        /** CR-21 ①：来源设备的机型；老备份没有该字段时为 null。 */
        val deviceModel: String? = null
    )

    companion object {
        const val AUTO_PREFIX = SyncPaths.AUTO_PREFIX
        const val AUTO_KEEP = 5 // §5.10：自动备份仅保留最近 5 份
    }
}
