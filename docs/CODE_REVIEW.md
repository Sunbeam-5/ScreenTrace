# CODE_REVIEW · 历史评审与返修复核

> 归档合并：2026-11-13 由 7 份历史文件合并；**2026-11-14 起按负责人要求，评审不再单独建档，一律追加到本文末尾**（CR-15 那份为第一份直接并入的，合并前未入库、无 git 历史）。每个 `## 【来源】` 节即一份原文。
> **正文内容一字未改**；仅把原文内部的 markdown 标题整体下移两级（代码块内不动），以免与分节标题撞级。评审人事后补的裁决/落地结果写在独立的 `## 【补记】` 节里，明确标注「不属于上节原文」。
> 文中的测试数、验收条数、提交号、文档路径均为**成文时点快照**，不代表现状；现状以 `docs/项目文档地图.md` 第 2 节为准（见其 §6 快照约定）。

> 覆盖批 1 / 2-5 / 6a / 6b-6d / 6g / 8a / CR-15（A0·A1·B）的 AI 评审、返修复核与裁决记录；含当时的 BUGS、FIX_PLAN 结论。
>
> **写评审的规矩（2026-11-14 负责人定）**：评审只负责「看 + 判」，不新建文档、不顺手改代码；结论一律追加到本文件末尾（`## 【来源】…` + 下移两级的正文），需要裁决的事项在正文里列成表，负责人裁决后的落地结果由评审人补 `## 【补记】` 节闭环。


---


## 【来源】批次1评审-Deepseek V4.1 Flash.md

> 原路径 `docs/批次1评审-Deepseek V4.1 Flash.md`（合并后原文件已移除，原始字节见 git 历史 `a89bd33:docs/批次1评审-Deepseek V4.1 Flash.md`）

### 批次 1 评审报告（Kimi，2026-11-10）

评审对象：commit `b50deb6` feat(uikit): 批次 1——通用组件与页面骨架
评审方式：读全部 uikit/ 源码 + 实际构建 + 对比度/宽度实算，不看提交说明的自我评价

#### 一、实测结果

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

#### 二、必须修的问题

##### P1-1　FreshNavBar「减少动画」会裁掉选中项名称（真 bug）

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

##### P1-2　浅色 SegmentedControl 未选中文字对比度 4.28–4.45，未达 4.5

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

##### P1-3　MetricCard 数值 26sp 在 360dp 双卡内只放得下 4 个中文字

单卡 154dp − 20×2 内边距 = **内容 114dp**；26sp 中文约 26dp/字 → 只能容 4 字。
「3小时52分钟」（6 字）必折行，而 §5.5 要求双卡「同一数值排版、等高」——一张折两行一张单行就破相。

建议在批 4 开工前定死规则（三选一）：
1. 数值字号降为 22sp 并补 §5.3 token（卡内可放 5 字），仍可能不够；
2. 数值改紧凑格式（如 `3h52m` / `3:52` + 说明行写完整时长）；
3. 允许两行但两卡统一规则（`maxLines=2`、`softWrap=true`），并接受卡片变高。

#### 三、建议修（不阻塞批次 2）

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

#### 四、结论

**批次 1 通过，可进入批次 2。** 骨架、组件 token 化、禁假按钮三项核心目标达成，构建与 11 单测实证通过。

三个 P1 在批次 2 期间顺手修掉即可（P1-1 是 2 行真 bug、P1-2 是色板一处值、P1-3 需先定格式规则——批 2 不依赖 MetricCard，不阻塞）。
P2-4 建议随批次 2 一起补，成本极低；其余排入批 4/批 9。

---


## 【来源】批次2-5评审-DeepSeek V4.1 Flash.md

> 原路径 `docs/批次2-5评审-DeepSeek V4.1 Flash.md`（合并后原文件已移除，原始字节见 git 历史 `a89bd33:docs/批次2-5评审-DeepSeek V4.1 Flash.md`）

### 批次 2–5 评审报告（DeepSeek V4.1 Flash，2026-11-10）

评审对象：`affd4bb`（批 2 图表/日历）、`8633528`（批 3 状态层+导航+弹层）、`09e1fd1`（批 4 四页）、`0f564f4`（批 5 解析器），含前置契约 v0.2/v0.3。
评审方式：读全部 `app/src/main`（5.5k 行）+ 强制重跑单测 + **用真实 10 天 dump（15MB）跑 Kotlin 解析器并与 `screen_usage_report.json` 逐日对账** + 硬编码色/假按钮 grep。不看提交说明的自我评价。

---

#### 一、实测结果

| 项 | 结果 |
|---|---|
| 构建 | `gradlew.bat :app:testDebugUnitTest --rerun-tasks` → BUILD SUCCESSFUL（14s） |
| 单测 | **32 个全绿**（UsageCalculator 16 / UsageDumpParser 11 / 对比度 5），非 up-to-date 实跑 |
| 硬编码色 | `ui/` `uikit/` 零 `Color(0x…)` / `Color.White` / `FreshColors`，仅 `Color.Transparent` 结构性透明 ✓ |
| 假按钮 | 双卡按纯摘要卡渲染（无箭头无点击）、设置页诚实待办、详情页占位 EmptyState ✓ 红线守住了 |
| 对比度 | 九主题×深浅 18 套，正文/tonal/单位色/月历日号全部 ≥4.5 自动断言 ✓ |
| 页面结构 | 今日 3 组、统计 3 卡、日历 4 段、排行 3 段，顺序与 §5.7 一致 ✓ |
| 解析性能 | 真实 15MB 双 dump → **1047ms**（桌面 JVM），366 天量级需真机复测 |

**真实数据对账（Kotlin 解析器 vs 已验证的 Python v2 报告，2026-10-31 → 11-09）**

| 日期 | 使用时长 py/kt | 解锁 py/kt | 启动 py/kt | 打扰 py/kt | 已查看 py/kt | 最长连续 py/kt |
|---|---|---|---|---|---|---|
| 10-31 | 410.8 / **411** | 82 / **190** | 1282 / 1437 | 395 / **490** | 157 / **229** | 41.6 / 42 |
| 11-01 | 522.1 / **522** | 133 / **399** | 1558 / 1672 | 145 / **164** | 121 / **153** | 39.8 / 40 |
| 11-02 | 571.9 / **572** | 89 / **267** | 1443 / 1694 | 260 / **318** | 163 / **243** | 44.2 / **102** |
| 11-03 | 703.9 / **704** | 130 / **390** | 2137 / 2416 | 433 / **519** | 215 / **283** | 99.8 / 102 |
| 11-04 | 491.4 / **493** | 124 / **372** | 1664 / 1887 | 233 / **295** | 127 / **190** | 32.8 / 33 |
| 11-05 | 402.1 / **402** | 120 / **360** | 1257 / 1547 | 258 / **333** | 237 / **296** | 38.0 / 38 |
| 11-06 | 551.3 / **552** | 92 / **276** | 1486 / 1649 | 184 / **259** | 126 / **170** | 42.1 / 42 |
| 11-07 | 322.8 / **323** | 72 / **216** | 801 / 903 | 117 / **334** | 70 / **154** | 38.6 / 39 |
| 11-08 | 307.6 / **308** | 61 / **183** | 1017 / 1147 | 111 / 122 | 61 / 82 | 29.1 / 29 |
| 11-09 | 352.3 / **352** | 43 / **129** | 628 / 764 | 100 / 116 | 69 / 84 | 41.9 / 42 |

**结论先说**：亮屏使用时长口径**完全正确**（逐日 ≤1.6min，远优于 §7-2 的 5min 线），小时矩阵合计与日合计 ≤4min（取整累积，可接受）；但**计数层（解锁/启动/通知）全线虚高 2–3 倍，最长连续在跨零点日翻倍**，这两项是批 5 的硬伤，必须在批 6a 入库前修掉。应用时长总量 5/6 项与基线差 ≤0.2min（微信 592.2/592.2、桌面 670.3/670.5、B站 443.8/443.7、PiliPlus 397.8/397.9、豆包 280.5/280.6），但米家 205.8/236.7 差 31min——**这一项的错在基线，不在解析器**（基线只算主空间，漏了手机分身 150.9min），见 P1-5。

---

#### 二、必须修

##### P0-1　计数层没有跨账户去重（系统事件被三个账户各记一遍）——但**不能**用「只取主用户」来修

**先纠正 §2.2 的事实前提**：「user=666/999 是后台服务账户（2–7 个包、时长为 0），忽略」这句话在真实 10 天 dump 上不成立：

| 账户 | 每区间包数 | 非零包数 | 真实内容 |
|---|---|---|---|
| user=0 | 4464–9635 | 4265–9462 | 主空间 |
| user=666 | 284–604 | 279–574 | **vivo 隐私空间**：相册 30 次前台 / 116 次交互 / 11.7min，文件管理 0.3min，intentresolver 0.2min，10 天合计 12.2min，**通知 0 条**（空间隔离） |
| user=999 | 342–1641 | 338–1635 | **手机分身（第二空间）**：米家 614 次 resumed、抖音 27、QQ 8、微信 2；通知 433 条 |

两个都是**真人会主动切进去用的独立空间**，不是后台服务账户。隐私空间里虽然只有相册/文件管理，但那是真实占用的屏幕时间（相册 11.7min 是看照片的时间），一样要计入。

`>50 包` 这个过滤器**挡不住任何一个账户**。`user=999` 是真人会切进去用的分身空间，其应用数据与主空间**独立**（不是镜像）：

- 米家 10 天：u0 = 205.8min，u999 = 150.9min；逐日互补（11-02：u0 51.0 / u999 7.0；11-04：u0 0.5 / u999 28.1）
- 明文 `In-memory monthly`：u0 米家 54:24，u999 57:03；`yearly`：u0 12:11:40，u999 46:03:16 —— 量级完全对不上，不可能是同一批数据的副本
- 逐日「u0+666+999」应用时长之和始终**远低于**当日亮屏时长（余量 188–566min），不存在双计

所以「只取 u0」会把分身里的真实使用直接丢掉。真正的缺陷是另一件事：**系统广播事件被 fan-out 到每个 profile，而计数层没做跨账户收敛**。

实测（checkin 全量；下表为**按秒去重后的计数**，因为同一秒内同名事件可能有多条，如 u0 的 `ACTIVITY_RESUMED` 原始 13673 条 → 12232 个独立秒）：

| 事件 | u0 | u666 | u999 | u0∩u999 | 仅 u999 独有 | 三账户并集 |
|---|---|---|---|---|---|---|
| `KEYGUARD_HIDDEN` | 900 | 906 | 904 | **873** | 31 | **935** |
| `SCREEN_INTERACTIVE` | 1036 | 1043 | 1040 | 1005 | 35 | 1077 |
| `ACTIVITY_RESUMED` | 12232 | 39 | 555 | **7** | **548** | 12807 |
| `NOTIFICATION_INTERRUPTION` | 2087 | 0 | 376 | 3 | **373** | 2460 |
| `NOTIFICATION_SEEN` | 1035 | 0 | 84 | 42 | 42 | 1077 |
| `USER_INTERACTION` | 1540 | 76 | 24 | 1 | 23 | 1639 |

规律非常干净：

- **系统级事件（KEYGUARD_* / SCREEN_*）是镜像**：两两重合 96.5%，相加 2710 = 2.98×（这就是 `UNLOCK_EV=2782` 的来源），并集只有 935；
- **应用级事件（ACTIVITY_RESUMED / 通知 / 交互）是独立的**：u999 的 555 条 resumed 里 548 条 u0 完全没有，通知 376 条里 373 条独有，u666 的 76 次交互 u0 零重合——这些必须全收。

毫秒级容差测试：以 u0 为基准、±1000ms 匹配后，u999 仅剩 31 条未被覆盖（`tol=0` 时是 91 条，说明**秒级 key 会因为毫秒截断产生 3–4% 的伪新增**；`tol` 放宽到 2s/3s 结果不再变化，31 条是稳定值）。

**修法**（不是过滤账户，而是统一去重键）：

1. 解析时记录当前 `user=` 块，三个账户的区间 / `In-memory` / 应用事件**全部保留**（666 是隐私空间、999 是手机分身，都是真人切进去用的），删除 §2.2 的「>50 包」以及「忽略 666/999」；
2. 计数层跨 user 收敛：同 `type` 且 `|Δt| ≤ 1000ms` 视为同一物理事件（贪心最近匹配），系统事件应收敛到 ≈931，应用事件几乎不收敛（因为时间/包名不同）；
3. 应用时长按 `(date, pkg)` 跨账户合并——分身版微信与主微信归同一个 app 名下，用户看到「微信 592min」而不是两行，这符合产品直觉；
4. 保留「checkin 边 vs 明文边取较大条数」用于双源合并，但它不能替代跨账户收敛。

**验收不变式**（比「复现 Python 报告」更可靠，因为基线自己也没算分身）：`Σ(各账户当日应用时长) ≤ 当日亮屏时长`；`去重后解锁数 / u0 单账户解锁数 ∈ [0.98, 1.05]`。

**影响**：不解此条，批 6a 入库后今日卡「解锁次数」、通知详情页全部数字、排行启动次数都错 2–3 倍，`§7-17`（事件解析）与 `§7-16`（详情页）不可能通过。

##### P0-2　最长连续在跨零点日虚高（最长连续卡直接错）

```kotlin
for ((a, b) in screens) {
    ...
    while (cur < b) {
        ...
        longestDay[date] = max(longestDay[date] ?: 0.0, subKg(a, b) / 60.0)  // ← 用整段，不是当日切片
```

`subKg(a,b)` 是**整段亮屏区间**，却被赋给该区间触达的每一天。11-02 报 102min（基线 44.2），11-03 报 102（基线 99.8）——前一天的跨零点长区间被完整复制到次日。

Python 基线是逐日切片 `for dk,s,e in split_by_day(a,b): u = sub_kg(s,e); day_longest[dk]=max(...,u)`。

**修法**：移到切片内用 `subKg(cur, dayEnd)`，与 §3-5 的「单个 sub_kg 后的亮屏区间」在日内口径一致。改完 11-02 应回落到 ~44min。

##### P1-3　「完全无记录」时四个页面渲染假 0（红线）

`EmptyUsageRepository` 返回的是**非 null 的空对象**，`AppViewModel.loadAll()` 照常构造 `TodayData/StatsData/CalendarData`，于是页面走的是 `data != null` 分支：

- 今日：圆环 `0:00`、解锁 `0`、通知打扰 `0`「已查看 0 次」、对比「暂无上一周期数据」；
- 统计：大数字 `0分钟`、最久/最少双卡 `0分钟`（无日期）、洞察隐藏；
- 日历：连续 `0` 天、整月无色档、双卡 `0分钟 / 0 天`；
- 排行因 `apps.isEmpty()` 走了空态（唯一做对的）。

§5.5「禁止把无数据伪装成真实的 0 分钟」、§5.8「首次无数据时才显示全页引导」、§6 红线，以及批 4 自述的验收项「空态不得出现 0 分钟」——四页里三页不达标。

**修法**：VM 层在 `!summary.hasAnyRecord` 时把对应 UiState 置为「无数据」（`data = null` 或新增 `Empty` 状态，与 loading/error 并列），页面复用统一 `EmptyState`；同时 `EmptyUsageRepository.metricDetail` 不该返回 `NotificationCounts(0,0)`（UI 已支持 null → 「—」，但永远走不到）。

##### P1-4　日历点击详情的数据源只覆盖当前筛选区间

`minutesByDate` 来自 `repository.dailyTrend(selection)`：

```kotlin
minutesByDate = repository.dailyTrend(selection).filter { it.recorded }.associate { it.date to it.minutes }
```

默认筛选是「今天」，但月视图可翻到任意月、年视图固定显示 20 周。点任何非今天的格子都拿到 `null` → 显示「无记录」，即使那天真实有数据。§7-9 要求「点击任一格显示真实日期与时长」。

**修法**：契约加一个按范围取明细的方法（如 `minutesByDate(start: LocalDate, end: LocalDate): Map<LocalDate, Int>`），月视图传整月、年视图传 20 周首尾；或让 `monthLevels/yearLevels` 的 `CalendarLevel` 直接带 `minutes`（现在只有 level，页面只能再查一次）。

##### P1-5　明文 `In-memory` 段把三个用户都吸进来，且与 checkin 区间叠加

明文 dump 里 `In-memory daily stats` 出现 **3 次**（第 4783 / 8785 / 9542 行，分别属于 user 0/666/999），`inDaily` 标志只按 `In-memory weekly` 关闭、不看 user 块 → 三个账户的包聚合全部塞进 `todayPkgs`，以同一个 `humanDate` 落库。

**这里要修正上一版报告的一个归因错误**：米家 10 天 Kotlin 236.7min vs 基线 205.8min（+31min），上一版判为「被分身数据污染」。实际是反的——**基线漏了分身**。实测 u0 米家 205.8min、u999 米家 150.9min，真值应为 **356.7min**（月/年口径也印证：u0 年 12h11m vs u999 年 46h03m）。Kotlin 的 236.7 是「只吃到了一部分分身数据」的中间态，既不是对的，也不是多的。

因此这条的正确标题是：**`In-memory` 段没有按账户归属，且与 checkin 区间重复计入**（同一段数据既走区间、又走 `In-memory` 补采，导致时多时少）。

另外 `todayScreenMs`（明文 `screen-interactive` 聚合）解析了但从未使用（编译警告），今日亮屏只靠事件——目前能对上，但这段死代码应删或明确口径。

**修法**：`In-memory` 也按 `(date, pkg)` 跨账户合并，并与区间数据做同一套去重/取大逻辑，不再作为「补丁」叠加；对账时以「不变式 + 系统 UI 抽样」为准，**不要拿这份只含 u0 的 Python 报告当分身类 app 的真值**。

##### P1-6　`appUsage` 输出没过滤 0 时长、也没按 `(date,pkg)` 归并

Kotlin：`APP_ROWS=2483`（逐区间逐个包直出）；Python 基线：585 行（`total_ms <= 0` 的包直接丢弃）。同一 `(date,pkg)` 在「区间行 + 今日 In-memory 行」会各出一条。

- 若批 6a 按 §4「同日 `(date,pkg)` 取 minutes 较大值」入库，今日应用时长会取「单条片段」而不是日累计；
- 若按求和入库，则会与 In-memory 重复相加；
- 排行若直接数行数，会出现 248 个应用（大量 0 分钟）而不是 58 个。

**修法**：解析层就按 `(date,pkg)` 聚合成一行（`totalMs` 求和、`launches` 求和），并丢弃 `totalMs <= 0`；在 `AppUsageRow` 注释里写死「一行一日一包，入库取较大值语义只用于重拉同日」，别让 6a 猜。

---

#### 三、建议修（不阻塞批 6a）

| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| P2-1 | 柱图槽宽被日期标签撑开：`Text` 在 `Column` 内无宽度约束，7dp 细柱视图列宽变成 ~26dp，30 天即需横滚、柱距忽宽忽窄；刻度 `0` 的标签 `offset` 到底边外溢 8dp | `uikit/CapsuleBarChart.kt:116-152` | 标签放固定 `slotWidth` 的 Box（`overflow` 裁切）或改 Canvas 绘制；刻度列加 `clipToBounds` |
| P2-2 | 月视图连续组里**单日**着色日期用了 100% 圆角（胶囊），§5.7 要求单日 10dp；10dp 只用在无色格 | `uikit/MonthCalendar.kt:100-105` | `count == 1` 时 `RoundedCornerShape(10.dp)`，组内分段仍由外层全胶囊裁切 |
| P2-3 | tonal 卡内边距 20dp，§5.2 规定 tonal 卡 16dp | `uikit/MetricCard.kt:56`、`ui/CalendarScreen.kt:103` | 改 16dp（普通卡保持 20） |
| P2-4 | 日期弹层快捷项触摸区约 37dp（12sp 文字 + 上下 10dp），§5.9 要求 ≥48dp | `ui/DateSheet.kt:150-158` | `.defaultMinSize(minHeight = 48.dp)` |
| P2-5 | 趋势图「零使用 / 无记录」没有详情文案区分 | `ui/StatsScreen.kt:96` | 传 `onPointClick` 并在卡内显示一行「11/7 · 无记录 / 0分钟」，或柱下加三态说明 |
| P2-6 | §2.2/§7-17 写「16 项」，实际清单只有 15 个数字码（1,2,5,7,8,10,11,12,15,16,17,18,19,20,23），代码也只映射 15 项 | `docs/需求文档…md:41,311`、`data/parse/EventTypes.kt` | 文档改「15 项」；补一个测试遍历清单断言全覆盖，避免下次再飘 |
| P2-7 | `humanDate` 用「最后一条明文事件」推断 In-memory 归属日，跨日/当日零事件时会落到昨天 | `data/parse/UsageDumpParser.kt:238-241` | 用解析时刻的 `LocalDate.now(zone)` 或明文段头 `beginTime` |
| P2-8 | 月视图切月时 `monthLevelsState` loading 没有骨架，日历瞬间变全空（像「无数据」） | `ui/CalendarScreen.kt:161-170` | loading 时保留上一月数据 + 轻量骨架 |
| P2-9 | 筛选胶囊条在设置页、详情占位页也显示 | `ui/AppNav.kt:141` | 仅四个数据页渲染 |
| P2-10 | 今日「解锁次数」单日取的是 `averageUnlocks`（`unlocks/recordedDays`），单日恰好等于当日值，但语义是日均 | `ui/TodayScreen.kt:99` | 契约补 `Summary.unlocks`（当日/区间合计）或单日直接取 `total` |
| P2-11 | 今日 hero 只有圆环弧线，没有「目标 X 小时 · 已完成 Y%」文字 | `ui/TodayScreen.kt:82-92` | §5.7 要求「每日目标进度」可读，弧线不足以表达 |
| P2-12 | `MetricCardPair` 用 `IntrinsicSize.Min + fillMaxHeight`，说明行换行时按单行算高、外层 `clip` 会裁字（1.3 倍字体风险） | `uikit/MetricCard.kt:96` | 去掉 IntrinsicSize，改 `Row(Modifier.height(IntrinsicSize.Max))` 或允许两卡各自高度 |
| P2-13 | 排行「共 N 个应用」按行数计；区间若返回按日拆分的行会虚高 | `ui/RankingScreen.kt:93` | 契约写死「一行一应用（区间累计）」 |
| P2-14 | README 仍是「只有契约层 / 7 个单测 / 四页未实现」 | `README.md` | 与现状严重漂移，批 9 前更新，避免误导下一位 AI |
| P2-15 | 编译警告：`todayScreenMs` 未使用、两处多余 `!!`、`Icons.Default.List` 等 3 处 deprecated、`palette` 未使用 | 解析器/AppNav/Calendar/DateSheet | 清一遍，警告归零再进 6a |
| P2-16 | 性能：`subKg` 对每个小时切片遍历全部锁屏区间（O(n²)），`metricDetail` 对每个自然日重扫全部事件（O(days×events)） | 解析器、`UsageCalculator` | 366 天区间前先做一次真机实测；必要时把 kg 区间按时间排序后二分/双指针 |

---

#### 四、结论

**批次 2/3/4：有条件通过；批次 5：不通过（需返修 P0-1、P0-2）。**

- 做对的硬骨头值得记一笔：语义 token 全程零硬编码色、禁假按钮执行得干净（双卡纯摘要、设置诚实待办、详情占位）、对比度 18 套自动断言、亮屏时长与锁屏扣减口径**真机数据逐日 ≤1.6min**、小时矩阵与日合计一致、应用时长 5/6 项与基线差 ≤0.2min、四页结构与 §5.7 逐条对得上。
- 但「计数层」是这次唯一失守的地方，而且失守方式很典型：区间层做了去重，计数层为了保毫秒把三份用户数据原样保留。**这是会直接写进 Room、出现在卡片上、再被 §7-2 用系统 UI 对账抓出来的错**，不能留到批 9。

**批 6a 开工前置（必须全部完成）**

1. **撤销 §2.2 的账户过滤**：三个账户的区间 / `In-memory` / 应用事件全部保留（分身是真实使用）；改为在计数层按「同 type + ±1000ms」跨账户收敛，系统事件收敛到 ≈931，应用事件保持独立；
2. `longestDay` 改逐日切片；
3. `appUsage` 按 `(date,pkg)` 跨账户聚合 + 丢 0 时长行；
4. 用这 10 天真实 dump 建一个**可重复执行**的对账测试，但断言目标改为不变式而非复现 Python 报告：`Σ各账户当日应用时长 ≤ 当日亮屏时长`、`去重解锁数 / u0 解锁数 ∈ [0.98, 1.05]`、米家 10 天合计 ≈356.7min（u0 205.8 + 分身 150.9）。这条测试是批 5 唯一缺的验收物——现在 11 个 fixture 全是自造的，自造数据里根本不会出现 666/999。

**批 4 的两条 P1（假 0、日历详情数据源）建议在 6b 接真实仓库之前修掉**，否则一旦有数据、无数据的边界被真实仓库覆盖，反而更难复现。P2 全部排入批 9 巡检。

---

评审人：DeepSeek V4.1 Flash（独立会话，未参考其他 AI 输出）
实测命令：`JAVA_HOME=C:/Users/Administrator/.jdks/jbr-21.0.11 ./gradlew.bat :app:testDebugUnitTest --rerun-tasks`
真实数据：`E:/project/屏幕使用时间App/源数据/usagestats-checkin.txt`（13MB）+ `usagestats(1).txt`（2.4MB），基线 `screen_usage_report.json`

---


## 【来源】批次2-5返修复审-DeepSeek V4.1 Flash.md

> 原路径 `docs/批次2-5返修复审-DeepSeek V4.1 Flash.md`（合并后原文件已移除，原始字节见 git 历史 `a89bd33:docs/批次2-5返修复审-DeepSeek V4.1 Flash.md`）

### 批次 2–5 返修复审（DeepSeek V4.1 Flash，2026-11-10 17:35）

审的是提交 `d498ce2`（批次 2–5 评审返修，CR-02）。生产代码逐条复核过，测试**独立跑过并自己写了探针复算**，没有采信提交信息里的数字。

---

#### 一、结论

**生产代码：P0-1、P0-2 真修好了，数字可复现。**
**验收物：不通过。** 真实 dump 对账测试里的**解锁断言从未执行**——基线 JSON 里 `"unlocks"` 出现 20 次（`days` 数组存了两遍），而测试写的是 `if (baseUnlocks.size == ktUnlocks.size)`，20 ≠ 10，整段 `assertTrue` 被静默跳过。提交信息里「与基线逐日比值 [0.98,1.05]」这句话，测试并没有验证过。

`44 测全绿 / 0 skip`（含 6a 新写的 `MergePolicyTest` 6 条）是真的，但绿得没有含金量——只要那个 `if` 不成立，代码把解锁算成 3 倍也一样全绿。

---

#### 二、我自己跑出来的对账（探针已删，未留痕）

| 日期 | 解锁 kt/基线 | 启动 kt/基线 | 亮屏 | 应用合计 | 合计/亮屏 | 最长连续 |
|---|---|---|---|---|---|---|
| 10-31 | 82 / 82 ✅ | 1437 / 1282 | 412 | 409 | 0.99 | 42 |
| 11-01 | 133 / 133 ✅ | 1672 / 1558 | 525 | 505 | 0.96 | 40 |
| 11-02 | 89 / 89 ✅ | 1694 / 1443 | 574 | 589 | 1.03 | **44**（基线 44.2）|
| 11-03 | 130 / 130 ✅ | 2416 / 2137 | 710 | 738 | 1.04 | **100**（基线 99.8）|
| 11-04 | 124 / 124 ✅ | 1887 / 1664 | 496 | 531 | 1.07 | 33 |
| 11-05 | 120 / 120 ✅ | 1547 / 1257 | 404 | 378 | 0.94 | 38 |
| 11-06 | 92 / 92 ✅ | 1649 / 1486 | 555 | 561 | 1.01 | 42 |
| 11-07 | 72 / 72 ✅ | 903 / 801 | 325 | 361 | **1.11** | 39 |
| 11-08 | 61 / 61 ✅ | 1147 / 1017 | 309 | 309 | 1.00 | 29 |
| 11-09 | 43 / 43 ✅ | 764 / 628 | 353 | 350 | 0.99 | 42 |

- **解锁逐日比值 1.000**，一天不差（946/946）。跨账户收敛的容差、贪心匹配、只作用于 MIRROR_TYPES，三点都对；也没有把分身独有的那 31 条误删。
- **P0-2 修好**：11-02 = 44（原来 102）、11-03 = 100（原来也是 102）。逐日切片生效。
- **应用聚合**：589 行（基线 585），一行一日一包，0 时长已滤。
- 单应用：微信 592（基线 592.2）、桌面 670（670.3）、B站 443（443.7）、PiliPlus 397（397.9）、豆包 280（280.6）、抖音 207（基线 203.3，多出的 3.7min 是分身里真实用的）。
- **米家 362 vs 真值 356.7（+1.5%）**：差额来自 11-09，明文 `In-memory daily` 给分身记 30:48、区间只给 25:30，取大后多 5.3min。这个方向正确（In-memory 覆盖 11-08 22:58–11-09 15:50 的滚动 24h，比区间宽），不算错。

---

#### 三、必须修（都在测试文件里，不动生产代码）

##### P0-A　对账测试的解锁断言被静默跳过

```kotlin
if (baseUnlocks.size == ktUnlocks.size) { ... }   // 20 != 10，整段不执行
```

`Regex(""""unlocks": (\d+)""")` 在 `screen_usage_report.json` 里命中 **20** 次（`days` 数组重复了一遍，两组数字还不完全一样：82/79、133/130…）。`ktUnlocks` 是 10 个。

**修法**：抽出 `days` 数组再解析（或先 `assertEquals(10, baseUnlocks.size)` 让不匹配直接红），并把 `if` 换成 `require`。修完请把实测 946/946 打印出来，别只留一个断言。

##### P1-B　三条真实数据断言都能被 `assumeTrue` 静默跳过

`findSourceFile()` 用 `E:/project/屏幕使用时间App/源数据/...` 绝对路径 + `assumeTrue`。换台机器或 CI 上就是 0 执行、0 失败、全绿。这正是上一轮我批评「11 个 fixture 全是自造」的同一个坑换了个位置。

**修法**：找不到文件时 `fail("真实 dump 缺失，对账未执行")`，或加 `@Tag("realdump")` 单独 task 并在 CI 里显式要求非零执行数。

##### P1-C　不变式 1 的上界松到测不出东西

`appSumMin <= screenMin * 1.2 + 30`：实测最大才 **1.11×**（11-07），余量足够。这个上界意味着「应用合计多算 20%」也能过——而那正是批 5 要防的错。

**修法**：收到 `1.15×`（留 4% 余量给滚动日界/分屏），或 `1.05× + 10min`。别用 `+30min` 的绝对量，11-09 那种亮屏 353 的日子它会把 20% 的误差吞掉。

##### P1-D　米家断言是区间不是点，掩盖了差额来源

`mijiaMin in 330..390` 太宽（真值 356.7，实测 362）。建议 `356..365` 并注释「上界含 11-09 In-memory 覆盖滚动 24h 的 5min 增量」——否则下一个人不知道为什么允许超过 356.7，改回「只取 u0」也照样绿。

---

#### 四、需要更正我上一轮的一个判断

上一轮我说「通知打扰 +32% / 已查看 +40% 是解析器把 666/999 后台账户全算进来」。**这个归因是错的**，实测拆开看：

| 11-07 通知打扰 | 条数 |
|---|---|
| u0 原始（毫秒多重集合） | 133 |
| u999（分身，米家推送爆发） | 201 |
| 解析器输出 | 334 ✅ |
| 基线 | 117 |

10-31 同理：u0 原始 488、u999 2、解析器 490、基线 395。也就是说 **+24% 的主体是「毫秒口径 vs 基线秒级去重」**（488 → 秒级 395），分身只贡献个位数；11-07 那种 2.85× 才是分身真实数据（米家一天推 201 条）。

启动也一样：10-31 u0 原始 1429、秒级 1282、解析器 1437（+8 来自分身），基线 1282 → **+11.5% 是口径差，+0.6% 是分身**。

这不是 bug——§2.2 坑 2 / CR-01 明确要求「计数层保留毫秒，同秒多条真实通知不丢」。但它有一个后果必须在文档里写死：**计数层的通知/启动条数与系统设置里的聚合数字天然不可直接比对**（§7-2 拿系统 UI 对账会撞上）。建议 §5 或 §7-2 加一句说明，否则批 9 的真机验收会被当成 bug 追。

---

#### 五、P2（不阻塞）

| # | 问题 | 位置 |
|---|---|---|
| P2-A | 日历选中格跨月残留：`selectedEpochDay/selectedMinutes` 只在切换月/年视图时清空，用 `onMonthChange` 翻月后旧选中格还在，详情显示上月日期 | `ui/CalendarScreen.kt:133-190` |
| P2-B | 编译警告 7 条未清（`!!`×3、`DateSheet` 的 `palette` 未用、`CapsuleBarChart.endDay` 未用、`Icons.Default.List/KeyboardArrow*` 已弃用）——P2-15 只做了一半 | 多处 |
| P2-C | `convergeMirrorEvents` 每个事件扫一遍 `result`（O(n²)）。900 条无感，但 16k 事件全量时是主要开销；可按 type 分组后双指针 | `UsageDumpParser.kt:370-390` |

