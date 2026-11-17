package com.screentrace.app.data.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.data.db.DaySummaryEntity
import com.screentrace.app.data.db.ScreenSessionEntity
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.db.SourceIds
import com.screentrace.app.data.db.UnlockEventEntity
import com.screentrace.app.data.prefs.DeviceInfo
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
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
 * CR-28 批次 C：备份 schema 6 的设备分量与往返（测试矩阵 T1/T4/T5/T12）。
 *
 * - T5：schema 5 无 `device.id` 旧备份导入 → `'__legacy__'`（绝不归导入设备）；
 * - T1：同一备份重复导入幂等（行数与合计不变）；
 * - T12：A 导出 → B 导入 → B 再导出 → 再导入：来源身份不变、分量逐值保持、不重复累计；
 * - T4：数据写入成功、账本被杀丢失后重试不重复累计（同源取大兜底）；
 * - 三层兼容警告 + CSV 展平带来源列。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupSourceRoundTripTest {

    private lateinit var db: ScreenTraceDatabase
    private lateinit var dbB: ScreenTraceDatabase
    private val zone: ZoneId = ZoneId.systemDefault()
    private val d = "2026-11-10"

    private fun repoOf(
        database: ScreenTraceDatabase,
        id: String,
        info: DeviceInfo? = null
    ) = BackupRepository(
        db = database,
        zone = zone,
        device = { info },
        sourceDeviceId = { id }
    )

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, ScreenTraceDatabase::class.java).allowMainThreadQueries().build()
        dbB = Room.inMemoryDatabaseBuilder(ctx, ScreenTraceDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
        dbB.close()
    }

    private fun dayRow(date: String, screen: Int, source: String) =
        DaySummaryEntity(date, screen, screen, 2, 5, screen, 1L, source)

    private fun msOf(date: String, hour: Int): Long =
        java.time.LocalDate.parse(date).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private suspend fun allSources(database: ScreenTraceDatabase): List<String> =
        database.daySummaryDao().getAll().map { "${it.date}|${it.screenMin}|${it.sourceDeviceId}" } +
            database.appUsageDao().getAll().map { "${it.date}|${it.pkg}|${it.minutes}|${it.sourceDeviceId}" } +
            database.unlockEventDao().getAll().map { "${it.occurredAtMs}|${it.seq}|${it.sourceDeviceId}" } +
            database.screenSessionDao().getAll().map { "${it.startMs}|${it.kind}|${it.sourceDeviceId}" }

    private suspend fun sumScreen(database: ScreenTraceDatabase): Int =
        database.daySummaryDao().sumRange("0001-01-01", "9999-12-31").sumOf { it.screenMin }

    // --------------------------------------------------------------- T5

    @Test
    fun `T5 schema 5 无设备标识的旧备份导入后保持来源未知`() = runBlocking {
        val legacyFile = BackupFile(
            schemaVersion = 5,
            exportedAt = "2026-01-01 10:00",
            appVersion = "1.0",
            daySummary = listOf(DaySummaryDto(d, 60, 60, 2, 5, 60, 1L)),
            unlockEvent = listOf(UnlockEventDto(1_700_000_000_000L)),
            screenSession = listOf(ScreenSessionDto(1_700_000_000_000L, 1_700_000_300_000L, "SCREEN"))
        )
        repoOf(db, "本机-id", DeviceInfo("本机-id", "本机", "X"))
            .restore(legacyFile, BackupRepository.Strategy.MERGE, importSettings = false)

        val day = db.daySummaryDao().getAll().single()
        assertEquals("无 device.id 一律归历史来源未知", SourceIds.LEGACY, day.sourceDeviceId)
        assertEquals(SourceIds.LEGACY, db.unlockEventDao().getAll().single().sourceDeviceId)
        assertEquals(SourceIds.LEGACY, db.screenSessionDao().getAll().single().sourceDeviceId)
    }

    // --------------------------------------------------------------- T1

    @Test
    fun `T1 同一备份重复导入幂等 行数与合计不变`() = runBlocking {
        // 顶层 dev-B + sources[dev-A、__legacy__]
        val file = BackupFile(
            schemaVersion = 6,
            exportedAt = "2026-11-17 12:00",
            appVersion = "1.0",
            device = BackupDevice("dev-B", "乙", "B"),
            daySummary = listOf(DaySummaryDto(d, 120, 120, 2, 5, 120, 2L)),
            unlockEvent = listOf(UnlockEventDto(msOf(d, 9), 0)),
            screenSession = listOf(ScreenSessionDto(msOf(d, 9), msOf(d, 10), "SCREEN")),
            sources = listOf(
                BackupSource(
                    device = BackupDevice("dev-A", "甲", "A"),
                    daySummary = listOf(DaySummaryDto(d, 60, 60, 1, 2, 60, 3L)),
                    unlockEvent = listOf(UnlockEventDto(1_700_000_000_000L, 0), UnlockEventDto(1_700_000_000_000L, 1))
                ),
                BackupSource(
                    device = BackupDevice(SourceIds.LEGACY, "历史来源未知", ""),
                    daySummary = listOf(DaySummaryDto(d, 240, 200, 10, 20, 90, 4L))
                )
            )
        )
        val repo = repoOf(db, "本机-id", DeviceInfo("本机-id", "本机", "X"))
        repo.restore(file, BackupRepository.Strategy.MERGE, importSettings = false)
        val first = allSources(db)
        val firstSum = sumScreen(db)

        repo.restore(file, BackupRepository.Strategy.MERGE, importSettings = false)
        assertEquals("重复导入行集不变", first, allSources(db))
        assertEquals("重复导入合计不变", firstSum, sumScreen(db))
        assertEquals("合计 = 120 + 60 + 240", 420, firstSum)
        assertEquals("同毫秒两条解锁各占一行", 3, db.unlockEventDao().getAll().size)
    }

    // --------------------------------------------------------------- T12

    @Test
    fun `T12 A 导出 B 导入 B 再导出 再导入 往返保持身份与分量`() = runBlocking {
        // ① A 库：dev-A 分量 + legacy 分量
        db.daySummaryDao().insert(dayRow(d, 120, "dev-A"))
        db.daySummaryDao().insert(dayRow(d, 240, SourceIds.LEGACY))
        db.unlockEventDao().insertIgnore(listOf(UnlockEventEntity(1_700_000_000_000L, "dev-A", 0)))
        val repoA = repoOf(db, "dev-A", DeviceInfo("dev-A", "甲", "X"))
        val fileA = repoA.buildFile("1.0")

        assertEquals("顶层身份 = 导出设备 A", "dev-A", fileA.device?.id)
        assertEquals("A 的分量在顶层", 120, fileA.daySummary.single().screenMin)
        assertEquals(
            "legacy 分量进 sources[]",
            listOf(SourceIds.LEGACY),
            fileA.sources.map { it.device?.id }
        )
        assertEquals(240, fileA.sources.single().daySummary.single().screenMin)

        // ② B 导入 A 的备份：A 的数据仍属 A，不归 B
        val repoB = repoOf(dbB, "dev-B", DeviceInfo("dev-B", "乙", "Y"))
        repoB.restore(fileA, BackupRepository.Strategy.OVERWRITE, importSettings = false)
        val bySource = dbB.daySummaryDao().getAll().associate { it.sourceDeviceId to it.screenMin }
        assertEquals("A 的行归 A", 120, bySource["dev-A"])
        assertEquals("legacy 行归 legacy", 240, bySource[SourceIds.LEGACY])
        assertEquals(null, bySource["dev-B"])

        // ③ B 再导出：B 自己的行在顶层，A 与 legacy 在 sources[]，A 的分量逐值保持
        dbB.daySummaryDao().insert(dayRow(d, 60, "dev-B"))
        val fileB = repoB.buildFile("1.0")
        assertEquals("顶层身份 = B", "dev-B", fileB.device?.id)
        assertEquals(60, fileB.daySummary.single().screenMin)
        val sourceA = fileB.sources.single { it.device?.id == "dev-A" }
        assertEquals("A 分量逐值保持", 120, sourceA.daySummary.single().screenMin)
        assertEquals(0, sourceA.unlockEvent.single().seq)
        assertEquals(
            setOf("dev-A", SourceIds.LEGACY),
            fileB.sources.map { it.device?.id }.toSet()
        )

        // ④ 再导入 A 库（合并）：A 的数据仍属 A，不重复累计
        repoA.restore(fileB, BackupRepository.Strategy.MERGE, importSettings = false)
        val aRows = db.daySummaryDao().getAll()
        assertEquals(
            "A 库三行：A 原行 + B 分量 + legacy（A 的分量被同源取大、不翻倍）",
            3,
            aRows.size
        )
        assertEquals(120, aRows.single { it.sourceDeviceId == "dev-A" }.screenMin)
        assertEquals(60, aRows.single { it.sourceDeviceId == "dev-B" }.screenMin)
        assertEquals(240, aRows.single { it.sourceDeviceId == SourceIds.LEGACY }.screenMin)
    }

    @Test
    fun `legacy 分量导入只增不改 本地已有行保留原值`() = runBlocking {
        // 本地已有 legacy 行（历史混合，只读保护）
        db.daySummaryDao().insert(dayRow(d, 240, SourceIds.LEGACY))

        val file = BackupFile(
            schemaVersion = 6,
            exportedAt = "2026-11-17 12:00",
            appVersion = "1.0",
            device = BackupDevice("dev-B", "乙", "B"),
            sources = listOf(
                BackupSource(
                    device = BackupDevice(SourceIds.LEGACY, "历史来源未知", ""),
                    daySummary = listOf(
                        DaySummaryDto(d, 300, 300, 9, 9, 99, 9L),      // 与本地冲突：不得改写
                        DaySummaryDto("2026-11-11", 50, 50, 1, 1, 50, 9L) // 本地没有：照常补入
                    )
                )
            )
        )
        repoOf(db, "dev-A", DeviceInfo("dev-A", "甲", "X"))
            .restore(file, BackupRepository.Strategy.MERGE, importSettings = false)

        val legacyRows = db.daySummaryDao().getAll().filter { it.sourceDeviceId == SourceIds.LEGACY }
        assertEquals(240, legacyRows.single { it.date == d }.screenMin)
        assertEquals("本地没有的日期照常补入", 50, legacyRows.single { it.date == "2026-11-11" }.screenMin)
    }

    // --------------------------------------------------------------- T4

    @Test
    fun `T4 账本丢失后重试不重复累计 同源取大兜底`() = runBlocking {
        val file = BackupFile(
            schemaVersion = 6,
            exportedAt = "2026-11-17 12:00",
            appVersion = "1.0",
            device = BackupDevice("dev-B", "乙", "B"),
            daySummary = listOf(DaySummaryDto(d, 120, 120, 2, 5, 120, 2L))
        )
        val repo = repoOf(db, "dev-A", DeviceInfo("dev-A", "甲", "X"))
        repo.mergeAccumulate(file, setOf(d), ledgerDeviceId = "dev-B")
        assertEquals(120, sumScreen(db))
        assertEquals(setOf(d), db.deviceMergeLedgerDao().datesOf("dev-B").toSet())

        // 模拟「进程被杀、账本没写」：账本丢失（数据已写）
        db.openHelper.writableDatabase.execSQL("DELETE FROM device_merge_ledger")

        val again = repo.mergeAccumulate(file, setOf(d), ledgerDeviceId = "dev-B")
        assertEquals("重试后合计不翻倍（同源取大）", 120, sumScreen(db))
        assertEquals("分量行仍是一行", 1, db.daySummaryDao().getAll().size)
        assertEquals("重放被同源取大吸收（无新增、无改写）", 0, again.added + again.updated)
    }

    // --------------------------------------------------------------- 三层兼容 + CSV

    @Test
    fun `多来源导出给旧版本的兼容警告 无分量时不警告`() = runBlocking {
        db.daySummaryDao().insert(dayRow(d, 60, "dev-A"))
        db.daySummaryDao().insert(dayRow(d, 240, SourceIds.LEGACY))
        val repo = repoOf(db, "dev-A", DeviceInfo("dev-A", "甲", "X"))
        val file = repo.buildFile("1.0")

        val warning = repo.legacyCompatWarning(file)
        assertNotNull("含 sources 必须警告", warning)
        assertTrue(warning!!.contains("旧版本导入会丢失非本机来源"))

        // 只有单一来源时不警告
        dbB.daySummaryDao().insert(dayRow(d, 60, "dev-B"))
        val fileB = repoOf(dbB, "dev-B", DeviceInfo("dev-B", "乙", "Y")).buildFile("1.0")
        assertNull(repoOf(dbB, "dev-B").legacyCompatWarning(fileB))
    }

    @Test
    fun `CSV 展平含全部来源分量与来源列`() = runBlocking {
        db.daySummaryDao().insert(dayRow(d, 60, "dev-A"))
        db.daySummaryDao().insert(dayRow(d, 240, SourceIds.LEGACY))
        val repo = repoOf(db, "dev-A", DeviceInfo("dev-A", "甲", "X"))
        val csvs = repo.exportCsv("1.0")
        val day = csvs.first { it.first.contains("day_summary") }.second
        assertTrue(day.contains("source_device_id"))
        assertTrue("顶层分量在", day.contains("dev-A"))
        assertTrue("sources 分量展平并入", day.contains(SourceIds.LEGACY))
        assertEquals("两行数据 + 表头", 3, day.trim().lines().size)
    }
}
