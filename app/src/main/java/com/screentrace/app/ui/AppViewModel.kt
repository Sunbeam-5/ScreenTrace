package com.screentrace.app.ui

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.screentrace.app.contract.AppUsage
import com.screentrace.app.contract.AppCategoryRow
import com.screentrace.app.contract.CalendarLevel
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.DailyPoint
import com.screentrace.app.contract.DEFAULT_LOW_THRESHOLD_MIN
import com.screentrace.app.contract.EmptyUsageRepository
import com.screentrace.app.contract.ExtremesInfo
import com.screentrace.app.contract.MetricDetail
import com.screentrace.app.contract.MetricKind
import com.screentrace.app.contract.PeriodSummary
import com.screentrace.app.contract.PeriodSummaryCalculator
import com.screentrace.app.contract.PeriodType
import com.screentrace.app.contract.StreakInfo
import com.screentrace.app.contract.Summary
import com.screentrace.app.contract.UsageCalculator
import com.screentrace.app.contract.UsageRepository
import com.screentrace.app.data.prefs.SettingsStore
import com.screentrace.app.data.pull.HistoryCoverage
import com.screentrace.app.data.pull.PullChannel
import com.screentrace.app.data.pull.PullCoordinator
import com.screentrace.app.data.pull.PullOutcome
import com.screentrace.app.data.pull.ProviderStatus
import com.screentrace.app.theme.ThemeVariant
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
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

    /** §5.11 周期总结（二级页）：周期类型存在 ViewModel 里，路由只带返回目标。 */
    data class Period(val returnTo: AppPage = Today) : AppPage

    /** §5.8「权限与数据」组：应用分类管理（二级页）。 */
    data class AppCategories(val returnTo: AppPage = Today) : AppPage

    /** §5.10 云端同步配置（二级页，真机反馈 #3：配置不平铺在设置页）。 */
    data class CloudSync(val returnTo: AppPage = Today) : AppPage

    companion object {
        /** 底部导航选中态映射：二级页保持进入前主页面的选中（§5.5）。 */
        fun mainOf(page: AppPage): AppPage = when (page) {
            is Ranking -> page.returnTo
            is Settings -> page.returnTo
            is UnlockDetail -> page.returnTo
            is NotificationDetail -> page.returnTo
            is Period -> page.returnTo
            is AppCategories -> page.returnTo
            // CloudSync 从设置页进入：返回目标可能是二级页（Settings），递归归位
            is CloudSync -> mainOf(page.returnTo)
            else -> page
        }
    }
}

/**
 * 整页空态的**原因**（6b-6d 评审 P0-1）。`data == null` 只说明「没东西可画」，
 * 但「从来没拉到过」和「拉到了、只是这条通道没有这类数据」是两件完全不同的事，
 * 给同一句文案就是在骗用户。
 */
enum class EmptyReason {
    /** 三张表全空：从未成功拉取，或系统确实没有任何可解析记录。 */
    NO_DATA,

    /**
     * 有数据，但只有应用级数据（降级通道）：`day_summary` 为空，
     * 所以屏幕时长 / 解锁 / 通知这些**没有事件源**的指标一律不渲染，
     * 应用排行照常出数据。
     */
    NO_EVENT_DATA
}

/** 通用加载态：加载 / 错误 / 数据 / 空态原因（§6 各页 UiState 的统一形状）。 */
data class LoadState<T>(
    val loading: Boolean = false,
    val error: String? = null,
    val data: T? = null,
    /** data == null 时说明为什么空；[EmptyReason] 决定页面用哪套文案。 */
    val empty: EmptyReason? = null
)

/** 今日页数据（hero / 双卡 / 应用构成，§5.7 CR-01）。 */
data class TodayData(
    val summary: Summary,
    val compare: UsageCalculator.CompareInfo,
    /** CR-01：卡片通知数字与详情页同源于 metricDetail（§7-16）。 */
    val notification: MetricDetail?,
    /** CR-01：卡片解锁数字同样与详情页同源，不用 Summary 另算一套（§7-16 三处一致）。 */
    val unlock: MetricDetail?,
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
    val extremes: ExtremesInfo,
    /**
     * 趋势图点柱浮层的数据源（§5.7-5）：每个自然日的 top3。
     * 默认空 Map —— 降级通道没有逐日应用数据时浮层只显示当日总时长，不画空行。
     */
    val topAppsPerDay: Map<java.time.LocalDate, List<AppUsage>> = emptyMap()
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
    val apps: List<AppUsage>,
    /**
     * 应用级时长合计（分钟）。降级通道下 `summary.totalScreenUnlockMin` 必为 0
     * （没有 `day_summary`），摘要卡若照读它就是在屏幕上画一个假 0；
     * 这时改读本字段并换标题（评审 P0-1 连带项）。
     */
    val appsTotalMinutes: Int
)

/**
 * §6 单向数据流：Repository -> ViewModel -> UiState -> Compose。
 * 四页共享同一 DateSelection（§5.6）：冷启动默认**近 7 天**（批 6g 真机反馈 #6）、
 * 切页/进排行/旋转保持、清除筛选恢复今天；「完全无记录」不应用新筛选、保留原视图并提示。
 * 主题与每日目标先内存承载（旋转保持），DataStore 持久化在批 6a 接入。
 */
