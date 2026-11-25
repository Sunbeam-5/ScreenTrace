package com.screentrace.app.ui

import java.util.concurrent.atomic.AtomicLong

/**
 * CR-22 ①：点 tab 触发的**程序化滚动**守卫。
 *
 * ## 为什么需要它（真机实测）
 *
 * 底栏三个主页面由 `HorizontalPager` 常驻（CR-17：Pager 是唯一真相源，点 tab 只发
 * `animateScrollToPage`，路由由 `snapshotFlow { currentPage }` 单向回写）。
 *
 * 回写用的是 `currentPage`（**过半即翻**），这是滑动跟手的关键。但点 tab 触发的
 * **跨两页**滚动也会经过**中间页**：真机日志显示点「今日 → 日历」后，路由先在 70ms
 * 被回写成「统计」、180ms 才回写成「日历」——标题与导航胶囊中途跳到中间页再跳回，
 * 观感就是「点 tab 卡一下」。**滑动切换没有这一步**（回写的就是用户正滑向的那页），
 * 所以用户反馈「滑动不卡、点 tab 卡」。
 *
 * ## 口径
 *
 * 程序化滚动期间**只忽略中间页**的回写（目标页照常回写，动画走到七成时页面已经基本
 * 是目标页，标题这时切过去最自然；等到落定才切会慢半拍）。滚动结束后再按 **Pager
 * 的实际停留页**对齐一次路由。于是：
 * - 点 tab：路由**直达目标页**，不经过中间页；
 * - 滑动：不受影响，仍然过半即切、跟手；
 * - 动画被用户手势打断：按 Pager 停在哪儿来对齐，标题与页面不会不一致。
 *
 * **Pager 仍是唯一真相源**：对齐时写的是 `pagerState.currentPage`（Pager 此刻的位置），
 * 不是「用户点的页」——点 tab 依旧不直接写路由（CR-17 红线不变）。
 *
 * ## A-5 R10：所有权按「点击 token」判定（CR-30-A5-R10 / P2）
 *
 * 旧实现用 `target` 页索引充当协程身份（`end(index)` 只看 `target != index`）。
 * 但**目标页不是协程身份**：**同目标连续点击**时（新旧两次点击 target 相同），
 * 旧协程被新点击取消后进入 finally，判据失效 →
 * ① 旧协程错误返回 true，抢到对齐权；
 * ② 旧协程把新点击刚建立的 `programmatic`/`target` 守卫状态一并清掉，
 *    新协程结束时反而拿不到对齐权。
 * 真机证据（A-5 R9 v3 s5_same，连点两次统计页）：seq1 `scrollCancel→scrollFinally(aligned=true)`
 * 且触发 `Route/align=1`，seq2 `scrollEnd→scrollFinally(aligned=false)` 且**没有 Route/align**。
 *
 * 修复：**每次 `begin` 发放一个单调递增、永不复用的唯一 token**；
 * `end(token)` 只有 token 命中当前 owner 才可结束守卫并决定是否对齐路由，
 * 旧协程的 finally **不得清除新协程的 programmatic/target、不得抢先对齐**。
 *
 * ## 状态读取的一致性
 *
 * 守卫状态用**一个不可变快照**（[GuardState]）整体持有、整体替换：
 * `@Volatile` 只保证单字段可见性，多个独立可变字段组合起来**没有整体一致性**——
 * 读取期间恰逢更新就可能读到「programmatic=true 但 target=-1」这种非法状态。
 * 快照对象整体替换后，**任何一次读取拿到的都是某个时刻的合法完整状态**。
 * 需要同一时刻的三要素时用 [snapshot] 取一次快照（三个派生 getter 各自独立读，
 * 连读可能跨状态）。
 */
class TabScrollGuard {

    /**
     * 守卫状态快照。**`internal` 可见**：同模块测试可读、可断言；外部无法改写
     * （字段全是 `val`，构造器随 `internal` 一起受限）。
     */
    internal data class GuardState(
        val programmatic: Boolean,
        val target: Int,
        val owner: Long
    )

