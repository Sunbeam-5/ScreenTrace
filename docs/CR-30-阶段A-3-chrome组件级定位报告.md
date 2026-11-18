# CR-30 阶段 A-3：chrome 组件级定位报告（R1 修订版）

> **性质**：只做定位，不实施修复。Pager 预加载数量、CR-28 数据架构/数据库/备份/来源口径零改动；
> 未隐藏页面、未删除必要状态、未破坏现有功能（全部实验改动为临时构建，测后已无条件还原，
> 仅保留 debug-only tick 探针与 A-1 同款 PerfProbe，release no-op）。
>
> 设备：Redmi 2407FRK8EC，120Hz，far 切换（今日↔日历）为主测场景；测量日期 2026-11-18。
> 方法：**组件级探针映射**（10 个 tick 点）+ **累积消融**（V1–V5）+ **Perfetto 帧精算**。
> **R1 修订**：按 GPT 深度复审六项更正——路由时间线、V1–V5 实验性质、构建与证据补编、
> tick 语义、帧拆分对账、设备外推。可复现 patch 与原始证据见 `docs/CR-30-A3-实验补证/`。

## 1. 组件级探针映射：切换时「谁在执行」

一次 far 切换（滚动窗口 484ms）的 tick 序列（logcat 时间戳，完整记录见证据目录）：

| 时段 | 组件（探针位置） | 执行次数 | 说明 |
|---|---|---|---|
| 滚动段（+12ms 起） | **AppHeader** | **15** | 滚动段间隔 ~16ms（120Hz 每 2 帧），**非逐帧**；窗口均值 32ms |
| 同上 | **FreshNavBar** | **15** | 与 AppHeader 成对（同一状态源驱动） |
| 路由写入后（+240ms 起，135ms 窗口） | AppNav（根） | 1 | 路由状态变化触发 |
| 同上 | Theme（ScreenTimeTheme） | 1 | |
| 同上 | PullZone / CTO / DPO | 各 1 | **不消费 mainPage 的无谓传播** |
| 同上 | ScrollablePage ×3 | 3 | 三个页面包装各 1 次 |
| 同上 | 页根 Today/Stats/Calendar | 各 1 | 间隔 36–55ms |
| — | DateSheet / StyleSheet | 0 | 未打开，排除 |

**tick 语义（GPT 要求 4 更正）**：tick ＝该 Composable **函数体执行一次**，**不代表整个子树
全部重组**——子节点可能被 Compose skip，本构建未逐层验证。故本表统一称
**「观测到执行的组合域/探针位置」**而非「重组域」。

**R2 更正一（GPT 复审）**：`Compose/PullZone` 探针实际写在 AppNav 函数体内（调用点），
**不是独立 PullZone Composable 的组合探针**——只能证明 AppNav 执行到 PullToRefreshContainer
调用位置，不能证明该组件子树重组。已更名为 `AppNav/PullToRefreshCallsite`；本报告及
A-2 中 PullZone 计数一律归类为**父级代码位置观察值**。CTO/DPO/AppHeader/FreshNavBar/
ScrollablePage/页根探针位于各自 Composable 函数内，无此问题。restart scope 划分未逐个验证；
这些位置同处 AppNav 父组合域、由同一 `mainPage` 状态传播触发（时间戳连续 +240–391ms），
是判断「同一状态传播触发」的依据。「逐帧级」旧表述不准确：实测滚动段间隔 ~16ms
＝**每 2 帧一次（半帧率级）**。

## 2. 触发重组的状态与传播（代码证据）

| 触发状态 | 读取位置 | 传播范围 | 后果（实测） |
|---|---|---|---|
| pager offset（`derivedStateOf { currentPage + offsetFraction }`，AppNav.kt:380） | **ScreenScaffold.kt:227** `titleShiftDp(it.value)`、**FreshNavBar.kt:148** `navProximity(index, progress.value)`（组合期读取） | 两个组件函数体按 ~16ms 间隔反复执行 | 动画期中段帧 p90/p95 21/24→13/14ms（V1/V2 实测） |
| `mainPage` 路由写入（**滚动中段**，见 §2.1） | AppNav 根订阅 | AppNav 整树 → 观测到 12+ 个组合域执行 | 切换帧长尾的爆发源 |
| PageEnter 的 `progress.value`（PageEnter.kt:33/56） | 位于 `graphicsLayer {}` 绘制期 | 不触发重组 | **排除嫌疑** |

