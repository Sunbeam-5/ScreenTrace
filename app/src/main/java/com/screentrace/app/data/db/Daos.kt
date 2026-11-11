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

    /** 评审 P0-1：整页空态判定用（降级通道不写本表，须三表一起看）。 */
    @Query("SELECT COUNT(*) FROM day_summary")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM day_summary WHERE date BETWEEN :start AND :end")
    suspend fun countBetween(start: String, end: String): Int

    @Query("DELETE FROM day_summary WHERE date = :date")
    suspend fun delete(date: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: DaySummaryEntity)
}

@Dao
interface AppUsageDao {
    /**
     * §5.7 排行：区间按 (pkg) 聚合（分钟/启动数求和，name/category 取一致值）。
     *
     * name 用 `MAX(CASE WHEN name = pkg THEN '' ELSE name END)` 而不是裸 `MAX(name)`
     * （6b-6d 评审 P2-1）：裸 MAX 是**字符串字典序**比较，包名占位（如 `com.xxx`）
     * 会把回填过的真名（如 `Zenly`）比下去，界面又退回包名。这里先把「名字 == 包名」
     * 视作空串参与比较，真名只要存在就一定赢。
     */
    @Query(
        """
        SELECT pkg, MAX(CASE WHEN name = pkg THEN '' ELSE name END) AS name,
               MAX(category) AS category,
               SUM(minutes) AS minutes, SUM(launches) AS launches
        FROM app_usage WHERE date BETWEEN :start AND :end
        GROUP BY pkg ORDER BY minutes DESC
        """
    )
    suspend fun sumBetween(start: String, end: String): List<AppUsageAggregated>

    @Query("SELECT DISTINCT pkg, name FROM app_usage")
    suspend fun allNames(): List<PkgName>

    /**
     * 有应用数据的全部日期（评审 P0-1 第 2 条）：降级通道不写 `day_summary`，
     * 覆盖范围只数日聚合会把「只有应用数据」误判成「完全无数据」。
     */
    @Query("SELECT DISTINCT date FROM app_usage")
    suspend fun dates(): List<String>

    @Query("SELECT * FROM app_usage WHERE date = :date AND pkg = :pkg")
    suspend fun get(date: String, pkg: String): AppUsageEntity?

    @Query("SELECT COUNT(*) FROM app_usage")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM app_usage WHERE date BETWEEN :start AND :end")
    suspend fun countBetween(start: String, end: String): Int

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

    @Query("SELECT COUNT(*) FROM hour_usage")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM hour_usage WHERE date BETWEEN :start AND :end")
    suspend fun countBetween(start: String, end: String): Int

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

/**
 * 批 6g 三轮：会话区间 DAO（v2 新增表）。合并语义见 [ScreenSessionEntity] 注释：
 * 读出重叠窗口的旧行 → 与新拉取区间做扫线并集 → 先删后插（单事务）。
 */
@Dao
interface ScreenSessionDao {

    /** 与窗口相交的全部区间（`endMs >= lo AND startMs <= hi`）。 */
    @Query("SELECT * FROM screen_session WHERE endMs >= :startMs AND startMs <= :endMs ORDER BY startMs")
    suspend fun overlapping(startMs: Long, endMs: Long): List<ScreenSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rows: List<ScreenSessionEntity>)

    /** 与窗口相交的区间整段删除（合并输出覆盖了它们的全部范围，无信息丢失）。 */
    @Query("DELETE FROM screen_session WHERE endMs >= :startMs AND startMs <= :endMs")
    suspend fun deleteOverlapping(startMs: Long, endMs: Long)

    @Query("SELECT COUNT(*) FROM screen_session")
    suspend fun count(): Int

    @Query("SELECT MIN(startMs) FROM screen_session WHERE kind = 'SCREEN'")
    suspend fun minScreenStartMs(): Long?
}
