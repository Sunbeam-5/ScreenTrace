package com.screentrace.app.uikit

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import kotlin.math.abs

/**
 * §5.2 CR-23：tab 转场运动的三条**纯映射**（景深 / 标题位移）。
 *
 * ## 为什么是纯函数
 *
 * 这三条是观感的核心参数，必须能被 JVM 单测直接打靶、能被变异验证咬住：
 * 它们只吃「偏移」「进度」这样的数值，不依赖 Compose 状态，也不该依赖——
 * 依赖了就没法在没有设备的情况下证明「居中 = 不变、完全滑出 = 最暗最小」。
 *
 * ## 三条口径（§5.2「tab 转场运动」条）
 *
 * - **景深**：每页内容按 `|offset|`（0 = 居中，1 = 完全滑出）轻微缩放与淡化。
 *   居中页完全清晰、滑出页渐暗渐小，于是横滑与点 tab 都不再是「一整块刚性平面」。
 *   用 `Modifier.graphicsLayer` 在**绘制期**套用——不重建子树、不重播任何动画，
 *   因此不触发 CR-15 A1-④ / CR-17 ③「整页 Crossfade 会闪一帧」的立论（红线例外
 *   见需求 CR-23 登记）。
 * - **标题位移**：标题块按 `目标页 − 连续进度` 做轻微位移，落定自然归 0。
 *   「目标页」取**程序化滚动的目标**（点 tab 时就知道），而不是路由当前值——
 *   路由要到七成才翻（CR-22），用它会算出反向位移。
 *
 * ## 与进度的关系
 *
 * 三条都只吃位置/进度，**不自己起时间轴**：位置由
 * `pagerState.currentPage + pagerState.currentPageOffsetFraction` 这一条连续进度给出，
 * 于是景深、标题、导航胶囊天然在同一条时间轴上（这正是 CR-23 治「头部与身体
 * 不在一条时间轴」的手段）。
 */
object TabMotion {

    /** 完全滑出时的透明度（居中恒为 1）。 */
    const val DEPTH_ALPHA_AT_EDGE = 0.45f

    /** 完全滑出时的缩放（居中恒为 1），只做轻微收缩，不压扁文字。 */
    const val DEPTH_SCALE_AT_EDGE = 0.95f

    /** 标题每跨一页的位移（dp）。 */
    const val TITLE_SHIFT_PER_STEP_DP = 18f

    /**
     * 点 tab 的运动曲线（§5.2 CR-25）：**有明确落点**的强调缓出。
     *
     * 治的是「点 tab 比滑动卡」的直接原因——`spring` 的**渐近尾巴**：
     * 真机 logcat 实测跨两页时 **78% 的行程只用 147ms，剩下 22% 却爬了 630ms**
     * （154ms 时 progress=1.71，779ms 才落定 2.0）。那段「几乎看不出在动、又没结束」
     * 的磨蹭就是观感上的「卡住」；滑动是手指驱动、松手即落定，没有这段。
     *
     * 改用 M3 emphasized decelerate（**稳定 API**，§6 不引实验组件）：
     * 起步有速度、中段平稳、**末端准确停住**——`tween` 到时即进度 1.0，没有尾巴。
     *
     * 与 [alphaFor] / [scaleFor] / [titleShiftDp] 同处一室：转场运动的参数只有一个
     * 出处，调观感时不会出现「标题改了曲线没改」的分叉。
     */
    val EMPHASIZED_DECELERATE = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** CR-25：单页基础时长（ms）。 */
    const val SCROLL_BASE_MS = 350

    /** CR-25：每多一页加的时长（ms）。 */
    const val SCROLL_PER_EXTRA_PAGE_MS = 90

    /** CR-25：时长上限（§5.5「转场总时长 ≤500ms」，留 20ms 余量）。 */
    const val SCROLL_MAX_MS = 480

    /**
     * 点 tab 的滚动时长：按**行程**给（跨页越多越久），上限 [SCROLL_MAX_MS]。
     *
     * 1 页 = 350ms、2 页 = 440ms、3 页及以上 = 480ms。
     */
    fun scrollDurationMs(pageDelta: Int): Int {
        val d = pageDelta.coerceAtLeast(1)
        return (SCROLL_BASE_MS + (d - 1) * SCROLL_PER_EXTRA_PAGE_MS).coerceAtMost(SCROLL_MAX_MS)
    }

    /** 点 tab 的滚动曲线：强调缓出 + 按行程给时长。 */
    fun scrollSpecFor(pageDelta: Int): AnimationSpec<Float> =
        tween(durationMillis = scrollDurationMs(pageDelta), easing = EMPHASIZED_DECELERATE)

    /**
     * 景深透明度：居中 1 → 完全滑出 [DEPTH_ALPHA_AT_EDGE]，线性插值。
     *
     * @param offset 该页相对当前页位置的偏移（0 = 居中，±1 = 完全滑出）。
     */
    fun alphaFor(offset: Float): Float {
        val o = abs(offset).coerceIn(0f, 1f)
        return 1f - (1f - DEPTH_ALPHA_AT_EDGE) * o
    }

    /**
     * 景深缩放：居中 1 → 完全滑出 [DEPTH_SCALE_AT_EDGE]，线性插值。
     */
    fun scaleFor(offset: Float): Float {
        val o = abs(offset).coerceIn(0f, 1f)
        return 1f - (1f - DEPTH_SCALE_AT_EDGE) * o
    }

    /**
     * 标题块的横向位移（dp）：`目标页 − 进度` 乘每步位移。
     *
     * 前进（目标页在右、进度还在左）时为正 → 标题从右侧滑入落定；
     * 后退时为负 → 从左侧滑入；落定时进度 = 目标页，位移归 0。
     *
     * @param target 程序化滚动的目标页索引
     * @param progress 连续进度（`currentPage + currentPageOffsetFraction`）
     * @param perStepDp 每跨一页的位移（dp）
     */
    fun titleShiftDp(
        target: Float,
        progress: Float,
        perStepDp: Float = TITLE_SHIFT_PER_STEP_DP
    ): Float = (target - progress) * perStepDp
}
