# TODO · 当前待办（单一来源）

> 本文件是**待办的唯一来源**；`docs/项目文档地图.md` 第 2 节只链接到这里，不再复制清单。
> 最后核对：2026-11-16（**CR-21 云备份三项改进落地（设备列表显示机型 + 跨设备累加合并 + 切换设备真切换），493 测全绿 + 13 处变异全红，Redmi 真机复核，APK `902c746d`**；同日下午 **CR-21 补记**修 P0 记账缺陷、结果提示可见性与累加边界，**§7 增至 46 条**）。此前同日（**CR-20 顶栏拆回四枚独立圆按钮 + 壁纸卡片配色跟随壁纸（两套方案可切换对比）落地，464 测全绿 + 11 处变异全红，APK `795896d9`**）。此前同日（**CR-19 顶栏纵排 + 壁纸半透明/遮罩加深 + 全屏裁切编辑落地，445 测全绿 + 9 处变异全红，APK `4f5dfbbb`**）。此前 2026-11-15（**CR-18 两条裁决落地 + Redmi 真机四步复核通过，437 测全绿**）。**P3 三条已完成（432 测全绿 / 0 skip）**：P3-3 删 cursor 参数、P3-2 格式错分支补真覆盖（计划原例实测修正）、P3-1 读屏合并语义（真机发现 clearAndSetSemantics 清掉点击语义并补回）。此前 2026-11-14（**Step 9 深色 + 九套主题巡检通过**（54 截图 + §7.3 抽检，读屏缺口已补齐）；8b-2 自动同步接线完成；339 测全绿含变异验证；双机均已补装并完成 CR-14 冒烟；验收 37 迷你柱已撤销）。**CR-15 六条反馈方案已由负责人两轮裁定并于 11-14 调整（需求文档头部登记，按下方批次实施）**；**批 A0 闪退修复已完成（346 测全绿 + 变异验证 + Redmi 真机复现通过）；批 A1 时段分布真日均 + 今日页切日期过渡已完成（351 测全绿 + 变异验证 + Redmi 录屏抽帧复核）；批 B 日期状态拆分已完成（360 测全绿 + 变异验证 + Redmi 真机双状态互不影响复核）；**批 B 补丁 CR-16 二级页弹层收窄已完成（362 测全绿 + 两处变异验证 + Redmi 真机三路复核）；**CR-15 评审遗留 P1-2 / P2-1~5 已修完（367 测全绿）；CR-17 三条真机改进已完成（381 测全绿 + Redmi 真机三条复核）**；**批 C 多设备数据层已完成（404 测全绿 + 14 处变异验证 + Redmi 真机复核，APK `6962b183`）；**批 E 自定义壁纸已完成（418 测全绿 + 11 处变异验证 + Redmi 真机选图/暗化/模糊/深浅主题/状态栏/恢复默认复核，APK `6e1321ca`）；**批 D 四宫格顶栏已完成（428 测全绿 + 9 处变异验证 + Redmi 真机四宫格/日历三枚/设备面板/转场起点复核，APK `61aaaaef`）****。README 冻结，仅 4 行路径例外。
> **15:40 批 C 三项裁定（负责人均按推荐）**：① 下一步推批 C（不先清 P3 / 不做批 E / 不先推送）；② 交付边界＝数据层 + 云同步页最小设备卡片（顶栏四宫格仍留批 D）；③ 根目录旧备份原地保留、归「未知设备」、不迁移不删除。需求文档头部已登记，§4 / §5.10 / §6 / §7-13 同步。
> **16:59 批 E 三项裁定（负责人均按推荐）**：① 入口＝设置页「外观」组内卡片（不新增页面 / 不并入 StyleSheet / 不动顶栏）；② 暗化 0–60% + 模糊 0–16dp 两条滑杆，API < 31 禁模糊并提示；③ 遮罩只存一份参数，浅/深自动叠底色 + 按壁纸亮度微调，状态栏仅启用壁纸时重判。需求头部已登记，§5.4 / §6 同步。
> **20:05 批 D 三项裁定（负责人均按推荐）**：① 下拉面板四枚圆按钮**一行横排**右对齐（日历 tab 三枚不缺角）；② 「切换设备」＝**下拉内轻量设备列表面板**（接批 C 只读 API、内联展开摘要、可返回四宫格，不跳云同步页）；③ 点面板「设置」进设置页时**容器变换起点重登记为被点的那枚圆按钮**。另：**GitHub 推送因环境权限规则拒绝读取 `~/.ssh/` 而失败**，改由负责人手动执行 `git push origin main`（本轮批 D 提交届时一并推送）。需求头部已登记，§5.2 / §5.5 / §5.6 / §6 / §7-36 同步。
> **09:20 CR-20 两条裁定（负责人真机反馈）**：① 顶栏四枚动作**拆回独立圆按钮**（推翻批 D ① 与 CR-19 ① 的单按钮+下拉面板）：主页面横排「切换设备 / 日期 / 主题 / 设置」，日历 tab 三枚；② 壁纸**卡片色跟随壁纸主色**（取色 → `paletteFromSeed` 派生）+ **透明度大幅下调**（浅 60% / 深 55%），未启用壁纸时完全保持原样；强调色是否跟随壁纸提供**两套方案**（仅卡片 / 整套主题）由设置页切换对比。**真机复核追加 ③**：浮层（底部弹层 / 设备面板 / 提示条）底色必须**不透明**（新 token `panelSurface`），否则壁纸与底下数字透上来会与浮层文字叠字。
> **08:15 CR-19 三项裁定（负责人今早反馈）**：① 下拉面板改**纵向一列四枚** + 展开过渡动画（推翻批 D ①一行横排）；② 壁纸模式全 App 卡片半透明（浅 92% / 深 88%）+ 遮罩自动加深；③ 壁纸全屏预览裁切编辑（拖动/捏合，参数落 DataStore）。另：**P3 三条与原两条待裁决已由 AS 的 Claude 插件于 11-15 完成**（437 测全绿）。
> 文档归类后的目录说明见 `docs/项目文档地图.md` §6；历史评审与交付档案分别在 `CODE_REVIEW.md`、`WRAPUP.md`。

## 已完成（截至 2026-11-14）

- [x] **Step 9 深色模式 + 九套主题逐套巡检（2026-11-14）**：`scripts/patrol_themes.py` 真机 54 截图（九套 × 浅/深 × 三主页面）拼 6 张联系表目检**全部通过**；§7.3 抽检补齐——字体 1.3 倍无截断、移除动画下容器变换正常退化、**发现并补齐圆环/趋势柱读屏描述缺口**（`RingMath.description` / `TrendChartMath.barDescription`，7 测变异验证，339 测全绿）。遗留登记：TalkBack 实听、平板宽度、Redmi 待补装含读屏补齐的构建。详见交付说明第 15 节。

- [x] **8b-2 自动同步生产接线（2026-11-14）**：`PullCoordinator.onSuccess` 钩子（批 8 预留）接 `AutoSyncScheduler.onPullSuccess`——覆盖下拉 / 手动重试 / 打开节流拉取 / 每日 Worker 全部成功路径（CR-04 手动下拉联动由此统一覆盖）；频率窗口复用 `SyncManager.shouldAutoSync`，上传走 `AutoSyncWorker`（WorkManager 一次性任务：「仅 Wi-Fi」→ `NetworkType.UNMETERED` 否则 `CONNECTED`，指数退避重试 ≤3 次，执行时复查频率与开关），失败静默写设置页。新增 9 测（门限/约束映射/重试上限/钩子触发与容错）变异验证通过，**332 测全绿**；Redmi 真机端到端验证：下拉触发 → `AutoSyncWorker SUCCESS` → 坚果云真实上传 → 「上次同步 2026-11-14 08:32 · 成功」，频率复位「关闭」后下拉不再触发；蜂窝网拦截路径由单测覆盖（真机在 Wi-Fi 下）。

- [x] **CR-14 日期弹层按入口页裁剪（负责人提出）**：今日页 / 日历页弹层只留「今天 / 昨天 / 指定单日」，统计页只留「近 7 天 / 近 30 天 / 本周 / 本月 / 自定义区间」（新增 `DateSelection.thisWeek` = 周一至周日），排行 / 解锁详情 / 通知详情保持完整集合。文档先行（需求 CR-14 + §5.6）；323 测全绿，`thisWeek` 与模式映射变异验证变红；二级页调用方走默认参数零改动。解锁次数「—→0」经确认**暂不改**（维持 P1-1「不渲染假 0」红线，负责人裁决「算了，这个先不改」）。

- [x] **CR-13 图标层定稿（负责人裁定 B 方案）**：图标放大上限 1.5×（`iconScale` 纯函数，线性无折点），淡出维持 0.35–0.65 保留锚点；内容/底板/时长/几何一律不动。316 测全绿、变异验证变红、vivo 慢放真机复核通过。双机对比后撤销「必须联动内容提前」的旧结论。

- [x] **CR-12 转场几何修订**（负责人提供 Claude fix-nav 补丁）：裁剪框独立插值 + 页面 `coverScale` 等比缩放 + 屏幕坐标真实半径圆角（`CORNER_HOLD=0.6`），废止 `ScaledCornerShape` 反算。文档先行 `e2760c6`；编译通过、314 测全绿、变异验证变红、真机慢放/正常速度录屏与系统原生三方对照完成。详见 `docs/VI-UI视觉升级-交付说明.md` 第 13 节。

