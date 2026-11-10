# 批次计划评审报告（Kimi，2026-11-10）

评审对象：`docs/实施任务批次计划-v1.md`（9 批 + 前置步骤）
评审依据：需求文档 v2.3 §5–§7、README.md、工程代码实际盘点（contract/theme/uikit/legacy/build.gradle.kts/AndroidManifest/单测）
评审方式：独立核对，未假设计划正确；计划中引用的工程事实逐条验证。

## 一、计划所述事实的核对结果（全部属实）

| 计划声称 | 实际核对 |
|---|---|
| 契约骨架 v0.1：九主题×深浅 token、DateSelection、领域计算器、7 单测、EmptyUsageRepository | 属实（`UsageCalculatorTest` 恰为 7 个 @Test） |
| UsageRepository 六方法 | 属实（summary/dailyTrend/appRanking/hourlyDistribution/calendarLevels/pullLatest） |
| `MonthCalendar` 有 `week[i+k]!!` 非空断言 | 属实（第 95 行） |
| docs/ 内需求文档为 v2.3 | 属实 |
| build.gradle.kts 注释依赖清单（Room/WorkManager/DataStore/Shizuku/OkHttp） | 属实 |
| 无 git 基线，前置步骤需 git init | 属实（非 git 仓库） |

## 二、发现的问题（按严重度排序）

### P0-1　契约缺口：日历 streak、最久/最少日、低于2h天数、任意月份月视图，无批次认领

§5.7 页面需要的数据，`UsageRepository` 接口拿不到：

- **日历页连续记录卡**（当前 streak / 最长 streak / 起始日）→ 契约无任何 streak 接口，`Summary` 也没有该字段；
- **统计页双卡**（最久的一天 / 最克制的一天）与**日历页双卡**（最久的一天 / 低于 2 小时天数）→ `Summary` 只有 `longestSessionMin`（单次连续时长），没有「最久的一天（日期+时长）」「最少的一天」「低于 2h 天数」；
- **月视图跳到所选日期所在月** → `calendarLevels(anchor, weeks=20)` 只能取锚点向前 20 周；用户选中 6 个月前的日期时，月视图数据取不到，接口签名不支持任意月份。

计划全程把契约当固定边界（README「契约已定」），批 3 的 UiState、批 4 的页面接线、批 6 的「实现六方法」均未安排契约扩展。**批 4 做到日历页第一张卡时必然发现无数据可接，返工批 3/4/6 三处。**

**修改建议**：前置步骤增加「契约扩展 v0.2」任务（不改 §1–§4 数据口径，只扩 App 侧接口）：
- `UsageRepository` 增加 `streak(anchor): StreakInfo`（当前/最长/起始日）；
- 增加 `extremes(selection): ExtremesInfo`（最久/最少的有记录日、低于 2h 天数，口径照 §5.6：只统计有记录日、真实 0 分钟计入低于 2h）；
- `calendarLevels` 拆为 `yearLevels(anchor, weeks)` + `monthLevels(yearMonth)`；
- `EmptyUsageRepository` 同步补空实现；新增 2–3 个 `UsageCalculator` 单测覆盖 streak 连续段与 extremes 口径。

### P1-2　关键路径过长：批 6 整包依赖批 4+5，可把无页面依赖的部分前移

当前关键路径 1→3→4→6→7→8→9 共 7 环。但批 6 中的「Room schema/DAO/合并单事务/DataStore/解析入库管道」只依赖 contract + 批 5 解析器，与页面无关；只有「四页换真实仓库」需要批 4。

**修改建议**：直接启用预案拆 6a/6b 并前移 6a：
- 6a（Room+DataStore+合并策略+解析入库+合并纯函数单测）仅依赖批 5，可与批 3/4 并行，KSP 首次配置这一最高风险点提前暴露；
- 6b（Shizuku 通道+WorkManager+Empty→Room 切换+拉取状态机）依赖批 4+6a。
关键路径缩短 1–2 环。

### P1-3　Google 登录依赖无落点

批 6「启用注释中的依赖」清单里没有 Google Sign-In / Credential Manager；批 8 的 `GoogleDriveProvider` 任务也没提添加该依赖。批 8 执行时会现找现加。
**建议**：批 8 任务清单第一行写明「添加 Google 登录依赖（play-services-auth 或 Credential Manager）」。

### P2-4　Material3 实验 API 与 §6「只用稳定 API」的小冲突

