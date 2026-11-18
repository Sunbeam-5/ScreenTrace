# A-3 原始证据清单（R2 补编）

> R2 补：\`a3_map_r1.txt\` 入库（原缺失）；完整 SHA256 见 \`SHA256SUMS.txt\`（19 条，
> 含 A-3 全部 trace 与 E2 逐轮 trace）；E2 逐轮统计 \`e2_per_round_stats.txt\`（12 行）
> 与代表 trace \`e2_far120_r1.pftrace.gz\`（5.9MB）补入；V5 探针版 APK 未留存（可按
> patches/V5.diff + 8babcba 复现），R2 干净版 a3r2_v5_clean.apk 已留存并入哈希清单；
> a3_stats.py 已修连续长帧算法（真实时序、不跨 trace），修正后历史数据连续长帧对=0。

## 代表性 logcat
- `a3_map_r1.txt`：组件探针映射（一次 far 切换的完整 tick 序列，Redmi 120Hz，17:11）
- `a3_timeline_r1.txt`：路由时间线 + tick（far 切换，19:28）——R1 修订依据：
  Route/currentPage=1→guardIgnore、Route/currentPage=2→selectMain（滚动中段）、
  AppHeader/FreshNavBar 间隔 ~16ms（120Hz 每 2 帧）、burst 各域时间戳

## 代表性 Perfetto trace
- `a3_baseline_r4.pftrace.gz`（gzip 5.9MB；原文件 22.8MB）
  - **代表长帧 frame_token=537421352**，ts=897391214503556ns，dur=207.2ms（Dropped Frame）
  - 对应 doFrame ts=897391214605402，dur=197.5ms
  - 对账（exclusive 法）：Compose 重组+applyChanges 160.0ms + 纯 measure/layout 30.3ms
    + 绘制记录 3.2ms + 杂项 4.0ms = 197.5ms ✓
- 全量 trace 哈希（原文件在 C:/tmp/cr30_a2/a3raw/）：
  - a3_baseline_r4: 0F1917B74CF4F974…
  - v1_navfreeze_r4: CDBCD47FFDC533EB…
  - v2_hdrfreeze_r4: 1EBD8D9A1B15A6BE…（注：实为 CDB 系续，完整值见本地哈希）
  - v3_overlays_r4: B7B11DDC5E39097B…
  - v4_scrollstrip_r4: 5B021826439646C6…
  - v5_allstrip_r4: 9E14C718B1B739C4…

## 统计口径（scripts/ 内脚本可复算）
- `a3_run.py`：测量协议（force-stop → 预热 6s → gfxinfo reset → [perfetto] → far 切换 ×2 → dump）
- `a3_frame_detail.py`：代表帧 inclusive/exclusive 拆分与对账
- `a3_stats.py`：p50/90/95/99、max、>16.67/33.33/50/100ms 计数、连续长帧
- `a2_compact.py`：长帧分解（UI/RT/SCHED/COMPOSE）
- 帧序列取自 `actual_frame_timeline_slice`（upid 过滤 com.screentrace.app）；
  嵌套扣除法：exclusive = 本层 dur − 直接子层 dur 之和；对账见 README 代表帧行。