- [x] **转场录屏 / 抽帧工具脚本化**：`scripts/capture_anim.py` —— 一次调用完成唤醒设备、自动定位入口按钮、录「打开二级页 → 返回」、生成全流程 / 进入段 / 返回段联系表 + 压缩视频，`--slow` 可临时双向慢放并在 `finally` 中无条件还原代码与设备设置。零参数与 `--slow` 两种模式均已真机实跑，产物无黑格、往返完整无误，`git diff` 为空、`stay_on` 还原。调用命令见文末「工具速查」。

- [x] **CR-11 三层容器变换转场**（负责人提供 Claude 方案）：底板 / 入口图标 / 内容三层，底板仅在入口附近渐入渐出、图标放大到约一半淡出、内容交叠淡入；圆角反算、LinearOutSlowIn 进 320 / 出 380ms、起点缩放下限 0.01。文档先行已改需求 CR-11 / §5.5 / §7-22；录屏抽帧验证通过（含慢放与退场中点屏不飘）。

- [x] **Step 7**：顶栏三按钮（CR-10 终态，日期 / 三色圆点主题 / 设置，二级页不加）、导航胶囊质感（92% 白 + 描边 + 阴影 + 线性图标）、首屏遮挡复核（112dp 足够，不加留白）。提交 `334e53e`；顺带修复 `b2852d4` 主题弹层单色渐变崩溃。

## 当前优先（负责人 2026-11-13 裁定：先收口 UI 与同步）

- [x] ~~**转场进一步贴近系统原生**~~：负责人双机真机对比后裁定 **B 方案收口**（CR-13，仅图标上限 1.5× + 保留 0.35–0.65 淡出），不追求与系统完全同构；「裁剪框竖向中间态」属几何必然、非缺陷。对照素材保留在 `C:/tmp/screentrace-capture/`。

- [x] ~~**Step 7**：导航胶囊质感 + 首屏遮挡复核 + 顶栏三按钮。~~（已完成，见上方）
  - [x] ~~**先复核再改 padding**：~~ 复核结论：滚到底「查看全部应用」bottom=2181px < 导航胶囊 top≈2400px，`112dp + navigationBarsPadding()` 已足够；首屏重叠是固定覆盖层与长内容自然交叠，未加留白，已写进 `ScrollablePage` 注释。
  - [x] ~~恢复顶栏三枚圆按钮~~：CR-10 文档先行已完成；三色圆点自绘、两处入口共用全局 `StyleSheet`、二级页仅返回按钮，真机验证通过。
- [x] ~~**双机补装 CR-14 版**~~ **2026-11-14 完成**：vivo `10CEB40M5G001DK` 与 Redmi `GAQOHMNFQWFUPBQO` 均已补装并完成弹层冒烟（今日/日历仅「今天/昨天/指定单日」、统计仅「近7天/近30天/本周/本月/自定义区间」）。**余尾**：Redmi 当前装机版本早于读屏补齐提交（`130b2d4` 后），下次在线时 `adb install -r` 最新构建即可，不算待办阻塞。
- [x] ~~**今日页近 7 天迷你柱（验收 37 漏项）**：当前 `TodayScreen` 未实现；需求为强制项，规格已修正。复用统计趋势同源数据，无记录日不伪造数值，单独小提交补齐。~~ **2026-11-13 负责人裁定撤销该功能：不做迷你柱，验收 §7-37 作废（编号保留，有效验收 41 条）；「最长连续」圆环卡一行小字维持现状。**
- [x] ~~**8b-2 自动同步生产接线**：`SyncManager.uploadAuto/shouldAutoSync` 已有测试，但 `AppContainer.pullCoordinator` 未接 `onSuccess`，生产代码无调用方。接入 App 内手动/自动拉取与 WorkManager 成功链路，补「仅 Wi-Fi」约束与手动下拉联动实测。~~ **2026-11-14 完成，见「已完成」区（Redmi 真机端到端验证）；8b-2 余项只剩 Google Drive，归功能批次。**
- [x] ~~**Step 9**：深色模式 + 九套主题逐套巡检（深色此前未逐项验证，因 `darkTheme` 由 App 内控制、切系统夜景不重组）。~~ **2026-11-14 完成，见「已完成」区。**

## CR-15 方案已敲定待实施（负责人 2026-11-14 两轮裁定；A0 / A1 / B 已完成，余 C / D / E）

> 全文见需求文档头部 CR-15 登记；每批实施时先按该条修订对应章节再改代码。基线 **339 测全绿**（HEAD `77f024c`）。**11-14 调整**：批次重排（A0 闪退单独提前、A1 顺延）+ 口径补齐（日历 tab 四宫格隐藏日期、月补 0 规则、日均分母 `Summary.recordedDays`、不做常驻设备三角、多设备不加深）。

- [x] **批 A0 · 闪退修复 + 交互重构一次做完（2026-11-14 完成）**：
  - [x] ② 日期输入：真实文本 4-2-2 数字流 + `TextFieldValue` 光标置末尾；月首位 >1 自动补 0、0/1 等第二位，日同理；重写 `OffsetMapping`（修光标与 `9 -> 11` 越界）；`syncCalendar` 加 1900–2100 / 合法性守卫（修 `year(1234)`）；月 13–99、日 00/32+、年份越界即时提示；补两次真机闪退回归用例 + 变异验证；§7-25 措辞已同步。
  - [x] Redmi 真机复现原崩溃输入路径：**9 位 `202611130` → 硬截成 `2026-11-13`、年份 `1234` → 显示「年份需在 1900–2100 之间」且不调日历、13 月即时提示、`202635` 自动补成 `2026-03-05`、30 位乱输不崩**；全程 crash 缓冲区 0 条 FATAL、进程 pid 未变。
  - 结果：346 测全绿（新增 7 例），去掉钳制/年份守卫后 2 例变红（变异验证）；APK `35cdd282` 已装 Redmi。vivo 当前不在线，待下次补验。
- [x] **批 A1 · P0 其余两项（2026-11-14 完成）**：
  - [x] ⑥ 时段分布：新增 `UsageCalculator.dailyAveragePeriods`（分母 `Summary.recordedDays`），统计页与周期总结页同源归一；周期页标题标「日均」；Preview 假数据改日均量纲；§5.7 第 7 条补口径。新增 5 测，去掉除法后 3 例变红。
  - [x] ④ 今日页切换：先用 `dumpsys gfxinfo` 抓 Redmi 帧分布（GPU 50th=4ms 非瓶颈、50th=10ms/90th=18ms/Janky 10.78%）定位为「无反馈 + 偶发 UI 线程慢帧」；双卡数字接 `RollingNumberText` 逐位滚动。**负责人随后反馈「切日期闪一帧」：高密度抽帧（`switch_dense.png`）+ 原始单帧确认是 `Crossfade` 重建内容分支导致 `RingProgress` 的 900ms 入场动画从 0 重播（圆环塌成一小截弧）+ 交叉帧整页半透明；已按修订后的 CR-15 ④ 去掉整页 Crossfade，只留数字滚动与圆环平滑变值。** 复录 `a1_no_flash2/switch_dense.png`（8×6=48 帧 / 1.0s）确认：圆环全程完整深紫、无塌环、无半透明帧，数字仍逐位滚动；APK `b93956ce`。
  - [x] 真机验证：近 7 天（共 5 天有记录、日均 18h42m）四段为 4h33m / 3h13m / 2h47m / 6h24m，**全部落在 6/6/4/8 小时时钟跨度内**；`scripts/capture_anim.py --date-switch` 新模式的 `switch.png` 拍到半透明交叠过渡帧与数字逐位变化；crash 缓冲区 0 条 FATAL。APK `05b5b687` 已装 Redmi（vivo 另有测试，未动）。
- [x] **批 B · 日期状态拆分（①，2026-11-14 完成）**：
  - [x] 文档先行：§5.6 推翻「单一 `DateSelection`」改写为双状态表 + 日历全量历史；同步 §5.2 / §5.7 / §7-11 / §7-36 / §7-40 / §7-41 共 7 处口径。
  - [x] 实现：新增 `DateScope` + `dateScopeFor(page)`（二级页按 `returnTo` 归属）+ `historySelection()`；ViewModel 拆 `todaySelection`（默认今天）/ `statsSelection`（默认近 7 天），`applySelection` 按当前页归属写入、`loadAll` 三窗口独立取数（今日 / 统计 / 日历全量历史），排行锚点取归属状态结束日且不晚于今天；日历 tab 顶栏不传 `onDateClick`，`_calendarMonth` 固定 `YearMonth.now()`。
  - [x] 验证：新增 9 测（`DateScopeTest` 3 + `AppViewModelDateScopeTest` 6，真 Room 端到端）；三处变异（一律写今日状态 / 日历读统计窗口 / 二级页不解析 returnTo）共 4 例变红；**360 测全绿**。
  - [x] Redmi 真机：今日选「昨天」→ 统计仍「11月8日 – 11月14日」；统计切「近 30 天」→ 今日仍「11月13日」；切 tab 往返各自保留；二级页（解锁详情）弹层选「今天」写回今日状态、统计仍近 30 天；**日历 tab 顶栏无「选择日期」按钮**且显示「已记录 5 天」全量历史；crash 缓冲区 0 条 FATAL。APK `7afd958f`（vivo 另有测试，全程未动）。
