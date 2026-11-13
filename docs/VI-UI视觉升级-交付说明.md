# VI 批次 / CR-07 视觉升级 · 交付说明（Step 1–6、Step 8 + CR-09 + CR-10 文档收口）

> 覆盖区间：本地 `7824186` → `d12d3c6`（2026-11-12 16:30 ~ 20:51，共 7 个提交）+ Step 4（21:40，第 6 节）+ Step 5（23:25，第 7 节）+ Step 6（11-13 01:05，第 8 节）+ CR-09 四项整改（11-13 09:00，第 9 节）。
> 里程碑：VI-1 四步完成 → 负责人引入《屏迹前端实现规格》→ 立 CR-07 → 按规格 §7.2 完成 Step 1–6 → CR-09 体验整改。
> **当前测试 310 例 / 0 失败**（CR-09 后）；下文第 3/6/7/8 节里的 282/288/294/298 均为**各步成文时点的快照**，不代表现状。本文件与 `docs/项目文档地图.md` 配套阅读。

---

## 1. 规格变更链（最容易读错的一段）

1. 16:12 产出 VI-1/2/3 原始任务书（`给MiMo-UI视觉升级提示词.md`，**2026-11-13 已删除**；变更链见本节），把 Claude 的三页设计稿转成任务书，圆环方案为「外环目标进度 + 内环超目标」。
2. 负责人勘误：圆环改 **24h 全天环**（弧长 = 当日使用 / 24h）→ `33690a9` 落地，记为 CR-06。
3. 19:36 负责人贴入 Claude 写的《屏迹 ScreenTrace 前端实现规格（给 AI 开发者）》，AI 比对后列出两版规格冲突；19:38 负责人裁定 **「完全按新规格」**。
4. 按项目纪律「先改文档再改代码」，先立 CR-07 并修订需求条款，再回滚圆环 → `df9d71b`。
5. 规格全文原只在对话里，21:25 从 Android Studio 的 Claude Code GUI 会话记录恢复，落盘 `docs/屏迹前端实现规格-给AI开发者.md`。

**现行结论**：圆环 = 目标环双层（`progress = 使用/目标`，允许超 1、上限 2），超目标部分用 `ringOver` 从 12 点起叠画第二圈。CR-06 的 24h 全天环已作废，仅作为变更历史保留。

---

## 2. 逐提交清单

| 提交 | 内容 |
|---|---|
| `7824186` VI-1-1 | Manrope 四个字重（`res/font/manrope_{regular,medium,semibold,extrabold}.ttf`，OFL）；`FreshType` 拆数字/中文 family，数字加 `tnum` 等宽；中文继续走系统字体（不打包 8–10MB 中文字体） |
| `3c3592d` VI-1-2 | 页面背景渐变 + 浅色卡片轻阴影；抽出 `uikit/FreshCard.kt` 统一 24dp 圆角容器，替换四页里 20 多处重复 `clip+background+padding` |
| `33690a9` VI-1-3 | 圆环改 24h 全天环（**后被 CR-07 推翻**）；新增 `uikit/RingMath.kt` 纯函数几何 + `RingMathTest` |
| `e1f6bff` VI-1-4 | 顶部三圆按钮收敛为「日期 + 设置」两枚；设置页风格选择升级为色块网格、点选即生效；修复圆环空白的根因 |
| `a798351` CR-07 Step1 | 令牌校准：白卡 24→28dp、`pageTitle` 32sp、`heroNumber` 46sp/800、新增 `statsNumber` 40sp、`metricNumber` 30sp（统计卡 28sp）、三段背景渐变、双层阴影近似；新增 `FreshTypeFontTest` |
| `df9d71b` CR-07 Step2 | 圆环回滚目标环双层：单一动画值 0→progress（900ms FastOutSlowIn）同时驱动两圈与数字；超目标第二圈用 `ringOver`；减少动画直接落终值 |
| `d12d3c6` CR-07 Step3 | 状态标签三色（超目标珊瑚 `overTarget` / 对比中性 `chipSurface` / 下降绿 `downSurface`）+ 可下钻卡图标结构；新增 `uikit/StatusChip.kt`、`StatusChipTest` |

