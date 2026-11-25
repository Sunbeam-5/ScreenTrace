package com.screentrace.app.data

import com.screentrace.app.data.backup.BackupCodec
import com.screentrace.app.data.backup.BackupDevice
import com.screentrace.app.data.backup.BackupFile
import com.screentrace.app.data.backup.DaySummaryDto
import com.screentrace.app.data.parse.UsageDumpParser
import com.screentrace.app.data.pull.DumpShape
import com.screentrace.app.data.pull.LoadedUsage
import com.screentrace.app.data.pull.ShellRunner
import com.screentrace.app.data.pull.ShizukuShell
import com.screentrace.app.data.pull.ShizukuUsageProvider
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TD-Batch2（TD-02 / TD-03）：内存与解析优化的等价性及双向兼容回归单测。
 * 覆盖行为变异锚点 M-TD5、M-TD6、M-TD7。
 */
class MemoryOptimizationRegressionTest {

    @Test
    fun `M_TD5_BackupCodec默认导出紧凑JSON且与旧版prettyPrint备份双向兼容`() {
        val file = BackupFile(
            schemaVersion = BackupCodec.SCHEMA_VERSION,
            exportedAt = "2026-11-25 14:00",
            appVersion = "0.1.0",
            device = BackupDevice("dev-1", "Redmi", "2407FRK8EC"),
            daySummary = listOf(
                DaySummaryDto("2026-11-25", 120, 100, 12, 20, 30, 1_700_000_000_000L)
            )
        )
        val encoded = BackupCodec.encode(file)
        assertFalse("TD-03 紧凑导出不应包含缩进换行", encoded.contains("\n"))

        val compactRef = Json { prettyPrint = false; encodeDefaults = true }.encodeToString(file)
        assertTrue(encoded.length <= (compactRef.length * 1.05).toInt())

        // 旧版 prettyPrint = true 备份仍能正常解回
        val legacyPretty = Json { prettyPrint = true; encodeDefaults = true }.encodeToString(file)
        val decodedLegacy = BackupCodec.decode(legacyPretty)
        assertTrue(decodedLegacy is BackupCodec.DecodeResult.Ok)
        assertEquals(1, (decodedLegacy as BackupCodec.DecodeResult.Ok).file.daySummary.size)

        val decodedCompact = BackupCodec.decode(encoded)
        assertTrue(decodedCompact is BackupCodec.DecodeResult.Ok)
        assertEquals(100, (decodedCompact as BackupCodec.DecodeResult.Ok).file.daySummary.first().screenUnlockMin)
    }

    @Test
    fun `M_TD6_DumpShape单遍扫描准确识别CHECKIN与PLAIN与UNKNOWN与EMPTY`() {
        assertEquals(DumpShape.Kind.EMPTY, DumpShape.of("   \n  ").kind)

        val checkinDays = DumpShape.of("Days: 2026-11-25\nbeginTime=1000 endTime=2000\ntype=SCREEN_INTERACTIVE")
        assertEquals(DumpShape.Kind.CHECKIN, checkinDays.kind)
        assertTrue(checkinDays.looksLikeCheckin)

        val checkinScreen = DumpShape.of("screen-interactive: 3x for 12000\n")
        assertEquals(DumpShape.Kind.CHECKIN, checkinScreen.kind)

        val plainWithData = DumpShape.of(
            """
            user=0
              In-memory daily stats
                timeRange="2026-11-25 00:00:00" to "2026-11-25 12:00:00"
                package=com.test.app totalTimeUsed="00:10:00" lastTimeUsed="2026-11-25 11:00:00"
            """.trimIndent()
        )
        assertEquals(DumpShape.Kind.PLAIN, plainWithData.kind)
        assertTrue(plainWithData.usableAsPlain)

        val plainNoData = DumpShape.of("App Standby States:\n  package=com.a bucket=10\n")
        assertEquals(DumpShape.Kind.PLAIN, plainNoData.kind)
        assertFalse(plainNoData.usableAsPlain)

        assertEquals(DumpShape.Kind.UNKNOWN, DumpShape.of("random error output").kind)
    }

    @Test
    fun `M_TD7_UsageDumpParser预筛不漏掉任何checkin或明文事件与包时长`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val day = LocalDate.of(2026, 11, 25)
        val human = """
            user=0
              In-memory daily stats
                timeRange="2026-11-25 08:00:00" to "2026-11-25 10:00:00"
                package=com.bench.a totalTimeUsed="00:25:00" lastTimeUsed="2026-11-25 09:30:00" appLaunchCount=4
              Last 24 hour events
                time="2026-11-25 08:05:00" type=SCREEN_INTERACTIVE package=android class=null
                time="2026-11-25 08:05:10" type=KEYGUARD_HIDDEN package=android class=null
                time="2026-11-25 08:15:00" type=NOTIFICATION_INTERRUPTION package=com.bench.a channelId=msg
                time="2026-11-25 08:20:00" type=SCREEN_NON_INTERACTIVE package=android class=null
        """.trimIndent()

        val result = UsageDumpParser.parse("", human, zone, day)
        assertEquals(1, result.unlockEvents.size)
        assertEquals(1, result.notificationEvents.size)
        assertEquals(1, result.screenSessions.size)
        assertTrue(result.appUsage.any { it.pkg == "com.bench.a" && it.totalMs == 25 * 60_000L && it.launches == 4 })
    }

    @Test
    fun `ShizukuUsageProvider先判checkin无效后再拉明文且结果不丢`() = runBlocking {
        val order = mutableListOf<String>()
        val fakeShell = object : ShellRunner {
            override suspend fun exec(command: String, timeoutMs: Long): String {
                order += command
                return when (command) {
                    ShizukuShell.CHECKIN_CMD -> "useless metadata only"
                    ShizukuShell.PLAIN_CMD -> """
                        user=0
                          In-memory daily stats
                            timeRange="2026-11-25 08:00:00" to "2026-11-25 10:00:00"
                            package=com.bench.a totalTimeUsed="00:10:00" lastTimeUsed="2026-11-25 09:00:00"
                    """.trimIndent()
                    else -> ""
                }
            }
        }
        val provider = ShizukuUsageProvider(
            shell = fakeShell,
            zone = ZoneId.of("Asia/Shanghai"),
            clock = { 1_764_036_000_000L }
        )
        val loaded = provider.load()
        assertTrue(loaded is LoadedUsage.Parsed)
        assertEquals(listOf(ShizukuShell.CHECKIN_CMD, ShizukuShell.PLAIN_CMD), order)
        assertTrue((loaded as LoadedUsage.Parsed).warning!!.contains("明文主导"))
    }
}
