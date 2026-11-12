package com.screentrace.app.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.data.backup.BackupRepository
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.prefs.SettingsStore
import com.screentrace.app.data.prefs.SyncFrequency
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 内存假 provider：测 [SyncManager] 编排语义（清理策略/解码/失败记录），不碰真网络。 */
private class FakeSyncProvider : SyncProvider {
    override val id = "fake"
    val remote = linkedMapOf<String, ByteArray>()
    var failNext: SyncFailure? = null

    private fun gate(): SyncResult? = failNext?.let { SyncResult.Err(it, "fake") }.also { failNext = null }

    override suspend fun testConnection(): SyncResult = gate() ?: SyncResult.Ok()
    override suspend fun upload(path: String, bytes: ByteArray): SyncResult {
        gate()?.let { return it }
        remote[path] = bytes
        return SyncResult.Ok()
    }
    override suspend fun download(path: String): SyncResult {
        gate()?.let { return it }
        val bytes = remote[path] ?: return SyncResult.Err(SyncFailure.NOT_FOUND, "404")
        return SyncResult.Ok(content = bytes)
    }
    override suspend fun list(): SyncResult =
        gate() ?: SyncResult.Ok(files = remote.keys.map { RemoteFile(it, remote.getValue(it).size.toLong()) })
    override suspend fun delete(path: String): SyncResult {
        gate()?.let { return it }
        remote.remove(path)
        return SyncResult.Ok()
    }
    override suspend fun disconnect() {}
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncManagerTest {

    private lateinit var db: ScreenTraceDatabase
    private lateinit var prefs: DataStore<Preferences>
    private lateinit var settings: SettingsStore
    private lateinit var backups: BackupRepository
    private lateinit var provider: FakeSyncProvider
    private lateinit var manager: SyncManager
    private lateinit var prefsFile: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ScreenTraceDatabase::class.java)
            .allowMainThreadQueries().build()
        prefsFile = File(context.cacheDir, "sync-test-${System.nanoTime()}.preferences_pb")
        prefs = PreferenceDataStoreFactory.create { prefsFile }
        settings = SettingsStore(prefs)
        backups = BackupRepository(db, settings)
        provider = FakeSyncProvider()
        manager = SyncManager(provider, backups, settings)
    }

    @After
    fun tearDown() {
        db.close()
        prefsFile.delete()
    }

    @Test
    fun `自动备份仅保留最近 5 份且不动手动固定文件`() = runBlocking {
        repeat(7) { i ->
            // 时间戳文件名可区分序（同分钟会重名覆盖，这里用不同名模拟 7 个批次）
            provider.remote["screen-time-backup-auto-20261112-000$i.json"] = "{}".toByteArray()
        }
        provider.remote[SyncWebDAVManualFile] = "{}".toByteArray()

        // 逐次上传触发清理（名字带同一前缀 + 时间戳）
        repeat(3) { manager.uploadAuto("test") }

        val autos = provider.remote.keys.filter { it.startsWith(SyncManager.AUTO_PREFIX) }
        // 3 次上传（同分钟同名覆盖 1 个）+ 原 7 个里清理到最多 5 个自动文件
        assertTrue("自动备份应 ≤5 份，实际 ${autos.size}", autos.size <= 5)
        assertTrue("手动固定文件不被清理", provider.remote.containsKey(SyncWebDAVManualFile))
    }

    @Test
    fun `自动同步频率判断`() = runBlocking {
        settings.setSyncFrequency(SyncFrequency.OFF)
        assertTrue(!manager.shouldAutoSync(null))

        settings.setSyncFrequency(SyncFrequency.EVERY_PULL)
        assertTrue(manager.shouldAutoSync(System.currentTimeMillis())) // 刚同步过也要传（每次拉取后）

        settings.setSyncFrequency(SyncFrequency.EVERY_1_DAY)
        assertTrue(manager.shouldAutoSync(null)) // 从未同步 → 应传
        assertTrue(!manager.shouldAutoSync(System.currentTimeMillis()))
        assertTrue(manager.shouldAutoSync(System.currentTimeMillis() - 25 * 60 * 60 * 1000L))
    }

    @Test
    fun `远端坏 JSON 报格式错误 不入库`() = runBlocking {
        provider.remote[SyncWebDAVManualFile] = "not-json".toByteArray()
        val downloaded = manager.downloadManual()
        assertNotNull(downloaded.error)
        assertTrue(downloaded.error is SyncResult.Err)
        assertEquals(SyncFailure.FORMAT, (downloaded.error as SyncResult.Err).kind)
        assertEquals(0, db.daySummaryDao().getAll().size) // 8b 硬原则：不直写库
    }

    @Test
    fun `远端虚高备份经统一恢复入口被自洽校验丢弃`() = runBlocking {
        // 备份 JSON：一天 45 分钟屏幕 + 8888 分钟虚高应用行（8a 评审 P1-1 的场景）
        val json = """
            {"schemaVersion":4,"exportedAt":"2026-11-12T00:00:00","appVersion":"0.1.0",
             "day_summary":[{"date":"2026-11-09","screenMin":45,"screenUnlockMin":45,"unlocks":2,"launches":3,"longestSessionMin":30,"pulledAt":1700000000000}],
             "app_usage":[{"date":"2026-11-09","pkg":"com.inflated","name":"虚高","minutes":8888,"launches":1,"category":"OTHER"}],
             "hour_usage":[],"pull_log":[],"unlock_event":[],"notification_event":[],"screen_session":[],"app_limit":[]}
        """.trimIndent().toByteArray()
        provider.remote[SyncWebDAVManualFile] = json

        val downloaded = manager.downloadManual()
        assertNotNull(downloaded.file)
        // 恢复走 BackupRepository.restore 统一入口（IngestSanity 内置）
        val result = manager.restore(
            downloaded.file!!,
            BackupRepository.Strategy.MERGE,
            importSettings = false
        )
        assertTrue("虚高行应被丢弃", db.appUsageDao().getAll().none { it.pkg == "com.inflated" })
        assertTrue(result.skipped >= 1)
    }

    @Test
    fun `失败分类文案映射覆盖五类`() {
        assertEquals("鉴权失败（401/403）：请核对账号与授权密码", SyncFailure.AUTH.userMessage())
        assertTrue(SyncFailure.NETWORK.userMessage().contains("网络"))
        assertTrue(SyncFailure.NOT_FOUND.userMessage().contains("404"))
        assertTrue(SyncFailure.FORMAT.userMessage().contains("备份"))
        assertTrue(SyncFailure.QUOTA.userMessage().contains("空间"))
    }
}

/** §5.10 手动备份固定文件名（与 WebDavProvider.MANUAL_FILE 一致）。 */
private const val SyncWebDAVManualFile = "screen-time-backup.json"
