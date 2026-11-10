package com.screentrace.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * §4 六表 schema（CR-01 修正版红线：原四表原字段不得删除或改义，事件表只增不改）。
 * date 一律为设备本地自然日的 `yyyy-MM-dd` 字符串（§3-4）；时间戳为毫秒。
 */

@Entity(tableName = "day_summary")
data class DaySummaryEntity(
    @PrimaryKey val date: String,
    val screenMin: Int,
    val screenUnlockMin: Int,
    val unlocks: Int,
    val launches: Int,
    val longestSessionMin: Int,
    val pulledAt: Long
)

@Entity(tableName = "app_usage", primaryKeys = ["date", "pkg"])
data class AppUsageEntity(
    val date: String,
    val pkg: String,
    val name: String,
    val minutes: Int,
    val launches: Int,
    val category: String
)

@Entity(tableName = "hour_usage", primaryKeys = ["date", "hour"])
data class HourUsageEntity(
    val date: String,
    val hour: Int,
    val minutes: Int
)

@Entity(tableName = "pull_log")
data class PullLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pulledAt: Long,
    val source: String,
    val ok: Boolean,
    val note: String?
)

/** §4 unlock_event（CR-01，只增不改）：occurred_at_ms 主键去重。 */
@Entity(tableName = "unlock_event")
data class UnlockEventEntity(
    @PrimaryKey val occurredAtMs: Long
)

/**
 * §4 notification_event（CR-01，只增不改）：
 * PK = (occurred_at_ms, type, pkg)；channel_id 缺失为 NULL。
 * 按时间/包名建索引（CR-01 事件表索引要求）。
 * 注意（复审意见）：PK 不含 channelId——同毫秒同包同类型的两条记录会被
 * `INSERT OR IGNORE` 吞掉后者；物理上同毫秒双通知概率极低，如需保留需扩展 PK。
 */
@Entity(
    tableName = "notification_event",
    primaryKeys = ["occurredAtMs", "type", "pkg"],
    indices = [Index("pkg"), Index("occurredAtMs")]
)
data class NotificationEventEntity(
    val occurredAtMs: Long,
    val type: String,
    val pkg: String,
    val channelId: String?
)
