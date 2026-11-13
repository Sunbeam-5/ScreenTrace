# WRAPUP · 历史批次交付说明

> 归档合并：2026-11-13。本文由 10 份历史文件合并而成，每个 `## 【来源】` 节即一份原文。
> **正文内容一字未改**；仅把原文内部的 markdown 标题整体下移两级（代码块内不动），以免与分节标题撞级。
> 文中的测试数、验收条数、提交号、文档路径均为**成文时点快照**，不代表现状；现状以 `docs/项目文档地图.md` 第 2 节为准（见其 §6 快照约定）。

> 覆盖 6a~14a 各批次的交付说明（VI 视觉升级交付说明仍为活文档，单独保留在 `docs/VI-UI视觉升级-交付说明.md`）。


---


## 【来源】批次6a返修-交付说明.md

> 原路径 `docs/批次6a返修-交付说明.md`（合并后原文件已移除，原始字节见 git 历史 `a89bd33:docs/批次6a返修-交付说明.md`）

### 批次 6a 返修交付说明（2026-11-10）

针对 `docs/批次6a评审-DeepSeek V4.1 Flash.md` 的整改记录。结论：**P0 已修、P1 四条全部落地（含此前缺失的管道测试）、P2 修四条，其余两条留批 6b/9 并写明理由**。

验证命令与结果：

```
JAVA_HOME=C:/Users/Administrator/.jdks/jbr-21.0.11 ./gradlew.bat :app:testDebugUnitTest :app:assembleDebug
→ BUILD SUCCESSFUL；61 测，0 fail，0 skip（原 44 + 新增 17）
```

| 类 | 数量 | 说明 |
|---|---|---|
| `IngestPipelineTest`（新） | 10 | 真 Room + Robolectric，管道级 |
| `RoomUsageRepositoryTest`（新） | 7 | 覆盖三态 / name 优先 / 分类覆盖 |
| `MergePolicyTest` | 6 | 纯函数（原有） |
| `UsageDumpParserTest` | 17 | 解析器（原有） |
| `UsageCalculatorTest` / `FreshPaletteContrastTest` | 16 / 5 | 原有 |

---

#### 一、P0　`deleteByMs` 多日重拉必炸

- `NotificationEventDao.deleteByMs(List<Long>)` → 改为 `deleteBetween(startMs, endMs)`，调用点改用 `IngestPipeline` 已有的窗口两端（`min-1000` / `max+1000`）。
- 新增结构守卫测试 `通知删除接口不得再接受主键列表`：反射检查 DAO 不存在任何吃 `List` 的 `delete*` 方法，防止后续被"顺手改回去"。
- 体量冒烟：4837 条通知连灌两次，两次 `ok=true`、行数稳定 4837、`failedMs` 为 0。
  - 诚实说明：**Robolectric 跑宿主 SQLite（上限 32766），复现不出 Android 8–11 的 999 上限**，所以这条不靠"跑崩"来证明，靠上面的接口形态守卫。

#### 二、P1-1　分类永远是 OTHER

- `AppNames.categoryOf(pkg)` 的内置表**不是手写 55 条**，而是直接从对账脚本 `parse_usage_v2.py` 的 `CATEGORY_RULES` 生成 **176 个包**，与 `screen_usage_report.json` 的 `apps_range[].category` 逐包一致（脚本核过集合完全相同）——避免"代码里另立一套分类口径、排行图例与对账基线打架"。
- `IngestPipeline` 写库时按该表落 `category`。
- `RoomUsageRepository.appRanking` 读取层叠加 `SettingsStore.categoryOverrides`（用户改过的优先），构造函数注入 `settings`（可空，6b 接线时不破坏既有调用）。
- 真实 dump 里仍有 15 个包不在表内（`com.bbk.launcher2` 桌面、`com.xiaomi.smarthome` 米家、`com.twitter.android`、`com.sinovatech.unicom.ui` 联通、`com.alicloud.databox`、`jp.pxv.android` 等），按 §4 归 `OTHER`，属"用户可改"范畴，代码注释已写明不另立口径。

#### 三、P1-2　管道单测缺失（本轮重点补课）

新增 `testImplementation`：`robolectric:4.12.2`、`androidx.test:core-ktx`、`room-testing:2.6.1`、`kotlinx-coroutines-test`；`testOptions.unitTests.isIncludeAndroidResources = true`。

`IngestPipelineTest`（`Room.inMemoryDatabaseBuilder` 真库）覆盖：

1. 同一份 dump 连灌两次：五张表行数与取值全不变（对应验收项「连续拉取不重复计数」）；
2. `pull_log` 每次拉取各追加一行且标成功；
3. 同秒 3 条真实通知不丢；重拉不增不减（对应验收项「同秒多条通知不丢失」）；
4. 第二次只拉到同秒 1 条时，按「整边取大」保留 3 条（对应验收项「双源合并不重复」）；
5. 先 1 条后 3 条的增长方向——这条才真正走「删窗口 → 批量写回」分支（P0 所在路径）；
6. 第二次拉到更小日聚合时整行保留，不回退；
7. 4837 条通知两次拉取不失败；8. 删除接口形态守卫；
9. 空 dump 记 `ok=false` + `note="dump 为空"`（P2-2）；
10. 入库时按内置映射写分类（`微信=SOCIAL`、`B站=VIDEO`、`桌面=OTHER`）。

`RoomUsageRepositoryTest` 覆盖：

- `pull_log.ok=false` 且当天无成功的日期 → `Coverage.GAP`；同日后来成功则不再算缺口（P1-3）；
- 窗口起点按指标分表取（P2-3）：解锁表起点晚于通知表时，查解锁不得被拉宽成「真实 0」；
- 详情页通知排行优先库内 `name`，仅为包名时回退内置中文名（P1-4）；
- 用户覆盖分类压过内置映射（P1-1 读取层）；
- 无记录时不出峰值时段、不伪造数值。

**测试有效性验证（防"假绿"）**：把 `MergePolicy.mergeNotifications` 变异成"只返回 incoming"，`IngestPipelineTest` 立即报红 1 条（`9 tests completed, 1 failed`），恢复后全绿——证明这批测试不是摆设。

#### 四、P1-3　`gapDays` 退化成两态

- `RoomUsageRepository.computeGapDays`：`pull_log.ok=false` 的 `pulledAt` 日期 − 当天有 `ok=true` 的日期 = `gapDays`；`metricDetail` 不再传 `emptySet()`。三态（NO_RECORD / GAP / RECORDED）在真实查询层成立。
- DAO 补 `failedMs()` / `okMs()`（本地纯查询，不需要网络）。

#### 五、P1-4　`appLabels` 忽略库内 name

- 抽 `displayName(pkg, storedName)`：库内 `name` 非空且不等于包名时优先，否则回退 `AppNames.nameOf`。`appRanking` 与 `appLabels` 共用同一函数。

#### 六、P2 处理

| # | 状态 | 说明 |
|---|---|---|
| P2-1 schema 导出 | ✅ 本轮做 | `exportSchema = true` + `ksp { arg("room.schemaLocation", ...) }`，产出 `app/schemas/.../1.json`（六表字段已核对：7/6/3/5(含自增 id)/1/4 列）。注释写明 DB version 与备份 schemaVersion 是两套版本号 |
| P2-2 空 dump 仍 ok | ✅ | 空结果直接 `ok=false`、`note="dump 为空"`，且写日志失败不会盖掉原始异常 |
| P2-3 windowStart 混两表 | ✅ | 按 `MetricKind` 分别取本表 `minMs()` |
| P2-4 unlock PK 单一毫秒 | ✅（注释） | 实体注释写明折叠风险与"日计数 > 事件数"时的升级路径；未改 schema（version 1 尚无迁移，改动会牵动解析器调用约定） |
| P2-5 240 双份 | ✅ | `AppViewModel.DEFAULT_GOAL_MIN` 收敛为引用 `SettingsStore.DEFAULT_GOAL_MIN` |
| P2-6 `appRanking` 的 date 语义 | ⏳ 批 6b | 需给 `AppUsage.date` 可空或新增 `AppUsageRange`，属契约改动，与 6b 排行页一起改更安全 |
| P2-7 命中旧值仍 REPLACE | ✅ | 三处 `if (merged != existing)` 才写 |

另外顺手：`IngestPipeline` 结构整理（`logPull` 抽出、缩进修正），此前 `flow.first` 漏导入导致编译失败的问题已修。

#### 七、留给批 6b 的明确事项

1. `P2-6` 的 `AppUsage.date` 语义（契约层）；
2. 真实 dump → `ParseResult` 的 Shizuku 通道接上后，用 **真实 15MB dump** 重跑本章程：确认 4837 条通知重拉在真机（API 26–30）也不再抛 `too many SQL variables`；
3. `PackageManager` 回填 `app_usage.name`（P1-4 的写入侧，读取侧已就绪）；
4. 事务失败的"中途失败必回滚"目前**没有自动化用例**（Robolectric 里难以在不改产线代码的前提下注入事务内异常）。计划在 6b 接真实拉取时，用「解析成功但写库抛错」的可注入 fake 补一条。

---


## 【来源】批次6b交付说明.md

> 原路径 `docs/批次6b交付说明.md`（合并后原文件已移除，原始字节见 git 历史 `a89bd33:docs/批次6b交付说明.md`）

### 批次 6b 交付说明 — Shizuku 拉取 + WorkManager + 真实接线

> 会话背景：公司 mimo 额度 402 用尽，由 DeepSeek V4.1 Flash 接替继续开发。
> 6a 返修已提交（`c92a693`），本批为 6b。设备受限：插着的手机**没装 Shizuku**，
> 且 20:20 前被其他测试任务占用，因此本轮先把代码写完、单测跑绿，真机部分见文末。

#### 一、目标对照（计划 §批次 6b）

| 计划要求 | 落点 | 状态 |
| --- | --- | --- |
| Shizuku 通道跑双 dump | `data/pull/ShizukuShell.kt`、`ShizukuUsageProvider.kt` | 完成 |
| 授权状态机 / 引导 | `ProviderStatus`（未安装/未授权/可访问/临时故障）+ 各态中文文案 | 状态机完成，引导 UI 在批 7 向导 |
| 降级 `UsageStatsManager` 通道 | `UsageStatsUsageProvider.kt` + `ForegroundBucketer.kt` | 完成（口径见下「诚实边界」） |
| 调批 5 解析器 | `ShizukuUsageProvider.parse = UsageDumpParser.parse(checkin, human, zone)` | 完成 |
| 事件流与日聚合同一事务写入 | `IngestPipeline.ingest` 单事务（6a 已具备），6b 只是接上真实源 | 完成 |
| App 打开 30 分钟节流 | `PullCoordinator.pullIfStale` | 完成（分档，见下） |
| WorkManager 每日拉取 | `DailyPullWorker` + `PullScheduler` | 完成 |
| 成功后预留自动同步钩子 | `PullCoordinator(onSuccess = …)`，批 8 链式上传 | 完成（空实现） |
| 拉取状态机 6 态 | `PullOutcome` + `HistoryCoverage` + `isPartialHistory` | 完成 |
| Manifest 注册 `dev.rikka.shizuku:provider`（S05） | `AndroidManifest.xml` + `build.gradle.kts` | 完成 |
| `AppViewModel` 换真实仓库 | `AppContainer` 装配，`AppNav` 从 `ScreenTraceApp` 取容器 | 完成 |
| 已有历史时失败保留旧数据 + 顶部内联错误 | `PullCoordinator` 不动库 + `NoticeBar(actionText="重试")` | 完成 |
| §3-8 展示名回填（真机验证时补漏） | `AppLabelResolver` + `IngestPipeline.labelOf` + 清单 `<queries>` | 完成（见下） |

**补漏说明**：`IngestPipeline` 原来把 `name` 直接写成包名，注释却写着「批 6b 用
PackageManager 回填」——回填实际没做。真实 dump 里 `com.miui.home`、`com.example.bluetoothota`
这类不在内置映射表的包会在排行页直接显示包名字符串。本轮补上：
`AppLabelResolver`（`getApplicationLabel` + 缓存，失败回退映射表再回退包名），
清单加 `MAIN/LAUNCHER` 的 `<queries>`（Android 11+ 不声明就查不到别的应用，回填等于白做；
不用 `QUERY_ALL_PACKAGES` 这种过宽权限）。同时把 `MergePolicy.mergeApp` 的名字与数值解耦，
否则「库内是包名占位 + 本次入库数值更大」会把握手回填好的真名冲回包名。