- [x] **批 B 补丁 · CR-16 二级页弹层收窄（2026-11-14，负责人裁决评审 P1-1）**：
  - [x] 文档先行：需求文档新增 CR-16 登记（推翻 CR-14「三个二级页保持完整集合」）；§5.6 表格标题改 CR-14/CR-16、二级页归属段重写（选项集与写回目标共用 `returnTo` 判据）、今日页单日段删掉「区间态锚定今天」的兼容前提、快捷项条目改写；CR-14 条目原文加删除线并注明 CR-16 收口。
  - [x] 实现：`dateSheetModeFor(page)` 改按 `AppPage.mainOf(page)` 判（归属今日 → DAY / 归属统计 → RANGE / 传 null 才 FULL）；`AppNav` 三处二级页顶栏 `DateSheetMode.FULL` 改 `dateSheetModeFor(detail)`。`todayEffective` 的区间→今天回落保留为防御性兜底（正常路径不可达）。
  - [x] 验证：`DateSheetModeTest` 3 → 5 测（新增「二级页跟随 returnTo」「弹层模式与写回归属同源」）；两处变异（还原成按页判断 → 2 例红、统计页误回 DAY → 3 例红）；**362 测全绿**。
  - [x] Redmi 真机（APK 13:42 构建）：今日 → 解锁卡 → 解锁详情 → 日期，**弹层只剩「今天 / 昨天 / 指定单日」**（原「近 7 天」入口消失）；今日 → 应用构成 → 查看全部应用（排行，returnTo=Today）→ 日期，同为单日集合；统计页日期弹层仍为「近 7 天 / 近 30 天 / 本周 / 本月 / 自定义区间」（无今天/昨天）。截图 `C:/tmp/cr16/{unlock_detail_sheet,stats_sheet}.png`。
- [x] **批 C · 多设备数据层（③ 下半，2026-11-14 完成）**：
  - [x] **文档先行** `eb27e8e`：需求头部登记「CR-15 ③ 批 C 实施裁定」；§4 备份范围 v4→v5、§5.10 导出/手动/自动三处改写 + 新增「多设备」小节、§6 补 `SyncProvider` 子路径与 deviceId 存储红线、§7-13 补 v1–v4 兼容；文档地图同步。代码零改动。
  - [x] **数据层** `9d1a5ee`：`BackupFile` 增 `device`、`SCHEMA_VERSION=5`；`SettingsStore` 首启 UUID + 可改昵称；新增 `SyncPaths` 统一远端路径（顺带解 P3-4）；`WebDavProvider` 逐级 MKCOL + `listIn` 子目录 + 认出 collection；`SyncManager` 分目录上传/只清本设备、恢复回落旧固定文件、`listDevices()` 归「未知设备」、`inspectDevice()` 只读不写库。399 测全绿。
  - [x] **云同步页最小设备卡片** `6338f90`：本机短 ID + 挂起式昵称保存、按需拉设备列表、每行「查看摘要」显示来源/导出/范围/各表条数并明写「只读查看，不会写入本机数据」；不新增跨设备恢复入口。403 测全绿。
  - [x] **真机复核收口（本笔）**：Redmi `GAQOHMNFQWFUPBQO`（vivo 全程禁用）发现并修复坚果云 PROPFIND 带 `d:` 命名空间前缀导致解析器一条都读不出的真 bug（原 `parsePropfindNames` 只认裸标签，旧备份清理也一直空转）；补带前缀回归用例 + 变异验证变红。终版 APK `6962b183`：**本机 1 份 / 未知设备（旧备份）2 份**、旧备份摘要显示 `v4 · 3 天`、本机摘要显示 `v5 · 5 天 · 来源 MyRedmi`、昵称改名落盘并刷新生效、crash 缓冲区 0 条 FATAL。截图 `C:/tmp/cr_c/final.png`。
  - [x] **验证**：新增 22 测（SyncPaths / WebDavPropfind / DeviceSync / AppViewModelDevice）；变异验证共 **14 处全部变红**（数据层 9：含 M3 首版无效变异换成「deviceId 不落盘」后 4 例红；UI 层 4；命名空间 1）；`--rerun-tasks` **404 测全绿 / 0 skip**。
- [x] **批 D · 四宫格顶栏（③ 上半，2026-11-14 完成）**：
  - [x] **文档先行** `187aa47`：需求头部登记三项裁定；§5.2 改写 `AppHeader` + 新增「下拉面板」「设备列表面板」两条、§5.5 补容器变换起点重登记、§5.6 口径对齐、§6 补工程红线、§7-36 改写（放弃 CR-10 三按钮终态）。代码零改动、单独提交。
  - [x] **顶栏四宫格下拉面板** `5dc3291`：`AppHeader` 加带默认值菜单参数（二级页 / `@Preview` 零改动）；`quickMenuActionsFor` 按 `dateScopeFor` 推导四枚 / 三枚、`quickMenuTransitionFor` 纯函数保证四枚都有真实动作；`QuickMenuOverlay` 最上层浮层（全屏点击层收起 + 按 header 底边窗口坐标右对齐），四枚按钮接 `transformSource` 并下发 `LocalContainerTransformState`；`StyleSheet` / `DateSheet` 零改动复用；切 tab / 开二级页 / 系统返回先收起；不碰 CR-17 Pager 跟手链路。
  - [x] **下拉内轻量设备列表面板** `5dc3291`：`DeviceQuickMenuPanel` 是面板内第二层（带返回回四宫格），只在点开时列举设备、点选才下载摘要并内联展开；新增 `deviceBackupSummary` 统一只读文案，云同步页卡片共用一份。不写库、不合并、不新增恢复入口。
  - [x] **真机复核收口（本笔）**：Redmi `GAQOHMNFQWFUPBQO`（vivo 全程禁用）APK `61aaaaef`：顶栏只剩一枚「打开快捷菜单」、下拉四枚一行横排右对齐、日历 tab 三枚且无「选择日期」、主题与日期弹层复用正常、设备面板列出「MyRedmi · 本机 1 份 / 未知设备（旧备份）2 份」且摘要显示 `v4 · 3 天` 与只读声明、返回键收起面板、快速连点 5 次 tab 标题与高亮一致、crash 0 FATAL。`capture_anim.py` 新增 `--quick-menu` 模式（先开下拉再点面板设置），`--slow` 抽帧确认容器变换**从面板里被点的那枚圆按钮 (498,465) 放大**、返回缩回同处，齿轮不拉长、无闪白叠影；产物 `C:/tmp/screentrace-capture/cr_d_quick_menu/{enter,exit}.png`。截图 `C:/tmp/cr_d/{menu2,devices,inspect,cal3_menu,rapid2}.png`。
  - [x] **验证**：新增 10 测（QuickMenuActions 6 / DeviceSummary 4）；变异验证 **9 处全部变红**（D8 首轮脚本锚点写错，修正后单跑变红）；`--rerun-tasks` **428 测全绿 / 0 skip**。
- [x] **批 E · 自定义壁纸（⑤，2026-11-14 完成）**：
  - [x] **文档先行** `d291c44`：需求头部登记「CR-15 ⑤ 批 E 实施裁定」；§5.4 新增「自定义壁纸」小节（全 App 单张 / 选图与存储 / 遮罩与参数 / 状态栏 / 失败处理）、§6 补工程红线。代码零改动。
  - [x] **数据层 + UI** `62f4682`：`WallpaperTuning` 纯函数 + `WallpaperStore`（PickVisualMedia → EXIF 旋转 → 长边 ≤1920 → `no_backup` 私有 JPEG → 平均亮度）+ `SettingsStore` 持久化；`ScreenTimeTheme` 以 `LocalWallpaper` 下发单份 `ImageBitmap`，`PageScaffold` 按「壁纸 → 暗化(scrim) → 主题底色(background)」绘制，无壁纸回三段渐变；`AppNav` 状态栏按遮罩后亮度重判；设置页「外观」组加选图 / 暗化 0–60% / 模糊 0–16dp / 恢复默认卡片。418 测全绿。
  - [x] **真机复核收口（本笔）**：Redmi `GAQOHMNFQWFUPBQO`（vivo 全程禁用）用亮/暗两张测试图走 Photo Picker，主页面与二级页均显示壁纸、暗化 58% / 模糊 5dp 生效、切深色主题遮罩与状态栏自动跟随、恢复默认后参数复位且 `run-as` 确认 `no_backup/wallpaper/` 已空、crash 缓冲区 0 条 FATAL。**真机发现状态栏阈值 128 在暗化 58% 时刚好卡边界、深色图标压暗背景对比不足**，改为 140 并补回归（变异退回 128 变红）。APK `6e1321ca`，截图 `C:/tmp/cr_e/{final_home,dark_theme,restored}.png`。
  - [x] **验证**：新增 14 测（WallpaperTuning 7 / WallpaperStore 3 / AppViewModelWallpaper 4）；变异验证 **11 处全部变红**（E10 首轮暴露取消分支未被覆盖，把 `uri == null` 判断提前后重跑变红；状态栏阈值修正另做一次）；`--rerun-tasks` **418 测全绿 / 0 skip**。

- [x] **CR-19 · 顶栏纵排 + 壁纸可见性/裁切（2026-11-16 完成）**：
  - [x] **文档先行**：需求头部登记三项裁定；§5.2 下拉面板纵向一列 + 展开过渡、§5.4 新增卡片半透明与裁切编辑两条、§7-36 同步。代码零改动、单独提交。
  - [x] **② 下拉纵排 + 过渡**：`QuickMenuPanel` 改纵向一列；`QuickMenuOverlay` 改 `expandVertically + fadeIn 约 200ms`（减少动画直落）；转场起点、日历三枚、设备面板不变。
  - [x] **③ 壁纸半透明 + 遮罩加深**：`ScreenTimeTheme` 在壁纸启用时把 `surface / surfaceVariant / secondaryContainer / primaryContainer` 降 alpha；`WallpaperTuning.themeScrimAlpha` 加半透明补偿。
  - [x] **④ 裁切编辑**：`WallpaperConfig` 加 `cropScale / cropOffsetX / cropOffsetY`（归一化）；`WallpaperCropMath` 纯函数（minCover 抬升、平移钳制）；`PageScaffold` 改 Fit + graphicsLayer；新增全屏编辑浮层（拖动/捏合/确认/还原）；壁纸卡片加缩略预览与「调整」入口。不进备份 JSON。
  - [x] **验证**：新增 9 测（WallpaperCropTest 8 / 裁切落盘 1）；**9 处变异全红**（遮罩补偿/卡片 alpha 对调/缩放偏移夹取/zoom 下限/平移钳制/焦点公式/minZoom/落盘）；`--rerun-tasks` **445 测全绿 / 0 skip**；Redmi 真机（vivo 全程禁用）：纵排一列右对齐、`--quick-menu --slow` 抽帧确认面板自上往下展开 + 容器变换从面板按钮放大、壁纸卡片预览 + 调整裁切、编辑浮层拖动生效、保存后重启偏移持久、半透明卡片透出壁纸且文字清晰、crash 0 FATAL。捏合缩放由 8 例几何单测覆盖（adb 无法模拟双指）。截图 `C:/tmp/cr19/`，抽帧 `C:/tmp/screentrace-capture/cr19_menu_anim/`。
