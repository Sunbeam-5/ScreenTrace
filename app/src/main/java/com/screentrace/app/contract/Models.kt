package com.screentrace.app.contract

import java.time.LocalDate

/**
 * 需求文档 §4 的领域模型。Room entity 落库时 date 转成设备本地自然日的
 * "yyyy-MM-dd" 字符串；领域层统一用 LocalDate，禁止用 UTC 时间戳做日界。
 *
 * screenUnlockMin 是 §3 的对外口径（亮屏减去锁屏重叠，sub_kg），
 * screenMin 是未扣减的原始亮屏分钟，两者不要混用。
 */
/**
 * CR-30-R11（D-07 方案 C+）：日级派生数据质量（仅用于聚合/领域层，数据库永不写入 `'LEGACY'`）。
 */
enum class DayQuality {
    EXACT,
    LEGACY,
    PARTIAL,
    UNKNOWN;

    companion object {
        fun fromSql(raw: String?): DayQuality = when (raw) {
            "EXACT" -> EXACT
            "LEGACY" -> LEGACY
            "PARTIAL" -> PARTIAL
            else -> UNKNOWN
        }

        /** 兼容旧单测仅传行级 screenQuality 构造 DaySummary 的场景。 */
        fun fromSingleRowQuality(raw: String?): DayQuality = when (raw) {
            null -> LEGACY
            "EXACT" -> EXACT
            "PARTIAL" -> PARTIAL
            else -> UNKNOWN
        }
    }
}

data class DaySummary(
    val date: LocalDate,
    val screenMin: Int,
    val screenUnlockMin: Int,
    val unlocks: Int,
    val launches: Int,
    val longestSessionMin: Int,
    val pulledAt: Long,
    /** 兼容保留字段；D-07 方案 C+ 领域层统一以 [dayQuality] 为准。 */
    val screenQuality: String? = null,
    val dayQuality: DayQuality = DayQuality.fromSingleRowQuality(screenQuality)
) {
    /** D-07 方案 C+ 统计准入：完整日（EXACT）与互斥历史兼容日（LEGACY）可参与；PARTIAL/UNKNOWN 排除。 */
    val isCompleteForStats: Boolean
        get() = dayQuality == DayQuality.EXACT || dayQuality == DayQuality.LEGACY
}

data class AppUsage(
    val date: LocalDate,
    val pkg: String,
    val name: String,
    val minutes: Int,
    val launches: Int,
    val category: AppCategory
)

enum class AppCategory(val label: String) {
    VIDEO("视频娱乐"),
    SOCIAL("社交沟通"),
    AI("AI 助手"),
    TOOLS("工具效率"),
    GAME("游戏"),
    SHOPPING("购物"),
    OTHER("其他")
}

data class HourUsage(
    val date: LocalDate,
    val hour: Int,
    val minutes: Int
)

data class PullLog(
    val id: Long,
    val pulledAt: Long,
    val source: String,
    val ok: Boolean,
    val note: String?
)

/** §4 unlock_event 行（CR-01，只增不改）：解锁事件，毫秒时间戳主键。 */
data class UnlockEvent(val occurredAtMs: Long)

/** §4 notification_event 的 type 取值（CR-01）：打扰 / 已查看。 */
enum class NotificationType { INTERRUPTION, SEEN }

/**
 * §4 notification_event 行（CR-01，只增不改）：通知事件。
 * PK = (occurredAtMs, type, pkg)；channelId 缺失为 null。
 * 语义（§3）：INTERRUPTION = 产生视觉/声音打扰的通知（静音/静默渠道不计入）；
 * SEEN = 用户点开通知。二者无必然包含关系。
 */
data class NotificationEvent(
    val occurredAtMs: Long,
    val type: NotificationType,
    val pkg: String,
    val channelId: String?
)
