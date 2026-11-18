package com.screentrace.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.contract.NotificationEvent
import com.screentrace.app.contract.NotificationType
import com.screentrace.app.contract.UnlockEvent
import com.screentrace.app.data.db.EventSeq
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.parse.DayMetrics
import com.screentrace.app.data.parse.ParseResult
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CR-28 T13：同毫秒事件——**同设备同毫秒多条不丢**（补全 CR-01 红线到 DB 层）、
 * **跨设备同毫秒互不吞**、**同一份输入重放幂等**（确定性 `seq`）。
 *
 * 旧 PK（`occurredAtMs` / 不含 `channelId`）会把同毫秒第二条 `INSERT OR IGNORE`
 * 吞掉（`Entities.kt` 旧注释自陈）；本测试就是钉住修复的三件事。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SameMillisecondEventTest {

    private lateinit var db: ScreenTraceDatabase
    private val zone: ZoneId = ZoneId.systemDefault()
    private val d1: LocalDate = LocalDate.of(2026, 11, 2)
    private val t: Long = d1.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ScreenTraceDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 同毫秒：2 条解锁 + 2 条同包同类型通知（CR-01 红线场景）。 */
    private fun sameMsResult() = ParseResult(
        days = mapOf(d1 to DayMetrics(420, 400, 2, 10, 35)),
        hourMinutes = mapOf(d1 to List(24) { 10 }),
        appUsage = emptyList(),
        unlockEvents = listOf(UnlockEvent(t), UnlockEvent(t)),
        notificationEvents = listOf(
            NotificationEvent(t, NotificationType.INTERRUPTION, "com.a", "ch"),
            NotificationEvent(t, NotificationType.INTERRUPTION, "com.a", "ch")
        )
    )

    @Test
    fun `同设备同毫秒多条事件各占一行不被吞`() = runBlocking {
        val pipeline = IngestPipeline(db, localDeviceId = { "dev-A" })
        assertTrue(pipeline.ingest(sameMsResult(), "dump", t).ok)

        val unlocks = db.unlockEventDao().getAll()
        assertEquals("同毫秒两条解锁都要在", 2, unlocks.size)
        assertEquals(listOf(0, 1), unlocks.map { it.seq }.sorted())

        val notifs = db.notificationEventDao().getAll()
        assertEquals("同毫秒同包同类型两条通知都要在", 2, notifs.size)
        assertEquals(listOf(0, 1), notifs.map { it.seq }.sorted())
    }

    @Test
    fun `同一份输入重放幂等不增行`() = runBlocking {
        val pipeline = IngestPipeline(db, localDeviceId = { "dev-A" })
        val result = sameMsResult()
        assertTrue(pipeline.ingest(result, "dump", t).ok)
        assertTrue(pipeline.ingest(result, "dump", t + 60_000).ok)

        assertEquals(2, db.unlockEventDao().getAll().size)
        assertEquals(2, db.notificationEventDao().getAll().size)
    }

    @Test
    fun `跨设备同毫秒事件互不吞`() = runBlocking {
        val result = sameMsResult()
        assertTrue(IngestPipeline(db, localDeviceId = { "dev-A" }).ingest(result, "dump", t).ok)
        assertTrue(IngestPipeline(db, localDeviceId = { "dev-B" }).ingest(result, "dump", t).ok)

        assertEquals("A、B 同一毫秒解锁：4 条都在", 4, db.unlockEventDao().getAll().size)
        assertEquals("A、B 同一毫秒通知：4 条都在", 4, db.notificationEventDao().getAll().size)
    }

    @Test
    fun `同秒组不同内容两批合并不丢行 旧整边替换会丢第一批`() = runBlocking {
        // 两批数据落在同一秒、同 type、同 pkg，内容不同（不同 channelId）
        val batch1 = ParseResult(
            days = mapOf(d1 to DayMetrics(60, 60, 1, 1, 60)),
            hourMinutes = emptyMap(),
            appUsage = emptyList(),
            unlockEvents = emptyList(),
            notificationEvents = listOf(
                NotificationEvent(t, NotificationType.INTERRUPTION, "com.a", "ch1"),
                NotificationEvent(t + 100, NotificationType.INTERRUPTION, "com.a", "ch1")
            )
        )
        val batch2 = ParseResult(
            days = mapOf(d1 to DayMetrics(60, 60, 1, 1, 60)),
            hourMinutes = emptyMap(),
            appUsage = emptyList(),
            unlockEvents = emptyList(),
            notificationEvents = listOf(
                NotificationEvent(t + 200, NotificationType.INTERRUPTION, "com.a", "ch2"),
                NotificationEvent(t + 300, NotificationType.INTERRUPTION, "com.a", "ch2")
            )
        )
        val pipeline = IngestPipeline(db, localDeviceId = { "dev-A" })
        assertTrue(pipeline.ingest(batch1, "dump", t).ok)
        assertTrue(pipeline.ingest(batch2, "dump", t + 60_000).ok)

        assertEquals(
            "同秒组两批内容并集：4 条都在（旧「整边替换」会把第一批整组丢掉）",
            4,
            db.notificationEventDao().getAll().size
        )
    }

    @Test
    fun `seq 按组内稳定内容键排序编号同一输入得同一组键`() {
        val items = listOf("a0", "b0", "a1", "b0b", "a2")
        val first = EventSeq.assign(items, { listOf(it.first()) }, { listOf(it) })
        val second = EventSeq.assign(items, { listOf(it.first()) }, { listOf(it) })
        assertEquals("同一输入必须得同一组 (item, seq)", first, second)
        // a 组按内容排序编号 0/1/2；b 组按内容排序编号 0/1
        val seqByItem = first.toMap()
        assertEquals(0, seqByItem["a0"])
        assertEquals(1, seqByItem["a1"])
        assertEquals(2, seqByItem["a2"])
        assertEquals(0, seqByItem["b0"])
        assertEquals(1, seqByItem["b0b"])
    }
}
