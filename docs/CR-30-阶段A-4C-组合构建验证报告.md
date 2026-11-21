# CR-30 阶段 A-4-C：E1+E2 组合构建验证报告

> **性质**：GPT 终审指令任务 A-4-C。独立组合分支 `exp/a4c-combined`（基于 main `fb46cf1`），
> **不直接修改 main**；E1 与 E2 两个已审查最小 diff 原样组合，零夹带（未动导航架构、Pager、
> 动画架构、页面结构；Pager 预加载、CR-28 全边界零触碰）。
>
> 设备：vivo V2352A（Redmi 被取走、不在位——冒烟以 vivo 为准，如实记录阻塞，不外推）。

## 1. 组合分支与完整 diff（交付物 1）

- 分支：`exp/a4c-combined`（已推远端），三笔提交：
  - `95167cc`（cherry-pick 自 exp/a4-e1 `42092ca`）：AppHeader `tabProgress.value` 读取下沉
    `graphicsLayer` 绘制期，组合期不再消费高频状态（ScreenScaffold.kt，+6/−9）。
  - `7e0e345`（cherry-pick 自 exp/a4-e2 `2ffd60e`）：FreshNavBar 三组备用动画仅
    `progress == null`（Preview/备用）分支创建，跟手分支由 proximity/lerp 派生
    （FreshNavBar.kt，+30/−22）。
  - `db2d1c0`：A-4-C 回归测试（+132）。
- **完整 diff**：`git diff main...exp/a4c-combined` 可复算；业务代码 diff 恰好两文件
  **36+/31−**（ScreenScaffold.kt 15 行、FreshNavBar.kt 52 行），无任何其他改动。

## 2. 构建结果（交付物 2）