### 2.1 路由变化真实时间线（R1 修订：原「滚动结束才写路由」表述有误）

实际路径：Pager currentPage 变化 → snapshotFlow → TabScrollGuard **忽略中间页、放行目标页**
→ viewModel.selectMain(target) → AppNav 重组；alignRouteToPager 仅在滚动协程结束时做
**最终对齐**。far 切换（今日→日历）实测时间戳（相对 scrollStart，证据
`evidence/a3_timeline_r1.txt`）：

| 相对时刻 | 事件 | 概念类别 |
|---|---|---|
| +0ms | Pager/scrollStart | 动画路径 |
| +12ms 起 | AppHeader/FreshNavBar 成对执行，间隔 ~16ms | 子 Composable 函数执行 |
| +138ms | Route/currentPage=1 → guardIgnore=1（中间页被拦截） | 路由状态（忽略） |
| **+238ms** | Route/currentPage=2 → **selectMain(Calendar)** | **路由状态变化（滚动中段）** |
| +240ms | AppNav#5 → Theme#5 | AppNav 函数执行 |
| +245–264ms | AppHeader#17 / PullZone / CTO / FreshNavBar#17 / DPO | 子 Composable 函数执行 |
| +280–391ms | ScrollablePage#7/8/9 + Stats/Calendar/Today 页根 | 子 Composable 函数执行 |
| 滚动结束 | Route/align + Pager/scrollEnd | 最终对齐 |

**概念区分（全文统一口径）**：① 路由状态变化＝viewModel.page 写入；② AppNav 函数执行；
③ 子 Composable 函数执行（tick 可观测）；④ **子树实际重组**——③ ≠ ④（tick 不证明子树
全量重组）；⑤ Measure/Layout；⑥ Draw/Record。⑤⑥ 见 §3 精算。

## 3. Composition / Measure / Layout / Draw 精算（R1 修订：同帧可对账）

**代表长帧**：frame_token=**537421352**，ts=897391214503556ns，dur=207.2ms（Dropped Frame，
含 queue/present）；其 UI 线程 doFrame：ts=897391214605402，dur=**197.5ms**。
拆分法：inclusive＝切片自身时长；exclusive＝本层 − 直接子层之和（`a3_frame_detail.py` 可复算）。

| 层级 | 切片 | inclusive | exclusive | 归类 |
|---|---|---|---|---|
| doFrame | Choreographer#doFrame | 197.5 | 0.1 | — |
| └ animation | animation | 34.0 | 0.9 | — |
| &nbsp;&nbsp;└ | Recomposer:recompose | 33.1 | 3.0 | 重组调度 |
| &nbsp;&nbsp;&nbsp;&nbsp;└ | Compose:recompose / applyChanges | 23.2 / 7.0 | 23.2 / 7.0 | **Composition** |
| └ traversal | traversal | 163.4 | 0.1 | — |
| &nbsp;&nbsp;└ | Record View#draw() | 162.7 | 2.5 | **Draw/Record** |
| &nbsp;&nbsp;&nbsp;&nbsp;└ | AndroidOwner:measureAndLayout | 160.2 | **30.3** | **Measure/Layout** |
| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;└ | Compose:recompose ×7（叶） | 31.1/2.3/7.5/17.6/2.0/21.5/29.4 | 同左 | **Composition** |
| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;└ | Compose:applyChanges ×5（叶） | 4.7/1.8/2.9/5.5/3.5 | 同左 | **Composition** |

**对账**：Composition（160.0）+ Measure/Layout（30.3）+ Draw/Record（约 3.2，含 draw-VRI
杂项）+ 重组调度/杂项（约 4.0）= **197.5ms = doFrame 时长** ✓。

**结论（已可定量）**：该帧 Composition ≈ **81%**、Measure/Layout ≈ 15%、Draw/Record ≈ 2%。
（注：单帧单样本定量结论，长帧个体间比例有波动；A-2 多帧观察与此一致。）

## 4. 累积消融 V1–V5（R1 修订：实验性质与结论口径更正）

**实验性质（重要）**：V1–V5 是**累积叠加消融**，不是完整的独立单变量实验——
V1 单独验证 FreshNavBar 中和；**V2 = V1 + AppHeader，无法单独得出 AppHeader 独立贡献**；
V3 = V2 + PullZone/CTO/DPO；V4 = V3 + ScrollablePage 结构调整；V5 = V1–V4 叠加 + 空页面内容。
每构建细节（基线 SHA 8babcba、diff、tick/PerfProbe 保留、内容、设备、时间）见
`docs/CR-30-A3-实验补证/patches/`（可复现 patch 全量留存）。

