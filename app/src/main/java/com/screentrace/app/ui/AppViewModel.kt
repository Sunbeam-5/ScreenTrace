package com.screentrace.app.ui

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.screentrace.app.contract.AppUsage
import com.screentrace.app.contract.CalendarLevel
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.DailyPoint
import com.screentrace.app.contract.EmptyUsageRepository
import com.screentrace.app.contract.ExtremesInfo
import com.screentrace.app.contract.MetricDetail
import com.screentrace.app.contract.MetricKind
import com.screentrace.app.contract.StreakInfo
import com.screentrace.app.contract.Summary
import com.screentrace.app.contract.UsageCalculator
import com.screentrace.app.contract.UsageRepository
import com.screentrace.app.theme.ThemeVariant
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/** 页面路由（不引入 navigation 库——§6 依赖白名单不含它，单层二级页用状态即可）。 */
sealed interface AppPage {
    object Today : AppPage
    object Stats : AppPage
    object Calendar : AppPage

    /** 二级页：返回 + 标题（§5.2）；returnTo = 进入前所在的主页面。 */
    data class Ranking(val returnTo: AppPage = Today) : AppPage
    data class Settings(val returnTo: AppPage = Today) : AppPage

    /** CR-01 详情页路由（批 6d 填充内容；批 4 只建路由与最小占位）。 */
    data class UnlockDetail(val returnTo: AppPage = Today) : AppPage
    data class NotificationDetail(val returnTo: AppPage = Today) : AppPage

    companion object {
        /** 底部导航选中态映射：二级页保持进入前主页面的选中（§5.5）。 */
        fun mainOf(page: AppPage): AppPage = when (page) {
            is Ranking -> page.returnTo
            is Settings -> page.returnTo
            is UnlockDetail -> page.returnTo
            is NotificationDetail -> page.returnTo
            else -> page
        }
    }
}

/** 通用加载态：加载 / 错误 / 数据（§6 各页 UiState 的统一形状）。 */
data class LoadState<T>(
    val loading: Boolean = false,
    val error: String? = null,
    val data: T? = null
)

/** 今日页数据（hero / 双卡 / 应用构成，§5.7 CR-01）。 */
data class TodayData(
    val summary: Summary,
    val compare: UsageCalculator.CompareInfo,
    /** CR-01：卡片通知数字与详情页同源于 metricDetail（§7-16）。 */
    val notification: MetricDetail?,
    val topApps: List<AppUsage>,
    val otherMinutes: Int,
    /** 日间占比（%），与夜间互补。 */
    val dayPercent: Int
)

/** 统计页数据（趋势卡 / 时段分布 / 最久最少双卡）。 */
data class StatsData(
    val summary: Summary,
    val compare: UsageCalculator.CompareInfo,
    val trend: List<DailyPoint>,
    /** 06–12 / 12–18 / 18–22 / 22–06 四段分钟数（§5.7）。 */
    val periods: List<Int>,
    val extremes: ExtremesInfo
)

/** 日历页数据（连续记录 / 年视图 / 双卡）。点击详情直接用 CalendarLevel.minutes（返修 P1-4）。 */
data class CalendarData(
    val streak: StreakInfo,
    val extremes: ExtremesInfo,
    val yearLevels: List<CalendarLevel>
)

/** 排行页数据（摘要 + 全量应用列表，页面自行切 top20 / 全部）。 */
data class RankingData(
    val summary: Summary,
    val apps: List<AppUsage>
)

/**
 * §6 单向数据流：Repository -> ViewModel -> UiState -> Compose。
 * 四页共享同一 DateSelection（§5.6）：冷启动今天、切页/进排行/旋转保持、
 * 清除筛选恢复今天；「完全无记录」不应用新筛选、保留原视图并提示。
 * 主题与每日目标先内存承载（旋转保持），DataStore 持久化在批 6a 接入。
 */
