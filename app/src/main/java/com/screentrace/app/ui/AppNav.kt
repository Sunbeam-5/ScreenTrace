package com.screentrace.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.Composable
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
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme
import com.screentrace.app.uikit.AppHeader
import com.screentrace.app.uikit.FreshNavBar
import com.screentrace.app.uikit.NavItem
import com.screentrace.app.uikit.PageScaffold
import com.screentrace.app.uikit.ScrollablePage
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AppNav() {
    val container = (LocalContext.current.applicationContext as? ScreenTraceApp)?.container
    val viewModel: AppViewModel = viewModel(factory = viewModelFactory {
        initializer {
            if (container != null) {
                AppViewModel(
                    repository = container.repository,
                    pulls = container.pullCoordinator,
                    settings = container.settings
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
    val unlockDetail by viewModel.unlockDetail
    val notificationDetail by viewModel.notificationDetail
    val monthLevelsState by viewModel.monthLevelsState
    val calendarMonth by viewModel.calendarMonth
    val dailyGoalMin by viewModel.dailyGoalMin
    val notice by viewModel.notice
    val pullError by viewModel.pullError
    val pulling by viewModel.pulling
    val lowThresholdMin by viewModel.lowThresholdMin

    var showDateSheet by rememberSaveable { mutableStateOf(false) }
    var showStyleSheet by rememberSaveable { mutableStateOf(false) }

    // §2.1「App 打开时拉取」的真实落点：挂在 ON_RESUME 上而不是 ViewModel.init。
    // init 只在进程内首次建 VM 时跑一次——用户退到后台再回来（尤其是去 Shizuku 里
    // 授权完切回来）时不会重新拉，界面就永远停在旧数据。ON_RESUME 覆盖冷启动
    // （START→RESUME）与「从授权页返回」（PAUSE→RESUME）两种情况；
    // 重复调用由协调器的节流 + 互斥兜住，这里不需要自己去重。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.pullOnOpen()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ScreenTimeTheme(variant = variant, darkTheme = darkTheme, dynamicColor = dynamicColor) {
        val palette = LocalFreshPalette.current
        val reduceMotion = rememberReduceMotion()
        val mainPage = AppPage.mainOf(page)
        val detailPage = detailOf(page)

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
        ) {
            // ---- 主页面层（常驻：三页都在场，切回来时滚动位置与内容都是现成的）----
            PageScaffold(
                header = {
                    AppHeader(
                        title = titleOf(mainPage),
                        subtitle = selectionSubtitle(selection),
                        onDateClick = { showDateSheet = true },
                        onStyleClick = { showStyleSheet = true },
                        onSettingsClick = viewModel::openSettings
                    )
                }
            ) {
                Column(Modifier.fillMaxSize()) {
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
                                    onRetry = viewModel::reload
                                )
                                else -> CalendarScreen(
                                    state = calendarState,
                                    monthLevelsState = monthLevelsState,
                                    calendarMonth = calendarMonth,
                                    onMonthChange = viewModel::setCalendarMonth,
                                    onRetry = viewModel::reload,
                                    lowThresholdMin = lowThresholdMin
                                )
                            }
                        }
                    }
                }
            }

            // ---- 二级页覆盖层：自带顶部栏，盖住主页面层（含它的标题栏），但让出底部导航 ----
            AnimatedContent(
                targetState = detailPage,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    // **纯滑动，不做淡入淡出**（真机反馈 #4）：覆盖层半透明时会透出
                    // 底下的主页面，形成「两页叠影」——这就是退场时看到的视觉残留。
                    // 整页不透明滑动（标准 push/pop 手势的方向感）没有这个问题。
                    if (targetState != null) {
                        // 进入：整页从右滑入
                        slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { it }
                            .togetherWith(fadeOut(tween(120)))
                    } else {
                        // 返回：整页向右滑出，比进入更快一点，回退要「利落」
                        fadeIn(tween(120))
                            .togetherWith(slideOutHorizontally(tween(240, easing = FastOutSlowInEasing)) { it })
                    }
                },
                contentAlignment = Alignment.TopStart,
                label = "detail"
            ) { detail ->
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
                                    subtitle = null,
                                    onBack = viewModel::goBack,
                                    onDateClick = { showDateSheet = true }
                                )
                                is AppPage.Settings -> AppHeader(
                                    title = "设置",
                                    subtitle = null,
                                    onBack = viewModel::goBack
                                )
                                is AppPage.UnlockDetail -> AppHeader(
                                    title = "解锁详情",
                                    subtitle = null,
                                    onBack = viewModel::goBack,
                                    onDateClick = { showDateSheet = true }
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
                                    darkTheme = darkTheme,
                                    dynamicColor = dynamicColor,
                                    onDarkThemeChange = viewModel::setDarkTheme,
                                    onDynamicColorChange = viewModel::setDynamicColor,
                                    lowThresholdMin = lowThresholdMin,
                                    onLowThresholdChange = viewModel::setLowThresholdMin
                                )
                                is AppPage.UnlockDetail -> UnlockDetailScreen(
                                    state = unlockDetail,
                                    onRetry = viewModel::reload
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
                onSelect = viewModel::setThemeVariant,
                onDismiss = { showStyleSheet = false }
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
