package com.screentrace.app.data.parse

import java.util.Locale

/**
 * 批 13 Review 修复（P2-1）：镜像收敛审计结论三态。
 * - [NORMAL]：账户分区信息明确（≥2）且有效样本类型的重复率均在预期区间内；
 * - [ANOMALY]：至少一类镜像事件的重复率显著偏离 dump 实际账户数倍率；
 * - [INDETERMINATE]：账户分区信息不完整（如无 `user=N` 头或仅单账户）或各类样本不足，明确标记无法判断。
 */
enum class MirrorAuditStatus {
    NORMAL,
    ANOMALY,
    INDETERMINATE
}

/** 单类系统镜像事件（如 `SCREEN_INTERACTIVE` / `KEYGUARD_HIDDEN`）的独立审计结果。 */
data class MirrorTypeAudit(
    val eventType: String,
    val rawCount: Int,
    val convergedCount: Int,
    val repetitionRatio: Double,
    val status: MirrorAuditStatus
)

/**
 * §2.3 / §7-32：多账户系统镜像事件（SCREEN_* / KEYGUARD_*）跨账户收敛自检报告。
 *
 * 批 13 Review 修复（P2-1）：
 * - [distinctUsers] 必须来自整个 dump 的 `user=N` 分区计数，而非仅从出现镜像事件的账户反推，
 *   防止某账户缺失系统级镜像事件时漏报；
 * - 按事件类型独立计算跨账户 `±1000ms` 镜像组倍率（[byType]）；
 * - 样本不足或账户分区信息不完整时明确标记 [MirrorAuditStatus.INDETERMINATE]，不默认正常；
 * - 审计只产出诊断告警，绝不修改事件收敛算法。
 */
data class MirrorConvergenceReport(
    val distinctUsers: Int,
    val rawMirrorEvents: Int,
    val convergedMirrorEvents: Int,
    val repetitionRatio: Double,
    val anomalyWarning: String?,
    val status: MirrorAuditStatus = MirrorAuditStatus.INDETERMINATE,
    val byType: List<MirrorTypeAudit> = emptyList()
)

object MirrorConvergenceAudit {
    /** 最少收敛后物理事件组数，避免极小样本（1–4 组）触发误报。 */
    const val MIN_SAMPLE_EVENTS = 5

    /**
     * 允许偏离 dump 账户数倍率的容忍比例（±20%）：
     * - 2 账户正常区间 [1.60×, 2.40×]（丢 1 账户 1.00× 必检出）；
     * - 3 账户正常区间 [2.40×, 3.60×]（实测 ≈2.98× 正常；丢 1 账户 2.00× 必检出）；
     * - 4 账户正常区间 [3.20×, 4.80×]（丢 1 账户 3.00× 必检出）。
     */
    const val TOLERANCE_FRACTION = 0.20

    /**
     * 按事件类型独立审计跨账户镜像倍率。
     *
     * @param dumpUserCount 整个 dump 中解析到的独立 `user=N` 分区总数（0 表示缺少分区头，信息不完整）
     * @param perTypeCounts `eventType -> (rawCount, convergedGroupCount)`
     */
    fun evaluateByType(
        dumpUserCount: Int,
        perTypeCounts: Map<String, Pair<Int, Int>>
    ): MirrorConvergenceReport {
        val totalRaw = perTypeCounts.values.sumOf { it.first }
        val totalConverged = perTypeCounts.values.sumOf { it.second }
        val overallRatio = if (totalConverged > 0) {
            totalRaw.toDouble() / totalConverged.toDouble()
        } else {
            0.0
        }

        val expected = dumpUserCount.toDouble()
        val low = expected * (1.0 - TOLERANCE_FRACTION)
        val high = expected * (1.0 + TOLERANCE_FRACTION)

        val typeAudits = perTypeCounts.entries
            .sortedBy { it.key }
            .map { (type, counts) ->
                val (raw, converged) = counts
                val ratio = if (converged > 0) raw.toDouble() / converged.toDouble() else 0.0
                val typeStatus = when {
                    dumpUserCount < 2 || converged < MIN_SAMPLE_EVENTS -> MirrorAuditStatus.INDETERMINATE
                    ratio < low || ratio > high -> MirrorAuditStatus.ANOMALY
                    else -> MirrorAuditStatus.NORMAL
                }
                MirrorTypeAudit(
                    eventType = type,
                    rawCount = raw,
                    convergedCount = converged,
                    repetitionRatio = ratio,
                    status = typeStatus
                )
            }

        val anomalies = typeAudits.filter { it.status == MirrorAuditStatus.ANOMALY }
        val hasNormal = typeAudits.any { it.status == MirrorAuditStatus.NORMAL }
        val overallStatus = when {
            dumpUserCount < 2 -> MirrorAuditStatus.INDETERMINATE
            anomalies.isNotEmpty() -> MirrorAuditStatus.ANOMALY
            hasNormal -> MirrorAuditStatus.NORMAL
            else -> MirrorAuditStatus.INDETERMINATE
        }

        val warning = if (overallStatus == MirrorAuditStatus.ANOMALY) {
            val detail = anomalies.joinToString("，") { a ->
                String.format(
                    Locale.ROOT,
                    "%s %.2f×（%d→%d）",
                    a.eventType,
                    a.repetitionRatio,
                    a.rawCount,
                    a.convergedCount
                )
            }
            String.format(
                Locale.ROOT,
                "镜像事件重复率异常：%d账户分区预期≈%.1f×，异常项：%s，建议核对±1000ms收敛窗口与多账户事件输出",
                dumpUserCount,
                expected,
                detail
            )
        } else {
            null
        }

        return MirrorConvergenceReport(
            distinctUsers = dumpUserCount,
            rawMirrorEvents = totalRaw,
            convergedMirrorEvents = totalConverged,
            repetitionRatio = overallRatio,
            anomalyWarning = warning,
            status = overallStatus,
            byType = typeAudits
        )
    }

    /** 兼容单汇总口径入口（委托 [evaluateByType]）。 */
    fun evaluate(
        distinctUsers: Int,
        rawMirrorEvents: Int,
        convergedMirrorEvents: Int
    ): MirrorConvergenceReport = evaluateByType(
        dumpUserCount = distinctUsers,
        perTypeCounts = if (rawMirrorEvents == 0 && convergedMirrorEvents == 0) {
            emptyMap()
        } else {
            mapOf("ALL_MIRROR" to (rawMirrorEvents to convergedMirrorEvents))
        }
    )
}
