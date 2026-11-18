package com.screentrace.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.data.backup.DaySummaryDto
import com.screentrace.app.data.backup.ScreenSessionDto
import com.screentrace.app.data.backup.UnlockEventDto
import com.screentrace.app.data.db.DaySummaryEntity
import com.screentrace.app.data.db.ScreenSessionEntity
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.db.SourceIds
import com.screentrace.app.data.db.UnlockEventEntity
import java.io.File
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
 * CR-28 批次 D / 测试矩阵 T6：历史重建来源（快照 → 差减拆分 → 核对 → 回滚）。
 *
 * - 完整备份 → legacy 行删、分量各就位、SUM 不变；
 * - 缺来源 → 只写实证分量、legacy 留剩余、SUM 不变；
 * - Σ分量 > legacy 判「对不上」→ 整日不重建；
 * - 事件/会话同键改归属、不一致留 legacy；
 * - 核对不过（重复来源注入）→ 整体回滚 + rebuild_log 记 rolled_back；
 * - recover 按快照恢复原样。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SourceRebuildTest {

    private lateinit var db: ScreenTraceDatabase
    private val zone: ZoneId = ZoneId.systemDefault()
    private val d = "2026-11-10"
    private lateinit var snapDir: File

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ScreenTraceDatabase::class.java
        ).allowMainThreadQueries().build()
        snapDir = File(System.getProperty("java.io.tmpdir"), "cr28-rebuild-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
        snapDir.deleteRecursively()
    }

    private fun msOf(hour: Int): Long =
        java.time.LocalDate.parse(d).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun legacyDay(screen: Int) = DaySummaryEntity(d, screen, screen, 10, 20, 90, 1L, SourceIds.LEGACY)

    private fun backup(id: String, screen: Int) = SourceRebuild.SourceBackup(
        deviceId = id,
        daySummaries = listOf(DaySummaryDto(d, screen, screen, 5, 10, 60, 2L))
    )

    private suspend fun sumAll(): Int =
        db.daySummaryDao().getAll().filter { it.date == d }.sumOf { it.screenMin }

    // --------------------------------------------------------------- 完整拆分

    @Test
    fun `完整备份拆分后 legacy 行删除 分量各就位 SUM 不变`() = runBlocking {
        db.daySummaryDao().insert(legacyDay(240))

        val report = SourceRebuild(db, zone).rebuild(
            targets = setOf(d),
            sources = listOf(backup("dev-A", 120), backup("dev-B", 120)),
            snapshotDir = snapDir
        )

        val rebuilt = report.days.single()
        assertEquals("rebuilt", rebuilt.status)
        assertEquals(0, rebuilt.legacyRemainingMin)
        assertEquals("SUM 不变", 240, sumAll())
        assertEquals("legacy 行已删除", null, db.daySummaryDao().get(d, SourceIds.LEGACY))
        assertEquals(120, db.daySummaryDao().get(d, "dev-A")!!.screenMin)
        assertEquals(120, db.daySummaryDao().get(d, "dev-B")!!.screenMin)
        assertEquals("rebuild_log 记一行", 1, db.rebuildLogDao().count())
        assertTrue(File(report.snapshotPath).exists())
    }

    // --------------------------------------------------------------- 缺来源

    @Test
    fun `缺来源时只写实证分量 legacy 留剩余 SUM 不变`() = runBlocking {
        db.daySummaryDao().insert(legacyDay(240))

        val report = SourceRebuild(db, zone).rebuild(
            targets = setOf(d),
            sources = listOf(backup("dev-A", 120)),
            snapshotDir = snapDir
        )

        val rebuilt = report.days.single()
        assertEquals("rebuilt_with_remainder", rebuilt.status)
        assertEquals(120, rebuilt.legacyRemainingMin)
        assertEquals("SUM 不变（120 分量 + 120 剩余）", 240, sumAll())
        assertEquals(120, db.daySummaryDao().get(d, SourceIds.LEGACY)!!.screenMin)
        assertEquals(120, db.daySummaryDao().get(d, "dev-A")!!.screenMin)
    }

    // --------------------------------------------------------------- 对不上

    @Test
    fun `备份分量超过 legacy 时整日不重建 原样保留`() = runBlocking {
        db.daySummaryDao().insert(legacyDay(120))

        val report = SourceRebuild(db, zone).rebuild(
            targets = setOf(d),
            sources = listOf(backup("dev-A", 300)),
            snapshotDir = snapDir
        )

        assertEquals("mismatch_skipped", report.days.single().status)
        assertEquals("不猜不丢：legacy 原值保留", 120, db.daySummaryDao().get(d, SourceIds.LEGACY)!!.screenMin)
        assertEquals("不写任何分量", 1, db.daySummaryDao().getAll().size)
        assertEquals(120, sumAll())
    }

    // --------------------------------------------------------------- 同键改归属

    @Test
    fun `事件会话同键改归属 不一致的一律留 legacy`() = runBlocking {
        // 两条解锁：一条与备份同键、一条不同；会话同理
        db.unlockEventDao().insertIgnore(
            listOf(
                UnlockEventEntity(msOf(9), SourceIds.LEGACY, 0),   // 与备份同键 → 改归属
                UnlockEventEntity(msOf(10), SourceIds.LEGACY, 0)   // 备份没有 → 留 legacy
            )
        )
        db.screenSessionDao().insert(
            listOf(
                ScreenSessionEntity(msOf(8), msOf(9), ScreenSessionEntity.KIND_SCREEN, SourceIds.LEGACY), // 同键 → 改归属
                ScreenSessionEntity(msOf(14), msOf(15), ScreenSessionEntity.KIND_SCREEN, SourceIds.LEGACY) // 不同 → 留
            )
        )
        db.daySummaryDao().insert(legacyDay(240))

        SourceRebuild(db, zone).rebuild(
            targets = setOf(d),
            sources = listOf(
                SourceRebuild.SourceBackup(
                    deviceId = "dev-A",
                    daySummaries = listOf(DaySummaryDto(d, 120, 120, 5, 10, 60, 2L)),
                    unlockEvent = listOf(UnlockEventDto(msOf(9), 0)),
                    screenSession = listOf(ScreenSessionDto(msOf(8), msOf(9), "SCREEN"))
                ),
                backup("dev-B", 120)
            ),
            snapshotDir = snapDir
        )

        val unlockBySource = db.unlockEventDao().getAll().associate { it.occurredAtMs to it.sourceDeviceId }
        assertEquals("同键解锁改归属 A", "dev-A", unlockBySource[msOf(9)])
        assertEquals("不同键解锁留 legacy", SourceIds.LEGACY, unlockBySource[msOf(10)])

        val sessions = db.screenSessionDao().getAll().associate { it.startMs to it.sourceDeviceId }
        assertEquals("同键会话改归属 A", "dev-A", sessions[msOf(8)])
        assertEquals("不同键会话留 legacy", SourceIds.LEGACY, sessions[msOf(14)])
    }

    // --------------------------------------------------------------- 回滚

    @Test
    fun `核对不过时整体回滚 rebuild_log 记 rolled_back`() = runBlocking {
        db.daySummaryDao().insert(legacyDay(240))
        // 预置 dev-A 分量 150（大于备份 120）：取大写入 150+120=270 与差减口径（Σ=240）矛盾
        // → 单日 SUM 硬断言必炸 → 整体回滚（这正是核对兜底的价值）
        db.daySummaryDao().insert(DaySummaryEntity(d, 150, 150, 1, 1, 50, 3L, "dev-A"))

        val report = SourceRebuild(db, zone).rebuild(
            targets = setOf(d),
            sources = listOf(backup("dev-A", 120), backup("dev-B", 120)),
            snapshotDir = snapDir
        )

        assertTrue(report.rolledBack)
        assertEquals("事务回滚：legacy 原值回来", 240, db.daySummaryDao().get(d, SourceIds.LEGACY)!!.screenMin)
        assertEquals("事务回滚：dev-A 原值回来", 150, db.daySummaryDao().get(d, "dev-A")!!.screenMin)
        assertEquals(2, db.daySummaryDao().getAll().size)
        val log = db.rebuildLogDao().getAll().single()
        assertTrue("rebuild_log 记 rolled_back", log.rolledBack)
    }

    // --------------------------------------------------------------- 超 24h 脏行清洗（批次 F）

    @Test
    fun `超 24h 脏行按会话并集校正 launches 保留 unlocks 按事件计数`() = runBlocking {
        // 脏行 1530 分钟（旧累加撑爆）；当日实证：会话 9:00-10:00（60min）+ 2 条解锁事件
        db.daySummaryDao().insert(DaySummaryEntity(d, 1530, 1530, 0, 40, 765, 1L, "dev-A"))
        db.screenSessionDao().insert(
            listOf(ScreenSessionEntity(msOf(9), msOf(10), ScreenSessionEntity.KIND_SCREEN, "dev-A"))
        )
        db.unlockEventDao().insertIgnore(
            listOf(
                UnlockEventEntity(msOf(9) + 1000, "dev-A", 0),
                UnlockEventEntity(msOf(9) + 2000, "dev-A", 1)
            )
        )

        val report = SourceRebuild(db, zone).rebuildOversizedDays(snapDir)

        val fixed = db.daySummaryDao().get(d, "dev-A")!!
        assertEquals("screenMin 校正为会话并集 60", 60, fixed.screenMin)
        assertEquals(60, fixed.screenUnlockMin)
        assertEquals("unlocks 按事件计数校正", 2, fixed.unlocks)
        assertEquals("launches 无实证保留原值", 40, fixed.launches)
        assertEquals("longest 收紧为重算值", 60, fixed.longestSessionMin)
        assertEquals("oversized_corrected", report.days.single().status)
        assertEquals("rebuild_log 留痕", 1, db.rebuildLogDao().count())
        assertTrue(File(report.snapshotPath).exists())
    }

    @Test
    fun `无脏行时零改动不写 rebuild_log`() = runBlocking {
        db.daySummaryDao().insert(DaySummaryEntity(d, 60, 60, 1, 1, 60, 1L, "dev-A"))

        val report = SourceRebuild(db, zone).rebuildOversizedDays(snapDir)

        assertTrue(report.days.isEmpty())
        assertEquals(0, db.rebuildLogDao().count())
        assertEquals("原值不动", 60, db.daySummaryDao().get(d, "dev-A")!!.screenMin)
    }

    @Test
    fun `recover 按快照恢复重建前原样`() = runBlocking {
        db.daySummaryDao().insert(legacyDay(240))
        db.unlockEventDao().insertIgnore(listOf(UnlockEventEntity(msOf(9), SourceIds.LEGACY, 0)))
        val rebuild = SourceRebuild(db, zone)

        val report = rebuild.rebuild(
            targets = setOf(d),
            sources = listOf(backup("dev-A", 120), backup("dev-B", 120)),
            snapshotDir = snapDir
        )
        assertEquals("先重建出新形态", null, db.daySummaryDao().get(d, SourceIds.LEGACY))

        val snapshot = rebuild.recover(
            SourceRebuild.Snapshot.serializer().let {
                kotlinx.serialization.json.Json.decodeFromString(it, File(report.snapshotPath).readText(Charsets.UTF_8))
            }
        )
        assertEquals("恢复两行（日 + 解锁）", 2, snapshot)
        assertEquals("legacy 原值回来", 240, db.daySummaryDao().get(d, SourceIds.LEGACY)!!.screenMin)
        assertEquals("分量行已清", 1, db.daySummaryDao().getAll().size)
        assertEquals(SourceIds.LEGACY, db.unlockEventDao().getAll().single().sourceDeviceId)
    }
}
