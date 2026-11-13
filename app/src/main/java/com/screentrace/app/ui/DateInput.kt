package com.screentrace.app.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/**
 * §5.6 / §7-25：自定义区间的手输日期解析（纯函数，便于单测）。
 *
 * 只接受 `年-月-日`；CR-09 验收 41 起同时接受 8 位纯数字（`20261113`，数字键盘
 * 上用户不会输 `-`）。用 STRICT 解析，`2026-02-30`、`2026-13-01` 判非法而不是
 * 被 Java 悄悄进位成 3 月 2 日。
 */
internal object DateInput {

    private val FORMAT = DateTimeFormatter
        .ofPattern("uuuu-M-d")
        .withResolverStyle(ResolverStyle.STRICT)

    /**
     * CR-09 验收 41：**显示掩码**（[VisualTransformation]）。
     *
     * 字段真实文本保持用户输入的数字流（`20261113`），只在展示层补 `-`——
     * 不改字段文本就没有「格式化后的文本回灌 → 光标错位 → 后续输入插错位置」
     * 的经典问题（实测 `12345678` 会被显示层重排成 `1234-67-85`，见交付说明）。
     * 字段文本已含 `-`（日历回填的旧格式）时原样透传。
     */
    val mask: VisualTransformation = VisualTransformation { text ->
        val (masked, dashPositions) = maskDigits(text.text)
        TransformedText(
            AnnotatedString(masked),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int {
                    var result = offset
                    dashPositions.forEach { if (it < offset) result++ }
                    return result
                }

                override fun transformedToOriginal(offset: Int): Int {
                    var result = offset
                    dashPositions.forEach { if (it < offset) result-- }
                    return result
                }
            }
        )
    }

    /**
     * 掩码核心（纯函数，JVM 可测）：数字流 → `yyyy-MM-dd` 展示文本 + 分隔符插入位置。
     * 已含 `-` 的文本原样透传（日历回填兼容）；中间态（不足位数）只补已确定的分隔符。
     */
    internal fun maskDigits(raw: String): Pair<String, Set<Int>> {
        if (raw.contains('-')) return raw to emptySet()
        val digits = raw.filter { it.isDigit() }
        val masked = buildString {
            append(digits.take(4))
            if (digits.length >= 4) {
                append('-')
                append(digits.substring(4, minOf(6, digits.length)))
            }
            if (digits.length > 6) {
                append('-')
                append(digits.substring(6, minOf(8, digits.length)))
            }
        }
        val dashPositions = buildSet {
            if (digits.length >= 4) add(4)
            if (digits.length > 6) add(7)
        }
        return masked to dashPositions
    }

    /** 允许完整的 ISO 输入，也允许中间态（空串 / 正在输入）与 8 位纯数字；非法一律返回 null。 */
    fun parse(text: String): LocalDate? {
        val trimmed = normalize(text)
        if (trimmed.isEmpty()) return null
        return try {
            LocalDate.parse(trimmed, FORMAT)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /**
     * 8 位纯数字（无 `-`）补成掩码形；其余原样返回（含正在输入的中间态）。
     * 数字键盘上一位数月/日必须补 0（`20260105` → 2026-01-05）。
     */
    internal fun normalize(text: String): String {
        val trimmed = text.trim()
        if (trimmed.contains('-')) return trimmed
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length != 8 || digits.length != trimmed.length) return trimmed
        return "${digits.substring(0, 4)}-${digits.substring(4, 6)}-${digits.substring(6, 8)}"
    }

    /** 输入框下方的即时校验文案；合法或空串返回空串。 */
    fun error(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || parse(trimmed) != null) return ""
        // CR-09：还没输满 8 位数字是正在输入的中间态（如 202611），不打扰；
        // 输满了仍是非法的才提示（区分「格式错」和「日期不存在」）。
        if (trimmed.count { it.isDigit() } < 8) return ""
        return if (!SHAPE.matches(normalize(trimmed))) {
            "按 年-月-日 输入，例如 2026-11-11"
        } else {
            "这个日期不存在，请检查月份和日期"
        }
    }

    /** 日历 → 输入框的回填写**数字流**（与显示掩码约定一致，字段文本永远不含 `-`）。 */
    fun format(date: LocalDate): String {
        val m = date.monthValue
        val d = date.dayOfMonth
        return "${date.year}${if (m < 10) "0$m" else "$m"}${if (d < 10) "0$d" else "$d"}"
    }

    /** 只校验形状（四位年 + 1~2 位月 + 1~2 位日），用于区分「格式错」和「日期不存在」。 */
    private val SHAPE = Regex("""\d{4}-\d{1,2}-\d{1,2}""")
}
