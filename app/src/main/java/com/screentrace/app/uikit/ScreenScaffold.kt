package com.screentrace.app.uikit

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.screentrace.app.theme.FreshType
import com.screentrace.app.data.wallpaper.WallpaperCropMath
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.theme.LocalWallpaper
import com.screentrace.app.theme.ScreenTimeTheme

/**
 * 页面骨架（批 6g 重构）：背景 + 状态栏内边距 + 顶部栏 + **占满剩余高度的内容区**。
 *
 * 与旧版的关键区别：这里**不再自带纵向滚动**。旧版把「顶部栏以下的所有东西」包在
 * 一个 `verticalScroll` 里，于是三个主页面只能共用一个 `ScrollState`——
 * 从「今日」滚到一半切到「统计」，统计页会直接从同样的偏移开始画，看起来就像
 * 内容突然蹦出来。现在滚动下沉到 [ScrollablePage]，每个页面自己一份滚动状态，
 * Pager 里多页并存时互不影响，返回上级页也天然保住原位置。
 *
 * 底部悬浮导航不在这里，由 `AppNav` 画在最外层（要压在详情覆盖层之上）。
 */
@Composable
fun PageScaffold(
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val palette = LocalFreshPalette.current
    // §5.4 批 E：壁纸走 CompositionLocal 的单份位图，主页面层与二级页覆盖层共用；
    // 无壁纸时才画 CR-07 的三段渐变。绘制顺序必须与 WallpaperTuning 的亮度计算一致：
    // 壁纸 → 用户暗化（scrim 黑）→ 主题底色（浅/深 background 自动切换）。
    val wallpaper = LocalWallpaper.current
    // 背景铺满整个 Box（含状态栏后面），内容侧才做 statusBarsPadding：
    // 否则状态栏那一条会露出系统底色（§「上面有一条黑边」的根因）。
    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (wallpaper == null) Modifier.background(
                    // CR-07：三段渐变（0% 顶部 / 18% 中段 / 40% 起落回 background），
                    // 背景固定在屏幕后，不随内容滚动
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to palette.gradientTop,
                        0.18f to palette.gradientMid,
                        0.40f to palette.background
                    )
                ) else Modifier
            )
    ) {
        if (wallpaper != null) {
            // §5.4 CR-19 ③：壁纸改「完整显示 + graphicsLayer 变换」支持裁切；
            // 旧数据（cropScale=1f）在绘制时自动抬到恰好盖满，观感与原 Crop 一致。
            // §5.4 CR-24：贴**已经模糊好**的位图（WallpaperBlur 一次性算好），
            // 不再挂 `Modifier.blur`——那是每帧 RenderEffect，三页常驻时被乘以 3。
            // 裁切几何跟着这张图的尺寸走（与原图同宽高比，结果一致）。
            val bmpW = wallpaper.blurred.width.toFloat()
            val bmpH = wallpaper.blurred.height.toFloat()
            Image(
                bitmap = wallpaper.blurred,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val geo = WallpaperCropMath.geometry(bmpW, bmpH, size.width, size.height)
                        val zoom = if (geo != null) maxOf(wallpaper.cropScale, geo.minZoom) else wallpaper.cropScale
                        scaleX = zoom
                        scaleY = zoom
                        translationX = wallpaper.cropOffsetX * size.width
                        translationY = wallpaper.cropOffsetY * size.height
                    }
            )
            if (wallpaper.dimAlpha > 0f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(palette.scrim.copy(alpha = wallpaper.dimAlpha))
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(palette.background.copy(alpha = wallpaper.themeScrimAlpha))
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            header()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                content()
            }
        }
    }
}

/**
 * 可滚动内容页：左右边距 20dp、卡片间距 12dp、底部预留 112dp 给悬浮导航；
 * 宽屏内容最大 520dp 居中（§5.2）。
 *
 * `fillMaxSize` + `verticalScroll` 的组合让页面高度等于视口、内容超出才滚，
 * 这样它放进 Pager 的每一页时尺寸稳定，不会因为内容长短把 Pager 撑变形。
 */
