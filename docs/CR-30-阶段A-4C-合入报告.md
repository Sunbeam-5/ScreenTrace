# CR-30 A-4-C 合入报告（任务一，待 GPT 复审）

> GPT 已批准 E1+E2 组合代码合入。基线：main `d7be46f`。合入方式：
> `git merge --no-ff exp/a4c-integrated`（保留已审查提交 `3fd7f0b`/`8b68613`/`995442f`
> 原始 SHA 与审计轨迹）。原始分支 `exp/a4c-combined` 与全部实验材料**未做任何修改**。

## 1. 合入范围核查

`git diff d7be46f..HEAD --stat`：

| 文件 | 变更 |
|---|---|
| app/src/main/java/com/screentrace/app/uikit/ScreenScaffold.kt | +6/−9 |
| app/src/main/java/com/screentrace/app/uikit/FreshNavBar.kt | +30/−22 |
| app/src/test/java/com/screentrace/app/uikit/A4cCombinedRegressionTest.kt | +132（新增） |

**无额外文件、无其他业务变更**；AppNav、Pager 预加载、CR-28、Room、备份、导航业务
逻辑零改动（diff 逐行可核）。

## 2. 合入后测试

`gradlew.bat :app:testDebugUnitTest`（JAVA_HOME=jbr-21.0.11）→ **BUILD SUCCESSFUL**，
**总数 606，失败 0，跳过 0**（598 基线 + 8 项 A4cCombinedRegressionTest）。

## 3. 为何集成构建结果仍适用（指令 6）

自集成构建（`995442f`，2026-11-21 上午）至今，main 的增量为 **纯文档/证据提交**
（`2f9e957` 集成验证报告、`d7be46f` R4 说明补记与自测输出归档），**业务代码零变化**；
本合入后的代码树与已验证的集成分支代码树逐字节等价（同一组 cherry-pick 提交）。
因此 `assembleDebug` 产物与测试结论继续适用——**APK SHA256
`DDF3BDB50B88E109448BCE44A846A82E41B0BDB27BDB9D3B549679A3D2C8D3C9`**（13,200,676
字节）与集成构建字节一致，不为形式重复全部性能实验；合入后仅复跑全量单测确认（上节）。

## 4. 原始产物与记录保留

- APK：`evidence/a4c/a4c_combined.apk`（SHA `DDF3BDB5…D3C9`）、`evidence/a4c/base_clean.apk`
  （SHA `5EED68D5…B95C`）原样保留。
- 全部实验材料（a4c_smoke/、a4c_perf/、manual_acceptance/ 等）未删改；
  SHA256SUMS.txt 历史条目继续有效。

## 5. 状态

合入完成于 main，**等待 GPT 复审**。
