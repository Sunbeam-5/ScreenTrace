package com.screentrace.app.data.pull

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.screentrace.app.data.parse.AppUsageRow
import com.screentrace.app.data.parse.ParseResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 降级通道（§2.1）：系统「使用情况访问」授权后，用 `UsageStatsManager` 取同源数据。
 *
 * **能力边界必须诚实**（这是本类最重要的设计约束）：
 * - 能拿到：应用级前台时长、启动次数、逐小时分布；
 * - 拿不到：`SCREEN_*` / `KEYGUARD_*` / `NOTIFICATION_*` 事件——这些对普通 App 不可见，
 *   因此**解锁次数、通知打扰/已查看、日聚合的 `unlocks/launches` 都没有数据**。
 * - 因此本通道**只写 `app_usage` 与 `hour_usage`，不写 `day_summary`**：
 *   `day_summary` 一旦写入就等于向 UI 宣称「解锁 = 0 次」这种假 0（§4/§7-16 红线）。
 *   代价是今日/统计页在降级通道下显示空态，排行与时段仍可用——这是诚实的降级，
 *   不是缺功能；`pull_log.note` 会写明本次来源，设置页据此提示用户启用 Shizuku。
 */
class UsageStatsUsageProvider(
    private val context: Context,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** 与主通道对齐：系统只保留约 10 个滚动日（§2.1）。 */
    private val lookbackDays: Long = 9
) : UsageProvider {

    override val channel: PullChannel = PullChannel.USAGE_STATS

    override suspend fun status(): ProviderStatus = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val uid = Process.myUid()
        val pkg = context.packageName
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, pkg)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, pkg)
        }
        if (mode == AppOpsManager.MODE_ALLOWED) ProviderStatus.AVAILABLE
        else ProviderStatus.USAGE_ACCESS_DENIED
    } catch (t: Throwable) {
        ProviderStatus.TEMPORARILY_UNAVAILABLE
    }

    override suspend fun load(): LoadedUsage {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return LoadedUsage.Failed(ProviderStatus.TEMPORARILY_UNAVAILABLE, "UsageStatsManager 不可用")
        val now = clock()
        val startMs = LocalDate.now(zone).minusDays(lookbackDays)
            .atStartOfDay(zone).toInstant().toEpochMilli()

        return try {
            val result = withContext(Dispatchers.Default) { build(manager, startMs, now) }
            LoadedUsage.Parsed(
                result = result,
                capturedAtMs = now,
                warning = "降级通道：无解锁/通知事件，日聚合与详情页不可用"
            )
        } catch (t: Throwable) {
            LoadedUsage.Failed(
                ProviderStatus.TEMPORARILY_UNAVAILABLE,
                "使用情况访问读取失败：${t.message ?: t.javaClass.simpleName}"
            )
        }
    }

    private fun build(manager: UsageStatsManager, startMs: Long, endMs: Long): ParseResult {
        val spans = mutableListOf<LongRange>()
        // (date, pkg) → 毫秒与启动次数
        val perApp = HashMap<Pair<LocalDate, String>, AppAgg>()
        val openSince = HashMap<String, Long>()

        val events = manager.queryEvents(startMs, endMs)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            when (event.eventType) {
                // 1 = ACTIVITY_RESUMED（MOVE_TO_FOREGROUND），2 = ACTIVITY_PAUSED；用字面量以兼容 minSdk 26
                1, 2, 23 -> {
                    if (event.eventType == 1) {
                        openSince[pkg] = event.timeStamp
                        perApp.getOrPut(dateOf(event.timeStamp) to pkg) { AppAgg() }.launches += 1
                    } else {
                        val startedAt = openSince.remove(pkg) ?: continue
                        appendSpan(spans, perApp, pkg, startedAt, event.timeStamp)
                    }
                }
            }
        }
        // 拉取时仍在前台的：截到 now
        openSince.forEach { (pkg, startedAt) -> appendSpan(spans, perApp, pkg, startedAt, endMs) }

        val hourMinutes = ForegroundBucketer.bucket(spans, zone)
        val appUsage = perApp.entries
            .filter { it.value.totalMs > 0 }
            .map { (key, agg) ->
                AppUsageRow(
                    date = key.first,
                    pkg = key.second,
                    totalMs = agg.totalMs,
                    launches = agg.launches
                )
            }

        return ParseResult(
            days = emptyMap(), // 见类注释：降级通道不写日聚合，避免假 0
            hourMinutes = hourMinutes,
            appUsage = appUsage,
            unlockEvents = emptyList(),
            notificationEvents = emptyList()
        )
    }

    /** 逐日逐应用的累加器；用持有类而不是 `LongArray(2)`，下标语义（[0]=ms/[1]=次数）太容易写错。 */
    private class AppAgg(var totalMs: Long = 0L, var launches: Int = 0)

    private fun appendSpan(
        spans: MutableList<LongRange>,
        perApp: HashMap<Pair<LocalDate, String>, AppAgg>,
        pkg: String,
        startMs: Long,
        endMs: Long
    ) {
        if (endMs <= startMs) return
        val cursor = maxOf(startMs, 0L)
        val end = endMs
        spans.add(cursor until end)
        // 跨零点的那一段要按日拆开，否则排行页的「逐日」会全算到起始日
        var dayCursor = cursor
        while (dayCursor < end) {
            val date = dateOf(dayCursor)
            val nextDay = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val segEnd = minOf(end, nextDay)
            perApp.getOrPut(date to pkg) { AppAgg() }.totalMs += segEnd - dayCursor
            dayCursor = segEnd
        }
    }

    private fun dateOf(ms: Long): LocalDate =
        Instant.ofEpochMilli(ms).atZone(zone).truncatedTo(ChronoUnit.DAYS).toLocalDate()
}