---

#### 六、顺带看的两眼 6a（17:27 刚写、未提交，不算本次范围）

- `IngestPipeline` 里 `minutes = (totalMs / 60_000L).toInt()` 是**截断**：59 秒的真实使用会存成 0 分钟，和 §5.5「不得把真实使用显示成 0」擦边。建议四舍五入，或表里存秒、展示层再取整。
- `hour_usage` 那层在 `forEachIndexed` 里调 `hourDao.getForDate(key)`，一天 24 次查询；先取一次 `associateBy { hour }` 更省。
- `notification_event` 的 PK 是 `(occurredAtMs, type, pkg)`，而 `mergeNotifications` 承诺「组内多条全保留」——同毫秒同包同类型的两条在 `insertIgnore` 时会被吞掉。概率极低，但语义上自相矛盾，建议注释写明或 PK 加 `channelId`。

---

#### 七、放行条件

修完 **P0-A + P1-B/C/D**（纯测试文件改动）即可继续 6a。生产代码这轮不用再动——解锁、最长连续、应用聚合三项在真实 10 天数据上都已对到基线或不变式内。

---

评审人：DeepSeek V4.1 Flash（独立会话）
实测命令：`JAVA_HOME=C:/Users/Administrator/.jdks/jbr-21.0.11 ./gradlew.bat :app:testDebugUnitTest --rerun-tasks`（44 测，0 fail，0 skip）
复算方式：临时 JVM 探针直接调用 `UsageDumpParser.parse()` 打印逐日数字，与 `screen_usage_report.json` 手工比对，探针已删除

---


## 【来源】批次6a评审-DeepSeek V4.1 Flash.md

> 原路径 `docs/批次6a评审-DeepSeek V4.1 Flash.md`（合并后原文件已移除，原始字节见 git 历史 `a89bd33:docs/批次6a评审-DeepSeek V4.1 Flash.md`）

### 批次 6a 评审（DeepSeek V4.1 Flash，2026-11-10 18:40）

审的提交：`df8ca06`（feat(data): 批次 6a——Room 六表 + 合并策略 + DataStore + 解析入库管道）。
KSP 真跑过（`kspDebugKotlin` 强制重跑通过），44 测全绿（16 领域 + 6 合并 + 17 解析器 + 5 对比度），工作区干净。

---

#### 一、结论

**骨架合格，但不通过。** 六表 schema 与 §4 逐字段一致（原四表没被改义，红线守住），合并策略抽成纯函数、单事务 + 失败回滚、SettingsStore 的键设计都对。问题集中在「接上真实数据就会露」的地方：

| 级别 | 问题 | 后果 |
|---|---|---|
| P0 | `deleteByMs` 用 `IN (:msList)` 且不分批 | Android 8–11 上多日重拉必崩（SQLite 变量上限 999，一次 4837 条） |
| P1 | `category` 全写 `"OTHER"`，`categoryOverrides` 无人消费 | 排行页图例永远只有「其他 100%」 |
| P1 | **管道单测完全缺失**（无 Robolectric / androidTest / room-testing） | 计划明确要求的「连续拉取不重复计数」没有任何测试 |
| P1 | `metricDetail` 的 `gapDays = emptySet()` | 覆盖三态退化成两态，违反 §4「查询层必须能区分三种状态」 |

---

#### 二、对照 6a 任务清单

| 任务项 | 状态 | 说明 |
|---|---|---|
| KSP 1.9.24-1.0.20 + Room 2.6.1 / WorkManager 2.9.0 / DataStore 1.1.1 | ✅ | 版本对齐，`kspDebugKotlin` 实跑通过 |
| `data/db/` 六表，原四表照 §4 不动 | ✅ | 逐字段核过：`day_summary` 7 列、`app_usage` 6 列、`hour_usage` 3 列、`pull_log` 4 列、`unlock_event` 1 列、`notification_event` 4 列，一致 |
| 事件表索引 | ✅ | `Index("pkg")` + `Index("occurredAtMs")` |
| `RoomUsageRepository` 九方法 | ⚠️ | 方法都在，但 `category`、`gapDays`、`appLabels` 三处打折，见 P1 |
| `data/prefs/` DataStore | ✅ | 目标/频率/主题/深浅/动态取色/分类覆盖齐全 |
| 合并策略纯函数 + JVM 单测 | ✅ | 6 条，覆盖取大/同秒多条/跨秒独立 |
| 管道单测（连续拉取不重复计数、双源不重复） | ❌ | 一条都没有，见 P1-3 |
| 验证：单测通过 | ✅ | 44 测 0 fail 0 skip |

---

#### 三、P0　`deleteByMs` 会在多日重拉时炸掉

```kotlin
@Query("DELETE FROM notification_event WHERE occurredAtMs IN (:msList)")
suspend fun deleteByMs(msList: List<Long>)
```

Room 生成的实现（`app/build/generated/ksp/debug/.../NotificationEventDao_Impl.java` 核对过）是逐个占位符拼 SQL：

```java
_stringBuilder.append("DELETE FROM notification_event WHERE occurredAtMs IN (");
final int _inputSize = msList.size();
StringUtil.appendPlaceholders(_stringBuilder, _inputSize);   // 没有分批
```

- `minSdk = 26`，Android 8–11 自带 SQLite < 3.32，**绑定参数上限 999**；Android 12 起才是 32766。
- 调用点：`IngestPipeline.kt:82`，`msList` 来自 `between(min-1000, max+1000)`，也就是整段 dump 窗口。
- 真实数据：10 天 dump 解析出 **4837 条通知**。第二次拉取时 `existingNotifs` ≈ 4837 行 → 4837 个绑定参数 → `SQLiteException: too many SQL variables`。

而且它**不会把 App 弄崩**（异常被 `catch` 住），表现是「每次拉取都提示入库失败，通知数据永远停在第一次」——这种「假装失败」的形态比崩溃更难查。

**修法**：删按区间，不按主键列表：

```kotlin
@Query("DELETE FROM notification_event WHERE occurredAtMs BETWEEN :startMs AND :endMs")
suspend fun deleteBetween(startMs: Long, endMs: Long)
```

窗口两端 `IngestPipeline` 本来就有（`startMs`/`endMs`），改两行即可。

> 附：空列表这条路是安全的——SQLite 明确允许 `IN ()`（视为空集），所以首次入库不会因 `deleteByMs(emptyList())` 报错。

---

#### 四、P1

##### P1-1　`category` 永远是 `OTHER`，排行图例必然退化

- `IngestPipeline.kt:54`：`category = "OTHER"` 硬编码；
- `AppNames` 只有 `nameOf`，没有 `categoryOf`，全仓不存在任何 pkg→分类映射；
- `SettingsStore.categoryOverrides` 有持久化，但 `RoomUsageRepository` 从没读过它。

而 `RankingScreen.kt:99` 正是 `groupBy { it.category }` 来画分类占比条的 —— 真实数据一接，排行页顶部就是一条纯色「其他 100%」，§5.7 的分类着色等于没做。

**修法**：`AppNames` 加 `categoryOf(pkg): AppCategory`（按 §4 六类建表，至少覆盖 dump 里出现量前 30 的包），`IngestPipeline` 写库时用它；`RoomUsageRepository.appRanking` 再叠加 `categoryOverrides`（用户改过的优先）。

##### P1-2　管道单测缺失，而提交信息把它算进了合并单测

6a 验证项原文：「管道单测覆盖『连续拉取不重复计数』『同秒多条通知不丢失』『双源合并不重复』」。现状：

- `app/src/` 下只有 `main` 和 `test`，**没有 `androidTest`**；
- `build.gradle.kts` 只有 `testImplementation("junit:junit:4.13.2")`，**没有 robolectric / room-testing**；
- `MergePolicyTest` 6 条测的是纯函数，`IngestPipeline` 一行没测。

`MergePolicy` 正确 ≠ 管道正确。管道里有三件事纯函数测不到：`existing` 怎么取窗口、`delete` 与 `insert` 的顺序、失败是否真回滚。这类差别恰好就是上一轮「对账断言被跳过」的同一种失守方式——**声称覆盖了，其实没覆盖**。

**修法**：加 `testImplementation("org.robolectric:robolectric:4.11.1")` + `room-testing`，用 `Room.inMemoryDatabaseBuilder` 跑三条：同一份 `ParseResult` 连灌两次断言 `notification_event`/`unlock_event` 行数不变；同秒 3 条通知灌两次仍 3 条；`result.days` 第二次全小值时 `day_summary` 不回退。

##### P1-3　`metricDetail` 的三态覆盖退化成两态

```kotlin
gapDays = emptySet(),   // 批 6b 起从 pull_log 推导
```

`UsageCalculator.coverageOf` 的三态是：`windowStart` 之前 → `NO_RECORD`、`gapDays` 内 → `GAP`、其余 → `RECORDED`。`gapDays` 恒空，`GAP` 这个分支永远不会出现 —— 拉取失败的日期会被画成「真实 0 次」，正是 §4 那句「UI 不得把无记录时段画成真实的 0」要禁的东西，也是 §5.7 详情页的验收点（§7-16）。

6a 说「metricDetail 全实现」，这一条该打折。**而且 6a 就能做，不用等 6b**：`pull_log` 里 `ok = false` 的行，把 `pulled_at` 那天计入 `gapDays`（拉取失败当天数据不可信），这是本地纯查询。

##### P1-4　`appLabels` 忽略库里的 `name`

```kotlin
val appLabels = db.appUsageDao().allNames().associate { it.pkg to AppNames.nameOf(it.pkg) }
```

`name` 列现在是 pkg 本身，所以看不出问题；但 6b 一旦用 PackageManager 回填中文名，这里仍然只认 `AppNames`，详情页通知排行会退回显示包名。改成 `it.pkg to it.name.takeIf { n -> n != it.pkg } ?: AppNames.nameOf(it.pkg)` 即可。

---

#### 五、P2（不阻塞）

| # | 问题 | 位置 | 修法 |
|---|---|---|---|
| P2-1 | `exportSchema = false`，没有 schema JSON；`Room version = 1` 与备份 `schemaVersion = 2`（C07）两套版本号并存，批 8 极易混淆 | `ScreenTraceDatabase.kt:24` | 开 `exportSchema = true` + `room.schemaLocation`；在 §5.10 写明「DB 版本」与「备份 schemaVersion」是两件事 |
| P2-2 | dump 为空时仍写 `pull_log(ok = true, "入库完成：0 天")` | `IngestPipeline.kt:91` | 空结果直接返回 `ok=false`/`note="dump 为空"`，符合 §6「保留旧数据并记录」 |
| P2-3 | `windowStart` 取解锁/通知两表 min 的较小值，算解锁覆盖时会被通知表更早的起点拉宽 | `RoomUsageRepository.kt:96` | 按 metric 分别取表 |
| P2-4 | `unlock_event` PK 只有 `occurred_at_ms`：跨账户两个真实解锁若撞同一毫秒会折叠（`day_summary` 计 2、事件表存 1） | `Entities.kt:51` | 注释说明，或 PK 加 user |
| P2-5 | `AppViewModel.DEFAULT_GOAL_MIN` 与 `SettingsStore.DEFAULT_GOAL_MIN` 双份 240 | 两处 | 常量收到 contract 层 |
| P2-6 | `appRanking` 把区间聚合行的 `date` 填成 `selection.endDate`，污染 `AppUsage.date` 语义 | `RoomUsageRepository.kt:49` | `date` 改可空，或新增 `AppUsageRange` |
| P2-7 | `mergeDay`/`mergeHour`/`mergeApp` 判出 existing 更大后仍 REPLACE 写回同一条 | `IngestPipeline.kt:41,56,65` | 命中「保留旧值」时跳过写 |

---

#### 六、放行条件

1. 修 P0（`deleteBetween`）——两行；
2. 修 P1-1（分类映射 + overrides 消费）——不然 6b 一接真实仓库，排行页当着人的面挂；
3. 补 P1-2 的管道测试（加 Robolectric）——这是 6a 唯一没交付的验收物；
4. P1-3 至少把 `pull_log.ok=false` 的日期接进 `gapDays`，或明确在 6a 交付说明里降级。

P2 可留到批 9 巡检，但 P2-1（schema 导出）建议现在改，越晚改越贵。

---

评审人：DeepSeek V4.1 Flash（独立会话）
实测：`JAVA_HOME=C:/Users/Administrator/.jdks/jbr-21.0.11 ./gradlew.bat :app:testDebugUnitTest --rerun-tasks` → 44 测，0 fail，0 skip
额外核对：`app/build/generated/ksp/debug/.../NotificationEventDao_Impl.java` 的 `deleteByMs` 生成代码、`RankingScreen.kt:99` 的 `groupBy { it.category }`、`UsageCalculator.coverageOf` 三态分支

---


## 【来源】批次6b-6d评审-DeepSeek V4.1 Flash.md

> 原路径 `docs/批次6b-6d评审-DeepSeek V4.1 Flash.md`（合并后原文件已移除，原始字节见 git 历史 `a89bd33:docs/批次6b-6d评审-DeepSeek V4.1 Flash.md`）

### 批次 6b–6d 代码评审（DeepSeek V4.1 Flash）

> 范围：`c92a693`（6a 返修）之后到 `20d2d1e`（6d）共 6 个提交，约 3.4k 行新增。
> 评审方式：读全部生产代码 + 强制重跑单测（`--rerun-tasks`）+ 写临时探针跑降级通道
> 的真实数据流（跑完已删除），不采信交付说明里的数字。

#### 结论先说

骨架和测试质量比上一轮明显进步：**105 测全绿 0 skip**，6a 那条 P0（`IN (:msList)`）
修得干净且补了「近五千条 + 反射守接口」的回归；6b 的两个「顺手修的真 bug」也是真的
（`WorkManager` 拖垮 `onCreate`、`persist` 接收者歧义）。

但 **6b 的降级通道在界面上是死的**：数据入库成功、仓库能读出来，四个页面却全部渲染成
「尚无使用记录」。这不是文案问题，是 `loadAll` 的入口判定只看 `day_summary`，而降级通道
按设计**不写** `day_summary`。对没有 Shizuku 的用户（也就是绝大多数用户）来说，App 拉了
一次、成功了、然后告诉他「没有数据」。

**判定：6b 不通过（P0-1），6d 有条件通过（P1-2 修完再上真机）。**

---

#### P0-1　降级通道的数据进得来，但一个页面都不显示

`UsageStatsUsageProvider` 按设计只写 `app_usage` / `hour_usage`，`days = emptyMap()`
（`UsageStatsUsageProvider.kt:117`，理由正确：拿不到 `KEYGUARD_*`，写 `day_summary`
就是假 0）。问题在读到界面这一段：

```kotlin
// AppViewModel.kt:408-419
val summary = repository.summary(selection)
if (!summary.hasAnyRecord) {          // = recordedDays > 0，只数 day_summary
    _todayState.value = LoadState(data = null)
    _statsState.value = LoadState(data = null)
    _calendarState.value = LoadState(data = null)
    _rankingState.value = LoadState(data = null)
    return@launch
}
```

我用真实调用链跑了一遍（临时探针，产物与降级通道完全一致：0 天日聚合 + 2 个应用 +
75 分钟小时矩阵）：

```
PROBE ingest ok=true msg=入库完成：0 天
PROBE summary.hasAnyRecord=false  recordedDays=0  totalMin=0
PROBE appRanking.size=2            ← 数据在库里，仓库也读得出来
PROBE hourly.sum=75                ← 时段分布同样读得出来
PROBE appUsageRows=2
```

也就是说 `appRanking` / `hourlyDistribution` 都正常，唯独入口被 `hasAnyRecord` 挡住，
四页统一走 `EmptyState`，今日页文案是「尚无使用记录 / 连接 Shizuku 并完成首次拉取后…」。
**拉取成功、`pull_log.ok=true`、没有任何错误提示**，用户只会认为 App 坏了。

三处连带影响：

1. **与 6b 交付说明自相矛盾**：文档写「代价是今日/统计页在降级通道下是空态，**排行与时段
   仍可用**」——实际排行也不可用；
2. **`historyCoverage()` 同样只看 `day_summary`**（`PullCoordinator.kt:161-170`），
   所以批 7 首启向导会把「只有应用数据」误判成「完全无数据」；
3. `UsageStatsUsageProvider` 类注释里「排行与时段仍可用——这是诚实的降级」这句，
   现在是一句没兑现的承诺。

**建议修法**（三选一，我倾向前两个组合）：

- 最小改动：仓库加 `suspend fun hasAnyData(): Boolean`（三表任一非空），`loadAll` 用它
  决定「是否整页空态」；`summary.hasAnyRecord` 只用于「日聚合指标是否有值」；
- 体验正确：降级通道下今日/统计保持空态但**换一套文案**（「已拉取到应用使用数据，
  解锁与通知需要 Shizuku」），排行页照常出数据——用户至少知道「拉了、有东西」；
- 契约层彻底解：`DaySummary` 的 `unlocks` 改可空/加 `unlocksAvailable`（6b 文档自己
  也提了这条），让「拿不到」与「真 0」在数据层就分开，顺带解决 P1-1。

---

#### P1-1　「解锁 0 次 / 已查看 0 次」的假 0 还在卡片上，而且 6d 说已兜住的那句话在真机上到不了

`MetricDetail` 没有「本指标是否有事件源」这个信息，UI 只能读 `total`。于是：

- `TodayScreen.kt:113-117`：`data.unlock` 非空但 `total = 0` → 卡片渲染 **「0 次」**；
- 同一份 detail 进详情页：`windowStart = null` → 所有点 `NO_RECORD` →
  `hasAnyRecordedPoint = false` → `DetailStateHost` 走 `EmptyState("该时段没有记录")`。

**卡片说 0，详情页说没有记录**，同一指标两个说法，正是 §7-16 要防的那种不一致。而这台
开发机（`KEYGUARD_HIDDEN` 10 天 0 条）就活在这个分支里。

顺带：6d 交付说明 §2.4 说「`total == 0` 时摘要区额外给一句『本次区间内没有解锁事件记录…』」
——这句话在 `windowStart == null` 时**不可达**，因为 `DetailStateHost` 在渲染 `summary()`
之前就短路成 `EmptyState` 了。只有在「窗口起点存在、但所选区间内恰好 0 条」时才看得到。

**修法**：`MetricDetail` 加 `hasEventSource: Boolean`（由 `windowStart != null` 派生），
卡片在 `!hasEventSource` 时渲染 `—` 且描述改成「暂无解锁事件」；详情页两种空态文案分开。

---

#### P1-2　区间态柱图横轴标签被槽宽裁掉（≥8 天的趋势不可读）

`MetricBarChart.kt:201-252`：每个槽固定 `width(slotWidth)`，细柱时 `slotWidth = 7 + 4 = 11dp`，
标签是 `Text(maxLines = 1)` 直接放在这个 11dp 的 Box 里。`label` 字号 12sp，
「11/8」约 26dp、4 个字——被约束到 11dp 后只会画出前 1–2 个字符。

对照 `CapsuleBarChart`：它是 `Column{ 柱; Text }` + `Arrangement.spacedBy`，标签会把槽宽
**撑开**（这就是批 2 记的 P2「标签撑开槽宽导致 30 天要横滚」）。新组件把槽宽固定住了，
等于把那个 P2 换成了「标签被裁」。

1–7 个点（粗柱 24+10=34dp）没问题，所以单日 24 小时视图是好的；**只有 ≥8 天区间会坏**，
而这恰好是「近 7 天/30 天趋势」的主场景。

**修法**：标签给一个固定宽度并居中偏移（`Modifier.width(48.dp).offset(x = -(48.dp - slotWidth)/2)`），
或 `wrapContentWidth(unbounded = true)`；顺带把 `CapsuleBarChart` 一起改成同一套，让两个柱图
真的共用规格。

#### P1-3　主通道超时会把「半份 dump」当成功入库，尾部静默丢失

`ShizukuShell.kt:68-86`：看门狗到点 `remote.destroy()`，`readAll` 捕异常后当 EOF 返回，
`text` 非空 → 不进 `if (text.isEmpty())` 分支 → 正常返回。

后果：13MB checkin 被截断后仍能解析出前 N 天（解析器是逐行特征匹配，不要求完整性），
入库 `ok=true`、`pull_log` 记成功、界面显示「已更新 N 天」。**今天的那部分数据就这么没了，
而且没有痕迹**。120s 在真机上大概率够（解析本身 1.05s），但这类「静默截断」一旦发生
就是最难查的 bug——它长得和「今天没怎么用手机」一模一样。

**修法**（任选，改动都很小）：
- `exec` 增加 `truncated` 结果位：看门狗先置一个 `AtomicBoolean`，`readAll` 返回时若已置位
  就抛 `ShizukuExecException("命令超时被截断")` → 协调器走「失败保留旧数据」；
- 或者做完整性校验：checkin 末尾应能匹配到 `Packages:` / `In-memory daily stats` 段，
  匹配不到视为截断。

#### P1-4　降级通道整类零测试，而它是普通用户唯一的通道

`UsageStatsUsageProvider` 没有任何测试（`ForegroundBucketerTest` 测的是纯函数，
适配层没测）：事件类型 `1 / 2 / 23` 的字面量映射（minSdk 26 拿不到常量，写错就是 0 数据）、
`appendSpan` 的跨零点按日切分、`perApp` 的 `launches` 归属、`days = emptyMap()` 这个
「绝不写日聚合」的约束——全靠人眼。这几处任一处写错，界面上都无法区分「没数据」和「算错」。

Robolectric 有 `ShadowUsageStatsManager`，可以像 `IngestPipelineTest` 那样把
「事件进 → 逐日逐应用分钟数出」钉住。这跟 6a 那条 P1-1 是同一种失守方式：声称覆盖，实际没覆盖。

---

#### P2（不阻塞，建议随批 7 一起收）

1. `AppUsageDao.sumBetween` 用 `MAX(name)` 取展示名 —— 字符串比较，ASCII 名的应用
   （`Zenly` < `com.xxx`）会被包名占位赢掉，回填过的真名又退回包名。应改
   `MAX(CASE WHEN name = pkg THEN '' ELSE name END)`。
2. `metricDetail(UNLOCK, …)` 也把区间内全部通知读出来（`RoomUsageRepository.kt:109`），
   解锁分支根本不用它。10 天 5k 行白读一次。
3. `MetricBarChart` 没有网格线，`CapsuleBarChart` 画了 5 条；刻度 y 偏移公式也不同
   （`(plotHeight-12)*…` vs `plotHeight*…-8dp`）。两个柱图并排放会看出差别。
4. `Icons.Filled.KeyboardArrowRight` 已废弃，清单声明了 `supportsRtl="true"`，RTL 下箭头方向会错
   （`MetricCard.kt:136`，其余 `Icons.Filled.List` 同理）。
5. `MetricCard` 同时设 `contentDescription` 和子文本，TalkBack 会把「解锁次数 86 次，查看详情」
   与卡片内文字各读一遍；应 `clearAndSetSemantics`。
6. 下钻箭头用的 `size(16.dp)`，计划写的是 12dp 前箭头。
7. `AppIcon` 每个包 `Bitmap.createBitmap` 最大 192²，通知排行用 `forEach` 全量渲染
   （不是 LazyColumn），几百个包时首帧会明显卡；建议加个上限或改 LazyColumn。
8. `ShizukuUsageProvider` 的 `capturedAtMs = clock()` 在解析**之后**取值，应取 dump 开始时刻
   （解析 1s 不影响，但语义上是「抓取时刻」）。
9. `RoomUsageRepository.minOfNotNull` 已成死代码（windowStart 改按指标取最小后就不用了）。
10. 每次从「数据表」切回「图表」都会重播柱体生长（`remember(points)` 随组合重建），
    与交付说明里「已有缓存直接渲染，不重播入场」不符。
11. 返回恢复滚动位置依赖共享 `ScrollState` + `LaunchedEffect(page)`，`scrollTo` 发生在
    新内容测量之前，长内容可能回不到原位——这条只能真机验，先记着。
12. `PullCoordinator` 补记 `pull_log` 时 `source` 恒取 `providers.first()`，两条通道都不可用时
    会记成 `shizuku`，与实际失败通道无关。

---

#### 做得对的地方（避免下一轮被改坏）

- **6a 的 P0 修得彻底**：`deleteBetween` 区间删除 + 「近五千条两次拉取」回归 +
  反射断言「DAO 删除接口不得再收 List」。这条我认为可以结案了。
- **测试有牙齿**：6b 自述的变异验证（节流分档改成恒 30 分钟 → 1 条报红）是真的会红；
  6a 那条「对账断言被跳过」的假绿没有重演——`ShizukuEndToEndTest` 用 `assertTrue(file.exists())`
  硬失败，不是 `assumeTrue` 静默跳过。
- **`ParseResult.isEmpty` 收敛成单一出处**，同时服务管道与协调器，`EmptyDump` 从「不可达」
  变成真状态——这是正确的抽象。
- **`ForegroundBucketer` 以「切到下一个应用」收尾**这个判断是对的，而且文档写清了为什么
  （只信 `ACTIVITY_PAUSED` 会把被杀进程的区间一路延到拉取时刻）。
- 三态（有记录/真实 0/无记录）在表格与柱图里是分开实现的，且 `MetricTableTest` 把「缺口不
  画成 0」钉住了；占比以 `detail.total` 为分母，跟摘要大数字同源，这个恒等式可测。
- 零硬编码色（`ui/`、`uikit/`、`ui/detail/` 全部走 `LocalFreshPalette`）。

---

#### 建议顺序

1. **P0-1**（降级通道可见性）——这是「功能在、用户看不到」，优先级最高；
2. **P1-1**（假 0 + 卡片/详情不一致）——同一次改动里能一起收，`MetricDetail` 加标志位；
3. **P1-3**（截断当成功）——两行改动，防的是最难查的静默数据丢失；
4. **P1-2**（柱图标签）与 **P1-4**（降级通道测试）——真机验证前修掉，否则第一次装上去
   就会同时撞上「字被裁」和「这条通道从没测过」。

修完 P0-1 + P1-1 之后，6b 的验收项「Empty→Room 一次性切换，四页显真实数据」才真正成立；
6b 交付说明里「排行与时段仍可用」这句也需要按实际能力改写。

---


## 【来源】批次6g-7-8a-10a-14a评审-DeepSeek V4.1 Flash.md

> 原路径 `docs/批次6g-7-8a-10a-14a评审-DeepSeek V4.1 Flash.md`（合并后原文件已移除，原始字节见 git 历史 `a89bd33:docs/批次6g-7-8a-10a-14a评审-DeepSeek V4.1 Flash.md`）

### 批次 6g / 7 / 8a / 10a / 14a 评审

评审日期：2026-11-12
评审对象：`319fdc0`（6g 收口）、`58de38c`（14a 周期总结）、`1c441d1`（批 7 设置/向导/报告）、`373e0f9`（10a 主题引擎）、`7650966`（8a 备份恢复）
代码规模：约 7,400 行新增，5 个提交
评审方式：逐文件走查 + `--rerun-tasks` 强制重跑全量单测 + 写临时 Robolectric 探针跑真实 Room 验证合并语义

---

#### 一、结论

| 批次 | 判定 | 阻断项 |
|---|---|---|
| 6g 收口（19/23/24/25/26） | **通过** | — |
| 7 设置 + 向导 + HTML 报告 | **有条件通过** | P1-4（向导门控白屏） |
| 8a 本地备份恢复 | **有条件通过** | P1-1（绕开自洽校验）、P1-2（会话合并零测试）、P1-3（迁移零测试） |
| 10a 主题派生引擎 | **有条件通过** | P1-5（选预设后界面不变） |
| 14a 周期总结 | **通过** | — |

全量单测：**261 例，0 fail，0 skip**（`--rerun-tasks` 真跑，非 up-to-date）。上一轮那种「断言被跳过」的假绿没有重演：`AppViewModelDegradedTest`、`UsageStatsUsageProviderTest`、`ShizukuEndToEndTest` 都是实断言。

值得记一笔的几处正确决策：

- 6g 把「区间手输」抽成 `DateInput` 纯函数并用 `ResolverStyle.STRICT`，`2026-02-30` 被拒而不是静默进位——这是最容易写错的一处，且有 5 条测试钉住；
- 14a 把「自然周期」与「数据窗口」拆成两个字段（`range.end` vs `range.dataEnd`），未结束时覆盖率只按已过天数算，`previousEqualLength()` 取上一周期**前** N 天而不是最后 N 天，方向正确；
- 8a 的 `BackupMerge` 是纯函数，`day_summary` 整行取大而不是逐字段取大——这条规则如果写错，会产出「解锁次数 900、屏幕时长 3 分钟」的拼行，注释里明确写了原因；
- 10a 的 `PaletteEngine` 有「极端种子 8 个 + 随机 50 个 × 深浅」的对比度断言，`safeSeed` 先夹再派生，不是拍脑袋调亮。

---

#### 二、P1 逐条

##### P1-1（8a）合并恢复绕过 `IngestSanity`，虚高应用行会永久留在库里

`IngestPipeline` 在入库前跑 `IngestSanity.check`：当日应用合计超过「屏幕时长 × 3」就整日丢弃应用/时段数据（`SLACK_RATIO = 2.0`）。理由是 MIUI 的 `totalTimeUsed` 会把后台常驻、画中画、跨天累计算进日块——真机出现过单应用 1386 分钟而当天屏幕只有 510 分钟。

`BackupRepository.merge()` 直接把 `file.appUsage` 交给 `BackupMerge.appUsage()`，**没有任何自洽校验**：

```kotlin
val appPlan = BackupMerge.appUsage(localRows, file.appUsage)   // BackupRepository.kt:224
insertAppUsage(appPlan.rows)
```

探针实证（Robolectric 真 SQLite，本地 11-09 只有 45 分钟日聚合，备份里塞一行 8888 分钟）：

```
day 2026-11-09 screen=45 unl=45 unlocks=2
inflated rows = [(com.inflated, 8888)]        ← 直接入库
```

后果比「一次多算」严重：合取大，`MergePolicy.mergeApp` 之后每次拉取都取大值，正确的新数据永远盖不掉它。这正是 `MIGRATION_2_3` / `MIGRATION_3_4` 两次「清表重建」要治的病，恢复一个旧备份（或另一台 MIUI 手机的备份）就会原样复发，用户看到的是「应用合计 2467 小时 > 屏幕 11 小时」。

**修法**：`merge()` 里按日跑一次 `IngestSanity.check`（它已经是纯函数），或用更轻的判据——`minutes > 当日 screenMin × 3` 或 `minutes > 1440` 的行不入库，并在结果摘要里报「丢弃 N 行异常应用数据」。同时补一条 `BackupRepositoryTest`：备份带虚高行 → 合并后库里没有该行。

---

##### P1-2（8a）`mergeSessions` 是 8a 最危险的一段，却一条测试都没有

`BackupRepositoryTest` 只有 4 例，全部走 day/app/hour/pull 路径；`seed()` 里插了 `screen_session`（第 68 行），但备份文件里从未出现过 `screenSession` 行——`file.screenSession` 恒为空，`mergeSessions` 里「并集 + 重算 + 24 桶」的主干从未被执行。

而这段代码是 8a 唯一会 `deleteAll()` 再重建、还会改写 `day_summary` / `hour_usage` 的地方（`BackupRepository.kt:286-331`）：

```kotlin
db.screenSessionDao().deleteAll()
db.screenSessionDao().insert(screen + keyguard)
...
db.daySummaryDao().upsert(DaySummaryEntity(date, maxOf(slice.screenMin, current?.screenMin ?: 0), ...))
mergedHours.forEachIndexed { hour, minutes -> db.hourUsageDao().insert(HourUsageEntity(...)) }
```

我写了探针验证正常路径是**对的**：

```
本地 10:00–10:30  ∪  备份 10:00–11:00  →  sessions = 1 条 60 分钟
day 2026-11-10 screen=60 unl=60 unlocks=5   ← unlocks 保留，没有被打成 0
hour rows 11-10 = 24 sum=60
```

但「我手测一次对」和「以后不被改坏」是两件事。这是上一轮 `deleteByMs` 那类问题的同一面：危险路径没有验收物。建议补两例：
1. 本地 + 备份区间重叠 → 并集正确、`day_summary` 取大不回退、`unlocks` 不被清零；
2. 只有备份有会话（本地空）→ 自动建日聚合与 24 桶，且 `screenMin` 与并集时长一致。

---

##### P1-3（8a）四次迁移零测试，其中两次是不可逆清表

`Migrations.kt` 有 `1→2`（建 `screen_session`）、`2→3`（`DELETE app_usage`）、`3→4`（`DELETE hour_usage`）、`4→5`（建 `app_limit`）。项目里 `exportSchema = true`、`app/schemas` 有 1..5 五份 JSON，`MigrationTestHelper` 的原料齐了，但没有一条迁移测试（`grep MIGRATION_` 在 `src/test` 与 `src/androidTest` 均无命中）。

风险不是理论值：迁移写错（列类型、索引名、`NOT NULL` 漏写）时 Room 会在**用户升级后第一次打开数据库**抛 `IllegalStateException`，App 直接起不来；而本项目的兜底手段恰好是"清表重建"，用户只能丢数据。`4→5` 是这次新写的，索引/主键名靠人眼对齐 `5.json`，这是最容易差一个字符的地方。