`FreshPalette` 新增语义 token（均有默认值，九套预设与 `paletteFromSeed` 同步派生）：`gradientTop` / `gradientMid` / `shadow` / `ringGradientEnd` / `ringOver` / `chipSurface` / `chipOn` / `downSurface` / `downOn` / `overTarget`。UI 层无裸 `Color(0xFF…)`。

---

## 3. 验证

- `./gradlew :app:testDebugUnitTest` → Step 3 时 **282 例 / 0 失败**；Step 4 后 **288 例**；Step 5 后 **294 例**；Step 6 后 **298 例**；CR-09 后 **310 例 / 0 失败**（均为当时基线）。
- 新增测试：`FreshTypeFontTest`、`RingMathTest`、`StatusChipTest`；`FreshPaletteContrastTest` 同步扩展。
- 真机结论（来自会话记录）：Step 2 验证 4.5× 目标时双圈满叠且不溢出；Step 3 验证 chips 与挂锁/铃铛图标块显示正常；VI-1-4 修复了圆环空白问题。
- **已知笔误**：`d12d3c6` 的提交信息把「282 测全绿」写成 283。按项目纪律不 amend，以本文件为准。

---

## 4. 未完成（按规格 §7.2）

| Step | 内容 | 备注 |
|---|---|---|
| 6 | 日历页：热力四档、今日描边、图例 | **口径已统一**：按使用/目标比值 100%/140%/175%，见需求 §5.7；代码仍是三档 `coerceIn(0,3)` |
| 7 | 导航胶囊 + 底部 contentPadding | 未动 |
| 9 | 深色模式降饱和 + 九套主题逐套巡检 | 未动 |

**Step 8 已完成**（VI-1-4 提前做了顶栏两按钮 + 设置页主题入口），不要再作为待办重复实现。

其它不在本批次：8b-2 Google Drive、批 11/12/13/14b、14a PNG 长图、批 9 收尾。

---

## 5. 接手注意

- 执行依据是规格 §4 / §7.2 / §7.3，**不是** VI-1/2/3 原始任务书（已于 2026-11-13 删除，其圆环方案与分批编号已失效）。
- 规格 §4.8 明确「点某一天沿用原项目跳转详情，改底部面板不在本次范围」；任务书里那条底部面板建议不执行。
- 不改 `contract/` 与 `data/` 业务逻辑、不硬编码颜色、不加运行时依赖、不改 README；每步一个提交并真机复核。
- 截至 11-12 晚，本批 7 个提交与 8b-1 等共 **23 个提交未推送**（历史时点）；此后持续累积，`origin/main` 停在 `7650966`，**实时未推数用 `git status -sb` 看，不在文档写死**。推送由负责人执行。

---

## 6. Step 4 应用构成卡（CR-07 §4.4，提交 `749431b`）

**改动**：
- 新增 `uikit/AppBreakdownMath.kt`（纯函数）：过滤零分钟段、颜色按可见顺序 1→5（不再让「其他」跳到第 5 色）、整数百分比用最大余数法合计 100。
- 新增 `uikit/AppBreakdown.kt`：14dp 色带 + 五行行式图例（8dp 同色圆点 / 28dp 真应用图标 / 名称 / 14sp 等宽时长 / 12sp 右对齐百分比）、日间夜间行（自绘太阳与月亮图标、13sp 文案、10dp 双色条）、48dp 圆角 18 的「查看全部应用」按钮（`transformSource` 起点保留在按钮上，圆角同步改 18dp）。
- `ui/TodayScreen.kt`：标题行加右侧「共 X小时Y分」，换成新组件；口径说明文字保留；删掉本页私有的 `chartList` 扩展。
- `theme/`：新增语义 token `viewAllSurface` / `nightIcon`（九套预设与 `paletteFromSeed` 同步派生），新增字体 token `numberBody` / `bodySmall` / `bodySmallBold` / `buttonBold`。
- `SegmentedBar` / `RatioBar` **未改**：统计页、排行页、周期总结页仍在共用，不能为今日页动它们。

