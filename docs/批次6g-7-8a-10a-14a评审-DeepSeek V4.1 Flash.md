# 批次 6g / 7 / 8a / 10a / 14a 评审

评审日期：2026-11-12
评审对象：`319fdc0`（6g 收口）、`58de38c`（14a 周期总结）、`1c441d1`（批 7 设置/向导/报告）、`373e0f9`（10a 主题引擎）、`7650966`（8a 备份恢复）
代码规模：约 7,400 行新增，5 个提交
评审方式：逐文件走查 + `--rerun-tasks` 强制重跑全量单测 + 写临时 Robolectric 探针跑真实 Room 验证合并语义

---

## 一、结论

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

## 二、P1 逐条

### P1-1（8a）合并恢复绕过 `IngestSanity`，虚高应用行会永久留在库里

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

### P1-2（8a）`mergeSessions` 是 8a 最危险的一段，却一条测试都没有

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

### P1-3（8a）四次迁移零测试，其中两次是不可逆清表

`Migrations.kt` 有 `1→2`（建 `screen_session`）、`2→3`（`DELETE app_usage`）、`3→4`（`DELETE hour_usage`）、`4→5`（建 `app_limit`）。项目里 `exportSchema = true`、`app/schemas` 有 1..5 五份 JSON，`MigrationTestHelper` 的原料齐了，但没有一条迁移测试（`grep MIGRATION_` 在 `src/test` 与 `src/androidTest` 均无命中）。

风险不是理论值：迁移写错（列类型、索引名、`NOT NULL` 漏写）时 Room 会在**用户升级后第一次打开数据库**抛 `IllegalStateException`，App 直接起不来；而本项目的兜底手段恰好是"清表重建"，用户只能丢数据。`4→5` 是这次新写的，索引/主键名靠人眼对齐 `5.json`，这是最容易差一个字符的地方。

建议至少补 `4→5`：`MigrationTestHelper.createDatabase(4)` → `runMigrationsAndValidate(5, MIGRATION_4_5)` → 断言 `app_limit` 可插入可查询。这是 20 行的事。

---

### P1-4（7）DataStore 读失败时，首启向导会把 App 卡成永久空白

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

### P1-5（10a）选了自定义主题之后，点「界面风格」里的九套预设，界面不会变

`ScreenTimeTheme` 的优先级是 `customSeed ?: variant.palette(dark)`（`ScreenTimeTheme.kt:106`），而风格弹层的回调没有清种子：

```kotlin
StyleSheet(current = variant, onSelect = viewModel::setThemeVariant, ...)   // AppNav.kt:623
```

`setThemeVariant` 只写 variant，不动 `customThemeSeed`。于是用户启用过自定义主题后再选「海盐」「暮色」，`variant` 变了、`customSeed` 还在 → 色板仍由种子派生 → **界面一个像素都不变**，只有「外观」组里那行 "预设（已自定义）" 透露出发生了什么。用户会判定主题切换坏了（§5.4 要求切 variant 只替换调色板）。

**修法**：`onSelect = { viewModel.setThemeVariant(it); viewModel.setCustomThemeSeed(null) }`；或保留种子但把弹层里当前项显示为「自定义（不跟随预设）」。前者更符合"点哪套就是哪套"的直觉。

---

## 三、P2 清单

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

## 四、建议修复顺序

1. **P1-1**（合成一行 `IngestSanity` 过滤 + 一例测试）——数据正确性，越晚修越可能已经污染用户库；
2. **P1-2 / P1-3**（两条测试，纯测试文件，不动生产代码）——把 8a 的验收物补齐；
3. **P1-5**（一行回调）——用户可感知的"功能像坏了"；
4. **P1-4**（异常路径兜底）——影响小但后果重（白屏无法自救）；
5. P2 里优先做 1、2、7：分别对应「日志膨胀」「假 0 口径」「主题重组合开销」。

修完这四条 + 补完两处测试，8a 可以结案并进入 8b（WebDAV/Drive）。8b 会复用同一套 `BackupMerge` 与 `restore`，P1-1 那种"信任远端数据"的假设在云同步里会更危险，建议在那之前定好「远端数据先过一遍自洽校验」的原则。
