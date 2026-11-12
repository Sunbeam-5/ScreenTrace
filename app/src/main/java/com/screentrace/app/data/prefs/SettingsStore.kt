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

    /**
     * 拉取频率（§5.8「目标与同步」）。字符串存储保持向后兼容（旧版默认写的是 `daily`），
     * 读取时大小写不敏感匹配枚举名，认不出的一律按默认「每天」。
     */
    val pullFrequency: Flow<PullFrequency> = dataStore.data.map {
        PullFrequency.of(it[PULL_FREQUENCY])
    }
    suspend fun setPullFrequency(value: PullFrequency) =
        dataStore.edit { it[PULL_FREQUENCY] = value.name }

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
     * §5.4 CR-04 自定义主题种子色（ARGB 长整型）。null = 未启用，走九套预设。
     *
     * 存 Long 而不是 `Color`：DataStore 只认基本类型，且这个值要原样进备份 settings，
     * 存成 Int 会在备份 JSON 里变成负数（ARGB 高位为 1），排查时反而看不懂。
     */
    val customThemeSeed: Flow<Long?> = dataStore.data.map { it[CUSTOM_THEME_SEED] }

    suspend fun setCustomThemeSeed(argb: Long?) = dataStore.edit { prefs ->
        if (argb == null) prefs.remove(CUSTOM_THEME_SEED) else prefs[CUSTOM_THEME_SEED] = argb
    }

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

    /**
     * §5.8 首启向导是否已完成（含「跳过」）。
     *
     * 只用于决定要不要盖向导，**不参与备份导出**：换机恢复备份后仍然应该看一次
     * 本机的授权状态，而不是被备份里的 true 直接跳过。
     */
    val onboardingDone: Flow<Boolean> = dataStore.data.map { it[ONBOARDING_DONE] ?: false }

    suspend fun setOnboardingDone(value: Boolean) =
        dataStore.edit { it[ONBOARDING_DONE] = value }

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

    /** 恢复备份时整批写入（§5.10「同时恢复设置」勾选后）。 */
    suspend fun setCategoryOverrides(overrides: Map<String, AppCategory>) = dataStore.edit { prefs ->
        prefs[CATEGORY_OVERRIDES] = overrides.map { (pkg, category) -> "$pkg=${category.name}" }.toSet()
    }

    suspend fun setCategoryOverride(pkg: String, category: AppCategory) = dataStore.edit { prefs ->
        val current = prefs[CATEGORY_OVERRIDES] ?: emptySet()
        prefs[CATEGORY_OVERRIDES] = current.filterNot { it.startsWith("$pkg=") }.toSet() + "$pkg=${category.name}"
    }

    // ---- 批 8b：云同步非机密配置与状态（§5.10；密码/令牌在 SyncCredentials）----

    /** WebDAV 地址（默认坚果云，可改任意 WebDAV 服务）。 */
    val webdavUrl: Flow<String> = dataStore.data.map { it[WEBDAV_URL] ?: com.screentrace.app.data.sync.WebDavProvider.JIANGUOYUN_URL }
    suspend fun setWebdavUrl(value: String) = dataStore.edit { it[WEBDAV_URL] = value }

    val webdavUsername: Flow<String> = dataStore.data.map { it[WEBDAV_USERNAME] ?: "" }
    suspend fun setWebdavUsername(value: String) = dataStore.edit { it[WEBDAV_USERNAME] = value }

    /** 远端目录（默认 /screen-time/）。 */
    val webdavRemoteDir: Flow<String> = dataStore.data.map { it[WEBDAV_REMOTE_DIR] ?: "/screen-time/" }
    suspend fun setWebdavRemoteDir(value: String) = dataStore.edit { it[WEBDAV_REMOTE_DIR] = value }

    /** 自动同步频率（默认关闭，§5.10）。 */
    val syncFrequency: Flow<SyncFrequency> = dataStore.data.map { SyncFrequency.of(it[SYNC_FREQUENCY]) }
    suspend fun setSyncFrequency(value: SyncFrequency) = dataStore.edit { it[SYNC_FREQUENCY] = value.name }

    /** 仅 Wi-Fi 同步（默认开启，§5.10）。 */
    val syncWifiOnly: Flow<Boolean> = dataStore.data.map { it[SYNC_WIFI_ONLY] ?: true }
    suspend fun setSyncWifiOnly(value: Boolean) = dataStore.edit { it[SYNC_WIFI_ONLY] = value }

    /** 上次同步时间与结果（失败静默写状态，设置页展示，§5.10）。 */
    val syncLastAt: Flow<Long?> = dataStore.data.map { it[SYNC_LAST_AT] }
    val syncLastOk: Flow<Boolean?> = dataStore.data.map { it[SYNC_LAST_OK] }
    val syncLastMessage: Flow<String> = dataStore.data.map { it[SYNC_LAST_MESSAGE] ?: "" }
    suspend fun recordSync(atMs: Long, ok: Boolean, message: String) = dataStore.edit {
        it[SYNC_LAST_AT] = atMs
        it[SYNC_LAST_OK] = ok
        it[SYNC_LAST_MESSAGE] = message
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
        private val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        private val CUSTOM_THEME_SEED = androidx.datastore.preferences.core.longPreferencesKey("custom_theme_seed")
        private val WEBDAV_URL = stringPreferencesKey("webdav_url")
        private val WEBDAV_USERNAME = stringPreferencesKey("webdav_username")
        private val WEBDAV_REMOTE_DIR = stringPreferencesKey("webdav_remote_dir")
        private val SYNC_FREQUENCY = stringPreferencesKey("sync_frequency")
        private val SYNC_WIFI_ONLY = booleanPreferencesKey("sync_wifi_only")
        private val SYNC_LAST_AT = androidx.datastore.preferences.core.longPreferencesKey("sync_last_at")
        private val SYNC_LAST_OK = booleanPreferencesKey("sync_last_ok")
        private val SYNC_LAST_MESSAGE = stringPreferencesKey("sync_last_message")
    }
}

/**
 * §2.1/§5.8：后台拉取频率。
 *
 * 系统只保留约 10 个滚动日（§2.1），所以**最慢**也必须每天拉一次——这三档不是
 * 「随便给几个选项」，而是「至少一天一次」到「更保险」的区间：
 * 9 天窗口 + 一天一次的余量已经很紧，12/6 小时只是给爱关机的人留的双保险。
 */
enum class PullFrequency(val label: String, val hours: Long) {
    DAILY("每天", 24),
    TWELVE_HOURS("每 12 小时", 12),
    SIX_HOURS("每 6 小时", 6);

    companion object {
        fun of(raw: String?): PullFrequency =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: DAILY
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


/**
 * §5.10 自动同步频率（默认关闭）。手动下拉成功在「每次成功拉取后」档同样触发上传（C10）；
 * 「每 1/3/7 天」按时间窗口判断，不因手动刷新提前或推迟。
 */
enum class SyncFrequency(val label: String, val days: Long) {
    OFF("关闭", 0),
    EVERY_PULL("每次成功拉取后", 0),
    EVERY_1_DAY("每 1 天", 1),
    EVERY_3_DAYS("每 3 天", 3),
    EVERY_7_DAYS("每 7 天", 7);

    companion object {
        fun of(raw: String?): SyncFrequency =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: OFF
    }
}
