package com.screentrace.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.screentrace.app.ScreenTraceApp
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.EmptyUsageRepository
import com.screentrace.app.data.pull.PullScheduler
import com.screentrace.app.ui.onboarding.OnboardingScreen
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme
import com.screentrace.app.uikit.AppHeader
import com.screentrace.app.uikit.ContainerTransformOverlay
import com.screentrace.app.uikit.ContainerTransformState
import com.screentrace.app.uikit.FreshNavBar
import com.screentrace.app.uikit.LocalContainerTransformState
import com.screentrace.app.uikit.NavItem
import com.screentrace.app.uikit.PageScaffold
import com.screentrace.app.uikit.ScrollablePage
import com.screentrace.app.uikit.recordTransformOrigin
import com.screentrace.app.uikit.rememberReduceMotion
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")

/** 三个主页面，顺序与底部导航一致；Pager 的页索引就是这个列表的下标。 */
private val MAIN_PAGES = listOf(AppPage.Today, AppPage.Stats, AppPage.Calendar)

/**
 * 应用根组合：主题包裹 + 主页面 Pager + 二级页覆盖层 + 底部胶囊导航 + 日期/风格弹层。
 *
 * ### 批 6g：为什么从「淡入 hack」改成 Pager
 *
 * 上一版用一个 `Animatable(1f)` + `LaunchedEffect(page)` 里 `snapTo(0f)` 再淡入来做转场，
 * 真机表现是「切 tab 先闪一下完整内容，再淡进来」。原因很明确：
 * `LaunchedEffect` 在**首帧提交之后**才跑，新页面第一帧是以 alpha=1 完整画出来的，
 * 第二帧才被按到 0 —— 那一帧就是用户看到的「闪现」。
 * 而且三个页面共用同一个 `ScrollState`：从滚了一半的今日切到统计，统计页直接从
 * 同样的偏移开始画，看起来也像「内容突然冒出来」。
 *
 * 现在的做法对齐 nsh07/Tomato 的思路（它用 NavDisplay 的 `fadeIn togetherWith fadeOut`，
 * 新旧两页**同时在场**做交叉过渡，任何时刻都没有「先完整显示再消失」的中间帧）：
 * 1. 三个主页面交给 `HorizontalPager`，`beyondBoundsPageCount = 2` 让三页**常驻**——
 *    切换时目标页早已组合、布局完成，落帧就是最终内容，不存在补画。
 * 2. 每页各自一份 `ScrollState`（在 `ScrollablePage` 内部 `remember`），互不串位。
 * 3. 点 tab 用 `animateScrollToPage`（默认 spring），也能直接横滑，跟手且有惯性。
 * 4. 二级页是覆盖在主页面之上的独立图层，右侧小幅滑入 + 交叉淡出；底部导航画在
 *    最外层，所以二级页里导航条依然在（§5.2/§5.5）。
 */