@Composable
fun ScrollablePage(
    modifier: Modifier = Modifier,
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
            // 底部预留 = 悬浮导航高度 + 系统手势条。只留 112dp 时，全面屏机型上
            // 手势条会把「查看全部应用」这类最后一屏内容压住（真机可见）。
            // 【Step 7 复核结论 2026-11-13，Redmi 1220×2712】此值**足够，不要再加大**：
            // 滚到底时「查看全部应用」bottom=2181px，导航胶囊 top≈2400px，末尾内容已在胶囊上方；
            // 首屏看到日间/夜间行与胶囊重叠，是「固定悬浮覆盖层 + 长内容滚动前自然交叠」，
            // 不是 viewport / padding 缺陷。加留白只会让首屏更空、滚到底拖出一大片空白。
            // 观感由 §4.6 的胶囊质感（92% 白 + 描边 + 阴影）解决，不靠堆 padding。
            .padding(bottom = 112.dp)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

/**
 * §5.2（CR-15 ③ 批 D → **CR-20 ① 拆回直出**）：左标题 + 日期副标题 + 右侧动作按钮。
 * - **主页面**：右侧横排**四枚独立圆按钮**（切换设备 / 日期 / 主题 / 设置），
 *   各接一个真实动作；**日历 tab 三枚**（不渲染「选择日期」，§5.6）。
 * - **二级页**（排行 / 解锁详情 / 通知详情）：只渲染「返回 + 标题 + 可选日期」，
 *   参数全带默认值，因此二级页与 `@Preview` 调用点零改动。
 * 视觉 40dp、点击区 ≥48dp；主题按钮三色圆点自绘（不引 icons-extended）。
 * 未传入的按钮不渲染——调用方只接线真实可用的动作，避免假按钮（§6）。
 *
 * 标题与副标题走 `Crossfade`：主页面之间横滑时标题是跟着换的，
 * 硬切会让顶部像"被抽掉再贴上一张"，交叉淡入淡出才跟得上页面滑动。
 */
@Composable
fun AppHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /**
     * §5.2 CR-23：标题块的横向位移（dp），按「目标页 − 连续进度」算。
     *
     * **签名是 `(progress: Float) -> Float` 而不是 `() -> Float`**：位移必须每帧更新，
     * 而 Header 里的 `Crossfade` 是靠它自己的 `Animatable` 驱动的，**并不逐帧重组**；
     * 只把 lambda 写进 `graphicsLayer {}` 也不行——那个块只在本节点重绘时才跑。
     * 所以进度由调用方以 [tabProgress] 传入、在**本组合**里 `it.value` 读一次
     * （与 `FreshNavBar` 的 `progress` 同一套路），才拿得到逐帧更新。
     * 默认 null = 不位移（二级页 / Preview 一律零改动）。
     */
    tabProgress: State<Float>? = null,
    titleShiftDp: (progress: Float) -> Float = { 0f },
    onBack: (() -> Unit)? = null,
    /** §5.2 CR-20 ①：主页面「切换设备」——打开设备列表面板浮层。 */
    onDevicesClick: (() -> Unit)? = null,
    onDateClick: (() -> Unit)? = null,
    onStyleClick: (() -> Unit)? = null,
    onSettingsClick: (() -> Unit)? = null
) {
    val palette = LocalFreshPalette.current
    // CR-23：标题位移按连续进度**每帧求值**。不能只写进 `graphicsLayer {}` 就算数——
    // 那个块只在本节点重绘时才跑，而 Header 不随 Pager 滚动重绘，位移会一直停在首帧的 0
    // （真机 PTS 抽帧实测：标题左边缘全程齐平，36dp≈122px 完全没动）。
    // 改成与 `FreshNavBar` 的 `progress` 同一套路：在**组合期**读 `State<Float>`，
    // 于是每帧重组 AppHeader 一小段（不惊动整棵 `AppNav`），`graphicsLayer` 再把值画出去。
    val titleShiftPx = with(LocalDensity.current) {
        (tabProgress?.let { titleShiftDp(it.value) } ?: 0f).dp.toPx()
    }
    if (kotlin.math.abs(titleShiftPx) > 2f) {
        android.util.Log.d("TabNav", "titleShiftPx=${titleShiftPx}")
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 8.dp), // §5.2：与内容间隔 8dp
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            HeaderActionButton(contentDescription = "返回", onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = palette.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (onBack != null) 4.dp else 0.dp)
                // CR-23：标题块随连续进度轻微位移（落定归 0）。值在上面组合期算好，
                // 这里只负责画出去（每帧重组时块里的捕获值随之更新）。
                .graphicsLayer { translationX = titleShiftPx }
        ) {
            Crossfade(
                targetState = title,
                animationSpec = tween(180),
                label = "headerTitle"
            ) { text ->
                Text(text = text, style = FreshType.pageTitle, color = palette.onSurface)
            }
            if (subtitle != null) {
                Crossfade(
                    targetState = subtitle,
                    animationSpec = tween(180),
                    label = "headerSubtitle"
                ) { text ->
                    Text(text = text, style = FreshType.label, color = palette.onSurfaceVariant)
                }
            }
        }
        if (onDevicesClick != null) {
            // §5.2 CR-20 ①：「切换设备」打开的是**浮层**（设备列表面板），不是容器变换页面，
            // 所以不登记 transformSource——免得这枚按钮把自己记成转场起点。
            HeaderActionButton(
                contentDescription = "切换设备",
                onClick = onDevicesClick,
                registerTransform = false
            ) {
                HeaderActionIcon(HeaderAction.Devices)
            }
        }
        if (onDateClick != null) {
            HeaderActionButton(contentDescription = "选择日期", onClick = onDateClick) {
                HeaderActionIcon(HeaderAction.Date)
            }
        }
        if (onStyleClick != null) {
            HeaderActionButton(contentDescription = "界面风格", onClick = onStyleClick) {
                HeaderActionIcon(HeaderAction.Theme)
            }
        }
        if (onSettingsClick != null) {
            HeaderActionButton(contentDescription = "设置", onClick = onSettingsClick) {
                HeaderActionIcon(HeaderAction.Settings)
            }
        }
    }
}

