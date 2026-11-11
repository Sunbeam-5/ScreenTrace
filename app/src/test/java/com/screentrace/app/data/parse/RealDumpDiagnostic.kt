package com.screentrace.app.data.parse

import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * 临时诊断（不提交）：实时 dump 里「今天」的亮屏口径 vs MIUI 自带统计。
 */
class RealDumpDiagnostic {

    @Test
    fun liveTodayScreenTime() {
        val plain = File("D:/Personal/Temp/live2-plain.txt")
        val checkin = File("D:/Personal/Temp/live2-checkin.txt")
        if (!plain.exists()) return println("missing")
        val result = UsageDumpParser.parse(
            if (checkin.exists()) checkin.readText() else "",
            plain.readText(),
            zone = ZoneId.systemDefault(),
            today = LocalDate.of(2026, 11, 11)
        )
        println("days=${result.days.size}")
        result.days.entries.sortedByDescending { it.key }.take(3).forEach { (d, m) ->
            println("$d screenMin=${m.screenMin} screenUnlockMin=${m.screenUnlockMin} unlocks=${m.unlocks} longest=${m.longestSessionMin}")
        }
    }
}
