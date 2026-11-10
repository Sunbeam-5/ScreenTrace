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
        private val CATEGORY_OVERRIDES = stringSetPreferencesKey("category_overrides")
    }
}
