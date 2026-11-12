# 屏迹 UI 视觉升级 · 交给 MiMo 的任务提示词（VI 批次）

> **用法**：从「一、你的任务」开始整段复制给 MiMo。本文自包含，它不需要看之前的对话。
> **视觉基准**：`docs/design/ui-v2/01-today.png`、`02-stats.png`、`03-calendar.png`（780×2260 / 780×2112 / 780×1800，约 2.16 倍密度，按 dp 还原不要按像素抄）。
> **代码库**：`E:/project/ScreenTrace`，当前 HEAD `d54370b`，分支 `main`，**测试基线 272 例全绿**。
> **写入时间**：2026-11-12。

---

## 一、你的任务

把「屏迹」（Android 屏幕使用时间 App，Kotlin + Jetpack Compose + Material3）的界面，从「结构正确但平淡」升级到三张设计稿的质感。

**这不是一次重写，而是在现有代码上做视觉与信息层级的改造。** 现有四页的数据流、状态管理、导航、转场、空态、无障碍语义都已经验证过，全部保留；只换皮、补细节、调层级。

具体要做的 9 项，来自一次外部设计评审（Claude 走查了项目代码 + 出了设计稿），逐条列在第五节。你要**直接写实现代码**，不是给设计建议。

---

## 二、开工前必读（按顺序，别跳）

1. `docs/需求文档-屏幕使用时间App.md` 的 §5.2（视觉语言）、§5.3（字体与数字 token）、§5.4（主题）、§5.7（四页规格）
2. `docs/实施任务批次计划-v2.md` 第 200–220 行（当前进度：6g / 7 / 8a / 10a / 14a 完成，8b-1 云同步完成，**8b-2 Google Drive 未做**）
3. 三张设计稿 PNG（唯一视觉基准，色值以图为准）
4. 要改的代码：
   - `app/src/main/java/com/screentrace/app/theme/FreshPalette.kt`
   - `app/src/main/java/com/screentrace/app/theme/ScreenTimeTheme.kt`（`FreshType` 字体 token 在这里）
   - `app/src/main/java/com/screentrace/app/theme/PaletteEngine.kt`（自定义种子派生引擎）
   - `app/src/main/java/com/screentrace/app/uikit/RingProgress.kt`
   - `app/src/main/java/com/screentrace/app/uikit/MonthCalendar.kt`
   - `app/src/main/java/com/screentrace/app/uikit/CapsuleBarChart.kt`
   - `app/src/main/java/com/screentrace/app/uikit/ScreenScaffold.kt`（`AppHeader` + 页面背景）
   - `app/src/main/java/com/screentrace/app/ui/TodayScreen.kt`、`StatsScreen.kt`、`CalendarScreen.kt`

---

## 三、红线（违反即返工，不接受解释）

1. **不动 `contract/` 和 `data/` 的业务逻辑**。只允许在 `theme/`、`uikit/`、`ui/` 里改；如果某个数据（比如近 7 天迷你柱）现有 ViewModel 没提供，可以在 `AppViewModel` 里**新增查询调用**，但不许改 `UsageCalculator` 既有函数语义、不许改表结构、不许加迁移。
2. **不硬编码颜色**。所有颜色必须来自 `LocalFreshPalette.current`。新增语义色必须同时加进 `FreshPalette` + 9 套预设 + `paletteFromSeed`，并让 `FreshPaletteContrastTest`、`PaletteEngineTest` 通过。任何一处 `Color(0xFF...)` 出现在 `ui/` 或 `uikit/` 里都算违规（主题定义文件除外）。
3. **`README.md` 冻结，一个字都不许改**。工程说明写 `docs/开发与构建说明.md`。
4. **不加运行时依赖**。唯一例外：字体文件进 `res/font`（见 VI-1-1）。不引入图片库、动画库、图标库。
5. **不能只说"改好了"**。每个批次结束必须给：`./gradlew :app:testDebugUnitTest` 的通过数、`./gradlew :app:assembleDebug` 成功、以及**真机或模拟器截图**（没有设备就明说"未做真机验证"）。
6. **新写的测试必须能变红**。写完后手动把实现改坏一次，确认测试失败，再改回来。做不到就说明测试是假绿。
7. **单插槽容器的坑**：`Crossfade` / `AnimatedContent` / `Box` 内容区是**单插槽**，里面塞多根节点会全部叠在左上角。本项目已经踩过三次（TodayContent / StatsContent / DateSheet）。凡是在这些容器里加内容，先包一层 `Column`。

