# 屏迹 / ScreenTrace

Android 屏幕使用时间统计 App：**本地优先、离线、无账号**。通过 Shizuku 拉取系统
`dumpsys usagestats`，解析后在本地库中逐日累积，提供今日概览、趋势、日历热力图、
应用排行与解锁/通知下钻。

> 数据只在本机。唯一可选的联网行为是 §5.10 云同步（WebDAV / Google Drive）与
> §5.11 用户主动点击的 AI 分析，两者默认关闭。

## 当前状态

| 批次 | 内容 | 状态 |
|---|---|---|
| 1–5 | 组件库、图表、状态层、四个页面、解析器移植 | ✅ 已完成 |
| 6a–6b | Room + 合并策略 + 解析入库 + Shizuku/WorkManager 接线 | ✅ 已完成 |
| 6d | 解锁详情 / 通知详情下钻页 | ✅ 已完成 |
| 6e–6f | 真机冒烟 + 13 条真机反馈修复 | ✅ 已完成 |
| 6g | 会话区间真并集 ✅；下拉刷新、趋势图规范、点柱看前三、容器变换、口径 1–24h、日历对齐、日期手输、排行单日口径 | 🚧 进行中 |
| 7–14 | 设置/向导/HTML 导出、备份与云同步、自定义主题、提醒与限额、小组件、多机型、周期总结与 AI | ⏳ 未开工 |

已实现：四页骨架（今日 / 统计 / 日历 + 悬浮导航，Pager 横滑）、九套主题 × 深浅色、
日期筛选（单日 / 区间 / 自定义）、应用排行、解锁与通知下钻、设置页（外观 / 统计口径）、
Room 七表 + 会话区间并集、Shizuku 与 UsageStats 双通道降级、WorkManager 每日拉取。

**版本**：`0.1.0`（versionCode 1），包名 `com.screentrace.app`，minSdk 26 / targetSdk 34。

## 技术栈

Kotlin 1.9.24 · Compose BOM 2024.06 · Material 3 · Room 2.6.1 · WorkManager 2.9.0 ·
DataStore 1.1.1 · Shizuku 13.1.5 · Gradle 8.7 / AGP 8.5.2（全部稳定版，不用实验性 API）。

## 构建与运行

环境：JDK 17（`JAVA_HOME` 指向 17 最稳；Gradle 8.7 也能跑在 21 上）、Android SDK Platform 34。

```bash
./gradlew :app:assembleDebug            # 构建 APK
./gradlew :app:testDebugUnitTest        # 跑单测（174 个 JVM 用例，无需真机）
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次运行需安装并授权 [Shizuku](https://shizuku.rikka.app/)；未授权时 App 会降级到
`UsageStatsManager`（只有应用级与时段数据，没有事件流），并在界面上明示降级状态。

## 目录结构

```
app/src/main/java/com/screentrace/app/
├── data/            拉取、解析、Room、合并与入库
│   ├── parse/       UsageDumpParser（checkin + 明文双格式）、EventTypes、DumpShape
│   ├── db/          七张表、DAO、MIGRATION_1_2
│   ├── pull/        PullCoordinator（节流/降级/补读）、PullWorker、两个通道
│   ├── prefs/       SettingsStore（DataStore）
│   └── SessionUnion.kt / MergePolicy.kt / IngestPipeline.kt   并集与合并（纯函数 + 单事务入库）
├── contract/        领域模型、DateSelection、UsageCalculator、仓库接口
├── ui/              AppNav（Pager + 覆盖层）、四个页面、弹层、设置、ViewModel
├── uikit/           卡片、图表、日历、导航条、状态视图等自绘组件
└── theme/           语义色板（九套 × 深浅）、字体与形状 token
app/src/test/        174 个 JVM 用例（Robolectric + 纯函数）
docs/                需求文档（唯一权威）、批次计划、各批复盘与交付说明
```

## 数据来源与已知限制

这些限制来自系统接口本身，不是 App 的 bug，界面上也有对应说明：

- **OEM 历史窗口不同**：小米（HyperOS 3，Android 16 实测）的 `dumpsys usagestats`
  只暴露**最近约 2 天**的按日统计；vivo 可达 10 天以上。系统自带的「屏幕使用时间」
  读的是特权内部接口，第三方拿不到。因此本 App 采用**每日拉取、自建历史库**，用得越久
  数据越完整。
- **事件缓冲是滚动窗口**：单次 dump 只覆盖最近几小时，且过期事件会消失。所以屏幕时长
  不是「单次取大」，而是把每次见到的**亮屏/锁屏会话区间入库做真并集**（`screen_session`
  表）后重算——任何一次拉取见过的时段都会计入，只增不减。
- **解锁次数可能缺失**：系统未上报 `KEYGUARD_*` 事件时，无法得到解锁次数。此时显示
  `—` 而不是 `0`（区分「真实 0 次」与「拿不到」）；若设备开了「不灭屏」，整日只有一条
  连续亮屏会话，`0` 是真实值，会正常显示。
- **应用时长之和可能大于屏幕时长**：含前台服务、画中画与并行窗口，界面上有说明。
- **`QUERY_ALL_PACKAGES`**：为了把包名解析成应用名与图标而声明；上架 Google Play 时
  需要在表单中声明用途，或改为按需查询。

## 文档

- [`docs/需求文档-屏幕使用时间App.md`](docs/需求文档-屏幕使用时间App.md) —— 唯一权威需求（v2.6，含 CR-01~04）
- [`docs/实施任务批次计划-v2.md`](docs/实施任务批次计划-v2.md) —— 执行基线（19 个批次）
- `docs/批次*-交付说明.md` —— 每批的实际产出、验证方式与已知边界

## 路线图

1. **6g 收尾**：趋势图统一量程（0 → min(ceil(max), 24h) + 平均线 + 30 天细柱一屏）、
   点柱看前三名应用、容器变换转场、下拉刷新（CR-04 终态：三页 + 60 秒轻冷却）、
   统计口径 1–24 小时、日历居中、自定义区间手输、排行单日口径；
2. **批 7–8**：设置页补全 + 首启向导 + HTML 报告、JSON/CSV 备份恢复、WebDAV / Drive 云同步；
3. **批 10–14**：自定义主题引擎、提醒与应用限额、桌面小组件、多机型适配、周/月/年总结与可选 AI 分析。

## 许可

仓库暂未指定开源许可证。在添加 `LICENSE` 之前，默认保留所有权利。