class AppViewModel(
    private val repository: UsageRepository = EmptyUsageRepository()
) : ViewModel() {

    private val _selection = mutableStateOf<DateSelection>(DateSelection.today())
    val selection: State<DateSelection> = _selection

    private val _page = mutableStateOf<AppPage>(AppPage.Today)
    val page: State<AppPage> = _page

    private val _notice = mutableStateOf<String?>(null)
    val notice: State<String?> = _notice

    private val _todayState = mutableStateOf(LoadState<TodayData>(loading = true))
    val todayState: State<LoadState<TodayData>> = _todayState

    private val _statsState = mutableStateOf(LoadState<StatsData>(loading = true))
    val statsState: State<LoadState<StatsData>> = _statsState

    private val _calendarState = mutableStateOf(LoadState<CalendarData>(loading = true))
    val calendarState: State<LoadState<CalendarData>> = _calendarState

    private val _rankingState = mutableStateOf(LoadState<RankingData>(loading = true))
    val rankingState: State<LoadState<RankingData>> = _rankingState

    /** 日历月视图当前月份（锚点 = 区间结束日所在月，可前后翻月，§5.7）。 */
    private val _calendarMonth = mutableStateOf(YearMonth.now())
    val calendarMonth: State<YearMonth> = _calendarMonth

    private val _monthLevelsState = mutableStateOf(LoadState<List<CalendarLevel>>(loading = true))
    val monthLevelsState: State<LoadState<List<CalendarLevel>>> = _monthLevelsState

    private val _themeVariant = mutableStateOf(ThemeVariant.MINT)
    val themeVariant: State<ThemeVariant> = _themeVariant

    private val _darkTheme = mutableStateOf(false)
    val darkTheme: State<Boolean> = _darkTheme

    private val _dynamicColor = mutableStateOf(false)
    val dynamicColor: State<Boolean> = _dynamicColor

    /** 每日目标（分钟）。批 7 设置页可改、批 6a DataStore 持久化；此处为设置默认值。 */
    private val _dailyGoalMin = mutableStateOf(DEFAULT_GOAL_MIN)
    val dailyGoalMin: State<Int> = _dailyGoalMin

    private var loadJob: Job? = null

    init {
        loadAll()
    }

    // ---- 导航 ----

    fun selectMain(page: AppPage) {
        _page.value = page
    }

    fun openRanking() {
        _page.value = AppPage.Ranking(returnTo = AppPage.mainOf(_page.value))
    }

    fun openSettings() {
        _page.value = AppPage.Settings(returnTo = AppPage.mainOf(_page.value))
    }

    /** CR-01：详情路由入口（批 6d 给双卡接线时启用）。 */
    fun openUnlockDetail() {
        _page.value = AppPage.UnlockDetail(returnTo = AppPage.mainOf(_page.value))
    }

    fun openNotificationDetail() {
        _page.value = AppPage.NotificationDetail(returnTo = AppPage.mainOf(_page.value))
    }

    fun goBack() {
        val current = _page.value
        _page.value = when (current) {
            is AppPage.Ranking -> current.returnTo
            is AppPage.Settings -> current.returnTo
            is AppPage.UnlockDetail -> current.returnTo
            is AppPage.NotificationDetail -> current.returnTo
            else -> current
        }
    }

    // ---- 日期筛选（§5.6） ----

    /**
     * §5.6：单日/区间完全无记录时**不应用**新筛选，保留原视图并提示；
     * 部分有记录时正常应用。清除筛选（[clearFilter]）恢复今天，不做该拦截。
     */
    fun applySelection(newSelection: DateSelection) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val summary = repository.summary(newSelection)
            if (!summary.hasAnyRecord) {
                _notice.value = "该时段尚无数据，请先拉取"
            } else {
                _selection.value = newSelection
                _notice.value = null
                resetCalendarAnchor(newSelection)
                loadAll()
            }
        }
    }

    fun clearFilter() {
        _selection.value = DateSelection.today()
        _notice.value = null
        resetCalendarAnchor(_selection.value)
        loadAll()
    }

    fun consumeNotice() {
        _notice.value = null
    }

    /** 错误重试入口（ErrorRetry 按钮）。 */
    fun reload() {
        loadAll()
    }

    // ---- 日历月视图 ----

    fun setCalendarMonth(yearMonth: YearMonth) {
        _calendarMonth.value = yearMonth
        loadMonthLevels(yearMonth)
    }

    // ---- 主题与目标 ----

    fun setThemeVariant(variant: ThemeVariant) {
        _themeVariant.value = variant
    }

    fun setDarkTheme(dark: Boolean) {
        _darkTheme.value = dark
    }

    fun setDynamicColor(enabled: Boolean) {
        _dynamicColor.value = enabled
    }

    // ---- 数据加载 ----

    private fun resetCalendarAnchor(selection: DateSelection) {
        _calendarMonth.value = YearMonth.from(selection.endDate)
    }

    private fun loadAll() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val selection = _selection.value
            val single = selection is DateSelection.Day
            _todayState.value = LoadState(loading = true)
            _statsState.value = LoadState(loading = true)
            _calendarState.value = LoadState(loading = true)
            _rankingState.value = LoadState(loading = true)
            try {
                val summary = repository.summary(selection)
                if (!summary.hasAnyRecord) {
                    // 返修 P1-3（§5.5 红线）：完全无记录时 data=null → 页面统一 EmptyState，
                    // 不得渲染 0:00 / 0 次 / 0分钟 伪装真实数据
                    _todayState.value = LoadState(data = null)
                    _statsState.value = LoadState(data = null)
                    _calendarState.value = LoadState(data = null)
                    _rankingState.value = LoadState(data = null)
                    _monthLevelsState.value = LoadState(data = emptyList())
                    return@launch
                }
                val previous = repository.summary(UsageCalculator.previousPeriod(selection))
                val compare = UsageCalculator.compare(summary, previous, single)
                val notification = repository.metricDetail(MetricKind.NOTIFICATION, selection)

                // 今日
                val apps = repository.appRanking(selection, limit = Int.MAX_VALUE)
                val top = apps.take(4)
                val otherMinutes = apps.drop(4).sumOf { it.minutes }
                val hours = repository.hourlyDistribution(selection)
                val totalMinutes = hours.sumOf { it.minutes }
                val night = UsageCalculator.nightMinutes(hours)
                val dayPercent =
                    if (totalMinutes <= 0) 0 else ((totalMinutes - night) * 100 / totalMinutes)
                _todayState.value = LoadState(
                    data = TodayData(summary, compare, notification, top, otherMinutes, dayPercent)
                )

                // 统计
                _statsState.value = LoadState(
                    data = StatsData(
                        summary = summary,
                        compare = compare,
                        trend = repository.dailyTrend(selection),
                        periods = UsageCalculator.periodBuckets(hours),
                        extremes = repository.extremes(selection)
                    )
                )

                // 日历
                _calendarState.value = LoadState(
                    data = CalendarData(
                        streak = repository.streak(selection.endDate),
                        extremes = repository.extremes(selection),
                        yearLevels = repository.yearLevels(selection.endDate)
                    )
                )

                // 排行
                _rankingState.value = LoadState(data = RankingData(summary, apps))

                loadMonthLevels(_calendarMonth.value)
            } catch (e: Exception) {
                val message = e.message ?: "数据加载失败"
                _todayState.value = LoadState(error = message)
                _statsState.value = LoadState(error = message)
                _calendarState.value = LoadState(error = message)
                _rankingState.value = LoadState(error = message)
            }
        }
    }

    private fun loadMonthLevels(yearMonth: YearMonth) {
        viewModelScope.launch {
            _monthLevelsState.value = LoadState(loading = true)
            _monthLevelsState.value = try {
                LoadState(data = repository.monthLevels(yearMonth))
            } catch (e: Exception) {
                LoadState(error = e.message ?: "数据加载失败")
            }
        }
    }

    companion object {
        /** 设置默认值：每日目标 240 分钟（批 7 设置页可改、批 6a 持久化）。
         *  P2-5：单一出处 = SettingsStore，避免 UI 与持久化层各写一份 240。 */
        const val DEFAULT_GOAL_MIN = com.screentrace.app.data.prefs.SettingsStore.DEFAULT_GOAL_MIN
    }
}
