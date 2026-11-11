package com.screentrace.app.uikit

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.screentrace.app.theme.LocalFreshPalette
import androidx.compose.ui.util.lerp

/**
 * §5.5 / §7-22：二级页的**容器变换转场**（container transform）。
 *
 * ### 为什么不做「从右滑入」
 *
 * 真机反馈原话是「返回上一级退出动画要像打开 app 退出 app 一样，app 展开，app 回到原处」。
 * 系统 App 启动动画的观感是：入口图标先出现在它自己的位置，然后**放大到全屏**；退出时反向
 * 缩回图标处。横向 push/pop 是完全另一种隐喻（并列层级），跟"从卡片里长出来"不是一回事。
 *
 * ### 关键约束：全程不透明
 *
 * 批 6g 二轮已经踩过一次：覆盖层做半透明淡出时，**底下的主页面会透出来**，两层叠在一起
 * 就是用户说的「视觉残留」。所以这里 alpha 恒为 1（只有减少动画模式才用纯淡入），
 * 转场差异全部由**缩放 + 位移 + 圆角**表达。缩放过程中四周露出主页面是 container
 * transform 的正常观感（卡片放大盖住页面），不是叠影——因为上层始终是实心的。
 *
 * ### 首帧不能靠 LaunchedEffect
 *
 * 上一版 tab 闪现的教训：`LaunchedEffect` 在首帧提交**之后**才跑，任何「先 snap 到起点
 * 再 animate」的写法，第一帧都会以终态画出来。这里用 `animateFloatAsState`——它一直
 * 存在于组合中，上一轮的动画值就是 0，目标变 1 时自然从 0 开始，第 0 帧就是起点状态。
 *
 * ### 几何
 *
 * 起点 = 入口矩形（点击点 + 典型卡片尺寸）；缩略图**内接**于该矩形并中心对齐，
 * 所以视觉上像"从这块地方长出来"。progress 从 0→1 时：
 * - scale：`startScale → 1`
 * - translation：`起点偏移 → 0`（保证终点严丝合缝铺满）
 * - 圆角：`28dp → 0`
 */
@Stable
class ContainerTransformState {

    /** 最近一次**进入前**的入口矩形（窗口坐标）。退出动画沿用同一个起点。 */
    var origin by mutableStateOf<Rect?>(null)
        private set

    /** 入口矩形自身的圆角（px）：起点用它，终点归零。 */
    var originCornerPx by mutableStateOf(0f)
        private set

    /** 兜底：只知道按下点（入口没有上报自身矩形）时，以点为心取一个方框。 */
    fun record(point: Offset, sizePx: Float) {
        val half = sizePx / 2f
        origin = Rect(
            left = point.x - half,
            top = point.y - half,
            right = point.x + half,
            bottom = point.y + half
        )
        originCornerPx = half
    }

    /** 精确入口：被点击控件自己的矩形与圆角。 */
    fun recordRect(bounds: Rect, cornerPx: Float) {
        if (bounds.width <= 0f || bounds.height <= 0f) return
        origin = bounds
        originCornerPx = cornerPx
    }

    fun clear() {
        origin = null
        originCornerPx = 0f
    }
}

/**
 * 转场起点的「可记录区域」。
 *
 * 只在主页面层提供：[AppNav] 用 `Provider(state)` 包住三页主内容、用 `Provider(null)`
 * 包住二级页覆盖层。这样覆盖层里的顶部按钮（日期、返回）不会把自己登记成新起点——
 * 否则在设置页点返回，缩回去的会是那个返回按钮，而不是进入时的位置。
 */
val LocalContainerTransformState = staticCompositionLocalOf<ContainerTransformState?> { null }

/**
 * 把控件自己的矩形登记为转场起点。
 *
 * 用 `boundsInWindow()` 而不是"按下点 + 固定尺寸"：卡片是 320×160、齿轮按钮是 48×48，
 * 用统一尺寸会出现「从卡片中间一小块长出来」的错位感。
 *
 * 事件用 [PointerEventPass.Initial]**只读不消费**：既不影响控件的 clickable/ripple，
 * 也不影响 Pager 横滑。父容器（[Modifier.recordTransformOrigin]）会先记一个按下点兜底，
 * 这里随后用精确矩形覆盖它。
 */
@Composable
fun Modifier.transformSource(cornerRadius: Dp = 24.dp): Modifier {
    val state = LocalContainerTransformState.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val cornerPx = with(LocalDensity.current) { cornerRadius.toPx() }
    val target = state
    return if (target == null) {
        this
    } else {
        this
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .pointerInput(target, cornerPx) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        if (change.pressed && !change.previousPressed) {
                            target.recordRect(bounds, cornerPx)
                        }
                    }
                }
            }
    }
}

/**
 * 挂在根容器上，记录「按下点」作为下一次转场的起点。
 *
 * 用 [PointerEventPass.Initial] 只读不消费：既不干扰子节点的 clickable，也不干扰
 * Pager 的横滑。`enabled` 由调用方控制（只在**主页面层**记录）——覆盖层打开时若还
 * 记录，点「返回」按钮会把起点改成按钮位置，退出就缩错地方了。
 */
@Composable
fun Modifier.recordTransformOrigin(
    state: ContainerTransformState,
    enabled: Boolean,
    originSize: Dp = 72.dp
): Modifier {
    val sizePx = with(LocalDensity.current) { originSize.toPx() }
    val currentEnabled by rememberUpdatedState(enabled)
    return this.pointerInput(sizePx) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (!currentEnabled) continue
                val change = event.changes.firstOrNull() ?: continue
                // 只认「按下的那一帧」，否则拖动过程中会反复覆盖起点
                if (change.pressed && !change.previousPressed) {
                    state.record(change.position, sizePx)
                }
            }
        }
    }
}

