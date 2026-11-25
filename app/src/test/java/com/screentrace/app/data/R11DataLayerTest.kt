package com.screentrace.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.data.backup.BackupCodec
import com.screentrace.app.data.backup.BackupDevice
import com.screentrace.app.data.backup.BackupFile
import com.screentrace.app.data.backup.BackupRepository
import com.screentrace.app.data.backup.DaySummaryDto
import com.screentrace.app.data.db.CollectionStatusValues
import com.screentrace.app.data.db.DataQualityValues
import com.screentrace.app.data.db.DaySummaryEntity
import com.screentrace.app.data.db.EvidenceTypes
import com.screentrace.app.data.db.ScreenEvidenceEntity
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.db.SourceIds
import com.screentrace.app.data.parse.UsageDumpParser
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CR-30-R11 数据层集成与备份兼容测试：
 * 1. 解析 -> 入库全链路：跨日窗口入 `usage_window`、W1/E3 证据入 `screen_evidence` 且永不污染
 *    `screen_session` / 单日 `app_usage`，`pull_log.collectionStatus` 落库；
 * 2. 备份 v7 往返保真质量列、`screen_evidence`、`usage_window`，且恢复证据表不产生会话；
 * 3. 旧版 v6 备份缺质量字段时解码为 `null`（UNKNOWN），绝不补成 `EXACT`；legacy 只读不改质量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class R11DataLayerTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private lateinit var db: ScreenTraceDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ScreenTraceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `跨日 checkin 与 W1 证据入库后严格隔离 不污染 app_usage 与 screen_session`() = runBlocking {
        val d22 = LocalDate.of(2026, 11, 22)
        val d23 = LocalDate.of(2026, 11, 23)
        val begin = d22.atTime(10, 31).atZone(zone).toInstant().toEpochMilli()
        val end = d23.atTime(10, 31).atZone(zone).toInstant().toEpochMilli()
        val checkin = buildString {
            appendLine("user=0")
            appendLine("  beginTime=$begin endTime=$end ")
            appendLine("    screen-interactive: 1x for 86400000")
            appendLine("      package=com.screentrace.app totalTimeUsed=86400000 lastTimeUsed=$end appLaunchCount=2")
        }

        val parsed = UsageDumpParser.parse(checkin, "", zone, null)
        val pull = IngestPipeline(db).ingest(parsed, source = "shizuku", pulledAtMs = end)
        assertTrue(pull.ok)

        // 跨日应用聚合在 usage_window，不在单日 app_usage
        assertTrue(db.appUsageDao().getAll().isEmpty())
        val windows = db.usageWindowDao().getAll()
        assertEquals(1, windows.size)
        assertEquals(1440, windows.single().minutes)

        // W1 在 screen_evidence，不在 screen_session
        assertTrue(db.screenSessionDao().getAll().isEmpty())
        val evidences = db.screenEvidenceDao().getAll()
        assertEquals(1, evidences.size)
        assertEquals(EvidenceTypes.WINDOW, evidences.single().evidenceType)
        assertEquals(86_400_000L, evidences.single().measuredMs)

        // pull_log 记录 collectionStatus
        val log = db.pullLogDao().getAll().single()
        assertEquals(CollectionStatusValues.PARTIAL, log.collectionStatus)
    }

    @Test
    fun `备份 v7 往返保真质量标记与独立表 且 v6 旧备份保持 null(UNKNOWN)`() = runBlocking {
        val devId = "dev-r11"
        val repo = BackupRepository(
            db = db,
            zone = zone,
            device = { com.screentrace.app.data.prefs.DeviceInfo(devId, "主力机", "Redmi") },
            sourceDeviceId = { devId }
        )

        db.daySummaryDao().insert(
            DaySummaryEntity("2026-11-23", 120, 100, 5, 10, 40, 1_700_000_000L, devId, DataQualityValues.EXACT)
        )
        db.daySummaryDao().insert(
            DaySummaryEntity("2026-11-20", 300, 280, 12, 20, 60, 1_699_800_000L, SourceIds.LEGACY, null)
        )
        db.screenEvidenceDao().insertIgnore(
            listOf(
                ScreenEvidenceEntity(
                    startMs = 1_700_000_000_000L,
                    endMs = null,
                    kind = "SCREEN",
                    evidenceType = EvidenceTypes.ANCHOR,
                    quality = DataQualityValues.ANCHOR_ONLY,
                    measuredMs = null,
                    sourceDumpAt = 1_700_000_000_500L,
                    sourceDeviceId = devId
                )
            )
        )

        val encoded = BackupCodec.encode(repo.buildFile("0.1.0"))
        val decoded = BackupCodec.decode(encoded) as BackupCodec.DecodeResult.Ok
        assertEquals(7, decoded.file.schemaVersion)
        assertEquals(1, decoded.summary.evidenceRows)

        db.daySummaryDao().deleteAll()
        db.screenEvidenceDao().deleteAll()
        repo.restore(decoded.file, BackupRepository.Strategy.OVERWRITE, importSettings = false)

        val restoredDev = db.daySummaryDao().get("2026-11-23", devId)!!
        assertEquals("dev day 必须保真 EXACT 质量", DataQualityValues.EXACT, restoredDev.screenQuality)
        val restoredLegacy = db.daySummaryDao().get("2026-11-20", SourceIds.LEGACY)!!
        assertNull("legacy 行质量必须保持 null(UNKNOWN)，不得升成 EXACT", restoredLegacy.screenQuality)
        assertEquals(1, db.screenEvidenceDao().count())
        assertEquals("恢复证据不得写入 screen_session", 0, db.screenSessionDao().count())

        // v6 旧备份兼容性：无质量字段 -> null (UNKNOWN)
        val v6Json = BackupCodec.encode(
            BackupFile(
                schemaVersion = 6,
                exportedAt = "2026-11-18 10:00",
                appVersion = "0.1.0",
                device = BackupDevice(devId, "旧版", "Redmi"),
                daySummary = listOf(DaySummaryDto("2026-11-18", 200, 180, 10, 20, 50, 1_699_000_000L))
            )
        )
        val v6Ok = BackupCodec.decode(v6Json) as BackupCodec.DecodeResult.Ok
        repo.restore(v6Ok.file, BackupRepository.Strategy.OVERWRITE, importSettings = false)
        assertNull(db.daySummaryDao().get("2026-11-18", devId)?.screenQuality)
    }
}
