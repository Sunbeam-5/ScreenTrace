package com.screentrace.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.contract.DayQuality
import com.screentrace.app.data.backup.BackupFile
import com.screentrace.app.data.backup.BackupMerge
import com.screentrace.app.data.backup.BackupDevice
import com.screentrace.app.data.backup.DaySummaryDto
import com.screentrace.app.data.backup.SameDeviceFingerprint
import com.screentrace.app.data.backup.UnlockEventDto
import com.screentrace.app.data.db.DataQualityValues
import com.screentrace.app.data.db.DaySummaryEntity
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.db.SourceIds
import com.screentrace.app.data.parse.AppUsageRow
import com.screentrace.app.data.parse.DayMetrics
import com.screentrace.app.data.parse.ParseResult
import com.screentrace.app.data.parse.SessionSpan
import java.io.File
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
 * D-07 方案 C+ 第一批（C+a 数据层闭环）真实 Room 内存库测试（覆盖 M-CA1 ~ M-CA5）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class D07DataLayerTest {

    private lateinit var db: ScreenTraceDatabase
    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")

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

    private fun dayRow(date: String, src: String, minutes: Int, quality: String?): DaySummaryEntity =
        DaySummaryEntity(
            date = date,
            screenMin = minutes,
            screenUnlockMin = minutes,
            unlocks = 5,
            launches = 10,
            longestSessionMin = minutes,
            pulledAt = 1_000L,
            sourceDeviceId = src,
            screenQuality = quality
        )

    @Test
    fun sqlDayQuality_coversAll12CrossSourceStatesAndOrderIndependence() = runBlocking {
        // M-CA1：验证 4a(EXACT+设备NULL -> LEGACY)、4b(EXACT+__legacy__ -> UNKNOWN)、
        // 7a(设备NULL+设备NULL -> LEGACY)、7b(设备NULL+__legacy__ -> UNKNOWN)、未知串 'FOO' -> UNKNOWN
        val cases = listOf(
            "2026-11-01" to listOf(dayRow("2026-11-01", "dev-a", 120, DataQualityValues.EXACT), dayRow("2026-11-01", "dev-b", 60, DataQualityValues.EXACT)) to "EXACT",
            "2026-11-02" to listOf(dayRow("2026-11-02", "dev-a", 120, DataQualityValues.EXACT), dayRow("2026-11-02", "dev-b", 60, DataQualityValues.PARTIAL)) to "PARTIAL",
            "2026-11-03" to listOf(dayRow("2026-11-03", "dev-a", 120, DataQualityValues.EXACT), dayRow("2026-11-03", "dev-b", 60, DataQualityValues.UNKNOWN)) to "UNKNOWN",
            "2026-11-04" to listOf(dayRow("2026-11-04", "dev-a", 120, DataQualityValues.EXACT), dayRow("2026-11-04", "dev-b", 60, null)) to "LEGACY",
            "2026-11-05" to listOf(dayRow("2026-11-05", "dev-a", 120, DataQualityValues.EXACT), dayRow("2026-11-05", SourceIds.LEGACY, 60, null)) to "UNKNOWN",
            "2026-11-06" to listOf(dayRow("2026-11-06", "dev-a", 120, DataQualityValues.PARTIAL), dayRow("2026-11-06", "dev-b", 60, null)) to "PARTIAL",
            "2026-11-07" to listOf(dayRow("2026-11-07", "dev-a", 120, DataQualityValues.UNKNOWN), dayRow("2026-11-07", "dev-b", 60, null)) to "UNKNOWN",
            "2026-11-08" to listOf(dayRow("2026-11-08", "dev-a", 120, null), dayRow("2026-11-08", "dev-b", 60, null)) to "LEGACY",
            "2026-11-09" to listOf(dayRow("2026-11-09", "dev-a", 120, null), dayRow("2026-11-09", SourceIds.LEGACY, 60, null)) to "UNKNOWN",
            "2026-11-10" to listOf(dayRow("2026-11-10", "dev-a", 120, DataQualityValues.PARTIAL), dayRow("2026-11-10", "dev-b", 60, DataQualityValues.UNKNOWN)) to "PARTIAL",
            "2026-11-11" to listOf(dayRow("2026-11-11", SourceIds.LEGACY, 90, null)) to "LEGACY",
            "2026-11-12" to listOf(dayRow("2026-11-12", "dev-a", 120, DataQualityValues.EXACT), dayRow("2026-11-12", "dev-b", 60, "FOO")) to "UNKNOWN"
        )

        // 正序与逆序插入均得到完全相同的 dayQuality 与 CR-28 SUM 数值
        for (reverse in listOf(false, true)) {
            db.daySummaryDao().deleteAll()
            cases.forEach { (pair, _) ->
                val rows = if (reverse) pair.second.reversed() else pair.second
                rows.forEach { db.daySummaryDao().insert(it) }
            }
            val aggs = db.daySummaryDao().sumRange("2026-11-01", "2026-11-12").associateBy { it.date }
            cases.forEach { (pair, expectedQuality) ->
                val (date, rows) = pair
                val agg = aggs[date]!!
                assertEquals("date=$date reverse=$reverse", expectedQuality, agg.dayQuality)
                assertEquals(rows.sumOf { it.screenUnlockMin }, agg.screenUnlockMin)
                assertEquals(DayQuality.fromSql(expectedQuality), DayQuality.fromSql(agg.dayQuality))
            }
        }
    }

    @Test
    fun ingestGates_requireClosedDayAndDstAwareFullCoverageWithoutFabricatingZeroRows() = runBlocking {
        // M-CA2：G1(日已闭合) + G2(历次 Shizuku 窗口并集覆盖 America/New_York 夏令时 23h 自然日) + RL-14(无事件日不造行)
        val ny = ZoneId.of("America/New_York")
        // 2026-03-08 是纽约夏令时切换日（23 小时自然日）
        val dstDay = LocalDate.of(2026, 3, 8)
        val prevDay = LocalDate.of(2026, 3, 7)
        val nextDay = LocalDate.of(2026, 3, 9)
        val dstStart = dstDay.atStartOfDay(ny).toInstant().toEpochMilli()
        val dstEnd = nextDay.atStartOfDay(ny).toInstant().toEpochMilli()
        val midPullAt = dstDay.atTime(14, 0).atZone(ny).toInstant().toEpochMilli()
        val sessionStart = dstDay.atTime(9, 0).atZone(ny).toInstant().toEpochMilli()
        val sessionEnd = dstDay.atTime(10, 0).atZone(ny).toInstant().toEpochMilli()

        val pipeline = IngestPipeline(db = db, localDeviceId = { "dev-ny" }, zone = ny)

        // 第 1 次拉取：发生在 dstDay 14:00（当天未结束，不满足 G1），缓冲覆盖 [prevDay 22:00, dstDay 14:00]
        val pull1 = ParseResult(
            days = mapOf(dstDay to DayMetrics(60, 60, 3, 5, 60)),
            hourMinutes = mapOf(dstDay to List(24) { if (it == 9) 60 else 0 }),
            appUsage = emptyList(),
            unlockEvents = emptyList(),
            notificationEvents = emptyList(),
            screenSessions = listOf(SessionSpan(sessionStart, sessionEnd)),
            rawEventMinMs = prevDay.atTime(22, 0).atZone(ny).toInstant().toEpochMilli(),
            rawEventMaxMs = midPullAt
        )
        assertTrue(pipeline.ingest(pull1, source = "shizuku", pulledAtMs = midPullAt).ok)
        assertEquals(DataQualityValues.PARTIAL, db.daySummaryDao().get(dstDay.toString(), "dev-ny")!!.screenQuality)
        // RL-14：prevDay 虽然在窗口内但无任何事件，绝不凭空生成 0 分钟行
        assertNull(db.daySummaryDao().get(prevDay.toString(), "dev-ny"))

        // 第 2 次拉取：次日 01:00，缓冲覆盖 [dstDay 12:00, nextDay 01:00]，与第 1 次并集完整覆盖 23h DST 自然日
        val pull2At = nextDay.atTime(1, 0).atZone(ny).toInstant().toEpochMilli()
        val pull2 = ParseResult(
            days = mapOf(dstDay to DayMetrics(60, 60, 3, 5, 60)),
            hourMinutes = mapOf(dstDay to List(24) { if (it == 9) 60 else 0 }),
            appUsage = emptyList(),
            unlockEvents = emptyList(),
            notificationEvents = emptyList(),
            screenSessions = listOf(SessionSpan(sessionStart, sessionEnd)),
            rawEventMinMs = dstDay.atTime(12, 0).atZone(ny).toInstant().toEpochMilli(),
            rawEventMaxMs = pull2At
        )
        assertTrue(pipeline.ingest(pull2, source = "shizuku", pulledAtMs = pull2At).ok)
        assertEquals(DataQualityValues.EXACT, db.daySummaryDao().get(dstDay.toString(), "dev-ny")!!.screenQuality)
        assertTrue(dstEnd - dstStart == 23L * 3600L * 1000L)
    }

    @Test
    fun sameSourceMerge_isCommutativeRegardlessOfArrivalOrder() {
        // M-CA3：同来源同分钟平手时，EXACT 与 PARTIAL 无论谁先到达，结果恒为 EXACT
        val exactRow = dayRow("2026-11-20", "dev-a", 100, DataQualityValues.EXACT)
        val partialRow = dayRow("2026-11-20", "dev-a", 100, DataQualityValues.PARTIAL)

        val ab = MergePolicy.mergeDay(exactRow, partialRow)
        val ba = MergePolicy.mergeDay(partialRow, exactRow)
        assertEquals(DataQualityValues.EXACT, ab.screenQuality)
        assertEquals(DataQualityValues.EXACT, ba.screenQuality)

        val dtoE = DaySummaryDto("2026-11-20", 100, 100, 5, 10, 100, 1_000L, DataQualityValues.EXACT)
        val dtoP = DaySummaryDto("2026-11-20", 100, 100, 5, 10, 100, 1_000L, DataQualityValues.PARTIAL)
        assertEquals(0, BackupMerge.daySummaries(listOf(dtoE), listOf(dtoP)).rows.size)
        assertEquals(1, BackupMerge.daySummaries(listOf(dtoP), listOf(dtoE)).rows.size)
    }

    @Test
    fun degradedChannelNeverWritesExactAndSourceRebuildPreservesQualityOnRollback() = runBlocking {
        // M-CA4：降级通道写入 hour_usage/app_usage 必为 PARTIAL；SourceRebuild 快照回滚完整保留质量
        val day = LocalDate.of(2026, 11, 18)
        val pullAt = LocalDate.of(2026, 11, 19).atTime(8, 0).atZone(shanghai).toInstant().toEpochMilli()
        val pipeline = IngestPipeline(db = db, localDeviceId = { "dev-a" }, zone = shanghai)
        val degraded = ParseResult(
            days = emptyMap(),
            hourMinutes = mapOf(day to List(24) { if (it == 10) 40 else 0 }),
            appUsage = listOf(AppUsageRow(day, "com.example.demo", 40L * 60_000L, 3)),
            unlockEvents = emptyList(),
            notificationEvents = emptyList()
        )
        assertTrue(pipeline.ingest(degraded, source = "usage_stats", pulledAtMs = pullAt).ok)
        assertTrue(db.appUsageDao().getAll().all { it.minutesQuality == DataQualityValues.PARTIAL })
        assertTrue(db.hourUsageDao().getAll().all { it.quality == DataQualityValues.PARTIAL })

        // SourceRebuild 快照与回滚必须保留 PARTIAL / EXACT 质量，不得退化为 null
        db.daySummaryDao().insert(dayRow("2026-11-18", "dev-a", 80, DataQualityValues.PARTIAL))
        val rebuild = SourceRebuild(db, shanghai)
        val snap = SourceRebuild.Snapshot(
            createdAtMs = 1L,
            dates = listOf("2026-11-18"),
            days = listOf(
                SourceRebuild.SnapDay("dev-a", "2026-11-18", 80, 80, 5, 10, 80, 1_000L, DataQualityValues.PARTIAL)
            ),
            apps = emptyList(),
            hours = emptyList(),
            unlocks = emptyList(),
            notifs = emptyList(),
            sessions = emptyList()
        )
        rebuild.recover(snap)
        assertEquals(DataQualityValues.PARTIAL, db.daySummaryDao().get("2026-11-18", "dev-a")!!.screenQuality)
    }

    @Test
    fun v5BackupBidirectionalEventIntersectionDetectsSameDeviceOverlap() {
        // M-CA5：schemaVersion=5 备份与本机共享部分毫秒事件但互不为子集，仍必须识别为重叠日
        val date = "2026-11-15"
        val msShared = LocalDate.of(2026, 11, 15).atTime(10, 0).atZone(shanghai).toInstant().toEpochMilli()
        val msOnlyBackup = msShared + 5_000L
        val msOnlyLocal = msShared + 10_000L

        val file = BackupFile(
            schemaVersion = 5,
            exportedAt = "2026-11-15T12:00:00Z",
            appVersion = "0.1.0",
            device = BackupDevice(id = "other-dev", nickname = "Other", model = "M"),
            daySummary = listOf(DaySummaryDto(date, 120, 120, 5, 10, 40, 1_000L)),
            unlockEvent = listOf(UnlockEventDto(msShared), UnlockEventDto(msOnlyBackup))
        )
        val localDays = mapOf(date to SameDeviceFingerprint.DayFingerprint(90, 90, 3, 6, 30))
        val localEvents = mapOf(
            date to SameDeviceFingerprint.DayEventKeys(
                setOf(
                    SameDeviceFingerprint.DayEventKeys.ofUnlock(msShared),
                    SameDeviceFingerprint.DayEventKeys.ofUnlock(msOnlyLocal)
                )
            )
        )

        val verdict = SameDeviceFingerprint.detect(
            file = file,
            localDays = localDays,
            localDeviceId = "local-dev",
            localEvents = localEvents,
            zone = shanghai
        )
        assertTrue("v5 双向事件交集应命中同机重叠日", date in verdict.sameDeviceDates)
    }
}
