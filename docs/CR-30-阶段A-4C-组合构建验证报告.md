# CR-30 阶段 A-4-C：E1+E2 组合构建验证报告

> **性质**：GPT 终审指令任务 A-4-C。独立组合分支 `exp/a4c-combined`（基于 main `fb46cf1`），
> **不直接修改 main**；E1 与 E2 两个已审查最小 diff 原样组合，零夹带（未动导航架构、Pager、
> 动画架构、页面结构；Pager 预加载、CR-28 全边界零触碰）。
>
> 设备：vivo V2352A（Redmi 被取走、不在位——冒烟以 vivo 为准，如实记录阻塞，不外推）。

## 1. 组合分支与完整 diff（交付物 1）

- 分支：`exp/a4c-combined`（已推远端），三笔提交：
  - `95167cc`（cherry-pick 自 exp/a4-e1 `42092ca`）：AppHeader `tabProgress.value` 读取下沉
    `graphicsLayer` 绘制期，组合期不再消费高频状态（ScreenScaffold.kt，+6/−9）。
  - `7e0e345`（cherry-pick 自 exp/a4-e2 `2ffd60e`）：FreshNavBar 三组备用动画仅
    `progress == null`（Preview/备用）分支创建，跟手分支由 proximity/lerp 派生
    （FreshNavBar.kt，+30/−22）。
  - `db2d1c0`：A-4-C 回归测试（+132）。
- **完整 diff**：`git diff main...exp/a4c-combined` 可复算；业务代码 diff 恰好两文件
  **36+/31−**（ScreenScaffold.kt 15 行、FreshNavBar.kt 52 行），无任何其他改动。

## 2. 构建结果（交付物 2）

