package com.screentrace.app.uikit

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.screentrace.app.theme.LocalFreshPalette

/**
 * §5.5 / §7-22：二级页的**容器变换转场**（container transform）。
 *
 * ### 想要的观感（真机反馈原话）
 *
 * 「点击设置，设置图标开始放大，放大到一半左右开始变为设置内容，然后持续放大到全屏；
 * 返回时先缩小到差不多一半变回设置图标，继续缩小回到原位置」——就是系统打开 / 退出 App
 * 的那套动画。分三层实现：
 *
 * 1. **底板**：入口控件自己的底色（[ContainerTransformState.originColor]），形状与入口一致
 *    （圆形按钮就是圆形），从入口矩形放大到全屏；放大过程中底色渐变为页面背景色。
 * 2. **图标**：入口控件上的图标（[ContainerTransformState.originIcon]），跟着底板等比放大，
 *    在中段淡出——这就是「图标在变大」的那部分观感。
 * 3. **内容**：二级页本体，在中段淡入。内容**等比**缩放（不压扁文字），超出底板的部分被裁掉。
 *
 * ### 上一版为什么「闪一帧、没有效果」（务必不要改回去）
 *
 * - 底板曾经直接用页面背景色：放大中的「图标」与页面同色，前半段什么都看不见，
 *   观感就是「一片空白缩小，然后图标突然冒出来」。
 * - 底板第 0 帧就是**不透明**的，立刻盖住入口控件；退出收尾时节点一移除，被盖住的控件
 *   又「弹」出来——这就是闪的那一帧。现在底板不透明度从 0 渐入（[ContainerTransformMath.plateAlpha]），
 *   起止两端露出的是入口控件本身，没有跳变。
 * - 圆角写在 `graphicsLayer` 里是**缩放之前**的局部坐标，被 X / Y 非等比缩放后会压扁，
 *   圆形按钮起步时变成近似方块。现在裁剪框直接在屏幕坐标里画圆角（[RectCornerShape]），页面另行等比缩放，半径即真实半径。
 * - 退出时「内容→图标」的切换窗口只有约 48ms（不到 3 帧）。现在缓动改为 LinearOutSlowIn
 *   （起步无延迟、切换窗口更宽），退出时长也比进入长一点。
 *
 * ### 关键约束：全程不透明
 *
 * 批 6g 二轮已经踩过一次：覆盖层做半透明淡出时，**底下的主页面会透出来**，两层叠在一起
 * 就是用户说的「视觉残留」。底板只在**入口矩形附近**（进度 < [PLATE_FADE_END]）渐入渐出，
 * 那里下面就是入口控件本身，透出来的不是主页面；之后一直不透明。
 *
 * ### 首帧不能靠 LaunchedEffect
 *
 * 上一版 tab 闪现的教训：`LaunchedEffect` 在首帧提交**之后**才跑，任何「先 snap 到起点
 * 再 animate」的写法，第一帧都会以终态画出来。这里用 `animateFloatAsState`——它一直
 * 存在于组合中，上一轮的动画值就是 0，目标变 1 时自然从 0 开始，第 0 帧就是起点状态。
 *
 * ### 几何
 *
 * 起点 = 入口矩形（控件自己上报的 `boundsInWindow`）。progress 从 0→1 时：
 * - scale：`startScale → 1`（X / Y 独立）
 * - translation：`起点偏移 → 0`（保证终点严丝合缝铺满）
 * - 圆角：`入口圆角 → 0`
 */
@Stable
class ContainerTransformState {

    /** 最近一次**进入前**的入口矩形（窗口坐标）。退出动画沿用同一个起点。 */
    var origin by mutableStateOf<Rect?>(null)
        private set

    /** 入口矩形自身的圆角（px）：起点用它，终点归零。 */
    var originCornerPx by mutableStateOf(0f)
        private set

