package com.screentrace.app.data.pull

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §7-54（CR-27）：提示文案必须跟**实际**节流间隔走，不得写死数字。
 *
 * 背景：`DEFAULT_MIN_INTERVAL_MS` 批 6g 已从 30 分钟收紧到 10 分钟，
 * 但 `PullOutcome.Throttled` 的文案仍写「30 分钟内不重复拉取」——用户会以为要等半小时。
 */
class PullCoordinatorMessageTest {

    @Test
    fun `节流文案按实际间隔出 不写死数字`() {
        val msg = PullOutcome.Throttled.asPullResult().message
        val minutes = PullCoordinator.DEFAULT_MIN_INTERVAL_MS / 60_000
        assertTrue("文案应含实际分钟数 $minutes，实测：$msg", msg.contains("$minutes 分钟"))
        assertTrue("旧文案的 30 分钟不得再出现，实测：$msg", !msg.contains("30 分钟"))
    }

    @Test
    fun `节流不是失败 但要如实告知`() {
        val r = PullOutcome.Throttled.asPullResult()
        assertTrue("节流不算失败（数据是刚拉过的）", r.ok)
    }
}
