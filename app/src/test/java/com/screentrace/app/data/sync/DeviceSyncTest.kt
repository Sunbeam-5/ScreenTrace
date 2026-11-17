package com.screentrace.app.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.data.backup.BackupCodec
import com.screentrace.app.data.backup.BackupRepository
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.prefs.DeviceIds
import com.screentrace.app.data.prefs.DeviceInfo
import com.screentrace.app.data.prefs.SettingsStore
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §5.10 批 C：分设备存储、旧备份兼容、只读查看、本机身份不随恢复漂移。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceSyncTest {

    private class DeviceFakeProvider : SyncProvider {
        override val id = "fake-device"
        val remote = linkedMapOf<String, ByteArray>()
        var devicesDirMissing = false

        override suspend fun testConnection(): SyncResult = SyncResult.Ok()
        override suspend fun upload(path: String, bytes: ByteArray): SyncResult {
            remote[path] = bytes
            return SyncResult.Ok()
        }
        override suspend fun download(path: String): SyncResult =
            remote[path]?.let { SyncResult.Ok(content = it) }
                ?: SyncResult.Err(SyncFailure.NOT_FOUND, "404")
        override suspend fun list(): SyncResult = SyncResult.Ok(
            files = remote.keys.filter { !it.contains('/') }
                .map { RemoteFile(it, remote.getValue(it).size.toLong()) }
        )
        override suspend fun listIn(path: String): SyncResult {
            if (devicesDirMissing && path == "devices/") {
                return SyncResult.Err(SyncFailure.NOT_FOUND, "远端目录不存在")
            }
            val prefix = path.trimEnd('/') + "/"
            val entries = linkedMapOf<String, RemoteEntry>()
            remote.keys.forEach { key ->
                if (!key.startsWith(prefix)) return@forEach
                val rest = key.removePrefix(prefix)
                if (rest.isEmpty()) return@forEach
                val first = rest.substringBefore('/')
                if (first.isEmpty()) return@forEach
                entries[first] = if (rest.contains('/')) {
                    RemoteEntry(first, 0L, true)
                } else {
                    RemoteEntry(first, remote.getValue(key).size.toLong(), false)
                }
            }
            return SyncResult.Listed(entries.values.toList())
        }
        override suspend fun delete(path: String): SyncResult {
            remote.remove(path)
            return SyncResult.Ok()
        }
        override suspend fun disconnect() {}
    }

    private lateinit var db: ScreenTraceDatabase
    private lateinit var prefs: DataStore<Preferences>
    private lateinit var settings: SettingsStore
    private lateinit var backups: BackupRepository
    private lateinit var provider: DeviceFakeProvider
    private lateinit var manager: SyncManager
    private lateinit var prefsFile: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ScreenTraceDatabase::class.java)
            .allowMainThreadQueries().build()
        prefsFile = File(context.cacheDir, "device-sync-${System.nanoTime()}.preferences_pb")
        prefs = PreferenceDataStoreFactory.create { prefsFile }
        settings = SettingsStore(prefs, "测试机型")
        backups = BackupRepository(db, settings, device = { settings.currentDevice() })
        provider = DeviceFakeProvider()
        manager = SyncManager(provider, backups, settings, device = { settings.currentDevice() })
    }

    @After
    fun tearDown() {
        db.close()
        prefsFile.delete()
    }

    private fun managerFor(id: String) = SyncManager(
        provider, backups, settings, device = { DeviceInfo(id, id, "M") }
    )

    private fun backupJson(label: String): ByteArray = """
        {"schemaVersion":5,"exportedAt":"2026-11-14 10:00","appVersion":"1.0",
         "device":{"id":"$label","nickname":"$label","model":"M"},
         "day_summary":[{"date":"2026-11-14","screenMin":60,"screenUnlockMin":60,
                         "unlocks":1,"launches":1,"longestSessionMin":30,"pulledAt":1700000000000}],
         "app_usage":[],"hour_usage":[],"pull_log":[],"unlock_event":[],
         "notification_event":[],"screen_session":[],"app_limit":[]}
    """.trimIndent().toByteArray()

    @Test
    fun `deviceId 首启生成且幂等 昵称可改可回落`() = runBlocking {
        val first = settings.getOrCreateDeviceId()
        assertEquals(first, settings.getOrCreateDeviceId())
        assertTrue(first.matches(Regex("[0-9a-f]{8}-[0-9a-f-]{27}")))
        assertEquals("测试机型", settings.deviceNickname.first())

        settings.setDeviceNickname("  我的手机  ")
        assertEquals("我的手机", settings.currentDevice().nickname)
        settings.setDeviceNickname("   ")
        assertEquals("测试机型", settings.currentDevice().nickname)
    }

    @Test
    fun `导出备份带本机 device 恢复别的设备后本机 id 不漂移`() = runBlocking {
        val localId = settings.getOrCreateDeviceId()
        val file = backups.buildFile("1.0")
        assertEquals(6, file.schemaVersion)
        assertEquals(localId, file.device?.id)
        assertEquals("测试机型", file.device?.nickname)

        val encoded = BackupCodec.decode(BackupCodec.encode(file))
        assertTrue(encoded is BackupCodec.DecodeResult.Ok)
        assertEquals(localId, (encoded as BackupCodec.DecodeResult.Ok).summary.device?.id)

        // 换机恢复：备份里的 foreign-device 绝不能写回本机身份
        val foreign = BackupCodec.decode(backupJson("foreign-device").decodeToString())
        backups.restore(
            (foreign as BackupCodec.DecodeResult.Ok).file,
            BackupRepository.Strategy.MERGE,
            importSettings = false
        )
        assertEquals(localId, settings.getOrCreateDeviceId())
    }

    @Test
    fun `手动上传按设备分目录 两台设备不再互相覆盖`() = runBlocking {
        managerFor("device-aaaa1111").uploadManual("1.0")
        managerFor("device-bbbb2222").uploadManual("1.0")
        assertTrue(provider.remote.containsKey(SyncPaths.manualPath("device-aaaa1111")))
        assertTrue(provider.remote.containsKey(SyncPaths.manualPath("device-bbbb2222")))
        assertFalse("不应再写根目录固定文件", provider.remote.containsKey(SyncPaths.MANUAL_FILE))
    }

    @Test
    fun `恢复回落读根目录旧固定文件`() = runBlocking {
        provider.remote[SyncPaths.MANUAL_FILE] = backupJson("legacy-device")
        val downloaded = manager.downloadManual()
        assertNotNull(downloaded.file)
        assertEquals("legacy-device", downloaded.file!!.device?.id)
    }

    @Test
    fun `本设备目录优先于根目录旧备份`() = runBlocking {
        val localId = settings.getOrCreateDeviceId()
        provider.remote[SyncPaths.MANUAL_FILE] = backupJson("legacy-device")
        provider.remote[SyncPaths.manualPath(localId)] = backupJson("local-device")
        assertEquals("local-device", manager.downloadManual().file?.device?.id)
    }

    @Test
    fun `listDevices 归未知设备并认出其它设备`() = runBlocking {
        val localId = settings.getOrCreateDeviceId()
        provider.remote[SyncPaths.MANUAL_FILE] = "{}".toByteArray()
        provider.remote[SyncPaths.manualPath("other-device-9999")] = "{}".toByteArray()
        provider.remote[SyncPaths.manualPath(localId)] = "{}".toByteArray()

        val result = manager.listDevices()
        assertTrue(result.error == null)
        val legacy = result.devices.single { it.isLegacy }
        assertEquals(SyncPaths.LEGACY_DEVICE_ID, legacy.deviceId)
        assertEquals(1, legacy.fileCount)
        val other = result.devices.single { it.deviceId == "other-device-9999" }
        assertFalse(other.isLocal)
        assertEquals(1, other.fileCount)
        assertEquals(1, result.devices.single { it.isLocal }.fileCount)
    }

    @Test
    fun `devices 目录不存在时仍给本机占位 不当作失败`() = runBlocking {
        provider.devicesDirMissing = true
        val result = manager.listDevices()
        assertTrue(result.error == null)
        assertEquals(1, result.devices.size)
        assertTrue(result.devices[0].isLocal)
        assertEquals(0, result.devices[0].fileCount)
    }

    @Test
    fun `inspectDevice 只读查看摘要 不写本机库`() = runBlocking {
        provider.remote[SyncPaths.manualPath("other-device-9999")] = backupJson("other-phone")
        val inspected = manager.inspectDevice("other-device-9999")
        assertNotNull(inspected.summary)
        assertEquals("other-phone", inspected.summary!!.device?.nickname)
        assertEquals("other-phone", inspected.deviceLabel)
        assertTrue("只读查看不得写库", db.daySummaryDao().getAll().isEmpty())
    }

    @Test
    fun `inspectDevice 手动备份缺失时退到最新自动备份`() = runBlocking {
        val dir = SyncPaths.deviceDir("other-device-9999")
        provider.remote[dir + "screen-time-backup-auto-abcd-20261101-0900.json"] =
            backupJson("auto-old")
        provider.remote[dir + "screen-time-backup-auto-abcd-20261114-0900.json"] =
            backupJson("auto-new")
        assertEquals("auto-new", manager.inspectDevice("other-device-9999").file?.device?.id)
    }

    @Test
    fun `自动清理只作用于本设备目录 不动其它设备与根目录旧备份`() = runBlocking {
        val localId = settings.getOrCreateDeviceId()
        val dir = SyncPaths.deviceDir(localId)
        val short = DeviceIds.short(localId)
        val otherDir = SyncPaths.deviceDir("other-device-9999")
        repeat(7) { i ->
            provider.remote[dir + "screen-time-backup-auto-$short-2026110${i + 1}-0900.json"] =
                "{}".toByteArray()
            provider.remote[otherDir + "screen-time-backup-auto-9999-2026110${i + 1}-0900.json"] =
                "{}".toByteArray()
        }
        provider.remote[SyncPaths.MANUAL_FILE] = "{}".toByteArray()

        manager.uploadAuto("1.0")

        fun autos(prefix: String) = provider.remote.keys.count {
            it.startsWith(prefix) && SyncPaths.isAuto(it.substringAfterLast('/'))
        }
        assertTrue("本设备自动备份应 ≤5，实际 ${autos(dir)}", autos(dir) <= 5)
        assertEquals(7, autos(otherDir))
        assertTrue("根目录旧备份不被清理", provider.remote.containsKey(SyncPaths.MANUAL_FILE))
    }
}
