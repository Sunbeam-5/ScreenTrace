package com.screentrace.app.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.contract.AppCategory
import com.screentrace.app.contract.Coverage
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.MetricKind
import com.screentrace.app.contract.NotificationType
import com.screentrace.app.data.db.AppUsageEntity
import com.screentrace.app.data.db.PullLogEntity
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.db.UnlockEventEntity
import com.screentrace.app.data.prefs.SettingsStore
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 批 6a 评审 P1-3 / P1-4 / P1-1（读取层）的验收物：
 * 覆盖三态不再退化、库内 name 优先、用户分类覆盖优先于内置映射。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomUsageRepositoryTest {

    private lateinit var db: ScreenTraceDatabase
    private lateinit var repo: RoomUsageRepository

    private val d1: LocalDate = LocalDate.of(2026, 11, 2)
    private val d2: LocalDate = d1.plusDays(1)
    private val d3: LocalDate = d1.plusDays(2)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ScreenTraceDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = RoomUsageRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------- P1-3：覆盖三态 ----------

    @Test
    fun `拉取失败且当天无成功的日期标记为 GAP`() = runBlocking {
        db.unlockEventDao().insertIgnore(listOf(UnlockEventEntity(msOf(d1, 9)), UnlockEventEntity(msOf(d3, 9))))
        // d2 拉取失败，且当天没有成功记录 → 不可信，必须画成 GAP 而不是真实 0
        db.pullLogDao().insert(PullLogEntity(pulledAt = msOf(d2, 12), source = "dump", ok = false, note = "超时"))
        db.pullLogDao().insert(PullLogEntity(pulledAt = msOf(d1, 12), source = "dump", ok = true, note = null))
        db.pullLogDao().insert(PullLogEntity(pulledAt = msOf(d3, 12), source = "dump", ok = true, note = null))

        val detail = repo.metricDetail(MetricKind.UNLOCK, DateSelection.Range(d1, d3))
        val coverage = detail.points.associate { it.date to it.coverage }

        assertEquals(Coverage.RECORDED, coverage[d1])
        assertEquals("拉取失败日不得画成真实 0（§4/§7-16）", Coverage.GAP, coverage[d2])
        assertEquals(Coverage.RECORDED, coverage[d3])
    }

    @Test
    fun `同一天后来拉取成功时不再算缺口`() = runBlocking {
        db.unlockEventDao().insertIgnore(listOf(UnlockEventEntity(msOf(d1, 9))))
        db.pullLogDao().insert(PullLogEntity(pulledAt = msOf(d1, 8), source = "dump", ok = false, note = "x"))
        db.pullLogDao().insert(PullLogEntity(pulledAt = msOf(d1, 20), source = "dump", ok = true, note = null))

        val detail = repo.metricDetail(MetricKind.UNLOCK, DateSelection.Day(d1))
        assertEquals(Coverage.RECORDED, detail.points.first { it.hour == 9 }.coverage)
    }

    @Test
    fun `窗口起点按指标分别取本表最早事件`() = runBlocking {
        // 解锁只有 d2 之后的事件；通知从 d1 就有 → 查解锁不得被通知表更早的起点拉宽
        db.unlockEventDao().insertIgnore(listOf(UnlockEventEntity(msOf(d2, 10))))
        db.notificationEventDao().insertIgnore(
            listOf(
                com.screentrace.app.data.db.NotificationEventEntity(
                    msOf(d1, 10), NotificationType.INTERRUPTION.name, "com.tencent.mm", null
                )
            )
        )

        val unlock = repo.metricDetail(MetricKind.UNLOCK, DateSelection.Range(d1, d2))
        assertEquals(
            "d1 在解锁事件窗口之外 → NO_RECORD",
            Coverage.NO_RECORD,
            unlock.points.first { it.date == d1 }.coverage
        )

        val notif = repo.metricDetail(MetricKind.NOTIFICATION, DateSelection.Range(d1, d2))
        assertEquals(Coverage.RECORDED, notif.points.first { it.date == d1 }.coverage)
    }

    // ---------- P1-4：库内 name 优先 ----------

    @Test
    fun `详情页通知排行优先使用库内已回填的应用名`() = runBlocking {
        db.appUsageDao().insert(AppUsageEntity(d1.toString(), "com.tencent.mm", "微信", 12, 30, "SOCIAL"))
        db.notificationEventDao().insertIgnore(
            listOf(
                com.screentrace.app.data.db.NotificationEventEntity(
                    msOf(d1, 10), NotificationType.INTERRUPTION.name, "com.tencent.mm", null
                )
            )
        )

        val detail = repo.metricDetail(MetricKind.NOTIFICATION, DateSelection.Day(d1))
        assertEquals("微信", detail.appRanking.first().name)
    }

    @Test
    fun `库内 name 只是包名时回退内置中文映射`() = runBlocking {
        db.appUsageDao().insert(
            AppUsageEntity(d1.toString(), "com.tencent.mm", "com.tencent.mm", 12, 30, "SOCIAL")
        )
        db.notificationEventDao().insertIgnore(
            listOf(
                com.screentrace.app.data.db.NotificationEventEntity(
                    msOf(d1, 10), NotificationType.SEEN.name, "com.tencent.mm", null
                )
            )
        )

        val detail = repo.metricDetail(MetricKind.NOTIFICATION, DateSelection.Day(d1))
        assertEquals("微信", detail.appRanking.first().name)
    }

    @Test
    fun `只在通知事件里出现的包也要能查到真实名字`() = runBlocking {
        // 真机场景：`com.xiaomi.mi_connect_service` 这类没有桌面入口的系统服务，
        // **只出现在通知事件里**、从不进入 app_usage。名字表如果只从 app_usage 构建，
        // 通知详情就会显示成一串 com.xxx。（这是变异测试抓出来的：第一版把该包
        // 同时插进了 app_usage，于是「补全通知包」那段代码根本不参与，测了个寂寞。）
        val resolver = { pkg: String -> if (pkg == "com.xiaomi.mi_connect_service") "小米互联通信服务" else null }
        val withResolver = RoomUsageRepository(db, labelResolver = resolver)
        db.notificationEventDao().insertIgnore(
            listOf(
                com.screentrace.app.data.db.NotificationEventEntity(
                    msOf(d1, 10), NotificationType.INTERRUPTION.name, "com.xiaomi.mi_connect_service", null
                )
            )
        )

        val detail = withResolver.metricDetail(MetricKind.NOTIFICATION, DateSelection.Day(d1))
        assertEquals("小米互联通信服务", detail.appRanking.first().name)
    }

    @Test
    fun `PackageManager 也查不到时回退内置映射`() = runBlocking {
        val resolver = { _: String -> null }
        val withResolver = RoomUsageRepository(db, labelResolver = resolver)
        db.appUsageDao().insert(
            AppUsageEntity(d1.toString(), "com.tencent.mm", "com.tencent.mm", 12, 30, "SOCIAL")
        )
        db.notificationEventDao().insertIgnore(
            listOf(
                com.screentrace.app.data.db.NotificationEventEntity(
                    msOf(d1, 10), NotificationType.SEEN.name, "com.tencent.mm", null
                )
            )
        )

        val detail = withResolver.metricDetail(MetricKind.NOTIFICATION, DateSelection.Day(d1))
        assertEquals("微信", detail.appRanking.first().name)
    }

    // ---------- P1-1：用户分类覆盖 ----------

    @Test
    fun `用户覆盖的分类优先于内置映射`() = runBlocking {
        db.appUsageDao().insert(AppUsageEntity(d1.toString(), "com.tencent.mm", "微信", 30, 12, "SOCIAL"))

        val plain = repo.appRanking(DateSelection.Day(d1), 10).first()
        assertEquals("内置映射：微信属社交沟通", AppCategory.SOCIAL, plain.category)

        db.close()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ScreenTraceDatabase::class.java
        ).allowMainThreadQueries().build()
        db.appUsageDao().insert(AppUsageEntity(d1.toString(), "com.tencent.mm", "微信", 30, 12, "SOCIAL"))
        val settings = newSettingsStore()
        settings.setCategoryOverride("com.tencent.mm", AppCategory.GAME)
        val withOverride = RoomUsageRepository(db, settings)
            .appRanking(DateSelection.Day(d1), 10)
            .first()

        assertEquals("用户改过的分类必须盖住内置表", AppCategory.GAME, withOverride.category)
    }

    @Test
    fun `无记录时解锁详情不伪造数据点数值`() = runBlocking {
        val detail = repo.metricDetail(MetricKind.UNLOCK, DateSelection.Day(d1))
        val point = detail.points.first()
        assertEquals(0, point.value)
        assertEquals(Coverage.NO_RECORD, point.coverage)
        assertNull("窗口外不得给出峰值时段", detail.peakHour)
    }

    // ---------- helpers ----------

    private fun newSettingsStore(): SettingsStore {
        val file = File(
            ApplicationProvider.getApplicationContext<Context>().cacheDir,
            "settings-${System.nanoTime()}.preferences_pb"
        )
        return SettingsStore(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            ) { file }
        )
    }

    private fun msOf(date: LocalDate, hour: Int): Long =
        date.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
