package com.screentrace.app.data.parse

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-07 Review 修复（P1-2）：单通道有界连续观测窗口与 6 类屏幕状态机截断门禁测试（覆盖 M-CR2 / M-CR3）。
 */
class ScreenStreamAuditTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val humanFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private fun ms(date: LocalDate, hour: Int, minute: Int = 0, second: Int = 0, millis: Int = 0): Long =
        date.atTime(hour, minute, second, millis * 1_000_000)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun checkinLine(ms: Long, type: String, pkg: String = "android"): String =
        "  time=$ms type=$type package=$pkg"

    private fun humanLine(date: LocalDate, hour: Int, minute: Int, second: Int, type: String, pkg: String = "android"): String {
        val ts = date.atTime(hour, minute, second).format(humanFmt)
        return "    time=\"$ts\" type=$type package=$pkg flags=0x0"
    }

    @Test
    fun mCr2_nonScreenAndKeyguardHiddenAloneCannotOpenObservedWindow() {
        val d1 = LocalDate.of(2026, 11, 20)
        val d2 = LocalDate.of(2026, 11, 21)
        val checkin = buildString {
            appendLine("user=0")
            appendLine(checkinLine(ms(d1, 23, 50), "ACTIVITY_RESUMED", "com.tencent.mm"))
            appendLine(checkinLine(ms(d1, 23, 55), "KEYGUARD_HIDDEN", "android"))
            appendLine(checkinLine(ms(d2, 0, 10), "ACTIVITY_PAUSED", "com.tencent.mm"))
        }

        val parsed = UsageDumpParser.parse(checkin, "", zone, capturedAtMs = ms(d2, 1, 0))
        assertNull("无 SCREEN_INTERACTIVE/NON_INTERACTIVE 时不得签发 observedWindow", parsed.observedWindow)
    }

    @Test
    fun mCr2_crossChannelOldCheckinAndTodayHumanNeverStitchAcrossDays() {
        val dOld = LocalDate.of(2026, 11, 17)
        val dToday = LocalDate.of(2026, 11, 20)
        val checkin = buildString {
            appendLine("user=0")
            appendLine(checkinLine(ms(dOld, 10, 0), "SCREEN_INTERACTIVE"))
            appendLine(checkinLine(ms(dOld, 11, 0), "SCREEN_NON_INTERACTIVE"))
        }
        val human = buildString {
            appendLine("user=0")
            appendLine(humanLine(dToday, 8, 0, 0, "SCREEN_INTERACTIVE"))
            appendLine(humanLine(dToday, 9, 0, 0, "SCREEN_NON_INTERACTIVE"))
        }

        // capturedAtMs 为今日 10:00：checkin 距今 3 天（>28h）失效，独立采用 human 今日窗口，绝不跨通道混拼
        val parsed = UsageDumpParser.parse(checkin, human, zone, capturedAtMs = ms(dToday, 10, 0))
        val win = parsed.observedWindow
        assertNotNull(win)
        assertEquals(ms(dToday, 8, 0), win!!.startMs)
        assertEquals(ms(dToday, 10, 0), win.endMs)
        assertTrue(win.endMs - win.startMs <= ScreenStreamAudit.MAX_SINGLE_PULL_WINDOW_MS)
    }

    @Test
    fun mCr3_leadingNonInteractiveMarksStartDateTruncated() {
        val day = LocalDate.of(2026, 11, 20)
        val checkin = buildString {
            appendLine("user=0")
            appendLine(checkinLine(ms(day, 0, 5), "SCREEN_NON_INTERACTIVE"))
            appendLine(checkinLine(ms(day, 9, 0), "SCREEN_INTERACTIVE"))
            appendLine(checkinLine(ms(day, 10, 0), "SCREEN_NON_INTERACTIVE"))
        }

        val parsed = UsageDumpParser.parse(checkin, "", zone, capturedAtMs = ms(day.plusDays(1), 0, 5))
        assertTrue("首条孤立 SCREEN_NON_INTERACTIVE 必须将该日记入 truncatedScreenDates", day in parsed.truncatedScreenDates)
    }

    @Test
    fun mCr3_trailingUnclosedInteractiveAcrossMidnightMarksBothDatesTruncated() {
        val yesterday = LocalDate.of(2026, 11, 20)
        val today = LocalDate.of(2026, 11, 21)
        val checkin = buildString {
            appendLine("user=0")
            appendLine(checkinLine(ms(yesterday, 8, 0), "SCREEN_INTERACTIVE"))
            appendLine(checkinLine(ms(yesterday, 9, 0), "SCREEN_NON_INTERACTIVE"))
            appendLine(checkinLine(ms(yesterday, 23, 50), "SCREEN_INTERACTIVE"))
        }

        val parsed = UsageDumpParser.parse(checkin, "", zone, capturedAtMs = ms(today, 0, 30))
        assertTrue("昨日 23:50 未闭合亮屏跨午夜，昨日必须截断", yesterday in parsed.truncatedScreenDates)
        assertTrue("跨午夜未闭合亮屏同时覆盖今日", today in parsed.truncatedScreenDates)
    }

    @Test
    fun mCr3_consecutiveSameStateAndSameTimestampConflictMarkTruncated() {
        val d1 = LocalDate.of(2026, 11, 20)
        val d2 = LocalDate.of(2026, 11, 21)

        // 连续 ON->ON（跨日）
        val onOn = ScreenStreamAudit.audit(
            events = listOf(
                ScreenStateEvent(ms(d1, 23, 0), ScreenStreamAudit.TYPE_INTERACTIVE, user = 0),
                ScreenStateEvent(ms(d2, 1, 0), ScreenStreamAudit.TYPE_INTERACTIVE, user = 0),
                ScreenStateEvent(ms(d2, 2, 0), ScreenStreamAudit.TYPE_NON_INTERACTIVE, user = 0)
            ),
            zone = zone,
            capturedAtMs = ms(d2, 3, 0)
        )
        assertTrue(d1 in onOn.truncatedDates)
        assertTrue(d2 in onOn.truncatedDates)

        // 同时间戳 ON + OFF 冲突
        val conflict = ScreenStreamAudit.audit(
            events = listOf(
                ScreenStateEvent(ms(d1, 10, 0), ScreenStreamAudit.TYPE_INTERACTIVE, user = 0),
                ScreenStateEvent(ms(d1, 10, 0), ScreenStreamAudit.TYPE_NON_INTERACTIVE, user = 0)
            ),
            zone = zone,
            capturedAtMs = ms(d1, 12, 0)
        )
        assertTrue(d1 in conflict.truncatedDates)
    }

    @Test
    fun mCr3_multiAccountMirrorEventsWithin1000msDoNotTriggerFalseTruncation() {
        val day = LocalDate.of(2026, 11, 20)
        val checkin = buildString {
            appendLine("user=0")
            appendLine(checkinLine(ms(day, 9, 0, 0, 100), "SCREEN_INTERACTIVE"))
            appendLine(checkinLine(ms(day, 10, 0, 0, 100), "SCREEN_NON_INTERACTIVE"))
            appendLine("user=666")
            appendLine(checkinLine(ms(day, 9, 0, 0, 350), "SCREEN_INTERACTIVE"))
            appendLine(checkinLine(ms(day, 10, 0, 0, 350), "SCREEN_NON_INTERACTIVE"))
            appendLine("user=999")
            appendLine(checkinLine(ms(day, 9, 0, 0, 700), "SCREEN_INTERACTIVE"))
            appendLine(checkinLine(ms(day, 10, 0, 0, 700), "SCREEN_NON_INTERACTIVE"))
        }

        val parsed = UsageDumpParser.parse(checkin, "", zone, capturedAtMs = ms(day, 12, 0))
        assertNotNull(parsed.observedWindow)
        assertTrue("多账户 ±1000ms 正常镜像事件绝不能误报截断：${parsed.truncatedScreenDates}", parsed.truncatedScreenDates.isEmpty())
    }

    @Test
    fun checkinMillisecondPairIsNotFalseKilledByHumanSecondPrecision() {
        val day = LocalDate.of(2026, 11, 20)
        val checkin = buildString {
            appendLine("user=0")
            appendLine(checkinLine(ms(day, 9, 0, 0, 100), "SCREEN_INTERACTIVE"))
            appendLine(checkinLine(ms(day, 9, 0, 0, 800), "SCREEN_NON_INTERACTIVE"))
        }
        // human 只有秒精度，两条落到同一秒 09:00:00
        val human = buildString {
            appendLine("user=0")
            appendLine(humanLine(day, 9, 0, 0, "SCREEN_INTERACTIVE"))
            appendLine(humanLine(day, 9, 0, 0, "SCREEN_NON_INTERACTIVE"))
        }

        val parsed = UsageDumpParser.parse(checkin, human, zone, capturedAtMs = ms(day, 12, 0))
        assertNotNull(parsed.observedWindow)
        assertTrue("checkin 毫秒配对有效时不得被 human 1s 精度误杀", parsed.truncatedScreenDates.isEmpty())
    }
}
