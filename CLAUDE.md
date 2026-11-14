# CLAUDE.md · ScreenTrace（屏迹）项目交接指令

> 给 AI 编程助手（Android Studio 的 Claude 插件等）的项目级指令。人类负责人只做裁决；
> 任何冲突以 `docs/需求文档-屏幕使用时间App.md` 为准。

## 1. 项目与构建
- 路径 `E:\project\ScreenTrace`，包名 `com.screentrace.app`；Kotlin + Compose（BOM 2024.06）+ Room + DataStore + WorkManager。
- JDK 17；`JAVA_HOME="C:/Users/Administrator/.jdks/jbr-21.0.11"`。
- 当前基线：**432 测全绿 / 0 skip**，改完必须保持全绿。

## 2. 先读什么（按顺序，不要凭印象）
1. `docs/需求文档-屏幕使用时间App.md` —— **唯一权威**（数据口径、§5 UI 规范、§7 42 条验收；头部登记了 CR-01~CR-17 全部裁定）
2. `docs/TODO.md` —— 待办唯一来源，当前批次与剩余项都在这
3. `docs/项目文档地图.md` —— 文档角色与进度快照
4. `docs/开发与构建说明.md` —— 构建、签名、真机调试
5. `docs/CODE_REVIEW.md` —— 评审档案，**只读最新补记**；旧结论不代表现状

`README.md` 已冻结，除非负责人明确解除，否则不许改。

## 3. 硬性纪律
- **文档先行**：每批先改需求文档对应章节，再改代码；文档与代码冲突时先修文档。
- **评审只写 `docs/CODE_REVIEW.md`**：不新建评审文档，评审过程不改业务代码，结论追加文末。
- **测试必须能变红**：新测试要做变异验证（改坏实现 → 必须失败 → 无条件还原）。禁止恒真断言。
- **真机只用 Redmi `GAQOHMNFQWFUPBQO`**；vivo `10CEB40M5G001DK` 在跑自动化，**全程禁用**。所有 adb 显式 `-s`。
- **不要 `git push`**：由负责人手动执行（当前环境读 `~/.ssh` 被权限规则拒绝）。本地提交可以做。
- **不要删 `.workbuddy/`**（项目记忆，不是缓存）。
- **不要用 `git checkout --` 撤销未提交改动**（曾误删过未提交代码）。
- 分笔提交：文档一笔、实现一笔、真机收口一笔。

## 4. Compose 红线（踩过的坑）
- 单插槽容器（`Crossfade` / `AnimatedContent` / `Box`）包多根节点页面，必须先包 `Column`。
- **禁止整页 `Crossfade` / `AnimatedContent` 做数据过渡**：会重建子树、重播 `remember { Animatable(0f) }` → 「闪一帧」。过渡只做叶子节点（同一 `Text` 内数值平滑、柱高/色带权重平滑）。
- 数字与单位必须在同一个 `Text`（`AnnotatedString`），不拆两个 Text。
- 二级页转场用 `ContainerTransformOverlay`，入口按钮接 `transformSource`；覆盖层内**不给** `LocalContainerTransformState`（否则返回按钮会把自己记成起点）。
- `HorizontalPager` 是 tab 唯一真相源：点 tab 只 `animateScrollToPage`，`snapshotFlow { currentPage }` 单向回写路由，禁止双向写。

## 5. 当前状态（2026-11-15）
- CR-15 六条反馈全部完成：A0 日期输入闪退 / A1 真日均 + 过渡 / B 日期双状态 / C 多设备数据层 / D 四宫格顶栏 / E 自定义壁纸。
- 批 D：顶栏只留一枚设置按钮，点开下拉一行四枚（日历 tab 三枚）；「切换设备」是下拉内只读设备面板；容器变换起点重登记为被点的那枚圆按钮。
- **P3 三条已完成（2026-11-15，432 测全绿）**：P3-3 删 `normalizeInput` 未用 cursor（`c0cf556`）、P3-2 格式错分支补真覆盖（`d64a4a5`，计划原例 `2026-1-1-1` 实测为 7 位中间态已修正）、P3-1 读屏合并语义（`eb381b0` + `035235c`，真机 dump 发现 clearAndSetSemantics 会清掉 clickable 语义、须在块内补回 `onClick`）。
- 本地有未推送提交，数量以 `git rev-list --count origin/main..main` 为准。

## 6. 下一步（按顺序，动手前先让负责人确认范围）
1. **两条待负责人裁决**：排行页口径、统计侧详情页「选区间当前页不变」。
2. 远期：Google Drive、年视图色阶、提醒限额、Glance 小组件、多机型适配、AI 分析、PNG 长图、§7 逐条签核。

## 7. 常用命令
```bash
JAVA_HOME="C:/Users/Administrator/.jdks/jbr-21.0.11" ./gradlew.bat :app:testDebugUnitTest --rerun-tasks
JAVA_HOME="C:/Users/Administrator/.jdks/jbr-21.0.11" ./gradlew.bat :app:assembleDebug
adb -s GAQOHMNFQWFUPBQO install -r app/build/outputs/apk/debug/app-debug.apk

# 转场/动画验证只用脚本，不要手搓 screenrecord
python scripts/capture_anim.py --slow -s GAQOHMNFQWFUPBQO
python scripts/capture_anim.py --quick-menu --slow -s GAQOHMNFQWFUPBQO   # 顶栏四宫格
python scripts/capture_anim.py --date-switch -s GAQOHMNFQWFUPBQO         # 日期切换
```
- Git Bash 会把 `adb ... /sdcard/...` 转成 Windows 路径（`MSYS_NO_PATHCONVERSION=1` 也不管用）→ 用 Python `subprocess` 直调 adb，或 `adb exec-out` 读文件。
- `capture_anim.py --slow` 结束时只重装 APK、**不重启 App**；之后操作前先 `am force-stop` + `monkey` 重启，否则会点到桌面。
- 变异脚本不要批量删 `test-results/*.xml`（触发安全删除确认）；靠源码变更让 Gradle 重跑即可。
