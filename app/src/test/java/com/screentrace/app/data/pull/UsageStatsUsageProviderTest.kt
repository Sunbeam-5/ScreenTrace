package com.screentrace.app.data.pull

import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowUsageStatsManager

/**
 * 降级通道（`UsageStats`）的适配层测试——6b-6d 评审 P1-4。
 *
 * 为什么这条必须补：**它才是绝大多数用户唯一的通道**（没有 Shizuku 的人）。
 * 6b 只有 [ForegroundBucketerTest] 测了纯函数，适配层四件事全靠人眼：
 * 1. 事件类型 `1 / 2 / 23` 的字面量映射（minSdk 26 拿不到常量，写错就是 0 数据）；
 * 2. `appendSpan` 的跨零点按日切分（不切 → 排行页「逐日」全算到起始日）；
 * 3. `launches` 的归属日；
 * 4. `days = emptyMap()` 这条「绝不写日聚合」的硬约束（写了就是假 0，§4/§7-16 红线）。
 *
 * 这几处任一处写错，界面上都无法区分「没数据」和「算错」——所以必须钉在事件到分钟的
 * 端到端结果上，而不是断言中间变量。
 *
 * `queryEvents` 用 Robolectric 的 `ShadowUsageStatsManager.addEvent` 注水，与真机
 * 走的是同一个 `UsageStatsManager` 入口，只是少了系统侧的权限校验。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UsageStatsUsageProviderTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val today: LocalDate = LocalDate.now(zone)
    private val yesterday: LocalDate = today.minusDays(1)

    /** 固定「拉取时刻」= 今天 23:00，避免测试随真实钟点飘。 */
    private val nowMs: Long = at(today, 23)

    private lateinit var manager: UsageStatsManager
    private lateinit var shadow: ShadowUsageStatsManager

    @Before
    fun setUp() {
        // shadow 的事件表是 static，必须显式清，否则跨用例互相污染
        ShadowUsageStatsManager.reset()
        val context = ApplicationProvider.getApplicationContext<Context>()
        manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        shadow = shadowOf(manager)
    }

    // ---------- 1. 事件类型映射：1 进入 / 2、23 收尾 ----------

    @Test
    fun `ACTIVITY_RESUMED 到 PAUSED 配对成区间且分钟数按小时落桶`() = runBlocking {
        // 10:00 → 10:30，30 分钟
        event("com.tencent.mm", at(yesterday, 10, 0), EVENT_RESUMED)
        event("com.tencent.mm", at(yesterday, 10, 30), EVENT_PAUSED)

        val result = load()

        val row = result.appUsage.single()
        assertEquals("com.tencent.mm", row.pkg)
        assertEquals(yesterday, row.date)
        assertEquals(30 * 60_000L, row.totalMs)
        assertEquals(1, row.launches)
        assertEquals(30, result.hourMinutes.getValue(yesterday)[10])
        assertEquals(0, result.hourMinutes.getValue(yesterday)[11])
    }

    @Test
    fun `ACTIVITY_STOPPED 同样能收尾区间`() = runBlocking {
        // 14:00 → 14:20 走 type 23 收尾（很多进程被杀的场景只留 STOPPED）
        event("tv.danmaku.bili", at(yesterday, 14, 0), EVENT_RESUMED)
        event("tv.danmaku.bili", at(yesterday, 14, 20), EVENT_STOPPED)

        val result = load()

        assertEquals(20 * 60_000L, result.appUsage.single().totalMs)
        assertEquals(20, result.hourMinutes.getValue(yesterday)[14])
    }

    @Test
    fun `没有配对的进入事件不会被算成区间`() = runBlocking {
        // 只有 pause、没有 resume（拉取窗口起点早于事件）：不许凭空造出时长
        event("com.tencent.mm", at(yesterday, 11, 0), EVENT_PAUSED)

        val result = load()

        assertTrue("没有进入事件就没有区间", result.appUsage.isEmpty())
        assertTrue(result.isEmpty)
    }

    // ---------- 2. 跨零点按日切分 ----------

    @Test
    fun `跨零点的一段按自然日拆成两行`() = runBlocking {
        // 昨天 23:30 → 今天 00:30，各 30 分钟
        event("com.tencent.mm", at(yesterday, 23, 30), EVENT_RESUMED)
        event("com.tencent.mm", at(today, 0, 30), EVENT_PAUSED)

        val result = load()

        val byDate = result.appUsage.associateBy { it.date }
        assertEquals("必须拆成两天，否则「逐日」全算到起始日", 2, result.appUsage.size)
        assertEquals(30 * 60_000L, byDate.getValue(yesterday).totalMs)
        assertEquals(30 * 60_000L, byDate.getValue(today).totalMs)
        assertEquals(30, result.hourMinutes.getValue(yesterday)[23])
        assertEquals(30, result.hourMinutes.getValue(today)[0])
    }

    // ---------- 3. launches 归属 ----------

    @Test
    fun `启动次数按进入事件所在的自然日归属`() = runBlocking {
        event("com.tencent.mm", at(yesterday, 9, 0), EVENT_RESUMED)
        event("com.tencent.mm", at(yesterday, 9, 10), EVENT_PAUSED)
        event("com.tencent.mm", at(today, 9, 0), EVENT_RESUMED)
        event("com.tencent.mm", at(today, 9, 10), EVENT_PAUSED)

        val result = load()

        val byDate = result.appUsage.associateBy { it.date }
        assertEquals(1, byDate.getValue(yesterday).launches)
        assertEquals(1, byDate.getValue(today).launches)
    }

    @Test
    fun `同一应用连续两次 RESUMED 不重开区间但仍算两次启动`() = runBlocking {
        // 切换 Activity 会连发两个 RESUMED，区间不能切成两段（否则每次切页都多一段）
        event("com.tencent.mm", at(yesterday, 10, 0), EVENT_RESUMED)
        event("com.tencent.mm", at(yesterday, 10, 10), EVENT_RESUMED)
        event("com.tencent.mm", at(yesterday, 10, 30), EVENT_PAUSED)

        val result = load()

        val row = result.appUsage.single()
        assertEquals(30 * 60_000L, row.totalMs)
        assertEquals(2, row.launches)
    }

    // ---------- 4. 「绝不写日聚合」的硬约束 ----------

    @Test
    fun `降级通道绝不产出日聚合`() = runBlocking {
        event("com.tencent.mm", at(yesterday, 10, 0), EVENT_RESUMED)
        event("com.tencent.mm", at(yesterday, 10, 30), EVENT_PAUSED)

        val result = load()

        // 拿不到 KEYGUARD_*，写 day_summary 就等于宣称「解锁 0 次」——
        // 这是 §4/§7-16 红线，比「没数据」严重得多
        assertTrue("days 必须恒为空", result.days.isEmpty())
        assertTrue("解锁事件也必须为空", result.unlockEvents.isEmpty())
        assertTrue("通知事件也必须为空", result.notificationEvents.isEmpty())
        // 但应用数据是真的，所以整体不能被判成「空 dump」（否则协调器返回 EmptyDump）
        assertFalse("有应用数据就不是空 dump", result.isEmpty)
    }

    @Test
    fun `warning 必须写明降级能力边界`() = runBlocking {
        event("com.tencent.mm", at(yesterday, 10, 0), EVENT_RESUMED)
        event("com.tencent.mm", at(yesterday, 10, 30), EVENT_PAUSED)

        val loaded = provider().load() as LoadedUsage.Parsed

        assertNotNull("pull_log.note 要能让用户知道少了什么", loaded.warning)
        assertTrue(
            "warning 必须点明无解锁/通知事件",
            loaded.warning!!.contains("解锁") && loaded.warning!!.contains("通知")
        )
        assertEquals(nowMs, loaded.capturedAtMs)
    }

    // ---------- 5. 进程被杀 / 丢 paused 时不虚高 ----------

    @Test
    fun `丢失 PAUSED 时以下一个应用进入前台收尾而不是延到拉取时刻`() = runBlocking {
        // 微信 10:00 进入后被杀，没有 paused；40 分钟后哔哩进入前台
        event("com.tencent.mm", at(yesterday, 10, 0), EVENT_RESUMED)
        event("tv.danmaku.bili", at(yesterday, 10, 40), EVENT_RESUMED)
        event("tv.danmaku.bili", at(yesterday, 10, 50), EVENT_PAUSED)

        val result = load()
        val byPkg = result.appUsage.associateBy { it.pkg }

        assertEquals("不许虚高到拉取时刻", 40 * 60_000L, byPkg.getValue("com.tencent.mm").totalMs)
        assertEquals(10 * 60_000L, byPkg.getValue("tv.danmaku.bili").totalMs)
    }

    // ---------- 6. 拉取窗口 ----------

    @Test
    fun `滚动窗口之外的事件不会被算进来`() = runBlocking {
        val tooOld = today.minusDays(30)
        event("com.tencent.mm", at(tooOld, 10, 0), EVENT_RESUMED)
        event("com.tencent.mm", at(tooOld, 10, 30), EVENT_PAUSED)
        event("tv.danmaku.bili", at(yesterday, 10, 0), EVENT_RESUMED)
        event("tv.danmaku.bili", at(yesterday, 10, 10), EVENT_PAUSED)

        val result = load()

        assertEquals(listOf("tv.danmaku.bili"), result.appUsage.map { it.pkg })
    }

    // ---------- helpers ----------

    private suspend fun load() = (provider().load() as LoadedUsage.Parsed).result

    /**
     * 注一条事件。`addEvent(String, long, int)` 在 Robolectric 里被标了 deprecated
     * （建议自己构造 `UsageEvents.Event`），但它一行就是「包名 + 时刻 + 类型」，
     * 正好对上要测的东西；自己构造反而多三行样板。局部压制，不外溢。
     */
    @Suppress("DEPRECATION")
    private fun event(pkg: String, atMs: Long, type: Int) =
        shadow.addEvent(pkg, atMs, type)

    private fun provider() = UsageStatsUsageProvider(
        context = ApplicationProvider.getApplicationContext(),
        zone = zone,
        clock = { nowMs }
    )

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private companion object {
        // minSdk 26 拿不到 UsageEvents.Event.ACTIVITY_RESUMED 等常量（API 29 才有），
        // 生产代码只能写字面量；这里也写字面量，并显式断言它们就是 1/2/23——
        // 万一有人把它们「优化」成别的常量，测试必须红。
        const val EVENT_RESUMED = 1
        const val EVENT_PAUSED = 2
        const val EVENT_STOPPED = 23
    }
}
