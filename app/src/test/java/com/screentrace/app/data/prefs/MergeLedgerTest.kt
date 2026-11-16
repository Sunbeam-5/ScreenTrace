package com.screentrace.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §7-46（CR-21 补记）：防重复记账必须**整集合保留**。
 *
 * 真机实测踩过的坑：记账编码成多条「来源设备 ID|日期」条目后，早期解码用
 * `.toMap()` 收尾——同键条目互相覆盖，只剩**最后一个**日期。于是第二次合并时
 * 其余已合并日期被当成「新日期」再并一遍，当天数据翻倍（11-10 由 1530 分钟
 * 变 2295 分钟）。这里把「同键取并集」钉成契约。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MergeLedgerTest {

    private lateinit var prefsFile: File
    private lateinit var settings: SettingsStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefsFile = File(context.cacheDir, "ledger-${System.nanoTime()}.preferences_pb")
        val prefs: DataStore<Preferences> = PreferenceDataStoreFactory.create { prefsFile }
        settings = SettingsStore(prefs, "本机机型")
    }

    @After
    fun tearDown() {
        prefsFile.delete()
    }

    @Test
    fun `同一来源的多个日期全部保留`() = runBlocking {
        settings.recordMergedDates("dev-a", setOf("2026-11-10", "2026-11-11", "2026-11-12"))
        assertEquals(
            "三个已合并日期一个都不能丢",
            setOf("2026-11-10", "2026-11-11", "2026-11-12"),
            settings.mergedDatesOf("dev-a")
        )
    }

    @Test
    fun `再次记账不会丢旧日期`() = runBlocking {
        settings.recordMergedDates("dev-a", setOf("2026-11-10", "2026-11-11"))
        settings.recordMergedDates("dev-a", setOf("2026-11-12"))
        assertEquals(
            setOf("2026-11-10", "2026-11-11", "2026-11-12"),
            settings.mergedDatesOf("dev-a")
        )
    }

    @Test
    fun `不同来源设备互不影响`() = runBlocking {
        settings.recordMergedDates("dev-a", setOf("2026-11-10", "2026-11-11"))
        settings.recordMergedDates("dev-b", setOf("2026-11-20"))
        assertEquals(setOf("2026-11-10", "2026-11-11"), settings.mergedDatesOf("dev-a"))
        assertEquals(setOf("2026-11-20"), settings.mergedDatesOf("dev-b"))
    }

    @Test
    fun `账本能读出完整的日期集合`() = runBlocking {
        settings.recordMergedDates("dev-a", setOf("2026-11-10", "2026-11-11", "2026-11-12"))
        val ledger = settings.mergeLedger.first()
        assertEquals(setOf("2026-11-10", "2026-11-11", "2026-11-12"), ledger["dev-a"])
    }

    @Test
    fun `没有记账时是空集合`() = runBlocking {
        assertEquals(emptySet<String>(), settings.mergedDatesOf("dev-a"))
    }
}
