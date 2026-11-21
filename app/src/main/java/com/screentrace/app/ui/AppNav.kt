package com.screentrace.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.screentrace.app.ScreenTraceApp
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.EmptyUsageRepository
import android.os.Build
import com.screentrace.app.data.pull.PullScheduler
import com.screentrace.app.data.sync.SyncManager
import com.screentrace.app.data.wallpaper.WallpaperTuning
import com.screentrace.app.data.wallpaper.WallpaperBlur
import com.screentrace.app.ui.onboarding.OnboardingScreen
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme
import com.screentrace.app.theme.WallpaperState
import com.screentrace.app.uikit.AppHeader
import com.screentrace.app.uikit.ContainerTransformOverlay
import com.screentrace.app.uikit.ContainerTransformState
import com.screentrace.app.uikit.FreshNavBar
import com.screentrace.app.uikit.LocalContainerTransformState
import com.screentrace.app.uikit.NavIconKind
import com.screentrace.app.uikit.NavItem
import com.screentrace.app.uikit.PageScaffold
import com.screentrace.app.uikit.ScrollablePage
import com.screentrace.app.uikit.TabMotion
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun remainingPages(target: Int, pagerState: androidx.compose.foundation.pager.PagerState): Int {
    val here = pagerState.currentPage + pagerState.currentPageOffsetFraction
    return kotlin.math.abs(target - here).let { kotlin.math.ceil(it) }.toInt().coerceAtLeast(1)
}

