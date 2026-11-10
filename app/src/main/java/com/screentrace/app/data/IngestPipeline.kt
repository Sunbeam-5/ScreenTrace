package com.screentrace.app.data

import androidx.room.withTransaction
import com.screentrace.app.contract.PullResult
import com.screentrace.app.data.db.AppUsageEntity
import com.screentrace.app.data.db.DaySummaryEntity
import com.screentrace.app.data.db.HourUsageEntity
import com.screentrace.app.data.db.NotificationEventEntity
import com.screentrace.app.data.db.PullLogEntity
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.db.UnlockEventEntity
import com.screentrace.app.data.parse.ParseResult

/**
 * 解析入库管道（批 6a）：ParseResult（日聚合 + 事件流）→ 合并入 Room + pull_log。
 * 全部写库在**单事务**中完成，失败整体回滚（§6）；连续拉取不重复计数
 * （day/app/hour 取大合并、unlock 主键去重、notification 秒级分组取大——
 * 策略集中在 [MergePolicy]，JVM 单测覆盖）。
 * Shizuku 拉取通道（把 dump 文本变成 ParseResult）在批 6b 接入。
 */
class IngestPipeline(private val db: ScreenTraceDatabase) {

    suspend fun ingest(
        result: ParseResult,
        source: String,
        pulledAtMs: Long = System.currentTimeMillis()
    ): PullResult = try {
        db.withTransaction {
            val dayDao = db.daySummaryDao()
            for ((date, metrics) in result.days) {
                val key = date.toString()
                val incoming = DaySummaryEntity(
                    date = key,
                    screenMin = metrics.screenMin,
                    screenUnlockMin = metrics.screenUnlockMin,
                    unlocks = metrics.unlocks,
                    launches = metrics.launches,
                    longestSessionMin = metrics.longestSessionMin,
                    pulledAt = pulledAtMs
                )
                dayDao.insert(MergePolicy.mergeDay(dayDao.get(key), incoming))
            }

            val appDao = db.appUsageDao()
            for (row in result.appUsage) {
                val key = row.date.toString()
                val incoming = AppUsageEntity(
                    date = key,
                    pkg = row.pkg,
                    name = row.pkg, // 展示名由调用方（批 6b）用 PackageManager/映射表回填
                    minutes = (row.totalMs / 60_000L).toInt(),
                    launches = row.launches,
                    category = "OTHER"
                )
                appDao.insert(MergePolicy.mergeApp(appDao.get(key, row.pkg), incoming))
            }

            val hourDao = db.hourUsageDao()
            for ((date, buckets) in result.hourMinutes) {
                val key = date.toString()
                buckets.forEachIndexed { hour, minutes ->
                    val incoming = HourUsageEntity(key, hour, minutes)
                    val existing = hourDao.getForDate(key).firstOrNull { it.hour == hour }
                    hourDao.insert(MergePolicy.mergeHour(existing, incoming))
                }
            }

            // CR-01 事件流：unlock 主键去重；notification 秒级分组取大
            db.unlockEventDao().insertIgnore(result.unlockEvents.map { UnlockEventEntity(it.occurredAtMs) })

            val notifDao = db.notificationEventDao()
            val incomingNotifs = result.notificationEvents.map {
                NotificationEventEntity(it.occurredAtMs, it.type.name, it.pkg, it.channelId)
            }
            if (incomingNotifs.isNotEmpty()) {
                val startMs = incomingNotifs.minOf { it.occurredAtMs } - 1000
                val endMs = incomingNotifs.maxOf { it.occurredAtMs } + 1000
                val existingNotifs = notifDao.between(startMs, endMs)
                val merged = MergePolicy.mergeNotifications(existingNotifs, incomingNotifs)
                if (merged != existingNotifs.sortedBy { it.occurredAtMs }) {
                    notifDao.deleteByMs(existingNotifs.map { it.occurredAtMs })
                    notifDao.insertIgnore(merged)
                }
            }

            db.pullLogDao().insert(
                PullLogEntity(pulledAt = pulledAtMs, source = source, ok = true, note = null)
            )
        }
        PullResult(ok = true, message = "入库完成：${result.days.size} 天")
    } catch (e: Exception) {
        // 失败整体回滚后记录 pull_log（§6：dump 为空或失败保留旧数据并记录）
        try {
            db.pullLogDao().insert(
                PullLogEntity(pulledAt = pulledAtMs, source = source, ok = false, note = e.message)
            )
        } catch (_: Exception) {
        }
        PullResult(ok = false, message = e.message ?: "入库失败")
    }
}