    /**
     * 入口控件自己的底色。[Color.Unspecified] = 不知道（兜底按钮 / 没有上报），
     * 覆盖层退回调色板的 `surface`。
     */
    var originColor by mutableStateOf(Color.Unspecified)
        private set

    /**
     * 入口控件上的图标（可为空：卡片、文字按钮没有「图标」，只放大底板）。
     * 覆盖层在放大过程中用它的布局尺寸 = 入口矩形尺寸重画一份，所以传进来的内容
     * 要和控件上的图标**等价**（同一个 Icon / 同一个 Canvas）。
     */
    var originIcon by mutableStateOf<(@Composable () -> Unit)?>(null)
        private set

    /**
     * CR-09 验收 42：主页面层是否允许记录新的转场起点。
     *
     * 退出动画进行中（visible=false 但 progress 还没收到 0）必须是 false——否则
     * 用户在退场动画里点一下屏幕，起点被改到点击处，正在缩回去的二级页会**飘到
     * 点击点**（真机反馈）。由 [ContainerTransformOverlay] 每帧同步；覆盖层尚未
     * 组合过（首次启动）时默认 true。
     */
    var acceptingCaptures by mutableStateOf(true)

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
        // 兜底起点不知道底色与图标：必须清掉上一个入口留下的，否则会放大出错的图标
        originColor = Color.Unspecified
        originIcon = null
    }

    /** 精确入口：被点击控件自己的矩形、圆角、底色与图标。 */
    fun recordRect(
        bounds: Rect,
        cornerPx: Float,
        color: Color = Color.Unspecified,
        icon: (@Composable () -> Unit)? = null
    ) {
        if (bounds.width <= 0f || bounds.height <= 0f) return
        origin = bounds
        originCornerPx = cornerPx
        originColor = color
        originIcon = icon
    }

    fun clear() {
        origin = null
        originCornerPx = 0f
        originColor = Color.Unspecified
        originIcon = null
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
 *
 * @param cornerRadius 入口**可见形状**的圆角（圆形按钮 = 半径）。
 * @param containerColor 入口控件的底色。放大的底板用它，起步时才和入口控件无缝衔接；
 *   不传就退回调色板 `surface`。
 * @param inset 点击区比可见形状大多少（每边）。例如 48dp 点击区里画了 40dp 圆，
 *   传 4.dp，登记的矩形就是那个 40dp 圆，而不是 48dp 方框。
 * @param icon 入口控件上的图标。放大时跟着底板一起变大再淡出；卡片 / 文字按钮不传。
 */
@Composable
fun Modifier.transformSource(
    cornerRadius: Dp = 24.dp,
    containerColor: Color = Color.Unspecified,
    inset: Dp = 0.dp,
    icon: (@Composable () -> Unit)? = null
): Modifier {
    val state = LocalContainerTransformState.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    val cornerPx = with(density) { cornerRadius.toPx() }
    val insetPx = with(density) { inset.toPx() }
    // 颜色与图标每次重组都可能是新实例；按下那一刻读最新值即可，不需要重启 pointerInput
    val latestColor by rememberUpdatedState(containerColor)
    val latestIcon by rememberUpdatedState(icon)
    val target = state
    return if (target == null) {
        this
    } else {
        this
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .pointerInput(target, cornerPx, insetPx) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (!target.acceptingCaptures) continue
                        val change = event.changes.firstOrNull() ?: continue
                        if (change.pressed && !change.previousPressed) {
                            target.recordRect(
                                bounds = if (insetPx > 0f) bounds.deflate(insetPx) else bounds,
                                cornerPx = cornerPx,
                                color = latestColor,
                                icon = latestIcon
                            )
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
                if (!currentEnabled || !state.acceptingCaptures) continue
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
            // 减少动画：不缩放不位移，退化成短淡入（§5.5 降级要求）。
            // 进入与退出分开定时长：退出起步无延迟、前半程很快，需要更长的收尾才不显仓促。
            durationMillis = when {
                reduceMotion -> 120
                visible -> ContainerTransformMath.ENTER_DURATION_MS
                else -> ContainerTransformMath.EXIT_DURATION_MS
            },
            // LinearOutSlowIn：起步即全速（返回键按下立刻有反馈）、尾段缓缓落位；
            // 中段接近线性，「内容↔图标」的切换窗口才够宽。退出时同一条曲线反向取值 =
            // 快出缓收，不再另做 p^1.6 重映射。
            easing = LinearOutSlowInEasing
        ),
        label = "containerTransform"
    )

    // CR-09 验收 42：每帧同步「主页面能否记录新起点」——退场动画中禁止（否则
    // 点一下屏幕页面就飘到点击点），完全收起后恢复。放在提前 return **之前**，
    // 否则最后一帧（progress 归零）不会执行。
    SideEffect { state.acceptingCaptures = !(visible || progress > 0.001f) }

    // 完全收起后不再绘制，节点随之移除（下次进入重建二级页，符合预期）
    if (!visible && progress <= 0.001f) return

    val palette = LocalFreshPalette.current
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val origin = state.origin

        // 入口矩形（窗口坐标）
        val originLeft: Float
        val originTop: Float
        val originW: Float
        val originH: Float
        if (origin != null && origin.width > 0f && origin.height > 0f) {
            originLeft = origin.left
            originTop = origin.top
            originW = origin.width
            originH = origin.height
        } else {
            // 没有记录到入口（例如深链直达二级页）：居中小幅放大兜底
            originW = width * FALLBACK_START_SCALE
            originH = height * FALLBACK_START_SCALE
            originLeft = (width - originW) / 2f
            originTop = (height - originH) / 2f
        }

        // 进度：进入退出同一条 `progress`（0 = 入口矩形，1 = 全屏）
        val p = progress

        // 系统「打开 / 返回桌面」的观感：**窗口本体等比缩放**（始终是整屏的缩略图，不被压成细条），
        // 而**裁剪框**从入口矩形独立插值到全屏。两者分开，才会既保持页面比例又从图标形状长出来。
        // （上一版把 X / Y 独立缩放直接作用在整页上：40dp 方形图标会拉成一根高瘦的药丸，
        // 真机录屏里就是「一条绿柱子 + 巨大齿轮」。）
        val clipL = if (reduceMotion) 0f else ContainerTransformMath.offset(originLeft, p)
        val clipT = if (reduceMotion) 0f else ContainerTransformMath.offset(originTop, p)
        val clipR = if (reduceMotion) width
        else lerp(originLeft + originW, width, p.coerceIn(0f, 1f))
        val clipB = if (reduceMotion) height
        else lerp(originTop + originH, height, p.coerceIn(0f, 1f))
        val rectW = clipR - clipL
        val rectH = clipB - clipT
        // 圆角在大半程保持入口圆角（系统窗口缩小时一直是大圆角），最后一段才收到 0
        val cornerPx = if (reduceMotion) 0f
        else ContainerTransformMath.cornerRadius(state.originCornerPx, p)

        val contentAlpha = if (reduceMotion) p else ContainerTransformMath.contentAlpha(p)
        val plateAlpha = if (reduceMotion) 1f else ContainerTransformMath.plateAlpha(p)
        val iconAlpha = if (reduceMotion) 0f else ContainerTransformMath.iconAlpha(p)

        // 页面等比缩放比：起点取「刚好盖住入口矩形」的比例，终点 1；中心对齐裁剪框中心
        val s = if (reduceMotion) 1f
        else ContainerTransformMath.coverScale(originW, originH, width, height, p)
        val contentTx = clipL + rectW / 2f - s * width / 2f
        val contentTy = clipT + rectH / 2f - s * height / 2f

        // 底板底色：入口控件的底色 → 页面背景色（随内容淡入完成渐变）。
        val sourceColor = state.originColor.takeIf { it != Color.Unspecified } ?: palette.surface
        val plateColor = if (reduceMotion) {
            palette.background
        } else {
            lerpColor(sourceColor, palette.background, contentAlpha)
        }
        val fullyOpen = clipL <= 0.5f && clipT <= 0.5f &&
            rectW >= width - 0.5f && rectH >= height - 0.5f && cornerPx <= 0.5f

        // ---- 第 1、3 层：底板 + 内容（裁剪框 + 等比缩放的页面）----
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 本层不做变换，只负责按屏幕坐标裁剪：圆角半径就是屏幕上的真实半径
                    shape = RectCornerShape(clipL, clipT, clipR, clipB, cornerPx)
                    clip = !fullyOpen
                }
        ) {
            // 底板：不透明度从 0 渐入——起点处下面就是入口控件本身，不能把它盖住
            Box(
                Modifier
                    .fillMaxSize()
                    .background(plateColor.copy(alpha = plateAlpha))
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = s
                        scaleY = s
                        translationX = contentTx
                        translationY = contentTy
                        alpha = contentAlpha
                    }
            ) {
                content()
            }
        }

        // ---- 第 2 层：入口图标 ----
        // 不放进上面的容器：那个容器是非等比缩放的，图标会被拉扁。这里单独做**等比**变换：
        // 布局尺寸 = 入口矩形尺寸，居中放在当前底板矩形的中心，缩放比 = 底板短边 / 入口短边。
        val icon = state.originIcon
        if (!reduceMotion && icon != null && iconAlpha > 0.01f) {
            // 等比：按裁剪框宽度（窗口比图标高，取短边 = 宽）放大
            // CR-13 定稿：图标最多放大到 1.5×（线性无折点），不再跟着裁剪框短边
            // 放大到占满整块；淡出时机仍按 CR-11 的 0.35–0.65，保留可见锚点。
            val iconScale = ContainerTransformMath.iconScale(p)
            Box(
                modifier = Modifier
                    .size(
                        width = with(density) { originW.toDp() },
                        height = with(density) { originH.toDp() }
                    )
                    .graphicsLayer {
                        transformOrigin = TransformOrigin.Center
                        translationX = clipL + rectW / 2f - originW / 2f
                        translationY = clipT + rectH / 2f - originH / 2f
                        scaleX = iconScale
                        scaleY = iconScale
                        alpha = iconAlpha
                    },
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
        }
    }
}