#### 二、四个必须说清的技术决策

##### 1. Shizuku 13 没有 `Shizuku.newProcess` 了

`api:13.1.5` 的公共面只剩 `Shizuku.getBinder()`——`javap` 实测无 `newProcess`，
编译器直接报 `Cannot access 'newProcess': it is private in 'Shizuku'`。

改走 AIDL 层：

```
IShizukuService.Stub.asInterface(Shizuku.getBinder())
    .newProcess(arrayOf("sh","-c",cmd), null, null)  →  IRemoteProcess
```

**为什么不用官方推荐的 UserService**：UserService 要把结果经 AIDL 回传 String，
而 binder 单次事务上限 1MB，10 天 `dumpsys usagestats --checkin` 约 **13MB**，
必然 `TransactionTooLargeException`。`newProcess` 把 stdout 落成 `ParcelFileDescriptor`，
按块读不受事务限制——**这是唯一能读完整 13MB 的路**。

代价：依赖 `moe.shizuku.server.*` 内部包。一旦 Shizuku 升级改 AIDL，这里运行时抛异常
→ 协调器自动降级到 UsageStats，不崩。已在类注释写明。

##### 2. 降级通道「诚实边界」：不写 `day_summary`

`UsageStatsManager` 拿不到 `SCREEN_*` / `KEYGUARD_*` / `NOTIFICATION_*`，所以
解锁次数、通知打扰/已查看、日聚合的 `unlocks/launches` 都没有数据。

因此降级通道**只写 `app_usage` 与 `hour_usage`，绝不写 `day_summary`**：
写进去就等于向 UI 宣称「今天解锁 0 次」这种假 0（§4/§7-16 红线）。
代价是今日/统计页在降级通道下是空态，排行与时段仍可用——这是诚实降级，不是缺功能，
`pull_log.note` 会写明来源。

另外，降级通道的**前后台配对不能只靠 `ACTIVITY_PAUSED` 收尾**：应用进程被系统杀掉时
不会有 paused 事件，那段区间会一路延到拉取时刻，把之后所有应用的时间都算到它头上
（排行页与时段分布整页虚高，且虚高多少取决于拉取时刻——这种错真机上肉眼极难发现）。
所以 `ForegroundBucketer.spansOf` 以「**下一个应用进入前台**」作为上一段的结束，
即使丢了 paused 也不会错，同应用连续多次 RESUMED 也不切碎区间。

##### 3. 节流必须分档：成功 30 分钟，失败 60 秒

计划原文是「App 打开 30 分钟节流」。如果失败也吃 30 分钟，会出现这个死循环：
用户看到「Shizuku 未授权」→ 去 Shizuku 里点允许 → 切回 App → 被节流挡住 → 界面毫无变化
→ 用户认为功能坏了。

所以：上次**成功** 30 分钟；上次**失败/空 dump** 只冷却 **60 秒**。失败路径本来就不贵
（权限探测不打 dump），60 秒足以挡住「反复切前台把界面卡在拉取态」的抖动。

##### 4. 「空 dump」是独立状态，且判定只有一个出处

`ParseResult.isEmpty`（五类产物全空）同时服务于：
- `IngestPipeline`：空则**不记成功**（6a 评审 P2-2）；
- `PullCoordinator`：空则返回 `PullOutcome.EmptyDump`，界面文案是「系统暂无可解析记录」
  而不是「拉取失败」。

两处各写一份判定，将来补新产物类型（如亮度事件）就会出现「管道认为非空、界面认为空」
的状态错乱。原实现里 `EmptyDump` **不可达**（管道返回 ok=false，协调器一律翻成 Failure），
本轮修掉了。

#### 三、顺手修掉的两个真 bug（不是本批计划内，但不修会炸）

1. **`ScreenTraceApp.onCreate` 会被 WorkManager 拖垮**
   `PullScheduler.ensureScheduled` 在 WorkManager 未初始化时抛
   `IllegalStateException: WorkManager is not initialized properly`（部分 ROM 禁用后台自启、
   被动升级后进程被掐都会遇到）。Application.onCreate 抛异常 = 用户连界面都进不去。
   已改为 `runCatching` + `Log.w`：排程只是兜底，主力是打开 App 时的节流拉取。
   （这条是 Robolectric 一次性暴露 34 条测试全红才发现的。）

2. **`AppViewModel.persist` 的接收者歧义**
   原写法 `persist { setThemeVariant(variant) }` 配 `suspend SettingsStore.() -> Unit`：
   lambda 里无接收者调用会解析到**最近作用域**，极易误调 `this@AppViewModel.setThemeVariant`
   造成**无限递归**（编译能过，运行时栈溢出）。改成 `(SettingsStore) -> Unit` + `it.` 彻底消灭。

另外 `RoomUsageRepository.pullLatest()` 原来返回
`PullResult(false, "拉取通道批 6b 接线…")` ——6b 已接线，这句话就成了假失败串。
现改为由 `AppContainer` 注入 `pullCoordinator.pull().asPullResult()`，未注入时明确返回
「当前环境未接入拉取通道」（§6 红线：不许假按钮/假结果）。

#### 四、验证

```
./gradlew :app:testDebugUnitTest :app:assembleDebug   → BUILD SUCCESSFUL
93 tests, 0 failed, 0 skipped   （6a 末为 61，本批新增 32）
```

新增：

| 测试类 | 数量 | 覆盖 |
| --- | --- | --- |
| `PullCoordinatorTest` | 10 | 节流分档、手动拉取不节流、降级并记录来源、双通道不可用、失败保留旧数据（六表逐行比对）、空 dump 独立态 + `pull_log` ok=false、并发 `AlreadyRunning`、首次必为部分历史、设置页文案映射 |
| `ShizukuUsageProviderTest` | 8 | 命令形态（必带 `--checkin`）、checkin 失败不产半份数据、checkin 空输出按失败、明文失败仍成功但带 warning、两段正常无 warning、解析异常转失败、无 Shizuku 环境状态探测不抛 |
| `ForegroundBucketerTest` | 12 | 分桶：单桶/跨小时/跨零点/跨多小时/零头/空输入/零长度区间；**配对**：正常配对、丢 paused 时以切换收尾、同应用连续 resumed 不切碎、仍在最前台截到拉取时刻、乱序事件 |
| `ShizukuEndToEndTest` | 2 | **真实 dump 样本**走 读→解析→入库→仓库读出 全链路；断言「通道报出的数 == 直接调解析器的数」「入库不吞行」「连续拉两次六表规模完全不变」 |

其中 `ShizukuEndToEndTest` 是本批最有价值的一条：它是**唯一不依赖真机**就能证明
「通道没把数据搞坏」的证据，而且把验收项「连续拉取不重复计数（含事件表）」
在通道层（而不只是 6a 的纯管道层）钉住了。

**变异验证（防假绿）**：把 `pullIfStale` 的 `if (last.ok) minIntervalMs else FAILURE_RETRY_MS`
改成恒为 `minIntervalMs` → `9 tests completed, 1 failed`，恢复后全绿。测试有牙齿。

**测试里的一处诚实说明**：`ShizukuEndToEndTest` 用 `AssumeGranted` 包装把通道状态固定为
AVAILABLE——因为协调器先查状态再读，而 Robolectric 里没有 Shizuku 服务，状态必然
「未安装」。这是**显式声明「假设已授权」**，不是偷偷放宽 status 断言（权限探测由
`ShizukuUsageProviderTest` 单独覆盖）。

#### 五、真机验证（Xiaomi 2407FRK8EC / Android 16 / HyperOS 3）

设备可用后做的第一件事不是装 App（被 HyperOS「通过 USB 安装」策略挡住，
`INSTALL_FAILED_USER_RESTRICTED`，换 installer 身份也无法绕过），而是把**这台机器
的真实 dump 直接喂给解析器**——因为此前所有 golden 样本都来自另一台旧机型，
Android 16 的 `dumpsys usagestats` 格式有没有漂移从来没验过。

抓取：`adb shell dumpsys usagestats --checkin` = **13.1MB / 52858 行**，
明文版 4.1MB / 376KB 事件。解析耗时 **1.05 秒**（单线程，13MB String 全量读入）。

**结论：没有格式漂移，解析结果与对账脚本 `parse_usage_v2.py` 逐日吻合。**

| 日期 | 屏幕时长（本 App / 对账脚本） | 解锁时长 | 最长会话 | 启动次数 |
| --- | --- | --- | --- | --- |
| 11-02 | 644 / 644.1 | 644 / 644.1 | 323 / 322.8 | 93 / 90 |
| 11-03 | 1241 / 1241.1 | 1241 / 1241.1 | 458 / 458.2 | 12 / 12 |
| 11-04 | 1440 / 1440.0 | 1440 / 1440.0 | 1440 / 1440.0 | 2 / 2 |
| 11-05 | 1341 / 1340.7 | 1341 / 1340.7 | 553 / 553.5 | **452 / 100** |
| 11-06 | 1285 / 1285.0 | 1285 / 1285.0 | 482 / 482.2 | **859 / 84** |
| 11-09 | 1421 / 1421.4 | 0 / 0 | 0 / 0 | **4122 / 38** |

三类指标（屏幕时长 / 解锁时长 / 最长会话）**逐日完全吻合**，说明区间层、跨零点切片、
解锁配对、多账户镜像收敛在 Android 16 上都成立。

##### 三处差异，都是「已定义行为」而非 bug（如实记录，避免复查时误判）

1. **启动次数从 11-05 起分叉**。根因是这台机器有一个 `user=999` 手机分身，11-05 之后
   在跑自动化：分身当日 `ACTIVITY_RESUMED` 达 335 / 768 / 6982 条，而主账户只有
   117 / 91 / 91 条。按 §2.2（CR-02 / P0-1）的既定设计，分身/隐私空间是**真人独立空间，
   事件与应用都保留**，所以 452 = 117+335 是对的；对账脚本只取主账户，故为 100。
   另外 §4 明确规定日级 `launches` 是 `ACTIVITY_RESUMED` 计数、`app_usage.launches` 才是
   `appLaunchCount`，两者**不得混用**——两边本来就不同源。
   > 附带结论：若产品上希望「启动次数」接近系统自报的 appLaunchCount，需要在 UI 上
   > 说明口径（或在批 7 设置里提供「包含分身」开关），不能在解析层偷偷改。
2. **解锁次数全程为 0**。查证：这台机器 10 天内 `KEYGUARD_HIDDEN` 事件**一条都没有**
   （只有 2 条 `KEYGUARD_SHOWN`），因为它作为测试机长期亮屏、不锁屏。所以 0 是**真实值**，
   不是解析失败（对账脚本同样给出 unlocks=0）。
   > 但这暴露一个 UI 风险：**「拿不到」与「真是 0」在当前契约里长得一样**。
   > 建议批 7 前把 `DaySummary.unlocks` 改成可空或加 `unlocksAvailable` 标志，
   > 否则在不锁屏/不产生 keyguard 事件的机型上会显示「今日解锁 0 次」这种假 0（§7-16）。
3. **顶部多出 `Flags:` 头**（`android.app.usage.use_parceled_list: true` 等）。解析器按行
   特征匹配，未受影响；已确认无副作用。

##### 仍未完成

**APK 装不上**：HyperOS 3 需要用户在开发者选项里手动打开「通过 USB 安装」
（部分版本还要求登录小米账号 + 插卡）。在此之前 `adb install` 与 `pm install` 一律
`INSTALL_FAILED_USER_RESTRICTED`，设备侧设置项也没有通过 `settings` 暴露，无法脚本化绕过。
因此**降级通道的真机冒烟（装 App → 启动 → 授权使用情况访问 → 看诚实空态）尚未执行**。

#### 六、未完成 / 待真机

1. **真机对账（§批次 6b 验收线 ≤5 分钟）未做**：本机**没装 Shizuku**，
   Shizuku 通道在真机上从未跑过一次。需要一台装了 Shizuku 并能授权 API_V23 的设备。
   UsageStats 降级通道可以在当前设备验（APK 待安装，见上）。
2. **授权回调后自动重拉未接**：需要 `Shizuku.addRequestPermissionResultListener` →
   触发 `retryPull()`。合理落点是批 7 首启向导（那里才有授权引导按钮），本轮不抢。
3. **`buildFeatures` 未开 R8 的 keep 规则**：`moe.shizuku.server.*` 在 release 混淆下
   需要 keep（下次开 minify 时必须补，否则 AIDL 被裁）。