**验证**：
- 288 例 / 0 失败；新增 `AppBreakdownMathTest` 6 例。
- 变异验证两次均变红：①「其他」固定拿第 5 色 → 颜色顺序用例失败；② 跳过最大余数法 → 百分比合计用例失败。均已还原。
- 真机（`GAQOHMNFQWFUPBQO`，1220×2712）：浅色截图确认标题/色带/五行/日夜间/按钮完整、无截断重叠；`uiautomator dump` 确认全部文案在层级内，行间距 102px ≈ 34dp。
- 深色模式：切系统夜景后 App 未重组（`darkTheme` 由 App 内设置控制），**未逐项验证**，归入 Step 9 巡检。

**已知待办（不属本步）**：首屏底部导航胶囊会遮住列表末尾 2–3 行，需 Step 7 的 contentPadding 处理；占比 1% 的段在色带上呈细线，是真实比例，规格未要求最小宽度。

---

## 7. Step 5 统计页（CR-07 §4.5 / §5.2 / §4.7，提交 `da96c4d`）

**改动**：
- `CapsuleBarChart` 视觉层重写（`TrendChartMath` 数学算法未动）：点柱详情浮层 → 图表上方固定面板，默认选中最后一天；18dp 方柱（顶圆角 9 / 底 4）、新增 `barSurface`、绘图区 150dp、生长动画（500ms + 每根 30ms）、平均线改 55% 透明虚线且图例移到图表下方、横轴选中日加粗；左右滑动 30dp 阈值 + 轻震。
- 新组件 `PeriodDistribution`：36dp 四段条（首末外圆角 18 / 内 8）+ 2×2 图例网格；百分比抽为共用 `PercentMath`（Step 4 的 `AppBreakdownMath` 同步复用，行为不变、原测试仍全绿）。
- 新增纯函数 `TrendSelectionMath`（默认最后一天、滑动夹取）；`StatsScreen` 分段控件置顶、日均亮屏 12sp 标签 + 40sp 大数字、无对比数据不显示 chip、双卡 28sp + 日期周几副行。
- `SegmentedControl` 按 §4.7 全局改 44dp / `chipSurface` 轨道 / 4dp 内缩 / 250ms 滑块（统计页与日历页共用）。
- theme 新增 `barSurface` / `numberSection` / `numberBodyLarge` / `labelBold`；需求文档 §5.7 统计第 3/4/5/7 条、§7-38、§5.2 分段控件条款已先行同步（同一提交）。

**真机发现并修复的两个问题**（都是截图 / dump 才看得出来的）：
1. **点柱无效**：多余的 `LaunchedEffect(points, resetKey)` 会把刚点中的柱子重置回最后一天；改用 `remember` 的 key 单独承担「换数据 / 切页回默认」。修复后点 11/10 柱 → 面板变「周二 · 11月10日 12小时45分钟」、选中柱变色、药丸完整。
2. **第三个应用药丸被挤成省略号**：`formatDuration` 的「13小时16分钟」太长，三个药丸放不下；改用项目既有 `H:MM` 形态（13:16 / 3:58 / 0:11）并均分宽度。

**验证**：294 例 / 0 失败（新增 `StatsStep5MathTest` 6 例）；两次变异均变红并还原（跳过最大余数法、默认选中改第 0 天）。真机截图 + `uiautomator dump`：详情面板、三档刻度、平均线图例、时段 2×2（23+22+20+35=100）、双卡日期周几、洞察与周期入口完整；滚动后无导航遮挡。

**仍未做**：深色逐项巡检（Step 9）、首屏底部导航胶囊遮挡（Step 7 contentPadding）。

---

## 8. Step 6 日历页（CR-07 §4.8 / §5.3 / 需求 §5.7）

**文档先行**：实施前先改需求文档两处过时条款——§5.7 第 3 条与验收 9 的「连续日期合并胶囊」按 CR-07 §4.8 定稿改为**独立 38dp 方块**（设计稿即独立方块，规格全文未提胶囊）。