**依赖与纪律**：**A0 / A1 / B / C / D / E 全部完成，CR-15 六条反馈收口**（不放假按钮）；每批一个提交，文档先行 + 变异验证 + 真机复核；不新增 §7 验收条目；README 仍冻结。

- [x] **CR-20 · 顶栏拆回四枚独立圆按钮 + 壁纸卡片配色跟随壁纸（2026-11-16 完成）**：
  - [x] **文档先行** `71dcd4d`：需求头部登记 CR-20；§5.2 改写顶栏与设备面板两条、§5.4 新增「卡片配色跟随壁纸」「配色范围两套方案」「对比度红线」「未启用壁纸不变」「浮层不透明」五条、§5.5 批 D 起点重登记作废、§5.8 外观组补一项、§6 补壁纸配色工程要求、§7-36 改写。代码零改动、单独提交。
  - [x] **① 顶栏四枚独立按钮**（推翻批 D ① 与 CR-19 ①）：主页面右侧横排「切换设备 / 日期 / 主题 / 设置」四枚独立圆按钮（改版前的直出位置），**日历 tab 三枚**（无「选择日期」）。`HeaderAction` 枚举自带 `target`（编译期保证「不放假按钮」）；`QuickMenu.kt`/`QuickMenuActions.kt`/`DeviceQuickMenuPanel.kt` 改名重构为 `HeaderAction.kt`/`HeaderActions.kt`/`DevicePanel.kt`；设备面板升为**顶栏正下方的浮层**（带关闭按钮，点外部 / 系统返回键收起，**不下发 `LocalContainerTransformState`**）；顶栏按钮**自身**即容器变换起点（批 D「面板按钮起点重登记」作废，`registerTransform=false` 只给开浮层的「切换设备」）。
  - [x] **② 壁纸卡片配色**：选图时用同一份 32×32 采样算**壁纸主色**（`WallpaperTuning.dominantSeedOf`：12 色相桶、面积 ×(1+饱和度) 加权、近灰不参与分桶）；卡片底色由主色经既有 `paletteFromSeed` 派生；不透明度 **浅 60% / 深 55%**（CR-19 的 92%/88% 几乎不透）；半透明卡片的文字对比度按「壁纸 → 暗化 → 遮罩 → 卡片叠加」的**实际合成底色**校验，不足时把卡片底色推向安全端（`safeCardColor`）；「仅卡片 / 整套主题」两套方案在设置页可切换对比；**未启用壁纸时色板一行不改**（`WallpaperPalette.paletteFor(null)` 原样返回主题色板）。
  - [x] **③ 浮层不透明**（真机复核追加）：新语义 token `panelSurface`；底部弹层（日期 / 风格 / 取色器 / 滚轮）、设备面板、提示条改用它——真机实测浮层跟着卡片变透明会让底下数字透上来与文字叠字。
  - [x] **验证**：新增 18 测（HeaderActions 5 / WallpaperSeed 8 / WallpaperPalette 11，含改写旧 6 测）；**11 处变异全部变红**（M1 日历多给日期 / M2 两动作撞同入口 / M3 透明度退回 92% / M4 整套主题不跟随 / M5 去掉对比度兜底 / M6 主色只看像素数 / M10 不排除近灰 / M7 次要正文不兜底 / M8 没壁纸也走壁纸配色 / M9 按钮顺序乱 / M11 浮层跟着变透明）；`--rerun-tasks` **464 测全绿 / 0 skip**；Redmi 真机（vivo 全程禁用，APK `795896d9`，全流程复核用同源构建 `7195dac5`——差异仅 debug 签名时间戳）：顶栏四枚直出（读屏 content-desc 与可点性已 dump 复核）、日历 tab 三枚无日期键、设备面板浮层列出「MyRedmi · 本机 1 份 / 未知设备（旧备份）2 份」且底色不透明无叠字、点设置走容器变换、壁纸卡片明显透出且带壁纸色调、「仅卡片 / 整套主题」两套观感切换对比、恢复默认后回到不透明卡片 + 主题色原样。截图 `C:/tmp/cr20/`。

- [x] **CR-21 · 云备份三项改进：设备列表显示机型 + 跨设备累加合并 + 切换设备真切换（2026-11-16 完成）**：
  - [x] **文档先行** `79e3e05`：需求头部登记 CR-21（负责人三条反馈 + 问卷四项裁定 + 追加「切换设备要真切换」）；§4 新增「跨设备累加合并」「防重复记账」「每设备独立库」三条、§5.2 设备列表面板改写、§5.10 多设备小节改写（真切换 / 机型元数据 / 跨设备合并）+ 边界条、§6 补多设备存储与切换工程要求、§7 追加 **43~45** 三条验收（42 → 45 条）。代码零改动、单独提交。
  - [x] **① 机型显示**：每次上传在设备目录多写一份小型 `device.json`（`{ id, nickname, model }`，幂等覆盖）；设备列表读它得到机型，**不再以短 ID 当设备名**；老设备目录没有该文件时列表回落「设备 <短ID>」、展开摘要再从备份顶层 `device.model` 补出真实机型；**取机型不批量下载整份备份**（列目录时只对确实存在 `device.json` 的设备发一次小请求）。
  - [x] **② 跨设备合并（累加）**：新增 `CrossDeviceMerge` 纯函数口径——与 §4 原有「同日取较大」**并列的第二套口径**（§6 红线：两套口径不得互相复用）；同日 `screen_min / screen_unlock_min / unlocks / launches` **相加**、`app_usage` / `hour_usage` 相加、事件按主键去重（两机时间戳不同故计数自然累加）、`screen_session` 走**区间并集**（不累加，重算取大不把累加值改小）、`app_limit` **完全不写**（限额是本机用户意图）；**防重复记账**（来源设备 ID → 已合并日期集合，落 DataStore、不进备份 JSON）→ 同一天重复合并不翻倍；合并成功后**自动上传一次**（失败只提示、**不回滚**本地合并）；写入目标**恒为本机采集库**，远端设备自己的副本库与云端备份**不被改动**（旧数据单独保留、新数据里同时含有旧数据）。
  - [x] **③ 切换设备 = 真切换**：每台设备一个**独立 Room 库文件**（本机采集库沿用 `screentrace.db` 承继历史数据；远端副本库 `screentrace-dev-<设备ID>.db`），「当前库」指针落 DataStore；切换 = 下载该设备云端备份 → 经 `BackupRepository.restore` **统一恢复入口**写进它自己的副本库（OVERWRITE ＝ 精确副本）→ 切指针 → 四页重载；`RoomUsageRepository` 改为**按当前库逐次解析 DAO**（持有实例的写法切换后仍读旧库）；本机采集恒写本机库、**不会污染远端副本库**；上传内容源恒为本机采集库；**不改本机身份与设置**。
  - [x] **UI**：顶栏设备面板与云同步页设备卡片**两处入口**，共用同一套动作按钮与**同一个合并确认弹层**（新增 `ui/DeviceActions.kt`）；面板行显示机型 + 「· 本机」「· 当前」标记；旧红线条文「只读查看，不会写入本机数据」按 CR-21 改写为「摘要只读；切换与合并需显式点击并确认」（摘要本身仍只读，两枚动作是显式入口，合并需二次确认）。
  - [x] **验证**：新增 23 测（`CrossDeviceMergeTest` 10 + `CR21DeviceTest` 13，真 SQLite + 假 WebDAV 端到端）；**12 处变异全部变红**（累加改取大 / 应用不加 / 时段不加 / 防重复失效 / 机型回落短 ID / 不写元数据 / 不记账 / 不自动上传 / 切换不落副本库 / 合并写错库 / 不切指针 / 不切回本机）；`--rerun-tasks` **487 测全绿 / 0 skip**；Redmi 真机复核证据见 `CODE_REVIEW.md` 文末补记。
  - [x] **CR-21 补记（2026-11-16 12:40，Redmi 真机复核发现的两处缺陷 + 一条口径边界）**：① **P0 · 记账只记住最后一个日期**——`SettingsStore.decodeLedger` 把「来源设备|日期」多条条目按同键 `.toMap()` **覆盖**（而不是取并集），同一来源只留最后一个已合并日期 → 真机第二次合并把 11-10 / 11-11 又累加了一遍（当天数据翻倍，11-10 由 1530 变 2295 分钟）；补正为**同键取并集**（全部已合并日期完整保留），补 `MergeLedgerTest` 5 例 + `CR21DeviceTest` 多日期端到端 1 例，变异（改回覆盖语义）**5 例全红**。② **结果提示不可见**——合并 / 切换的结果文案渲染在设备列表**末尾**，长列表时被顶出屏幕（真机 dump 与截图都找不到「已并入 N 天的数据」）；改为**置于设备列表之前**（面板顶部），点完无需滚动即可看到。③ **累加口径的边界**——来源若是**同一台机器自己导出的旧文件**（本机这台 Redmi 的升级前根目录旧备份正是如此），重叠日期按累加口径**翻倍**（11-10 的 765 → 1530 分钟）；确认弹层补写警示、需求文档 §4 补边界条、§7 加第 46 条验收。④ **真机数据修复**：按「只并入一次」口径精确反向相减（`day_summary / hour_usage / app_usage`），逐项核对后写回并重新上传，云端与本地对齐（11-10 = 1530 / 11-11 = 2804 / 应用 45 / 时段 168 / 事件 893 / 会话 545 / `app_limit` 0）。⑤ **收口**：`79e3e05` 文档先行 → `6d20de4` 补记文档先行（§7 增至 **46 条**）→ `7ba4c91` 实现 → 真机收口笔；**493 测全绿 / 0 skip**、**13 处变异全部变红**、Redmi APK `902c746d`（收尾排版修正后 `f189c600`），证据见 `CODE_REVIEW.md` 文末补记。
