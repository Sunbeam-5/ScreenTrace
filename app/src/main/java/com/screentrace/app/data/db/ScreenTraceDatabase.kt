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
    exportSchema = false
)
abstract class ScreenTraceDatabase : RoomDatabase() {
    abstract fun daySummaryDao(): DaySummaryDao
    abstract fun appUsageDao(): AppUsageDao
    abstract fun hourUsageDao(): HourUsageDao
    abstract fun pullLogDao(): PullLogDao
    abstract fun unlockEventDao(): UnlockEventDao
    abstract fun notificationEventDao(): NotificationEventDao
}
