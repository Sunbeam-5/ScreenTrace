# A-5 R10 真机复核证据（Redmi · 仅 120Hz）

> 本目录是 **A-5 R10「TabScrollGuard 同目标连续点击的守卫所有权（P2）」** 在
> **Redmi 2407FRK8EC（GAQOHMNFQWFUPBQO）· 仅 120Hz** 上的受控真机复核原始证据。
> 放行范围：GPT 复审「R10 代码实现通过 / 整体交付附条件通过 / P2 暂不关闭，等待 Redmi 真机复核」。
> **vivo 10CEB40M5G001DK 全程未触碰**；未启动完整 A-5 矩阵；未合入 main。

## 1. 身份与门禁

| 项 | 值 | 证据 |
| --- | --- | --- |
| 设备 | Redmi 2407FRK8EC（`GAQOHMNFQWFUPBQO`），Android 16，1220×2712 | 各轮 `run_info.json` → `env.model` / `env.wm_size` |
| APK 身份 | 本地构建 SHA256 `0CC0171E45D29DFAD57C2F9DDC4CFBD6FAD2572BC2C0582BBC9400791BC486E4`（实现提交 `d9098cf`） | `gate/apk_identity_r10_build.json` |
| 设备侧对账 | 拉取设备 `base.apk` 实算 SHA256 **与本地构建完全一致**（`sha256_match=true`） | 同上 |
| 刷新率门禁 | 9 个有效轮次全部 `actual_active_display_fps=120.00001`（活动模式 modeId=3），`refresh_match_request=true` | 各轮 `run_info.json` → `env.display_parse`；原始 `dumpsys_display.txt` |
| 刷新率恢复 | 每轮结束均恢复设备原值 60/60 并回读校验，`restore_ok=true`（9/9） | 各轮 `run_info.json` → `env.refresh_restore` |
| 场景有效性 | 每轮 2 次点击/手势数与场景定义一致；`selected_tab` 由 uiautomator 落地 XML 解析 | 各轮 `ui_final.xml` / `ui_setup.xml` |

SHA 清单与校验记录文件自身不入清单（与 `SHA256SUMS.txt` 同规则），避免自引用导致条目失真。

**本轮有效轮次 9 个（3 场景 × 3 轮）**，另有 5 个无效/对照轮次原样保留在 `_invalid_rounds/`（见 `6`）。

## 2. s5_same：连续两次点同一 Tab（P2 核心验收，3 轮）

GPT 给出的核心判据：

```
seq1: scrollCancel → scrollFinally aligned=false
seq2: scrollEnd    → scrollFinally aligned=true
Route/align 只允许由 seq2 触发
```

| 轮次 | seq1 收尾 | seq2 收尾 | `Route/align` 次数 | 归属 | `A5/routeScroll` | 最终页 / 选中 Tab |
| --- | --- | --- | --- | --- | --- | --- |
| `s5_same_120hz_r3` | `scrollCancel=1#1` → `scrollFinally=1#1 aligned=false` | `scrollEnd=1#2` → `scrollFinally=1#2 aligned=true` | 1 | seq2 | 无 | 统计页 / 统计 |
| `s5_same_120hz_r4` | `scrollEnd=1#1` → `scrollFinally=1#1 aligned=false` | `scrollEnd=1#2` → `scrollFinally=1#2 aligned=true` | 1 | seq2 | 无 | 统计页 / 统计 |
| `s5_same_120hz_r5` | `scrollCancel=1#1` → `scrollFinally=1#1 aligned=false` | `scrollEnd=1#2` → `scrollFinally=1#2 aligned=true` | 1 | seq2 | 无 | 统计页 / 统计 |

- **3/3 轮满足 GPT 核心判据**：旧点击（seq1）恒为 `aligned=false`，末击（seq2）恒为 `aligned=true`，
  `Route/align` 每轮**恰好 1 次**且归属 seq2 的 `aligned=true`。
- r4 是另一种收尾形态：旧协程未被取消而是正常结束（`scrollEnd` 先于 seq2 落定），
  仍为 `aligned=false` —— 所有权判定与**结束先后**无关，符合 token 模型预期。