---

## 四、构建与验证命令

```bash
cd /e/project/ScreenTrace
export JAVA_HOME="/c/Program Files/Java/jdk-17"

./gradlew :app:testDebugUnitTest --console=plain   # 必须 0 failures，基线 272
./gradlew :app:assembleDebug --console=plain       # 产物 app/build/outputs/apk/debug/app-debug.apk
```

警告也要看：`grep -E "^w: "` 里不许出现你新引入的未使用 import / 弃用 API。

---

## 五、分批任务（严格按序，每批单独提交，不要攒成一个大提交）

### VI-1 地基（先做，四页都受益）

#### VI-1-1 字体系统：数字用 Manrope，中文交给系统

**现状**：`ScreenTimeTheme.kt` 第 26 行 `private val family = FontFamily.Default`，全站一个字体。

**目标**：数字与拉丁字母用 Manrope（几何无衬线，字重 800 时数字有力），中文继续用系统字体。

**做法**：
- 下载 Manrope（SIL OFL 许可，可商用可打包）的 4 个静态字重：Regular / Medium / SemiBold / ExtraBold，放进 `app/src/main/res/font/`（命名 `manrope_regular.ttf` 等）。
- `FreshType` 拆成两个 family：
  ```kotlin
  private val numberFamily = FontFamily(
      Font(R.font.manrope_regular, FontWeight.Normal),
      Font(R.font.manrope_medium, FontWeight.Medium),
      Font(R.font.manrope_semibold, FontWeight.SemiBold),
      Font(R.font.manrope_extrabold, FontWeight.ExtraBold)
  )
  private val textFamily = FontFamily.Default   // 中文走系统（MIUI = MiSans，观感已接近设计稿）
  ```
- `heroNumber` / `metricNumber` 用 `numberFamily` + `fontFeatureSettings = "tnum"`（等宽数字，避免数字跳动时宽度抖）；`pageTitle` / `sectionTitle` / `body` / `label` / `button` 用 `textFamily`。
- **不要**打包 Noto Sans SC 之类的完整中文字体：单字重 8–10MB，APK 体积直接翻倍，而 MIUI/HyperOS 的系统中文已经是 MiSans，肉眼差别极小。这是有意识的取舍，写进 `docs/开发与构建说明.md` 的「字体」一节。

**验收**：`FreshType.heroNumber.fontFamily` 是 Manrope；切到英文/数字界面字重视觉明显变粗；APK 增量 < 1MB（4 个字重合计约 300–400KB）。

#### VI-1-2 页面背景渐变 + 卡片层次

**现状**：`ScreenScaffold.kt` 第 68 行 `.background(palette.background)` 纯色；卡片是 `clip + background(surface)`，白卡贴在灰白底上，层次接近没有。

**目标**：设计稿那种「顶部一层极淡主色、向下渐隐到中性底」的氛围；卡片浮起来一点点。

**做法**：
- `FreshPalette` 新增两个字段（**必须给默认值**，避免 9 套预设全部编译报错）：
  ```kotlin
  val gradientTop: Color = Color(0xFFEAF2F6),  // 页面顶部渐变起点
  val shadow: Color = Color(0x14000000)        // 卡片阴影色
  ```
