package com.screentrace.app

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CR-29：系统备份关闭（P1 隐私）。
 *
 * `allowBackup="true"` 时系统云备份 / `adb backup` 会把全部应用数据（使用记录数据库、
 * DataStore 设置、合并账本）带出设备、绕过 §5.10 的导出体系。三重断言：
 * ① 运行时 flags（行为向）；② manifest 声明；③ 两个规则文件全排除（兜底）。
 * 改回 true / 删掉 exclude 必须变红（变异验证）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupPolicyTest {

    private fun src(path: String): File =
        File(System.getProperty("user.dir"), "src/main/$path")

    @Test
    fun `应用不允许系统备份`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val info = ctx.packageManager.getApplicationInfo(ctx.packageName, 0)
        assertFalse(
            "allowBackup 必须关闭（FLAG_ALLOW_BACKUP 不置位）",
            (info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP) != 0
        )
    }

    @Test
    fun `manifest 关闭 allowBackup 并挂全排除规则文件`() {
        val manifest = src("AndroidManifest.xml").readText(Charsets.UTF_8)
        assertTrue("allowBackup 必须为 false", manifest.contains("android:allowBackup=\"false\""))
        assertTrue("≤API30 兜底规则必须挂上", manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue("API31+ 规则必须挂上", manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }

    @Test
    fun `备份规则文件全排除云端备份与设备迁移`() {
        val backup = src("res/xml/backup_rules.xml").readText(Charsets.UTF_8)
        assertTrue("≤API30 规则全排除", backup.contains("full-backup-content") && backup.contains("domain=\"root\""))

        val rules = src("res/xml/data_extraction_rules.xml").readText(Charsets.UTF_8)
        // 按**标签**截段（注释里也提到 cloud-backup / device-transfer 字样，按裸词截会截到注释）
        assertTrue("云备份全排除", rules.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>").contains("domain=\"root\""))
        assertTrue("设备迁移全排除", rules.substringAfter("<device-transfer>").substringBefore("</device-transfer>").contains("domain=\"root\""))
    }
}