/**
 * 屏幕坐标里的圆角矩形裁剪框（本层不做缩放，半径即真实半径）。
 * 半径超过短边一半时 [RoundRect] 自动收到最大值，所以圆形按钮起步就是正圆。
 */
private class RectCornerShape(
    private val left: Float,
    private val top: Float,
    private val right: Float,
    private val bottom: Float,
    private val radius: Float
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline = Outline.Rounded(RoundRect(left, top, right, bottom, radius, radius))
}

/**
 * 起点缩放下限：只防止除零 / 极端入口。
 *
 * 曾经是 0.05：但顶部 40dp 圆按钮在 1220×2712 屏上 Y 向占比只有 0.044，被夹到 0.05 后
 * 底板比按钮高 11%，起步就对不上控件。0.01 对应「入口只有屏高的 1%」，真实控件不会这么小。
 */
private const val MIN_START_SCALE = 0.01f

/** 内容开始淡入 / 完成淡入的进度点（前半段只画底板与图标）。 */
internal const val CONTENT_FADE_START = 0.42f
internal const val CONTENT_FADE_END = 0.86f

/** 入口图标开始 / 完成淡出的进度点：在「约一半」附近消失，与内容淡入交叠。 */
/** CR-13 定稿：入口图标最大放大倍数（原来无上限，会变成巨型齿轮）。 */
internal const val ICON_MAX_SCALE = 1.5f

