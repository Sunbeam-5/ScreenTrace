package com.screentrace.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §7-47（CR-22 ①）：点 tab 跨页时路由**直达目标页**，不经过中间页。
 *
 * 真机实测（Redmi）：点「今日 → 日历」时路由先在 70ms 被回写成「统计」、180ms 才回写成
 * 「日历」，标题与导航胶囊中途跳变，观感就是「点 tab 卡一下」；滑动切换没有这一步所以不卡。
 *
 * 这里把口径钉成纯函数，便于变异验证。
 */
class TabScrollGuardTest {

    @Test
    fun `程序化滚动途中 忽略中间页的回写`() {
        // 从 0 页滚到 2 页，途中经过 1 页
        assertTrue(TabScrollGuard.ignoresWrite(programmatic = true, target = 2, index = 1))
    }

    @Test
    fun `目标页照常回写 标题在动画走到七成时切过去`() {
        assertFalse(TabScrollGuard.ignoresWrite(programmatic = true, target = 2, index = 2))
    }

    @Test
    fun `滑动切换不忽略回写 仍然跟手`() {
        assertFalse(TabScrollGuard.ignoresWrite(programmatic = false, target = -1, index = 1))
        assertFalse(TabScrollGuard.ignoresWrite(programmatic = false, target = -1, index = 0))
    }

    @Test
    fun `被更新的点击取代时 那次点击不负责对齐`() {
        assertTrue(TabScrollGuard.ownsTarget(target = 2, index = 2))
        assertFalse(TabScrollGuard.ownsTarget(target = 2, index = 1))
    }

    @Test
    fun `开始滚动后进入程序化状态 目标页记为目标`() {
        val guard = TabScrollGuard()
        assertFalse(guard.ignoresWrite(1))
        guard.begin(2)
        assertTrue(guard.ignoresWrite(1))
        assertFalse("目标页不忽略", guard.ignoresWrite(2))
        assertEquals(2, guard.target)
    }

    @Test
    fun `滚动结束由本次点击对齐路由`() {
        val guard = TabScrollGuard()
        guard.begin(1)
        assertTrue(guard.end(1))
        assertFalse(guard.ignoresWrite(1))
    }

    @Test
    fun `被更新的点击取代时 不动标记也不对齐`() {
        val guard = TabScrollGuard()
        guard.begin(1)
        guard.begin(2) // 用户又点了别的页
        assertFalse("旧的那次不该抢着对齐", guard.end(1))
        assertTrue("标记留给新的那次", guard.ignoresWrite(1))
        assertEquals(2, guard.target)
        assertTrue(guard.end(2))
        assertFalse(guard.ignoresWrite(1))
    }
}
