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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CR-17 ②：`selectMain` 幂等去重回归。
 * 变异锚点：去掉 `if (AppPage.mainOf(_page.value) == page) return`，
 * 「二级页期间重复回写」必须变红（覆盖层会被误关）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppViewModelSelectMainTest {

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
    fun `主页面之间切换正常`() = runTest(dispatcher) {
        val vm = AppViewModel(repository = EmptyUsageRepository())
        advanceUntilIdle()

        assertEquals(AppPage.Today, vm.page.value)
        vm.selectMain(AppPage.Stats)
        assertEquals(AppPage.Stats, vm.page.value)
        vm.selectMain(AppPage.Calendar)
        assertEquals(AppPage.Calendar, vm.page.value)
    }

    @Test
    fun `二级页期间重复回写同一主页面不会误关覆盖层`() = runTest(dispatcher) {
        val vm = AppViewModel(repository = EmptyUsageRepository())
        advanceUntilIdle()

        val detail = AppPage.UnlockDetail(returnTo = AppPage.Today)
        vm.openUnlockDetail()
        assertEquals(detail, vm.page.value)

        // Pager 的 currentPage 回写可能落在二级页打开期间；mainOf 归位后与当前主页面相同，
        // 去重必须让这次写入无效，否则 _page.value 会被改成 Today、覆盖层直接消失。
        vm.selectMain(AppPage.Today)
        assertEquals(detail, vm.page.value)
    }

    @Test
    fun `二级页归位后仍可正常切主页面`() = runTest(dispatcher) {
        val vm = AppViewModel(repository = EmptyUsageRepository())
        advanceUntilIdle()

        vm.selectMain(AppPage.Stats)
        vm.openSettings()
        assertEquals(AppPage.Settings(returnTo = AppPage.Stats), vm.page.value)
        vm.goBack()
        assertEquals(AppPage.Stats, vm.page.value)
    }
}
