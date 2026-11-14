package com.screentrace.app.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import com.screentrace.app.data.backup.BackupRepository
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.prefs.SettingsStore
import com.screentrace.app.data.prefs.SyncFrequency
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 自动同步触发门限（批 8b-2，§7-15 / CR-04 联动）。
 *
 * 钉死三件真机难验的事：① 频率门限——OFF 与按天窗口未到期不排任务，
 * 「每次成功拉取后」每次必排（手动下拉刷新成功随之触发是 CR-04 的明确要求）；
 * ② 「仅 Wi-Fi」开关原样进约束映射；③ 指数退避重试上限 3 次。
 * 改坏任何一条，对应断言必须变红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutoSyncSchedulerTest {

    private lateinit var db: ScreenTraceDatabase
    private lateinit var prefs: DataStore<Preferences>
    private lateinit var settings: SettingsStore
    private lateinit var sync: SyncManager
    private lateinit var prefsFile: File

    /** 记录替身：门限放行时应携带的「仅 Wi-Fi」值。 */
    private val scheduled = mutableListOf<Boolean>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ScreenTraceDatabase::class.java)
            .allowMainThreadQueries().build()
        prefsFile = File(context.cacheDir, "auto-sync-test-${System.nanoTime()}.preferences_pb")
        prefs = PreferenceDataStoreFactory.create { prefsFile }
        settings = SettingsStore(prefs)
        sync = SyncManager(NoopProvider(), BackupRepository(db, settings), settings)
    }

    @After
    fun tearDown() {
        db.close()
        prefsFile.delete()
    }

    private fun trigger() = runBlocking {
        AutoSyncScheduler.onPullSuccess(
            context = ApplicationProvider.getApplicationContext(),
            sync = sync,
            settings = settings,
            schedule = { scheduled += it }
        )
    }

    // ---------- 频率门限 ----------

    @Test
    fun `频率关闭时不排任务`() = runBlocking {
        settings.setSyncFrequency(SyncFrequency.OFF)
        trigger()
        assertTrue("OFF 档一次都不该排", scheduled.isEmpty())
    }

    @Test
    fun `每次拉取后每次都排 刚同步过也排`() = runBlocking {
        // CR-04：手动下拉刷新成功在「每次成功拉取后」频率下同样触发上传
        settings.setSyncFrequency(SyncFrequency.EVERY_PULL)
        settings.recordSync(System.currentTimeMillis(), ok = true, message = "成功")

        trigger()
        assertEquals("EVERY_PULL 每次拉取成功都要触发", 1, scheduled.size)
    }

    @Test
    fun `按天窗口未到期不排 到期或从未同步才排`() = runBlocking {
        settings.setSyncFrequency(SyncFrequency.EVERY_1_DAY)

        // 刚同步过 → 窗口未到，不因手动刷新提前（CR-04）
        settings.recordSync(System.currentTimeMillis(), ok = true, message = "成功")
        trigger()
        assertTrue("窗口未到不得排任务", scheduled.isEmpty())

        // 上次同步 25 小时前 → 窗口到期
        settings.recordSync(System.currentTimeMillis() - 25 * 60 * 60 * 1000L, ok = true, message = "成功")
        trigger()
        assertEquals("窗口到期应排任务", 1, scheduled.size)

        // 从未同步 → 应传
        scheduled.clear()
        val fresh = SettingsStore(PreferenceDataStoreFactory.create {
            File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "fresh-${System.nanoTime()}.preferences_pb")
        })
        AutoSyncScheduler.onPullSuccess(
            context = ApplicationProvider.getApplicationContext(),
            sync = sync,
            settings = fresh,
            schedule = { scheduled += it }
        )
        assertEquals("从未同步按天档应触发", 1, scheduled.size)
    }

    @Test
    fun `手动刷新不推迟按天窗口`() = runBlocking {
        // 「每 3 天」窗口内同步过：刷新多少次都不排——不提前也不推迟
        settings.setSyncFrequency(SyncFrequency.EVERY_3_DAYS)
        settings.recordSync(System.currentTimeMillis() - 24 * 60 * 60 * 1000L, ok = false, message = "网络不可达")
        repeat(3) { trigger() }
        assertTrue("失败记录也占窗口：刷新不得反复触发", scheduled.isEmpty())
    }

    // ---------- 仅 Wi-Fi 约束 ----------

    @Test
    fun `仅 Wi-Fi 开关原样传入约束`() = runBlocking {
        settings.setSyncFrequency(SyncFrequency.EVERY_PULL)

        settings.setSyncWifiOnly(true)
        trigger()
        settings.setSyncWifiOnly(false)
        trigger()

        assertEquals("约束参数必须跟随开关", listOf(true, false), scheduled)
    }

    @Test
    fun `约束映射 仅 Wi-Fi 为不计费网络 否则联网即可`() {
        assertEquals(NetworkType.UNMETERED, AutoSyncScheduler.networkType(wifiOnly = true))
        assertEquals(NetworkType.CONNECTED, AutoSyncScheduler.networkType(wifiOnly = false))
    }

    // ---------- 退避重试上限 ----------

    @Test
    fun `指数退避重试最多 3 次`() {
        assertTrue(AutoSyncScheduler.shouldRetry(0))
        assertTrue(AutoSyncScheduler.shouldRetry(1))
        assertTrue(AutoSyncScheduler.shouldRetry(2))
        assertFalse("第 3 次重试后必须放弃", AutoSyncScheduler.shouldRetry(3))
    }
}

/** 只为构造 SyncManager 的空 provider：门限测试不碰网络。 */
private class NoopProvider : SyncProvider {
    override val id = "noop"
    override suspend fun testConnection(): SyncResult = SyncResult.Ok()
    override suspend fun upload(path: String, bytes: ByteArray): SyncResult = SyncResult.Ok()
    override suspend fun download(path: String): SyncResult = SyncResult.Ok()
    override suspend fun list(): SyncResult = SyncResult.Ok()
    override suspend fun delete(path: String): SyncResult = SyncResult.Ok()
    override suspend fun disconnect() {}
}
