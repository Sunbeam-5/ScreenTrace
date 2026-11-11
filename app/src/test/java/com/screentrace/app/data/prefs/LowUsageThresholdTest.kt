package com.screentrace.app.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

/** §7-23：阈值 1–24 小时的口径契约（备份导入 / 旧配置 / UI 滑杆共用）。 */
class LowUsageThresholdTest {

    @Test
    fun `越界分钟被夹到 1 到 24 小时`() {
        assertEquals(60, LowUsageThreshold.clampMinutes(-100))
        assertEquals(60, LowUsageThreshold.clampMinutes(0))
        assertEquals(1440, LowUsageThreshold.clampMinutes(Int.MAX_VALUE))
        assertEquals(120, LowUsageThreshold.clampMinutes(120))
    }

    @Test
    fun `分钟转小时档位`() {
        assertEquals(1, LowUsageThreshold.hoursOf(60))
        assertEquals(2, LowUsageThreshold.hoursOf(120))
        assertEquals(24, LowUsageThreshold.hoursOf(1440))
        // 非整小时的旧值向下取整到整点档，滑杆不会停在半格上
        assertEquals(3, LowUsageThreshold.hoursOf(200))
        assertEquals(24, LowUsageThreshold.hoursOf(5000))
    }

    @Test
    fun `小时转分钟也夹取范围`() {
        assertEquals(60, LowUsageThreshold.minutesOf(0))
        assertEquals(1440, LowUsageThreshold.minutesOf(99))
        assertEquals(720, LowUsageThreshold.minutesOf(12))
    }

    @Test
    fun `整小时往返不丢值`() {
        for (h in 1..24) {
            assertEquals(h * 60, LowUsageThreshold.minutesOf(LowUsageThreshold.hoursOf(h * 60)))
        }
    }
}
