# CR-30 阶段 A-3：chrome 组件级定位报告

> **性质**：只做定位，不实施修复。Pager 预加载数量、CR-28 数据架构/数据库/备份/来源口径零改动；
> 未隐藏页面、未删除必要状态、未破坏现有功能（全部实验改动为临时构建，测后已无条件还原，
> 仅保留 debug-only tick 探针与 A-1 同款 PerfProbe，release no-op）。
>
> 设备：Redmi 2407FRK8EC，120Hz，far 切换（今日↔日历）为主测场景；测量日期 2026-11-18。
> 方法：**组件级探针映射**（10 个 tick 点）+ **受控二分**（V1–V5）+ **Perfetto 三段拆分**。

## 1. 组件级探针映射：切换时「谁在重组」

一次 far 切换（滚动 484ms）的完整重组序列（logcat tick 时间戳）：

| 时段 | 组件 | 执行次数 | 说明 |
|---|---|---|---|
| 滚动动画全程 | **AppHeader** | **15** | 逐帧级（约每 16ms 一次） |
| 滚动动画全程 | **FreshNavBar** | **15** | 逐帧级，与 AppHeader 成对 |
| 切换帧爆发（135ms 窗口） | AppNav（根） | 1 | 路由写回触发 |
| 同上 | Theme（ScreenTimeTheme） | 1 | |
| 同上 | PullZone / CTO / DPO | 各 1 | **不消费 mainPage 的无谓传播** |
| 同上 | ScrollablePage ×3 | 3 | 三个页面包装各 1 次 |
| 同上 | 页根 Today/Stats/Calendar | 各 1 | 间隔 36–46ms，落在长帧窗口内 |
| — | DateSheet / StyleSheet | 0 | 未打开，排除 |

**关键结论 1**：切换帧爆发 = **12+ 个重组域集中重执行**（与 A-2 长帧分解的 5–7 个
Compose:recompose 切片对应）；动画期间另有 **AppHeader+FreshNavBar 每帧级重组**持续施压。

## 2. 触发重组的状态与传播（代码证据）

| 触发状态 | 读取位置 | 传播范围 | 后果 |
|---|---|---|---|
| pager offset（`derivedStateOf { currentPage + offsetFraction }`，AppNav.kt:380） | **ScreenScaffold.kt:227** `titleShiftDp(it.value)`、**FreshNavBar.kt:148** `navProximity(index, progress.value)` | 组合期读取 → 两个组件**整棵子树**每帧重组 | 动画期中段帧 p90/p95 21/24→13/14ms（实测） |
| `mainPage` 路由写回（alignRouteToPager，滚动结束） | AppNav 根订阅 | AppNav 整树 → 12 域全量重执行 | 切换帧长尾的爆发源 |
| PageEnter 的 `progress.value`（PageEnter.kt:33/56） | 位于 `graphicsLayer {}` 绘制期 | 不触发重组 | **排除嫌疑** |

**关键结论 2（GPT 要求 3）**：高频状态确实向不需要它的组件传播——`mainPage` 变化使
PullZone/CTO/DPO（不消费该值）重执行；offset State 的组合期读取使 AppHeader/FreshNavBar
子树逐帧重组（AppHeader 内含 Crossfade 标题切换，正是人工验证「标题切换生硬」的所在）。

## 3. Composition / Measure / Layout / Draw 拆分（GPT 要求 2）

| 长帧（Redmi expA far 188.3ms） | 耗时 | 占比 |
|---|---|---|
| **Composition**（动画阶段重组 1 遍 + 布局阶段 5–7 遍 + applyChanges） | ≈145–183ms | **≈80%+** |
| **Measure/Layout**（扣除嵌套重组后的纯布局） | ≈30–40ms | ≈15–20% |
| **Draw / Record**（扣除嵌套后的纯绘制记录） | ≈2–3ms | ≈1% |
| RenderThread / GPU / GC / 调度 | ≤10ms / 无 / 无 | — |

> 嵌套说明：trace 中 `Record View#draw()` ⊃ `AndroidOwner:measureAndLayout` ⊃ 重组切片，
> 上表已按包含关系扣重。长帧主体是**重组执行**，布局其次，绘制可忽略。

## 4. 受控二分（每轮 4 次切换 ×3 重复 + 1 trace）

