# 屏迹 / ScreenTrace —— Android 工程骨架

> 依据 `docs/需求文档-屏幕使用时间App.md`（Kimi 更新后的 **v2.3 统一设计系统版**）生成，
> 对应文档 §6 实施顺序的**第 ①② 层**。生成时间：2026-11-10。

## 这是什么 / 不是什么

**是**：一个可以直接用 Android Studio 打开、Sync、Run 的最小 Gradle + Jetpack Compose 工程。
已经落地的只有「契约层」：语义色板 + 九套主题 × 深浅色 + 字体/卡片 token +
共享 `DateSelection` + 领域计算器 + `UsageRepository` 接口 + 空态实现 + 7 个 JVM 单测。
首屏可点击切换九套主题和深色模式，用来验证主题链路真的通了。

**不是**：一个做完的 App。Shizuku 拉取、解析算法、Room、四个数据页面、设置页、
JSON/CSV 导出、§5.10 云同步都**还没实现**，工程里也没有任何假业务数值。
文档 §6 明确写了「不得先复制 HTML 占位数值再假装完成数据接线；未完成能力明确列为待办」，
所以首屏是一张诚实的状态卡，不是四页图表的空壳。

## 直接打开

1. Android Studio → `File → Open` → 选 **`E:\project\ScreenTrace`**（不要选到 `app` 子目录）；
2. 弹出提示时点 `Trust Project`，等 Gradle Sync 自动跑完；
3. 真机连上后点 `Run 'app'`。首屏会显示「屏迹 / 契约骨架 v0.1」，点色块可切九套主题。

跑单测：右键 `app/src/test/java/.../UsageCalculatorTest.kt` → Run，
或在工程根目录执行 `gradlew.bat :app:testDebugUnitTest`（7 个用例，不需要真机）。

## 首次 Sync 前的三个检查点

| 检查 | 说明 |
|---|---|
| **SDK Platform 版本** | `app/build.gradle.kts` 里 `compileSdk = 34` / `targetSdk = 34`。若 SDK Manager 装的是 35/36 而没有 34，把这两行改成已安装的版本即可（§6 要求 ≥34）。 |
| **Gradle 下载慢** | `settings.gradle.kts` 已预置阿里云 Maven 镜像；`gradle-wrapper.properties` 的 `distributionUrl` 仍指向官方。卡住时把域名换成 `https://mirrors.cloud.tencent.com/gradle/`，保留文件名 `gradle-8.7-bin.zip`。 |
| **JDK（Gradle JDK）** | AGP 8.5 官方表格里的 17 是**最低/默认版本**，不是「只准 17」；本工程用的 **Gradle 8.7 明确支持 Java 21 运行**（Gradle 8.5 起支持）。所以 AS 自动下载的 JBR 21 **直接用即可，不必降级**。工程里 `jvmTarget = "17"` 是生成字节码的目标版本，与运行 Gradle 的 JVM 是两件事，不冲突。只有出现 JDK 相关构建错误时才切回 17（见「常见故障」）。`gradle/wrapper/gradle-wrapper.jar`、`gradlew`、`gradlew.bat` 已随工程放好，不用自己生成。 |

## 目录结构

```
ScreenTrace/
├── settings.gradle.kts            插件/依赖仓库（国内镜像优先）
├── build.gradle.kts               顶层插件版本
├── gradle/wrapper/                Gradle 8.7 wrapper
├── app/
│   ├── build.gradle.kts           模块配置 + 依赖
│   └── src/main/
│       ├── AndroidManifest.xml    PACKAGE_USAGE_STATS + 仅同步用 INTERNET
│       ├── res/values(-night)/    App 名「屏迹」、启动主题
│       └── java/com/screentrace/app/
│           ├── MainActivity.kt        骨架首页（主题切换 + 状态卡 + @Preview）
│           ├── theme/
│           │   ├── FreshPalette.kt      §5.4 语义 token + 九套主题 × 深浅
│           │   └── ScreenTimeTheme.kt   §5.3 字体/形状 + §6 要求的主题入口
│           ├── contract/
│           │   ├── Models.kt            §4 四张表的领域模型
│           │   ├── DateSelection.kt    §5.6 共享日期状态（Day / Range）
│           │   ├── UsageRepository.kt  数据层契约 + 结果类型
│           │   ├── UsageCalculator.kt  §5.6 聚合 / §5.7 阈值 / §5.3 格式
│           │   └── EmptyUsageRepository.kt  空态实现（无假数据）
│           └── uikit/legacy/      8 个参考组件，包名已统一，头部列了整改项
├── app/src/test/                  UsageCalculatorTest（7 个 JVM 用例）
├── docs/                          最新需求文档、HTML 原型、AI 评审简报（副本）
└── reference/                     parse_usage3.py、report_data.json、两个原始切片
```

