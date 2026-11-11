package com.screentrace.app.data.pull

import java.util.Locale

/**
 * 一份 dump 文本的「形态」判定。
 *
 * **为什么需要它**：`dumpsys usagestats` 在两种 ROM 上给出的东西完全不同，而
 * 「喂错格式」的失败方式是**静默产出似真数据**（正则扫不到就当没有，最后得到
 * 「几天 + 一堆可疑数值」），比直接报错危险得多。
 *
 * 真机实测（小米 2407FRK8EC / Android 16 / HyperOS 3）：
 * - `dumpsys usagestats`（不带参数）→ 4.17MB 完整明文，含
 *   `Last 24 hour events` / `In-memory daily stats` / 4026 条 `ACTIVITY_RESUMED`
 * - `dumpsys usagestats --checkin` → 561KB，**只有元信息**，`totalTimeUsed` 0 条
 *
 * 也就是说这台 ROM 上 `--checkin` 并没有被「忽略」，而是走了一个只吐元信息的分支；
 * 真正可用的数据在**不带参数的明文**里。所以判定必须能区分三件事：
 * 1. 这份文本是 CSV（checkin）还是明文；
 * 2. 明文里**到底有没有使用数据**（561KB 那份明文一个 `ACTIVITY_RESUMED` 都没有）；
 * 3. 空输出。
 *
 * 判定结果要写进 `pull_log.note`：用户报「数据不对」时，第一件事就是看这次拿到了什么。
 */
internal data class DumpShape(
    val bytes: Int,
    val kind: Kind,
    /** 文本里是否含可用的使用数据（事件 / In-memory 包时长）。 */
    val hasUsageData: Boolean
) {

    enum class Kind {
        /** 空输出（进程被杀 / shell 被拒）。 */
        EMPTY,

        /** 机器可读的 checkin CSV（含 Days / 区间 / 事件）。 */
        CHECKIN,

        /** 人类可读的明文 dump。 */
        PLAIN,

        /** 既不像 checkin 也不像明文（ROM 改版、报错文本等）。 */
        UNKNOWN
    }

    val looksLikeCheckin: Boolean get() = kind == Kind.CHECKIN

    /** 明文且确实带数据——只有这种才能当解析主输入。 */
    val usableAsPlain: Boolean get() = kind == Kind.PLAIN && hasUsageData

    /** 日志/`pull_log.note` 用的一句话，例如 `4.0MB/plain+data`。 */
    val summary: String
        get() = String.format(
            Locale.ROOT,
            "%.1fMB/%s%s",
            bytes / 1024.0 / 1024.0,
            kind.name.lowercase(Locale.ROOT),
            if (hasUsageData) "+data" else "-nodata"
        )

    companion object {

        /**
         * checkin CSV **独有**的特征。
         *
         * 踩过的坑：早先这里用 `totalTimeUsed=` 和 `screen-interactive:` 判 checkin，
         * 而明文里两者都在——明文的写法是 `totalTimeUsed="23:06:08"`、
         * `screen-interactive: 1x for "00:00"`。于是 4.17MB 完整明文被判成 CHECKIN，
         * 喂给 CSV 解析器后一行都匹配不上，最后以「0 天」收场（或更糟：成似真数据）。
         *
         * 所以特征必须是**带格式细节**的：
         * - `Days:` 只在 CSV 里作为行头出现；
         * - `beginTime=<数字>` 是 CSV 的区间头，明文用 `timeRange="…"`；
         * - `screen-interactive: Nx for N`（**无引号、纯数字毫秒**）也是 CSV 独有。
         */
        private val CHECKIN_MARKERS = listOf(
            Regex("""(^|\n)Days:"""),
            Regex("""beginTime=\d+"""),
            Regex("""screen-interactive: \d+x for \d+\s*$""", RegexOption.MULTILINE)
        )

        /** 明文 dump 独有的段落头。 */
        private val PLAIN_MARKERS = listOf(
            "App Standby States",
            "Broadcast response stats:",
            "CountQuotaTracker:",
            "In-memory daily stats",
            "Last 24 hour events"
        )

        /** 明文里「真的带了数据」的证据：事件流或 In-memory 包时长。 */
        private val USAGE_DATA_MARKERS = listOf(
            "In-memory daily stats",
            "type=ACTIVITY_RESUMED",
            "type=SCREEN_INTERACTIVE",
            "type=KEYGUARD_HIDDEN",
            "type=NOTIFICATION_INTERRUPTION"
        )

        fun of(text: String): DumpShape {
            val hasData = USAGE_DATA_MARKERS.any(text::contains)
            return DumpShape(
                bytes = text.length,
                kind = when {
                    text.isBlank() -> Kind.EMPTY
                    CHECKIN_MARKERS.any { it.containsMatchIn(text) } -> Kind.CHECKIN
                    PLAIN_MARKERS.any(text::contains) -> Kind.PLAIN
                    else -> Kind.UNKNOWN
                },
                hasUsageData = hasData
            )
        }
    }
}
