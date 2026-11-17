package com.screentrace.app.data.sync

import com.screentrace.app.data.backup.BackupCodec
import com.screentrace.app.data.backup.BackupDevice
import com.screentrace.app.data.backup.BackupFile
import com.screentrace.app.data.prefs.DeviceIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §5.10 批 C：远端路径口径 + v5 备份兼容（纯 JVM，无需 Robolectric）。 */
class SyncPathsTest {

    @Test
    fun `设备目录路径带 devices 前缀且路径安全`() {
        assertEquals("devices/abc-123/", SyncPaths.deviceDir("abc-123"))
        assertEquals("devices/abc123/", SyncPaths.deviceDir("../../abc/123"))
        assertEquals("devices/unknown/", SyncPaths.deviceDir("///"))
        assertEquals("devices/abc-123/screen-time-backup.json", SyncPaths.manualPath("abc-123"))
    }

    @Test
    fun `自动备份文件名带设备短 ID 与时间戳`() {
        val id = "a1b2c3d4-5678-90ef"
        val name = SyncPaths.autoFileName(id, "20261114-1530")
        assertEquals("screen-time-backup-auto-a1b2c3d4-20261114-1530.json", name)
        assertEquals("devices/a1b2c3d4-5678-90ef/" + name, SyncPaths.autoPath(id, "20261114-1530"))
        assertTrue(SyncPaths.isAuto(name))
        assertFalse(SyncPaths.isAuto(SyncPaths.MANUAL_FILE))
        assertEquals("a1b2c3d4", DeviceIds.short(id))
        assertEquals("unknown", DeviceIds.short("---"))
    }

    @Test
    fun `latestFile 手动固定文件优先 其余按自动倒序`() {
        val names = listOf(
            "screen-time-backup-auto-abcd-20261112-0900.json",
            "screen-time-backup-auto-abcd-20261114-0900.json",
            "readme.txt",
            SyncPaths.MANUAL_FILE
        )
        assertEquals(SyncPaths.MANUAL_FILE, SyncPaths.latestFile(names))
        assertEquals(
            "screen-time-backup-auto-abcd-20261114-0900.json",
            SyncPaths.latestFile(names - SyncPaths.MANUAL_FILE)
        )
        assertNull(SyncPaths.latestFile(listOf("readme.txt")))
        assertFalse(SyncPaths.isBackup("readme.txt"))
    }

    @Test
    fun `备份往返保留 device 且无缺表提示`() {
        val file = BackupFile(
            schemaVersion = BackupCodec.SCHEMA_VERSION,
            exportedAt = "2026-11-14 15:30",
            appVersion = "1.0",
            device = BackupDevice("uuid-1", "我的手机", "Redmi")
        )
        assertEquals(6, BackupCodec.SCHEMA_VERSION)
        val decoded = BackupCodec.decode(BackupCodec.encode(file))
        assertTrue(decoded is BackupCodec.DecodeResult.Ok)
        val ok = decoded as BackupCodec.DecodeResult.Ok
        assertEquals("uuid-1", ok.file.device?.id)
        assertEquals("我的手机", ok.summary.device?.nickname)
        assertTrue(ok.summary.missingTables.isEmpty())
    }

    @Test
    fun `v4 旧备份解码后归未知设备 且逐条提示设备标识缺失`() {
        val old = """{"schemaVersion":4,"exportedAt":"2026-11-01 08:00","appVersion":"0.9",
            "day_summary":[],"app_usage":[],"hour_usage":[],"pull_log":[]}"""
        val decoded = BackupCodec.decode(old)
        assertTrue(decoded is BackupCodec.DecodeResult.Ok)
        val ok = decoded as BackupCodec.DecodeResult.Ok
        assertNull(ok.file.device)
        assertNull(ok.summary.device)
        assertTrue(ok.summary.missingTables.any { it.contains("设备标识") })
    }

    @Test
    fun `高于当前 schema 版本的备份明确拒绝`() {
        val future = "{\"schemaVersion\":${BackupCodec.SCHEMA_VERSION + 1},\"exportedAt\":\"x\",\"appVersion\":\"9\"}"
        val decoded = BackupCodec.decode(future)
        assertTrue(decoded is BackupCodec.DecodeResult.Invalid)
        assertTrue((decoded as BackupCodec.DecodeResult.Invalid).reason.contains("升级"))
    }
}
