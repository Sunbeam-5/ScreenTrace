package com.screentrace.app.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.data.RoomUsageRepository
import com.screentrace.app.data.backup.BackupRepository
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.prefs.DeviceInfo
import com.screentrace.app.data.prefs.SettingsStore
import java.io.File
import java.time.ZoneId
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

/**
 * §7-43 / §7-44 / §7-45（CR-21）：机型显示、跨设备累加合并、切换设备真切换。
 *
 * 真 SQLite + 假 WebDAV（Robolectric）：三件事都要落到「真的写进库 / 真的换库」，
 * 纯函数测试证明不了「切换之后页面读到的是另一台设备的数据」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CR21DeviceTest {

    private class FakeProvider : SyncProvider {
        override val id = "fake"
        val remote = linkedMapOf<String, ByteArray>()
        val downloads = mutableListOf<String>()

        override suspend fun testConnection(): SyncResult = SyncResult.Ok()

        override suspend fun upload(path: String, bytes: ByteArray): SyncResult {
            remote[path] = bytes
            return SyncResult.Ok()
        }

        override suspend fun download(path: String): SyncResult {
            downloads += path
            return remote[path]?.let { SyncResult.Ok(content = it) }
                ?: SyncResult.Err(SyncFailure.NOT_FOUND, "404")
        }

        override suspend fun list(): SyncResult = SyncResult.Ok(
            files = remote.keys.filter { !it.contains('/') }
                .map { RemoteFile(it, remote.getValue(it).size.toLong()) }
        )

        override suspend fun listIn(path: String): SyncResult {
            val prefix = path.trimEnd('/') + "/"
            val entries = linkedMapOf<String, RemoteEntry>()
            remote.keys.forEach { key ->
                if (!key.startsWith(prefix)) return@forEach
                val rest = key.removePrefix(prefix)
                if (rest.isEmpty()) return@forEach
                val first = rest.substringBefore('/')
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

    private val zone: ZoneId = ZoneId.of("UTC")
    private val remoteId = "other-device-9999"

    private lateinit var localDb: ScreenTraceDatabase
    private lateinit var copyDb: ScreenTraceDatabase
    private lateinit var prefsFile: File
    private lateinit var settings: SettingsStore
    private lateinit var provider: FakeProvider
    private lateinit var manager: SyncManager
    private lateinit var operations: DeviceOperations

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        localDb = Room.inMemoryDatabaseBuilder(context, ScreenTraceDatabase::class.java)
            .allowMainThreadQueries().build()
        copyDb = Room.inMemoryDatabaseBuilder(context, ScreenTraceDatabase::class.java)
            .allowMainThreadQueries().build()
        prefsFile = File(context.cacheDir, "cr21-${System.nanoTime()}.preferences_pb")
        val prefs: DataStore<Preferences> = PreferenceDataStoreFactory.create { prefsFile }
        settings = SettingsStore(prefs, "本机机型")
        provider = FakeProvider()
        val localBackups = BackupRepository(localDb, settings, zone = zone, device = { settings.currentDevice() })
        manager = SyncManager(provider, localBackups, settings, device = { settings.currentDevice() })
        operations = DeviceOperations(
            sync = manager,
            localBackups = localBackups,
            deviceBackups = { BackupRepository(copyDb, settings, zone = zone, device = { settings.currentDevice() }) },
            copyHasData = { copyDb.daySummaryDao().count() > 0 },
            settings = settings,
            onSwitch = { settings.setCurrentDeviceId(it) },
            uploadAfterMerge = { manager.uploadManual("1.0") is SyncResult.Ok },
            zone = zone
        )
    }

    @After
    fun tearDown() {
        localDb.close()
        copyDb.close()
        prefsFile.delete()
    }

    /** 远端设备的一份备份（机型 Redmi Note 12）。 */
    private fun remoteBackupJson(
        screenMin: Int = 60,
        unlockMin: Int = 50,
        date: String = "2026-11-01",
        model: String = "Redmi Note 12"
    ): ByteArray = """
        {"schemaVersion":5,"exportedAt":"2026-11-16 10:00","appVersion":"1.0",
         "device":{"id":"$remoteId","nickname":"旧手机","model":"$model"},
         "day_summary":[{"date":"$date","screenMin":$screenMin,"screenUnlockMin":$unlockMin,
                         "unlocks":5,"launches":8,"longestSessionMin":25,"pulledAt":1700000000000}],
         "app_usage":[{"date":"$date","pkg":"com.a","name":"应用A","minutes":30,"launches":2,"category":"SOCIAL"}],
         "hour_usage":[{"date":"$date","hour":9,"minutes":15}],
         "pull_log":[],"unlock_event":[],"notification_event":[],"screen_session":[],
         "app_limit":[{"pkg":"com.a","limitMin":60,"enabled":true}]}
    """.trimIndent().toByteArray()

    /** 远端设备的多日期备份（累加口径的回归场景：记账必须整集合保留）。 */
    private fun remoteBackupJsonMulti(dates: List<String>, screenMin: Int = 60): ByteArray {
        val days = dates.joinToString(",") { date ->
            """{"date":"$date","screenMin":$screenMin,"screenUnlockMin":$screenMin,
                "unlocks":5,"launches":8,"longestSessionMin":25,"pulledAt":1700000000000}"""
        }
        val apps = dates.joinToString(",") { date ->
            """{"date":"$date","pkg":"com.a","name":"应用A","minutes":30,"launches":2,"category":"SOCIAL"}"""
        }
        return """
            {"schemaVersion":5,"exportedAt":"2026-11-16 10:00","appVersion":"1.0",
             "device":{"id":"$remoteId","nickname":"旧手机","model":"Redmi Note 12"},
             "day_summary":[$days],
             "app_usage":[$apps],
             "hour_usage":[],"pull_log":[],"unlock_event":[],"notification_event":[],
             "screen_session":[],"app_limit":[]}
        """.trimIndent().toByteArray()
    }

    /**
     * 远端备份 + 一条通知事件（CR-22 ② 信号③：事件主键子集 = 同机更早快照）。
     *
     * @param eventMs 事件时间戳（落在 2026-11-01）
     */
    private fun remoteBackupJsonWithEvent(
        eventMs: Long,
        date: String = "2026-11-01"
    ): ByteArray = """
        {"schemaVersion":5,"exportedAt":"2026-11-16 10:00","appVersion":"1.0",
         "device":{"id":"$remoteId","nickname":"旧手机","model":"Redmi Note 12"},
         "day_summary":[{"date":"$date","screenMin":60,"screenUnlockMin":50,
                         "unlocks":5,"launches":8,"longestSessionMin":25,"pulledAt":1700000000000}],
         "app_usage":[],"hour_usage":[],"pull_log":[],"unlock_event":[],
         "notification_event":[{"occurredAtMs":$eventMs,"type":"NOTIFICATION_SEEN",
                                "pkg":"com.a","channelId":"ch1"}],
         "screen_session":[],"app_limit":[]}
    """.trimIndent().toByteArray()

    // ---------------------------------------------------------- 机型显示（§7-43）

    @Test
    fun `上传时顺带写一份机型元数据`() = runBlocking {
        manager.uploadManual("1.0")
        val localId = settings.getOrCreateDeviceId()
        val meta = provider.remote[SyncPaths.deviceMetaPath(localId)]
        assertNotNull("上传必须写 device.json，否则设备列表拿不到机型", meta)
        val decoded = DeviceMetaCodec.decode(meta!!.decodeToString())
        assertNotNull(decoded)
        assertEquals("本机机型", decoded!!.model)
    }

    @Test
    fun `设备列表显示远端机型而不是短 ID`() = runBlocking {
        provider.remote[SyncPaths.manualPath(remoteId)] = remoteBackupJson()
        provider.remote[SyncPaths.deviceMetaPath(remoteId)] =
            DeviceMetaCodec.encode(DeviceMeta(remoteId, "旧手机", "Redmi Note 12")).toByteArray()

        val result = manager.listDevices()
        val row = result.devices.single { it.deviceId == remoteId }
        assertEquals("Redmi Note 12", row.label)
        assertEquals("Redmi Note 12", row.model)
    }

    @Test
    fun `老设备没有机型元数据时回落短 ID 摘要再补出机型`() = runBlocking {
        provider.remote[SyncPaths.manualPath(remoteId)] = remoteBackupJson()

        val row = manager.listDevices().devices.single { it.deviceId == remoteId }
        assertEquals("设备 ${com.screentrace.app.data.prefs.DeviceIds.short(remoteId)}", row.label)
        assertEquals(null, row.model)

        val inspected = manager.inspectDevice(remoteId)
        assertEquals("Redmi Note 12", inspected.deviceModel)
    }

    @Test
    fun `取机型不下载整份备份`() = runBlocking {
        provider.remote[SyncPaths.manualPath(remoteId)] = remoteBackupJson()
        provider.remote[SyncPaths.deviceMetaPath(remoteId)] =
            DeviceMetaCodec.encode(DeviceMeta(remoteId, "旧手机", "Redmi Note 12")).toByteArray()

        provider.downloads.clear()
        manager.listDevices()
        assertFalse(
            "列表阶段不得下载备份正文",
            provider.downloads.any { it.endsWith(SyncPaths.MANUAL_FILE) }
        )
    }

    // ------------------------------------------------------- 切换设备（§7-45）

    @Test
    fun `切换设备把远端数据拉到本地独立库 整个 App 随之换数据`() = runBlocking {
        provider.remote[SyncPaths.manualPath(remoteId)] = remoteBackupJson()
        localDb.daySummaryDao().insert(
            com.screentrace.app.data.db.DaySummaryEntity(
                "2026-11-01", 400, 360, 40, 60, 90, 1_700_000_000_000
            )
        )

        val result = operations.switchToDevice(remoteId)
        assertTrue(result is DeviceOperations.SwitchResult.Ok)

        // 远端数据进了它自己的库
        val copied = copyDb.daySummaryDao().get("2026-11-01")
        assertNotNull("远端数据必须落到该设备自己的副本库", copied)
        assertEquals(60, copied!!.screenMin)

        // 本机采集库**没有被远端数据覆盖**（设备之间彼此独立）
        assertEquals(400, localDb.daySummaryDao().get("2026-11-01")!!.screenMin)

        // 「整个 App 的数据变成那台设备的」：读层按当前库解析
        var current = copyDb
        val repository = RoomUsageRepository({ current })
        val remoteSummary = repository.summary(DateSelection.Day(java.time.LocalDate.of(2026, 11, 1)))
        assertEquals(50, remoteSummary.totalScreenUnlockMin)

        current = localDb
        val localSummary = repository.summary(DateSelection.Day(java.time.LocalDate.of(2026, 11, 1)))
        assertEquals(360, localSummary.totalScreenUnlockMin)
    }

    @Test
    fun `本地已有副本时再切换不重复下载`() = runBlocking {
        provider.remote[SyncPaths.manualPath(remoteId)] = remoteBackupJson()
        operations.switchToDevice(remoteId)
        val downloadsAfterFirst = provider.downloads.size

        operations.switchToDevice(remoteId)
        assertEquals("本地已有副本就不该再下载一次", downloadsAfterFirst, provider.downloads.size)
    }

    @Test
    fun `切回本机后指针回到本机`() = runBlocking {
        provider.remote[SyncPaths.manualPath(remoteId)] = remoteBackupJson()
        operations.switchToDevice(remoteId)
        assertEquals(remoteId, settings.currentDeviceId.first())

        val localId = settings.getOrCreateDeviceId()
        operations.switchToDevice(localId)
        assertEquals(localId, settings.currentDeviceId.first())
    }

    // ------------------------------------------------- 跨设备合并（§7-44）

    @Test
    fun `合并到本机是累加 旧数据单独保留 新数据里也有旧数据`() = runBlocking {
        provider.remote[SyncPaths.manualPath(remoteId)] = remoteBackupJson(screenMin = 60, unlockMin = 50)
        localDb.daySummaryDao().insert(
            com.screentrace.app.data.db.DaySummaryEntity(
                "2026-11-01", 400, 360, 40, 60, 90, 1_700_000_000_000
            )
        )
        // 本机同一天同包也有数据：应用时长 / 时段分钟都必须**相加**，不能被覆盖
        localDb.appUsageDao().insert(
            com.screentrace.app.data.db.AppUsageEntity(
                "2026-11-01", "com.a", "本机名", 120, 5, "SOCIAL"
            )
        )
        localDb.hourUsageDao().insert(com.screentrace.app.data.db.HourUsageEntity("2026-11-01", 9, 30))

        val result = operations.mergeIntoLocal(remoteId)
        assertTrue(result is DeviceOperations.MergeResult.Ok)

        val day = localDb.daySummaryDao().get("2026-11-01")!!
        assertEquals("两台设备当天使用量必须相加", 460, day.screenMin)
        assertEquals(410, day.screenUnlockMin)

        val app = localDb.appUsageDao().get("2026-11-01", "com.a")!!
        assertEquals("应用时长必须相加", 150, app.minutes)
        assertEquals("启动数必须相加", 7, app.launches)
        assertEquals("名字以本机为准", "本机名", app.name)

        val hour = localDb.hourUsageDao().getForDate("2026-11-01").single { it.hour == 9 }
        assertEquals("时段分钟必须相加", 45, hour.minutes)

        // 旧设备自己的副本库与云端备份**不被改动**
        assertEquals("远端副本库不该被写", 0, copyDb.daySummaryDao().count())
        val remoteJson = provider.remote[SyncPaths.manualPath(remoteId)]!!.decodeToString()
        assertTrue(remoteJson.contains("\"screenMin\":60"))
    }

    @Test
    fun `同一天重复合并不会翻倍 只并入新日期`() = runBlocking {
        provider.remote[SyncPaths.manualPath(remoteId)] = remoteBackupJson(screenMin = 60, unlockMin = 50)
        localDb.daySummaryDao().insert(
            com.screentrace.app.data.db.DaySummaryEntity(
                "2026-11-01", 400, 360, 40, 60, 90, 1_700_000_000_000
            )
        )

        operations.mergeIntoLocal(remoteId)
        assertEquals(460, localDb.daySummaryDao().get("2026-11-01")!!.screenMin)

        val second = operations.mergeIntoLocal(remoteId)
        assertTrue("重复合并应被记账挡住", second is DeviceOperations.MergeResult.Skipped)
        assertEquals("同一天不能翻倍", 460, localDb.daySummaryDao().get("2026-11-01")!!.screenMin)
    }

    @Test
    fun `多日期备份重复合并不会翻倍 只并入新日期`() = runBlocking {
        // 真机踩到的坑：来源备份有**多天**时，记账若只记住最后一个日期，
        // 第二次合并会把其余已合并日期再并一遍（11-10 由 1530 分钟变 2295 分钟）。
        val dates = listOf("2026-11-10", "2026-11-11", "2026-11-12")
        provider.remote[SyncPaths.manualPath(remoteId)] = remoteBackupJsonMulti(dates, screenMin = 60)
        dates.forEach { date ->
            localDb.daySummaryDao().insert(
                com.screentrace.app.data.db.DaySummaryEntity(
                    date, 400, 360, 40, 60, 90, 1_700_000_000_000
                )
            )
        }

        val first = operations.mergeIntoLocal(remoteId)
        assertTrue(first is DeviceOperations.MergeResult.Ok)
        assertEquals("三天都要并入", 3, (first as DeviceOperations.MergeResult.Ok).mergedDates)
        dates.forEach { date ->
            assertEquals("并入后当天使用量相加", 460, localDb.daySummaryDao().get(date)!!.screenMin)
        }
        assertEquals(
            "记账必须记全三天",
            setOf("2026-11-10", "2026-11-11", "2026-11-12"),
            settings.mergedDatesOf(remoteId)
        )

        val second = operations.mergeIntoLocal(remoteId)
        assertTrue("重复合并应被记账挡住", second is DeviceOperations.MergeResult.Skipped)
        dates.forEach { date ->
            assertEquals("已合并的日期不能再翻倍", 460, localDb.daySummaryDao().get(date)!!.screenMin)
        }
    }

    @Test
    fun `来源是本机自己的旧备份时 重叠日期以本机为准 不累加`() = runBlocking {
        // 备份与本机同一天的数据**逐项完全一致**（同一台机器的两份快照）
        provider.remote[SyncPaths.manualPath(remoteId)] = remoteBackupJson(screenMin = 60, unlockMin = 50)
        localDb.daySummaryDao().insert(
            com.screentrace.app.data.db.DaySummaryEntity(
                "2026-11-01", 60, 50, 5, 8, 25, 1_700_000_000_000
            )
        )

        val result = operations.mergeIntoLocal(remoteId)
        assertTrue("应识别为同一台机器并跳过", result is DeviceOperations.MergeResult.Skipped)
        assertTrue(
            "提示要写明判定结果",
            (result as DeviceOperations.MergeResult.Skipped).message.contains("同一台机器")
        )
        assertEquals("同一天不能翻倍", 60, localDb.daySummaryDao().get("2026-11-01")!!.screenMin)
    }

    @Test
    fun `部分日期与本机一致时 只并入不一致的那天`() = runBlocking {
        provider.remote[SyncPaths.manualPath(remoteId)] =
            remoteBackupJsonMulti(listOf("2026-11-01", "2026-11-02"), screenMin = 60)
        localDb.daySummaryDao().insert(
            com.screentrace.app.data.db.DaySummaryEntity(
                "2026-11-01", 60, 60, 5, 8, 25, 1_700_000_000_000
            )
        )
        localDb.daySummaryDao().insert(
            com.screentrace.app.data.db.DaySummaryEntity(
                "2026-11-02", 400, 360, 40, 60, 90, 1_700_000_000_000
            )
        )

        val result = operations.mergeIntoLocal(remoteId)
        assertTrue(result is DeviceOperations.MergeResult.Ok)
        val ok = result as DeviceOperations.MergeResult.Ok
        assertEquals("只并入不一致的那天", 1, ok.mergedDates)
        assertTrue(
            "提示要写明跳过了几天",
            ok.message.contains("同一台机器") && ok.message.contains("1 天")
        )
        assertEquals(
            "一致的那天以本机为准、不累加",
            60,
            localDb.daySummaryDao().get("2026-11-01")!!.screenMin
        )
        assertEquals(
            "不一致的那天照常累加",
            460,
            localDb.daySummaryDao().get("2026-11-02")!!.screenMin
        )
    }

    @Test
    fun `备份事件是本机事件子集时 判为同一台机器 不累加`() = runBlocking {
        // 2026-11-01T00:00:00Z（测试固定用 UTC，事件归属日不受宿主时区影响）
        val eventMs = 1_793_491_200_000L
        provider.remote[SyncPaths.manualPath(remoteId)] = remoteBackupJsonWithEvent(eventMs)
        // 本机同一天有**更多**事件（备份是本机更早的快照）
        localDb.daySummaryDao().insert(
            com.screentrace.app.data.db.DaySummaryEntity(
                "2026-11-01", 400, 360, 40, 60, 90, 1_700_000_000_000
            )
        )
        localDb.notificationEventDao().insertIgnore(
            com.screentrace.app.data.db.NotificationEventEntity(
                eventMs, "NOTIFICATION_SEEN", "com.a", "ch1"
            )
        )
        localDb.notificationEventDao().insertIgnore(
            com.screentrace.app.data.db.NotificationEventEntity(
                eventMs + 60_000, "NOTIFICATION_SEEN", "com.b", "ch2"
            )
        )

        val result = operations.mergeIntoLocal(remoteId)
        assertTrue("事件子集应判为同一台机器", result is DeviceOperations.MergeResult.Skipped)
        assertTrue(
            (result as DeviceOperations.MergeResult.Skipped).message.contains("同一台机器")
        )
        assertEquals(
            "当天不能翻倍",
            400,
            localDb.daySummaryDao().get("2026-11-01")!!.screenMin
        )
    }

    @Test
    fun `合并后自动上传一次 云端本机备份立刻含旧数据`() = runBlocking {
        provider.remote[SyncPaths.manualPath(remoteId)] = remoteBackupJson(screenMin = 60, unlockMin = 50)
        localDb.daySummaryDao().insert(
            com.screentrace.app.data.db.DaySummaryEntity(
                "2026-11-01", 400, 360, 40, 60, 90, 1_700_000_000_000
            )
        )

        val result = operations.mergeIntoLocal(remoteId)
        assertTrue(result is DeviceOperations.MergeResult.Ok)
        assertTrue((result as DeviceOperations.MergeResult.Ok).uploaded)

        val localId = settings.getOrCreateDeviceId()
        val uploaded = provider.remote[SyncPaths.manualPath(localId)]!!.decodeToString()
        assertTrue("云端备份必须已含合并进来的旧数据", uploaded.contains("\"screenMin\": 460"))
    }

    @Test
    fun `合并不覆盖本机的应用限额`() = runBlocking {
        provider.remote[SyncPaths.manualPath(remoteId)] = remoteBackupJson()
        localDb.appLimitDao().upsert(
            com.screentrace.app.data.db.AppLimitEntity("com.a", 30, true)
        )

        operations.mergeIntoLocal(remoteId)
        assertEquals(
            "限额是本机用户意图，跨设备合并不覆盖",
            30,
            localDb.appLimitDao().getAll().single { it.pkg == "com.a" }.limitMin
        )
    }

    @Test
    fun `合并失败时不记账 可以重试`() = runBlocking {
        provider.remote[SyncPaths.manualPath(remoteId)] = remoteBackupJson()
        val broken = DeviceOperations(
            sync = manager,
            localBackups = BackupRepository(localDb, settings, zone = zone),
            deviceBackups = { BackupRepository(copyDb, settings, zone = zone) },
            copyHasData = { false },
            settings = settings,
            onSwitch = { },
            uploadAfterMerge = { true }
        )
        // 先把远端备份换成非法 JSON：解码失败 → 合并失败
        provider.remote[SyncPaths.manualPath(remoteId)] = "{不是 json".toByteArray()
        val result = broken.mergeIntoLocal(remoteId)
        assertTrue(result is DeviceOperations.MergeResult.Failed)
        assertTrue(settings.mergedDatesOf(remoteId).isEmpty())
    }
}