- 9 套预设里显式赋值：浅色 `gradientTop = seed.mix(White, 0.90f)`，`shadow = Color(0x14000000)`；深色 `gradientTop = seed.mix(DarkNeutral, 0.86f)`，`shadow = Color(0x33000000)`。
- `paletteFromSeed` 同步派生（自定义主题也要有渐变，不能只有预设好看）。派生体在 `FreshPalette.kt` 第 129 行 `internal fun derivePalette(...)`，九套预设与自定义种子共用它，改这里两处一起生效。
- 页面背景改成纵向渐变：0f → `gradientTop`，0.42f → `background`，剩余纯 `background`。
- **卡片阴影的裁决**（这里和需求文档冲突，见第六节）：允许 0–2dp 极淡阴影，**只在浅色模式**；深色模式 elevation 归零，靠 `surfaceVariant` 与 `surface` 的色阶差分层（深色下阴影本来就看不清，硬加只会糊）。
- 顺手抽一个 `uikit/FreshCard.kt`：`FreshCard(modifier, padding = 20.dp, content)` 封装「24dp 圆角 + surface + 阴影」，然后把 Today / Stats / Calendar / Period 四页里散落的 `Column(clip(RoundedCornerShape(24.dp)).background(surface).padding(20.dp))` 全部换掉。现在这套样式在四页里重复了 20 多次，你要加阴影就得改 20 处，抽出来以后只改一处。

**验收**：浅色下顶部能看出淡淡的主色倾向且向下自然消失；深色下没有脏灰的阴影边；`FreshPaletteContrastTest` 全绿（新字段不参与对比度断言，不要顺手加进去）。

#### VI-1-3 圆环重做：超出目标要看得出来 + 进场动画

**现状**：`RingProgress.kt` 单圈 9dp，`progress.coerceIn(0f, 1f)` 之后**超过目标永远是一整圈**，超了多少完全看不出来。

**目标**（设计稿第一页）：两层同心环。
- 外环：track 底 + 主色弧，走到 `min(progress, 1)` 就停。
- 内环（半径比外环小 16dp，同 9dp 粗）：**只有超目标时才画**，从 12 点顺时针画 `min((usage - goal) / goal, 1)` 圈，颜色用新的 `overTarget`（珊瑚）。
- 中心三行：上「今日亮屏」（`label`，12sp）、中 `H:MM`（`heroNumber` 提到 44sp）、下「目标 4:00」（`label`，`onSurfaceVariant`）。
- 圆环外下方两枚 chip 并排：`超出目标 1小时42分`（珊瑚底 + 深珊瑚字，**仅在超目标时出现**）、`较昨日 12 分钟`（浅底 + 箭头，沿用现有 `CompareLine` 语义）。

**做法**：
- 保持 `RingProgress(progress, timeText, ...)` 向后兼容，新增可选参数：`overRatio: Float = 0f`、`overMinutes: Int = 0`、`caption: String? = null`、`note: String? = null`（`caption` / `note` 已经存在，保留）。
- **把圈的几何算成纯函数**放 `uikit/RingMath.kt`（`outerSweep(progress)`、`innerSweep(overRatio)`、`shouldShowInner(overRatio)`），这样能直接 JVM 单测，不用 Robolectric。
- 动画：一个 `Animatable(0f)` 在首次组合时从 0 跑到目标（`tween(700, easing = FastOutSlowInEasing)`），数字用 `animateIntAsState` 从 0 滚到 `usageMin`。**必须**接 `rememberReduceMotion()`（项目已有），减少动画时直接落到终值，不要只是缩短时长。
- 超目标时圆环主色不变，只有内环变珊瑚；**不要**整圈变红——珊瑚是"信号"，不是"警报"。

**验收**：`usage = 4h42m / goal = 3h` 时外环满、内环走 56%（1h42 / 3h）；`goal = 0` 时不画任何弧、不显示 chip；动画在首帧就从 0 起（不许先闪一帧满圈）；`RingMathTest` 至少 6 例，含边界 0 / 恰好等于目标 / 超 3 倍目标。

