package com.screentrace.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.EmptyUsageRepository
import com.screentrace.app.ScreenTraceApp
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.ScreenTimeTheme
import com.screentrace.app.uikit.AppHeader
import com.screentrace.app.uikit.FreshNavBar
import com.screentrace.app.uikit.NavItem
import com.screentrace.app.uikit.ScreenScaffold
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")

/**
 * 应用根组合（批次 3）：主题包裹 + 页面路由 + 底部胶囊导航 + 日期/风格弹层。
 * 二级页（排行/设置）用「返回 + 标题」顶部栏，底部导航保持进入前主页面选中（§5.2/§5.5）。
 * 仓库注入点（批 6b）：由 [ScreenTraceApp] 的容器提供真实仓库与拉取协调器；
 * 容器不可用时（预览/纯 UI 场景）退回 [EmptyUsageRepository]，不崩也不造假数据。
 */
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

    // §5.7：进详情页前记住今日页滚动位置，返回时滚回去（页面变短会把 value 夹小，
    // 所以必须**离开前**存快照，回来再 scrollTo，而不是指望 ScrollState 自己记住）
    val contentScroll = androidx.compose.foundation.rememberScrollState()
    var savedTodayOffset by rememberSaveable { mutableStateOf(0) }

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
        // 之前只有顶部栏那个「返回」按钮接了 goBack，系统返回没人拦 → Activity 直接
        // finish()，用户看到的就是「在设置里一滑返回就退出 App」。
        // 主页面（今日/统计/日历）不拦截，保持系统默认的「退出应用」。
        BackHandler(enabled = !isRootPage(page)) { viewModel.goBack() }

        androidx.compose.runtime.LaunchedEffect(page) {
            if (page == AppPage.Today) contentScroll.scrollTo(savedTodayOffset)
        }

        ScreenScaffold(
            scrollState = contentScroll,
            header = {
                when (page) {
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
                    is AppPage.NotificationDetail -> AppHeader(
                        title = "通知详情",
                        subtitle = null,
                        onBack = viewModel::goBack,
                        onDateClick = { showDateSheet = true }
                    )
                    else -> AppHeader(
                        title = when (AppPage.mainOf(page)) {
                            AppPage.Stats -> "统计"
                            AppPage.Calendar -> "日历"
                            else -> "今日"
                        },
                        subtitle = selectionSubtitle(selection),
                        onDateClick = { showDateSheet = true },
                        onStyleClick = { showStyleSheet = true },
                        onSettingsClick = viewModel::openSettings
                    )
                }
            },
            floatingNav = {
                FreshNavBar(
                    items = listOf(
                        NavItem("今日", Icons.Default.Home),
                        NavItem("统计", Icons.AutoMirrored.Filled.List),
                        NavItem("日历", Icons.Default.DateRange)
                    ),
                    selectedIndex = when (AppPage.mainOf(page)) {
                        AppPage.Stats -> 1
                        AppPage.Calendar -> 2
                        else -> 0
                    },
                    onSelect = { index ->
                        viewModel.selectMain(
                            when (index) {
                                1 -> AppPage.Stats
                                2 -> AppPage.Calendar
                                else -> AppPage.Today
                            }
                        )
                    }
                )
            }
        ) {
            FilterChipBar(
                selection = selection,
                recordedDays = todayState.data?.summary?.recordedDays,
                onClear = viewModel::clearFilter
            )
            // 提示条必须走 AnimatedVisibility：直接 `notice?.let {}` 会让这一行
            // 瞬间消失，下面整页内容「啪」地往上跳一格——用户点 x 看到的就是
            // 「页面刷新闪了一下」。展开/收起都是 140ms 的高度动画，视觉上连续。
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
            /**
             * 页面切换过渡：整块内容淡入 + 上浮 12dp。
             *
             * 两个必须遵守的约束，都是踩坑换来的：
             *
             * 1. **必须用 `Column` 包，不能用 `Box`**。各页面的 Composable 往往有多个根节点
             *    （`TodayContent` 就是「圆环卡 / 双卡 / 应用构成卡」三个并列），
             *    `ContentColumn` 是 `ColumnScope`，所以它们原本是纵向排列的。
             *    一旦包进 `Box`，三个根节点就变成 Box 的兄弟 → **全部叠在左上角**，
             *    真机表现是「只看得见最后绘制的应用构成卡，圆环和双卡被盖住」
             *    （UI 树里三者 bounds 互相覆盖，就是这原因）。`AnimatedContent` 同理。
             * 2. 外层 `ContentColumn` 的 `spacedBy(12.dp)` 现在只作用于这一个 Column，
             *    所以卡间距要在这里补上同样的 12dp，否则卡片会贴在一起。
             */
            val contentAlpha = remember { Animatable(1f) }
            val reduceMotion = com.screentrace.app.uikit.rememberReduceMotion()
            LaunchedEffect(page) {
                if (reduceMotion) {
                    contentAlpha.snapTo(1f)
                    return@LaunchedEffect
                }
                contentAlpha.snapTo(0f)
                contentAlpha.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
            }
            Column(
                modifier = Modifier.graphicsLayer {
                    // graphicsLayer 只改绘制、不改布局，纵向排列完全不受影响
                    alpha = contentAlpha.value
                    translationY = (1f - contentAlpha.value) * 12.dp.toPx()
                },
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (page) {
                    AppPage.Today -> TodayScreen(
                        state = todayState,
                        dailyGoalMin = dailyGoalMin,
                        // 首次 dump 要十几秒：这期间显示「正在读取」，而不是「尚无使用记录」
                        pulling = viewModel.pulling.value,
                        onOpenRanking = viewModel::openRanking,
                        onRetry = viewModel::reload,
                        onOpenUnlock = {
                            savedTodayOffset = contentScroll.value
                            viewModel.openUnlockDetail()
                        },
                        onOpenNotification = {
                            savedTodayOffset = contentScroll.value
                            viewModel.openNotificationDetail()
                        }
                    )
                    AppPage.Stats -> StatsScreen(
                        state = statsState,
                        selection = selection,
                        onApplySelection = viewModel::applySelection,
                        onRetry = viewModel::reload
                    )
                    AppPage.Calendar -> CalendarScreen(
                        state = calendarState,
                        monthLevelsState = monthLevelsState,
                        calendarMonth = calendarMonth,
                        onMonthChange = viewModel::setCalendarMonth,
                        onRetry = viewModel::reload,
                        lowThresholdMin = lowThresholdMin
                    )
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
                    is AppPage.NotificationDetail -> NotificationDetailScreen(
                        state = notificationDetail,
                        onRetry = viewModel::reload
                    )
                }
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
