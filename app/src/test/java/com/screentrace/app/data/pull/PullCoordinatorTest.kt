package com.screentrace.app.data.pull

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.contract.NotificationEvent
import com.screentrace.app.contract.NotificationType
import com.screentrace.app.contract.UnlockEvent
import com.screentrace.app.data.IngestPipeline
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.parse.AppUsageRow
import com.screentrace.app.data.parse.DayMetrics
import com.screentrace.app.data.parse.ParseResult
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
 * 拉取协调器（§5.8 状态机 / §2.1 节流与降级）的真实数据库测试。
 *
 * 覆盖的都是「纯函数测不到、真机又很难复现」的分支：
 * 1. 节流分档：成功 10 分钟（批 6g 收紧）、失败 60 秒（否则「刚授权完回 App」假死）；
 * 2. 通道降级：Shizuku 不可用 → UsageStats 顶上，且 `pull_log.source` 记的是降级通道；
 * 3. 失败保留旧数据：拉取失败后库里原有行必须原样不动（§6 红线）；
 * 4. 空 dump 是**独立状态**，不能混进「失败」；
 * 5. 并发拉取互斥：第二次立即 [PullOutcome.AlreadyRunning]，不许排队堆积。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PullCoordinatorTest {

    private lateinit var db: ScreenTraceDatabase
    private lateinit var pipeline: IngestPipeline

    private val zone: ZoneId = ZoneId.systemDefault()
    private val day: LocalDate = LocalDate.of(2026, 11, 3)
    private val baseMs: Long = day.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

    /** 可拨动的时钟：节流断言不能依赖真实时间。 */
    private var now: Long = baseMs

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ScreenTraceDatabase::class.java
        ).allowMainThreadQueries().build()
        pipeline = IngestPipeline(db)
        now = baseMs
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------- 节流 ----------

    @Test
    fun `成功拉取后 10 分钟内再次打开不重复拉取`() = runBlocking {
        val provider = FakeProvider(PullChannel.SHIZUKU, parsed())
        val coordinator = coordinator(provider)

        assertTrue(coordinator.pullIfStale() is PullOutcome.Success)
        assertEquals(1, provider.loadCount)

        now += 9 * 60_000
        assertEquals(PullOutcome.Throttled, coordinator.pullIfStale())
        assertEquals("节流命中不应该再去读 dump", 1, provider.loadCount)

        now += 2 * 60_000 // 累计 11 分钟
        assertTrue(coordinator.pullIfStale() is PullOutcome.Success)
        assertEquals(2, provider.loadCount)
    }

    @Test
    fun `失败后 60 秒内不重试 超过 60 秒允许重试`() = runBlocking {
        val provider = FakeProvider(PullChannel.SHIZUKU, LoadedUsage.Failed(ProviderStatus.TEMPORARILY_UNAVAILABLE, "binder 挂了"))
        val coordinator = coordinator(provider)

        assertTrue(coordinator.pullIfStale() is PullOutcome.Failure)
        assertEquals(1, provider.loadCount)

        now += 30_000
        assertEquals(PullOutcome.Throttled, coordinator.pullIfStale())
        assertEquals(1, provider.loadCount)

        // 关键：用户刚去 Shizuku 里授权完切回来，不该被 30 分钟节流挡住
        now += 31_000
        provider.loaded = parsed()
        assertTrue(coordinator.pullIfStale() is PullOutcome.Success)
        assertEquals(2, provider.loadCount)
    }

    @Test
    fun `手动拉取不受节流约束`() = runBlocking {
        val provider = FakeProvider(PullChannel.SHIZUKU, parsed())
        val coordinator = coordinator(provider)

        assertTrue(coordinator.pullIfStale() is PullOutcome.Success)
        assertTrue(coordinator.pull() is PullOutcome.Success)
        assertEquals(2, provider.loadCount)
    }

    // ---------- 降级 ----------

    @Test
    fun `主通道未授权时降级到使用情况访问并记录来源`() = runBlocking {
        val shizuku = FakeProvider(PullChannel.SHIZUKU, parsed())
            .apply { status = ProviderStatus.PERMISSION_DENIED }
        val usageStats = FakeProvider(PullChannel.USAGE_STATS, parsed())
        val coordinator = coordinator(shizuku, usageStats)

        val outcome = coordinator.pull()
        assertTrue("应降级成功而非报错", outcome is PullOutcome.Success)
        assertEquals(PullChannel.USAGE_STATS, (outcome as PullOutcome.Success).channel)
        assertEquals("主通道没授权就不该去读 dump", 0, shizuku.loadCount)
        assertEquals("usage-stats", db.pullLogDao().latest()?.source)
        assertTrue(db.pullLogDao().latest()?.ok == true)
    }

    @Test
    fun `两条通道都不可用时失败并写明原因`() = runBlocking {
        val shizuku = FakeProvider(PullChannel.SHIZUKU, parsed())
            .apply { status = ProviderStatus.NOT_INSTALLED }
        val usageStats = FakeProvider(PullChannel.USAGE_STATS, parsed())
            .apply { status = ProviderStatus.USAGE_ACCESS_DENIED }
        val coordinator = coordinator(shizuku, usageStats)

        val outcome = coordinator.pull()
        assertTrue(outcome is PullOutcome.Failure)
        outcome as PullOutcome.Failure
        assertFalse("空库无旧数据", outcome.keptOldData)
        assertEquals(ProviderStatus.NOT_INSTALLED, outcome.status)
        assertTrue("失败原因要能指导用户去装 Shizuku", outcome.message.contains("Shizuku"))
        // 通道层面失败也要留痕，否则设置页「最近拉取」空白
        assertEquals(false, db.pullLogDao().latest()?.ok)
    }

    // ---------- 失败保留旧数据 ----------

    @Test
    fun `拉取失败时已有历史原样保留`() = runBlocking {
        val provider = FakeProvider(PullChannel.SHIZUKU, parsed())
        val coordinator = coordinator(provider)
        coordinator.pull()
        val before = snapshot()

        now += 60 * 60_000
        provider.loaded = LoadedUsage.Failed(ProviderStatus.TEMPORARILY_UNAVAILABLE, "读取超时")
        val outcome = coordinator.pull()

        assertTrue(outcome is PullOutcome.Failure)
        assertTrue("必须标记已保留旧数据，界面才会内联报错而不是整页空态", (outcome as PullOutcome.Failure).keptOldData)
        assertEquals("六表内容一行都不许动", before, snapshot())
    }

    // ---------- 空 dump ----------

    @Test
    fun `空 dump 是独立状态且记失败日志`() = runBlocking {
        val provider = FakeProvider(PullChannel.SHIZUKU, LoadedUsage.Parsed(emptyResult(), baseMs))
        val coordinator = coordinator(provider)

        assertEquals(PullOutcome.EmptyDump, coordinator.pull())
        val log = db.pullLogDao().latest()
        assertNotNull(log)
        assertEquals("空 dump 不能记成功（6a 评审 P2-2）", false, log!!.ok)
        assertEquals("dump 为空", log.note)
    }

    // ---------- 并发互斥 ----------

    @Test
    fun `拉取进行中再次触发立即返回 AlreadyRunning`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val slow = FakeProvider(PullChannel.SHIZUKU, parsed())
        slow.onLoad = {
            started.complete(Unit)
            gate.await()
        }
        val coordinator = coordinator(slow)

        val first = async { coordinator.pull() }
        started.await()
        assertEquals(PullOutcome.AlreadyRunning, coordinator.pull())

        gate.complete(Unit)
        assertTrue(first.await() is PullOutcome.Success)
        assertEquals("只有第一次真正读了 dump", 1, slow.loadCount)
    }

    // ---------- 覆盖范围（部分历史 / 完全无数据） ----------

    @Test
    fun `覆盖范围由库内数据推导且首次拉取必为部分历史`() = runBlocking {
        val coordinator = coordinator(FakeProvider(PullChannel.SHIZUKU, parsed()))
        assertFalse(coordinator.historyCoverage().hasAny)

        coordinator.pull()
        val coverage = coordinator.historyCoverage()
        assertTrue(coverage.hasAny)
        assertEquals(day, coverage.firstDate)
        // 系统滚动窗口只给约 10 天，首次拉取一定不足 9 天 → 界面要提示「部分历史」
        assertTrue(coordinator.isPartialHistory(coverage))
    }

    // ---------- 设置页文案映射 ----------

    @Test
    fun `设置页文案映射如实反映各结果`() {
        // 节流不是失败：数据是刚拉过的
        assertTrue(PullOutcome.Throttled.asPullResult().ok)
        // 空 dump 要算未成功：用户点了「立即拉取」，期待的是新数据
        assertFalse(PullOutcome.EmptyDump.asPullResult().ok)
        assertFalse(PullOutcome.AlreadyRunning.asPullResult().ok)

        val success = PullOutcome.Success(PullChannel.SHIZUKU, days = 10, unlockEvents = 42, notificationEvents = 7, pulledAtMs = baseMs).asPullResult()
        assertTrue(success.ok)
        assertTrue(success.message.contains("10 天"))
        assertTrue(success.message.contains("shizuku"))

        val failure = PullOutcome.Failure(ProviderStatus.PERMISSION_DENIED, "Shizuku 未授权", true).asPullResult()
        assertFalse(failure.ok)
        assertEquals("失败原因必须原样透出，不能被包装成模糊文案", "Shizuku 未授权", failure.message)
    }

    // ---------- 自动同步钩子（批 8b-2 §5.10 链式触发） ----------

    @Test
    fun `成功拉取后触发同步钩子 失败与空 dump 不触发`() = runBlocking {
        val fired = mutableListOf<Int>()
        val provider = FakeProvider(PullChannel.SHIZUKU, parsed())
        val c = coordinator(provider) { fired += it.days }

        val ok = c.pull()
        assertTrue(ok is PullOutcome.Success)
        assertEquals("一次成功拉取触发一次钩子", listOf(1), fired)

        // 空 dump / 失败都不算「成功拉取」，不触发（§7-15「在成功拉取后」）
        provider.loaded = LoadedUsage.Parsed(emptyResult(), baseMs)
        c.pull()
        provider.loaded = LoadedUsage.Failed(ProviderStatus.TEMPORARILY_UNAVAILABLE, "读取超时")
        c.pull()
        assertEquals("空 dump 与失败不得触发自动同步", listOf(1), fired)
    }

    @Test
    fun `同步钩子抛异常不影响拉取成功`() = runBlocking {
        val provider = FakeProvider(PullChannel.SHIZUKU, parsed())
        val c = coordinator(provider) { error("云端炸了") }
        val outcome = c.pull()
        assertTrue("钩子失败不得把拉取成功降级成失败（§5.10）", outcome is PullOutcome.Success)
    }

    private fun coordinator(
        vararg providers: FakeProvider,
        onSuccess: suspend (PullOutcome.Success) -> Unit = {}
    ) = PullCoordinator(
        db = db,
        pipeline = pipeline,
        providers = providers.toList(),
        clock = { now },
        zone = zone,
        onSuccess = onSuccess
    )

    private suspend fun snapshot(): List<String> = listOf(
        db.daySummaryDao().getAll().map { "${it.date}|${it.screenUnlockMin}|${it.unlocks}" },
        db.appUsageDao().sumBetween("0001-01-01", "9999-12-31").map { "${it.pkg}|${it.minutes}" },
        db.hourUsageDao().sumBetween("0001-01-01", "9999-12-31").map { "${it.hour}|${it.minutes}" },
        db.unlockEventDao().msBetween(0, Long.MAX_VALUE).map { it.toString() },
        db.notificationEventDao().getAll().map { "${it.occurredAtMs}|${it.type}|${it.pkg}" }
    ).flatten()

    private fun parsed(): LoadedUsage.Parsed = LoadedUsage.Parsed(sampleResult(), baseMs)

    private fun sampleResult(): ParseResult = ParseResult(
        days = mapOf(
            day to DayMetrics(
                screenMin = 200,
                screenUnlockMin = 180,
                unlocks = 42,
                launches = 60,
                longestSessionMin = 35
            )
        ),
        hourMinutes = mapOf(day to List(24) { if (it == 9) 45 else 0 }),
        appUsage = listOf(AppUsageRow(day, "com.tencent.mm", 90 * 60_000L, 12)),
        unlockEvents = listOf(UnlockEvent(baseMs + 1_000)),
        notificationEvents = listOf(
            NotificationEvent(baseMs + 2_000, NotificationType.INTERRUPTION, "com.tencent.mm", null)
        )
    )

    private fun emptyResult(): ParseResult = ParseResult(
        days = emptyMap(),
        hourMinutes = emptyMap(),
        appUsage = emptyList(),
        unlockEvents = emptyList(),
        notificationEvents = emptyList()
    )

    /** 假通道：`onLoad` 让并发用例把 load 卡住，测完再放行。 */
    private class FakeProvider(
        override val channel: PullChannel,
        var loaded: LoadedUsage
    ) : UsageProvider {

        var status: ProviderStatus = ProviderStatus.AVAILABLE
        var loadCount: Int = 0
        var onLoad: (suspend () -> Unit)? = null

        override suspend fun status(): ProviderStatus = status

        override suspend fun load(): LoadedUsage {
            loadCount++
            onLoad?.invoke()
            return loaded
        }
    }
}
