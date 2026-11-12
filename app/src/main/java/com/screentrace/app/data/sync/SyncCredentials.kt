package com.screentrace.app.data.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * §5.10：云同步凭据用 EncryptedSharedPreferences 存储，
 * 禁止明文写入数据库、日志或备份 JSON（§6 硬约束）。
 *
 * 只放**机密**（WebDAV 授权密码、OAuth 令牌）；非机密配置（地址/账号/远端目录/
 * 同步频率/仅 Wi-Fi/上次同步状态）在 SettingsStore（DataStore）。
 */
class SyncCredentials(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "sync_credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** WebDAV 密码（坚果云为「第三方应用授权密码」，不是登录密码）。 */
    var webdavPassword: String?
        get() = prefs.getString(KEY_WEBDAV_PASSWORD, null)
        set(value) = prefs.edit().putString(KEY_WEBDAV_PASSWORD, value).apply()

    /** Google OAuth 访问令牌（8b-2）；退出登录清除。 */
    var googleAccessToken: String?
        get() = prefs.getString(KEY_GOOGLE_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_GOOGLE_TOKEN, value).apply()

    /** 退出登录：清除全部机密（§5.10「退出登录即清除本地令牌」）。 */
    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_WEBDAV_PASSWORD = "webdav_password"
        const val KEY_GOOGLE_TOKEN = "google_access_token"
    }
}
