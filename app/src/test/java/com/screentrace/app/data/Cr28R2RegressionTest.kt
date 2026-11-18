package com.screentrace.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.contract.NotificationEvent
import com.screentrace.app.contract.NotificationType
import com.screentrace.app.contract.UnlockEvent
import com.screentrace.app.data.backup.AppUsageDto
import com.screentrace.app.data.backup.BackupFile
import com.screentrace.app.data.backup.BackupRepository
import com.screentrace.app.data.backup.CrossDeviceMerge
import com.screentrace.app.data.backup.DaySummaryDto
import com.screentrace.app.data.backup.HourUsageDto
import com.screentrace.app.data.backup.UnlockEventDto
import com.screentrace.app.data.db.AppUsageEntity
import com.screentrace.app.data.db.DaySummaryEntity
import com.screentrace.app.data.db.HourUsageEntity
import com.screentrace.app.data.db.NotificationEventEntity
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CR-28-R2 回归（GPT 针对性复审：4 新 P1 + 6 测试缺口）。
 * 纪律：每个用例都先证明**旧错误实现会失败**（对应变异见 mutate_cr28_r2.py），
 * 再证明修复后通过；变异必须改可观察行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Cr28R2RegressionTest {

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
        snapDir = File(System.getProperty("java.io.tmpdir"), "cr28-r2-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
        snapDir.deleteRecursively()
    }

    private fun msOf(date: String, hour: Int, minute: Int = 0): Long =
        LocalDate.parse(date).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun dayRow(screen: Int, unlockMin: Int, unlocks: Int, launches: Int, longest: Int, source: String) =
        DaySummaryEntity(d, screen, unlockMin, unlocks, launches, longest, 1L, source)

    // ============ 新问题 ①：app_usage.launches 独立差减/预检/核对

    @Test
    fun `app launches 超额时整日跳过 minutes 正常也不例外`() = runBlocking {
        db.daySummaryDao().insert(dayRow(240, 240, 2, 20, 90, SourceIds.LEGACY))
        db.appUsageDao().insert(AppUsageEntity(d, "com.x", "X", 100, 5, "OTHER", SourceIds.LEGACY))

        val report = SourceRebuild(db, zone).rebuild(
            targets = setOf(d),
            sources = listOf(
                SourceRebuild.SourceBackup(
                    deviceId = "dev-A",
                    daySummaries = listOf(DaySummaryDto(d, 120, 120, 1, 10, 60, 2L)),
                    // minutes 正常（60 ≤ 100）但 launches 超额（9 > 5）
                    appUsage = listOf(AppUsageDto(d, "com.x", "X", 60, 9, "OTHER"))
                ),
                SourceRebuild.SourceBackup(
                    deviceId = "dev-B",
                    daySummaries = listOf(DaySummaryDto(d, 120, 120, 1, 10, 60, 2L))
                )
            ),
            snapshotDir = snapDir
        )

        assertEquals("mismatch_skipped", report.days.single().status)
        assertEquals("整日不重建：原样保留", 5, db.appUsageDao().getAll().single().launches)
    }

    @Test
    fun `app launches 正常时独立差减`() = runBlocking {
        db.daySummaryDao().insert(dayRow(240, 240, 2, 20, 90, SourceIds.LEGACY))
        db.appUsageDao().insert(AppUsageEntity(d, "com.x", "X", 100, 5, "OTHER", SourceIds.LEGACY))

        SourceRebuild(db, zone).rebuild(
            targets = setOf(d),
            sources = listOf(
                SourceRebuild.SourceBackup(
                    deviceId = "dev-A",
                    daySummaries = listOf(DaySummaryDto(d, 120, 120, 1, 10, 60, 2L)),
                    appUsage = listOf(AppUsageDto(d, "com.x", "X", 60, 3, "OTHER"))
                ),
                SourceRebuild.SourceBackup(
                    deviceId = "dev-B",
                    daySummaries = listOf(DaySummaryDto(d, 120, 120, 1, 10, 60, 2L))
                )
            ),
            snapshotDir = snapDir
        )

        val apps = db.appUsageDao().getAll().filter { it.pkg == "com.x" }
        val legacy = apps.single { it.sourceDeviceId == SourceIds.LEGACY }
        assertEquals("minutes 差减", 40, legacy.minutes)
        assertEquals("launches 独立差减", 2, legacy.launches)
    }

    // ============ 新问题 ②：删 legacy 行不得丢 longestSessionMin（MAX）

    @Test
    fun `删 legacy 行不得丢历史最长会话 MAX 保留`() = runBlocking {
        // legacy 120min / MAX 120；备份 A、B 各 60 / MAX 60——SUM 可全拆但 MAX 拆不出
        db.daySummaryDao().insert(dayRow(120, 120, 2, 20, 120, SourceIds.LEGACY))

        val report = SourceRebuild(db, zone).rebuild(
            targets = setOf(d),
            sources = listOf(
                SourceRebuild.SourceBackup(
                    deviceId = "dev-A",
                    daySummaries = listOf(DaySummaryDto(d, 60, 60, 1, 10, 60, 2L))
                ),
                SourceRebuild.SourceBackup(
                    deviceId = "dev-B",
                    daySummaries = listOf(DaySummaryDto(d, 60, 60, 1, 10, 60, 2L))
                )
            ),
            snapshotDir = snapDir
        )

        val rows = db.daySummaryDao().getAll()
        assertEquals("重建后跨来源 MAX 仍是 120（历史最长会话不丢）", 120, rows.maxOf { it.longestSessionMin })
        val legacy = rows.single { it.sourceDeviceId == SourceIds.LEGACY }
        assertEquals("legacy 行保留剩余（只余 MAX）", 120, legacy.longestSessionMin)
        assertEquals(0, legacy.screenMin)
        assertEquals("SUM 不变", 120, rows.sumOf { it.screenMin })
        assertTrue(report.days.single().status.startsWith("rebuilt"))
    }

    // ============ 新问题 ③：清洗校验键 (date, sourceDeviceId)

    @Test
    fun `清洗校验按日期加来源复合键 A 校正 B 无证据不误炸`() = runBlocking {
        // A：超 24h + 有会话证据 → 校正；B：超 24h + 无会话 → 保留原值（>1440 豁免校验）
        db.daySummaryDao().insert(dayRow(1500, 1500, 0, 40, 700, "dev-A"))
        db.daySummaryDao().insert(dayRow(1600, 1600, 0, 40, 700, "dev-B"))
        db.screenSessionDao().insert(
            listOf(ScreenSessionEntity(msOf(d, 9), msOf(d, 10), ScreenSessionEntity.KIND_SCREEN, "dev-A"))
        )

        val report = SourceRebuild(db, zone).rebuildOversizedDays(snapDir)

        assertEquals("不回滚", false, report.rolledBack)
        assertEquals("A 校正为 60", 60, db.daySummaryDao().get(d, "dev-A")!!.screenMin)
        assertEquals("B 无证据保留原值", 1600, db.daySummaryDao().get(d, "dev-B")!!.screenMin)
    }

    // ============ 新问题 ④：归档失败上抛 → 恢复事务回滚

    @Test
    fun `restore 归档失败时 MERGE 与 OVERWRITE 都整体回滚原数据不变`() = runBlocking {
        val file = BackupFile(
            schemaVersion = 6,
            exportedAt = "2026-11-18 10:00",
            appVersion = "1.0",
            device = com.screentrace.app.data.backup.BackupDevice("dev-A", "甲", "X"),
            daySummary = listOf(DaySummaryDto(d, 999, 999, 9, 9, 99, 9L))
        )
        for (strategy in listOf(BackupRepository.Strategy.MERGE, BackupRepository.Strategy.OVERWRITE)) {
            val fresh = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                ScreenTraceDatabase::class.java
            ).allowMainThreadQueries().build()
            try {
                fresh.daySummaryDao().insert(dayRow(60, 60, 1, 1, 60, "dev-A"))
                val broken = BackupRepository(
                    db = fresh,
                    zone = zone,
                    sourceDeviceId = { throw IllegalStateException("归档故障注入") }
                )
                try {
                    broken.restore(file, strategy, importSettings = false)
                    fail("$strategy 归档失败必须上抛（不得静默吞掉带病恢复）")
                } catch (e: IllegalStateException) {
                    // 预期：上抛
                }
                val rows = fresh.daySummaryDao().getAll()
                assertEquals("$strategy 事务回滚：原数据不变", 1, rows.size)
                assertEquals("$strategy 事务回滚：没有部分写入", 60, rows.single().screenMin)
            } finally {
                fresh.close()
            }
        }
    }

    // ============ 缺口 G1/G2：IngestPipeline 跨批同毫秒

    @Test
    fun `同毫秒解锁跨批 1 条到 2 条保留 2 条 重放不增`() = runBlocking {
        val t = msOf(d, 9)
        fun batch(count: Int) = ParseResult(
            days = mapOf(LocalDate.parse(d) to DayMetrics(60, 60, 1, 1, 60)),
            hourMinutes = emptyMap(),
            appUsage = emptyList(),
            unlockEvents = List(count) { UnlockEvent(t) },
            notificationEvents = emptyList()
        )
        val pipeline = IngestPipeline(db, localDeviceId = { "dev-A" })
        assertTrue(pipeline.ingest(batch(1), "dump", t).ok)
        assertTrue(pipeline.ingest(batch(2), "dump", t + 60_000).ok)
        assertEquals("批 1 条 + 批 2 条（超集）→ 保留 2 条", 2, db.unlockEventDao().getAll().size)
        assertTrue(pipeline.ingest(batch(2), "dump", t + 120_000).ok)
        assertEquals("重放不增", 2, db.unlockEventDao().getAll().size)
    }

    @Test
    fun `同毫秒同内容通知从 1 条增至 2 条保留 2 条`() = runBlocking {
        val t = msOf(d, 9)
        fun batch(count: Int) = ParseResult(
            days = mapOf(LocalDate.parse(d) to DayMetrics(60, 60, 1, 1, 60)),
            hourMinutes = emptyMap(),
            appUsage = emptyList(),
            unlockEvents = emptyList(),
            notificationEvents = List(count) { NotificationEvent(t, NotificationType.INTERRUPTION, "com.a", "ch1") }
        )
        val pipeline = IngestPipeline(db, localDeviceId = { "dev-A" })
        assertTrue(pipeline.ingest(batch(1), "dump", t).ok)
        assertTrue(pipeline.ingest(batch(2), "dump", t + 60_000).ok)
        assertEquals("同内容双胞胎超集：保留 2 条", 2, db.notificationEventDao().getAll().size)
        assertTrue(pipeline.ingest(batch(2), "dump", t + 120_000).ok)
        assertEquals("重放不增", 2, db.notificationEventDao().getAll().size)
    }

    // ============ 缺口 G3：通知完整主键迁移（含 R2.1 正向场景）

    @Test
    fun `通知无冲突时按完整主键成功迁移给目标来源`() = runBlocking {
        val ms = msOf(d, 9)
        // legacy seq=0；dev-A 无对应；备份提供完整匹配的 (ms, type, pkg, seq=0) → 成功迁移
        db.notificationEventDao().insertIgnore(
            listOf(NotificationEventEntity(ms, "INTERRUPTION", "com.a", "ch", SourceIds.LEGACY, 0))
        )
        db.daySummaryDao().insert(dayRow(240, 240, 2, 20, 90, SourceIds.LEGACY))

        SourceRebuild(db, zone).rebuild(
            targets = setOf(d),
            sources = listOf(
                SourceRebuild.SourceBackup(
                    deviceId = "dev-A",
                    daySummaries = listOf(DaySummaryDto(d, 120, 120, 1, 10, 60, 2L)),
                    notificationEvent = listOf(
                        com.screentrace.app.data.backup.NotificationEventDto(ms, "INTERRUPTION", "com.a", "ch", 0)
                    )
                ),
                SourceRebuild.SourceBackup(
                    deviceId = "dev-B",
                    daySummaries = listOf(DaySummaryDto(d, 120, 120, 1, 10, 60, 2L))
                )
            ),
            snapshotDir = snapDir
        )

        val rows = db.notificationEventDao().getAll()
        assertTrue("无冲突：成功迁给 dev-A", rows.any { it.sourceDeviceId == "dev-A" && it.seq == 0 })
        assertTrue("legacy 原行已迁走（不再留在 legacy）",
            rows.none { it.sourceDeviceId == SourceIds.LEGACY && it.seq == 0 })
    }

    @Test
    fun `通知按完整主键改归属 seq 不匹配留 legacy 冲突保留 legacy`() = runBlocking {
        val ms = msOf(d, 9)
        db.notificationEventDao().insertIgnore(
            listOf(
                NotificationEventEntity(ms, "INTERRUPTION", "com.a", "ch", SourceIds.LEGACY, 0),
                NotificationEventEntity(ms, "INTERRUPTION", "com.a", "ch", SourceIds.LEGACY, 1)
            )
        )
        db.notificationEventDao().insertIgnore(
            listOf(NotificationEventEntity(ms, "INTERRUPTION", "com.a", "ch", "dev-A", 0))
        )
        db.daySummaryDao().insert(dayRow(240, 240, 2, 20, 90, SourceIds.LEGACY))

        SourceRebuild(db, zone).rebuild(
            targets = setOf(d),
            sources = listOf(
                SourceRebuild.SourceBackup(
                    deviceId = "dev-A",
                    daySummaries = listOf(DaySummaryDto(d, 120, 120, 1, 10, 60, 2L)),
                    notificationEvent = listOf(
                        com.screentrace.app.data.backup.NotificationEventDto(ms, "INTERRUPTION", "com.a", "ch", 0)
                    )
                ),
                SourceRebuild.SourceBackup(
                    deviceId = "dev-B",
                    daySummaries = listOf(DaySummaryDto(d, 120, 120, 1, 10, 60, 2L))
                )
            ),
            snapshotDir = snapDir
        )

        val rows = db.notificationEventDao().getAll()
        assertTrue("seq0 冲突：legacy 保留", rows.any { it.sourceDeviceId == SourceIds.LEGACY && it.seq == 0 })
        assertTrue("seq1 无实证：legacy 保留", rows.any { it.sourceDeviceId == SourceIds.LEGACY && it.seq == 1 })
        assertTrue("dev-A 原行不动", rows.any { it.sourceDeviceId == "dev-A" && it.seq == 0 })
    }

    // ============ 缺口 G4：六指标逐项超额跳过 + 正常前后 SUM 不变

    @Test
    fun `六指标逐项超额都整日跳过 正常重建前后全部 SUM 不变`() = runBlocking {
        // 六个指标各构造一次「该项超额」场景
        val overCases = listOf<Pair<String, List<DaySummaryDto>>>(
            "screenUnlockMin" to listOf(DaySummaryDto(d, 120, 250, 1, 10, 60, 2L)),
            "unlocks" to listOf(DaySummaryDto(d, 120, 120, 12, 10, 60, 2L)),
            "launches" to listOf(DaySummaryDto(d, 120, 120, 1, 30, 60, 2L)),
            "screenMin" to listOf(DaySummaryDto(d, 300, 120, 1, 10, 60, 2L))
        )
        for ((name, dayRows) in overCases) {
            val fresh = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                ScreenTraceDatabase::class.java
            ).allowMainThreadQueries().build()
            try {
                fresh.daySummaryDao().insert(
                    DaySummaryEntity(d, 240, 240, 2, 20, 90, 1L, SourceIds.LEGACY)
                )
                val report = SourceRebuild(fresh, zone).rebuild(
                    targets = setOf(d),
                    sources = listOf(
                        SourceRebuild.SourceBackup(deviceId = "dev-A", daySummaries = dayRows),
                        SourceRebuild.SourceBackup(
                            deviceId = "dev-B",
                            daySummaries = listOf(DaySummaryDto(d, 0, 0, 0, 0, 0, 2L))
                        )
                    ),
                    snapshotDir = snapDir
                )
                assertEquals("$name 超额必须整日跳过", "mismatch_skipped", report.days.single().status)
                assertEquals("$name 超额：原值保留", 240, fresh.daySummaryDao().getAll().single().screenMin)
            } finally {
                fresh.close()
            }
        }
        // app.minutes / app.launches / hour.minutes 超额
        val extraCases = listOf(
            Triple("app.minutes", AppUsageDto(d, "com.x", "X", 200, 1, "OTHER"), null),
            Triple("app.launches", AppUsageDto(d, "com.x", "X", 50, 9, "OTHER"), null),
            Triple("hour.minutes", null, HourUsageDto(d, 9, 300))
        )
        for ((name, app, hour) in extraCases) {
            val fresh = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                ScreenTraceDatabase::class.java
            ).allowMainThreadQueries().build()
            try {
                fresh.daySummaryDao().insert(DaySummaryEntity(d, 240, 240, 2, 20, 90, 1L, SourceIds.LEGACY))
                fresh.appUsageDao().insert(AppUsageEntity(d, "com.x", "X", 100, 5, "OTHER", SourceIds.LEGACY))
                fresh.hourUsageDao().insert(HourUsageEntity(d, 9, 30, SourceIds.LEGACY))
                val report = SourceRebuild(fresh, zone).rebuild(
                    targets = setOf(d),
                    sources = listOf(
                        SourceRebuild.SourceBackup(
                            deviceId = "dev-A",
                            daySummaries = listOf(DaySummaryDto(d, 120, 120, 1, 10, 60, 2L)),
                            appUsage = listOfNotNull(app),
                            hourUsage = listOfNotNull(hour)
                        ),
                        SourceRebuild.SourceBackup(
                            deviceId = "dev-B",
                            daySummaries = listOf(DaySummaryDto(d, 120, 120, 1, 10, 60, 2L))
                        )
                    ),
                    snapshotDir = snapDir
                )
                assertEquals("$name 超额必须整日跳过", "mismatch_skipped", report.days.single().status)
            } finally {
                fresh.close()
            }
        }

        // 正常重建：六项 SUM + MAX 前后不变（含 app launches 差减）
        db.daySummaryDao().insert(dayRow(240, 100, 10, 20, 120, SourceIds.LEGACY))
        db.appUsageDao().insert(AppUsageEntity(d, "com.x", "X", 100, 5, "OTHER", SourceIds.LEGACY))
        db.hourUsageDao().insert(HourUsageEntity(d, 9, 30, SourceIds.LEGACY))
        SourceRebuild(db, zone).rebuild(
            targets = setOf(d),
            sources = listOf(
                SourceRebuild.SourceBackup(
                    deviceId = "dev-A",
                    daySummaries = listOf(DaySummaryDto(d, 120, 50, 5, 10, 60, 2L)),
                    appUsage = listOf(AppUsageDto(d, "com.x", "X", 60, 3, "OTHER")),
                    hourUsage = listOf(HourUsageDto(d, 9, 10))
                ),
                SourceRebuild.SourceBackup(
                    deviceId = "dev-B",
                    daySummaries = listOf(DaySummaryDto(d, 120, 50, 5, 10, 60, 2L)),
                    appUsage = listOf(AppUsageDto(d, "com.x", "X", 40, 2, "OTHER")),
                    hourUsage = listOf(HourUsageDto(d, 9, 20))
                )
            ),
            snapshotDir = snapDir
        )
        val afterRows = db.daySummaryDao().getAll()
        assertEquals("screenMin 前后不变", 240, afterRows.sumOf { it.screenMin })
        assertEquals("screenUnlockMin 前后不变", 100, afterRows.sumOf { it.screenUnlockMin })
        assertEquals("unlocks 前后不变", 10, afterRows.sumOf { it.unlocks })
        assertEquals("launches 前后不变", 20, afterRows.sumOf { it.launches })
        assertEquals("MAX 前后不变", 120, afterRows.maxOf { it.longestSessionMin })
        val appRows = db.appUsageDao().getAll()
        assertEquals("app.minutes 前后不变", 100, appRows.sumOf { it.minutes })
        assertEquals("app.launches 前后不变", 5, appRows.sumOf { it.launches })
        assertEquals("hour.minutes 前后不变", 30, db.hourUsageDao().getAll().sumOf { it.minutes })
    }

    // ============ 缺口 G5：半开边界四例

    @Test
    fun `半开区间边界 事件在 lo 归入 会话接触边界不归入 空区间不占用`() = runBlocking {
        // ① 事件恰好位于 lo（d 0 点整）→ 归入 d
        // ② 会话恰好结束于 lo（endMs == d 0 点）→ 不与 d 相交
        // ③ 会话恰好开始于 hi（startMs == dNext 0 点）→ 不与 d 相交
        // ④ 空区间（startMs == endMs）→ 不占用任何日
        assertEquals(setOf(d), CrossDeviceMerge.datesOfSession(msOf(d, 23), msOf(dNext, 0), zone))
        assertTrue(CrossDeviceMerge.datesOfSession(msOf(d, 0), msOf(d, 0), zone).isEmpty())

        db.unlockEventDao().insertIgnore(listOf(UnlockEventEntity(msOf(d, 0), SourceIds.UNARCHIVED, 0)))
        db.screenSessionDao().insert(
            listOf(
                // 会话跨 d 全天（22:00 → dNext 0 点 = 结束于 **hi**）→ 归入 d
                ScreenSessionEntity(msOf(d, 22), msOf(dNext, 0), ScreenSessionEntity.KIND_SCREEN, SourceIds.UNARCHIVED),
                // 会话恰开始于 hi → 不归入 d
                ScreenSessionEntity(msOf(dNext, 0), msOf(dNext, 1), ScreenSessionEntity.KIND_SCREEN, SourceIds.UNARCHIVED)
            )
        )
        SourceArchive.archive(db, "dev-1", setOf(d), zone)

        val event = db.unlockEventDao().getAll().single()
        assertEquals("事件恰在 lo 归入 d", SourceIds.LEGACY, event.sourceDeviceId)
        val sessions = db.screenSessionDao().getAll()
        val endsAtHi = sessions.first { it.endMs == msOf(dNext, 0) }
        val startsAtHi = sessions.first { it.startMs == msOf(dNext, 0) }
        assertEquals("会话结束于 hi（占 d 全天）归入 d", SourceIds.LEGACY, endsAtHi.sourceDeviceId)
        assertEquals("会话恰开始于 hi 不归入 d", "dev-1", startsAtHi.sourceDeviceId)
    }

    @Test
    fun `会话恰结束于 lo 与次日窗口无交集不归入次日`() = runBlocking {
        // 会话 [d 23:00, dNext 00:00)——endMs == 次日窗口 lo：与 dNext 无交集
        // （半开 [lo,hi)：R2 用例曾把这种标成「结束于 lo」，实际那是结束于 hi；本例才是 endMs==lo）
        db.screenSessionDao().insert(
            listOf(ScreenSessionEntity(msOf(d, 23), msOf(dNext, 0), ScreenSessionEntity.KIND_SCREEN, SourceIds.UNARCHIVED))
        )
        SourceArchive.archive(db, "dev-1", setOf(dNext), zone)

        val session = db.screenSessionDao().getAll().single()
        assertEquals("endMs==lo 不归入次日", "dev-1", session.sourceDeviceId)
    }

    // ============ 缺口 G6：副本库归档真实恢复链路

    @Test
    fun `restore 归档真实链路 未归档行归属副本设备`() = runBlocking {
        // 副本库（模拟 screentrace-dev-X）：迁移遗留 '' 行
        val copy = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ScreenTraceDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            copy.daySummaryDao().insert(
                DaySummaryEntity(d, 60, 60, 1, 1, 60, 1L, SourceIds.UNARCHIVED)
            )
            val repo = BackupRepository(
                db = copy,
                zone = zone,
                sourceDeviceId = { "dev-X" }
            )
            repo.restore(
                BackupFile(
                    schemaVersion = 6,
                    exportedAt = "2026-11-18 10:00",
                    appVersion = "1.0",
                    device = com.screentrace.app.data.backup.BackupDevice("dev-X", "X", "X"),
                    daySummary = listOf(DaySummaryDto(dNext, 30, 30, 1, 1, 30, 2L))
                ),
                BackupRepository.Strategy.MERGE,
                importSettings = false
            )
            val rows = copy.daySummaryDao().getAll()
            assertEquals("未归档行归属副本设备（不是 '' 也不是别的）", "dev-X",
                rows.first { it.date == d }.sourceDeviceId)
            assertEquals("新恢复行同源", "dev-X", rows.first { it.date == dNext }.sourceDeviceId)
        } finally {
            copy.close()
        }
    }

    // ============ R2.1 ③：restore 真正「部分写入后」中途故障 → 整体回滚

    @Test
    fun `restore 写入中途故障时已写入部分也整体回滚`() = runBlocking {
        // SQLite Trigger 在 hour_usage 写入时注入失败：OVERWRITE 先删后写，
        // day_summary 已写入（999）→ hour 炸 → 事务必须回滚已写入的 day 行
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_hour BEFORE INSERT ON hour_usage BEGIN SELECT RAISE(ABORT, 'injected'); END"
        )
        db.daySummaryDao().insert(dayRow(60, 60, 1, 1, 60, "dev-A"))
        val repo = BackupRepository(db, zone = zone, sourceDeviceId = { "dev-A" })
        val file = BackupFile(
            schemaVersion = 6,
            exportedAt = "2026-11-18 10:00",
            appVersion = "1.0",
            device = com.screentrace.app.data.backup.BackupDevice("dev-A", "甲", "X"),
            daySummary = listOf(DaySummaryDto(d, 999, 999, 9, 9, 99, 9L)),
            hourUsage = listOf(HourUsageDto(d, 0, 5))
        )
        try {
            repo.restore(file, BackupRepository.Strategy.OVERWRITE, importSettings = false)
            fail("中途故障必须上抛")
        } catch (e: Exception) {
            // 预期：触发器 ABORT
        }
        val rows = db.daySummaryDao().getAll()
        assertEquals("已写入的 day 行也要回滚", 1, rows.size)
        assertEquals("原数据不变（不是 999）", 60, rows.single().screenMin)
    }

    // ============ R2.1 ④：afterRows 空集合（全零 legacy 无备份）不无谓回滚

    @Test
    fun `全零 legacy 无备份时删行后空集合 MAX 不炸 不回滚`() = runBlocking {
        db.daySummaryDao().insert(dayRow(0, 0, 0, 0, 0, SourceIds.LEGACY))

        val report = SourceRebuild(db, zone).rebuild(
            targets = setOf(d),
            sources = emptyList(),
            snapshotDir = snapDir
        )

        assertEquals("不回滚", false, report.rolledBack)
        assertEquals("全零行拆无可拆：删除", 0, db.daySummaryDao().getAll().size)
        assertEquals("rebuilt", report.days.single().status)
    }
}