- 对照基线（同一场景、**R9 旧 APK**）：见 `_invalid_rounds/s5_same_120hz_r1_oldapk_contrast/`，
  旧实现为 seq1 `aligned=true` + `Route/align=1`、seq2 `aligned=false` —— 与本轮结果正好相反。

## 3. s7_swipe_fast：快速横滑跨两页（OBS-1 检查，3 轮）

**判定口径（设计文档 `OBS-1）**：快速横滑若出现 `A5/routeScroll`（即 `LaunchedEffect(mainPage)`
的 `currentPage != target` 回滚分支成立），即说明 OBS-1 命中。

| 轮次 | 起手页 | `Route/currentPage` 序列 | `Route/selectMain` 序列 | `A5/routeScroll` | 最终页 / 选中 Tab |
| --- | --- | --- | --- | --- | --- |
| `s7_swipe_fast_120hz_r3` | 今日 | 1 → 2 | 统计 → 日历 | **无** | 日历页 / 日历 |
| `s7_swipe_fast_120hz_r4` | 今日 | 1 → 0 → 1 | 统计 → 今日 → 统计 | **无** | 统计页 / 统计 |
| `s7_swipe_fast_120hz_r5` | 今日 | 1 → 0 → 1 | 统计 → 今日 → 统计 | **无** | 统计页 / 统计 |

- 3/3 轮**均未出现 `A5/routeScroll`**、未建立程序化 owner、未发射任何 `A5/*` 事件
  （用户手势不产生程序化滚动探针，属预期）。
- 每轮路由回写与 Pager 实际停留页**逐页一致**（`selectMain` 序列与 `currentPage` 序列一一对应），
  最终选中 Tab 与最终页一致。
- **OBS-1 本轮未命中**（3 轮，未观测到回滚滚动）。**不等于 OBS-1 关闭** —— 观察项继续开放，
  结论待 GPT 复审后裁决。

## 4. s8_swipe_interrupt：横滑中途打断程序化滚动（3 轮）

场景：今日页 → 点「日历」（触发程序化滚动 → 第 2 页）→ 120ms 后立刻反向快滑（打断）。
**判别性**：点击目标页（日历）与打断后 Pager 实际停留页（今日/统计）**不同**，
因此能区分「路由跟随 Pager 实际停留页」与「路由粘在点击目标页」两种实现。

| 轮次 | 打断证据 | `Route/currentPage` | `Route/guardIgnore` | `Route/align` | 最终页 / 选中 Tab | 与点击目标是否一致 |
| --- | --- | --- | --- | --- | --- | --- |
| `s8_swipe_interrupt_120hz_r2` | `A5/scrollCancel=2#2` | 1 → 0 | `guardIgnore=1` | `Route/align=1` | 今日页 / 今日 | **不一致**（点击目标是日历） |
| `s8_swipe_interrupt_120hz_r3` | `A5/scrollCancel=2#2` | 1 → 2 → 1 | `guardIgnore=1` | `Route/align=2` | 统计页 / 统计 | **不一致**（点击目标是日历） |
| `s8_swipe_interrupt_120hz_r4` | `A5/scrollCancel=2#2` | 1 → 0 | `guardIgnore=1` | `Route/align=1` | 今日页 / 今日 | **不一致**（点击目标是日历） |

- 3/3 轮：程序化滚动**确实被手势打断**（`A5/scrollCancel`），守卫正常收尾
  （`A5/scrollFinally=2#2 aligned=true`）。
- 3/3 轮：**最终路由 = Pager 实际停留页**，且**不等于点击目标页** → 「路由跟随 Pager 实际停留页」成立。
- 3/3 轮：滚动途中的**中间页回写被守卫忽略**（`Route/guardIgnore=1`），标题未闪中间页（CR-22 口径）。
- 3/3 轮均**无 `A5/routeScroll`**。

## 5. 方法与已知限制（如实登记，不掩盖）

1. **单个人类手势无法跨两页**：屏宽 1220px，`HorizontalPager` 用默认 `flingBehavior`
   （`snapDistance=atMost(1)`），跨两页需 ≥1.5 页宽（≈1830px）——超出屏宽。
   故 s7 的「跨两页」用**越界长拖**（单手势连过两个 50% 阈值，观测到 `currentPage` 1→0→1）
   + 一次快滑构造，属**合成手势，与人手不同**，报告如实标注。
2. **手势有效区**：y=1200 的横滑被页面内控件吞掉（0 次页面变化），改用 y=700（实测有效）。
3. **时序窗口**：adb 主机往返约 150ms/命令，会错过 OBS-1 的亚帧窗口，
   故被测手势改为**设备侧单条 shell 串行执行**（不回主机），串内嵌 `date +%s.%N` 记录设备端时刻：
   - s7 手势间隔：331ms / 388ms / 220ms（r3/r4/r5）
   - s8 点击→滑动间隔：76ms / 64ms / 51ms（r2/r3/r4），落在程序化滚动动画窗口内
4. **门禁采样时点修正**：本 ROM 面板在 idle 态恒为 60Hz，只有唤醒屏幕并让 App 进入前台才切到目标模式。
   脚本已把门禁 dumpsys 移到**活动态**（App 在前台、与交互同一时窗）采样；门禁语义不变
   （仍要求活动模式唯一映射到目标 fps，映射缺失/重复即 INVALID）。
5. `adb input tap` 时间戳＝**主机发起命令时刻**，非真实手指触摸时间；录屏仅作辅助证据。

## 6. 无效 / 对照轮次（保留，不删除）

| 目录 | 性质 | 说明 |
| --- | --- | --- |
| `_invalid_rounds/s5_same_120hz_r1_oldapk_contrast/` | **对照基线（有效对照）** | R9 **旧 APK** 的同场景结果：seq1 `aligned=true`+`Route/align=1`、seq2 `aligned=false` |
| `_invalid_rounds/s5_same_120hz_r2_invalid_env_gate/` | INVALID（零采样） | 门禁在 idle 态采到 60Hz → `refresh mismatch` → 整轮 INVALID、零采样，恢复 60/60 成功 |
| `_invalid_rounds/s7_swipe_fast_120hz_r1_gesture_noop_y1200/` | 无效手势 | y=1200 横滑被控件吞掉，0 次页面变化（用于定位有效手势区） |
| `_invalid_rounds/s7_swipe_fast_120hz_r2_scriptbug/` | 脚本缺陷 | `done` 变量未赋值导致 UnboundLocalError（已修） |
| `_invalid_rounds/s8_swipe_interrupt_120hz_r1_scriptbug/` | 脚本缺陷 | 同上 |

## 7. 本轮结论

- **P2 的核心验收判据在真机成立**（s5_same 3/3 轮）：旧点击 `aligned=false`、末击 `aligned=true`、
  `Route/align` 仅由末击触发一次。**是否关闭 P2 由 GPT 复审裁决，本轮不自行宣布。**
- **OBS-1 本轮 3 轮未命中**（无 `A5/routeScroll`），观察项**继续开放**。
- **新登记观察项 OBS-2（只登记不修）**：s8 打断瞬间的 `Route/align` 对齐到**中间页**
  （r2/r4 → `align=1`=统计；r3 → `align=2`=日历），随后才被 Pager 实际停留页纠正
  （最终 `selectMain` = 今日/统计）。表现为一次短暂的路由/标题抖动，
  属 CR-22 同类既有时序，改导航架构超出本轮授权，故只登记。
- 本轮**未合入 main**、**未启动完整 A-5 矩阵**、**未碰 vivo**、**未改业务代码**。

## 8. 逐轮机器可读汇总

`gate/analysis_summary.json`：9 个有效轮次 + 5 个无效/对照轮次的完整字段
（status / restore_ok / 实际刷新率 / setup_tab / selected_tab / A5 时间线 /
Route 时间线 / 设备端手势时刻 / 录屏字节数）。
