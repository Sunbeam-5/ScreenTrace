package com.screentrace.app.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.contract.EmptyUsageRepository
import com.screentrace.app.data.IngestPipeline
import com.screentrace.app.data.db.PullLogEntity
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.parse.DayMetrics
import com.screentrace.app.data.parse.ParseResult
import com.screentrace.app.data.pull.LoadedUsage
import com.screentrace.app.data.pull.ProviderStatus
import com.screentrace.app.data.pull.PullChannel
import com.screentrace.app.data.pull.PullCoordinator
import com.screentrace.app.data.pull.UsageProvider
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 阶段 E 审阅 P2：`ON_RESUME` 授权恢复不得被 60 秒失败冷却吞掉。
 * 含行为变异锚点 M-TD3：若 `onResumeForeground` 先调 `pullOnOpen()` 或去掉 `!autoPulled` 判断，
 * 失败后 60 秒内授权返回将因 `Throttled` 不拉取，`loadCount` 不会增加，本测试必变红。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ForegroundRecoveryOrderTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: ScreenTraceDatabase

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ScreenTraceDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `M_TD3_失败后60秒内授权恢复回到前台_onResumeForeground必须绕过冷却立即补拉`() = runTest(dispatcher) {
        var nowMs = 1_700_000_000_000L
        var currentStatus = ProviderStatus.PERMISSION_DENIED
        var loadCalls = 0

        val fakeProvider = object : UsageProvider {
            override val channel: PullChannel = PullChannel.SHIZUKU
            override suspend fun status(): ProviderStatus = currentStatus
            override suspend fun load(): LoadedUsage {
                loadCalls++
                return LoadedUsage.Parsed(
                    result = ParseResult(
                        days = mapOf(
                            LocalDate.of(2026, 11, 25) to DayMetrics(60, 50, 5, 10, 20)
                        ),
                        hourMinutes = emptyMap(),
                        appUsage = emptyList(),
                        unlockEvents = emptyList(),
                        notificationEvents = emptyList()
                    ),
                    capturedAtMs = nowMs
                )
            }
        }

        val coordinator = PullCoordinator(
            db = db,
            pipeline = IngestPipeline(db),
            providers = listOf(fakeProvider),
            clock = { nowMs }
        )

        // 1. 首次未授权失败，写入一条 10 秒前的失败 pull_log（处于 60s FAILURE_RETRY_MS 冷却期内）
        db.pullLogDao().insert(
            PullLogEntity(pulledAt = nowMs - 10_000L, source = "shizuku", ok = false, note = "未授权")
        )

        val vm = AppViewModel(repository = EmptyUsageRepository(), pulls = coordinator)
        vm.refreshChannelStatuses()
        advanceUntilIdle()
        assertEquals(0, loadCalls)

        // 2. 用户在 10 秒后去 Shizuku 授权并切回前台（仍在 60 秒失败冷却期内）
        nowMs += 10_000L
        currentStatus = ProviderStatus.AVAILABLE
        vm.onResumeForeground()
        advanceUntilIdle()

        assertEquals("授权恢复切回前台必须绕过 60s 失败冷却触发一次补拉", 1, loadCalls)
    }
}
