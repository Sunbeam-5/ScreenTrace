package com.screentrace.app.data.sync

import java.io.ByteArrayOutputStream
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * TD-07：WebDAV URL 路径逐段编码与 RFC 3986 path 段对称解码（纯函数，无 Android 依赖）。
 *
 * 解决旧实现的三个缺陷：
 * 1. 字符串拼接未编码导致空格/中文/`#`/`?`/`%`/`&` 破坏 URL 语义；
 * 2. `URLDecoder.decode` 按 form-urlencoded 语义把字面 `+` 错误解成空格；
 * 3. `URLDecoder.decode` 遇非法 `%` 抛 `IllegalArgumentException` 导致整份 PROPFIND 列表失败。
 */
object WebDavUrlCodec {

    /**
     * 使用 OkHttp `HttpUrl.Builder.addPathSegment` 逐段编码 `remoteDir` 与 `path`。
     * - 保留目录末尾 `/`（当 `path == null` 或 `path.endsWith("/")` 时追加空段 `addPathSegment("")`）；
     * - `baseUrl` 非法时抛 `IllegalArgumentException`（由调用方转为 `SyncFailure.FORMAT`）。
     */
    fun buildWebDavUrl(baseUrl: String, remoteDir: String, path: String?): HttpUrl {
        val parsed = baseUrl.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("无效的 WebDAV 地址：$baseUrl")
        val builder = parsed.newBuilder()
        val baseSegments = parsed.pathSegments.filter { it.isNotEmpty() }
        val dirSegments = remoteDir.split('/').filter { it.isNotEmpty() }
        val relSegments = path?.split('/')?.filter { it.isNotEmpty() }.orEmpty()
        val trailingSlash = path == null || path.endsWith("/")

        builder.encodedPath("/")
        (baseSegments + dirSegments + relSegments).forEach { seg ->
            builder.addPathSegment(seg)
        }
        if (trailingSlash) {
            builder.addPathSegment("")
        }
        return builder.build()
    }

    /**
     * RFC 3986 path 段百分比解码（UTF-8）：
     * - 绝不把 `+` 解成空格；
     * - 遇到非法 `%` 序列（如 `%ZZ` 或末尾孤立 `%`）按字面 `%` 原样保留，绝不抛异常吞掉整份目录列表。
     */
    fun decodePathSegment(raw: String): String {
        if (!raw.contains('%')) return raw
        val out = ByteArrayOutputStream(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '%' && i + 2 < raw.length) {
                val hi = Character.digit(raw[i + 1], 16)
                val lo = Character.digit(raw[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    out.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            out.write(ch.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
