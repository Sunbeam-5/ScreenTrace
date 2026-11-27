package com.screentrace.app.data.parse

import java.util.Locale

/**
 * §2.3 / §7-32：多账户系统镜像事件（SCREEN_* / KEYGUARD_*）跨账户收敛自检报告。
 *
 * 当设备存在多个活跃账户（如 user=0/666/999）时，系统级事件通常按账户数近似倍增（如 3 账户 ≈ 3×）。
 * 若实测重复率显著偏离账户数倍率，则在 `pull_log.note` 写入告警，供 OEM 差异矩阵修订。
 */
data class MirrorConvergenceReport(
    val distinctUsers: Int,
    val rawMirrorEvents: Int,
    val convergedMirrorEvents: Int,
    val repetitionRatio: Double,
    val anomalyWarning: String?
)

object MirrorConvergenceAudit {
    /** 最少收敛后样本数，避免极小样本（1–4 条事件）触发误报。 */
    const val MIN_SAMPLE_EVENTS = 5

    /** 允许偏离账户数倍率的容忍比例（±35%，如 3 账户允许 1.95× ~ 4.05×）。 */
    const val TOLERANCE_FRACTION = 0.35

    fun evaluate(
        distinctUsers: Int,
        rawMirrorEvents: Int,
        convergedMirrorEvents: Int
    ): MirrorConvergenceReport {
        val ratio = if (convergedMirrorEvents > 0) {
            rawMirrorEvents.toDouble() / convergedMirrorEvents.toDouble()
        } else {
            0.0
        }
        val warning = if (distinctUsers >= 2 && convergedMirrorEvents >= MIN_SAMPLE_EVENTS) {
            val expected = distinctUsers.toDouble()
            val low = expected * (1.0 - TOLERANCE_FRACTION)
            val high = expected * (1.0 + TOLERANCE_FRACTION)
            if (ratio < low || ratio > high) {
                String.format(
                    Locale.ROOT,
                    "镜像事件重复率异常：%d账户实测%.2f×（原始%d→收敛%d，预期≈%.1f×），建议核对±1000ms收敛窗口",
                    distinctUsers,
                    ratio,
                    rawMirrorEvents,
                    convergedMirrorEvents,
                    expected
                )
            } else {
                null
            }
        } else {
            null
        }
        return MirrorConvergenceReport(
            distinctUsers = distinctUsers,
            rawMirrorEvents = rawMirrorEvents,
            convergedMirrorEvents = convergedMirrorEvents,
            repetitionRatio = ratio,
            anomalyWarning = warning
        )
    }
}
