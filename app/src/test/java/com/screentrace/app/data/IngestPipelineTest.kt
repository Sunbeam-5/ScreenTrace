package com.screentrace.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.contract.NotificationEvent
import com.screentrace.app.contract.NotificationType
import com.screentrace.app.contract.UnlockEvent
import com.screentrace.app.data.db.NotificationEventDao
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.parse.AppUsageRow
import com.screentrace.app.data.parse.DayMetrics
import com.screentrace.app.data.parse.ParseResult
import java.time.LocalDate
import java.time.ZoneId
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
 * 批 6a 评审 P1-2 的验收物：**管道级**测试（真 Room / 真 SQLite 语义）。
 *
 * [MergePolicyTest] 只证明纯函数对不对，证明不了管道里那三件纯函数测不到的事：
 * 1. `existing` 取的是哪个窗口（窗口取错 → 合并基数错）；
 * 2. delete 与 insert 的顺序（先删后插 + 单事务，失败可回滚）；
 * 3. 连续拉取后表里的**行数与取值**是否稳定（真正对应验收项「连续拉取不重复计数」）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IngestPipelineTest {

    private lateinit var db: ScreenTraceDatabase
    private lateinit var pipeline: IngestPipeline

    private val d1: LocalDate = LocalDate.of(2026, 11, 2)
    private val d2: LocalDate = d1.plusDays(1)
    private val t0: Long = msOf(d1, 9)

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

    // ---------- 验收项 1：连续拉取不重复计数 ----------

    @Test
    fun `同一份 dump 连灌两次所有表行数与取值不变`() = runBlocking {
        val result = sampleResult()

        assertTrue(pipeline.ingest(result, "dump", t0).ok)
        val first = TableSnapshot.of(db)
        assertTrue(pipeline.ingest(result, "dump", t0 + 60_000).ok)
        val second = TableSnapshot.of(db)

        assertEquals("day_summary 不得因重拉而变行", first.days, second.days)
        assertEquals("app_usage 不得因重拉而翻倍", first.apps, second.apps)
        assertEquals("hour_usage 不得因重拉而翻倍", first.hours, second.hours)
        assertEquals("unlock_event 不得因重拉而翻倍", first.unlocks, second.unlocks)
        assertEquals("notification_event 不得因重拉而翻倍", first.notifs, second.notifs)
        // 数据值本身也没被第二次写入改写（分钟数不是两倍）
        val wechat = db.appUsageDao().get(d1.toString(), "com.tencent.mm")
        assertEquals(12, wechat?.minutes)
        assertEquals(30, wechat?.launches)
    }

    @Test
    fun `pull_log 每次拉取各记一行且标记成功`() = runBlocking {
        assertTrue(pipeline.ingest(sampleResult(), "dump", t0).ok)
        assertTrue(pipeline.ingest(sampleResult(), "dump", t0 + 60_000).ok)
        // pull_log「仅追加」，因此两次成功 = 两条 ok 记录
        assertEquals(2, db.pullLogDao().okMs().size)
        assertEquals(0, db.pullLogDao().failedMs().size)
        assertNotNull(db.pullLogDao().latest())
    }

    // ---------- 验收项 2：同秒多条通知不丢失 ----------

    @Test
    fun `同秒三条通知入库后保留三条且重拉不增不减`() = runBlocking {
        val sameSecond = listOf(
            NotificationEvent(t0, NotificationType.INTERRUPTION, "com.tencent.mm", "channel-a"),
            NotificationEvent(t0 + 120, NotificationType.INTERRUPTION, "com.tencent.mm", "channel-b"),
            NotificationEvent(t0 + 400, NotificationType.INTERRUPTION, "com.tencent.mm", "channel-c")
        )
        val result = emptyResult().copy(notificationEvents = sameSecond)

        assertTrue(pipeline.ingest(result, "dump", t0).ok)
        assertEquals("同秒 3 条真实通知不得被秒级去重吞掉", 3, db.notificationEventDao().getAll().size)

        assertTrue(pipeline.ingest(result, "dump", t0 + 1_000).ok)
        assertEquals(3, db.notificationEventDao().getAll().size)
    }

    @Test
    fun `第二次只拉到同秒中的一条时保留较大边不丢数据`() = runBlocking {
        val three = (0 until 3).map {
            NotificationEvent(t0 + it * 200L, NotificationType.INTERRUPTION, "com.tencent.mm", "c$it")
        }
        assertTrue(pipeline.ingest(emptyResult().copy(notificationEvents = three), "dump", t0).ok)

        // 双源/重拉只看到 1 条：按 §2.2 坑 2 的「整边取大」，应保留 3 条
        val one = listOf(three.first())
        assertTrue(pipeline.ingest(emptyResult().copy(notificationEvents = one), "dump", t0 + 5_000).ok)
        assertEquals(3, db.notificationEventDao().getAll().size)
    }

    @Test
    fun `先拉到一条再拉到同秒三条时按整边取大写入`() = runBlocking {
        val all = (0 until 3).map {
            NotificationEvent(t0 + it * 200L, NotificationType.INTERRUPTION, "com.tencent.mm", "c$it")
        }
        // 这条方向才会真正走「删窗口 → 批量写回」的分支（上一版 IN 列表的 P0 就在这条路径上）
        assertTrue(pipeline.ingest(emptyResult().copy(notificationEvents = all.take(1)), "dump", t0).ok)
        assertEquals(1, db.notificationEventDao().getAll().size)

        assertTrue(pipeline.ingest(emptyResult().copy(notificationEvents = all), "dump", t0 + 2_000).ok)
        assertEquals("后到的较大边应整边替换", 3, db.notificationEventDao().getAll().size)
    }

    // ---------- 验收项 3：历史只增不删（较小值不回退） ----------

    @Test
    fun `第二次拉到更小的日聚合时保留旧值`() = runBlocking {
        val bigger = emptyResult().copy(
            days = mapOf(d1 to DayMetrics(420, 400, 22, 61, 35))
        )
        assertTrue(pipeline.ingest(bigger, "dump", t0).ok)

        val smaller = emptyResult().copy(
            days = mapOf(d1 to DayMetrics(200, 120, 5, 6, 7))
        )
        assertTrue(pipeline.ingest(smaller, "dump", t0 + 60_000).ok)

        val row = db.daySummaryDao().get(d1.toString())
        assertEquals(400, row?.screenUnlockMin)
        assertEquals(420, row?.screenMin)
        assertEquals("整行保留（§4：取 screen_unlock_min 较大的一行）", 35, row?.longestSessionMin)
    }

    // ---------- P0 回归：大窗口通知重拉 ----------

    /**
     * P0 回归（6a 评审 §三）：真实 10 天 dump 有 4837 条通知，第二次拉取时
     * `IN (:msList)` 会拼 4837 个绑定参数，Android 8–11 的 SQLite 上限 999 → 必炸。
     *
     * 注：Robolectric 跑的是宿主 SQLite（上限 32766），**复现不出 999 上限**，
     * 因此这里既做体量冒烟，也用反射守住「删除接口不得再收 List」这条契约。
     */
    @Test
    fun `近五千条通知连续两次拉取不失败且不重复`() = runBlocking {
        val many = (0 until 4_837).map {
            NotificationEvent(t0 + it * 700L, NotificationType.INTERRUPTION, "com.pkg${it % 30}", null)
        }
        val result = emptyResult().copy(notificationEvents = many)

        assertTrue(pipeline.ingest(result, "dump", t0).ok)
        assertEquals(4_837, db.notificationEventDao().getAll().size)

        assertTrue("第二次拉取不得因绑定变量上限失败", pipeline.ingest(result, "dump", t0 + 60_000).ok)
        assertEquals(4_837, db.notificationEventDao().getAll().size)
        assertEquals(2, db.pullLogDao().okMs().size)
        assertEquals("失败不得被 catch 成 ok=false 静默吞掉", 0, db.pullLogDao().failedMs().size)
    }

    @Test
    fun `通知删除接口不得再接受主键列表`() {
        val listDeletes = NotificationEventDao::class.java.methods.filter { m ->
            (m.name.startsWith("delete") || m.name.startsWith("remove")) &&
                m.parameterTypes.any { it == List::class.java }
        }
        assertTrue(
            "delete 接受 List 会在多日重拉时撞 SQLite 绑定变量上限，应改用 deleteBetween：$listDeletes",
            listDeletes.isEmpty()
        )
    }

    // ---------- P2-2 / P1-1 ----------

    @Test
    fun `空 dump 记失败而不是成功`() = runBlocking {
        val result = pipeline.ingest(emptyResult(), "dump", t0)
        assertFalse(result.ok)
        assertEquals(0, db.pullLogDao().okMs().size)
        assertEquals(1, db.pullLogDao().failedMs().size)
        assertEquals("dump 为空", db.pullLogDao().latest()?.note)
    }

    @Test
    fun `入库时按内置映射写入应用分类`() = runBlocking {
        assertTrue(pipeline.ingest(sampleResult(), "dump", t0).ok)
        val rows = db.appUsageDao().sumBetween(d1.toString(), d2.toString()).associateBy { it.pkg }
        // 分类口径与对账脚本 parse_usage_v2.py 的 CATEGORY_RULES 一致
        assertEquals("SOCIAL", rows["com.tencent.mm"]?.category)
        assertEquals("VIDEO", rows["tv.danmaku.bili"]?.category)
        assertEquals("OTHER", rows["com.bbk.launcher2"]?.category)
    }

    // ---------- helpers ----------

    private fun emptyResult() = ParseResult(
        days = emptyMap(),
        hourMinutes = emptyMap(),
        appUsage = emptyList(),
        unlockEvents = emptyList(),
        notificationEvents = emptyList()
    )

    private fun sampleResult(): ParseResult = ParseResult(
        days = mapOf(
            d1 to DayMetrics(screenMin = 420, screenUnlockMin = 400, unlocks = 22, launches = 61, longestSessionMin = 35),
            d2 to DayMetrics(screenMin = 300, screenUnlockMin = 290, unlocks = 18, launches = 40, longestSessionMin = 21)
        ),
        hourMinutes = mapOf(
            d1 to List(24) { 10 },
            d2 to List(24) { 5 }
        ),
        appUsage = listOf(
            AppUsageRow(d1, "com.tencent.mm", 12 * 60_000L, 30),
            AppUsageRow(d1, "tv.danmaku.bili", 30 * 60_000L, 5),
            AppUsageRow(d1, "com.bbk.launcher2", 40 * 60_000L, 70),
            AppUsageRow(d2, "com.tencent.mm", 6 * 60_000L, 12)
        ),
        unlockEvents = (0 until 3).map { UnlockEvent(t0 + it * 1_000L) },
        notificationEvents = listOf(
            NotificationEvent(t0 + 10, NotificationType.INTERRUPTION, "com.tencent.mm", "msg"),
            NotificationEvent(t0 + 20, NotificationType.SEEN, "com.tencent.mm", "msg")
        )
    )

    private fun msOf(date: LocalDate, hour: Int): Long =
        date.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** 只比「行数 + 主键集合」，足以暴露重复计数与丢数据。 */
    private data class TableSnapshot(
        val days: List<String>,
        val apps: List<String>,
        val hours: List<String>,
        val unlocks: List<Long>,
        val notifs: List<String>
    ) {
        companion object {
            suspend fun of(db: ScreenTraceDatabase): TableSnapshot = TableSnapshot(
                days = db.daySummaryDao().getAll().map { "${it.date}|${it.screenMin}|${it.screenUnlockMin}|${it.unlocks}" },
                apps = db.appUsageDao().sumBetween("0001-01-01", "9999-12-31")
                    .map { "${it.pkg}|${it.minutes}|${it.launches}" },
                hours = db.hourUsageDao().sumBetween("0001-01-01", "9999-12-31")
                    .map { "${it.hour}|${it.minutes}" },
                unlocks = db.unlockEventDao().msBetween(0, Long.MAX_VALUE),
                notifs = db.notificationEventDao().getAll()
                    .map { "${it.occurredAtMs}|${it.type}|${it.pkg}" }
            )
        }
    }
}
