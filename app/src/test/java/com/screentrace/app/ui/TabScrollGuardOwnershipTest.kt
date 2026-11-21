package com.screentrace.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A-5 R10（P2）：`TabScrollGuard` 的**所有权状态机**测试。
 *
 * 缺陷背景：旧实现用 `target` 页索引充当协程身份（`end(index)` 只看 `target != index`），
 * 而**目标页不是协程身份**——同目标连续点击时该判据失效，旧协程被取消后错误取得对齐权、
 * 并把新协程的守卫状态一并清掉。真机证据：A-5 R9 v3 s5_same（连点两次统计页）
 * seq1 `scrollCancel→scrollFinally(aligned=true)`+`Route/align=1`，seq2 `aligned=false` 且无 Route/align。
 *
 * 本组用例驱动的是**守卫状态机**（`begin`/`end` 的时序），**不等于**真实协程取消测试；
 * 真实链路的 Compose 协程取消 / Pager 动画互相中断 / 路由回写验证属真机复核范畴。
 *
 * 断言纪律：**每条用例都基于 `guard.snapshot` 一次取值**断言 programmatic / target / owner 三要素，
 * 不逐个 getter 连读（三个 getter 各自独立读状态，跨状态变化时可能读到不同时刻的组合）。
 */
class TabScrollGuardOwnershipTest {

    @Test
    fun `T1 不同目标被新点击取代 旧点击不得抢所有权`() {
        val guard = TabScrollGuard()
        val t1 = guard.begin(1)
        val t2 = guard.begin(2)
        assertNotEquals("两次 begin 必须发不同的 token", t1, t2)

        assertFalse("旧点击已被取代，不得取得所有权", guard.end(t1))

        val s = guard.snapshot
        assertTrue("新点击的守卫仍在", s.programmatic)
        assertEquals(2, s.target)
        assertEquals(t2, s.owner)

        assertTrue("新点击自己收尾", guard.end(t2))
        val done = guard.snapshot
        assertFalse(done.programmatic)
        assertEquals(-1, done.target)
        assertEquals(TabScrollGuard.NO_OWNER, done.owner)
    }

    @Test
    fun `T2 相同目标被新点击取代 旧协程不得抢所有权 P2核心回归`() {
        val guard = TabScrollGuard()
        val t1 = guard.begin(1)
        val t2 = guard.begin(1) // 同目标：旧实现的 target != index 判据正是在这里失效
        assertNotEquals(t1, t2)

        assertFalse("同目标时旧协程也不得取得所有权", guard.end(t1))

        val s = guard.snapshot
        assertTrue("新点击的守卫未被旧协程清除", s.programmatic)
        assertEquals(1, s.target)
        assertEquals(t2, s.owner)

        assertTrue("真正的主人自己收尾", guard.end(t2))
        val done = guard.snapshot
        assertFalse(done.programmatic)
        assertEquals(TabScrollGuard.NO_OWNER, done.owner)
    }

    @Test
    fun `T3 旧协程先结束 它仍持有所有权 应成功释放`() {
        val guard = TabScrollGuard()
        val t1 = guard.begin(1)

        assertTrue("此时没有更新的点击取代，旧协程理应成功释放", guard.end(t1))
        val released = guard.snapshot
        assertFalse(released.programmatic)
        assertEquals(-1, released.target)
        assertEquals(TabScrollGuard.NO_OWNER, released.owner)

        val t2 = guard.begin(2)
        assertTrue("新的点击独立走完整生命周期", guard.end(t2))
        assertFalse(guard.snapshot.programmatic)
    }

    @Test
    fun `T4 新协程先结束 旧协程finally后执行 不得复活状态`() {
        val guard = TabScrollGuard()
        val t1 = guard.begin(1)
        val t2 = guard.begin(1)

        assertTrue("新协程是主人", guard.end(t2))
        val afterNew = guard.snapshot
        assertFalse(afterNew.programmatic)
        assertEquals(-1, afterNew.target)
        assertEquals(TabScrollGuard.NO_OWNER, afterNew.owner)

        assertFalse("旧协程的 finally 不得夺权", guard.end(t1))
        val afterOld = guard.snapshot
        assertFalse("不得复活 programmatic", afterOld.programmatic)
        assertEquals(-1, afterOld.target)
        assertEquals(TabScrollGuard.NO_OWNER, afterOld.owner)
    }