建议至少补 `4→5`：`MigrationTestHelper.createDatabase(4)` → `runMigrationsAndValidate(5, MIGRATION_4_5)` → 断言 `app_limit` 可插入可查询。这是 20 行的事。

---

##### P1-4（7）DataStore 读失败时，首启向导会把 App 卡成永久空白

`AppNav.kt:233`：

```kotlin
if (onboardingDone != true) {
    Box(Modifier.fillMaxSize().background(palette.background)) {
        if (onboardingDone == false) OnboardingScreen(...)
    }
}
```

`onboardingDone` 的三态是 `null`（还没读出来）/ `false` / `true`，`null` 只铺底色——这个设计对「首帧不闪主界面」是对的。但它的唯一写入方是：

```kotlin
viewModelScope.launch { runCatching { store.onboardingDone.collect { _onboardingDone.value = it } } }
```

一旦 DataStore 读取抛异常（prefs 文件损坏、从别处恢复的 `datastore/` 目录、磁盘满），`runCatching` 吞掉异常、流结束，`_onboardingDone` **永远停在 null**：既不出向导，也不出主界面，用户看到一块纯色屏，重启也一样。同一份代码里其它七个设置的默认值都有兜底（`dailyGoalMin` 等直接给了初始值），只有这个门控是硬等待。

**修法**：给门控加超时降级（`LaunchedEffect { delay(1500); if (_onboardingDone.value == null) _onboardingDone.value = false }`），或在 `runCatching` 的 `onFailure` 里显式置 `false`。两者都只影响异常路径。

---

##### P1-5（10a）选了自定义主题之后，点「界面风格」里的九套预设，界面不会变

`ScreenTimeTheme` 的优先级是 `customSeed ?: variant.palette(dark)`（`ScreenTimeTheme.kt:106`），而风格弹层的回调没有清种子：

```kotlin
StyleSheet(current = variant, onSelect = viewModel::setThemeVariant, ...)   // AppNav.kt:623
```

`setThemeVariant` 只写 variant，不动 `customThemeSeed`。于是用户启用过自定义主题后再选「海盐」「暮色」，`variant` 变了、`customSeed` 还在 → 色板仍由种子派生 → **界面一个像素都不变**，只有「外观」组里那行 "预设（已自定义）" 透露出发生了什么。用户会判定主题切换坏了（§5.4 要求切 variant 只替换调色板）。

**修法**：`onSelect = { viewModel.setThemeVariant(it); viewModel.setCustomThemeSeed(null) }`；或保留种子但把弹层里当前项显示为「自定义（不跟随预设）」。前者更符合"点哪套就是哪套"的直觉。

---

#### 三、P2 清单

1. **`pull_log` 合并不幂等**（`BackupRepository.kt:240`）：备份里的拉取日志无条件 `insert`，而本地可能已有同一条。探针：本地 1 行 → 第一次合并 2 行 → 第二次 3 行，摘要还报"新增 1 条"。`historyCoverage` 用日期集合所以数字不脏，但设置页「最近成功拉取」取 `MAX(pulledAt)`，会被另一台设备更晚的备份时间戳顶掉。建议按 `(pulledAt, source)` 去重。
2. **`mergeSessions` 每天固定写 24 行（含 0 分钟）**：`hour_usage` 里出现"该小时 0 分钟"的行，而 `hasDataIn()` 只数行数（`RoomUsageRepository.kt:66-72`）→ 只恢复了会话、没有事件的日期会被判成"有记录"。与"无记录不画 0 柱"的口径相抵。建议只写 `minutes > 0` 的桶，或让 `hasDataIn` 按 `SUM(minutes) > 0` 判。
3. **`daySummaries` 同 `screenUnlockMin` 时不比 `screenMin`**（`BackupMerge.kt:45`）：字面实现了「按 screenUnlockMin 整行取大」，但两台设备可能一个 `screenMin=200/unlock=60`、一个 `100/60`，合并后保留后者，屏幕时长少一半。建议加平手时的次级比较（`thenBy screenMin`）。
4. **恢复大备份的内存与校验**：`BackupGroup.kt:129` 一次性 `readText()`，`BackupCodec.decode` 先 `parseToJsonElement` 再 `decodeFromString`（同一份文本解析两遍）。多年备份（事件表几十万行）在 2GB 机型上有 OOM 风险，且**没有行级校验**——`date="abc"`、`hour=99` 都能入库。建议先读 `schemaVersion` 做流式预检，并对 date/hour 做格式与范围过滤。
5. **v1/v2/v3 兼容分支没有任何真实旧文件样本**：`missingTables` 只按版本号推断（`<2` 缺事件、`<3` 缺会话…）。建议把一个最小 v2 JSON 字符串固化成测试常量，跑 `decode → merge` 断言不崩、缺表提示正确。
6. **CSV 转义不完整**（`escapeCsv`）：漏 `\r`；也不防 `=`/`+`/`-`/`@` 开头的公式注入（应用名可控，Excel 打开会当公式）。另：CSV 导了 7 张表，`pull_log` 未包含，若 §5.10 要求八表需补。
7. **`paletteFromSeed` 无 `remember`**（`ScreenTimeTheme.kt:106`）：每次重组合都重跑派生 + 校正，最坏 11 次 `fit` × 100 步 ≈ 千余次对比度计算。建议 `remember(customSeed, darkTheme) { PaletteEngine.paletteFromSeed(...) }`。
8. **`fit()` 越界用 `return@repeat`**（`PaletteEngine.kt:194`）：只跳过当次迭代，继续白跑剩余步数；语义上应 `break`。结果没错，纯浪费。
9. **`TodayScreen.kt:73` `state.data == null && pulling` 恒真**（编译警告已报）：前面分支已判过非空，这个条件是冗余。建议改成 `else if (pulling)`，顺手确认 Stats/Calendar 有无同款（本轮只报了 Today）。
10. **恢复后「最近成功拉取」不刷新**：`_lastPulledAt` 只在 `applyPullOutcome` 里赋值，恢复只更新 `_coverage` + `loadAll()`，设置页那一行与 `pull_log` 里的新行短期内不一致。
11. **`BackupGroup` 的文件选择器**用 `arrayOf("application/json", "text/plain", "*/*")`，第三个把前两个的作用抹平了；`busy` 在 `onBuildJson` 抛异常时会永久卡住（当前 VM 内部 `runCatching` 兜住了，属"靠调用方保证"）。
12. **主题种子在备份里是 `Long`，`Color(it)` 依赖 sRGB 打包格式**：当前 `hslColor` 产出必然是 sRGB 所以安全，但若将来支持广色域，`and 0xFFFFFFFFL` 的截断会丢色彩空间标记。建议在注释里钉住这个前提，或存 `#RRGGBB` 字符串。

---

#### 四、建议修复顺序

1. **P1-1**（合成一行 `IngestSanity` 过滤 + 一例测试）——数据正确性，越晚修越可能已经污染用户库；
2. **P1-2 / P1-3**（两条测试，纯测试文件，不动生产代码）——把 8a 的验收物补齐；
3. **P1-5**（一行回调）——用户可感知的"功能像坏了"；
4. **P1-4**（异常路径兜底）——影响小但后果重（白屏无法自救）；
5. P2 里优先做 1、2、7：分别对应「日志膨胀」「假 0 口径」「主题重组合开销」。

修完这四条 + 补完两处测试，8a 可以结案并进入 8b（WebDAV/Drive）。8b 会复用同一套 `BackupMerge` 与 `restore`，P1-1 那种"信任远端数据"的假设在云同步里会更危险，建议在那之前定好「远端数据先过一遍自洽校验」的原则。

---


## 【来源】批次8a返修复核与R9-7裁决-DeepSeek V4.1 Flash.md

> 原路径 `docs/批次8a返修复核与R9-7裁决-DeepSeek V4.1 Flash.md`（合并后原文件已移除，原始字节见 git 历史 `a89bd33:docs/批次8a返修复核与R9-7裁决-DeepSeek V4.1 Flash.md`）

### 批次 8a 返修复核 + R9-7 裁决说明（DeepSeek V4.1 Flash，2026-11-12）

> 对象：提交 `060696d`（6g/7/8a/10a/14a 评审修正）+ 本轮真机反馈两条 bug。
> 方法：`--rerun-tasks` 强制重跑、逐条读改动、用新增测试的断言形态判断是否真能变红，不采信提交信息里的自述。
> 结论：**返修达标，8a 可结案进 8b**；R9-7 见第一节，建议 ①（登记偏差）。

---

#### 一、需要你裁决的只有一件事：R9-7（§4 列名 vs 物理列名）

##### 1.1 现象是什么

需求 §4 把表结构写成 SQL 记法：

```
day_summary(date PK, screen_min, screen_unlock_min, unlocks, launches, longest_session_min, pulled_at)
```

而 SQLite 里的实际列名（`schemas/5.json`，v1 起就是这样）是 camelCase：

| §4 写的（逻辑名） | 物理列名（`PRAGMA table_info`） |
|---|---|
| `screen_min` | `screenMin` |
| `screen_unlock_min` | `screenUnlockMin` |
| `longest_session_min` | `longestSessionMin` |
| `pulled_at` | `pulledAt` |
| `occurred_at_ms` | `occurredAtMs` |
| `channel_id` | `channelId` |
| `start_ms` / `end_ms` / `limit_min` | `startMs` / `endMs` / `limitMin` |

**只影响库内**。对外两种格式都是 snake_case，合规：

- CSV 明细表头：`BackupRepository.kt:118` 写死 `listOf("date","screen_min","screen_unlock_min",…)` ✅ §5.10；
- 备份 JSON 的字段名走 `@SerialName`，也是 snake_case ✅。

这个偏差是补 `MigrationTest` 时被 `table day_summary has no column named screen_min` 顶出来的——**两轮评审都没发现**，因为评审都在读 Kotlin（Entity 字段名 = camelCase 很自然），没人去对 §4 的 SQL 记法。

##### 1.2 为什么这算「要你拍板」而不是我直接改

- §4 那句红线原文是「**只增不改**：原四表、原字段与既有口径全部保持不变」（CR-01 加的）。它保护的是**逻辑字段与口径**，不是拼写；但字面上「原字段不变」也能被读成「列名不变」，两种解释都站得住，所以这是**解释权**问题，不是技术判断。
- 改物理列名不是改个注解：`@ColumnInfo(name = "screen_min")` 之后必须 `version 5→6` + 五张表重建迁移（minSdk 26，SQLite 不支持 `RENAME COLUMN`，只能 建新表→灌数据→删旧表→改名）。风险落在**已有数据的迁移**上，而迁移正是当前测试覆盖最薄的一环（见 3.3）。
- 收益只有「文档与库一致」。没有任何用户可见行为、性能、体积差异。

##### 1.3 两个选项

| | ① 维持现状（推荐） | ② 统一改列名 |
|---|---|---|
| 动作 | 批 9 文档勘误：把 §4 标注为「**逻辑名**」，并在工程说明里给一张「逻辑名 → 物理列名」对照表；`MigrationTest` 里已写注释说明为何 SQL 用 camelCase | `@ColumnInfo` 全表加名 + `MIGRATION_5_6` 五表重建 + 备份兼容验证 + 迁移测试 |
| 风险 | 零代码风险；残余风险是**下一个人**又照着 §4 写 SQL（加一张对照表 + 一条注释即可挡住） | 迁移写错 = 升级即崩；需要真机拿旧库跑一遍；「原字段不得改义」的边界要再解释一次 |
| 成本 | 10 分钟文档 | 半天 + 一轮真机验证 |
| 一致性收益 | 文档层解决 | 库层解决 |

**我的建议：①。** 物理列名是内部实现细节，SQLite 没有「契约」概念，对外契约（CSV/JSON）已经是对的；用一次高风险全表迁移换取「文档里的拼写与库一致」，性价比不成立。真正要治的是「读文档的人不知道 §4 是逻辑名」——这靠一行标注就能根治。

若你选 ②，我建议同时把 `schemaVersion` 一起处理（库 6 / 备份仍 4，两套版本号本来就是独立语义），并先在真机上用现有 v5 库跑一遍迁移再合。

---

#### 二、修复核对（R9-1 ~ R9-6）

实测：`./gradlew :app:testDebugUnitTest --rerun-tasks` → **265 测全绿、0 fail、0 skip**（本轮又加 2 条，现 267）。新增用例逐条读过断言，**不是**「声明覆盖」。

| 编号 | 判定 | 证据 |
|---|---|---|
| R9-1 合并恢复过 IngestSanity | ✅ 达标 | `BackupRepository.merge()` 先用**合并后的 `day_summary`** 当参照跑 `IngestSanity.check`，按 `droppedDates` 过滤备份侧 app/hour 行，`skipped` 计入摘要（`BackupRepository.kt:227-257`）。测试 `合并恢复丢弃虚高应用行` 断言 8888 分钟的行不入库 + `skipped>=2`——把它去掉后断言必红，是真断言 |
| R9-2 mergeSessions 补测试 | ✅ 达标 | 两例：重叠区间并集 60min（本地 30 ∪ 备份 60）、`unlocks` 保留 5 不清零、`hour_usage` 只有 10 点一格且合计 60；仅备份有会话时自动建日聚合。`deleteAll` 后重建这条路径终于有测试兜着 |
| R9-3 迁移测试 | ⚠️ **部分达标** | 只补了 `MIGRATION_4_5`（v4 库 → v5 打开，Room 端到端 schema 校验，这一步做得对）。`MIGRATION_1_2 / 2_3 / 3_4` 仍零覆盖，而 **2→3、3→4 是清表重建**——写错等于老用户升级即崩。已登记 R9-11 到批 9。**不阻塞 8b**（这三条早已上线到你的机器并被验证过），但不能算「迁移零测试」这条 P1 完全关闭 |
| R9-4 选预设清种子 | ✅ 达标 | `StyleSheet.onSelect` 里 `setThemeVariant` + `setCustomThemeSeed(null)`；`customSeed ?: variant.palette()` 的优先级不再挡路 |
| R9-5 向导白屏兜底 | ✅ 达标（留一条尾巴） | `collect` 的 `onFailure` 置 `false` → 走向导。**尾巴**：若流「正常结束但一次没发射」，`onFailure` 不会触发，`_onboardingDone` 会停在 `null`。DataStore 的 preferences flow 不会这样，但换 store 实现时是隐患（P2，批 9 可加 `withTimeoutOrNull` 兜底） |
| R9-6 P2 顺手 | ✅ 达标 | pull_log 按 `(pulledAt, source)` 去重、0 分钟桶不写行 + `hasDataIn` 按分钟判、`daySummaries` 平手比 `screenMin`、`paletteFromSeed` 加 `remember` |

**一处需要写清楚的副作用**：R9-1 是「整日丢弃」，所以备份里某天只要有一条虚高行，**该天备份侧的全部正常应用行也一起丢**（测试里 `com.a` 就跟着被丢）。这是 `IngestSanity` 的既定语义（宁可整日无数据也不给编的分布），本地已存的行不受影响，摘要里报「丢弃 N 行」。行为可接受，但用户看到「恢复后这天应用排行少了」时没有解释入口——建议批 9 在恢复摘要里把这句写成人话（如「11-10 的数据与屏幕时长矛盾，已按不可信丢弃」）。

---

#### 三、本轮真机两条 bug

##### 3.1 a）刷新后转圈不消失 —— 术语与根因

**专业名词**：**「状态未复位」/ 状态机泄漏（stuck refreshing state / leaked loading state）**，界面表现叫**永久 Loading 态（perpetual loading）**；根因是**缺少 finally 保证的清理（no guaranteed cleanup）**，以及**阻塞 IO 没有硬超时（missing hard timeout on blocking IO）**。

两层原因，都成立：

1. **UI 层没有 finally**（`AppNav.kt:451`）。原代码：

   ```kotlin
   LaunchedEffect(Unit) {
       viewModel.refreshFromPull()   // 中间任何异常 / 协程取消
       pullState.endRefresh()        // ← 就永远执行不到
   }
   ```

   `refreshFromPull()` 里 `runCatching` 只包了 `coordinator.pull()`，**没包 `applyPullOutcome`**；而 `applyPullOutcome` 里有 `historyCoverage()`（Room 查询）和 `loadAll()`。DB 出错、覆盖范围查询抛异常、或页面被销毁导致协程取消，`isRefreshing` 就永久停在 `true`，指示器一直转。这种 bug 比崩溃难查：界面看着还在「努力」，用户只会一直等。

2. **数据层可能永不返回**（`ShizukuShell`）。超时靠 `remote.destroy()` 杀远端进程让 `read()` 返回 EOF，但 `destroy()` 要经 binder 到 Shizuku 服务端才生效；**服务端已死 / binder 断 / 进程已退出时它静默失败**，而 `ins.read()` 是阻塞 IO、不吃协程取消 → `coroutineScope` 永不结束 → `pull()` 永挂起 → 指示器永久转圈，只能杀进程。这解释了「等多久都不消失」，而不是「慢」。

**已修**（三层，最小改动）：

| 位置 | 改动 |
|---|---|
| `AppNav.kt` | `try { refreshFromPull() } finally { pullState.endRefresh() }` |
| `AppViewModel.pullOnOpen/retryPull` | `_pulling` 复位进 `finally`（原样也会被 `applyPullOutcome` 的异常漏掉，顶部「正在拉取使用记录」同样会卡死） |
| `ShizukuShell` | 新增 `StreamCloser`：看门狗**先关 fd、再 destroy**。关流是本进程唯一能保证硬打断阻塞 `read()` 的手段，`destroy()` 退化为「尽量」 |

回归测试：`StreamCloserTest` 2 例（先 bind 后 close、看门狗先开枪后 bind 补关）。

**残余建议**（未做，等你定）：给 `refreshFromPull` 加一个 UI 侧的「最长展示时长」（如 150 秒后强制收起并提示「拉取超时，已保留旧数据」）。现在保证的是「不会永久转圈」，但如果一次 dump 真读满 120 秒×2 条命令，用户还是得对着转圈等 4 分钟。

##### 3.2 b）「低于 x 小时」要滚轮选择 —— 术语与实现

**专业名词**：**滚轮选择器（Wheel Picker / Number Picker）**；交互形态是**底部弹层（Modal Bottom Sheet）+ 吸附式滚动（Snap Fling）**；Android 原生同类控件叫 `NumberPicker`，iOS 叫 `UIPickerView`。Compose Material3 **没有内置**滚轮，标准做法是 `LazyColumn` + `rememberSnapFlingBehavior`（吸附交给 `FlingBehavior`，不要自己写「滚完再 `animateScrollToItem` 收尾」，那种写法快速甩动时会先甩过头再回弹，手上是「打滑」感）。

**这是一条需求变更（CR-05），不是 bug**：§5.8 与验收 §7-23 原文写的是「滑杆 + 数字双向输入」。已按项目约定**先改文档再改代码**：

- 需求 §5.8 / §7-23 改为滚轮（已落档，头部新增 CR-05 条目）；
- 新增 `uikit/NumberWheelPicker.kt`：5 格可见、单格 44dp、上下 `contentPadding` 各 2 格（于是停下时 `firstVisibleItemIndex` 就是居中格，不需要物理计算）、中线双光带（静态线比「选中项背景块」稳，不会跟着内容抖）、点某格可直接滚过去、无障碍整块报「当前 N 小时」；
- `SettingsScreen.ThresholdGroup`：滑杆 + 输入框 + 两份草稿 → 一行「日历里「低于 x 小时」的判断标准  3 小时 ›」+ 弹层。

**需要你知道的两个连带项**（未改，等你决定）：

1. **每日目标**（`GoalGroup`）仍是「滑杆 + 数字输入框」，就在同一个设置页上方。两个同类输入用了两套交互，观感不统一；改起来是同一处组件、约 20 行。**要不要一起换成滚轮？**
2. §5.12 **应用限额**同样是「滑杆 + 数字双向输入」（15 分钟–23 小时 45 分，步进 15 分钟），批 9 实现时是否也用滚轮（分钟粒度 15 分钟一档，滚轮更合适）？

**未在真机验证**：本轮只做了编译 + 单测；滚轮的观感（格高、中线位置、甩动手感）需要你在机器上过一眼。

---

#### 四、放行判定

- **8a：结案。** R9-1/2/4/5/6 达标，R9-3 部分达标（1→4 迁移排期批 9）。
- **可以进 8b（WebDAV / Google Drive 云同步）**，前提是把上一轮提的那条原则写进 8b 的验收：**远端数据先过自洽校验**——现在同一入口（`BackupRepository.merge`）已经覆盖本地与远端，8b 不要另开一条「直接写库」的旁路。
- R9-7 等你裁决（建议 ①）；R9-9/R9-10 已修，R9-11 排期批 9。

---

#### 附：本轮改动文件

```
M app/src/main/java/com/screentrace/app/ui/AppNav.kt              try/finally 复位指示器
M app/src/main/java/com/screentrace/app/ui/AppViewModel.kt       _pulling finally 复位
M app/src/main/java/com/screentrace/app/data/pull/ShizukuShell.kt StreamCloser：先关流再 destroy
M app/src/main/java/com/screentrace/app/ui/SettingsScreen.kt     阈值组改滚轮入口
A app/src/main/java/com/screentrace/app/uikit/NumberWheelPicker.kt
A app/src/test/java/com/screentrace/app/data/pull/StreamCloserTest.kt
M docs/需求文档-屏幕使用时间App.md                                 CR-05 + §5.8 + §7-23
M docs/实施任务批次计划-v2.md                                      R9-9/R9-10/R9-11 登记
```

---

## 【来源】批次CR-15评审-DeepSeek V4.1 Flash.md

> 原路径 `docs/批次CR-15评审-DeepSeek V4.1 Flash.md`（2026-11-14 按负责人「评审不单独建档」要求合并，原文件已移除；合并前为未入库文件，无 git 历史可回溯）
> 归档说明：正文一字未改，仅把原 `#`/`##`/`###` 标题整体下移两级，以免与分节标题撞级。

### CR-15（A0 / A1 / B）代码评审 — DeepSeek V4.1 Flash

> 评审时间：2026-11-14 12:30 · 基线 `bab3a3f`（工作区干净）
> 评审范围：CR-15 三批（A0 日期输入、A1 时段日均 + 切日期过渡、B 日期双状态）+ 与它们直接相邻的今日/统计/详情/顶栏接线；同时复核自上次评审（8a 返修）以来的静态红线。
> 未覆盖：CR-06～CR-13 的视觉/转场逐项重审（本轮只查与 CR-15 交界处）、批 8b-2 网络层单测细节、批 10b/11-14b 未开工项。

---

#### 0. 结论速览

| 批次 | 判定 | 依据 |
|---|---|---|
| A0 日期输入（分段智能输入 + 两次闪退修复） | **通过** | 掩码/映射/规范化三个纯函数都打靶生产实现；两处崩溃的回归用例真实命中；`STRICT` 解析拒绝 `2026-02-30` |
| A1 ⑥ 时段分布真日均 | **实现通过，验收物不通过** | 口径正确（`hour_usage` 累计 ÷ `recordedDays`，两处同源）；但**两处接线零断言**——把接线改回累计值，360 测仍全绿（见 P1-2，已实测） |
| A1 ④ 切日期过渡 | **今日页通过 / 统计页未同步** | 今日页去 Crossfade 的做法正确且真机抽帧有据；统计页仍是整页 `Crossfade`（P2-1） |
| B 日期双状态 | **基本通过** | 9 测实跑、按 `returnTo` 归属正确、日历 tab 真无日期按钮；但详情页区间选择静默无效（P1-1，探针实证） |

**放行意见**：A0 可以结案；A1-⑥ 补 2 条接线测试、B 修 P1-1 后可结案。P1-1 需要产品口径裁决（见 §4）。**不建议**在 P1-2 修复前把 CR-15 标为「验收完成」——它和之前两轮「声称覆盖、实际没覆盖」是同一种失守方式。

---

#### 1. 实测证据

| 项 | 结果 |
|---|---|
| `:app:testDebugUnitTest --rerun-tasks` | **360 tests / 0 fail / 0 skip**（真跑，非 up-to-date） |
| 工作区 | `git status` 干净，三批均已提交 |
| 硬编码色（`ui/`、`uikit/`，排除 `theme/`） | 0 处 |
| 运行时依赖 | `git diff 77f024c..HEAD -- app/build.gradle.kts` 为空，红线未破 |
| 临时探针（Robolectric + 真 Room + 真 `AppViewModel`） | 见 §3 P1-1 |
| 变异实验 | 见 §3 P1-2 |

---

#### 2. 做得对的地方（不必再动）

1. **A0 的测试是打靶生产代码，不是复刻公式**。`DateInput.mapToTransformed / mapToOriginal / maskDigits / normalizeInput` 都被直接调用，注释里也写明「不再复刻测试自己的公式（防假绿）」——这正是前两轮缺的东西。
2. **两个崩溃的防线是结构性的**，不是加 `try/catch`：8 位硬截 + 双向钳制解决 `9 -> 11 is not in range`，`parse` 与 `MIN_YEAR/MAX_YEAR` 守卫解决 `year (1234)`。钳制写在映射函数内部（`coerceIn`），任何调用方都不会再越界。
3. **B 的 `dateScopeFor(page)` 用纯函数表达归属**，二级页靠 `AppPage.mainOf` 的 `returnTo` 推导，而不是在 UI 里散落 `if (page is UnlockDetail)`。`DateScopeTest` 把「三个主页面 + 五个二级页 + null」都钉住了。
4. **`AppViewModelDateScopeTest` 断言打在两个层面**（状态本身 + `summary.selection`），所以「`applySelection` 又写回同一个全局状态」这种退化会变红——这是这批测试真正的价值所在。
5. **日历不再被筛选污染**：`historySelection()`（今天起往前 20 年）+ `extremes/streak/yearLevels` 三条查询各自独立，测试里「统计页切到今天，日历极值不变」的断言直接把这条钉住。
6. **`dailyAveragePeriods` 的边界处理干净**：`recordedDays <= 0` 返回全 0（不渲染假数值）、四舍五入、`coerceAtLeast(0)`；两处消费点（统计页 / 周期页）都走了同一个函数，标题也补了「日均」。
7. 今日页去掉整页 `Crossfade` 的判断是对的：`MetricBarChart` 是 `remember(points){ Animatable(0f) }`，任何重建内容分支的容器都会让柱图/圆环重播入场动画——这与「减少动画」和「不闪一帧」是同一件事的两面。

---

#### 3. P1（两条，建议本批内解决）

##### P1-1　详情页可以选区间，但数据窗口恒为单日 → 选择静默无效

**复现路径（真机可复现）**：今日页 → 解锁次数卡 → 右上「选择日期」→ 选「近 7 天」→ 页面没有任何变化，也没有提示。

**根因**：`DateSheetMode.FULL` 给详情页保留了完整集合（CR-14），但 `loadAll` 取详情数据时用的是 `todayEffective`（区间回落今天）：

```kotlin
// AppViewModel.kt:938-939
val todayEffective = if (todaySel is DateSelection.Day) todaySel else DateSelection.today()
// AppViewModel.kt:1047-1048
repository.metricDetail(MetricKind.NOTIFICATION, todayEffective)
repository.metricDetail(MetricKind.UNLOCK, todayEffective)
```

写回也照常发生（写进 `todaySelection`），所以「状态变了、数据没变、提示为空」，三者拼起来就是一个**没有反馈的控件**。

**探针实证**（3 天数据：今天 1 次解锁、昨天 2 次、前天 1 次；进入解锁详情后选近 7 天）：

```
before:  unlock.selection = Day(2026-11-14)   total = 1
applySelection(lastDays(7)) →
after :  todaySelection = Range(2026-11-08..2026-11-14)
         unlock.selection = Day(2026-11-14)   total = 1   ← 未变
         notice = null                                     ← 也没有提示
```

近 7 天真实是 4 次，页面仍显示 1 次。

**注意**：这不是「实现偏离文档」，而是**文档自身矛盾**——§5.6 一边规定「详情页显示单日 / 区间时锚定今天」，一边（CR-14）让详情页弹层保留「近 7 天 / 近 30 天 / 本月 / 自定义区间」。按现文档逐条执行，结果必然是现在这样。需要按 R9-7 的方式登记一条裁决（见 §4）。

**另外**：`MetricDetailPage` 本身**支持**区间（`val single = detail.selection is DateSelection.Day` 分支齐全、表格/图表都按 selection 渲染），也就是说能力已经在了，只是没喂进去。

---

##### P1-2　时段日均的两处接线零断言（360 测全绿仍可退化）

**做了什么**：把两个调用点都改回累计值（正是 A1 要修的那个 bug）：

```diff
- periods = UsageCalculator.dailyAveragePeriods(UsageCalculator.periodBuckets(statsHours), statsSummary.recordedDays)
+ periods = UsageCalculator.periodBuckets(statsHours)              // AppViewModel
- val periods = UsageCalculator.dailyAveragePeriods(periodBuckets(hours), summary.recordedDays)
+ val periods = periodBuckets(hours)                                // PeriodSummaryCalculator
```

**结果**：`--rerun-tasks` → **BUILD SUCCESSFUL，360 tests / 0 fail**。

也就是说：真机反馈的那个「06–12 显示 7 小时 25 分」如果哪天被改回来，现有测试**一条都不会红**。`DailyAveragePeriodsTest` 的 5 条只覆盖纯函数本身，没有任何一条断言「统计页 / 周期页的 `periods` 是日均」。提交信息里的「5 测变异验证」对这个退化模式无效。

**顺带一条恒真断言**（同文件，`四段日均不超过各自时钟跨度`）：

```kotlin
val caps = listOf(360, 360, 240, 480)
val cumulative = caps.map { it * 7 }
assertEquals(caps, dailyAveragePeriods(cumulative, 7))
assertEquals(true, dailyAveragePeriods(cumulative, 7).zip(caps).all { (a, c) -> a <= c })
```

输入就是 `caps × 7`，输出必然等于 `caps`，第二条断言只是在复述第一条。它证明不了「真机数据下不超时钟跨度」（真正的危险场景是 `hour_usage` 里有记录日多于 `day_summary` 时，分母偏小、日均被放大——见 P2-2）。

**建议补的测试**（各 1 条，成本很低）：
- `AppViewModel`：入库 7 天、`hour_usage` 每天同一小时 +N 分钟 → 断言 `statsState.data!!.periods` ＝ 日均而非累计（变异：去掉除法则红）。
- `PeriodSummaryCalculator`：断言 `summary.periods.sum() == 日均合计`，不是区间累计。
- 把恒真断言换成「按 `recordedDays` 归一后 ≤ 时钟跨度」的构造：给一个**不可能**的输入（如 06–12 段累计 445×7 但 `recordedDays=3`），断言结果不会被静默截断（要么承认会超跨度、要么加显式钳制并写明理由）。

---

#### 4. 需要裁决（一条）

| 议题 | 现文档 | 现状 | 选项 |
|---|---|---|---|
| 详情页日期弹层是否保留区间 | §5.6「详情页显示单日，区间回落今天」／CR-14「详情页弹层保持完整集合」 | 保留区间但选了没用（P1-1） | **① 弹层收窄为 DAY 模式**（今日页同款：今天/昨天/指定单日），改动 3 行、与 §5.6 一致、零歧义；**② 详情页支持区间**（把 `metricDetail` 改喂 `todaySelection`），但会撞 §7-16「卡与详情同源」——卡上永远是单日数字，详情变区间合计，两处对不上。**建议 ①**，同时把 CR-14 里「排行/解锁详情/通知详情保持完整集合」改成「排行保持完整集合，两个详情页与今日页一致」。 |

---

#### 5. P2（五条，不阻塞但建议本批或下一批处理）

##### P2-1　统计页仍用整页 `Crossfade`，与今日页刚推翻的口径冲突
`StatsScreen.kt:75` `Crossfade(targetState = state.data, tween(200))`，而 `MetricBarChart.kt:115` 是 `remember(points) { Animatable(0f) }`——切 7→30 天时：出栈分支还画着旧柱、入栈分支柱子从 0 重新长，两帧半透明交叠。**这与负责人在今日页反馈「闪一帧」的成因是同一个**，只是发生在统计页。今日页的注释已经写明「整页淡变会重建内容分支」，统计页没跟着改。
**建议**：去掉 Crossfade（柱图生长 + 日均数字已足够表达「换了区间」），或至少 `--date-switch` 抽帧复核统计页再决定。

##### P2-2　日均分母与小时表可能不同源（降级通道混用场景）
`dailyAveragePeriods` 的分母是 `Summary.recordedDays`（来自 `day_summary`），分子是 `hour_usage` 的 SUM。文档明确「不另按 `hour_usage` 有无单独计数」，所以口径是合规的——但**如果某天只有 `hour_usage` 没有 `day_summary`**（降级通道 + Shizuku 混用、或历史迁移残留），分子会含这一天、分母不含，日均被放大，四段之和可超 6/6/4/8 小时。建议在 `IngestSanity` 或读取层保证「有 hour 行必有 day 行」，或在 `dailyAveragePeriods` 加一次性钳制并写明理由。