#### 七、风险

- Shizuku AIDL 属内部包，升级可能失效 → 已设计为「运行时异常 → 降级 UsageStats」，不崩。
- 13MB 明文全量读入内存：一次约 13MB + 2.4MB 字符串，2GB 设备上需关注；若后续 OOM，
  改成流式喂解析器（当前解析器接口是 `String`）。

---


## 【来源】批次6d交付说明.md

> 原路径 `docs/批次6d交付说明.md`（合并后原文件已移除，原始字节见 git 历史 `a89bd33:docs/批次6d交付说明.md`）

### 批次 6d 交付说明 — 指标详情页（解锁 / 通知下钻）

> 范围：`docs/实施任务批次计划-v2.md` §批次 6d（CR-01 新增）：通用 `MetricDetailPage` +
> 解锁/通知两个详情页 + 今日页双卡升级为可下钻卡。验收对应 §7-16 全项。
> 本轮为 **6d 全量**（骨架 + 两页 + 表格 + 动效），未拆 6d-a/6d-b。

#### 一、目标对照

| 计划要求 | 落点 | 状态 |
| --- | --- | --- |
| 共用骨架 `MetricDetailPage` | `ui/detail/MetricDetailPage.kt` | 完成 |
| 两页共用同一 `metricDetail` 查询 | 复用 `AppViewModel.loadAll` 里那次调用（见「三处同源」） | 完成 |
| 派生 `UnlockDetailScreen` / `NotificationDetailScreen` | `ui/DetailScreens.kt`（替换批次 4 占位） | 完成 |
| 今日页双卡加下钻样式 | `uikit/MetricCard.kt` + `ui/TodayScreen.kt` | 完成 |
| 单日逐小时 / 区间逐日柱图 | `uikit/MetricBarChart.kt`（沿用 `CapsuleBarChart` 规格） | 完成 |
| 按住/拖动显示跟随气泡 | 同上（`awaitEachGesture`，滚动接管时收起） | 完成 |
| 三态区分（有记录 / 真实 0 / 无记录） | `MetricBarChart` 空档虚线 + `MetricTableModel` 行模型 | 完成 |
| 数据表三列 + 占比以区间合计为分母 | `ui/detail/MetricTableModel.kt` | 完成 |
| 通知详情应用排行（打扰/已查看可切换） | `DetailScreens.NotificationRanking` | 完成 |
| 解锁详情**不做**应用归因 | 改为「最高频时段 / 每小时平均 / 最活跃日期」 | 完成 |
| 返回恢复滚动位置 | `AppNav` 离开前存偏移 + `LaunchedEffect(page)` 回滚 | 完成 |
| 底部导航保持「今日」选中 | 复用 `AppPage.mainOf(returnTo)` | 完成 |

#### 二、四个必须说清的决定

##### 1. 卡片数字、图表、表格真的「同源」

§7-16 要求「通知打扰/已查看与卡片数字、详情图表、数据表三者一致」。原实现里今日页
解锁卡读的是 `Summary.averageUnlocks`（来自 `day_summary.unlocks`），详情页读的是
`metricDetail`——两套聚合，一旦某次拉取写入了日聚合但事件被截断，两者就会打架。

本轮把解锁卡也改成读 `metricDetail`（`TodayData` 新增 `unlock: MetricDetail?`），
通知卡本来就读它。于是**卡片、柱图、表格、摘要全部来自同一次查询结果**，
UI 层只做展示与百分比换算，不再有第二套聚合。

##### 2. 图表与表格不各算一套

表格行由 `MetricTable.rows(detail)` 从 `detail.points` 直接映射：`count` 取点的 `value`，
占比分母取 `detail.total`。因此「表格占比之和 = 100%」是可测的恒等式（已用单测钉住），
不存在图表 38 条、表格加起来 42 条这种事故。

##### 3. 三态是靠**不画**来区分的

- `RECORDED` 且 `value > 0`：画实体柱；
- `RECORDED` 且 `value = 0`：**不画实体柱**（真实 0，表格里显示 0）；
- `NO_RECORD` / `GAP`：画虚线空档框，表格里显示「该时段无记录 / 该日无记录」、占比「—」。

换句话说：空档不是「矮柱」也不是「0 柱」，是一个明确不同的形状与文案——
把无记录画成 0 的一排柱子是 §5.7 明令禁止的那条红线。

##### 4. 解锁为 0 时要分清「真 0」与「拿不到」

6b 真机验证发现这台 HyperOS 设备 10 天内 `KEYGUARD_HIDDEN` 一条都没有（长期亮屏、
不锁屏）。这种机型上解锁详情会显示 0，与「事件流压根没拉到」长得一样。
所以 `total == 0` 时摘要区额外给一句：「本次区间内没有解锁事件记录。部分机型不产生
锁屏事件，或事件流尚未拉取到。」——不猜测、不掩饰。

#### 三、动效（§5.7 第 3 条，总时长 ≤500ms）

| 动作 | 实现 | 时长 |
| --- | --- | --- |
| 卡片按压 | `graphicsLayer` 缩放至 0.98（整卡含底板） | 80ms |
| 页面入场 | 淡入 + 上移 16dp，两页同一转场 | 240ms |
| 大数字入场 | 淡入 + 上移 12dp，**不做数字滚动** | 180ms |
| 柱体生长 | 基线 `scaleY 0→1`，逐柱错开 8ms、**总错开封顶 360ms** | 220–260ms |
| 图表 ↔ 数据表 | 旧淡出 120ms → 换内容 → 新淡入 160ms | 280ms |
| 骨架 | 加载超 **150ms** 才出现；已有缓存直接渲染，不重播入场 | — |
| 减少动画 | 取消错开/位移/缩放，直接淡入（`rememberReduceMotion`） | 0 |

容器高度固定 264dp：图表与表格切换时页面不跳动，表格在容器内自己滚动
（§5.7 明确禁止用弹窗承载大表格）。

#### 四、验证

```
./gradlew :app:testDebugUnitTest :app:assembleDebug   → BUILD SUCCESSFUL
105 tests, 0 failed, 0 skipped   （6b 末为 96，本批新增 9）
```

新增 `ui/detail/MetricTableTest`（9 条，纯 JVM 无 Android 依赖）：

| 用例 | 钉住的东西 |
| --- | --- |
| 单日 24 行、标签 0–1时…23–24时 | 表格结构与时间升序 |
| 真实 0 显示 0 / 窗口外显示无记录 | 三态不互相伪装 |
| 拉取缺口不画成 0 | `GAP` 与 `RECORDED` 区分 |
| 占比分母 = 区间合计且求和 ≈100% | 图表与表格同源 |
| 区间态逐日出行、无记录日不参与占比 | 区间语义 |
| 摘要单日/区间文案 | 「当日累计」vs「日均 6 次 · 区间合计 12 次」 |
| 明细三项无数据时显示破折号 | 不伪造「最高频时段」 |
| `hasAnyRecordedPoint` 判定全缺口/窗口外 | 空态不刷成一排空框 |
| `percentInt` 0 分母不返回 NaN | 除零防护 |

#### 五、未做 / 风险（如实）

1. **真机视觉未验**：HyperOS 3 的「通过 USB 安装」开关未开，APK 装不上（6b 起就卡在这），
   柱图间距、气泡跟随手感、1.3 倍字体与 360dp 窄屏下的换行都还只是按规格推算。
   一旦能装，第一件事是跑：今日页点双卡 → 详情 → 切图表/表格 → 返回看滚动位置。
2. **应用排行图标**：新增 `uikit/AppIcon`（`getApplicationIcon`，失败回退首字母），
   依赖 6b 补的 `MAIN/LAUNCHER` `<queries>`；桌面无入口的应用（如 `android`）会回退首字母。
   排行页 `RankingScreen` 仍是首字母占位，**未切换**到 `AppIcon`（避免本批改动面继续外扩）。
3. **通知排行无条数上限**：全量渲染，极端情况下（几百个包）会让详情页变长；
   规格未给上限，暂按「全部」处理，若真机上过长再考虑折叠。
4. **`DaySummary.unlocks` 契约未动**：仍未区分「拿不到」与「真 0」，本轮只在详情页用文案
   兜住。彻底解决要给契约加可空/标志位，属契约改动，建议随批 7 一起做。
5. **表格内部滚动**：24 行 / 366 行都在 264dp 内滚动，长区间时要滑很久；若真机反馈难用，
   可改成「默认显示前 N 行 + 展开更多」。

---


## 【来源】批次6b-6d返修-交付说明.md

> 原路径 `docs/批次6b-6d返修-交付说明.md`（合并后原文件已移除，原始字节见 git 历史 `a89bd33:docs/批次6b-6d返修-交付说明.md`）

### 批次 6b–6d 评审返修交付说明

> 评审来源：`docs/批次6b-6d评审-DeepSeek V4.1 Flash.md`（针对 `c92a693..20d2d1e`）  
> 本次返修提交：见 `git log`（一次提交，含 P0/P1 全部 + 6 条 P2）  
> 测试：**127 通过 / 0 跳过 / 0 失败**（返修前 105；新增 22 条）  
> 环境：Robolectric 4.12.2 + 真实 Room（in-memory，直连执行器）



---

#### 一、结论

| 编号               | 评审结论                    | 我的核对                                          | 状态      |
| ---------------- | ----------------------- | --------------------------------------------- | ------- |
| P0-1             | 降级通道数据进得来，一个页面都不显示      | **成立**，且比评审写的更严重（`applySelection` 也被同一条错判据拦住） | 已修      |
| P1-1             | 「解锁 0 次」假 0 + 卡片/详情两个说法 | **成立**                                        | 已修      |
| P1-2             | 区间态柱图横轴标签被槽宽裁掉          | **成立**                                        | 已修      |
| P1-3             | 主通道超时把半份 dump 当成功       | **成立**                                        | 已修      |
| P1-4             | 降级通道适配层零测试              | **成立**                                        | 已补 10 条 |
| P2-1/2/4/8/9/12  | 非阻塞                     | 成立，顺手收掉                                       | 已修      |
| P2-3/5/6/7/10/11 | 非阻塞                     | 见第五节，本轮不动                                     | 留待批 7   |

评审里说「做得对的地方」那几条我全部复核过，没有发现被自己改坏的（`ParseResult.isEmpty`  
仍是唯一出处、`ForegroundBucketer` 收尾逻辑未动、三态语义未动）。

---

#### 二、P0-1　降级通道的数据进得来，但一个页面都不显示

##### 核对结果：成立，并发现一处评审没提到的连带失守

评审证据全部对上：`loadAll` 用 `Summary.hasAnyRecord`（只数 `day_summary`）当整页空态开关，  
而 `UsageStatsUsageProvider` 按设计 `days = emptyMap()`（理由正确），于是 `app_ranking` /  
`hourly` 读得出来却四页全空。

**评审没提的第 4 处**：`applySelection` 用的是同一条判据：

```kotlin
// 旧代码
if (!repository.summary(newSelection).hasAnyRecord) { _notice.value = "该时段尚无数据，请先拉取" }
```

降级通道下这个条件恒为 true，所以**用户连日期都选不了**——每次选日期都被提示「尚无数据」，  
只能永远看今天。这比「四个页面空态」更难被误认为是「还没拉」，因为文案还在让他「先拉取」。

##### 修法

1. **仓库层加两个判据**，语义写进注释，避免下次又有人用 `hasAnyRecord` 顶替：
   ```kotlin
   suspend fun hasAnyData(): Boolean               // day_summary | app_usage | hour_usage 任一非空
   suspend fun hasDataIn(selection): Boolean       // 同上，限定区间
   ```
   DAO 侧每条表加 `count()` / `countBetween()`，一共 6 条 `SELECT COUNT(*)`。
2. `loadAll` 用 `hasAnyData()` 决定「是否整页空态」；`summary.hasAnyRecord` 退回它本来  
   该干的事——「日聚合指标是否有值」。
3. `LoadState` 增加 `empty: EmptyReason?`（`NO_DATA` / `NO_EVENT_DATA`）。  
   前者「从来没拉到过」，后者「拉到了，但这条通道没有这类数据」。给同一句文案就是在骗用户。
4. **排行页在降级通道下照常出数据**（评审方案②），四页空态统一换文案，今日页空态额外给一个  
   「查看应用排行」出口（`EmptyState` 加了可选 `actionText/onAction`）。
