# A-3 二分变体 Patch 集（可复现）

> 应用基线：commit 8babcba（含 A-1 PerfProbe + A-3 共 10 个 tick 探针）。
> 每个变体 = 基线 + 本目录对应 .diff；V2–V5 为**累积叠加**（Vn = Vn-1 的全部改动 + 本变体改动）。
> 应用方式：按 .diff 中 old/new 精确替换（文件均为 CRLF）。测后全部还原，主线零残留。

## 构建清单

| 构建 | 变量 | tick | PerfProbe | 页面内容 | 累积 | 设备/频率 | 时间 |
|---|---|---|---|---|---|---|---|
| A 基线 | 无 | 10 | A-1 全套 | 真实 | — | Redmi 2407FRK8EC / 120Hz | 2026-11-18 17:00–17:50 |
| V1 | FreshNavBar proximity 读取→常量 | 10 | A-1 全套 | 真实 | — | 同上 | 17:20 |
| V2 | +AppHeader titleShiftDp 读取→常量 | 10 | A-1 全套 | 真实 | 含 V1 | 同上 | 17:26 |
| V3 | +PullZone/CTO/DPO 短路 | 10 | A-1 全套 | 真实 | 含 V1+V2 | 同上 | 17:36 |
| V4 | +ScrollablePage 剥离 verticalScroll/横 padding | 10 | A-1 全套 | 真实 | 含 V1–V3 | 同上 | 17:42 |
| V5 | +三页内容换空 Box | 10 | A-1 全套 | **空** | 含 V1–V4 | 同上 | 17:47 |

- APK：C:/tmp/cr30_a2/apks/（a3_v1_navfreeze / a3_v2_hdrfreeze / a3_v3_overlays / a3_v4_scrollstrip 已留存；
  **V5 构建未留存 APK**，按 V5.diff 在基线上复现；A 基线同口径数据以 a3_timeline 构建补测）
- 运行协议：a3_run.py（force-stop → am start → 预热 6s → gfxinfo reset → [可选 perfetto]
  → 固定坐标 far 切换 ×2 轮 → gfxinfo dump）；每构建 3 轮 + 1 轮带 trace。
- 测量时期为 120Hz 锁频；Redmi 2407FRK8EC / Android 16。
