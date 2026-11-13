# CODE_REVIEW · 历史评审与返修复核

> 归档合并：2026-11-13。本文由 7 份历史文件合并而成，每个 `## 【来源】` 节即一份原文。
> **正文内容一字未改**；仅把原文内部的 markdown 标题整体下移两级（代码块内不动），以免与分节标题撞级。
> 文中的测试数、验收条数、提交号、文档路径均为**成文时点快照**，不代表现状；现状以 `docs/项目文档地图.md` 第 2 节为准（见其 §6 快照约定）。

> 覆盖批 1 / 2-5 / 6a / 6b-6d / 6g / 8a 的 AI 评审、返修复核与裁决记录；含当时的 BUGS、FIX_PLAN 结论。


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