@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)
@Composable
fun AppNav() {
    val context = LocalContext.current
    val container = (context.applicationContext as? ScreenTraceApp)?.container
    val viewModel: AppViewModel = viewModel(factory = viewModelFactory {
        initializer {
            if (container != null) {
                AppViewModel(
                    repository = container.repository,
                    pulls = container.pullCoordinator,
                    settings = container.settings,
                    backups = container.backup,
                    sync = container.syncManager,
                    syncCredentials = container.syncCredentials,
                    // 改拉取频率后立即重排后台任务（§5.8）：等下次冷启动才生效
                    // 在用户看来就是「改了没用」。
                    onPullFrequencyChanged = { frequency ->
                        PullScheduler.ensureScheduled(context.applicationContext, frequency)
                    }
                )
            } else {
                AppViewModel(EmptyUsageRepository())
            }
        }
    })
    val variant by viewModel.themeVariant
    val darkTheme by viewModel.darkTheme
    val dynamicColor by viewModel.dynamicColor
    val page by viewModel.page
    val selection by viewModel.selection
    val todayState by viewModel.todayState
    val statsState by viewModel.statsState
    val calendarState by viewModel.calendarState
    val rankingState by viewModel.rankingState
    val periodState by viewModel.periodState
    val unlockDetail by viewModel.unlockDetail
    val notificationDetail by viewModel.notificationDetail
    val monthLevelsState by viewModel.monthLevelsState
    val calendarMonth by viewModel.calendarMonth
    val dailyGoalMin by viewModel.dailyGoalMin
    val notice by viewModel.notice
    val pullError by viewModel.pullError
    val pulling by viewModel.pulling
    val lowThresholdMin by viewModel.lowThresholdMin
    val coverage by viewModel.coverage
    val lastPulledAt by viewModel.lastPulledAt
    val pullFrequency by viewModel.pullFrequency
    val channelStatuses by viewModel.channelStatuses
    val appCategories by viewModel.appCategories
    val onboardingDone by viewModel.onboardingDone
    val customThemeSeed by viewModel.customThemeSeed
    val webdavUrl by viewModel.webdavUrl
    val webdavUsername by viewModel.webdavUsername
    val webdavRemoteDir by viewModel.webdavRemoteDir
    val webdavPasswordSaved by viewModel.webdavPasswordSaved
    val syncFrequency by viewModel.syncFrequency
    val syncWifiOnly by viewModel.syncWifiOnly
    val syncLastAt by viewModel.syncLastAt
    val syncLastOk by viewModel.syncLastOk
    val syncLastMessage by viewModel.syncLastMessage

    /** 云端下载的备份候选 → 转给 BackupGroup 的统一恢复流程（同一套策略 UI）。 */
    var cloudCandidate by remember {
        mutableStateOf<Pair<com.screentrace.app.data.backup.BackupFile, com.screentrace.app.data.backup.BackupSummary>?>(null)
    }

    /** §5.8 设置页「关于」与 HTML 报告用的版本号（BuildConfig 未开启，直接问 PackageManager）。 */
    val appVersion = remember(context) {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "—"
    }

    // §5.8：进设置页时重探一次两条通道的授权状态——用户常是「去 Shizuku 里点完授权再切回来」，
    // 只在冷启动时探一次会一直显示「未授权」。分类页每次进入重读，改完立刻能看到新分类。
    LaunchedEffect(page, pulling) {
        when (page) {
            is AppPage.Settings -> viewModel.refreshChannelStatuses()
            is AppPage.AppCategories -> viewModel.loadAppCategories()
            else -> Unit
        }
    }

    // 向导第 2 步要显示真实授权状态，进向导时就探一次
    LaunchedEffect(onboardingDone) {
        if (onboardingDone == false) viewModel.refreshChannelStatuses()
    }

    var showDateSheet by rememberSaveable { mutableStateOf(false) }
    var showStyleSheet by rememberSaveable { mutableStateOf(false) }
    // 自定义主题取色器不存实例状态：进程重建后滑杆位置无所谓，种子本身已落 DataStore
    var showCustomThemeSheet by remember { mutableStateOf(false) }

    // §5.5 / §7-22：二级页容器变换的起点（最近一次在主页面层按下的位置）。
    // 进入与退出共用同一起点，所以「返回手势」和「返回按钮」缩回的是同一个地方。
    val transformState = remember { ContainerTransformState() }

    // §2.1「App 打开时拉取」的真实落点：挂在 ON_RESUME 上而不是 ViewModel.init。
    // init 只在进程内首次建 VM 时跑一次——用户退到后台再回来（尤其是去 Shizuku 里
    // 授权完切回来）时不会重新拉，界面就永远停在旧数据。ON_RESUME 覆盖冷启动
    // （START→RESUME）与「从授权页返回」（PAUSE→RESUME）两种情况；
    // 重复调用由协调器的节流 + 互斥兜住，这里不需要自己去重。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.pullOnOpen()
                // 从 Shizuku / 系统授权页返回时状态可能已经变了，顺手重探一次
                viewModel.refreshChannelStatuses()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // LifecycleEventObserver 只接收**之后**发生的事件。首次组合如果发生在 onResume
        // 之后（Compose 的首帧可能晚于 Activity 生命周期回调），这一次 ON_RESUME 就永远
        // 收不到，表现为「打开 App 压根不拉取」——真机就踩到了：装完新包反复冷启动，
        // pull_log 一条新记录都没有，应用构成一直是空的。所以这里补一次当前状态检查。
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            viewModel.pullOnOpen()
            viewModel.refreshChannelStatuses()
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ScreenTimeTheme(
        variant = variant,
        darkTheme = darkTheme,
        dynamicColor = dynamicColor,
        // §5.4 CR-04：自定义种子优先于预设；派生结果已含对比度校正
        customSeed = customThemeSeed?.let { androidx.compose.ui.graphics.Color(it) }
    ) {
        val palette = LocalFreshPalette.current

        // §5.8 首启向导：没看过就先盖向导。
        // `null` 是「DataStore 还没读出来」，这一帧只铺底色——否则会先完整画一遍主界面
        // （连带触发一次拉取与四页加载），再被向导盖住，视觉上就是一次白闪。
        if (onboardingDone != true) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(palette.background)
            ) {
                if (onboardingDone == false) {
                    OnboardingScreen(
                        themeVariant = variant,
                        onThemeVariantChange = viewModel::setThemeVariant,
                        darkTheme = darkTheme,
                        onDarkThemeChange = viewModel::setDarkTheme,
                        dailyGoalMin = dailyGoalMin,
                        onDailyGoalChange = viewModel::setDailyGoalMin,
                        pullFrequency = pullFrequency,
                        onPullFrequencyChange = viewModel::setPullFrequency,
                        channelStatuses = channelStatuses,
                        onRefreshStatuses = viewModel::refreshChannelStatuses,
                        onFinish = viewModel::completeOnboarding
                    )
                }
            }
            return@ScreenTimeTheme
        }

        val reduceMotion = rememberReduceMotion()
        // §7-19：三个主页面共享一份下拉状态（手势挂在主内容层，Pager 横滑不走这条链）。
        // 覆盖层自带 pointerInput 命中层，打开二级页时下拉不会穿透过来。
        val pullState = rememberPullToRefreshState()
        val mainPage = AppPage.mainOf(page)
        val detailPage = detailOf(page)

        // 退出动画期间 `detailPage` 已经变成 null，但覆盖层还得继续画**旧内容**才能缩回去。
        // 所以缓存最后一次非空的二级页。这里的写入只在值真正变化时发生一次（写完就相等），
        // 不会自激重组；换成 LaunchedEffect 反而会让首帧没有内容可画。
        var lastDetail by remember { mutableStateOf<AppPage?>(null) }
        if (detailPage != null && lastDetail != detailPage) lastDetail = detailPage
        // 覆盖层实际渲染的页面：退出动画期间 detailPage 已为 null，退回缓存的最后一个
        val layerDetail = detailPage ?: lastDetail

        val pagerState = rememberPagerState(
            initialPage = MAIN_PAGES.indexOf(mainPage).coerceAtLeast(0)
        ) { MAIN_PAGES.size }

        // ① 导航状态 → Pager：点底部导航、或从二级页返回时让 Pager 滑到对应页。
        //    `animateScrollToPage` 的默认动画就是 spring（StiffnessMediumLow），
        //    和 Tomato 用 motionScheme 空间动画的手感一致，比固定 tween 自然。
        LaunchedEffect(mainPage) {
            // 切主页面时收起打开中的弹层（真机反馈 #7）：弹层属于某一个页面的上下文，
            // 页面都换了还挂着一张旧弹层只会让人困惑。返回二级页不受影响（mainPage 未变）。
            showDateSheet = false
            showStyleSheet = false
            val target = MAIN_PAGES.indexOf(mainPage)
            if (target !in MAIN_PAGES.indices) return@LaunchedEffect
            if (pagerState.currentPage != target) {
                if (reduceMotion) pagerState.scrollToPage(target)
                else pagerState.animateScrollToPage(target)
            }
        }
        // ② Pager → 导航状态：用户横滑落定后把选中项同步回 ViewModel，
        //    否则导航条高亮会和实际页面对不上。
        //    这里读 `viewModel.page.value` 而不是组合里的 `page`：这个 effect 只起一次，
        //    捕获的 `page` 会一直停留在首次组合时的值，判断就会失效。
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage }.collect { index ->
                val target = MAIN_PAGES.getOrNull(index) ?: return@collect
                if (AppPage.mainOf(viewModel.page.value) != target) viewModel.selectMain(target)
            }
        }

        // 系统栏图标明暗必须跟**用户选的**深浅色走，而不是跟系统：
        // enableEdgeToEdge 只在启动时按系统深浅设一次，用户在 App 内切深色后
        // 状态栏图标会变成「深色图标压深色背景」，几乎看不见。
        val view = LocalView.current
        val activity = LocalContext.current as? android.app.Activity
        SideEffect {
            if (activity != null) {
                val controller = androidx.core.view.WindowCompat.getInsetsController(activity.window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }

        // 系统返回键 / 全面屏返回手势：二级页必须回到上一层，而不是直接退到桌面。
        // 主页面（今日/统计/日历）不拦截，保持系统默认的「退出应用」。
        BackHandler(enabled = !isRootPage(page)) { viewModel.goBack() }

        Box(
            Modifier
                .fillMaxSize()
                .background(palette.background)
                // 只在主页面层记录起点：覆盖层打开后若还记录，点「返回」会把起点改成
                // 返回按钮的位置，退出就缩错地方了（真正想要的是缩回进入时的那个点）。
                .recordTransformOrigin(transformState, enabled = detailPage == null)
        ) {
            // ---- 主页面层（常驻：三页都在场，切回来时滚动位置与内容都是现成的）----
            // 只有这一层登记转场起点：覆盖层在 provider 之外，读到 null，里面的日期/返回
            // 按钮不会把自己记成新起点（否则从设置页返回会缩到返回按钮上）。
            CompositionLocalProvider(LocalContainerTransformState provides transformState) {
                PageScaffold(
                header = {
                    AppHeader(
                        title = titleOf(mainPage),
                        // 今日页锚定今天（§5.6 修订）：它不跟随全局筛选，
                        // 副标题也必须写「今天」，否则用户会以为看到的是区间数据。
                        subtitle = selectionSubtitle(
                            if (mainPage == AppPage.Today) DateSelection.today() else selection
                        ),
                        onDateClick = { showDateSheet = true },
                        onStyleClick = { showStyleSheet = true },
                        onSettingsClick = viewModel::openSettings
                    )
                }
            ) {
                Box(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .nestedScroll(pullState.nestedScrollConnection)
                    ) {
                    // 提示条 / 拉取错误 / 拉取中共用一份，不跟着页面横滑（它们是全局状态，
                    // 三页各放一份既重复又会各自播一遍展开动画）。
                    // 底部固定留 12dp：没有提示条时就是标题与首卡之间的呼吸位，
                    // 有提示条时它随展开动画把内容整体下推，不会出现「啪地跳一格」。
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // （批 6g 三轮）全局筛选胶囊条已移除：顶部标题栏副标题本来就
                        // 显示当前区间，跨页常驻的「有记录 x 天」条被真机反馈点名；
                        // 恢复「今天」用日期弹层里的「今天」快捷项。
                        AnimatedVisibility(
                            visible = notice != null,
                            enter = expandVertically(tween(140)) + fadeIn(tween(140)),
                            exit = shrinkVertically(tween(140)) + fadeOut(tween(100))
                        ) {
                            notice?.let { text ->
                                NoticeBar(text = text, onDismiss = viewModel::consumeNotice)
                            }
                        }
                        // §5.8：已有历史时拉取失败 → 顶部内联错误 + 可重试，页面继续显示旧数据
                        AnimatedVisibility(
                            visible = pullError != null,
                            enter = expandVertically(tween(140)) + fadeIn(tween(140)),
                            exit = shrinkVertically(tween(140)) + fadeOut(tween(100))
                        ) {
                            pullError?.let { text ->
                                NoticeBar(
                                    text = text,
                                    onDismiss = viewModel::dismissPullError,
                                    actionText = "重试",
                                    onAction = viewModel::retryPull
                                )
                            }
                        }
                        AnimatedVisibility(
                            visible = pulling,
                            enter = expandVertically(tween(140)) + fadeIn(tween(140)),
                            exit = shrinkVertically(tween(140)) + fadeOut(tween(100))
                        ) {
                            PullingBar()
                        }
                    }

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        // 三页全部常驻：切 tab 时目标页已组合 + 已布局，落帧即终态，
                        // 不会出现「先空一帧再补内容」的闪烁（只有 3 页，代价可忽略）。
                        beyondBoundsPageCount = MAIN_PAGES.size,
                        // 二级页盖在上面时禁止横滑，避免在覆盖层底下偷偷翻页
                        userScrollEnabled = detailPage == null
                    ) { index ->
                        ScrollablePage {
                            when (MAIN_PAGES[index]) {
                                AppPage.Today -> TodayScreen(
                                    state = todayState,
                                    dailyGoalMin = dailyGoalMin,
                                    // 首次 dump 要十几秒：这期间显示「正在读取」，而不是「尚无使用记录」
                                    pulling = pulling,
                                    onOpenRanking = viewModel::openRanking,
                                    onRetry = viewModel::reload,
                                    onOpenUnlock = viewModel::openUnlockDetail,
                                    onOpenNotification = viewModel::openNotificationDetail
                                )
                                AppPage.Stats -> StatsScreen(
                                    state = statsState,
                                    selection = selection,
                                    onApplySelection = viewModel::applySelection,
                                    onRetry = viewModel::reload,
                                    // §5.11 入口：统计页底部「本周/本月/本年总结」
                                    onOpenPeriod = viewModel::openPeriod,
                                    // 离开统计页时收起前三名浮层
                                    resetKey = mainPage
                                )
                                else -> CalendarScreen(
                                    state = calendarState,
                                    monthLevelsState = monthLevelsState,
                                    calendarMonth = calendarMonth,
                                    onMonthChange = viewModel::setCalendarMonth,
                                    onRetry = viewModel::reload,
                                    lowThresholdMin = lowThresholdMin,
                                    // §5.11：日历页顶部同一入口
                                    onOpenPeriod = viewModel::openPeriod
                                )
                            }
                        }
                    }
                }
                        PullToRefreshContainer(
                            state = pullState,
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                        if (pullState.isRefreshing) {
                            LaunchedEffect(Unit) {
                                // 等真正拉完再收起转圈，指示器不会一闪就没。
                                // **必须 finally**：拉取里任何一条异常/协程取消路径
                                // （DB 查询失败、通道崩、页面被销毁）如果跳过 endRefresh，
                                // isRefreshing 就永远停在 true，指示器永久转圈——
                                // 这是「状态机未复位」（stuck refreshing state）的典型形态，
                                // 比一次可见的失败更难查，因为界面看着还在「努力」。
                                try {
                                    viewModel.refreshFromPull()
                                } finally {
                                    pullState.endRefresh()
                                }
                            }
                        }
                    }
                }
            }

            // ---- 二级页覆盖层：自带顶部栏，盖住主页面层（含它的标题栏），但让出底部导航 ----
            // 转场用容器变换（§5.5 / §7-22）：从进入时按下的位置放大到全屏、返回缩回原处，
            // 与系统「打开 App：从图标展开」是同一套隐喻；全程不透明，不会出现两层叠影。
            ContainerTransformOverlay(
                visible = detailPage != null,
                state = transformState,
                reduceMotion = reduceMotion
            ) {
                val detail = layerDetail
                if (detail != null) {
                    PageScaffold(
                        // 覆盖层的不透明背景挡住了主页面，但**触摸**并不会被挡住：
                        // 空白处的点击会穿透到下层标题栏的「日期 / 设置」按钮上。
                        // 这个 pointerInput 只接收不消费，作用是让本层成为命中目标，
                        // 从而把下层兄弟节点排除在命中链之外（子节点仍先收到事件）。
                        modifier = Modifier.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent()
                            }
                        },
                        header = {
                            when (detail) {
                                is AppPage.Ranking -> AppHeader(
                                    title = "全部应用",
                                    // 必须写明当前窗口，避免与今日页的区间数字混淆（§5.7 排行-2）
                                    subtitle = rankingState.data?.summary?.selection
                                        ?.let { selectionSubtitle(it) },
                                    onBack = viewModel::goBack,
                                    onDateClick = { showDateSheet = true }
                                )
                                is AppPage.Settings -> AppHeader(
                                    title = "设置",
                                    subtitle = null,
                                    onBack = viewModel::goBack
                                )
                                is AppPage.AppCategories -> AppHeader(
                                    title = "应用分类",
                                    subtitle = null,
                                    onBack = viewModel::goBack
                                )
                                is AppPage.UnlockDetail -> AppHeader(
                                    title = "解锁详情",
                                    subtitle = null,
                                    onBack = viewModel::goBack,
                                    onDateClick = { showDateSheet = true }
                                )
                                is AppPage.Period -> AppHeader(
                                    title = "周期总结",
                                    // 副标题写当前周期窗口与「未结束 · 截至 X」，与页内一致
                                    subtitle = periodState.data?.subtitle,
                                    onBack = viewModel::goBack
                                )
                                else -> AppHeader(
                                    title = "通知详情",
                                    subtitle = null,
                                    onBack = viewModel::goBack,
                                    onDateClick = { showDateSheet = true }
                                )
                            }
                        }
                    ) {
                        ScrollablePage {
                            when (detail) {
                                is AppPage.Ranking -> RankingScreen(
                                    state = rankingState,
                                    onRetry = viewModel::reload
                                )
                                is AppPage.Settings -> SettingsScreen(
                                    dailyGoalMin = dailyGoalMin,
                                    onDailyGoalChange = viewModel::setDailyGoalMin,
                                    pullFrequency = pullFrequency,
                                    onPullFrequencyChange = viewModel::setPullFrequency,
                                    lastPulledAt = lastPulledAt,
                                    pulling = pulling,
                                    onPullNow = viewModel::retryPull,
                                    channelStatuses = channelStatuses,
                                    onRefreshStatuses = viewModel::refreshChannelStatuses,
                                    coverage = coverage,
                                    onOpenAppCategories = viewModel::openAppCategories,
                                    themeVariant = variant,
                                    onOpenStyleSheet = { showStyleSheet = true },
                                    customThemeEnabled = customThemeSeed != null,
                                    onOpenCustomTheme = { showCustomThemeSheet = true },
                                    darkTheme = darkTheme,
                                    onDarkThemeChange = viewModel::setDarkTheme,
                                    dynamicColor = dynamicColor,
                                    onDynamicColorChange = viewModel::setDynamicColor,
                                    lowThresholdMin = lowThresholdMin,
                                    onLowThresholdChange = viewModel::setLowThresholdMin,
                                    appVersion = appVersion,
                                    buildReport = { hideNames ->
                                        viewModel.buildHtmlReport(hideNames, appVersion)
                                    },
                                    onBuildBackupJson = { viewModel.buildBackupJson(appVersion) },
                                    onBuildCsv = { viewModel.buildCsvFiles(appVersion) },
                                    onDecodeBackup = { text -> viewModel.decodeBackup(text) },
                                    onRestoreBackup = { file, overwrite, importSettings ->
                                        viewModel.restoreBackup(file, overwrite, importSettings)
                                    },
                                    externalCloudCandidate = cloudCandidate,
                                    cloudSection = {
                                        CloudSyncGroup(
                                            webdavUrl = webdavUrl,
                                            webdavUsername = webdavUsername,
                                            webdavRemoteDir = webdavRemoteDir,
                                            passwordSaved = webdavPasswordSaved,
                                            syncFrequency = syncFrequency,
                                            syncWifiOnly = syncWifiOnly,
                                            lastAt = syncLastAt,
                                            lastOk = syncLastOk,
                                            lastMessage = syncLastMessage,
                                            onConfigChange = viewModel::setWebdavConfig,
                                            onPasswordSet = viewModel::setWebdavPassword,
                                            onFrequencyChange = viewModel::setSyncFrequency,
                                            onWifiOnlyChange = viewModel::setSyncWifiOnly,
                                            onTestConnection = { viewModel.testCloudConnection() },
                                            onUpload = { viewModel.uploadToCloud() },
                                            onDownload = { viewModel.downloadFromCloud() },
                                            onDownloadedReady = { file, summary -> cloudCandidate = file to summary },
                                            onNotice = viewModel::showNotice
                                        )
                                    },
                                    onNotice = viewModel::showNotice
                                )
                                is AppPage.AppCategories -> AppCategoryScreen(
                                    state = appCategories,
                                    onSetCategory = viewModel::setAppCategory,
                                    onRetry = viewModel::loadAppCategories
                                )
                                is AppPage.UnlockDetail -> UnlockDetailScreen(
                                    state = unlockDetail,
                                    onRetry = viewModel::reload
                                )
                                is AppPage.Period -> PeriodScreen(
                                    state = periodState,
                                    onTypeChange = viewModel::setPeriodType,
                                    onOpenAllApps = viewModel::openRankingFor,
                                    onRetry = viewModel::reload,
                                    resetKey = page
                                )
                                else -> NotificationDetailScreen(
                                    state = notificationDetail,
                                    onRetry = viewModel::reload
                                )
                            }
                        }
                    }
                }
            }

            // ---- 底部导航：画在最上层，二级页里也保持选中项不跳变（§5.2/§5.5）----
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // 全面屏手势条会压住悬浮导航：垫起 navigationBars 高度，
                    // 再留 20dp 视觉间距（§5.2）
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp)
            ) {
                FreshNavBar(
                    items = listOf(
                        NavItem("今日", Icons.Default.Home),
                        NavItem("统计", Icons.AutoMirrored.Filled.List),
                        NavItem("日历", Icons.Default.DateRange)
                    ),
                    selectedIndex = MAIN_PAGES.indexOf(mainPage).coerceAtLeast(0),
                    onSelect = { index ->
                        viewModel.selectMain(MAIN_PAGES[index])
                    }
                )
            }
        }

        if (showDateSheet) {
            DateSheet(
                current = selection,
                onApply = {
                    viewModel.applySelection(it)
                    showDateSheet = false
                },
                onDismiss = { showDateSheet = false }
            )
        }
        if (showStyleSheet) {
            StyleSheet(
                current = variant,
                onSelect = { variant ->
                    // 评审 P1-5：点哪套就是哪套——选预设即退出自定义主题。
                    // 否则种子还留着时 variant 变了色板不变，用户会以为主题切换坏了
                    viewModel.setThemeVariant(variant)
                    viewModel.setCustomThemeSeed(null)
                },
                onDismiss = { showStyleSheet = false }
            )
        }
        if (showCustomThemeSheet) {
            ThemeSeedSheet(
                currentSeed = customThemeSeed,
                onApply = { argb ->
                    viewModel.setCustomThemeSeed(argb)
                    showCustomThemeSheet = false
                },
                onReset = {
                    viewModel.setCustomThemeSeed(null)
                    showCustomThemeSheet = false
                },
                onDismiss = { showCustomThemeSheet = false }
            )
        }
    }
}