- [x] **CR-22 · 点 tab 卡顿修复 + 同一台机器旧备份指纹识别（2026-11-16 12:34 负责人反馈 + 三项裁决）**：
  - [x] **文档先行**：需求头部登记 CR-22（① 点 tab 跨页不得经过中间页、② 同一台机器旧备份指纹识别、③ 壁纸两套方案都保留）；§4 新增「同一台机器的识别」、§5.2 Pager 同步条补 CR-22 ①、§5.10 合并流程补指纹步骤、§7 追加 **47~48** 两条验收（46 → 48 条）。代码零改动、单独提交（`88aeb89`；补第三条信号的文档补记 `8502b13`）。
  - [x] **① 点 tab 跨页直达**：新增 `ui/TabScrollGuard`（纯状态 + 纯函数 `ignoresWrite` / `ownsTarget`）；程序化滚动（点 tab 触发的 `animateScrollToPage`，以及二级页返回的回滚滚动）期间**只忽略 Pager 中间页的回写**、目标页照常回写（动画七成时切标题最自然）；滚动结束或被手势打断后按 **Pager 实际停留页**对齐（写的是 `pagerState.currentPage`，**Pager 仍是唯一真相源，点 tab 不直接写路由**的红线不变）；新点击取代旧点击时旧协程不抢对齐；滑动切换的跟手回写**不受影响**（真机 logcat：点 tab `write index=2 programmatic=true`、滑动 `write index=1 programmatic=false`，全程无「统计」回写）。
  - [x] **② 同一台机器指纹识别**：新增 `data/backup/SameDeviceFingerprint`，三条信号——① 备份顶层 `device.id` 与本机 `deviceId` 相同；② 重叠日期的 `day_summary` 五字段（`screen_min / screen_unlock_min / unlocks / launches / longest_session_min`）**逐项完全一致**；③ 该日备份的**事件主键 ⊆ 本机该日事件主键**（覆盖「同机更早快照」，真机 11-12 实测 17 ⊂ 72）；命中的重叠日期**以本机为准、不累加**，只并入本机没有的日期；提示写明判定结果与跳过天数；`BackupRepository` 增两个只读方法、`DeviceOperations.mergeIntoLocal` 接入判定；合并弹层文案同步改写（自动识别 + 不累加警示）。真机：清掉 `merge_ledger` 里 `unknown` 的三条记账后合并「未知设备（旧备份）」→ 提示「判定为同一台机器的旧备份（有 3 天的事件全部包含于本机…），重叠的 3 天以本机为准，没有新日期可并入」，**本机数据一格未动**（11-10 = 1530 / 11-11 = 2804 / 11-12 = 2005）。
  - [x] **③ 壁纸两套方案**：负责人裁定「两个都保留」——设置页「仅卡片 / 整套主题」都留着、可切换，默认「仅卡片」；本轮**不改实现**，只登记裁决。
  - [x] **收口（2026-11-16 13:30）**：`88aeb89` 文档先行 → `8502b13` 文档补记 → `370411d` 实现 → 真机收口笔；新增 20 测、**513 测全绿 / 0 skip**、**5 处变异全部变红**（守卫不忽略中间页 / `ownsTarget` 反 / 旧协程抢对齐 / 指纹不判同一台 / 合并不剔重）、Redmi APK `374f277d`，证据见 `CODE_REVIEW.md` 文末补记。
- [ ] **CR-23 · 点 tab 转场自然化：页面景深 + 标题跟随运动 + 软着陆曲线（2026-11-16 13:50 负责人反馈「点击 tab 切换好僵硬，滑动就很丝滑，fushi 点击 tab 很流畅、过渡动画很自然」+ 三档方案裁定「再加上页面景深」）**：
  - [x] **文档先行**：头部登记 CR-23（含**红线修订**：「过渡只做叶子节点」只针对数据过渡，tab 切换的页面景深用 `graphicsLayer` 绘制期变换、不重建子树不重播动画，负责人裁定放开）；§5.2 新增「tab 转场运动」条（景深 / 标题跟随 / 运动曲线三者同源一条连续进度）；§5.7 补红线例外指向；§7 追加 **49~50** 两条验收（48 → 50 条）。代码零改动、单独提交。
  - [x] **① 页面景深**：每页内容按 `|offset|` 轻微缩放与淡化（`alpha = 1 − 0.55·|offset|`、`scale = 1 − 0.05·|offset|`），`Modifier.graphicsLayer` 绘制期套用；点 tab 与横滑**一视同仁**，消除纯平移的机械感。真机联系表已见转场中段每页变淡变小。
  - [x] **② 标题跟随运动**：标题块按 `目标页 − 连续进度` 轻微位移（每步 18dp，落定归 0）+ `Crossfade` 交叉淡化；位移只在程序化滚动期间生效（横滑维持原样，避免过半即翻时跳变）。真机 logcat 逐帧 `115.6px → 18px → 0`、PTS 抽帧见标题从 x≈165 滑到 x≈75 落位。**实现修正**：位移必须在 `AppHeader` 组合期读 `State<Float>`（`graphicsLayer` 的块不会自己重跑）；**诊断更正**：「标题滞后于内容」是联系表的 PTS 假象，logcat 证实标题在 20% 时间点就翻、本就不滞后。
  - [x] **③ 软着陆曲线**：`animateScrollToPage` 从默认 `spring(DampingRatioNoBouncy)` 换成 `spring(dampingRatio = 0.85, stiffness = StiffnessMediumLow)`（轻微回弹，落定有生命感）；横滑仍由手指驱动不受影响；减少动画仍走 `scrollToPage`。真机落定 774~779ms、曲线收在 `TabMotion.scrollSpec` 单一出处。
  - [x] **纯函数 + 变异验证**：`TabMotion.alphaFor / scaleFor / titleShiftDp / scrollSpec` 均为纯函数，`TabMotionTest` 14 例；**4 处变异全部变红**（alpha 恒 1 / scale 恒 1 / shift 恒 0 / dampingRatio 退回 1.0）；`--rerun-tasks` **527 测全绿 / 0 skip**。
  - [x] **真机复核**：`scripts/capture_anim.py --label 日历 --no-back` 复拍点 tab 转场逐帧对比（景深生效、标题位移可见）；`--swipe` 复拍横滑不受影响；`dumpsys gfxinfo` 50th=8ms / 真 jank 0.5% 无新增掉帧。证据见 `CODE_REVIEW.md` 文末补记。
  - [x] **vivo 复核（2026-11-16 17:00）**：已接入并用 dumpsys gfxinfo framestats 逐帧拆段排查。**结论：CR-23 无罪**（景深 GPU 5.13ms vs 关掉 5.31ms、标题位移 0.07ms，都在噪声内）；vivo 每帧成本「录制绘制 5.67ms + GPU 5.1ms」，唯一确定可归本 App 的是**壁纸模糊约 1ms GPU/帧**；200~350ms 尖峰来自后台进程 GC（gallery.aiservice/QQ 等），非本 App。测量方法学记账：**vivo 不触摸自动降到 60Hz、\input tap\ 不触发高刷**，脚本测量必须按帧自己的 vsync 分档或用真实触摸。CR-23 三项在 vivo 上视觉正常。
- [x] **CR-24 · 壁纸模糊一次性位图缓存（2026-11-16 17:05 负责人同意）**：
  - [x] **文档先行**：头部登记 CR-24（含排查记账与「API<31 静默不模糊」的诚实性缺口）；§5.4 新增「模糊走一次性位图缓存」条；§7 追加 51 条验收（50 → 51 条）。代码零改动、单独提交。
  - [x] **① 纯函数模糊**：`WallpaperTuning.blurArgb(width, height, pixels, radius)`——只吃整数像素与半径，三趟可分离盒式模糊 + 前缀和 O(像素数)；`WallpaperBlurTest` 10 例覆盖「半径 0 原样」「越大越平滑」「三趟过渡带比一趟宽」「均值守恒」「边缘不发黑边」「四通道不串色」「纯函数且不改写输入」。
  - [x] **② 一次性缓存**：`WallpaperBlur.blurredImage` 按 density 降采样（1px ≈ 1dp，长边钳 800）后模糊；`WallpaperState` 增 `blurred`，`AppNav` 组装时一次性算好，`PageScaffold` 只贴它、**删掉每帧 `Modifier.blur`**；**API < 31 一并生效**（`blurSupported()` 恒真、`effectiveBlurDp` 只做越界夹取）。
  - [x] **③ 变异验证**：5 处变异**全部变红**（半径 0 返回空数组 / 不复制输入 / 只模糊一趟 / 边界不 clamp / 低版本又钳成 0）；首轮 2 处未变红已复查修正（M1 是非行为变异、M3 是真弱项补了「过渡带」用例）。`--rerun-tasks` **537 测全绿 / 0 skip**。
  - [x] **④ 真机 GPU 复测（2026-11-17 08:45 完成，结论与预期相反）**：vivo 同机同配置 A/B（`wallpaper_blur = 12`）——缓存版 GPU 5.08ms vs 每帧 `Modifier.blur` 5.11ms、总耗时 13.38 vs 13.24ms，**差异在噪声内，CR-24 的性能收益应按「无收益」记账**。同时更正两处归因：「壁纸模糊 +0.97ms GPU」实为关掉**整层壁纸**（位图 + 两层遮罩）的差值、与模糊无关；vivo 实测 `wallpaper_blur = 1`、模糊本就几乎没在跑。CR-24 的真实价值是**三条非性能项**（API < 31 也生效 / 模糊成纯函数可单测 / 参数变化时算一次）。详见 `CODE_REVIEW.md` 文末「补记·更正」。
