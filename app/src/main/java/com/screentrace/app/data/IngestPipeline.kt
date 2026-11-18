package com.screentrace.app.data

import androidx.room.withTransaction
import com.screentrace.app.contract.PullResult
import com.screentrace.app.data.db.AppUsageEntity
import com.screentrace.app.data.db.DaySummaryEntity
import com.screentrace.app.data.db.EventSeq
import com.screentrace.app.data.db.HourUsageEntity
import com.screentrace.app.data.db.NotificationEventEntity
import com.screentrace.app.data.db.PullLogEntity
import com.screentrace.app.data.db.ScreenSessionEntity
import com.screentrace.app.data.db.ScreenTraceDatabase
import com.screentrace.app.data.db.SourceIds
import com.screentrace.app.data.db.UnlockEventEntity
import java.time.ZoneId
import com.screentrace.app.data.parse.AppNames
import com.screentrace.app.data.parse.ParseResult

/**
 * 解析入库管道（批 6a）：ParseResult（日聚合 + 事件流）→ 合并入 Room + pull_log。
 * 全部写库在**单事务**中完成，失败整体回滚（§6）；连续拉取不重复计数
 * （day/app/hour 同来源取大合并、unlock/notification 主键去重 + 确定性 seq、
 * notification 秒级分组取大——策略集中在 [MergePolicy]，管道级行为由
 * `IngestPipelineTest` 覆盖）。Shizuku 拉取通道（把 dump 文本变成 ParseResult）在批 6b 接入。
 *
 * CR-28：本机拉取的行全部带 `sourceDeviceId = localDeviceId`，同来源取大合并
 * （跨来源求和在读侧聚合）；窗口删改只动本来源行（legacy 只读保护）。
 */
