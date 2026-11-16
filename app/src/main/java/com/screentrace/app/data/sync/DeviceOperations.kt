package com.screentrace.app.data.sync

import com.screentrace.app.data.backup.BackupRepository
import com.screentrace.app.data.backup.CrossDeviceMerge
import com.screentrace.app.data.backup.SameDeviceFingerprint
import com.screentrace.app.data.prefs.DeviceIds
import com.screentrace.app.data.prefs.SettingsStore
import java.time.ZoneId

/**
 * §5.10 CR-21：「切换到此设备」与「合并到本机」两件事的**编排**。
 *
 * 两条纪律（8b 硬原则）：
 *
 * 1. **远端数据必须经 [BackupRepository.restore] 统一恢复入口**落库——
 *    切换设备就是把该设备的备份恢复进**它自己的副本库**（OVERWRITE ＝ 精确副本），
 *    绝不直写数据库，也绝不用「取大合并」那套同设备语义。
 * 2. **合并目标恒为本机采集库**。旧设备自己的副本库与云端备份**不被改动**——
 *    这正是需求里「旧数据单独保留，新数据里同时含有旧数据」的那一半。
 *
 * 防重复合并（§4）：按「来源设备 ID + 已合并日期集合」记账，重复合并同一台设备
 * **只并入尚未合并过的新日期**，累加口径下不会把同一天翻倍。
 */
class DeviceOperations(
    private val sync: SyncManager,
    /** 本机采集库（合并的写入目标）。 */
    private val localBackups: BackupRepository,
    /** 某台远端设备副本库的 repository——同一个 restore 入口，只是换了库。 */
    private val deviceBackups: (String) -> BackupRepository,
    /** 该设备的本地副本库里是否已经有数据（决定要不要联网下载）。 */
    private val copyHasData: suspend (String) -> Boolean,
    private val settings: SettingsStore,
    /** 切指针：持久化到 DataStore + 更新内存里的「当前库」。 */
    private val onSwitch: suspend (String) -> Unit,
    /** 合并成功后自动上传一次（失败只提示，**不回滚**已完成的本地合并）。 */
    private val uploadAfterMerge: suspend () -> Boolean,
    private val zone: ZoneId = ZoneId.systemDefault()
) {

    sealed interface SwitchResult {
        /** [downloaded] = true 表示这次顺带把云端数据拉到了本地。 */
        data class Ok(val deviceId: String, val downloaded: Boolean, val message: String) : SwitchResult
        data class Failed(val message: String) : SwitchResult
    }

    sealed interface MergeResult {
        data class Ok(
            val deviceId: String,
            val mergedDates: Int,
            val uploaded: Boolean,
            val message: String
        ) : MergeResult

        /** 该设备的数据已经全部并入过，没有新日期。 */
        data class Skipped(val deviceId: String, val message: String) : MergeResult
        data class Failed(val message: String) : MergeResult
    }

    /**
     * CR-21 ③：切到某台设备。
     *
     * 首次切换要把它的云端备份下载下来、恢复到**它自己的副本库**，再切指针；
     * 本地副本已有数据时直接切（不联网、不重复下载）。
     */
    suspend fun switchToDevice(deviceId: String): SwitchResult {
        val localId = runCatching { settings.getOrCreateDeviceId() }.getOrDefault("")
        if (DeviceIds.safe(deviceId) == DeviceIds.safe(localId)) {
            onSwitch(localId)
            return SwitchResult.Ok(localId, downloaded = false, message = "已切回本机")
        }
        var downloaded = false
        if (!copyHasData(deviceId)) {
            val remote = sync.fetchDeviceBackup(deviceId)
            val file = remote.file
                ?: return SwitchResult.Failed("下载失败：${remote.errorText()}")
            val restored = runCatching {
                deviceBackups(deviceId).restore(
                    file = file,
                    strategy = BackupRepository.Strategy.OVERWRITE,
                    importSettings = false
                )
            }
            if (restored.isFailure) {
                return SwitchResult.Failed("写入本地副本失败：${restored.exceptionOrNull()?.message ?: "未知错误"}")
            }
            downloaded = true
        }
        onSwitch(deviceId)
        return SwitchResult.Ok(
            deviceId = deviceId,
            downloaded = downloaded,
            message = if (downloaded) "已切换，并下载了该设备的数据" else "已切换到该设备"
        )
    }

    /**
     * CR-21 ②：把某台远端设备的最新备份**累加**并入本机采集库。
     *
     * 流程：下载 → 判定来源是否本机自己（CR-22 ②，重叠日期以本机为准、不累加）
     * → 按「尚未合并过的日期」累加并入本机采集库（单事务，失败整体回滚）
     * → 记账 → 自动上传一次（上传失败只提示，不回滚本地合并）。
     */
    suspend fun mergeIntoLocal(deviceId: String): MergeResult {
        val remote = sync.fetchDeviceBackup(deviceId)
        val file = remote.file
            ?: return MergeResult.Failed("下载失败：${remote.errorText()}")

        val already = settings.mergedDatesOf(deviceId)
        val candidates = CrossDeviceMerge.allowedDatesOf(file, already, zone)
        if (candidates.isEmpty()) {
            return MergeResult.Skipped(deviceId, "该设备的数据已全部合并过，没有新日期可并入")
        }

        // CR-22 ②：来源若是**本机自己**的旧备份，重叠日期不能累加（会翻倍）。
        val localId = runCatching { settings.getOrCreateDeviceId() }.getOrDefault("")
        val localDays = localBackups.dayFingerprints(candidates)
        val localEvents = localBackups.dayEventKeys(candidates, zone)
        val verdict = SameDeviceFingerprint.detect(
            file = file,
            localDays = localDays,
            localDeviceId = localId,
            localEvents = localEvents,
            zone = zone
        )
        val skipDates = verdict.sameDeviceDates
        val allowed = candidates - skipDates
        if (allowed.isEmpty()) {
            return MergeResult.Skipped(
                deviceId,
                "判定为同一台机器的旧备份（${verdict.reason}），重叠的 ${skipDates.size} 天以本机为准，没有新日期可并入"
            )
        }

        val merged = runCatching { localBackups.mergeAccumulate(file, allowed) }
        if (merged.isFailure) {
            return MergeResult.Failed(
                "合并失败，本机数据未改动：${merged.exceptionOrNull()?.message ?: "未知错误"}"
            )
        }
        settings.recordMergedDates(deviceId, allowed)

        val uploaded = runCatching { uploadAfterMerge() }.getOrDefault(false)
        val uploadNote = if (uploaded) "，已自动上传一次" else "，自动上传失败（本机合并已保留）"
        val skipNote = if (skipDates.isEmpty()) {
            ""
        } else {
            "（判定为同一台机器，${skipDates.size} 天以本机为准、未累加）"
        }
        return MergeResult.Ok(
            deviceId = deviceId,
            mergedDates = allowed.size,
            uploaded = uploaded,
            message = "已并入 ${allowed.size} 天的数据$skipNote$uploadNote"
        )
    }

    private fun SyncManager.DownloadedBackup.errorText(): String =
        (error as? SyncResult.Err)?.kind?.userMessage() ?: "远端备份不可用"
}