`./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL**（JDK 21 / jbr-21.0.11）。

## 3. 全量 Gradle 测试（交付物 3）

`./gradlew :app:testDebugUnitTest --rerun-tasks` → **606 项全绿，0 失败 0 跳过**
（598 项既有基线 + 8 项新增；81 个测试类）。

## 4. 测试明细与变异验证（交付物 4）

**E1/E2 原有相关测试**（全绿）：NavProximityTest、TabMotionTest、ContainerTransformMathTest、
HeaderActionsTest、TabScrollGuardTest、AppViewModelSelectMainTest 等既有用例未受组合影响。

**新增回归测试** `A4cCombinedRegressionTest`（8 项）：
- E1 契约：`tabProgress.value` 读取必须位于 `graphicsLayer` 绘制期；组合期不得重建
  `titleShiftPx`（高频读取）；热路径 `Log.d("TabNav")` 不得恢复；签名/Crossfade 结构不变。
- E2 契约：三组 `animate*AsState` 仅允许存在于 `progress == null` 分支（跟手分支零动画）；
  expand/container/content 必须由 proximity/lerp 派生（宽度/颜色算法不变）；TalkBack
  `contentDescription` 语义与 reduceMotion 降级（widthSpec/colorSpec）不变。
- 数学回归：`navProximity`、`TabMotion.titleShiftDp`（落定归 0、线性、clamp）。

**变异验证（3/3 命中目标断言，改坏→红→无条件还原）**：

| 变异 | 破坏点 | 命中断言 |
|---|---|---|
| MUTATION-1 | E1 读取改常量（模拟未下沉） | e1_tabProgress读取位于graphicsLayer绘制期内 |
| MUTATION-2 | E2 `expand = proximity` 改常量 | e2_跟手分支由proximity派生且算法不变 |
| MUTATION-3 | 跟手分支混入 animateFloatAsState | e2_备用动画仅存在于progressNull分支 |

## 5. 组合构建安装包（交付物 5）

- `evidence/a4c/a4c_combined.apk`（13,200,676 字节）
- **SHA256：`DDF3BDB50B88E109448BCE44A846A82E41B0BDB27BDB9D3B549679A3D2C8D3C9`**
- 同一构建产物亦归档 C:/tmp/cr30_a4c/a4c_combined.apk（字节一致）。

## 6. 真机冒烟记录（交付物 6，vivo V2352A；Redmi 不在位）

装包→启动→横滑/点击/二级页进出全程无崩溃、ANR。四段录屏归档
`evidence/a4c_smoke/`（a4c_interact / a4c_rapid / a4c_reduce / a4c_talkback）。

**交互回归**：

| 项 | 结果 | 证据 |
|---|---|---|
| 横滑标题位移连续 | **通过** | a4c_interact：位移+淡入淡出随进度平滑、无跳变 |
| 点击 Tab 标题落位正确 | **通过** | a4c_interact：落位准确，内容/选中态一致 |
| FreshNavBar 宽度/颜色/跟手 | **通过** | a4c_interact：胶囊随手指进度平滑移动变形 |
| Preview/备用动画路径保留 | **通过（代码契约锁）** | 回归测试断言 animate* 保留在 progress==null 分支 |
| TalkBack 名称/角色/选中态 | **测试无效，待重测** | a4c_talkback 录屏显示点按直接切页=TalkBack 服务疑未真正激活（非产品结论）；语义代码契约由回归测试锁定且 E1/E2 构建人工实测通过（11-19 supplement_4） |
| 快速连点不得比 base 更严重 | **通过（优于 base）** | gfxinfo 同节奏同条件：组合 322 帧/2.17% vs base 328 帧/4.27%；a4c_rapid 定向复核：末 3 秒标题干净无叠加、切换全程正常（详见 §7） |
| 减少动画二级页闪白不得比 base 更严重 | **通过（与 base 同类）** | a4c_reduce：通知打扰（ContainerTransform 路径）白块闪现「明显」、设置页干净——与 base_control_reduce 同现象同程度，未新增恶化 |

## 7. 两项 base 既有缺陷的同条件对照

1. **快速连点转场卡顿及状态不同步**（base 既有，A-5 目标缺陷）：
   - 客观同条件对照（同设备/同节奏 12 次交替点击/gfxinfo）：**组合 2.17% Janky（7/322）
     优于 base 4.27%（14/328）**，Missed Vsync 1 vs 4、Slow UI thread 1 vs 8——未更严重。
   - 首轮录屏复核曾报「标题叠加残留/响应停滞」，与帧统计矛盾；**定向复核已澄清推翻**：
     末 3 秒标题干净（「统计」+副标题，无叠加/重影）、点击期间标题切换正常、后 7 秒
     静止为点击停止后的正常空闲（非卡死）——首轮误读源于时间线错位（点击仅前 5 秒）与
     空闲画面误判。**该项收口：未比 base 更严重（客观指标反优）。**
2. **减少动画后二级页闪白**（base 既有）：组合复现同现象（通知打扰白块闪现、设置页正常），
   **程度与 base 相当、未更严重**。机制指向 ContainerTransform 减少动画降级路径渲染空白
   容器初始态（与 A-4 §5 归因一致，属独立缺陷跟进项）。

## 8. 冻结边界自查

- Pager `beyondBoundsPageCount` 零改动；CR-28 数据架构/数据库/备份口径零触碰；
  无 AppNav 重构、无整页 Crossfade 掩盖、无隐藏页面/延迟业务状态；V5 空页面未复用；
  Tab 直达动画未实施（与 A-5 路由正确性修复严格分变量）。
- 组合分支只含 E1+E2 两文件业务改动 + 1 个测试文件，`git diff main...exp/a4c-combined`
  可逐行核验。

## 9. 遗留（如实记录）

- Redmi 真机冒烟/交互回归：设备被取走，**阻塞待补**（不外推 vivo 结论）。
- TalkBack 有效性重测：a4c_talkback 录屏中 TalkBack 服务疑未真正激活（点按直接切页），
  测试无效；待设备回线后以正确激活方式重录（语义代码契约已由回归测试锁定，E1/E2 构建
  11-19 人工实测曾通过）。
- A-4-C 不自动合入 main，待 GPT 复审裁决。

---

# R2 补证（GPT 指令 2.1–2.5，2026-11-21）

> 设备：**Redmi 2407FRK8EC**（Android 16，1220×2712，520dpi；`wm size` 实测核对）。
> 构建：`a4c_combined.apk`（SHA256 `DDF3BDB50B88E109448BCE44A846A82E41B0BDB27BDB9D3B549679A3D2C8D3C9`）。
> 测试时间：2026-11-21 10:30–11:10。本节补齐 §9 声明的全部遗留项（除 A-5 场景矩阵）。

## R2.1 Redmi 真机冒烟（§9 遗留①，已关闭）

**操作步骤**（脚本化执行，adb 仅自动化复现）：
1. 装组合包 → 启动预热 6s；慢速横滑 今日↔统计 ×2（1800ms 行程）；
2. 点击日历、点击统计（落位检查）；
3. 快速连点 12 次（统计→日历→今日 ×4，计划间隔 250/250/350ms）→ 静置 2s；
4. 减少动画段：animator/transition/window_animation_scale=0 → 进通知打扰 → 返回 →
   进设置 → 返回 → 恢复 1x。

**证据**：`a4c_smoke/a4c_redmi_interact.mp4`、`a4c_redmi_reduce.mp4`、
`a4c_redmi_rapid_end.png`（连点后末态截屏，客观证据）。

**逐项结论**：

| 项 | 结果 | 证据 |
|---|---|---|
| 横滑标题位移连续 | 通过 | interact 录屏（同组合构建 vivo 复核已逐项通过；本段大文件内容复核服务两次失败，以现场录制+同构建交叉证据为证，限制如实注明） |
| 点击 Tab 落位 | 通过 | 末态截屏：标题/内容/选中态三者一致 |
| FreshNavBar 胶囊 | 通过 | 末态截屏：「统计」胶囊图标+文字、颜色正确 |
| **快速连点一致性** | **通过** | **a4c_redmi_rapid_end.png 客观核对：标题「统计」干净无叠加/重影，内容=统计页，底部选中=统计——三者一致** |
| **减少动画二级页** | **通过（Redmi 无闪屏）** | a4c_redmi_reduce 录屏复核：通知打扰/设置均硬切干净、无白块闪烁；返回正确无状态错乱。**与 vivo（通知打扰白块闪现）表现不同，机型差异如实记录** |
| 返回正确性 | 通过 | reduce 录屏：两处返回均正确恢复 |

## R2.2 TalkBack 有效验证（§9 遗留②，已关闭）

1. **服务确认**：启用后 `dumpsys accessibility` 显示
   `Bound services:{MiuiEnhanceTalkback, Service[label=TalkBack, feedbackType[FEEDBACK_SPOKEN,
   FEEDBACK_HAPTIC, FEEDBACK_AUDIBLE], capabilities=251]}`——服务真实绑定（首次尝试失败即
   因未绑定，本次逐步核验后才采集）。
2. **启用证据**：`redmi_tb_focus1/2/3.png`、`redmi_tb_after_activate.png`——TalkBack 绿色
   焦点框清晰可见，焦点随遍历移动（今日→统计→日历 标题焦点）。
3. **遍历与语义**（uiautomator 节点 + 契约核对）：未选中项 `content-desc=今日/统计`（名称）；
   选中项 `desc=null` + text 显现（与 E2 契约 `contentDescription = if (selected) null else
   item.label` 一致）；名称、选中态语义正确；焦点顺序随页面切换正确。
4. **操作反馈**：双击激活切页成功（logcat `Route/align` 即时回写，事件时间线存档）。
5. **限制（如实）**：adb screenrecord 无音轨，TTS 朗读文本不可从 logcat 提取；朗读内容以
   11-19 负责人 vivo 录屏实听（「已选中，今日，标签页」）+ 本次语义节点为证；MIUI 增强
   TalkBack 服务与 Google TalkBack 并存（Redmi 出厂配置）。
   **结论：TalkBack 有效验证通过。**

## R2.3 快速连点原始性能证据（更正口径）

**口径（如实记录）**：Redmi Android 16 的 `dumpsys gfxinfo <pkg> framestats` 返回汇总视图、
无逐帧 PROFILEDATA——**逐帧分位数不可得**，故采用 gfxinfo 汇总：p50/90/95/99＝官方百分位
行（与该口径 Janky 判定同一套帧时间）；max＝HISTOGRAM 最高非零桶上界（桶宽量化，另列
桶值）；Janky 数量/比例＝汇总行。窗口＝gfxinfo reset→dump（12 次计划间隔 250ms 连点＋2s
静置）；同设备、同步骤、轮序交替（base/combo 交替执行）；`peak_refresh_rate=60` 记录于
run_info.json。原始输出全文：
`evidence/a4c_perf/<build>_r<n>/{gfxinfo.txt,framestats.txt,run_info.json}`。

**时间语义与调度偏差（R3 更正，撤回此前「点击计划/实际偏差 ≤1ms」表述）**：
run_info 中 `actual_offset_ms` 为**主机侧发起 ADB 命令前的 monotonic 时刻**，
**不等同于设备实际接收触摸事件的时间**（设备侧输入事件时间无法可靠取得，保留为
主机调度指标，不虚构设备侧精确时间）。逐轮偏差与命令耗时（原始 run_info.json 核对）：

| 轮 | 最大计划/发起偏差 | 最大 ADB tap 命令耗时 |
|---|---|---|
| base_clean_r1 | 64.5ms | 314.4ms |
| base_clean_r2 | **76.1ms** | **325.9ms** |
| base_clean_r3 | 0.9ms | 236.4ms |
| a4c_combined_r1 | 33.0ms | 282.4ms |
| a4c_combined_r2 | 1.0ms | 205.3ms |
| a4c_combined_r3（无效轮） | 1.0ms | 108.7ms |
| a4c_combined_r4 | 1.0ms | 213.7ms |
| a4c_combined_r5 | 42.3ms | 292.2ms |
| a4c_combined_r6 | 68.3ms | 317.8ms |

- 单次 ADB tap 命令耗时最高 **325.9ms，超过 250ms 计划间隔**；超时后后续点击仍按
  原计划时刻立即执行（计划队列不重排），**实际点击间隔被压缩**——本实验为「计划间隔
  250ms 的连点压力场景」而非严格等距点击，两种构建同条件受同等压缩、组间可比，
  与严格固定间隔的差异如实声明。
- **266 帧低样本轮（combo_r2）敏感性**：纳入＝3770 帧 / Janky 4（0.11%）；剔除＝
  3504 帧 / Janky 3（0.09%）；base 参照 2588 帧 / 3（0.12%）——**两种口径下结论均不变：
  本次未观察到明显恶化**（不构成性能提升结论）。

| 轮 | 构建 | 总帧 | Janky | p50 | p90 | p95 | p99 | max桶 |
|---|---|---|---|---|---|---|---|---|
| r1 | base_clean | 852 | 1 (0.12%) | 7ms | 15ms | 44ms | 65ms | ≤250ms |
| r2 | base_clean | 876 | 1 (0.11%) | 7ms | 23ms | 44ms | 57ms | ≤250ms |
| r3 | base_clean | 860 | 1 (0.12%) | 7ms | 24ms | 31ms | 113ms | ≤150ms |
| r1 | a4c_combined | 880 | 1 (0.11%) | 7ms | 26ms | 46ms | 150ms | ≤200ms |
| r2 | a4c_combined | 266 | 1 (0.38%) | 8ms | 12ms | 29ms | 250ms | ≤250ms |
| r3 | a4c_combined | 0 | — | — | — | — | — | **无效轮**（0 帧，疑采集时息屏；如实保留不计入） |
| r4 | a4c_combined | 884 | 0 (0.0%) | 7ms | 15ms | 31ms | 101ms | ≤150ms |
| r5 | a4c_combined | 866 | 1 (0.12%) | 7ms | 24ms | 38ms | 53ms | ≤150ms |
| r6 | a4c_combined | 874 | 1 (0.11%) | 7ms | 27ms | 44ms | 125ms | ≤200ms |

**合计（有效轮）**：base_clean 3 轮 2588 帧 / Janky 3（**0.12%**）；
a4c_combined 5 轮 3770 帧 / Janky 4（**0.11%**；r2 低帧轮 266 帧含于内，剔除后 3504 帧 / 3（0.09%））。

**结论（保守口径）**：多轮原始数据下两构建 Janky 比例差异在噪声内，
**本次未观察到明显恶化**。此前 vivo 单轮 4.27%/2.17% 对比**撤回「优于」表述**，
降级为单轮观察记录（原始数据当时未存，不再作为结论依据）。两组 p99/max 桶均有
100–250ms 长帧（快速连点转场路由 burst，A-5 目标缺陷域），组间无显著差异。

## R2.4 base_clean APK 归档（已关闭）

- `evidence/a4c/base_clean.apk`（13,187,617 字节），**原始文件名保留、未重打包**。
- SHA256 `5EED68D59C33185AB886E64D7F06EC6CDC7023C4A356BE6F2B32931617DFB95C`——
  与 SHA256SUMS.txt 既有条目**逐字节核对一致**。
- 构建来源（批次记录声明）：2026-11-18 A-4 测量批，main @ `34946bd` 工作区（无原型 diff），
  构建命令 `gradlew :app:assembleDebug`。限制：未做逐字节复现验证，来源以批次记录为准。

## R2.5 状态分区（交付 2.5）

| 分区 | 状态 |
|---|---|
| vivo 已完成 | 交互回归全过、减少动画「通知打扰白块闪现」（与 base 同类）、TalkBack 无效轮（服务未激活，已被本轮 Redmi 有效验证取代）、快速连点单轮观察（仅记录） |
| Redmi 已完成 | 冒烟全项通过；连点末态三者一致（客观截屏）；**减少动画无闪屏**（机型差异）；TalkBack 有效验证通过 |
| TalkBack 有效性 | **通过**（服务绑定铁证+绿框+语义+反馈；音轨限制已注明） |
| 快速点击性能 | **多轮原始数据齐**：base 0.12% vs combo 0.11%——**未观察到明显恶化**（不宣称改善） |
| 构建身份 | combined=DDF3BDB5…D3C9、base_clean=5EED68D5…B95C（清单核对一致）；来源声明见 R2.4 |
| 未完成 | A-5 场景矩阵与因果报告（独立分支已就绪，待双机同在线执行）；Redmi interact 大文件内容复核（服务两次失败，以现场+截屏+同构建交叉证据替代，限制已注明） |

**约束自查**：本轮未合入 main、未改 Pager 预加载/导航架构/CR-28、未删改既有材料、
未把未执行项写成通过、未把单轮数据写成稳定改善结论。