/** 主页面标题：与底部导航一一对应。 */
private fun titleOf(mainPage: AppPage): String = when (mainPage) {
    AppPage.Stats -> "统计"
    AppPage.Calendar -> "日历"
    else -> "今日"
}

/** 二级页（覆盖层）：需要自己的顶部栏与返回。返回 null 表示当前在主页面层。 */
private fun detailOf(page: AppPage): AppPage? = when (page) {
    is AppPage.Ranking -> page
    is AppPage.Settings -> page
    is AppPage.UnlockDetail -> page
    is AppPage.NotificationDetail -> page
    is AppPage.Period -> page
    is AppPage.AppCategories -> page
    else -> null
}

/** §5.2 AppHeader 副标题：今天 / 指定单日 / 区间。 */
private fun selectionSubtitle(selection: DateSelection): String = when (selection) {
    is DateSelection.Day ->
        if (selection.date == LocalDate.now()) "今天 · ${selection.date.format(DAY_FORMAT)}"
        else selection.date.format(DAY_FORMAT)
    is DateSelection.Range ->
        "${selection.start.format(DAY_FORMAT)} – ${selection.end.format(DAY_FORMAT)}"
}

/** 三个主页面（底部导航所在层）。其余都是二级页，需要拦截系统返回。 */
private fun isRootPage(page: AppPage): Boolean =
    page === AppPage.Today || page === AppPage.Stats || page === AppPage.Calendar

/** §5.6「完全无记录」提示条 / §5.8 拉取内联错误：内联展示、可关闭，不用 Toast（§5.5）。 */
@Composable
private fun NoticeBar(
    text: String,
    onDismiss: () -> Unit,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    val palette = LocalFreshPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(palette.surfaceVariant)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = FreshType.label,
            color = palette.error,
            modifier = Modifier.weight(1f)
        )
        if (actionText != null && onAction != null) {
            Text(
                text = actionText,
                style = FreshType.label,
                color = palette.primary,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onAction)
                    .semantics {
                        contentDescription = "$actionText：$text"
                        role = Role.Button
                    }
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            )
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .semantics {
                    contentDescription = "关闭提示"
                    role = Role.Button
                }
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = palette.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** §5.8「拉取中」：一行中性提示，不用遮罩（拉取期间不许锁死界面）。 */
@Composable
private fun PullingBar() {
    val palette = LocalFreshPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(palette.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "正在拉取使用记录…",
            style = FreshType.label,
            color = palette.onSurfaceVariant
        )
    }
}