#### VI-1-4 顶部按钮从三个收敛到两个

**现状**：`AppHeader` 有 `onDateClick` / `onStyleClick` / `onSettingsClick` 三个圆按钮，风格切换占了最贵的位置，但一个月点不了两次。

**目标**：今日页 / 统计页 / 日历页顶部只留「日期」+「设置」两个圆按钮。

**做法**：
- `AppHeader` 删掉 `onStyleClick` 参数与对应 `HeaderActionButton`，同步清掉 `AppNav.kt` 的传参。
- 确认设置页「外观 → 界面风格」这一行还在（`SettingsScreen.kt` 里本来就有 `ActionRow(label = "界面风格")`），入口不丢。
- 顺手把设置页的风格选择从「弹层」升级成**色块网格选择器**：9 套预设各一格圆形色板（主色实心 + 选中态描边），当前自定义主题也显示为一个格子。实时预览（点了立即生效，不再需要"确认"）。这属于 VI-3，但既然在改 `AppHeader`，一起把 `StyleSheet.kt` 的布局改了更省事。

**验收**：三页顶部两个按钮；设置页仍能切九套风格 + 自定义；`AppHeader` 的 Preview 同步更新。

---

### VI-2 三页改造

#### VI-2-1 今日页（对照 `01-today.png`）

| 位置 | 现状 | 目标 |
|---|---|---|
| 圆环卡 | 圆环 + `CompareLine` + 「最长连续 X」一行 | 圆环（双层，见 VI-1-3）+ 两枚 chip + **近 7 天迷你柱**（右侧「日均 5小时31分」）+ 星期标签 |
| 双卡 | tonal 双卡（解锁 / 通知打扰），`MetricCardPair` 只有标签/数值/备注，**没有图标参数** | 保留双色 tonal，卡内左上加圆形白底图标、右上加箭头（给 `MetricCardPair` 加 `leftIcon / rightIcon: ImageVector? = null`，不要另写一套卡） |
| 应用构成 | 一条色带 + 纯文字图例 | 色带保留；图例改成**行式**：图标（底色 = 对应段色）+ 名称 + 时长 + 右对齐占比，每行 32dp |
| 日间/夜间 | 双色条 + 两端文字 | 保留，深色主条 + 淡紫副条，标签两端对齐 |
| 底部 | 「查看全部应用」 | 保留（它已是 `PrimaryButton` 风格，改成设计稿那种浅底 pill） |

**近 7 天迷你柱是新增组件**：写 `uikit/MiniWeekBars.kt`（不是复用 `CapsuleBarChart`，那个带交互和浮层，太重）。数据来源：`AppViewModel.loadAll` 里已经有 `UsageCalculator.dailyPoints(...)` 的调用（周期总结页在用），给今日页传一份近 7 天点即可——**只加查询调用，不改数据层语义**。

「最长连续 X」设计稿里没有：折叠成圆环卡底部一行 12sp 小字（`onSurfaceVariant`），或者干脆并进统计页。**选前者**，别丢信息。

#### VI-2-2 统计页（对照 `02-stats.png`）

1. **选中详情从「浮动层」改成「图表上方固定条」**。现状 `CapsuleBarChart` 是「浮层落在选中柱的对侧，不遮挡该柱」——柱子在两端时对侧空间不够，还是会被挤到柱子上面。改成图表**正上方**一条固定高度的详情卡：`周六 · 11月7日` + 右侧 `7小时12分` + 3 个应用 chip（图标 + 名称 + 时长）。未选中时这一条显示淡色提示「点击柱子查看当日详情」（**不要隐藏**，否则点选时整页会跳一下）。
2. 柱子：未选 `chart2`，选中 `primary` 且加高一点点（设计稿选中柱明显更高更粗）；平均虚线 + 左下角「日均 5小时31分」图例保留。
3. 交互：点柱选中（已有）、**左右滑动切换选中日**（在绘图区加 `pointerInput` 的 `horizontalDrag`，阈值 30dp 防误触）、切换时 `LocalHapticFeedback` 轻震（`HapticFeedbackType.TextHandleMove`）。
4. **时段分布**改成 4 段色块 + 2×2 图例网格（圆点 + `06–12` + `1小时24分 25%`），替换现在的一行文字。
5. 底部「最久的一天 / 最少的一天」双卡：紫 / 蓝 tonal，保留现有数据。

