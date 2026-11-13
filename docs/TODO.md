# TODO · 当前待办（单一来源）

> 本文件是**待办的唯一来源**；`docs/项目文档地图.md` 第 2 节只链接到这里，不再复制清单。
> 最后核对：2026-11-13 09:40（310 例单测全绿 / §7 验收 42 条 / README 冻结，仅 4 行路径例外）。
> 文档归类后的目录说明见 `docs/项目文档地图.md` §6；历史评审与交付档案分别在 `CODE_REVIEW.md`、`WRAPUP.md`。

## UI 视觉升级（CR-07，按规格 §7.2 的 Step 编号）

- [ ] **Step 7**：导航胶囊 + 底部 contentPadding（首屏末尾被导航遮住 2–3 行）。
  - [ ] 顺带恢复顶栏三枚圆按钮「日期 / 主题 / 设置」（负责人 11-13 偏好，主题在中间用三色圆点图标）。
    - 前置：按文档先行**立 CR 改需求 §5.5 与验收 36**（现写「仅两枚」），同步文档地图冲突区。
    - 实现：`AppHeader` 恢复 `onStyleClick`，两处入口共用 AppNav 全局 `showStyleSheet` 的同一个 `StyleSheet`；只在三个主页面加，二级页仍单返回按钮；图标自绘不引依赖；48dp 点击区 + `contentDescription`。
- [ ] **Step 9**：深色模式 + 九套主题逐套巡检（深色此前未逐项验证，因 `darkTheme` 由 App 内控制、切系统夜景不重组）。

## 功能批次

- [ ] 8b-2：Google Drive provider + 自动同步联动
- [ ] 批 11：提醒与限额
- [ ] 批 12：Glance 小组件
- [ ] 批 13：多机型适配
- [ ] 14b：AI 分析（默认关闭）
- [ ] 14a 余项：PNG 长图
- [ ] 批 9：§7 **42 条**验收逐条签核收尾

## 口径待裁决

- [ ] **年视图色阶跟随可配阈值 x**（CR-05 遗留）：需求 §5.7 第 4 条写 `x / 2x / 3x / 4x`，但 `UsageCalculator.yearLevel` 写死 120/240/360；跟随 x 需改 `UsageRepository.yearLevels` → `RoomUsageRepository` / `EmptyUsageRepository` → `AppViewModel` 整条 contract 链，超出 VI「不动数据口径」边界，待负责人裁决后另开批次。

## 验证补齐

- [ ] **CR-09 ③ 转场**：录屏核对「关闭两段式」观感与「退场动画中点屏不飘」（当时设备被转用未验）；`adb shell screenrecord` + ffmpeg 抽帧。

## 工程状态

- [ ] 本地提交未推送（累计 30+，**实时数以 `git status -sb` 为准**），`origin/main` 停在 `7650966`；推送由负责人执行。

---

**完成定义**：每步一个提交并写清编号；改代码前先对齐需求文档；新增测试要能通过变异验证（改坏实现必须变红）；UI 改动真机截图/`uiautomator dump` 复核；不修改 `README.md`。
