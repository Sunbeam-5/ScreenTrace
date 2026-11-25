package com.screentrace.app.data.sync

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TD-08：WebDAV Basic Auth HTTPS 边界单测 + 行为变异锚点 M-TD2。
 */
class WebDavSecurityPolicyTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `M_TD2_HTTPS无警告_HTTP明文显式标记风险但不阻断_非法scheme拒绝`() {
        val https = assessWebDavSecurity("https://dav.jianguoyun.com/dav/")
        assertEquals(WebDavTransportSecurity.HTTPS, https.security)
        assertFalse(https.isPlaintext)
        assertNull(https.warningMessage)

        val http = assessWebDavSecurity("http://192.168.1.2:8080/dav/")
        assertEquals(WebDavTransportSecurity.HTTP_PLAINTEXT, http.security)
        assertTrue("http:// 必须标记明文传输风险", http.isPlaintext)
        assertNotNull(http.warningMessage)
        assertTrue(http.warningMessage!!.contains("明文"))

        val invalid = assessWebDavSecurity("ftp://192.168.1.2/dav/")
        assertEquals(WebDavTransportSecurity.INVALID, invalid.security)
        assertFalse(invalid.isPlaintext)
        assertNotNull(invalid.warningMessage)
    }

    @Test
    fun `WebDavProvider使用http局域网地址时正常连通但结果带明文传输标记`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(207))
        val provider = WebDavProvider(
            config = {
                WebDavConfig(
                    baseUrl = server.url("/dav/").toString(), // MockWebServer 默认 http://
                    username = "nas",
                    password = "secret",
                    remoteDir = "/screen-time/"
                )
            }
        )
        val res = provider.testConnection()
        assertTrue(res is SyncResult.Ok)
        assertTrue((res as SyncResult.Ok).plaintextTransport)
    }
}
