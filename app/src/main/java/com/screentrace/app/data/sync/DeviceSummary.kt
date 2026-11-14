package com.screentrace.app.data.sync

/**
 * §5.2 批 D / §5.10 批 C：设备备份「只读摘要」的统一文案。
 *
 * 云同步页设备卡片与顶栏下拉设备面板共用这一份，禁止两处各写一套口径漂移；
 * 末尾那句「只读查看，不会写入本机数据」是批 C 裁定的红线，不能删。
 */
fun deviceBackupSummary(downloaded: SyncManager.DownloadedBackup): String {
    val summary = downloaded.summary
        ?: return "查看失败：${(downloaded.error as? SyncResult.Err)?.kind?.userMessage() ?: "远端备份不可用"}"
    return "来源：${downloaded.deviceLabel ?: "未知设备"}\n" +
        "导出：${summary.exportedAt} · App ${summary.appVersion} · v${summary.schemaVersion}\n" +
        "范围：${summary.rangeText}\n" +
        "记录：日 ${summary.dayCount} / 应用 ${summary.appRows} / 时段 ${summary.hourRows} / " +
        "事件 ${summary.unlockRows + summary.notificationRows} 条\n" +
        "只读查看，不会写入本机数据"
}
