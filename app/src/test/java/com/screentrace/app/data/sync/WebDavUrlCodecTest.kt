package com.screentrace.app.data.sync

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TD-07：WebDAV URL 逐段编码与 RFC 3986 path 解码单测 + MockWebServer 集成验证。
 * 含行为变异锚点 M-TD1（decodePathSegment 误把 + 解成空格或抛异常）与 M-TD4（buildUrl 绕过编码）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WebDavUrlCodecTest {

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
    fun `M_TD1_decodePathSegment保留字面加号且遇非法百分号不抛异常`() {
        assertEquals("dev+1", WebDavUrlCodec.decodePathSegment("dev+1"))
        assertEquals("a+b c", WebDavUrlCodec.decodePathSegment("a+b%20c"))
        assertEquals("红米 Note", WebDavUrlCodec.decodePathSegment("%E7%BA%A2%E7%B1%B3%20Note"))
        // 非法 %ZZ 或末尾孤立 % 不抛 IllegalArgumentException，原样保留
        assertEquals("bad%ZZname%", WebDavUrlCodec.decodePathSegment("bad%ZZname%"))
    }

    @Test
    fun `buildWebDavUrl对特殊字符逐段编码而不破坏query或fragment`() {
        val url = WebDavUrlCodec.buildWebDavUrl(
            baseUrl = "https://dav.example.com/dav/",
            remoteDir = "/screen trace/",
            path = "devices/id#1?a=2+3%4/screen-time-backup.json"
        )
        assertEquals("", url.query ?: "")
        assertEquals(null, url.fragment)
        val encoded = url.encodedPath
        assertTrue(encoded.contains("screen%20trace"))
        assertTrue(encoded.contains("id%231%3Fa=2+3%254") || encoded.contains("id%231%3Fa%3D2+3%254"))
        assertEquals(
            listOf("dav", "screen trace", "devices", "id#1?a=2+3%4", "screen-time-backup.json"),
            url.pathSegments
        )
    }

    @Test
    fun `M_TD4_WebDavProvider真实HTTP请求逐段编码且PROPFIND不吞掉含加号与非法百分号的条目`() = runBlocking {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/devices/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/devices/dev%2Bplus/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/devices/raw+plus/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/devices/bad%ZZdir/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(207).setBody(xml))

        val provider = WebDavProvider(
            config = {
                WebDavConfig(
                    baseUrl = server.url("/dav/").toString(),
                    username = "u",
                    password = "p",
                    remoteDir = "screen time"
                )
            }
        )
        val res = provider.listIn("devices/id#1?x=1/")
        assertTrue(res is SyncResult.Listed)
        val names = (res as SyncResult.Listed).entries.map { it.name }
        assertTrue(names.contains("dev+plus"))
        assertTrue(names.contains("raw+plus"))
        assertTrue(names.contains("bad%ZZdir"))

        val recorded = server.takeRequest()
        assertFalse("特殊字符 # 不得截断为 fragment", recorded.path!!.contains("#"))
        assertTrue(recorded.path!!.contains("screen%20time"))
        assertTrue(recorded.path!!.contains("id%231%3Fx"))
    }
}
