package com.screentrace.app.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.data.db.AppLimitEntity
import com.screentrace.app.data.db.AppUsageEntity
import com.screentrace.app.data.db.DaySummaryEntity
import com.screentrace.app.data.db.HourUsageEntity
import com.screentrace.app.data.db.PullLogEntity
import com.screentrace.app.data.db.ScreenSessionEntity
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.db.UnlockEventEntity
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
 * §7-13：导出→恢复的往返必须**不丢行、不多行**（真 SQLite，Robolectric）。
 *
 * 纯函数单测只能证明「合并规则对」，证明不了「八张表都真的写回去了」——
 * 少接一张表的 DAO 在纯函数测试里完全看不出来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupRepositoryTest {

    private lateinit var db: ScreenTraceDatabase
    private lateinit var repo: BackupRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ScreenTraceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = BackupRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private fun seed() = runBlocking {
        db.daySummaryDao().insert(
            DaySummaryEntity("2026-11-10", 400, 360, 40, 60, 90, 1_700_000_000_000)
        )
        db.daySummaryDao().insert(
            DaySummaryEntity("2026-11-11", 200, 180, 20, 30, 45, 1_700_000_100_000)
        )
        db.appUsageDao().insert(
            AppUsageEntity("2026-11-10", "com.a", "应用A", 120, 5, "SOCIAL")
        )
        db.hourUsageDao().insert(HourUsageEntity("2026-11-10", 9, 30))
        db.pullLogDao().insert(
            PullLogEntity(pulledAt = 1_700_000_000_000, source = "shizuku", ok = true, note = null)
        )
        db.unlockEventDao().insertIgnore(listOf(UnlockEventEntity(1_700_000_000_000)))
        db.screenSessionDao().insert(
            listOf(
                ScreenSessionEntity(1_700_000_000_000, 1_700_000_600_000, ScreenSessionEntity.KIND_SCREEN)
            )
        )
        db.appLimitDao().upsert(AppLimitEntity("com.a", 60, true))
    }

    @Test
    fun `导出包含八张表的全部行`() = runBlocking {
        seed()
        val file = repo.buildFile("0.1.0")
        assertEquals(BackupCodec.SCHEMA_VERSION, file.schemaVersion)
        assertEquals(2, file.daySummary.size)
        assertEquals(1, file.appUsage.size)
        assertEquals(1, file.hourUsage.size)
        assertEquals(1, file.pullLog.size)
        assertEquals(1, file.unlockEvent.size)
        assertEquals(1, file.screenSession.size)
        assertEquals(1, file.appLimit.size)
    }

    @Test
    fun `JSON 往返后覆盖恢复 行数一致`() = runBlocking {
        seed()
        val encoded = BackupCodec.encode(repo.buildFile("0.1.0"))
        val decoded = BackupCodec.decode(encoded)
        assertTrue(decoded is BackupCodec.DecodeResult.Ok)
        val file = (decoded as BackupCodec.DecodeResult.Ok).file

        // 清空本地再恢复，模拟「换机导入」
        db.daySummaryDao().deleteAll()
        db.appUsageDao().deleteAll()
        db.appLimitDao().deleteAll()

        repo.restore(file, BackupRepository.Strategy.OVERWRITE, importSettings = false)

        assertEquals(2, db.daySummaryDao().getAll().size)
        assertEquals(1, db.appUsageDao().getAll().size)
        assertEquals(1, db.hourUsageDao().getAll().size)
        assertEquals(1, db.unlockEventDao().getAll().size)
        assertEquals(1, db.screenSessionDao().getAll().size)
        assertEquals(1, db.appLimitDao().getAll().size)
        assertEquals(
            360,
            db.daySummaryDao().get("2026-11-10")?.screenUnlockMin
        )
    }

    @Test
    fun `合并时本地更大的日数据不会被备份覆盖`() = runBlocking {
        seed()
        val backup = repo.buildFile("0.1.0").let {
            // 备份里同一天的值更小
            it.copy(
                daySummary = it.daySummary.map { row ->
                    if (row.date == "2026-11-10") row.copy(screenUnlockMin = 100) else row
                }
            )
        }
        repo.restore(backup, BackupRepository.Strategy.MERGE, importSettings = false)
        assertEquals(360, db.daySummaryDao().get("2026-11-10")?.screenUnlockMin)
    }

    @Test
    fun `覆盖恢复后本地只保留备份内容`() = runBlocking {
        seed()
        // 本地多一条备份里没有的日期
        db.daySummaryDao().insert(
            DaySummaryEntity("2026-11-12", 100, 90, 10, 10, 20, 1_700_000_200_000)
        )
        val backup = repo.buildFile("0.1.0").let { file ->
            file.copy(daySummary = file.daySummary.filter { it.date != "2026-11-12" })
        }
        repo.restore(backup, BackupRepository.Strategy.OVERWRITE, importSettings = false)
        assertEquals(null, db.daySummaryDao().get("2026-11-12"))
        assertEquals(2, db.daySummaryDao().getAll().size)
    }
}
