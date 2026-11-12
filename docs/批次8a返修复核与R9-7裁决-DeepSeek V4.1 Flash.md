# 批次 8a 返修复核 + R9-7 裁决说明（DeepSeek V4.1 Flash，2026-11-12）

> 对象：提交 `060696d`（6g/7/8a/10a/14a 评审修正）+ 本轮真机反馈两条 bug。
> 方法：`--rerun-tasks` 强制重跑、逐条读改动、用新增测试的断言形态判断是否真能变红，不采信提交信息里的自述。
> 结论：**返修达标，8a 可结案进 8b**；R9-7 见第一节，建议 ①（登记偏差）。

---

## 一、需要你裁决的只有一件事：R9-7（§4 列名 vs 物理列名）

### 1.1 现象是什么

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

### 1.2 为什么这算「要你拍板」而不是我直接改

- §4 那句红线原文是「**只增不改**：原四表、原字段与既有口径全部保持不变」（CR-01 加的）。它保护的是**逻辑字段与口径**，不是拼写；但字面上「原字段不变」也能被读成「列名不变」，两种解释都站得住，所以这是**解释权**问题，不是技术判断。
- 改物理列名不是改个注解：`@ColumnInfo(name = "screen_min")` 之后必须 `version 5→6` + 五张表重建迁移（minSdk 26，SQLite 不支持 `RENAME COLUMN`，只能 建新表→灌数据→删旧表→改名）。风险落在**已有数据的迁移**上，而迁移正是当前测试覆盖最薄的一环（见 3.3）。
- 收益只有「文档与库一致」。没有任何用户可见行为、性能、体积差异。

### 1.3 两个选项

| | ① 维持现状（推荐） | ② 统一改列名 |
|---|---|---|
| 动作 | 批 9 文档勘误：把 §4 标注为「**逻辑名**」，并在工程说明里给一张「逻辑名 → 物理列名」对照表；`MigrationTest` 里已写注释说明为何 SQL 用 camelCase | `@ColumnInfo` 全表加名 + `MIGRATION_5_6` 五表重建 + 备份兼容验证 + 迁移测试 |
| 风险 | 零代码风险；残余风险是**下一个人**又照着 §4 写 SQL（加一张对照表 + 一条注释即可挡住） | 迁移写错 = 升级即崩；需要真机拿旧库跑一遍；「原字段不得改义」的边界要再解释一次 |
| 成本 | 10 分钟文档 | 半天 + 一轮真机验证 |
| 一致性收益 | 文档层解决 | 库层解决 |

**我的建议：①。** 物理列名是内部实现细节，SQLite 没有「契约」概念，对外契约（CSV/JSON）已经是对的；用一次高风险全表迁移换取「文档里的拼写与库一致」，性价比不成立。真正要治的是「读文档的人不知道 §4 是逻辑名」——这靠一行标注就能根治。

若你选 ②，我建议同时把 `schemaVersion` 一起处理（库 6 / 备份仍 4，两套版本号本来就是独立语义），并先在真机上用现有 v5 库跑一遍迁移再合。

---

## 二、修复核对（R9-1 ~ R9-6）

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

## 三、本轮真机两条 bug

### 3.1 a）刷新后转圈不消失 —— 术语与根因

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

### 3.2 b）「低于 x 小时」要滚轮选择 —— 术语与实现

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

## 四、放行判定

- **8a：结案。** R9-1/2/4/5/6 达标，R9-3 部分达标（1→4 迁移排期批 9）。
- **可以进 8b（WebDAV / Google Drive 云同步）**，前提是把上一轮提的那条原则写进 8b 的验收：**远端数据先过自洽校验**——现在同一入口（`BackupRepository.merge`）已经覆盖本地与远端，8b 不要另开一条「直接写库」的旁路。
- R9-7 等你裁决（建议 ①）；R9-9/R9-10 已修，R9-11 排期批 9。

---

## 附：本轮改动文件

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