- [x] **CR-25 · 点 tab 曲线改「明确落点」的强调缓出（2026-11-17 08:52 负责人「可以做出来看看效果」）**：
  - [x] **文档先行**：头部登记 CR-25（治 `spring` 渐近尾巴：78% 行程 147ms、剩下 22% 爬 630ms）；§5.2「运动曲线」条改写；§7 追加 52 条验收（51 → 52 条）。代码零改动（`64571b9`）。
  - [x] **实现**：`TabMotion.EMPHASIZED_DECELERATE = CubicBezierEasing(0.05, 0.7, 0.1, 1)`（M3 emphasized decelerate，稳定 API）+ `scrollDurationMs(pageDelta)`（1 页 350 / 2 页 440 / 3+ 页 480ms，上限 §5.5）+ `scrollSpecFor(pageDelta)` 取代旧 `scrollSpec`（`358297b`）。
  - [x] **验证**：`TabMotionTest` 改写曲线用例；**5 处变异全部变红**；`--rerun-tasks` **540 测全绿 / 0 skip**；顺手修掉一条空洞断言（拿字段跟自己比 → 直接打缓动函数输出）。
  - [x] **vivo 真机验证（2026-11-17 09:55）**：vivo 不放 App 日志（`Log.d`/`Log.w` 都被过滤），改用**帧时间戳对照口径**——点标题（无动画）27 帧/464ms vs 点 tab 50~52 帧/873ms，**差值 ≈24 帧 ≈409ms**，与新曲线 350/440ms 吻合（旧 spring 实测 779ms，总跨度应约 1240ms）。详见 `CODE_REVIEW.md` 文末补记。
- [x] **CR-26 · 纠正 CR-25 曲线起步过冲（2026-11-17 10:56 负责人按 GPT/Claude 复核裁定「做 1~3」）**：
  - [x] **复算核实**：Claude 的曲线表**逐格准确**（二分解三次贝塞尔）。CR-25 的 `(0.05,0.7,0.1,1)` **起步过冲**——60Hz 首帧走完 38% = 跨两页一帧跳 **926px**，而旧 spring 首帧只有 5.9%（CR-25 治了尾巴、却把起步搞坏了）。
  - [x] **① 曲线换 `FastOutSlowInEasing`**（S 形：慢起→中段快→慢收），首帧跳变 **926px → 8px**（60Hz 两页，目标 <100px）。
  - [x] **② 收紧断言**：「5% ≤ 5%」（专咬起步过冲）+「33% ≤ 50%」+「50% ∈ [40%,85%]」+「90% ≥ 95%」；**自我纠错**：初稿「33% ≤ 25%」是错的（S 形曲线 33% 处实为 44.8%），已按实算值更正（`f0133c2`）。
  - [x] **③ 剩余距离改连续进度**：`TabMotion.remainingPages(target, currentPage, offsetFraction)` 纯函数（`currentPage` 是离散索引，动画中再点 tab 会算错）。
  - [x] **验证**：**5 处变异全部变红**（M4/M5 首轮绿，补了落在变异盲区的边界用例才咬住——变异不红先怀疑用例设计）；`--rerun-tasks` **543 测全绿 / 0 skip**；真机（vivo）逐帧渐进无瞬移、帧跨度 ~414ms 与 440ms 吻合。
- [x] **CR-27 · 记账入 Room 同事务 + 节流文案勘误 + 文档进度源纪律（2026-11-17 11:53 负责人按 GPT 项目审查裁定）**：
  - [x] **文档先行**（`693df68`）：CR-27 登记 + §5.10 记账同事务口径 + §7-53~54（52 → 54 条）+ **文档进度源纪律**（TODO.md 唯一真实进度源 / 需求文档只管 §7 条数 / 文档地图每次收口同步 / README 冻结、数字是历史快照）。
  - [x] **① 记账入 Room 同事务**：新增 `device_merge_ledger`（PK = deviceId+date）+ `MIGRATION_5_6` + DB v5→6；账本写入挪进 `mergeAccumulate` 的同一 `db.withTransaction`；读取取 **Room ⊕ 旧 DataStore 并集**（不丢老账）；去掉事务外的 `recordMergedDates`。
  - [x] **② 节流文案勘误**：实际 10 分钟、文案写「30 分钟」；改为按实际间隔出文案。
  - [x] **验证**：新增 7 例；**5 处变异全部变红**（首轮 3 处绿，复查发现 M4/M5 是**非行为变异**——这是第三轮同类问题，教训记入评审档案）；`--rerun-tasks` **550 测全绿 / 0 skip**。

