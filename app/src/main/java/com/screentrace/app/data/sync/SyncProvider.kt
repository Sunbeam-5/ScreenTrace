package com.screentrace.app.data.sync

/**
 * §5.10 云同步统一接口：WebDAV 与 Google Drive 各为一个适配器，
 * 新增云端（Dropbox/OneDrive）只允许新增适配器，不改备份格式与恢复流程（§6）。
 *
 * **8b 硬原则（8a 返修复核）**：远端数据必须经 [com.screentrace.app.data.backup.BackupRepository]
 * 的统一恢复入口（自洽校验 IngestSanity 已内置），任何 provider 不得另开直写库旁路。
 */
interface SyncProvider {
    /** 适配器标识（"webdav" / "google_drive"），用于状态记录。 */
    val id: String

    /** 验证地址/凭据可用（WebDAV 为 PROPFIND，Drive 为 about 探测）。 */
    suspend fun testConnection(): SyncResult

    /** 上传到远端固定路径（手动备份覆盖 `screen-time-backup.json`）。 */
    suspend fun upload(path: String, bytes: ByteArray): SyncResult

    /** 下载远端文件；不存在返回 [SyncFailure.NOT_FOUND]。 */
    suspend fun download(path: String): SyncResult

    /** 列出远端目录文件（自动备份清理「仅保留最近 5 份」用）。 */
    suspend fun list(): SyncResult

    /** 删除远端文件（清理旧自动备份用）。 */
    suspend fun delete(path: String): SyncResult

    /** 退出登录：清除本地令牌/会话（Google Drive 语义，WebDAV 为空实现）。 */
    suspend fun disconnect()
}

data class RemoteFile(val name: String, val sizeBytes: Long)

/** §5.10 失败分类提示（网络不可达 / 401 鉴权 / 404 目录不存在 / 格式错误 / 空间不足）。 */
enum class SyncFailure {
    NETWORK,   // 网络不可达 / 超时 / 断网
    AUTH,      // 401/403 鉴权失败
    NOT_FOUND, // 404 目录/文件不存在
    FORMAT,    // 远端内容不是合法备份
    QUOTA,     // 远端空间不足
    UNKNOWN
}

sealed interface SyncResult {
    data class Ok(
        val content: ByteArray? = null,
        val files: List<RemoteFile> = emptyList()
    ) : SyncResult {
        override fun equals(other: Any?): Boolean =
            other is Ok && other.files == files && content.contentEquals(other.content)
        override fun hashCode(): Int = files.hashCode() * 31 + (content?.contentHashCode() ?: 0)
    }

    data class Err(val kind: SyncFailure, val detail: String) : SyncResult
}

/** §5.10 失败分类 → 用户文案（统一一处，各 provider 复用）。 */
fun SyncFailure.userMessage(): String = when (this) {
    SyncFailure.NETWORK -> "网络不可达，请检查网络后重试"
    SyncFailure.AUTH -> "鉴权失败（401/403）：请核对账号与授权密码"
    SyncFailure.NOT_FOUND -> "远端目录或文件不存在（404）"
    SyncFailure.FORMAT -> "远端内容不是有效的备份文件"
    SyncFailure.QUOTA -> "远端存储空间不足"
    SyncFailure.UNKNOWN -> "同步失败，请稍后重试"
}
