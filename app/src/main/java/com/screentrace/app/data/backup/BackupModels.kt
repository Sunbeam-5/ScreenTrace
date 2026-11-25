package com.screentrace.app.data.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * §5.10：JSON 备份结构（当前 schemaVersion **5**）。
 *
 * 版本演进（文档 §4「备份范围」）：
 * - v1 = 原四表；
 * - v2 = 增事件表（unlock_event / notification_event）；
 * - v3 = 增会话区间（screen_session）；
 * - v4 = 增应用限额（app_limit），settings 增超时提醒开关与自定义主题种子色；
 * - v5 = 顶层增 `device` 设备标识（CR-15 ③ 批 C）。旧备份缺该字段时归「未知设备」，
 *   恢复流程绝不把备份里的 id 写回本机身份。
 * - v6 = 保留设备分量（CR-28）：顶层六表 = 备份导出设备自己的分量，新增 `sources[]`
 *   存其他来源分量（含 `'__legacy__'`），事件条目增 `seq`。旧读取器 `ignoreUnknownKeys`
 *   跳过 `sources` —— 能解析 / 能展示（只见顶层）/ 不能正常导入（三层兼容）。
 *
 * 所有列表都给默认空值：恢复 v1 备份时文件里没有这些键，缺省即「该备份不含此表」，
 * 由 [BackupSummary.missingTables] 逐条提示，而不是靠解析失败去猜。
 */
@Serializable
data class BackupFile(
    val schemaVersion: Int,
    val exportedAt: String,
    val appVersion: String,
    val settings: BackupSettings? = null,
    @SerialName("day_summary") val daySummary: List<DaySummaryDto> = emptyList(),
    @SerialName("app_usage") val appUsage: List<AppUsageDto> = emptyList(),
    @SerialName("hour_usage") val hourUsage: List<HourUsageDto> = emptyList(),
    @SerialName("pull_log") val pullLog: List<PullLogDto> = emptyList(),
    @SerialName("unlock_event") val unlockEvent: List<UnlockEventDto> = emptyList(),
    @SerialName("notification_event") val notificationEvent: List<NotificationEventDto> = emptyList(),
    @SerialName("screen_session") val screenSession: List<ScreenSessionDto> = emptyList(),
    @SerialName("app_limit") val appLimit: List<AppLimitDto> = emptyList(),
    /** v5 新增；放在末位且带默认值，旧备份与既有构造点零改动兼容。 */
    val device: BackupDevice? = null,
    /**
     * CR-28（v6 新增）：其他来源的分量（含 `'__legacy__'`＝历史来源未知项）。
     *
     * 顶层六表 = **备份导出设备**（`device.id`）自己的分量；`sources[]` 每项带自己的
     * `device`。导入派生：顶层行归 `device.id`（无 → `'__legacy__'`，绝不归导入设备）、
     * `sources[]` 各归各的 `device.id`。旧读取器 `ignoreUnknownKeys` 跳过本字段——
     * **能解析、能展示（只见顶层）、不能正常导入**（三层兼容，导出时须提示）。
     */
    val sources: List<BackupSource> = emptyList(),
    /** CR-30-R11（v7 新增）：独立屏幕证据（E3 锚点 / W1 窗口读数，永不进 screen_session）。 */
    @SerialName("screen_evidence") val screenEvidence: List<ScreenEvidenceDto> = emptyList(),
    /** CR-30-R11（v7 新增）：跨日窗口级应用聚合（不混入单日 app_usage）。 */
    @SerialName("usage_window") val usageWindow: List<UsageWindowDto> = emptyList()
)

/**
 * CR-28（v6）：一个非导出设备来源的分量（`'__legacy__'` 也用它表达）。
 * 不含 pull_log / app_limit / settings——那些是导出设备本机的，不随来源搬运。
 */
@Serializable
data class BackupSource(
    val device: BackupDevice? = null,
    @SerialName("day_summary") val daySummary: List<DaySummaryDto> = emptyList(),
    @SerialName("app_usage") val appUsage: List<AppUsageDto> = emptyList(),
    @SerialName("hour_usage") val hourUsage: List<HourUsageDto> = emptyList(),
    @SerialName("screen_session") val screenSession: List<ScreenSessionDto> = emptyList(),
    @SerialName("unlock_event") val unlockEvent: List<UnlockEventDto> = emptyList(),
    @SerialName("notification_event") val notificationEvent: List<NotificationEventDto> = emptyList(),
    /** CR-30-R11（v7 新增）：该来源分量的独立屏幕证据。 */
    @SerialName("screen_evidence") val screenEvidence: List<ScreenEvidenceDto> = emptyList(),
    /** CR-30-R11（v7 新增）：该来源分量的跨日窗口级应用聚合。 */
    @SerialName("usage_window") val usageWindow: List<UsageWindowDto> = emptyList()
)

