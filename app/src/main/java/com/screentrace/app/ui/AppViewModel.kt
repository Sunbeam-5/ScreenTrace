package com.screentrace.app.ui

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.screentrace.app.contract.DateSelection
import com.screentrace.app.contract.EmptyUsageRepository
import com.screentrace.app.contract.Summary
import com.screentrace.app.contract.UsageRepository
import com.screentrace.app.theme.ThemeVariant
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 页面路由（不引入 navigation 库——§6 依赖白名单不含它，单层二级页用状态即可）。 */
sealed interface AppPage {
    object Today : AppPage
    object Stats : AppPage
    object Calendar : AppPage

    /** 二级页：返回 + 标题（§5.2）；returnTo = 进入前所在的主页面。 */
    data class Ranking(val returnTo: AppPage = Today) : AppPage
    data class Settings(val returnTo: AppPage = Today) : AppPage

    companion object {
        /** 底部导航选中态映射：二级页保持进入前主页面的选中（§5.5）。 */
        fun mainOf(page: AppPage): AppPage = when (page) {
            is Ranking -> page.returnTo
            is Settings -> page.returnTo
            else -> page
        }
    }
}

/** 页面数据态：加载 / 错误 / 摘要（含覆盖天数，§6）。批 4 起按页扩展具体数据。 */
data class PageUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val summary: Summary? = null
)

/**
 * §6 单向数据流：Repository -> ViewModel -> UiState -> Compose。
 * 四页共享同一 DateSelection（§5.6）：冷启动今天、切页/进排行/旋转保持、
 * 清除筛选恢复今天；「完全无记录」不应用新筛选、保留原视图并提示。
 * 主题状态先内存承载（旋转保持），DataStore 持久化在批 6a 接入。
 */
class AppViewModel(
    private val repository: UsageRepository = EmptyUsageRepository()
) : ViewModel() {

    private val _selection = mutableStateOf<DateSelection>(DateSelection.today())
    val selection: State<DateSelection> = _selection

    private val _page = mutableStateOf<AppPage>(AppPage.Today)
    val page: State<AppPage> = _page

    private val _summaryState = mutableStateOf(PageUiState(loading = true))
    val summaryState: State<PageUiState> = _summaryState

    /** 「该时段尚无数据，请先拉取」提示（§5.6），内联展示，不用 Toast。 */
    private val _notice = mutableStateOf<String?>(null)
    val notice: State<String?> = _notice

    private val _themeVariant = mutableStateOf(ThemeVariant.MINT)
    val themeVariant: State<ThemeVariant> = _themeVariant

    private val _darkTheme = mutableStateOf(false)
    val darkTheme: State<Boolean> = _darkTheme

    private val _dynamicColor = mutableStateOf(false)
    val dynamicColor: State<Boolean> = _dynamicColor

    private var summaryJob: Job? = null

    init {
        loadSummary()
    }

    fun selectMain(page: AppPage) {
        _page.value = page
    }

    fun openRanking() {
        _page.value = AppPage.Ranking(returnTo = AppPage.mainOf(_page.value))
    }

    fun openSettings() {
        _page.value = AppPage.Settings(returnTo = AppPage.mainOf(_page.value))
    }

    fun goBack() {
        val current = _page.value
        _page.value = when (current) {
            is AppPage.Ranking -> current.returnTo
            is AppPage.Settings -> current.returnTo
            else -> current
        }
    }

    /**
     * §5.6：单日/区间完全无记录时**不应用**新筛选，保留原视图并提示；
     * 部分有记录时正常应用。清除筛选（[clearFilter]）恢复今天，不做该拦截。
     */
    fun applySelection(newSelection: DateSelection) {
        summaryJob?.cancel()
        summaryJob = viewModelScope.launch {
            val summary = repository.summary(newSelection)
            if (!summary.hasAnyRecord) {
                _notice.value = "该时段尚无数据，请先拉取"
            } else {
                _selection.value = newSelection
                _notice.value = null
                _summaryState.value = PageUiState(summary = summary)
            }
        }
    }

    fun clearFilter() {
        val today = DateSelection.today()
        _selection.value = today
        _notice.value = null
        loadSummary()
    }

    fun consumeNotice() {
        _notice.value = null
    }

    fun setThemeVariant(variant: ThemeVariant) {
        _themeVariant.value = variant
    }

    fun setDarkTheme(dark: Boolean) {
        _darkTheme.value = dark
    }

    fun setDynamicColor(enabled: Boolean) {
        _dynamicColor.value = enabled
    }

    private fun loadSummary() {
        summaryJob?.cancel()
        summaryJob = viewModelScope.launch {
            _summaryState.value = PageUiState(loading = true)
            _summaryState.value = try {
                PageUiState(summary = repository.summary(_selection.value))
            } catch (e: Exception) {
                PageUiState(error = e.message ?: "数据加载失败")
            }
        }
    }
}
