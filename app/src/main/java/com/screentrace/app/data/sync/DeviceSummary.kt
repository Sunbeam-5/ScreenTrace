package com.screentrace.app.data.sync

/**
 * §5.2 / §5.10：设备备份「只读摘要」的统一文案。
 *
 * 云同步页设备卡片与顶栏设备面板共用这一份，禁止两处各写一套口径漂移。
 *
 * CR-21 ①：摘要里补一行「机型」——老设备目录里没有 `device.json` 时，
 * 机型从这里（备份顶层 `device.model`）补出来。
 *
 * CR-21 ②③：末尾那句从「只读查看，不会写入本机数据」改成
 * 「摘要只读；切换与合并需显式点击并确认」——CR-21 给设备面板与云同步页加了
 * 「切换到此设备」「合并到本机」两枚真实动作按钮，继续写「不会写入本机数据」
 * 就是与实际行为不符的假文案了（需求文档 §5.2 / §5.10 已同步改写）。
 */
fun deviceBackupSummary(downloaded: SyncManager.DownloadedBackup): String {
    val summary = downloaded.summary
        ?: return "查看失败：${(downloaded.error as? SyncResult.Err)?.kind?.userMessage() ?: "远端备份不可用"}"
    return "来源：${downloaded.deviceLabel ?: "未知设备"}\n" +
        "机型：${downloaded.deviceModel ?: "未知机型"}\n" +
        "导出：${summary.exportedAt} · App ${summary.appVersion} · v${summary.schemaVersion}\n" +
        "范围：${summary.rangeText}\n" +
        "记录：日 ${summary.dayCount} / 应用 ${summary.appRows} / 时段 ${summary.hourRows} / " +
        "事件 ${summary.unlockRows + summary.notificationRows} 条\n" +
        "摘要只读；切换与合并需显式点击并确认"
}
