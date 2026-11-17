package com.screentrace.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * §4 六表 schema（CR-01 修正版红线：原四表原字段不得删除或改义，事件表只增不改）。
 * date 一律为设备本地自然日的 `yyyy-MM-dd` 字符串（§3-4）；时间戳为毫秒。
 *
 * CR-28：六表加 `sourceDeviceId` 来源维度，事件表主键再加确定性 `seq`。
 */

/**
 * CR-28：来源标识的保留值（写死的哨兵，永不与真实设备 ID 冲突）。
 *
 * - [LEGACY]：历史来源未知（迁移归档的混合总量）——**只读**，普通拉取/合并/重算
 *   不得改写（需求 §4「来源维度与历史保全」）。
 * - [UNARCHIVED]：`MIGRATION_6_7` 搬行后的「未归档」临时态，只由
 *   [com.screentrace.app.data.SourceArchive] 首启归档消化，正常写路径永不使用。
 * - [UNSPECIFIED]：实体构造默认值——测试与旧调用点的「某台设备」；生产写路径
 *   必须显式传真实设备 ID（本机 `deviceId` / 远端设备 ID）。
 */
object SourceIds {
    const val LEGACY = "__legacy__"
    const val UNARCHIVED = ""
    const val UNSPECIFIED = "__local__"
}

@Entity(
    tableName = "day_summary",
    primaryKeys = ["date", "sourceDeviceId"],
    // CR-28：索引声明必须与 MIGRATION_6_7 建的逐字一致（真机踩过：迁移建了索引、
    // 实体没声明 → Room schema 校验炸、App 起不来）
    indices = [Index("sourceDeviceId")]
)
data class DaySummaryEntity(
    val date: String,
    val screenMin: Int,
    val screenUnlockMin: Int,
    val unlocks: Int,
    val launches: Int,
    val longestSessionMin: Int,
    val pulledAt: Long,
    /** CR-28：来源设备维度（见 [SourceIds]）。 */
    val sourceDeviceId: String = SourceIds.UNSPECIFIED
)

@Entity(
    tableName = "app_usage",
    primaryKeys = ["date", "pkg", "sourceDeviceId"],
    indices = [Index("pkg"), Index("sourceDeviceId")]
)
data class AppUsageEntity(
    val date: String,
    val pkg: String,
    val name: String,
    val minutes: Int,
    val launches: Int,
    val category: String,
    val sourceDeviceId: String = SourceIds.UNSPECIFIED
)

@Entity(
    tableName = "hour_usage",
    primaryKeys = ["date", "hour", "sourceDeviceId"],
    indices = [Index("sourceDeviceId")]
)
data class HourUsageEntity(
    val date: String,
    val hour: Int,
    val minutes: Int,
    val sourceDeviceId: String = SourceIds.UNSPECIFIED
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
 * §4 unlock_event（CR-01，只增不改；CR-28 主键加 `sourceDeviceId` 与 `seq`）。
 *
 * CR-28 修一个隐性缺陷：旧 PK 只有 `occurredAtMs`，同毫秒的第二条真实解锁会被
 * `INSERT OR IGNORE` 吞掉（与 §3-8「同账户快速重复解锁保留」的解析口径打架）。
 * 新 PK = (occurredAtMs, sourceDeviceId, seq)：`seq` 为**来源内**按解析器输出顺序
 * 的确定性序号（[EventSeq]）——同毫秒多条各占一行，同一份输入重放得同一组键、
 * 重复拉取幂等；跨来源同毫秒天然互不吞。
 */
@Entity(
    tableName = "unlock_event",
    primaryKeys = ["occurredAtMs", "sourceDeviceId", "seq"]
)
data class UnlockEventEntity(
    val occurredAtMs: Long,
    val sourceDeviceId: String = SourceIds.UNSPECIFIED,
    val seq: Int = 0
)

/**
 * §4 notification_event（CR-01，只增不改；CR-28 主键加 `sourceDeviceId` 与 `seq`）。
 *
 * 旧 PK 不含 channelId——同毫秒同包同类型的两条记录会被 `INSERT OR IGNORE` 吞掉
 * 后者（复审意见自陈），与 CR-01 红线「同秒多条真实通知不丢」打架；CR-28 用
 * 确定性 `seq` 补全：PK = (occurredAtMs, type, pkg, sourceDeviceId, seq)。
 * channel_id 缺失为 NULL；按时间/包名建索引（CR-01 事件表索引要求）。
 */
@Entity(
    tableName = "notification_event",
    primaryKeys = ["occurredAtMs", "type", "pkg", "sourceDeviceId", "seq"],
    indices = [Index("pkg"), Index("occurredAtMs")]
)
data class NotificationEventEntity(
    val occurredAtMs: Long,
    val type: String,
    val pkg: String,
    val channelId: String?,
    val sourceDeviceId: String = SourceIds.UNSPECIFIED,
    val seq: Int = 0
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
 * screenUnlockMin = 亮屏并集 − 锁屏并集的重叠。PK (startMs, kind, sourceDeviceId)：
 * 入库前已做**同来源**扫线合并（同 kind 区间互不重叠），同毫秒跨 kind 不冲突；
 * CR-28：不同来源的完全重叠区间各存一行、互不覆盖，跨来源区间永不合并
 * （合并会破坏「占用时钟时长（UNION）」的同时性信息）。
 */
@Entity(
    tableName = "screen_session",
    primaryKeys = ["startMs", "kind", "sourceDeviceId"],
    indices = [Index("endMs"), Index("sourceDeviceId")]
)
data class ScreenSessionEntity(
    val startMs: Long,
    val endMs: Long,
    val kind: String,
    val sourceDeviceId: String = SourceIds.UNSPECIFIED
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

/**
 * §5.10 CR-27：跨设备合并的**防重复记账**（与数据写入同一事务）。
 *
 * 旧实现把账本放 DataStore、在 Room 事务**之后**才写，两步之间进程被杀就会
 * 「数据已加、账没记」→ 下次合并把同一批日期**再累加一遍**（当天翻倍）。
 * 迁进 Room 后与 `mergeAccumulate` 同事务：先查未合并日期、再累加、最后写账本，
 * 三步一荣俱荣一损俱损。
 *
 * PK = (`deviceId`, `date`)：同一来源设备的每一天各占一行，天然「整集合保留」
 * ——CR-21 补记踩过「同键覆盖丢日期」的坑，这里用联合主键从结构上堵死。
 *
 * **不进备份 JSON**（与旧 DataStore 账本一致）：它是本机的合并流水，不是用户数据。
 */
@Entity(tableName = "device_merge_ledger", primaryKeys = ["deviceId", "date"])
data class DeviceMergeLedgerEntity(
    val deviceId: String,
    val date: String
)

/**
 * CR-28 批次 D：历史重建流水（`rebuild_log`，独立表——重建是修复动作、拉取是采集
 * 流水，语义不同不并入 `pull_log`；三小点默认裁决之一）。
 *
 * 每次「重建历史来源」一行：重建范围（日期区间 + 涉及来源 + 快照路径）、
 * 前后行数、核对值（SUM/MAX/UNION）、是否回滚。快照文件是审计与人工恢复的依据。
 */
@Entity(tableName = "rebuild_log")
data class RebuildLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateRangeStart: String,
    val dateRangeEnd: String,
    val snapshotPath: String,
    val touchedSources: String,
    val rowsBefore: Int,
    val rowsAfter: Int,
    val checkSumMin: Int,
    val checkUnionMin: Int,
    val checkMaxMin: Int,
    val rolledBack: Boolean,
    val createdAtMs: Long
)
