package com.screentrace.app.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.data.SourceArchive
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
 * CR-28 T8：首启归档（[SourceArchive]）的幂等与归属判定。
 *
 * 三个断言面：
 * 1. **归属**：合并账本内日期 → `'__legacy__'`，纯本机日 → 默认来源；
 * 2. **幂等**：跑两遍结果完全一致（只处理未归档行），第二遍 moved = 0；
 * 3. **不回头**：已归档行（含 legacy）永远不再被改写——换默认来源再跑也不动。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SourceArchiveTest {

    private lateinit var db: ScreenTraceDatabase

    private val zone: ZoneId = ZoneId.systemDefault()
    private val d1: String = "2026-11-10"   // 历史混合日
    private val d2: String = "2026-11-11"   // 纯本机日

    private fun msOf(date: String, hour: Int): Long =
        LocalDate.parse(date).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

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

    /** 六表各写未归档行；会话横跨 d1 23:00 → d2 01:00（保守归 legacy 的关键用例）。 */
    private suspend fun seedUnarchived() {
        db.daySummaryDao().insert(DaySummaryEntity(d1, 240, 200, 10, 20, 60, 1L, SourceIds.UNARCHIVED))
        db.daySummaryDao().insert(DaySummaryEntity(d2, 120, 110, 6, 12, 40, 2L, SourceIds.UNARCHIVED))
        db.appUsageDao().insert(AppUsageEntity(d1, "com.a", "A", 100, 5, "OTHER", SourceIds.UNARCHIVED))
        db.hourUsageDao().insert(HourUsageEntity(d2, 21, 25, SourceIds.UNARCHIVED))
        db.unlockEventDao().insertIgnore(
            listOf(
                UnlockEventEntity(msOf(d1, 9), SourceIds.UNARCHIVED, 0),
                UnlockEventEntity(msOf(d2, 9), SourceIds.UNARCHIVED, 0)
            )
        )
        db.notificationEventDao().insertIgnore(
            listOf(
                NotificationEventEntity(msOf(d1, 10), "INTERRUPTION", "com.a", "ch", SourceIds.UNARCHIVED, 0),
                NotificationEventEntity(msOf(d2, 10), "SEEN", "com.a", "ch", SourceIds.UNARCHIVED, 0)
            )
        )
        db.screenSessionDao().insert(
            listOf(
                // 跨两日：任一日在账本里 → 整段归 legacy（保守，不猜来源）
                ScreenSessionEntity(msOf(d1, 23), msOf(d2, 1), ScreenSessionEntity.KIND_SCREEN, SourceIds.UNARCHIVED),
                // 只在纯本机日 → 归默认来源
                ScreenSessionEntity(msOf(d2, 20), msOf(d2, 21), ScreenSessionEntity.KIND_SCREEN, SourceIds.UNARCHIVED)
            )
        )
    }

    @Test
    fun `归档按账本判定归属且跨日会话保守归 legacy`() = runBlocking {
        seedUnarchived()

        val moved = SourceArchive.archive(db, "dev-1", setOf(d1))
        assertEquals("六表共 10 行未归档", 10, moved)

        val days = db.daySummaryDao().getAll().associate { it.date to it.sourceDeviceId }
        assertEquals(SourceIds.LEGACY, days[d1])
        assertEquals("dev-1", days[d2])
        assertTrue(db.appUsageDao().get(d1, "com.a", SourceIds.LEGACY) != null)
        val hourRow = db.hourUsageDao().getForDate(d2, "dev-1").single()
        assertEquals("dev-1", hourRow.sourceDeviceId)
        assertEquals(25, hourRow.minutes)

        // 事件按本地时区日期归属
        assertEquals(SourceIds.LEGACY, db.unlockEventDao().getAll().first { it.occurredAtMs == msOf(d1, 9) }.sourceDeviceId)
        assertEquals("dev-1", db.unlockEventDao().getAll().first { it.occurredAtMs == msOf(d2, 9) }.sourceDeviceId)
        assertEquals(SourceIds.LEGACY, db.notificationEventDao().getAll().first { it.occurredAtMs == msOf(d1, 10) }.sourceDeviceId)
        assertEquals("dev-1", db.notificationEventDao().getAll().first { it.occurredAtMs == msOf(d2, 10) }.sourceDeviceId)

        // 会话：跨 d1/d2 的整段归 legacy；只在 d2 的归 dev-1
        val sessions = db.screenSessionDao().getAll()
        assertEquals(SourceIds.LEGACY, sessions.first { it.startMs == msOf(d1, 23) }.sourceDeviceId)
        assertEquals("dev-1", sessions.first { it.startMs == msOf(d2, 20) }.sourceDeviceId)
    }

    @Test
    fun `归档幂等跑两遍结果一致`() = runBlocking {
        seedUnarchived()
        val first = SourceArchive.archive(db, "dev-1", setOf(d1))
        val snapshot = allSources()

        val second = SourceArchive.archive(db, "dev-1", setOf(d1))
        assertEquals("第二遍不该再动任何行", 0, second)
        assertEquals(snapshot, allSources())
        assertTrue(first == 10)
    }

    @Test
    fun `已归档行永不被改写含 legacy 与换默认来源`() = runBlocking {
        seedUnarchived()
        SourceArchive.archive(db, "dev-1", setOf(d1))
        val snapshot = allSources()

        // 换一个默认来源、换一批 legacy 日期再跑：已归档行必须原样不动
        val moved = SourceArchive.archive(db, "dev-2", setOf(d2))
        assertEquals(0, moved)
        assertEquals("legacy 与 dev-1 的行都不许被改写", snapshot, allSources())
    }

    private suspend fun allSources(): List<String> =
        db.daySummaryDao().getAll().map { "${it.date}|${it.sourceDeviceId}" } +
            db.appUsageDao().getAll().map { "${it.date}|${it.pkg}|${it.sourceDeviceId}" } +
            db.hourUsageDao().getAll().map { "${it.date}|${it.hour}|${it.sourceDeviceId}" } +
            db.unlockEventDao().getAll().map { "${it.occurredAtMs}|${it.seq}|${it.sourceDeviceId}" } +
            db.notificationEventDao().getAll().map { "${it.occurredAtMs}|${it.type}|${it.pkg}|${it.seq}|${it.sourceDeviceId}" } +
            db.screenSessionDao().getAll().map { "${it.startMs}|${it.kind}|${it.sourceDeviceId}" }
}