internal const val ICON_FADE_START = 0.35f
internal const val ICON_FADE_END = 0.65f

/** 底板从透明渐变到不透明所需的进度（只在入口矩形附近，避免起止两端盖住入口控件）。 */
internal const val PLATE_FADE_END = 0.12f

/** 圆角保持入口圆角到这个进度，之后收为 0。 */
internal const val CORNER_HOLD = 0.6f

/** 没有入口信息时的兜底起点。 */
private const val FALLBACK_START_SCALE = 0.92f

/**
 * 转场几何：入口矩形 → 全屏的插值。抽成纯函数是为了能单测——「终点是否真的严丝合缝
 * 铺满」「起点是否真的等于入口矩形」这类性质靠肉眼在逐帧截图里判断并不可靠。
 */
internal object ContainerTransformMath {

    /** 进入时长（§5.5：320ms）。 */
    const val ENTER_DURATION_MS = 320

    /**
     * 退出时长。比进入长：LinearOutSlowIn 反向取值时前 20% 时间就缩到约半屏，
     * 后面 80% 用来缓缓落回入口；380ms 下「内容→图标」切换窗口约 80ms（320ms 只有约 68ms）。
     */
    const val EXIT_DURATION_MS = 380

    /** 缩放：入口尺寸 → 1（全屏）。 */
    fun scale(startScale: Float, progress: Float): Float =
        lerp(startScale.coerceIn(MIN_START_SCALE, 1f), 1f, progress.coerceIn(0f, 1f))