`./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL**（JDK 21 / jbr-21.0.11）。

## 3. 全量 Gradle 测试（交付物 3）

`./gradlew :app:testDebugUnitTest --rerun-tasks` → **606 项全绿，0 失败 0 跳过**
（598 项既有基线 + 8 项新增；81 个测试类）。

## 4. 测试明细与变异验证（交付物 4）

**E1/E2 原有相关测试**（全绿）：NavProximityTest、TabMotionTest、ContainerTransformMathTest、
HeaderActionsTest、TabScrollGuardTest、AppViewModelSelectMainTest 等既有用例未受组合影响。

**新增回归测试** `A4cCombinedRegressionTest`（8 项）：
- E1 契约：`tabProgress.value` 读取必须位于 `graphicsLayer` 绘制期；组合期不得重建
  `titleShiftPx`（高频读取）；热路径 `Log.d("TabNav")` 不得恢复；签名/Crossfade 结构不变。
- E2 契约：三组 `animate*AsState` 仅允许存在于 `progress == null` 分支（跟手分支零动画）；
  expand/container/content 必须由 proximity/lerp 派生（宽度/颜色算法不变）；TalkBack
  `contentDescription` 语义与 reduceMotion 降级（widthSpec/colorSpec）不变。
- 数学回归：`navProximity`、`TabMotion.titleShiftDp`（落定归 0、线性、clamp）。

**变异验证（3/3 命中目标断言，改坏→红→无条件还原）**：

| 变异 | 破坏点 | 命中断言 |
|---|---|---|
| MUTATION-1 | E1 读取改常量（模拟未下沉） | e1_tabProgress读取位于graphicsLayer绘制期内 |
| MUTATION-2 | E2 `expand = proximity` 改常量 | e2_跟手分支由proximity派生且算法不变 |
| MUTATION-3 | 跟手分支混入 animateFloatAsState | e2_备用动画仅存在于progressNull分支 |

## 5. 组合构建安装包（交付物 5）

- `evidence/a4c/a4c_combined.apk`（13,200,676 字节）
- **SHA256：`DDF3BDB50B88E109448BCE44A846A82E41B0BDB27BDB9D3B549679A3D2C8D3C9`**
- 同一构建产物亦归档 C:/tmp/cr30_a4c/a4c_combined.apk（字节一致）。

## 6. 真机冒烟记录（交付物 6，vivo V2352A；Redmi 不在位）

装包→启动→横滑/点击/二级页进出全程无崩溃、ANR。四段录屏归档
`evidence/a4c_smoke/`（a4c_interact / a4c_rapid / a4c_reduce / a4c_talkback）。

**交互回归**：

| 项 | 结果 | 证据 |
|---|---|---|
| 横滑标题位移连续 | **通过** | a4c_interact：位移+淡入淡出随进度平滑、无跳变 |
| 点击 Tab 标题落位正确 | **通过** | a4c_interact：落位准确，内容/选中态一致 |
| FreshNavBar 宽度/颜色/跟手 | **通过** | a4c_interact：胶囊随手指进度平滑移动变形 |
| Preview/备用动画路径保留 | **通过（代码契约锁）** | 回归测试断言 animate* 保留在 progress==null 分支 |
| TalkBack 名称/角色/选中态 | **测试无效，待重测** | a4c_talkback 录屏显示点按直接切页=TalkBack 服务疑未真正激活（非产品结论）；语义代码契约由回归测试锁定且 E1/E2 构建人工实测通过（11-19 supplement_4） |
| 快速连点不得比 base 更严重 | **通过（优于 base）** | gfxinfo 同节奏同条件：组合 322 帧/2.17% vs base 328 帧/4.27%；a4c_rapid 定向复核：末 3 秒标题干净无叠加、切换全程正常（详见 §7） |
| 减少动画二级页闪白不得比 base 更严重 | **通过（与 base 同类）** | a4c_reduce：通知打扰（ContainerTransform 路径）白块闪现「明显」、设置页干净——与 base_control_reduce 同现象同程度，未新增恶化 |

## 7. 两项 base 既有缺陷的同条件对照

1. **快速连点转场卡顿及状态不同步**（base 既有，A-5 目标缺陷）：
   - 客观同条件对照（同设备/同节奏 12 次交替点击/gfxinfo）：**组合 2.17% Janky（7/322）
     优于 base 4.27%（14/328）**，Missed Vsync 1 vs 4、Slow UI thread 1 vs 8——未更严重。
   - 首轮录屏复核曾报「标题叠加残留/响应停滞」，与帧统计矛盾；**定向复核已澄清推翻**：
     末 3 秒标题干净（「统计」+副标题，无叠加/重影）、点击期间标题切换正常、后 7 秒
     静止为点击停止后的正常空闲（非卡死）——首轮误读源于时间线错位（点击仅前 5 秒）与
     空闲画面误判。**该项收口：未比 base 更严重（客观指标反优）。**
2. **减少动画后二级页闪白**（base 既有）：组合复现同现象（通知打扰白块闪现、设置页正常），
   **程度与 base 相当、未更严重**。机制指向 ContainerTransform 减少动画降级路径渲染空白
   容器初始态（与 A-4 §5 归因一致，属独立缺陷跟进项）。

## 8. 冻结边界自查

- Pager `beyondBoundsPageCount` 零改动；CR-28 数据架构/数据库/备份口径零触碰；
  无 AppNav 重构、无整页 Crossfade 掩盖、无隐藏页面/延迟业务状态；V5 空页面未复用；
  Tab 直达动画未实施（与 A-5 路由正确性修复严格分变量）。
- 组合分支只含 E1+E2 两文件业务改动 + 1 个测试文件，`git diff main...exp/a4c-combined`
  可逐行核验。

## 9. 遗留（如实记录）

- Redmi 真机冒烟/交互回归：设备被取走，**阻塞待补**（不外推 vivo 结论）。
- TalkBack 有效性重测：a4c_talkback 录屏中 TalkBack 服务疑未真正激活（点按直接切页），
  测试无效；待设备回线后以正确激活方式重录（语义代码契约已由回归测试锁定，E1/E2 构建
  11-19 人工实测曾通过）。
- A-4-C 不自动合入 main，待 GPT 复审裁决。
