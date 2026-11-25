package com.screentrace.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * CR-30 阶段 D：轻量反馈单槽优先级策略单测。
 * 变异锚点（M-D3）：把优先级顺序反转或改为先取 transientNotice，必须变红。
 */
class NoticeFeedbackPolicyTest {

    @Test
    fun `无任何反馈时返回 null`() {
        assertNull(resolveActiveFeedback(null, null, null))
        assertNull(resolveActiveFeedback("  ", "", TransientNotice(" ", 1)))
    }

    @Test
    fun `拉取错误优先级高于常驻与短时提示`() {
        val fb = resolveActiveFeedback(
            pullError = "拉取失败：无权限",
            persistentNotice = "请先拉取数据",
            transientNotice = TransientNotice("已保存", 1)
        )!!
        assertEquals(FeedbackChannel.PULL_ERROR, fb.channel)
        assertEquals(FeedbackSeverity.ERROR, fb.severity)
        assertEquals("重试", fb.actionText)
    }

    @Test
    fun `无错误时常驻提示优先于短时成功提示`() {
        val fb = resolveActiveFeedback(
            pullError = null,
            persistentNotice = "该时段尚无数据，请先拉取",
            transientNotice = TransientNotice("已复制", 2)
        )!!
        assertEquals(FeedbackChannel.PERSISTENT, fb.channel)
        assertEquals(FeedbackSeverity.INFO, fb.severity)
        assertNull(fb.actionText)
    }

    @Test
    fun `仅有短时提示时归类为 SUCCESS`() {
        val fb = resolveActiveFeedback(
            pullError = null,
            persistentNotice = null,
            transientNotice = TransientNotice("已导出 backup.json", 3)
        )!!
        assertEquals(FeedbackChannel.TRANSIENT, fb.channel)
        assertEquals(FeedbackSeverity.SUCCESS, fb.severity)
        assertEquals("已导出 backup.json", fb.text)
    }
}
