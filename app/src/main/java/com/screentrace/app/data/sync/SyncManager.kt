package com.screentrace.app.data.sync

import com.screentrace.app.data.backup.BackupCodec
import com.screentrace.app.data.backup.BackupRepository
import com.screentrace.app.data.prefs.SettingsStore
import com.screentrace.app.data.prefs.SyncFrequency
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first

/**
 * §5.10 云同步编排：上传 / 下载 / 恢复 / 自动备份清理。
 *
 * **8b 硬原则**：下载到的远端备份必须走 [BackupRepository.restore] 的统一恢复入口
 * （IngestSanity 自洽校验、单事务回滚都已内置），本类**绝不直写数据库**。
 * 手动备份固定文件 `screen-time-backup.json`；自动备份带时间戳、仅保留最近 5 份，
 * 手动固定文件不受清理影响。失败分类提示统一走 [SyncFailure.userMessage]，
 * 状态静默写入 SettingsStore（设置页展示，不弹窗）。
 */
class SyncManager(
    private val provider: SyncProvider,
    private val backups: BackupRepository,
    private val settings: SettingsStore,
    private val zone: ZoneId = ZoneId.systemDefault()
) {

    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    suspend fun testConnection(): SyncResult {
        val result = provider.testConnection()
        record(result)
        return result
    }

    /** 手动上传：当前完整备份覆盖远端固定文件（§5.10）。 */
    suspend fun uploadManual(appVersion: String): SyncResult {
        val result = runCatching { backups.buildFile(appVersion) }
            .mapCatching { file -> provider.upload(WebDavProvider.MANUAL_FILE, BackupCodec.encode(file).toByteArray(Charsets.UTF_8)) }
            .getOrElse { SyncResult.Err(SyncFailure.UNKNOWN, it.message ?: "导出失败") }
        record(result)
        return result
    }

    /**
     * 自动上传：带时间戳文件名 `screen-time-backup-auto-YYYYMMDD-HHmm.json`，
     * 成功后清理超出保留数量（5 份）的旧自动备份；手动固定文件不清理（§5.10）。
     */
    suspend fun uploadAuto(appVersion: String): SyncResult {
        val result = runCatching { backups.buildFile(appVersion) }
            .mapCatching { file ->
                val name = "screen-time-backup-auto-${LocalDateTime.now(zone).format(timeFormat)}.json"
                provider.upload(name, BackupCodec.encode(file).toByteArray(Charsets.UTF_8))
            }
            .getOrElse { SyncResult.Err(SyncFailure.UNKNOWN, it.message ?: "导出失败") }
        if (result is SyncResult.Ok) {
            runCatching { pruneAutoBackups() }
        }
        record(result)
        return result
    }

    /** 仅保留最近 [AUTO_KEEP] 份自动备份（按文件名字典序 = 时间序）。 */
    private suspend fun pruneAutoBackups() {
        val listed = provider.list()
        if (listed !is SyncResult.Ok) return
        val autos = listed.files
            .map { it.name }
            .filter { it.startsWith(AUTO_PREFIX) && it.endsWith(".json") }
            .sortedDescending()
        autos.drop(AUTO_KEEP).forEach { provider.delete(it) }
    }

    /**
     * 下载远端备份并解码（恢复的**第一步**；策略二选一在 UI 层，执行仍走
     * [BackupRepository.restore] 统一入口）。
     */
    suspend fun downloadManual(): DownloadedBackup {
        return when (val result = provider.download(WebDavProvider.MANUAL_FILE)) {
            is SyncResult.Err -> DownloadedBackup(error = result)
            is SyncResult.Ok -> {
                val bytes = result.content ?: return DownloadedBackup(
                    error = SyncResult.Err(SyncFailure.FORMAT, "远端文件为空")
                )
                when (val decoded = BackupCodec.decode(bytes.decodeToString())) {
                    is BackupCodec.DecodeResult.Ok -> DownloadedBackup(file = decoded.file, summary = decoded.summary)
                    is BackupCodec.DecodeResult.Invalid -> DownloadedBackup(
                        error = SyncResult.Err(SyncFailure.FORMAT, decoded.reason)
                    )
                }
            }
        }
    }

    /** 恢复执行：与本地文件恢复同一入口（覆盖/合并二选一，单事务可回滚）。 */
    suspend fun restore(
        file: com.screentrace.app.data.backup.BackupFile,
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
            is SyncResult.Ok -> "成功"
            is SyncResult.Err -> result.kind.userMessage()
        }
        settings.recordSync(System.currentTimeMillis(), result is SyncResult.Ok, message)
    }

    data class DownloadedBackup(
        val file: com.screentrace.app.data.backup.BackupFile? = null,
        val summary: com.screentrace.app.data.backup.BackupSummary? = null,
        val error: SyncResult? = null
    )

    companion object {
        const val AUTO_PREFIX = "screen-time-backup-auto-"
        const val AUTO_KEEP = 5 // §5.10：自动备份仅保留最近 5 份
    }
}
