package com.screentrace.app.data.sync

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser

/**
 * §5.10 通用 WebDAV 适配器（OkHttp 手写 PROPFIND / MKCOL / PUT / GET / DELETE 最小子集）。
 * 预置坚果云 `https://dav.jianguoyun.com/dav/`（账号 = 邮箱，密码 = **第三方应用授权密码**，
 * 不是登录密码——配置页附获取指引）。
 *
 * 超时 15 秒（§6）；错误按 §5.10 分类：网络不可达 / 401 鉴权 / 404 目录不存在 /
 * 格式错误 / 507 空间不足。
 */
class WebDavProvider(
    /** 每次调用读当前配置（设置页随时可改，不能建连时缓存）；从 DataStore 读需 suspend。 */
    private val config: suspend () -> WebDavConfig,
    private val client: OkHttpClient = defaultClient()
) : SyncProvider {

    override val id: String = "webdav"

    override suspend fun testConnection(): SyncResult = request("PROPFIND", depth = "0") { it.toResult() }

    override suspend fun upload(path: String, bytes: ByteArray): SyncResult {
        // 远端目录可能还不存在：MKCOL 幂等（405/301 = 已存在，忽略）
        val dir = request("MKCOL", dirOnly = true) { SyncResult.Ok() }
        if (dir is SyncResult.Err && dir.kind == SyncFailure.NETWORK) return dir
        return request("PUT", path = path, body = bytes.toRequestBody("application/json".toMediaType())) { it.toResult() }
    }

    override suspend fun download(path: String): SyncResult = request("GET", path = path) { resp ->
        when (resp.code) {
            404 -> SyncResult.Err(SyncFailure.NOT_FOUND, "远端文件不存在")
            else -> SyncResult.Ok(content = resp.body?.bytes())
        }
    }

    override suspend fun list(): SyncResult = request("PROPFIND", depth = "1", dirOnly = true) { resp ->
        val body = resp.body?.string().orEmpty()
        try {
            SyncResult.Ok(files = parsePropfindNames(body))
        } catch (e: Exception) {
            SyncResult.Err(SyncFailure.FORMAT, "目录列表解析失败：${e.message}")
        }
    }

    override suspend fun delete(path: String): SyncResult = request("DELETE", path = path) { resp ->
        when (resp.code) {
            404 -> SyncResult.Ok() // 幂等：不存在即目标状态
            else -> resp.toResult()
        }
    }

    override suspend fun disconnect() { /* WebDAV 无会话态（Basic Auth 随请求携带） */ }

    private suspend fun request(
        method: String,
        path: String? = null,
        depth: String? = null,
        dirOnly: Boolean = false,
        body: okhttp3.RequestBody? = null,
        handle: (Response) -> SyncResult
    ): SyncResult = withContext(Dispatchers.IO) {
        val cfg = config()
        val url = buildUrl(cfg, if (dirOnly) null else path)
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(cfg.username, cfg.password))
        depth?.let { builder.header("Depth", it) }
        if (method == "PROPFIND") {
            builder.method(method, PROPFIND_BODY.toRequestBody("application/xml".toMediaType()))
        } else {
            builder.method(method, body)
        }
        try {
            client.newCall(builder.build()).execute().use { resp ->
                when (resp.code) {
                    401, 403 -> SyncResult.Err(SyncFailure.AUTH, "HTTP ${resp.code}")
                    404 -> if (method == "PROPFIND") {
                        SyncResult.Err(SyncFailure.NOT_FOUND, "远端目录不存在")
                    } else {
                        handle(resp)
                    }
                    507 -> SyncResult.Err(SyncFailure.QUOTA, "HTTP 507 存储空间不足")
                    in 200..299, 405, 301 -> handle(resp) // 405/301：MKCOL 已存在的幂等语义
                    else -> SyncResult.Err(SyncFailure.UNKNOWN, "HTTP ${resp.code}")
                }
            }
        } catch (e: IOException) {
            SyncResult.Err(SyncFailure.NETWORK, e.message ?: "网络不可达")
        }
    }

    private fun Response.toResult(): SyncResult = SyncResult.Ok()

    private fun buildUrl(cfg: WebDavConfig, path: String?): String {
        val base = cfg.baseUrl.trimEnd('/') + "/" + cfg.remoteDir.trim('/') + "/"
        return if (path == null) base else base + path.trimStart('/')
    }

    companion object {
        /** 坚果云预置地址（§5.10）。 */
        const val JIANGUOYUN_URL = "https://dav.jianguoyun.com/dav/"

        /** 手动备份固定文件名（§5.10：对所有 provider 一致）。 */
        const val MANUAL_FILE = "screen-time-backup.json"

        private val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <a:propfind xmlns:a="DAV:">
              <a:prop><a:getcontentlength/><a:resourcetype/></a:prop>
            </a:propfind>
        """.trimIndent()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        /** 解析 PROPFIND Depth-1 响应里的文件名（排除目录自身与子目录）。 */
        internal fun parsePropfindNames(xml: String): List<RemoteFile> {
            val files = mutableListOf<RemoteFile>()
            val parser = android.util.Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(xml.reader())
            var inResponse = false
            var href: String? = null
            var isCollection = false
            var length = 0L
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when {
                    event == XmlPullParser.START_TAG && parser.name == "response" -> {
                        inResponse = true; href = null; isCollection = false; length = 0L
                    }
                    inResponse && event == XmlPullParser.START_TAG && parser.name == "href" ->
                        href = parser.nextText()
                    inResponse && event == XmlPullParser.START_TAG && parser.name == "collection" ->
                        isCollection = true
                    inResponse && event == XmlPullParser.START_TAG && parser.name == "getcontentlength" ->
                        length = parser.nextText().toLongOrNull() ?: 0L
                    event == XmlPullParser.END_TAG && parser.name == "response" -> {
                        val name = href?.substringAfterLast('/')?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                        if (inResponse && !isCollection && !name.isNullOrEmpty()) {
                            files += RemoteFile(name, length)
                        }
                        inResponse = false
                    }
                }
                event = parser.next()
            }
            return files
        }
    }
}

/** §5.10 WebDAV 配置（非机密部分在 SettingsStore，密码在 [SyncCredentials]）。 */
data class WebDavConfig(
    val baseUrl: String,
    val username: String,
    val password: String,
    val remoteDir: String
)