/** §5.10 批 C：备份的来源设备。只用于展示，不参与恢复时的本机身份写入。 */
@Serializable
data class BackupDevice(
    val id: String,
    val nickname: String = "",
    val model: String = ""
)

/** §5.10：备份里的设置。默认不导入，用户勾选「同时恢复设置」才生效。 */
@Serializable
data class BackupSettings(
    val dailyGoalMin: Int? = null,
    val lowThresholdMin: Int? = null,
    val themeVariant: String? = null,
    val darkTheme: Boolean? = null,
    val dynamicColor: Boolean? = null,
    val customThemeSeed: Long? = null,
    val pullFrequency: String? = null,
    /** `pkg -> AppCategory.name`。 */
    val categoryOverrides: Map<String, String> = emptyMap(),
    /** §5.12 CR-04：目标超时提醒开关（批 11 落地后才有真实写入方）。 */
    val alertEnabled: Boolean? = null
)

@Serializable
data class DaySummaryDto(
    val date: String,
    val screenMin: Int,
    val screenUnlockMin: Int,
    val unlocks: Int,
    val launches: Int,
    val longestSessionMin: Int,
    val pulledAt: Long,
    /** CR-30-R11（v7 新增）：屏幕指标数据质量（缺省 null = UNKNOWN）。 */
    val screenQuality: String? = null
)

@Serializable
data class AppUsageDto(
    val date: String,
    val pkg: String,
    val name: String,
    val minutes: Int,
    val launches: Int,
    val category: String,
    /** CR-30-R11（v7 新增）：应用时长数据质量（缺省 null = UNKNOWN）。 */
    val minutesQuality: String? = null
)

@Serializable
data class HourUsageDto(
    val date: String,
    val hour: Int,
    val minutes: Int,
    /** CR-30-R11（v7 新增）：小时桶数据质量（缺省 null = UNKNOWN）。 */
    val quality: String? = null
)

@Serializable
data class PullLogDto(
    val pulledAt: Long,
    val source: String,
    val ok: Boolean,
    val note: String? = null,
    /** CR-30-R11（v7 新增）：采集状态（缺省 null = UNKNOWN）。 */
    val collectionStatus: String? = null
)

@Serializable
data class ScreenEvidenceDto(
    val startMs: Long,
    val endMs: Long? = null,
    val kind: String,
    val evidenceType: String,
    val quality: String,
    val measuredMs: Long? = null,
    val sourceDumpAt: Long
)

@Serializable
data class UsageWindowDto(
    val startMs: Long,
    val endMs: Long,
    val pkg: String,
    val name: String,
    val minutes: Int,
    val launches: Int,
    val category: String
)

@Serializable
data class UnlockEventDto(val occurredAtMs: Long, /** CR-28：确定性序号（同毫秒多条不丢 + 重放幂等）。 */ val seq: Int = 0)

@Serializable
data class NotificationEventDto(
    val occurredAtMs: Long,
    val type: String,
    val pkg: String,
    val channelId: String? = null,
    /** CR-28：确定性序号（同毫秒同包同类型多条不丢 + 重放幂等）。 */
    val seq: Int = 0
)

@Serializable
data class ScreenSessionDto(val startMs: Long, val endMs: Long, val kind: String)

@Serializable
data class AppLimitDto(val pkg: String, val limitMin: Int, val enabled: Boolean)

