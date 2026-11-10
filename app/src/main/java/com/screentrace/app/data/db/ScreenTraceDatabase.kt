package com.screentrace.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * §4 六表（初次建库即全量，避免先四表再迁移——CR-01 C01）。
 * 版本 1：原四表 schema 严格照 §4 红线 + CR-01 两张事件表（只增不改）。
 */
@Database(
    entities = [
        DaySummaryEntity::class,
        AppUsageEntity::class,
        HourUsageEntity::class,
        PullLogEntity::class,
        UnlockEventEntity::class,
        NotificationEventEntity::class
    ],
    version = 1,
    // P2-1（6a 评审）：导出 schema JSON 到 app/schemas，供批 6b+ 的迁移与批 8 备份校验使用。
    // 注意区分：这里的 version 是**数据库结构版本**，备份文件的 schemaVersion（§5.10 C07）是另一套。
    exportSchema = true
)
abstract class ScreenTraceDatabase : RoomDatabase() {
    abstract fun daySummaryDao(): DaySummaryDao
    abstract fun appUsageDao(): AppUsageDao
    abstract fun hourUsageDao(): HourUsageDao
    abstract fun pullLogDao(): PullLogDao
    abstract fun unlockEventDao(): UnlockEventDao
    abstract fun notificationEventDao(): NotificationEventDao
}
