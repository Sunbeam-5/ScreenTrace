package com.screentrace.app.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §5.10 批 C：PROPFIND 解析必须能认出 `devices/<id>/` 子目录，否则设备列举永远为空。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WebDavPropfindTest {

    private val xml = """
        <?xml version="1.0" encoding="utf-8"?>
        <multistatus xmlns="DAV:">
          <response>
            <href>/dav/screen-time/devices/</href>
            <propstat><prop><resourcetype><collection/></resourcetype></prop></propstat>
          </response>
          <response>
            <href>/dav/screen-time/devices/abc-123/</href>
            <propstat><prop><resourcetype><collection/></resourcetype></prop></propstat>
          </response>
          <response>
            <href>/dav/screen-time/devices/abc-123/screen-time-backup.json</href>
            <propstat><prop><getcontentlength>1234</getcontentlength></prop></propstat>
          </response>
        </multistatus>
    """.trimIndent()

    @Test
    fun `解析条目同时认出子目录与文件`() {
        val entries = WebDavProvider.parsePropfindEntries(xml)
        assertEquals(3, entries.size)
        assertTrue(entries.any { it.name == "devices" && it.isCollection })
        assertTrue(entries.any { it.name == "abc-123" && it.isCollection })
        val file = entries.single { !it.isCollection }
        assertEquals("screen-time-backup.json", file.name)
        assertEquals(1234L, file.sizeBytes)
    }

    @Test
    fun `旧的文件名列表仍排除所有子目录`() {
        val names = WebDavProvider.parsePropfindNames(xml)
        assertEquals(1, names.size)
        assertEquals("screen-time-backup.json", names[0].name)
        assertEquals(1234L, names[0].sizeBytes)
    }

    /** 真机复核回归：坚果云的 Multi-Status 带 `d:` 前缀，只认裸标签会一条都解析不到。 */
    private val prefixedXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
          <d:response>
            <d:href>/dav/screen-time/devices/</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
          </d:response>
          <d:response>
            <d:href>/dav/screen-time/screen-time-backup.json</d:href>
            <d:propstat><d:prop><d:getcontentlength>4321</d:getcontentlength></d:prop></d:propstat>
          </d:response>
        </d:multistatus>
    """.trimIndent()

    @Test
    fun `坚果云带 d 命名空间前缀的响应能解析出目录与文件`() {
        val entries = WebDavProvider.parsePropfindEntries(prefixedXml)
        assertEquals(2, entries.size)
        assertTrue(entries.any { it.name == "devices" && it.isCollection })
        val names = WebDavProvider.parsePropfindNames(prefixedXml)
        assertEquals(1, names.size)
        assertEquals("screen-time-backup.json", names[0].name)
        assertEquals(4321L, names[0].sizeBytes)
    }
}