5. **排行页摘要卡不能读 `summary.totalScreenUnlockMin`**——那是 0，画出来就是新的假 0。  
   改为读新增的 `RankingData.appsTotalMinutes`（应用行分钟合计，降级通道下真实可算），  
   标题同时改成「应用使用累计（降级通道，不含锁屏统计）」。
6. `PullCoordinator.historyCoverage()` 的日期来源并入 `app_usage`（评审连带项 2）：  
   只数 `day_summary` 会让批 7 首启向导把「只有应用数据」当成「从未拉过」再引导一遍。

文案单一出处：`ui/DataEmptyState.kt`，四个页面共用，不在各页各写一遍。

##### 验证（`ui/AppViewModelDegradedTest`，7 条，真 Room + 真 ViewModel）

- 只有 `app_usage`/`hour_usage` 时：排行页 `data != null` 且包名与分钟数正确；
- 同一份数据下：今日/统计/日历/解锁详情/通知详情五处 `data == null` 且  
  `empty == NO_EVENT_DATA`（不是 `NO_DATA`，也不是假 0）；
- 同一份数据下：`applySelection` 不被拦（`notice == null`）；
- `historyCoverage().hasAny == true`，首末日期正确；
- **反向**：三张表全空时六处都是 `NO_DATA`、选日期仍被拦（别为了修 P0 把 P1-3 松掉）；
- **反向**：主通道（有 `day_summary`）四页照常有数据，排行摘要仍走屏幕时长口径。

---

#### 三、P1-1　「解锁 0 次」的假 0，以及卡片与详情页两个说法

##### 核对结果：成立

`MetricDetail` 只有 `total`，UI 无法区分「拿不到」和「真 0」。评审举的两处（卡片渲染  
「0 次」、详情页说「该时段没有记录」）都复现。另外确认评审对 6d 交付说明 §2.4 的判断也对：  
那句「本次区间内没有解锁事件记录」在 `windowStart == null` 时确实走不到。

##### 修法

- `MetricDetail` 增加 `hasEventSource: Boolean`，在 `UsageCalculator.buildUnlockDetail` /  
  `buildNotificationDetail` 里由 `windowStart != null` 派生（这是唯一判据，不给调用方留口子）。
- 今日双卡：`hasEventSource == false` 时值渲染 `—`，「已查看 X 次」一并收掉（那同样是假 0），  
  读屏描述改成「…，暂无解锁事件，查看详情」。  
  **同时删掉 `unlock == null` 时退回 `summary.averageUnlocks` 的兜底**——那条路径在降级通道下  
  正好是 0，看起来就像「今天解锁 0 次」，宁可不给数字。
- 详情页两种空态分开：
  - `!hasEventSource` → 「暂无解锁事件 / 暂无通知事件」，说明该机型不产生锁屏事件或当前是  
    降级通道，需要 Shizuku；
  - `hasEventSource && 无 RECORDED 点` → 「该时段没有记录」（窗口外 / 拉取缺口）。
- `UnlockSummary` 里那句 `total == 0` 的补充说明改成「事件源正常，这段时间确实没有解锁」——  
  它现在只在**真实 0** 分支里可达，不该再拿机型说事。

##### 验证（`UsageCalculatorTest.metricDetailDistinguishesNoEventSourceFromRealZero`）

- `windowStart = null` → `hasEventSource == false`，所有点 `NO_RECORD`；
- `windowStart` 在区间之前、区间内 0 条 → `hasEventSource == true`，所有点 `RECORDED`、`total == 0`；
- 通知通道同样断言（两个指标都不能漏）。

---

#### 四、P1-3　主通道超时把「半份 dump」当成功入库

##### 核对结果：成立

`ShizukuShell.exec` 的看门狗到点 `destroy()`，`readAll` 把异常当 EOF 吞掉，`text` 非空即返回。  
13MB checkin 被截断后解析器（逐行特征匹配、不要求完整）照样能出前 N 天，  
`pull_log.ok = true`、界面显示「已更新 N 天」——今天那段静默消失。

##### 修法

看门狗先置 `AtomicBoolean truncated` 再 `destroy()`，读取返回后由  
`checkedOutput(text, truncated, command, timeoutMs)` 统一判定：**已截断就抛  
`ShizukuExecException`**。协调器于是走「主通道失败 → 试降级通道 / 保留旧数据」，  
而不是悄悄少半天数据。

抽成顶层纯函数 `checkedOutput` 的唯一理由：这个分支以前既没测试也看不出来，  
抽出来才能把它钉住（不抽的话只能靠真机等 120 秒超时，永远不会有人验）。

##### 验证（`ShizukuUsageProviderTest` 新增 4 条）

- 「非空 + 已截断」必须抛，且报错能看出是截断（这就是原 bug 的复现）；
- 未截断原样返回；空输出仍按失败处理；
- 走完整条通道：shell 报截断 → `LoadedUsage.Failed`，且**解析器根本没被调用**。

---

#### 五、P1-2　区间态柱图横轴标签被裁

##### 核对结果：成立

细柱 `slotWidth = 7 + 4 = 11dp`，标签直接放在 11dp 宽的 Box 里且 `maxLines = 1`，  
「11/8」约 26dp 只会画出前一两个字符。1–7 点（34dp 槽）看不出来，**≥8 天全坏**——  
恰好是近 7 天/30 天趋势的主场景。

##### 修法

- 标签改用 `Modifier.requiredWidth(48.dp)` + `align(BottomCenter)` + `textAlign(Center)`。  
  **必须是 `requiredWidth`**：普通 `width` 会被父 Box 的 `maxWidth = 槽宽` 反过来夹住，  
  等于什么都没改（这就是原 bug 的成因）。
- 滚动内容左右各留 `(48 - 槽宽) / 2` 的 `edgePad`，首尾标签才不会贴边被滚动区裁掉；  
  指针命中和气泡定位同步减去/加上这段偏移。
- `labelEvery` 从「按 28dp 常数」改成「按标签宽度算」：细柱时每 4 槽打一个点，  
  否则两个 26dp 的标签会叠在一起。

##### 验证方式（重要：这条**没有**自动化测试）

Compose 布局断言需要 `createComposeRule` 真渲染，本工程尚未接入，我没有为了凑覆盖率写  
一个「断言 48dp 常量」这种必然为真的假测试（那正是本次评审批评的「声称覆盖、实际没覆盖」）。  
这条靠读代码 + `@Preview`（`MetricBarChartGapPreview` 是 10 个点的区间态）确认，  
**真机第一次装上时必须目视核对「近 7 天」和「30 天」两个区间的横轴标签**。  
`CapsuleBarChart` 本轮不动：它的标签会把槽宽撑开（批 2 记的 P2 取舍），本身不会裁字；  
两图合并规格是视觉工程，留给批 7 统一收。

---

#### 六、P1-4　降级通道适配层零测试

##### 核对结果：成立

`ForegroundBucketerTest` 测的是纯函数，适配层（事件类型映射 / 跨零点切分 / launches 归属 /  
`days = emptyMap()`）确实全靠人眼。

##### 修法

新增 `UsageStatsUsageProviderTest`（10 条），用 Robolectric `ShadowUsageStatsManager.addEvent`  
注水，走真 `UsageStatsManager.queryEvents` 入口，断言落在「事件 → 逐日逐应用分钟数」的结果上：

| 用例                                  | 钉住的东西                      |
| ----------------------------------- | -------------------------- |
| RESUMED→PAUSED 配对 + 按小时落桶           | 事件类型 1/2 的字面量映射            |
| ACTIVITY_STOPPED 收尾                 | 类型 23 也必须收尾                |
| 只有 PAUSED 没有 RESUMED                | 不许凭空造时长                    |
| 跨零点拆两行                              | `appendSpan` 按自然日切分        |
| launches 按进入事件归属日                   | 排行「逐日启动」口径                 |
| 连续两次 RESUMED                        | 切换 Activity 不重开区间，但启动数算 2  |
| **`days` 恒为空 + `isEmpty == false`** | 「绝不写日聚合」的硬约束，同时不能被判成空 dump |
| warning 写明能力边界                      | `pull_log.note` 可追溯        |
| 丢 PAUSED 时以下一个应用收尾                  | 不虚高到拉取时刻                   |
| 30 天前的事件不进结果                        | 滚动窗口真的生效                   |

---

#### 七、顺手收掉的 P2

| 编号    | 改动                                                                                                          |
| ----- | ----------------------------------------------------------------------------------------------------------- |
| P2-1  | `AppUsageDao.sumBetween` 的 `MAX(name)` → `MAX(CASE WHEN name = pkg THEN '' ELSE name END)`，「包名占位」不再靠字典序赢过真名 |
| P2-2  | `metricDetail` 不再顺手把区间内全部通知读一遍（解锁分支只查解锁表，通知分支只查通知表）                                                         |
| P2-4  | `KeyboardArrowRight/Left`、`List` 改用 `Icons.AutoMirrored.*`，与 `supportsRtl="true"` 对齐                        |
| P2-8  | `ShizukuUsageProvider.capturedAtMs` 改为在 **dump 开始前**取（它是节流基准与 `spansOf` 的 endMs，语义上该是抓取时刻而不是解析完成时刻）         |
| P2-9  | 删掉已成死代码的 `RoomUsageRepository.minOfNotNull`                                                                 |
| P2-12 | `pull_log.source` 记「实际尝试到的最后一条通道」，不再恒记 `providers.first()`                                                  |

**本轮不动**（理由：要么是视觉工程、要么必须真机验证，改了反而更不可信）：  
P2-3（两柱图网格线与刻度公式统一）、P2-5（`clearAndSetSemantics` 防 TalkBack 重复朗读——  
需要真机读屏验证，改错会让卡片对读屏用户不可点）、P2-6（箭头 12dp）、  
P2-7（通知排行 `forEach` 改 `LazyColumn`）、P2-10（切 tab 重播柱体生长）、  
P2-11（返回滚动位置恢复）。建议随批 7 一起收。

---

#### 八、变异验证（确认新测试有牙齿）

以下 6 处故意改坏源码，逐条确认测试**真的报红**，然后还原：

| 变异                                | 预期命中的测试                       | 结果 |
| --------------------------------- | ----------------------------- | -- |
| `hasAnyData()` 退回只看 `day_summary` | `AppViewModelDegradedTest`    | 红  |
| `hasDataIn()` 退回只看 `day_summary`  | `AppViewModelDegradedTest`    | 红  |
| `hasEventSource` 恒为 `true`        | `UsageCalculatorTest`         | 红  |
| 删掉 `truncated` 判定（回到旧行为）          | `ShizukuUsageProviderTest`    | 红  |
| 事件类型 `23` 写成 `24`                 | `UsageStatsUsageProviderTest` | 红  |
| 跨零点改成 `val segEnd = end`          | `UsageStatsUsageProviderTest` | 红  |

---

#### 九、真机验证受阻说明（不是代码问题）

