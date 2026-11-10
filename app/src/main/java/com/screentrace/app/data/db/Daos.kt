package com.screentrace.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DaySummaryDao {
    @Query("SELECT * FROM day_summary WHERE date BETWEEN :start AND :end ORDER BY date")
    suspend fun getBetween(start: String, end: String): List<DaySummaryEntity>

    @Query("SELECT * FROM day_summary ORDER BY date")
    suspend fun getAll(): List<DaySummaryEntity>

    @Query("SELECT * FROM day_summary WHERE date = :date")
    suspend fun get(date: String): DaySummaryEntity?

    @Query("DELETE FROM day_summary WHERE date = :date")
    suspend fun delete(date: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: DaySummaryEntity)
}

@Dao
interface AppUsageDao {
    /** §5.7 排行：区间按 (pkg) 聚合（分钟/启动数求和，name/category 取一致值）。 */
    @Query(
        """
        SELECT pkg, MAX(name) AS name, MAX(category) AS category,
               SUM(minutes) AS minutes, SUM(launches) AS launches
        FROM app_usage WHERE date BETWEEN :start AND :end
        GROUP BY pkg ORDER BY minutes DESC
        """
    )
    suspend fun sumBetween(start: String, end: String): List<AppUsageAggregated>

    @Query("SELECT DISTINCT pkg, name FROM app_usage")
    suspend fun allNames(): List<PkgName>

    @Query("SELECT * FROM app_usage WHERE date = :date AND pkg = :pkg")
    suspend fun get(date: String, pkg: String): AppUsageEntity?

    @Query("DELETE FROM app_usage WHERE date = :date AND pkg = :pkg")
    suspend fun delete(date: String, pkg: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: AppUsageEntity)
}

data class AppUsageAggregated(
    val pkg: String,
    val name: String,
    val category: String,
    val minutes: Int,
    val launches: Int
)

data class PkgName(val pkg: String, val name: String)

@Dao
interface HourUsageDao {
    @Query("SELECT hour, SUM(minutes) AS minutes FROM hour_usage WHERE date BETWEEN :start AND :end GROUP BY hour ORDER BY hour")
    suspend fun sumBetween(start: String, end: String): List<HourBucketAggregated>

    @Query("SELECT * FROM hour_usage WHERE date = :date ORDER BY hour")
    suspend fun getForDate(date: String): List<HourUsageEntity>

    @Query("DELETE FROM hour_usage WHERE date = :date AND hour = :hour")
    suspend fun delete(date: String, hour: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: HourUsageEntity)
}

data class HourBucketAggregated(val hour: Int, val minutes: Int)

@Dao
interface PullLogDao {
    @Insert
    suspend fun insert(row: PullLogEntity)

    @Query("SELECT * FROM pull_log ORDER BY pulledAt DESC LIMIT 1")
    suspend fun latest(): PullLogEntity?

    /** 6a 评审 P1-3：拉取失败/成功的毫秒时间戳（推导覆盖三态的 GAP 日）。 */
    @Query("SELECT pulledAt FROM pull_log WHERE ok = 0")
    suspend fun failedMs(): List<Long>

    @Query("SELECT pulledAt FROM pull_log WHERE ok = 1")
    suspend fun okMs(): List<Long>
}

@Dao
interface UnlockEventDao {
    /** §4 合并策略：主键去重，重复插入即忽略。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: UnlockEventEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(rows: List<UnlockEventEntity>)

    @Query("SELECT occurredAtMs FROM unlock_event WHERE occurredAtMs BETWEEN :startMs AND :endMs ORDER BY occurredAtMs")
    suspend fun msBetween(startMs: Long, endMs: Long): List<Long>

    @Query("SELECT MIN(occurredAtMs) FROM unlock_event")
    suspend fun minMs(): Long?
}

@Dao
interface NotificationEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: NotificationEventEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(rows: List<NotificationEventEntity>)

    @Query("SELECT * FROM notification_event WHERE occurredAtMs BETWEEN :startMs AND :endMs ORDER BY occurredAtMs")
    suspend fun between(startMs: Long, endMs: Long): List<NotificationEventEntity>

    @Query("SELECT * FROM notification_event ORDER BY occurredAtMs")
    suspend fun getAll(): List<NotificationEventEntity>

    /**
     * 按区间删除（6a 评审 P0）：Android 8–11 的 SQLite 绑定参数上限 999，
     * `IN (:msList)` 多日重拉必炸——区间删除无变量上限问题。
     */
    @Query("DELETE FROM notification_event WHERE occurredAtMs BETWEEN :startMs AND :endMs")
    suspend fun deleteBetween(startMs: Long, endMs: Long)

    @Query("SELECT MIN(occurredAtMs) FROM notification_event")
    suspend fun minMs(): Long?
}
