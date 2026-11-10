package com.screentrace.app.data.pull

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.data.IngestPipeline
import com.screentrace.app.data.RoomUsageRepository
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.parse.UsageDumpParser
import java.io.File
import java.time.ZoneId
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

/**
 * 端到端：**真实 dump 样本** → Shizuku 通道 → 解析 → 入 Room → 仓库读出。
 *
 * 这是本批唯一不依赖真机就能证明「通道没把数据搞坏」的测试。断言不写死数字
 * （批 5 的 golden 测试已经钉了数字），而是断言**不变量**：
 *
 * 1. 通道报出的天数/事件数 == 直接调解析器的结果（通道没有二次加工、没有丢事件）；
 * 2. 库里行数 == 解析器产出（入库不吞行）；
 * 3. 连续拉两次，六表规模与 `unlock/notification` 条数**完全不变**
 *    —— 对应 §批次 6b 验收项「连续拉取不重复计数（含事件表）」。
 *
 * 样本来自 `reference/sample-checkin.txt`（7 条通知事件）与 `sample-human.txt`
 * （含 KEYGUARD_HIDDEN），与批 5 用的是同一份。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShizukuEndToEndTest {

    private lateinit var db: ScreenTraceDatabase
    private lateinit var pipeline: IngestPipeline

    private val zone: ZoneId = ZoneId.systemDefault()
    private var now: Long = 1_793_400_000_000 // 落在样本时间窗之后，作为「拉取时刻」

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ScreenTraceDatabase::class.java
        ).allowMainThreadQueries().build()
        pipeline = IngestPipeline(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `真实样本走完整通道后解析数入库数与仓库读数一致`() = runBlocking {
        val checkin = sample("sample-checkin.txt")
        val human = sample("sample-human.txt")
        val expected = UsageDumpParser.parse(checkin, human, zone)
        assertTrue("样本本身应先被解析出内容，否则这条测试没意义", expected.days.isNotEmpty())

        val coordinator = coordinator(shellReturning(checkin, human))
        val outcome = coordinator.pull()

        assertTrue("真实样本必须能走通主通道", outcome is PullOutcome.Success)
        outcome as PullOutcome.Success
        assertEquals(PullChannel.SHIZUKU, outcome.channel)
        assertEquals(expected.days.size, outcome.days)
        assertEquals(expected.unlockEvents.size, outcome.unlockEvents)
        assertEquals(expected.notificationEvents.size, outcome.notificationEvents)

        // 入库不吞行
        assertEquals(expected.days.size, db.daySummaryDao().getAll().size)
        assertEquals(
            "解锁事件按毫秒主键去重后条数应等于解析结果的去重条数",
            expected.unlockEvents.map { it.occurredAtMs }.toSet().size,
            db.unlockEventDao().msBetween(0, Long.MAX_VALUE).size
        )
        assertEquals(
            expected.notificationEvents.map { Triple(it.occurredAtMs, it.type, it.pkg) }.toSet().size,
            db.notificationEventDao().getAll().size
        )

        // pull_log 如实记录来源
        val log = db.pullLogDao().latest()
        assertNotNull(log)
        assertEquals("shizuku", log!!.source)
        assertTrue(log.ok)

        // 仓库能读出：区间覆盖 == 入库天数
        val coverage = coordinator.historyCoverage()
        assertNotNull(coverage.firstDate)
        val repository = RoomUsageRepository(db)
        val summary = repository.summary(DateSelection.Range(coverage.firstDate!!, coverage.lastDate!!))
        assertTrue("仓库读不出数据说明聚合口径与入库口径打架", summary.hasAnyRecord)

        // ---------- 连续拉取不重复计数（含事件表） ----------
        val before = stats()
        assertTrue(coordinator.pull() is PullOutcome.Success)
        assertEquals("第二次拉取后六表规模必须原样", before, stats())
    }

    @Test
    fun `样本里出现过的包都能在排行里找到且分类不是清一色`() = runBlocking {
        val checkin = sample("sample-checkin.txt")
        val human = sample("sample-human.txt")
        val coordinator = coordinator(shellReturning(checkin, human))
        coordinator.pull()

        val coverage = coordinator.historyCoverage()
        val repository = RoomUsageRepository(db)
        val apps = repository.appRanking(
            DateSelection.Range(coverage.firstDate!!, coverage.lastDate!!)
        )
        assertTrue("排行应有内容", apps.isNotEmpty())
        // 内置分类表覆盖度（6a 的 176 包）至少要让一部分应用落到非 OTHER
        assertTrue(
            "分类表若全部落 OTHER，说明 6a 的映射没接上",
            apps.count { it.category.name != "OTHER" } > 0
        )
    }

    // ---------- helpers ----------

    private suspend fun stats(): List<String> = listOf(
        db.daySummaryDao().getAll().map { "${it.date}|${it.screenUnlockMin}|${it.unlocks}" }.sorted().toString(),
        db.appUsageDao().sumBetween("0001-01-01", "9999-12-31")
            .map { "${it.pkg}|${it.minutes}|${it.launches}" }.sorted().toString(),
        db.hourUsageDao().sumBetween("0001-01-01", "9999-12-31")
            .map { "${it.hour}|${it.minutes}" }.sorted().toString(),
        db.unlockEventDao().msBetween(0, Long.MAX_VALUE).sorted().toString(),
        db.notificationEventDao().getAll()
            .map { "${it.occurredAtMs}|${it.type}|${it.pkg}" }.sorted().toString()
    )

    private fun coordinator(shell: ShellRunner): PullCoordinator {
        val provider = ShizukuUsageProvider(shell = shell, zone = zone, clock = { now })
        return PullCoordinator(
            db = db,
            pipeline = pipeline,
            providers = listOf(AssumeGranted(provider)),
            clock = { now },
            zone = zone
        )
    }

    /**
     * 把通道状态固定为 AVAILABLE。
     *
     * 必须这么做的原因：协调器**先查状态再读**，而 Robolectric 里没有 Shizuku 服务，
     * `Shizuku.pingBinder()` 必然失败 → 状态是「未安装」→ 压根不会调 [UsageProvider.load]。
     * 本测试验的是「读 dump → 解析 → 入库 → 读出」这条数据通路，不是权限探测
     * （权限探测由 `ShizukuUsageProviderTest` 单独覆盖），所以这里显式声明「假设已授权」，
     * 而不是把 status 的断言偷偷放宽。
     */
    private class AssumeGranted(private val delegate: UsageProvider) : UsageProvider {
        override val channel: PullChannel get() = delegate.channel
        override suspend fun status(): ProviderStatus = ProviderStatus.AVAILABLE
        override suspend fun load(): LoadedUsage = delegate.load()
    }

    private fun shellReturning(checkin: String, human: String) = object : ShellRunner {
        override suspend fun exec(command: String, timeoutMs: Long): String = when (command) {
            ShizukuShell.CHECKIN_CMD -> checkin
            ShizukuShell.PLAIN_CMD -> human
            else -> error("未预期的命令：$command")
        }
    }

    private fun sample(name: String): String {
        val file = File("../reference/$name")
        assertTrue("缺少样本文件 ${file.absolutePath}", file.exists())
        return file.readText()
    }
}
