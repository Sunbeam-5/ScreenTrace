package com.screentrace.app.ui

import com.screentrace.app.contract.EmptyUsageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CR-17 ①：`showNotice` 的双通道分流回归。
 * 变异锚点：把 `isTransientNotice` 改成恒 false（全部写进常驻通道），前两条必须变红。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppViewModelNoticeTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `成功类写短时通道且不碰常驻`() = runTest(dispatcher) {
        val vm = AppViewModel(repository = EmptyUsageRepository())
        advanceUntilIdle()

        vm.showNotice("已导出 report.json")
        advanceUntilIdle()

        assertNull("成功确认不该变成常驻横幅", vm.notice.value)
        assertNotNull(vm.transientNotice.value)
        assertEquals("已导出 report.json", vm.transientNotice.value!!.text)
    }

    @Test
    fun `失败类写常驻通道且不动短时`() = runTest(dispatcher) {
        val vm = AppViewModel(repository = EmptyUsageRepository())
        advanceUntilIdle()

        vm.showNotice("导出失败，请换个位置再试")
        advanceUntilIdle()

        assertEquals("导出失败，请换个位置再试", vm.notice.value)
        assertNull(vm.transientNotice.value)
    }

    @Test
    fun `连发同文案序号递增以重启计时`() = runTest(dispatcher) {
        val vm = AppViewModel(repository = EmptyUsageRepository())
        advanceUntilIdle()

        vm.showNotice("连接成功")
        val first = vm.transientNotice.value!!.seq
        vm.showNotice("连接成功")
        val second = vm.transientNotice.value!!.seq

        assertNotEquals("同文案也要换 key，否则短时条不会重新计时", first, second)
    }

    @Test
    fun `consumeTransient 只清短时不清常驻`() = runTest(dispatcher) {
        val vm = AppViewModel(repository = EmptyUsageRepository())
        advanceUntilIdle()

        vm.showNotice("已导出 report.json")
        vm.showNotice("导出失败，请换个位置再试")
        vm.consumeTransient()

        assertNull(vm.transientNotice.value)
        assertEquals("导出失败，请换个位置再试", vm.notice.value)
    }
}
