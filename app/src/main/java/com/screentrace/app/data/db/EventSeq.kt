package com.screentrace.app.data.db

/**
 * CR-28：事件行的确定性 `seq` 编号（同毫秒多条不丢 + 重复拉取幂等）。
 *
 * 规则（设计审查 CR-28 §1.3，CR-28-R1 修订）：
 * - **组 = 主键去掉 `seq` 后的键**（解锁 = `occurredAtMs`；通知 = `occurredAtMs, type, pkg`
 *   ——组的定义必须与主键一致，否则组间会撞同一个 `seq` 而吞行）；
 * - `seq` = 组内按**稳定内容键排序**后的序号（通知内容键含 `channelId`；同内容按原次序）；
 * - 同一份输入 → 同一组 `(主键, seq)` → upsert/insertIgnore 重放幂等；
 * - 同毫秒同组多条（内容全同的双胞胎）→ 按原次序 0、1、2… 各占一行，不被吞；
 * - **跨批次稳定（CR-28-R1 P1-1）**：组内按内容排序编号与输入次序无关——同毫秒组内
 *   dump 次序变化、乱序超集、增量新增都不会与既有行撞键丢事件。
 */
object EventSeq {

    /**
     * 给 [list] 的每项配组内序号（按分组聚集返回）。
     * [groupOf] = 主键去掉 `seq` 的组键；[stableKey] = 组内稳定排序的内容键。
     */
    fun <T> assign(
        list: List<T>,
        groupOf: (T) -> List<Any?>,
        stableKey: (T) -> List<Any?>
    ): List<Pair<T, Int>> =
        list.withIndex()
            .groupBy { groupOf(it.value) }
            .flatMap { (_, group) ->
                val ordered = group.sortedWith(
                    compareBy({ stableKey(it.value).toString() }, { it.index })
                )
                ordered.mapIndexed { seq, iv -> iv.value to seq }
            }
}