`adb install` 报 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`。  
抓 logcat 确认**与「USB 安装」开关无关**（该开关一直是开的），真实原因：

```
I/BufferQueue: com.miui.securitycenter/com.miui.permcenter.install.AdbInstallActivity
W/WindowManager: wms.hideSurface ... AdbInstallActivity reason:(prepareSurfaceLocked)
I/MTK_APPList: AdbInstallActivity state:PAUSED
D/VA_AppStateWatcher: currentPkgName:com.xiaomi.smarthome   ← 分身里的小米之家在前台
E/Pm: install msg : Failure [INSTALL_CANCELED_BY_USER]
```

设备当前停在**手机分身（user 999）**，前台是分身里的 `com.xiaomi.smarthome`；  
而 MIUI 的 ADB 安装确认框 `AdbInstallActivity` 属于 **user 0**。分身占着显示，  
user 0 的确认框一创建就被压成 PAUSED，MIUI 随即按「用户取消」收单。

`--user 0` / `--user 999` 指定目标用户都无效（确认框本身跑在 u0，与安装目标用户无关）。

**结论**：需要先退出手机分身回到主空间，再执行安装；届时安装确认框能正常显示并点「继续安装」。  
APK 已构建好（`app/build/outputs/apk/debug/app-debug.apk`），随时可装。

---

#### 十、遗留风险

1. **P1-2 只有代码评审与 Preview，没有自动化测试**（见第五节），真机第一次装上必须目视核对  
   7 天/30 天区间的横轴标签。
2. `DaySummary.unlocks` 的契约仍不区分「拿不到」与「真 0」——本次只把**详情页与卡片**这条  
   链路补上了 `hasEventSource`，日聚合层（`day_summary`）依旧是「有行就有值」。批 7 若要做  
   「解锁趋势卡」，必须先把这一层拆开，否则统计页会出现和今日页同样的假 0。
3. `UsageStats` 通道的 `contentProvider` 取不到 `KEYGUARD_*`，因此降级通道的「解锁」永远是  
   `NO_EVENT_DATA`。这是设计边界，不是 bug；设置页文案需要在批 7 明确写出来。

---


## 【来源】批次6e-真机冒烟-交付说明.md

> 原路径 `docs/批次6e-真机冒烟-交付说明.md`（合并后原文件已移除，原始字节见 git 历史 `a89bd33:docs/批次6e-真机冒烟-交付说明.md`）

### 批次 6e 交付说明：真机冒烟与主通道修复

> 真机：小米 2407FRK8EC / Android 16 / HyperOS 3 / 1220×2712
> 通道：Shizuku 13.6.0（ADB 启动，uid 2000）+ 降级通道已授权
> 测试：151 条全绿（0 跳过 0 失败），11 项变异验证全部报红

---

#### 一、这次真机冒烟最重要的发现：`--checkin` 的结论是反的

之前（批次 6b）的判断是「HyperOS 3 忽略了 `--checkin` 参数」。本轮用字节数把这件事查清了：

| 命令 | 输出 | `totalTimeUsed` | `ACTIVITY_RESUMED` |
|---|---|---|---|
| `dumpsys usagestats` | **3.7–4.2 MB 明文** | 880+ | 4000+ |
| `dumpsys usagestats --checkin` | 0.5 MB 元信息 | **0** | **0** |

`--checkin` 并没有被忽略，它走了「只吐元信息、把数据段全砍掉」的分支；**真正可用的数据一直在不带参数的明文里**。

更关键的连带结论：**08:29 那次「拿到真 checkin」的判断是错的**——当时解析出的 `765` 分钟只可能来自明文（`--checkin` 输出里没有任何时长数据）。也就是说主通道这几个小时一直靠明文工作，只是被批次 6b 加的形态守卫判成失败挡掉了。

同时发现 `DumpShape` 自己的判定也是错的：`CHECKIN_MARKERS` 用了 `totalTimeUsed=` / `screen-interactive:`，而**明文里两者都在**（`totalTimeUsed="23:06:08"`、`screen-interactive: 1x for "00:00"`），于是 4 MB 完整明文被判成 CHECKIN、喂给 CSV 解析器一行都匹配不上。

##### 修法

- `DumpShape`：特征改为**带格式细节**的 CSV 独有写法（`^Days:` / `beginTime=<数字>` / `screen-interactive: Nx for <毫秒>` 无引号）；新增 `hasUsageData`，区分「明文带数据」与「明文只有元信息」（真机上 `--checkin` 就是后者）。
- `ShizukuUsageProvider`：从「checkin 不是 checkin 就整次失败」改成**谁有数据谁当主输入**。明文主导时 checkin 分支传空串（避免误解析），两路都没数据才判通道失败。`pull_log.note` 记录两路各自形态，例如：
  - `checkin 无数据（0.5MB/plain-nodata），本次明文主导；明文 3.8MB/plain+data`
  - `checkin 0.7MB/checkin+data；明文 3.8MB/plain+data`（同一台机器上一次真给了 CSV）

**这台 ROM 的 `--checkin` 是不稳定的**：两次冷启动分别给「CSV」和「只有元信息」，所以两条路都必须能跑。

---

#### 二、明文暴露的四个真 bug

##### 1. In-memory 跨天不摊，单日应用合计超全天 → 整日数据被校验丢掉

真机 `In-memory daily stats` 的窗口是 `2026/11/10 09:15 – 2026/11/11 08:55`（约 23.7 小时，跨两天），而 `totalTimeUsed` 是整段累计。原实现把所有包时长全归「今天」：

```
修复前：11-10 appSum=0      （15 个应用凭空消失）
        11-11 appSum=1420   （屏幕只有 538）
        → IngestSanity 判脏，dropped=[11-10, 11-11]，keptApps=0/30

修复后：11-10 appSum=835 / 11-11 appSum=584，dropped=[]，keptApps=30/30
```

修法：解析器记录每个 user 的 In-memory `timeRange`，按**当天屏幕时长**为权重把包时长分摊到各日（`dayWeights`）。显式传 `today` 时保留「全归当天」的旧语义。

##### 2. 缺 `appLaunchCount` 的包行被整行丢弃

明文主空间那段 **223 个包行里只有 132 行带 `appLaunchCount`**（系统在值为 0 时省略该字段）。`PKG_HUMAN` 把该字段写进主正则 → 91 个包连同真实时长一起消失，表现是「排行页只剩 4 个应用」。

修法：`appLaunchCount` 改为独立可选匹配，缺失按 0 计。

##### 3. 自洽性校验的 2% 容差是错的

`IngestSanity` 假设「应用前台时长 ≤ 屏幕亮着时长」，但 Android 的 `totalTimeUsed` 还含前台服务、画中画、灭屏可见 Activity。真机实测 583 vs 557（+4.7%）、632 vs 557（+13.5%）都是正常值。

修法：`SLACK_RATIO` 2% → **50%**。真机正常数据能过，`1386 vs 510`（2.7 倍）那种量级错误仍被挡住。

> 顺带纠正批次 6b 的一个误判：当时把「0–7 点各 60 分钟」当成假分布，其实那是屏幕从 11-10 11:15 一直亮到 11-11 08:58 跨零点的**正确切分**。

##### 4. 后台已入库、前台被节流 → 界面永远停在空态

冷启动时 WorkManager 的 Worker 先把 4 MB dump 拉完入库，UI 随后的 `pullOnOpen` 因 30 分钟节流返回 `Throttled`，而该分支只刷新覆盖率**不重读数据库**——于是「数据明明在库里，界面显示尚无使用记录」，且怎么切前台都不变。真机 100% 复现。

修法：
- `PullCoordinator` 新增 `changes: SharedFlow`，成功入库后 `tryEmit`；
- `AppViewModel` 订阅它并 `loadAll()`；
- `Throttled` / `AlreadyRunning` 分支在当前无数据时补读一次（有数据时不动，保留 §5.7 不闪骨架）。

---

#### 三、闪退：`IntrinsicSize` 撞 `SubcomposeLayout`

```
IllegalStateException: Asking for intrinsic measurements of
SubcomposeLayout layouts is not supported
```

`MetricCardPair` 用 `height(IntrinsicSize.Min)` 让双卡等高，而卡内 `MetricText` 用了 `BoxWithConstraints`（本身是 SubcomposeLayout）——对 SubcomposeLayout 求 intrinsic 直接抛异常。**有数据才渲染双卡**，所以表现为「授权完、数据一到就闪退」。

修法：`MetricText` 改用 `Layout` + 自定义 `MeasurePolicy`（显式实现 intrinsic 查询），双卡等高恢复用原生 `IntrinsicSize.Min`。中间试过「两趟测量」的写法，被 `measure() 同一 Measurable 只能测一次` 的硬约束否掉，已删除。

---

#### 四、UI 侧三处

| 问题 | 修法 |
|---|---|
| 首次拉取要十几秒，期间显示「尚无使用记录」 | 加 `PullingState`（转圈 + 「正在读取使用记录」），由 `pulling` 状态驱动 |
| 应用时长可高于全天（583 vs 557）无处解释 | 应用构成卡在超出时显示「应用时长含前台服务与画中画，可能略高于亮屏时长」 |
| 解锁空态写「连接 Shizuku 后再看」，但已经在用 Shizuku 主通道 | 改为不依赖通道的陈述：「部分机型不产生锁屏事件，设备未设锁屏时也不会有记录」 |
| 排行出现「Tomato · 0分钟」 | 入库时过滤 `minutes <= 0`（不足半分钟不上榜），59 秒仍记 1 分钟 |

---

#### 五、真机验证结果

```
pull_log: source=shizuku, ok=1
note="checkin 无数据（0.5MB/plain-nodata），本次明文主导；明文 3.8MB/plain+data"

day_summary: 11-10 screen=765 launches=3601 / 11-11 screen=582 launches=189
app_usage:   11-10 米家 803 / 11-11 米家 583、屏迹 13、系统桌面 3
notification_event: 83
unlock_event: 0（该机无锁屏，KEYGUARD_* 事件为 0 是真实值）
```

界面：今日页 9:31 / 较昨日减少 3:14 / 最长连续 9:31 / 解锁「—」/ 通知 20；统计页柱图标签 `11/11` 居中未被裁；排行页「共 4 个应用」。

##### 关于米家 23 小时

系统自己就这么记：

```
In-memory daily stats
timeRange="2026/11/10 09:15 – 2026/11/11 08:55"
  package=com.xiaomi.smarthome totalTimeUsed="23:06:08" ...
event aggregations
  screen-interactive: 1x for "00:00" (now running, started at )
