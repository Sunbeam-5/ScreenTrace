package com.screentrace.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.screentrace.app.contract.AppCategory
import com.screentrace.app.contract.DEFAULT_LOW_THRESHOLD_MIN
import com.screentrace.app.theme.ThemeVariant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * §5.8/§6：每日目标、拉取频率、主题/深浅/动态取色、分类修改用 DataStore 持久化。
 * 「分类修改纳入备份与同步」（§4）——categoryOverrides 随备份导出（批 8）。
 * UI 接线在批 6b（AppViewModel 注入）。
 */
class SettingsStore(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.settingsDataStore)

    val dailyGoalMin: Flow<Int> = dataStore.data.map { it[DAILY_GOAL_MIN] ?: DEFAULT_GOAL_MIN }
    suspend fun setDailyGoalMin(value: Int) = dataStore.edit { it[DAILY_GOAL_MIN] = value }

    /** 拉取频率（§5.8「目标与同步」）：如 "daily"；批 7 设置页细化枚举。 */
    val pullFrequency: Flow<String> = dataStore.data.map { it[PULL_FREQUENCY] ?: "daily" }
    suspend fun setPullFrequency(value: String) = dataStore.edit { it[PULL_FREQUENCY] = value }

    val themeVariant: Flow<ThemeVariant> = dataStore.data.map {
        runCatching { ThemeVariant.valueOf(it[THEME_VARIANT] ?: "") }
            .getOrDefault(ThemeVariant.MINT)
    }
    suspend fun setThemeVariant(value: ThemeVariant) = dataStore.edit { it[THEME_VARIANT] = value.name }

    val darkTheme: Flow<Boolean> = dataStore.data.map { it[DARK_THEME] ?: false }
    suspend fun setDarkTheme(value: Boolean) = dataStore.edit { it[DARK_THEME] = value }

    val dynamicColor: Flow<Boolean> = dataStore.data.map { it[DYNAMIC_COLOR] ?: false }
    suspend fun setDynamicColor(value: Boolean) = dataStore.edit { it[DYNAMIC_COLOR] = value }

    /**
     * 「低于 x 小时算少」的阈值（分钟）。日历页那张卡片的标题与计数都取自这里，
     * 不再写死 2 小时——作息不同的人对这个线的感受完全不同。
     */
    val lowUsageThresholdMin: Flow<Int> = dataStore.data.map {
        LowUsageThreshold.clampMinutes(
            it[LOW_USAGE_THRESHOLD_MIN] ?: DEFAULT_LOW_THRESHOLD_MIN
        )
    }

    suspend fun setLowUsageThresholdMin(value: Int) =
        dataStore.edit { it[LOW_USAGE_THRESHOLD_MIN] = LowUsageThreshold.clampMinutes(value) }

    /** 分类覆盖（§4：用户可改并纳入备份）：`pkg=CATEGORY` 编码。 */
    val categoryOverrides: Flow<Map<String, AppCategory>> = dataStore.data.map { prefs ->
        (prefs[CATEGORY_OVERRIDES] ?: emptySet()).mapNotNull { encoded ->
            val idx = encoded.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val pkg = encoded.substring(0, idx)
            val category = runCatching { AppCategory.valueOf(encoded.substring(idx + 1)) }.getOrNull()
            category?.let { pkg to it }
        }.toMap()
    }

    suspend fun setCategoryOverride(pkg: String, category: AppCategory) = dataStore.edit { prefs ->
        val current = prefs[CATEGORY_OVERRIDES] ?: emptySet()
        prefs[CATEGORY_OVERRIDES] = current.filterNot { it.startsWith("$pkg=") }.toSet() + "$pkg=${category.name}"
    }

    companion object {
        /** §5.8 设置默认值：每日目标 240 分钟（与 AppViewModel.DEFAULT_GOAL_MIN 一致）。 */
        const val DEFAULT_GOAL_MIN = 240

        private val DAILY_GOAL_MIN = intPreferencesKey("daily_goal_min")
        private val PULL_FREQUENCY = stringPreferencesKey("pull_frequency")
        private val THEME_VARIANT = stringPreferencesKey("theme_variant")
        private val DARK_THEME = booleanPreferencesKey("dark_theme")
        private val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val LOW_USAGE_THRESHOLD_MIN = intPreferencesKey("low_usage_threshold_min")
        private val CATEGORY_OVERRIDES = stringSetPreferencesKey("category_overrides")
    }
}

/**
 * §5.8 / §7-23：低使用阈值的**唯一口径**（1–24 小时，分钟存储）。
 *
 * 之前设置页只给 1/2/3/4 四档，但阈值还会从备份 / 旧配置 / DataStore 直接读出来，
 * 这些入口都可能带进越界值。夹取放在存取两端，UI 与外部导入都不必各自记一遍范围。
 */
internal object LowUsageThreshold {
    const val MIN_HOURS = 1
    const val MAX_HOURS = 24

    /** 分钟 → 分钟（夹到 60..1440）。 */
    fun clampMinutes(value: Int): Int = value.coerceIn(MIN_HOURS * 60, MAX_HOURS * 60)

    /** 分钟 → 小时档位（UI 滑杆用）。 */
    fun hoursOf(minutes: Int): Int =
        (clampMinutes(minutes) / 60).coerceIn(MIN_HOURS, MAX_HOURS)

    /** 小时 → 分钟（写入用）。 */
    fun minutesOf(hours: Int): Int =
        hours.coerceIn(MIN_HOURS, MAX_HOURS) * 60
}
