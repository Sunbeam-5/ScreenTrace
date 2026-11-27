package com.screentrace.app.data.parse

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * 单通道有界连续观测窗口（BoundedObservedWindow）。
 *
 * 口径声明：`EXACT` 是基于单通道有界观测 + 屏幕状态机完整性检查的**保守工程完整性判定策略**，
 * 而非 Android `dumpsys usagestats` 的 OS 级零丢包证明。
 */
data class BoundedObservedWindow(val startMs: Long, val endMs: Long)

/**
 * 单通道屏幕状态流审计结果。
 * - [window]：通过屏幕状态事件与 28h 有界门禁的连续观测窗口；任一前置不满足时为 null。
 * - [truncatedDates]：命中状态机截断/异常的自然日集合，供入库 G3 门禁强制降级为 PARTIAL。
 */
data class ScreenStreamAuditResult(
    val window: BoundedObservedWindow?,
    val truncatedDates: Set<LocalDate>
)

/** 单通道原始屏幕状态事件输入（不跨通道混拼）。 */
data class ScreenStateEvent(
    val ms: Long,
    val type: String,
    val user: Int = 0
)

/**
 * D-07 Review 修复（P1-2）：单通道有界连续观测窗口签发与屏幕状态机截断门禁。
 *
 * 硬约束：
 * - 屏幕状态事件严格仅限 `{SCREEN_INTERACTIVE, SCREEN_NON_INTERACTIVE}`；
 *   `KEYGUARD_HIDDEN` 仅作解锁计数/诊断辅助，严禁参与窗口起点签发或状态机初态确立。
 * - 单通道审计前必须先对该通道内的屏幕事件执行跨账户 `±1000ms` 镜像收敛，
 *   避免 Redmi/vivo 多账户（`user=0/666/999`）的正常镜像事件被误判为 `ON→ON` 截断。
 * - `EXACT` 是基于单通道有界观测 + 屏幕状态机完整性检查的保守工程完整性判定策略，
 *   而非 Android `dumpsys usagestats` 的 OS 级零丢包证明。
 */
object ScreenStreamAudit {

    /** 单次拉取有界窗口上限：28 小时（兼容 vivo 实测 ~26h 缓冲 + DST/flush 余量，拦截跨多天离散旧事件）。 */
    const val MAX_SINGLE_PULL_WINDOW_MS: Long = 28L * 3600L * 1000L

    private const val MIRROR_TOLERANCE_MS: Long = 1000L
    const val TYPE_INTERACTIVE: String = "SCREEN_INTERACTIVE"
    const val TYPE_NON_INTERACTIVE: String = "SCREEN_NON_INTERACTIVE"

    private val SCREEN_STATE_TYPES = setOf(TYPE_INTERACTIVE, TYPE_NON_INTERACTIVE)

    fun audit(
        events: List<ScreenStateEvent>,
        zone: ZoneId = ZoneId.systemDefault(),
        capturedAtMs: Long? = null
    ): ScreenStreamAuditResult {
        // 门禁 1：通道无屏幕状态事件 → window = null（不得由非屏幕事件或 KEYGUARD_HIDDEN 撑起窗口）
        val rawScreen = events.filter { it.type in SCREEN_STATE_TYPES }
        if (rawScreen.isEmpty()) {
            return ScreenStreamAuditResult(window = null, truncatedDates = emptySet())
        }

        // 多账户防误杀：跨账户 ±1000ms 同态镜像收敛（同账户重复保留，交由状态机拦截）
        val converged = convergeCrossAccountMirrors(rawScreen)
        val startMs = converged.minOf { it.ms }
        val lastScreenMs = converged.maxOf { it.ms }
        val endMs = if (capturedAtMs != null) maxOf(capturedAtMs, lastScreenMs) else lastScreenMs

        // 门禁 6：单通道窗口 endMs <= startMs 或超过 28h 上限 → window = null
        val window = if (endMs > startMs && (endMs - startMs) <= MAX_SINGLE_PULL_WINDOW_MS) {
            BoundedObservedWindow(startMs = startMs, endMs = endMs)
        } else {
            null
        }

        val truncatedDates = mutableSetOf<LocalDate>()
        fun dateOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        fun markDateRange(fromMs: Long, toMs: Long) {
            val lo = minOf(fromMs, toMs)
            val hi = maxOf(fromMs, toMs)
            var d = dateOf(lo)
            val last = dateOf(hi)
            while (!d.isAfter(last)) {
                truncatedDates.add(d)
                d = d.plusDays(1)
            }
        }

        val byMs = converged.groupBy { it.ms }.toSortedMap()
        var isFirst = true
        var prevType: String? = null
        var prevMs: Long? = null
        var openOnMs: Long? = null

        for ((ms, group) in byMs) {
            val hasOn = group.any { it.type == TYPE_INTERACTIVE }
            val hasOff = group.any { it.type == TYPE_NON_INTERACTIVE }

            // 门禁 2：同一时间戳出现冲突（INTERACTIVE + NON_INTERACTIVE）或同态重复
            if (hasOn && hasOff) {
                openOnMs?.let { markDateRange(it, ms) }
                truncatedDates.add(dateOf(ms))
                openOnMs = null
                prevType = null
                prevMs = ms
                isFirst = false
                continue
            }
            if (group.size > 1) {
                truncatedDates.add(dateOf(ms))
            }

            val type = group.first().type

            // 门禁 3：首条屏幕事件为 SCREEN_NON_INTERACTIVE（前置亮屏起点丢失）
            if (isFirst && type == TYPE_NON_INTERACTIVE) {
                truncatedDates.add(dateOf(ms))
            }

            // 门禁 4：相邻跃迁出现连续 ON→ON 或 OFF→OFF
            if (prevType != null && prevType == type && prevMs != null) {
                markDateRange(prevMs, ms)
            }

            if (type == TYPE_INTERACTIVE) {
                openOnMs = ms
            } else {
                openOnMs = null
            }
            prevType = type
            prevMs = ms
            isFirst = false
        }

        // 门禁 5：尾部存在未闭合 SCREEN_INTERACTIVE @ lastOnMs（含跨午夜）
        openOnMs?.let { lastOnMs ->
            markDateRange(lastOnMs, maxOf(lastOnMs, endMs))
        }

        return ScreenStreamAuditResult(window = window, truncatedDates = truncatedDates)
    }

    private fun convergeCrossAccountMirrors(events: List<ScreenStateEvent>): List<ScreenStateEvent> {
        return events
            .groupBy { it.type }
            .flatMap { (_, group) ->
                val sorted = group.sortedWith(compareBy<ScreenStateEvent> { it.ms }.thenBy { it.user })
                val kept = mutableListOf<ScreenStateEvent>()
                for (e in sorted) {
                    val mirrored = kept.any {
                        it.user != e.user && abs(it.ms - e.ms) <= MIRROR_TOLERANCE_MS
                    }
                    if (!mirrored) kept.add(e)
                }
                kept
            }
            .sortedWith(compareBy<ScreenStateEvent> { it.ms }.thenBy { it.user })
    }
}