BOM 2024.06.00（material3 1.2.x）中 `ModalBottomSheet` / `DatePicker` / `DateRangePicker` 仍需 `@OptIn(ExperimentalMaterial3Api)`，批 3 全用到。不阻塞，但计划风险表未提。
**建议**：批 3 风险项注明「接受 @OptIn 并记录；不为此升级 BOM（牵动全工程）」。

### P2-5　Shizuku manifest 声明遗漏

`dev.rikka.shizuku:provider` 需在 AndroidManifest 注册 provider，批 6 只提「启用依赖」未提 manifest 改动，会卡「Sync 成功但运行无 Shizuku」。
**建议**：写入批 6b 任务清单。

### P2-6　批 7「备份与同步（本批仅 HTML+占位）」措辞有红线风险

HTML 导出在 §5.8 属「权限与数据」组；「备份与同步」组若做成可点假按钮则违反 §6「不用假按钮掩盖未完成能力」。
**建议**：改措辞为「备份与同步组显示诚实待办态（灰显+说明文字），不可点击；HTML 导出归入权限与数据组」。

### P2-7　批 9 对比度抽查可脚本化

9 套主题 × 深浅 = 18 套色板人工抽查量大。`FreshPalette` 已是结构化数据，可写 JVM 单测直接算全部语义色对的 WCAG 对比度并断言 ≥4.5 / ≥3.0，把人工抽查变成回归测试。可提前到批 2（色板已就绪）。

## 三、计划的合理之处（确认成立）

- 双泳道（UI 批 1–4 / 纯 JVM 解析器批 5）划分正确，无资源冲突；
- 批次大小合理：批 1–5、7、9 各为一次会话量级，批 6/8 偏大但均有拆分预案；
- 验收映射 15 条全覆盖，无缺漏错配（#1 拆 6+7、#2 拆 5+6 的说明准确）；
- 红线识别完整：§1–§4 不可改、sample 只读、禁假数据、语义 token、Tomato 不抄源码；
- 风险表质量高：`hour_matrix` 口径、M:SS 坑、KSP 对齐、`week[i+k]!!` 均与代码实际一致；
- 每批固定出口条件（单测全绿 + 人工核对 + 一提交）可执行、可回滚。

## 四、结论

**有条件采纳。** 批次结构、顺序、并行策略、验收映射均成立，可作为执行基线；但必须先落实两项修正：

1. **前置步骤增加「契约扩展 v0.2」**（P0-1），否则批 4 必返工；
2. **批 6 拆 6a/6b 并前移 6a**（P1-2），缩短关键路径、提前暴露 KSP 风险。

P1-3、P2-4/5/6/7 为任务清单级补充，改完即可开工。

## 五、建议登记（汇总表格式）

| 编号 | 来源 | 类别 | 建议摘要 | 影响文件 | 理由 |
|---|---|---|---|---|---|
| S01 | Kimi | 工程 | 前置加「契约扩展 v0.2」：streak/extremes/monthLevels 接口 + 空实现 + 单测 | contract/UsageRepository.kt、EmptyUsageRepository.kt、UsageCalculator.kt、计划前置步骤 | §5.7 日历/统计双卡与 streak 卡无数据源，批 4 必返工 |
| S02 | Kimi | 工程 | 批 6 拆 6a/6b，6a 仅依赖批 5 前移并行 | 计划批 6 | 关键路径 7 环过长，KSP 风险应提前暴露 |
| S03 | Kimi | 工程 | 批 8 补 Google 登录依赖落点 | 计划批 8、app/build.gradle.kts | GoogleDriveProvider 无依赖来源 |
| S04 | Kimi | 工程 | 批 3 风险注明 ModalBottomSheet/DatePicker 需 @OptIn | 计划批 3 | BOM 2024.06.00 下仍为非稳定 API |
| S05 | Kimi | 工程 | 批 6b 补 Shizuku provider 的 manifest 注册 | 计划批 6、AndroidManifest.xml | 只加依赖不注册会运行失败 |
| S06 | Kimi | 需求/UI | 批 7 备份与同步组改为灰显待办态，HTML 导出归权限与数据组 | 计划批 7 | 避免触 §6 假按钮红线、对齐 §5.8 分组 |
| S07 | Kimi | 工程 | 批 9（或提前批 2）加色板对比度 JVM 断言测试 | 计划批 9、theme/FreshPalette.kt | 18 套色板人工抽查量过大，可回归化 |
