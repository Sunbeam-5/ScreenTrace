# CR-30 A-4-C 最新主线集成验证报告（交付 1）

> 对应 GPT 指令一。集成基线：最新 main **d450530**。原组合分支
> `exp/a4c-combined`（领先 3 / 落后 4）**原样保留**，未强制重置或覆盖。

## 1. 集成方式（可审计）

新建集成分支 `exp/a4c-integrated`（自 main d450530），将组合代码三笔提交
**逐笔 cherry-pick**（不带旧分支的历史报告/证据，避免重复覆盖 main）：

| 原提交（exp/a4c-combined） | 集成提交（exp/a4c-integrated） | 内容 |
|---|---|---|
| 95167cc（源自 exp/a4-e1 42092ca） | **3fd7f0b** | E1：ScreenScaffold 读取下沉 graphicsLayer |
| 7e0e345（源自 exp/a4-e2 2ffd60e） | **8b68613** | E2：FreshNavBar 备用动画条件化 |
| db2d1c0 | **995442f** | A4cCombinedRegressionTest（8 项） |

cherry-pick **无冲突**。

## 2. 最终代码 diff 核查

`git diff main...exp/a4c-integrated --stat`：

| 文件 | 变更 |
|---|---|
| app/src/main/java/com/screentrace/app/uikit/ScreenScaffold.kt | 15 行（+6/−9） |
| app/src/main/java/com/screentrace/app/uikit/FreshNavBar.kt | 52 行（+30/−22） |
| app/src/test/java/com/screentrace/app/uikit/A4cCombinedRegressionTest.kt | +132（新增） |

**业务修改仅限上述三文件**；AppNav、Pager 预加载、CR-28、Room、备份与导航业务语义
零改动（diff 逐行可核）。

## 3. 构建与测试（指令 6/7）

- 环境：Windows，JDK `C:/Users/Administrator/.jdks/jbr-21.0.11`，Gradle wrapper。
- 命令：`gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
- 结果：**BUILD SUCCESSFUL**
- 测试：**总数 606，失败 0，跳过 0**（598 项既有基线 + 8 项 A4cCombinedRegressionTest）
- 无冲突、无测试失败，未删除测试、未放宽断言。

## 4. 集成提交与产物（指令 8）

- 集成分支 HEAD：`995442f`（exp/a4c-integrated，已推远端）
- **APK SHA256：`DDF3BDB50B88E109448BCE44A846A82E41B0BDB27BDB9D3B549679A3D2C8D3C9`**
  （13,200,676 字节）——**与原组合构建 a4c_combined.apk 字节级一致**（确定性构建，
  集成未引入任何代码漂移；同一 APK 已归档 `evidence/a4c/a4c_combined.apk`）。
- 原始 A-4-C 证据可访问性：`evidence/a4c/`（APK×2）、`evidence/a4c_smoke/`（vivo/Redmi
  录屏与截屏）、`evidence/a4c_perf/`（九轮原始 gfxinfo）均在 main，SHA256SUMS.txt
  （179 条）逐条可核。

## 5. 结论

集成验证**通过**：E1+E2 组合代码在最新主线 d450530 上无冲突落位、构建成功、
606 测全绿、产物与已测构建字节一致。**等待 GPT 最终合入裁决**（不自动合入 main）。