#### VI-2-3 日历页（对照 `03-calendar.png`）

1. 连续记录卡改成紫 tonal（`secondaryContainer`），左侧白底圆形火焰图标，右侧「连续记录 / 18 天 / 最长 18 天 · 始于 10月26日」。
2. **月历从三档改四档**。现状 `MonthCalendar` 用 `palette.month1~3` + `level 0`（无色）。
   - **不要新增 `month4` 字段**，直接复用已有的热力四档 `heat1~4`（浅→深明度单调，浅色 0.78 / 0.56 / 0.34 / 0.0，正好四档）；`level 0` 保持无底色。
   - `UsageCalculator.monthLevels` 的档位阈值要跟着改（3 档 → 4 档，阈值函数 `monthLevel(minutes, recorded)` 在同一文件），**这会打红 `app/src/test/java/com/screentrace/app/UsageCalculatorTest.kt` 里的档位断言**，是预期的：更新断言并在测试里写清「四档边界」（含刚好落在阈值上的那一天）。
   - 底部加图例：`少 [四块色阶] 多` + 右侧 `○ 今天`。
   - 日号颜色已有 `textColorOn(fill)` 自动选深/浅字，**不要手改**；但四档最深一档（纯 seed）要补一条对比度断言，避免某个主题下白字只有 3.8。
   - 「今天」改成**白底 + 1.5dp `primary` 描边**（设计稿里 12 日在深色格子上是白底黑字带描边），不要用实心填充——实心会和选中态撞。
3. 点某一天：如果现在点日期是跳今日页，改成**底部弹层显示当天详情**（时长 + 前三个应用 + 「看详情」按钮跳今日页）。这一条要先确认现状再动，涉及导航就单独提交。

---

### VI-3 收尾

1. **空态不占行**：`CompareLine` 现在的 `CompareInfo("暂无上一周期数据", null)` 会占一整行。改成：没有对比数据时**整行不渲染**（不是显示灰字）。抽一个纯函数 `shouldShowCompare(info)` 单测覆盖。
2. **深色模式降饱和**：大面积纯色块（主圆环、选中柱、连续记录卡）在深色下饱和度 ×0.82。`PaletteEngine` 里已有 HSL 工具（`toHsl` / `hslColor`），复用它做 `desaturate(color, 0.82f)`。
3. **可读性复核**：次要文字不低于 12sp；所有新色对跑一遍 `FreshPaletteContrastTest` 的同一套断言（正文 4.5、图形 3.0）。
4. **（可选，时间不够可跳过）** 按应用类别着色：`AppCategory` 已存在，给社交/视频/工具/系统各一个色，构成条与热力图可用。跳过时在交付说明里写明原因，不要留半成品。

---

## 六、文档要同步改的条款（**先改文档，再改代码**）

项目纪律：需求文档是唯一权威，文档与代码冲突时先修文档。这次有 5 处必须改，否则实现完就是"违反需求"：

| 位置 | 现文 | 要改成 |
|---|---|---|
| §5.2 第 157 行 | 「卡片统一：**无描边、无阴影**、surface 背景、圆角 24dp」 | 允许 0–2dp 极淡阴影，且**仅浅色**；深色靠色阶分层；描边仍然禁止 |
| §5.2 / §5.7 | 圆环「超过目标后满圈」 | 双层圆环：外环满圈即止，内环表达超出比例，超出用暖色单色信号 |
| §5.3 | 字体 token 只有字号字重 | 增加 family 分层：数字/拉丁 Manrope（tnum 等宽），中文系统字体 |
| §5.7 柱图浮层 | 「浮层落在选中柱对侧，不遮挡该柱」 | 改为「详情条固定在图表上方，不浮动」 |
| §5.7 日历 | 三档着色 | 四档着色 + 图例 + 今日描边 |

