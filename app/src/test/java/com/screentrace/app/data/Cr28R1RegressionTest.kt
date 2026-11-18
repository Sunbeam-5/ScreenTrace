package com.screentrace.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.contract.NotificationEvent
import com.screentrace.app.contract.NotificationType
import com.screentrace.app.data.backup.CrossDeviceMerge
import com.screentrace.app.data.backup.DaySummaryDto
import com.screentrace.app.data.backup.UnlockEventDto
import com.screentrace.app.data.db.DaySummaryEntity
import com.screentrace.app.data.db.ScreenSessionEntity
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.db.SourceIds
import com.screentrace.app.data.db.UnlockEventEntity
import com.screentrace.app.data.parse.DayMetrics
import com.screentrace.app.data.parse.ParseResult
import java.io.File
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
 * CR-28-R1 回归（GPT 审查 2 P0 + 4 P1 逐条钉住）：
 * - P0-1 双来源清洗同来源重算（跨来源混算会 SUM 重复）；
 * - P0-2 无会话证据不清零；
 * - P1-1 EventSeq 跨批乱序超集不吞事件（含 channelId 不同不互吞、重放幂等）；
 * - P1-2 重建事件完整主键改归属（seq 不匹配留 legacy、冲突保留 legacy）；
 * - P1-3 重建全指标差减校验（unlocks 超额整日跳过；备份重复行不双扣）；
 * - P1-4 半开区间边界（会话结束于零点只算前一天、归档窗口右开）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Cr28R1RegressionTest {

    private lateinit var db: ScreenTraceDatabase
    private val zone: ZoneId = ZoneId.systemDefault()
    private val d = "2026-11-10"
    private val dNext = "2026-11-11"
    private lateinit var snapDir: File

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ScreenTraceDatabase::class.java
        ).allowMainThreadQueries().build()
        snapDir = File(System.getProperty("java.io.tmpdir"), "cr28-r1-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
        snapDir.deleteRecursively()
    }

    private fun msOf(date: String, hour: Int, minute: Int = 0): Long =
        LocalDate.parse(date).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    // --------------------------------------------------------------- P0-1

    @Test
    fun `双来源超 24h 清洗各自同来源重算 SUM 不重复`() = runBlocking {
        // A 行超 24h（A 会话只 60min）；B 行正常（B 会话 60min）——跨来源混算会把
        // A+B 的 120min 写进 A（SUM 变 180/220）
        db.daySummaryDao().insert(DaySummaryEntity(d, 1500, 1500, 0, 40, 700, 1L, "dev-A"))
        db.daySummaryDao().insert(DaySummaryEntity(d, 60, 60, 0, 5, 60, 1L, "dev-B"))
        db.screenSessionDao().insert(
            listOf(
                ScreenSessionEntity(msOf(d, 9), msOf(d, 10), ScreenSessionEntity.KIND_SCREEN, "dev-A"),
                ScreenSessionEntity(msOf(d, 11), msOf(d, 12), ScreenSessionEntity.KIND_SCREEN, "dev-B")
            )
        )

        val report = SourceRebuild(db, zone).rebuildOversizedDays(snapDir)

        assertEquals("A 校正为自己的 60（不是 A+B 的 120）", 60, db.daySummaryDao().get(d, "dev-A")!!.screenMin)
        assertEquals("B 不受影响", 60, db.daySummaryDao().get(d, "dev-B")!!.screenMin)
        assertEquals("SUM 不重复", 120, db.daySummaryDao().getAll().filter { it.date == d }.sumOf { it.screenMin })
        assertEquals("oversized_corrected", report.days.single().status)
    }

    // --------------------------------------------------------------- P0-2

    @Test
    fun `无会话证据不清零保留原值`() = runBlocking {
        db.daySummaryDao().insert(DaySummaryEntity(d, 1500, 1500, 3, 40, 700, 1L, "dev-A"))

        val report = SourceRebuild(db, zone).rebuildOversizedDays(snapDir)

        val row = db.daySummaryDao().get(d, "dev-A")!!
        assertEquals("缺证据不能把历史时长清零", 1500, row.screenMin)
        assertEquals(3, row.unlocks)
        assertEquals("oversized_no_evidence", report.days.single().status)
    }

    // --------------------------------------------------------------- P1-1

    @Test
    fun `通知 seq 分组含 channelId 跨批乱序超集不吞事件`() = runBlocking {
        val t = msOf(d, 9)
        fun batch(vararg channels: String) = ParseResult(
            days = mapOf(LocalDate.parse(d) to DayMetrics(60, 60, 1, 1, 60)),
            hourMinutes = emptyMap(),
            appUsage = emptyList(),
            unlockEvents = emptyList(),
            notificationEvents = channels.map { NotificationEvent(t, NotificationType.INTERRUPTION, "com.a", it) }
        )
        val pipeline = IngestPipeline(db, localDeviceId = { "dev-A" })

        assertTrue(pipeline.ingest(batch("ch1"), "dump", t).ok)
        // 批 2：乱序超集（新 ch2 排在旧 ch1 前）——旧「组按出现次序编号」会把 ch2
        // 编成 seq=0 与旧 ch1 撞键吞掉
        assertTrue(pipeline.ingest(batch("ch2", "ch1"), "dump", t + 60_000).ok)

        val rows = db.notificationEventDao().getAll()
        assertEquals("ch1 与 ch2 各一条、不互吞", 2, rows.size)
        assertEquals(setOf("ch1", "ch2"), rows.map { it.channelId }.toSet())

        // 重放幂等
        assertTrue(pipeline.ingest(batch("ch2", "ch1"), "dump", t + 120_000).ok)
        assertEquals(2, db.notificationEventDao().getAll().size)
    }

    // --------------------------------------------------------------- P1-2

    @Test
    fun `重建事件按完整主键改归属 冲突与不匹配都保留 legacy`() = runBlocking {
        val ms = msOf(d, 9)
        // legacy：同 ms 两条（seq 0/1）；目标 dev-A 已有 (ms, seq=0)（冲突）
        db.unlockEventDao().insertIgnore(
            listOf(
                UnlockEventEntity(ms, SourceIds.LEGACY, 0),
                UnlockEventEntity(ms, SourceIds.LEGACY, 1)
            )
        )
        db.unlockEventDao().insertIgnore(listOf(UnlockEventEntity(ms, "dev-A", 0)))
        db.daySummaryDao().insert(DaySummaryEntity(d, 240, 240, 2, 20, 90, 1L, SourceIds.LEGACY))

        SourceRebuild(db, zone).rebuild(
            targets = setOf(d),
            sources = listOf(
                SourceRebuild.SourceBackup(
                    deviceId = "dev-A",
                    daySummaries = listOf(DaySummaryDto(d, 120, 120, 1, 10, 60, 2L)),
                    unlockEvent = listOf(UnlockEventDto(ms, 0)) // 只有 seq=0 有备份实证
                ),
                SourceRebuild.SourceBackup(
                    deviceId = "dev-B",
                    daySummaries = listOf(DaySummaryDto(d, 120, 120, 1, 10, 60, 2L))
                )
            ),
            snapshotDir = snapDir
        )

        val unlocks = db.unlockEventDao().getAll()
        // (ms, seq=0, legacy)：目标已有同完整主键 → 保留 legacy 不动（证据不丢）
        assertTrue(unlocks.any { it.sourceDeviceId == SourceIds.LEGACY && it.seq == 0 })
        // (ms, seq=1)：备份无 seq=1 实证 → 留 legacy
        assertTrue(unlocks.any { it.sourceDeviceId == SourceIds.LEGACY && it.seq == 1 })
        // dev-A 的 (ms, seq=0) 原样
        assertTrue(unlocks.any { it.sourceDeviceId == "dev-A" && it.seq == 0 })
    }

    // --------------------------------------------------------------- P1-3

    @Test
    fun `重建全指标差减校验 unlocks 超额时整日跳过`() = runBlocking {
        // legacy unlocks=10，备份合计 unlocks=12（超额）→ 该日不重建
        db.daySummaryDao().insert(DaySummaryEntity(d, 240, 100, 10, 20, 90, 1L, SourceIds.LEGACY))

        val report = SourceRebuild(db, zone).rebuild(
            targets = setOf(d),
            sources = listOf(
                SourceRebuild.SourceBackup(
                    deviceId = "dev-A",
                    daySummaries = listOf(DaySummaryDto(d, 120, 50, 12, 10, 60, 2L))
                ),
                SourceRebuild.SourceBackup(
                    deviceId = "dev-B",
                    daySummaries = listOf(DaySummaryDto(d, 120, 50, 0, 10, 60, 2L))
                )
            ),
            snapshotDir = snapDir
        )

        assertEquals("mismatch_skipped", report.days.single().status)
        val legacy = db.daySummaryDao().getAll().single()
        assertEquals("整日不重建：原样保留", 240, legacy.screenMin)
        assertEquals(10, legacy.unlocks)
    }

    @Test
    fun `重建 app 差减用去重数据 备份重复行不双扣`() = runBlocking {
        db.daySummaryDao().insert(DaySummaryEntity(d, 240, 240, 2, 20, 90, 1L, SourceIds.LEGACY))
        db.appUsageDao().insert(
            com.screentrace.app.data.db.AppUsageEntity(d, "com.x", "X", 100, 5, "OTHER", SourceIds.LEGACY)
        )

        SourceRebuild(db, zone).rebuild(
            targets = setOf(d),
            sources = listOf(
                SourceRebuild.SourceBackup(
                    deviceId = "dev-A",
                    daySummaries = listOf(DaySummaryDto(d, 120, 120, 1, 10, 60, 2L)),
                    // 同 pkg 重复两行（手搓备份）：写入只留一份、差减也只扣一份
                    appUsage = listOf(
                        com.screentrace.app.data.backup.AppUsageDto(d, "com.x", "X", 60, 2, "OTHER"),
                        com.screentrace.app.data.backup.AppUsageDto(d, "com.x", "X", 60, 2, "OTHER")
                    )
                ),
                SourceRebuild.SourceBackup(
                    deviceId = "dev-B",
                    daySummaries = listOf(DaySummaryDto(d, 120, 120, 1, 10, 60, 2L))
                )
            ),
            snapshotDir = snapDir
        )

        val apps = db.appUsageDao().getAll().filter { it.pkg == "com.x" }
        assertEquals(40, apps.single { it.sourceDeviceId == SourceIds.LEGACY }.minutes)
        assertEquals("写入只留一份", 60, apps.single { it.sourceDeviceId == "dev-A" }.minutes)
    }

    // --------------------------------------------------------------- P1-4

    @Test
    fun `会话结束于零点只算前一天 归档窗口右开`() = runBlocking {
        // ① 会话 23:00 → 次日 00:00：只跨到 d（半开 [start, end)）
        val dates = CrossDeviceMerge.datesOfSession(msOf(d, 23), msOf(dNext, 0), zone)
        assertEquals("结束于零点不得把次日算进去", setOf(d), dates)

        // ② 事件恰在 hi（次日 0 点整）不归入 d；会话恰从次日 0 点起不归入 d
        db.unlockEventDao().insertIgnore(
            listOf(UnlockEventEntity(msOf(dNext, 0), SourceIds.UNARCHIVED, 0))
        )
        db.screenSessionDao().insert(
            listOf(ScreenSessionEntity(msOf(dNext, 0), msOf(dNext, 1), ScreenSessionEntity.KIND_SCREEN, SourceIds.UNARCHIVED))
        )
        SourceArchive.archive(db, "dev-1", setOf(d), zone)

        val event = db.unlockEventDao().getAll().single()
        assertEquals("hi 端点事件不归 d（右开）", "dev-1", event.sourceDeviceId)
        val session = db.screenSessionDao().getAll().single()
        assertEquals("从 hi 起的会话不归 d（半开相交）", "dev-1", session.sourceDeviceId)
    }
}
