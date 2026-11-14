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
        // §5.10 批 C：path 可能是 `devices/<deviceId>/...`，要把两级目录逐级建出来。
        // MKCOL 幂等（405/301/409 = 已存在，忽略）；只有网络类失败才提前放弃。
        val ensured = ensureDirsFor(path)
        if (ensured is SyncResult.Err) return ensured
        return request("PUT", path = path, body = bytes.toRequestBody("application/json".toMediaType())) { it.toResult() }
    }

    private suspend fun ensureDirsFor(path: String): SyncResult {
        val root = request("MKCOL", dirOnly = true) { SyncResult.Ok() }
        if (root is SyncResult.Err && root.kind == SyncFailure.NETWORK) return root
        var acc = ""
        path.trimStart('/').substringBeforeLast('/', "").split('/')
            .filter { it.isNotEmpty() }
            .forEach { segment ->
                acc += "$segment/"
                val made = request("MKCOL", path = acc) { SyncResult.Ok() }
                if (made is SyncResult.Err && made.kind == SyncFailure.NETWORK) return made
            }
        return SyncResult.Ok()
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

    /** §5.10 批 C：列 `devices/` 才能发现各设备目录，列 `devices/<id>/` 才能看该设备的备份。 */
    override suspend fun listIn(path: String): SyncResult =
        request("PROPFIND", path = path, depth = "1") { resp ->
            val body = resp.body?.string().orEmpty()
            // PROPFIND 响应第一条是目录自身（href 以 / 结尾且是 collection），要剔掉，
            // 否则会把 `devices` 自己当成一个设备。
            val selfName = path.trim('/').substringAfterLast('/')
            try {
                SyncResult.Listed(
                    parsePropfindEntries(body).filterNot { it.isCollection && it.name == selfName }
                )
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
                    in 200..299, 405, 301, 409 -> handle(resp) // MKCOL 已存在的幂等语义
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

        /** 手动备份固定文件名（§5.10：对所有 provider 一致）；口径收在 [SyncPaths]。 */
        const val MANUAL_FILE = SyncPaths.MANUAL_FILE

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
        internal fun parsePropfindNames(xml: String): List<RemoteFile> =
            parsePropfindEntries(xml).filter { !it.isCollection }
                .map { RemoteFile(it.name, it.sizeBytes) }

        /** 解析 PROPFIND Depth-1 的全部条目，含子目录（批 C 设备发现用）。 */
        internal fun parsePropfindEntries(xml: String): List<RemoteEntry> {
            val files = mutableListOf<RemoteEntry>()
            val parser = android.util.Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(xml.reader())
            var inResponse = false
            var href: String? = null
            var isCollection = false
            var length = 0L
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                // 真机（坚果云）返回的是 <d:response>/<d:href>/<d:collection>：
                // 关掉命名空间处理后 parser.name 会保留 "d:" 前缀，只比裸标签名会一条都解析不到
                // （2026-11-14 批 C 真机复核发现：设备列表永远为空、旧备份也归不了「未知设备」）。
                val tag = when (event) {
                    XmlPullParser.START_TAG, XmlPullParser.END_TAG ->
                        parser.name?.substringAfterLast(':') ?: ""
                    else -> ""
                }
                when {
                    event == XmlPullParser.START_TAG && tag == "response" -> {
                        inResponse = true; href = null; isCollection = false; length = 0L
                    }
                    inResponse && event == XmlPullParser.START_TAG && tag == "href" ->
                        href = parser.nextText()
                    inResponse && event == XmlPullParser.START_TAG && tag == "collection" ->
                        isCollection = true
                    inResponse && event == XmlPullParser.START_TAG && tag == "getcontentlength" ->
                        length = parser.nextText().toLongOrNull() ?: 0L
                    event == XmlPullParser.END_TAG && tag == "response" -> {
                        // 目录的 href 常以 / 结尾，先 trimEnd 才能拿到目录名本身
                        val name = href?.trimEnd('/')?.substringAfterLast('/')
                            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                        if (inResponse && !name.isNullOrEmpty()) {
                            files += RemoteEntry(name, length, isCollection)
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