| 构建 | 累积变量 | 长帧 max | p50/90/95/99 | >16.7ms | >50ms | >100ms |
|---|---|---|---|---|---|---|
| A 基线 | — | 207.2 | 8.5/18.9/21.8/29.9 | 60 | 4 | 4 |
| V1 | +FreshNavBar 读取中和 | 191.0 | 8.8/13.8/15.1/20.6 | **10** | 4 | 4 |
| V2 | +AppHeader 读取中和 | 226.4 | 8.4/13.1/14.3/23.1 | 13 | 4 | 4 |
| V3 | +覆盖层壳短路 | 186.2 | 8.3/13.1/14.0/22.4 | 13 | 4 | 4 |
| V4 | +ScrollablePage 剥离 | 210.4 | 8.3/13.4/14.3/21.5 | 11 | 4 | 4 |
| **V5** | **+空页面内容** | **78.9** | 7.7/12.8/13.5/23.4 | 16 | 4 | **0** |

（单 trace/构建；连读长帧对 3；原始数据与统计脚本见证据目录。A-2 D 组＝仅空内容 229ms
作交叉参照。）

**结论（按证据强度表述）**：
1. 中段帧改善可**单独归因 V1**（唯一独立单变量）：>16.67ms 帧 60→10，p90 18.9→13.8。
   V2 的独立贡献**无法**从本设计中分离。
2. **V5 组合性诊断构建显示显著可削减成本**（长帧 207→79ms、>100ms 帧 4→0）——
   这**证明成本可压缩**，但**不是产品修复收益承诺**；各变量对长尾的独立贡献
   （含是否为「叠加/交互」关系）**未证实**，需独立单变量实验（A-4-E1/E2 即其二）。
   **R2 更正二（GPT 复审，重要）**：A/V1–V4 的页面根带 `PerfProbe.tick`（AtomicInteger
   + Log.d 开销），V5 空内容使三个页面根 tick 同时消失——**探针开销不一致**，
   207→79ms 不能完全归因内容成本。V5 削减幅度降格为**初步诊断结果，不作量化结论**；
   干净构建等开销复测数据见 A-4 报告「R2 复测插节」：等开销干净对照下极端长帧削减
   **依然成立**（max 215.7→87.0、>100ms 12→0），「成本可压缩」结论仅限极端长帧；
   **R3 口径修订（GPT 复审）**：干净对照下 p90/95/99 变差（18.4/21.4/27.4→
   22.1/25.5/32.9）、>16.67ms 帧反增（189→266）——V5 **降低极端长帧但未改善整体
   帧耗时分布**（长帧被打散为更多中帧，总负担未消），**不得表述为「整体流畅度
   提升 60%」**；且 V5 仍属多变量＋空内容组合诊断构建，不是产品优化收益，
   不外推到真实页面或 vivo。
3. 不能据此断言「任一单独消除均无效」或「乘法效应」——A-2 的 D 组与本组 V1–V4
   单独观察长尾仍在，但样本与变量独立性不足以下此结论，仅作观察记录。
4. V5 残余 79ms 分解：动画阶段重组 53ms + 轻布局（frame 精算同 §3 方法）。

## 5. 修复方向建议（本批不实施；A-4-E1/E2 为其独立验证）

1. **逐帧读取下沉绘制期**（A-4-E1/E2 正在独立原型验证）：AppHeader 标题位移改
   `graphicsLayer` 内读取；FreshNavBar 需区分宽度（布局）/颜色透明度（绘制）路径，
   不能整体照搬。
2. `mainPage` 传播裁剪：PullZone/CTO/DPO 等不消费方参数稳定化——**待独立单变量实验**。
3. 页面参数稳定化：**待独立单变量实验**（不随意加 @Immutable 强提跳过率）。
4. 标题/日期区过渡（衔接人工验证「标题切换生硬」）。
5. 残余 79ms 组件命名：composition tracing 测量构建。
6. **Tab 直达过渡**（产品方向已裁定：目标页直达 + 160–200ms 淡入淡出，横滑保留原生跟手）：
   单独立项；**不把缩短动画当卡顿修复**（A-2 B 组证据）。

## 6. 设备外推修正（R1 要求 6）

