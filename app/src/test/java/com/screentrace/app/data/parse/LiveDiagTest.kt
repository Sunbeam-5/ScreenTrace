package com.screentrace.app.data.parse

import org.junit.Test
import java.io.File

class LiveDiagTest {
    @Test
    fun diag() {
        val f = File("D:/Personal/Temp/u2.txt")
        if (!f.exists()) return
        val result = UsageDumpParser.parse("", f.readText())
        for ((date, day) in result.days.toSortedMap()) {
            val appSum = result.appUsage.filter { it.date == date }.sumOf { it.totalMs } / 60_000
            val hourSum = result.hourMinutes[date]?.sum() ?: 0
            println("DIAG $date screen=${day.screenMin} hourSum=$hourSum appSum=$appSum limit=${maxOf(day.screenMin, hourSum) * 3.0}")
        }
    }
}