    @Test
    fun `T5 连续三次相同目标 只有最后一次拥有所有权`() {
        val guard = TabScrollGuard()
        val t1 = guard.begin(1)
        val t2 = guard.begin(1)
        val t3 = guard.begin(1)

        assertFalse(guard.end(t1))
        assertFalse(guard.end(t2))
        val s = guard.snapshot
        assertTrue(s.programmatic)
        assertEquals(1, s.target)
        assertEquals(t3, s.owner)

        assertTrue(guard.end(t3))
        assertFalse(guard.snapshot.programmatic)
    }

    @Test
    fun `T6 取消异常路径 被取代者无权 无取代者仍保留对齐权`() {
        // (a) 取消后被新点击取代 → 旧协程的 finally 不得动状态
        val guard = TabScrollGuard()
        val t1 = guard.begin(1)
        val t2 = guard.begin(1)
        assertFalse("被取代的取消路径不得取得所有权", guard.end(t1))
        val s = guard.snapshot
        assertTrue(s.programmatic)
        assertEquals(1, s.target)
        assertEquals(t2, s.owner)

        // (b) 取消但没有新点击取代 → 仍由本协程按 Pager 停留页对齐（保留 CR-22 原语义）
        val solo = TabScrollGuard()
        val only = solo.begin(1)
        assertTrue("手势打断程序化滚动时仍应由本协程对齐", solo.end(only))
        assertFalse(solo.snapshot.programmatic)
    }

    @Test
    fun `T7 正常单次点击 状态齐备且结束后归位`() {
        val guard = TabScrollGuard()
        val token = guard.begin(2)

        val s = guard.snapshot
        assertTrue(s.programmatic)
        assertEquals(2, s.target)
        assertEquals(token, s.owner)

        assertTrue(guard.end(token))
        val done = guard.snapshot
        assertFalse(done.programmatic)
        assertEquals(-1, done.target)
        assertEquals(TabScrollGuard.NO_OWNER, done.owner)
    }

    @Test
    fun `T8 横滑期间守卫不建立程序化状态 回写不被忽略`() {
        val guard = TabScrollGuard()

        // 横滑期间守卫应保持 IDLE：任何页的回写都不被忽略（跟手不受影响）
        assertFalse(guard.snapshot.programmatic)
        assertFalse("横滑回写不得被忽略", guard.ignoresWrite(0))
        assertFalse(guard.ignoresWrite(1))
        assertFalse(guard.ignoresWrite(2))

        // 程序化滚动期间：只忽略中间页，目标页照常回写（CR-22 口径不变）
        val token = guard.begin(2)
        assertTrue("中间页回写应被忽略", guard.ignoresWrite(1))
        assertFalse("目标页照常回写", guard.ignoresWrite(2))
        guard.end(token)
        assertFalse(guard.snapshot.programmatic)
    }

    @Test
    fun `补充 token 单调递增且不复用`() {
        val guard = TabScrollGuard()
        val tokens = (1..5).map { guard.begin(1) }
        assertEquals("token 必须互不相同", tokens.size, tokens.distinct().size)
        assertEquals("token 必须严格递增", tokens.sorted(), tokens)
    }

    @Test
    fun `补充 过期token不匹配时快照整体不变`() {
        val guard = TabScrollGuard()
        val t1 = guard.begin(1)
        val t2 = guard.begin(2)
        val before = guard.snapshot
        assertFalse("过期 token 不得收尾", guard.end(t1))
        val after = guard.snapshot
        assertEquals(before, after)
        assertTrue(after.programmatic)
        assertEquals(2, after.target)
        assertEquals(t2, after.owner)
    }

    @Test
    fun `补充 begin写入的快照必须自洽`() {
        val guard = TabScrollGuard()
        val token = guard.begin(3)
        val s = guard.snapshot
        assertTrue("programmatic=true 必须有 owner", s.owner != TabScrollGuard.NO_OWNER)
        assertEquals(token, s.owner)
        assertTrue("programmatic=true 必须有合法 target", s.target >= 0)
    }

    @Test
    fun `补充 ignoresWrite与快照同源`() {
        val guard = TabScrollGuard()
        val token = guard.begin(2)
        val s = guard.snapshot
        assertEquals(
            TabScrollGuard.ignoresWrite(s.programmatic, s.target, 1),
            guard.ignoresWrite(1)
        )
        guard.end(token)
    }
}
