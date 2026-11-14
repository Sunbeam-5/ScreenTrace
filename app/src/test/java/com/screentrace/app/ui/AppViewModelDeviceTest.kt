package com.screentrace.app.ui

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.screentrace.app.contract.EmptyUsageRepository
import com.screentrace.app.data.prefs.DeviceIds
import com.screentrace.app.data.prefs.SettingsStore
import com.screentrace.app.data.sync.SyncFailure
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** CR-15 ③ 批 C：云同步页设备卡片的 ViewModel 契约（未装配降级 / 昵称 / 短 ID）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppViewModelDeviceTest {

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
    fun `未装配同步模块时设备列举给出明确错误而不是空成功`() = runTest(dispatcher) {
        val vm = AppViewModel(repository = EmptyUsageRepository())
        advanceUntilIdle()

        val result = vm.listCloudDevices()
        assertTrue(result.devices.isEmpty())
        assertNotNull(result.error)
        assertEquals(SyncFailure.UNKNOWN, result.error!!.kind)
    }

    @Test
    fun `未装配同步模块时只读查看返回错误且不产生候选文件`() = runTest(dispatcher) {
        val vm = AppViewModel(repository = EmptyUsageRepository())
        advanceUntilIdle()

        val result = vm.inspectCloudDevice("other-device")
        assertNull(result.file)
        assertNull(result.summary)
        assertNotNull(result.error)
    }

    @Test
    fun `昵称无持久化层时只改内存态 空串回落默认`() = runTest(dispatcher) {
        val vm = AppViewModel(repository = EmptyUsageRepository())
        advanceUntilIdle()

        vm.setDeviceNickname("  我的手机  ")
        assertEquals("我的手机", vm.deviceNickname.value)
        vm.setDeviceNickname("   ")
        assertEquals("本机设备", vm.deviceNickname.value)
    }

    @Test
    fun `装配 SettingsStore 后昵称与短 ID 来自 DataStore 且改名落盘`() = runTest(dispatcher) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefsFile = File(context.cacheDir, "vm-device-${System.nanoTime()}.preferences_pb")
        val prefs = PreferenceDataStoreFactory.create { prefsFile }
        val store = SettingsStore(prefs, "测试机型")
        store.setDeviceNickname("客厅平板")
        // 先让 ID 真正落盘：DataStore 的 edit 走真实 IO，测试调度器推进不到它；
        // 否则 ViewModel 那条 launch 会卡在首次生成，短码仍是空串。
        val expectedShort = DeviceIds.short(store.getOrCreateDeviceId())
        try {
            val vm = AppViewModel(repository = EmptyUsageRepository(), settings = store)
            advanceUntilIdle()

            assertEquals("客厅平板", vm.deviceNickname.value)
            assertEquals(expectedShort, vm.deviceIdShort.value)

            vm.setDeviceNickname("新昵称")
            advanceUntilIdle()
            assertEquals("新昵称", vm.deviceNickname.value)
            assertEquals("新昵称", store.deviceNickname.first())
        } finally {
            prefsFile.delete()
        }
    }
}