##### P2-3　日历 tab 的副标题会回落成「今天」
`AppNav.kt:361-370`：日历副标题取 `calendarState.data`，取不到时走 `selectionSubtitle(headerSelection)`，而 `headerSelection` 对日历页是 `todaySelection`。于是**无数据 / 加载中**时，一个「不参与日期筛选」的页面上会出现「今天 · 11月14日」。建议日历分支单独给文案（`"已记录 0 天"` 或空）。

##### P2-4　`catch (e: Exception)` 会吞掉 `CancellationException`
`AppViewModel.loadAll` / `loadPeriod` 的 `try` 里全是可取消的 Room suspend 查询，而 `catch (e: Exception)` 把 `CancellationException` 一并接住，然后**把六个页面状态写成 error**。快速连点日期/连续切换区间时，被取消的那次加载可能在新的加载之后落笔，把 error 标志留在状态里（`data` 非空时不可见，首次加载被取消时就会看到整页「数据加载失败: StandaloneCoroutine was cancelled」）。
**修法**：`catch (e: CancellationException) { throw e } catch (e: Exception) { ... }`。

##### P2-5　`applySelection` 里存在「自己取消自己」
`applySelection` 把协程赋给 `loadJob`，协程体里又调用 `loadAll()`，后者第一行 `loadJob?.cancel()` 取消的就是**当前正在跑的协程**。现在能工作，只是因为 `loadAll()` 之后没有挂起点（挂起点会抛 `CancellationException`）。这是随时会被下一次改动踩爆的结构，建议 `loadAll` 增加 `cancelPrevious: Boolean` 参数，或把「写入状态 + 触发重算」拆成两步。

---

#### 6. P3（四条，登记即可）

1. **`RollingNumberText` 逐字 Text 的读屏副作用**：每个字符一个 `AnimatedContent`→一个 `Text`，`MetricCardPair` 没有 `clearAndSetSemantics`，TalkBack 可能把「87」读成两段（圆环已用 `clearAndSetSemantics` 规避）。建议在 `RollingNumberText` 内部加一层 `clearAndSetSemantics { contentDescription = text }`，再在真机上听一遍。
2. **`DateInput.error` 的「格式错」分支不可达**：字段文本永远是 `normalizeInput` 出来的数字流，`SHAPE` 那条 `按 年-月-日 输入` 永远不会触发（测试用 `error("abc") == ""` 把这个现状固化下来了）。要么删分支，要么保留但注释「防御性」。
3. **`normalizeInput(raw, cursor)` 的 `cursor` 参数未使用**（永远置末尾），签名会误导调用方。
4. **`SyncManager.uploadManual/downloadManual` 直接引用 `WebDavProvider.MANUAL_FILE`**：`provider` 已是抽象，但手动文件名绑在 WebDAV 常量上；下一步接 Google Drive（8b-2 余项）时容易被复制成「两个 provider 抢同一个远端名」。另外自动备份文件名精确到分钟，同一分钟两次上传互相覆盖（批 C 加 `deviceId` 会顺带解决）。

---

#### 7. 建议修复顺序

1. **P1-1**：按裁决结果改（① 收窄详情页弹层为 DAY，改 `dateSheetModeFor` 一处 + 同步 CR-14 措辞；② 或改 `metricDetail` 数据源并接受 §7-16 需要同步修订）。
2. **P1-2**：补 2 条接线测试 + 替换恒真断言；顺便把 P2-2 的口径写进测试注释。
3. **P2-1**：去掉统计页整页 `Crossfade`，用 `--date-switch` 复录一次统计页。
4. **P2-3 / P2-4 / P2-5**：三处都是十几行以内的小改，一起做。
5. P3 四条随下一批顺手。

---

#### 8. 复现命令

```bash
cd E:/project/ScreenTrace
JAVA_HOME="C:/Users/Administrator/.jdks/jbr-21.0.11" ./gradlew.bat :app:testDebugUnitTest --rerun-tasks --console=plain
# 统计
python - <<'EOF'
import glob, xml.etree.ElementTree as ET
t=f=0
for x in glob.glob('app/build/test-results/testDebugUnitTest/*.xml'):
    r=ET.parse(x).getroot(); t+=int(r.get('tests')); f+=int(r.get('failures'))+int(r.get('errors'))
print('TOTAL',t,'FAIL',f)
EOF
```

P1-1 的探针与 P1-2 的变异都是临时改动，复现后已 `git checkout --` 还原，工作区保持干净。

---

## 【补记】CR-15 评审裁决与落地结果（2026-11-14 13:45）

> 本节由评审人补记，不属于上节原文；记录 P1-1 的裁决与落地证据，便于后续追溯。

- **P1-1 裁决：方案 ①**（负责人 2026-11-14 13:33 选定）——两个指标详情页不再能选区间。落地为 **CR-16**：`dateSheetModeFor(page)` 改按 `AppPage.mainOf(page)`（即 `returnTo`）判模式，与 `dateScopeFor` 的写回判据同源；归属今日 → DAY、归属统计 → RANGE，`FULL` 只剩传 `null` 的兜底。`AppNav` 三处二级页顶栏不再硬写 `DateSheetMode.FULL`。
- **落地后果复核**：P1-1 描述的「选了没反应、还偷偷改掉一份隐藏状态」入口已消失（今日页侧只剩单日选项）；同时反向漏洞（从统计页进详情点「今天」把单日写进 `statsSelection`，统计页趋势只剩一根柱）一并关闭。
- **测试**：`DateSheetModeTest` 3 → 5 测（新增「二级页跟随 returnTo」「弹层模式与写回归属同源」）；变异两处——还原成按页判断红 2 例、统计页误回 DAY 红 3 例；**362 测全绿**。真机三路复核：解锁详情、排行（从今日进）只剩单日集合；统计页仍为区间集合。
- **口径更正**：本节 P1-1 原文的结论（静默无效）与实测一致；但评审当日口述给的「再进详情会变成 7 天」是错误的，`metricDetail` 恒喂 `todayEffective`，任何时候都不会显示区间。需求文档 CR-16 条目已按实测改正。
- **新增待裁决**：排行页按 §5.7 排行-2 恒取锚点单日，从统计侧进排行选「近 30 天」只改锚点结束日（默认已是今天）→ 仍表现为无变化。属排行口径问题，不在 CR-16 范围。
- P1-2 / P2-1 / P2-2 / P2-3 / P2-4 / P2-5 / P3 四条**尚未处理**；~~仍挂在 `docs/TODO.md`~~ **（13:53 复核更正：当时一条都未登记，原表述与事实不符）**。已按负责人裁定补登 `docs/TODO.md` 的「CR-15 评审遗留」章节，修复结果见文末后续补记。

---

## 【补记】CR-15 评审遗留补登与修复（2026-11-14 14:25）

> 负责人 13:53 裁定「先补评审遗留」。本节记录 P1-2 / P2-1~5 的落地证据；P3 四条与两条待裁决**只登记、未改**。
> 本节为新补记，不改写上文评审原文。

- **补登与更正**：`docs/TODO.md` 新增「CR-15 评审遗留」章节共 12 条（P1-2 / P2-1~5 / P3 四条 / 排行口径待裁决 / 统计侧详情页残留）；修正 13:45 补记末句「仍挂在 `docs/TODO.md`」的不实表述（当时一条都没登记）；需求文档 §5.7 第 7 条补写 P2-2 钳制口径。
- **P1-2（验收物）**：新增 `StatsPeriodsWiringTest`（真 Room 入库 7 天 → 真 `AppViewModel.loadAll` → 断言 `statsState.periods[0] == 60`，若接线退回累计值则为 420）与 `PeriodPeriodsWiringTest`（直接打靶 `PeriodSummaryCalculator.build` 的 `period.periods[0] == 60`）。`DailyAveragePeriodsTest` 的恒真断言换成评审给的危险构造（7 天 hour 表 / 3 天 day 表），并补「恰好达上限不被误伤」用例。**四处变异**（两处接线退回累计值 / 去钳制 / 去除法）8 项中 **5 项变红**。
- **P2-1**：`StatsScreen` 去掉整页 `Crossfade`（`ScrollablePage` 内层 Column 本就是 12dp 间距，多根节点不需要额外包 Column）；**额外收口** `MetricBarChart` 的 `remember(points, reduceMotion) { Animatable(0f) }` → `remember { Animatable(...) }`，消除切区间时柱图从 0 重播（评审只建议「去掉 Crossfade」后仍残留的同源问题）。`scripts/capture_anim.py` 新增 `--switch-tab` / `--switch-label`，两次录屏抽帧对照 `p21_stats_switch` vs `p21_stats_noreplay`：修复前中间帧柱图几乎空白，修复后柱图全程完整、无半透明交叠、四张卡始终独立。
- **P2-2**：`dailyAveragePeriods` 按四段 6/6/4/8 小时上限显式钳制并写明理由（分母 day_summary、分子 hour_usage 不同源时防荒谬读数）。
- **P2-3**：日历 tab 副标题抽成纯函数 `calendarSubtitle(recordedDays)`，加载中 / 无数据显示「全量历史」而非回落「今天」；`CalendarSubtitleTest` 2 测，变异（兜底改回「今天」）1 例变红。
- **P2-4 / P2-5**：`loadAll` / `loadPeriod` / `loadMonthLevels` 三处 `catch` 前置 `CancellationException → throw e`；`loadAll(cancelPrevious: Boolean = true)`，`applySelection` 传 `false`，消除「自己取消自己」。
- **验证**：`--rerun-tasks` 后 **367 测全绿 / 0 失败**；Redmi 真机（vivo 另有测试未动）——统计页切「近 30 天」副标题 `10月16日 – 11月14日`、日历 tab 副标题「已记录 5 天」且顶栏无日期按钮、crash 缓冲区 0 条 FATAL。APK `a8d33320`。
- **仍未处理**：P3 四条、排行页口径待裁决、统计侧详情页「选区间当前页不变」残留，均已在 `docs/TODO.md` 登记。

---

## 【补记】CR-17 三条真机改进落地证据（2026-11-14 15:10）

> 负责人 14:28 提出三条改进、14:30 明确「vivo 在跑自动化，禁止使用」；三项方案均按推荐裁定，真机全程只用 Redmi `GAQOHMNFQWFUPBQO`。
> 本节记录落地证据，不改写上文任何评审原文。

- **文档先行** `79b4041`：需求文档头部登记 CR-17；§5.5 补「提示分流」与「统计页切区间过渡」条款、§5.2 补「Pager 与底部导航同步」条款；TODO 排期三条。
- **① 提示分流**（提交见下）：`AppViewModel.showNotice` 按 `isTransientNotice` 白名单把纯确认类（`已导出…` / `已恢复…` / `云端配置已保存` / `连接成功` / `已上传…` / `刚刚已更新`）写进带 `seq` 的短时通道，其余默认常驻；`AppNav` 在 AppHeader 下方 108dp 最上层覆盖层加 2.5s 自绘短时条（复用 `NoticeBar` 胶囊样式、可提前关闭），`LaunchedEffect(seq)` 重启计时；5 处 `android.widget.Toast` 统一改走 `onNotice`。**位置取上方**：底部 50dp 悬浮导航胶囊 + 手势条 + 112dp 预留已占满，系统 Toast 默认落点必重叠。新增 7 测（白名单 + 双通道分流），变异「全部走常驻」3 例变红。
- **② tab 跟手**（提交见下）：点 tab 只 `animateScrollToPage`、不再直接写路由；`snapshotFlow { currentPage }.distinctUntilChanged()` 单向回写取代落定才发值的 `settledPage`，`selectMain` 幂等去重，根治「滞后 settle 覆盖更晚点击」；`currentPage + currentPageOffsetFraction` 以 `State<Float>` 传给 `FreshNavBar`，`navProximity` 按距离连续插值（跟手阶段不叠 spring 二次延迟，进度只在导航条内部读取避免整树重组）。新增 7 测，变异「导航条离散化 / 去掉去重」3 例变红。
- **③ 统计页叶子节点过渡**（本批提交）：`MetricValueText` 的 `rolling` 对时长型改在同一 `Text` 内 `animateIntAsState` 平滑（§5.3 禁止拆数字与单位），统计页日均大数字与最久/最少双卡开启；`CapsuleBarChart` 入场生长改为只播一次、量程与每根柱高按新值平滑；`PeriodDistribution` 四段色带权重与图例数值平滑；洞察/平均线文案只包单个 `Text` 做 180ms 淡入，全程不套整页容器（A1-④ 闪帧教训）。减少动画全部直落终值。
- **验证**：`--rerun-tasks` 后 **381 测全绿 / 0 失败**；Redmi 真机（`vivo` 未动）：① 云同步「保存配置」→ 顶部出现「云端配置已保存」、3.2s 后消失；选无数据的 11/1 → 「该时段尚无数据，请先拉取」4s 后仍在（常驻）；② `--swipe` 录屏抽帧看到两页横向并排的中间帧与导航高亮连续渐变，快速连点 6 轮每轮最终「今日」标题 + 「应用构成」内容一致、无错位；③ 统计页切近 30 天抽帧看到 7 根粗柱平滑过渡到 30 根细柱、无空白帧/半透明/重影；crash 缓冲区 0 条 FATAL。APK `c6ecc22e`。
- **观察**：真机只有 5 天有记录，近 7 天与近 30 天的日均/时段数值恰好相同，所以数字与色带的平滑在这组数据上看不出中间态（柱图点数变化可见）；逻辑已接线并由纯函数/代码审查保证。
- **仍未处理**：P3 四条、排行页口径待裁决、统计侧详情页残留，以及日期弹层标题与状态栏略重叠的观察项，均在 `docs/TODO.md` 登记。

---

## 【补记】CR-15 评审遗留 P3-4 闭环与批 C 落地证据（2026-11-14 16:55）

> 本节由评审人补记，不改写上文任何评审原文；记录 P3-4 的解决与批 C 的落地 / 真机证据。

- **P3-4 闭环**：`SyncManager` 原先直接引用 `WebDavProvider.MANUAL_FILE` / `AUTO_PREFIX`，接第二个 provider 时会抢同一远端名；批 C 新增 `SyncPaths` 作为所有 provider 共用的路径与命名唯一口径（`manualPath` / `autoPath` / `isAuto` / `latestFile`），`SyncManager` 不再引用任何 WebDAV 常量，自动备份文件名带设备短 ID，同分钟不同设备不再互相覆盖。`docs/TODO.md` 的 P3-4 已勾选。
- **三项裁定（负责人 15:40 均按推荐）**：按排期推批 C；交付边界＝数据层 + 云同步页最小设备卡片（顶栏四宫格留批 D）；根目录旧备份原地保留、归「未知设备」、不迁移不删除。需求文档头部登记，§4 / §5.10 / §6 / §7-13 同步（`eb27e8e`，代码零改动）。
- **落地三笔**：`9d1a5ee`（schema v5 + deviceId + `devices/<id>/` 分目录 + 回落读旧固定文件 + 只读 `inspectDevice`，399 测）、`6338f90`（云同步页设备卡片，403 测）、真机复核修复笔（404 测）。
- **真机新发现（不属原评审范围）**：坚果云 PROPFIND 返回 `<d:response>` / `<d:href>` / `<d:collection>` 带命名空间前缀，而 `parsePropfindNames` 在 `FEATURE_PROCESS_NAMESPACES=false` 下只比对裸标签名，导致真机上**一条文件都解析不到**——设备列表永远空、根目录旧备份归不了「未知设备」，且批 8b 自动备份的「仅保留 5 份」清理一直空转。此前只有无前缀单测覆盖，属典型假绿。修复为取 `substringAfterLast(':')` 的本地名，补带 `d:` 前缀回归用例；变异（退回只认裸标签）该用例变红。
- **验证**：数据层变异 9 处（M3 首版未真正改坏实现，换成「deviceId 不落盘」后 4 例红）、UI 层 4 处、命名空间 1 处，共 **14 处全部变红**；`--rerun-tasks` **404 测全绿 / 0 skip**。Redmi `GAQOHMNFQWFUPBQO` 真机（vivo 在跑自动化，全程禁用）：本机 1 份 / 未知设备（旧备份）2 份、旧备份摘要 `v4 · 3 天`、本机摘要 `v5 · 5 天 · 来源 MyRedmi`、昵称改名落盘并刷新生效、只读查看不弹恢复流程、crash 缓冲区 0 条 FATAL；APK `6962b183`，截图 `C:/tmp/cr_c/final.png`。
- **仍未处理**：P3-1（`RollingNumberText` 读屏）、P3-2（`DateInput.error` 格式错分支不可达）、P3-3（`normalizeInput` 的 cursor 未使用）、排行页口径待裁决、统计侧详情页残留、日期弹层标题与状态栏略重叠。

---

## 【补记】CR-15 ⑤ 批 E 自定义壁纸落地证据（2026-11-14 17:50）

> 本节由评审人补记，不改写上文任何评审原文；记录批 E 的裁定、落地与真机证据。

- **三项裁定（负责人 16:59 均按推荐）**：入口＝设置页「外观」组内卡片（不新增页面 / 不并入 `StyleSheet` / 不动顶栏）；暗化 0–60% + 模糊 0–16dp 两条滑杆，API < 31 禁用并提示；遮罩只存一份参数，浅/深主题自动叠底色并按壁纸亮度微调，状态栏仅启用壁纸时按遮罩后实际亮度重判。
- **落地两笔**：`d291c44`（需求 §5.4 新增「自定义壁纸」小节 + §6 工程红线，代码零改动）；`62f4682`（`WallpaperTuning` 纯函数 / `WallpaperStore` / `SettingsStore` 持久化 / `LocalWallpaper` 单份位图 / `PageScaffold` 三层绘制 / 设置页卡片，418 测）。
- **真机发现**：暗化 58% 时有效亮度恰好落在 128 阈值附近，深色状态栏图标压在变暗的壁纸上对比度不足；阈值改为 140（更保守，背景不够亮就切浅色图标），补回归用例，变异（退回 128）该用例变红。另修复「选图取消判断在取 `wallpaperStore` 之后」导致的覆盖盲点，把 `uri == null` 提前。
- **验证**：变异验证 **11 处全部变红**（纯函数 6 / 存储 1 / ViewModel 3 / 状态栏阈值 1）；`--rerun-tasks` **418 测全绿 / 0 skip**。Redmi `GAQOHMNFQWFUPBQO` 真机（vivo 在跑自动化，全程禁用）APK `6e1321ca`：Photo Picker 选图后主页面与二级页均显示壁纸、暗化 58% / 模糊 5dp 生效、切深色主题遮罩与状态栏自动跟随、恢复默认后参数复位且 `run-as` 确认 `no_backup/wallpaper/` 为空、crash 缓冲区 0 条 FATAL。截图 `C:/tmp/cr_e/{final_home,dark_theme,restored}.png`。
- **边界复核**：壁纸图片与参数均不进 `BackupSettings` / 备份 JSON；`Modifier.blur` 在 API < 31 由 `effectiveBlurDp` 显式归零并在 UI 禁用滑杆；恢复默认不残留私有文件。

---

## 【补记】CR-15 ③ 批 D 四宫格顶栏落地证据（2026-11-14 20:40）

> 本节由评审人补记，不改写上文任何评审原文；记录批 D 的裁定、落地与真机证据。

- **三项裁定（负责人 20:05 均按推荐）**：① 下拉面板四枚圆按钮一行横排右对齐（日历 tab 三枚不缺角）；②「切换设备」＝下拉内轻量设备列表面板（接批 C 只读 API、内联展开摘要、可返回四宫格，不跳云同步页）；③ 点面板「设置」进设置页时容器变换起点重登记为被点那枚圆按钮。
- **落地两笔**：`187aa47`（需求 §5.2 / §5.5 / §5.6 / §6 / §7-36 口径，代码零改动）；`5dc3291`（`AppHeader` 带默认值菜单参数 / `quickMenuActionsFor` + `quickMenuTransitionFor` 纯函数 / `QuickMenuOverlay` 最上层浮层 / `QuickMenuPanel` 一行四枚 / `DeviceQuickMenuPanel` 设备第二层 / `deviceBackupSummary` 统一只读文案，428 测）。**放弃 CR-10 三按钮终态**；`StyleSheet` / `DateSheet` 零改动；二级页与 `@Preview` 调用点零改动。
- **起点重登记**：`QuickMenuOverlay` 下发 `LocalContainerTransformState`，四枚按钮复用带 `transformSource` 的 `HeaderActionButton`。`capture_anim.py` 新增 `--quick-menu`（预展开 → 定位 → 收起 → 录屏重放两步点击），`--slow` 抽帧确认 `enter.png` 首帧圆角底板 + 齿轮出现在面板「设置」按钮 (498,465) 附近而非顶栏右上 (1077,263)，`exit.png` 缩回同处；齿轮保持圆形不拉长、无闪白叠影。产物 `C:/tmp/screentrace-capture/cr_d_quick_menu/`。
- **验证**：新增 10 测（QuickMenuActions 6 / DeviceSummary 4）；变异 **9 处全部变红**（D8 首轮脚本锚点把带闭合引号的片段当子串，修正后单跑变红）；`--rerun-tasks` **428 测全绿 / 0 skip**。Redmi `GAQOHMNFQWFUPBQO` 真机（vivo 全程禁用）APK `61aaaaef`：四枚 / 日历三枚、主题与日期弹层复用、设备面板「MyRedmi · 本机 1 份 / 未知设备（旧备份）2 份」且摘要 `v4 · 3 天`、返回键收起面板、快速连点 5 次 tab 无错位、crash 缓冲区 0 条 FATAL。
- **仍未处理**：P3 三条、排行页口径待裁决、统计侧详情页残留、日期弹层标题与状态栏略重叠。本地提交因环境权限规则拒绝读 `~/.ssh/` 未推送，由负责人手动 `git push origin main`。


---

## 【补记】P3 三条落地证据（2026-11-15）

> 本节由评审人补记，不改写上文任何评审原文；记录 P3-3 / P3-2 / P3-1 的落地与真机证据。
> 三条均按负责人采纳的建议方案 A（`docs/TODO.md` 2026-11-15 01:50 版）。

- **P3-3 `normalizeInput` 删未用 cursor 参数（`c0cf556`）**：签名改 `normalizeInput(raw): Pair<String, Int>`，
  DateSheet 2 处调用去掉第二参，DateInputTest 单参调用零改动；KDoc 保留「光标一律置末尾（自动补 0 是末尾插入）」
  并注明删参理由；编译 warning 消除。纯删参无行为变化，既有测试钉住行为，无需新增用例。
- **P3-2 `DateInput.error` 格式错分支补真覆盖（`d64a4a5`，代码不动）**：补 2 条纯函数用例——
  `error("2026/11/11")` 与 `error("2026-11-11-11")` → 「按 年-月-日 输入」格式提示。
  **事实修正**：建议方案里的原例 `error("2026-1-1-1")` 实测只有 7 位数字、进不了 ≥8 分支（返回 ""，
  属输入中间态），换成同样「多余分段」但满 8 位的 4 段形状；该实测结论一并钉进用例。
  变异（格式错分支改恒走「不存在」）该用例变红。
- **P3-1 读屏合并语义（`eb381b0` + 真机收口 `035235c`）**：
  - 抽 `metricCardDescription()` 纯函数：「标签 + 当前值 + 说明 + 查看详情」一句话，修掉原默认
    `"$label 查看详情"` 缺当前值（与 §5.2 不符）；时长与页面同源（formatDuration）。新增 3 测，
    变异（去掉值拼接）3 例全红。
  - `MetricCard` 可下钻分支改 `clearAndSetSemantics` 整卡一句话；`RollingNumberText` 补
    `semantics(mergeDescendants = true)`；TodayScreen 可用态走默认描述（带「已查看 X 次」），
    无事件源保留「暂无事件」自定义（不念破折号）。
  - **真机发现并修复**：`clearAndSetSemantics` 连同节点上的 clickable 语义一起清掉——
    Redmi uiautomator dump 实测 `clickable=false`（TalkBack 无法激活卡片），在语义块里补回
    `onClick(label = "查看详情")` 后恢复 `clickable=true`。
  - Redmi `GAQOHMNFQWFUPBQO` dump 复核（vivo 全程未动）：双卡各为**单节点**且 content-desc 为
    整句（「通知打扰 1，已查看 0 次，查看详情」），卡内无逐位残留数字节点；统计页大数字单节点
    「18 小时 23 分钟」；「87」拆读消除。
- **验证**：`--rerun-tasks` **432 测全绿 / 0 skip**（428 + P3-2 1 例 + P3-1 3 例）；变异验证 P3-2 一处、
  P3-1 一处均变红并还原。
- **仍未处理**：排行页口径待裁决、统计侧详情页「选区间当前页不变」待裁决、日期弹层标题与状态栏略重叠。


---

## 【补记】CR-18 落地证据（2026-11-15）

> 本节由评审人补记，不改写上文任何评审原文；记录 CR-16 两条遗留的负责人裁决与落地。
> 证据 dump 全套在 `C:/tmp/cr18/`。

- **两条裁定（负责人 2026-11-15 均按推荐）**：① 排行弹层只给单日（否决「排行改独立单日状态」）；② 详情页跟随归属状态取数（否决「维持现状登记限制」）。需求头部 CR-18 登记 + §5.6 / §7-26 / CR-16 残留句同步（`338bef1`，代码零改动）。
- **实现（`787753d`，437 测全绿）**：`dateSheetModeFor` 排行例外恒 DAY（CR-16「弹层量纲=写回状态量纲」的唯一有意例外，测试已注明）；`AppViewModel` 详情取数按 `dateScopeFor(returnTo)` 取窗（今日→`todayEffective` 与双卡同源、统计→`statsSelection` 原窗口）、`open*Detail` 带 `loadAll` 打开即重算、排行上显式选日清 `rankingScope` 退出周期窗口。新增 `AppViewModelCr18Test` 4 例（真 Room 端到端，断言 `MetricDetail.selection` / 排行 `summary.selection`）+ `DateSheetModeTest` 改写 2 例并新增排行专测；三处变异（详情窗口写死今日 / 打开不重算 / 选日不清周期窗口）对应 3 例变红后还原。
- **Redmi 真机复核（`GAQOHMNFQWFUPBQO`，vivo 全程未动）四步全过**：
  1. 今日 → 查看全部应用 → 排行 → 日期弹层**只有「今天 / 昨天 / 指定单日」**；选「昨天」排行立即切 11月14日（累计采集 23 小时 59 分钟 / 共 9 个应用）。
  2. 统计 → 本周总结 → 查看全部 → **周期口径排行**（副标题 11月9日 – 11月15日 / 累计采集 110 小时 37 分钟）→ 弹层同样只给单日三档 → 选「昨天」**退出周期窗口**、切 11月14日单日。
  3. 统计页弹层仍为 RANGE 五项（近 7 天 / 近 30 天 / 本周 / 本月 / 自定义区间）回归通过。
  4. 今日 → 解锁卡 → 解锁详情弹层三档 → 选「昨天」页面**立即变化**（11月15日 → 11月14日）。
  crash 缓冲区 0 条 FATAL。注意：批 D 后主页面日期入口在「打开快捷菜单」下拉四宫格内，二级页顶栏直出（驱动脚本按此导航）。
- **仍未处理**：日期弹层标题与状态栏略重叠（CR-15 评审旧观察，未立项）。

---

## 【补记】CR-19 顶栏竖排 + 壁纸可见性/裁切落地证据（2026-11-16 08:50）

> 本节由评审人补记，不改写上文任何评审原文。

- **三项裁定（负责人 2026-11-16 08:15）**：① 下拉面板一行横排 → **纵向一列**（反馈「现在是横着的」）+ **展开过渡**（反馈纯淡入 120ms「看不到动画」）；② 壁纸模式全 App surface 类卡片半透明（浅 92% / 深 88%）+ 遮罩自动加深（反馈「壁纸卡片不透明，壁纸快被挡完」）；③ 壁纸**全屏裁切编辑**（拖动平移 + 双指捏合，参数归一化落 DataStore，不进备份）。
- **落地两笔**：`9a802ad`（文档先行：§5.2 / §5.4 / §6 / §7-36，代码零改动）；`cff7cbd`（`QuickMenuPanel` 纵排 / `QuickMenuOverlay` 拆独立 `AnimatedVisibility` + `expandVertically(200ms)+fadeIn`，点击层独立无动画 / `WallpaperTuning.cardAlpha + translucentCardBoost + themeScrimAlpha(cardTranslucent)` / `ScreenTimeTheme` 壁纸模式降 surface 类卡片 alpha / `WallpaperCropMath` 纯函数（盖满抬升、平移钳制、围绕焦点缩放 `t' = f − (f − t)·z'/z`）/ `PageScaffold` 改 Fit + graphicsLayer（旧数据自动抬到盖满，观感与原 Crop 一致）/ `WallpaperEditorScreen` 全屏浮层 / 设置页壁纸卡片缩略预览 + 「调整裁切」入口 / `AppViewModel` 挂起 `setWallpaperCrop` / `resetWallpaperCrop`）。
- **验证**：新增 9 测（`WallpaperCropTest` 8 / `AppViewModelWallpaperTest` 裁切落盘 1）；**9 处变异全部变红**（遮罩补偿失效 / 卡片 alpha 对调 / 缩放与偏移夹取放开 / zoom 盖满下限失效 / 平移钳制失效 / 焦点公式比例写反 / minZoom 取 min / 裁切不落盘）；`--rerun-tasks` **445 测全绿 / 0 skip**。
- **真机**（Redmi `GAQOHMNFQWFUPBQO`，vivo 全程禁用，APK `4f5dfbbb`）：下拉四枚纵向一列右对齐（y=387/569/751/933）；`capture_anim --quick-menu --slow` 抽帧确认面板**从上往下纵向展开**、容器变换从面板按钮放大（`C:/tmp/screentrace-capture/cr19_menu_anim/enter.png`）；设置页壁纸卡片显示缩略预览与「调整裁切」；编辑浮层全屏 Fit 预览、单指拖动生效、保存后**重启壁纸偏移持久**；半透明卡片透出壁纸且文字清晰；crash 缓冲区 0 条 FATAL。捏合缩放由 8 例几何单测覆盖（adb 无法模拟双指手势）。
- **边界**：裁切参数按相对屏幕宽高归一化存储、不进备份 JSON；无壁纸时卡片透明度与遮罩完全不变；`PageScaffold` 对 `cropScale=1` 的旧数据自动抬升到盖满，升级观感无跳变。

---

## 【补记】CR-20 顶栏拆回四枚独立圆按钮 + 壁纸卡片配色落地证据（2026-11-16 09:40）

> 本节由评审人补记，不改写上文任何评审原文。

- **两条裁定（负责人 2026-11-16 09:20）**：① 顶栏四枚动作**拆回独立圆按钮**（推翻 CR-15 ③ 批 D 与 CR-19 ① 的「单枚设置按钮 + 下拉面板」合并形态，恢复改版前的顶栏直出位置，保留四枚：切换设备 / 日期 / 主题 / 设置，日历 tab 三枚）；② 壁纸**卡片色跟随壁纸主色** + **透明度大幅下调**（浅 60% / 深 55%），**未启用壁纸时完全保持原样**；强调色是否跟随壁纸做**两套方案**（仅卡片 / 整套主题）供真机对比。
- **落地两笔**：`71dcd4d`（文档先行：需求头部 CR-20 登记 + §5.2 / §5.4 / §5.5 / §5.8 / §6 / §7-36，代码零改动）；实现笔（`HeaderAction` / `HeaderActions` / `DevicePanel` 重构 + `WallpaperPalette` 新模块 + `WallpaperTuning.dominantSeedOf` + `paletteFor` 入口 + `panelSurface` 浮层 token）。
- **关键实现取舍**：
  - `HeaderAction` 枚举**自带 `target`**（DEVICE_PANEL / DATE_SHEET / STYLE_SHEET / SETTINGS_PAGE），让「不设无动作的假按钮」这条红线在**编译期**成立——`AppNav` 穷举接线，少接一个分支就编译不过。
  - 「切换设备」按钮 `registerTransform = false`：它开的是浮层不是容器变换页面，不该把自己记成转场起点（批 D 的「面板按钮起点重登记」随之作废）。
  - 壁纸主色提取用**同一份 32×32 采样**（与平均亮度共用），12 个色相桶按「像素数 × (1 + 平均饱和度)」取最高桶，近灰像素不参与分桶；**不新增第二套颜色算法**，派生仍走既有 `paletteFromSeed`。
  - 半透明卡片的文字对比度按**合成后的实际底色**（壁纸 → 暗化 → 遮罩 → 卡片叠加）校验，不足时把**卡片底色**推向安全端，而不是让文字去迁就。
