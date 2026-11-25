package com.screentrace.app.uikit

import com.screentrace.app.contract.AppCategory
import com.screentrace.app.contract.AppUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * CR-07 §4.4 应用构成：颜色顺序与百分比分配是纯函数，放 JVM 单测钉死。
 * 变异验证过：把「其他」的颜色下标改回固定 4、或把百分比改成直接四舍五入，
 * 本文件的对应用例会变红。
 */
class AppBreakdownMathTest {

    private val date = LocalDate.of(2026, 11, 12)

    private fun app(name: String, minutes: Int) = AppUsage(
        date = date,
        pkg = "pkg.$name",
        name = name,
        minutes = minutes,
        launches = 1,
        category = AppCategory.entries.first()
    )

    @Test
    fun `前四加其他共五行且色阶按可见顺序 0 到 4`() {
        val rows = AppBreakdownMath.rows(
            topApps = listOf(app("A", 40), app("B", 30), app("C", 20), app("D", 10)),
            otherMinutes = 5
        )
        assertEquals(5, rows.size)
        assertEquals(listOf(0, 1, 2, 3, 4), rows.map { it.colorIndex })
        assertEquals("其他", rows.last().name)
        assertEquals(5, rows.last().minutes)
    }

    @Test
    fun `应用不足四个时其他不能跳到第五色`() {
        val rows = AppBreakdownMath.rows(
            topApps = listOf(app("A", 60), app("B", 30)),
            otherMinutes = 10
        )
        // 旧实现让「其他」固定拿 chart5，色带会变成 1/2/5 三色，行首圆点也对不上
        assertEquals(listOf(0, 1, 2), rows.map { it.colorIndex })
    }

    @Test
    fun `整数百分比合计正好一百`() {
        val rows = AppBreakdownMath.rows(
            topApps = listOf(app("A", 10), app("B", 10), app("C", 10)),
            otherMinutes = 0
        )
        assertEquals(100, rows.sumOf { it.percent })
        // 最大余数法：并列时更靠前的段优先拿到多出的 1
        assertEquals(listOf(34, 33, 33), rows.map { it.percent })
    }

    @Test
    fun `零分钟应用与零分钟其他都不生成行`() {
        val rows = AppBreakdownMath.rows(
            topApps = listOf(app("A", 50), app("B", 0)),
            otherMinutes = 0
        )
        assertEquals(1, rows.size)
        assertEquals("A", rows.single().name)
        assertEquals(100, rows.single().percent)
    }

    @Test
    fun `无应用数据返回空列表`() {
        assertTrue(AppBreakdownMath.rows(emptyList(), 0).isEmpty())
        assertTrue(AppBreakdownMath.rows(listOf(app("A", 0)), 0).isEmpty())
    }

    @Test
    fun `其他行用伪包名以便图标回退首字母`() {
        val rows = AppBreakdownMath.rows(listOf(app("A", 50)), 50)
        assertEquals("_other_", rows.last().pkg)
        assertEquals(listOf(50, 50), rows.map { it.minutes })
    }

    @Test
    fun `CR-30-R11 缺分母时 dayPercent 返回 null 而非 0`() {
        assertNull(AppBreakdownMath.dayPercent(totalMinutes = 0, nightMinutes = 0))
        assertNull(AppBreakdownMath.dayPercent(totalMinutes = -10, nightMinutes = 0))
    }

    @Test
    fun `CR-30-R11 有效分母下 dayPercent 计算正确且钳制在 0 到 100`() {
        assertEquals(72, AppBreakdownMath.dayPercent(totalMinutes = 100, nightMinutes = 28))
        assertEquals(0, AppBreakdownMath.dayPercent(totalMinutes = 60, nightMinutes = 60))
        assertEquals(100, AppBreakdownMath.dayPercent(totalMinutes = 60, nightMinutes = 0))
        assertEquals(0, AppBreakdownMath.dayPercent(totalMinutes = 60, nightMinutes = 90))
    }

    @Test
    fun `CR-30-R11 dayNightSplit 在无分母时返回 null 不得伪造 0 和 100 百分比`() {
        assertNull(AppBreakdownMath.dayNightSplit(null))
        assertEquals(AppBreakdownMath.DayNightSplit(72, 28), AppBreakdownMath.dayNightSplit(72))
        assertEquals(AppBreakdownMath.DayNightSplit(0, 100), AppBreakdownMath.dayNightSplit(0))
        assertEquals(AppBreakdownMath.DayNightSplit(100, 0), AppBreakdownMath.dayNightSplit(100))
    }
}
