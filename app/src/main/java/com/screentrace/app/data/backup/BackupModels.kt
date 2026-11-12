package com.screentrace.app.data.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * §5.10：JSON 备份结构（schemaVersion 首版 **4**）。
 *
 * 版本演进（文档 §4「备份范围」）：
 * - v1 = 原四表；
 * - v2 = 增事件表（unlock_event / notification_event）；
 * - v3 = 增会话区间（screen_session）；
 * - v4 = 增应用限额（app_limit），settings 增超时提醒开关与自定义主题种子色。
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
    @SerialName("app_limit") val appLimit: List<AppLimitDto> = emptyList()
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
    val pulledAt: Long
)

@Serializable
data class AppUsageDto(
    val date: String,
    val pkg: String,
    val name: String,
    val minutes: Int,
    val launches: Int,
    val category: String
)

@Serializable
data class HourUsageDto(val date: String, val hour: Int, val minutes: Int)

@Serializable
data class PullLogDto(val pulledAt: Long, val source: String, val ok: Boolean, val note: String? = null)

@Serializable
data class UnlockEventDto(val occurredAtMs: Long)

@Serializable
data class NotificationEventDto(
    val occurredAtMs: Long,
    val type: String,
    val pkg: String,
    val channelId: String? = null
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
    val missingTables: List<String>
) {
    /** 「11月1日 – 11月12日 · 8 天」这种一句话范围；无数据时明确说没有。 */
    val rangeText: String
        get() = if (firstDate == null || lastDate == null) "无日期数据"
        else "$firstDate – $lastDate · $dayCount 天"
}

/** 备份编解码（§5.10 第 1 步：解析失败/缺版本/版本过高分别给明确错误）。 */
object BackupCodec {

    /** 当前 App 能写出的最高备份版本。 */
    const val SCHEMA_VERSION = 4

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
            missingTables = missing
        )
    }

    fun fileName(at: java.time.LocalDateTime): String =
        "screen-time-backup-" + at.format(
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
        ) + ".json"
}
