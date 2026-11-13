# TODO · 当前待办（单一来源）

> 本文件是**待办的唯一来源**；`docs/项目文档地图.md` 第 2 节只链接到这里，不再复制清单。
> 最后核对：2026-11-13 13:50（CR-11 三层容器变换转场完成并录屏验证；转场录屏抽帧已工具化；311 测全绿；13:10 已推送 39 个提交）。README 冻结，仅 4 行路径例外。
> 文档归类后的目录说明见 `docs/项目文档地图.md` §6；历史评审与交付档案分别在 `CODE_REVIEW.md`、`WRAPUP.md`。

## 已完成（2026-11-13 13:50）

- [x] **转场录屏 / 抽帧工具脚本化**：`scripts/capture_anim.py` —— 一次调用完成唤醒设备、自动定位入口按钮、录「打开二级页 → 返回」、生成全流程 / 进入段 / 返回段联系表 + 压缩视频，`--slow` 可临时双向慢放并在 `finally` 中无条件还原代码与设备设置。零参数与 `--slow` 两种模式均已真机实跑，产物无黑格、往返完整无误，`git diff` 为空、`stay_on` 还原。调用命令见文末「工具速查」。

- [x] **CR-11 三层容器变换转场**（负责人提供 Claude 方案）：底板 / 入口图标 / 内容三层，底板仅在入口附近渐入渐出、图标放大到约一半淡出、内容交叠淡入；圆角反算、LinearOutSlowIn 进 320 / 出 380ms、起点缩放下限 0.01。文档先行已改需求 CR-11 / §5.5 / §7-22；录屏抽帧验证通过（含慢放与退场中点屏不飘）。

- [x] **Step 7**：顶栏三按钮（CR-10 终态，日期 / 三色圆点主题 / 设置，二级页不加）、导航胶囊质感（92% 白 + 描边 + 阴影 + 线性图标）、首屏遮挡复核（112dp 足够，不加留白）。提交 `334e53e`；顺带修复 `b2852d4` 主题弹层单色渐变崩溃。

## 当前优先（负责人 2026-11-13 裁定：先收口 UI 与同步）

- [x] ~~**Step 7**：导航胶囊质感 + 首屏遮挡复核 + 顶栏三按钮。~~（已完成，见上方）
  - [x] ~~**先复核再改 padding**：~~ 复核结论：滚到底「查看全部应用」bottom=2181px < 导航胶囊 top≈2400px，`112dp + navigationBarsPadding()` 已足够；首屏重叠是固定覆盖层与长内容自然交叠，未加留白，已写进 `ScrollablePage` 注释。
  - [x] ~~恢复顶栏三枚圆按钮~~：CR-10 文档先行已完成；三色圆点自绘、两处入口共用全局 `StyleSheet`、二级页仅返回按钮，真机验证通过。
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

- [x] ~~**CR-09 ③ 转场**~~：已由 CR-11 三层方案取代并录屏验证（`screenrecord` + ffmpeg 抽帧）：打开「图标放大到一半变页面再放大到全屏」、关闭「缩成带齿轮的小块再收回右上角」、退场中点屏不飘、无闪帧。

## 工程状态

- [ ] **2026-11-13 13:10 已推送 39 个提交到 `origin/main`**（负责人授权）；CR-11 之后的新提交待推，**实时数以 `git status -sb` 为准**。推送由负责人执行。

## 工具速查（常备，不排期）

```bash
# 零参数：自动选唯一在线设备，正式速度录一次完整往返
python scripts/capture_anim.py

# 看不清动画：双向慢放（临时改常量→构建→录→自动还原重装）
python scripts/capture_anim.py --slow

# 系统桌面原生动画参考：先把手机停在含目标图标的桌面页，按 HOME 返回桌面
python scripts/capture_anim.py --launcher
python scripts/capture_anim.py --launcher --slow   # 5x 系统动画缩放，自动还原

# 多台设备 / 手动指定入口坐标 / 只录进入
python scripts/capture_anim.py -s GAQOHMNFQWFUPBQO --name cr11_recheck
python scripts/capture_anim.py --tap 1077,263 --no-back
```

桌面模式不启动屏迹、不按 HOME 初始化（避免翻页丢失图标），返回默认用 HOME 键（键码 3），`--label` 可按图标描述改目标。实测 MIUI Launcher 的启动/返回动画不受 `window/transition/animator_animation_scale` 控制，`--slow` 在桌面模式效果有限；看原生过渡请直接看正常速度的 `enter.png` / `exit.png`。

产物默认在 `C:/tmp/screentrace-capture/<名称>/`：`full.png`（全流程联系表）、`enter.png`、`exit.png`、`clip.mp4`、`raw.mp4`。脚本用 `uiautomator dump` 定位按钮（跨机型不写死坐标），并按录屏**真实时长**收敛抽帧窗口（`screenrecord` 静止段不写帧会导致末尾黑格）。

---

**完成定义**：每步一个提交并写清编号；改代码前先对齐需求文档；新增测试要能通过变异验证（改坏实现必须变红）；UI 改动真机截图/`uiautomator dump` 复核；不修改 `README.md`（2026-11-13 仅获一次性例外修 4 行失效路径，此后仍冻结）。