/**
 * 覆盖层容器：按 [visible] 在「全屏」与「入口矩形」之间做缩放位移。
 *
 * [content] 的布局始终是满屏的，只有绘制被 `graphicsLayer` 变换——所以不需要
 * 各个二级页配合，也不需要知道自己的目标尺寸。
 */
@Composable
fun ContainerTransformOverlay(
    visible: Boolean,
    state: ContainerTransformState,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            // 减少动画：不缩放不位移，退化成短淡入（§5.5 降级要求）
            durationMillis = if (reduceMotion) 120 else 320,
            easing = FastOutSlowInEasing
        ),
        label = "containerTransform"
    )

    // 完全收起后不再绘制，节点随之移除（下次进入重建二级页，符合预期）
    if (!visible && progress <= 0.001f) return

    val palette = LocalFreshPalette.current
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val origin = state.origin
        val density = LocalDensity.current
        val startCornerPx = state.originCornerPx

        val startScaleX: Float
        val startScaleY: Float
        val startX: Float
        val startY: Float
        if (origin != null && origin.width > 0f && origin.height > 0f) {
            // **X / Y 独立缩放**，不是等比：卡片是 1.6:1、屏幕是 1:2.2，等比内接会让
            // 缩略图变成一条窄条，看起来像"从卡片中间挤出来"而不是"卡片展开"。
            // 与系统 shared element（ChangeTransform）一样按轴分别插值。
            startScaleX = (origin.width / width).coerceIn(MIN_START_SCALE, 1f)
            startScaleY = (origin.height / height).coerceIn(MIN_START_SCALE, 1f)
            // 左上角对齐：起点矩形左上角就是入口控件左上角
            startX = origin.left
            startY = origin.top
        } else {
            // 没有记录到入口（例如深链直达二级页）：居中小幅放大兜底
            startScaleX = FALLBACK_START_SCALE
            startScaleY = FALLBACK_START_SCALE
            startX = (width - width * startScaleX) / 2f
            startY = (height - height * startScaleY) / 2f
        }

        // 线性插值到全屏。起点就是入口控件本身（卡片/按钮还在下面），所以收尾缩回去时
        // 正好"融回"那个控件，不需要额外缩到一个点。
        val scaleX = if (reduceMotion) 1f else ContainerTransformMath.scale(startScaleX, progress)
        val scaleY = if (reduceMotion) 1f else ContainerTransformMath.scale(startScaleY, progress)
        val dx = if (reduceMotion) 0f else ContainerTransformMath.offset(startX, progress)
        val dy = if (reduceMotion) 0f else ContainerTransformMath.offset(startY, progress)
        val cornerDp = if (reduceMotion) 0f else {
            val px = ContainerTransformMath.offset(startCornerPx, progress)
            with(density) { px.toDp().value }
        }
        // 前半段只画不透明底板（一块放大的圆角色块，观感等同"图标正在变大"），
        // 内容到后半段才淡入——这正是系统启动动画的节奏：先把图标放大到接近全屏，
        // 再让真实界面浮现。全程不做整页半透明，因此不会透出下层形成叠影。
        val contentAlpha = if (reduceMotion) {
            progress
        } else {
            ContainerTransformMath.contentAlpha(progress)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 以左上角为变换原点，位移才能直接按「屏幕坐标差」计算
                    transformOrigin = TransformOrigin(0f, 0f)
                    this.scaleX = scaleX
                    this.scaleY = scaleY
                    translationX = dx
                    translationY = dy
                    shape = RoundedCornerShape(cornerDp.dp)
                    clip = cornerDp > 0.5f
                }
        ) {
            // 底板必须不透明且跟随缩放：它就是"放大中的那一块"
            Box(
                Modifier
                    .fillMaxSize()
                    .background(palette.background)
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = contentAlpha }
            ) {
                content()
            }
        }
    }
}

/** 起点缩放下限：点击点靠近屏幕边缘时避免缩略图小到看不清内容。 */
private const val MIN_START_SCALE = 0.05f

/** 内容开始淡入 / 完成淡入的进度点（前半段只画底板）。 */
internal const val CONTENT_FADE_START = 0.42f
internal const val CONTENT_FADE_END = 0.86f

/** 没有入口信息时的兜底起点。 */
private const val FALLBACK_START_SCALE = 0.92f

/**
 * 转场几何：入口矩形 → 全屏的插值。抽成纯函数是为了能单测——「终点是否真的严丝合缝
 * 铺满」「起点是否真的等于入口矩形」这类性质靠肉眼在逐帧截图里判断并不可靠。
 */
internal object ContainerTransformMath {

    /** 缩放：入口尺寸 → 1（全屏）。 */
    fun scale(startScale: Float, progress: Float): Float =
        lerp(startScale.coerceIn(MIN_START_SCALE, 1f), 1f, progress.coerceIn(0f, 1f))

    /** 位移：入口位置 → 0。终点必须是 0，否则页面边缘会露出下层的缝。 */
    fun offset(startOffset: Float, progress: Float): Float =
        lerp(startOffset, 0f, progress.coerceIn(0f, 1f))

    /**
     * 内容不透明度：前 [CONTENT_FADE_START] 段恒为 0（只看到底板在放大），
     * 之后淡入到 1。
     *
     * 这就是用户要的「图标放大到差不多一半再显示 App 内容」：前半段是形状变化，
     * 后半段才是内容浮现。若全程都画内容，观感是「整页缩成一个小方块」，与系统
     * 启动动画完全是两回事。
     */
    fun contentAlpha(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        return ((p - CONTENT_FADE_START) / (CONTENT_FADE_END - CONTENT_FADE_START))
            .coerceIn(0f, 1f)
    }
}