**改动**：
- `uikit/MonthCalendar.kt` 重写：去掉胶囊合并逻辑，独立 38dp 方块（圆角 13、间距 4，360dp 屏按可用宽度等比收缩、下限 30dp，与年视图同一策略防溢出）；档位由 `MonthLevelMath` 按「使用/目标」比值在 UI 层重算（contract 旧三档口径未动），heat1~4 色阶、第 4 档白字由对比函数自然得出；「今天」白底 + 主色 2dp 外环；补位日不着色灰色数字、不可点；读屏文案改播真实时长（旧档位文案「低于 2 小时」在比值口径下不成立）；新增 `MonthCalendarLegend`（少 [heat1~4 四块 16dp 圆角 6] 多 + ○ 今天）。
- `uikit/MonthLevelMath.kt`（Step 6 已建文件）：`levelOf(minutes, recorded, goalMinutes)` 四档纯函数，目标 ≤0 退化为最低有记录档。
- `ui/CalendarScreen.kt`：连续记录卡按 §5.3-2 重排（圆角 28、56dp 白 55% 图标块 + 火苗线性图标、34sp 大数字 + 「天」、副行「最长 N 天 · 始于 M月D日」——顺带修掉旧文案错字「委于」）；翻月按钮 44dp 圆形 chipSurface 底 + 主色 20dp 箭头（点击区 48dp）；翻月动画 200ms 淡入 + 8dp 水平位移（reduceMotion 直出终态）；双统计卡 compact（28sp）+ 左注日期周几、右注「共 N 个有记录日」（N 从 20 周年视图 levels 统计，contract 未提供该计数，UI 层推导）；新增 `goalMinutes` 参数。
- 火苗图标：material-icons-core 无 Whatshot，不为此引入 icons-extended 全量包——material whatshot 外轮廓 24 视口路径用 `PathParser` 解析 + DrawScope.scale 描边自绘（与 Step 4 日/月图标同一策略）。
- `ui/AppNav.kt`：CalendarScreen 调用点传 `dailyGoalMin`；日历页顶栏副标题在数据就绪时改「已记录 N 天」（§5.3-1），未就绪回退区间副标题。
- `theme/`（上次会话已建）：`numberDay`（14sp SemiBold tnum）/ `numberStreak`（34sp ExtraBold tnum）。

**验证**：
- 298 例 / 0 失败；新增 `MonthLevelMathTest` 4 例（无记录/0 分钟/目标未设置退化、100%/140%/175% 逐点边界、改目标档位重算）。
- 变异验证变红并还原：把 1.4 改 1.5 → 边界用例失败。
- 真机（`GAQOHMNFQWFUPBQO`）截图 + `uiautomator dump`：副标题「已记录 4 天」、连续记录卡、月视图独立方块（11/10–12 深档白字）、今天 13 白底主色环、图例「少[四块]多 + ○ 今天」、双卡「23小时22分 · 11月11日 周三 / 低于 2 小时 1 天 · 共 4 个有记录日」全部正确；翻 12 月空月渲染正常（补位灰、不着色）；年视图无回归（20 周完整居中）。

**登记待办（不属本步）**：需求 §5.7 第 4 条说年视图色阶按**可配阈值 x**，但 `UsageCalculator.yearLevel` 仍写死 120/240/360（CR-05 遗留）；跟随 x 需改 contract 仓储接口链，超出 VI 边界，已登记文档地图冲突区待裁决。深色模式巡检仍归 Step 9。

---

## 9. CR-09 四项体验整改（2026-11-13）

**背景**：负责人提出四项——①首页指定单日不生效；②区间手输不自动填充；③二级页转场达不到「回到桌面」效果且退场中点屏会飘；④数字滚动僵硬。调研结论：转场官方现成方案 `SharedTransitionLayout` 需 Compose 1.7（BOM 2024.09+），与 S04「不为实验 API 升级 BOM」决议冲突，继续自研；数字滚动现成库均需 JitPack 运行时依赖，与「不添加运行时依赖」红线冲突，自实现。

