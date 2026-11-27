package com.screentrace.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.data.backup.BackupCodec
import com.screentrace.app.data.backup.BackupDevice
import com.screentrace.app.data.backup.BackupFile
import com.screentrace.app.data.backup.BackupMerge
import com.screentrace.app.data.backup.BackupRepository
import com.screentrace.app.data.backup.DaySummaryDto
import com.screentrace.app.data.backup.HourUsageDto
import com.screentrace.app.data.backup.PullLogDto
import com.screentrace.app.data.db.DataQualityValues
import com.screentrace.app.data.db.DaySummaryEntity
import com.screentrace.app.data.db.HourUsageEntity
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.db.SourceIds
import com.screentrace.app.data.parse.BoundedObservedWindow
import com.screentrace.app.data.parse.DayMetrics
import com.screentrace.app.data.parse.ParseResult
import com.screentrace.app.data.parse.SessionSpan
import com.screentrace.app.data.parse.UsageDumpParser
import com.screentrace.app.ui.AppViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D-07 GPT Codex Review 定向修复端到端测试（真实 Room 内存库 + BackupRepository + UsageDumpParser + AppViewModel）。
 * 覆盖行为变异锚点 M-CR1 ~ M-CR5。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class D07ReviewFixTest {

    private lateinit var db: ScreenTraceDatabase
    private val dispatcher = UnconfinedTestDispatcher()
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val humanFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ScreenTraceDatabase::class.java
        )
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun ms(date: LocalDate, hour: Int, minute: Int = 0, second: Int = 0): Long =
        date.atTime(hour, minute, second).atZone(zone).toInstant().toEpochMilli()

    private fun checkinLine(ms: Long, type: String, pkg: String = "android"): String =
        "  time=$ms type=$type package=$pkg"

    private fun humanLine(date: LocalDate, hour: Int, minute: Int, second: Int, type: String, pkg: String = "android"): String =
        "    time=\"${date.atTime(hour, minute, second).format(humanFmt)}\" type=$type package=$pkg flags=0x0"

    // ============================================================
    // M-CR1（P1-1）：pull_log 跨设备隔离与旧备份零伪造
    // ============================================================
    @Test
    fun mCr1_crossDevicePullLogIsolationAndZeroFabricationOnLegacyBackup() = runBlocking {
        val targetDay = LocalDate.of(2026, 11, 20)
        val nextDay = LocalDate.of(2026, 11, 21)
        val devBPulledAt = ms(nextDay, 9, 30)
        val devAPulledAt = ms(nextDay, 8, 0)

        // 设备 B 含 11/20 完整 Shizuku 窗口，以及一条旧备份（sourceDeviceId = null）完整窗口
        val backupFromB = BackupFile(
            schemaVersion = BackupCodec.SCHEMA_VERSION,
            exportedAt = "2026-11-21 09:30",
            appVersion = "0.1.0",
            device = BackupDevice(id = "dev-b", nickname = "DeviceB", model = "M-B"),
            daySummary = listOf(
                DaySummaryDto(targetDay.toString(), 120, 120, 5, 10, 40, devBPulledAt, DataQualityValues.EXACT)
            ),
            pullLog = listOf(
                PullLogDto(
                    pulledAt = devBPulledAt,
                    source = "shizuku",
                    ok = true,
                    note = "dev-b full",
                    collectionStatus = "OK",
                    eventWindowStartMs = ms(targetDay, 0, 0),
                    eventWindowEndMs = ms(nextDay, 0, 5),
                    sourceDeviceId = "dev-b"
                ),
                PullLogDto(
                    pulledAt = devBPulledAt - 60_000L,
                    source = "shizuku",
                    ok = true,
                    note = "legacy-null-source",
                    collectionStatus = "OK",
                    eventWindowStartMs = ms(targetDay, 0, 0),
                    eventWindowEndMs = ms(nextDay, 0, 5),
                    sourceDeviceId = null
                )
            )
        )

        val backupRepo = BackupRepository(db = db, zone = zone, sourceDeviceId = { "dev-a" })
        backupRepo.restore(backupFromB, BackupRepository.Strategy.MERGE, importSettings = false)

        // 验证旧备份 sourceDeviceId == null 严禁伪造回填，远端 dev-b 原样保留
        val logs = db.pullLogDao().getAll()
        assertEquals(2, logs.size)
        assertTrue(logs.any { it.note == "dev-b full" && it.sourceDeviceId == "dev-b" })
        assertTrue(logs.any { it.note == "legacy-null-source" && it.sourceDeviceId == null })

        // 设备 A 仅对 11/20 做局部拉取（10:00..12:00），不得借用 dev-b 或 null 窗口升级为 EXACT
        val pipelineA = IngestPipeline(db = db, localDeviceId = { "dev-a" }, zone = zone)
        val partialA = ParseResult(
            days = mapOf(targetDay to DayMetrics(60, 60, 2, 4, 60)),
            hourMinutes = mapOf(targetDay to List(24) { if (it == 10) 60 else 0 }),
            appUsage = emptyList(),
            unlockEvents = emptyList(),
            notificationEvents = emptyList(),
            screenSessions = listOf(SessionSpan(ms(targetDay, 10, 0), ms(targetDay, 11, 0))),
            observedWindow = BoundedObservedWindow(ms(targetDay, 10, 0), ms(targetDay, 12, 0))
        )
        assertTrue(pipelineA.ingest(partialA, source = "shizuku", pulledAtMs = devAPulledAt).ok)

        val rowA = db.daySummaryDao().get(targetDay.toString(), "dev-a")!!
        assertEquals("设备 A 的局部拉取不得借用设备 B 或 null 窗口升 EXACT", DataQualityValues.PARTIAL, rowA.screenQuality)

        // latestOkPulledAtForDevice 严格按设备隔离：不返回设备 B 更晚的拉取时刻
        val repoA = RoomUsageRepository(db = db, ownerDeviceId = { "dev-a" })
        assertEquals(devAPulledAt, repoA.latestOkPulledAt())
        assertEquals(devBPulledAt, db.pullLogDao().latestOkPulledAtForDevice("dev-b"))
    }

    // ============================================================
    // M-CR2（P1-2）：非屏幕/KEYGUARD_HIDDEN 与跨通道超跨度端到端门禁
    // ============================================================
    @Test
    fun mCr2_endToEndParserToIngestRejectsNonScreenAndCrossChannelStitch() = runBlocking {
        val dPrev = LocalDate.of(2026, 11, 19)
        val dTarget = LocalDate.of(2026, 11, 20)
        val dNext = LocalDate.of(2026, 11, 21)
        val pipeline = IngestPipeline(db = db, localDeviceId = { "dev-a" }, zone = zone)

        // 00:00 前仅有 ACTIVITY_RESUMED / KEYGUARD_HIDDEN，无 SCREEN_* -> observedWindow == null -> dTarget PARTIAL
        val noScreenDump = buildString {
            appendLine("user=0")
            appendLine(checkinLine(ms(dPrev, 23, 50), "KEYGUARD_HIDDEN"))
            appendLine(checkinLine(ms(dPrev, 23, 55), "ACTIVITY_RESUMED", "com.tencent.mm"))
            appendLine(checkinLine(ms(dTarget, 10, 0), "ACTIVITY_RESUMED", "com.tencent.mm"))
            appendLine(checkinLine(ms(dTarget, 11, 0), "ACTIVITY_PAUSED", "com.tencent.mm"))
        }
        val parsedNoScreen = UsageDumpParser.parse(noScreenDump, "", zone, capturedAtMs = ms(dNext, 1, 0))
        assertNull(parsedNoScreen.observedWindow)
        assertTrue(pipeline.ingest(parsedNoScreen, source = "shizuku", pulledAtMs = ms(dNext, 1, 0)).ok)
        assertEquals(DataQualityValues.PARTIAL, db.daySummaryDao().get(dTarget.toString(), "dev-a")!!.screenQuality)

        // checkin 3 天前旧事件 + human 今日事件不跨通道混拼，中间日 dTarget 保持 PARTIAL
        db.daySummaryDao().deleteAll()
        db.pullLogDao().deleteAll()
        val dOld = LocalDate.of(2026, 11, 17)
        val checkinOld = buildString {
            appendLine("user=0")
            appendLine(checkinLine(ms(dOld, 10, 0), "SCREEN_INTERACTIVE"))
            appendLine(checkinLine(ms(dOld, 11, 0), "SCREEN_NON_INTERACTIVE"))
        }
        val humanToday = buildString {
            appendLine("user=0")
            appendLine(humanLine(dTarget, 10, 0, 0, "SCREEN_INTERACTIVE"))
            appendLine(humanLine(dTarget, 11, 0, 0, "SCREEN_NON_INTERACTIVE"))
            appendLine(humanLine(dNext, 8, 0, 0, "SCREEN_INTERACTIVE"))
            appendLine(humanLine(dNext, 9, 0, 0, "SCREEN_NON_INTERACTIVE"))
        }
        val parsedMixed = UsageDumpParser.parse(checkinOld, humanToday, zone, capturedAtMs = ms(dNext, 10, 0))
        assertTrue(pipeline.ingest(parsedMixed, source = "shizuku", pulledAtMs = ms(dNext, 10, 0)).ok)
        assertEquals(DataQualityValues.PARTIAL, db.daySummaryDao().get(dTarget.toString(), "dev-a")!!.screenQuality)
    }

    // ============================================================
    // M-CR3（P1-2）：状态机截断（首孤立 OFF / 跨午夜未闭合 ON）端到端必 PARTIAL
    // ============================================================
    @Test
    fun mCr3_endToEndStateMachineTruncationsForcePartial() = runBlocking {
        val d1 = LocalDate.of(2026, 11, 20)
        val d2 = LocalDate.of(2026, 11, 21)
        val pipeline = IngestPipeline(db = db, localDeviceId = { "dev-a" }, zone = zone)

        // 首事件为孤立 SCREEN_NON_INTERACTIVE（覆盖 00:00 前到次日 00:05），但因 G3 截断必为 PARTIAL
        val leadingOff = buildString {
            appendLine("user=0")
            appendLine(checkinLine(ms(d1, 0, 0), "SCREEN_NON_INTERACTIVE"))
            appendLine(checkinLine(ms(d1, 9, 0), "SCREEN_INTERACTIVE"))
            appendLine(checkinLine(ms(d1, 10, 0), "SCREEN_NON_INTERACTIVE"))
        }
        val parsedLeadingOff = UsageDumpParser.parse(leadingOff, "", zone, capturedAtMs = ms(d2, 0, 5))
        assertTrue(d1 in parsedLeadingOff.truncatedScreenDates)
        assertTrue(pipeline.ingest(parsedLeadingOff, source = "shizuku", pulledAtMs = ms(d2, 0, 5)).ok)
        assertEquals(DataQualityValues.PARTIAL, db.daySummaryDao().get(d1.toString(), "dev-a")!!.screenQuality)

        // 昨日 23:50 未闭合 SCREEN_INTERACTIVE 跨午夜 -> 昨日必为 PARTIAL
        db.daySummaryDao().deleteAll()
        db.pullLogDao().deleteAll()
        val trailingOpen = buildString {
            appendLine("user=0")
            appendLine(checkinLine(ms(d1.minusDays(1), 23, 55), "SCREEN_INTERACTIVE"))
            appendLine(checkinLine(ms(d1, 0, 5), "SCREEN_NON_INTERACTIVE"))
            appendLine(checkinLine(ms(d1, 23, 50), "SCREEN_INTERACTIVE"))
        }
        val parsedTrailing = UsageDumpParser.parse(trailingOpen, "", zone, capturedAtMs = ms(d2, 0, 30))
        assertTrue(d1 in parsedTrailing.truncatedScreenDates)
        assertTrue(pipeline.ingest(parsedTrailing, source = "shizuku", pulledAtMs = ms(d2, 0, 30)).ok)
        assertEquals(DataQualityValues.PARTIAL, db.daySummaryDao().get(d1.toString(), "dev-a")!!.screenQuality)
    }

    // ============================================================
    // M-CR4（P1-3）：同源降级保护 + 跨源 PARTIAL 小时剔除 + 单源 __legacy__ 24 行不误杀
    // ============================================================
    @Test
    fun mCr4_mergeHourProtectionCrossSourcePartialExclusionAndSingleLegacy24Rows() = runBlocking {
        // 1) 同来源 EXACT 120m vs 降级 PARTIAL 130m -> 无论顺序均保留 EXACT 120m
        val exact = HourUsageEntity("2026-11-20", 9, 120, "dev-a", DataQualityValues.EXACT)
        val partialBigger = HourUsageEntity("2026-11-20", 9, 130, "dev-a", DataQualityValues.PARTIAL)
        assertEquals(exact, MergePolicy.mergeHour(exact, partialBigger))
        assertEquals(exact, MergePolicy.mergeHour(partialBigger, exact))

        val dtoExact = HourUsageDto("2026-11-20", 9, 120, DataQualityValues.EXACT)
        val dtoPartial = HourUsageDto("2026-11-20", 9, 130, DataQualityValues.PARTIAL)
        assertEquals(0, BackupMerge.hourUsage(listOf(dtoExact), listOf(dtoPartial)).rows.size)
        assertEquals(1, BackupMerge.hourUsage(listOf(dtoPartial), listOf(dtoExact)).rows.size)

        // 历史 null 50m 不被更小 PARTIAL 30m 覆盖
        val legacyNull = HourUsageEntity("2026-11-20", 10, 50, "dev-a", null)
        val partialSmaller = HourUsageEntity("2026-11-20", 10, 30, "dev-a", DataQualityValues.PARTIAL)
        assertEquals(legacyNull, MergePolicy.mergeHour(legacyNull, partialSmaller))
        assertEquals(legacyNull, MergePolicy.mergeHour(partialSmaller, legacyNull))

        // 2) 设备 A day/hour=EXACT + 设备 B hour=PARTIAL -> 该日被剔除出时段分子分母
        val dMixed = LocalDate.of(2026, 11, 20)
        val dClean = LocalDate.of(2026, 11, 21)
        val dLegacy = LocalDate.of(2026, 11, 22)
        db.daySummaryDao().insert(DaySummaryEntity(dMixed.toString(), 120, 120, 5, 10, 60, 1L, "dev-a", DataQualityValues.EXACT))
        db.hourUsageDao().insert(HourUsageEntity(dMixed.toString(), 9, 120, "dev-a", DataQualityValues.EXACT))
        db.hourUsageDao().insert(HourUsageEntity(dMixed.toString(), 9, 30, "dev-b", DataQualityValues.PARTIAL))

        db.daySummaryDao().insert(DaySummaryEntity(dClean.toString(), 60, 60, 2, 4, 60, 1L, "dev-a", DataQualityValues.EXACT))
        db.hourUsageDao().insert(HourUsageEntity(dClean.toString(), 9, 60, "dev-a", DataQualityValues.EXACT))

        // 3) 单源 __legacy__ 24 条小时行必须判为 LEGACY（不得因 COUNT(*) > 1 误判 UNKNOWN）
        db.daySummaryDao().insert(DaySummaryEntity(dLegacy.toString(), 240, 240, 10, 20, 30, 1L, SourceIds.LEGACY, null))
        (0..23).forEach { h ->
            db.hourUsageDao().insert(HourUsageEntity(dLegacy.toString(), h, 10, SourceIds.LEGACY, null))
        }
        val qualities = db.hourUsageDao()
            .dayQualitiesForDates(listOf(dMixed.toString(), dClean.toString(), dLegacy.toString()))
            .associate { it.date to it.dayQuality }
        assertEquals("PARTIAL", qualities[dMixed.toString()])
        assertEquals("EXACT", qualities[dClean.toString()])
        assertEquals("LEGACY", qualities[dLegacy.toString()])

        val repo = RoomUsageRepository(db = db, ownerDeviceId = { "dev-a" })
        val qualified = repo.qualifiedHourlyDistribution(listOf(dMixed, dClean, dLegacy))
        // dMixed 剔除，仅 dClean + dLegacy 合格（共 2 天）
        assertEquals(2, qualified.eligibleDays)
        assertEquals(70, qualified.buckets.first { it.hour == 9 }.minutes) // dClean(60) + dLegacy(10)
    }

    // ============================================================
    // M-CR5（P1-3）：完整零使用日 vs 缺失小时日 + 彻底拔除 .ifEmpty 回退
    // ============================================================
    @Test
    fun mCr5_completeZeroDayVsMissingHoursAndNoIfEmptyFallback() = runBlocking {
        val today = LocalDate.of(2026, 11, 26)
        val dFull = LocalDate.of(2026, 11, 23)
        val dZero = LocalDate.of(2026, 11, 24)
        val dMissing = LocalDate.of(2026, 11, 25)
        val repo = RoomUsageRepository(db = db, ownerDeviceId = { "dev-a" })

        // 场景 A：EXACT 120m（有小时）+ EXACT 0m（完整零使用日，无小时行）-> 分母 2，06-12 日均 60m
        db.daySummaryDao().insert(DaySummaryEntity(dFull.toString(), 120, 120, 5, 10, 60, 1L, "dev-a", DataQualityValues.EXACT))
        db.hourUsageDao().insert(HourUsageEntity(dFull.toString(), 9, 120, "dev-a", DataQualityValues.EXACT))
        db.daySummaryDao().insert(DaySummaryEntity(dZero.toString(), 0, 0, 0, 0, 0, 1L, "dev-a", DataQualityValues.EXACT))

        val qZero = repo.qualifiedHourlyDistribution(listOf(dFull, dZero))
        assertEquals(2, qZero.eligibleDays)
        assertEquals(120, qZero.buckets.first { it.hour == 9 }.minutes)
        assertEquals(60, com.screentrace.app.contract.UsageCalculator.dailyAveragePeriods(
            com.screentrace.app.contract.UsageCalculator.periodBuckets(qZero.buckets),
            qZero.eligibleDays
        )[0])

        // 场景 B：EXACT 120m（有小时）+ EXACT 180m（缺失小时行）-> 分母 1，06-12 日均 120m
        db.daySummaryDao().deleteAll()
        db.hourUsageDao().deleteAll()
        db.daySummaryDao().insert(DaySummaryEntity(dFull.toString(), 120, 120, 5, 10, 60, 1L, "dev-a", DataQualityValues.EXACT))
        db.hourUsageDao().insert(HourUsageEntity(dFull.toString(), 9, 120, "dev-a", DataQualityValues.EXACT))
        db.daySummaryDao().insert(DaySummaryEntity(dMissing.toString(), 180, 180, 8, 16, 60, 1L, "dev-a", DataQualityValues.EXACT))

        val qMissing = repo.qualifiedHourlyDistribution(listOf(dFull, dMissing))
        assertEquals(1, qMissing.eligibleDays)
        assertEquals(120, com.screentrace.app.contract.UsageCalculator.dailyAveragePeriods(
            com.screentrace.app.contract.UsageCalculator.periodBuckets(qMissing.buckets),
            qMissing.eligibleDays
        )[0])

        // 场景 C：全为 PARTIAL 小时时，AppViewModel 统计页与周期页绝不回退全区间
        db.daySummaryDao().deleteAll()
        db.hourUsageDao().deleteAll()
        db.daySummaryDao().insert(DaySummaryEntity(dFull.toString(), 120, 120, 5, 10, 60, 1L, "dev-a", DataQualityValues.EXACT))
        db.hourUsageDao().insert(HourUsageEntity(dFull.toString(), 9, 120, "dev-a", DataQualityValues.PARTIAL))
        db.daySummaryDao().insert(DaySummaryEntity(dZero.toString(), 180, 180, 6, 12, 60, 1L, "dev-a", DataQualityValues.PARTIAL))
        db.hourUsageDao().insert(HourUsageEntity(dZero.toString(), 9, 180, "dev-a", DataQualityValues.PARTIAL))

        val vm = AppViewModel(repository = repo, todayProvider = { today })
        val stats = vm.statsState.value.data
        assertNotNull(stats)
        assertEquals("全为 PARTIAL 小时时统计页绝不得 .ifEmpty 回退全区间", listOf(0, 0, 0, 0), stats!!.periods)

        vm.openPeriod()
        val period = vm.periodState.value.data
        assertNotNull(period)
        assertEquals("全为 PARTIAL 小时时周期页绝不得 .ifEmpty 回退全区间", listOf(0, 0, 0, 0), period!!.periods)
    }
}
