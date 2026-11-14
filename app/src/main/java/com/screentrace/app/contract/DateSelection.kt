package com.screentrace.app.contract

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * §5.6 / CR-15 B：日期窗口值对象。今日页与统计页各持有一份独立状态
 * （`todaySelection` 默认 `today()`、`statsSelection` 默认 `lastDays(7)`），
 * 日历页不参与筛选而用全量历史窗口；二级页按进入它的主页面归属其中一份。
 * 两份状态都不持久化，进程重建各自回默认。日期一律为设备本地时区的自然日。
 */
sealed interface DateSelection {
    val startDate: LocalDate
    val endDate: LocalDate

    /** 共 N 个自然日（含首尾）。 */
    val days: Int
        get() = (ChronoUnit.DAYS.between(startDate, endDate) + 1).toInt()

    data class Day(val date: LocalDate) : DateSelection {
        override val startDate: LocalDate get() = date
        override val endDate: LocalDate get() = date
    }

    data class Range(val start: LocalDate, val end: LocalDate) : DateSelection {
        override val startDate: LocalDate get() = start
        override val endDate: LocalDate get() = end

        companion object {
            /** DateRangePicker 反选时自动交换（§5.6）。 */
            fun normalized(start: LocalDate, end: LocalDate): Range =
                if (end.isBefore(start)) Range(end, start) else Range(start, end)
        }
    }

    companion object {
        fun today(now: LocalDate = LocalDate.now()): Day = Day(now)

        fun lastDays(days: Int, now: LocalDate = LocalDate.now()): Range =
            Range.normalized(now.minusDays(days.toLong() - 1), now)

        /**
         * CR-14：本周（周一至周日的 ISO 周），供统计页日期弹层「本周」快捷项。
         * 与「本月」同构：区间写满整周，未来无记录的天由统计语义自然处理为无记录。
         */
        fun thisWeek(now: LocalDate = LocalDate.now()): Range {
            val monday = now.minusDays((now.dayOfWeek.value - 1).toLong())
            return Range(monday, monday.plusDays(6))
        }

        /** 自定义区间上限 366 个自然日（§5.6）。 */
        const val MAX_DAYS = 366
    }
}
