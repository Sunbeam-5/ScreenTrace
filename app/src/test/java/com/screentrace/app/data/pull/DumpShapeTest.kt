package com.screentrace.app.data.pull

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * dump 形态判定的回归。
 *
 * 两次踩坑，方向正好相反：
 * 1. 最早用 `package=` 判 checkin —— 明文 App Standby 段落满屏都是它，必误判；
 * 2. 后来改用 `totalTimeUsed=` / `screen-interactive:` —— 这两串**明文里也有**
 *    （`totalTimeUsed="23:06:08"`、`screen-interactive: 1x for "00:00"`），于是
 *    4.17MB 的完整明文被判成 CHECKIN 喂给 CSV 解析器，一行都匹配不上。
 *
 * 所以这里的核心断言是：**明文永远不能判成 CHECKIN**，且必须能分辨
 * 「明文带了数据」和「明文只有元信息」（真机上 --checkin 只吐 561KB 元信息）。
 */
class DumpShapeTest {

    /** 只吐元信息、没有使用数据的明文（真机 `--checkin` 的输出形态）。 */
    private val bareMetaDump = """
        Flags:
            android.app.usage.user_interaction_type_api: true
        user=0
        user=999
        Broadcast response stats:
            notification:+2d22h13m13s724s; event=<posted>, pkg=com.xiaomi.smarthome, usr=0
        CountQuotaTracker:
        User 0 App Standby States:
          package=com.miui.screenrecorder u=0 bucket=5 reason=d used=+163d16h12m37s559ms
        Settings:
      """.trimIndent()

    /**
     * 带数据的完整明文（真机 `dumpsys usagestats` 不带参数时的关键片段）。
     *
     * 刻意保留 `totalTimeUsed="23:06:08"` 和 `screen-interactive: 1x for "00:00"`
     * 这两处**与 checkin 特征撞车**的写法，作为防误判的活样本。
     */
    private val richPlainDump = """
        Flags:
            android.app.usage.user_interaction_type_api: true
        user=0 
          Last 24 hour events (timeRange="2026/11/10 08:58 – 2026/11/11 08:58" )
            time="2026-11-10 11:15:10" type=SCREEN_INTERACTIVE package=android flags=0x0 
            time="2026-11-11 08:16:33" type=ACTIVITY_RESUMED package=com.xiaomi.smarthome class=x flags=0x0 
            time="2026-11-11 08:12:44" type=NOTIFICATION_INTERRUPTION package=tv.danmaku.bili channelId=mipush|tv.danmaku.bili|1 flags=0x0 
          In-memory daily stats
          timeRange="2026/11/10 09:15 – 2026/11/11 08:55" 
            packages
              package=com.xiaomi.smarthome totalTimeUsed="23:06:08" lastTimeUsed="2026-11-11 08:26:00" appLaunchCount=0
            event aggregations
              screen-interactive: 1x for "00:00" (now running, started at )
              screen-non-interactive: 1x for "02:25"
      """.trimIndent()

    /** checkin CSV 骨架。 */
    private val checkinDump = """
        Days:
        0,2026-11-10,765,0,3976,765
        packages:
        0,com.xiaomi.smarthome,0,1386,0,1794300000000,totalTimeUsed=1386,appLaunchCount=0
      """.trimIndent()

    @Test
    fun `带数据的完整明文判成 PLAIN 且绝不能当 checkin`() {
        val shape = DumpShape.of(richPlainDump)
        assertEquals(DumpShape.Kind.PLAIN, shape.kind)
        assertFalse("明文绝不能被当作 checkin 喂进 CSV 解析器", shape.looksLikeCheckin)
        assertTrue("这份明文确实带数据，应可作主输入", shape.usableAsPlain)
    }

    @Test
    fun `明文里的 totalTimeUsed 带引号不是 checkin 特征`() {
        // 防回归：早先用 `totalTimeUsed=` 判 checkin，而明文写的是 totalTimeUsed="23:06:08"
        assertTrue("前提：明文里确实有该串", richPlainDump.contains("totalTimeUsed="))
        assertFalse(DumpShape.of(richPlainDump).looksLikeCheckin)
    }

    @Test
    fun `明文里的 screen-interactive 带引号不是 checkin 特征`() {
        // CSV 是 `screen-interactive: 1x for 1386`（纯数字毫秒）；明文带引号且是 HH:MM
        assertTrue(richPlainDump.contains("screen-interactive:"))
        assertFalse(DumpShape.of(richPlainDump).looksLikeCheckin)
    }

    @Test
    fun `只有元信息的明文标成无数据`() {
        val shape = DumpShape.of(bareMetaDump)
        assertEquals(DumpShape.Kind.PLAIN, shape.kind)
        assertFalse("这份 561KB 的元信息里一个事件都没有，不能当数据源", shape.usableAsPlain)
        assertTrue("摘要要能看出没有数据，便于排查", shape.summary.contains("nodata"))
    }

    @Test
    fun `明文里的 package 行不是 checkin 特征`() {
        // 这一条是防回归的核心：曾经想过用 `package=` 判形态，而明文满屏都是它
        assertTrue("前提：明文里确实有 package= 行", bareMetaDump.contains("package="))
        assertFalse(DumpShape.of(bareMetaDump).looksLikeCheckin)
    }

    @Test
    fun `checkin 判成 CHECKIN`() {
        val shape = DumpShape.of(checkinDump)
        assertEquals(DumpShape.Kind.CHECKIN, shape.kind)
        assertTrue(shape.looksLikeCheckin)
    }

    @Test
    fun `只有 Days 段的残缺 checkin 也能认出来`() {
        // 拉取被截断时可能只剩聚合段：仍应判定为 checkin，由截断检测去报错
        val truncated = "Days:\n0,2026-11-10,765,0,3976,765"
        assertTrue(DumpShape.of(truncated).looksLikeCheckin)
    }

    @Test
    fun `无引号的 screen-interactive 毫秒值是 checkin 特征`() {
        val csv = "beginTime=1794300000000 endTime=1794386399999\n  screen-interactive: 2x for 45900000"
        assertTrue(DumpShape.of(csv).looksLikeCheckin)
    }

    @Test
    fun `空输出判成 EMPTY`() {
        assertEquals(DumpShape.Kind.EMPTY, DumpShape.of("").kind)
        assertEquals(DumpShape.Kind.EMPTY, DumpShape.of("   \n\n ").kind)
    }

    @Test
    fun `报错文本判成 UNKNOWN 而不是误判成两种已知格式`() {
        val shape = DumpShape.of("Exception occurred while dumping:\njava.lang.SecurityException")
        assertEquals(DumpShape.Kind.UNKNOWN, shape.kind)
        assertFalse(shape.looksLikeCheckin)
        assertFalse(shape.usableAsPlain)
    }

    @Test
    fun `摘要带体积与形态便于追溯`() {
        val summary = DumpShape.of(checkinDump).summary
        assertTrue("要有体积", summary.contains("MB"))
        assertTrue("要有形态", summary.contains("checkin"))
    }
}
