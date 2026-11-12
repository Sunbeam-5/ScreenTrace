package com.screentrace.app.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §5.8 拉取频率的**向后兼容**：老版本往 DataStore 写的是字符串 `"daily"`（小写），
 * 现在存的是枚举名 `DAILY`。读取时认不出就退回默认值——如果这里写错，
 * 用户升级后拉取频率会被静默重置成「每天」，而从界面上看不出来。
 */
class PullFrequencyTest {

    @Test
    fun `旧版小写字符串仍能识别`() {
        assertEquals(PullFrequency.DAILY, PullFrequency.of("daily"))
    }

    @Test
    fun `枚举名大小写不敏感`() {
        assertEquals(PullFrequency.SIX_HOURS, PullFrequency.of("six_hours"))
        assertEquals(PullFrequency.TWELVE_HOURS, PullFrequency.of("Twelve_Hours"))
    }

    @Test
    fun `缺失或垃圾值退回每天`() {
        assertEquals(PullFrequency.DAILY, PullFrequency.of(null))
        assertEquals(PullFrequency.DAILY, PullFrequency.of(""))
        assertEquals(PullFrequency.DAILY, PullFrequency.of("每分钟"))
    }

    @Test
    fun `三档周期秒数符合标签`() {
        assertEquals(24L, PullFrequency.DAILY.hours)
        assertEquals(12L, PullFrequency.TWELVE_HOURS.hours)
        assertEquals(6L, PullFrequency.SIX_HOURS.hours)
    }
}