- **真机复核发现并修复（CR-20 ③）**：首版把 `surface` 的 alpha 直接降到 0.60，**浮层跟着变透明**——设备面板底下的圆环数字「1:35」透上来与「MyRedmi · 本机」叠在一起。修复为新增语义 token `panelSurface`（不透明），底部弹层（日期 / 风格 / 取色器 / 滚轮）、设备面板、提示条改用它；页内卡片仍半透明。这是 CR-19 半透明口径在 60% 档位下才暴露的缺陷。
- **验证**：新增 18 测（HeaderActions 5 / WallpaperSeed 8 / WallpaperPalette 11，含改写旧 6 测）；**11 处变异全部变红**（M1 日历多给日期 / M2 两动作撞同入口 / M3 透明度退回 92% / M4 整套主题不跟随 / M5 去掉对比度兜底 / M6 主色只看像素数 / M10 不排除近灰 / M7 次要正文不兜底 / M8 没壁纸也走壁纸配色 / M9 按钮顺序乱 / M11 浮层跟着变透明）；`--rerun-tasks` **464 测全绿 / 0 skip**。
  - **变异脚本自身暴露的问题（两处，都已修正）**：① M6 首版「90 蓝 + 10 红」区分度不够——去掉饱和度加权后仍然是蓝赢，改用「50 低饱和蓝 + 40 纯红」才咬得住；② 新用例把低饱和蓝写成 `0x8090C0`（**alpha = 0**，会被当透明像素整片跳过，等于这个桶根本没参与），补 `FF` alpha 后才真正测到东西。
- **真机（Redmi `GAQOHMNFQWFUPBQO`，vivo 全程禁用，APK `795896d9`；全流程复核用同源构建 `7195dac5`，差异仅 debug 签名时间戳）**：顶栏四枚按钮直出（`uiautomator dump` 复核 content-desc 与 clickable 均正确，坐标 x = 609/765/921/1077）；日历 tab 三枚且**无「选择日期」**；设备面板浮层列出「MyRedmi · 本机 1 份 / 未知设备（旧备份）2 份」+ 只读声明，底色不透明无叠字；点顶栏「设置」走容器变换进设置页；壁纸卡片明显透出且带壁纸色调；「仅卡片 / 整套主题」两套观感切换对比明显（前者强调色仍是主题紫、后者主色/图表/圆环/导航全部转为壁纸色系）；**恢复默认后完全回到不透明卡片 + 主题色的原样**（`paletteFor(null)` 原样返回主题色板）。截图 `C:/tmp/cr20/`。
- **vivo 复核（`10CEB40M5G001DK`，1260×2800 / 560dpi / 雾蓝主题，2026-11-16 10:10）**：负责人手动在系统 Photo Picker 里选了一张粉红花卉壁纸。顶栏四枚按钮直出（content-desc 与 clickable 经 dump 复核，中心 x = 602/770/938/1106）；**日历 tab 三枚且无「选择日期」**；设备面板浮层正常弹出、底色不透明无叠字（vivo 上坚果云凭据已失效，面板诚实显示「读取失败：鉴权失败（401/403）」，**不是本次改动引入**）；壁纸卡片明显透出且带壁纸色调；「仅卡片 / 整套主题」两套切换生效（后者圆环、图表色带、导航胶囊、主题三色点全部转为壁纸粉红色系）；配色方案已切回出厂默认「仅卡片」，本次推入的测试图已从 `/sdcard/Pictures/` 删除并重新扫描媒体库；今日 crash 缓冲区 0 条 FATAL。截图 `C:/tmp/cr20vivo/`。
- **vivo 复核新发现（既有缺陷，非本次改动引入）**：**切 tab 后 Pager 偶发卡在两页之间**——从「日历」点「今日」（跨两页）后，标题与路由已是「今日」，但 Pager 滚动偏移停在两页中间（今日页只占屏幕左侧约 58%、统计页露在右侧、底部导航胶囊也停在两图标之间），再点一次「今日」即归位；同样路径第二次复跑正常，**属偶发**。根因在 CR-17 的 Pager 回写环路：点 tab 发起 `animateScrollToPage` → 动画中途 `snapshotFlow { currentPage }` 回写路由 → `LaunchedEffect(mainPage)` 再发起一次滚动 → 两次动画互相打断。**负责人 2026-11-16 裁定：本轮只记账不修**（保持 464 测全绿的干净基线），登记见 `docs/TODO.md`。
- **仍未处理**：① 日期弹层标题与状态栏略重叠（CR-15 评审旧观察，未立项）；② Pager 跨页跳转偶发卡两页之间（vivo 复核新发现，已记账本轮不修）。

---

## 【补记】CR-21 云备份三项改进 + 补记三处修正落地证据（2026-11-16 12:40）

> 本节由评审人补记，不改写上文任何评审原文。Redmi 真机复核证据在 `C:/tmp/cr21/`。

- **裁定（负责人 2026-11-16 10:25 + 问卷四项）**：① 设备列表显示**机型**而不是短 ID；② 换机可选择把旧机型数据**合并**进新机数据（旧数据单独保留，新数据里同时含有旧数据）；③「切换设备」要**真切换**（远端数据拉到本地、不同设备数据彼此独立、整个 App 四页跟随）。需求头部 CR-21 登记 + §4 / §5.2 / §5.10 / §6 / §7-43~45（`79e3e05`，代码零改动）。
- **落地三笔**：`79e3e05`（文档先行）→ `6d20de4`（**补记文档先行**：记账整集合口径 / 结果提示可见 / 累加边界，§7 增至 **46 条**）→ `7ba4c91`（实现 + 测试）。
- **关键实现取舍**：
  - **每台设备一个独立 Room 库文件**（本机采集库沿用 `screentrace.db` 承继历史；远端副本库 `screentrace-dev-<设备ID>.db`）+「当前库」指针落 DataStore；**拒绝给八张表加 `device_id` 列**（上百条 DAO 查询 + 迁移风险不可控）。
  - **两套合并口径必须并存且不复用**：§5.10 同设备恢复＝「同日取较大」（`BackupMerge`）；CR-21 跨设备＝「累加」（`CrossDeviceMerge` + `BackupRepository.mergeAccumulate`）。`screen_session` 走**区间并集**不累加（区间是时序表示），`app_limit` **完全不写**（限额是本机用户意图），事件按主键去重故两机计数自然累加。
  - **远端数据一律经 `BackupRepository.restore` 统一恢复入口**（8b 硬原则）：切换＝把远端备份 OVERWRITE 恢复到**它自己的副本库**；合并＝`mergeAccumulate` 写**本机采集库**；provider 与 SyncManager 绝不直写库。
  - **机型走 `device.json` 小文件**（`{ id, nickname, model }`，上传时幂等写）：列表**只对该文件存在的设备发一次小请求**，**不为拿机型批量下载整份备份**；老设备回落「设备 <短ID>」，摘要再从备份顶层 `device.model` 补出。
  - **合并写入目标恒为本机采集库**：远端设备自己的副本库与云端备份**不被改动**（这正是「旧数据单独保留」的那一半）；上传内容源恒为本机采集库。
- **真机复核发现并修复的三处问题（补记）**：
  1. **P0 · 防重复记账只记住最后一个日期**——记账编码成多条「来源设备|日期」条目后，`decodeLedger` 用 `.toMap()` 收尾，**同键条目互相覆盖**；来源备份有三天时，第二天里的其余两天被当成「新日期」再并一遍，当天数据翻倍（真机实测 11-10 由 1530 分钟变 2295 分钟）。修复：**同键取并集**，来源设备的全部已合并日期完整保留；补 `MergeLedgerTest` 5 例 + `CR21DeviceTest` 多日期端到端 1 例，变异（改回覆盖语义）**5 例全红**后还原。
  2. **动作结果提示不可见**——合并 / 切换的结果文案渲染在设备列表**末尾**，列表一长就被顶出屏幕（真机 dump 与截图都找不到「已并入 N 天的数据」）。修复：提示**置于设备列表之前**（面板顶部），点完无需滚动即可看到。
  3. **累加口径的边界**——来源若是**同一台机器自己导出的旧文件**（本机这台 Redmi 的升级前根目录旧备份正是如此：会话时间戳与本机完全一致），重叠日期会按累加口径**翻倍**。修复：合并确认弹层加一行警示「若这份备份来自「同一台机器自己导出的旧文件」，重叠的日期会按累加口径翻倍——请确认来源是另一台设备」，需求 §4 同步补边界条。
- **真机数据修复**：按「只并入一次」的口径**精确反向相减**（以远端副本库那份旧备份为被多并进去的部分，在 `day_summary / hour_usage / app_usage` 上反向相减；事件表主键去重、会话区间并集，本来就没被第二次合并改变，不动），逐项核对后写回设备并重新上传，云端与本地已对齐：11-10 = 1530 / 11-11 = 2804 / 时段 470 与 2804 / 应用 478 与 2850 / 应用 45 行 / 时段 168 行 / 事件 893 条 / 会话 545 条 / `app_limit` 0 行。
- **验证**：新增 29 测（`CrossDeviceMergeTest` 10 / `CR21DeviceTest` 14 / `MergeLedgerTest` 5，含改写 `DeviceSummaryTest` 5）；**13 处变异全部变红**（原 12 处 + 本次记账同键覆盖 1 处）；`--rerun-tasks` **493 测全绿 / 0 skip**。
- **真机（Redmi `GAQOHMNFQWFUPBQO`，vivo 全程禁用，APK `902c746d`，收尾排版修正后 `f189c600`）**：
  - 设备面板行显示「**2407FRK8EC · 本机 · 当前**」（机型来自 `device.json`）与「未知设备（旧备份）」；每行两枚动作按钮，窄栏纵向排列不折行（上一版并排时「切回本机」折行，已改 `DeviceActionRow(stacked=true)`；云同步页设备卡片同口径改纵向，APK `f189c600` 复核无折行）。
  - 「切换到此设备」→ 提示「已切换，并下载了该设备的数据」，今日页变成旧备份的数据；「切回本机」→ 恢复本机数据（3 小时 42 分钟）。
  - 合并后本机库逐项对上累加口径：**11-10 = 765(本机) + 765(旧备份) = 1530**、11-11 = 1402 + 1402 = 2804、11-12 = 1347 + 658 = 2005；`longestSessionMin` 取大（11-12 = 755，不是 755+658）；会话 545 = 534(并集) + 11(本机)；`app_limit` 0 行（合并不写限额）。
  - 重复合并：**提示「该设备的数据已全部合并过，没有新日期可并入」并置于面板顶部**，本地数据**一格未动**。
  - 合并确认弹层含「同一台机器的旧文件会翻倍」警示；弹层底色不透明、底部让开导航条（上一版 `**` 星号被原样显示，已改「」引号）。
  - 云端与本地对齐：本机设备云端备份摘要＝「来源 MyRedmi / 机型 2407FRK8EC / 导出 2026-11-16 12:21 · v5 / 范围 2026-11-10 – 2026-11-16 · 7 天 / 日 7 · 应用 45 · 时段 168 · 事件 896」。
  - **两处入口行为一致**：云同步页「查看所有设备的备份」展开后，本机行＝「2407FRK8EC · 本机 · 当前 / 1 份备份 / 查看摘要 / 切回本机 / 合并到本机」，未知设备行＝「2 份备份 / 查看摘要 / 切换到此设备 / 合并到本机」——与顶栏面板同一套按钮、同一个确认弹层。
- **仍未处理**：① 本机行的按钮恒为「切回本机」，即使当前已在本机（行内已有「· 当前」标记，不另起状态，暂不改文案）；② 累加口径无法自动识别「来源就是本机自己」，只能靠警示与记账兜底，是否要做机型/序列号级指纹校验待负责人裁决；③ 日期弹层标题与状态栏略重叠、Pager 跨页偶发卡两页之间（CR-20 旧观察，本轮不修）。

---

## 【补记】CR-22 点 tab 卡顿修复 + 同一台机器旧备份指纹识别落地证据（2026-11-16 13:30）

> 本节由评审人补记，不改写上文任何评审原文。Redmi 真机复核证据在 `C:/tmp/cr21/`（`cr22_panel*.xml` / `cr22_confirm.xml` / `cr22_result.xml` / `tab_final/titles.png` / `pull/cr22_before` / `pull/cr22_after`）。

- **三条裁定（负责人 2026-11-16 12:34）**：① **点 tab 跨页不得经过中间页**（真机反馈「点击 tab 切换卡顿，是严重 bug，之前还很丝滑。滑动页面切换就不卡」）；② **同一台机器旧备份的指纹识别**（负责人裁定「做机型/序列号级校验」）；③ **壁纸配色「仅卡片 / 整套主题」两套方案都保留**（负责人裁定「两个都保留」），默认仍为「仅卡片」——本轮**只登记裁决、不改实现**。
- **落地三笔**：`88aeb89`（文档先行：头部 CR-22 登记 + §4「同一台机器的识别」/ §5.2 Pager 同步条 / §5.10 合并流程 / §7-47~48，代码零改动，§7 增至 **48 条**）→ `8502b13`（文档补记：补第三条信号「事件主键子集」，覆盖同机更早快照）→ `370411d`（实现 + 测试，9 文件 +743/-12）。
- **关键实现取舍**：
  - `ui/TabScrollGuard` 只做**状态 + 纯函数**（`ignoresWrite(programmatic, target, index)` / `ownsTarget(target, index)`），口径钉在可单测的纯函数上；不引运行时依赖，不碰 Compose 动画 API。
  - **只忽略中间页、目标页照常回写**：动画走到七成时页面已经基本是目标页，这时切标题最自然；等到落定才切会慢半拍。（口径补记：早期草稿写的是「中间页与目标页都不接受回写」，与文档不一致，已改正。）
  - **新点击取代旧点击时旧协程不抢对齐**：`end(index)` 只在 `target == index` 时才清标记并对齐，否则快速连点会让旧动画的落点覆盖新选择。
  - **对齐写的是 `pagerState.currentPage`**（Pager 此刻的位置）而不是「用户点的页」——CR-17「Pager 是唯一真相源、点 tab 不直接写路由」的红线不变。
  - **指纹判定取保守取向**：命中才跳过，宁可漏判（边缘场景可能翻倍）也不误判（换机主场景会丢数据）。信号③覆盖「同机更早快照」——真机 11-12 实测本机 72 条通知、旧备份 17 条，17 条全部是 72 条的子集。
  - 事件主键格式：通知 `n|ms|type|pkg`、解锁 `u|ms`（两种表用**不同前缀**避免撞键）；事件按**本机时区**归属自然日。
  - 命中判据的**重叠日期以本机为准、不累加**，只并入本机没有的日期；非重叠日期照常按累加口径并入。合并写入目标恒为**本机采集库**，远端副本库与云端备份不动。
  - `BackupRepository` 只新增两个**只读**方法（`dayFingerprints` / `dayEventKeys`），不写库；写库路径仍是 `mergeAccumulate`（8b 硬原则不变）。
- **验证**：新增 20 测（`TabScrollGuardTest` 6 / `SameDeviceFingerprintTest` 10 / `CR21DeviceTest` 新增 4，含真 SQLite 端到端 3 例）；**5 处变异全部变红**（M1 守卫不忽略中间页 / M2 `ownsTarget` 判定反 / M3 旧协程抢着对齐 / M4 指纹不判同一台 / M5 合并不剔除重叠日期）；`--rerun-tasks` **513 测全绿 / 0 skip**。
  - **测试自身暴露的两处问题（都已修正）**：① 变异脚本首版把 `.bat` 用错调用方式且没隔离 `test-results`，5 处「未变红」是**读到上次残留 XML 的假阴性**；修正调用方式并每次先清结果目录后 5 处全红。② 端到端例的事件时间戳写成 `1_794_355_200_000`（**实际是 11-11 而不是 11-01**），导致候选日期变成两天、本机事件键集合为空；已改为 `1_793_491_200_000`，并让 `DeviceOperations` 与 `BackupRepository` 用**同一个时区**（否则日期归属会分叉）。
- **真机（Redmi `GAQOHMNFQWFUPBQO`，vivo 全程禁用，APK `374f277d`；取证用的临时埋点版已移除后重新构建）**：
  - **点 tab 跨页**（临时 `Log.d("TabNav", …)` 取证，取证后已移除并重新构建安装）：点「今日 → 日历」→ `tap index=2` → `write index=2 programmatic=true target=2` → `align landed=2`，**全程没有 index=1（「统计」）的回写**（修复前真机日志是点击后 70ms 回写「统计」、180ms 才回写「日历」）。
  - **滑动切换不受影响**：左滑「日历 → 统计 → 今日」依次 `write index=1 programmatic=false target=-1` / `write index=0 programmatic=false target=-1`——照常过半即翻、跟手不变。
  - **用户可见**：连拍四帧裁标题区域拼接（`C:/tmp/cr21/tab_final/titles.png`）＝ **今日 → 日历 → 日历 → 日历，全程无「统计」**。
  - **指纹识别命中**：先按**字节级精确**清掉 DataStore `merge_ledger` 里的 `unknown|2026-11-10~12` 三条（只动这一个 key，写回前校验**其余 17 个 key 的值字节完全一致**），再从设备面板对「未知设备（旧备份）」（导出 2026-11-12 11:02 · v4 · 3 天 · 事件 133 条）发起「合并到本机」→ 提示「**判定为同一台机器的旧备份（有 3 天的事件全部包含于本机（同机更早的快照）），重叠的 3 天以本机为准，没有新日期可并入**」（信号③命中；提示**置于面板顶部**、直接可见）。
  - **数据一格未动**：合并前后逐表核对——`day_summary`（11-10 = 1530 / 11-11 = 2804 / 11-12 = 2005 / 11-13 = 1418 / 11-14 = 1439 / 11-15 = 673 / 11-16 = 308）、`app_usage`（11-10 = 2 行 478 分 / 11-11 = 9 行 2850 分 …）、`hour_usage`（11-10 = 470 / 11-11 = 2804 …）、通知事件 899 条、会话 545 条、`app_limit` 0 行——**与合并前完全一致**（若仍按旧累加口径，11-10 会变成 2295）。
  - **确认弹层文案**已改写：「会自动识别「同一台机器」的旧备份：重叠的日期以本机为准、不累加」＋警示「若这份备份来自「同一台机器自己导出的旧文件」，重叠的日期不会累加（以本机为准）——请确认来源是另一台设备」。
- **仍未处理**：① 本机行的按钮恒为「切回本机」（行内已有「· 当前」标记，暂不改文案，负责人未裁决）；② 壁纸两套方案的**实现**本轮未动（按裁定只登记，默认「仅卡片」）；③ vivo 上 Pager 跨页偶发卡两页之间（CR-20 旧观察，本轮在 Redmi 上未复现）；④ 日期弹层标题与状态栏略重叠（CR-15 旧观察）。

---

## 【补记】CR-23 点 tab 转场自然化落地证据（2026-11-16 15:20）

> 本节由评审人补记，不改写上文任何评审原文。Redmi 真机证据在 `C:/tmp/screentrace-capture/{cr23_tap_cal,cr23_tap_hi,cr23_swipe,cr23_probe,cr23_shift*,cr23_final}/` 与 `C:/tmp/{probe_titles,dense_titles,fin_titles}.png`。

- **裁定（负责人 2026-11-16 13:50）**：真机反馈「目前点击 tab 切换好僵硬，滑动页面切换就很丝滑，fushi 这个开源项目点击 tab 切换就很流畅、过渡动画很自然」；三档方案（① 只调运动曲线 / ② +标题跟随运动 / ③ +页面景深，③ 明示碰「过渡只做叶子节点」红线）中裁定取**第 ③ 档**，并**放开该红线的这一处例外**（见需求 CR-23 红线修订）。
- **落地三笔**：`71d5555`（文档先行：CR-23 登记 + 红线修订理由 + §5.2「tab 转场运动」条 + §5.7 例外指向 + §7-49~50，代码零改动）→ `af5f321`（文档补记：更正标题位移的逐帧更新机制）→ `1c9b4d8`（实现 + 测试）。
- **红线修订的立论（为什么不算破例）**：CR-15 A1-④ 与 CR-17 ③ 的「过渡只做叶子节点」是针对**整页 `Crossfade` / `AnimatedContent` 数据过渡**的禁令，理由写在本文——**容器重建内容分支 → 重播 `remember { Animatable(0f) }` 入场动画 → 闪一帧**。景深用 `Modifier.graphicsLayer`（绘制期变换）**不重建子树、不重播任何动画**，不触发该立论；整页 `Crossfade` / `AnimatedContent` 做数据过渡仍然禁止。
- **关键实现取舍**：
  - `uikit/TabMotion.kt` 把转场运动参数收成**单一出处**（`alphaFor` / `scaleFor` / `titleShiftDp` / `scrollSpec`），全部纯函数——调观感时不会出现「标题改了曲线没改」的分叉，也能被变异验证咬住。
  - **标题位移的「目标页」取 `tabGuard.target` 而不是路由当前值**：路由要到七成才翻（CR-22），用它会算出**反方向**的位移。点 tab 当下就知道目标页，所以守卫里那个值才是对的。
  - **位移只在程序化滚动期间生效**：横滑时路由是过半即翻的，套同一条式子会让位移在翻页瞬间跳变（18dp/步 → 一次 18dp 的跳）。
  - **运动曲线用 `spring(dampingRatio = 0.85)`** 取代默认的 `DampingRatioNoBouncy`（临界阻尼＝单调减速、机械停住）；回弹量约 2%，是「有生命感」而不是「弹跳」。
- **实现过程中的两处实测修正（都已修，记账备查）**：
  1. **`graphicsLayer {}` 的块不会自己重跑**——首版把标题位移 lambda 写进 `graphicsLayer {}`，真机 logcat 抓到 775ms 动画里**只算了一次**（`titleShiftPx=19.28` 后就停住）。原因：Header 里的 `Crossfade` 是靠它自己的 `Animatable` 驱动的、**并不逐帧重组**，而 `graphicsLayer` 的块只在本节点重绘时才跑；景深那层能生效，只是因为页面本身每帧都在被 Pager 滚动重绘。改为在 `AppHeader` **组合期**读 `State<Float>`（与 `FreshNavBar` 的 `progress` 同一套路）后逐帧更新（`115.6 → 106.0 → 84.7 → 68.6 → 54.5 → 35.8 → 25.8 → 18.0 → 0`）。
  2. **「标题滞后于内容」是联系表的假象**（**先前诊断有误，予以更正**）：`capture_anim.py` 的 `contact_sheet` 按 `fps` 重采样 + `tile` 打包，而 screenrecord **静止段不写帧**，`fps` 滤镜只能拿前一帧补重复，于是转场被**挤到末尾几格**——看起来像「内容到位了标题才翻」。用 logcat（墙钟）+ 按已知 PTS 抽帧重做：点击后 **155ms** 就回写路由（此时 `progress=1.625`、目标页已进视 62.5%），**775ms** 才落定，即标题在 **20% 的时间点**就翻了，**远早于内容到位**，§7-49「标题不得滞后于内容」本就成立。本轮真正的缺陷是**① 纯平移无景深**与**② 标题不随运动位移**，都已修。
- **验证**：新增 `TabMotionTest` 14 例（景深透明度 4 / 偏移超界夹取 1 / 景深缩放 3 / 标题位移 5 / 软着陆曲线 1）；**4 处变异全部变红**（M1 alpha 恒 1 → 3 例红；M2 scale 恒 1 → 1 例红；M3 shift 恒 0 → 4 例红；M4 `dampingRatio` 退回 1.0 → 1 例红）；`--rerun-tasks` **527 测全绿 / 0 skip**（513 + 14）。
- **真机（Redmi `GAQOHMNFQWFUPBQO`，APK `1FBFF9FE`）**：
  - **景深生效**：`capture_anim.py --label 日历 --no-back` 逐帧联系表（`cr23_after/enter.png`、`cr23_tap_hi/enter.png`）里，转场中段每一页整体**变淡、变小**，不再是「一整块刚性平面」；落定页完全清晰。
  - **标题位移生效**（logcat + PTS 抽帧双证）：logcat 逐帧 `115.6px → … → 18.0px → 0`（起步 33.9dp、落定归 0）；按已知 PTS 20ms 步长抽标题区（`fin_titles.png`）看到标题从 x≈165 一路滑到 x≈75 落位，1.10 处交叉淡化成「日历」。
  - **时序**（logcat 墙钟）：`tap` → **147~155ms** 回写路由（`progress=1.54~1.63`）→ **774~779ms** 落定；标题在 19~20% 的时间点就翻，**不滞后于内容**。
  - **横滑不受影响**：`--swipe 1000,1400,220,1400,400` 复拍正常，景深一视同仁、跟手不变。
  - **无新增掉帧**：`dumpsys gfxinfo` 50th=8ms / 90th=10ms、真 jank 0.5%（与改动前同量级）。
- **工具与方法学记账（本轮踩的坑）**：`capture_anim.py` 的联系表**能回答「发生了什么」、不能回答「什么时候」**——帧序可靠、帧时刻不可靠。要定时序必须用 logcat 埋点或按已知 PTS 单帧抽取，二者对齐后互证。
- **仍未处理**：① **vivo `10CEB40M5G001DK`「卡的要死」**（负责人 2026-16:16 指出，疑与 CR-20 评审记账的「切 tab 后 Pager 偶发卡在两页之间」同源：点 tab 的 `animateScrollToPage` 与 `LaunchedEffect(mainPage)` 的回滚滚动两次动画互相打断）——**该机当前未连接 adb，待接入后排查**；② 本机行按钮恒为「切回本机」文案未改；③ 壁纸两套方案本轮只登记未实现；④ 日期弹层标题与状态栏略重叠（CR-15 旧观察）。

---

## 【补记】CR-24 壁纸模糊一次性位图缓存 + vivo 卡顿归因（2026-11-16 17:50）

> 本节由评审人补记，不改写上文任何评审原文。归因数据采自 vivo `10CEB40M5G001DK` 与 Redmi `GAQOHMNFQWFUPBQO`（同口径脚本 `dumpsys gfxinfo framestats` 逐帧拆段）。

- **起因**：CR-23 收口后负责人追加指出「vivo 才是真的卡的要死」。排查过程有三处**方法学修正**，先记账（都影响过结论）：
  1. **vivo 不触摸就自动降到 60Hz**（虽设了 120），**`adb shell input tap` 不触发高刷**——只有真实触摸才提到 120。于是先前几轮「掉帧率 / 1.5s 帧数」全是在 60Hz 档测的，**不可跨次比较**。改用 `framestats` 的**每帧真实耗时**并**按每帧自己的 vsync 分档**后才可比。
  2. **`capture_anim.py` 的联系表只能回答「发生了什么」、不能回答「什么时候」**（screenrecord 静止段不写帧 + `fps` 重采样补重复帧）。时序必须用 logcat 埋点或按已知 PTS 单帧抽取。
  3. **底部导航选中胶囊会移动**，一次性坐标会点错邻居（本轮又踩了一次，落点是「统计」不是「今日」），每次点击前必须重新 dump 定位。
- **归因（vivo，p50 每帧，120Hz 档）**：
  | 配置 | 录制绘制 | GPU+交换 | 帧总耗时 |
  |---|---|---|---|
  | CR-23 全开 + 壁纸 | 5.67 | 5.13 | 13.63 |
  | 关**景深** | 5.66 | 5.31 | 14.22 |
  | 再关**壁纸** | 5.67 | **4.34** | 13.43 |
  | 再关**标题位移** | 5.66 | **4.27** | 12.98 |
  | **Redmi 参照**（无壁纸） | **2.97** | **3.48** | **9.24** |
  **结论**：① **CR-23 无罪**——景深实测**免费**（GPU 5.13 vs 关掉 5.31，是噪声）、标题位移 0.07ms；② **壁纸模糊 = +0.97ms GPU/帧**，是唯一可归到本 App 的确定开销；③ **vivo 的基线绘制成本是 Redmi 的 1.9 倍**（5.67 vs 2.97ms），不受任何配置影响（SoC 差异 + 三页常驻）；④ **200~350ms 的「卡一下」不是本 App**——logcat 显示是后台进程（`gallery.aiservice` 一次 GC 229ms、QQ 247ms、`widgetControl` 212ms）抢 CPU，本 App 的 logcat 里**没有 GC**。
- **落地两笔**：`841316a`（文档先行：CR-24 登记 + §5.4 模糊口径 + §7-51，§7 增至 **51 条**，代码零改动）→ `63192bf`（实现 + 测试）。
- **关键实现取舍**：
  - **半径口径「1px ≈ 1dp」**：模糊前按 **density 降采样**，于是降采样位图上 1px ≈ 屏幕 1dp，`blurDp` 直接是半径像素数，与旧 `Modifier.blur(blurDp.dp)` 同语义；长边再钳 800。后续裁切缩放等比放大模糊半径——与旧口径一致（旧的也是先模糊后缩放）。
  - **不做逐像素等价**：三趟盒式模糊与 RenderEffect 的高斯不是同一算法，是「同量级」；用户本来就拖滑杆看效果。口径写进 §5.4。
  - **`blurArgb` 是纯函数**（只吃整数像素与半径）——符合 §6「壁纸配色工程要求」的既有约定，可 JVM 单测打靶；前缀和把每像素求和摊成 O(1)，整趟 O(像素数)、不随半径变慢。
  - **缓存键 = (位图, blurDp, density)**：只在变化时重算；拖滑杆时按新值重算一次（单次 CPU 模糊在降采样图上可接受），拖动过程不做逐帧模糊。
- **实现中修掉一个真 bug**：`blurArgb` 首版 `var src = pixels` 是**别名**，模糊直接改写了调用方的输入数组并把它返回（不是纯函数）。由 `WallpaperBlurTest` 的「模糊后不该还是原图」咬住，改为先 `copyOf()` 并加「输入数组不得被改写」显式断言。
- **变异验证的两处自我修正**：首轮 5 处变异 2 处未变红，复查发现——① M1「半径 0 不再原样返回」是**非行为变异**（半径 0 的盒式模糊本就是恒等变换，改了也无变化），换成「返回空数组」这种真变异；② M3「只模糊一趟」是**真弱项**（旧用例只比半径 1 vs 6，比不出趟数），补「三趟过渡带比一趟宽」（阶跃边半径 4：一趟 8 个中间值、三趟 20+）。修正后 **5/5 全红**。
- **验证**：新增 `WallpaperBlurTest` 10 例 + 改写 `WallpaperTuningTest` 1 例（API<31 用例 → 「全版本生效 + 越界夹取」）；`--rerun-tasks` **537 测全绿 / 0 skip**（527 + 10）。
- **真机**：Redmi `GAQOHMNFQWFUPBQO` 已装 `63192bf` 构建、537 测全绿；**vivo `10CEB40M5G001DK` 在收口时掉线**，「GPU 段应从 5.13ms 降到 ~4.3ms」的复测与壁纸模糊的真机观感核对**待接入后补做**（已记入 TODO）。
- **仍未处理**：① vivo 的 GPU 段复测与壁纸模糊观感核对（设备掉线）；② `beyondBoundsPageCount = 3` 造成的绘制录制开销（Redmi 2.97 / vivo 5.67ms）——降到 1 可大幅改善但**会退回「切页先空一帧再补内容」**，风险高、待裁决；③ 本机行按钮恒为「切回本机」文案；④ 壁纸两套方案（仅卡片/整套主题）只登记未改实现；⑤ 日期弹层标题与状态栏略重叠。

---

## 【补记·更正】CR-24 性能归因有误——壁纸模糊实测不是开销（2026-11-17 08:45）

> 本节是对上一节（CR-24 补记）的**更正**，不删改原文。vivo `10CEB40M5G001DK` 复测，同机同脚本 `dumpsys gfxinfo framestats` 拆段。

- **更正一：「壁纸模糊 = +0.97ms GPU/帧」是错的。** 那个 0.97ms 来自 A/B 里「关掉**整层壁纸**」那一档（关掉的是位图绘制 + dim 遮罩 + 主题遮罩**三层**，不只是模糊）。而且复测发现 vivo 的 `wallpaper_blur` 实际是 **1**（几乎不模糊），模糊本来就没在跑——把该值设成 12 之前，新旧实现行为完全一致。
- **更正二：CR-24 的性能收益实测为噪声。** 打开模糊（`wallpaper_blur = 12`）后同机 A/B：

  | 实现（blur=12） | 录制绘制 | GPU+交换 | 帧总耗时 |
  |---|---|---|---|
  | **缓存版**（CR-24） | 5.67ms | **5.08ms** | 13.38ms |
  | **每帧 `Modifier.blur`**（旧） | 5.66ms | **5.11ms** | 13.24ms |

  差异在噪声内。**「`beyondBoundsPageCount = 3` 把模糊乘以 3」的推断不成立**——每页各自的壁纸 `Image` 上的 `Modifier.blur` 半径只有十几个 dp，现代 GPU 本就吃得下。CR-24 收口时写的「GPU 5.13 → 4.58ms」同样是噪声（两轮之间 `wallpaper_blur` 取值还不一样）。
- **CR-24 的真实价值不是性能**，是另外三条：① **API < 31 也生效**——旧 `Modifier.blur` 依赖 RenderEffect，低版本把 `blurDp` 钳成 0 并禁用滑杆，观感与高版本不一致；CPU 模糊后全版本一致。② 模糊成为**纯函数**（`blurArgb`），可 JVM 单测打靶、可变异验证（5/5 全红）。③ 「参数变化时算一次」在架构上更干净。**性能一项应当按「无收益」记账**，不是「省了 1ms」。
- **vivo「卡的要死」的真实构成（复核后）**：
  1. **录制绘制 5.67ms**（Redmi 同口径 2.97ms）——SoC 差异 + `beyondBoundsPageCount = 3` 三页常驻的显示列表；**这是最大头，且不受任何配置影响**。
  2. **200~350ms 的尖峰**——后台进程 GC 抢 CPU（`gallery.aiservice` 一次 229ms、QQ 247ms、`widgetControl` 212ms），本 App 的 logcat 无 GC。
  3. **点 tab 动画的 630ms 长尾**（见下条）。
  4. 壁纸层本身约 0.97ms GPU（位图 + 两层遮罩，**与模糊无关**）。
