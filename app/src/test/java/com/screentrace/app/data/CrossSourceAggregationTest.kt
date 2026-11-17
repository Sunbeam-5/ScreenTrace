package com.screentrace.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.data.backup.BackupFile
import com.screentrace.app.data.backup.BackupRepository
import com.screentrace.app.data.backup.DaySummaryDto
import com.screentrace.app.data.db.DaySummaryEntity
import com.screentrace.app.data.db.ScreenSessionEntity
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.db.SourceIds
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
 * CR-28 批次 B：跨来源读侧口径 + legacy 只读保护（设计审查测试矩阵 T2/T3/T9/T10/T11/T14）。
 *
 * - T2/T3/T14：SUM / UNION / MAX 的逐指标口径与跨午夜裁剪；
 * - T9/T10：legacy 日期内来源明确的新数据独立写入、legacy 原值不变；
 * - T11：A 合并 B 后 A 继续使用 30 分钟 → 合计 +30（R1 §0.1 P0 的回归）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CrossSourceAggregationTest {

    private lateinit var db: ScreenTraceDatabase
    private val zone: ZoneId = ZoneId.systemDefault()
    private val d: String = "2026-11-10"
    private val dNext: String = "2026-11-11"
    private val day: LocalDate = LocalDate.parse(d)

    private fun msOf(date: String, hour: Int, minute: Int = 0): Long =
        LocalDate.parse(date).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

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

    private fun dayRow(
        date: String,
        screen: Int,
        unlock: Int = screen,
        unlocks: Int = 2,
        launches: Int = 5,
        longest: Int = screen,
        source: String
    ) = DaySummaryEntity(date, screen, unlock, unlocks, launches, longest, 1L, source)

    private suspend fun sumScreen(date: String): Int =
        db.daySummaryDao().sumRange(date, date).single().screenMin

    /** 构造只含一行日聚合的 schema 5 备份（跨设备合并用）。 */
    private fun backupOf(date: String, screen: Int): BackupFile =
        BackupFile(
            schemaVersion = 5,
            exportedAt = "2026-11-17 12:00",
            appVersion = "1.0",
            daySummary = listOf(
                DaySummaryDto(date, screen, screen, 2, 5, screen, 2L)
            )
        )

    /** 构造本机拉取（单日、无应用行；可带解锁事件）。 */
    private fun pullOf(
        date: String,
        screen: Int,
        unlockMs: List<Long> = emptyList()
    ) = ParseResult(
        days = mapOf(LocalDate.parse(date) to DayMetrics(screen, screen, 3, 6, screen)),
        hourMinutes = emptyMap(),
        appUsage = emptyList(),
        unlockEvents = unlockMs.map { com.screentrace.app.contract.UnlockEvent(it) },
        notificationEvents = emptyList()
    )

    // --------------------------------------------------------------- T2

    @Test
    fun `T2 双设备同时使用 1 小时 SUM 2h UNION 1h MAX 1h`() = runBlocking {
        db.daySummaryDao().insert(dayRow(d, 60, source = "dev-A"))
        db.daySummaryDao().insert(dayRow(d, 60, source = "dev-B"))
        db.screenSessionDao().insert(
            listOf(
                ScreenSessionEntity(msOf(d, 9), msOf(d, 10), ScreenSessionEntity.KIND_SCREEN, "dev-A"),
                ScreenSessionEntity(msOf(d, 9), msOf(d, 10), ScreenSessionEntity.KIND_SCREEN, "dev-B")
            )
        )

        val agg = db.daySummaryDao().sumRange(d, d).single()
        assertEquals("总使用时长跨来源 SUM = 2h", 120, agg.screenMin)
        assertEquals("最长连续跨来源 MAX = 1h（不是 2h）", 60, agg.longestSessionMin)

        val summary = RoomUsageRepository({ db }).summary(DateSelection.Day(day))
        assertEquals("读路径（recordsBetween）同样是 SUM", 120, summary.totalScreenUnlockMin)
        assertEquals(60, summary.longestSessionMin)

        val union = SessionUnion.occupiedMinutes(db.screenSessionDao().getAll(), day, zone)
        assertEquals("占用时钟时长（UNION）= 1h", 60, union)
        assertEquals("SUM − UNION = 跨设备重叠时长", 60, 120 - union)
    }

    // --------------------------------------------------------------- T3

    @Test
    fun `T3 不重叠 1h 加 30min SUM 与 UNION 都是 90 分钟`() = runBlocking {
        db.daySummaryDao().insert(dayRow(d, 60, source = "dev-A"))
        db.daySummaryDao().insert(dayRow(d, 30, source = "dev-B"))
        db.screenSessionDao().insert(
            listOf(
                ScreenSessionEntity(msOf(d, 9), msOf(d, 10), ScreenSessionEntity.KIND_SCREEN, "dev-A"),
                ScreenSessionEntity(msOf(d, 10), msOf(d, 10, 30), ScreenSessionEntity.KIND_SCREEN, "dev-B")
            )
        )

        assertEquals(90, sumScreen(d))
        val union = SessionUnion.occupiedMinutes(db.screenSessionDao().getAll(), day, zone)
        assertEquals("不重叠时段 UNION = SUM", 90, union)
    }

    // --------------------------------------------------------------- T9

    @Test
    fun `T9 legacy 日期内新数据独立入库 legacy 原值不变 SUM 为三者之和`() = runBlocking {
        val legacy = dayRow(d, 240, unlocks = 10, launches = 20, longest = 90, source = SourceIds.LEGACY)
        db.daySummaryDao().insert(legacy)
        // legacy 事件（历史来源未知）
        db.unlockEventDao().insertIgnore(
            com.screentrace.app.data.db.UnlockEventEntity(msOf(d, 8), SourceIds.LEGACY, 0)
        )

        // 设备 A 新用 1h + 一条新解锁事件（本机拉取）
        assertTrue(
            IngestPipeline(db, localDeviceId = { "dev-A" })
                .ingest(pullOf(d, 60, unlockMs = listOf(msOf(d, 9))), "dump").ok
        )
        // 设备 B 新用 2h（来源分量直写；合并写路径见 T10）
        db.daySummaryDao().upsert(
            DaySummaryEntity(d, 120, 120, 2, 5, 120, 2L, "dev-B")
        )

        val rows = db.daySummaryDao().getAll()
        assertEquals("三个来源各一行", 3, rows.size)
        assertEquals("legacy 原值逐字段不变", legacy, rows.single { it.sourceDeviceId == SourceIds.LEGACY })
        assertEquals(60, rows.single { it.sourceDeviceId == "dev-A" }.screenMin)
        assertEquals(120, rows.single { it.sourceDeviceId == "dev-B" }.screenMin)

        // legacy 事件与新事件各归各（都在库里）
        val unlocks = db.unlockEventDao().getAll()
        assertEquals(2, unlocks.size)
        assertTrue(unlocks.any { it.sourceDeviceId == SourceIds.LEGACY && it.occurredAtMs == msOf(d, 8) })
        assertTrue(unlocks.any { it.sourceDeviceId == "dev-A" && it.occurredAtMs == msOf(d, 9) })

        val agg = db.daySummaryDao().sumRange(d, d).single()
        assertEquals("SUM = 4h + 1h + 2h = 7h", 420, agg.screenMin)
    }

    // --------------------------------------------------------------- T10

    @Test
    fun `T10 跨设备合并只新增来源分量 不修改 legacy 行`() = runBlocking {
        val legacy = dayRow(d, 240, unlocks = 10, launches = 20, longest = 90, source = SourceIds.LEGACY)
        db.daySummaryDao().insert(legacy)

        val repo = BackupRepository(db, zone = zone, sourceDeviceId = { "dev-A" })
        val result = repo.mergeAccumulate(
            file = backupOf(d, 120),
            allowedDates = setOf(d),
            ledgerDeviceId = "dev-B"
        )
        assertEquals("dev-B 分量作为新行写入", 1, result.added)

        val rows = db.daySummaryDao().getAll()
        assertEquals("legacy 行原样保留（逐字段）", legacy, rows.single { it.sourceDeviceId == SourceIds.LEGACY })
        assertEquals("只新增 dev-B 分量行", 120, rows.single { it.sourceDeviceId == "dev-B" }.screenMin)
        assertEquals(2, rows.size)
    }

    // --------------------------------------------------------------- T11

    @Test
    fun `T11 A 合并 B 后 A 继续使用 30 分钟 结果增加 30 分钟`() = runBlocking {
        db.daySummaryDao().insert(dayRow(d, 120, source = "dev-A"))

        BackupRepository(db, zone = zone, sourceDeviceId = { "dev-A" }).mergeAccumulate(
            file = backupOf(d, 180),
            allowedDates = setOf(d),
            ledgerDeviceId = "dev-B"
        )
        assertEquals("A 2h + B 3h 合计 5h", 300, sumScreen(d))

        // A 继续使用 30 分钟：本机拉取 2.5h（同来源取大）
        assertTrue(IngestPipeline(db, localDeviceId = { "dev-A" }).ingest(pullOf(d, 150), "dump").ok)

        assertEquals("结果增加 30 分钟（5h → 5.5h）", 330, sumScreen(d))
        assertEquals("B 分量不动", 180, db.daySummaryDao().getAll().single { it.sourceDeviceId == "dev-B" }.screenMin)
        assertEquals("A 分量取大增长", 150, db.daySummaryDao().getAll().single { it.sourceDeviceId == "dev-A" }.screenMin)
    }

    // --------------------------------------------------------------- T14

    @Test
    fun `T14 跨午夜会话按自然日裁剪 UNION 不跨日摊派`() = runBlocking {
        // 23:00 → 次日 01:00，两个来源同样的区间（跨来源重叠）
        db.screenSessionDao().insert(
            listOf(
                ScreenSessionEntity(msOf(d, 23), msOf(dNext, 1), ScreenSessionEntity.KIND_SCREEN, "dev-A"),
                ScreenSessionEntity(msOf(d, 23), msOf(dNext, 1), ScreenSessionEntity.KIND_SCREEN, "dev-B")
            )
        )
        val sessions = db.screenSessionDao().getAll()

        assertEquals("当日只计裁剪后的 1h", 60, SessionUnion.occupiedMinutes(sessions, day, zone))
        assertEquals("次日同样各计 1h，不整段归起点日", 60, SessionUnion.occupiedMinutes(sessions, day.plusDays(1), zone))
        assertEquals("再往后没有摊派", 0, SessionUnion.occupiedMinutes(sessions, day.plusDays(2), zone))
    }
}
