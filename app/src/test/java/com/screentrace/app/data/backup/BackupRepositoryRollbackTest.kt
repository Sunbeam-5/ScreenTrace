package com.screentrace.app.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.data.db.AppUsageEntity
import com.screentrace.app.data.db.DaySummaryEntity
import com.screentrace.app.data.db.HourUsageEntity
import com.screentrace.app.data.db.NotificationEventEntity
import com.screentrace.app.data.db.PullLogEntity
import com.screentrace.app.data.db.ScreenEvidenceEntity
import com.screentrace.app.data.db.ScreenSessionEntity
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.db.SourceIds
import com.screentrace.app.data.db.UnlockEventEntity
import com.screentrace.app.data.db.UsageWindowEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CR-30 阶段 E（§7-67）：`BackupRepository` `MERGE` 模式中途失败的事务原子回滚边界测试。
 *
 * 含行为变异锚点 M-E3：若移除 `BackupRepository.restore()` 的 `db.withTransaction`，
 * 在写 `hour_usage` 或 `pull_log` 触发 SQLite 异常后，前序写入的 `day_summary` / `app_usage` /
 * `SourceArchive` 归档行将残留脏数据，本套用例必变红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupRepositoryRollbackTest {

    private lateinit var db: ScreenTraceDatabase
    private lateinit var repo: BackupRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ScreenTraceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = BackupRepository(db, sourceDeviceId = { "dev-local" })
    }

    @After
    fun tearDown() = db.close()

    private fun seedLocalBaseline() = runBlocking {
        // 含一条未归档（""）行以验证 SourceArchive.archive 同样随事务整体回滚
        db.daySummaryDao().insert(
            DaySummaryEntity("2026-11-10", 120, 100, 10, 15, 25, 1_700_000_000_000, sourceDeviceId = "")
        )
        db.appUsageDao().insert(
            AppUsageEntity("2026-11-10", "com.local", "本地应用", 40, 2, "TOOLS", sourceDeviceId = "dev-local")
        )
        db.hourUsageDao().insert(
            HourUsageEntity("2026-11-10", 9, 20, sourceDeviceId = "dev-local")
        )
        db.unlockEventDao().insertIgnore(
            listOf(UnlockEventEntity(1_700_000_000_000, sourceDeviceId = "dev-local", seq = 0))
        )
        db.notificationEventDao().insertIgnore(
            listOf(NotificationEventEntity(1_700_000_000_100, "INTERRUPTION", "com.local", null, "dev-local", 0))
        )
        db.screenSessionDao().insert(
            listOf(ScreenSessionEntity(1_700_000_000_000, 1_700_000_600_000, ScreenSessionEntity.KIND_SCREEN, "dev-local"))
        )
        db.screenEvidenceDao().insertIgnore(
            listOf(
                ScreenEvidenceEntity(
                    startMs = 1_700_000_000_000,
                    endMs = 1_700_000_600_000,
                    kind = ScreenSessionEntity.KIND_SCREEN,
                    evidenceType = "EXACT",
                    quality = "EXACT",
                    measuredMs = 600_000,
                    sourceDumpAt = 1_700_000_700_000,
                    sourceDeviceId = "dev-local"
                )
            )
        )
        db.usageWindowDao().insert(
            listOf(
                UsageWindowEntity(
                    startMs = 1_700_000_000_000,
                    endMs = 1_700_003_600_000,
                    pkg = "com.local",
                    name = "本地应用",
                    minutes = 40,
                    launches = 2,
                    category = "TOOLS",
                    sourceDeviceId = "dev-local"
                )
            )
        )
        db.pullLogDao().insert(
            PullLogEntity(pulledAt = 1_700_000_000_000, source = "shizuku", ok = true, note = "baseline")
        )
    }

    private fun incomingBackup(): BackupFile = BackupFile(
        schemaVersion = BackupCodec.SCHEMA_VERSION,
        exportedAt = "2026-11-25 12:00",
        appVersion = "0.1.0",
        device = BackupDevice("dev-local", "本机", "Redmi"),
        daySummary = listOf(
            DaySummaryDto("2026-11-10", 300, 280, 30, 40, 60, 1_700_100_000_000),
            DaySummaryDto("2026-11-11", 180, 160, 15, 20, 35, 1_700_100_000_000)
        ),
        appUsage = listOf(
            AppUsageDto("2026-11-10", "com.local", "本地应用", 90, 5, "TOOLS"),
            AppUsageDto("2026-11-11", "com.new", "新应用", 50, 3, "SOCIAL")
        ),
        hourUsage = listOf(
            HourUsageDto("2026-11-11", 10, 45)
        ),
        unlockEvent = listOf(UnlockEventDto(1_700_100_000_000, seq = 0)),
        notificationEvent = listOf(NotificationEventDto(1_700_100_000_200, "SEEN", "com.new", null, seq = 0)),
        screenSession = listOf(ScreenSessionDto(1_700_100_000_000, 1_700_103_600_000, "SCREEN")),
        pullLog = listOf(PullLogDto(1_700_100_000_000, "shizuku", true, "incoming"))
    )

    @Test
    fun `M_E3_MERGE中途写hour_usage失败时全库九表与归档完整回滚`() = runBlocking {
        seedLocalBaseline()

        // 在事务中途（day_summary 与 app_usage 均已写入后，写 hour_usage 时）注入 SQLite ABORT
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TEMP TRIGGER fail_mid_merge_hour
            BEFORE INSERT ON hour_usage
            BEGIN
                SELECT RAISE(ABORT, 'injected mid-merge hour_usage failure');
            END;
            """.trimIndent()
        )

        var thrown: Throwable? = null
        try {
            repo.restore(incomingBackup(), BackupRepository.Strategy.MERGE, importSettings = false)
        } catch (t: Throwable) {
            thrown = t
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fail_mid_merge_hour")
        }

        assertNotNull("MERGE 中途失败必须向上抛异常而非静默吞掉", thrown)

        // 校验 9 张表全部回滚到恢复前精确基线
        val days = db.daySummaryDao().getAll()
        assertEquals(1, days.size)
        assertEquals(100, days.first().screenUnlockMin)
        assertEquals("SourceArchive 对未归档行的改动也必须随事务回滚", "", days.first().sourceDeviceId)

        val apps = db.appUsageDao().getAll()
        assertEquals(1, apps.size)
        assertEquals("com.local", apps.first().pkg)
        assertEquals(40, apps.first().minutes)

        assertEquals(1, db.hourUsageDao().getAll().size)
        assertEquals(1, db.unlockEventDao().getAll().size)
        assertEquals(1, db.notificationEventDao().getAll().size)
        assertEquals(1, db.screenSessionDao().getAll().size)
        assertEquals(1, db.screenEvidenceDao().getAll().size)
        assertEquals(1, db.usageWindowDao().getAll().size)
        assertEquals(1, db.pullLogDao().getAll().size)
        assertEquals("baseline", db.pullLogDao().getAll().first().note)
    }

    @Test
    fun `M_E3_MERGE写到最后一张表pull_log失败时前序会话并集与统计全回滚`() = runBlocking {
        seedLocalBaseline()

        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TEMP TRIGGER fail_mid_merge_pull_log
            BEFORE INSERT ON pull_log
            BEGIN
                SELECT RAISE(ABORT, 'injected mid-merge pull_log failure');
            END;
            """.trimIndent()
        )

        var thrown: Throwable? = null
        try {
            repo.restore(incomingBackup(), BackupRepository.Strategy.MERGE, importSettings = false)
        } catch (t: Throwable) {
            thrown = t
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fail_mid_merge_pull_log")
        }

        assertNotNull(thrown)
        assertEquals(1, db.daySummaryDao().getAll().size)
        assertEquals(100, db.daySummaryDao().getAll().first().screenUnlockMin)
        assertEquals(1, db.screenSessionDao().getAll().size)
        assertEquals(1, db.unlockEventDao().getAll().size)
        assertEquals(1, db.pullLogDao().getAll().size)
        assertEquals(SourceIds.UNSPECIFIED.isNotEmpty(), true)
    }
}
