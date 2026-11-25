package com.screentrace.app.data

import com.screentrace.app.data.backup.BackupCodec
import com.screentrace.app.data.backup.BackupDevice
import com.screentrace.app.data.backup.BackupFile
import com.screentrace.app.data.backup.DaySummaryDto
import com.screentrace.app.data.backup.NotificationEventDto
import com.screentrace.app.data.backup.ScreenSessionDto
import com.screentrace.app.data.backup.UnlockEventDto
import com.screentrace.app.data.parse.UsageDumpParser
import com.screentrace.app.data.pull.DumpShape
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TD-02 / TD-03：合成大数据量基准测量测试（只测不改生产解析/序列化管线）。
 * 记录：
 * 1. TD-03：5,000 解锁 + 10,000 通知 + 500 会话的 JSON prettyPrint 体积放大倍数与往返一致性；
 * 2. TD-02：~4MB 明文 `dumpsys usagestats` 样本的 `DumpShape.of` + `UsageDumpParser.parse` 耗时与堆增量。
 */
class MemoryBenchmarkProbeTest {

    @Test
    fun `TD_03_大备份JSON序列化体积放大倍数与往返一致性基准`() {
        val baseMs = 1_700_000_000_000L
        val days = (0 until 10).map { d ->
            DaySummaryDto("2026-11-${10 + d}", 300, 260, 50, 80, 45, baseMs + d * 86_400_000L)
        }
        val unlocks = (0 until 5_000).map { i -> UnlockEventDto(baseMs + i * 1_000L, seq = 0) }
        val notifs = (0 until 10_000).map { i ->
            NotificationEventDto(baseMs + i * 500L, if (i % 2 == 0) "INTERRUPTION" else "SEEN", "com.app.${i % 10}", null, seq = 0)
        }
        val sessions = (0 until 500).map { i ->
            ScreenSessionDto(baseMs + i * 60_000L, baseMs + i * 60_000L + 30_000L, "SCREEN")
        }
        val file = BackupFile(
            schemaVersion = BackupCodec.SCHEMA_VERSION,
            exportedAt = "2026-11-25 12:00",
            appVersion = "0.1.0",
            device = BackupDevice("bench-dev", "Benchmark", "Redmi"),
            daySummary = days,
            unlockEvent = unlocks,
            notificationEvent = notifs,
            screenSession = sessions
        )

        val t0 = System.nanoTime()
        val pretty = BackupCodec.encode(file)
        val prettyMs = (System.nanoTime() - t0) / 1_000_000.0

        val compact = Json { prettyPrint = false; encodeDefaults = true }.encodeToString(file)
        val ratio = pretty.length.toDouble() / compact.length.toDouble()
        println("[TD-03 BENCH] prettyBytes=${pretty.length}, compactBytes=${compact.length}, ratio=${"%.2f".format(ratio)}x, encodeMs=${"%.1f".format(prettyMs)}")

        val decoded = BackupCodec.decode(pretty)
        assertTrue(decoded is BackupCodec.DecodeResult.Ok)
        val out = (decoded as BackupCodec.DecodeResult.Ok).file
        assertEquals(5_000, out.unlockEvent.size)
        assertEquals(10_000, out.notificationEvent.size)
        assertTrue("prettyPrint 放大倍数应大于 1.0", ratio > 1.0)
    }

    @Test
    fun `TD_02_四MB级明文dump解析耗时与堆增量基准`() {
        val sb = StringBuilder(4_200_000)
        sb.appendLine("user=0")
        sb.appendLine("  In-memory daily stats")
        sb.appendLine("    timeRange=\"2026-11-25 00:00:00\" to \"2026-11-25 18:00:00\"")
        sb.appendLine("    packages:")
        for (i in 0 until 200) {
            sb.appendLine("      package=com.bench.app$i totalTimeUsed=\"00:15:00\" lastTimeUsed=\"2026-11-25 17:00:00\"")
        }
        sb.appendLine("  events:")
        var count = 0
        while (sb.length < 4_000_000) {
            val sec = count % 60
            val min = (count / 60) % 60
            val hr = (count / 3600) % 24
            val ts = "2026-11-25 %02d:%02d:%02d".format(hr, min, sec)
            val type = when (count % 4) {
                0 -> "SCREEN_INTERACTIVE"
                1 -> "KEYGUARD_HIDDEN"
                2 -> "ACTIVITY_RESUMED"
                else -> "SCREEN_NON_INTERACTIVE"
            }
            sb.appendLine("    time=\"$ts\" type=$type package=com.bench.app${count % 50} class=MainActivity")
            count++
        }
        val humanDump = sb.toString()
        val rt = Runtime.getRuntime()
        val usedBefore = rt.totalMemory() - rt.freeMemory()
        val t0 = System.nanoTime()

        val shape = DumpShape.of(humanDump)
        val result = UsageDumpParser.parse("", humanDump, ZoneId.of("Asia/Shanghai"), LocalDate.of(2026, 11, 25))
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
        val usedAfter = rt.totalMemory() - rt.freeMemory()
        println("[TD-02 BENCH] dumpBytes=${humanDump.length}, shape=${shape.kind}, eventsParsed=${count}, elapsedMs=${"%.1f".format(elapsedMs)}, heapDeltaKB=${(usedAfter - usedBefore) / 1024}")

        assertTrue(shape.usableAsPlain)
        assertTrue(result.appUsage.isNotEmpty())
    }
}