    /**
     * 页面等比缩放比：起点 = 刚好盖住入口矩形的比例（`max(ow/W, oh/H)`），终点 = 1。
     * 裁剪框尺寸是 `lerp(入口, 全屏)`，页面尺寸是 `lerp(起点比例, 1) × 屏幕`，
     * 起点比例 ≥ 入口占比，所以任一进度下页面都盖得住裁剪框，不会露出缝。
     */
    fun coverScale(originW: Float, originH: Float, width: Float, height: Float, progress: Float): Float {
        val start = maxOf(originW / width, originH / height).coerceIn(MIN_START_SCALE, 1f)
        return lerp(start, 1f, progress.coerceIn(0f, 1f))
    }

    /**
     * CR-13：入口图标缩放比，线性 1 → [ICON_MAX_SCALE]，全程不超过上限。
     * 旧实现直接取「裁剪框短边 / 入口短边」，会一路放大成巨型齿轮。
     */
    fun iconScale(progress: Float): Float =
        lerp(1f, ICON_MAX_SCALE, progress.coerceIn(0f, 1f))

    /** 圆角：[CORNER_HOLD] 之前保持入口圆角，之后平滑收到 0（全屏无圆角）。 */
    fun cornerRadius(startPx: Float, progress: Float): Float =
        startPx * (1f - smoothStep((progress.coerceIn(0f, 1f) - CORNER_HOLD) / (1f - CORNER_HOLD)))

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

    /**
     * 入口图标不透明度：[ICON_FADE_START] 之前保持 1（图标随底板一起变大），
     * 到 [ICON_FADE_END] 淡到 0。必须在内容完全浮现（[CONTENT_FADE_END]）之前就消失，
     * 否则放大的巨型图标会盖在页面内容上。
     */
    fun iconAlpha(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        return 1f - smoothStep((p - ICON_FADE_START) / (ICON_FADE_END - ICON_FADE_START))
    }

    /**
     * 底板不透明度：入口矩形附近从 0 渐入到 1。
     *
     * 起点处底板与入口控件重合——若这时就不透明，会把控件（文字 / 图标）盖掉，退出收尾时
     * 节点移除，控件又「弹」出来（上一版闪的那一帧）。渐入渐出让两端都是平滑交接。
     */
    fun plateAlpha(progress: Float): Float =
        smoothStep(progress.coerceIn(0f, 1f) / PLATE_FADE_END)

    /** 三次平滑阶跃：0→1，两端斜率为 0。 */
    private fun smoothStep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
