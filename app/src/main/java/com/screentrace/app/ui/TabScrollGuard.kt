package com.screentrace.app.ui

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
 */
class TabScrollGuard {

    /** 程序化滚动进行中（点 tab / 路由回滚触发的滚动）。 */
    @Volatile
    var programmatic: Boolean = false

    /** 本次程序化滚动要去的页索引；-1 = 无。 */
    @Volatile
    var target: Int = -1

    /** 开始一次程序化滚动。 */
    fun begin(index: Int) {
        target = index
        programmatic = true
    }

    /** 这次 Pager 回写是否应被忽略（程序化滚动途中的**中间页**）。 */
    fun ignoresWrite(index: Int): Boolean =
        ignoresWrite(programmatic, target, index)

    /**
     * 结束一次程序化滚动。
     *
     * @return true = 这次滚动没有被更新的点击取代，该由本协程按停留页对齐路由；
     *         false = 已被新的点击取代（标记留给那次处理），本协程不要动路由。
     */
    fun end(index: Int): Boolean {
        if (target != index) return false
        programmatic = false
        target = -1
        return true
    }

    companion object {
        /**
         * 纯函数口径（便于单测与变异验证）：程序化滚动期间的回写是否忽略。
         *
         * 只忽略**中间页**——它会让路由经过中间页（标题与胶囊先跳到中间页再跳回，
         * 观感＝卡一下）；**目标页照常回写**（动画走到七成时页面已经基本是目标页，
         * 标题这时切过去最自然；等到落定才切会慢半拍）。
         */
        fun ignoresWrite(programmatic: Boolean, target: Int, index: Int): Boolean =
            programmatic && index != target

        /**
         * 纯函数口径：这次滚动结束是否由本次点击负责对齐路由。
         *
         * 被更新的点击取代时返回 false（那次点击自己会对齐，别抢）。
         */
        fun ownsTarget(target: Int, index: Int): Boolean = target == index
    }
}
