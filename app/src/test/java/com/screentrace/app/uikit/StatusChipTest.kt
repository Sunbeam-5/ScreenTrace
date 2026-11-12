package com.screentrace.app.uikit

import com.screentrace.app.contract.UsageCalculator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CR-07 / VI-3.1：状态标签显隐规则。变异验证过：shouldShowCompare 去掉
 * 「暂无上一周期数据」判断后「无对比不显示」用例变红。
 */
class StatusChipTest {

    @Test
    fun `无对比数据时标签整行不显示`() {
        val none = UsageCalculator.CompareInfo("暂无上一周期数据", null)
        assertFalse(shouldShowCompare(none))
    }

    @Test
    fun `有对比数据时显示含持平`() {
        assertTrue(shouldShowCompare(UsageCalculator.CompareInfo("较昨日增加 12 分钟", true)))
        assertTrue(shouldShowCompare(UsageCalculator.CompareInfo("较昨日减少 8 分钟", false)))
        assertTrue(shouldShowCompare(UsageCalculator.CompareInfo("较昨日持平", null)))
    }

    @Test
    fun `超出目标 chip 只在真超目标时出现`() {
        assertFalse(shouldShowOverChip(usageMinutes = 200, goalMinutes = 240))
        assertFalse(shouldShowOverChip(usageMinutes = 240, goalMinutes = 240)) // 恰好等于不算
        assertFalse(shouldShowOverChip(usageMinutes = 300, goalMinutes = 0))   // 无目标不出
        assertTrue(shouldShowOverChip(usageMinutes = 282, goalMinutes = 180))
    }
}
