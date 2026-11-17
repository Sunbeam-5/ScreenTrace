package com.screentrace.app.data.db

/**
 * CR-28：事件行的确定性 `seq` 编号（同毫秒多条不丢 + 重复拉取幂等）。
 *
 * 规则（设计审查 CR-28 §1.3）：
 * - `seq` = **来源内**、按主键去掉 `seq` 后的分组、**列表顺序**（= 解析器输出顺序，
 *   解析器输出是稳定排序）的组内序号，从 0 起；
 * - 同一份输入 → 同一组 `(主键, seq)` → upsert/insertIgnore 重放幂等；
 * - 同毫秒同组多条 → seq 0、1、2… 各占一行，不被 `INSERT OR IGNORE` 吞掉。
 *
 * 已知取舍（设计审查 §8 待确认 2，默认方案）：同一设备**不同批次**的输入、
 * 同毫秒同组的两条不同事件可能都拿 `seq = 0`（各自批次内的第 0 条）→ 后批覆盖前批。
 */
object EventSeq {

    /**
     * 给 [list] 的每项配组内序号（保持原顺序返回）。
     * [groupOf] = 主键去掉 `seq` 后的分组键（如解锁 `{ ms }`、通知 `{ ms, type, pkg }`）。
     */
    fun <T> assign(list: List<T>, groupOf: (T) -> List<Any?>): List<Pair<T, Int>> {
        val next = HashMap<List<Any?>, Int>()
        return list.map { item ->
            val key = groupOf(item)
            val seq = next.getOrPut(key) { 0 }
            next[key] = seq + 1
            item to seq
        }
    }
}