- **顺带定位到「点 tab 比滑动卡」的直接原因**：`spring` 的渐近尾巴——logcat 实测跨两页点 tab 时，**78% 的行程只用 147ms，剩下 22% 却爬了 630ms**（154ms 时 progress=1.71，779ms 才落定 2.0）。这段「几乎看不出在动、又没结束」的磨蹭就是观感上的「卡住」；滑动没有这段尾巴（手指驱动，松手即落定）。**可改**：把 `TabMotion.scrollSpec` 换成有明确落点的曲线（强调缓出，约 400ms 准确停住），待负责人拍板。
- **测量方法学再记一笔**：A/B 必须**逐项确认被测变量的实际取值**（本轮 `wallpaper_blur = 1` 差点让我把「整层壁纸」的开销错记成「模糊」的）；同机同配置才是可比的，跨轮的「下降 X ms」在噪声里不作数。

---

## 【补记】CR-25 点 tab 曲线「明确落点」落地证据（2026-11-17 09:55）

> 本节由评审人补记，不改写上文任何评审原文。真机 vivo `10CEB40M5G001DK`（**Redmi 禁用中**：负责人 09:29 起做插件稳定性测试 12h45m，全程未碰）。

- **裁定（负责人 2026-11-17 08:52「可以做出来看看效果」）**：治「点 tab 比滑动卡」的直接原因——`spring` 的**渐近尾巴**。logcat 实测（Redmi，跨两页）**78% 的行程只用 147ms，剩下 22% 却爬了 630ms**（154ms 时 progress=1.71，779ms 才落定 2.0）；那段「几乎看不出在动、又没结束」就是观感上的「卡住」。滑动是手指驱动、松手即落定，没有这段。
- **落地两笔**：`64571b9`（文档先行：CR-25 登记 + §5.2「运动曲线」条改写 + §7-52，§7 增至 **52 条**，代码零改动）→ `358297b`（实现 + 测试）。
- **关键实现取舍**：
  - 曲线换 **M3 emphasized decelerate** `CubicBezierEasing(0.05, 0.7, 0.1, 1)`——**稳定 API**（§6「不为追求 Expressive 外观引入实验性组件」是禁实验组件，不禁稳定 API 的缓动）。`tween` **到时即进度 1.0**，从曲线类型上就没有尾巴。
  - **时长按行程给**：`scrollDurationMs(pageDelta)` = 1 页 350ms / 2 页 440ms / 3 页及以上 480ms（上限取 §5.5「≤500ms」留 20ms 余量）。旧 spring 的 779ms 落定对一次 tab 切换太长。
  - `scrollSpecFor(pageDelta)` 取代旧 `scrollSpec`；`AppNav` 两处调用点按 `|目标页 − 当前页|` 算行程。仍是**纯函数**，与 `alphaFor` / `scaleFor` / `titleShiftDp` 同在 `TabMotion` 一处。
- **验证**：`TabMotionTest` 改写曲线用例（必须是 tween + 强调缓出 / 中点已走完大部分行程 / 行程越远越久但 ≤500ms / 行程 0 按一页算）；**5 处变异全部变红**（时长恒 350 / 上限放开 900 / 缓动退回线性 / 时长误乘 4 倍 / 行程 0 不夹取）；`--rerun-tasks` **540 测全绿 / 0 skip**。
  - **顺手修掉一条空洞断言**：原 `assertEquals(EMPHASIZED_DECELERATE, tween.easing)` 是**拿字段跟自己比**——变异改的就是这个字段，恒真、等于没测。换成直接打缓动函数输出 `transform(0.5f) > 0.8`（线性是 0.5），M3 因此从绿变红。
- **真机（vivo，帧时间戳口径）**：
  | 动作 | 帧数 | 首末帧间隔 |
  |---|---|---|
  | 对照：点标题（无动画） | 27 | 464ms |
  | 点 tab（9 轮，1 页与 2 页均有） | 50~52 | 873ms |
  | **差值＝动画本身** | **≈24 帧** | **≈409ms** |
  实测 ~409ms 与新曲线的 350/440ms 吻合；**若仍是旧 spring（779ms），总跨度应约 1240ms**——曲线确实缩短约一半，且无尾巴。
- **测量方法学记账（本轮踩到）**：
  1. **vivo 不放 App 日志**——`Log.d` 与 `Log.w` 都被过滤，`logcat -d --pid=<pid>` 只能看到 `viewroot_draw_event` / `auditd` 这类系统事件。CR-22/CR-23 用的 logcat 埋点取证**在 vivo 上不可用**，必须改用 `framestats` 帧时间戳。
  2. **帧跨度不等于动画时长**——点 tab 与点不动画处都有固有的按键反馈开销（27 帧 / 464ms），必须**做对照相减**才能分离出动画本身。
  3. **每帧打一条日志会污染测量**（`Log.w` 在渲染热路径上），埋点版与正式版不能混着量。
- **仍未处理**：① 「点 tab 比滑动」的另两个因素未动——**直接操控 vs 观察动画**（感知规律，改不了）与**跨两页行程加倍**（可考虑远距 tab 走不同转场，待裁决）；② `beyondBoundsPageCount = 3` 的绘制录制开销（Redmi 2.97 / vivo 5.67ms），降到 1 会退回「切页先空一帧」，待裁决；③ 本机行按钮恒为「切回本机」文案；④ 壁纸两套方案只登记未改实现。

---

## 【补记】CR-26 纠正 CR-25 曲线起步过冲（外部复核触发，2026-11-17 11:15）

> 本节由评审人补记，不改写上文任何评审原文。本轮是**外部复核（Claude / GPT）→ 本地复算 → 自我纠错**的闭环；复算脚本 `curve_check.py`（三次贝塞尔二分求解）。

- **触发**：负责人把「点 tab 卡顿」的 issue（`C:/tmp/issue_tab_lag.md`，已推 GitHub `Sunbeam-5/ScreenTrace`）分别交给 Claude 与 GPT 分析。**两家独立结论一致：CR-25 的曲线起步过冲是「僵硬」的直接来源。**
- **复算核实**（我用二分解三次贝塞尔逐格重算，Claude 的表格**一格不差**）：

  | 时间占比 | 3% | 7% | 11% | 22% | 34% | 45% | 68% |
  |---|---|---|---|---|---|---|---|
  | **CR-25 `(0.05,0.7,0.1,1)`** | **38%** | 55% | 65% | 81% | 89% | 94% | 98% |
  | `(0.2,0,0,1)` | 2% | 8% | 21% | 56% | 74% | 85% | 96% |
  | **CR-26 `FastOutSlowIn`** | 0% | 1% | 3% | 18% | 48% | 71% | 93% |

  **首帧跳变**（跨两页 = 2×1220px）：CR-25 在 60Hz 首帧（16.7ms）走完 38% = **926px**；`FastOutSlowIn` 只走 0.32% = **8px**。
- **结论：CR-25「治了尾巴、却把起步搞坏了」**（Claude 的原话准确）。旧 spring 首帧实测只走 5.9%（CR-23 期 logcat：17ms 时 progress=0.117），**CR-25 的起步跳变是旧的 6.5 倍**。眼睛看到的是「跳过去」不是「滑过去」——这才是「僵硬」。
- **落地两笔**：`336dc9b`（文档先行：CR-26 登记 + §5.2 曲线再改写 + §7-52 纠正）→ 本笔（实现 + 测试）。
- **三条改动**：
  1. **曲线换 `FastOutSlowInEasing`**（`CubicBezier(0.4, 0, 0.2, 1)`，稳定 API）——S 形：慢起 → 中段快 → 慢收，末端 `tween` 到时即 1.0、同样无尾巴。
  2. **收紧曲线断言**：原「50% 时间点 > 80%」**恰好放过 95% 这种极端缓出**（GPT 指出，属实）。改为「5% ≤ 5%」（专咬起步过冲）+「33% ≤ 50%」+「50% ∈ [40%, 85%]」+「90% ≥ 95%」。
  3. **剩余距离改用连续进度**（GPT 指出，属实）：`|目标页 − (currentPage + offsetFraction)|` 向上取整。`currentPage` 是离散索引，动画进行到一半再点 tab 会把剩余距离算错。抽成 `TabMotion.remainingPages` 纯函数、可单测。
- **实现中的一处自我纠错**：我先提的断言「33% 时间点进度 ≤ 25%」**本身是错的**——`FastOutSlowIn` 是 S 形，33% 处实为 **44.8%**。已按实算值改成「5% ≤ 5% / 33% ≤ 50% / 50% ∈ [40%,85%] / 90% ≥ 95%」，需求文档里留了更正说明。**教训：曲线断言不能靠「看起来应该如此」，必须先复算再钉值。**
- **验证**：`TabMotionTest` 改写曲线用例 + 新增 `remainingPages` 用例（4 例）；**5 处变异全部变红**（曲线退回过冲 / 时长恒 350 / 上限放开 900 / 剩余行程退回离散索引 / 不夹取下限）；`--rerun-tasks` **541 测全绿 / 0 skip**；复算脚本确认**首帧跳变 926px → 8px**（60Hz 两页，目标 <100px）。
- **对两家分析的评价（给负责人留底）**：
  - **Claude**：数值部分**零误差**（三列曲线表逐格复算全对），「第一帧瞬移 0.76 页 ≈ 900px」精确；「emphasized decelerate 是给元素进入屏幕的短位移设计的，不是给整页位移设计的」定性判断也对；「滑动顺是因为手指速度连续、起步无跳变」回答了 issue 的 (a) 且可操作。**一处偏乐观**：说「vivo 帧总耗时 13.6ms 在 60Hz 预算内、三页常驻不建议动」——p50 是 13.6ms，但 p90 是 26~30ms 且有 200~350ms 尖峰；「不是点 tab 僵硬的主因」成立，「不用动」只对这个症状成立。
  - **GPT**：找到一个真代码问题（剩余距离用离散 `currentPage`），并指出「50% > 80% 的断言太宽松」——两条都属实、本轮已修。「只是把 Spring 的长尾换成了 Tween 的长尾」方向对但不精确（Tween 到时即 1.0、没有尾巴），真正的问题是**前段过冲**；Claude 的表述更准。三组曲线 A/B、和「不能因 jank=0.5% 就认定高刷没问题」都合理。
  - 两家共同的正确保留：帧率那条都只敢说「推断」、都要真手指测——对，我们自己也被 `input tap` 不触发高刷坑过。
- **真机（vivo `10CEB40M5G001DK`，Redmi 禁用中全程未碰）**：`capture_anim.py --label 日历 --no-back` 复拍点 tab 转场（`cr26_tap/enter.png`）——**渐进过渡、无瞬移**，景深仍生效（中段页变淡变小）；帧跨度对照口径约 **414ms**，与新曲线 2 页 440ms 吻合。**注意**：`screenrecord` 静止段不写帧 + `fps` 重采样，联系表**分辨不出首帧跳变**（那是亚 20ms 量级），首帧 926px→8px 的结论由**复算脚本**给出（三次贝塞尔二分求解，可复跑）。
- **仍未处理**：① 远距 tab 是否改 Fade Through（两家都提，改动大、待裁决）；② `beyondBoundsPageCount = 3` 的绘制录制开销（两家都建议先不动）；③ **真手指测刷新率**（两家共同的未验证项；`input tap` 不触发 vivo 高刷，需真手指按住后看 vsync 周期）；④ 本机行按钮文案、壁纸两套方案未改实现。

---

## 【补记】CR-27 记账入 Room 同事务 + 文案勘误（2026-11-17 12:55）

> 本节由评审人补记，不改写上文任何评审原文。触发：**GPT 完整项目审查**（7 条经本地**逐条核对代码、全部属实**）。

- **落地两笔**：`693df68`（文档先行：CR-27 登记 + §5.10 记账同事务口径 + §7-53~54，§7 增至 **54 条**；文档进度源纪律；文档地图过时数字同步）→ `15b1cae`（实现 + 测试）。
- **① 防重复记账不是原子操作（P1，GPT 抓到的真问题）**：`mergeIntoLocal` 先 `mergeAccumulate`（Room 事务累加）、再 `settings.recordMergedDates`（DataStore 记账），两步**不在同一事务**——进程在中间被杀会「数据已加、账没记」，下次合并把同一批日期**再累加一遍**（当天翻倍）。
  - 新增 Room 表 **`device_merge_ledger`**（PK = `deviceId` + `date`，**天然整集合保留**——CR-21 补记踩过「同键覆盖丢日期」的坑，用联合主键从结构上堵死）；`MIGRATION_5_6` + DB **v5→6**。
  - 账本写入挪进 `mergeAccumulate` 的**同一个 `db.withTransaction`**：先查未合并日期、再累加、最后写账本，三步一荣俱荣一损俱损。
  - 读取时取 **Room ⊕ 旧 DataStore 的并集**（迁移期两边都可能有历史，取并集才不丢老账）；去掉事务外的 `recordMergedDates`，DataStore 只作历史兼容、不再是账本。
- **② 节流文案与实际不符（GPT 抓到的低成本 bug）**：`DEFAULT_MIN_INTERVAL_MS` 批 6g 已从 30 分钟收紧到 **10 分钟**，`PullOutcome.Throttled` 的提示仍写「刚拉取过，30 分钟内不重复拉取」——用户会以为要等半小时。改为按实际间隔出文案，不再写死数字。
- **③ 文档进度源纪律**（负责人采纳 GPT 建议）：`docs/TODO.md` 是**唯一真实进度源**；需求文档只维护 **§7 验收条数**（唯一权威）；`docs/项目文档地图.md` 每次收口同步；**README 冻结**（红线），其中的测试数/进度是**历史快照、不代表现状**。**三处不再各维护一套进度数字。**
- **测试**：新增 7 例（Room 账本 4 / 「旧账在 DataStore 时也要被挡住」1 / 文案 2）；改两处旧用例口径（`多日期重复合并` 的账本断言改为「并集」、`MigrationTest` 补 `MIGRATION_5_6`——否则 Room 报「A migration from 4 to 6 was required but not found」）。`--rerun-tasks` **550 测全绿 / 0 skip**。
- **真机**：**无可用设备**（Redmi 禁用至约 22:14；vivo 掉线），迁移的真机验证待补。兜底是 `MigrationTest`：它先建 v4 库写入数据、再用 `ScreenTraceDatabase`(v6) + 两条迁移打开同一文件，Room 会做**完整 schema 校验**（与 `6.json` 比对表/列/索引），迁移 SQL 差一个索引名都会抛异常——比人眼看更严。
- **变异验证的方法学记账（第三轮同类问题）**：首轮 5 处有 3 处未变红，复查分两类——
  1. **非行为变异**（CR-24 与本轮）：`insert(emptyList())` 本就无效果；「多写一次 DataStore」不改变可观测行为（Room 账本照写）。**变异必须改变可观测结果**，否则它证明不了任何事。
  2. **用例落在变异盲区**（CR-26）：期望值被 `ceil`/`coerce` 抹平，变异前后答案相同。
  本轮补的两条用例与两处变异都按「必须改变可观测结果」设计：新用例断言**该日无行**（旧账必须挡住重累加）、M4 只记第一个日期、M5 记到错误的来源设备。
- **仍未处理**：① 迁移真机验证（无设备）；② **CR-28「来源设备维度」**——GPT 的 P0（跨设备合并后本机数据被少算），方案已出、待负责人过目与三项裁决；③ **`allowBackup=true` 无备份规则**（GPT 的 P1 隐私）；④ Pager 跨页停滞的 UI 级测试与复现；⑤ 远距 tab 是否改 Fade Through；⑥ 真手指测刷新率；⑦ targetSdk 36 / `QUERY_ALL_PACKAGES` / security-crypto 弃用（发布准备）。

---

## 【补记·CR-27 真机迁移验证完成】（2026-11-17 13:50，vivo 重新接入）

上一节记的「真机：无可用设备、迁移的真机验证待补」已补做，**结果：迁移通过**。

- **设备**：vivo CEB40M5G001DK\（Redmi 禁用至约 **00:47**，全程未碰）。
- **装机 + 冷启动**：\db install -r\ Success → \MainActivity\ 为 \	opResumedActivity\、**crash 缓冲 0 条**——迁移没让 App 起不来。
- **真机库核对**（拉 \screentrace.db\ + wal/shm 后只读打开）：
  | 表 | 行数 |
  |---|---|
  | `day_summary` | 10 ✓ |
  | `app_usage` | 247 ✓ |
  | `hour_usage` | 144 ✓ |
  | `notification_event` | 3735 ✓ |
  | `screen_session` | 1283 ✓ |
  | `app_limit` | 0 ✓ |
  | **`device_merge_ledger`** | **0**（新表建出，列 = `deviceId` + `date`）|
  `room_master_table.identity_hash = 467d338bb89b73624d0200e1703ad3a6` 已写，**老数据一行未丢**。
- **结论**：`MIGRATION_5_6` 在真机 v5→v6 上通过；`MigrationTest` 的完整 schema 校验（比对 `.json`）与真机结果一致。
（2026-11-17 修：本段曾被 PowerShell 反引号转义写坏——`a`/`n`/`r`/`f` 被当转义符吞掉、反引号变反斜杠，已按原意修复。）

---

## 补记 · CR-28 批次 A 真机迁移验证（v6→v7，2026-11-17 17:13 vivo `10CEB40M5G001DK`）

**首验抓到 P0（真机复核的价值）**：装机后 App 起不来，`IllegalStateException: Migration didn't properly handle: day_summary`——`MIGRATION_6_7` 建了 `sourceDeviceId` 索引而四个实体**没声明 `@Index`**，Room schema 校验期望 `indices=[]`、拒绝打开库。**`MigrationTest` 假绿**：Robolectric 的迁移校验对索引不敏感，同迁移在真机必崩（本轮最值得记账的教训：**迁移测试绿 ≠ 真能开**，「迁移建了索引、实体没声明」这类漂移 JVM 咬不住）。

**修复（`d6162d0`）**：四实体补 `@Index` 声明与迁移逐字一致；`MigrationTest` 补显式 `sqlite_master` 索引断言（8 条索引名逐一对账）堵假绿洞；M14 变异（迁移漏建一条索引）验证断言有牙；564 测全绿。

**复验通过（同机 v6→v7）**：
- `room_master_table.identity_hash = 401b38329181b3303f7ad96ed8be14af`（v7），App 无崩溃、采集正常。
- **归档正确**：六表全部行 `sourceDeviceId = 464e282a-…`（本机 UUID；Room ⊕ DataStore 账本皆空 → 无 legacy，符合「纯本机日归本机」）；无未归档行；旧行 `seq = 0`。
- **保真（按主键分类核对）**：unlock **890/890 全保真**；day/app/hour/session **键 0 丢**（session 1 处为扫线并集吞并、已验证被覆盖；day/app/hour 的值更新均为 11-17 活跃统计的正常增长，非迁移改值）。
- **seq 真机生效**：`notification_event` 出现 `seq = [0,1,2,3]`——同毫秒多条不丢真实发生。
- 附带记账：WAL 未 checkpoint 时 `run-as cat` 只拉主库文件会误判「迁移没跑」——`check_v7.py` 已改拉 db+wal+shm 三件套。

**遗留（非迁移问题，记待办）**：`notification_event` 1 键丢 + 10 处 `channelId` 变化，归因为 `MergePolicy.mergeNotifications` 的「秒级分组取大条数、**整边替换**」既有语义（CR-01「同秒多条不丢」在整边替换下并不严格保证），发生在迁移窗口的正常采集中；JVM T7 行数断言与 unlock 全保真佐证迁移搬行无损。待批次 E 或单列修复。

---

## 补记 · CR-28/29 Redmi 真机验证（2026-11-18 08:00 GAQOHMNFQWFUPBQO，插件稳定性测试结束后）

- **v5→v8 全链迁移通过**（该机库比 vivo 还旧一级，MIGRATION_5_6→6_7→7_8 全跑）：identity_hash = 776a5e89c0227e47f90ee1d1add7c2e3（v8），App 无崩溃、采集正常。
- **归档正确**：六表全部行 sourceDeviceId = 02ebd900-…（Redmi 本机 UUID；账本空 → 无 legacy，符合纯本机日归本机）。
- **保真（按主键分类核对）**：
otification_event **1492/1492 全保真**；day/app/hour/session **键 0 丢**（值更新均为启动后正常采集补录；session 1 处扫线并集吞并已验证被覆盖）。
- **UI 复核**：统计页「占用时钟时长 128小时52分钟」真机显示（图例行下，与「日均亮屏」＝SUM 明确区分，§7-57 ✓）；导出 JSON 走 SAF 保存正常，**纯本机来源无兼容警告＝正确负路径**（多来源正路径由 AppViewModelCr28Test/BackupSourceRoundTripTest 单测覆盖）。
- **冒烟**：今日/统计/日历三页渲染真实数据，pid 稳定、**零 FATAL**。
- **遗留观察（非 CR-28 引入，待裁决）**：Redmi 历史 day_summary 存在 **>24h 的脏行**（11-11 = 46h44m、11-12 = 33h25m、11-10 = 25h30m）——旧「累加合并」时代撑爆的日聚合（before 快照原值即如此；MIGRATION_2_3/3_4 清过 pp_usage/hour_usage，day_summary 从未清洗）。CR-28 保真核对确认不是本批引入；是否清洗这批历史脏行请负责人裁决（可用重建工具思路或单独清洗批次）。

**裁决与落地（2026-11-18 08:30，批次 F）**：负责人裁决「用重建工具处理」。SourceRebuild.rebuildOversizedDays 以重建工具四件套（快照 → 会话并集重算校正 → 核对 → 回滚 + 
ebuild_log）处理 screen_min > 1440 脏行：时长字段按「只在重算值更小时改写」收紧、unlocks 按事件表实证计数、launches 无实证保留原值；检测到脏行时首启自动执行（幂等）。单测 2 例 + **M24/M25 变异全红**（校正不写回 / unlocks 不按事件计数）；576 测全绿。

**Redmi 真机执行结果**：超 24h 脏行**清零**（11-10: 1530→234、11-11: 2804→1402、11-12: 2005→1347 分钟，均为当日会话并集值）；键集合不变（9 行）；
ebuild_log 记 1 行（11-10~11-12，checkSumMin=2983 == checkUnionMin=2983、
olled_back=0）；App 零崩溃。遗留观察：launches（5366/1050/415）同为旧脏值但**无实证不猜**、按设计保留原值。 \pp_usage\ | 247 ✓ |

---

## 补记 · CR-28-R1 数据正确性修复（2026-11-18 09:20，GPT 审查 2 P0 + 4 P1 逐条修复）

GPT 静态审查提出 2 P0 + 4 P1，逐条核对**全部属实**，按其建议顺序修复（不推翻架构）：

- **P0-1 清洗跨来源混算**：`rebuildOversizedDays` 的会话/事件未按 `sourceDeviceId` 过滤就写回单来源行（多来源下 SUM 重复）→ 改**逐行同来源重算**。回归：双来源清洗用例（A 60 + B 60，SUM=120 不重复）。
- **P0-2 无会话证据清零**：`slice==null → 0` 把无实证行清零 → 改**无实证不改写**（`oversized_no_evidence`，原值保留）。
- **P1-1 EventSeq 跨批冲突**：修法深化——**组 = 主键组、组内按稳定内容键（含 `channelId`）排序编号**（早先「完整内容键分组」方案会让不同 channelId 各拿 seq=0 撞主键，实现中当场发现并纠正）。回归：乱序超集不吞、channelId 不互吞、重放幂等。
- **P1-2 事件改归属不看 seq / 先删后插**：改**完整主键匹配 + 先查后迁**（目标已有同完整主键 → 保留 legacy 不动，证据不丢）。
- **P1-3 重建核对不完整**：加**逐指标差减预检**（任一指标 Σ备份 > legacy 整日不重建）、app/hour 差减改用**去重后**数据（备份重复行不双扣）、重建前后**六项 SUM 指标**全核对（`longest_session_min` 仍 MAX 不差减）。
- **P1-4 半开区间**：`datesOfSession` 结束日按 `endMs-1`（结束于零点只算前一天）；事件归档 `>= lo AND < hi`；会话相交 `endMs > lo AND startMs < hi`。
- **确认项 副本库归档**：归档挂到 `BackupRepository.restore()` 开头（写入前归档、幂等，覆盖副本库全部恢复路径；副本库主体来源=该副本设备）。

验证：`Cr28R1RegressionTest` 7 例 + `SameMillisecondEventTest` 修订 1 例；**六处变异全部真行为红**（M26 来源过滤回退 / M27b 无证据清零 / M28 不看 seq / M29 不按内容排序 / M30 去掉 unlocks 预检 / M32 endMs-1 回退，各 1 红；M27 首版为编译红、按纪律补 M27b 真行为变异）；`--rerun-tasks` **583 测全绿 / 0 skip**。Redmi 真机复验：清洗值保持（234/1402/1347）、`rebuild_log` 幂等空转不新增、零崩溃。

---

## 补记 · CR-28-R2 数据正确性修复（2026-11-18 09:50，GPT 针对性复审 4 新 P1 + 6 测试缺口）

逐条核对**全部属实**，按「先写能失败的回归测试 → 修复 → 行为变异证明旧实现必失败」执行：

- **① `app_usage.launches` 未参与历史差减/预检/核对** → launches 与 minutes 同等独立差减（`backupApps` 值改 (minutes, launches) 对）、超额整日跳过、重建前后核对（`afterAppLaunches == beforeAppLaunches`）。
- **② 删 legacy 日行丢 `longestSessionMin`（MAX）** → 删行条件加「备份分量 MAX ≥ legacy MAX」守卫（不可拆则保留剩余行：其余字段 0、longest 原值）；核对加 **MAX 只增不减**（`afterMax >= beforeMax`——实现中发现 `==` 过严：备份实证补大 MAX 是合法的，改「不得变小」才是 GPT 原意）。
- **③ 清洗校验键只按 date（同日 A 有证据 B 无证据时误炸整批）** → 改 `(date, sourceDeviceId)` 复合键；用例：A 校正 + B 无证据保留 → 事务正常提交。
- **④ `restore` 用 `runCatching` 静默吞归档异常** → 移除，失败上抛触发恢复事务回滚；故障注入（`sourceDeviceId` lambda 抛异常）覆盖 MERGE/OVERWRITE 双路，验证原数据不变、无部分写入。
- **6 项测试缺口补齐**：G1 同毫秒解锁跨批 1→2 保留 2、重放不增；G2 同毫秒同内容通知 1→2 保留 2；G3 通知完整主键迁移（seq 不匹配/冲突都留 legacy）；G4 六指标逐项超额跳过 + 正常重建前后六项 SUM + MAX 不变；G5 半开边界四例（事件恰在 lo 归入、会话恰结束于 lo 归入、恰开始于 hi 不归、空区间不占用——顺带补「空区间返回空集」语义）；G6 副本库归档**真实恢复链路**（restore 未归档行归属副本设备）。
- **修剪两处过时断言**：`SourceRebuildTest` 的 `legacyDay` longest 90→50（MAX 守卫使「拆完删行」需分量 MAX 覆盖；「MAX 不可拆保留行」由 R2-2 专测）。

验证：`Cr28R2RegressionTest` 10 例（含事务回滚 MERGE/OVERWRITE 双路）；**八处行为变异全部真红**（M33 launches 不差减 / M34 MAX 守卫去掉 / M35 单键校验 / M36 静默吞归档 / M38 seq 恒 0 / M39 通知先删后插 / M40 空区间 / M41 不归档，各 1+ 红；M39 首版锚点撞两处已换特异锚；无一用编译红冒充）；`--rerun-tasks` **594 测全绿 / 0 skip**。Redmi 真机复验：清洗值保持（234/1402/1347）、`rebuild_log` 幂等空转不新增、零崩溃。
---

## 补记 · CR-28-R2.1（2026-11-18 10:10，GPT 对 R2 复审：4 P1 修复全部通过，遗留四件事收干净）

GPT 复审结论「4/4 修复成立」，遗留收敛为四件事，逐条完成：

- **① G3 正向迁移测试**：legacy seq=0、目标无对应、备份完整匹配 → 成功迁移给目标来源（此前只测了「不该迁不迁」，补「该迁能迁」）。
- **② G5 `endMs == lo` 边界**：会话 [d 23:00, dNext 00:00) 与次日窗口**无交集**、不归入次日；并修正 R2 用例把「结束于 hi」误标「结束于 lo」的注释（断言本身正确）。
- **③ restore 真中途故障回滚**：SQLite Trigger 在 `hour_usage` 写入时注入 ABORT——OVERWRITE 已写入 `day_summary`(999) 后失败 → **已写入部分整体回滚**、原数据(60)不变（R2 的故障注入在归档前，未证明「部分写入后回滚」，此例补上真路径）。
- **④ `afterRows.maxOf` 空集合 P2**：改 `maxOfOrNull ?: 0`（全零 legacy 且无备份时删行后空集合不再抛异常致无谓回滚）+ 回归用例（不回滚、行删、`rebuilt`）。

验证：`Cr28R2RegressionTest` 14 例；**四处行为变异全部真红**（M42 迁移条件恒假 / M43 相交含边界接触 / M44 restore 撤事务 / M45 maxOf 回退，各 1 红，无编译红冒充）；`--rerun-tasks` **598 测全绿 / 0 skip**。Redmi 真机复验：清洗值保持、`rebuild_log` 幂等空转、零崩溃。
---

## 补记 · A-5 R9 复审：P2 登记（TabScrollGuard 同目标连续点击的守卫所有权冲突）（2026-11-21 16:30）

GPT 复审 R9 结论：**APK 身份对账通过、A5 专属探针六场景日志通过、Redmi 120Hz 六场景门禁与设置恢复记录通过——A-5 真机补验可以收口**；同时发现一条 **P2**：`TabScrollGuard` 对同目标连续点击的所有权判断存在缺陷。

- **位置**：`app/src/main/java/com/screentrace/app/ui/TabScrollGuard.kt`、`app/src/main/java/com/screentrace/app/ui/AppNav.kt`、`app/src/test/java/com/screentrace/app/ui/TabScrollGuardTest.kt`。
- **复现**：R9 v3 的 s5_same（连续两次点击统计页，目标 index 均为 1）。
- **根因**：守卫**只用 target index 区分协程**，没有用点击序号区分所有权；`end(index)` 的 `target != index` 判据在「新旧点击目标相同」时失效。
- **日志实证**（`evidence/a5_pilot_redmi_120hz_v3/s5_same_120hz_r1/logcat.txt`）：seq1 `scrollCancel → scrollFinally(aligned=true)` 并触发 `Route/align=1`——**旧协程抢到对齐权并清掉了新协程刚建立的守卫状态**；seq2 `scrollEnd → scrollFinally(aligned=false)`，**没有 Route/align**——真正的主人对齐权被剥夺。
- **缺陷边界**：**只在「新旧点击目标相同」时穿透**；目标不同的五轮（s2/s3/s4/s6）旧逻辑靠 `target != index` 侥幸挡住——那是「用值判身份」的偶然正确，不是所有权语义的正确表达。
- **危害评估**：s5_same 最终路由仍为统计页、**与视觉一致**（两次同目标、页面本就没离开），属「侥幸无害」；但旧协程在末击刚开始滚动时就执行了 `alignRouteToPager(pagerState.currentPage)`，写入的是**当时**的 Pager 位置——一旦时序叠加到 Pager 已过半翻页，就会写出与视觉不符的路由。**属潜在风险，未在 A-5 六轮中造成可见错误。**
- **本轮处置**：**未修改任何导航业务代码**（本任务不授权改业务）。修复设计（token 所有权机制 + 状态转移表 + 8 条测试设计 + 7 项变异验证 + 最小 diff 计划）见 `docs/CR-30-A5-R10-TabScrollGuard-所有权设计.md`，**待 GPT 评审后再决定是否实施**。
- **同时修正 R9 报告**：§5 表格中 s5_same 的 seq1 路径原写作「End→Finally(true)」（真值为 `scrollCancel→scrollFinally(aligned=true)`）已改正；新增 §5.1 澄清 **`aligned` = `TabScrollGuard.end(index)` 的 Boolean 返回值（守卫对齐所有权），不代表「Pager 当前页与目标页相等」**；新增 §5.2 登记本 P2。
- **未越界**：未执行 Redmi 60Hz、未执行 vivo、未启动完整性能矩阵、未合入 main；A-5 R9 原始证据（v1 旧 APK 轮 / v2 坐标缺陷轮 / v3 身份对账轮）全部保留不覆盖不删除。
---

## 补记 · A-5 R10 设计复审：退回修订（GPT 独立静态复审，2026-11-21 16:45）

GPT **重新从头审查** R10（不沿用上一轮结论），结论：**核心技术方向正确，但设计文档仍需修改，暂不批准进入编码**。上一轮「附条件通过」偏宽松——本轮除 T3 时序错误外，还发现一个 API 与状态管理问题。

### 复审确认成立的结论

