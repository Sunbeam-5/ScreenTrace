# A-3/A-4 实验证据清单（R3.1 版）

> **R3.1（2026-11-18）**：统计链路可靠性整改后的完整证据目录。旧「19 条哈希」说法作废，
> 一切哈希以 `SHA256SUMS.txt`（全量实算）为准；旧 APK 说明、旧证据路径均已修正。

## 目录结构

- `a3_map_r1.txt` / `a3_timeline_r1.txt`：组件探针映射、路由时间线 logcat（A-3 定位证据）
- `a3_baseline_r4.pftrace.gz`：A-3 基线代表 trace（含代表长帧 **frame_token=537421352**，
  dur=207.2ms；对应 doFrame 197.5ms，对账 Composition 160.0 + 布局 30.3 + 绘制 3.2 + 杂项 4.0 ✓）
- `e2_far120_r1.pftrace.gz`：E2 早期代表 trace
- `e2_traces/`：**E2 全 12 轮压缩原始 trace**（base/e2 × far/adj × 60/120 × 3 中 e2_clean 全部）
- `vivo_traces/`：**vivo V2352A 全 36 轮压缩原始 trace**（base_clean/e1_clean/e2_clean ×
  far/adj × 60/120 × 3，2026-11-19 补测；采集配置为从 trace 内嵌提取的 TraceConfig，
  与 Redmi 采集同源）
- `manual_acceptance/`：**人工手指验收录屏**（负责人 2026-11-19 双机实操，共 7 段：
  Redmi 1 段 + vivo 首录 1 段 + vivo 补录 5 段 vivo_supplement_*.mp4 + base 对照 2 段
  base_control_*.mp4；补录覆盖快速连点/减少动画/TalkBack/线框疑点/慢速拖拽跟手；
  逐项复核、两项不通过与 base 对照归因结论见 A-4 报告 §5）
- `a4_traces/`：**A-4 全部其余压缩原始 trace 入库**（base_clean/e1_clean/e1a/e1b 各 12 轮 +
  r2a/r2v5 干净对照各 3 轮，共 54 个 .pftrace.gz）——E1 逐轮原始 trace 可访问归档，
  后续可独立复算 E1/E2/V5 复测全部数值
- `stats/`：完整统计输出（可访问归档）
  - `r3_recompute.txt` / `e1_full_stats.txt`：R3 版全量重算输出（历史记录）
  - `r31_a3_v1v5.txt` / `r31_a4_all.txt` / `r31_e1_full.txt` / `r31_e2_full.txt` /
    `r31_vivo_all.txt`（vivo 12 组，2026-11-19 补测）：
    **R3.1 现行口径**重算输出（唯一帧 + NULL 三分 + 显式刷新率 + n/占比/窗口 + jank/present/on_time 分布）
- `patches/`：V1–V5 累积消融可复现 patch 集（含构建细节与基线 SHA）
- `SHA256SUMS.txt`：**全部实际交付文件逐个实算哈希**（仓库内相对路径）+
  外部归档登记（C:/tmp 未压缩原始 trace 与 APK，注明不在仓库）

## R3.1 统计口径（scripts/a3_stats.py、a3_frame_detail.py 可复算）

1. **唯一帧**：`actual_frame_timeline_slice` 按 (frame name, layer_name, upid) 唯一化；
   统计对象＝**应用图层帧记录（per-layer surface frame）**，非 app 级逻辑帧；
   android_frames（app 级）仅作对照计数输出。
2. **NULL 三分**：on_time_finish = on_time(true)/late(false)/unknown(null)；
   jank_type 区分 `'None'`（真实无 jank）与 NULL（数据缺失）；present_type 保留真实枚举 + unknown。
3. **刷新率逐 trace 显式声明**（文件名 token 或 --hz，缺失/冲突即报错，无默认值；
   组内混合 60/120Hz 直接报错）。**平台事实（Redmi 2407FRK8EC / MTK，2026-11-18 现场验证）**：
   面板刷新率与渲染率解耦——锁定 60Hz 时 `min/peak_refresh_rate=60` 持续生效
   （App 内连续滑动期间 `dumpsys display` 解析 RefreshRate=60.0），但 renderFrameRate=120，
   app/SF 以 120Hz 渲染、present 处大量 Dropped Frame；ActiveModeFps/RenderRateFps 为
   渲染侧模式投票、VSYNC-sf 为合成节奏，**均≠面板率**，trace 内容无可靠面板率信号，
   故面板 Hz 以采集配置显式声明为准（已现场验证该配置生效）。
4. **阈值表述拆分**：超单帧预算(>1×)／帧耗时超两倍预算(>2×)／严重长帧(>50ms)／极端长帧(>100ms)；
   「超两倍预算」不等于「错过一次刷新」。真实呈现迟到以 present_type（Late Present/
   Dropped Frame）+ on_time_finish=false + jank_type（App Deadline Missed）组合陈述；
   android_frames_overrun 在本 trace_processor 版本不可用（如实记录）。
5. **进程检查**：每 trace 输出目标进程数量/upid/pid/多实例（重启）/是否纳入；
   无目标进程或多实例进入异常清单，绝不静默取第一个 upid 或静默跳过。
6. **分母完整**：每组输出唯一帧 n、阈值原始帧数+占比、帧覆盖窗口/trace 时长、代表 trace、
   on_time/late/unknown 与 jank_type/present_type 分布。

## APK 说明（修正版）

- `a3r2_v5_clean.apk`（V5 干净对照构建）**不在仓库**，归档于 `C:/tmp/cr30_a2/apks/`，
  SHA256 已录 `SHA256SUMS.txt` 外部归档段。
- 原 V5 探针版 APK 未留存，可按 `patches/V5.diff` + 基线 8babcba 复现。
- A-4 构建（base_clean/e1_clean/e1a_clean/e2_clean 等）归档于 `C:/tmp/cr30_a4/`，
  哈希登记于 `SHA256SUMS.txt` 外部归档段。

## 复算方式

```
python scripts/a3_stats.py <组名> [--hz 60|120] <trace...>   # 解压 a4_traces/ 或 e2_traces/ 后指向 .pftrace
python scripts/a3_frame_detail.py <trace> [nth]              # 代表帧精算与对账
```

（统计对象与一致性检查逐 trace 输出；异常清单随输出附列。）