**A-3 本轮组件级实验仅覆盖 Redmi 120Hz**。V5 的削减幅度（207→79ms）**不外推**到
vivo 60Hz，不承诺任何机型收益幅度。A-2 的双设备结论（长尾双成分模型、D 组在 vivo
削长帧约 2/3）属 **A-2 测量结果**，与 A-3 本轮 Redmi 组件级测量**分开陈述**，不可混用
外推。原型收益须按 §7 测量规则在两台 × 60/120Hz 分别实测。

## 7. 测量规则与边界（本批及原型批遵守）

- **原型测量规则**（A-4-E1/E2）：同设备/频率/方向/真实内容/预热/脚本；诊断探针构建
  用于观察执行与时间线，**正式收益用不带高频 Log.d tick 的干净构建复测**；
  报告 p50/90/95/99、最大帧、>16.67/33.33/50/100ms 帧数、连续长帧、代表性 trace；
  先 Redmi 再 vivo，各 60/120Hz；人工手指操作录屏为最终体验证据（adb 不作数）。
- **原型不入主线**：独立提交/分支，GPT 复审前不合 main。
- 本批零改动：Pager 预加载、导航架构、CR-28 全边界；实验代码测后全部还原；
  V5 空页面为诊断性实验且已还原，产品行为零变化。
- 可复现性：`docs/CR-30-A3-实验补证/`（patch 集、代表性 logcat、代表帧 trace.gz
  frame_token=537421352、统计脚本、哈希清单）。

## 8. R3 附记：帧唯一化复核与刷新率适配口径（2026-11-18，GPT 复审第三轮）

**帧唯一化（R3 要求 1/2/7）**：`a3_stats.py`/`a3_frame_detail.py` 已按 (frame name,
layer_name, upid) 组合键对 `actual_frame_timeline_slice` 唯一化，不得以查询行数当帧数；
逐 trace 输出原始行数/唯一帧数/重复帧 ID 数/同 ID 的 ts,dur 一致性/重复 Track 分布。
**全量重算结果**：A-3 与 A-4 全部现有 trace 在本应用 upid 内 **raw=unique、dupIDs=0、
ts/dur 一致**——GPT 复审发现的重复帧（frame name 538008274 ×2）位于**非本应用 Track**
（未按 upid 过滤时出现），不进入本应用统计。p50/90/95/99、max、阈值计数、连续长帧
现均基于唯一帧；**历史数值（§3/§4 各表、代表帧 537421352）经唯一帧口径复核一致**；
「连续长帧＝0」经同 trace 内唯一帧真实时间序复核**成立**（原「待复核」标记解除）。
代表帧选择（`a3_frame_detail.py`）同口径基于唯一帧，重复行不再进入长帧列表。

**刷新率适配指标（R3 要求 3）**：绝对阈值（16.67/33.33/50/100ms）保留；新增 120Hz
单帧预算 8.33ms 与 60Hz 16.67/33.33ms 适配阈值，并输出 jank_type / present_type /
on_time_finish 分布（超单帧预算 / 错过一次刷新 / 严重长帧 >50ms / 极端长帧 >100ms）。
V1–V5 累积消融（Redmi 120Hz，唯一帧）按新口径重算：

| 构建 | 超单帧预算(>8.33) | 错过一次刷新(>16.67) | 严重长帧(>50) | 极端长帧(>100) | on_time / late |
|---|---|---|---|---|---|
| A 基线 | 268 | 60 | 4 | 4 | 375 / 114 |
| V1 | 318 | 10 | 4 | 4 | 379 / 171 |
| V2 | 269 | 13 | 4 | 4 | 382 / 128 |
| V3 | 251 | 13 | 4 | 4 | 383 / 123 |
| V4 | 253 | 11 | 4 | 4 | 403 / 109 |
| **V5** | 210 | 16 | 4 | **0** | 405 / 163 |

（每构建单 trace；on_time_finish 为唯一帧计数。jank_type/present_type 全量分布见证据
目录重算输出，逐 trace 可由 `a3_stats.py` 复算。）§3 代表帧、§4 表内 p 系列与阈值计数
与上表同源（唯一帧口径）。

**V5 口径（R3 要求 4，最终版）**：V5＝多变量＋空内容组合诊断构建。等开销干净对照下
**降低极端长帧**（max 215.7→87.0、>100ms 12→0），**未改善整体帧耗时分布**
（p90/95/99 变差、>16.67ms 189→266）；不作量化收益结论，不外推真实页面或 vivo。