/**
 * CR-10 / §4.9：主题按钮的「三色圆点」线性图标。
 * icons-core 没有调色板图标，不为此引入 icons-extended；三个点取主色色阶的深/中/浅，
 * 与「一个主色派生全套色阶」的既有原则一致。
 */
@Composable
internal fun ThemeDotsIcon() {
    val palette = LocalFreshPalette.current
    Canvas(modifier = Modifier.size(22.dp)) {
        val radius = 3.4.dp.toPx()
        val cy = size.height / 2f
        val colors = listOf(palette.primary, palette.chart2, palette.chart4)
        val xs = listOf(size.width * 0.2f, size.width * 0.5f, size.width * 0.8f)
        xs.forEachIndexed { i, x ->
            drawCircle(color = colors[i], radius = radius, center = Offset(x, cy))
        }
    }
}

/** §5.2：视觉 40dp 圆形按钮、点击区 48dp；语义 token 无硬编码色。 */
@Composable
internal fun HeaderActionButton(
    contentDescription: String,
    onClick: () -> Unit,
    /**
     * §5.2 CR-20 ①：是否把本按钮登记为容器变换起点。
     * 只有**会打开容器变换页面**的按钮才需要（「设置」）；开浮层的按钮
     * （「切换设备」）传 false，免得它把自己记成起点、把设置页的放大位置带偏。
     */
    registerTransform: Boolean = true,
    icon: @Composable () -> Unit
) {
    val palette = LocalFreshPalette.current
    Box(
        modifier = Modifier
            .size(48.dp)
            // 容器变换起点：这个按钮的矩形与圆形圆角（§5.5）。覆盖层里的按钮
            // 读不到 Provider（为 null），因此不会把自己登记成新起点。
            // 登记的是 40dp 可见圆（点击区每边多 4dp），底色与图标一并上报，
            // 覆盖层才能放大「这个按钮本身」而不是一块同色空板。
            .then(
                if (registerTransform) {
                    Modifier.transformSource(
                        cornerRadius = 20.dp,
                        containerColor = palette.buttonSurface,
                        inset = 4.dp,
                        icon = {
                            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { icon() }
                        }
                    )
                } else {
                    Modifier
                }
            )
            .clip(CircleShape) // ripple 与点击区同为圆形
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(palette.buttonSurface),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
    }
}


@Preview(widthDp = 360, showBackground = true)
@Composable
private fun AppHeaderPreview() {
    ScreenTimeTheme {
        Column {
            AppHeader(
                title = "屏迹",
                subtitle = "今天 · 11 月 10 日",
                onDateClick = {},
                onStyleClick = {},
                onSettingsClick = {}
            )
            AppHeader(title = "全部应用", subtitle = null, onBack = {}, onDateClick = {})
        }
    }
}
