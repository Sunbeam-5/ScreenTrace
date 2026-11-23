package com.screentrace.app.data.parse

import com.screentrace.app.data.IngestSanity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * CR-30-A5-R11「屏幕长亮后读不到时间」的**缺陷现状特征化测试**。
 *
 * ⚠️ 这里的断言**描述的是缺陷现状，不是期望行为**——GPT 评审定方案后，这两条必须改写为
 * 期望行为（届时它们应当变红，然后按新口径重写）。诊断全文见
 * `docs/CR-30-A5-R11-长亮屏读不到时间-诊断报告.md`。
 *
 * 两条分别锁住两条故障线：
 * · 线 1（漏算）：dump 里没有 SCREEN 事件时，解析器产不出任何亮屏会话 →
 *   该日亮屏记 0（真相是屏幕一直亮着）。这是「滚动缓冲把开机事件挤掉」的可见后果。
 * · 线 2（错日归属）：checkin 的 24 小时区间被**整体**记到区间结束日。
 */
class UsageDumpParserIntervalAttributionTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val d22: LocalDate = LocalDate.of(2026, 11, 22)
    private val d23: LocalDate = LocalDate.of(2026, 11, 23)

    private fun ms(date: LocalDate, h: Int, m: Int, s: Int = 0): Long =
        date.atTime(h, m, s).atZone(zone).toInstant().toEpochMilli()

    /** 线 2：24 小时 checkin 区间 → 整段记到区间结束日。 */
    @Test
    fun defect_characterization_checkin24hIntervalBooksWholeWindowToEndDay() {
        val begin = ms(d22, 10, 31)
        val end = ms(d23, 10, 31)
        val checkin = buildString {
            appendLine("user=0")
            appendLine("  beginTime=" + begin + " endTime=" + end + " ")
            appendLine(
                "      package=com.screentrace.app totalTimeUsed=86400000 lastTimeUsed=" +
                    end + " appLaunchCount=2"
            )
        }

        val result = UsageDumpParser.parse(checkin, "", zone, null)

        // 现状：24 小时整段落在这天（区间结束日），前一天一行都没有。
        assertEquals(1, result.appUsage.size)
        val row = result.appUsage.first()
        assertEquals("com.screentrace.app", row.pkg)
        assertEquals(d23, row.date)
        assertEquals(86_400_000L, row.totalMs)   // 1440 分钟 = 整整一天
        assertTrue(
            "现状是错日归属（整段 24h 记到结束日），方案裁决后此断言应改写",
            result.appUsage.none { it.date == d22 }
        )

        // 现状：当天没有任何屏幕指标（screenMin 缺失）。
        assertTrue(result.days.isEmpty())

        // 现状：入库自洽性校验不丢弃这行（两条门禁都恰好放行「正好 1440」）。
        val verdict = IngestSanity.check(result.days, result.appUsage, result.hourMinutes)
        assertTrue("现状是门禁未拦（> 24h 判据用严格大于）", verdict.droppedDates.isEmpty())
        assertEquals(1, verdict.appUsage.size)
    }

    /** 线 1：dump 里没有 SCREEN 事件 → 产不出亮屏会话（该日亮屏记 0）。 */
    @Test
    fun defect_characterization_dumpWithoutScreenEventsYieldsNoScreenSession() {
        val human = buildString {
            appendLine("user=0")
            appendLine("  In-memory daily stats")
            appendLine("  timeRange=\"2026/11/23 09:23\u201310:40\"")
            appendLine("    packages")
            appendLine(
                "      package=com.screentrace.app totalTimeUsed=\"1:15:19\" " +
                    "lastTimeUsed=\"2026-11-23 10:39:01\" appLaunchCount=2"
            )
        }

        val result = UsageDumpParser.parse("", human, zone, null)

        // 现状：屏幕事件已滚出缓冲 → 亮屏会话为空 → 该日没有任何屏幕指标。
        assertTrue("现状是没有亮屏会话（屏幕一直亮着但事件已过期）", result.screenSessions.isEmpty())
        assertTrue(result.days.isEmpty())
        assertTrue(result.hourMinutes.isEmpty())
    }
}