class AppViewModel(
    private val repository: UsageRepository = EmptyUsageRepository(),
    /** 批 6b：真实拉取通道；为空时（预览/单测）不做任何拉取。 */
    private val pulls: PullCoordinator? = null,
    /** 批 6b：主题/目标持久化；为空时退回内存态。 */
    private val settings: SettingsStore? = null,
    /**
     * 批 7：拉取频率改动后立即重排 WorkManager（ViewModel 拿不到 Context，
     * 由 AppNav 用 LocalContext 注入；预览/单测不传，只改内存态）。
     */
    private val onPullFrequencyChanged: ((com.screentrace.app.data.prefs.PullFrequency) -> Unit)? = null,
    /** 批 8：本地备份导出 / 恢复。 */
    private val backups: com.screentrace.app.data.backup.BackupRepository? = null,
    /** 批 8b：云同步编排（WebDAV）。 */
    private val sync: com.screentrace.app.data.sync.SyncManager? = null,
    /** 批 8b：机密凭据（WebDAV 授权密码等，EncryptedSharedPreferences）。 */
    private val syncCredentials: com.screentrace.app.data.sync.SyncCredentials? = null
) : ViewModel() {

    // 批 6g（真机反馈 #6）：默认展示**近 7 天**——进 App 就能看到趋势与时段分布，
    // 而不是只有今天一根柱。清除筛选（clearFilter）仍然回到「今天」。
    private val _selection = mutableStateOf<DateSelection>(DateSelection.lastDays(7))
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

    /**
     * 排行页的口径覆盖（§7-26）：null = 跟随今日页的单日口径；
     * 非 null = 从周期总结进来，按该周期统计（副标题由 `RankingData.summary.selection` 决定）。
     */
    private var rankingScope: DateSelection? = null

    /** §5.11 周期总结：当前选中的周期类型（页内 SegmentedControl 切换）。 */
    private val _periodType = mutableStateOf(PeriodType.WEEK)
    val periodType: State<PeriodType> = _periodType

    private val _periodState = mutableStateOf<LoadState<PeriodSummary>>(LoadState(loading = true))
    val periodState: State<LoadState<PeriodSummary>> = _periodState

    /**
     * CR-01 详情页数据（批 6d）：与今日页双卡、图表、数据表**同源于同一 `metricDetail`
     * 查询结果**（§7-16「三处数字必须一致」），因此这里直接复用 [loadAll] 里那次调用，
     * 不为详情页另发一次查询——另发一次就可能因为拉取/写入时点不同而对不上。
     */
    private val _unlockDetail = mutableStateOf(LoadState<MetricDetail>(loading = true))
    val unlockDetail: State<LoadState<MetricDetail>> = _unlockDetail

    private val _notificationDetail = mutableStateOf(LoadState<MetricDetail>(loading = true))
    val notificationDetail: State<LoadState<MetricDetail>> = _notificationDetail

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

    /** §5.4 CR-04：自定义主题种子色（ARGB）。null = 走九套预设。 */
    private val _customThemeSeed = mutableStateOf<Long?>(null)
    val customThemeSeed: State<Long?> = _customThemeSeed

    /** 每日目标（分钟）。批 7 设置页可改、批 6a DataStore 持久化；此处为设置默认值。 */
    private val _dailyGoalMin = mutableStateOf(DEFAULT_GOAL_MIN)
    val dailyGoalMin: State<Int> = _dailyGoalMin

    /** 日历页「低于 x 小时」阈值（分钟），默认 2 小时，可在设置页改。 */
    private val _lowThresholdMin = mutableStateOf(DEFAULT_LOW_THRESHOLD_MIN)
    val lowThresholdMin: State<Int> = _lowThresholdMin

    // ---- 批 7：设置页（§5.8） ----

    /** 后台拉取频率（§5.8「目标与同步」）。 */
    private val _pullFrequency =
        mutableStateOf(com.screentrace.app.data.prefs.PullFrequency.DAILY)
    val pullFrequency: State<com.screentrace.app.data.prefs.PullFrequency> = _pullFrequency

    /**
     * 两条通道的授权状态（Shizuku / 使用情况访问）。
     *
     * 每次进设置页重探一次而不是缓存：用户常是「去 Shizuku 里点完授权再切回来」，
     * 缓存会让设置页一直显示「未授权」——这类假状态比没有状态更糟（§6）。
     */
    private val _channelStatuses =
        mutableStateOf<List<Pair<PullChannel, ProviderStatus>>>(emptyList())
    val channelStatuses: State<List<Pair<PullChannel, ProviderStatus>>> = _channelStatuses

    /** 应用分类管理列表（§5.8「权限与数据」组）。 */
    private val _appCategories =
        mutableStateOf(LoadState<List<AppCategoryRow>>(loading = true))
    val appCategories: State<LoadState<List<AppCategoryRow>>> = _appCategories

    /**
     * §5.8 首启向导是否已完成。`null` = 还没从 DataStore 读出来——
     * 这一帧既不画向导也不画主界面（铺一层底色），否则冷启动会先闪一下主界面再被向导盖住。
     */
    private val _onboardingDone = mutableStateOf<Boolean?>(null)
    val onboardingDone: State<Boolean?> = _onboardingDone

    // ---- 批 8b：云同步 UI 状态（非机密配置在 DataStore，机密在 SyncCredentials）----

    private val _webdavUrl = mutableStateOf(com.screentrace.app.data.sync.WebDavProvider.JIANGUOYUN_URL)
    val webdavUrl: State<String> = _webdavUrl

    private val _webdavUsername = mutableStateOf("")
    val webdavUsername: State<String> = _webdavUsername

    private val _webdavRemoteDir = mutableStateOf("/screen-time/")
    val webdavRemoteDir: State<String> = _webdavRemoteDir

    private val _syncFrequency = mutableStateOf(com.screentrace.app.data.prefs.SyncFrequency.OFF)
    val syncFrequency: State<com.screentrace.app.data.prefs.SyncFrequency> = _syncFrequency

    private val _syncWifiOnly = mutableStateOf(true)
    val syncWifiOnly: State<Boolean> = _syncWifiOnly

    private val _syncLastAt = mutableStateOf<Long?>(null)
    val syncLastAt: State<Long?> = _syncLastAt

    private val _syncLastOk = mutableStateOf<Boolean?>(null)
    val syncLastOk: State<Boolean?> = _syncLastOk

    private val _syncLastMessage = mutableStateOf("")
    val syncLastMessage: State<String> = _syncLastMessage

    /** WebDAV 密码是否已保存（只暴露布尔，密码本体不出 ViewModel，§5.10）。 */
    private val _webdavPasswordSaved = mutableStateOf(false)
    val webdavPasswordSaved: State<Boolean> = _webdavPasswordSaved

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
        // 拉取**不放在 init**：由 AppNav 挂在 ON_RESUME 上（§2.1「App 打开时拉取」），
        // 否则「退到后台再回来」与「去 Shizuku 授权完切回来」都不会触发拉取。
        // init 里再拉一次会和 ON_RESUME 首帧撞成两次调用（虽被节流/互斥兜住，但没必要）。

        // 但要订阅「后台已经拉完」这件事：WorkManager 的 Worker 与界面是两条独立路径，
        // 冷启动时它常常先把 dump 拉完入库，界面随后的 pullOnOpen 会被 30 分钟节流挡成
        // Throttled——不重读就停在首帧空态（真机必现「数据在库里，界面说尚无记录」）。
        viewModelScope.launch {
            pulls?.changes?.collect { loadAll() }
        }
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
            // `finally` 不是洁癖：applyPullOutcome 里的 historyCoverage()/loadAll() 都在
            // runCatching 之外，一旦抛异常（或协程被取消）`_pulling` 会永久停在 true，
            // 顶部「正在拉取使用记录」条与今日页的读取中状态就再也不消失。
            try {
                val outcome = runCatching { coordinator.pullIfStale() }
                    .getOrElse { PullOutcome.Failure(com.screentrace.app.data.pull.ProviderStatus.TEMPORARILY_UNAVAILABLE, it.message ?: "拉取失败", false) }
                applyPullOutcome(outcome, coordinator)
            } finally {
                _pulling.value = false
            }
        }
    }

    /** 设置页/内联错误条的「手动重试」：不节流。 */
    fun retryPull() {
        val coordinator = pulls ?: return
        viewModelScope.launch {
            _pulling.value = true
            try {
                val outcome = runCatching { coordinator.pull() }
                    .getOrElse { PullOutcome.Failure(com.screentrace.app.data.pull.ProviderStatus.TEMPORARILY_UNAVAILABLE, it.message ?: "拉取失败", false) }
                applyPullOutcome(outcome, coordinator)
            } finally {
                _pulling.value = false
            }
        }
    }

    /**
     * §7-19 下拉手势触发的强制拉取：绕过 [PullCoordinator.pullIfStale] 的 10 分钟节流。
     *
     * 60 秒轻冷却：用户连着拉两次时不再跑一遍 2–4MB 的 dump（对 Shizuku 和解析都是
     * 纯浪费），只重读本地库（后台 Worker 可能刚写完）并给一句「刚刚已更新」。
     * 冷却以**上次成功拉取**为准，失败不计时（失败后应该允许立刻重试）。
     *
     * 声明为 suspend：调用方（下拉指示器）等它结束再收起转圈，指示器不会一闪就没。
     * 批 8 的云同步「每次成功拉取后」分支后续在此函数成功路径上接线。
     */
    suspend fun refreshFromPull() {
        val coordinator = pulls ?: return
        if (_pulling.value) return
        val now = System.currentTimeMillis()
        val last = _lastPulledAt.value ?: 0L
        if (last > 0 && now - last in 0 until PULL_REFRESH_COOLDOWN_MS) {
            loadAll()
            _notice.value = "刚刚已更新"
            return
        }
        val outcome = runCatching { coordinator.pull() }
            .getOrElse {
                PullOutcome.Failure(
                    com.screentrace.app.data.pull.ProviderStatus.TEMPORARILY_UNAVAILABLE,
                    it.message ?: "拉取失败",
                    false
                )
            }
        applyPullOutcome(outcome, coordinator)
    }

    fun dismissPullError() {
        _pullError.value = null
    }

    /** 设置页/导出结果等一次性提示（复用顶部提示条，不用 Toast）。 */
    fun showNotice(message: String) {
        _notice.value = message
    }

    /** §5.8 首启向导完成（含跳过）。 */
    fun completeOnboarding() {
        if (_onboardingDone.value == true) return
        _onboardingDone.value = true
        persist { it.setOnboardingDone(true) }
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
                // 节流/并发：只刷新覆盖范围与最近拉取时间
                _coverage.value = coordinator.historyCoverage()
                // 但「这次没拉」不等于「库里没有新数据」：后台 Worker 可能刚写完，
                // 而界面还停在启动时那次空查询的结果上。只在当前没有数据时补读一次，
                // 免得每次回前台都把已有画面重算一遍（§5.7 不闪骨架）。
                if (_todayState.value.data == null) loadAll()
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
        // 没有持久化层（预览/单测）时不盖向导：看不到真实授权状态，向导只会是假界面
        val store = settings ?: run {
            _onboardingDone.value = true
            return
        }
        viewModelScope.launch {
            _dailyGoalMin.value = runCatching { store.dailyGoalMin.first() }.getOrDefault(DEFAULT_GOAL_MIN)
        }
        viewModelScope.launch {
            runCatching { store.lowUsageThresholdMin.collect { _lowThresholdMin.value = it } }
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
        viewModelScope.launch {
            runCatching { store.pullFrequency.collect { _pullFrequency.value = it } }
        }
        viewModelScope.launch {
            runCatching { store.onboardingDone.collect { _onboardingDone.value = it } }
                .onFailure {
                    // 评审 P1-4：DataStore 读失败（prefs 损坏/磁盘满）不能把用户
                    // 永远卡在「只铺底色」的纯色屏——兜底视为未完成，直接走向导
                    _onboardingDone.value = false
                }
        }
        viewModelScope.launch {
            runCatching { store.webdavUrl.collect { _webdavUrl.value = it } }
        }
        viewModelScope.launch {
            runCatching { store.webdavUsername.collect { _webdavUsername.value = it } }
        }
        viewModelScope.launch {
            runCatching { store.webdavRemoteDir.collect { _webdavRemoteDir.value = it } }
        }
        viewModelScope.launch {
            runCatching { store.syncFrequency.collect { _syncFrequency.value = it } }
        }
        viewModelScope.launch {
            runCatching { store.syncWifiOnly.collect { _syncWifiOnly.value = it } }
        }
        viewModelScope.launch {
            runCatching {
                store.syncLastAt.collect { at ->
                    _syncLastAt.value = at
                    _syncLastOk.value = store.syncLastOk.first()
                    _syncLastMessage.value = store.syncLastMessage.first()
                }
            }
        }
        _webdavPasswordSaved.value = (syncCredentials?.webdavPassword?.isNotEmpty() == true)
        viewModelScope.launch {
            runCatching { store.customThemeSeed.collect { _customThemeSeed.value = it } }
        }
    }

    /** 日历「低于 x 小时」阈值。改完必须重算：卡片标题与计数都来自 extremes。 */
    fun setLowThresholdMin(value: Int) {
        if (value == _lowThresholdMin.value) return
        _lowThresholdMin.value = value
        settings?.let { store ->
            viewModelScope.launch { runCatching { store.setLowUsageThresholdMin(value) } }
        }
        loadAll()
    }

    // ---- 导航 ----

    fun selectMain(page: AppPage) {
        _page.value = page
    }

    fun openRanking() {
        rankingScope = null
        _page.value = AppPage.Ranking(returnTo = AppPage.mainOf(_page.value))
    }

    /**
     * §7-26：从周期总结进入排行，按**周期**统计（不是单日、也不是无标识的区间累加）。
     * 排行页副标题由 `RankingData.summary.selection` 渲染，因此这里只需换数据窗口。
     */
    fun openRankingFor(period: PeriodSummary) {
        rankingScope = period.range.selection
        _page.value = AppPage.Ranking(returnTo = AppPage.mainOf(_page.value))
        loadAll()
    }

    /** §5.11 入口：从统计页 / 日历页进入周期总结，默认按传入的周期类型。 */
    fun openPeriod(type: PeriodType = _periodType.value) {
        _periodType.value = type
        _page.value = AppPage.Period(returnTo = AppPage.mainOf(_page.value))
        loadPeriod()
    }

    /** 页内周/月/年切换。 */
    fun setPeriodType(type: PeriodType) {
        if (type == _periodType.value && _periodState.value.data != null) return
        _periodType.value = type
        loadPeriod()
    }

    /** 打开云端同步二级页（§5.10，真机反馈 #3）。 */
    fun openCloudSync() {
        _page.value = AppPage.CloudSync(returnTo = _page.value)
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
        // 离开排行页时把口径恢复成默认的「今天」：否则下次从今日页点「查看全部」
        // 还会带着上一次的周期窗口（副标题写着「11月1日–11月12日」，数字却对不上卡片）。
        if (current is AppPage.Ranking && rankingScope != null) {
            rankingScope = null
            loadAll()
        }
        _page.value = when (current) {
            is AppPage.Ranking -> current.returnTo
            is AppPage.Settings -> current.returnTo
            is AppPage.UnlockDetail -> current.returnTo
            is AppPage.NotificationDetail -> current.returnTo
            is AppPage.Period -> current.returnTo
            is AppPage.AppCategories -> current.returnTo
            else -> current
        }
    }

    // ---- 日期筛选（§5.6） ----

    /**
     * §5.6：单日/区间完全无记录时**不应用**新筛选，保留原视图并提示；
     * 部分有记录时正常应用。清除筛选（[clearFilter]）恢复今天，不做该拦截。
     *
     * 判据用 `hasDataIn` 而不是 `summary.hasAnyRecord`（评审 P0-1）：降级通道没有
     * `day_summary`，用旧判据会让**每一次选日期都被拦**，用户永远只能看今天。
     */
    fun applySelection(newSelection: DateSelection) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (!repository.hasDataIn(newSelection)) {
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
        // 「清除筛选」= 回到今天（与默认的近 7 天不同，保留一个明确的回退动作）
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

    /**
     * §5.4 自定义主题：null = 恢复九套预设。
     * 只替换调色板，不重拉数据、不改筛选、不重置滚动位置（与切预设主题同一待遇）。
     */
    fun setCustomThemeSeed(argb: Long?) {
        _customThemeSeed.value = argb
        persist { it.setCustomThemeSeed(argb) }
    }

    /** §5.8 每日目标：批 7 设置页滑块调用，落 DataStore。 */
    fun setDailyGoalMin(minutes: Int) {
        _dailyGoalMin.value = minutes
        persist { it.setDailyGoalMin(minutes) }
    }

    /**
     * §5.8 拉取频率：写盘 + 立即重排后台任务。
     *
     * 只写 DataStore 是不够的——WorkManager 里排着的还是旧周期的任务，
     * 用户改成「每 6 小时」后要等到下次冷启动才生效，看起来就是「改了没用」。
     */
    fun setPullFrequency(frequency: com.screentrace.app.data.prefs.PullFrequency) {
        if (frequency == _pullFrequency.value) return
        _pullFrequency.value = frequency
        persist { it.setPullFrequency(frequency) }
        onPullFrequencyChanged?.invoke(frequency)
    }

    /** 设置页可见/回到前台时重探两条通道（§5.8 授权状态必须真实）。 */
    fun refreshChannelStatuses() {
        val coordinator = pulls ?: return
        viewModelScope.launch {
            _channelStatuses.value = runCatching { coordinator.channelStatuses() }
                .getOrDefault(emptyList())
        }
    }

    /** 打开应用分类管理（§5.8「权限与数据」组）。 */
    fun openAppCategories() {
        _page.value = AppPage.AppCategories(returnTo = AppPage.mainOf(_page.value))
    }

    fun loadAppCategories() {
        viewModelScope.launch {
            val rows = runCatching { repository.appCategories() }.getOrDefault(emptyList())
            _appCategories.value = LoadState(data = rows)
        }
    }

    /**
     * 改分类：写覆盖 + 重读列表 + 重算四页。
     *
     * 为什么要 loadAll：排行页与详情页的分类标签直接来自读库时的覆盖叠加（§4），
     * 不重算就会出现「分类页已经改了、排行页还是旧标签」。
     */
    fun setAppCategory(pkg: String, category: com.screentrace.app.contract.AppCategory) {
        persist { it.setCategoryOverride(pkg, category) }
        loadAppCategories()
        loadAll()
    }

    /**
     * §7-12：生成自包含 HTML 报告（纯文本返回值，写盘交给 UI 层的 SAF）。
     *
     * 数据全部**现查一次仓库**（与页面同一套查询），而不是从 `statsState` 里捡——
     * 页面状态可能还挂着上一次筛选的旧数据，导出时切过日期就会导错区间。
     */
    suspend fun buildHtmlReport(hideAppNames: Boolean, appVersion: String): Pair<String, String>? {
        val selection = _selection.value
        return runCatching {
            val summary = repository.summary(selection)
            val input = com.screentrace.app.data.export.HtmlReportInput(
                rangeLabel = reportRangeLabel(selection),
                exportedAt = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                summary = summary,
                daily = repository.dailyTrend(selection),
                apps = repository.appRanking(selection, limit = REPORT_APP_LIMIT),
                hours = repository.hourlyDistribution(selection),
                appVersion = appVersion,
                hideAppNames = hideAppNames,
                dailyGoalMin = _dailyGoalMin.value
            )
            com.screentrace.app.data.export.HtmlReportExporter.fileName(java.time.LocalDateTime.now()) to
                com.screentrace.app.data.export.HtmlReportExporter.build(input)
        }.getOrNull()
    }

    // ---- 批 8b：云同步（§5.10）----

    /** 保存 WebDAV 非机密配置（地址/账号/远端目录）。 */
    fun setWebdavConfig(url: String, username: String, remoteDir: String) {
        _webdavUrl.value = url
        _webdavUsername.value = username
        _webdavRemoteDir.value = remoteDir
        viewModelScope.launch {
            val store = settings ?: return@launch
            store.setWebdavUrl(url)
            store.setWebdavUsername(username)
            store.setWebdavRemoteDir(remoteDir)
        }
    }

    /** 保存 WebDAV 授权密码（机密 → EncryptedSharedPreferences，不进日志/备份）。 */
    fun setWebdavPassword(password: String) {
        syncCredentials?.webdavPassword = password
        _webdavPasswordSaved.value = password.isNotEmpty()
    }

    fun setSyncFrequency(frequency: com.screentrace.app.data.prefs.SyncFrequency) {
        _syncFrequency.value = frequency
        viewModelScope.launch { settings?.setSyncFrequency(frequency) }
    }

    fun setSyncWifiOnly(enabled: Boolean) {
        _syncWifiOnly.value = enabled
        viewModelScope.launch { settings?.setSyncWifiOnly(enabled) }
    }

    suspend fun testCloudConnection(): com.screentrace.app.data.sync.SyncResult {
        val manager = sync ?: return com.screentrace.app.data.sync.SyncResult.Err(
            com.screentrace.app.data.sync.SyncFailure.UNKNOWN, "同步模块未装配"
        )
        val result = manager.testConnection()
        refreshSyncStatus()
        return result
    }

    /** 手动上传：完整备份覆盖远端固定文件 screen-time-backup.json（§5.10）。 */
    suspend fun uploadToCloud(): com.screentrace.app.data.sync.SyncResult {
        val manager = sync ?: return com.screentrace.app.data.sync.SyncResult.Err(
            com.screentrace.app.data.sync.SyncFailure.UNKNOWN, "同步模块未装配"
        )
        val result = manager.uploadManual(appVersion = "app")
        refreshSyncStatus()
        return result
    }

    /** 从云端下载固定文件并解码（恢复策略二选一在 UI 层，执行仍走统一入口）。 */
    suspend fun downloadFromCloud(): com.screentrace.app.data.sync.SyncManager.DownloadedBackup {
        val manager = sync ?: return com.screentrace.app.data.sync.SyncManager.DownloadedBackup(
            error = com.screentrace.app.data.sync.SyncResult.Err(
                com.screentrace.app.data.sync.SyncFailure.UNKNOWN, "同步模块未装配"
            )
        )
        val downloaded = manager.downloadManual()
        refreshSyncStatus()
        return downloaded
    }

    private suspend fun refreshSyncStatus() {
        val store = settings ?: return
        _syncLastAt.value = store.syncLastAt.first()
        _syncLastOk.value = store.syncLastOk.first()
        _syncLastMessage.value = store.syncLastMessage.first()
    }

    // ---- 批 8：备份与恢复 ----

    /** 生成 JSON 完整备份：（建议文件名, 全文）。 */
    suspend fun buildBackupJson(appVersion: String): Pair<String, String>? {
        val repo = backups ?: return null
        return runCatching { repo.exportJson(appVersion) }.getOrNull()
    }

    /** 生成七张明细表的 CSV：（文件名, 内容）列表。 */
    suspend fun buildCsvFiles(appVersion: String): List<Pair<String, String>> {
        val repo = backups ?: return emptyList()
        return runCatching { repo.exportCsv(appVersion) }.getOrDefault(emptyList())
    }

    suspend fun decodeBackup(text: String): com.screentrace.app.data.backup.BackupCodec.DecodeResult =
        com.screentrace.app.data.backup.BackupCodec.decode(text)

    /**
     * 执行恢复（单事务）。成功后重算四页——恢复的数据与当前界面必然不一致，
     * 不重算就会出现「提示导入 8 天，今日页还是空的」。
     */
    suspend fun restoreBackup(
        file: com.screentrace.app.data.backup.BackupFile,
        overwrite: Boolean,
        importSettings: Boolean
    ): com.screentrace.app.data.backup.BackupRepository.RestoreResult? {
        val repo = backups ?: return null
        val result = runCatching {
            repo.restore(
                file = file,
                strategy = if (overwrite) {
                    com.screentrace.app.data.backup.BackupRepository.Strategy.OVERWRITE
                } else {
                    com.screentrace.app.data.backup.BackupRepository.Strategy.MERGE
                },
                importSettings = importSettings
            )
        }.getOrNull() ?: return null
        _coverage.value = pulls?.historyCoverage()
        loadAll()
        return result
    }

    /** 报告标题里的区间文案（与页面副标题同一口径，单日不带「天」）。 */
    private fun reportRangeLabel(selection: DateSelection): String = when (selection) {
        is DateSelection.Day -> "${selection.date.monthValue}月${selection.date.dayOfMonth}日 · 1天"
        is DateSelection.Range ->
            "${selection.start.monthValue}月${selection.start.dayOfMonth}日 – " +
                "${selection.end.monthValue}月${selection.end.dayOfMonth}日 · ${selection.days}天"
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
            // 「低于几小时算少」的来源。变化时由 setLowThresholdMin 触发整页重算。
            val threshold = _lowThresholdMin.value
            // §5.7「已有缓存直接渲染」：重算期间**四个主页面也保留上一条结果**——
            // 之前只有详情页这样做，四个主页被直接清成 loading=true（data=null），
            // 换个日期/点一下「近 7 天」整页就闪一次骨架（真机反馈 #2/#3/#5 的根因）。
            // data 保留后，页面渲染旧内容直到新数据落地，无骨架、无入场重播。
            _todayState.value = LoadState(loading = true, data = _todayState.value.data)
            _statsState.value = LoadState(loading = true, data = _statsState.value.data)
            _calendarState.value = LoadState(loading = true, data = _calendarState.value.data)
            _rankingState.value = LoadState(loading = true, data = _rankingState.value.data)
            _unlockDetail.value = LoadState(loading = true, data = _unlockDetail.value.data)
            _notificationDetail.value = LoadState(loading = true, data = _notificationDetail.value.data)
            try {
                // 评审 P0-1：整页空态的入口判定必须看**三张表**，不能只看 day_summary。
                // 降级通道（普通用户的唯一通道）按设计不写 day_summary，用旧判据会把
                // 「库里有应用数据」判成「完全无数据」，四页一起盖空白——拉取成功却像坏了。
                if (!repository.hasAnyData()) {
                    // 三表全空：真的没数据（返修 P1-3 §5.5 红线，不得渲染 0:00 / 0 次）
                    setAllEmpty(EmptyReason.NO_DATA)
                    return@launch
                }

                val summary = repository.summary(selection)
                val apps = repository.appRanking(selection, limit = Int.MAX_VALUE)
                val hours = repository.hourlyDistribution(selection)
                val previous = repository.summary(UsageCalculator.previousPeriod(selection))
                val compare = UsageCalculator.compare(summary, previous, single)

                // ---- 「今日」页与两个下钻详情页**显示单日**（CR-09 扩展 CR-03 修订）----
                // 全局筛选为单日时跟随该单日（如「昨天」：单日数据与今日页量纲一致——
                // 圆环=当日亮屏、应用构成=当日构成、明细=当日明细）；筛选为区间时仍锚定
                // 今天（CR-03 的量纲冲突只在区间下成立：圆环是日均、构成是累计，永远对不上）。
                val todaySelection = if (single) selection else DateSelection.today()
                val todayIsSelection = todaySelection == selection
                val todaySummary =
                    if (todayIsSelection) summary else repository.summary(todaySelection)
                val todayApps = if (todayIsSelection) {
                    apps
                } else {
                    repository.appRanking(todaySelection, limit = Int.MAX_VALUE)
                }
                val todayHours =
                    if (todayIsSelection) hours else repository.hourlyDistribution(todaySelection)
                val todayCompare = if (todayIsSelection) {
                    compare
                } else {
                    UsageCalculator.compare(
                        todaySummary,
                        repository.summary(UsageCalculator.previousPeriod(todaySelection)),
                        true
                    )
                }

                // 排行只依赖 app_usage，与日聚合无关：降级通道下它是唯一还能用的页面
                // §7-26：从周期总结进来时按该周期统计，否则按今日页的锚点单日
                val scope = rankingScope
                val rankingSummary = when {
                    scope == null -> todaySummary
                    scope == selection -> summary
                    else -> repository.summary(scope)
                }
                val rankingApps = when {
                    scope == null -> todayApps
                    scope == selection -> apps
                    else -> repository.appRanking(scope, limit = Int.MAX_VALUE)
                }
                _rankingState.value =
                    if (rankingApps.isEmpty()) {
                        LoadState(data = null, empty = EmptyReason.NO_EVENT_DATA)
                    } else {
                        LoadState(
                            data = RankingData(
                                // §5.7 排行-2：入口在今日页的「应用构成」卡上，卡片是**今天**
                                // 的构成，点进去却按全局筛选的区间累计，两处数字对不上。
                                // 排行默认按今天单日，标题会自动显示「当日累计」；
                                // 从周期总结进入时按周期窗口，标题写明周期（§7-26）。
                                summary = rankingSummary,
                                apps = rankingApps,
                                appsTotalMinutes = rankingApps.sumOf { it.minutes }
                            )
                        )
                    }

                // 两个窗口都没有日聚合 → 屏幕时长/解锁/通知都没有事件源，这些页空态。
                // 但排行**不能一起清**：它只依赖 app_usage，降级通道下是唯一还能用的页面
                // （评审 P0-1）。所以这里不用 setAllEmpty（那会把 ranking 也清掉）。
                if (!todaySummary.hasAnyRecord && !summary.hasAnyRecord) {
                    _todayState.value = LoadState(data = null, empty = EmptyReason.NO_EVENT_DATA)
                    _statsState.value = LoadState(data = null, empty = EmptyReason.NO_EVENT_DATA)
                    _calendarState.value = LoadState(data = null, empty = EmptyReason.NO_EVENT_DATA)
                    _unlockDetail.value = LoadState(data = null, empty = EmptyReason.NO_EVENT_DATA)
                    _notificationDetail.value =
                        LoadState(data = null, empty = EmptyReason.NO_EVENT_DATA)
                    _monthLevelsState.value = LoadState(data = emptyList())
                    return@launch
                }

                // ---- 今日 ----
                if (!todaySummary.hasAnyRecord) {
                    // 昨天/历史有聚合但今天没有（例如今天还没拉取）→ 只空今日与两个明细页
                    _todayState.value = LoadState(data = null, empty = EmptyReason.NO_EVENT_DATA)
                    _unlockDetail.value = LoadState(data = null, empty = EmptyReason.NO_EVENT_DATA)
                    _notificationDetail.value =
                        LoadState(data = null, empty = EmptyReason.NO_EVENT_DATA)
                } else {
                    // 明细页的入口都在今日页的卡上，所以它们的窗口也是今天（不是全局筛选）
                    val notification =
                        repository.metricDetail(MetricKind.NOTIFICATION, todaySelection)
                    val unlock = repository.metricDetail(MetricKind.UNLOCK, todaySelection)
                    _notificationDetail.value = LoadState(data = notification)
                    _unlockDetail.value = LoadState(data = unlock)

                    val top = todayApps.take(4)
                    val otherMinutes = todayApps.drop(4).sumOf { it.minutes }
                    val todayTotalMinutes = todayHours.sumOf { it.minutes }
                    val todayNight = UsageCalculator.nightMinutes(todayHours)
                    val dayPercent =
                        if (todayTotalMinutes <= 0) 0
                        else ((todayTotalMinutes - todayNight) * 100 / todayTotalMinutes)
                    _todayState.value = LoadState(
                        data = TodayData(
                            summary = todaySummary,
                            compare = todayCompare,
                            notification = notification,
                            unlock = unlock,
                            topApps = top,
                            otherMinutes = otherMinutes,
                            dayPercent = dayPercent
                        )
                    )
                }

                // ---- 统计 / 日历（跟随全局筛选）----
                if (!summary.hasAnyRecord) {
                    _statsState.value = LoadState(data = null, empty = EmptyReason.NO_EVENT_DATA)
                    _calendarState.value = LoadState(data = null, empty = EmptyReason.NO_EVENT_DATA)
                    _monthLevelsState.value = LoadState(data = emptyList())
                } else {
                    _statsState.value = LoadState(
                        data = StatsData(
                            summary = summary,
                            compare = compare,
                            trend = repository.dailyTrend(selection),
                            periods = UsageCalculator.periodBuckets(hours),
                            extremes = repository.extremes(selection, threshold),
                            topAppsPerDay = repository.topAppsPerDay(selection, limit = 3)
                        )
                    )
                    _calendarState.value = LoadState(
                        data = CalendarData(
                            streak = repository.streak(selection.endDate),
                            extremes = repository.extremes(selection, threshold),
                            yearLevels = repository.yearLevels(selection.endDate)
                        )
                    )
                    loadMonthLevels(_calendarMonth.value)
                }

                // §5.11：周期总结页已经打开过 → 跟随本次刷新重算（首次进入由 openPeriod 触发）。
                // 不加这个，改完日期/拉完新数据再回周期页看到的还是旧数字。
                if (_periodState.value.data != null) loadPeriod()
            } catch (e: Exception) {
                val message = e.message ?: "数据加载失败"
                // 失败同样保留旧数据（§5.8）：页面分支是 data 优先，旧内容还在就继续展示，
                // error 只在真的什么都没有时才以 ErrorRetry 呈现。
                _todayState.value = LoadState(error = message, data = _todayState.value.data)
                _statsState.value = LoadState(error = message, data = _statsState.value.data)
                _calendarState.value = LoadState(error = message, data = _calendarState.value.data)
                _rankingState.value = LoadState(error = message, data = _rankingState.value.data)
                _unlockDetail.value = LoadState(error = message, data = _unlockDetail.value.data)
                _notificationDetail.value =
                    LoadState(error = message, data = _notificationDetail.value.data)
                _periodState.value =
                    LoadState(error = message, data = _periodState.value.data)
            }
        }
    }

    /**
     * §5.11：加载当前周期类型的总结。
     *
     * 只复用仓库已有的四个查询（dailyTrend / summary / appRanking / hourlyDistribution），
     * 不新增 DAO：换周期只是换一个 DateSelection 窗口，聚合规则全在
     * [PeriodSummaryCalculator] 里（纯函数，已有 JVM 单测）。
     * `previous` 取**上一等长窗口**的摘要（未结束时截断到相同天数），与页面上的
     * 「较上周日均」严格同源。
     */
    private fun loadPeriod() {
        viewModelScope.launch {
            // 同其它页：重算期间保留上一条结果，切周/月/年不闪骨架
            _periodState.value = LoadState(loading = true, data = _periodState.value.data)
            val type = _periodType.value
            val today = LocalDate.now()
            val range = type.natural(today)
            _periodState.value = try {
                val trend = repository.dailyTrend(range.selection)
                val previous = repository.summary(range.previousEqualLength().selection)
                val apps = repository.appRanking(range.selection, limit = Int.MAX_VALUE)
                val hours = repository.hourlyDistribution(range.selection)
                val period = PeriodSummaryCalculator.build(
                    type = type,
                    today = today,
                    trend = trend,
                    previousSummary = previous,
                    apps = apps,
                    hours = hours
                )
                if (!period.summary.hasAnyRecord) {
                    LoadState(data = null, empty = EmptyReason.NO_EVENT_DATA)
                } else {
                    LoadState(data = period)
                }
            } catch (e: Exception) {
                LoadState(
                    error = e.message ?: "数据加载失败",
                    data = _periodState.value.data
                )
            }
        }
    }

    /** 整页空态的单一出处：六处状态一起清，避免「关了 5 个页忘了第 6 个」。 */
    private fun setAllEmpty(reason: EmptyReason) {
        _todayState.value = LoadState(data = null, empty = reason)
        _statsState.value = LoadState(data = null, empty = reason)
        _calendarState.value = LoadState(data = null, empty = reason)
        _rankingState.value = LoadState(data = null, empty = reason)
        _unlockDetail.value = LoadState(data = null, empty = reason)
        _notificationDetail.value = LoadState(data = null, empty = reason)
        _periodState.value = LoadState(data = null, empty = reason)
        _monthLevelsState.value = LoadState(data = emptyList())
    }

    private fun loadMonthLevels(yearMonth: YearMonth) {
        viewModelScope.launch {
            // 同 loadAll：翻月重算期间保留上个月的热力图，不闪骨架
            _monthLevelsState.value =
                LoadState(loading = true, data = _monthLevelsState.value.data)
            _monthLevelsState.value = try {
                LoadState(data = repository.monthLevels(yearMonth))
            } catch (e: Exception) {
                LoadState(error = e.message ?: "数据加载失败", data = _monthLevelsState.value.data)
            }
        }
    }

    companion object {
        /** 设置默认值：每日目标 240 分钟（批 7 设置页可改、批 6a 持久化）。
         *  P2-5：单一出处 = SettingsStore，避免 UI 与持久化层各写一份 240。 */
        const val DEFAULT_GOAL_MIN = com.screentrace.app.data.prefs.SettingsStore.DEFAULT_GOAL_MIN

        /** §7-19 下拉刷新的轻冷却（毫秒）。 */
        const val PULL_REFRESH_COOLDOWN_MS = 60L * 1000

        /** HTML 报告里的应用排行条数：报告是概览，不铺几百行（页面里另有「全部应用」）。 */
        const val REPORT_APP_LIMIT = 20
    }
}