**改动**：
- **①首页单日（需求 §5.6 三处 + 验收 40 先行同步）**：`AppViewModel.loadAll` 的 `todaySelection` 改为「单日筛选跟随该单日，区间筛选锚定今天」（CR-03 量纲冲突只在区间下成立）；`TodayScreen` 圆环 caption 写实际显示日（非今天时「11月12日亮屏」）；`AppNav` 今日页副标题单日筛选时跟随该单日。真机验证：点「昨天」→ 首页 22:27 亮屏、副标题「11月12日」、超出目标 18小时27分钟 全部正确。
- **②手输日期显示掩码（验收 41）**：踩坑实录——先做「onValueChange 里改写文本」的 autoFormat，真机上 `12345678` 被显示成 `1234-67-85`：格式化后的文本回灌导致**光标位置与重排文本错位**，后续输入插错位置。重写为 **VisualTransformation 显示掩码**（`DateInput.mask`）：字段真实文本保持数字流、展示层自动补 `-`、OffsetMapping 处理光标映射；`parse/normalize` 兼容 8 位纯数字；日历回填改写数字流（`format` 两位补零）；中间态（<8 位数字）不再报错。新增 `DateInputTest` 7 例（normalize/parse/maskDigits/format 往返/中间态）。真机验证：`20261113` 显示 `2026-11-13`，双框填 11-13/11-28 正确，倒序区间被正确拦截。
- **③转场（验收 42）**：修复「退场动画中点屏幕页面飘到点击点」——根因是退场刚开始 `detailPage==null` 就恢复了起点记录，用户一按就把 `origin` 改到点击点；加 `ContainerTransformState.acceptingCaptures`，`ContainerTransformOverlay` 每帧同步（退场中禁止记录），`transformSource`/`recordTransformOrigin` 两个捕获入口都加门控。关闭动画改两段式：`exitProgress = p^1.6`（快出缓收）重映射缩放/位移/圆角——页面先快速缩到约半屏、内容淡出成「图标」状，再缓缓收进入口矩形。新增 `ContainerTransformMathTest` 2 例（重映射两端归位单调、前半程快于线性）。
- **④数字滚动（验收 42）**：新增 `uikit/RollingNumber.kt`——`RollingNumberText` 逐字符 AnimatedContent 里程表滚动（数值增大向上滚、减小向下滚、非数字交叉淡入），`RollingNumberMath.rollDirection` 纯函数 + 4 例测试；`RingProgress` 中心 H:MM 弃用 `animateIntAsState`（中间值是假读数、所有字符每帧都在变）改接逐位滚动。

**验证**：310 例 / 0 失败（新增 13 例：DateInput 7 + ContainerTransform 2 + RollingNumber 4）。真机已验：①②④（昨天单日、掩码输入、逐位滚动节点）。**未验**：③的录屏观感与「退场中点屏不飘」（验证中途设备被转用），待设备空闲补验。

**已知取舍**：显示掩码约定「字段文本永远不含 `-`」（回填已改数字流）；一位数月/日需补 0（`20260105`，标准掩码行为）；删除掩码分隔符会删除前一位数字（VisualTransformation 标准行为）。

---

## 10. CR-10 文档口径收口（2026-11-13，无代码改动）

**背景**：负责人允许修改文档后，按独立核验结果统一口径；本轮只改文档，不改 Kotlin 代码。

- **顶栏三按钮**：需求 §5.2、验收 36、规格 §4.9/§7.2 已统一为「日期 / 主题 / 设置」三枚（主题在中间、三色圆点自绘）；设置页保留主题入口，两处共用同一个全局 `StyleSheet`；二级页只保留返回按钮。实现归入 Step 7。
- **年视图阈值**：负责人裁决年视图最终跟随低使用阈值 `x`（`x / 2x / 3x / 4x`），月视图仍按「使用 / 每日目标」比值四档；实现归入批 10b（contract → Room/Empty → ViewModel → UI），不改数据库与备份 schema。
- **验收旧口径清理**：验收 7（圆环 14dp 目标环双层）、11（筛选条已移除）、21（固定详情面板取代浮层）、23（年视图随 x、月视图随目标）、26（移除区间累计标注要求）已同步；§7 总条数仍为 42。
- **漏项登记**：验收 37「今日页近 7 天迷你柱」在代码中未实现，规格原写「可选」已按需求强制口径修正；列入当前 UI 收口待办，单独小提交补齐。
- **同步状态修正**：8b-1 只应表述为「WebDAV 手动同步完成；自动同步算法与测试完成，生产接线未完成」。`uploadAuto()` 尚无生产调用方，归 8b-2。
- **资料源旧需求**：`E:/project/屏幕使用时间App/需求文档-屏幕使用时间App.md` 已降级为 v2.5 历史快照，禁止再当实现依据；不再做双份覆盖同步。