- [ ] **CR-28 · 跨设备合并的来源设备维度（2026-11-17 15:15 设计审查 R2 二审通过，三小点按默认）**：
  - [x] **设计审查 R1**（`8fc9301`）→ 负责人首审「整体方向认可、逐指标口径通过」+ 六条修订 → **R2**（`34032d4`）逐条并入：冻结日重做（legacy 只读 + 新数据独立写入，R1「跳过所有写入」废除）/ schema 6 身份＝导出设备 + 三层兼容判定 / 事件主键加确定性 `seq`（同毫秒丢行修复）/ 重建工具快照-记录-核对-回滚（R1「残差归本机」废除）/ 跨午夜按自然日裁剪 + UNION 范围 / 测试矩阵 12 → 14 条。
  - [x] **三小点按默认**：`rebuild_log` 独立建表 / `seq` 用解析器序号 / UNION 文案「占用时钟时长」。
  - [x] **批次 A · 表结构 + `MIGRATION_6_7` + `seq` + 首启归档（2026-11-17 实现完成）**：
    - [x] 文档先行 `27234ee`：需求头部登记 CR-28 + §4 来源维度 / legacy 保护 / seq / 跨午夜裁剪 + CR-21「不加 device_id 列」局部推翻注记 + §7-55~59（54 → 59 条）。
    - [x] 实现：六表加 `sourceDeviceId`（主键并入来源），事件表主键加确定性 `seq`（`EventSeq`，同毫秒多条不丢 + 重放幂等）；`MIGRATION_6_7` 建表搬行换名（旧行一行不丢、`sourceDeviceId=''` 未归档、seq=0）；`SourceArchive` 首启归档（合并账本内 → `'__legacy__'`、纯本机日 → 本机 ID；幂等、已归档行永不改写、跨日会话保守归 legacy）；写路径同来源取大 + 窗口删改按来源隔离（IngestPipeline / MergePolicy.mergeSessions / BackupRepository.mergeSessions，legacy 只读）。
    - [x] 验证：新增 8 例（MigrationTest T7 / SourceArchiveTest T8×3 / SameMillisecondEventTest T13×4）；**4 处变异全部变红**（归档判定失效 2 红 / archiveRest 无守卫 3 红 / EventSeq 恒 0 4 红 / 迁移丢 notification 行 1 红），字节级还原后复跑全绿；`--rerun-tasks` **558 测全绿 / 0 skip**（550 + 8）。CR21DeviceTest 两处红是真问题——恢复写行的默认来源与存量行不同键导致双行，已修为「默认 `UNSPECIFIED` 同锅、生产显式传真实 ID」。
    - [x] **真机收口（v6→v7 迁移复核，2026-11-17 17:13 复验通过）**：首验抓 P0——`MIGRATION_6_7` 建了 `sourceDeviceId` 索引而实体没声明 `@Index`，Room schema 校验崩（**MigrationTest 假绿**：Robolectric 索引比对不敏感）；修复笔 `d6162d0`（实体补 `@Index` + MigrationTest 显式 `sqlite_master` 索引断言 + M14 变异红）。**复验**：identity_hash 已更新 v7、App 无崩溃；六表全行归本机 UUID（账本空 → 无 legacy）、无未归档行；**unlock 890/890 全保真、day/app/hour/session 键 0 丢**（session 1 处扫线并集吞并已验证覆盖；值更新均为当日活跃统计增长）；**notif seq=[0..3] 真机生效**（同毫秒多条不丢）。证据：`CODE_REVIEW.md` 文末补记、`C:\tmp\cr28_v7\`（before/after 库 + 快照）、`check_v7.py`（已改拉 db+wal+shm）/`reconcile_v7.py`。**遗留**：notif 1 键丢 + 10 处 channelId 变化＝通知秒组「整边替换」既有语义（非迁移所致），待批次 E 或单列修复。
  - [x] **批次 B · 读侧聚合分流 + legacy 保护 + 跨设备合并按来源写行（2026-11-17 实现完成）**：
    - [x] **读侧口径**：`day_summary` 跨来源合计查询（SUM 时长/解锁/启动、MAX 最长连续）成为读路径唯一入口（`recordsBetween`/`streak`/详情「不灭屏日」判定）；`app_usage` 按 (date,pkg) 跨来源合计（名字取真名——「以本机为准」从写路径移到读路径）；`SessionUnion.occupiedMinutes`＝「占用时钟时长（UNION）」纯函数（跨午夜按自然日裁剪、跨来源并集、只计 SCREEN）。
    - [x] **跨设备合并改按来源写行**：`mergeAccumulate` 写 `sourceDeviceId = 来源设备 ID` 分量行、同来源取大（复用 `BackupMerge`）；`CrossDeviceMerge` 的三个累加函数废除、收敛为「备份内重复行去重」；同设备恢复（merge/overwrite）同样同来源过滤；`SameDeviceFingerprint` 本机来源行优先、回退 legacy（其他来源分量不参与「同一台机器」判定）。
    - [x] **验证**：新增 6 例 `CrossSourceAggregationTest`（T2 SUM=2h/UNION=1h/MAX=1h、T3 不重叠 90min、T9 legacy=4h+A=1h+B=2h→7h 且 legacy 逐字段不变、T10 合并不改 legacy、T11 合并后 +30min、T14 跨午夜两日各 1h）；`CrossDeviceMergeTest` 累加用例置换为去重用例；CR21DeviceTest 四处断言改 SUM 口径（含「自动上传」改查两行分量）。**4 处变异全部变红**（sumRange 逐行 4 红 / 累加进本机行 1 红 / UNION 不裁剪 1 红 / 来源写成本机锅 2 红），字节级还原后复跑全绿；`--rerun-tasks` **560 测全绿 / 0 skip**。
  - [ ] **批次 B · 查询层分流 + legacy 只读保护**：SUM/MAX/UNION/COUNT 逐指标口径、跨午夜裁剪、同来源取大写路径；测试 T2/T3/T9/T10/T11/T14。
  - [x] **批次 C · 备份 schema 6（2026-11-17 实现完成）**：
    - [x] 文档先行 `fcb43ae`：§4 备份范围 v5→v6、§5.10 导出结构（`sources[]`）+ 导入派生规则 + 三层兼容 + CSV 带来源列。
    - [x] 实现：`BackupFile.sources[]`（`BackupSource`，含 `'__legacy__'` 项）+ 事件 DTO 带 `seq`；导出「顶层＝导出设备分量、`sources[]`＝其余来源」（顶层缺 `device()` 也写出 `device.id`＝主体来源）；导入按派生规则打来源（顶层归 `device.id`、无 → `'__legacy__'`、**绝不归导入设备**）；`merge`/`mergeAccumulate`/`overwrite` 三入口收敛到 `mergeGroup`（同来源取大 + **legacy 只增不改** + 事件 `insertIgnore` 完整主键）；`BackupMerge.unlockEvents/notificationEvents` 删除（seq 主键后天然幂等，旧「先到先得」反而丢同毫秒第二条）；CSV 六表展平 `sources[]` 并带 `source_device_id` 列；`legacyCompatWarning()` 三层兼容警告 API。
    - [x] 验证：新增 7 例 `BackupSourceRoundTripTest`（T5 无设备标识归 legacy / T1 重复导入幂等 / T12 A→B→再导出→再导入身份与分量往返 / T4 账本丢失重试不重复累计 / legacy 只增不改 / 兼容警告 / CSV 展平）；`BackupMergeTest` 3 个事件用例随语义简化移除；`SyncPathsTest`/`DeviceSyncTest` 三处 schema 版本断言升 6（「高于 v5 拒绝」改按当前版本+1 判）。`--rerun-tasks` **564 测全绿 / 0 skip**。变异结果见提交说明。
  - [ ] **批次 D · 历史重建工具**：快照 / `rebuild_log` 记录 / SUM/MAX/UNION 核对 / 整日回滚；测试 T6。
  - [ ] **批次 E · 测试收口 + 变异 + 真机复核**（14 条矩阵全绿）。
  - 铁律：不清空数据库、不删旧备份、不伪造历史来源、优先保证历史数据不丢失；每批一个提交、文档先行、变异验证；Redmi 禁用窗口内真机用 vivo `10CEB40M5G001DK`。设计全文 `docs/设计审查-CR-28 来源设备维度.md`。
  - [x] **真机迁移验证（2026-11-17 13:50，vivo）**：v5→v6 迁移通过——冷启动 `MainActivity` 为前台、crash 缓冲 0 条；真机库核对**老数据一行未丢**（day_summary 10 / app_usage 247 / hour_usage 144 / notification_event 3735 / screen_session 1283），`device_merge_ledger` 建出（列 deviceId+date）、`identity_hash` 已写。与 `MigrationTest` 的完整 schema 校验一致。

- [ ] **CR-28 · 来源设备维度（GPT 的 P0，方案已出待过目）**：`MergePolicy` 取大 + `CrossDeviceMerge` 累加写同一张表 → **跨设备合并后本机新增被静默丢掉**（A 2h + B 3h = 5h，A 再用 30 分钟仍是 5h）。方案：四张聚合表加 `sourceDeviceId`、PK 改复合、按来源分流合并口径、展示时跨来源求和。**三个待裁决**：对外显示「总使用时长(SUM)」还是「占用时钟的时长(区间并集)」；历史已合并日子（无法回溯拆分）接受取大遗留还是清空重并；备份格式是否保留设备分量。详见 `C:/tmp/方案-跨设备合并的来源设备维度.md`。
- [ ] **`allowBackup=true` 无备份规则**（GPT 的 P1 隐私）：AndroidManifest 未设 `fullBackupContent`/`dataExtractionRules`，Room 的使用记录、DataStore 的设备 ID 与合并账本可能被 Auto Backup 传到 Google 账号；且 `EncryptedSharedPreferences` 按官方文档**不该**被 Auto Backup 备份。需定「严格本地优先」或「明确备份规则」。



## CR-17 三条真机改进（2026-11-14，已完成）

> 全文见需求文档头部 CR-17 登记。三项均按负责人选的推荐方案；真机**只用 Redmi**（vivo 在跑自动化，全程未动）。证据见 `CODE_REVIEW.md` 文末补记。

- [x] **① 提示分流**：`showNotice` 按白名单前缀分流到常驻 `NoticeBar` / 2.5s 自绘短时条；5 处系统 Toast 改走 `onNotice`；短时条放在 AppHeader 下方 108dp 最上层覆盖层。
- [x] **② tab 跟手**：点 tab 只滚 Pager、`snapshotFlow { currentPage }` 单向回写 + `selectMain` 去重；`FreshNavBar` 扩 `progress: State<Float>` 连续插值跟手。
- [x] **③ 统计页局部过渡**：`MetricValueText` 支持时长数值平滑（同一 Text）、`CapsuleBarChart` 柱高平滑、`PeriodDistribution` 色带权重与图例数值平滑、文字轻淡入；不包整页容器。

## CR-15 评审遗留（DeepSeek V4.1 Flash，2026-11-14 12:30；13:53 补登）

> 来源：`docs/CODE_REVIEW.md` 末节「CR-15（A0 / A1 / B）代码评审」与 13:45 补记。
> **13:53 复核更正**：补记末句称这些条目「仍挂在 `docs/TODO.md`」与事实不符——当时一条都没登记，本批补登。
> 负责人 13:53 裁定「先补评审遗留」：**P1-2 / P2-1~5 已于 14:25 修完**（证据见 `CODE_REVIEW.md` 文末补记）；P3 四条与两条待裁决仅登记、不阻塞。

- [x] **P1-2 时段日均两处接线零断言**：`DailyAveragePeriodsTest` 只打靶纯函数；把 `AppViewModel` / `PeriodSummaryCalculator` 的 `dailyAveragePeriods` 调用改回累计值，360 测仍全绿。且「四段不超时钟跨度」用例输入 = 期望 × 7，第二条断言恒真。→ 补两条接线测试 + 换掉恒真断言，变异必须变红。
- [x] **P2-1 统计页整页 Crossfade**：`StatsScreen` 的 `Crossfade(targetState = state.data, 200ms)` 重建内容分支，`MetricBarChart` 的 `remember(points){ Animatable(0f) }` 入场动画重播 —— 与今日页「闪一帧」同源。→ 去掉整页淡变，用 `scripts/capture_anim.py` 复录统计页切换抽帧。
- [x] **P2-2 日均分母与小时表不同源**：分母 `Summary.recordedDays`（day_summary）、分子 `hour_usage` SUM；某天只有 hour 行时日均被放大并超 6/6/4/8h。→ `dailyAveragePeriods` 按四段时钟跨度显式钳制并写明理由，补极端输入用例，§5.7 第 7 条同步。
- [x] **P2-3 日历 tab 副标题回落「今天」**：`calendarState.data == null`（加载中 / 无数据）时走 `selectionSubtitle(headerSelection)`，在「不参与筛选」的页面上出现「今天 · X月X日」。→ 日历分支独立文案，不显示日期。
- [x] **P2-4 `catch (e: Exception)` 吞 `CancellationException`**：`loadAll` / `loadPeriod` 快速连点日期时，被取消的加载会写入 error 状态。→ 先 `catch (e: CancellationException) { throw e }`。
- [x] **P2-5 `applySelection` 自己取消自己**：协程赋给 `loadJob` 后调 `loadAll()`，其首行 `loadJob?.cancel()` 取消的正是当前协程，目前靠「之后无挂起点」侥幸可用。→ `loadAll(cancelPrevious: Boolean)`，写入路径传 false。
- [x] **P3-1 读屏**：`RollingNumberText` 每位一个 `AnimatedContent` → 一个 `Text`，`MetricCardPair` 未 `clearAndSetSemantics`，TalkBack 可能把「87」读成两段；补语义并真机听一遍。
  - **建议方案 A（负责人待采纳，2026-11-15 01:50 已给建议）**：可下钻卡改 `clearAndSetSemantics`，一句话读完「标签 + 值 + 对比说明 + 查看详情」；抽 `metricCardDescription()` 纯函数（现状默认 `"$label 查看详情"` **缺当前值**，与 §5.2 原文不符，`TodayScreen` 也没传 `clickDescription`）；`RollingNumberText` 补 `semantics(mergeDescendants = true)` 兜住统计页大数字。约 20 行 + 3 测试；**不引 Compose 测试依赖**，验证用 Redmi `uiautomator dump` 看双卡节点数与 `content-desc`。备选：仅合并 `RollingNumberText`（收益小）；加 `ui-test-junit4`（拖慢构建，不推荐）。
- [x] **P3-2 `DateInput.error` 格式错分支不可达**：字段文本恒为数字流，`按 年-月-日 输入` 永不触发且被测试固化；删分支或注明防御性。
  - **建议方案 A（负责人待采纳）**：代码不动，补 2 条纯函数测试（`error("2026-1-1-1")`、`error("2026/11/11")` → 格式提示），把零覆盖补成真覆盖。**事实修正**：该分支 UI 不可达但**函数可达**（`error()` 是纯函数），不是死代码；现有测试注释已写「只在超过 8 位数字的非掩码形状下兜底」却无用例。备选：删分支 + 删 `SHAPE`（丢防御层）。
- [x] **P3-3 `normalizeInput(raw, cursor)` 的 cursor 参数未使用**（永远置末尾），签名误导调用方。**2026-11-15 按方案 A 完成（`c0cf556`）**；P3-2 完成（`d64a4a5`，计划原例 `2026-1-1-1` 实测为 7 位中间态、已换 4 段满 8 位形状并钉进用例）；P3-1 完成（`eb381b0` + 真机收口 `035235c`，dump 发现 clearAndSetSemantics 清掉点击语义、已补回 onClick）。三条详见 `CODE_REVIEW.md` 文末补记。
  - **建议方案 A（负责人待采纳）**：删参数 → `normalizeInput(raw): Pair<String, Int>`，改 `DateSheet` 2 处调用 + `DateInputTest`，KDoc 保留「光标一律置末尾（自动补 0 是末尾插入）」。约 5 行、零风险，顺带消掉编译 warning。备选：真按光标插入（要处理补 0 改长度后的光标映射，当前交互不需要）。
- [x] ~~**P3-4 `SyncManager` 绑死 `WebDavProvider.MANUAL_FILE`**~~ **批 C 已解决**：远端文件名/路径口径统一收进 `SyncPaths`（`SyncManager` 不再引用 WebDAV 常量），自动备份文件名带设备短 ID；详见 `CODE_REVIEW.md` 文末补记。
- [x] **~~待裁决 · 排行页口径~~** **2026-11-15 负责人裁定「排行弹层只给单日」，CR-18 落地（`338bef1` 文档 + `787753d` 实现 + Redmi 真机四步复核）**：排行弹层恒 DAY 三档；周期口径显式选日即退出周期窗口。
- [x] **~~复核新增 · 统计侧详情页残留~~** **2026-11-15 负责人裁定「详情页跟随归属状态」，CR-18 落地**：详情取数按 `returnTo` 归属取窗（今日→`todayEffective` 同源双卡、统计→`statsSelection` 原窗口），打开即重算；今日侧详情 Redmi 真机选日即时变化复核通过（统计侧入口为逻辑缺口、由 4 例端到端测试钉住）。

## 功能批次

- [ ] 8b-2 剩余：Google Drive provider、OAuth 登录/退出、Google 不可达不影响 WebDAV
- [ ] 批 10b：年视图色阶跟随低使用阈值 `x`（负责人已裁决；contract → Room/Empty → ViewModel → UI，月视图仍按每日目标比例）
- [ ] 批 11：提醒与限额
- [ ] 批 12：Glance 小组件
- [ ] 批 13：多机型适配
- [ ] 14b：AI 分析（默认关闭）
- [ ] 14a 余项：PNG 长图
- [x] ~~**Redmi 补装 CR-13 B 版**~~：设备短暂 `offline` 后恢复，已补装；vivo / Redmi 两台 APK MD5 同为 `631e398b`，启动冒烟均正常。
- [ ] 批 9：§7 **42 条**验收逐条签核收尾（37 已撤销 → 实签 41 条）

## 验证补齐

- [ ] **Pager 跨页跳转偶发卡在两页之间**（vivo `10CEB40M5G001DK` 2026-11-16 复核发现，**负责人裁定本轮只记账不修**）：从「日历」点「今日」（跨两页）后，标题与路由已是「今日」，但 Pager 滚动偏移停在两页中间——今日页只占屏幕左侧约 58%、统计页露在右侧、底部导航胶囊停在两图标之间；再点一次「今日」即归位，同样路径第二次复跑正常（偶发）。根因在 CR-17 的 Pager 回写环路：点 tab 发起 `animateScrollToPage` → 动画中途 `snapshotFlow { currentPage }` 回写路由 → `LaunchedEffect(mainPage)` 再发起一次滚动 → 两次动画互相打断。**修时注意**：CLAUDE.md / §5.2 定的是「点 tab 只滚动、`snapshotFlow` 单向回写、禁止双向写」，修法不能改成点 tab 直接写路由；可考虑在 `LaunchedEffect(mainPage)` 的纠正逻辑里等 `isScrollInProgress` 为 false 再动 Pager（或用 `settledPage` 兜底）。**归批 10b 一起做**（那是下一个动 Pager/数据链的批次），届时补文档 + 变异验证 + 双机复跑。
- [x] ~~**CR-09 ③ 转场**~~：已由 CR-11 三层方案取代并录屏验证（`screenrecord` + ffmpeg 抽帧）：打开「图标放大到一半变页面再放大到全屏」、关闭「缩成带齿轮的小块再收回右上角」、退场中点屏不飘、无闪帧。

## 工程状态

- [x] **2026-11-13 14:14 已推送 CR-11 / 录屏工具 / 桌面模式共 4 个提交到 `origin/main`**（负责人授权）。此后 CR-12～14 等提交仍在本地待推；**具体提交与实时数量只以 `git status -sb` / `git log` 为准**，不在本文固化，推送由负责人执行。

## 工具速查（常备，不排期）

```bash
# 零参数：自动选唯一在线设备，正式速度录一次完整往返
python scripts/capture_anim.py

