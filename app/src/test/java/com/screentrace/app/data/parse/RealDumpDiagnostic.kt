package com.screentrace.app.data.parse

import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * 临时诊断（不提交）：归属测试——深历史（8月～11月）到底在 checkin 还是明文里。
 */
class RealDumpDiagnostic {

    private fun report(tag: String, result: ParseResult) {
        println("== $tag: days=${result.days.size} unlockEvents=${result.unlockEvents.size} notif=${result.notificationEvents.size}")
        result.days.keys.sorted().let { if (it.isNotEmpty()) println("   range: ${it.first()} .. ${it.last()}") }
        println("   unlocks by day: " + result.days.entries.sortedBy { it.key }.take(12)
            .joinToString { "${it.key}=${it.value.unlocks}" })
    }

    @Test
    fun attributeDeepHistory() {
        val live = File("D:/Personal/Temp")
        val checkin = File(live, "live-checkin.txt")
        val plain = File(live, "live-plain.txt")
        if (!checkin.exists() || !plain.exists()) return println("live dumps missing")
        val today = LocalDate.of(2026, 11, 11)
        val zone = ZoneId.systemDefault()
        report("live both", UsageDumpParser.parse(checkin.readText(), plain.readText(), zone, today))
        report("live checkin only", UsageDumpParser.parse(checkin.readText(), "", zone, today))
        report("live plain only", UsageDumpParser.parse("", plain.readText(), zone, today))
    }
}