@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)
@Composable
fun AppNav() {
    com.screentrace.app.uikit.PerfProbe.tick("Compose/AppNav")
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
                    // CR-21 ②③：切换设备 / 跨设备合并（两处 UI 入口共用同一份逻辑）
                    deviceOperations = container.deviceOperations,
                    syncCredentials = container.syncCredentials,
                    wallpaperStore = container.wallpaperStore,
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
    // §5.4 批 E：壁纸参数只有一份，位图由 ScreenTimeTheme 以 CompositionLocal 下发
    val wallpaperBitmap by viewModel.wallpaperBitmap
    val wallpaperConfig by viewModel.wallpaperConfig
    val wallpaperMessage by viewModel.wallpaperMessage
    // CR-24：模糊按「1px ≈ 1dp」降采样，需要屏幕 density
    val wallpaperDensity = LocalDensity.current.density
    val wallpaperState = remember(wallpaperBitmap, wallpaperConfig, darkTheme, wallpaperDensity) {
        wallpaperBitmap?.takeIf { wallpaperConfig.enabled }?.let { bmp ->
            // CR-24：全版本生效（原 Modifier.blur 依赖 RenderEffect、低版本静默不模糊）
            val blurDp = WallpaperTuning.effectiveBlurDp(wallpaperConfig.blurDp)
            WallpaperState(
                bitmap = bmp,
                dimAlpha = WallpaperTuning.dimAlpha(wallpaperConfig.dimPercent),
                // CR-19 ②：壁纸启用即卡片半透明，遮罩按 cardTranslucent 加深
                themeScrimAlpha = WallpaperTuning.themeScrimAlpha(
                    wallpaperConfig.brightness, darkTheme, cardTranslucent = true
                ),
                blurDp = blurDp,
                // CR-24：**一次性**算好模糊位图（只在壁纸/blurDp 变化时重算），
                // 绘制端只贴它、不再每帧跑 RenderEffect。
                blurred = WallpaperBlur.blurredImage(bmp, blurDp.toInt(), wallpaperDensity),
                cropScale = wallpaperConfig.cropScale,
                cropOffsetX = wallpaperConfig.cropOffsetX,
                cropOffsetY = wallpaperConfig.cropOffsetY,
                // CR-20 ②：壁纸主色（卡片配色派生）与配色范围两套方案
                seedColor = wallpaperConfig.seedColor,
                colorScope = wallpaperConfig.colorScope
            )
        }
    }
    val dynamicColor by viewModel.dynamicColor
    val page by viewModel.page
    // CR-15 B：今日页与统计页各自持有日期状态，互不影响
    val todaySelection by viewModel.todaySelection
    val statsSelection by viewModel.statsSelection
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
    val transientNotice by viewModel.transientNotice
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
    // CR-15 ③ 批 C：云同步页设备卡片
    val deviceNickname by viewModel.deviceNickname
    val deviceIdShort by viewModel.deviceIdShort
    // CR-21 ③：当前正在查看哪台设备（设备列表标「· 当前」）
    val currentDeviceId by viewModel.currentDeviceId
    val syncLastOk by viewModel.syncLastOk
    val syncLastMessage by viewModel.syncLastMessage

    // 云端恢复流在 CloudSyncScreen 二级页内（复用同一个 RestoreSheet）

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

    // CR-17：短时提示 2.5s 后自动消失；key 用 seq，连发同文案也重启计时
    LaunchedEffect(transientNotice?.seq) {
        if (transientNotice != null) {
            kotlinx.coroutines.delay(2_500L)
            viewModel.consumeTransient()
        }
    }

    var showDateSheet by rememberSaveable { mutableStateOf(false) }
    // CR-14：弹层选项集按打开时的入口页裁剪（存 enum name，进程重建也能恢复）。
    // 主页面顶栏打开时记当前主页面；二级页顶栏打开时强制回 FULL。
    var dateSheetMode by rememberSaveable { mutableStateOf(DateSheetMode.FULL.name) }
    var showStyleSheet by rememberSaveable { mutableStateOf(false) }
    // 自定义主题取色器不存实例状态：进程重建后滑杆位置无所谓，种子本身已落 DataStore
    var showCustomThemeSheet by remember { mutableStateOf(false) }
    // §5.2 CR-20 ①：设备列表面板是 AppNav 的局部状态，不写路由 / ViewModel / Pager。
    var showDevicePanel by rememberSaveable { mutableStateOf(false) }
    /** 主页面 AppHeader 底边的窗口 y，用来把面板贴在顶栏正下方。 */
    var headerBottomPx by remember { mutableStateOf(0f) }
    // §5.4 CR-19 ②：壁纸裁切编辑浮层（从设置页壁纸卡片进入）
    var showWallpaperEditor by rememberSaveable { mutableStateOf(false) }

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
        customSeed = customThemeSeed?.let { androidx.compose.ui.graphics.Color(it) },
        wallpaper = wallpaperState
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
        // CR-15 B：当前页（含二级页 returnTo）归属哪份日期状态；日历 tab = null（无日期按钮）。
        // active 供弹层/报告按当前页取值，header 供主页面层副标题按主页面取值。
        val activeSelection =
            if (dateScopeFor(page) == DateScope.STATS) statsSelection else todaySelection
        val headerSelection =
            if (dateScopeFor(mainPage) == DateScope.STATS) statsSelection else todaySelection

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
        // CR-17 ②：连续页位置（currentPage + 手指偏移）作为 State 传给导航条。
        // 只在 FreshNavBar 内部读取 .value，避免整棵 AppNav 每帧重组。
        val navProgress = remember(pagerState) {
            derivedStateOf { pagerState.currentPage + pagerState.currentPageOffsetFraction }
        }
        val tabScope = rememberCoroutineScope()
        // CR-22 ①：程序化滚动守卫（点 tab 触发的滚动期间不接受 Pager 的中间页回写）
        val tabGuard = remember { TabScrollGuard() }

        // CR-22 ①：按 **Pager 的实际停留页**对齐路由。
        // 写的是 Pager 此刻的位置（不是「用户点的页」），所以 Pager 仍是唯一真相源，
        // 点 tab 不直接写路由的红线不变。这里必须读 `viewModel.page.value`：
        // 局部函数捕获的是组合时的值，早 Started 的协程里那个值已经过时。
        fun alignRouteToPager(landed: Int) {
            com.screentrace.app.uikit.PerfProbe.event("Route/align=" + landed)
            val page = MAIN_PAGES.getOrNull(landed) ?: return
            if (AppPage.mainOf(viewModel.page.value) != page) viewModel.selectMain(page)
        }

        // ① 路由 → Pager：只在「路由变化不是 Pager 回写引起」时才滚（二级页返回、
        //    goBack 回到另一主页面）。CR-17 起点 tab 直接在这里之外发滚动、不写路由，
        //    所以由 currentPage 回写触发的 mainPage 变化满足 currentPage == target，
        //    不会再反过来滚一次——双向环路就此断开。
        //    `animateScrollToPage` 的默认动画是 spring（StiffnessMediumLow）。
        LaunchedEffect(mainPage) {
            // 切主页面时收起打开中的弹层（真机反馈 #7）：弹层属于某一个页面的上下文，
            // 页面都换了还挂着一张旧弹层只会让人困惑。返回二级页不受影响（mainPage 未变）。
            showDateSheet = false
            showStyleSheet = false
            // CR-20 ①：切 tab 时设备面板属于旧页面上下文，必须先收起。
            showDevicePanel = false
            val target = MAIN_PAGES.indexOf(mainPage)
            if (target !in MAIN_PAGES.indices) return@LaunchedEffect
            if (pagerState.currentPage != target) {
                // CR-22 ①：这也是一次程序化滚动（二级页返回时的回滚），中途的中间页
                // 同样不能回写路由，否则标题会闪一下中间页。
                val seq = com.screentrace.app.uikit.PerfProbe.nextSeq()
                com.screentrace.app.uikit.PerfProbe.a5("routeScroll", target, seq)
                val guardToken = tabGuard.begin(target)
                try {
                    com.screentrace.app.uikit.PerfProbe.event("Pager/scrollStart")
                    com.screentrace.app.uikit.PerfProbe.a5("scrollStart", target, seq)
                    if (reduceMotion) pagerState.scrollToPage(target)
                    else pagerState.animateScrollToPage(target, animationSpec = TabMotion.scrollSpecFor(TabMotion.remainingPages(target, pagerState.currentPage, pagerState.currentPageOffsetFraction)))
                    com.screentrace.app.uikit.PerfProbe.event("Pager/scrollEnd")
                    com.screentrace.app.uikit.PerfProbe.a5("scrollEnd", target, seq)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    com.screentrace.app.uikit.PerfProbe.a5("scrollCancel", target, seq)
                    throw e
                } finally {
                    val aligned = tabGuard.end(guardToken)
                    com.screentrace.app.uikit.PerfProbe.a5Finally(target, seq, aligned)
                    if (aligned) alignRouteToPager(pagerState.currentPage)
                }
            }
        }
        // ② Pager → 路由（单向）：`currentPage` 过半即翻，比 `settledPage`（落定才发值）
        //    早得多，横滑过程中 tab 就会跟着切；配合 navProgress 的连续插值就是「跟手」。
        //    点 tab 不再写路由，因此不存在「滞后 settle 回写覆盖用户刚点的新选择」。
        //    这里读 `viewModel.page.value` 而不是组合里的 `page`：effect 只起一次，
        //    捕获的 `page` 会一直停留在首次组合时的值，判断就会失效。
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect { index ->
                com.screentrace.app.uikit.PerfProbe.event("Route/currentPage=" + index)
                val target = MAIN_PAGES.getOrNull(index) ?: return@collect
                // CR-22 ①：程序化滚动期间**只忽略中间页**的回写（目标页照常回写，
                // 动画走到七成时切标题最自然）——否则跨两页时路由会经过中间页，
                // 标题与胶囊先跳到中间页再跳回（观感＝卡一下）。
                if (tabGuard.ignoresWrite(index)) {
                    com.screentrace.app.uikit.PerfProbe.event("Route/guardIgnore=" + index)
                    return@collect
                }
                if (AppPage.mainOf(viewModel.page.value) != target) {
                    com.screentrace.app.uikit.PerfProbe.event("Route/selectMain=" + target)
                    viewModel.selectMain(target)
                }
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
                // §5.4 批 E：启用壁纸时按「遮罩后实际背景亮度」判图标明暗；
                // 无壁纸时维持原口径（跟用户选的深浅色，不跟系统）。
                val lightIcons = if (wallpaperState != null) {
                    WallpaperTuning.lightStatusIcons(
                        WallpaperTuning.effectiveBrightness(
                            wallpaperConfig.brightness,
                            WallpaperTuning.dimAlpha(wallpaperConfig.dimPercent),
                            WallpaperTuning.themeScrimAlpha(wallpaperConfig.brightness, darkTheme, cardTranslucent = true),
                            darkTheme
                        )
                    )
                } else !darkTheme
                controller.isAppearanceLightStatusBars = lightIcons
                controller.isAppearanceLightNavigationBars = lightIcons
            }
        }

        // 系统返回键 / 全面屏返回手势：二级页必须回到上一层，而不是直接退到桌面。
        // 主页面（今日/统计/日历）不拦截，保持系统默认的「退出应用」。
        BackHandler(enabled = !isRootPage(page)) { viewModel.goBack() }
        // CR-20 ①：设备面板打开时系统返回先收面板，不直接退到桌面。
        BackHandler(enabled = showDevicePanel) { showDevicePanel = false }
        BackHandler(enabled = showWallpaperEditor) { showWallpaperEditor = false }

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
                        // 批 D：拿到 header 底边（窗口坐标），面板据此贴在设置按钮下方；
                        // 首帧未布局时为 0，面板会等这个值就绪再画，不会闪到屏幕顶。
                        modifier = Modifier.onGloballyPositioned {
                            headerBottomPx = it.boundsInWindow().bottom
                        },
                        // 今日页（CR-09 验收 40）：单日筛选时副标题跟随该单日（如「昨天 · 11月12日」），
                        // 区间筛选时首页回落到今天。
                        subtitle = if (mainPage == AppPage.Calendar) {
                            // CR-15 评审 P2-3：日历页不参与日期筛选，加载中 / 无数据时
                            // 不回落成「今天」（那是今日状态，与该页无关）。
                            calendarSubtitle(calendarState.data?.yearLevels?.count { lv -> lv.recorded })
                        } else {
                            selectionSubtitle(
                                if (mainPage == AppPage.Today && headerSelection is DateSelection.Range) {
                                    DateSelection.today()
                                } else {
                                    headerSelection
                                }
                            )
                        },
                        // CR-23 ② 标题跟随运动：位移按「程序化滚动的目标页 − 连续进度」算，
                        // 落定自然归 0，于是标题不再「内容到位后才翻」。只在**程序化滚动**
                        // （点 tab / 二级页返回的回滚）期间生效——横滑时路由是过半即翻的，
                        // 套同一条式子会让位移在翻页瞬间跳变（§5.2 CR-23 ②）。
                        // 「目标页」取 `tabGuard.target`（点 tab 当下就知道）而不是路由当前值：
                        // 路由要到七成才翻（CR-22），用它会算出反方向的位移。
                        // lambda 在绘制期求值、读 `navProgress.value`，不引起整棵 AppNav 重组。
                        tabProgress = navProgress,
                        titleShiftDp = { progress ->
                            if (reduceMotion || !tabGuard.programmatic) {
                                0f
                            } else {
                                TabMotion.titleShiftDp(
                                    target = tabGuard.target.toFloat(),
                                    progress = progress
                                )
                            }
                        },
                        // §5.2 CR-20 ①：四枚动作**拆回顶栏直出**，各接一个真实动作。
                        // 「选择日期」按 dateScopeFor 判据给（CR-15 B：日历 tab 不参与日期筛选，
                        // 就不给这枚按钮），与 headerActionsFor 同源。
                        onDevicesClick = { showDevicePanel = !showDevicePanel },
                        onDateClick = if (dateScopeFor(mainPage) != null) {
                            {
                                dateSheetMode = dateSheetModeFor(mainPage).name
                                showDateSheet = true
                            }
                        } else {
                            null
                        },
                        onStyleClick = { showStyleSheet = true },
                        // 设置页走容器变换：这枚按钮自身的 transformSource 就是起点（§5.5）
                        onSettingsClick = { viewModel.openSettings() }
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
                        ScrollablePage(
                            // CR-23 ① 页面景深：按偏移给整页内容轻微缩放 + 淡化，
                            // 居中页完全清晰、滑出页渐暗渐小——横滑与点 tab 一视同仁，
                            // 消除「一整块刚性平面」的机械感。
                            // 红线例外（需求 CR-23 登记）：`graphicsLayer` 是绘制期变换，
                            // 不重建子树、不重播 `remember { Animatable(0f) }`，不触发
                            // CR-15 A1-④ / CR-17 ③ 的「闪一帧」立论。
                            // 进度在绘制期读取，不引起整棵 AppNav 每帧重组。
                            modifier = Modifier.graphicsLayer {
                                val offset =
                                    pagerState.currentPage + pagerState.currentPageOffsetFraction - index
                                alpha = TabMotion.alphaFor(offset)
                                val s = TabMotion.scaleFor(offset)
                                scaleX = s
                                scaleY = s
                            }
                        ) {
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
                                    // 统计页只认自己的状态；分段控件写回也只改它
                                    selection = statsSelection,
                                    onApplySelection = { viewModel.applySelection(it) },
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
                                    // CR-07 §4.8：月历四档按「使用/目标」比值，读用户实际每日目标
                                    goalMinutes = dailyGoalMin,
                                    lowThresholdMin = lowThresholdMin,
                                    // §5.11：日历页顶部同一入口
                                    onOpenPeriod = viewModel::openPeriod
                                )
                            }
                        }
                    }
                }
                        com.screentrace.app.uikit.PerfProbe.tick("AppNav/PullToRefreshCallsite") // R2 更名：AppNav 内调用点，非独立组件探针
                        PullToRefreshContainer(
                            state = pullState,
                            // M3 1.2 默认指示器空闲态不归零：真机上标题旁常驻一个无图标
                            // 的淡圆（ProgressBar 语义节点）。自定义 indicator 只在真正
                            // 刷新时画转圈，空闲/跟手阶段不画。
                            indicator = {
                                if (pullState.isRefreshing) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .size(28.dp),
                                        color = androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.contentColor,
                                        strokeWidth = 3.dp
                                    )
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                // 空圆根因（二分法定位）：container 本体空闲时在标题旁
                                // 留一个淡色圆底（M3 1.2 观感问题，uiautomator 之外的纯绘制层）。
                                // 整体 alpha 门控：刷新中显示，其余（空闲/下拉跟手）隐藏；
                                // 手势仍由 nestedScroll 接管，不影响下拉触发。
                                .graphicsLayer {
                                    alpha = if (pullState.isRefreshing) 1f else 0f
                                }
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
                                    // CR-16：弹层选项跟随 returnTo（今日→DAY / 统计→RANGE），
                                    // 与 applySelection 的写回判据同源
                                    onDateClick = {
                                        dateSheetMode = dateSheetModeFor(detail).name
                                        showDateSheet = true
                                    }
                                )
                                is AppPage.Settings -> AppHeader(
                                    title = "设置",
                                    subtitle = null,
                                    onBack = viewModel::goBack
                                )
                                is AppPage.CloudSync -> AppHeader(
                                    title = "云端同步",
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
                                    onDateClick = {
                                        dateSheetMode = dateSheetModeFor(detail).name
                                        showDateSheet = true
                                    }
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
                                    onDateClick = {
                                        dateSheetMode = dateSheetModeFor(detail).name
                                        showDateSheet = true
                                    }
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
                                    wallpaperBitmap = wallpaperBitmap,
                                    wallpaperConfig = wallpaperConfig,
                                    wallpaperMessage = wallpaperMessage,
                                    onOpenWallpaperEditor = { showWallpaperEditor = true },
                                    blurSupported = WallpaperTuning.blurSupported(),
                                    onWallpaperPicked = viewModel::onWallpaperPicked,
                                    onWallpaperDimChange = { v -> viewModel.setWallpaperDim(v) },
                                    onWallpaperBlurChange = { v -> viewModel.setWallpaperBlur(v) },
                                    onWallpaperColorScopeChange = { s -> viewModel.setWallpaperColorScope(s) },
                                    onClearWallpaper = viewModel::clearWallpaper,
                                    onConsumeWallpaperMessage = viewModel::consumeWallpaperMessage,
                                    lowThresholdMin = lowThresholdMin,
                                    onLowThresholdChange = viewModel::setLowThresholdMin,
                                    appVersion = appVersion,
                                    buildReport = { hideNames ->
                                        viewModel.buildHtmlReport(hideNames, appVersion)
                                    },
                                    onBuildBackupJson = { viewModel.buildBackupJson(appVersion) },
                                    onBackupCompatWarning = { viewModel.backupCompatWarning(appVersion) },
                                    onBuildCsv = { viewModel.buildCsvFiles(appVersion) },
                                    onDecodeBackup = { text -> viewModel.decodeBackup(text) },
                                    onRestoreBackup = { file, overwrite, importSettings ->
                                        viewModel.restoreBackup(file, overwrite, importSettings)
                                    },
                                    cloudSummary = if (syncLastAt != null) {
                                        "上次同步：" + syncLastMessage.ifEmpty { if (syncLastOk == true) "成功" else "失败" }
                                    } else {
                                        "未配置"
                                    },
                                    onOpenCloudSync = viewModel::openCloudSync,
                                    onNotice = viewModel::showNotice
                                )
                                is AppPage.CloudSync -> CloudSyncScreen(
                                    webdavUrl = webdavUrl,
                                    webdavUsername = webdavUsername,
                                    webdavRemoteDir = webdavRemoteDir,
                                    passwordSaved = webdavPasswordSaved,
                                    syncFrequency = syncFrequency,
                                    syncWifiOnly = syncWifiOnly,
                                    lastAt = syncLastAt,
                                    lastOk = syncLastOk,
                                    lastMessage = syncLastMessage,
                                    appVersion = appVersion,
                                    onConfigChange = viewModel::setWebdavConfig,
                                    onPasswordSet = viewModel::setWebdavPassword,
                                    onFrequencyChange = viewModel::setSyncFrequency,
                                    onWifiOnlyChange = viewModel::setSyncWifiOnly,
                                    onTestConnection = { viewModel.testCloudConnection() },
                                    onUpload = { viewModel.uploadToCloud() },
                                    onDownload = { viewModel.downloadFromCloud() },
                                    onRestore = { file, overwrite, importSettings ->
                                        viewModel.restoreBackup(file, overwrite, importSettings)
                                    },
                                    deviceNickname = deviceNickname,
                                    deviceIdShort = deviceIdShort,
                                    onDeviceNicknameChange = { name -> viewModel.setDeviceNickname(name) },
                                    onListDevices = { viewModel.listCloudDevices() },
                                    onInspectDevice = { id -> viewModel.inspectCloudDevice(id) },
                                    // CR-21 ②③：切换设备 / 合并到本机
                                    currentDeviceId = currentDeviceId,
                                    onSwitchDevice = { id -> viewModel.switchToDevice(id) },
                                    onMergeDevice = { id -> viewModel.mergeDeviceIntoLocal(id) },
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

            // ---- 全局提示条：与底部导航同层（最上层）——真机反馈 #1：原来画在主内容
            // 列里，被二级页覆盖层整块遮住，用户看不到任何成功/失败反馈。 ----
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 20.dp)
                    .padding(top = 108.dp)
            ) {
                androidx.compose.foundation.layout.Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // CR-17：纯确认类短时条（与常驻条同款样式，2.5s 自动消失，可提前关）
                    AnimatedVisibility(
                        visible = transientNotice != null,
                        enter = expandVertically(tween(140)) + fadeIn(tween(140)),
                        exit = shrinkVertically(tween(140)) + fadeOut(tween(100))
                    ) {
                        transientNotice?.let { n ->
                            NoticeBar(text = n.text, onDismiss = viewModel::consumeTransient, isError = false)
                        }
                    }
                    AnimatedVisibility(
                        visible = notice != null,
                        enter = expandVertically(tween(140)) + fadeIn(tween(140)),
                        exit = shrinkVertically(tween(140)) + fadeOut(tween(100))
                    ) {
                        notice?.let { text ->
                            // 操作反馈（成功/提示）用中性样式；错误红色留给 pullError
                            NoticeBar(text = text, onDismiss = viewModel::consumeNotice, isError = false)
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
                        NavItem("今日", NavIconKind.TODAY),
                        NavItem("统计", NavIconKind.STATS),
                        NavItem("日历", NavIconKind.CALENDAR)
                    ),
                    selectedIndex = MAIN_PAGES.indexOf(mainPage).coerceAtLeast(0),
                    onSelect = { index ->
                        // CR-17 ②：点 tab 只滚 Pager，不直接写路由；route 由 ② 单向回写
                        // CR-22 ①：标记为程序化滚动——滚动途中 Pager 的回写一律忽略，
                        // 结束后按 Pager 的停留页对齐一次（跨页时不经过中间页）。
                        val seq = com.screentrace.app.uikit.PerfProbe.nextSeq()
                        com.screentrace.app.uikit.PerfProbe.a5("tabClick", index, seq)
                        val guardToken = tabGuard.begin(index)
                        tabScope.launch {
                            try {
                                com.screentrace.app.uikit.PerfProbe.event("Pager/scrollStart")
                                com.screentrace.app.uikit.PerfProbe.a5("scrollStart", index, seq)
                                if (reduceMotion) pagerState.scrollToPage(index)
                                else pagerState.animateScrollToPage(index, animationSpec = TabMotion.scrollSpecFor(TabMotion.remainingPages(index, pagerState.currentPage, pagerState.currentPageOffsetFraction)))
                                com.screentrace.app.uikit.PerfProbe.event("Pager/scrollEnd")
                                com.screentrace.app.uikit.PerfProbe.a5("scrollEnd", index, seq)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                com.screentrace.app.uikit.PerfProbe.a5("scrollCancel", index, seq)
                                throw e
                            } finally {
                                val aligned = tabGuard.end(guardToken)
                                com.screentrace.app.uikit.PerfProbe.a5Finally(index, seq, aligned)
                                if (aligned) alignRouteToPager(pagerState.currentPage)
                            }
                        }
                    },
                    progress = navProgress
                )
            }

            // ---- §5.2 CR-20 ①：设备列表面板浮层（最上层，盖住导航胶囊与提示条）----
            DevicePanelOverlay(
                visible = showDevicePanel,
                headerBottomPx = headerBottomPx,
                reduceMotion = reduceMotion,
                onDismiss = { showDevicePanel = false },
                onListDevices = { viewModel.listCloudDevices() },
                onInspectDevice = { id -> viewModel.inspectCloudDevice(id) },
                // CR-21 ②③：切换设备 / 合并到本机（与云同步页同一套逻辑）
                currentDeviceId = currentDeviceId,
                onSwitchDevice = { id -> viewModel.switchToDevice(id) },
                onMergeDevice = { id -> viewModel.mergeDeviceIntoLocal(id) }
            )

            // ---- §5.4 CR-19 ②：壁纸裁切编辑（全屏，盖住一切含下拉面板）----
            if (showWallpaperEditor && wallpaperState != null) {
                WallpaperEditorScreen(
                    bitmap = wallpaperState.bitmap,
                    initialScale = wallpaperConfig.cropScale,
                    initialOffsetX = wallpaperConfig.cropOffsetX,
                    initialOffsetY = wallpaperConfig.cropOffsetY,
                    onApply = { zoom, tx, ty ->
                        tabScope.launch { viewModel.setWallpaperCrop(zoom, tx, ty) }
                        showWallpaperEditor = false
                    },
                    onReset = {
                        tabScope.launch { viewModel.resetWallpaperCrop() }
                        showWallpaperEditor = false
                    },
                    onDismiss = { showWallpaperEditor = false }
                )
            }
        }

        if (showDateSheet) {
            DateSheet(
                // 二级页弹层显示的是「进入它的主页面」那份状态，applySelection 同口径写回；
                // CR-16 起 mode 也由同一条 returnTo 判据得出（今日→DAY / 统计→RANGE），
                // 弹层可以给出的选项与写回目标永远同量纲。
                current = activeSelection,
                mode = DateSheetMode.valueOf(dateSheetMode),
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
                customSeedActive = customThemeSeed != null,
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
    is AppPage.CloudSync -> page
    else -> null
}

/**
 * 日历 tab 专属副标题：CR-15 评审 P2-3。`null` = 加载中 / 无数据，用「全量历史」
 * 占位而不是回落到 `selectionSubtitle(todaySelection)`（那样会在一个不参与筛选的
 * 页面上显示「今天 · X月X日」）。
 */
internal fun calendarSubtitle(recordedDays: Int?): String =
    recordedDays?.let { "已记录 $it 天" } ?: "全量历史"

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
    /** 操作反馈为中性样式，只有错误用红色（真机反馈：成功提示红得像报错）。 */
    isError: Boolean = true,
    onDismiss: () -> Unit,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    val palette = LocalFreshPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            // CR-20 ③：提示条叠在内容之上，底色保持不透明
            .background(palette.panelSurface)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = FreshType.label,
            // 真机反馈：操作反馈（成功/提示）不应用错误红——红色只留给真错误
            color = if (isError) palette.error else palette.onSurface,
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
            // CR-20 ③：提示条叠在内容之上，底色保持不透明
            .background(palette.panelSurface)
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

/**
 * §5.2 CR-20 ①：顶栏「切换设备」点开的**设备列表面板浮层**。
 *
 * 作为最上层浮层渲染：全屏透明点击层负责「点外部收起」，面板右对齐贴在
 * [headerBottomPx]（AppHeader 底边窗口坐标）下方 4dp，不推挤任何内容。
 *
 * **不下发 `LocalContainerTransformState`**：批 D 时期面板内按钮要登记容器变换起点，
 * CR-20 把动作拆回顶栏后不再需要——面板里的控件不得被记成转场起点。
 *
 * `headerBottomPx == 0f`（首帧尚未布局）时不画面板，避免闪到屏幕顶部。
 */
@Composable
private fun DevicePanelOverlay(
    visible: Boolean,
    headerBottomPx: Float,
    reduceMotion: Boolean,
    onDismiss: () -> Unit,
    onListDevices: suspend () -> SyncManager.DevicesResult,
    onInspectDevice: suspend (String) -> SyncManager.DownloadedBackup,
    // CR-21 ②③
    currentDeviceId: String,
    onSwitchDevice: suspend (String) -> String,
    onMergeDevice: suspend (String) -> String
) {
    com.screentrace.app.uikit.PerfProbe.tick("Compose/DPO")
    Box(Modifier.fillMaxSize()) {
        // 点外部收起：不加 ripple，避免整屏出现按压水波；透明层无动画，随 visible 立即出现/消失
        if (visible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onDismiss
                    )
            )
        }
        // CR-19 ①：面板纵向展开 + 淡入（负责人反馈纯淡入太快「看不到过渡」）。
        // 展开动画只作用在面板自身；收起动画期间点击层已移除，不再拦截点击。
        AnimatedVisibility(
            visible = visible && headerBottomPx > 0f,
            enter = if (reduceMotion) EnterTransition.None
            else expandVertically(tween(200), expandFrom = Alignment.Top) + fadeIn(tween(200)),
            exit = if (reduceMotion) ExitTransition.None
            else shrinkVertically(tween(160), shrinkTowards = Alignment.Top) + fadeOut(tween(120)),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(0, (headerBottomPx - 4.dp.toPx()).toInt()) }
                    .padding(horizontal = 20.dp)
            ) {
                DevicePanel(
                    onClose = onDismiss,
                    onListDevices = onListDevices,
                    onInspectDevice = onInspectDevice,
                    currentDeviceId = currentDeviceId,
                    onSwitchDevice = onSwitchDevice,
                    onMergeDevice = onMergeDevice
                )
            }
        }
    }
}
