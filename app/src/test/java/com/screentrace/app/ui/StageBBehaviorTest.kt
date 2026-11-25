package com.screentrace.app.ui

import com.screentrace.app.contract.EmptyUsageRepository
import com.screentrace.app.contract.PeriodType
import com.screentrace.app.data.reminder.ReminderConfig
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StageBBehaviorTest {

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
    fun `日历 今天 按钮只在翻离当前月时显示`() {
        val current = YearMonth.of(2026, 11)
        assertFalse(shouldShowTodayButton(YearMonth.of(2026, 11), current))
        assertTrue(shouldShowTodayButton(YearMonth.of(2026, 10), current))
        assertTrue(shouldShowTodayButton(YearMonth.of(2026, 12), current))
    }

    @Test
    fun `设置三级导航返回先回到分类首页 再退回主页面`() {
        val vm = AppViewModel(repository = EmptyUsageRepository())
        vm.selectMain(AppPage.Calendar)
        vm.openSettings()
        assertTrue(vm.page.value is AppPage.Settings)
        assertNull(vm.settingsCategory.value)

        vm.openSettingsCategory(SettingsCategory.REMINDERS)
        assertEquals(SettingsCategory.REMINDERS, vm.settingsCategory.value)

        // 第一次返回：三级子页 -> 设置分类首页
        vm.goBack()
        assertTrue(vm.page.value is AppPage.Settings)
        assertNull(vm.settingsCategory.value)

        // 第二次返回：设置分类首页 -> 主页面（日历）
        vm.goBack()
        assertEquals(AppPage.Calendar, vm.page.value)
    }

    @Test
    fun `周期总结提醒开关触发回调且通知入口直达日历周期总结`() {
        val configs = mutableListOf<ReminderConfig>()
        val vm = AppViewModel(
            repository = EmptyUsageRepository(),
            onReminderConfigChanged = { configs += it }
        )
        assertFalse(vm.weeklyReminderEnabled.value)
        assertFalse(vm.monthlyReminderEnabled.value)

        vm.setWeeklyReminderEnabled(true)
        assertTrue(vm.weeklyReminderEnabled.value)
        assertEquals(ReminderConfig(weeklyEnabled = true, monthlyEnabled = false, hour = 20), configs.last())

        vm.openPeriodFromReminder(PeriodType.MONTH)
        assertEquals(PeriodType.MONTH, vm.periodType.value)
        assertEquals(AppPage.Period(returnTo = AppPage.Calendar), vm.page.value)
    }
}