```

这两句在系统层面就矛盾（屏幕交互 1 次、时长 00:00、一直 now running，但米家 23 小时）。该机有 user=999 分身跑自动化、屏幕常亮，米家常驻前台，系统按「持续在前台」累加。不是聚合算法的问题，已用 UI 口径说明覆盖。

---

#### 六、本轮的边界（如实记录）

1. **明文只覆盖最近 24 小时**：`UsageStatsDatabase` 段只列 10 个 daily 文件名、不含内容，历史只能靠 In-memory 窗口分摊。所以「11-10 有屏幕时长但应用明细偏少」是数据源边界，不是丢数据。
2. **`day_summary` 与应用的日期归属口径不同**：屏幕时长按区间结束日归属、应用时长按 In-memory 窗口分摊，跨零点时两者会有几分钟差。
3. **`--checkin` 行为不稳定**：同一台机器两次冷启动分别给 CSV 与元信息，无法从客户端控制，只能两条路都支持。
4. **`unlocks=0` 不区分「拿不到」与「真 0」**：本机确实无 KEYGUARD 事件；`DaySummary.unlocks` 的契约问题仍在（批 7 前建议改）。
5. **In-memory 分摊是近似**：权重用当天屏幕时长，若某天使用高度集中（例如只在深夜用 1 小时），分摊会有偏差。系统不提供更细的信息。
6. **P1-2 柱图标签裁切仍无自动化测试**：需要 `createComposeRule` 断言布局，工程未接。真机已目视确认「近 7 天 / 近 30 天」标签居中不被裁。

---


## 【来源】批次6f-13项修复-交付说明.md

> 原路径 `docs/批次6f-13项修复-交付说明.md`（合并后原文件已移除，原始字节见 git 历史 `a89bd33:docs/批次6f-13项修复-交付说明.md`）

### 批次 6f：13 项真机反馈修复 + 参考 Tomato 的交互改造

> 设备：小米 2407FRK8EC / Android 16 / HyperOS 3
> 基线：`4a99c87`（批次 6e）
> 结果：测试 151 → **159（0 跳过 / 0 失败）**，4 项变异验证全部报红

---

#### 一、逐条修复

##### 1. 设置里返回直接回桌面

**根因**：`goBack()` 只接在顶部栏那个「返回」按钮上，**系统返回没人拦**。
Activity 没有 `OnBackPressedCallback`，于是系统返回 → `finish()` → 回桌面。

**修法**：`AppNav` 里按页面层级挂 `BackHandler`：

```kotlin
BackHandler(enabled = !isRootPage(page)) { viewModel.goBack() }
```

主页面（今日 / 统计 / 日历）不拦截，保持系统默认的「退出应用」。

**真机验证**：设置页按系统返回 → 回到今日页，Activity 未销毁 ✅

---

##### 2. 状态栏是黑色不透明

**根因**：`MainActivity` **从未调用 `enableEdgeToEdge()`**，只在 `themes.xml` 写了
`windowLightStatusBar=true`——那只把图标改成深色，底色仍是系统默认黑。
深色图标压黑底，观感就是「上面多了一条黑边」。

**修法**：
- `MainActivity.onCreate` 调 `enableEdgeToEdge()`（与 nsh07/Tomato 一致，它也是这么做的）
- `themes.xml` 补 `statusBarColor/navigationBarColor=transparent` 与
  `windowLayoutInDisplayCutoutMode=shortEdges` 作兜底
- `ScreenScaffold`：内容 `statusBarsPadding()`、悬浮导航 `navigationBarsPadding()`。
  **背景色仍铺到状态栏下方**（只在内容上加 padding，不加在最外层 Box），
  否则状态栏那一条会露出系统底色
- 图标明暗跟**用户选的深浅色**走（`SideEffect` 里设 `isAppearanceLightStatusBars`），
  不跟系统——本 App 支持 App 内独立切深色

**真机验证**：状态栏与页面背景同色（浅绿），图标深色 ✅

---

##### 3. 日期弹窗全屏、被状态栏与摄像头遮挡

**根因**：`ModalBottomSheet` 内容太高（DatePicker + DateRangePicker 各自 400+dp），
sheet 撑满整屏，顶部顶到状态栏下面；同时缺 `enableEdgeToEdge`，inset 处理也不生效。

**修法**：
- 内容 `heightIn(max = 屏高 × 0.86)`，`heightIn` 必须写在 `verticalScroll` **之前**
- 间距 16 → 12dp（弹层高度有限，收紧后「应用」按钮不用滚就露出来）
- `ModalBottomSheet(windowInsets = WindowInsets(0))`：默认值会让 sheet 的 surface
  在导航栏上方就结束，edge-to-edge 后底部露出一条背景色的缝；清零后 surface 铺到屏幕底，
  避让改由内容的 `navigationBarsPadding()` 负责

**真机验证**：弹窗从状态栏下方开始、顶圆角可见、标题不再被时间/信号图标压住 ✅

---

##### 4. 解锁次数应显示「—」而不是 0

**核对结果**：今日页**已经是「—」**（批次 6b 的 `hasEventSource` 生效）。
`windowStart = unlockEventDao.minMs()` 本来就是**全表**最早事件，不是区间内的，
所以 `hasEventSource` 的语义已经是「这台设备能不能拿到锁屏事件」。

本机 `unlock_event` 表为空（设备无锁屏、ROM 不广播 `KEYGUARD_*`）→ 「—」正确。

**结论**：无代码改动。若你看到的是「0」，那是**有事件源但该区间恰好 0 次**，
属于真实值，详情页会配文案「事件源正常，这段时间确实没有解锁」。

---

##### 5. 对比 Tomato，tab 切换太僵硬

参考了 nsh07/Tomato（Material 3 Expressive）：它用 `enableEdgeToEdge` +
`ContentTransform(fadeIn(motionScheme.defaultEffectsSpec()), fadeOut(...))` 做页面切换，
导航项图标用 `Crossfade` 切 outlined/filled。

我们的 BOM 是 2024.06.00，拿不到 `ShortNavigationBar` / `motionScheme`，
但同样效果的实现不需要升级 BOM：

**① 页面切换**：整块内容淡入 + 上浮 12dp
```kotlin
Column(Modifier.graphicsLayer {
    alpha = contentAlpha.value
    translationY = (1f - contentAlpha.value) * 12.dp.toPx()
})
```

**② `SegmentedControl` 加滑动指示器**（影响范围最大的那条）：
以前选中态是每项 `background(primary)` 硬切，点「近 30 天」时色块瞬间跳到另一项。
现在是一块独立圆角指示器按 `offset` spring 平移，文字颜色同步 `animateColorAsState`。
统计页 / 日历页 / 日期弹窗 / 详情页 / 设置页五处一起受益。

**踩坑记录（重要）**：第一版用 `AnimatedContent` 包页面，真机上**圆环、双卡、应用构成
三张卡全部重叠**，只看得见最后画的那张。根因不是 `AnimatedContent` 本身，而是
**页面 Composable 有多个根节点**（`TodayContent` 就是三张并列的卡）：
`ContentColumn` 是 `ColumnScope`，多根节点本来是纵向排的；包进 `Box`（或
`AnimatedContent` 内部的 Box）后它们变成兄弟节点 → 全叠在 top-start。
`graphicsLayer` 方案配 `Column` 才正确，卡间距要在新 Column 上补回 12dp。

---

##### 6. 统计柱状图很多滑到 0 轴下面

**根因**：`CapsuleBarChart` 的每列是 `Column { 柱区(plotHeight) + 标签 }`，
而**有标签的列放 `Text`（约 15dp）、无标签的列放 `Spacer(22dp)`**。
外层 `Row` 用 `verticalAlignment = Bottom` 对齐 → 有标签的列整列矮 7dp →
Bottom 对齐后**柱底比无标签的列低 7dp**，正好穿出 0 刻度线。

**修法**：标签行改成固定 `labelRowHeight` 的槽位，所有列等高，柱底回到同一条基线。

---

##### 7. 选择弹窗卡片文字偏上、高度不对

**根因**：`QuickChip` 用 `Box` 且**没设 `contentAlignment`**（默认 `TopStart`），
靠 `padding` 撑高度时文字贴顶，胶囊下半截是空的。

**修法**：`contentAlignment = Alignment.Center` + `minimumInteractiveComponentSize()`
（视觉按内容走、点击区仍 ≥48dp，§5.9）。

---

##### 8. 通知详情图标错误、柱子压到文字上

**两个独立问题**：

**① 柱子压文字**：`BarSlot` 的标签用 `align(BottomCenter).offset(y = labelRowHeight - 12.dp)`，
而 **offset 只改绘制位置、不改测量尺寸**——外层 Box 高度仍是 `plotHeight`，
标签被画到柱区内部，于是「柱子压着文字、整体还偏一截」。
改成柱区与标签两个等高槽位上下堆叠。

**② 应用名显示成 `com.xiaomi.mi_connect_service`**：
- 该包**没有桌面入口**，清单里的 `<queries>` 只声明 MAIN/LAUNCHER → 查不到
- 而且它**只出现在通知事件里、从不进入 `app_usage`**，
  而名字表是从 `app_usage` 的 `allNames()` 构建的

**修法**：
- 清单加 `QUERY_ALL_PACKAGES`（对屏幕时间类 App 不算越权：要回答「哪个应用用了多久」，
  本来就必须能看到全部应用）
- `RoomUsageRepository` 加 `labelResolver`，读取层兜底现查
- 名字表补上「只在通知事件里出现的包」

**真机验证**：`小米互联通信服务` 正常显示 ✅

**顺带修的横轴问题**：
- `edgePad` 从 `(labelWidth - slotWidth)/2` 改成 `labelWidth / 2`——
  前者在细柱时算出来只有 18.5dp，最右那个标签仍会顶到内容框边界被裁成「2:」
- `lastLabelIndex` 改成对齐点：以前无条件强制显示 lastIndex，24 个点时
  最后两个标签是 20 和 23，相距 33dp 而标签宽 48dp，必然重叠

---

##### 9. 日历「低于 2 小时」要能自定义

新增设置项「统计口径 → 低于 x 小时（1/2/3/4 小时）」，默认 2 小时。

- `SettingsStore.lowUsageThresholdMin`（DataStore 持久化）
- `DEFAULT_LOW_THRESHOLD_MIN = 120` 放在契约层，作为唯一默认值出处
- `UsageRepository.extremes(selection, lowThresholdMin)` 加参数
  （仓库层保持纯查询，配置由 ViewModel 注入，单测才能直接钉不同阈值）
- `ExtremesInfo.under2hDays` 改名 `underThresholdDays`（字段名不能再骗人）
- 卡片标题跟着阈值走，非整小时显示「低于 90 分钟」

**真机验证**：设置改 3 小时 → 日历卡片标题变「低于 3 小时」✅

---

##### 10. 圆环保持 HH:MM，去掉下方「小时 : 分钟」

`9:42` 本身已经自解释，再写一行属于重复标注、白占高度。
`RingProgress` 的 `caption` 改成可选（默认 null），今日页不再传。

---

##### 11. 点击「自定义区间」闪退

**根因**（logcat 抓到的原始栈）：
```
IllegalStateException: Vertically scrollable component was measured with
an infinity maximum height constraints
  at LazyListKt$rememberLazyListMeasurePolicy