    /**
     * 唯一的可变状态字段——**只允许这一个**（A-5 R10 结构性审查项 S1）。
     * 读出口全部经由它，更新一律整体替换。
     */
    @Volatile
    private var state = GuardState(programmatic = false, target = -1, owner = NO_OWNER)

    /** token 生成器：单调递增、永不复用（A-5 R10）。 */
    private val nextToken = AtomicLong(0)

    /**
     * `begin`/`end` 是「读快照 → 判断 → 换快照」的复合操作，需要互斥。
     * 当前调用全在主线程，这是**纵深防御**：将来守卫被挪到后台线程时语义不依赖字段写入顺序。
     */
    private val lock = Any()

    /** 程序化滚动进行中（点 tab / 路由回滚触发的滚动）。 */
    val programmatic: Boolean get() = state.programmatic

    /** 本次程序化滚动要去的页索引；-1 = 无。 */
    val target: Int get() = state.target

    /** 当前守卫状态的所有者 token；NO_OWNER = 无守卫（供测试与诊断）。 */
    val owner: Long get() = state.owner

    /**
     * **统一只读快照入口**——测试断言三要素必须用它取一次快照。
     * 三个派生 getter 各自独立读 state，依次调用时可能跨越状态变化；
     * 需要「同一时刻的 programmatic/target/owner」时一律读本属性。
     */
    internal val snapshot: GuardState get() = state

    /**
     * 开始一次程序化滚动，发放唯一 owner token 并返回它。
     *
     * 发放新 token 即**取代**旧 owner：旧协程之后调用 `end(旧 token)` 一定返回 false，
     * 一个字段都动不了。
     */
    fun begin(index: Int): Long = synchronized(lock) {
        val token = nextToken.incrementAndGet()
        state = GuardState(programmatic = true, target = index, owner = token)
        token
    }

    /**
     * 这次 Pager 回写是否应被忽略（程序化滚动途中的**中间页**）。
     */
    fun ignoresWrite(index: Int): Boolean {
        val snapshot = state // ← 只读一次快照，保证三要素同源
        return ignoresWrite(snapshot.programmatic, snapshot.target, index)
    }

    /**
     * 结束一次程序化滚动。
     *
     * @param token 本次 `begin` 返回的 owner token。
     * @return true = token 命中当前 owner：该由本协程按停留页对齐路由；
     *         false = 已被更新的点击取代（或 token 不匹配）：**不改任何状态**，
     *                由那次点击自己收尾。
     */
    fun end(token: Long): Boolean = synchronized(lock) {
        val snapshot = state
        if (!ownsToken(snapshot.owner, token)) return false // 不是主人：快照一个字段都不动
        state = GuardState(programmatic = false, target = -1, owner = NO_OWNER)
        return true
    }

    companion object {
        /** 无守卫状态的所有者 token。 */
        const val NO_OWNER: Long = 0L

        /**
         * 纯函数口径：token 是否命中当前所有者（便于单测与变异验证）。
         *
         * token 唯一且充分——**不再校验 target**：旧实现用 target 页当身份，
         * 同目标连点时判据失效；token 判定后，协程身份与「滚向哪一页」彻底分开。
         */
        fun ownsToken(owner: Long, token: Long): Boolean =
            token != NO_OWNER && token == owner

        /**
         * 纯函数口径（便于单测与变异验证）：程序化滚动期间的回写是否忽略。
         *
         * 只忽略**中间页**——它会让路由经过中间页（标题与胶囊先跳到中间页再跳回，
         * 观感＝卡一下）；**目标页照常回写**（动画走到七成时页面已经基本是目标页，
         * 标题这时切过去最自然；等到落定才切会慢半拍）。
         */
        fun ignoresWrite(programmatic: Boolean, target: Int, index: Int): Boolean =
            programmatic && index != target
    }
}

// ============================================================================
// CR-30-R10-OBS2：取消原因两段式判定 + 稳定对齐策略 + 代际来源
// （不改动上方 TabScrollGuard 的 token 与不可变快照实现）
// ============================================================================

