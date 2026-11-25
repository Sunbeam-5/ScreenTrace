package com.screentrace.app.data.parse

import com.screentrace.app.data.IngestSanity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * CR-30-R11 期望行为回归测试（由原缺陷特征化测试按定稿口径重写）：
 * - 线 2（方案甲）：跨自然日 checkin 24 小时区间（如 10:31 -> 次日 10:31）进入 `usageWindows`，
 *   不再整段归属结束日，单日 `appUsage` 为空；
 * - E3 / W1 隔离：仅有未闭合 SCREEN_INTERACTIVE 锚点或仅有 `screen-interactive` 聚合读数时，
 *   只记入 `screenEvidence`（ANCHOR / WINDOW），不伪造 `screenSessions`；
 * - 门禁保留 `>` 1440：单日恰好 1440 分钟是长亮屏真值，允许通过 `IngestSanity`。
 */
class UsageDumpParserIntervalAttributionTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val d22: LocalDate = LocalDate.of(2026, 11, 22)
    private val d23: LocalDate = LocalDate.of(2026, 11, 23)

    private fun ms(date: LocalDate, h: Int, m: Int, s: Int = 0): Long =
        date.atTime(h, m, s).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `线2 跨日24小时 checkin 区间进入 usageWindows 且不污染单日 appUsage`() {
        val begin = ms(d22, 10, 31)
        val end = ms(d23, 10, 31)
        val checkin = buildString {
            appendLine("user=0")
            appendLine("  beginTime=$begin endTime=$end ")
            appendLine("    screen-interactive: 1x for 86400000")
            appendLine(
                "      package=com.screentrace.app totalTimeUsed=86400000 lastTimeUsed=" +
                    end + " appLaunchCount=2"
            )
        }

        val result = UsageDumpParser.parse(checkin, "", zone, null)

        // 跨日窗口不再整段记入结束日单日 appUsage
        assertTrue("跨日 checkin 区间不得落入单日 appUsage", result.appUsage.isEmpty())
        assertEquals(1, result.usageWindows.size)
        val w = result.usageWindows.single()
        assertEquals("com.screentrace.app", w.pkg)
        assertEquals(begin, w.startMs)
        assertEquals(end, w.endMs)
        assertEquals(86_400_000L, w.totalMs)
        assertEquals(2, w.launches)

        // W1 计数器读数进入 screenEvidence，永不进入 screenSessions
        assertTrue("W1 不得写入精确 screenSessions", result.screenSessions.isEmpty())
        val w1 = result.screenEvidence.single()
        assertEquals("WINDOW", w1.evidenceType)
        assertEquals("WINDOW", w1.quality)
        assertEquals(86_400_000L, w1.measuredMs)
    }

    @Test
    fun `单日恰好 1440 分钟属于长亮屏真值 IngestSanity 不得误杀`() {
        val row = AppUsageRow(d23, "com.screentrace.app", totalMs = 86_400_000L, launches = 1)
        val verdict = IngestSanity.check(emptyMap(), listOf(row), emptyMap())
        assertTrue("恰好 1440 分钟应放行", verdict.droppedDates.isEmpty())
        assertEquals(1, verdict.appUsage.size)
    }

    @Test
    fun `线1 仅有未闭合 SCREEN_INTERACTIVE 锚点时记录 E3 ANCHOR_ONLY 且不伪造会话时长`() {
        val human = buildString {
            appendLine("user=0")
            appendLine("  In-memory daily stats")
            appendLine("  timeRange=\"2026/11/23 09:23\u201310:40\"")
            appendLine("    packages")
            appendLine(
                "      package=com.screentrace.app totalTimeUsed=\"1:15:19\" " +
                    "lastTimeUsed=\"2026-11-23 10:39:01\" appLaunchCount=2"
            )
            appendLine("  Last 24 hour events (timeRange=\"2026/11/22 10:40 \u2013 2026/11/23 10:40\" )")
            appendLine("    time=\"2026-11-23 09:23:00\" type=SCREEN_INTERACTIVE package=android flags=0x0")
        }

        val result = UsageDumpParser.parse("", human, zone, null)

        assertTrue("E3 纯锚点不得伪造 screenSessions", result.screenSessions.isEmpty())
        assertEquals(1, result.screenEvidence.size)
        val e3 = result.screenEvidence.single()
        assertEquals("ANCHOR", e3.evidenceType)
        assertEquals("ANCHOR_ONLY", e3.quality)
        assertNull("E3 纯锚点不得产出 endMs", e3.endMs)
    }
}