```
`DateRangePicker` 内部是 **LazyColumn**，被外层 `Column(verticalScroll)`
按无限高测量 → 直接抛异常。

**修法**：给 `DateRangePicker` 一个有界高度（`height(340.dp)`），内部自己滚。
顺带把 `title`/`headline` 置空（省高度 + 避免与外层「选择日期」标题重复）。

**真机验证**：点「自定义区间」不再闪退，日历正常显示 ✅

---

##### 12. 点通知条 x 后页面闪一下

**根因**：`notice?.let { NoticeBar(...) }` 让提示条**瞬间消失**，
下面整页内容「啪」地往上跳一格。

**修法**：`AnimatedVisibility`（展开/收起 140ms + 淡入淡出），
`notice` / `pullError` / `pulling` 三处统一。

---

##### 13. 屏幕使用时间 9:42，应用构成只有 28 分钟（分身没算）

**这条是全批最严重的**，而且原假设（分身没算进去）是错的。

**实测**：`dumpsys usagestats` 里
```
user=0   In-memory daily stats  timeRange="2026/11/11 09:13–09:50"   ← 只有 37 分钟
user=999 In-memory daily stats  packages（空）
```

**真根因**：应用时长只有两个来源——
① checkin 的 `packages:` 段（本 ROM 的 `--checkin` 只给元信息，没有这段）
② 明文 In-memory 段（**系统会重置**，真机只剩 37 分钟）

而屏幕时长来自 `SCREEN_INTERACTIVE` 事件流，覆盖整个 24 小时窗口。
**两个口径的时间窗口根本不同**，数字天生对不上，跟分身没关系。

**修法**：`UsageDumpParser` 增加第三个来源——**事件流推导**：
用 `ACTIVITY_RESUMED/PAUSED/STOPPED` 配对出前台区间（`foregroundFromEvents`），
与屏幕时长吃同一批事件、同一个窗口。

- 每个 user **各自**维护前台栈（主空间与分身是两套）→ 分身数据自动计入
- 换应用时用「新应用进入前台的时刻」收尾上一段（不等 PAUSED——进程被杀时根本没有）
- 跨零点按自然日切分（23:50→00:10 拆成 10+10）
- 三源**取大**，不是相加（同一件事的三种观测）

**为什么取大而不是「事件流优先」**：真机对账样本（`reference/sample-checkin.txt`）里
In-memory 给 356.7 分钟、事件流只配出 104 分钟——事件流在窗口边界、进程被杀、
前台服务等场景会**系统性低估**。反过来 In-memory 在窗口重置时又只剩几十分钟。
两者谁高谁更接近真实没法事先判断，取大最稳。

**连带调整 `IngestSanity`**：
- 容差 0.5 → 2.0（上限 = 参照 × 3）：**主空间与分身可以并行**，应用合计到屏幕时长
  的两倍是真实情况，不是脏数据（第一版改完就误杀了 11-11 一整天）
- 新增「单个应用 > 24 小时」硬判据（不依赖参照物）

**真机验证**：应用构成 = 米家 8h17m + 屏迹 51m + 桌面 15m + 设置 12m + 其他 5m
≈ 580 分钟，与圆环 9:42（582 分钟）**对上了** ✅

---

#### 二、测试与验证

| 项 | 结果 |
|---|---|
| 全量单测 | **159 通过 / 0 跳过 / 0 失败**（151 → 159） |
| 变异验证 | 7 项，全部报红 |
| 真机冒烟 | 13 项逐条验证，全部通过 |

新增测试：
- `UsageDumpParserTest` +3：事件流推导时长、跨零点切分、多账户各自配对
- `IngestSanityTest` +2 / 改 2：分身并行不误杀、3 倍上限、单应用 24h 硬判据
- `UsageCalculatorTest` +1：自定义阈值真的生效（钉住「不是装饰参数」）
- `RoomUsageRepositoryTest` +2：只在通知事件里的包也能查到名字、兜底回退顺序

**变异验证抓出的两个假绿**（值得记下来）：
1. `只在通知事件里的包` 那条测试第一版**同时把该包插进了 `app_usage`**，
   于是「补全通知包」的代码根本不参与 → 变异全绿。改成只插通知事件才真正测到。
2. `单个应用超过一天` 第一版用 `days = emptyMap()`，走的是「24 小时兜底」路径，
   上限也是 1440 → 去掉硬判据仍然绿。改成给一个 600 分钟的屏幕参照才区分得出来。

---

#### 三、如实记录的边界

1. **没有自动化 UI 测试**。柱图基线、弹窗留白、SegmentedControl 动画这些都是
   靠真机截图 + `uiautomator dump` 的 bounds 逐帧核对的。工程还没接
   `createComposeRule`，写「断言某个常量等于 48dp」的测试必然为真，属于假覆盖。
2. **vivo 未验证**。当前只插着小米这一台。`enableEdgeToEdge` 是官方兼容方案，
   OriginOS 上一般正常，但**刘海/挖孔的不对称 inset**、以及部分 ROM 的
   `--checkin` 行为差异仍需真机确认。这台小米的 `--checkin` 就已经是
   「有时给 CSV、有时只给元信息」的不稳定行为。
3. **底部悬浮导航会压住内容最后一段**。已加 112dp + `navigationBarsPadding()` 留白，
   滚到底能看全；但首屏未滚动时最后一项仍被遮住一角，这是悬浮设计的固有代价。
4. **`CapsuleBarChart` 的列宽由标签撑开**（细柱时列宽 30dp、柱宽 7dp，间距偏松）。
   `MetricBarChart` 已改成固定槽宽，趋势图这条还没动。
5. **`QUERY_ALL_PACKAGES` 是策略变更**。若后续要上架 Google Play，需要在
   Play Console 声明用途并可能接受审核。

---

#### 四、参考对象与借鉴点

**nsh07/Tomato**（GitHub，Material 3 Expressive 风格番茄钟，GPL-3.0）
- 借鉴：`enableEdgeToEdge()` 的用法、页面切换用 `ContentTransform(fadeIn, fadeOut)`、
  导航项图标 `Crossfade` 切换 outlined/filled、Scaffold 的 `contentPadding` 统一处理 insets
- 未借鉴：`ShortNavigationBar` / `motionScheme` / `navigation3`（要求 M3 1.4+ 与
  BOM 2025.x，本项目 BOM 2024.06.00 拿不到；升级风险大于收益）

**fushi（app.fushi.reader，设备上的复视阅读器）**
- 只作为「美术观感」参照：卡片大圆角、大量留白、低饱和主色。
  本项目已有自己的 `FreshPalette` 语义 token 体系，未直接抄它的实现。
- 注：它曾在屏迹安装后 24 秒被另一路安装进来，设备上两个包同时存在，
  排查时别把它当成「装错了」。

---


## 【来源】批次7-设置页与向导-交付说明.md

> 原路径 `docs/批次7-设置页与向导-交付说明.md`（合并后原文件已移除，原始字节见 git 历史 `a89bd33:docs/批次7-设置页与向导-交付说明.md`）

### 批次 7 交付说明 — 设置页 · 首启向导 · HTML 报告导出（2026-11-12）

对应需求 §5.8 / §7-1 / §7-12；对应批次计划批次 7。

#### 1. 设置页（七组，全部真实状态）

| 组 | 落地内容 | 状态 |
| --- | --- | --- |
| 目标与同步 | 每日目标（1–16 小时，滑杆与数字双向，松手才落盘）、后台拉取频率（每天 / 每 12 小时 / 每 6 小时）、最近成功拉取时间、立即拉取（拉取中禁用） | ✅ |
| 权限与数据 | Shizuku 状态 + 重新检测 + 请求授权；使用情况访问状态 + 去系统授权（无该子页时退回应用详情）；应用分类入口；数据覆盖（首末日 + 天数）；导出 HTML 报告 | ✅ |
| 提醒与限额 | 一行「尚未开放」+ 说明（批 11） | 诚实待办 |
| 备份与同步 | 一行「尚未开放」+ 说明（批 8） | 诚实待办 |
| AI 分析 | 一行「尚未开放 · 默认关闭」+ 边界说明（14b） | 诚实待办 |
| 统计口径 | 低使用阈值 1–24 小时，滑杆 + 数字输入（6f 已有，本批随页面重排） | ✅ |
| 外观 | 九套风格入口（复用 StyleSheet）、深色模式、动态取色 | ✅ |
| 关于 | 口径差、应用相加大于总时长、通知打扰/已查看口径、事件明细自首次拉取累积、数据仅存本机 + 当前覆盖 | ✅ |

**为什么待办组不画开关**：点到一个什么都不发生的开关，比没有这个开关更糟（§6 红线）。
三组待办都写明「什么时候来、现在能做什么」，不制造假能力。

#### 2. 首启向导（§7-1）

`ui/onboarding/OnboardingScreen.kt`，四步：

1. **欢迎与主题**：九套风格胶囊 + 深色开关，即时预览（主题是全局 CompositionLocal，选完当场变色）。
2. **Shizuku 授权**：真实状态文案（已授权 / 未安装 / 未授权 / 临时不可用），「重新检测」+「请求授权」，并说明拒绝后自动退到系统「使用情况访问」通道、只是拿不到解锁与通知明细。
3. **目标与频率**：每日目标滑杆（1–16 小时）+ 拉取频率三档。
4. **备份与云端同步**：说明尚未开放 + 现在能做什么（导出 HTML 报告），并解释为什么不做假开关。

每步都有「稍后在设置中配置」；全程可「跳过向导」，最后一步主按钮为「开始使用」。
`onboarding_done` 存 DataStore；**读盘期间只铺底色**——否则冷启动会先完整画一遍主界面（连带触发拉取与四页加载）再被向导盖住，视觉上就是一次白闪。

#### 3. HTML 报告导出（§7-12）

`data/export/HtmlReportExporter.kt` 为**纯函数**，输入全部来自仓库既有查询（`summary` / `dailyTrend` / `appRanking` / `hourlyDistribution`），所以报告与页面同源。

硬约束（单测钉死）：
- 不含 `http://`、`https://`、`<script>`、`<link>`、`<img>`、`css url()`、`@import` —— 断网打开与联网打开完全一致；
- 所有动态文本过 `escape()`（真实应用名里确实有 `&` 与 `<`）；
- 无记录日写「无记录」而不是 0；
- 应用占比分母 = 应用时长合计（与排行页同一口径），并在页脚说明「应用相加可能大于屏幕合计」；
- 导出前可选「隐藏应用名称」，只留「应用 1/2/3…」与颜色。

落盘走 SAF `ACTION_CREATE_DOCUMENT`（`text/html`），不需要存储权限，写完用顶部提示条反馈成功/失败。

#### 4. 拉取频率（顺带修的真问题）

原实现把频率写进 DataStore 却**没有任何读取方**：Worker 永远按 1 天排、`ExistingPeriodicWorkPolicy.KEEP` 让改动永远不生效。
本批：
- 新增 `PullFrequency`（24h / 12h / 6h），读取兼容旧字符串 `"daily"`（大小写不敏感，垃圾值退回默认）；
- `PullScheduler.ensureScheduled(context, frequency)` 改 `ExistingPeriodicWorkPolicy.UPDATE`，改频率当场重排；
- `ScreenTraceApp` 异步读一次频率再排程（不阻塞冷启动，失败只降级为「打开 App 时拉取」）。

#### 5. 新增/改动文件

- 新增：`data/export/HtmlReportExporter.kt`、`data/pull/ShizukuAuth.kt`、`ui/AppCategoryScreen.kt`、`ui/onboarding/OnboardingScreen.kt`
- 新增测试：`HtmlReportExporterTest`（9）、`PullFrequencyTest`（4）
- 改动：`ui/SettingsScreen.kt`（重写）、`ui/AppNav.kt`（向导闸门 + 设置接线 + 分类页路由 + 版本号）、`ui/AppViewModel.kt`（分类/频率/通道状态/报告/向导状态）、`contract/UsageRepository.kt`（`AppCategoryRow` + `appCategories()`）、`data/RoomUsageRepository.kt`、`data/prefs/SettingsStore.kt`、`data/pull/PullWorker.kt`、`ScreenTraceApp.kt`、`docs/实施任务批次计划-v2.md`

#### 6. 未做 / 下批

- JSON/CSV 备份恢复与云同步（批 8）、提醒与限额（批 11）、AI 分析（14b）
- PNG 长图导出（周期总结 14a 余项，与报告导出共用排版时可一起做）
- 真机验证：向导各分支、SAF 保存位置、断网打开报告

---


## 【来源】批次8a-本地备份恢复-交付说明.md

> 原路径 `docs/批次8a-本地备份恢复-交付说明.md`（合并后原文件已移除，原始字节见 git 历史 `a89bd33:docs/批次8a-本地备份恢复-交付说明.md`）

### 批次 8a 交付说明 — 本地备份导出与统一恢复（2026-11-12）

对应需求 §5.10「导出（本地，SAF）」与「统一恢复流程」；对应批次计划批次 8 的第一步与 8a。

#### 1. 数据库先走一步：`app_limit` + DB v5

`app_limit(pkg PK, limitMin, enabled)` + `MIGRATION_4_5`，DB version 4→5。

在批 8 就建表（业务留到批 11）的理由：备份 `schemaVersion=4` 必须包含八张表。
等批 11 再建的话备份结构又得涨一版，之前导出的文件全部要再写一段兼容分支——
代价比现在多建一张空表大得多。

#### 2. 备份结构（`data/backup/BackupModels.kt`）

`{ schemaVersion, exportedAt, appVersion, settings, day_summary[], app_usage[], hour_usage[], pull_log[], unlock_event[], notification_event[], screen_session[], app_limit[] }`

- `schemaVersion = 4`（v1 四表 / v2 +事件表 / v3 +会话区间 / v4 +应用限额与新增设置项）；
- 所有列表都有默认空值：恢复 v1/v2/v3 时缺的键就是「该备份不含此表」；
- `settings`：每日目标、低使用阈值、主题风格、深浅色、动态取色、自定义主题种子、拉取频率、分类覆盖、超时提醒开关（批 11 前恒为 null）。

#### 3. 校验与摘要（`BackupCodec`）

先用 `JsonObject` 判三件事，再反序列化——直接 decode 时缺字段只会抛一句
`SerializationException`，用户看不懂：

| 情况 | 结果 |
| --- | --- |
| 不是 JSON | 「不是有效的 JSON 文件」 |
| 缺 `schemaVersion` | 「可能不是屏迹的备份文件」 |
| 版本 > 4 | 「版本 N 高于当前 App 支持的 4，请先升级」 |
| 版本 < 2 / < 3 / < 4 | 摘要里逐条提示缺「事件明细 / 会话区间 / 应用限额」，**不阻断** |

摘要同时给导出时间、App 版本、日期范围、各表条数、是否含设置。

#### 4. 合并规则（`BackupMerge`，纯函数 + 单测）

- `day_summary`：同日取 `screenUnlockMin` **较大的一行整体保留**。
  ⚠️ 不是逐字段取大——逐字段会拼出「A 的屏幕时长 + B 的解锁数」的假行；
- `app_usage`：同 `(date,pkg)` 取 `minutes` 较大值，`name/category` 跟着那行走；
- `hour_usage`：同 `(date,hour)` 取较大值；
- `pull_log`：仅追加；
- `unlock_event`：主键去重；
- `notification_event`：**按 `(秒,type,pkg)` 分组比条数**，把多出来的补进去（简单按主键去重会把同秒第二条真实通知丢掉）；
- `screen_session`：全表按 kind 扫线并集后重写，再用 `SessionUnion.daySlices` **重算受影响日期**的日聚合与小时矩阵，与旧值取大（否则会出现「区间 16h、日聚合 11h」两套数）；
- `app_limit`：同 pkg **以备份为准覆盖**（限额是用户意图，没有「取大」的道理）；
- 设置：**默认不导入**，勾选「同时恢复设置」才写。

#### 5. 落库（`BackupRepository`）

- 导出：`buildFile(appVersion)` 逐行照抄八张表 + 设置；JSON 单文件；CSV 七张表（UTF-8 BOM、逗号/引号/换行转义、Excel 直接打开不乱码）；
- 恢复：`db.withTransaction { ... }` 单事务，覆盖时先清空八张表再导入（含 `app_limit`），任何异常整体回滚，原数据不变。

#### 6. 界面（`ui/BackupGroup.kt`，接在设置页「备份与同步」组）

- 「导出 JSON」→ 系统文件选择器（`CreateDocument("application/json")`）；
- 「导出 CSV」→ 系统目录选择器（`OpenDocumentTree`）+ `DocumentFile` 逐表写 7 个文件，同名先删再建；
- 「选择备份文件」→ 读取 → 校验 → **摘要弹层**：策略二选一（合并默认、覆盖带 ⚠ 红字）、**覆盖需二次点击确认**、可选「同时恢复设置」；
- 完成后顶部提示条给结果摘要「新增 X 条、更新 Y 条、跳过 Z 条」，并重算四页。

