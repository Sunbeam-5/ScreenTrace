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
import com.screentrace.app.data.parse.AppNames
import com.screentrace.app.data.parse.ParseResult

/**
 * 解析入库管道（批 6a）：ParseResult（日聚合 + 事件流）→ 合并入 Room + pull_log。
 * 全部写库在**单事务**中完成，失败整体回滚（§6）；连续拉取不重复计数
 * （day/app/hour 取大合并、unlock 主键去重、notification 秒级分组取大——
 * 策略集中在 [MergePolicy]，管道级行为由 `IngestPipelineTest` 覆盖）。
 * Shizuku 拉取通道（把 dump 文本变成 ParseResult）在批 6b 接入。
 */
class IngestPipeline(
    private val db: ScreenTraceDatabase,
    /**
     * §3-8 展示名来源（批 6b 接线）：返回 null 表示拿不到，回退 [AppNames] 再回退包名。
     * 不直接依赖 PackageManager，JVM 单测可以注入假实现。
     */
    private val labelOf: (String) -> String? = { null }
) {

    suspend fun ingest(
        result: ParseResult,
        source: String,
        pulledAtMs: Long = System.currentTimeMillis(),
        /** 部分源缺失等需要可追溯的说明（写进 `pull_log.note`，成功时也写）。 */
        note: String? = null
    ): PullResult {
        // 6a 评审 P2-2：dump 为空不记成功（§6「dump 为空或失败保留旧数据并记录」）
        // 判定收敛到 ParseResult.isEmpty，与拉取协调器的「空 dump」态共用同一口径
        if (result.isEmpty) {
            logPull(source, pulledAtMs, ok = false, note = "dump 为空")
            return PullResult(ok = false, message = "dump 为空")
        }

        return try {
            // 先过自洽性校验：应用时长超过当天屏幕时长的日期整日丢弃（见 IngestSanity 类注释）
            val verdict = IngestSanity.check(result.days, result.appUsage, result.hourMinutes)
            val finalNote = if (verdict.hasDrops) {
                val dropped = verdict.droppedDates.joinToString(",") { it.toString() }
                listOfNotNull(note, "应用/时段数据与全天时长矛盾，已丢弃：$dropped").joinToString("；")
            } else {
                note
            }
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
                    val existing = dayDao.get(key)
                    val merged = MergePolicy.mergeDay(existing, incoming)
                    // P2-7：命中「保留旧值」时不写回，避免无意义的 REPLACE
                    if (merged != existing) dayDao.insert(merged)
                }

                val appDao = db.appUsageDao()
                for (row in verdict.appUsage) {
                    val key = row.date.toString()
                    // 四舍五入而非截断：59 秒真实使用不得存成 0 分钟（§5.5，复审意见）
                    val minutes = Math.round(row.totalMs / 60_000.0).toInt()
                    // 但不足半分钟会舍成 0：这种行在排行里显示「Tomato · 0分钟」，
                    // 用户只会认为统计坏了，还会把「共 N 个应用」的计数虚增。
                    // 分钟粒度下不入库（原始 ms 已在解析结果里，不丢信息，只是不上榜）。
                    if (minutes <= 0) continue
                    val incoming = AppUsageEntity(
                        date = key,
                        pkg = row.pkg,
                        name = labelOf(row.pkg) ?: AppNames.nameOf(row.pkg),
                        minutes = minutes,
                        launches = row.launches,
                        // P1-1：内置分类映射写库，用户覆盖在仓库读取层叠加
                        category = AppNames.categoryOf(row.pkg).name
                    )
                    val existing = appDao.get(key, row.pkg)
                    val merged = MergePolicy.mergeApp(existing, incoming)
                    if (merged != existing) appDao.insert(merged)
                }

                val hourDao = db.hourUsageDao()
                for ((date, buckets) in verdict.hourMinutes) {
                    val key = date.toString()
                    val existingByHour = hourDao.getForDate(key).associateBy { it.hour } // 一次查询（复审意见）
                    buckets.forEachIndexed { hour, minutes ->
                        val incoming = HourUsageEntity(key, hour, minutes)
                        val existing = existingByHour[hour]
                        val merged = MergePolicy.mergeHour(existing, incoming)
                        if (merged != existing) hourDao.insert(merged)
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
                        // P0：区间删除，避免 `IN (:msList)` 在多日重拉时超过
                        // Android 8–11 的 SQLite 绑定变量上限（999）
                        notifDao.deleteBetween(startMs, endMs)
                        notifDao.insertIgnore(merged)
                    }
                }

                db.pullLogDao().insert(
                    PullLogEntity(pulledAt = pulledAtMs, source = source, ok = true, note = finalNote)
                )
            }
            PullResult(ok = true, message = "入库完成：${result.days.size} 天")
        } catch (e: Exception) {
            // 失败整体回滚后记录 pull_log（§6：dump 为空或失败保留旧数据并记录）
            logPull(source, pulledAtMs, ok = false, note = e.message)
            PullResult(ok = false, message = e.message ?: "入库失败")
        }
    }

    /** 失败日志本身再失败不得盖掉原始异常（写日志失败=无可记录）。 */
    private suspend fun logPull(source: String, pulledAtMs: Long, ok: Boolean, note: String?) {
        runCatching {
            db.pullLogDao().insert(
                PullLogEntity(pulledAt = pulledAtMs, source = source, ok = ok, note = note)
            )
        }
    }
}
