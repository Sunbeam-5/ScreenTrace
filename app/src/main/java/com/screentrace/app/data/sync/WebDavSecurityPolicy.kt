package com.screentrace.app.data.sync

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class WebDavTransportSecurity {
    HTTPS,
    HTTP_PLAINTEXT,
    INVALID
}

data class WebDavSecurityAssessment(
    val security: WebDavTransportSecurity,
    val isPlaintext: Boolean,
    val warningMessage: String?
)

/**
 * TD-08：WebDAV 传输安全边界判定（纯函数）。
 * - `https://`：安全，无警告；
 * - `http://`：允许局域网 NAS 等合法场景继续使用，但必须显式标记明文风险；
 * - 其他/非法 scheme：判 `INVALID`。
 */
fun assessWebDavSecurity(baseUrl: String): WebDavSecurityAssessment {
    val trimmed = baseUrl.trim()
    val url = trimmed.toHttpUrlOrNull()
    if (url == null) {
        return WebDavSecurityAssessment(
            security = WebDavTransportSecurity.INVALID,
            isPlaintext = false,
            warningMessage = "WebDAV 地址格式无效，请以 https:// 或 http:// 开头"
        )
    }
    return when (url.scheme.lowercase()) {
        "https" -> WebDavSecurityAssessment(
            security = WebDavTransportSecurity.HTTPS,
            isPlaintext = false,
            warningMessage = null
        )
        "http" -> WebDavSecurityAssessment(
            security = WebDavTransportSecurity.HTTP_PLAINTEXT,
            isPlaintext = true,
            warningMessage = "当前使用 HTTP 明文连接，账号与授权密码未加密传输，仅建议在可信局域网内使用"
        )
        else -> WebDavSecurityAssessment(
            security = WebDavTransportSecurity.INVALID,
            isPlaintext = false,
            warningMessage = "仅支持 https:// 或 http:// 协议"
        )
    }
}
