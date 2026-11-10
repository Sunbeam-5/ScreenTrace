package com.screentrace.app.contract

import java.time.LocalDate

/**
 * 需求文档 §4 的领域模型。Room entity 落库时 date 转成设备本地自然日的
 * "yyyy-MM-dd" 字符串；领域层统一用 LocalDate，禁止用 UTC 时间戳做日界。
 *
 * screenUnlockMin 是 §3 的对外口径（亮屏减去锁屏重叠，sub_kg），
 * screenMin 是未扣减的原始亮屏分钟，两者不要混用。
 */
data class DaySummary(
    val date: LocalDate,
    val screenMin: Int,
    val screenUnlockMin: Int,
    val unlocks: Int,
    val launches: Int,
    val longestSessionMin: Int,
    val pulledAt: Long
)

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