- **R9 的 P2 定性保留**：`end(index)` 用 `target` 判断协程是否还是当前所有者——**目标页不是协程身份**；R9 的 s5_same 日志（seq1 `scrollCancel→scrollFinally(aligned=true)`+`Route/align=1`，seq2 `scrollEnd→scrollFinally(aligned=false)`）直接证明了所有权错误。
- **但必须分开陈述两个事实**：① 所有权错误已被日志直接证明；② **用户可见的错误路由尚未被本轮证据证明**（R9 最终页面仍是统计页），不能描述成「已经发生页面错位」。R10 对根因的理解正确。

### 退回的四条 P1

| # | 问题 | 要求 |
|---|---|---|
| P1-1 | **T3 测试时序写错**：描述为「旧协程先结束」却配了「新点击先发生、旧协程后结束」的断言（实为 T2）。**照 v1 描述实现很可能把正常的所有权释放误判成失败** | T3 改为：`begin(1)`→t1 → **`end(1,t1)`=true**（它仍拥有所有权，理应成功释放）→ 状态回 IDLE → 再 `begin(2)`→t2 → `end(2,t2)`=true |
| P1-2 | **`issuedState` 没有保留价值**：自己定义了 `owner != NO_OWNER → owner == issued`，且 `begin()` 总是同时更新两者——`token == owner && token == issued` **在重复检查同一个事实** | 只保留 `nextToken`（生成 token）+ `owner`（当前有效 token），不维护没有独立语义的第二份状态 |
| P1-3 | **多字段状态读取不具备整体一致性**：三个独立 `@Volatile` 字段只保证单字段可见性；`ignoresWrite()` 未在同一把锁里读三个字段，读取期间恰逢更新可能读到 `programmatic=true / target=-1 / owner=NO_OWNER` 这种**非法业务状态** | 改为不可变 `GuardState(programmatic, target, owner)` 快照，`@Volatile` 持有；`begin`/`end` **整体替换**状态对象；`ignoresWrite` **只读取一次**快照 |
| P1-4 | **`end(index, token)` 双校验可能导致守卫永久残留**：token 正确但 index 传错 → 返回 false → 不清状态 → 之后**没有其他协程能再正确释放这个 owner** → 守卫停留在 ACTIVE（比原缺陷更糟） | **token 已足以证明所有权，index 不应再参与所有权判定** → 改为 `fun end(token: Long): Boolean`；token 匹配即结束，不匹配则完全不动状态；`target` 仍存在状态里供 `ignoresWrite()` 用，AppNav 继续用自己的 target 局部变量做日志与路由业务 |

### 测试方案重新裁决

T1 保留 / **T2 保留（核心回归）** / **T3 修正时序** / T4 保留（重要边界）/ T5 保留 / **T6 保留但明确是状态机时序测试** / T7 保留 / **T8 需补充 AppNav 接线审查**。

两条边界声明：

1. **直接调用 begin/end ≠ 真实协程取消测试**——状态机测试可验证取消后的 `finally` 语义，但不等于验证 Compose 中真实协程取消、Pager 动画互相中断与路由回写；
2. **T8 目前没有真正验证「横滑不调用 begin」**——它只证明守卫在 IDLE 时不会忽略回写；是否存在其他调用路径错误地创建 owner，需检查 AppNav 真实调用链；**这部分先做静态调用链审查，不必引入庞大的 Compose UI 测试框架**。

### Q1~Q6 最终裁决

| 项目 | 最终决定 |
|---|---|
| Q1 `ownsTarget` | **删除，token 取代** |
| Q2 `aligned` 日志字段 | **保留**，不破坏 R9 证据兼容性 |
| Q3 增加 owner token 探针 | **暂不增加**，避免扩大探针修改范围 |
| Q4 修复时机 | **A-5 完整矩阵之前** |
| Q5 新增验收条目 | **是，§7-69**（并纠正：§7 当前是 **68 条**，不是 54 条；新增后 69 条） |
| Q6 真机复测 | **代码评审通过后，再单独申请 Redmi** |

### 本轮不放行事项

不允许直接编码、不允许修改 AppNav 导航业务、不允许合入 main、不允许启动完整 A-5 性能矩阵、**不允许擅自启动 Redmi 或 vivo 真机实验**。

### 复审建议（已按此执行）

「这次不要再让施工方只做文字解释，而是要求它把**状态机、API 和测试时序一起修订**。」→ 已出 v2 修订稿（`docs/CR-30-A5-R10-TabScrollGuard-所有权设计.md`），逐条对应见该文档 §9 对照表：T3 时序已改、`issuedState` 已删、状态改为不可变快照、`end` 改为 `end(token)`、§7-69 编号与验收文案已修正、**T8 已补 AppNav 静态调用链审查**（结论：横滑只走回写路径，`LaunchedEffect(mainPage)` 内 `pagerState.currentPage != target` 守卫使其**不进入 begin**，故横滑期间守卫恒 IDLE、跟手不受影响）、T6 已标注为状态机时序测试。**本轮仍未改任何业务代码、未动真机。**
---

## 补记 · A-5 R10 实施落地（2026-11-21 17:10，GPT 附条件通过设计后进入实现）

GPT 对 R10 v2 设计的复审结论：**附条件通过，允许进入实现**（上一轮"退回修订"的 5 项必修已在 v3 全部落实，见设计文档 §10 对照表）。本轮实现**只做 token 化与状态模型**，不碰导航业务逻辑。

### 实现内容（提交 `d9098cf`）

- **所有权 token 化**：`begin(index): Long` 发放单调递增、永不复用的 owner token 并返回；`end(token): Boolean` **只有 token 命中 owner 才可结束守卫并决定是否对齐路由**；token 不匹配时**一个字段都不动**。旧实现用 target 页当协程身份，同目标连击时判据失效（旧协程抢权 + 清掉新协程守卫状态）——此为 P2 根因。
- **不可变状态快照**：`GuardState(programmatic, target, owner)` 整体持有、整体替换；`@Volatile` 只剩**一个** state 字段。消除多字段撕裂读（原可读到「programmatic=true 但 target=-1」这类非法状态）。
- **统一只读快照入口**：新增 `internal val snapshot: GuardState`，测试断言三要素**必须一次取值**（三个派生 getter 各自独立读，连读可能跨状态）。
- **删除 `ownsTarget`**（Q1 裁决）：token 取代，纯函数改为 `ownsToken(owner, token)`。
- **AppNav 两处调用点**（点 tab `:966`、routeScroll `:416`）各自传递 token，**其余逻辑零改动**；Pager 唯一真相源、点 tab 不直接写路由、导航动画与预加载策略、CR-28/Room/备份语义全部未动。

### 测试

- 新增 `TabScrollGuardOwnershipTest` **12 条**（T1~T8 + 4 条补充断言）：不同目标取代 / **同目标取代（P2 核心回归）** / **旧协程先结束（时序已按 v3 修正：`end(t1)` 必须 true 并释放回 IDLE）** / 新协程先结束旧 finally 后执行 / 连续三次同目标 / 取消异常路径 / 正常单次点击 / 横滑期间守卫不建立程序化状态 + 补充断言（token 单调递增、过期 token 快照不变、begin 快照自洽、ignoresWrite 与快照同源）。**每条基于 `guard.snapshot` 一次取值断言三要素。**
- 既有 `TabScrollGuardTest` 适配新 API（6 → 7 条），**`ignoresWrite` 三条口径断言原样保留未放宽**。
- **全量 618 测 / 0 fail / 0 skip**（测试类文件 82 个）。

### 变异验证：M1~M6 + M7′ 共 **7 项可执行变异，全部真红**

| 变异 | 命中 |
|---|---|
| M1 `end` 用**目标页**当协程身份（`ownsToken(snapshot.target.toLong(), token)`） | RED（6 条同时红） |
| M2 `end` 在非 owner 时也清空快照 | RED（T2） |
| M3 `end` 在非 owner 时把 owner 改回旧 token | RED（T2） |
| M4 `begin` 不更新 owner | RED（T7） |
| M5 `begin` 的 token 不递增 | RED（T7） |
| M6 `end` 返回 true 时不清空 programmatic/target | RED（T7） |
| M7′ `begin` 写入的快照不自洽（owner ≠ token） | RED（T7） |

**无编译红冒充、无非行为变异**；每项改坏→跑→确认红→**无条件还原**（实现已还原并与提交一致，grep 复核 `ownsToken(snapshot.owner, token)`）。原 M8 已按 GPT 复审删除（新 API 无 index 参数，恢复它必编译失败）；原 M7 降级为结构性审查项 **S1**（单线程 JVM 单测无法稳定构造撕裂读，**不计入变异命中数**，如实标注）。

### S1 结构性审查（通过）

`@Volatile` 字段计数 = 1（仅 state）；只读出口（programmatic / target / owner / snapshot / ignoresWrite / end）**全部经由同一份快照**；`begin`/`end` **只做整体替换**。接线自查：`begin` 调用点仍只有两处（点 tab、routeScroll），无手势直接触发的入口。

### 本轮新发现并如实登记（OBS-1）

T8 收敛绝对化结论时发现：**「回写触发的路由变化满足 currentPage == target」这一假设在快速横滑下可能不成立**——`LaunchedEffect(mainPage)` 的重启与 Pager 手势进度不是原子过程，快速跨页横滑时可能出现 currentPage 已到第 2 页而 mainPage 仍为第 1 页 → `currentPage != target` 成立 → 进入回滚分支、**建立程序化 owner 并把用户正滑向的页拽回**。**本轮只登记不修**（改导航架构超出授权），实施后真机重点复核：快速连续横滑跨两页，检查 logcat 是否出现 `A5/routeScroll`（该事件只由回滚分支发射）。

### 构建与边界

- `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL**；APK SHA256 `0CC0171E45D29DFAD57C2F9DDC4CFBD6FAD2572BC2C0582BBC9400791BC486E4`（**本轮未安装真机**）。
- 分支 `exp/a5-route-probe` 相对 `origin/main`（`6d07a5c`）：**ahead 21 / behind 0**，merge-base = `6d07a5c`。
- **未合入 main、未启动完整 A-5 矩阵、未占用 Redmi/vivo 真机、未改 Pager 预加载/导航动画/CR-28/Room/备份**；A-5 R9 原始证据三轮全部保留。
- **P2 是否已修复待真机复核确认**（Q6：代码评审通过后另申请 Redmi 跑 s5_same ≥3 轮，确认 seq1 `aligned=false`、seq2 `aligned=true`、`Route/align` 只出现在末击）。**不自行宣布 A-5 通过。**

---

## A-5 R10 真机复核（Redmi 2407FRK8EC · 仅 120Hz）· 2026-11-21

> 放行依据：GPT 复审「R10 代码实现通过 / 整体交付附条件通过 / P2 暂不关闭，等待 Redmi 真机复核」，
> 明确放行范围 = **Redmi `GAQOHMNFQWFUPBQO`（2407FRK8EC）+ 仅 120Hz + 三种场景**；
> 禁止 Redmi 60Hz、vivo 60/120、完整 A-5 矩阵、合入 main、修改 Pager 预加载/导航动画/CR-28 数据架构。
> **vivo 全程未触碰；未启动矩阵；未合 main；未改业务代码。**

### 门禁

| 项 | 结果 |
| --- | --- |
| 设备身份 | Redmi 2407FRK8EC / `GAQOHMNFQWFUPBQO` / 1220×2712（各轮 `run_info.json`） |
| APK 身份 | 装机 base.apk 实算 SHA256 = `0CC0171E45D29DFAD57C2F9DDC4CFBD6FAD2572BC2C0582BBC9400791BC486E4` ≡ 本地构建（实现提交 `d9098cf`），`sha256_match=true`（`gate/apk_identity_r10_build.json`） |
| 刷新率 | 9 个有效轮次全部活动模式 120.00001Hz（modeId=3），`refresh_match_request=true` |
| 恢复 | 每轮恢复设备原值 60/60 并回读校验，`restore_ok=true`（9/9） |
| 证据 | `docs/CR-30-A3-实验补证/evidence/a5_r10_guard_ownership_real/`（9 有效 + 5 无效/对照轮次） |
| SHA | `SHA256SUMS.txt` 重算 362 条，全量校验 **362/362 一致**（0 缺失 / 0 不一致） |

### s5_same（P2 核心验收，3 轮）

| 轮次 | seq1 收尾 | seq2 收尾 | Route/align | A5/routeScroll | 最终页 / 选中 Tab |
| --- | --- | --- | --- | --- | --- |
| r3 | `scrollCancel` → `aligned=false` | `scrollEnd` → `aligned=true` | 1 次，归属 seq2 | 无 | 统计 / 统计 |
| r4 | `scrollEnd` → `aligned=false` | `scrollEnd` → `aligned=true` | 1 次，归属 seq2 | 无 | 统计 / 统计 |
| r5 | `scrollCancel` → `aligned=false` | `scrollEnd` → `aligned=true` | 1 次，归属 seq2 | 无 | 统计 / 统计 |

**3/3 轮满足 GPT 核心判据**。对照基线（R9 旧 APK 同场景）恰好相反：seq1 `aligned=true` + `Route/align=1`、seq2 `aligned=false`（原样保留在 `_invalid_rounds/`）。

### s7_swipe_fast（OBS-1 检查，3 轮）

| 轮次 | currentPage 序列 | selectMain 序列 | A5/routeScroll | 最终页 / 选中 Tab |
| --- | --- | --- | --- | --- |
| r3 | 1 → 2 | 统计 → 日历 | **无** | 日历 / 日历 |
| r4 | 1 → 0 → 1 | 统计 → 今日 → 统计 | **无** | 统计 / 统计 |
| r5 | 1 → 0 → 1 | 统计 → 今日 → 统计 | **无** | 统计 / 统计 |

**3/3 轮未出现 `A5/routeScroll`** → **OBS-1 本轮未命中**（未观测到回滚滚动、未建立程序化 owner）；
路由回写与 Pager 实际停留页逐页一致。**OBS-1 继续开放，不自行关闭。**

### s8_swipe_interrupt（打断程序化滚动，3 轮）

场景：今日页 → 点「日历」（程序化滚动 → 第 2 页）→ 120ms 后立即反向快滑。**判别性**：点击目标页与打断后 Pager 实际停留页不同。

| 轮次 | 打断证据 | currentPage | guardIgnore | Route/align | 最终页 / 选中 Tab |
| --- | --- | --- | --- | --- | --- |
| r2 | `A5/scrollCancel=2#2` | 1 → 0 | `guardIgnore=1` | `align=1` | 今日 / 今日 |
| r3 | `A5/scrollCancel=2#2` | 1 → 2 → 1 | `guardIgnore=1` | `align=2` | 统计 / 统计 |
| r4 | `A5/scrollCancel=2#2` | 1 → 0 | `guardIgnore=1` | `align=1` | 今日 / 今日 |

**3/3 轮**：打断成立、守卫正常收尾（`aligned=true`）、中间页回写被忽略（CR-22 口径）、
**最终路由 = Pager 实际停留页且 ≠ 点击目标页（日历）**；均无 `A5/routeScroll`。

### 本轮新发现并如实登记（OBS-2，只登记不修）

s8 打断瞬间的 `Route/align` 对齐到**中间页**（r2/r4 → align=1=统计；r3 → align=2=日历），
随后才被 Pager 实际停留页纠正（最终 selectMain=今日/统计）。表现为一次短暂的路由/标题抖动，
属 CR-22 同类既有时序（对齐取的是取消瞬间的 `pagerState.currentPage`，可能正停在中间页）。
**本轮只登记不修**（改导航架构超出授权），建议后续单独立项裁决。

### 方法限制（如实登记，不掩盖）

1. 屏宽 1220px + `HorizontalPager` 默认 `snapDistance=atMost(1)` → **单个人类手势无法跨两页**（需 ≥1.5 页宽 ≈1830px）；s7 的「跨两页」用**越界长拖**（单手势连过两个 50% 阈值，观测到 currentPage 1→0→1）+ 一次快滑构造，属**合成手势**，与人手不同。
2. y=1200 的横滑被页面内控件吞掉（0 次页面变化），改用 y=700（实测有效）。
3. adb 主机往返约 150ms/命令会错过 OBS-1 的亚帧窗口 → 被测手势改为**设备侧单条 shell 串行执行**（不回主机），串内嵌 `date +%s.%N` 记录设备端时刻（s8 点击→滑动间隔 76/64/51ms，落在程序化滚动动画窗口内）。
4. **门禁采样时点修正**：本 ROM 面板 idle 态恒 60Hz，只有唤醒屏幕 + App 在前台才切到目标模式 → 门禁 dumpsys 改到**活动态**采样（门禁语义不变，仍要求活动模式唯一映射到目标 fps）。修正前的一轮按门禁判 INVALID、零采样，原样保留在 `_invalid_rounds/`。
5. `adb input tap` 时间戳＝**主机发起命令时刻**，非真实手指触摸时间；录屏仅辅助证据。

### 本轮结论与边界

- **P2 的核心验收判据在真机成立**（s5_same 3/3 轮）。**是否关闭 P2 由 GPT 复审裁决，本轮不自行宣布。**
- **OBS-1 本轮 3 轮未命中，继续开放**；**新登记 OBS-2（只登记不修）**。
- 未合 main、未启动完整 A-5 矩阵、未碰 vivo、未改业务代码；A-5 R9 原始证据三轮全部保留。

---

## CR-30-A5-R11 · 「屏幕长亮后读不到时间」真机诊断（待 GPT 评审定方案）· 2026-11-23

> **本节只登记诊断结论与证据，不含任何业务代码改动、不改任何数据口径、未做历史清洗、未合 main。**
> 分支 `exp/a5-route-probe`；真机 Redmi 2407FRK8EC（`GAQOHMNFQWFUPBQO`），vivo 全程未触碰。
> 诊断全文：`docs/CR-30-A5-R11-长亮屏读不到时间-诊断报告.md`；证据包：
> `docs/CR-30-A3-实验补证/evidence/bug_idle_day_fake_24h/`。

### 触发与事实

负责人真机反馈：**手机插着 USB、开发者模式开了「插着数据线不休眠」，从 2026-11-21（周六）下午起一直没息过屏**，
结果 App「读不到时间」。今日页实测三个互斥数字：

| 显示 | 值 | 真相 | 性质 |
| --- | --- | --- | --- |
| 今日亮屏 | 0 分钟 | ≈10 小时 40 分 | **漏算** |
| 应用构成 共 | 24 小时（屏迹 100%） | ≈10.7 小时 | **错日归属**（值对、日错） |
| 日间 / 夜间 | 0% / 100% | — | **除零兜底** |

设备侧佐证：`screen_session` 里 11-21 17:44→11-22 10:28 有一条 **17.4 小时连续亮屏会话**；
checkin dump 头部 `Currently Active:com.screentrace.app`；今日 `day_summary.screenMin=0`、
`app_usage` 屏迹 = 1440 分钟、`hour_usage` 24 桶全 0。

### 两条故障线

- **线 1（漏算）**：`SCREEN_INTERACTIVE` 只发一次、永不 `SCREEN_NON_INTERACTIVE` 的长亮屏场景下，
  开机事件滚出 MIUI 滚动缓冲后，解析器产不出任何亮屏会话（`UsageDumpParser.kt` 的
  `on?.let { screens.add(it to intervalEvents.last().ms) }` 只能在缓冲还留着事件时累计到拉取时刻）；
  入库侧 `IngestPipeline` 又只对「有新入库区间」的日期重算 → 该日亮屏停在 0。
  应用前台区间同源同断（「应用在前台 ⇒ 屏幕亮着」这条兜底随之失效）。
- **线 2（错日归属）**：checkin 的区间是**约 24 小时、以 ~09:2x 为锚的窗口**，而
  `UsageDumpParser.kt:385` 按「区间结束日」整段归属 → 屏迹那 24 小时全记在 11-23。

### 门禁为何未拦（以及为什么这次**不该**拦）

`IngestSanity.kt:81` 的硬判据是 `> 24h`（本例正好 `=`）；`IngestSanity.kt:117` 在当天 screenMin=0 时
把参照物退回 1440 上限；`UsageDumpParser.kt:468` 的相对判据前置条件 `screenMs > 0` 不成立。
**但长亮屏场景下 1440 本身是真实值——该修归属日，不是丢数据。**
初版诊断把 1440 判成「编出来的假值」**不准确，已在修订版推翻**（初版稿已从 docs 撤下，存 `app/build/probe/`）。

### 最小复现（App 自己的解析器）

合成 24h checkin 区间 + `package=com.screentrace.app totalTimeUsed=86400000`：

    PROBE2 appUsage 2026-11-23 com.screentrace.app totalMs=86400000 min=1440.0
    PROBE2 days=[]           ← 该日没有任何屏幕指标
    PROBE2 sanity dropped=[] ← 门禁未拦

输出与真机库内 2026-11-23 屏迹 = 1440 分钟**完全一致**。

### 本轮落库内容

- 新增 `UsageDumpParserIntervalAttributionTest` **2 条缺陷现状特征化测试**（线 1 / 线 2 各一条）：
  断言的是**现状**而非期望行为，GPT 定方案后必须改写为期望行为（届时应先变红再改）。
- 全量单测 **620 测 / 0 fail / 0 skip**（83 个测试类文件；此前 618 + 新增 2）。
- 证据包 5 个文件（明文 dump 全量 / checkin dump 全量 / 库内切片 / 今日页 UI dump / 探针输出），
  SHA256SUMS 重算并全量校验。

### 待 GPT 裁决（四个问题）

1. **方案 A**「跨拉取维持『屏幕还亮着』的状态」是否可接受（需新增持久化状态，是否允许动 `screen_session` 表结构）。
2. 是否允许改 §2.2「应用时长按区间归属日」口径（**方案 B**）——允许则历史各日应用数值会变、需配套重算。
3. 历史脏数据处置：① 只清可判定越界的行；② 用 `SourceRebuild` 重算 11-18~11-23；③ 先不清。
4. **方案 D**（UI 诚实化：无数据不显示 0 分钟与 0%/100%）是否单独兜底。

**本轮不自行宣布任何口径变更、不自行关闭 OBS-1/P2、不合 main、不碰 vivo。**

## CR-30 阶段 A · 三包设计交付（R11 修复设计 / R10-OBS2 导航取消时序 / 全项目技术债务登记）· 2026-11-23

本轮**只出设计与登记，不改任何生产代码**。基线 `exp/a5-route-probe` @ `ead51d6`，全量单测 **620 测 / 0 fail / 0 skip**。

### 落库清单

| 包 | 文档 | 行数 | 结构 |
|---|---|---|---|
| 工作包一 · R11 | `docs/CR-30-R11-长亮屏与跨日数据可信度修复设计.md` | 1086 | §0 + **§1–§13**（13 节齐）+ 证据清单 |
| 工作包二 · OBS-2 | `docs/CR-30-R10-OBS2-程序化滚动取消与用户手势竞争设计.md` | 963 | §0 + **§1–§10**（10 节齐）+ 证据清单 |
| 工作包三 · 技术债务 | `docs/TECH-DEBT-全项目工程审计登记.md` | 755 | **10 项 × 7 属性** + 处置顺序 + 局限声明 + 证据索引 |

### 关键结论（供评审逐条核对）

**工作包一 · R11**

1. 三条故障线拆开处理：线 1 亮屏漏算（源端滚动缓冲，**不可修复**）+ 线 2 跨日区间错日归属（`UsageDumpParser.kt:385`）+ 线 3 缺分母被渲染成精确百分比（`AppViewModel.kt:1375` + `uikit/AppBreakdown.kt:125-130` 的 `right = 100 - left`）；
2. **明令排除**「上次没看到息屏事件就延长会话」：给出 E1 完整事件证据 / E2 可靠状态证据 / E3 无法确定区间三态证据机，E3 **不得伪造成精确时长**；
3. 线 2 按 GPT 要求对比了**「保留窗口级聚合」（方案甲）vs「允许估算但标记估算」（方案乙）**：倾向甲（标记一旦在某一端丢失即退化为假精确，甲没有这个暴露面）；乙需标记贯穿 7 层方可考虑；
4. `screen_session` 是否改表结构**未自行决定**，给出 4 个选项（不动表 / 加列 / 独立新表 / 纯文本登记）与各自迁移方案、CR-28 冲击面；
5. **明确反对**把 `IngestSanity.kt:81` 的 `> 1440` 改成 `>= 1440`：长亮屏下 1440 是真值，改后会误伤「单日整日使用」的真实数据；改为给出**七态门禁**设计（真实 1440 / 窗口造成的 1440 / 缺屏幕证据 / 部分缺失 / 采集失败 / 暂无数据 / 真实为零）；
6. 覆盖 `IngestSanity` / `IngestPipeline` / `UsageDumpParser` / `AppViewModel` / 日历·统计·周期总结页 / 备份导入导出 **七处**；
7. UI 硬约束：缺分母时**不得**渲染「日间 0% / 夜间 100%」；`dayPercent` 由 `Int` 改为 `Int?`（类型层面诚实化）。

**工作包二 · OBS-2**

1. 核心裁决问题「旧协程被用户手势打断后是否有权 `alignRouteToPager()`」→ 答：**没有无条件对齐权**，须同时满足 token 命中 + 本次非取消 + 目标非中间页 + Pager 已稳定；
2. 用 R10 真机 s8 r2 / r3 / r4 三轮原始 logcat **逐行**给出证据，并如实标注**证据等级为 ADB 合成手势、不是真实手指**；
3. 十个场景各自列出：事件时间线 / `currentPage` / `targetPage` / `isScrollInProgress` / 路由状态 / token owner / 是否允许 `alignRouteToPager()` / 最终页面与选中态；
4. 候选 7 案对比（A1 推荐 / A2 / B / C / D / E / F 禁止项），并逐条说明 **B 的「路由滞留」缺口**、**D 的 `isScrollInProgress` 漏洞**、**C 的 `settledPage` 误用**；
5. 推荐 **A1**（取消让权 + 取消路径专属的稳定后对齐器），决策抽成纯函数 `ScrollAlignPolicy.shouldAlign` 便于单测与变异验证；
6. 明确不改：`HorizontalPager` 架构 / `beyondBoundsPageCount` / 主 tab 动画 / 关动画 / 固定延迟 / 整页 Crossfade；**不重开 R10 原 P2**；
7. 真机复核矩阵 10 项 + 7 条量化判据 + 对 R10 已通过场景的 7 条回归要求 + 五级回滚路径。

**工作包三 · 技术债务**

1. 10 项 × 7 属性齐（当前代码事实 / 风险是否已实际复现 / 优先级与理由 / 影响范围 / 建议验证方式 / 是否需立即修 / 与其他任务的依赖关系）；
2. **TD-01 精确表述**：实测 `./gradlew :app:tasks --all` 输出含 `assembleRelease` / `bundleRelease` → **AGP 默认存在 release 构建类型**，「没显式写 `buildTypes`」**≠**「不存在 Release」；并把「能否构建 / 是否正式签名 / 是否启用 R8 / 规则是否正确 / 混淆后能否启动采集备份恢复」**拆成五项**分别标注实测与否。当前事实：无 `signingConfigs`（→ 未签名）、无 `isMinifyEnabled`（→ R8 关）、无 proguard 文件（→ 无规则）、仓库内无 keystore；
3. **TD-09 是唯一「已复现」项**，且复现的是「**保障缺失**」本身，不是某个具体 bug；
4. **TD-02 / TD-03 只出基准测试设计**，把内存拆成 读取 / String 转换 / 解析 / 入库 / 备份序列化 / WebDAV 上传 **六段**，**未**直接重写为流式；
5. **TD-04**：AIDL 调用已集中在 `ShizukuShell.kt` 一处（符合「集中在适配层」）；明确**不得**未经验证改反射 —— 反射**不解决 1MB binder 事务上限**，读 13MB 仍必须走 fd；
6. **TD-05 / TD-06 不以行数为唯一标准**，给出职责耦合 / 状态所有权 / 依赖方向 / 测试边界四维分析 + 低风险渐进方案（接口化 → 壁纸 → 备份 → 同步）；
7. **TD-07 / TD-08 同时分析** URL 编码与明文 HTTP；TD-08 明确**不在未确认局域网场景前禁止所有 HTTP**，改为「显式提示风险 + 可选信任开关 + 自签证书支持」；
8. **TD-10 分渠道分析** GitHub Releases / F-Droid / Google Play，**不预判 Play 一定通过或拒绝**；并指出 Manifest 里两段**自相矛盾**的历史注释。

### 本轮边界（明确声明）

- **未改任何生产代码 / Gradle / Manifest / Room schema / 备份格式 / 导航行为**；
- **未改需求文档** —— 本轮无口径变更、§7 无新增条目。GPT 允许的范围只列了「设计文档、技术债务登记、测试方案及证据索引」，故需求文档未动；如需一并登记，请 GPT / 负责人指示；
- **未合 main、未启动完整 A-5 矩阵、未占用 Redmi、未连接 vivo**；
- 三份文档**只有设计与登记**，不含任何已完成的修复；
- 技术债务 10 项中 **9 项标「未复现」**，唯一「已复现」项（TD-09）复现的是保障缺失；
- R11 与 OBS-2 的真机复验方案**均已写入设计文档但未执行**（执行需 Redmi 放行）。

**本轮到此停止，等 GPT 评审。**


---

## CR-30 阶段 A · GPT 评审整改（v2）· 2026-11-23

> **本条只登记文档整改，未改任何业务代码。** 评审只写 `docs/CODE_REVIEW.md`，本节为文末补记。

### 评审输入与结论

| 工作包 | GPT 结论 | 本轮处置 |
|---|---|---|
| R11 长亮屏与跨日数据可信度 | **设计需要修订，暂不允许编码** | 已按 §3.1–§3.7 逐条整改 → v2 |
| R10-OBS2 导航取消时序 | **A1 方向通过**，补齐条件后允许独立实施 | 已补齐四项条件 → v2 |
| 全项目技术债务 | 登记通过，**TD-10 需要修订** | **只改 TD-10**，其余 9 项一字未改 |

### 落库清单

| 文件 | 状态 | 行数 |
|---|---|---|
| `docs/CR-30-R11-长亮屏与跨日数据可信度修复设计.md` | 修订为 **v2** | 1392 |
| `docs/CR-30-R10-OBS2-程序化滚动取消与用户手势竞争设计.md` | 修订为 **v2** | 1645 |
| `docs/TECH-DEBT-全项目工程审计登记.md` | 修订 **TD-10**（v2） | 850 |
| `docs/CR-30-阶段A-未决问题清单.md` | **新增** | 313 |

### R11 整改要点（逐条对应 GPT §3）

| GPT 条目 | 整改 | 位置 |
|---|---|---|
| §3.1 E3 上下界语义 | E3 降为「仅锚点」，**不产出任何时长**（连下界都推不出 —— 10:00 亮过 / 11:00 拉取之间可能息过又亮过）；**删除** `UPPER_BOUND` / `LOWER_BOUND` 这类「区间不等式」作为证据类型；D3 暂不允许显示「≥ N 分钟」；新增硬红线 RL-10 | §0.3 / §4.1 / §4.1.1 |
| §3.2 Activity 证据 | 降级为**辅助证据**；N4 未验证前不得生成精确亮屏区间；**禁止**用应用时长反推亮屏时长；列出「可联合证明的系统状态清单（待验证）」 | §4.2 |
| §3.3 质量模型 | 拆为**采集状态**（OK / EMPTY / PARTIAL / FAILED / UNAVAILABLE）× **数据质量**（EXACT / WINDOW / ANCHOR_ONLY / PARTIAL / UNKNOWN）两维度；质量**按指标**标注而非整行；`PARTIAL` 的已观测值可展示，但**不进日均分母、不参与极值** | §5.1–§5.10 |
| §3.4 D1–D8 | 按 GPT 建议方向更新，并明确区分「**已获方向性同意**」与「**仍待确认的具体细节**」，不得写成已批准事实 | §4.6 |
| §3.5 采样时间线 | **新增 §3.5**：逐条锚点 + **三处「无法关联」** | §3.5 |
| §3.6 跨日窗口 | 七页逐页梳理 + 状态 → 文案对照表 | §10 |
| §3.7 CR-28 保护 | **F1–F9 冻结口径逐条** + 每处改动逐条影响 + 新证据表**不得绕过约束**的 7 条断言 | §8.1–§8.6 |

### §3.5 采样时间线的关键结论（本轮新查证）

| 事实 | 依据 |
|---|---|
| pull_log 169 = **10:31:38**，是**唯一**一条 `checkin+data` 记录 | 库内 `pull_log` |
| DB 副本采集时刻 = **10:40:44**（库内最新 pull_log = 171 @ 10:38:49） | 文件 mtime |
| 明文 dump 采集时刻 = **10:41** | dump 内 `timeRange="2026/11/22 10:41 – 2026/11/23 10:41"` + 末条事件 `10:40:01` |
| UI XML 落盘 = **10:54:21**，比 DB 副本晚 **13 分 37 秒** | 文件 mtime |
| 归档的 checkin dump **内容无任何时间锚点**（0 条 `time="…"`、无 `timeRange`、无 `beginTime`） | 该文件内容 |
| UI XML 显示「亮屏 10 分钟 / 最长连续 9 分钟」，而 DB 副本 11-23 **只有一个来源**、`SUM(screenMin)=0`、`screen_session` 当日仅 3 秒会话（10:38:44→10:38:47） | UI XML + DB 查询 |
| → **两者不是同一采样点，无法证明同刻** | 上两行 |
| **产生 1440 分钟的那份 dump 不在归档里** → 1440 的归属只由 pull_log 笔记 + 探针推断支撑 | 归档清单 + `pull_log`（登记为 N7） |

