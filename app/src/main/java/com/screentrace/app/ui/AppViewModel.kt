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
import com.screentrace.app.data.prefs.SettingsStore
import com.screentrace.app.data.pull.HistoryCoverage
import com.screentrace.app.data.pull.PullCoordinator
import com.screentrace.app.data.pull.PullOutcome
import com.screentrace.app.theme.ThemeVariant
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
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
    private val repository: UsageRepository = EmptyUsageRepository(),
    /** 批 6b：真实拉取通道；为空时（预览/单测）不做任何拉取。 */
    private val pulls: PullCoordinator? = null,
    /** 批 6b：主题/目标持久化；为空时退回内存态。 */
    private val settings: SettingsStore? = null
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

    // ---- 批 6b：拉取状态（§5.8 未启动/未授权/拉取中/失败/空 dump） ----

    private val _pulling = mutableStateOf(false)
    val pulling: State<Boolean> = _pulling

    /** 已有历史时拉取失败的内联错误（§5.8：保留旧数据 + 顶部内联错误，不整页报错）。 */
    private val _pullError = mutableStateOf<String?>(null)
    val pullError: State<String?> = _pullError

    /** 库里已有数据的覆盖范围（「部分历史 / 完全无数据」两态判定）。 */
    private val _coverage = mutableStateOf<HistoryCoverage?>(null)
    val coverage: State<HistoryCoverage?> = _coverage

    private val _lastPulledAt = mutableStateOf<Long?>(null)
    val lastPulledAt: State<Long?> = _lastPulledAt

    init {
        observeSettings()
        loadAll()
        pullOnOpen()
    }

    // ---- 拉取 ----

    /**
     * §2.1：App 打开时拉取，30 分钟内不重复（节流在 [PullCoordinator] 里，
     * 以 `pull_log` 为准，进程重启也不会重复拉）。
     */
    fun pullOnOpen() {
        val coordinator = pulls ?: return
        viewModelScope.launch {
            _pulling.value = true
            val outcome = runCatching { coordinator.pullIfStale() }
                .getOrElse { PullOutcome.Failure(com.screentrace.app.data.pull.ProviderStatus.TEMPORARILY_UNAVAILABLE, it.message ?: "拉取失败", false) }
            applyPullOutcome(outcome, coordinator)
            _pulling.value = false
        }
    }

    /** 设置页/内联错误条的「手动重试」：不节流。 */
    fun retryPull() {
        val coordinator = pulls ?: return
        viewModelScope.launch {
            _pulling.value = true
            val outcome = runCatching { coordinator.pull() }
                .getOrElse { PullOutcome.Failure(com.screentrace.app.data.pull.ProviderStatus.TEMPORARILY_UNAVAILABLE, it.message ?: "拉取失败", false) }
            applyPullOutcome(outcome, coordinator)
            _pulling.value = false
        }
    }

    fun dismissPullError() {
        _pullError.value = null
    }

    private suspend fun applyPullOutcome(outcome: PullOutcome, coordinator: PullCoordinator) {
        when (outcome) {
            is PullOutcome.Success -> {
                _pullError.value = null
                _lastPulledAt.value = outcome.pulledAtMs
                _coverage.value = coordinator.historyCoverage()
                loadAll()
            }

            PullOutcome.Throttled, PullOutcome.AlreadyRunning -> {
                // 节流/并发：不动界面，只刷新一次覆盖范围与最近拉取时间
                _coverage.value = coordinator.historyCoverage()
            }

            PullOutcome.EmptyDump -> {
                _pullError.value = "系统暂无可解析的使用记录（dump 为空），已保留原有数据"
                _coverage.value = coordinator.historyCoverage()
            }

            is PullOutcome.Failure -> {
                _pullError.value = if (outcome.keptOldData) {
                    "拉取失败：${outcome.message}（已保留原有数据）"
                } else {
                    "拉取失败：${outcome.message}"
                }
                _coverage.value = coordinator.historyCoverage()
                loadAll()
            }
        }
    }

    /** 主题/目标从 DataStore 同步到界面（批 6a 已持久化，6b 接上）。 */
    private fun observeSettings() {
        val store = settings ?: return
        viewModelScope.launch {
            _dailyGoalMin.value = runCatching { store.dailyGoalMin.first() }.getOrDefault(DEFAULT_GOAL_MIN)
        }
        viewModelScope.launch {
            runCatching {
                store.themeVariant.collect { _themeVariant.value = it }
            }
        }
        viewModelScope.launch {
            runCatching { store.darkTheme.collect { _darkTheme.value = it } }
        }
        viewModelScope.launch {
            runCatching { store.dynamicColor.collect { _dynamicColor.value = it } }
        }
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
        persist { it.setThemeVariant(variant) }
    }

    fun setDarkTheme(dark: Boolean) {
        _darkTheme.value = dark
        persist { it.setDarkTheme(dark) }
    }

    fun setDynamicColor(enabled: Boolean) {
        _dynamicColor.value = enabled
        persist { it.setDynamicColor(enabled) }
    }

    /** §5.8 每日目标：批 7 设置页滑块调用，落 DataStore。 */
    fun setDailyGoalMin(minutes: Int) {
        _dailyGoalMin.value = minutes
        persist { it.setDailyGoalMin(minutes) }
    }

    /**
     * 设置写盘失败不阻塞交互（DataStore 极少失败，失败也能靠内存态继续用）。
     *
     * **显式传参而不是 `suspend SettingsStore.() -> Unit`**：后者与 AppViewModel 自身的
     * `setThemeVariant` 同名，lambda 里的无接收者调用会解析到「最近的作用域」——写成
     * 接收者 lambda 时极易误调 `this@AppViewModel.setThemeVariant` 造成无限递归
     * （编译能过，运行时栈溢出）。这里用 `(SettingsStore) -> Unit` + `it.` 把歧义彻底消灭。
     */
    private fun persist(block: suspend (SettingsStore) -> Unit) {
        val store = settings ?: return
        viewModelScope.launch { runCatching { block(store) } }
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