/** 观测到的事实：这次取消看起来是什么原因。只用于日志与取证，不得作为行为判据。 */
internal enum class ObservedCause {
    NONE,
    GESTURE,
    SUPERSEDED,
    SCOPE,
    UNKNOWN
}

/** 实际采取的动作：唯一的行为判据。 */
internal enum class EffectiveAction {
    NO_ACTION,
    GESTURE_COMPENSATE,
    CONSERVATIVE_COMPENSATE,
    NO_ALIGN_SUPERSEDED,
    NO_ALIGN_SCOPE
}

/** 滚动协程收尾状态。 */
internal enum class ScrollOutcome { SETTLED, CANCELLED }

/** 代际推进来源（OBS-2 §6.3.6.3）。 */
internal enum class GenSource { NONE, PROGRAMMATIC, GESTURE }

/** 代际快照：单调递增编号 + 推进来源。 */
internal data class ScrollGeneration(
    val value: Long,
    val source: GenSource
)

/** 线程安全的代际计数器：只在「新程序化滚动」与「用户手势接管」两处推进。 */
internal class ScrollGenerationCounter {
    private val lock = Any()
    @Volatile
    private var state = ScrollGeneration(value = 0L, source = GenSource.NONE)

    val current: ScrollGeneration get() = state

    fun advance(source: GenSource): ScrollGeneration = synchronized(lock) {
        val next = ScrollGeneration(value = state.value + 1L, source = source)
        state = next
        next
    }
}

/**
 * OBS-2 两段式取消判定：
 * 1. [observe] 返回观测事实（仅日志）；
 * 2. [actionFor] 返回实际动作（唯一行为判据，UNKNOWN 必须走保守补偿）。
 */
internal object CancelCauseResolver {
    fun observe(scopeActive: Boolean, tokenOwned: Boolean, userDragging: Boolean): ObservedCause =
        when {
            !scopeActive -> ObservedCause.SCOPE
            !tokenOwned -> ObservedCause.SUPERSEDED
            userDragging -> ObservedCause.GESTURE
            else -> ObservedCause.UNKNOWN
        }

    fun actionFor(observed: ObservedCause): EffectiveAction =
        when (observed) {
            ObservedCause.NONE -> EffectiveAction.NO_ACTION
            ObservedCause.GESTURE -> EffectiveAction.GESTURE_COMPENSATE
            ObservedCause.SUPERSEDED -> EffectiveAction.NO_ALIGN_SUPERSEDED
            ObservedCause.SCOPE -> EffectiveAction.NO_ALIGN_SCOPE
            ObservedCause.UNKNOWN -> EffectiveAction.CONSERVATIVE_COMPENSATE
        }
}

/**
 * OBS-2 路由对齐纯函数策略（两条写入路径分离判定）。
 */
internal object ScrollAlignPolicy {

    /** 路径一：程序化滚动正常收尾（finally）时是否允许对齐。 */
    fun shouldAlignAtFinally(
        tokenOwned: Boolean,
        outcome: ScrollOutcome,
        isScrollInProgress: Boolean,
        currentPage: Int,
        settledPage: Int,
        routePage: Int
    ): Boolean =
        tokenOwned &&
            outcome == ScrollOutcome.SETTLED &&
            !isScrollInProgress &&
            currentPage == settledPage &&
            routePage != currentPage

    /** 路径二：取消路径专属的稳定后对齐器是否允许写入。 */
    fun shouldAlignAtSettle(
        genValid: Boolean,
        isScrollInProgress: Boolean,
        currentPage: Int,
        settledPage: Int,
        routePage: Int
    ): Boolean =
        genValid &&
            !isScrollInProgress &&
            currentPage == settledPage &&
            routePage != settledPage

    /** 代际过期时的规范退出原因（与 OBS-2 §6.3.6.4 逐字一致）。 */
    fun skipReasonForStaleGeneration(currentSource: GenSource): String =
        when (currentSource) {
            GenSource.GESTURE -> "gesture-takeover"
            GenSource.PROGRAMMATIC -> "superseded"
            GenSource.NONE -> "stale-generation"
        }
}