### OBS-2 整改要点（逐条对应 GPT §4）

| GPT 条目 | 整改 | 位置 |
|---|---|---|
| §4.1 取消原因 | **三分类** `gesture` / `superseded` / `scope` + 判定顺序（作用域优先 → token 不匹配 → 用户拖动 → 未能识别**保守当 gesture**，理由：两方向的错误代价不对称）+ `CancelCauseResolver` 纯函数 | §1.5 / §6.2 要点 1 |
| §4.2 对齐器所有权 | **9 条所有权边界**（OW-1~OW-9）+ **代际机制**（`gen++` 于新程序化滚动与用户手势接管；对齐器执行前比对代际，不一致即失效并记 `alignSkip=stale-generation`） | §6.3 |
| §4.3 统一入口 | **唯一写入口 `commitRouteFromPager()`**（幂等 / 只写路由不写 Pager / 不维护影子路由状态 / 来源标记只进打点）；**唯一待架构裁决 A-01**：`snapshotFlow` 回写是否也接入 | §6.4 |
| §4.4 异步竞态测试 | **新增 10 个场景 R1–R10**（每条含初始状态 / 事件顺序 / Token 所有权 / Pager 状态 / 预期路由 / 是否允许写入 / **中间态验证** / 日志证据）+ 共性断言 A1–A7；§7.4 给出**可注入对齐器 + 虚拟时间**的确定性实现方式；§7.6 给出三条**必须真红**的异步变异 | §4.11 / §4.12 / §7.4 / §7.6 |
| §4.5 M1–M10 | **完整保留**；另加 M11–M13（代际 / 作用域取消）；§8.5 强化「真实手指优先」与证据等级标注（每轮 `run_info.json` 必须写 `gesture_kind`） | §8 |

### TD-10 整改要点（逐条对应 GPT §5）

| GPT 条目 | 整改 |
|---|---|
| §5.1 Google Play | 「中高匹配度 / 不是必然被拒」**下调**为「**发行资格尚未确认** + **较高审核风险**」；新增两项**必做分析**（举证「广泛应用可见性是核心功能所必需的」+ 分析「更窄范围查询是否可以满足需求」）；明确**不得**因「屏幕使用时间统计工具」身份自动认定合规；**不预判**通过或拒绝 |
| §5.2 F-Droid | 由「⚠️ 可行」改为 **B1–B7 逐项核查**（自由软件许可 / 依赖库来源 / 构建可复现性 / 第三方服务依赖 / Shizuku 依赖及发行方式 / Android 权限与实际功能关系 / F-Droid 当前纳入政策）；**自身许可 Apache-2.0 已核实**（仓库根目录 `LICENSE`） |
| §5.2 风险分离 | 新增「两类审核风险的分离声明」：**Play 通过 ≠ F-Droid 可纳入**，两条渠道分别取证、分别下结论；另列签名 / 产物风险与国内合规风险为正交项 |
| §5.3 其他技术债 | **TD-01 ~ TD-09 一字未改**，仍只登记不实施 |

### 本轮边界（明确声明）

- **未改任何生产代码 / Gradle / Manifest / Room schema / 备份格式 / 导航行为**；
- **未改需求文档正文** —— 只产出**差异清单**（DR-01~DR-10，见未决清单 §2.2），等裁决后一次性落笔；**§7 仍 42 条**；
- **未合 main、未启动真机矩阵、未占用 Redmi、未连接 vivo**；
- **未跑单测** —— 本轮不允许改生产代码，测试无对象；只做了文档链接 / 格式 / 引用 / 差异 / 仓库状态检查；
- 三份文档都只是**设计**，不含任何已完成的修复。

### 停止

**本轮到此停止，等 GPT 重新评审。**


---

## CR-30 阶段 A · v2.1 编码前定稿（GPT 阶段 A v2 复审）· 2026-11-23

> **本条只登记设计定稿，未改任何业务代码。** 评审只写 `docs/CODE_REVIEW.md`，本节为**文末补记**；**不改动任何历史评审结论**。

### 本轮目标与结论

| 工作包 | GPT v2 要求 | 本轮处置 | 状态 |
|---|---|---|---|
| **R11** | R11-1 计数器证据等级 / R11-2 `EMPTY` 判定边界 / R11-3 窗口级六场景 / R11-4 D1–D10 状态 | 四项**全部落实** | **达到编码前终审条件** |
| **OBS-2** | OBS-2-1 α 定为唯一 / OBS-2-2 日志字段语义 / OBS-2-3 三分类行为表 / OBS-2-4 异步测试 / OBS-2-5 证据纪律 | 五项**全部落实** | **达到编码前终审条件** |
| **未决清单** | 同步 + 状态化 | 加状态标记；**A-01 / A-03 关闭**；**A-02 保留** | 已同步 |

### 落库清单

| 文件 | 状态 | 行数 |
|---|---|---|
| `docs/CR-30-R11-长亮屏与跨日数据可信度修复设计.md` | **v2.1** | 1547 |
| `docs/CR-30-R10-OBS2-程序化滚动取消与用户手势竞争设计.md` | **v2.1** | 1875 |
| `docs/CR-30-阶段A-未决问题清单.md` | **v2.1** | 351 |
| `docs/TECH-DEBT-全项目工程审计登记.md` | **本轮未改** | 863 |

### R11 四项的落地位置（逐条对应 GPT v2.1）

| GPT v2.1 条目 | 落地 | 位置 |
|---|---|---|
| **R11-1 累计计数器的证据等级** | 新增 **W1 窗口级累计增量**为与 E1/E2/E3 **并列的第四类**，**明确排除于 E1/E2/E3 之外**；W-1~W-7 七条硬性规定（只能证明两采样点间的累计变化 / 不能证明具体时间段 / 不得转成 `screen_session` 区间 / 不得参与精确 UNION / 归入独立窗口级聚合 / 六类降级一律「不产出数值」 / 不得当单日精确亮屏时长，跨日窗口尤甚）；**RL-2 扩为四类证据**、新增 **RL-14** | R11 §4.1 / §4.1.3 / §4.2.4 / §4.3 / §0.3 |
| **R11-2 `EMPTY` 判定边界** | 四类情形区分（真实空数据 / 暂无证据 / 采集失败 / 通道不可用）+ 四个必要前提 P-1~P-4（**P-2 窗口完整、P-3 来源可信当前均无法证明** → 只能是「弱 EMPTY」）+ 逐项回答「能否用 `EMPTY`」+ 三件事必须能区分（没有记录 ≠ 记录为 0 ≠ 没有使用）+ 红线 RL-14 | R11 §5.2.1 / §0.3 |
| **R11-3 窗口级跨日数据的场景规则** | 新增 **§10.6**：S-1 首页今日统计 / S-2 日历日统计 / S-3 应用排行 / S-4 应用详情 / S-5 周月年总结 / S-6 数据导出与备份恢复，每场景逐条给出「是否展示 / 展示形态 / 是否排除 / 提示文案 / 明确禁止」；外加贯穿三场景的「不完整日期不进日均分母」规则与跨场景底线 | R11 §10.6 |
| **R11-4 D1–D10 决策状态** | §4.6 重写为 **D1–D10 统一状态表**，每行给出 ✅/🟡/⛔、已确认方向、仍待确认细节、**需要谁决策**、**缺什么证据或材料**、**影响模块**；GPT 点名的六个补充问题标 ◆；§13.2 改为只列仍然开放的条目并指向 §4.6 | R11 §4.6 / §13.2 |

### OBS-2 五项的落地位置（逐条对应 GPT v2.1）

| GPT v2.1 条目 | 落地 | 位置 |
|---|---|---|
| **OBS-2-1 统一路由入口 α** | **α 定为唯一方案**：三个调用点（`snapshotFlow` 回写 / `finally` 对齐 / 稳定后对齐器）全部经 `commitRouteFromPager()`；**写入函数只有 1 个，触发源有 3 个**；α-1~α-8 八条硬性约束；**CR-17 逐条冲突检查结论 = 无冲突，CR-17 不需要改**（A-01 关闭、A-03 关闭）；CB-1~CB-6 六条禁止 | OBS-2 §6.4.3 / §6.4.3.1 / §6.4.4 / §0.6 |
| **OBS-2-2 日志字段语义** | **六个不可混用概念 C1–C6**（守卫所有权 / 滚动是否正常结束 / Pager 是否真正稳定 / `currentPage` 是否等于 `settledPage` / 代际是否有效 / 是否实际提交了路由）；**`aligned` 的真实含义 = C1 守卫所有权，不是「对齐成功」**（取消路径上同样为 true，这正是 OBS-2 的成因）；历史字段真实含义与兼容关系逐条说明（**没有任何历史字段的语义被改变**）；打点格式定稿 + 统计口径表 | OBS-2 §6.6.1 / §6.6.2 / §6.6.3 |
| **OBS-2-3 三类取消原因** | **三分类保留**；行为表定稿（GESTURE 派发对齐器且只能写最终 `settledPage` / SUPERSEDED 旧对齐器必须失效、路由由新操作决定 / SCOPE 不派发任何后台对齐任务、不允许旧作用域写入）；每条给出依据；**保守处理的明确依据**（两个方向的错误代价不对称 → 倒向补偿）；**`userDragging` 的 API 尚未验证**的如实声明（未验证前不得声称已实现） | OBS-2 §1.5.5 / §1.5.2 / §6.2 |
| **OBS-2-4 异步测试** | §4.11 重写为 **12 个场景**，每个含 **7 个必答字段**（初始状态 / 操作顺序 / 协程挂起点 / 状态变化 / 恢复时的有效性检查 / 预期路由写入次数 / 预期最终状态）+ 中间态断言 + 日志证据；新增 **R11**（端到端最终态）与 **R12**（组合不卡死）；§4.12 断言增至 **A1–A11**（新增 A8 取消原因互斥、A9 写入全部经统一入口、A10 未派发不得产生 skip、A11 组合不卡死） | OBS-2 §4.11 / §4.12 |
| **OBS-2-5 真机证据纪律** | **E-1~E-10** 十条定稿（合成手势不得冒充真实手指 / `run_info.json` 必须记 `gesture_kind` / 无效轮次必须保留 / 每场景 ≥ 3 有效轮次 / 原始 logcat+UI XML+视频+设备信息+SHA256SUMS / Redmi 需放行 / vivo 禁止连接 / **不得把 R10 旧证据写成修复后的验证证据** / 修复前后同场景逐行对照 / `capture_anim.py --slow` 后须重启 App）。**本轮不执行真机测试** | OBS-2 §8.6 |

### 未决清单同步结果

| 编号 | 变化 | 依据 |
|---|---|---|
| **A-01** | 待裁决 → **已裁决（α）** | GPT v2.1 OBS-2-1 |
| **A-03** | 待裁决 → **已裁决（幂等覆盖回写）** | GPT v2.1 α-3 |
| **A-02** | **保留**为 ⚪ 待产品决策 | 前置 = F-09 编译验证 |
| **D-01 / D-03 / D-10（前半）** | → ✅ 已裁决 | GPT v2.1 R11-1 / R11-3 |
| **D-02 / D-04 ~ D-09** | 🟡 待裁决 | 状态与决策人见 R11 §4.6 |
| **F-01 ~ F-10 / B-01 ~ B-09** | **全部保留为待取证** | — |

> **没有删除任何尚未解决的问题**；每一条状态变化都在未决清单 §1.5 留痕。

### 本轮边界（明确声明）

- **未改任何生产 Kotlin / Gradle / Manifest / Room schema / 数据库迁移 / 备份格式**；
- **未改导航逻辑、数据解析逻辑、入库逻辑**；
- **未改 CR-28 已冻结的数据口径**；**未改需求文档正文**（差异清单 DR-01~DR-10 仍待裁决后落笔）；
- **未清理、重建或覆盖任何历史数据**；
- **未合并 main**、**未启动真机测试矩阵**、**未占用 Redmi**、**未连接 vivo**；
- **未跑单测** —— 本轮不允许改生产代码，测试无对象；只做了文档链接 / 格式 / 引用 / 差异 / 仓库状态检查；
- 两份设计文档都只是**设计**，**不构成编码授权**。

### 停止

**本轮到此停止，等待 GPT 编码前终审。**
---

## CR-30 阶段 A · v2.2 编码前定稿（GPT 阶段 A v2.2 复审）· 2026-11-23

> **本条只登记设计定稿，未改任何业务代码。** 评审只写 `docs/CODE_REVIEW.md`，本节为**文末补记**；**不改动任何历史评审结论**。
> **审查基线**：分支 `exp/a5-route-probe` @ **`71a230b`**（v2.1 编码前定稿后 HEAD）；**最后核对日期 2026-11-23**。

### 本轮目标与结论

| 工作包 | GPT v2.2 要求 | 本轮处置 | 状态 |
|---|---|---|---|
| **R11** | R11-01 清除 W1/E2 混用 / R11-02 W1 三层语义 + 九类异常 / R11-03 九个消费边界 / R11-04 保持 D1–D10 | 四项**全部落实** | **达到编码前定稿条件** |
| **OBS-2** | OBS2-01 两段式拆分 / OBS2-02 UNKNOWN 补偿边界 / OBS2-03 确定性竞态测试 / OBS2-04 写入历史断言 / OBS2-05 F-09/A-02/F-10 保持开放 | 五项**全部落实** | **达到编码前定稿条件** |
| **未决清单** | 同步 + 基线 SHA + 日期 | 基线更新为 `71a230b`；日期 2026-11-23；**F-09 / F-10 / A-02 保持开放**；D-01~D-10 状态逐字未改 | 已同步 |

### 落库清单

| 文件 | 状态 | 行数 |
|---|---|---|
| `docs/CR-30-R11-长亮屏与跨日数据可信度修复设计.md` | **v2.2** | 1675 |
| `docs/CR-30-R10-OBS2-程序化滚动取消与用户手势竞争设计.md` | **v2.2** | 2154 |
| `docs/CR-30-阶段A-未决问题清单.md` | **v2.2** | 372 |
| `docs/TECH-DEBT-全项目工程审计登记.md` | **本轮未改** | 863 |
### R11 四项的落地位置（逐条对应 GPT v2.2）

| GPT v2.2 条目 | 落地 | 位置 |
|---|---|---|
| **R11-01 彻底清除 W1 / E2 语义混用** | **新增 §4.1.4**（W1 与 E2 定位对照表 + **G-1~G-7 七条统一规则**，其中 **G-3 为硬约束：无论 N6 是否通过，W1 永不升级为 E2**）；**改正全文 6 处真实混用**（§0.2 线 1 / §1.2 / §4.2.3 / §4.7 第 2 步 / §11.4 / §13.4）；§3.4 补「W1 不在证据状态机内」注记；§6.3 `evidenceType` 枚举补 `WINDOW(W1)` 并说明 4 值互斥；§5.3 / §5.10 / §8.5 / §11.1 补 W1 硬边界与断言（U14~U16） | R11 §4.1.4 / §0.4.2 |
| **R11-02 W1 三层语义 + 九类异常** | **§4.1.3 重写**：九类异常 **X-1~X-9**（Δ 与 E1 矛盾 / 重置 / 回绕 / 重启 / 采样间隔过长 / 采样缺失 / 设备身份变化 / 采集失败 / 连续性不可确定）+ 前置条件 X-10 / X-11，每类给出判定依据 + 降级动作 + **是否产生 C 层**；写入红线「**标 UNKNOWN 不是免责**」；**新增 §4.1.5**：A 原始读数 / B 计算结果 / C 可用窗口级数值三层语义 + **V-1~V-6** 校验项（B→C 必须全通过，C 层是唯一允许进入展示与统计的层） | R11 §4.1.3 / §4.1.5 |
| **R11-03 W1 九个消费边界** | **新增 §4.1.6**：**W-S1~W-S10** 逐场景给出「是否消费 / 展示形态 / 是否参与总量统计 / 是否需要部分数据或估算提示 / 是否可能与 E1/E2 重复计算」；结论为**九个场景一律不消费 W1**，仅 W-S9（备份恢复，仅作记录）与 W-S10（窗口级提示行）可消费 C 层；**防重复计算三条硬约束**（永不与 E1/E2 相加 / 已覆盖全窗则不展示 / 不得补齐差额） | R11 §4.1.6 |
| **R11-04 保持 D1–D10 边界** | **§4.6 决策表逐字未改**（D1 ✅ / D2 🟡 / D3 ✅ / D4 🟡 / D5 🟡 / D6 🟡 / D7 🟡 / D8 🟡 / D9 ⛔ / D10 🟡）；新增的 §4.1.4~§4.1.6 均标注为**设计约束**，**不构成对 D1 / D2 的新裁决** | R11 §4.6 / §0.4.2 |
### OBS-2 五项的落地位置（逐条对应 GPT v2.2）

| GPT v2.2 条目 | 落地 | 位置 |
|---|---|---|
| **OBS2-01 拆分 `observedCause` 与 `effectiveAction`** | **两段式**：`observedCause`（观测事实 `NONE`/`GESTURE`/`SUPERSEDED`/`SCOPE`/`UNKNOWN`，**仅日志与取证**）与 `effectiveAction`（实际动作 `NO_ACTION`/`GESTURE_COMPENSATE`/`CONSERVATIVE_COMPENSATE`/`NO_ALIGN_SUPERSEDED`/`NO_ALIGN_SCOPE`，**唯一行为判据**）；**UNKNOWN 记为 `unknown` 而不再伪装成 `GESTURE`**，映射为 `CONSERVATIVE_COMPENSATE`（**补偿**）—— 修掉 v2.1「保守补偿却触发不了补偿」的自相矛盾；**OW-1 改写为读 `effectiveAction`**，并同步清理 §1.5.3 判定链 / §1.5.4 打点 / §1.5.5 行为表 / §6.2 要点 1 代码 / §6.4.3 调用点 / §6.5 纯函数表 / §6.6.1 C1–C7 / §6.6.3 格式 / §7.1 单测表 / §7.6 变异表 / §4.11 / §4.12 / §8.2~§8.4 真机判据 | OBS-2 §1.5.3 / §1.5.4 / §1.5.5 / §6.2 / §6.3.2 / §6.4.3 / §6.5 / §6.6.1 / §6.6.3 / §7.1 / §7.6 / §8.2–§8.4 |
| **OBS2-02 UNKNOWN 补偿的安全边界与退出条件** | **新增 §6.3.5**：**边界一 · 7 项安全检查**（等 Pager 稳定 / 查 `currentPage` 与 `settledPage` / 查 generation / 经 `commitRouteFromPager()` / 保留幂等 / 不写中间页 / 不写非落定值）；**边界二 · 7 条退出条件 EX-1~EX-7**（新手势接管 / 新导航接管 / 代际过期 / 作用域消失 / Pager 再次不稳定 / 幂等命中 / 已有更新的写入）；**边界三 · 能保证与不能保证什么**（F-09 不可用时的如实声明）；**不破坏** R10 Token 所有权 / 三分类 / Pager 唯一真相源 / CR-17 / 已关闭的 R10 P2 | OBS-2 §6.3.5 |
| **OBS2-03 R12 改造成确定性竞态测试** | **§7.4 重写**：**RT-1~RT-6**（确定性 / 可控挂起点 / 可控 Pager / 提交记录器 / **不为制造时序引入生产复杂度** / 断言中间态）；`runTest` + `TestScheduler.advanceUntilIdle()` + `CompletableDeferred` **事件屏障** + `FakePagerProbe`；**§7.4.4 给出 R12 的七步确定性编排**；**§7.4.5 补齐 R11 / R12**；**TD-4 可重复性**（连跑 10 次结果一致）+ **TD-5 不得污染生产代码**（评审口径：`testOnly` / `debug` 条件分支即不通过） | OBS-2 §7.4 |
| **OBS2-04 R12 必须断言路由写入历史** | **新增 `RouteWriteRecorder`（§7.4.3）**，记录每次提交尝试（seq / page / src / **genAtWrite** / **genExpected** / **genValid** / **currentPage** / **settledPage** / **settledMatch** / **viaUnifiedEntry** / **wrote**）；**§7.4.4 的 W-1~W-10 路由写入历史断言**（提交次数恰好 1 / 不得 0 次 / 不得多于 1 次 / **每次提交 generation 均有效** / **不存在旧 generation 写入** / 落定一致 / 不得写中间页 / **必须经统一入口** / 最终结果 == Pager 停留页 / **不得有非法中间态写入**）；**§4.12 新增 A16**；**§6.6.3 补真机侧核对来源**；**§7.6 新增 4 条必须变红的变异** | OBS-2 §7.4.3 / §7.4.4 / §4.12 / §6.6.3 / §7.6 |
| **OBS2-05 F-09 / A-02 / F-10 不得提前关闭** | **三项全部保持开放**（F-09 🔵 待**编译验证** / F-10 🔵 待真机 / A-02 ⚪ 待产品决策）；**`userDragging` 可用性以实际编译验证为准**，未验证前不得声称已实现；**§6.3.5 边界三**给出 F-09 不可用时的降级策略与**能 / 不能保证什么**；**两段式拆分不依赖 F-09 结论**（不可用时 `observedCause=unknown` → `effectiveAction=conservative_compensate`，设计路径完全不变） | OBS-2 §1.5.5 / §6.3.5 / 未决清单 §1.3 |
### 未决清单同步结果

| 编号 | 变化 | 依据 |
|---|---|---|
| **F-09 / F-10 / A-02** | **保持开放**（未关闭） | GPT v2.2 OBS2-05 |
| **D-01 ~ D-10** | **状态逐字未改**（未把待裁决改成已批准） | GPT v2.2 R11-04 |
| **F-06** | 仍待取证，但**证据等级与降级规则已定**（W1，**永不升级为 E2**） | GPT v2.2 R11-01 / R11-02 |
| **A-01 / A-03** | 维持 v2.1 的 ✅ 已裁决 | — |
| **F-01 ~ F-10 / B-01 ~ B-09** | **全部保留为待取证** | — |

> **没有删除任何尚未解决的问题**；每一条状态变化都在未决清单 §1.5 留痕。

### 本轮边界（明确声明）

- **未改任何生产 Kotlin / Gradle / Manifest / Room schema / 数据库迁移 / 备份格式**；
- **未改导航逻辑、数据解析逻辑、入库逻辑**；
- **未改 CR-28 已冻结的数据口径**；**未改需求文档正文**（差异清单 DR-01~DR-10 仍待裁决后落笔）；
- **未清理、重建或覆盖任何历史数据**；
- **未合并 main**、**未启动真机测试矩阵**、**未占用 Redmi**、**未连接 vivo**；
- **未跑单测** —— 本轮不允许改生产代码，测试无对象；只做了文档链接 / 格式 / 引用 / 差异 / 仓库状态检查；
- 两份设计文档都只是**设计**，**不构成编码授权**。

### 停止

**本轮到此停止，等待 GPT 编码前终审。**
---

## CR-30 阶段 A · v2.3 R12 测试契约可信度修复（GPT 阶段 A v2.3 复审）· 2026-11-23

> **本条只登记设计整改，未改任何业务代码，也未改任何测试源码。** 评审只写 `docs/CODE_REVIEW.md`，本节为**文末补记**；**不改动任何历史评审结论**。
> **审查基线**：分支 `exp/a5-route-probe` @ **`b9490e3`**（v2.2 编码前定稿后 HEAD）；**最后核对日期 2026-11-23**。

### 本轮目标与结论

| 工作包 | GPT v2.3 要求 | 本轮处置 | 状态 |
|---|---|---|---|
| **R11** | 本轮不动 | **一个字未改** | **状态保持设计通过** |
| **OBS-2** | OBS2-01 ~ OBS2-06 六项整改（**只针对 R12 测试契约可信度**） | 六项**全部落实** | **R12 仍为 ⛔ 未通过复审**（其余场景、原则与日志语义不变） |
| **TD-10 / TECH-DEBT** | 本轮不动 | **一个字未改** | 不变 |
| **未决清单** | 同步 + 基线 SHA + 日期 + **新增 A-04** | 基线 `b9490e3`；日期 2026-11-23；**新增 A-04 ⚪**；**F-09 / F-10 / A-02 保持开放** | 已同步 |

### 落库清单

| 文件 | 状态 | 行数 |
|---|---|---|
| `docs/CR-30-R10-OBS2-程序化滚动取消与用户手势竞争设计.md` | **v2.3** | 2665 |
| `docs/CR-30-阶段A-未决问题清单.md` | **v2.3** | 390 |
| `docs/CR-30-R11-长亮屏与跨日数据可信度修复设计.md` | **本轮未改** | 1675 |
| `docs/TECH-DEBT-全项目工程审计登记.md` | **本轮未改** | 863 |
| `docs/CODE_REVIEW.md` | **v2.3 补记（本节）** | — |
| `docs/TODO.md` | **v2.3 同步** | — |

### OBS-2 六项的落地位置（逐条对应 GPT v2.3）

| GPT v2.3 条目 | 落地 | 位置 |
|---|---|---|
| **OBS2-01 记录器不得伪造测试结果** | **§7.4.3 整节重写**：`RouteWriteRecorder` → **`RouteAudit`**；**删除** `viaUnifiedEntry = true` / `wrote = true` 两行硬编码；改为 **5 类事件**（`EntryInvocation` / `CommitAttempt` / `CommitAccepted` / `CommitSkipped` / `StateMutation`）+ **三通道**（C-a 生产代码上报 / C-b 独立状态观察 `viewModel.page` StateFlow / C-c 归因）；**`viaUnifiedEntry` 降级为归因结论**（`StateMutation` 必须有同目标同代际的 `CommitAccepted` 背书），**`wrote` 由独立观察者得出**（绕行写入 → `unattributedMutations` → W-8 失败）；**「某次提交没有发生状态变化」由两条交叉证据证明**（提交被拒 + 状态未变），而不是「没有记录」；记录器**只观察不干预**（TD-6） | OBS-2 §7.4.3 / §7.4.6 |
| **OBS2-02 生产导航链路统一入口集成测试** | **§7.4.4 新增三层分层**：**L1 对齐器单测** / **L2 统一入口单测** / **L3 生产导航链路集成测试**（**L1 / L2 不得替代 L3**，TD-7）；L3 覆盖 **`snapshotFlow` / 正常 `finally` / 稳定后对齐器**三个来源**实际经过同一生产统一入口**；列出**六类必发现错误**（绕行入口 / 直调 `selectMain()` / 绕过 generation 校验 / 绕过 Pager 稳定性校验 / 重复提交导致重复状态变化 / 旧协程在新导航接管后写入）；**§6.4.5 给出最小可测试性改造 P1–P4 方案**（P1 审计钩子 `RouteAuditSink` / P2 手势信号注入 / P3 代际来源 / P4 状态可观察）—— **只写方案，本轮不改代码**；**不批准 P1 则 L3 不成立**（登记 A-04） | OBS-2 §7.4.4 / §6.4.5 / 未决清单 §1.3 A-04 |
| **OBS2-03 R12 必须覆盖真实动画取消路径** | **§4.11 R12 拆成两个独立场景**：**R12-A**（`reduceMotion=true` / `scrollToPage()` 瞬时滚动 / 快速点击 / 手势接管 / 路由最终一致 / 旧代际不能写入）与 **R12-B**（正常动画 / `animateScrollToPage()` / 可控挂起点暂停 / 注入第二次点击 / 取消旧动画协程 / 注入用户手势 / **按明确顺序恢复** 旧协程 → 新协程 → 对齐器 / 记录全部提交尝试 / 检查错误中间态写入）；**§7.4.5 给出两场景七步编排 + 等价性声明表**；**只用 `runTest` / `TestDispatcher` / `CompletableDeferred` / 可控 fake Pager / 明确挂起点与屏障**，**禁止 `Thread.sleep` 与真实时间延迟**（TD-3 / TD-4）；**两个场景都必须在 L3 跑** | OBS-2 §4.11 R12 / §7.4.5 |
| **OBS2-04 统一 Pager 不稳定时的等待与退出规则** | **§6.3.5.1 成为唯一权威**：**7 条件状态转移表**（Pager 正在滚动 → **继续等待**、不允许提交、超限 `settle-timeout`；`currentPage != settledPage` → **继续等待**；generation 失效 → `stale-generation` / `gesture-takeover` / `superseded`；新手势接管 → `gesture-takeover`；新导航接管 → `superseded`；等待超时 → `settle-timeout`；scope 取消 → `scope-cancelled`）；**`not-stable` 降级为诊断事件、不再是退出原因**；**§6.2 要点 2 伪代码改为等待循环 + `withTimeoutOrNull` + 固定判定顺序（scope → 代际 → 超时）**；**§6.3.6.4 给出完整退出原因表**；正文 / 伪代码 / R6 / R7 / R12 预期**逐字一致** | OBS-2 §6.3.5 / §6.2 要点 2 / §6.3.6.4 / §4.11 R6–R7 |
| **OBS2-05 三种触发源各自的 generation 校验** | **§6.3.6 新增**：**三触发源对照表**（6 维：来源 / 读取时刻 / 失效条件 / 提交前复验 / 阻止旧代际写入 / 记录方式）；`snapshotFlow` = **`observed-at-emit`**（**不预捕获、不伪造 `genExpected`**）/ `finally` = **`captured-at-begin`**（**必须在滚动开始处捕获**）/ 对齐器 = **`captured-at-start`**；**§6.3.6.3 新增 `GenSource`**（`PROGRAMMATIC` / `GESTURE` / `NONE`）以区分 `superseded` 与 `gesture-takeover`；**§6.3.6.5 如实登记 F-09 不可用时的降级**（`GESTURE` 来源无法产生，`gesture-takeover` 在 L3 不可达，由 F-10 真机覆盖） | OBS-2 §6.3.6 |
| **OBS2-06 重新定义 W-1～W-10** | **§7.4.6 整节重写**：**五个计数口径分开**（入口调用次数 / 提交尝试次数 / 被拒绝次数 / 幂等短路次数 / 实际状态变化次数）；**明确「入口调用多次 ≠ 多次状态变化」**（snapshotFlow 与对齐器先后提交同一目标时：可多次入口调用、可多次提交尝试、后续由幂等短路、**实际状态变化不得重复**）；**七类错误全覆盖**（错误中间页写入 / 旧 generation 写入 / Pager 未落定写入 / 绕过统一入口 / 新手势接管后旧协程写入 / 新导航接管后旧协程写入 / 先写错再写对）；**断言检查完整历史而非最终页面**；**W-10 对 `mutations` 全序列逐条判定** | OBS-2 §7.4.6 / §4.12 A17–A24 |

### 未决清单同步结果

| 编号 | 变化 | 依据 |
|---|---|---|
| **A-04** | **新增 · ⚪ 待裁决**（是否批准 P1 审计钩子 —— L3 成立的前置条件；不批准则 L3 不成立） | GPT v2.3「OBS2-02」 |
| **F-09 / F-10 / A-02** | **保持开放**（未关闭） | GPT v2.3「保留既有设计」第 11 条 |
| **A-01 / A-03** | 维持 v2.1 的 ✅ 已裁决 | — |
| **D-01 ~ D-10** | **状态逐字未改**（未把待裁决改成已批准） | GPT v2.3「R11 状态保持设计通过」 |
| **R11** | **状态保持设计通过**（本轮一个字未改） | GPT v2.3 裁决 |
| **R12** | **⛔ 未通过复审**（v2.3 已按六项重写，等 GPT 独立评审；**不得提前标记完成**） | GPT v2.3 裁决「唯独 OBS-2 R12 退回修订」 |
| **F-01 ~ F-08 / B-01 ~ B-09** | **全部保留为待取证** | — |

> **没有删除任何尚未解决的问题**；每一条状态变化都在未决清单 §1.5 留痕。

### 本轮边界（明确声明）

- **未改任何生产 Kotlin**；**未改任何测试源码**；**未改 Gradle / Manifest / Room schema / 数据库迁移 / 备份格式**；
- **未改导航逻辑、数据解析逻辑、入库逻辑**；
- **未改 CR-28 已冻结的数据口径**；**未改需求文档正文**（差异清单 DR-01~DR-10 仍待裁决后落笔）；
- **未改 R11 设计正文**（**R11 状态保持设计通过**）；**未改 TECH-DEBT 登记**；
- **未清理、重建或覆盖任何历史数据**；
- **未合并 main**、**未启动真机测试矩阵**、**未占用 Redmi**、**未连接 vivo**；
- **未跑单测** —— 本轮不允许改生产代码，测试无对象；只做了文档结构校验（代码围栏配对 / 表内空行 / 表列数一致 / 行尾 LF / 章节顺序）与仓库状态检查；
- **§6.4.5 的 P1–P4 只是方案，本轮未改任何代码**；
- **本轮完成不构成生产编码授权** —— 两份设计文档都只是**设计**；**不得把「文档设计通过」写成「代码实现通过」**。

### 停止

**本轮到此停止，等待 GPT 独立评审（复审 R12 测试契约是否真能约束生产实现）。**

