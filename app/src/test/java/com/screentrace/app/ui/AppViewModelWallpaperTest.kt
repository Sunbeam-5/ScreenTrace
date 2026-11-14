package com.screentrace.app.ui

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.contract.EmptyUsageRepository
import com.screentrace.app.data.prefs.SettingsStore
import com.screentrace.app.data.wallpaper.WallpaperConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §5.4 批 E：壁纸参数与「取消/失败保留」的 ViewModel 契约。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppViewModelWallpaperTest {

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
    fun `暗化与模糊参数立即更新内存态`() = runTest(dispatcher) {
        val vm = AppViewModel(repository = EmptyUsageRepository())
        advanceUntilIdle()

        vm.setWallpaperDim(45)
        assertEquals(45, vm.wallpaperConfig.value.dimPercent)
        vm.setWallpaperBlur(9)
        assertEquals(9, vm.wallpaperConfig.value.blurDp)
    }

    @Test
    fun `装配 SettingsStore 时参数落盘`() = runTest(dispatcher) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefsFile = File(context.cacheDir, "vm-wall-${System.nanoTime()}.preferences_pb")
        val prefs = PreferenceDataStoreFactory.create { prefsFile }
        val store = SettingsStore(prefs, "测试机型")
        store.setWallpaper(WallpaperConfig()) // 先完成一次真实 IO，避免测试调度器推进不到
        try {
            val vm = AppViewModel(repository = EmptyUsageRepository(), settings = store)
            advanceUntilIdle()

            vm.setWallpaperDim(50)
            assertEquals(50, store.wallpaper.first().dimPercent)
            vm.setWallpaperBlur(12)
            assertEquals(12, store.wallpaper.first().blurDp)
        } finally {
            prefsFile.delete()
        }
    }

    @Test
    fun `恢复默认清空位图与参数并给就地提示`() = runTest(dispatcher) {
        val vm = AppViewModel(repository = EmptyUsageRepository())
        advanceUntilIdle()
        vm.setWallpaperDim(45)

        vm.clearWallpaper()
        advanceUntilIdle()

        assertEquals(WallpaperConfig(), vm.wallpaperConfig.value)
        assertNull(vm.wallpaperBitmap.value)
        assertEquals("已恢复默认背景", vm.wallpaperMessage.value)
        vm.consumeWallpaperMessage()
        assertNull(vm.wallpaperMessage.value)
    }

    @Test
    fun `取消选图不改状态也不提示`() = runTest(dispatcher) {
        val vm = AppViewModel(repository = EmptyUsageRepository())
        advanceUntilIdle()

        vm.onWallpaperPicked(null)
        advanceUntilIdle()

        assertEquals(WallpaperConfig(), vm.wallpaperConfig.value)
        assertNull(vm.wallpaperBitmap.value)
        assertNull(vm.wallpaperMessage.value)
    }
}
