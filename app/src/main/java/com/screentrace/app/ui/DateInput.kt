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
 * CR-15 A0「分段智能输入」：真实文本始终是 **4-2-2 数字流**（最多 8 位），由
 * [normalizeInput] 在 `onValueChange` 里规范化（月/日首位过大时自动补 0）；
 * 显示层 [mask] 只补 `-`，不改字段文本，避免格式化回灌造成的光标错位。
 *
 * 两条真机崩溃的防线都在这里：
 * - `OffsetMapping` 越界（`9 -> 11 is not in range [0,10]`）→ 硬截 8 位 + 映射钳制；
 * - M3 日历年份范围（`year (1234) out of 1900..2100`）→ [parse] 与 [MIN_YEAR]/[MAX_YEAR] 守卫。
 */
internal object DateInput {

    /** M3 DatePicker / DateRangePicker 只接受 1900..2100。 */
    const val MIN_YEAR = 1900
    const val MAX_YEAR = 2100

    private val FORMAT = DateTimeFormatter
        .ofPattern("uuuu-M-d")
        .withResolverStyle(ResolverStyle.STRICT)

    /**
     * CR-09 起用「显示掩码」；CR-15 A0 重写偏移映射。
     *
     * 旧公式 `dashPositions.forEach { if (it < offset) result++ }` 差一位：输满 4 位年时
     * 光标停在 `-` **前面**（负责人反馈「竖杠没有在后面」），且输入第 9 位时映射值
     * 超出 transformed 长度，Compose 直接抛 IllegalStateException 闪退。
     * 现在按「分隔符插在原始 4 / 6 位之后」直接算，并**双向钳制到合法区间**。
     */
    val mask: VisualTransformation = VisualTransformation { text ->
        val raw = text.text
        val masked = maskDigits(raw).first
        TransformedText(
            AnnotatedString(masked),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int =
                    mapToTransformed(raw, masked.length, offset)

                override fun transformedToOriginal(offset: Int): Int =
                    mapToOriginal(raw, offset)
            }
        )
    }

    /**
     * 原始光标 → 显示光标（纯函数，JVM 可测）。分隔符插在原始 4 / 6 位之后，
     * **必须钳制**到 `[0, maskedLength]`：Compose 对越界映射直接抛异常。
     */
    internal fun mapToTransformed(raw: String, maskedLength: Int, offset: Int): Int {
        if (raw.contains('-')) return offset.coerceIn(0, maskedLength)
        val len = raw.filter { it.isDigit() }.take(8).length
        var t = offset
        if (len >= 4 && offset >= 4) t++
        if (len >= 6 && offset >= 6) t++
        return t.coerceIn(0, maskedLength)
    }

    /** 显示光标 → 原始光标；同样钳制到 `[0, raw.length]`。 */
    internal fun mapToOriginal(raw: String, offset: Int): Int {
        if (raw.contains('-')) return offset.coerceIn(0, raw.length)
        val len = raw.filter { it.isDigit() }.take(8).length
        var o = offset
        if (len >= 4 && offset > 4) o--
        if (len >= 6 && offset > 7) o--
        return o.coerceIn(0, raw.length)
    }

    /**
     * 掩码核心（纯函数，JVM 可测）：数字流 → `yyyy-MM-dd` 展示文本 + 分隔符位置。
     * 已含 `-` 的文本原样透传；CR-15 A0 起**月输满（6 位）即给第二个 `-`**，
     * 作为「下一段开始」的可见提醒。
     */
    internal fun maskDigits(raw: String): Pair<String, Set<Int>> {
        if (raw.contains('-')) return raw to emptySet()
        val digits = raw.filter { it.isDigit() }.take(8)
        val masked = buildString {
            append(digits.take(4))
            if (digits.length >= 4) {
                append('-')
                append(digits.substring(4, minOf(6, digits.length)))
            }
            if (digits.length >= 6) {
                append('-')
                append(digits.substring(6, minOf(8, digits.length)))
            }
        }
        val dashPositions = buildSet {
            if (digits.length >= 4) add(4)
            if (digits.length >= 6) add(7)
        }
        return masked to dashPositions
    }

    /**
     * CR-15 A0：把用户输入规范化成 4-2-2 数字流，返回 `(文本, 新光标)`。
     * - 月首位 2–9 → 自动补 0 成 02–09，后续位让给日；首位 0/1 等第二位组成 10–12；
     * - 日首位 4–9 → 自动补 0；首位 0–3 等第二位；
     * - 粘贴带 `-` 的 ISO 日期直接转成规范数字流；
     * - 过滤非数字、**硬截 8 位**（越界映射崩溃的第一道防线）；
     * - 光标一律置末尾：自动补 0 是末尾插入，逐位输入不会插错位置。
     */
    internal fun normalizeInput(raw: String, cursor: Int = raw.length): Pair<String, Int> {
        if (raw.contains('-')) {
            val parsed = runCatching { LocalDate.parse(raw.trim(), FORMAT) }.getOrNull()
            if (parsed != null && parsed.year in MIN_YEAR..MAX_YEAR) {
                val iso = format(parsed)
                return iso to iso.length
            }
        }
        val digits = raw.filter { it.isDigit() }.take(8)
        if (digits.isEmpty()) return "" to 0
        val year = digits.take(4)
        val afterYear = digits.drop(4)
        val (month, afterMonth) = when {
            afterYear.isEmpty() -> "" to ""
            afterYear[0] in '2'..'9' -> "0${afterYear[0]}" to afterYear.drop(1)
            else -> afterYear.take(2) to afterYear.drop(2)
        }
        val day = when {
            afterMonth.isEmpty() -> ""
            afterMonth[0] in '4'..'9' -> "0${afterMonth[0]}"
            else -> afterMonth.take(2)
        }
        val out = (year + month + day).take(8)
        return out to out.length
    }

    /** 允许完整 ISO 输入，也允许中间态与 8 位纯数字；非法/超范围一律返回 null。 */
    fun parse(text: String): LocalDate? {
        val trimmed = normalize(text)
        if (trimmed.isEmpty()) return null
        return try {
            val date = LocalDate.parse(trimmed, FORMAT)
            if (date.year in MIN_YEAR..MAX_YEAR) date else null
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /** 8 位纯数字（无 `-`）补成掩码形；其余原样返回（含正在输入的中间态）。 */
    internal fun normalize(text: String): String {
        val trimmed = text.trim()
        if (trimmed.contains('-')) return trimmed
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length != 8 || digits.length != trimmed.length) return trimmed
        return "${digits.substring(0, 4)}-${digits.substring(4, 6)}-${digits.substring(6, 8)}"
    }

    /** 分段即时校验：年 / 月 / 完整日期逐级提示，未输完的中间态不打扰。 */
    fun error(text: String): String {
        val digits = text.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        if (digits.length >= 4) {
            val year = digits.take(4).toIntOrNull()
            if (year != null && year !in MIN_YEAR..MAX_YEAR) {
                return "年份需在 $MIN_YEAR–$MAX_YEAR 之间"
            }
        }
        if (digits.length >= 6) {
            val month = digits.substring(4, 6).toIntOrNull()
            if (month == null || month !in 1..12) return "月份需在 01–12 之间"
        }
        if (digits.length >= 8 && parse(text) == null) {
            return if (!SHAPE.matches(normalize(text))) {
                "按 年-月-日 输入，例如 2026-11-11"
            } else {
                "这个日期不存在，请检查月份和日期"
            }
        }
        return ""
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
