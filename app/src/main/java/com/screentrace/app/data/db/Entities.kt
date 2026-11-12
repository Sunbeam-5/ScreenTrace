package com.screentrace.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * §4 六表 schema（CR-01 修正版红线：原四表原字段不得删除或改义，事件表只增不改）。
 * date 一律为设备本地自然日的 `yyyy-MM-dd` 字符串（§3-4）；时间戳为毫秒。
 */

@Entity(tableName = "day_summary")
data class DaySummaryEntity(
    @PrimaryKey val date: String,
    val screenMin: Int,
    val screenUnlockMin: Int,
    val unlocks: Int,
    val launches: Int,
    val longestSessionMin: Int,
    val pulledAt: Long
)

@Entity(tableName = "app_usage", primaryKeys = ["date", "pkg"])
data class AppUsageEntity(
    val date: String,
    val pkg: String,
    val name: String,
    val minutes: Int,
    val launches: Int,
    val category: String
)

@Entity(tableName = "hour_usage", primaryKeys = ["date", "hour"])
data class HourUsageEntity(
    val date: String,
    val hour: Int,
    val minutes: Int
)

@Entity(tableName = "pull_log")
data class PullLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pulledAt: Long,
    val source: String,
    val ok: Boolean,
    val note: String?
)

/**
 * §4 unlock_event（CR-01，只增不改）：occurred_at_ms 主键去重。
 * 注意（6a 评审 P2-4）：PK 只有毫秒。跨账户（user=0 / 666 隐私空间 / 999 分身）
 * 两个真实解锁若撞同一毫秒会折叠成一行——`day_summary.unlocks` 计 2 而事件表只有 1。
 * 解析器已把系统广播事件跨账户收敛（|Δt|≤1000ms），物理同毫秒概率极低；
 * 若后续对账出现「日计数 > 事件数」，再考虑 PK 加 user（DB version 已为 1，需迁移）。
 */
@Entity(tableName = "unlock_event")
data class UnlockEventEntity(
    @PrimaryKey val occurredAtMs: Long
)

/**
 * §4 notification_event（CR-01，只增不改）：
 * PK = (occurred_at_ms, type, pkg)；channel_id 缺失为 NULL。
 * 按时间/包名建索引（CR-01 事件表索引要求）。
 * 注意（复审意见）：PK 不含 channelId——同毫秒同包同类型的两条记录会被
 * `INSERT OR IGNORE` 吞掉后者；物理上同毫秒双通知概率极低，如需保留需扩展 PK。
 */
@Entity(
    tableName = "notification_event",
    primaryKeys = ["occurredAtMs", "type", "pkg"],
    indices = [Index("pkg"), Index("occurredAtMs")]
)
data class NotificationEventEntity(
    val occurredAtMs: Long,
    val type: String,
    val pkg: String,
    val channelId: String?
)

/**
 * 批 6g 三轮：屏幕亮/灭**会话区间**（跨拉取真并集的数据源，v2 新增表）。
 *
 * 背景：MIUI 的 usagestats 事件缓冲是**滚动窗口**（实测只保留最近几小时），单次
 * 拉取只看到窗口内的会话；「同日取大合并」只能保住历史最大值，救不回任何一次
 * 拉取都没覆盖到的时段（真机：凌晨 2:40 无拉取 → 亮屏少算 ≈2h40）。
 *
 * 解法：每次拉取把解析出的会话区间（SCREEN_INTERACTIVE→NON_INTERACTIVE 配对）
 * 原样入库，入库时与已存区间做**扫线并集**（重叠/相邻合并、含入折叠），日聚合
 * （screenMin/screenUnlockMin/longestSessionMin/小时矩阵）从并集重算后再与旧值
 * 取大——任何一次拉取见过的时段都永久入账，不再依赖单次缓冲的窗口。
 *
 * kind：`SCREEN`（亮屏会话）/ `KEYGUARD`（锁屏期间，从 KEYGUARD_SHOWN→HIDDEN 配对），
 * screenUnlockMin = 亮屏并集 − 锁屏并集的重叠。PK (startMs, kind)：入库前已做
 * 扫线合并（同 kind 区间互不重叠），同毫秒跨 kind 不冲突。
 */
@Entity(tableName = "screen_session", primaryKeys = ["startMs", "kind"], indices = [Index("endMs")])
data class ScreenSessionEntity(
    val startMs: Long,
    val endMs: Long,
    val kind: String
) {
    companion object {
        const val KIND_SCREEN = "SCREEN"
        const val KIND_KEYGUARD = "KEYGUARD"
    }
}

/**
 * §4 / §5.12 CR-04：应用每日时长限额（v5 新增表，批 8 第一步建表）。
 *
 * 限额是**用户意图**而不是统计结果，所以合并策略与其它表相反：从备份恢复时
 * 同 pkg 以备份值覆盖（§4），不是取大。
 *
 * 本批（8a）只建表与 DAO，限额的设置 UI 与到量提醒在批 11——先建表是因为
 * `schemaVersion=4` 的 JSON 备份要包含它，否则批 11 一上线备份结构就得再涨一版。
 */
@Entity(tableName = "app_limit")
data class AppLimitEntity(
    @PrimaryKey val pkg: String,
    /** 每日上限（分钟），15..1425，步进 15。 */
    val limitMin: Int,
    val enabled: Boolean
)
