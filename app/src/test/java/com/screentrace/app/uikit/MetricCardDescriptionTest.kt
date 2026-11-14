package com.screentrace.app.uikit

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P3-1：可下钻卡整卡读屏一句话（§5.2「标签 + 当前值 + 查看详情」）。
 *
 * 原默认 `"$label 查看详情"` 缺当前值；本组用例钉住拼接口径——值必含、
 * 说明有则含无则省、时长与页面同源（formatDuration）。改坏拼接必须变红。
 */
class MetricCardDescriptionTest {

    @Test
    fun `数字值含当前值 无说明`() {
        assertEquals(
            "解锁次数 87，查看详情",
            metricCardDescription("解锁次数", MetricValue.Number("87"))
        )
    }

    @Test
    fun `说明有则一并读出`() {
        assertEquals(
            "通知打扰 27，已查看 13 次，查看详情",
            metricCardDescription("通知打扰", MetricValue.Number("27"), note = "已查看 13 次")
        )
    }

    @Test
    fun `时长值与页面同源格式化`() {
        assertEquals(
            "最长连续 1小时12分钟，查看详情",
            metricCardDescription("最长连续", MetricValue.Duration(72))
        )
        assertEquals(
            "低于 2 小时 4，共 7 个有记录日，查看详情",
            metricCardDescription("低于 2 小时", MetricValue.Number("4"), note = "共 7 个有记录日")
        )
    }
}
