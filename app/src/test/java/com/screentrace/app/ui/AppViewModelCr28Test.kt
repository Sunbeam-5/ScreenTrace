package com.screentrace.app.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.data.RoomUsageRepository
import com.screentrace.app.data.backup.BackupRepository
import com.screentrace.app.data.db.DaySummaryEntity
import com.screentrace.app.data.db.ScreenSessionEntity
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.db.SourceIds
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CR-28 UI 批次接线回归：
 * - 统计页「占用时钟时长」（UNION）副指标取数——双机同时用 1h 时
 *   `clockOccupiedMin = 1h` 而总使用时长 = 2h（两者不得混为一谈，§7-57）；
 * - 导出 JSON 的旧版本兼容警告（三层兼容，§7-58）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppViewModelCr28Test {

    private val dispatcher = StandardTestDispatcher()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val day: LocalDate = LocalDate.now(zone)

    private lateinit var db: ScreenTraceDatabase
    private lateinit var repository: RoomUsageRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ScreenTraceDatabase::class.java
        )
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        repository = RoomUsageRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun msOf(hour: Int): Long =
        day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `统计页占用时钟时长是跨来源 UNION 不是总时长 SUM`() = runTest(dispatcher) {
        // 双机同时使用 1 小时（会话完全重叠）：SUM=2h、UNION=1h
        db.daySummaryDao().insert(DaySummaryEntity(day.toString(), 60, 60, 1, 1, 60, 1L, "dev-A"))
        db.daySummaryDao().insert(DaySummaryEntity(day.toString(), 60, 60, 1, 1, 60, 1L, "dev-B"))
        db.screenSessionDao().insert(
            listOf(
                ScreenSessionEntity(msOf(9), msOf(10), ScreenSessionEntity.KIND_SCREEN, "dev-A"),
                ScreenSessionEntity(msOf(9), msOf(10), ScreenSessionEntity.KIND_SCREEN, "dev-B")
            )
        )

        val vm = AppViewModel(repository = repository)
        advanceUntilIdle()

        val stats = vm.statsState.value.data!!
        assertEquals("UNION = 1 小时", 60, stats.clockOccupiedMin)
        assertEquals("总使用时长（SUM）= 2 小时，与 UNION 区分", 120, stats.summary.totalScreenUnlockMin)
    }

    @Test
    fun `导出含多来源分量时给旧版本兼容警告 纯本机无警告`() = runTest(dispatcher) {
        // 本机分量 + legacy 分量（会进 sources[]）→ 必须警告
        db.daySummaryDao().insert(DaySummaryEntity(day.toString(), 60, 60, 1, 1, 60, 1L, "dev-A"))
        db.daySummaryDao().insert(DaySummaryEntity(day.toString(), 240, 200, 5, 5, 90, 1L, SourceIds.LEGACY))
        val vm = AppViewModel(
            repository = repository,
            backups = BackupRepository(db, zone = zone, sourceDeviceId = { "dev-A" })
        )
        advanceUntilIdle()

        val warning = vm.backupCompatWarning("1.0")
        assertNotNull("含 sources 必须警告", warning)
        assertTrue(warning!!.contains("旧版本导入会丢失非本机来源"))
    }

    @Test
    fun `纯本机分量导出无兼容警告`() = runTest(dispatcher) {
        val db2 = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ScreenTraceDatabase::class.java
        ).allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        try {
            db2.daySummaryDao().insert(DaySummaryEntity(day.toString(), 60, 60, 1, 1, 60, 1L, "dev-B"))
            val vm2 = AppViewModel(
                repository = RoomUsageRepository(db2),
                backups = BackupRepository(db2, zone = zone, sourceDeviceId = { "dev-B" })
            )
            advanceUntilIdle()
            assertNull("纯本机导出无需警告", vm2.backupCompatWarning("1.0"))
        } finally {
            db2.close()
        }
    }
}
