# 批次 6b–6d 代码评审（DeepSeek V4.1 Flash）

> 范围：`c92a693`（6a 返修）之后到 `20d2d1e`（6d）共 6 个提交，约 3.4k 行新增。
> 评审方式：读全部生产代码 + 强制重跑单测（`--rerun-tasks`）+ 写临时探针跑降级通道
> 的真实数据流（跑完已删除），不采信交付说明里的数字。

## 结论先说

骨架和测试质量比上一轮明显进步：**105 测全绿 0 skip**，6a 那条 P0（`IN (:msList)`）
修得干净且补了「近五千条 + 反射守接口」的回归；6b 的两个「顺手修的真 bug」也是真的
（`WorkManager` 拖垮 `onCreate`、`persist` 接收者歧义）。

但 **6b 的降级通道在界面上是死的**：数据入库成功、仓库能读出来，四个页面却全部渲染成
「尚无使用记录」。这不是文案问题，是 `loadAll` 的入口判定只看 `day_summary`，而降级通道
按设计**不写** `day_summary`。对没有 Shizuku 的用户（也就是绝大多数用户）来说，App 拉了
一次、成功了、然后告诉他「没有数据」。

**判定：6b 不通过（P0-1），6d 有条件通过（P1-2 修完再上真机）。**

---

## P0-1　降级通道的数据进得来，但一个页面都不显示

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

## P1-1　「解锁 0 次 / 已查看 0 次」的假 0 还在卡片上，而且 6d 说已兜住的那句话在真机上到不了

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

## P1-2　区间态柱图横轴标签被槽宽裁掉（≥8 天的趋势不可读）

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

## P1-3　主通道超时会把「半份 dump」当成功入库，尾部静默丢失

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

## P1-4　降级通道整类零测试，而它是普通用户唯一的通道

`UsageStatsUsageProvider` 没有任何测试（`ForegroundBucketerTest` 测的是纯函数，
适配层没测）：事件类型 `1 / 2 / 23` 的字面量映射（minSdk 26 拿不到常量，写错就是 0 数据）、
`appendSpan` 的跨零点按日切分、`perApp` 的 `launches` 归属、`days = emptyMap()` 这个
「绝不写日聚合」的约束——全靠人眼。这几处任一处写错，界面上都无法区分「没数据」和「算错」。

Robolectric 有 `ShadowUsageStatsManager`，可以像 `IngestPipelineTest` 那样把
「事件进 → 逐日逐应用分钟数出」钉住。这跟 6a 那条 P1-1 是同一种失守方式：声称覆盖，实际没覆盖。

---

## P2（不阻塞，建议随批 7 一起收）

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

## 做得对的地方（避免下一轮被改坏）

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

## 建议顺序

1. **P0-1**（降级通道可见性）——这是「功能在、用户看不到」，优先级最高；
2. **P1-1**（假 0 + 卡片/详情不一致）——同一次改动里能一起收，`MetricDetail` 加标志位；
3. **P1-3**（截断当成功）——两行改动，防的是最难查的静默数据丢失；
4. **P1-2**（柱图标签）与 **P1-4**（降级通道测试）——真机验证前修掉，否则第一次装上去
   就会同时撞上「字被裁」和「这条通道从没测过」。

修完 P0-1 + P1-1 之后，6b 的验收项「Empty→Room 一次性切换，四页显真实数据」才真正成立；
6b 交付说明里「排行与时段仍可用」这句也需要按实际能力改写。
