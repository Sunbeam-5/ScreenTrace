# VI 批次 / CR-07 视觉升级 · 交付说明（Step 1–3、Step 8）

> 覆盖区间：本地 `7824186` → `d12d3c6`（2026-11-12 16:30 ~ 20:51，共 7 个提交）+ Step 4（21:40，见第 6 节）+ Step 5（23:25，见第 7 节）。
> 里程碑：VI-1 四步完成 → 负责人引入《屏迹前端实现规格》→ 立 CR-07 → 按规格 §7.2 完成 Step 1–3。
> 当前测试 **294 例 / 0 失败**（Step 5 后）。本文件与 `docs/项目文档地图.md` 配套阅读。

---

## 1. 规格变更链（最容易读错的一段）

1. 16:12 产出 `docs/给MiMo-UI视觉升级提示词.md`，把 Claude 的三页设计稿转成 VI-1/2/3 任务书，圆环方案为「外环目标进度 + 内环超目标」。
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

- `./gradlew :app:testDebugUnitTest` → Step 3 时 **282 例 / 0 失败**；Step 4 后 **288 例**；Step 5 后 **294 例 / 0 失败**。
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

- 执行依据是规格 §4 / §7.2 / §7.3，**不是** `给MiMo-UI视觉升级提示词.md`（那份的圆环方案与分批编号已失效）。
- 规格 §4.8 明确「点某一天沿用原项目跳转详情，改底部面板不在本次范围」；任务书里那条底部面板建议不执行。
- 不改 `contract/` 与 `data/` 业务逻辑、不硬编码颜色、不加运行时依赖、不改 README；每步一个提交并真机复核。
- 本批 7 个提交与 8b-1 等共 **23 个提交仍未推送**，`origin/main` 停在 `7650966`。推送由负责人执行。

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