改完在 `§7 验收清单` 末尾**新增**验收项（从 §7-30 起编号），每条可观察、可判定，例如「§7-30 目标 3h、实际 4h42m 时内环可见且长度等于超出比例」——不要写「界面更美观」这种没法验收的话。

---

## 七、提交与交付

- **每批一个提交**，中文 commit，格式参照现有提交（`feat(ui): VI-1 地基——Manrope 字体 + 背景渐变 + 双层圆环`）。
- 提交前：`git status` 不许有调试残留（`ui_dump*.xml`、`*.png` 临时截图、`local.properties` 之类），项目已有 `.gitignore` 规则，别绕过。
- 交付说明写 `docs/VI-UI视觉升级-交付说明.md`：每项做了什么、截图前后对比、测试增量、**没做的和为什么**。
- 不要 `git push`（负责人自己推）；不要 force push；不要 amend 已有提交。
- 不许改 `README.md`、不许改 `docs/需求文档` 之外的历史文档正文（只允许在本轮相关章节追加）。

---

## 八、本轮明确不做

- **8b-2 Google Drive 云同步**（`SyncCredentials` 里已经留了 OAuth 字段注释，但那是另一条线，别在这轮碰）
- 桌面小组件（Glance，批 12）
- 应用限额 / 到量提醒（批 11）
- AI 分析（14b）
- 多机型适配（批 13）

如果 MiMo 想顺手做上面任何一项，答案是**不做**——先把视觉做完做对，比同时开五条线强。

---

## 九、设计稿关键色值（我从 PNG 采样，可直接对照）

只看结构不看颜色会做出「形似神不似」的成品，这几个值先记住（**它们来自设计稿固定的一套蓝色主题，不是项目现有的墨绿**）：

| 用途 | 值 |
|---|---|
| 主色（圆环、选中柱、主按钮） | `#2C576D` |
| 深墨（正文、选中柱顶） | `#14262F` |
| 次级蓝（未选中柱、构成第 2 段） | `#7FA6BA` |
| 浅蓝（构成第 3 段、日历第 2 档） | `#AFD2E4` |
| 极浅蓝（日历第 1 档） | `#E3EDF2` |
| 紫 tonal（连续记录卡、解锁卡） | `#CECCE6` |
| 页面背景顶部 / 中部 | `#F4F7F9` / `#EAF2F6` |
| 珊瑚 chip 底 / 字 | `#FBE9E1` / `#B04A28` |
| 浅绿对比 chip 底 | `#DDEFE8` |

**处理方式**：新增第 10 套预设 `MIST_BLUE`（seed = `#2C576D`），用 `derivePalette` 派生出完整色板，不要手写一堆散装色值。默认主题**先不改**（用户已选过偏好，突变更像 bug），是否把 `MIST_BLUE` 设为默认由负责人定——在交付说明里提一句就行。

---

## 十、自检清单（交活前逐条打勾）

- [ ] `./gradlew :app:testDebugUnitTest` → 0 failures，数量 ≥ 272
- [ ] `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL
- [ ] 新增测试做过变异验证（改坏实现 → 变红 → 还原）
- [ ] `ui/` 与 `uikit/` 里没有裸 `Color(0xFF...)`（主题文件除外）
- [ ] 四页在 360dp 宽下无横向溢出；内容按 520dp 上限居中
- [ ] 深色模式逐页看过，没有"深色卡片 + 深色阴影"糊成一团
- [ ] 系统「减少动画」打开后，圆环与转场能正常落终值
- [ ] 空态：无对比数据时不占行；无记录日不画 0 柱
- [ ] 截图放进交付说明，标清「真机 / 模拟器」
