package com.screentrace.app.ui

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/**
 * §5.6 / §7-25：自定义区间的手输日期解析（纯函数，便于单测）。
 *
 * 只接受 `年-月-日`，月份和日期允许一位数（`2026-1-5` 合法）。用 STRICT 解析，
 * 这样 `2026-02-30`、`2026-13-01` 会被判为非法而不是被 Java 悄悄进位成 3 月 2 日。
 */
internal object DateInput {

    private val FORMAT = DateTimeFormatter
        .ofPattern("uuuu-M-d")
        .withResolverStyle(ResolverStyle.STRICT)

    /** 允许完整的 ISO 输入，也允许中间态（空串 / 正在输入）；非法一律返回 null。 */
    fun parse(text: String): LocalDate? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return try {
            LocalDate.parse(trimmed, FORMAT)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /** 输入框下方的即时校验文案；合法或空串返回空串。 */
    fun error(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || parse(trimmed) != null) return ""
        return if (!SHAPE.matches(trimmed)) {
            "按 年-月-日 输入，例如 2026-11-11"
        } else {
            "这个日期不存在，请检查月份和日期"
        }
    }

    fun format(date: LocalDate): String = date.toString()

    /** 只校验形状（四位年 + 1~2 位月 + 1~2 位日），用于区分「格式错」和「日期不存在」。 */
    private val SHAPE = Regex("""\d{4}-\d{1,2}-\d{1,2}""")
}