| 构建 | 变量 | 长帧 max | 中段帧 p90/p95 | 长帧分解变化 |
|---|---|---|---|---|
| A 基线（探针构建） | — | ≈201ms（A-2 同型） | 21/24 | 基准 |
| V1 | FreshNavBar 逐帧读取中和 | 191ms | **14/15** | 长尾不变 |
| V2 | +AppHeader 逐帧读取中和 | 226ms | **13/14** | 长尾不变 |
| V3 | +覆盖层壳短路（PullZone/CTO/DPO） | 186ms | 13/14 | 长尾不变 |
| V4 | +ScrollablePage 结构剥离（scroll/padding） | 210ms | 13/14 | 长尾不变 |
| D（A-2） | 仅空内容（chrome 全开） | 229ms | 21 | 长尾不变 |
| **V5** | **四变量全中和 + 空内容** | **78.9ms（−60%）** | 12.8 | 剩余＝动画阶段重组 53ms + 布局 17ms |

⚠️ **方法披露**：V2–V4 为**累积叠加**（V2 含 V1，以此类推），非严格独立单变量；
单变量贡献可直接读 V1，组合效应以 V4/V5 为准。原始数据 `C:/tmp/cr30_a2/a3raw/`。

**关键结论 3（成本归属与交互）**：
1. **逐帧 chrome 重组**（AppHeader/FreshNavBar）与**页面树重执行**在爆发窗口**叠加**，
   两者**任一单独消除都不降长尾**（D、V1–V4 均 ≈200ms），**同时消除才降到 79ms**——
   长尾是两类成本相乘的窗口重压，不是单一元凶。
2. V5 残余 79ms = AppNav/Theme/页包装的爆发重组 53ms + 轻布局——仍可继续压缩，
   且**长帧可削减 60%+ 直接证明其非 Pager/框架固有成本**（进一步否定预加载根因论）。
3. 修复一个入口即可改善中段帧（V1/V2 各让 p90 降约 35%），两个入口都修 + 页面稳定化
   才能动长尾。

## 5. 修复方向建议（本批不实施，A-3 交审后按批推进）

1. **逐帧读取下沉绘制期**：AppHeader 标题位移、FreshNavBar 邻近度改走 `graphicsLayer`
   绘制期读取（PageEnter.kt 已是正确范式），或参数稳定化——预期消中段帧重组。
2. **`mainPage` 传播裁剪**：PullZone/CTO/DPO 等不消费方参数稳定化（@Immutable/常量），
   爆发域数 12 → 预期 ≤5。
3. **页面参数稳定化**：页根入参 @Immutable + lambda 提升，父级重组时页根可跳过——
   对照 V5 已证明页面树重执行是长尾主项之一。
4. **标题/日期区过渡**（衔接人工验证「标题切换生硬」）：换值加短交叉淡变。
5. **残余 79ms 归因**：需 composition tracing 命名（下批做开 Tracing 的测量构建，
   把爆发内重组切片落到具体函数）。
6. **Tab 直达过渡**（产品方向已裁定：目标页直达 + 160–200ms 淡入淡出，横滑保留原生跟手）：
   A-3 交审后单独立项做方案与小范围原型；**不把缩短动画当卡顿修复**（B 组证据）。

## 6. 与最终验收的对应（供实施批测量基线）

| GPT 验收条件 | A-3 已提供 |
|---|---|
| UI 线程长帧显著下降 | V5 已证明可达 −60%（79ms），修复路径明确 |
| vivo 60Hz 无严重顿挫 | 长尾双成分结论对 vivo 同样适用（vivo 内容成本更大，收益更高） |
| 双机 60/120 真机验证 | 实施批执行（沿用 A-1/A-2 矩阵协议） |
| 其余（直达过渡/横滑跟手/连点不错位/CR-28 不破坏） | 属实施批；本批零改动 |

## 7. 边界与证据

- 本批零改动：Pager 预加载数量、导航架构、CR-28 数据架构、数据库、备份、来源口径。
- 每轮一变量（V2–V4 累加已如实披露），对照数据全保留：`C:/tmp/cr30_a2/a3raw/`
  （v1_navfreeze / v2_hdrfreeze / v3_overlays / v4_scrollstrip / v5_allstrip × 4 轮）。
- 实验代码测后全部还原；保留的仅为 10 个 debug-only tick 探针（与 A-1 PerfProbe 同族）。
- 未隐藏页面/未删必要状态来「降低重组」——V5 空内容为诊断性实验且已还原，产品行为零变化。
