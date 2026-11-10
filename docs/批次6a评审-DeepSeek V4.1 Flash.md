# 批次 6a 评审（DeepSeek V4.1 Flash，2026-11-10 18:40）

审的提交：`df8ca06`（feat(data): 批次 6a——Room 六表 + 合并策略 + DataStore + 解析入库管道）。
KSP 真跑过（`kspDebugKotlin` 强制重跑通过），44 测全绿（16 领域 + 6 合并 + 17 解析器 + 5 对比度），工作区干净。

---

## 一、结论

**骨架合格，但不通过。** 六表 schema 与 §4 逐字段一致（原四表没被改义，红线守住），合并策略抽成纯函数、单事务 + 失败回滚、SettingsStore 的键设计都对。问题集中在「接上真实数据就会露」的地方：

| 级别 | 问题 | 后果 |
|---|---|---|
| P0 | `deleteByMs` 用 `IN (:msList)` 且不分批 | Android 8–11 上多日重拉必崩（SQLite 变量上限 999，一次 4837 条） |
| P1 | `category` 全写 `"OTHER"`，`categoryOverrides` 无人消费 | 排行页图例永远只有「其他 100%」 |
| P1 | **管道单测完全缺失**（无 Robolectric / androidTest / room-testing） | 计划明确要求的「连续拉取不重复计数」没有任何测试 |
| P1 | `metricDetail` 的 `gapDays = emptySet()` | 覆盖三态退化成两态，违反 §4「查询层必须能区分三种状态」 |

---

## 二、对照 6a 任务清单

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

## 三、P0　`deleteByMs` 会在多日重拉时炸掉

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

## 四、P1

### P1-1　`category` 永远是 `OTHER`，排行图例必然退化

- `IngestPipeline.kt:54`：`category = "OTHER"` 硬编码；
- `AppNames` 只有 `nameOf`，没有 `categoryOf`，全仓不存在任何 pkg→分类映射；
- `SettingsStore.categoryOverrides` 有持久化，但 `RoomUsageRepository` 从没读过它。

而 `RankingScreen.kt:99` 正是 `groupBy { it.category }` 来画分类占比条的 —— 真实数据一接，排行页顶部就是一条纯色「其他 100%」，§5.7 的分类着色等于没做。

**修法**：`AppNames` 加 `categoryOf(pkg): AppCategory`（按 §4 六类建表，至少覆盖 dump 里出现量前 30 的包），`IngestPipeline` 写库时用它；`RoomUsageRepository.appRanking` 再叠加 `categoryOverrides`（用户改过的优先）。

### P1-2　管道单测缺失，而提交信息把它算进了合并单测

6a 验证项原文：「管道单测覆盖『连续拉取不重复计数』『同秒多条通知不丢失』『双源合并不重复』」。现状：

- `app/src/` 下只有 `main` 和 `test`，**没有 `androidTest`**；
- `build.gradle.kts` 只有 `testImplementation("junit:junit:4.13.2")`，**没有 robolectric / room-testing**；
- `MergePolicyTest` 6 条测的是纯函数，`IngestPipeline` 一行没测。

`MergePolicy` 正确 ≠ 管道正确。管道里有三件事纯函数测不到：`existing` 怎么取窗口、`delete` 与 `insert` 的顺序、失败是否真回滚。这类差别恰好就是上一轮「对账断言被跳过」的同一种失守方式——**声称覆盖了，其实没覆盖**。

**修法**：加 `testImplementation("org.robolectric:robolectric:4.11.1")` + `room-testing`，用 `Room.inMemoryDatabaseBuilder` 跑三条：同一份 `ParseResult` 连灌两次断言 `notification_event`/`unlock_event` 行数不变；同秒 3 条通知灌两次仍 3 条；`result.days` 第二次全小值时 `day_summary` 不回退。

### P1-3　`metricDetail` 的三态覆盖退化成两态

```kotlin
gapDays = emptySet(),   // 批 6b 起从 pull_log 推导
```

`UsageCalculator.coverageOf` 的三态是：`windowStart` 之前 → `NO_RECORD`、`gapDays` 内 → `GAP`、其余 → `RECORDED`。`gapDays` 恒空，`GAP` 这个分支永远不会出现 —— 拉取失败的日期会被画成「真实 0 次」，正是 §4 那句「UI 不得把无记录时段画成真实的 0」要禁的东西，也是 §5.7 详情页的验收点（§7-16）。

6a 说「metricDetail 全实现」，这一条该打折。**而且 6a 就能做，不用等 6b**：`pull_log` 里 `ok = false` 的行，把 `pulled_at` 那天计入 `gapDays`（拉取失败当天数据不可信），这是本地纯查询。

### P1-4　`appLabels` 忽略库里的 `name`

```kotlin
val appLabels = db.appUsageDao().allNames().associate { it.pkg to AppNames.nameOf(it.pkg) }
```

`name` 列现在是 pkg 本身，所以看不出问题；但 6b 一旦用 PackageManager 回填中文名，这里仍然只认 `AppNames`，详情页通知排行会退回显示包名。改成 `it.pkg to it.name.takeIf { n -> n != it.pkg } ?: AppNames.nameOf(it.pkg)` 即可。

---

## 五、P2（不阻塞）

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

## 六、放行条件

1. 修 P0（`deleteBetween`）——两行；
2. 修 P1-1（分类映射 + overrides 消费）——不然 6b 一接真实仓库，排行页当着人的面挂；
3. 补 P1-2 的管道测试（加 Robolectric）——这是 6a 唯一没交付的验收物；
4. P1-3 至少把 `pull_log.ok=false` 的日期接进 `gapDays`，或明确在 6a 交付说明里降级。

P2 可留到批 9 巡检，但 P2-1（schema 导出）建议现在改，越晚改越贵。

---

评审人：DeepSeek V4.1 Flash（独立会话）
实测：`JAVA_HOME=C:/Users/Administrator/.jdks/jbr-21.0.11 ./gradlew.bat :app:testDebugUnitTest --rerun-tasks` → 44 测，0 fail，0 skip
额外核对：`app/build/generated/ksp/debug/.../NotificationEventDao_Impl.java` 的 `deleteByMs` 生成代码、`RankingScreen.kt:99` 的 `groupBy { it.category }`、`UsageCalculator.coverageOf` 三态分支
