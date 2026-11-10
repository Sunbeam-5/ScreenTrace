# 批次 1 评审报告（Kimi，2026-11-10）

评审对象：commit `b50deb6` feat(uikit): 批次 1——通用组件与页面骨架
评审方式：读全部 uikit/ 源码 + 实际构建 + 对比度/宽度实算，不看提交说明的自我评价

## 一、实测结果

| 项 | 结果 |
|---|---|
| 构建 | `gradlew.bat :app:testDebugUnitTest` → BUILD SUCCESSFUL |
| 单测 | 11 个用例全绿（与提交说明一致） |
| 硬编码色 | uikit/ 非 legacy 与 MainActivity 无 `Color(0x…)` / `Color.White` / `FreshColors`；仅 `Color.Transparent`（结构性透明，可接受） |
| 未接线按钮 | AppHeader 的 onDate/onStyle/onSettings/onBack 为 null 时不渲染；MainActivity 只传 onStyleClick → **符合 §6 禁假按钮，做得最干净的一项** |
| AppHeader 40/48 | 视觉 40dp 圆形 + 外层 48dp 点击区，contentDescription 与 Role.Button 齐全 ✓ |
| NavBar 几何 | 52×2 + 150 + 4×2 + 6×2 = **274dp < 360dp** ✓；点击高 50dp ≥48 ✓ |
| 360dp 横向 | Header 320dp 可用、ContentColumn 520dp 上限居中、Segmented 自适应，无横向溢出 |
| 深色对比度 | 正文/弱化/主色/tonal 实测全部 ≥4.5（5.0–11+），图形 ≥3 ✓ |

## 二、必须修的问题

### P1-1　FreshNavBar「减少动画」会裁掉选中项名称（真 bug）

`FreshNavBar.kt:76` `targetValue = if (selected && !reduceMotion) 1f else 0f`
`FreshNavBar.kt:105` `showLabel = if (reduceMotion) selected else expand > 0.45f`

开启系统减少动画后，目标宽度被强制为 0 → 选中项宽度停在 52dp，但名称仍渲染（图标 22 + 间距 10 + 文字约 42dp = 74dp），超出部分被 `clip(CircleShape)` 裁掉。§5.5 要求此时「改为淡入淡出」，即宽度直接到 150dp、只是不播 spring。

```kotlin
val expand by animateFloatAsState(
    targetValue = if (selected) 1f else 0f,          // 不再受 reduceMotion 影响
    animationSpec = if (reduceMotion) tween(0) else spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    ),
    label = "navExpand"
)
```

### P1-2　浅色 SegmentedControl 未选中文字对比度 4.28–4.45，未达 4.5

`onSurfaceVariant #5F7268` 叠在 `surfaceVariant`（seed 混白 90%）上，九套主题全部不达标：

| 主题 | 比值 | 主题 | 比值 |
|---|---|---|---|
| 薄荷 | 4.43 | 珊瑚橘 | 4.35 |
| 雾蓝 | 4.39 | 琥珀黄 | 4.44 |
| 靛青 | **4.28** | 森绿 | 4.45 |
| 鸢尾紫 | 4.31 | 石墨蓝 | 4.38 |
| 樱粉 | 4.32 | | |

14sp/600 不属于 WCAG 大字号，必须 ≥4.5。修法二选一：
- 全局加深 `onSurfaceVariant`（约 `#556A5F`，可到 4.6+），九套同时受益；
- 或 SegmentedControl 未选中文字改用 `palette.onSurface`。
改完需重新逐套复算，别再手写。

### P1-3　MetricCard 数值 26sp 在 360dp 双卡内只放得下 4 个中文字

单卡 154dp − 20×2 内边距 = **内容 114dp**；26sp 中文约 26dp/字 → 只能容 4 字。
「3小时52分钟」（6 字）必折行，而 §5.5 要求双卡「同一数值排版、等高」——一张折两行一张单行就破相。

建议在批 4 开工前定死规则（三选一）：
1. 数值字号降为 22sp 并补 §5.3 token（卡内可放 5 字），仍可能不够；
2. 数值改紧凑格式（如 `3h52m` / `3:52` + 说明行写完整时长）；
3. 允许两行但两卡统一规则（`maxLines=2`、`softWrap=true`），并接受卡片变高。

## 三、建议修（不阻塞批次 2）

| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| P2-4 | 深色 Preview 只覆盖 1/8 组件（仅 FreshNavBar 有 NIGHT_YES） | uikit/*.kt | 每个组件补一个深色 Preview，支撑 §7-10 人工核对 |
| P2-5 | Tab/导航无「已选中」语义 | SegmentedControl.kt:54、FreshNavBar.kt:94 | 改 `selectable(selected, role = Role.Tab, …)`，TalkBack 才能播报选中态 |
| P2-6 | 「界面风格」按钮实际切深浅色 | MainActivity.kt:78 | 批 3 前改 contentDescription 为「切换深浅色」，或暂不渲染 |
| P2-7 | Header 底部 padding 12dp，§5.2 要求与内容间隔 8dp | ScreenScaffold.kt:119 | 统一为 8dp |
| P2-8 | 48dp 外层未 clip 圆形，ripple 呈方形 | ScreenScaffold.kt:180 | 外层加 `.clip(CircleShape)` |
| P2-9 | RatioBar 0% 仍画 1% 细条 | SegmentedBar.kt:126 | 0 时隐藏该段 |
| P2-10 | SegmentedBar label 允许为空，图例出现空行 | SegmentedBar.kt:80 | label 空时跳过该段或断言非空 |
| P2-11 | floatingNav 无 navigationBarsPadding | ScreenScaffold.kt:64 | 现非 edge-to-edge 不暴露；一旦启用会被手势条压住，建议提前加 |

## 四、结论

**批次 1 通过，可进入批次 2。** 骨架、组件 token 化、禁假按钮三项核心目标达成，构建与 11 单测实证通过。

三个 P1 在批次 2 期间顺手修掉即可（P1-1 是 2 行真 bug、P1-2 是色板一处值、P1-3 需先定格式规则——批 2 不依赖 MetricCard，不阻塞）。
P2-4 建议随批次 2 一起补，成本极低；其余排入批 4/批 9。
