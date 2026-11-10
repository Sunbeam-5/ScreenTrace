package com.screentrace.app.data.parse

/**
 * §2.2（CR-01 C02）：事件类型归一化——AOSP 标准 checkin 可能输出数字类型，
 * 本机输出英文名；先归一化再处理。映射覆盖 §2.2 全部 16 项。
 */
object EventTypes {

    private val NUMERIC: Map<String, String> = mapOf(
        "1" to "ACTIVITY_RESUMED",
        "2" to "ACTIVITY_PAUSED",
        "5" to "CONFIGURATION_CHANGE",
        "7" to "USER_INTERACTION",
        "8" to "SHORTCUT_INVOCATION",
        "10" to "NOTIFICATION_SEEN",
        "11" to "STANDBY_BUCKET_CHANGED",
        "12" to "NOTIFICATION_INTERRUPTION",
        "15" to "SCREEN_INTERACTIVE",
        "16" to "SCREEN_NON_INTERACTIVE",
        "17" to "KEYGUARD_SHOWN",
        "18" to "KEYGUARD_HIDDEN",
        "19" to "FOREGROUND_SERVICE_START",
        "20" to "FOREGROUND_SERVICE_STOP",
        "23" to "ACTIVITY_STOPPED"
    )

    /** 已知标准名（16 项）。 */
    val KNOWN: Set<String> = NUMERIC.values.toSet()

    /** 数字/英文名 → 标准名；未知类型返回 null（解析时忽略该事件）。 */
    fun normalize(raw: String): String? = when {
        raw in NUMERIC -> NUMERIC.getValue(raw)
        raw in KNOWN -> raw
        else -> null
    }
}