`docs/` 与 `reference/` 是**副本**，源在 `E:\project\屏幕使用时间App\`。
需求文档继续由 Kimi 更新时，记得把新版同步回 `docs/`，否则工程里的实现依据会过期。

## 与需求文档的对应

| 需求条款 | 工程里的位置 | 状态 |
|---|---|---|
| §5.3 字体 token | `theme/ScreenTimeTheme.kt` → `FreshType` | 已实现 |
| §5.2 24dp 无描边卡片、4dp 间距基数 | `FreshShapes` / `MainActivity.InfoCard` | 已实现 |
| §5.4 / §6 语义色板 `FreshPalette` + `ScreenTimeTheme(variant, darkTheme, dynamicColor)` | `theme/` 两个文件 | 已实现（深浅对比度仍需逐套校验） |
| §5.4 九套主题色值 | `ThemeVariant` 枚举，逐行抄自 §5.4 表格 | 已实现 |
| §5.6 共享 `DateSelection`、区间上限 366、反选交换 | `contract/DateSelection.kt` | 已实现 |
| §5.6 区间日均只除有记录日、缺失日≠真实 0 分钟 | `UsageCalculator.summarize/dailyPoints` + 单测 | 已实现 |
| §5.7 月/年视图色档、22:00–06:00 时段 | `UsageCalculator.monthLevel/yearLevel/periodBuckets` | 已实现（纯计算，未画 UI） |
| §6 Repository → ViewModel → UiState | `UsageRepository` 接口 | 契约已定，ViewModel 待建 |
| §2–§4 Shizuku / 解析 / Room | `EmptyUsageRepository` 占位 | **待办** |
| §5.2 / §5.5 通用组件与四页 | `uikit/legacy/` 起点 | **待办**，须按文件头整改 |
| §5.8 设置、§5.10 导出与云同步 | — | **待办** |
| §7-5 golden fixtures（M:SS、去重、跨零点） | — | **待办**，见下方说明 |

## 几个刻意的取舍

- **没引入 Room / KSP / Shizuku 依赖**：Room 注解处理器要与 Kotlin 版本严格对齐，是首次 Sync 最常见的失败点。契约层因此写成纯 Kotlin 接口，`app/build.gradle.kts` 里留了注释版依赖清单，接数据层时再启用并加 `com.google.devtools.ksp` 插件。
- **`INTERNET` 权限已声明但没用**：v2.3 §6 允许「仅同步必需的 INTERNET 权限」，骨架阶段不发任何网络请求。实现 §5.10 时仍要遵守「仅手动或按频率自动触发」。
- **没有 `FakeRepository`**：文档 §6 禁止用假数据掩盖未完成接线。UI 迭代期若需要，请单独建 `app/src/debug/` 下的实现，交付前必须移除。
- **`uikit/legacy/` 里的 8 个 `.kt` 是待整改参考**：它们仍有硬编码颜色、不支持深色，且 `CapsuleBarChart` / `HeatmapCalendar` / `MonthCalendar` 与 §5.7 规格有差距。包名已从 `com.example.screentime.ui` 统一改为 `com.screentrace.app.uikit.legacy`，旧版单套主题函数改名为 `LegacyScreenTimeTheme` 以免和新的 `ScreenTimeTheme` 混淆。
- **包名 `com.screentrace.app`**：`applicationId` 一旦上架不可改，要换现在换。改名时同步修改 `namespace`、`applicationId`、所有 `.kt` 的第一行（含 legacy 8 个）与目录层级。
- **版本组合**：Gradle 8.7 / AGP 8.5.2 / Kotlin 1.9.24 / Compose Compiler 1.5.14 / Compose BOM 2024.06.00 / core-ktx 1.13.1，全部为稳定版，不使用实验性 Expressive API（§6）。

## 建议的下一步

1. 先 `git init && git add . && git commit -m "chore: ScreenTrace 契约骨架"`，之后每次交给 AI 改代码前都留一版基线，用 `git diff` 复核；
2. 跑一次 `UsageCalculatorTest`，确认 Sync 和测试链路都通；
3. 再按文档 §6 的固定顺序推进：通用组件整改 → 四个数据页面（用 `EmptyUsageRepository` 的空态先跑通布局）→ 数据层真实实现 → 设置与 §5.10 → 测试；
4. 解析器测试要用**新建的 golden fixtures**，不要改动 `reference/sample-*.txt`（§6、§7-5 明确要求原切片不可修改，且它们不能单独复现完整 `report_data.json`）。

## 常见故障

| 现象 | 处理 |
|---|---|
| `SDK platform 34 not found` | SDK Manager 勾装 Android 14 (API 34)，或把 `compileSdk/targetSdk` 改成已装版本 |
| Gradle 下载卡住 | 按上文改 `distributionUrl` 为腾讯云镜像；Maven 依赖已走阿里云 |
| Gradle JDK 显示 21，担心与 `jvmTarget = "17"` 冲突 | 不冲突：21 是跑 Gradle 的 JVM，17 是产物字节码目标。先照常 Sync；若报 `Unsupported class file major version 65`、R8/D8 或 Kotlin 编译器异常，再到 `Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK` 选 17（没有就 `Download JDK` 装 Temurin 17），Apply 后重新 Sync。不要在 `gradle.properties` 里写 `org.gradle.java.home` 硬编码绝对路径 |
| 命令行构建与 IDE 用的 JDK 不一致 | IDE 构建走 `Gradle JDK` 设置，Terminal 里的 `gradlew.bat` 走 `JAVA_HOME`；两者可以不同。用 `gradlew.bat -v` 看 Terminal 实际用的 JVM |
| `Duplicate class` / Kotlin 版本冲突 | 确认 `composeOptions.kotlinCompilerExtensionVersion = 1.5.14` 未被改动 |
| Sync 成功但 Run 报 `ClassNotFound` | 先 `Build → Clean Project` 再 Sync；确认打开的是工程根目录而不是 `app/` |
| 想看视觉参考 | 浏览器打开 `docs/screen-time-ui-tomato.html`；截图在 `E:\project\屏幕使用时间App\ui-reference\tomato\` |

—— 完 ——
