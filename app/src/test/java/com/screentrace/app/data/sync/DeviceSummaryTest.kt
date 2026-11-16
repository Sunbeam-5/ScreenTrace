package com.screentrace.app.data.sync

import com.screentrace.app.data.backup.BackupSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSummaryTest {

    private fun sampleSummary() = BackupSummary(
        exportedAt = "2026-11-14 18:00",
        appVersion = "1.0",
        schemaVersion = 5,
        firstDate = "2026-11-10",
        lastDate = "2026-11-14",
        dayCount = 5,
        appRows = 12,
        hourRows = 24,
        pullRows = 5,
        unlockRows = 30,
        notificationRows = 40,
        sessionRows = 10,
        limitRows = 2,
        hasSettings = true,
        missingTables = emptyList()
    )

    @Test
    fun `正常摘要带来源 机型 版本 范围 记录数与只读声明`() {
        val text = deviceBackupSummary(
            SyncManager.DownloadedBackup(
                summary = sampleSummary(),
                deviceLabel = "MyRedmi",
                deviceModel = "Redmi Note 12"
            )
        )
        assertTrue(text.contains("来源：MyRedmi"))
        assertTrue(text.contains("机型：Redmi Note 12"))
        assertTrue(text.contains("导出：2026-11-14 18:00 · App 1.0 · v5"))
        assertTrue(text.contains("范围："))
        assertTrue(text.contains("日 5 / 应用 12 / 时段 24 / 事件 70 条"))
        assertTrue(text.contains("摘要只读"))
    }

    @Test
    fun `旧备份没有设备昵称时归未知设备`() {
        val text = deviceBackupSummary(
            SyncManager.DownloadedBackup(summary = sampleSummary(), deviceLabel = null)
        )
        assertTrue(text.contains("来源：未知设备"))
    }

    @Test
    fun `CR-21 拿不到机型时明说未知机型 不假装知道`() {
        val text = deviceBackupSummary(
            SyncManager.DownloadedBackup(summary = sampleSummary(), deviceLabel = "旧机", deviceModel = null)
        )
        assertTrue(text.contains("机型：未知机型"))
    }

    @Test
    fun `下载失败时给失败提示且不伪装成摘要`() {
        val text = deviceBackupSummary(
            SyncManager.DownloadedBackup(
                error = SyncResult.Err(SyncFailure.NETWORK, "连接超时")
            )
        )
        assertTrue(text.startsWith("查看失败："))
        assertFalse(text.contains("摘要只读"))
        assertFalse(text.contains("导出："))
    }

    /**
     * CR-21 ②③：摘要本身**不提供**恢复 / 合并入口——那两枚动作按钮在设备列表行里，
     * 且合并要二次确认。摘要只负责把话说清楚。
     */
    @Test
    fun `摘要不给恢复入口 只声明切换与合并需显式点击`() {
        val text = deviceBackupSummary(
            SyncManager.DownloadedBackup(summary = sampleSummary(), deviceLabel = "其它手机")
        )
        assertFalse("摘要不得声称自己写入本机数据", text.contains("不会写入本机数据"))
        assertTrue(text.contains("切换与合并需显式点击并确认"))
        assertFalse(text.contains("覆盖本地"))
        assertFalse(text.contains("导入"))
    }
}