/** 备份摘要（§5.10 第 2 步：恢复前展示，用户据此决定覆盖还是合并）。 */
data class BackupSummary(
    val exportedAt: String,
    val appVersion: String,
    val schemaVersion: Int,
    val firstDate: String?,
    val lastDate: String?,
    val dayCount: Int,
    val appRows: Int,
    val hourRows: Int,
    val pullRows: Int,
    val unlockRows: Int,
    val notificationRows: Int,
    val sessionRows: Int,
    val limitRows: Int,
    val hasSettings: Boolean,
    /** 旧备份缺的表（逐条提示，不阻断导入）。 */
    val missingTables: List<String>,
    /** v5 起才有；null = 旧备份，UI 显示「未知设备」。 */
    val device: BackupDevice? = null,
    /**
     * CR-28（v6）：`sources[]` 里的来源设备 ID（含 `'__legacy__'`）。
     * 非空 = 该备份含多设备分量，旧版本导入会丢失非本机来源（三层兼容提示）。
     */
    val sourceDevices: List<String> = emptyList(),
    /** CR-30-R11（v7）：独立屏幕证据条数。 */
    val evidenceRows: Int = 0,
    /** CR-30-R11（v7）：跨日窗口级应用记录条数。 */
    val windowRows: Int = 0
) {
    /** 「11月1日 – 11月12日 · 8 天」这种一句话范围；无数据时明确说没有。 */
    val rangeText: String
        get() = if (firstDate == null || lastDate == null) "无日期数据"
        else "$firstDate – $lastDate · $dayCount 天"
}

/** 备份编解码（§5.10 第 1 步：解析失败/缺版本/版本过高分别给明确错误）。 */
object BackupCodec {

    /** 当前 App 能写出的最高备份版本（v7：含质量元数据 + screen_evidence + usage_window）。 */
    const val SCHEMA_VERSION = 7

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val lenient = Json { ignoreUnknownKeys = true }

    fun encode(file: BackupFile): String = json.encodeToString(BackupFile.serializer(), file)

    sealed interface DecodeResult {
        data class Ok(val file: BackupFile, val summary: BackupSummary) : DecodeResult
        data class Invalid(val reason: String) : DecodeResult
    }

    fun decode(text: String): DecodeResult {
        // 先用 JsonObject 做「有没有 schemaVersion / 版本是否过高」的判定：
        // 直接反序列化时缺字段只会抛一句 SerializationException，用户看不懂
        val root = runCatching {
            lenient.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return DecodeResult.Invalid("不是有效的 JSON 文件")

        val versionElement = root["schemaVersion"]
            ?: return DecodeResult.Invalid("缺少 schemaVersion，可能不是屏迹的备份文件")
        val version = (versionElement as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.toIntOrNull()
            ?: return DecodeResult.Invalid("schemaVersion 不是整数，文件可能已损坏")

        if (version > SCHEMA_VERSION) {
            return DecodeResult.Invalid("备份版本 $version 高于当前 App 支持的 $SCHEMA_VERSION，请先升级 App")
        }
        if (version < 1) {
            return DecodeResult.Invalid("schemaVersion=$version 无效")
        }

        val file = runCatching {
            lenient.decodeFromString(BackupFile.serializer(), text)
        }.getOrNull() ?: return DecodeResult.Invalid("备份内容解析失败，文件可能已损坏")

        return DecodeResult.Ok(file, summarize(file))
    }

    fun summarize(file: BackupFile): BackupSummary {
        val dates = buildList {
            addAll(file.daySummary.map { it.date })
            addAll(file.appUsage.map { it.date })
            addAll(file.hourUsage.map { it.date })
        }
        val missing = buildList {
            if (file.schemaVersion < 2) add("事件明细（解锁 / 通知）")
            if (file.schemaVersion < 3) add("会话区间（恢复后亮屏时长沿用原值）")
            if (file.schemaVersion < 4) add("应用限额")
            if (file.schemaVersion < 5) add("设备标识（按未知设备处理）")
        }
        return BackupSummary(
            exportedAt = file.exportedAt,
            appVersion = file.appVersion,
            schemaVersion = file.schemaVersion,
            firstDate = dates.minOrNull(),
            lastDate = dates.maxOrNull(),
            dayCount = file.daySummary.size,
            appRows = file.appUsage.size,
            hourRows = file.hourUsage.size,
            pullRows = file.pullLog.size,
            unlockRows = file.unlockEvent.size,
            notificationRows = file.notificationEvent.size,
            sessionRows = file.screenSession.size,
            limitRows = file.appLimit.size,
            hasSettings = file.settings != null,
            missingTables = missing,
            device = file.device,
            sourceDevices = file.sources.map { it.device?.id ?: "__legacy__" },
            evidenceRows = file.screenEvidence.size + file.sources.sumOf { it.screenEvidence.size },
            windowRows = file.usageWindow.size + file.sources.sumOf { it.usageWindow.size }
        )
    }

    fun fileName(at: java.time.LocalDateTime): String =
        "screen-time-backup-" + at.format(
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
        ) + ".json"
}