#### 7. 测试

- `BackupMergeTest`（14 例）：同日整行语义、本地更大跳过、应用名字跟着较大值走、秒级分组补条数、限额覆盖、旧版本缺表提示、缺版本/版本过高/非 JSON 拒绝；
- `BackupRepositoryTest`（4 例，Robolectric 真 SQLite）：导出八表行数、JSON 往返后覆盖恢复行数一致、合并时本地更大值不被覆盖、覆盖后本地独有日期被清除。

全量 **261 例全绿**；`assembleDebug` 通过。

#### 8. 未做（8b）

WebDAV / Google Drive provider、自动同步频率（关闭/每次成功拉取后/1/3/7 天）与仅 Wi-Fi、失败分类提示、云端摘要与恢复入口。

---


## 【来源】批次10a-自定义主题引擎-交付说明.md

> 原路径 `docs/批次10a-自定义主题引擎-交付说明.md`（合并后原文件已移除，原始字节见 git 历史 `a89bd33:docs/批次10a-自定义主题引擎-交付说明.md`）

### 批次 10a 交付说明 — 自定义主题派生引擎（2026-11-12）

对应需求 §5.4「自定义主题（CR-04：种子色 + 自动校正）」；对应批次计划批次 10 的 10a 部分。

#### 1. 引擎（`theme/PaletteEngine.kt`，纯函数）

```kotlin
PaletteEngine.paletteFromSeed(seed: Color, dark: Boolean): FreshPalette
```

三段式：

1. **安全窗口**（`safeSeed`）：近纯黑（`luminance < 0.02` 或 `l < 0.14`）提到 `l = 0.26`；
   近纯白（`luminance > 0.82` 或 `l > 0.90`）压到 `l = 0.46`；饱和度过 0.95 收敛到 0.86。
   不先夹取的话，纯黑种子派生出来整页是灰的，纯白种子会让 `onPrimary` 全部扑街。
2. **派生**：复用九套预设的 `derivePalette`（把 `ThemeVariant.palette` 的派生体抽出来共用），
   只有三族按种子重算：
   - `chart1~5`：色相等距旋转 24°，明度阶梯（浅色 +0.18/档，深色 +0.07/档）；
   - `month1~3` / `heat0~4`：明度单调（浅色递减、深色递增）；
   - tonal 双卡 / 导航底 / 浅色背景：按种子色相与饱和度派生。
3. **自动校正**（`correct`）：正文 4.5:1、图形 3.0:1，不达标按 **±2% 明度步进**收敛，
   单方向上限 50 步、走完换反向（合计覆盖整个明度轴，收敛有保证）。校正顺序有讲究：
   - 先修 `surfaceVariant`（浅色下深种子会让深色正文看不清）；
   - 再修次要正文（对 background / surface / surfaceVariant 三处同时 ≥4.5）；
   - **深色容器先压暗**，再修主色——随机亮种子会让 `seed.mix(DarkNeutral, 0.42)` 停在中间灰，
     深色主题里「数字 / 单位 / 对比行」三处会一起掉到 3.1–4.4（实测踩到，见下）；
   - 主色要求同时满足：对底 ≥3:1、对两张 tonal 卡 ≥4.5（对比行）、存在能达 4.5 的 `onPrimary`；
   - 最后是容器文字、月历色档（保证日号有 ≥4.5 的选择）、chart（对卡片 ≥3:1）。

#### 2. 测试（`PaletteEngineTest`，6 例）

| 用例 | 断言 |
| --- | --- |
| `extremeSeedsPassAllPairs` | 8 个极端种子（纯黑白红绿蓝 + 近黑近白 + 高饱和青）× 浅/深，跑与 `FreshPaletteContrastTest` **同一组色对与阈值** |
| `randomFiftySeedsPassAllPairs` | 固定种子的 50 个随机色 × 浅/深，同上 |
| `sameSeedIsDeterministic` | 同一 `(seed, dark)` 两次结果完全相等（纯函数承诺） |
| `safeSeedLiftsPureBlackAndDropsPureWhite` | 纯黑被提亮、纯白被压深 |
| `heatAndMonthLaddersAreMonotonic` | 浅色递减、深色递增 |
| `chartHuesAreEquallyRotated` | 相邻 chart 色相 ≈24°（±12° 容差，明度校正会轻微带偏） |

**变异验证**：把 `fit()` 改成「永远原样返回」（即关掉校正），两个主用例立刻变红
（极端种子 6 项违规、随机种子 61 项违规）；还原后全绿。说明校正代码是真正吃劲的地方，
不是摆设。

#### 3. 取色器（`ui/ThemeSeedSheet.kt`）

- 色相 / 饱和度 / 明度三滑杆；**明度限定 0.25–0.72**——超出这个窗口的种子会被 `safeSeed`
  强行改色，用户会觉得「我选的颜色不算数」；
- 8 个快捷色点（即九套预设的主色）方便快速起手；
- 实时预览：正文行 + 主色块 + tonal 双卡 + chart 五色条，全部用**派生后的真实色板**绘制；
- 「恢复预设风格」清除种子（`custom_theme_seed` 从 DataStore 移除）。

#### 4. 持久化与接线

- `SettingsStore.customThemeSeed: Flow<Long?>`（Long 而非 Int：ARGB 高位为 1，存 Int 在备份 JSON 里是负数，排查时看不懂）；
- `AppViewModel.customThemeSeed` + `setCustomThemeSeed(Long?)`；只替换调色板，不重拉数据、不改筛选；
- `ScreenTimeTheme(..., customSeed: Color?)`：非空时调引擎，否则用预设 variant——预设仍是「关掉自定义后回到哪套」的记忆；
- 设置「外观」组新增「自定义主题 · 已启用 / 未启用」行，界面风格行在启用自定义后标注「预设（已自定义）」。

#### 5. 未做

- 种子色纳入 JSON 备份 settings（随批 8）
- 下拉刷新联动「每次成功拉取后」自动同步（随批 8）
- 真机人工核对：极端种子在真机上的观感（浅/深各看一遍）

---


## 【来源】批次14a-周期总结-交付说明.md

> 原路径 `docs/批次14a-周期总结-交付说明.md`（合并后原文件已移除，原始字节见 git 历史 `a89bd33:docs/批次14a-周期总结-交付说明.md`）

### 批次 14a 交付说明 — 周期总结（周 / 月 / 年）

- 日期：2026-11-12
- 对应需求：§5.11、§7-27（以及连带 §7-26 排行口径）
- 结论：**14a 已完成**（PNG 长图 / HTML 导出留作余项）；14b（AI 分析）未开始

#### 1. 做了什么

##### 1.1 领域层：`contract/PeriodSummary.kt`（新增，纯函数）

| 概念 | 口径 |
| --- | --- |
| `PeriodType.WEEK` | 周一 00:00 – 周日 24:00（设备本地时区）；2026-01-01 属于 2025-12-29 那一周 |
| `PeriodType.MONTH` | 自然月 |
| `PeriodType.YEAR` | 自然年 |
| `PeriodRange.start/end` | **自然周期**首尾（周永远 7 天、月永远到月末） |
| `PeriodRange.dataEnd` | **数据窗口**截止 = min(今天, end)；`unfinished = dataEnd < end` |
| `dayCount` | 数据窗口天数（未结束只算已过天数）→ 覆盖率分母 |
| `previousEqualLength()` | 上一周期的**前 N 天**（N = 已过天数）。本周三对比「上周一~上周三」，不是「上周五~上周日」 |

`PeriodSummaryCalculator.build(type, today, trend, previousSummary, apps, hours)` 输出
`PeriodSummary`：总时长 / 日均（分母只含有记录日）/ 对比 / 逐日柱 / 年视图月柱 /
最长最短 / Top5 / 时段 / 结论 / 覆盖 / 标题副标题。

关键约束（都有单测钉住）：

1. **未记录日不进日均分母、不画 0 柱**；
2. **未结束周期**副标题写「11月1日–11月30日 · 未结束（截至 11月12日）」，覆盖率按已过 12 天算；
3. **跨月长度不同不直接比总量**，一律比日均；
4. **年视图是「月日均」**，未记录的月份整根不出现；
5. 结论 ≤6 条，只陈述事实（禁用「因为/所以/建议/应该/注意/健康」），有记录 < 2 天时**不出结论**。

配套改动：

- `UsageCalculator` 新增 `summarizePoints` / `longestShortest` / `compareAverage`；
  原来的 `compare(...)` 区间分支改为复用 `compareAverage`，**百分比算法只有一份**。
- `PeriodSummaryCalculator.toPlainText(period, hideAppNames)`：导出文本与页内同源；
  `hideAppNames = true` 时**连结论里的应用名一起遮掉**（只遮榜单等于没遮，这个坑是单测抓出来的）。

##### 1.2 组件层：`uikit/MonthlyBarChart.kt`（新增）

年视图 12 根月柱：0/中/顶三档刻度 + 虚线平均线 + 只标 `1月/6月/12月`；
点柱在图表下方显示「3月 · 日均 2小时10分钟 · 有记录 21 天」。
不能复用 `CapsuleBarChart`：后者横轴按 `DailyPoint.date` 格式化，年视图要写月份。

##### 1.3 页面层：`ui/PeriodScreen.kt`（新增）

顺序固定（一页读完）：周/月/年切换 → hero（总时长 + 日均 + 对比 + 「未结束」标签）→
趋势（周 7 根 / 月 28–31 根 / 年 12 根月柱）→ 最长/最短双卡 → Top5 → 时段占比 →
结论 → 覆盖说明 + 「复制总结文本」。

细节：

- 选中态与图表都读 `period.type`（数据自带类型），避免「已选中年、画的还是月柱」的半帧错位；
- `Crossfade` 内容是单插槽，外层**包了 Column**（本项目第四次踩这个坑，已写在注释里）；
- 复制后用按钮文案「已复制到剪贴板」反馈 2 秒，不弹 Toast。

##### 1.4 入口与导航

- **统计页底部**：三行卡「本周总结 / 本月总结 / 本年总结」，每行写明当前周期日期范围；
- **日历页顶部**：「本周总结 · 查看周/月/年」；
- 二级页走既有 `ContainerTransformOverlay`（从按下位置展开 / 返回缩回）；
- **「查看全部」带周期口径进排行**：`AppViewModel.rankingScope` 覆盖排行数据窗口，
  排行副标题由 `RankingData.summary.selection` 渲染（显示「11月1日 – 11月12日」），
  返回时复位为「今天」，避免下次从今日页进入还带着周期窗口（§7-26）。

##### 1.5 顺手修的一个真 bug（与本批无关但今天变红了）

`PullCoordinator.isPartialHistory()` 原来用 `LocalDate.now(zone)` 判「首次拉取是否只拿到部分历史」，
单测用的是固定日期 2026-11-03 —— **2026-11-12 当天正好越过 9 天窗口，测试自己变红**。
改为从注入的 `clock()` 推导「今天」：与节流用同一个时间源，跨午夜不会两处不一致，测试也不再随日历漂移。

#### 2. 验证

- `./gradlew :app:testDebugUnitTest`：**224 个单测全绿**（原 209 + 新增 15）
- `./gradlew :app:assembleDebug`：`BUILD SUCCESSFUL`，无 `e:` / `w:`
- **变异验证（防假绿）**：
  - 把 `previousEqualLength()` 改成「取上一周期最后 N 天」→ 3 条测试立刻变红，改回后转绿；
  - `toPlainText(hideAppNames = true)` 只遮榜单不遮结论 → 「隐藏应用名称」用例变红，补遮罩后转绿。

#### 3. 未做 / 待真机确认

- **PNG 长图 / HTML 导出**（14a 余项）：目前只有「复制总结文本」，SAF 导出与长图绘制留到下一轮；
- **14b AI 分析**：`AiProvider` / OpenAI 兼容适配器 / payload 预览 / 密钥存储全部未开始；
- **真机待确认**（电脑在公司，今晚无法真机验证）：
  1. 统计页底部三行入口的点击区 ≥48dp、与洞察卡间距；
  2. 年视图 12 根柱在 360dp 宽度下的柱宽与「1月/6月/12月」标签是否被裁；
  3. 从周期总结进排行再返回，排行副标题是否回到「今天」；
  4. 当天数据不足 2 天时结论区的「数据不足以得出结论」文案；
  5. 容器变换从「本周总结」那一行展开的落点是否对齐。

---