# 看不清动画：双向慢放（临时改常量→构建→录→自动还原重装）
python scripts/capture_anim.py --slow

# 系统桌面原生动画参考：先把手机停在含目标图标的桌面页，按 HOME 返回桌面
python scripts/capture_anim.py --launcher
python scripts/capture_anim.py --launcher --slow   # 5x 系统动画缩放，自动还原

# 日期切换过渡：预定位「选择日期」按钮与「昨天」chip，录两步点击全过程
# 产物 switch.png = 切换瞬间逐帧联系表（不写死坐标、不按返回键）
# 产物 switch.png = 切换段联系表；switch_dense.png = 8×6 高密度诊断帧（逐帧约 21ms）
python scripts/capture_anim.py --date-switch -s GAQOHMNFQWFUPBQO --name a1_date_switch
# 统计页切区间（P2-1 复核）：先切 tab，再把 chip 换成「近 30 天」
python scripts/capture_anim.py --date-switch --switch-tab 统计 --switch-label "近 30 天" -s GAQOHMNFQWFUPBQO --name p21_stats
# 录 tab 横滑手势（CR-17 ② 跟手复核）
python scripts/capture_anim.py --swipe 1000,1400,220,1400,400 --no-back -s GAQOHMNFQWFUPBQO --name cr17_tab_swipe
# 批 D：先点「打开快捷菜单」再点下拉面板里的「设置」，验证转场起点重登记
# 预展开→定位→收起，录屏里重放两步点击；--slow 可看清放大起点（自动还原代码与 APK）
python scripts/capture_anim.py --quick-menu --slow -s GAQOHMNFQWFUPBQO --name cr_d_quick_menu

# 多台设备 / 手动指定入口坐标 / 只录进入
python scripts/capture_anim.py -s GAQOHMNFQWFUPBQO --name cr11_recheck
python scripts/capture_anim.py --tap 1077,263 --no-back
```

桌面模式不启动屏迹、不按 HOME 初始化（避免翻页丢失图标），返回默认用 HOME 键（键码 3），`--label` 可按图标描述改目标。实测 MIUI Launcher 的启动/返回动画不受 `window/transition/animator_animation_scale` 控制，`--slow` 在桌面模式效果有限；看原生过渡请直接看正常速度的 `enter.png` / `exit.png`。

产物默认在 `C:/tmp/screentrace-capture/<名称>/`：`full.png`（全流程联系表）、`enter.png`、`exit.png`、`clip.mp4`、`raw.mp4`。脚本用 `uiautomator dump` 定位按钮（跨机型不写死坐标），并按录屏**真实时长**收敛抽帧窗口（`screenrecord` 静止段不写帧会导致末尾黑格）。

---

**完成定义**：每步一个提交并写清编号；改代码前先对齐需求文档；新增测试要能通过变异验证（改坏实现必须变红）；UI 改动真机截图/`uiautomator dump` 复核；不修改 `README.md`（2026-11-13 仅获一次性例外修 4 行失效路径，此后仍冻结）；**评审只写 `docs/CODE_REVIEW.md`**——不新建评审文件、评审本身不改代码（2026-11-14 负责人定，CR-15 评审已按此并入）。