class IngestPipeline(
    private val db: ScreenTraceDatabase,
    /**
     * CR-28：本机 `deviceId`（写路径的来源标识）。默认 [SourceIds.UNSPECIFIED]
     * 仅供测试与旧调用点；生产由 AppContainer 传真实 ID。
     */
    private val localDeviceId: suspend () -> String = { SourceIds.UNSPECIFIED },
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
            val src = localDeviceId()
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
                        pulledAt = pulledAtMs,
                        sourceDeviceId = src
                    )
                    val existing = dayDao.get(key, src)
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
                        category = AppNames.categoryOf(row.pkg).name,
                        sourceDeviceId = src
                    )
                    val existing = appDao.get(key, row.pkg, src)
                    val merged = MergePolicy.mergeApp(existing, incoming)
                    if (merged != existing) appDao.insert(merged)
                }

                val hourDao = db.hourUsageDao()
                for ((date, buckets) in verdict.hourMinutes) {
                    val key = date.toString()
                    val existingByHour = hourDao.getForDate(key, src).associateBy { it.hour } // 一次查询（复审意见）
                    buckets.forEachIndexed { hour, minutes ->
                        val incoming = HourUsageEntity(key, hour, minutes, src)
                        val existing = existingByHour[hour]
                        val merged = MergePolicy.mergeHour(existing, incoming)
                        if (merged != existing) hourDao.insert(merged)
                    }
                }

                // CR-01 事件流：主键去重；CR-28 确定性 seq（同毫秒多条不丢、重放幂等）
                db.unlockEventDao().insertIgnore(
                    EventSeq.assign(
                        result.unlockEvents,
                        { listOf(it.occurredAtMs) },
                        { listOf(it.occurredAtMs) }
                    ).map { (e, seq) -> UnlockEventEntity(e.occurredAtMs, src, seq) }
                )

                // CR-28 批次 E：通知合并＝**完整主键 insertIgnore 并集**（同毫秒多条、
                // 同秒组不同内容互不丢；重复拉取同键幂等）。旧「秒级分组取大条数、
                // 整边替换」会把同秒组里条数少的一边整组丢掉（含其独有行）——
                // CR-01「同秒多条真实通知不丢」的 DB 层缺口，随主键含 seq 废除。
                // CR-28-R1 P1-1：组 = 主键组 (ms, type, pkg)、组内按**内容键**（含
                // channelId）稳定排序编号——跨批次乱序/超集/重放都稳定不吞。
                val incomingNotifs = EventSeq.assign(
                    result.notificationEvents,
                    { listOf(it.occurredAtMs, it.type, it.pkg) },
                    { listOf(it.occurredAtMs, it.type, it.pkg, it.channelId) }
                ).map { (e, seq) ->
                    NotificationEventEntity(e.occurredAtMs, e.type.name, e.pkg, e.channelId, src, seq)
                }
                db.notificationEventDao().insertIgnore(incomingNotifs)

                // 批 6g 三轮：会话区间真并集（治 MIUI 滚动缓冲少算）。
                // ① 旧行 ∪ 新区间 → 规范段集写回；② 受影响日期的日聚合/小时矩阵从
                // 并集重算，再与旧值取大——跨拉取的观测取「并」，不再是「单次观测取大」。
                // CR-28：并集与重算都只在**本来源**内做（跨来源区间永不合并）。
                val incomingSessions = result.screenSessions + result.keyguardSessions
                if (incomingSessions.isNotEmpty()) {
                    val sessionDao = db.screenSessionDao()
                    val lo = incomingSessions.minOf { it.startMs } - 60_000
                    val hi = incomingSessions.maxOf { it.endMs } + 60_000
                    val existingSessions = sessionDao.overlapping(lo, hi, src)
                    val mergedScreen = MergePolicy.mergeSessions(
                        existingSessions, result.screenSessions, ScreenSessionEntity.KIND_SCREEN, src
                    )
                    val mergedKg = MergePolicy.mergeSessions(
                        existingSessions, result.keyguardSessions, ScreenSessionEntity.KIND_KEYGUARD, src
                    )
                    sessionDao.deleteOverlapping(lo, hi, src)
                    sessionDao.insert(mergedScreen + mergedKg)

                    // ② 受影响日期从**全量已存区间**重算，不是只用本次窗口合并的子集——
                    // 同一天早先拉取见过的区间也要参与并集（滚动窗口场景：上午拉一次、
                    // 下午拉一次，各自只覆盖几小时，重算必须吃到两段）。
                    val zone = ZoneId.systemDefault()
                    val rangeStart = incomingSessions.minOf { it.startMs }
                    val rangeEnd = incomingSessions.maxOf { it.endMs }
                    var day = java.time.Instant.ofEpochMilli(rangeStart).atZone(zone).toLocalDate()
                    val lastDay =
                        java.time.Instant.ofEpochMilli(rangeEnd - 1).atZone(zone).toLocalDate()
                    while (!day.isAfter(lastDay)) {
                        val key = day.toString()
                        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
                        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                        // 该日窗口内的全部已存区间（含跨日长会话）；daySlices 内部按 kind 拆分
                        val stored = sessionDao.overlapping(dayStart, dayEnd, src)
                        val slice = SessionUnion.daySlices(stored, stored, zone)[day]
                        if (slice != null) {
                            val existing = dayDao.get(key, src)
                            val incomingRow = DaySummaryEntity(
                                date = key,
                                screenMin = slice.screenMin,
                                screenUnlockMin = slice.unlockMin,
                                // 解锁次数以事件表（只增、主键去重）为准，不从会话推导
                                unlocks = db.unlockEventDao().msBetween(dayStart, dayEnd - 1, src).size,
                                launches = existing?.launches ?: (result.days[day]?.launches ?: 0),
                                longestSessionMin = slice.longestMin,
                                pulledAt = pulledAtMs,
                                sourceDeviceId = src
                            )
                            val mergedDay = MergePolicy.mergeDay(existing, incomingRow)
                            if (mergedDay != existing) dayDao.insert(mergedDay)

                            val existingByHour = hourDao.getForDate(key, src).associateBy { it.hour }
                            slice.hours.forEachIndexed { hour, minutes ->
                                val incomingHour = HourUsageEntity(key, hour, minutes, src)
                                val existingHour = existingByHour[hour]
                                val mergedHour = MergePolicy.mergeHour(existingHour, incomingHour)
                                if (mergedHour != existingHour) hourDao.insert(mergedHour)
                            }
                        }
                        day = day.plusDays(1)
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
