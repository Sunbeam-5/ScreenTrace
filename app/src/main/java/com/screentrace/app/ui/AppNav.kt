package com.screentrace.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
    val monthLevelsState by viewModel.monthLevelsState
    val calendarMonth by viewModel.calendarMonth
    val dailyGoalMin by viewModel.dailyGoalMin
    val notice by viewModel.notice
    val pullError by viewModel.pullError
    val pulling by viewModel.pulling

    var showDateSheet by rememberSaveable { mutableStateOf(false) }
    var showStyleSheet by rememberSaveable { mutableStateOf(false) }

    ScreenTimeTheme(variant = variant, darkTheme = darkTheme, dynamicColor = dynamicColor) {
        ScreenScaffold(
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
                        NavItem("统计", Icons.Default.List),
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
            notice?.let { text ->
                NoticeBar(text = text, onDismiss = viewModel::consumeNotice)
            }
            // §5.8：已有历史时拉取失败 → 顶部内联错误 + 可重试，页面继续显示旧数据
            pullError?.let { text ->
                NoticeBar(
                    text = text,
                    onDismiss = viewModel::dismissPullError,
                    actionText = "重试",
                    onAction = viewModel::retryPull
                )
            }
            if (pulling) {
                PullingBar()
            }
            when (page) {
                AppPage.Today -> TodayScreen(
                    state = todayState,
                    dailyGoalMin = dailyGoalMin,
                    onOpenRanking = viewModel::openRanking,
                    onRetry = viewModel::reload
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
                    onRetry = viewModel::reload
                )
                is AppPage.Ranking -> RankingScreen(
                    state = rankingState,
                    onRetry = viewModel::reload
                )
                is AppPage.Settings -> SettingsScreen(
                    darkTheme = darkTheme,
                    dynamicColor = dynamicColor,
                    onDarkThemeChange = viewModel::setDarkTheme,
                    onDynamicColorChange = viewModel::setDynamicColor
                )
                is AppPage.UnlockDetail -> UnlockDetailScreen()
                is AppPage.NotificationDetail -> NotificationDetailScreen()
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
