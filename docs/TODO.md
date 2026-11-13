# TODO · 当前待办（单一来源）

> 本文件是**待办的唯一来源**；`docs/项目文档地图.md` 第 2 节只链接到这里，不再复制清单。
> 最后核对：2026-11-13 10:48（与代码 / git / 真机状态逐项对账：310 例实跑全绿、§7 验收 42 条连续、CR-10 条款落地属实、6 份文档归类后结构完整；README 冻结，仅 4 行路径例外）。
> 文档归类后的目录说明见 `docs/项目文档地图.md` §6；历史评审与交付档案分别在 `CODE_REVIEW.md`、`WRAPUP.md`。

## 当前优先（负责人 2026-11-13 裁定：先收口 UI 与同步）

- [ ] **Step 7**：导航胶囊质感 + 首屏遮挡复核 + 顶栏三按钮。
  - [ ] **先复核再改 padding**：`ScrollablePage` 已有 `112dp + navigationBarsPadding()`；真机仍有首屏遮挡感，需要用截图 / `uiautomator dump` 判断是 viewport、覆盖层还是内容布局问题，不得盲目叠加留白。
  - [ ] 恢复顶栏三枚圆按钮「日期 / 主题 / 设置」（主题在中间，三色圆点自绘图标）。CR-10 已先行改需求 §5.2/§7-36 与规格 §4.9；两处入口共用 AppNav 全局 `showStyleSheet` 的同一个 `StyleSheet`；二级页仍只保留返回按钮；48dp 点击区 + `contentDescription`。
- [ ] **今日页近 7 天迷你柱（验收 37 漏项）**：当前 `TodayScreen` 未实现；需求为强制项，规格已修正。复用统计趋势同源数据，无记录日不伪造数值，单独小提交补齐。
- [ ] **8b-2 自动同步生产接线**：`SyncManager.uploadAuto/shouldAutoSync` 已有测试，但 `AppContainer.pullCoordinator` 未接 `onSuccess`，生产代码无调用方。接入 App 内手动/自动拉取与 WorkManager 成功链路，补「仅 Wi-Fi」约束与手动下拉联动实测。
- [ ] **Step 9**：深色模式 + 九套主题逐套巡检（深色此前未逐项验证，因 `darkTheme` 由 App 内控制、切系统夜景不重组）。

## 功能批次

- [ ] 8b-2 剩余：Google Drive provider、OAuth 登录/退出、Google 不可达不影响 WebDAV
- [ ] 批 10b：年视图色阶跟随低使用阈值 `x`（负责人已裁决；contract → Room/Empty → ViewModel → UI，月视图仍按每日目标比例）
- [ ] 批 11：提醒与限额
- [ ] 批 12：Glance 小组件
- [ ] 批 13：多机型适配
- [ ] 14b：AI 分析（默认关闭）
- [ ] 14a 余项：PNG 长图
- [ ] 批 9：§7 **42 条**验收逐条签核收尾

## 验证补齐

- [ ] **CR-09 ③ 转场**：录屏核对「关闭两段式」观感与「退场动画中点屏不飘」（当时设备被转用未验）；`adb shell screenrecord` + ffmpeg 抽帧。

## 工程状态

- [ ] 本地提交未推送（累计 30+，**实时数以 `git status -sb` 为准**），`origin/main` 停在 `7650966`；推送由负责人执行。

---

**完成定义**：每步一个提交并写清编号；改代码前先对齐需求文档；新增测试要能通过变异验证（改坏实现必须变红）；UI 改动真机截图/`uiautomator dump` 复核；不修改 `README.md`（2026-11-13 仅获一次性例外修 4 行失效路径，此后仍冻结）。
