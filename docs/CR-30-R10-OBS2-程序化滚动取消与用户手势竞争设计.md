# CR-30 · R10-OBS2 程序化滚动取消与用户手势竞争设计

| 项 | 值 |
|---|---|
| 编号 | CR-30-R10-OBS2 |
| 版本 | **v2**（2026-11-23 按 GPT 评审整改；v1 见 §0.5 修订记录） |
| 阶段 | **阶段 A · 只出设计，未实施** |
| GPT 评审结论 | **A1 方向通过**；补齐下列条件后**允许独立实施**（本轮只补设计，不编码） |
| 关联 | `docs/CR-30-A5-R10-TabScrollGuard-所有权设计.md`（P2 已通过并真机验证）、`docs/CR-30-A3-实验补证/evidence/a5_r10_guard_ownership_real/` |
| 基线 | 分支 `exp/a5-route-probe` @ `2824ff0`；全量单测 **620 / 0 fail / 0 skip** |
| 本文档改动 | **无任何生产代码 / Gradle / Manifest / 导航行为改动** |

---

## 0. 背景、结论与修订记录

### 0.1 OBS-2 是什么

R10 真机复核发现并已登记 **OBS-2**：

> **程序化滚动被用户手势打断时，旧协程仍然执行了 `alignRouteToPager()`，把路由对齐到「打断瞬间的过渡页」，造成一次短暂的路由抖动。**

### 0.2 核心问题与本文档的答案

> **程序化滚动被用户手势打断时，旧协程是否有权执行 `alignRouteToPager()`？**

**答案：没有无条件对齐权。** 除「token 命中当前 owner」之外，还必须同时满足：

1. **本次滚动不是被取消的**（`scrollCancel` 路径一律不对齐）；
2. **对齐目标不是「守卫忽略的中间页」**；
3. **对齐发生在 Pager 已稳定之后**（用 `settledPage` 而不是过渡态的 `currentPage`）；
4. **当前代际（generation）仍然有效** —— 新导航开始后，旧对齐器必须失效（v2 新增，见 §6.3）；
5. **所有路由写入都经过唯一入口 `commitRouteFromPager()`**（v2 新增，见 §6.4）。

### 0.3 任务书给定的禁止项（本设计全程遵守）

| 编号 | 禁止项 |
|---|---|
| N1 | 不得改 `HorizontalPager` 架构 |
| N2 | 不得改 `beyondBoundsPageCount` |
| N3 | 不得改主 tab 动画规格（`TabMotion.scrollSpecFor`） |
| N4 | 不得「关动画」绕开问题 |
| N5 | 不得用「延迟固定毫秒」掩盖问题 |
| N6 | 不得用整页 `Crossfade` / `AnimatedContent` 做数据过渡 |
| N7 | 不得重开 R10 原 P2 |
| N8 | 不得把 ADB 合成手势冒充真实手指 |
| N9 | 不得引入第二套独立路由状态（v2 新增，来自 GPT §4.3） |
| N10 | 不得改变 R10 Token 所有权实现（v2 新增，来自 GPT §4.3） |

### 0.4 与 v1 的关系

v1 已经给出正确的**方向**（A1：取消让权 + 稳定后对齐器）与完整的场景矩阵。v2 **不推翻方向**，只补齐 GPT 指出的四个缺口：

| GPT 条目 | v1 的缺口 | v2 的处置 |
|---|---|---|
| §4.1 取消原因 | v1 只区分「取消 / 未取消」，把三种取消原因混为一谈 | §1.5 给出**三类取消原因**与**可靠的识别方式**（不只凭 `CancellationException`），并给出确定性判定顺序与各自处理 |
| §4.2 对齐器所有权 | v1 只说「交给稳定后对齐器」，未写清所有权、代际、失效条件 | §6.3 给出对齐器的 **9 条所有权边界**与**代际（generation）机制** |
| §4.3 统一入口 | v1 有两个路由写入点（`snapshotFlow` 回写 + `alignRouteToPager`） | §6.4 引入唯一入口 **`commitRouteFromPager()`**，所有基于 Pager 状态的路由更新都必须经过它 |
| §4.4 异步竞态测试 | v1 只有纯函数 `shouldAlign()` 的表驱动测试 | §4.11 新增 **10 个异步竞态场景**（每个含 7 项属性 + **中间态**验证）；§7 给出实现方式 |
| §4.5 M1–M10 | v1 已保留 | **完整保留**，并强化「真机复核优先真实手指」与证据等级标注（§8） |

### 0.5 修订记录

| 版本 | 时间 | 变更 |
|---|---|---|
| v1 | 2026-11-23 上午 | 初版：当前时序、真机日志证据、竞态状态图、十场景、7 案对比、推荐 A1、单测设计、真机矩阵、回归要求、回滚 |
| **v2** | 2026-11-23 下午 | 按 GPT 评审整改：① §1.5 取消原因三分类与可靠识别；② §6.3 对齐器所有权 9 条 + 代际机制；③ §6.4 统一入口 `commitRouteFromPager()`；④ §4.11 新增 10 个异步竞态测试场景；⑤ §7 补异步协调器测试实现方式；⑥ §8 强化真实手指优先；⑦ §0.3 新增 N9 / N10 禁止项 |
| **v2.1** | 2026-11-23 晚 | **编码前终审定稿**：① **α 定为唯一方案**（§6.4.3：三个调用点全部经 `commitRouteFromPager()`，附 CR-17 冲突检查 —— 结论**无冲突**，CR-17 不需改；**A-01 关闭**）；② **日志字段语义定稿**（§6.6.1 六个不可混用概念 C1–C6 / §6.6.2 历史字段真实含义与兼容关系 / §6.6.3 打点格式定稿；**`aligned` 的真实含义 = C1 守卫所有权**，不是「对齐成功」）；③ **§1.5.5 三类取消的行为表定稿** + `userDragging` API **尚未验证**的如实声明；④ **§4.11 重写为 12 个场景**（每个含 **7 个必答字段** + 中间态断言 + 日志证据；新增 R11 端到端最终态、R12 组合不卡死；§4.12 断言增至 A1–A11）；⑤ **§8.6 真机证据纪律 E-1~E-10** |

---

### 0.6 v2.1 编码前终审的结论摘要

| 检查项 | 结论 | 位置 |
|---|---|---|
| 统一路由入口 | **α 定为唯一方案**：三个调用点（`snapshotFlow` 回写 / `finally` 对齐 / 稳定后对齐器）全部经 `commitRouteFromPager()`；**写入函数只有 1 个**，触发源有 3 个 | §6.4.3 |
| CR-17 冲突 | **逐条检查，无冲突**；CR-17 条文**不需要改**，本设计不动 CR-17 | §6.4.3.1 |
| A-01 | **关闭**（由 GPT v2.1 裁决 α） | §6.4.3 末 |
| 日志字段 | 六个概念 C1–C6 **分离**；`aligned` 的真实含义**如实标注为「守卫所有权」**；新增 `owned` / `cause` / `stable` / `settledMatch` / `genValid` | §6.6.1 |
| 历史字段兼容 | **没有任何一个历史字段的语义被改变**；`Route/align` 仍打在幂等短路之前 | §6.6.2 |
| 取消原因 | 三分类**保留**；行为表定稿；`userDragging` 的 API **尚未验证**，未验证前不得声称已实现 | §1.5.5 |
| 异步测试 | **12 个场景**，每个含 7 个必答字段；共性断言 A1–A11 | §4.11 / §4.12 |
| 真机证据 | 纪律 E-1~E-10 定稿；**本轮不执行** | §8.6 |
| 未改的东西 | 生产 Kotlin / Gradle / Manifest / Room / 备份 / 导航逻辑 **零改动**；CR-17 零改动 | 全篇 |
## 1. 当前时序

### 1.1 参与方与状态

| 参与方 | 位置 | 职责 |
|---|---|---|
| `TabScrollGuard` | `ui/TabScrollGuard.kt` | 程序化滚动守卫：`begin(index)` 发放唯一 token；`end(token)` 判定是否由本协程收尾；`ignoresWrite(index)` 决定中间页回写是否忽略 |
| 底部导航 `onSelect` | `ui/AppNav.kt:960-985` | 点 tab → `tabGuard.begin(index)` → `tabScope.launch { animateScrollToPage … finally { … alignRouteToPager(pagerState.currentPage) } }` |
| 路由回滚 `LaunchedEffect(mainPage)` | `ui/AppNav.kt:404-433` | 二级页返回等场景触发的回滚滚动，同样是程序化滚动 |
| Pager → 路由回写 | `ui/AppNav.kt:437-461` | `snapshotFlow { pagerState.currentPage }.distinctUntilChanged()` 单向回写；中间页在程序化滚动期间被 `guardIgnore` |
| `alignRouteToPager` | `ui/AppNav.kt:391-396` | 写 `Route/align=<page>` 打点；按 `pagerState.currentPage` 对齐路由 |
| `PerfProbe` | `uikit/PerfProbe.kt` | `A5/tabClick`、`A5/scrollStart`、`A5/scrollEnd`、`A5/scrollCancel`、`A5/scrollFinally`、`Route/currentPage`、`Route/guardIgnore`、`Route/selectMain`、`Route/align` |

### 1.2 关键代码

```kotlin
// ui/AppNav.kt:960-985  底部导航 onSelect
onSelect = { index ->
    val seq = PerfProbe.nextSeq()
    PerfProbe.a5("tabClick", index, seq)
    val guardToken = tabGuard.begin(index)
    tabScope.launch {
        try {
            PerfProbe.a5("scrollStart", index, seq)
            if (reduceMotion) pagerState.scrollToPage(index)
            else pagerState.animateScrollToPage(index, animationSpec = TabMotion.scrollSpecFor(...))
            PerfProbe.a5("scrollEnd", index, seq)
        } catch (e: kotlinx.coroutines.CancellationException) {
            PerfProbe.a5("scrollCancel", index, seq)
            throw e
        } finally {
            val aligned = tabGuard.end(guardToken)
            PerfProbe.a5Finally(index, seq, aligned)
            if (aligned) alignRouteToPager(pagerState.currentPage)   // ← ★ OBS-2 的落点
        }
    }
}
```

```kotlin
// ui/AppNav.kt:391-396
fun alignRouteToPager(landed: Int) {
    PerfProbe.event("Route/align=" + landed)
    val page = MAIN_PAGES.getOrNull(landed) ?: return
    if (AppPage.mainOf(viewModel.page.value) != page) viewModel.selectMain(page)
}
```

### 1.3 现有守卫的判据与它的盲区

```kotlin
// ui/TabScrollGuard.kt
fun ownsToken(owner: Long, token: Long): Boolean = token != NO_OWNER && token == owner;
fun ignoresWrite(programmatic: Boolean, target: Int, index: Int): Boolean =
    programmatic && index != target;
```

守卫解决的是「**协程身份**」问题：同目标连点时，用 `target` 当身份会失效，改成唯一 token 后旧协程的 `finally` 动不了新协程的状态。**这一层已被 R10 真机验证通过，不在本设计改动范围内（N7 / N10）。**

**盲区**：守卫回答的是「**这个协程是不是当前的主人**」，没有回答「**现在适不适合对齐路由**」。

```text
finally {
    aligned = tabGuard.end(token)     // ← 「我是不是主人」
    if (aligned) alignRouteToPager(currentPage)   // ← 「我现在就按当前页对齐」
}
```

`end(token)` 在**取消路径**上同样返回 `true`（token 仍是自己发的、没被新点击取代），于是**被用户手势打断的旧协程照样行使了对齐权**——而且它对齐的目标是 `pagerState.currentPage`，即**打断瞬间的过渡值**。

### 1.4 取消的三个来源（v1 已列，v2 给出识别方式）

| 来源 | 机制 | 当前行为 | 期望行为 |
|---|---|---|---|
| **(a) 用户手势接管** | 用户拖动通过 `MutatorMutex` 取消正在进行的 `scroll()` | `finally` → `alignRouteToPager(currentPage)` → **对齐到过渡页** | **放弃对齐**，等手势结束、稳定后再对齐 |
| **(b) 竞争的程序化滚动** | `LaunchedEffect(mainPage)` 或另一次点击发起新的 `animateScrollToPage`，取消前一个 | 同上 | 新滚动自己收尾；旧的不该对齐 |
| **(c) 协程作用域被取消** | `tabScope` 随组合离开而取消 | 同上 | 不应对齐，**且不得启动任何新任务** |

### 1.5 取消原因的可靠识别（GPT §4.1 新增，v2 核心）

#### 1.5.1 为什么不能只凭 `CancellationException`

`CancellationException` 只说明「这个协程被取消了」，**不说明为什么**。三种原因需要**三种处理**：

| 取消原因 | 处理 |
|---|---|
| **用户手势接管** | 旧协程**不直接写路由**，把对齐权交给**稳定后对齐器** |
| **新程序化滚动取代旧滚动** | 旧协程**不得对齐**（新滚动自己收尾） |
| **作用域取消（页面退出）** | **不得启动**依赖已取消作用域的对齐任务（否则会留下后台任务 / 泄漏） |

#### 1.5.2 可用的判别信号

| 信号 | 指向的取消原因 | 当前可用性 | 备注 |
|---|---|---|---|
| `tabGuard.end(token)` 返回 **false** | **(b)** token 已被新点击取代 | ✅ **已实现、已真机验证** | s5_same r3/r4/r5：seq1 `aligned=false`，无 `Route/align` |
| 协程 Job 已取消（`currentCoroutineContext().isActive == false`）且守卫仍归本协程所有 | **(c)** 作用域取消 | ✅ 需在 `finally` 内取一次 `isActive` | `kotlinx.coroutines.isActive` |
| Pager 正处于**用户拖动**中 | **(a)** 用户手势 | ⚠️ **需先验证可用性**（N-OBS-1） | 候选：`PagerState.interactionSource` 的拖动状态（`collectIsDraggedAsState`）；本项目 Compose BOM 2024.06 / foundation 版本上**必须先确认该 API 可用**，不可用则回退到下一种信号 |
| 目标页回写**已经发生**（`Route/selectMain=目标页` 已发出）后随即被取消 | **(b)** 的间接信号 | ✅ 已有打点可佐证 | 目标页被写过 → 说明程序化滚动至少推进到目标 → 后续取消多半来自竞争滚动 |
| `pagerState.isScrollInProgress` | 辅助 | ⚠️ 不区分用户 / 程序化 | **不能作为唯一判据**（v1 §5.4 已论证） |

#### 1.5.3 确定性判定顺序

```text
  finally {
    // ① 作用域取消优先：此时不得启动任何新任务
    if (!currentCoroutineContext().isActive) {
        cause = CancelCause.SCOPE
        tabGuard.end(guardToken);      // 只清自己的状态，不对齐、不派发
        return;
    }

    // ② token 是否仍归本协程所有
    val owned = tabGuard.end(guardToken);
    if (!owned) {
        cause = CancelCause.SUPERSEDED;  // 被新点击取代
        return;                          // 不得对齐
    }

    // ③ 仍未取消 → 正常收尾
    cause = CancelCause.NONE;
    alignRouteToPager(pagerState.currentPage);
  }

  catch (CancellationException) {
    // 取消路径：在 finally 里判定原因
    throw e;
  }
```

**在 catch 里还需要一次原因判定**（因为取消路径不走 `aligned` 分支）：

```text
  取消路径的判定：
    ① 作用域已取消            → SCOPE       → 不对齐、不派发
    ② end(token) == false     → SUPERSEDED  → 不对齐
    ③ 检测到用户拖动           → GESTURE     → 派发稳定后对齐器
    ④ 其余（未能识别）         → GESTURE（保守）+ 打点记录「未知原因」
```

**为什么把「未能识别」保守地当作用户手势**：三种原因里只有「用户手势」需要**补偿性对齐**；把未知情况当作「不补偿」会让路由可能滞留（v1 §5.3 的缺口），当作「手势」则最坏只是多一次幂等对齐。**安全方向是补偿。**

#### 1.5.4 打点增强（可观测性）

| 打点 | 格式 | 用途 |
|---|---|---|
| `A5/scrollCancel` | `A5/scrollCancel=<index>#<seq>:<cause>`，`cause` ∈ `gesture` / `superseded` / `scope` / `unknown` | 让真机复核能区分取消原因 |
| `Route/align` | `Route/align=<page>:<src>`，`src` ∈ `finally` / `settle` | 区分两条对齐路径 |
| 新增 `A5/alignSkip` | `A5/alignSkip=<index>#<seq>:<cause>` | 记录「判为不许对齐」的时刻，便于验证 §4.11 的中间态 |

> **格式兼容性**：原有 `A5/scrollCancel=<index>#<seq>` 与 `Route/align=<page>` 是**前缀关系**，既有真机证据仍可按前缀比对；新格式**只追加后缀**，不改变既有字段的含义。
---

#### 1.5.5 三类取消的后续行为（定稿 · GPT v2.1 · OBS-2-3）

| 取消原因 | 是否派发稳定后对齐器 | 是否允许路由写入 | 代际是否推进 |
|---|---|---|---|
| **GESTURE**（用户手势接管） | **是** —— 按设计等待 Pager 稳定（OW-1 的唯一启动源） | **只能写最终 `settledPage`**（且必须同时满足 OW-7 双条件与 OW-4 代际有效） | **推进**（新用户意图 → 旧对齐器立即失效） |
| **SUPERSEDED**（新程序化滚动取代） | **否** —— 旧对齐器**必须失效** | **由新操作决定**（新滚动的 `finally` 自己负责收尾） | **推进**（由新程序化滚动本身推进） |
| **SCOPE**（协程作用域被取消） | **否** —— **不派发任何后台对齐任务** | **不允许旧作用域写入** | 不推进（作用域已不存在） |

**每一条的依据**：

| 行 | 依据 |
|---|---|
| GESTURE → 派发对齐器 | 手势接管后，**用户意图由手势决定**；旧协程已无对齐权，但路由又必须最终落到 Pager 停留页 → 需要一个**补偿性**写入者（OW-1 / OW-7 / OW-9） |
| GESTURE → 只能写 `settledPage` | 过渡页不是真相；且必须 `currentPage == settledPage` 才算落定（OW-7） |
| SUPERSEDED → 旧对齐器必须失效 | 新滚动会自己收尾；若旧对齐器仍活着，两次写入会竞争（OW-2 / OW-4） |
| SUPERSEDED → 路由由新操作决定 | 路由的最终值应来自**最新**的那次操作，而不是被取代的旧操作 |
| SCOPE → 不派发任何后台任务 | 作用域已随组合离开；派发任务等于**泄漏**（OW-5 / OW-6） |
| SCOPE → 不允许旧作用域写入 | 旧作用域写路由会写到**已离开的界面**上 → 状态错乱 |
| GESTURE / SUPERSEDED 推进代际 | 二者都是**新的意图**（用户手势 / 新导航）→ 旧对齐器必须立即失效（OW-3 / OW-4） |

**保守处理的明确依据（GPT v2.1 要求写清）**：

| 情况 | 处理 | 依据 |
|---|---|---|
| 取消原因**无法确定**（判定链走完仍不明确） | **保守按 GESTURE 处理**（派发补偿性对齐器），但打点**如实记 `unknown`** | 三类原因里只有 GESTURE 需要**补偿性写入**。误判为 GESTURE 的最坏后果 = **多一次幂等对齐**（幂等，不产生错误状态）；误判为 SUPERSEDED / SCOPE 的最坏后果 = **路由滞留**（标题与页面长期不一致）。**两个方向的错误代价不对称，必须倒向补偿。** |
| `userDragging` API **不可用**（N-OBS-1 未通过） | 判定链第 3 条恒为 false → 所有取消都落入「无法确定」→ 一律保守按 GESTURE 处理 | 此时 GESTURE 与 SUPERSEDED 的区分能力下降，但**行为仍然安全**（补偿性对齐是幂等的）；代价是 SUPERSEDED 场景会多一次对齐尝试（幂等短路后不写） |

**`userDragging` 的实现状态声明（必须如实）**：

> **`userDragging` 的 API 尚未验证。** 候选为 `PagerState.interactionSource` 的拖动状态（`collectIsDraggedAsState`）；本项目 Compose BOM 2024.06 / foundation 版本上**尚未确认该 API 是否可用**。
> **在编译验证通过之前，不得声称「已实现用户手势识别」**，也不得把「GESTURE 分类可用」写成既成事实。
> 本设计把 `CancelCauseResolver` 抽成纯函数，正是为了让**「信号缺失 → 保守 GESTURE」这条降级路径可以被确定性测试**（§7.1 的 C2 / C8）。

---
## 2. 原始真机日志证据

### 2.1 证据来源与等级

| 项 | 值 |
|---|---|
| 证据目录 | `docs/CR-30-A3-实验补证/evidence/a5_r10_guard_ownership_real/` |
| 场景 | `s8_swipe_interrupt_120hz_r2` / `r3` / `r4` |
| 设备 | Redmi 2407FRK8EC（`GAQOHMNFQWFUPBQO`），仅 120Hz |
| 有效轮次 | 3 / 3（r2、r3、r4） |
| **证据等级** | ⚠️ **ADB 合成手势**，**不是真实手指** |

> **证据等级必须如实标注**：本节的日志由**脚本驱动的合成手势**产生。按 N8，**不得**把它当作「真实手指验证」。R10 的 OBS-1 与本节的 OBS-2 **都仍需真实手指复核**。
>
> 可以确定的是：合成手势能**触发**同一段代码路径（`CancellationException` → `finally` → `align`），因此**时序逻辑的结论成立**；不能确定的是：真实手指下的**具体时间点**与**过渡值**是否与合成手势完全一致。

### 2.2 r2 日志（逐行）

```text
1795253053.019  D CR30Probe: A5/tabClick=2#2            ← 点「日历」(index=2)
1795253053.020  D CR30Probe: Pager/scrollStart
1795253053.020  D CR30Probe: A5/scrollStart=2#2
1795253053.160  D CR30Probe: Route/currentPage=1      ← 程序化滚动经过中间页「统计」
1795253053.160  D CR30Probe: Route/guardIgnore=1      ← 守卫忽略中间页（路由未变，仍是「今日」）
1795253053.236  D CR30Probe: A5/scrollCancel=2#2       ← ★ 用户手势打断，协程被取消
1795253053.236  D CR30Probe: A5/scrollFinally=2#2 aligned=true
1795253053.236  D CR30Probe: Route/align=1            ← ★★ OBS-2：对齐到「中间页」！
1795253053.622  D CR30Probe: Route/currentPage=0      ← 386ms 后 Pager 真正落到「今日」
1795253053.623  D CR30Probe: Route/selectMain=…Today
```

**读法**：

1. `guardIgnore=1` 明确表示「中间页 1 的回写被守卫拦下」，路由此时仍是「今日」；
2. 76ms 后滚动被用户手势取消 → `finally` 执行 → `aligned=true`（token 有效）→ `Route/align=1`；
3. **`Route/align=1` 把路由改成了「统计」——恰恰是守卫刚刚判定为「中间页、应当忽略」的那一页**；
4. 386ms 后 Pager 真正停在 0，回写把路由改回「今日」。

**用户可见症状**：标题与底部导航胶囊**闪一下「统计」**，再变回「今日」。

### 2.3 r3 日志（逐行）

```text
1795253115.697  D CR30Probe: A5/tabClick=2#2
1795253115.698  D CR30Probe: Pager/scrollStart
1795253115.698  D CR30Probe: A5/scrollStart=2#2
1795253115.843  D CR30Probe: Route/currentPage=1
1795253115.843  D CR30Probe: Route/guardIgnore=1
1795253115.934  D CR30Probe: Route/currentPage=2      ← 程序化滚动到达目标页「日历」
1795253115.934  D CR30Probe: Route/selectMain=…Calendar ← 路由合法地写成「日历」
1795253116.178  D CR30Probe: A5/scrollCancel=2#2       ← ★ 244ms 后用户手势打断
1795253116.178  D CR30Probe: A5/scrollFinally=2#2 aligned=true
1795253116.178  D CR30Probe: Route/align=2            ← ★★ OBS-2：对齐到「日历」（= 点击目标）
1795253116.264  D CR30Probe: Route/currentPage=1      ← 86ms 后 Pager 落在「统计」
1795253116.264  D CR30Probe: Route/selectMain=…Stats
```

**读法**：打断瞬间 `currentPage` 仍是 2（目标页），于是对齐到「日历」；随后 Pager 滑回 1（统计），回写改路由。**最终路由 = Pager 实际停留页（统计）≠ 点击目标（日历）**——这一部分是对的（Pager 仍是唯一真相源），但中间多了一次**多余的路由写**（「日历」→「统计」抖动）。

### 2.4 r4 日志（逐行）

```text
1795253144.366  D CR30Probe: A5/tabClick=2#2
1795253144.367  D CR30Probe: Pager/scrollStart
1795253144.367  D CR30Probe: A5/scrollStart=2#2
1795253144.520  D CR30Probe: Route/currentPage=1
1795253144.520  D CR30Probe: Route/guardIgnore=1
1795253144.578  D CR30Probe: A5/scrollCancel=2#2
1795253144.578  D CR30Probe: A5/scrollFinally=2#2 aligned=true
1795253144.578  D CR30Probe: Route/align=1            ← ★★ 与 r2 同型：对齐到中间页
1795253144.923  D CR30Probe: Route/currentPage=0
1795253144.923  D CR30Probe: Route/selectMain=…Today
```

### 2.5 三轮汇总

| 轮次 | `scrollCancel` 距 `scrollStart` | 打断瞬间 `currentPage` | `Route/align` | 之后 Pager 落点 | 最终路由 |
|---|---|---|---|---|---|
| r2 | +216 ms | 1（中间页） | **1（中间页）** | 0（今日） | 今日 |
| r3 | +244 ms | 2（目标页） | **2（目标页）** | 1（统计） | 统计 |
| r4 | +211 ms | 1（中间页） | **1（中间页）** | 0（今日） | 今日 |

**共同结论**：

1. `scrollFinally aligned=true` —— **token 判定认为旧协程仍然是主人**（这符合设计：没有新点击取代它）；
2. `Route/align` 在**每一次**取消后都发生 —— 旧协程**无条件行使了对齐权**；
3. 对齐目标在 r2 / r4 是**被守卫判定为应忽略的中间页** —— 守卫的「忽略中间页」规则在 `finally` 里被自己推翻了；
4. 最终路由都等于 Pager 的实际停留页 —— **说明这次多余的写入并非必要**，回写路径本来就能给出正确答案。

### 2.6 对照组：s5_same 与 s7_swipe_fast

```text
=== s5_same_120hz_r4（同目标连点两次）===
A5/tabClick=1#1 / scrollStart=1#1
Route/currentPage=1 / Route/selectMain=…Stats
A5/tabClick=1#2 / scrollStart=1#2
Pager/scrollEnd / A5/scrollEnd=1#2
A5/scrollFinally=1#2 aligned=true / Route/align=1
Pager/scrollEnd / A5/scrollEnd=1#1
A5/scrollFinally=1#1 aligned=false            ← 旧协程正确地没有对齐（token 机制生效）

=== s7_swipe_fast_120hz_r4（快速横滑，无 tab 点击）===
Route/currentPage=1 / Route/selectMain=…Stats
Route/currentPage=0 / Route/selectMain=…Today
Route/currentPage=1 / Route/selectMain=…Stats
（无 A5/scrollCancel、无 Route/align —— 纯手势路径不经过程序化滚动）
```

**s5_same 的结论**：token 机制工作正常，**旧协程没有对齐**。这证明「对齐权」是可以被精确授予的 —— OBS-2 不是「机制缺失」，而是**判据不足**（只判身份、没判时机与目标）。

**s7 的结论**：纯手势路径**完全不产生 `Route/align`**，也不产生 `A5/scrollCancel`。说明 `Route/align` 这个打点就是「程序化滚动收尾」的专属信号，`A5/scrollCancel` 是「取消」的专属信号。

---

## 3. 竞态状态图

### 3.1 参与方

```text
  P = 程序化滚动协程（tabScope.launch { animateScrollToPage … }）
  U = 用户手势（Pager 的拖动，通过 MutatorMutex 抢占 scroll()）
  W = Pager → 路由回写（snapshotFlow { currentPage }.distinctUntilChanged()）
  G = TabScrollGuard（programmatic / target / owner token）
  S = 稳定后对齐器（SettleAligner，v2 新增，一次性）
```

### 3.2 状态定义（v2 增补取消原因）

```text
  滚动协程生命周期：
    START ──► ANIMATING ──┬──► SETTLED          （scrollEnd）
                          │
                          ├──► CANCELLED_GESTURE   （用户手势接管）
                          ├──► CANCELLED_SUPERSEDED（新程序化滚动取代）
                          └──► CANCELLED_SCOPE      （作用域取消 / 页面退出）

  守卫状态：
    IDLE(programmatic=false, target=-1, owner=NO_OWNER)
    OWNED(programmatic=true, target=T, owner=TOKEN)

  代际（generation，v2 新增）：
    GEN = 单调递增整数；每次「用户手势接管程序化滚动」或「新的程序化滚动开始」时 +1；
         稳定后对齐器在启动时捕获 GEN，执行前比对 —— 不一致即失效。

  路由状态：
    ROUTE(p)  —— p 属于 {0 今日, 1 统计, 2 日历}
```

### 3.3 竞态全景（OBS-2 的形成过程）

```text
 时刻   事件                          Pager           守卫                路由        备注
 ────  ────────────────────────────  ──────────────  ──────────────────  ──────────  ─────────────────────────
 t0     P: A5/tabClick=2              currentPage=0   IDLE               今日        用户点「日历」
 t0     P: guard.begin(2) → #7        currentPage=0   OWNED(target=2,#7)  今日        token #7 发放
 t0     P: A5/scrollStart=2           动画开始        OWNED              今日
 t1     W: Route/currentPage=1        currentPage=1   OWNED              今日        中间页 → guardIgnore
 t1     W: Route/guardIgnore=1        currentPage=1   OWNED              今日        ★ 守卫说「1 不该成为路由」
 t2     U: 用户手指拖动 → 抢占 scroll  currentPage=1   OWNED              今日        用户接管
 t2     P: CancellationException       currentPage=1   OWNED              今日        A5/scrollCancel=2:gesture
 t2     P: finally { end(#7)=true }   currentPage=1   IDLE               今日        A5/scrollFinally aligned=true
 t2     P: alignRouteToPager(1)       currentPage=1   IDLE               统计        ★★ OBS-2：对齐到被忽略的中间页
 t3     W: Route/currentPage=0        currentPage=0   IDLE               统计        回写
 t3     W: Route/selectMain=Today      currentPage=0   IDLE               今日        最终正确，但用户看到过「统计」
```

### 3.4 v2 的目标时序（同一场景，修复后）

```text
 时刻   事件                          Pager           守卫                路由        备注
 ────  ────────────────────────────  ──────────────  ──────────────────  ──────────  ─────────────────────────
 t0     P: A5/tabClick=2              currentPage=0   IDLE               今日
 t0     P: guard.begin(2) → #7        currentPage=0   OWNED(target=2,#7)  今日
 t0     P: A5/scrollStart=2           动画开始        OWNED              今日
 t1     W: Route/currentPage=1        currentPage=1   OWNED              今日        guardIgnore=1
 t2     U: 用户拖动 → 抢占 scroll      currentPage=1   OWNED              今日        GEN: 6 → 7
 t2     P: CancellationException       currentPage=1   OWNED              今日        A5/scrollCancel=2:gesture
 t2     P: finally: cause=GESTURE     currentPage=1   IDLE               今日        ★ 不对齐！
 t2     P: 启动 SettleAligner(gen=7)  currentPage=1   IDLE               今日        等待稳定
 t3     W: Route/currentPage=0        currentPage=0   IDLE               今日        回写（当前页已等于目标）
 t4     P: SettleAligner 恢复          currentPage=0   IDLE               今日        稳定：currentPage==settledPage==0
 t4     P: commitRouteFromPager(0)    currentPage=0   IDLE               今日        ★ 幂等：路由已是 0，不写
```

**与 v1 的关键差异**：

| | v1（现状） | v2（目标） |
|---|---|---|
| 取消瞬间的路由写 | **有**（`Route/align=1` → 中间页） | **无** |
| 路由抖动 | 标题 / 胶囊闪一下 | **无** |
| 最终路由 | == Pager 停留页 | == Pager 停留页（不变） |
| 新增写入者 | —— | 稳定后对齐器（**一次性**、**Pager → 路由**方向、**幂等**） |

### 3.5 三个必须同时成立才安全的判据

```text
                       ┌───────────────────────────────────────────┐
                       │ 协程收尾到达                              │
                       └───────────────────┬───────────────────────┘
                                           │
                        ┌──────────────────▼───────────────────┐
                        │ 判据 1：本次是 SETTLED 还是 CANCELLED？ │
                        └──┬────────────────────────────┬─────┘
                     SETTLED│                            │CANCELLED
                           ▼                            ▼
            ┌──────────────────────────┐   ┌──────────────────────────────────────┐
            │ 判据 2：align 的目标页是   │   │ 判据 2'：按取消原因分流              │
            │ 「守卫忽略的中间页」吗？    │   │  gesture    → 交给稳定后对齐器      │
            └────┬────────────────┬────┘   │  superseded → 不得对齐              │
             不是 │                │ 是     │  scope      → 不得对齐、不派发      │
                 ▼                ▼      └──────────────────────────────────────┘
        ┌──────────────┐  ┌──────────────┐
        │ 判据 3：Pager │  │ 不允许对齐   │
        │ 已稳定？      │  │（中间页不应  │
        │ settledPage== │  │ 成为路由值） │
        │ currentPage？ │  └──────────────┘
        └──┬────────┬──┘
        是 │        │ 否
           ▼        ▼
   ┌───────────┐  ┌──────────────┐
   │ 允许对齐   │  │ 不允许对齐   │
   │（幂等）    │  │ 交给稳定后   │
   └───────────┘  │ 对齐器       │
                  └──────────────┘
```

### 3.6 判据 1 的必要性（为什么不能只靠 token）

token 回答的是「**我是谁**」，不是「**现在能不能动**」。R10 已证明 token 能正确区分「旧协程 vs 新协程」（s5_same r4：旧协程 `aligned=false`，没有产生 `Route/align`）。

但 OBS-2 的场景里**没有新点击**——token #7 从头到尾都是主人，于是 `end(#7)` 返回 `true`。

**结论**：token 判据**必须保留**（N7 / N10），但**不够**；需要补上「本次是 SETTLED 还是 CANCELLED」这一层。

### 3.7 判据 2 的必要性（为什么中间页不能成为路由值）

守卫的 `ignoresWrite` 已经明确表达了产品口径：

> 「程序化滚动途中经过的中间页，不应成为路由值（否则标题会先跳中间页再跳回，观感＝卡一下）。」

r2 / r4 里 `Route/align=1` 正是**把守卫刚刚忽略的中间页又写了回去**——守卫的规则在自己的 `finally` 里被推翻了。

**但要注意一个角色切换**：

- 在**程序化滚动期间**，中间页是噪声（不该成为路由）；
- 在**用户手势期间**，Pager 当前停在哪页就是哪页——此时**没有「中间页」概念**，因为用户接管了 Pager。

因此「不能对齐到中间页」这条约束的**适用范围是程序化滚动期间**；取消之后由稳定后对齐器按 `settledPage` 对齐是合法的（`settledPage` 不是过渡值）。
---

## 4. 十个场景的预期行为

### 4.0 记号与前提

```text
  页面编号：0 = 今日，1 = 统计，2 = 日历
  目标页 targetPage：本次程序化滚动要去的页（= guard.target）
  token owner：守卫当前所有者 token；NO_OWNER = 0 表示无守卫
  路由状态：viewModel.page 的主页面
  取消原因：gesture（用户手势）/ superseded（新滚动取代）/ scope（作用域取消）
```

**全局不变式**（所有场景都必须满足）：

- **INV-1**：Pager 始终是唯一真相源；最终路由 == Pager 的实际停留页。
- **INV-2**：任何**过渡态**的 `currentPage` 都不得成为路由值。
- **INV-3**：被守卫判定为「中间页」的页，在**程序化滚动期间**不得成为路由值。
- **INV-4**：用户手势期间不得发生任何「抢先覆盖用户意图」的路由写。
- **INV-5**：token 所有权机制与不可变快照实现**不被改动**。
- **INV-6**（v2 新增）：所有基于 Pager 状态的路由写**必须**经过 `commitRouteFromPager()`（§6.4）。
- **INV-7**（v2 新增）：新导航开始后，旧代际的对齐器**必须失效**。

---

### S1 · 正常点击完成

| 观察项 | 值 |
|---|---|
| 事件时间线 | `A5/tabClick=2` → `A5/scrollStart=2` → `Route/currentPage=1` + `Route/guardIgnore=1` → `Route/currentPage=2` + `Route/selectMain=Calendar` → `A5/scrollEnd=2` → `A5/scrollFinally aligned=true` → `Route/align=2:finally` |
| `currentPage` | 0 → 1 → 2 |
| `targetPage` | 2（全程不变） |
| `isScrollInProgress` | t0..t3 为 true；t3 之后 false |
| 路由状态 | 今日 →（中间页被忽略，仍是今日）→ 日历 → 日历（幂等） |
| token owner | t0 起 = #7；t3 `end(#7)` 成功后 = NO_OWNER |
| 取消原因 | 无（`SETTLED`） |
| 是否允许 `alignRouteToPager()` | **允许**（SETTLED + 目标已是稳定页 + 幂等） |
| 最终页面与选中态 | Pager 停在「日历」；底部导航选中「日历」；标题「日历」 |
| 现状 vs 期望 | **完全一致**——本场景不得有任何行为变化（回归基线） |

---

### S2 · 点击后立即横滑打断（★ OBS-2 主场景）

**场景**：在「今日」(0) 点「日历」(2)，程序化滚动途中用户立即右滑回「今日」。**对应真机 r2 / r4。**

| 观察项 | 值 |
|---|---|
| 事件时间线 | `A5/tabClick=2` → `A5/scrollStart=2` → `Route/currentPage=1` + `Route/guardIgnore=1` → **用户拖动** → `A5/scrollCancel=2:gesture` → `A5/scrollFinally aligned=true` → **（期望：无 `Route/align`）** → `Route/currentPage=0` + `Route/selectMain=Today` |
| `currentPage` | 0 → 1（中间页）→（用户拖动中）→ 0 |
| `targetPage` | 2（取消瞬间仍为 2，但目标已失效） |
| `isScrollInProgress` | t0 起 true（程序化动画）→ 取消瞬间**仍为 true**（用户的拖动接管了 scroll）→ 手势结束后 false |
| 路由状态 | 今日 →（guardIgnore 保持今日）→ **期望：始终是今日** |
| token owner | #7 直到 `finally` 才被 `end()` 清空 |
| 取消原因 | `gesture` |
| 是否允许 `alignRouteToPager()` | **不允许**。取消路径一律不对齐：此刻的 `currentPage=1` 是**过渡值**，且正是守卫刚判定为「应忽略」的中间页。对齐权交给稳定后对齐器 |
| 最终页面与选中态 | Pager 停在「今日」；底部导航选中「今日」；标题「今日」。**全程不应出现「统计」** |
| 现状 vs 期望 | 现状：标题 / 胶囊闪一下「统计」（r2 / r4 的 `Route/align=1`）。期望：**无抖动** |

---

### S3 · 横滑回原目标

**场景**：点「日历」(2) 后，用户手势把 Pager 推回并最终停在「日历」(2)。

| 观察项 | 值 |
|---|---|
| 事件时间线 | `A5/tabClick=2` → `A5/scrollStart=2` → `Route/currentPage=1` + `guardIgnore=1` → 用户拖动 → `A5/scrollCancel=2:gesture` → `A5/scrollFinally aligned=true` →（期望：无 `Route/align`）→ 稳定后 `Route/align=2:settle` |
| `currentPage` | 0 → 1 → 2 |
| `targetPage` | 2 |
| `isScrollInProgress` | true →（取消瞬间仍 true）→ 手势结束 false |
| 路由状态 | 今日 →（忽略中间页）→ 日历 |
| token owner | #7 → NO_OWNER |
| 取消原因 | `gesture` |
| 是否允许 `alignRouteToPager()` | **不允许**（取消路径）。对齐权移交稳定后对齐器，最终对齐到 `settledPage=2`（与目标一致，**幂等**） |
| 最终页面与选中态 | Pager 停在「日历」；选中「日历」 |
| 现状 vs 期望 | 现状：取消瞬间写 `Route/align=2`，此时 Pager 还在动 → 是**抢跑**。期望：等稳定后再写，且幂等 |

---

### S4 · 横滑到另一页

**场景**：点「日历」(2) 后，用户手势把 Pager 推回并停在「统计」(1)。**对应真机 r3。**

| 观察项 | 值 |
|---|---|
| 事件时间线 | `A5/tabClick=2` → `A5/scrollStart=2` → `Route/currentPage=1` + `guardIgnore=1` → `Route/currentPage=2` + `selectMain=Calendar` → 用户拖动 → `A5/scrollCancel=2:gesture` → `A5/scrollFinally aligned=true` →（现状：`Route/align=2`）→ `Route/currentPage=1` + `selectMain=Stats` |
| `currentPage` | 0 → 1 → 2 → 1 |
| `targetPage` | 2 |
| `isScrollInProgress` | true →（取消瞬间仍 true）→ 手势结束 false |
| 路由状态 | 今日 → 日历（目标页合法回写）→ **现状：再次写「日历」** → 统计 |
| token owner | #7 → NO_OWNER |
| 取消原因 | `gesture` |
| 是否允许 `alignRouteToPager()` | **不允许**（取消路径）。稳定后对齐器对齐到 `settledPage=1` |
| 最终页面与选中态 | Pager 停在「统计」；选中「统计」。**最终路由必须等于「统计」**（= Pager 实际停留页 ≠ 点击目标「日历」，这是正确行为） |
| 现状 vs 期望 | 现状：`Route/align=2` 是**多余写**（路由当时已是日历），86ms 后回写改「统计」。期望：直接落到「统计」，少一次抖动 |

---

### S5 · 横滑中再点 tab

**场景**：用户正在横滑（无程序化滚动）时，点击底部导航的另一个 tab。

| 观察项 | 值 |
|---|---|
| 事件时间线 | （用户手势中）`Route/currentPage=1` + `selectMain=Stats` → **点击「日历」** → `A5/tabClick=2` → `A5/scrollStart=2` → `A5/scrollCancel=2:?` → `A5/scrollFinally aligned=?` |
| `currentPage` | 1 →（动画途中）→ ? |
| `targetPage` | 2 |
| `isScrollInProgress` | true（用户手势）→ 动画期间 true |
| 路由状态 | 统计 →（动画途中 `currentPage=2` 时写「日历」） |
| token owner | #8（新点击的 token）；旧手势**不持有 token**（纯手势路径不调 `begin`） |
| 取消原因 | 取决于谁赢：`gesture`（用户拖动继续）或 `superseded`（新动画取代） |
| 是否允许 `alignRouteToPager()` | 旧协程：**一律不允许**（取消路径）。若原因为 `superseded` → 新滚动自己收尾；若为 `gesture` → 交给稳定后对齐器 |
| 最终页面与选中态 | 取决于谁赢：**用户的拖动若最终停下 → 以 Pager 停留页为准**；若动画赢了 → 「日历」。无论哪种，最终路由 == Pager 停留页（INV-1） |
| 现状 vs 期望 | 现状：`Route/align` 的目标取自取消瞬间的 `currentPage`，**可能与最终停留页不一致** → 抖动 |

---

### S6 · 连续快点不同 tab

**场景**：点「统计」(1)，还没到位就点「日历」(2)。

| 观察项 | 值 |
|---|---|
| 事件时间线 | `A5/tabClick=1` → `A5/scrollStart=1` → `A5/tabClick=2` → `A5/scrollStart=2` → 第一次滚动被第二次**竞争取消** → `A5/scrollCancel=1:superseded` → `A5/scrollFinally=1 aligned=false` → `A5/scrollEnd=2` → `A5/scrollFinally=2 aligned=true` → `Route/align=2:finally` |
| `currentPage` | 0 → 1 → 2 |
| `targetPage` | 1（第一次）→ 2（第二次） |
| `isScrollInProgress` | true（贯穿，可能不落 false） |
| 路由状态 | 今日 → 统计（第一次目标页回写）→ 日历 |
| token owner | #9 → 被 #10 取代 → NO_OWNER |
| 取消原因 | `superseded`（token 不匹配可证） |
| 是否允许 `alignRouteToPager()` | 第一次：**不允许**（`aligned=false`，现状已如此）。第二次：**允许**（SETTLED + token 命中） |
| 最终页面与选中态 | Pager 停在「日历」；选中「日历」 |
| 现状 vs 期望 | **一致**——token 机制已经正确处理了本场景（R10 s5_same 的同型验证）。本场景是**回归基线，不得变化** |

---

### S7 · 连续快点相同 tab

**场景**：点「统计」(1) 两次。**对应真机 s5_same r3/r4/r5。**

| 观察项 | 值 |
|---|---|
| 事件时间线 | `A5/tabClick=1#1` → `A5/scrollStart=1#1` → `Route/currentPage=1` + `selectMain=Stats` → `A5/tabClick=1#2` → `A5/scrollStart=1#2` → `A5/scrollEnd=1#2` → `A5/scrollFinally=1#2 aligned=true` → `Route/align=1:finally` → `A5/scrollEnd=1#1` → `A5/scrollFinally=1#1 aligned=false` |
| `currentPage` | 0 → 1 |
| `targetPage` | 1（两次相同） |
| `isScrollInProgress` | true →（#2 完成）false；#1 随后完成 |
| 路由状态 | 今日 → 统计 → 统计 |
| token owner | #11 → 被 #12 取代 → NO_OWNER |
| 取消原因 | 无（两次都是 SETTLED，只是 token 归属不同） |
| 是否允许 `alignRouteToPager()` | #1：**不允许**（`aligned=false`，现状已如此）。#2：**允许**（SETTLED + token 命中 + 幂等） |
| 最终页面与选中态 | Pager 停在「统计」；选中「统计」 |
| 现状 vs 期望 | **完全一致**——这是 R10 P2 的核心判据，**严禁破坏**（N7 / N10） |

---

### S8 · 手势刚结束时旧协程 finally

**场景**：用户手势在旧协程 `finally` 执行**前一刻**结束（最坏时序）。

| 观察项 | 值 |
|---|---|
| 事件时间线 | `A5/scrollCancel=2:gesture`（取消）→ **用户手势在此刻结束** → `A5/scrollFinally aligned=true` →（现状：`Route/align=<取消瞬间的 currentPage>`）→ `Route/currentPage=<最终页>` |
| `currentPage` | 取消瞬间 = 过渡值；手势结束后 = 最终页 |
| `targetPage` | 2（已失效） |
| `isScrollInProgress` | 取消瞬间可能已为 **false**（手势刚结束） |
| 路由状态 | 取决于手势最终停在哪页 |
| token owner | #13 → NO_OWNER |
| 取消原因 | `gesture` |
| 是否允许 `alignRouteToPager()` | **不允许**。注意：**即使此刻 `isScrollInProgress` 已经是 false，也仍然不允许**——因为 `currentPage` 可能是「手势刚停下的那一页」而非用户最终意图停留页（例如手势被吸附回弹）。**这正是「用 `isScrollInProgress` 当唯一判据」方案的漏洞** |
| 最终页面与选中态 | 由稳定后对齐器在 `currentPage == settledPage` 且静止后确定；最终路由 == Pager 停留页 |
| 现状 vs 期望 | 现状：写的是取消瞬间的过渡值。期望：写最终稳定页 |

---

### S9 · 快速反向滑动

**场景**：用户在一次手势里先向左拖再快速反向向右拖（回弹）。

| 观察项 | 值 |
|---|---|
| 事件时间线 | `Route/currentPage=1`（越界）→ `Route/currentPage=0`（回弹）→ 可能 `Route/currentPage=1` → 停 |
| `currentPage` | 0 → 1 → 0 →（1）→ 稳定 |
| `targetPage` | 无（纯手势路径不持有 token） |
| `isScrollInProgress` | true（全程） |
| 路由状态 | 跟随 `currentPage` 回写 |
| token owner | NO_OWNER |
| 取消原因 | 不适用（不经过 `finally`） |
| 是否允许 `alignRouteToPager()` | **不适用**——纯手势路径不经过 `finally`，不产生 `Route/align`（真机 s7_swipe_fast 已验证：3/3 轮无 `A5/scrollCancel`、无 `Route/align`） |
| 最终页面与选中态 | Pager 最终停留页；路由 == 停留页 |
| 现状 vs 期望 | **完全一致**——本场景是**回归基线，不得变化** |

---

### S10 · 取消后 Pager 未稳定窗口

**场景**：程序化滚动被取消后，Pager 处于「已停止滚动但尚未落到稳定页」的窗口（例如弹簧动画的回弹阶段，或 `settledPage` 尚未更新）。

| 观察项 | 值 |
|---|---|
| 事件时间线 | `A5/scrollCancel=2:gesture` → `A5/scrollFinally aligned=true` →（现状：`Route/align=<过渡值>`）→ … Pager 回弹落定 … → `Route/currentPage=<最终页>` |
| `currentPage` | 取消瞬间 = 过渡值；窗口期内可能继续变 |
| `targetPage` | 2（已失效） |
| `isScrollInProgress` | 窗口期内**可能为 false**（动画已结束），但 `currentPage != settledPage` |
| 路由状态 | 窗口期内可能是**过时值** |
| token owner | #14 → NO_OWNER |
| 取消原因 | `gesture` |
| 是否允许 `alignRouteToPager()` | **不允许**（取消路径）。且稳定后对齐器必须**同时**满足「`isScrollInProgress == false`」与「`currentPage == settledPage`」才动作——**只看 `isScrollInProgress` 会在本窗口内误判为已稳定** |
| 最终页面与选中态 | 窗口结束后由回写 / 稳定后对齐器给出；最终路由 == Pager 停留页 |
| 现状 vs 期望 | 现状：在窗口期内写过渡值。期望：窗口期内**不写**，窗口结束后写最终稳定页 |
---

### 4.11 异步竞态测试场景（GPT §4.4 · v2 重写为 12 个场景）

> 纯函数 `shouldAlign()` 的表驱动测试**不能替代**这些场景：它只覆盖「一次判定」，不覆盖「跨挂起点的所有权交接」。以下每个场景都必须验证**中间态**是否发生了错误写入，而不只是「最终正确」。
> **v2.1**：每个场景统一给出 **7 个必答字段**（初始状态 / 操作顺序 / 协程挂起点 / 状态变化 / 恢复时的有效性检查 / 预期路由写入次数 / 预期最终状态）+ 中间态断言 + 日志证据；并在 R1–R10 之外**新增 R11 / R12** 覆盖 GPT v2.1 点名的最后两项（最终对齐到实际停留页 / 组合场景不卡死）。

**统一记号**：

```text
  GEN   = 代际计数器（§6.3.3）；S = 稳定后对齐器；P = 程序化滚动协程
  TKN#n = 第 n 个 token；OWNER = 当前守卫所有者
  路由写入次数 = 统一入口实际调用 viewModel.selectMain 的次数（Route/selectMain / Route/commit）
```

---

#### R1 · 对齐器等待期间再次点击 Tab

| 项 | 内容 |
|---|---|
| **初始状态** | `ROUTE(0)`；Pager `currentPage=0`、`settledPage=0`、静止；`OWNER=NONE`；`GEN=5` |
| **操作顺序** | ① 点「日历」→ `begin` 发 TKN#8，`OWNER=#8`，**`GEN=6`**，启动 P ② P 挂起在 `animateScrollToPage` ③ 用户手势接管 → P 被取消，`cause=gesture`，**`GEN=7`**，启动 S（捕获 `gen0=7`）④ S 挂起等待稳定 ⑤ 用户**再次点「统计」** → `begin` 发 TKN#9，`OWNER=#9`，**`GEN=8`** ⑥ 新滚动完成 → `finally` 写 `Route/commit=1:finally` ⑦ S 恢复 |
| **协程挂起点** | P：`animateScrollToPage` 内部（被手势取消）；S：`awaitStable` 的**第 1 个挂起点**（`snapshotFlow { isScrollInProgress }.first { !it }`） |
| **状态变化** | ⑤ 之后 `GEN: 7 → 8`；`OWNER: NONE → #9`；Pager `currentPage: 0 → 1`、`settledPage: 0 → 1` |
| **恢复时的有效性检查** | S 恢复后依次检查：① `currentGeneration() == gen0` → **7 ≠ 8 → 失效**，记 `A5/alignSkip=…:stale-generation` → **不写**；②（若继续）`isScrollInProgress == false`；③ `currentPage == settledPage` |
| **预期路由写入次数** | **1 次**（仅 ⑥ 的新滚动 `finally`）；**S 的写入次数 = 0** |
| **预期最终状态** | `ROUTE(1)`（统计）；Pager `currentPage=1`、`settledPage=1`、静止；标题与底部胶囊一致 |
| **中间态断言** | ⑦ 之后，路由取值序列**不得**出现 2（日历）；`alignSkip` 恰好 **1** 次且原因为 `stale-generation`；`Route/align` 恰好 **1** 次且目标为 1 |
| **日志证据** | `A5/scrollCancel=2:gesture` → `A5/gen=7` → `A5/tabClick=1#9` → `A5/scrollFinally=1#9 aligned=true owned=true cause=none stable=true settledMatch=true` → `Route/align=1:finally` → `Route/selectMain=Stats` → `A5/alignSkip=…:stale-generation` |

---

#### R2 · 对齐器等待期间再次发生用户手势

| 项 | 内容 |
|---|---|
| **初始状态** | `ROUTE(0)`；Pager `currentPage=0`、`settledPage=0`、静止；`OWNER=NONE`；`GEN=5` |
| **操作顺序** | ① 点「日历」→ TKN#8，`GEN=6`，P 启动 ② 第一次手势接管 → P 取消，`cause=gesture`，**`GEN=7`**，S 启动（`gen0=7`）③ S 挂起 ④ 用户**再次横滑**（纯手势路径，不发 token）→ **代际推进点②** → **`GEN=8`** ⑤ Pager 落定于 0，S 恢复 |
| **协程挂起点** | S：`awaitStable` 第 1 个挂起点；纯手势路径**没有** `begin`，代际由 `userDragging` 由 false→true 的检测推进 |
| **状态变化** | ④ 之后 `GEN: 7 → 8`；Pager `currentPage` 在 1 与 0 之间变化，最终 `currentPage=0`、`settledPage=0` |
| **恢复时的有效性检查** | ① `currentGeneration() == gen0` → **7 ≠ 8 → 失效** → `alignSkip=stale-generation` → **不写**；② 即使代际未推进，③ `currentPage == settledPage` 在过渡期内也不成立 → 双重拦截 |
| **预期路由写入次数** | **≤ 1 次**（由 `snapshotFlow` 回写产生）；**S 的写入次数 = 0** |
| **预期最终状态** | `ROUTE(0)`（今日）；Pager 停留在 0 |
| **中间态断言** | 恢复后路由**不得**跳到 1（统计）再跳回 0 —— 一次抖动都不允许 |
| **日志证据** | `A5/scrollCancel=2:gesture` → `A5/gen=7` → `A5/gen=8` → `A5/alignSkip=…:stale-generation`（或 `not-stable`） |

> **R2 是代际机制的核心场景**：若代际只在「新程序化滚动」时推进（`userDragging` 不可用的降级情形），本场景的第二道拦截（`currentPage == settledPage`）仍必须拦住 —— 这条**双保险**是 N-OBS-2 要验证的内容。

---

#### R3 · 新旧对齐器同时等待

| 项 | 内容 |
|---|---|
| **初始状态** | `ROUTE(0)`；Pager `currentPage=0`、静止；`OWNER=NONE`；`GEN=5` |
| **操作顺序** | ① 第一次手势取消 → `GEN=6`，启动 S1（`gen0=6`）② S1 挂起 ③ 第二次手势取消（另一次程序化滚动的取消）→ **`GEN=7`**，启动 S2（`gen0=7`）④ S1、S2 同时挂起等待稳定 ⑤ Pager 落定，两个协程依次恢复 |
| **协程挂起点** | S1、S2 各自的 `awaitStable` 第 1 个挂起点 |
| **状态变化** | `GEN: 5 → 6 → 7`；Pager `currentPage` 改变一次后落定 |
| **恢复时的有效性检查** | S1：`currentGeneration()` = 7 ≠ `gen0=6` → **失效**；S2：`7 == gen0=7` → 继续 → 通过 `stable` 与 `settledMatch` 检查 → **允许写一次** |
| **预期路由写入次数** | **恰好 1 次**（S2）；S1 **0 次** |
| **预期最终状态** | `ROUTE` == Pager 落定页 |
| **中间态断言** | `alignSkip` 恰好 **1** 次（且 `stale-generation`）；`Route/align` 恰好 **1** 次；**不得**出现两次写入竞争 |
| **日志证据** | `A5/alignSkip=…:stale-generation`（S1）→ `A5/alignCommit=… gen=7 genValid=true`（S2）→ `Route/align=<落定页>:settle` |

---

#### R4 · 页面退出时协程取消

| 项 | 内容 |
|---|---|
| **初始状态** | `ROUTE(0)`；Pager `currentPage=0`、静止；`OWNER=NONE`；`GEN=5`；**组合即将离开** |
| **操作顺序** | ① 点「日历」→ TKN#8，`GEN=6`，P 启动 ② 用户手势接管 → P 取消，`cause=gesture` ③ **用户立即按返回键退出主 tab** → `tabScope` 被取消 → ④ P 的 `finally` 执行，判定 `isActive == false` |
| **协程挂起点** | P：`animateScrollToPage` 内部（被作用域取消）；**S 根本不会被创建** |
| **状态变化** | `tabScope` 取消 → 所有子协程失效；`GEN` 不再推进（无新意图） |
| **恢复时的有效性检查** | P 的 `finally`：`currentCoroutineContext().isActive == false` → **`cause=scope`** → **不写、不派发**（OW-1 / OW-6） |
| **预期路由写入次数** | **0 次** |
| **预期最终状态** | 无（页面已离开）；**不得**出现后台任务写路由 |
| **中间态断言** | **不得**产生 `Route/align` / `Route/selectMain` / `Route/commit`；**不得**产生 `alignSkip`（作用域取消连对齐器都没派发，不该有 skip 记录）；`tabScope` 内**无活跃子任务** |
| **日志证据** | `A5/scrollCancel=2:scope` → `A5/scrollFinally=2#8 aligned=true owned=true cause=scope stable=false settledMatch=false` → **（无任何 Route/*）** |
---

#### R5 · 新导航完成后旧对齐器才恢复执行

| 项 | 内容 |
|---|---|
| **初始状态** | `ROUTE(0)`；Pager `currentPage=0`、静止；`OWNER=NONE`；`GEN=5` |
| **操作顺序** | ① 手势取消 → `GEN=6`，S 启动（`gen0=6`）② S 挂起 ③ 用户点「统计」→ TKN#9，`OWNER=#9`，**`GEN=7`** ④ 新滚动**完成** → `finally` 写 `Route/commit=1:finally` ⑤ S 在新滚动完成**之后**才恢复 |
| **协程挂起点** | S：`awaitStable` 第 1 个挂起点（在新滚动完成之前就被挂住） |
| **状态变化** | `GEN: 6 → 7`；Pager `currentPage: 0 → 1` |
| **恢复时的有效性检查** | ① `currentGeneration()` = 7 ≠ `gen0=6` → **失效** → `alignSkip=stale-generation` → **不写** |
| **预期路由写入次数** | **1 次**（新滚动的 `finally`）；S **0 次** |
| **预期最终状态** | `ROUTE(1)`（统计） |
| **中间态断言** | S 恢复后**不得**产生任何写入；路由**不得**被拉回 0；`alignSkip` 恰好 1 次 |
| **日志证据** | `Route/align=1:finally` → `Route/selectMain=Stats` → `A5/alignSkip=…:stale-generation` |

> **R5 与 R1 的区别**：R1 的新点击发生在 S 挂起**期间**、S 在新滚动**完成前**恢复；R5 是新滚动**已完成**后 S 才恢复。两者的失效原因相同（代际过期），但**验证了失效不依赖恢复时机**。

---

#### R6 · 用户手势结束时 Pager 尚未稳定

| 项 | 内容 |
|---|---|
| **初始状态** | `ROUTE(0)`；Pager `currentPage=0`、`settledPage=0`、静止；`OWNER=NONE`；`GEN=5` |
| **操作顺序** | ① 点「日历」→ TKN#8，`GEN=6`，P 启动 ② 手势接管 → P 取消，`cause=gesture`，`GEN=7`，S 启动（`gen0=7`）③ 用户松手，Pager 进入**回弹**：`isScrollInProgress=false` 但 `currentPage=1`、`settledPage=0` ④ S 恢复 |
| **协程挂起点** | S：`awaitStable` 第 1 个挂起点（`isScrollInProgress` 变 false 时被唤醒） |
| **状态变化** | `currentPage: 0 → 1`；`settledPage` 仍为 0（尚未落定） |
| **恢复时的有效性检查** | ① 代际有效（`7 == gen0`）→ 继续；② `isScrollInProgress == false` → 通过；③ **`currentPage(1) != settledPage(0)` → 不稳定** → `alignSkip=not-stable` → **不写**，继续等待 |
| **预期路由写入次数** | **0 次**（S）；随后由 `snapshotFlow` 回写或 S 的后续等待给出最终值 |
| **预期最终状态** | `ROUTE` == Pager 最终落定页（回弹落定后） |
| **中间态断言** | 回弹窗口内**不得**出现 `Route/align`（旧实现正是这里写了过渡值）；`alignSkip=not-stable` 恰好 1 次 |
| **日志证据** | `A5/scrollCancel=2:gesture` → `A5/alignSkip=…:not-stable` →（落定后）`Route/align=<落定页>:settle` |

---

#### R7 · Pager 稳定后 currentPage 与 settledPage 不一致

| 项 | 内容 |
|---|---|
| **初始状态** | `ROUTE(0)`；Pager `currentPage=0`、`settledPage=0`、静止；`OWNER=NONE`；`GEN=5` |
| **操作顺序** | ① 手势取消 → `GEN=6`，S 启动（`gen0=6`）② Pager 滚动结束，`isScrollInProgress=false`，但系统尚未提交新的 `settledPage` → `currentPage=2`、`settledPage=0` ③ S 的第 1 个挂起点被唤醒，第 2 个挂起点（等待 `currentPage == settledPage`）继续挂起 ④ 稍后 `settledPage` 更新为 2，S 恢复 |
| **协程挂起点** | S：`awaitStable` 的**第 2 个挂起点**（`snapshotFlow { currentPage == settledPage }.first { it }`） |
| **状态变化** | `currentPage: 0 → 2`；`settledPage: 0 → 2`（延迟提交） |
| **恢复时的有效性检查** | ① 代际有效 → ② `isScrollInProgress == false` → ③ `currentPage(2) == settledPage(2)` → **允许写** |
| **预期路由写入次数** | **1 次** |
| **预期最终状态** | `ROUTE(2)`（日历） |
| **中间态断言** | ② 与 ④ 之间**不得**有任何写入；不得写入 `currentPage` 尚未落定的中间值 |
| **日志证据** | `A5/scrollFinally` 的 `settledMatch=false` → （落定后）`settledMatch=true` → `Route/align=2:settle` |

> **R6 与 R7 的区别**：R6 是「**滚动还没停**」的窗口（`isScrollInProgress` 为 true），R7 是「**滚动已停但 `settledPage` 还没提交**」的窗口（`isScrollInProgress` 为 false）。两者都必须被 OW-7 的**双条件**拦住 —— 只看 `isScrollInProgress` 会在 R7 漏判。

---

#### R8 · 取消事件与新 Token 创建交错

| 项 | 内容 |
|---|---|
| **初始状态** | `ROUTE(0)`；Pager `currentPage=0`、静止；`OWNER=NONE`；`GEN=5` |
| **操作顺序** | ① 点「日历」→ TKN#8，`OWNER=#8`，`GEN=6`，P 启动 ② P 被手势取消 → 取消回调**尚未执行** `end(token)` ③ 用户立刻点「统计」→ `begin` 发 TKN#9，`OWNER=#9`，**`GEN=7`** ④ P 的 `finally` 恢复执行 → `end(TKN#8)` 返回 **false** → `cause=superseded` ⑤ S 不派发 |
| **协程挂起点** | P：`animateScrollToPage` 内部（取消）；`finally` 内的 `end(token)` **不是挂起点**（同步调用） |
| **状态变化** | `OWNER: #8 → #9`；`GEN: 6 → 7` |
| **恢复时的有效性检查** | ① `end(TKN#8) == false` → `cause=superseded` → **不对齐、不派发**（OW-1）；② 代际已推进 → 即使派发也会被 OW-4 拦下（双保险） |
| **预期路由写入次数** | P 的 `finally` **0 次**；新滚动完成后 **1 次**（`finally` 路径） |
| **预期最终状态** | `ROUTE(1)`（统计） |
| **中间态断言** | 旧 token **不得**产生 `Route/align`；`cause=superseded`；**不得**出现两次写入竞争 |
| **日志证据** | `A5/scrollCancel=2:gesture` → `A5/tabClick=1#9` → `A5/scrollFinally=2#8 aligned=false owned=false cause=superseded stable=false settledMatch=false` → `Route/align=1:finally`（来自新滚动） |
---

#### R9 · 旧 Token finally 晚于新 Token 执行

| 项 | 内容 |
|---|---|
| **初始状态** | `ROUTE(0)`；Pager `currentPage=0`、静止；`OWNER=NONE`；`GEN=5` |
| **操作顺序** | ① 点「日历」→ TKN#8，`OWNER=#8`，`GEN=6`，P1 启动 ② P1 被手势取消，其 `finally` **被挂起**（调度延迟）③ 用户点「统计」→ TKN#9，`OWNER=#9`，`GEN=7`，P2 启动 ④ P2 完成 → 写 `Route/commit=1:finally` ⑤ **P1 的 `finally` 此刻才执行** → `end(TKN#8)` 返回 false |
| **协程挂起点** | P1 的 `finally` 在异常路径上被挂起（协程调度点），P2 的完成先于它恢复 |
| **状态变化** | `OWNER: #8 → #9`；`GEN: 6 → 7`；路由在 ④ 之后已是 1 |
| **恢复时的有效性检查** | `end(TKN#8) == false` → `cause=superseded` → **不对齐**（OW-1）；代际也过期（双保险） |
| **预期路由写入次数** | P1 **0 次**；P2 **1 次** |
| **预期最终状态** | `ROUTE(1)`（统计），且**不再被旧协程改动** |
| **中间态断言** | ⑤ 之后路由**不得**回到 0 或 2；总写入次数**恰好 1** |
| **日志证据** | `A5/scrollFinally=1#9 aligned=true owned=true cause=none` → `Route/align=1:finally` → `A5/scrollFinally=2#8 aligned=false owned=false cause=superseded` |

---

#### R10 · 重复稳定通知导致重复路由写入

| 项 | 内容 |
|---|---|
| **初始状态** | `ROUTE(0)`；Pager `currentPage=0`、静止；`OWNER=NONE`；`GEN=5` |
| **操作顺序** | ① 手势取消 → `GEN=6`，S 启动（`gen0=6`）② Pager 落定，S 恢复并通过全部检查 → 写 1 次 ③ **稳定信号重复到达**（`snapshotFlow` 因其它原因再次发射、`first` 之外的额外通知、用户再次轻微拖动后回弹） |
| **协程挂起点** | S：`awaitStable` 的两个 `first` 挂起点（**一次性语义**，`first` 之后协程即结束） |
| **状态变化** | 无路由变化（重复稳定不改变 Pager） |
| **恢复时的有效性检查** | ② 写入前：幂等检查 `current == target` → 第二次**短路**；③ 即使再次触发，`first` 已经结束协程，不会二次写 |
| **预期路由写入次数** | **恰好 1 次** |
| **预期最终状态** | `ROUTE` == Pager 落定页 |
| **中间态断言** | `Route/selectMain` / `Route/commit` 出现次数**恰好 1**；不得出现第二次写入 |
| **日志证据** | `Route/align=<落定页>:settle` → `Route/selectMain=<AppPage>` → `Route/commit=<idx>:settle`（**各恰好 1 次**） |
---

#### R11 · 用户手势后最终必须对齐到 Pager 实际停留页（端到端最终态）

| 项 | 内容 |
|---|---|
| **初始状态** | `ROUTE(0)`；Pager `currentPage=0`、静止；`OWNER=NONE`；`GEN=5` |
| **操作顺序** | ① 点「日历」（目标是 2）→ TKN#8，`GEN=6`，P 启动 ② 动画途中用户**向左滑到「统计」（1）并停住** → P 被取消，`cause=gesture`，`GEN=7`，S 启动（`gen0=7`）③ S 等待稳定 ④ Pager 落定于 1，S 恢复并写入 |
| **协程挂起点** | S：`awaitStable` 第 1 个挂起点 → 第 2 个挂起点 → 写入 |
| **状态变化** | `currentPage` 由 0 变到 1，中途经过中间页 2，最终回到 1；最终 `currentPage=1`、`settledPage=1` |
| **恢复时的有效性检查** | ① 代际有效（无新意图）→ ② 静止 → ③ `currentPage == settledPage` → **允许写**；写入目标是 `settledPage`（= 1），**不是**点击目标 2 |
| **预期路由写入次数** | **1 次**（目标 = 1） |
| **预期最终状态** | `ROUTE(1)`（统计）—— **等于 Pager 实际停留页**，尽管点击目标是「日历」 |
| **中间态断言** | 路由取值序列**不得**出现 2（日历）——「点击目标页」与「实际停留页」不同正是本场景的要点；**不得**出现 0 或过渡值 |
| **日志证据** | `A5/scrollCancel=2:gesture` → `A5/alignCommit=… gen=7 genValid=true` → `Route/align=1:settle` → `Route/selectMain=Stats` |

> **R11 是 OBS-2 的验收核心**：它直接验证「最终路由 == Pager 实际停留页」（INV-1），且验证旧协程**没有**把路由拉到中间页或点击目标页。

---

#### R12 · 取消动画 + 快速点击 + 手势的组合不得导致路由卡死

| 项 | 内容 |
|---|---|
| **初始状态** | `ROUTE(0)`；Pager `currentPage=0`、静止；`OWNER=NONE`；`GEN=5`；**系统开启「移除动画」**（`reduceMotion == true` → 走 `scrollToPage` 分支） |
| **操作顺序** | ① 点「日历」→ `scrollToPage(2)` 瞬时完成 ② 紧接着快速点「统计」→ TKN#9 ③ 在第 ② 步的 `finally` 之前插入一次手势拖动 ④ 三个动作的 `finally` 与取消回调交错执行 ⑤ 全部结束后观察路由 |
| **协程挂起点** | 多个协程的 `finally` 与取消回调在同一个调度批次内交错；`reduceMotion` 分支没有动画挂起点 |
| **状态变化** | `GEN` 多次推进；`OWNER` 多次变更；Pager `currentPage` 快速变化 |
| **恢复时的有效性检查** | 每个 `finally` 独立判定：所有权（`end(token)`）+ 取消原因（`cause`）+ 代际；**只有一个**能同时通过并写入 |
| **预期路由写入次数** | **恰好 1 次**（最后一次有效操作决定） |
| **预期最终状态** | `ROUTE` == Pager 最终停留页；**无死锁、无卡死、无协程泄漏**；底部胶囊与标题一致 |
| **中间态断言** | 路由**不得**停在中间页；**不得**出现 0 次写入（卡死）；**不得**出现大于 1 次的写入（竞争） |
| **日志证据** | 连续的 `A5/scrollFinally` 中**有且仅有一条** `owned=true` 且伴随 `Route/align`；其余 `owned=false` 或 `cause` 不是 `none` |
### 4.12 十二个异步场景的共性断言（v2.1）

| 断言 | 含义 | 覆盖场景 |
|---|---|---|
| **A1** | **代际失效**后不得写入 | R1 / R2 / R3 / R5 |
| **A2** | **作用域取消**后不得写入、**不得派发** | R4 |
| **A3** | **未稳定**时不得写入（`isScrollInProgress` 或 `currentPage != settledPage`） | R6 / R7 |
| **A4** | **幂等**：重复通知不产生重复写 | R10 |
| **A5** | **中间态无错误写入**（不只是最终正确） | 全部 |
| **A6** | **最终路由 == Pager 停留页** | 全部 |
| **A7** | **旧 token 不得抢新 token 的收尾** | R8 / R9 |
| **A8**（v2.1） | **取消原因互斥且可判定**：每一次取消都必须落到**唯一**一个 `cause`（`gesture` / `superseded` / `scope` / `unknown`），不得为空、不得同时成立 | R1 / R2 / R4 / R8 / R9 |
| **A9**（v2.1） | **所有路由写入经统一入口**：`Route/commit` 的出现次数必须等于 `Route/selectMain` 的出现次数（α 方案，§6.4.3） | 全部 |
| **A10**（v2.1） | **未派发的对齐器不得产生 `alignSkip`**（作用域取消连对齐器都没启动，不该有 skip 记录） | R4 |
| **A11**（v2.1） | **组合场景不得卡死**：快速点击 + 手势 + 移除动画的组合下，写入次数**恰好 1**，且无协程泄漏 | R12 |

---

## 5. 候选方案对比（v2）

### 5.1 方案清单

| 编号 | 方案 | v2 状态 |
|---|---|---|
| **A1** | **取消让权 + 取消路径专属的一次性稳定对齐器**（v2 增强：取消原因三分类前置 + 代际机制 + 唯一写入口） | **推荐** |
| **A2** | 取消让权 + 全局稳定观察器（所有翻页统一由常驻观察器对齐） | 备选（仅当 GPT 要求「Pager → 路由只有一条写路径」时启用） |
| **B** | 取消让权 + 完全不对齐 | 不推荐（§5.3） |
| **C** | 取消让权 + 立即按 `settledPage` 对齐 | 不推荐（§5.5） |
| **D** | `finally` 里加 `isScrollInProgress` 判据 | 不推荐（§5.4） |
| ~~E~~ | 区分取消原因 | **v2：已并入 A1**（理由见 §5.6） |
| **F** | 禁止改架构类方案 | 全部不采用（§5.7） |

**关于 E 的处置（v2 变更）**：v1 把 E 列为独立方案，判定「不作为独立方案，只建议把取消原因加进打点」。GPT §4.1 明确要求「取消原因三分类 + 可靠识别」是**必须补齐的前置条件**，不是可选的诊断增强。v2 因此把 E **并入 A1，作为它的判定前置**：取消原因的三分类（§1.5）与判定顺序（§1.5.3）决定「让权 / 派发对齐器 / 不派发」三条分支走向，是 A1 正确工作的必要输入，而不是与 A1 并列的替代方案。

### 5.2 逐项对比

| 维度 | A1 | A2 | B | C | D | F |
|---|---|---|---|---|---|---|
| 修掉 OBS-2（r2/r4 的 `Route/align=1`） | ✅ | ✅ | ✅ | ✅ | ⚠️ 见 §5.4 | — |
| 不写过渡态 `currentPage` | ✅ | ✅ | ✅ | ✅ | ❌ | — |
| 覆盖「取消后 Pager 未稳定窗口」（S10） | ✅ | ✅ | ⚠️ 见 §5.3 | ❌ | ❌ | — |
| 覆盖「手势刚结束」（S8） | ✅ | ✅ | ⚠️ | ❌ | ❌ | — |
| 保证最终路由 == Pager 停留页 | ✅ | ✅ | ⚠️ | ❌ | ❌ | — |
| **区分三种取消原因**（GPT §4.1） | ✅ | ⚠️ 需另加分派 | ❌ | ❌ | ❌ | — |
| **对齐器所有权明确 + 代际失效**（GPT §4.2） | ✅ | ⚠️ 常驻器所有权模型不同 | ❌ | ❌ | ❌ | — |
| **所有路由写经唯一入口**（GPT §4.3） | ✅（含一个待裁决点，§6.4.3） | ✅ | ⚠️ 两条路径语义不一 | ⚠️ | ⚠️ | — |
| **可承载 10 个异步竞态测试**（GPT §4.4） | ✅ | ✅ | ❌ | ❌ | ❌ | — |
| 对 S1 / S6 / S7 / S9 的行为影响 | **无**（仅取消路径新增分支） | **有**（常态多一次检查） | 无 | 无 | 无 | — |
| 回归面 | 小 | 中 | 小 | 小 | 小 | — |
| 引入新的「路由写入者」 | 1（取消路径专属，一次性） | 1（常驻） | 0 | 0 | 0 | — |
| 实现复杂度 | 中 | 中 | 极低 | 低 | 低 | — |
| 违反 N1–N10 | 否 | 否 | 否 | 否 | 否 | **是** |

### 5.3 方案 B 的缺口（为什么「完全不对齐」不够）

方案 B 假设「回写路径总能给出正确答案」。真机 r2 / r3 / r4 看起来支持这个假设，但存在一个**结构性缺口**：

```text
  点「日历」(0 → 2)：
    currentPage = 1（中间页）→ 回写被 guardIgnore 拦下 → 路由仍是「今日」
    此时用户手势接管 → 取消 → 方案 B 不对齐
    用户手势最终停在「统计」(1)

  问题：currentPage 从 1 变成 1（没有变化）
        snapshotFlow { currentPage }.distinctUntilChanged() **不会再发一次**
        ⇒ 回写不会把路由改成「统计」
        ⇒ 路由停留在「今日」，而 Pager 显示「统计」  ← ★ 违反 INV-1
```

**结论**：方案 B 会把「一次抖动」换成「一次**状态不一致**」，后者更糟（标题与页面长期对不上）。**除非**能证明该缺口不可达（例如「中间页被忽略的时刻必然伴随 `currentPage` 还会再变一次」），否则不推荐。

**该缺口的可达性**：在 r2 / r4 中，用户的拖动把 Pager 从 1 拉回 0，`currentPage` 从 1 变成 0 → 确实会再发一次。但如果用户只是「按住又松手」让 Pager 弹回 1，就**不会**再发。**这个缺口是可达的**，所以方案 B 不推荐。

> **v2 补注**：这个缺口正是 §6.4 统一入口要解决的那一半——统一入口让「Pager 位置 → 路由」只有**一套语义**（幂等 + 只写路由），缺口是否还存在取决于 `snapshotFlow` 回写是否也走统一入口（**待 GPT 裁决**，见 §6.4.3）。在裁决之前，本设计按「对齐器存在」实现，即缺口由对齐器兜住。

### 5.4 方案 D 的漏洞（为什么 `isScrollInProgress` 不能当唯一判据）

方案 D 的直觉是：「取消瞬间如果 Pager 还在滚动，说明是用户在拖，就不对齐」。但有三个问题：

1. **不区分用户手势与程序化滚动**：`isScrollInProgress` 对两者都为 true。竞争的程序化滚动（来源 b）也会被判成「用户在拖」→ 恰好也是不对齐，**结果凑巧正确**，但理由是错的，将来容易改错；
2. **取消瞬间的时序不确定**：`CancellationException` 抛出后到 `finally` 执行之间，`isScrollInProgress` 可能已经从 true 变成 false（手势刚好结束，见 S8）；也可能仍然是 true（用户还在拖）。**同一个判据在两个时刻给出不同答案**，无法作为可靠依据；
3. **不覆盖 S10**：`isScrollInProgress == false` 但 `currentPage != settledPage` 的未稳定窗口里，方案 D 会**认为已稳定并对齐到过渡值**——正好是它想避免的情形。

**结论**：`isScrollInProgress` 只能作为**辅助**信号（稳定后对齐器里的一个必要条件），**不能**作为取消路径的**唯一**判据。取消与否必须由「本次协程是否抛出 `CancellationException`」这个**确定**事实来判断。

### 5.5 方案 C 的漏洞

方案 C 在取消瞬间对齐到 `settledPage`。问题是：**用户手势进行中，`settledPage` 往往还是「手势开始前的那一页」**（Compose 的定义：drag 中 `settledPage` 是拖动前停留的页）。用它对齐 = 把路由拉回**用户正要离开的旧页**，比现状更糟（现状至少对齐到了 `currentPage`）。

**结论**：必须**等待稳定**（`currentPage == settledPage`）之后再用 `settledPage`，不能立即用。

### 5.6 v2 新增：为什么「取消原因三分类」不能做成独立方案

v1 把「区分取消原因」当成**可选的诊断增强**，GPT §4.1 把它提升为**必须的前置条件**。差别在于：v1 的三种来源（手势 / 竞争 / 作用域）在**行为上**是同一种——都不对齐——所以「不区分」也能修掉 OBS-2；v2 之所以必须区分，是因为 v2 引入了**代际机制**与**补偿性对齐器**：

| 取消原因 | 对齐器是否启动 | 代际是否推进 | 若不分类会怎样 |
|---|---|---|---|
| **gesture**（用户手势接管） | **启动** | **推进**（使旧对齐器失效） | 手势接管后，旧对齐器若继续运行，会在用户已经拖走之后补一次对齐 → 路由被拉回旧页 |
| **superseded**（新滚动取代） | 不启动 | **推进**（由新滚动本身推进） | 旧协程与新协程同时派发对齐 → 两次写入竞争 |
| **scope**（作用域取消） | **不启动**，且**不得派发任何任务** | 不推进 | 组合已离开仍启动对齐器 → 泄漏 / 崩溃 |

**结论**：三分类是 A1 内部**三个不同分支**的输入，不是并列方案。若强行做成「独立方案 E」，A1 与 E 会各自需要一次取消判定，产生**两处**判定 → 两处都可能判错 → 反而更容易出错。**故 v2 合并为 A1 的 §6.2 要点 1 与 §6.3 代际机制。**

### 5.7 方案 F（禁止项）逐一说明为何不采用

| 禁止项 | 为何不采用 |
|---|---|
| 整页 `Crossfade` / `AnimatedContent` | 会重建子树、重播 `remember { Animatable(0f) }` → **闪一帧**（项目 Compose 红线） |
| 关动画 | 破坏「滑动跟手」的既有体验，且**不解决路由写错**这个根因 |
| 延迟固定毫秒 | 用时间窗掩盖竞态；设备性能、动画时长、手势速度一变就失效；**是掩盖不是修复** |
| 改 `beyondBoundsPageCount` | 与路由对齐无关；且会改变预加载行为，影响性能基线 |
| 改主 tab 动画规格（`TabMotion.scrollSpecFor`） | 与路由对齐无关；R10 已验证的动画手感不能动 |
| **改 `TabScrollGuard` 的 token 机制**（v2 补充） | R10 已真机验证通过；改它会牵连 §9 的回归判据（N7 / N10） |

---
## 6. 推荐方案（v2）

### 6.1 选定：**A1 · 取消让权 + 取消路径专属的一次性稳定对齐器**

**一句话**：

> **程序化滚动被取消时，旧协程放弃对齐权；取消原因若为「用户手势接管」，改由一个「等 Pager 稳定后再对齐」的一次性对齐器，把路由对齐到 `settledPage`；所有基于 Pager 状态的路由写入都经唯一入口 `commitRouteFromPager()`。**

**为什么选 A1 而不是 A2**：A2 更优雅（对齐路径唯一），但它**改变了常态行为**（每一次翻页都多走稳定观察器），回归面从「取消路径」扩大到「全部翻页」。在本项目「R10 已真机验证通过、必须保持」的约束下，**A1 把改动限制在「本来就出错的分支」**，风险最小。

**v2 相对 v1 的四处增强**：① 取消原因三分类前置（§6.2 要点 1）；② 对齐器所有权 9 条 + 代际机制（§6.3）；③ 唯一路由写入口（§6.4）；④ 可注入依赖，使 10 个异步竞态场景可被确定性测试（§7.4）。

### 6.2 实现要点

#### 要点 1 · 取消原因的三分类判定（v2 新增，替代 v1 的 SETTLED/CANCELLED 二分）

v1 用 `SETTLED / CANCELLED` 两分，v2 改为四态：`NONE` / `GESTURE` / `SUPERSEDED` / `SCOPE`。判定必须是一个**纯函数**，以便表驱动单测：

```kotlin
// ui/TabScrollGuard.kt  新增（与守卫同文件，保持内聚）
internal enum class CancelCause {
    NONE,       // 未被取消，正常收尾
    GESTURE,    // 用户手势接管：旧协程让权，派发补偿性对齐器
    SUPERSEDED, // 被新的程序化滚动取代：不对齐、不派发
    SCOPE,      // 协程作用域被取消：不对齐、不派发、不启动任何任务
}

internal object CancelCauseResolver {
    /**
     * 取消原因的**确定性**判定。纯函数，便于表驱动单测与变异验证。
     *
     * 判定顺序（缺一不可，顺序不可交换）：
     *  1. scopeActive == false  → SCOPE       —— 作用域取消优先；此时绝不能派发任何任务；
     *  2. tokenOwned == false    → SUPERSEDED  —— token 已被新点击取代；
     *  3. userDragging == true   → GESTURE     —— 检测到用户拖动接管；
     *  4. 其余（未能识别）        → GESTURE     —— 保守当作手势（安全方向是补偿性对齐）。
     *
     * 正确性说明：
     *  - 第 1 条必须在最前：作用域已取消时，派发对齐器会泄漏任务；
     *  - 第 4 条的保守方向是有依据的：三种原因里只有 GESTURE 需要补偿性对齐，
     *    误判为 GESTURE 的最坏后果是「多一次幂等对齐」，误判为其它两种会导致路由滞留。
     */
    fun resolve(scopeActive: Boolean, tokenOwned: Boolean, userDragging: Boolean): CancelCause =
        when {
            !scopeActive -> CancelCause.SCOPE
            !tokenOwned  -> CancelCause.SUPERSEDED
            userDragging -> CancelCause.GESTURE
            else         -> CancelCause.GESTURE  // 保守
        }
}
```

**为什么第 4 条保守当 GESTURE 而不是「未知」**：见 §1.5.3。三种原因中只有 GESTURE 需要**补偿**；保守当 GESTURE 的最坏后果是「多一次幂等对齐」（无副作用），保守当其它原因的最坏后果是「路由滞留」（标题与页面长期不一致）。**两个方向的错误代价不对称，所以必须朝补偿方向倒。**

**`userDragging` 的信号来源（未验证，属 N-OBS-1）**：候选为 `PagerState.interactionSource` 的拖动状态（`collectIsDraggedAsState`）。本项目 Compose BOM 2024.06 / foundation 版本上**必须先确认该 API 可用**；不可用则回退到「未能识别 → 保守 GESTURE」路径（上面第 4 条），此时**行为仍然正确**，只是失去诊断精度。

#### 要点 2 · 稳定后对齐器（取消路径专属，一次性）

```kotlin
// ui/AppNav.kt  新增
fun startSettleAligner(gen0: Long) {
    tabScope.launch {
        // 1) 等 Pager 静止
        snapshotFlow { pagerState.isScrollInProgress }.first { !it }
        // 2) 等「当前页 == 稳定页」——这一步同时挡住 S8 与 S10
        snapshotFlow { pagerState.currentPage == pagerState.settledPage }.first { it }
        // 3) 代际校验（v2 新增，§6.3）：过期即失效，不得写入
        if (scrollGeneration.current != gen0) {
            PerfProbe.a5("alignSkip", index, seq, "stale-generation")
            return@launch
        }
        // 4) 再等一次静止（防止第 2 步与第 5 步之间又被手势打断）
        if (pagerState.isScrollInProgress) return@launch
        // 5) 幂等对齐（唯一入口，§6.4）
        commitRouteFromPager(pagerState.settledPage, source = "settle")
    }
}
```

**为什么第 2 步不可省**：

- S8（手势刚结束）：`isScrollInProgress` 已 false，但 `currentPage` 可能还在回弹途中 → `currentPage != settledPage` → 继续等；
- S10（未稳定窗口）：同样 `currentPage != settledPage` → 继续等；
- 只有 `currentPage == settledPage` 时，Pager 才**真的**停在某一页上，这时对齐才是有意义的。

**为什么第 3 步不可省**：第 2 步与第 5 步之间，用户可能又发起新手势或点了别的 tab。没有代际校验时，旧对齐器会在新动作之后补一次对齐，把路由拉回旧页 —— 这正是 §4.11 的 R1 / R2 要防的中间态错误写入。

**为什么第 4 步不可省**：第 2 步与第 5 步之间，用户可能又发起新手势。补一次静止检查，避免「刚稳定就又被拖走」的竞态。

#### 要点 3 · 决策抽成纯函数（便于单测与变异验证）

决策分成**两个纯函数**，分别对应两条写入路径：

```kotlin
// ui/TabScrollGuard.kt  新增
internal enum class ScrollOutcome { SETTLED, CANCELLED }

internal object ScrollAlignPolicy {
    /**
     * 路径一：协程收尾时（finally）是否允许对齐。
     * 规则（缺一不可）：
     *  1. tokenOwned：本次协程仍是守卫主人（否则是别人的收尾）；
     *  2. outcome == SETTLED：被取消的滚动一律不对齐（过渡态不是真相）；
     *  3. !isScrollInProgress：Pager 已静止；
     *  4. currentPage == settledPage：已落到稳定页（挡住未稳定窗口）；
     *  5. routePage != currentPage：幂等——已经是目标页就不必再写。
     */
    fun shouldAlignAtFinally(
        tokenOwned: Boolean,
        outcome: ScrollOutcome,
        isScrollInProgress: Boolean,
        currentPage: Int,
        settledPage: Int,
        routePage: Int
    ): Boolean =
        tokenOwned &&
        outcome == ScrollOutcome.SETTLED &&
        !isScrollInProgress &&
        currentPage == settledPage &&
        routePage != currentPage

    /**
     * 路径二：稳定后对齐器是否允许写入。
     * 规则（缺一不可）：
     *  1. genValid：代际未过期（§6.3）；
     *  2. !isScrollInProgress：Pager 已静止；
     *  3. currentPage == settledPage：已落到稳定页；
     *  4. routePage != settledPage：幂等。
     * 注意：**不含** SETTLED 条件——取消路径正是靠对齐器来收尾的。
     */
    fun shouldAlignAtSettle(
        genValid: Boolean,
        isScrollInProgress: Boolean,
        currentPage: Int,
        settledPage: Int,
        routePage: Int
    ): Boolean =
        genValid &&
        !isScrollInProgress &&
        currentPage == settledPage &&
        routePage != settledPage
}
```

**注意**：这两个纯函数**分别**表达两条路径的判定，不共用「SETTLED」这一条。v1 的单函数版本把两者混在一起，v2 拆开的原因是要点 1 的三分类与 §6.3 的代际校验需要各自的输入。

#### 要点 4 · 打点增强（可观测性，不改行为）

打点的完整规范见 §6.6。

#### 要点 5 · 明确不做的事

- **不改** `TabScrollGuard` 的 token 机制、不可变快照实现（N7 / N10）；
- **不改** `HorizontalPager` 架构、`beyondBoundsPageCount`、主 tab 动画规格、关动画行为（N1–N4）；
- **不加**任何固定延迟（N5）；
- **不用**整页 `Crossfade` / `AnimatedContent`（N6）；
- **不重开** R10 原 P2（N7）；
- **不引入**第二套独立路由状态（N9）；
- **不新增**任何「路由 → Pager」方向的写（N9 / §6.4.4）。
### 6.3 对齐器的所有权：9 条边界 + 代际机制（v2 新增，GPT §4.2）

#### 6.3.1 为什么需要「所有权」

v1 说「把对齐权交给稳定后对齐器」，但没写清**谁**有权启动、**何时**失效、**能不能**重复启动。结果是三个歧义：

| 歧义 | 后果 | 对应场景 |
|---|---|---|
| 谁能启动对齐器？ | 作用域取消也启动 → 泄漏任务 | R4 |
| 能不能启动第二个？ | 两个对齐器同时写 → 写入竞争 | R3 |
| 旧对齐器什么时候失效？ | 旧对齐器在新动作之后补写 → 路由被拉回旧页 | R1 / R2 |

#### 6.3.2 9 条所有权边界

| 编号 | 规则 | 违反时的现象 | 验证场景 |
|---|---|---|---|
| **OW-1** | **唯一启动源**：只有 `cause == GESTURE` 的取消路径可以启动对齐器；`SUPERSEDED` / `SCOPE` 一律不启动 | 非手势取消也派发对齐器 → 多余写入 | R4 |
| **OW-2** | **一次取消最多一个对齐器**：同一 `seq` 不得启动第二个 | 两个对齐器竞争写 | R3 |
| **OW-3** | **代际捕获**：对齐器启动时必须记录 `gen0 = scrollGeneration.current` | 无法判断自己是否过期 | 全部 |
| **OW-4** | **代际失效**：写入前必须比对 `scrollGeneration.current == gen0`；不一致即失效并记 `A5/alignSkip=…:stale-generation`，**不得写入** | 旧对齐器在新动作后补写 → 路由跳回旧页 | R1 / R2 / R3 |
| **OW-5** | **作用域绑定**：对齐器在 `tabScope` 内启动，不得脱离组合生命周期 | 组合离开后仍持有 Pager 引用 | — |
| **OW-6** | **作用域取消不派发**：`SCOPE` 分支既不对齐、也不启动对齐器、也不启动任何新任务 | 页面退出后后台任务写路由 → 崩溃 / 泄漏 | R4 |
| **OW-7** | **稳定性双条件**：必须**同时**满足 `!isScrollInProgress` **且** `currentPage == settledPage` 才允许写 | 在未稳定窗口写入过渡值 | R6 / R7 |
| **OW-8** | **一次性**：对齐器只执行一次（`first { }` 语义），不循环、不重试 | 同一动作产生多次写入 | R10 |
| **OW-9** | **幂等**：写入前比较当前路由页，已等于目标页则不写 | 重复写入 → 重复副作用 | R10 |

**OW-7 为什么是「双条件」而不是任一**：`isScrollInProgress == false` 但 `currentPage != settledPage` 的窗口真实存在（S10，回弹阶段），单看静止会误判为已稳定。反过来 `currentPage == settledPage` 但仍在滚动（如吸附动画的起点）也不代表已落定。

#### 6.3.3 代际（generation）机制

```text
  scrollGeneration : Long（单调递增，原子）

  代际推进点（只有两处）：
    ① 新程序化滚动开始（tabGuard.begin 之后立即 gen++）
    ② 用户手势接管（检测到 userDragging 由 false→true 时 gen++）

  对齐器生命周期：
    启动时  gen0 = scrollGeneration.current
    写入前  if (scrollGeneration.current != gen0) → 失效，记 alignSkip=stale-generation，return
```

| 事件 | 是否推进代际 | 说明 |
|---|---|---|
| 新程序化滚动开始 | **推进** | 新的导航意图 → 所有旧对齐器立即失效 |
| 用户手势接管 | **推进** | 新的用户意图 → 所有旧对齐器立即失效 |
| 对齐器自身写入 | 不推进 | 写完就结束，无后续动作 |
| `snapshotFlow` 回写 | 不推进 | 它不是「意图」，只是位置变化 |

**降级情形（必须写进实施说明）**：若 §6.2 要点 1 的 `userDragging` 信号不可用（N-OBS-1），代际只能在**第 ①**处推进。此时 OW-4 对「用户再次手势」的失效能力下降，安全性退化为依赖 OW-7 的双条件。该情形下**必须**在真机复核中额外验证 R2（对齐器等待期间再次手势）仍然**不产生错误写入**，并把结果登记为 N-OBS-2 的答案。

#### 6.3.4 所有权与 §4.11 场景的对应

| 场景 | 触发的所有权条款 | 期望日志 |
|---|---|---|
| R1 对齐器等待期间再次点 tab | OW-3 + OW-4 | `A5/alignSkip=…:stale-generation` |
| R2 对齐器等待期间再次手势 | OW-3 + OW-4 + OW-7 | `A5/alignSkip=…:stale-generation` |
| R3 新旧对齐器同时等待 | OW-2 + OW-4 | 恰好一次 `alignSkip` |
| R4 作用域取消 | OW-1 + OW-6 | **无** `alignSkip`、**无** `align` |
| R5 取消后新滚动正常完成 | OW-4 | 新滚动的 `finally` 正常 `align` |
| R6 / R7 未稳定窗口 | OW-7 | 无写入 |
| R8 / R9 token 归属 | OW-1（不越权） | 旧 token 无 `align` |
| R10 重复触发 | OW-8 + OW-9 | 恰好一次写入 |

### 6.4 唯一的路由写入口 `commitRouteFromPager()`（v2 新增，GPT §4.3）

#### 6.4.1 现状的问题

当前有**两个**把 Pager 状态写进路由的地方：

| 位置 | 代码 | 语义 |
|---|---|---|
| `AppNav.kt:437-461` | `snapshotFlow { pagerState.currentPage }` → `viewModel.selectMain(...)` | **跟随**：页面位置变了就跟随 |
| `AppNav.kt:391-396` | `alignRouteToPager(landed)` → `viewModel.selectMain(...)` | **对齐**：程序化滚动收尾时对齐 |

两处都直接调 `viewModel.selectMain`，但**没有共享幂等语义、没有共享「只写路由不写 Pager」的约束、没有共享打点口径**。这正是 §5.3 那个缺口（「回写路径未必能兜住」）的结构性来源。

#### 6.4.2 统一入口的契约

```kotlin
// ui/AppNav.kt  唯一入口
/**
 * 唯一的「Pager 状态 → 路由」写入口。
 *
 * 契约：
 *  1. 幂等：目标页 == 当前路由页时不写（不产生 selectMain 副作用、不产生打点）；
 *  2. 只写路由，绝不写 Pager（不得反向 set currentPage / scrollToPage / animateScrollToPage）；
 *  3. 不维护影子路由状态：幂等判断直接读 viewModel.page.value，不缓存「上次对齐到哪」；
 *  4. 来源标记只进打点，不进业务状态（不新增持久化字段、不进备份）。
 */
fun commitRouteFromPager(landed: Int, source: String) {
    val page = MAIN_PAGES.getOrNull(landed) ?: return
    // 历史语义保留：对齐调用打在幂等短路**之前**（旧实现 AppNav.kt:392 即如此）
    if (source == "finally" || source == "settle") {
        PerfProbe.event("Route/align=$landed:$source")          // 语义：执行了一次对齐调用
    }
    val current = AppPage.mainOf(viewModel.page.value)
    if (current == page) return                                  // 契约 1：幂等
    PerfProbe.event("Route/selectMain=$page")                  // 语义：路由确实会被写（历史同名同义）
    PerfProbe.event("Route/commit=$landed:$source")             // 新规范名：统一入口提交 + 来源
    viewModel.selectMain(page)                                  // 契约 2：只写路由
}
```

#### 6.4.3 三个调用点（α 已定稿：全部经唯一入口）

| 调用点 | 何时调用 | `source` | 说明 |
|---|---|---|---|
| 协程 `finally`（`cause == NONE`） | 正常收尾且 `shouldAlignAtFinally` 为真 | `finally` | 程序化滚动的收尾对齐 |
| 稳定后对齐器 | 取消路径（`cause == GESTURE`）且 `shouldAlignAtSettle` 为真 | `settle` | 手势补偿对齐 |
| `snapshotFlow` 回写 | Pager 页变化（未被 `guardIgnore` 拦下） | `snapshot` | **跟随式回写**（α 方案新增接入） |

**α 方案已定稿（GPT v2.1 · OBS-2-1）**：**统一使用 `commitRouteFromPager()`，三个调用点全部经它写入**；`snapshotFlow` 回写**不再**直调 `viewModel.selectMain`。

> **α 下的精确表述（消除「第二条路径」的歧义）**：
> **写入函数只有 1 个**（`commitRouteFromPager`）；**触发源有 3 个**（`snapshotFlow` / `finally` / 稳定后对齐器）。
> 三者都**只能**调用这一个函数，函数内部不区分来源地执行同一套幂等判断。
> 因此「Pager → 路由」在**写入层面**只有一条路径；A-01 由此**关闭**。

**八条硬性约束（GPT v2.1 逐条落地）**：

| 编号 | 约束 | 落地方式 |
|---|---|---|
| **α-1** | `snapshotFlow` 的 Pager → 路由回写经过该入口 | 回写改为 `commitRouteFromPager(index, "snapshot")`；**不再**直调 `viewModel.selectMain` |
| **α-2** | 程序化滚动稳定后的路由对齐也经过该入口 | `finally` 与对齐器都调 `commitRouteFromPager(landed, "finally" / "settle")` |
| **α-3** | 所有路由写入执行**统一的幂等判断** | 幂等判断集中在入口内（读 `viewModel.page.value` 与目标页比较）；`AppViewModel.selectMain` 内部的二次去重**保留不动**（CR-17 ② 的既有实现，`AppViewModel.kt:645-647`） |
| **α-4** | Pager 仍然是页面位置的**唯一真相源** | 入口入参 `landed` **一律取自 `pagerState`**；入口内**不读**路由状态来决定「去哪里」，只读它判断「要不要写」 |
| **α-5** | 不允许增加**第二套路由状态** | 入口不缓存「上次对齐到哪」；不新增任何路由镜像字段（N9 / CB-2） |
| **α-6** | 不允许从路由**反向驱动** Pager | 入口内**不得**出现 `scrollToPage` / `animateScrollToPage` / `currentPage =`（CB-1） |
| **α-7** | 保持 CR-17 的**单向回写**原则 | 回写方向仍是 Pager → 路由；入口不引入反向边（见 §6.4.3.1 的冲突检查） |
| **α-8** | 保留 `Route/commit` 与 `Route/align` 的日志区分，保证历史证据可比对 | 见 §6.6.2 / §6.6.3：两个打点各答一个问题，**历史字段语义不变** |

#### 6.4.3.1 与 CR-17 的冲突检查（GPT v2.1 要求：先指出冲突，再解决）

**CR-17 的三条约束**：① Pager 是唯一真相源；② 点 tab 只 `animateScrollToPage`，**路由由 `snapshotFlow { currentPage }` 单向回写**；③ **禁止双向写**。

| CR-17 约束 | α 是否冲突 | 论证 |
|---|---|---|
| ① Pager 是唯一真相源 | **不冲突** | α 没有新增任何位置真相源；入口的 `landed` 全部来自 `pagerState` |
| ② 路由由 `snapshotFlow { currentPage }` 单向回写 | **不冲突** | α **保留 `snapshotFlow` 作为回写的触发源**，只把「回写动作」改为经过统一入口；**触发源与写入点分离**不违反「由 snapshotFlow 回写」 |
| ③ 禁止双向写 | **不冲突** | α 涉及的三条写入路径**方向仍是 Pager → 路由**，没有任何一条从路由反推 Pager（CB-1） |

**结论：α 与 CR-17 无冲突，CR-17 不需要任何改动。**

**但必须记录 CR-17 原文里一处需要澄清的地方**：CR-17 ② 描述的是「路由由 `snapshotFlow` 回写」这一**常态路径**，而 `finally` 的对齐路径在 R10 之前就已存在（`AppNav.kt:391-396` 的 `alignRouteToPager`）。α 并未增加新方向，只是把三条**既有**写入路径**收敛到一个函数**。因此：

> **α 是对 CR-17 的「实现收敛」，不是对 CR-17 原则的修改。**
> 需求文档的 CR-17 条文**不需要改**；本设计**不动** CR-17（与本轮「不得修改 CR-17 语义」的约束一致）。

> **A-01 由此关闭**（见 `docs/CR-30-阶段A-未决问题清单.md` §1.3 的状态更新）。

#### 6.4.4 禁止事项（CB-1 ~ CB-6）

| 编号 | 禁止 | 理由 |
|---|---|---|
| CB-1 | 统一入口**不得**反向写 Pager（`scrollToPage` / `animateScrollToPage` / `currentPage =`） | 违反 CR-17「单向」与 α-6 |
| CB-2 | 统一入口**不得**维护影子路由状态（缓存上次值） | 会与真实路由漂移，且违反 N9 / α-5 |
| CB-3 | 统一入口**不得**把来源标记写进持久化字段 / 备份 | 会动备份格式，违反本轮边界 |
| CB-4 | 统一入口**不得**在幂等短路时**跳过打点**的对齐语义（见 §6.6.3 的双打点约定） | 会改变 `Route/align` 的历史语义，破坏 R10 证据可比对 |
| CB-5 | 任何调用点**不得**绕过统一入口直调 `viewModel.selectMain` | 否则幂等判断与打点口径再次分裂（α-3 的前提） |
| CB-6 | 统一入口**不得**新增第二个路由真相源（例如把「已对齐页」缓存进 `remember`） | 违反 N9 / α-5 |
### 6.5 `ScrollAlignPolicy` 纯函数（与 §6.2 要点 3 同源）

见 §6.2 要点 3。三个纯函数构成**全部**的决策点：

| 纯函数 | 位置 | 决策什么 |
|---|---|---|
| `CancelCauseResolver.resolve(scopeActive, tokenOwned, userDragging)` | `ui/TabScrollGuard.kt` | 本次取消属于哪一类 |
| `ScrollAlignPolicy.shouldAlignAtFinally(...)` | `ui/TabScrollGuard.kt` | 收尾时能否写路由 |
| `ScrollAlignPolicy.shouldAlignAtSettle(...)` | `ui/TabScrollGuard.kt` | 对齐器能否写路由 |

**设计原则**：所有「能不能写」的判断都必须是**纯函数 + 可注入的输入**。协程只负责采集输入、调用纯函数、执行写入。这样 §4.11 的 10 个异步场景才能被确定性测试（§7.4），而不是只能靠真机碰运气。

### 6.6 打点规范（v2 汇总）

| 打点 | v1 格式 | v2 格式 | 兼容性 |
|---|---|---|---|
| `A5/scrollCancel` | `A5/scrollCancel=<index>#<seq>` | `A5/scrollCancel=<index>#<seq>:<cause>`，`cause` ∈ `gesture` / `superseded` / `scope` | **前缀兼容**：既有证据按前缀比对仍成立 |
| `Route/align` | `Route/align=<page>` | `Route/align=<page>:<src>`，`src` ∈ `finally` / `settle` | **前缀兼容** |
| `Route/commit` | —（新增） | `Route/commit=<page>:<src>`，`src` ∈ `finally` / `settle` / `snapshot` | 新增；**与 `Route/align` 并存**，见 §6.4.3 选项 α |
| `A5/alignSkip` | —（新增） | `A5/alignSkip=<index>#<seq>:<cause>`，`cause` ∈ `stale-generation` / `not-stable` / `not-owned` / `scope` | 新增；用于验证 §4.11 的中间态 |
| `A5/gen` | —（新增） | `A5/gen=<value>`，代际推进时发一条 | 新增；用于验证 §6.3.3 的推进点 |
| `Route/currentPage` / `Route/guardIgnore` / `Route/selectMain` | 不变 | 不变 | 完全不变 |
| `A5/scrollStart` / `A5/scrollEnd` / `A5/scrollFinally` / `A5/tabClick` | 不变 | 不变 | 完全不变 |

**为什么 `Route/align` 与 `Route/commit` 并存**：历史 R10 真机证据里 `Route/align=<page>` 是判定「对齐发生了几次、目标是谁」的依据，**不能改语义**。新增的 `Route/commit` 承载「统一入口被调用」的语义，两者的差异在 §8 的判据里逐条说明。

#### 6.6.1 六个不可混用的概念（GPT v2.1 · OBS-2-2）

| 编号 | 概念 | 精确定义 | 判据 | 历史字段对应 |
|---|---|---|---|---|
| **C1** | **Token / 守卫所有权** | 本协程的 token 是否**仍是** `TabScrollGuard` 的当前 owner | `tabGuard.end(token) == true` | `A5/scrollFinally=… aligned=<bool>` |
| **C2** | **程序化滚动是否正常结束** | 本次滚动**没有**被 `CancellationException` 取消 | `cause == none` | **无历史字段**（v2.1 新增 `cause`） |
| **C3** | **Pager 是否真正稳定** | 滚动已结束 **且** 当前页 == 稳定页 | `!isScrollInProgress && currentPage == settledPage` | **无历史字段**（v2.1 新增 `stable`） |
| **C4** | **`currentPage` 是否等于 `settledPage`** | Pager 处于「过渡页」还是「已落定页」 | `currentPage == settledPage` | **无历史字段**（v2.1 新增 `settledMatch`） |
| **C5** | **对齐器是否仍持有有效 generation** | 代际计数器自捕获以来未推进 | `scrollGeneration.current == gen0` | **无历史字段**（v2.1 新增 `genValid`） |
| **C6** | **是否实际提交了路由** | 真的调用了 `viewModel.selectMain` 且未被幂等短路 | 入口内 `selectMain` 被调用 | `Route/selectMain=<AppPage>`（历史上**只有回写路径**有） |

**最关键的一条纠错**：

> **C1 ≠ C2 ≠ C3 ≠ C4 ≠ C5 ≠ C6。**
> 历史字段 `aligned=true` **只**代表 **C1（所有权有效）** —— 它**不代表** C2 / C3 / C4 / C6。
> 而在**取消路径**上 `aligned` **同样为 true**（token 仍归本协程，只是滚动被取消）—— **这正是 OBS-2 的成因**，也是 v1 文档「`aligned` = 对齐成功」这一表述的根源错误。

#### 6.6.2 历史字段的真实含义与兼容关系（不得悄悄改变既有语义）

| 历史字段 | **真实含义**（不是它字面像的意思） | v2.1 的处置 | 兼容关系 |
|---|---|---|---|
| `A5/scrollFinally=<idx>#<seq> aligned=<bool>` | `aligned` = **C1 守卫所有权有效**。**不是**「已对齐」、**不是**「滚动正常结束」、**不是**「路由写入成功」 | **保留原字段原位**，同一行**追加** `owned=` / `cause=` / `stable=` / `settledMatch=` 四个新字段；`aligned` 与 `owned` 同值，`aligned` 作为**历史别名**保留 | 历史脚本按 `aligned=` 抓取**完全不受影响**；新脚本改读 `owned=` / `cause=` / `stable=` |
| `Route/align=<idx>` | 「**执行了一次** `alignRouteToPager` 调用」——**不等于**「路由真的变了」（现实现里打点在幂等检查**之前**，`AppNav.kt:392`） | **保留**：仍在入口的幂等短路**之前**打点，只追加 `:<src>` 后缀 | 历史语义**一字未改**；`Route/align` 继续回答「对齐调用发生了几次、目标是谁」 |
| `Route/selectMain=<AppPage>` | 「路由**确实会被写**」（打点在 `if` 分支内，`AppNav.kt:451`） | **保留**：统一入口在真正写入前打同名同义打点；**覆盖面扩大**到 finally / settle 两条路径（历史只有回写路径有） | 历史语义不变；统计「对齐调用次数」必须用 `Route/align`，统计「路由写入次数」必须用 `Route/selectMain` / `Route/commit` |
| `Route/currentPage=<idx>` | Pager 位置变化（每次 `snapshotFlow` 发射） | 不变 | 不变 |
| `Route/guardIgnore=<idx>` | 中间页回写被守卫拦下 | 不变 | 不变 |
| `A5/scrollCancel=<idx>#<seq>` | 滚动被取消 | **追加** `:<cause>` | 前缀兼容 |
| `A5/scrollStart` / `A5/scrollEnd` / `A5/tabClick` / `A5/routeScroll` | 滚动开始 / 结束 / 点 tab / 回滚滚动 | 不变 | 不变 |

> **明确声明**：v2.1 **没有**改变任何一个历史字段的语义。
> 唯一的「位置调整」是把 `Route/align` 从 `alignRouteToPager` 挪进统一入口，**打点相对幂等判断的先后顺序保持不变**（仍在短路之前）。
> 唯一的新增是 `Route/selectMain` 现在**也会**在 finally / settle 路径出现 —— 这是**覆盖面扩大**，不是语义改变。

#### 6.6.3 v2.1 打点格式定稿

| 打点 | 格式 | 发出时机 | 回答的问题 |
|---|---|---|---|
| `A5/scrollFinally` | `A5/scrollFinally=<idx>#<seq> aligned=<bool> owned=<bool> cause=<cause> stable=<bool> settledMatch=<bool>` | 每次程序化滚动收尾（finally） | C1 + C2 + C3 + C4 |
| `A5/scrollCancel` | `A5/scrollCancel=<idx>#<seq>:<cause>` | 捕获 `CancellationException` 时 | 取消原因 |
| `A5/alignSkip` | `A5/alignSkip=<idx>#<seq>:<reason>` | 对齐器判定为不许写时 | 为什么没写 |
| `A5/gen` | `A5/gen=<value>` | 代际推进时 | 代际推进点是否生效 |
| `A5/alignCommit` | `A5/alignCommit=<idx>#<seq> gen=<n> genValid=<bool>` | 对齐器通过全部检查、即将写入 | C5 的判定结果 |
| `Route/align` | `Route/align=<idx>:<src>`（`src` ∈ `finally` / `settle`） | **对齐调用**发生（幂等短路之前） | 对齐调用了几次、目标是谁 |
| `Route/selectMain` | `Route/selectMain=<AppPage>` | 路由**确实会被写** | 路由提交了几次 |
| `Route/commit` | `Route/commit=<idx>:<src>`（`src` ∈ `finally` / `settle` / `snapshot`） | 路由**确实会被写** | 统一入口提交了几次、从哪个来源 |

**`<cause>` 取值表**：

| 取值 | 含义 | 判定来源 |
|---|---|---|
| `none` | 未被取消，正常收尾 | 未抛 `CancellationException` |
| `gesture` | 用户手势接管 | `userDragging == true`（**该 API 可用性尚未验证**，见 §1.5.2 的 N-OBS-1） |
| `superseded` | 被新的程序化滚动取代 | `tabGuard.end(token) == false` |
| `scope` | 协程作用域被取消 | `currentCoroutineContext().isActive == false` |
| `unknown` | 判定链走完仍不明确 → **保守按 `gesture` 处理**，但打点**如实记 `unknown`** | §1.5.3 的第 4 条 |

**`<reason>`（`A5/alignSkip`）取值表**：

| 取值 | 含义 | 对应所有权条款 |
|---|---|---|
| `stale-generation` | 代际已过期 | OW-4 |
| `not-stable` | Pager 未稳定（`isScrollInProgress` 或 `currentPage != settledPage`） | OW-7 |
| `not-owned` | 守卫所有权已失效 | OW-1 |
| `scope` | 作用域已取消，不得派发 | OW-6 |
| `idem` | 幂等：当前路由已等于目标页 | OW-9 |

**统计口径表（防止再次混用）**：

| 想回答的问题 | **必须**用 |
|---|---|
| 本次协程是不是守卫主人？ | `A5/scrollFinally … owned=`（旧脚本可用 `aligned=`） |
| 本次滚动是不是被取消的？取消原因是什么？ | `A5/scrollFinally … cause=` / `A5/scrollCancel=…:<cause>` |
| Pager 当时稳定了吗？ | `A5/scrollFinally … stable=`（= `!isScrollInProgress && currentPage == settledPage`） |
| 当时是过渡页还是落定页？ | `A5/scrollFinally … settledMatch=` |
| 对齐器代际还有效吗？ | `A5/alignCommit … genValid=` |
| 对齐调用发生了几次？ | `Route/align=` |
| 路由真的被写了几次？ | `Route/selectMain=` 或 `Route/commit=` |

### 6.7 与 CR-17 红线的关系

CR-17 定的是：「**点 tab 只 `animateScrollToPage`，路由由 `snapshotFlow { currentPage }` 单向回写，禁止双向写**」。

本方案**不违反**它：

| CR-17 的约束 | 本方案是否遵守 | 说明 |
|---|---|---|
| Pager 是唯一真相源 | ✅ | 对齐目标始终取自 `pagerState` |
| 点 tab 不直接写路由 | ✅ | 点 tab 仍然只 `animateScrollToPage`；路由写发生在 `finally` / 稳定后对齐器，且**取的是 Pager 的位置** |
| 路由回写单向（Pager → 路由） | ✅ | 稳定后对齐器也是 **Pager → 路由**，不引入「路由 → Pager」方向的写 |
| 禁止双向写 | ✅ | 统一入口契约 2 明确禁止反向写 Pager（CB-1） |
| 回写与对齐的幂等性 | ✅ | `shouldAlignAtFinally` / `shouldAlignAtSettle` 的幂等条 + `commitRouteFromPager` 的契约 1 |

**v2 补充的一致性论证**：`commitRouteFromPager()` 只消费 Pager 的位置、只产出路由的变更，**没有任何一条分支从路由反推 Pager**。因此新增对齐器不构成 CR-17 意义上的「双向写」。§6.4.3 的待裁决点讨论的是「Pager → 路由这一侧有几步」，与 CR-17 禁止的「路由 → Pager」是**两个不同的问题**——但为避免歧义，本文档仍把它标为待裁决。

---
## 7. 单测与 Compose 集成测试（v2）

### 7.1 纯函数单测 · 取消原因判定（`CancelCauseResolver`，JVM）

表驱动实现：一个 `@Test` 遍历下表全部行；每行一个命名 case，失败信息里带 case 名。

| 编号 | `scopeActive` | `tokenOwned` | `userDragging` | 期望 | 备注 |
|---|---|---|---|---|---|
| C1 | true | true | true | `GESTURE` | 典型手势打断 |
| C2 | true | true | false | `GESTURE` | **保守**：未能识别也当手势 |
| C3 | true | false | true | `SUPERSEDED` | token 已被取代**优先于**手势判定 |
| C4 | true | false | false | `SUPERSEDED` | 同目标连点 |
| C5 | **false** | true | true | `SCOPE` | **作用域取消优先于一切** |
| C6 | **false** | false | true | `SCOPE` | 同上 |
| C7 | false | false | false | `SCOPE` | 同上 |
| C8 | true | true | false | `GESTURE` | ★ 保守方向的回归锁；把 C2 的兜底改成 `NONE` 会让 C2 变红 |

**顺序敏感性必须被测到**：C3 与 C5、C6、C7 专门锁「判定顺序不可交换」。把第 1 条与第 2 条对调 → C3 变红；把第 3 条提到第 1 条之前 → C5 变红。

### 7.2 纯函数单测 · 对齐判定（`ScrollAlignPolicy`，JVM）

#### 7.2.1 `shouldAlignAtFinally`

| 编号 | `tokenOwned` | `outcome` | `isScrollInProgress` | `currentPage` | `settledPage` | `routePage` | 期望 | 备注 |
|---|---|---|---|---|---|---|---|---|
| P1 | false | SETTLED | false | 1 | 1 | 0 | `false` | 旧协程不得抢新协程的收尾 |
| P2 | true | **CANCELLED** | false | 1 | 1 | 0 | **`false`** | ★ OBS-2 的核心判据 |
| P3 | true | SETTLED | false | 1 | 1 | 0 | `true` | 正常路径仍可对齐 |
| P4 | true | SETTLED | **true** | 1 | 1 | 0 | `false` | 滚动中不对齐 |
| P5 | true | SETTLED | false | **2** | 1 | 0 | `false` | ★ 未稳定窗口（S10） |
| P6 | true | SETTLED | false | 1 | 1 | **1** | `false` | 幂等：已是目标页就不写 |
| P7 | true | CANCELLED | false | 1 | 1 | 1 | `false` | 取消 + 已一致 → 无需写 |
| P8 | true | SETTLED | false | **0** | 1 | 0 | `false` | 未稳定且 route 恰好等于 current |

#### 7.2.2 `shouldAlignAtSettle`

| 编号 | `genValid` | `isScrollInProgress` | `currentPage` | `settledPage` | `routePage` | 期望 | 备注 |
|---|---|---|---|---|---|---|---|
| Q1 | true | false | 1 | 1 | 0 | `true` | 对齐器正常写入 |
| Q2 | **false** | false | 1 | 1 | 0 | **`false`** | ★ 代际过期 → 禁止写入（OW-4） |
| Q3 | true | **true** | 1 | 1 | 0 | `false` | 仍在滚动 → 禁止写入 |
| Q4 | true | false | **2** | 1 | 0 | `false` | 未落定 → 禁止写入（OW-7） |
| Q5 | true | false | 1 | 1 | **1** | `false` | 幂等 → 禁止写入（OW-9） |

**两个函数的差异必须被锁住**：Q1 与 P3 的输入几乎相同但输出都为 true，而 **P2（CANCELLED）在 finally 路径为 false、在 settle 路径不适用**。变异「把 `shouldAlignAtFinally` 的 `outcome == SETTLED` 去掉」→ P2 / P7 变红；变异「把 `shouldAlignAtSettle` 的 `genValid` 去掉」→ Q2 变红。

### 7.3 现有守卫单测（回归，N7 / N10）

`TabScrollGuard` 的既有单测（token 唯一性、`end` 的幂等与不越权、`ignoresWrite` 的中间页口径、不可变快照）**必须全部保持通过**，且**实现不得改动**。

新增一条：`begin` 之后 `end(旧 token)` 返回 false 且**不改动任何字段**（已有覆盖则不重复加）。

### 7.4 异步竞态测试的实现方式（GPT §4.4 核心，v2 新增）

§4.11 的 R1–R10 是**跨挂起点的所有权交接**问题，纯函数表驱动（§7.1 / §7.2）只能覆盖「一次判定」，覆盖不了「等待 → 状态变化 → 恢复 → 是否还能写」。v2 的做法是**把对齐器从 Compose 依赖里剥出来**，用可注入依赖 + 虚拟时间做确定性测试。

#### 7.4.1 第一步 · 抽出可注入的对齐器宿主

```kotlin
// ui/TabScrollGuard.kt（或新文件 ui/SettleAligner.kt）新增
internal interface PagerProbe {
    val isScrollInProgress: Boolean
    val currentPage: Int
    val settledPage: Int
}

internal class SettleAligner(
    private val scope: CoroutineScope,
    private val currentGeneration: () -> Long,
    private val pager: PagerProbe,
    private val awaitStable: suspend () -> Unit,   // 等待稳定的挂起点
    private val commit: (Int, String) -> Unit,      // → commitRouteFromPager(landed, source)
    private val onSkip: (String) -> Unit            // → A5/alignSkip
) {
    suspend fun run(gen0: Long) {
        awaitStable()
        if (currentGeneration() != gen0) { onSkip("stale-generation"); return }
        if (pager.isScrollInProgress) { onSkip("not-stable"); return }
        if (pager.currentPage != pager.settledPage) { onSkip("not-stable"); return }
        commit(pager.settledPage, "settle")
    }
}
```

| 生产接线 | 测试接线 |
|---|---|
| `pager` = 真实 `PagerState` 的适配器 | `pager` = 可变的 fake（测试直接改字段） |
| `awaitStable` = 两个 `snapshotFlow { }.first { }` | `awaitStable` = 在测试调度器上挂起，直到 fake 报告已稳定 |
| `currentGeneration` = 读取 `scrollGeneration` | 同样读同一个计数器（fake 与生产共用） |
| `commit` = `commitRouteFromPager(landed, "settle")` | 记录到 `mutableListOf<Pair<Int,String>>()`（**中间态断言的抓手**） |

**这样做的关键收益**：`commit` 被替换成「记录」而不是「写路由」，于是测试可以断言**每一次写入的时机与目标**，而不只是最终结果 —— 这正是 §4.11 要求的「中间态验证」。

#### 7.4.2 第二步 · 用虚拟时间编排事件顺序

```kotlin
@Test
fun R1_对齐器等待期间再次点击Tab_旧对齐器不得写入() = runTest {
    val commits = mutableListOf<Pair<Int, String>>()
    val skips = mutableListOf<String>()
    val gen = GenerationCounter()
    val pager = FakePagerProbe()

    val aligner = SettleAligner(
        scope = backgroundScope,
        currentGeneration = { gen.value },
        pager = pager,
        awaitStable = { awaitStableFlag.await() },   // 由测试控制何时「稳定」
        commit = { page, src -> commits += page to src },
        onSkip = { skips += it }
    )

    // ① 手势打断 → 取消原因 GESTURE → 启动对齐器（捕获 gen0）
    val gen0 = gen.value
    backgroundScope.launch { aligner.run(gen0) }
    testScheduler.advanceUntilIdle()

    // ② 等待期间再次点击 tab → 新滚动开始 → gen++
    gen.bump()
    testScheduler.advanceUntilIdle()

    // ③ 让 pager 稳定，唤醒对齐器
    awaitStableFlag.complete()
    testScheduler.advanceUntilIdle()

    // 中间态断言：旧对齐器**不得**产生任何写入
    assertTrue(commits.isEmpty(), "旧对齐器不得写入，实际：$commits")
    assertEquals(listOf("stale-generation"), skips)
}
```

**为什么必须用虚拟时间**：R1–R10 的每一步都需要「在对齐器挂起**之后**精确地插入一个事件」。真实时间下无法保证插入点，`Thread.sleep` 会让测试变成 flaky。`kotlinx.coroutines.test` 的 `TestScheduler` 让每个挂起点成为可控的插入位。

#### 7.4.3 十个场景的测试编排与断言

| 场景 | 测试名 | 编排要点 | 中间态断言 | 最终断言 |
|---|---|---|---|---|
| R1 | 对齐器等待期间再次点 tab | 启动对齐器 → `gen++` → 放行稳定 | `commits` 为空；`skips == ["stale-generation"]` | 新滚动的写入存在且目标正确 |
| R2 | 对齐器等待期间再次手势 | 启动对齐器 → `gen++` → 放行稳定 | `commits` 为空；路由不得跳到中间页 | 路由 == 停留页 |
| R3 | 新旧对齐器同时等待 | 启动两个对齐器（不同 gen0）→ 两次 `gen++` → 放行 | **恰好一次** `stale-generation` | 只有最新代际的写入 |
| R4 | 作用域取消 | `scope.cancel()` 后触发取消路径 | `commits` 为空；**不得**有 `alignSkip` | 无后台任务（`scope` 无活跃子任务） |
| R5 | 取消后新滚动正常完成 | 取消 → 新点击 → 新滚动 `finally` | 新滚动的写入**只有一次** | 路由 == 新目标 |
| R6 | 未稳定窗口 | pager 报告 `currentPage != settledPage` | `commits` 为空 | 稳定后由回写或对齐器给出 |
| R7 | 稳定后又被拖走 | 稳定 → `isScrollInProgress` 变 true | `commits` 为空 | 路由 == 最终停留页 |
| R8 | 旧 token 不得抢收尾 | 新 token 发放后旧协程 `finally` | 旧协程无写入 | 新 token 独占收尾 |
| R9 | 同目标连点 | 同目标连续两次 | 旧 token 无写入 | 写入恰好一次 |
| R10 | 重复触发幂等 | 同一事件触发两次 | — | 写入恰好一次（OW-8 + OW-9） |

#### 7.4.4 三条测试纪律

| 编号 | 纪律 |
|---|---|
| TD-1 | 每个场景**必须**断言中间态（`commits` 的内容与时机），不能只断言最终路由 —— 否则「先写错再写对」的实现会蒙混过关 |
| TD-2 | 必须**逐个场景**用 `@Test` 命名（不用一个大循环），失败信息要能直接定位到 R 编号 |
| TD-3 | 虚拟时间测试**不得**使用 `Thread.sleep` / `delay(真实时长)`；一切等待通过 `TestScheduler.advanceUntilIdle()` 或显式挂起点 |

### 7.5 Compose 集成测试（`app/src/test` + Robolectric，或 `androidTest`）

用真实的 `HorizontalPager` + `PagerState`（不 mock），构造出 S1 / S2 / S4 三种时序，断言**最终路由**：

| 编号 | 场景 | 断言 |
|---|---|---|
| X1 | S1 点 tab 走完 | 路由 == 目标页 |
| X2 | S2 动画途中拖动 | **路由在整个过程中从未等于「中间页」**；最终路由 == Pager 停留页 |
| X3 | S4 拖回另一页 | 最终路由 == Pager 停留页（≠ 点击目标） |
| X4 | 幂等 | 连续两次「路由已等于停留页」时不产生额外写入 |
| X5 | 统一入口幂等 | 连续两次 `commitRouteFromPager(同一页)` 只产生一次 `Route/commit` 打点与一次 `selectMain` |

**X2 的断言方式**：订阅路由状态，收集所有取值，断言集合中**不含**中间页（或不含 Pager 从未真正停留过的页）。

### 7.6 变异验证（强制）

每个新测试都要做：

1. 改坏实现（例如把 `outcome == SETTLED` 去掉）→ 测试**必须变红**；
2. **不许**用编译失败冒充变异命中（改动必须能编译）；
3. 无条件还原，还原后全量单测必须全绿。

| 变异 | 预期变红的测试 |
|---|---|
| 去掉 `outcome == SETTLED` | P2 / P7 |
| 去掉 `currentPage == settledPage`（finally 路径） | P5 |
| 去掉 `!isScrollInProgress`（finally 路径） | P4 |
| 去掉 `routePage != currentPage`（finally 路径） | P6 |
| 去掉 `tokenOwned` | P1 |
| 去掉 `genValid`（settle 路径） | Q2 |
| 去掉 `currentPage == settledPage`（settle 路径） | Q4 |
| 去掉 `routePage != settledPage`（settle 路径） | Q5 |
| 把 `CancelCauseResolver` 的第 1 条与第 2 条对调 | C3 |
| 把 `CancelCauseResolver` 的第 3 条提到第 1 条之前 | C5 |
| 把保守兜底 `GESTURE` 改成 `NONE` | C2 / C8 |
| **去掉代际校验（OW-4）** | **R1 / R2 / R3** |
| **去掉「一次取消最多一个对齐器」（OW-2）** | **R3** |
| **去掉 `scope` 分支的不派发（OW-6）** | **R4** |
| **把 `settledPage` 换回 `currentPage`** | X2 / X3 |

> **最后三条（加粗）是 v2 新增的异步竞态变异**，它们是 GPT §4.4 的核心要求：**没有这三条变异，R1–R10 就只是「看起来测了」，实际无法证明代际与所有权机制真的在起作用。**

---
## 8. 真机复核矩阵（v2）

### 8.1 复核前置条件

| 项 | 要求 |
|---|---|
| 设备 | Redmi 2407FRK8EC（`GAQOHMNFQWFUPBQO`），**需负责人放行** |
| 禁用 | vivo `10CEB40M5G001DK` **全程不连接、不触碰** |
| adb | 所有命令显式 `-s GAQOHMNFQWFUPBQO` |
| 刷新率 | 该机仅 120Hz（R10 已实测）→ 复核矩阵按 **120Hz** 设计 |
| 手势方式 | **真实手指优先**；受限时可用 ADB 合成手势，但**证据等级必须标注为「合成」**（N8） |
| 采样 | 每场景 ≥ 3 个有效轮次；无效轮次**原样保留**在 `_invalid_rounds/`（R10 已建立的纪律） |
| 证据 | `logcat.txt`（`A5/*` / `Route/*` / `Pager/*`）、`ui_final.xml`、`video.mp4`、`dumpsys_display.txt`、`run_info.json`；入库后重算 SHA256SUMS |
| 证据等级标注 | 每份 `logcat.txt` 的 `run_info.json` **必须**记录 `gesture_kind: real_finger` / `synthetic` |

**v2 强化**：v1 的前置条件只写了「真实手指优先」，但没有强制记录。v2 要求**每一轮**都在 `run_info.json` 里显式写明手势来源，**否则该轮不得计入有效轮次**。原因：R10 的既有证据全部是 ADB 合成手势，而合成手势与真实手指在**取消时机**上不同（合成手势是瞬时的，真实手指有压感与抬起过程），这恰恰是 OBS-2 的敏感区。

### 8.2 复核矩阵

| 场景 | 对应 §4 | 操作 | 期望打点序列 | 期望最终态 | 判定 |
|---|---|---|---|---|---|
| **M1** | S1 | 「今日」点「日历」，不动 | `tabClick=2` → `scrollStart=2` → `guardIgnore=1` → `selectMain=Calendar` → `scrollEnd=2` → `scrollFinally aligned=true` → `align=2` | Pager=日历，路由=日历，选中日历 | `align` 恰好 1 次且目标 == 停留页 |
| **M2** | S2 | 「今日」点「日历」，动画途中右滑回「今日」 | `tabClick=2` → `scrollStart=2` → `guardIgnore=1` → **`scrollCancel=2:gesture`** → `scrollFinally aligned=true` → **（期望：无 `Route/align`）** → `currentPage=0` → `selectMain=Today` | Pager=今日，路由=今日，选中今日 | **全程无 `Route/align`**；日志中**不得出现**「统计」或「日历」的路由写 |
| **M3** | S3 | 「今日」点「日历」，滑到「日历」停 | 同 M2 但最终 `currentPage=2` | Pager=日历，路由=日历 | 稳定后对齐器对齐到 2；`align` 来源标记为 `settle` |
| **M4** | S4 | 「今日」点「日历」，滑到「统计」停 | 同 M2，最终 `currentPage=1` | Pager=统计，路由=统计 | 最终路由 == Pager 停留页（≠ 点击目标，正确） |
| **M5** | S5 | 手势中点另一个 tab | 出现 `tabClick` + `scrollCancel` | 以 Pager 最终停留页为准 | 路由 == 停留页；无过渡态写入 |
| **M6** | S6 | 快点不同 tab | `scrollFinally aligned=false`（旧）+ `aligned=true`（新） | Pager=最后点击的页 | 旧协程**不得**产生 `align` |
| **M7** | S7 | 快点相同 tab（回归 R10 s5_same） | seq1 `aligned=false`、seq2 `aligned=true`；`Route/align` 恰好 1 次且只归 seq2 | Pager=目标页 | **必须与 R10 完全一致** |
| **M8** | S9 | 纯横滑（回归 R10 s7_swipe_fast） | **无** `A5/scrollCancel`、**无** `Route/align` | 路由跟随翻页 | **必须与 R10 完全一致** |
| **M9** | S8 / S10 | 拖动到边界后立刻松手（回弹） | `scrollCancel` → 无 `align` → 回弹落定后 `currentPage` → `selectMain` | 路由 == 落定页 | **不得**在回弹窗口内出现 `Route/align` |
| **M10** | 全局 | 取消动画 + 连点等组合 | —— | —— | 不崩溃、不卡死、导航胶囊与标题始终一致 |

### 8.3 v2 新增的针对性场景（代际与所有权）

M1–M10 是 v1 的矩阵（全部保留）。v2 针对代际机制补充 3 个场景，编号 M11–M13：

| 场景 | 对应 §4 | 操作 | 期望打点序列 | 判定 |
|---|---|---|---|---|
| **M11** | R1 | 打断后**不松手**，立刻点另一个 tab | `scrollCancel=…:gesture` → `gen=<n+1>` → `tabClick` → `scrollEnd` → `scrollFinally aligned=true` → `align=<新目标>` → `alignSkip=…:stale-generation` | 旧对齐器**无** `align`；`alignSkip` 恰好 1 次 |
| **M12** | R2 | 打断后**再次横滑**（同一轮里第二次手势） | `scrollCancel=…:gesture` → `gen=<n+1>` → （第二次手势）→ `gen=<n+2>` → `alignSkip=…:stale-generation` | 路由**不得**跳回第一次打断前的页 |
| **M13** | R4 | 打断后立即**按返回键**退出主 tab（二级页） | `scrollCancel=…:scope` | **无** `align`、**无** `alignSkip`、**无**后台任务残留 |

### 8.4 通过判据（量化）

| 编号 | 判据 |
|---|---|
| V1 | M2 的 3 个有效轮次中，**`Route/align` 出现次数为 0**（现状是 1 次，且目标是中间页） |
| V2 | M2 / M3 / M4 的最终路由 **== Pager 的 `settledPage`** |
| V3 | M2 / M4 的日志中，**路由取值序列里不出现「统计」**（M2）或「日历」（M4，出现在点击目标页那一瞬除外） |
| V4 | M7 的 `aligned` 归属与 `Route/align` 次数与 R10 一致 |
| V5 | M8 仍无 `A5/scrollCancel`、无 `Route/align` |
| V6 | 全程无 ANR、无崩溃、无「标题与页面不一致」的截图 |
| V7 | 证据文件齐备且 SHA256SUMS 全量校验通过 |
| **V8** | **M2 的 `A5/scrollCancel` 必须带 `:gesture` 后缀**（若实现选择不区分原因，则 M2 的取消必须仍被正确处理 —— 两种情况都算通过，但要在报告中写明用的是哪种） |
| **V9** | **M11 的 `alignSkip=…:stale-generation` 恰好 1 次，且 `Route/align` 只有 1 次且目标是新 tab** |
| **V10** | **M12 的路由取值序列中不得出现第一次打断前的页**（除非它就是最终停留页） |
| **V11** | **M13 的日志中 `alignSkip` 出现次数为 0**（作用域取消**不派发**对齐器 → 连 `alignSkip` 都不该有） |
| **V12** | 若 §6.4.3 采用选项 α，`Route/commit` 与 `Route/align` 的关系必须在报告中说明：每个 `Route/align` 都应伴随一个同页的 `Route/commit` |

### 8.5 复核的诚实性要求

- **不得**把合成手势的轮次计为「真实手指验证」；
- **不得**只报告「没看到抖动」而不给原始 logcat —— 必须能逐行比对；
- 若某个场景**无法稳定复现**（例如真机上手指速度过快），必须记录尝试次数与失败轮次，**不得**只留成功轮次；
- **不得**在报告里把「v1 的失败现象消失」写成「已修复」—— 必须给出**修复前**与**修复后**的同场景逐行对照（R10 的 r2/r3/r4 日志就是修复前的原始证据）。

### 8.6 真机证据纪律（GPT v2.1 · OBS-2-5 定稿）

> **本轮不执行真机测试。** 以下八条是**将来**执行时的硬性纪律，逐条对应 GPT v2.1 的要求。

| 编号 | 纪律 | 落地方式 | 违反时的后果 |
|---|---|---|---|
| **E-1** | **ADB 合成手势不得冒充真实手指测试** | 每轮 `run_info.json` 必须写 `gesture_kind: real_finger` / `synthetic`；**报告正文必须**标明本场景的证据等级 | 该轮**不得计入有效轮次**；报告结论无效 |
| **E-2** | **`run_info.json` 必须记录 `gesture_kind`** | 同 E-1；缺字段即视为无效轮次 | 同上 |
| **E-3** | **无效轮次必须保留** | 原样存入 `_invalid_rounds/`（R10 已建立的纪律），**不得**只留成功轮次 | 证据包视为**选择性呈现**，结论不采信 |
| **E-4** | **每个场景至少三个有效轮次** | M1–M13 每场景 ≥ 3 个**有效**轮次；有效轮次的定义 = 手势类型符合要求 + 未发生未记录的干扰 + 关键打点齐全 | 该场景判为**未复核** |
| **E-5** | **保留原始 logcat、UI XML、视频、设备信息与 SHA256SUMS** | 每场景一套：`logcat.txt` / `ui_final.xml` / `video.mp4` / `dumpsys_display.txt` / `run_info.json`；入库后**重算** SHA256SUMS（排除 `__pycache__` 与自引用文件） | 证据不可复算 → 该场景不采信 |
| **E-6** | **Redmi 设备需负责人放行** | `GAQOHMNFQWFUPBQO`；所有 adb 命令**显式 `-s`**；未经放行**不得**连接 | 越权占用设备 |
| **E-7** | **vivo 本轮禁止连接和触碰** | `10CEB40M5G001DK` **全程不连接、不触碰**；自动化任务在跑的机器不得并用 | 影响其它任务的自动化 |
| **E-8** | **不得把 R10 的旧证据写成 OBS-2 修复后的验证证据** | R10 的 `s8_swipe_interrupt_120hz_r2/r3/r4` 是**修复前**的原始证据，只能作为「修复前对照」；修复后的证据**必须**是**修复后新采**的轮次，且轮次目录与 R10 的**物理分开** | 结论无效（等于用旧证据冒充新验证） |

**两条容易被忽略的补充纪律**：

| 编号 | 纪律 |
|---|---|
| **E-9** | 修复前后必须有**同场景逐行对照**：同一操作序列，修复前有 `Route/align=<中间页>`、修复后没有 —— 否则无法证明是本次修复起的作用 |
| **E-10** | `capture_anim.py --slow` 结束时只重装 APK、**不重启 App**；之后操作前必须先 `am force-stop` + `monkey` 重启，否则会点到桌面 —— 证据会静默失真 |

---
---

## 9. 对 R10 已通过场景的回归要求

### 9.1 R10 已通过的判据（不得破坏）

| R10 场景 | 已通过判据 | 有效轮次 |
|---|---|---|
| **s5_same**（同目标连点） | seq1 `aligned=false`、seq2 `aligned=true`；`Route/align` 每轮**恰好 1 次**且**只归 seq2**；**无** `A5/routeScroll` | r3 / r4 / r5 |
| **s7_swipe_fast**（快速横滑） | **无** `A5/routeScroll`；纯手势路径不产生程序化滚动事件 | r3 / r4 / r5 |
| **s8_swipe_interrupt**（横滑打断） | `A5/scrollCancel` 打断成立；守卫正常收尾；`Route/guardIgnore=1`；**最终路由 == Pager 实际停留页**（≠ 点击目标页） | r2 / r3 / r4 |

### 9.2 实施后必须重新验证的内容

| 编号 | 内容 | 通过标准 |
|---|---|---|
| R1 | s5_same 三个轮次 | **逐条**与 §9.1 的判据一致；`Route/align` 次数与归属**完全不变** |
| R2 | s7_swipe_fast 三个轮次 | 仍**无** `A5/scrollCancel`、**无** `Route/align` |
| R3 | s8_swipe_interrupt 三个轮次 | `scrollCancel` 成立、`guardIgnore=1`、**最终路由 == Pager 停留页**；**新增**：取消后不再产生指向过渡页的 `Route/align` |
| R4 | `TabScrollGuard` 既有单测 | **全部通过**，实现**零改动** |
| R5 | 全量单测 | **全绿**（当前基线 620 测 / 0 fail / 0 skip） |
| R6 | 「标题与导航胶囊一致」 | 全部轮次的 UI dump 中，标题文本 == 底部导航选中项 |
| R7 | `Route/align` 打点总数 | 除新增的 `settle` 来源标记外，**原有语义不变** |
| **R8** | **`CancelCauseResolver` 既有行为** | v1 的 SETTLED/CANCELLED 二分**已被三分类取代**，但「被取消的滚动一律不对齐」这一**最终效果**必须与 R10 一致 |
| **R9** | **§7.4 的 10 个异步竞态单测** | **全部通过**；且 §7.6 的三条加粗变异必须能把它变红 |

### 9.3 禁止的「修复」方式

以下做法会让 R10 的判据看似通过、实际是退化，**明令禁止**：

- 为了让 `Route/align` 消失而**去掉整个对齐逻辑**（会让「标题与页面不一致」在其它路径复现）；
- 把 `guardIgnore` 的中间页规则放宽成「任何页都回写」（会重现 CR-22 的「点 tab 卡一下」）；
- 改 `Route/align` 的目标页定义（会让真机证据无法与历史轮次比对）；
- 用固定延迟把 `finally` 推迟（N5）；
- **把代际校验做成「只在超时后兜底」**（即先写后校验）—— 这会让 OW-4 形同虚设，R1 / R2 的中间态仍会写错路由。

---

## 10. 回滚方案

### 10.1 提交分级

```text
提交 1：文档 —— 本设计 + 需求文档对应章节的登记（口径不变则不动需求文档）
提交 2：实现 —— 纯函数 + finally 分支 + 稳定后对齐器 + 代际机制 + 统一入口 + 打点增强 + 单测
提交 3：真机收口 —— 真机复核证据 + 复核报告 + 回归结论
```

三笔**分开提交**，便于逐级回滚。

### 10.2 回滚路径

| 级别 | 场景 | 回滚方式 | 后果 |
|---|---|---|---|
| L1 | 实现有缺陷、需要整体撤回 | `git revert <实现提交 sha>` | 回到 R10 已验证状态（`Route/align` 仍会在取消路径写过渡值，即回到 OBS-2 现状，但**其余行为不变**） |
| L2 | 只回滚「稳定后对齐器」，保留 finally 分支 | `git revert` 打点与对齐器的部分提交 | 取消路径**不对齐也不补偿** → 会暴露 §5.3 的缺口（路由可能滞留）。**因此 L2 不可单独使用**，必须 L1 或 L3 |
| L3 | 打点格式变更引发真机证据不可比对 | `git revert` 打点提交 | 既有证据格式恢复；新证据需重新采集 |
| L4 | 真机出现「导航胶囊与标题不一致」 | 立即 `adb -s GAQOHMNFQWFUPBQO install -r app/build/outputs/apk/debug/app-debug.apk` 重装上一个 debug APK | **优先 `-r` 覆盖安装**（不卸载，保留数据） |
| L5 | 需求口径需要回退 | 需求文档单独 revert（若本轮改过） | —— |
| **L6** | **代际机制导致「用户手势后路由不更新」** | `git revert` 代际相关提交（保留纯函数与 finally 分支） | 路由可能滞留；**必须在报告里写明这是已知退化** |
| **L7** | **统一入口引发回写行为变化** | `git revert` 统一入口提交，回写改回直调 `viewModel.selectMain` | 回到 v1 的双写路径；`Route/commit` 消失，`Route/align` 语义不变 |

### 10.3 回滚的前置条件

1. 实现提交**必须可独立 revert**（不与文档、真机收口混在一笔）；
2. `ScrollAlignPolicy` / `CancelCauseResolver` 是**新增**的纯函数，不改动 `TabScrollGuard` 的既有实现 → revert 时不会牵连 R10 已验证的 token 机制；
3. `finally` 的改动是**分支新增**而非重写 → revert 后代码回到原样，**不需要**重建；
4. 代际计数器是**独立的新增状态**，revert 时不残留半初始化状态；
5. 回滚后必须**重跑全量单测**，确认仍全绿；
6. **不使用** `git checkout --` 撤销未提交改动（项目纪律明令禁止；曾误删过未提交代码）。

---

## 附：本文档的证据清单

| 结论 | 证据 |
|---|---|
| 取消后旧协程仍执行 `alignRouteToPager()` | `s8_swipe_interrupt_120hz_r2/r3/r4/logcat.txt` 的 `A5/scrollFinally … aligned=true` + 紧随的 `Route/align=` |
| r2 / r4 对齐到的是守卫刚忽略的中间页 | 同上：`Route/guardIgnore=1` 与 `Route/align=1` 成对出现 |
| 最终路由 == Pager 实际停留页 | 同上：末尾 `Route/currentPage=0` + `Route/selectMain=Today`（r2 / r4）、`Route/currentPage=1` + `selectMain=Stats`（r3） |
| token 机制工作正常（旧协程不对齐） | `s5_same_120hz_r4/logcat.txt`：seq1 `aligned=false` 且无 `Route/align` |
| 纯手势路径不产生 `Route/align` | `s7_swipe_fast_120hz_r4/logcat.txt`：只有 `Route/currentPage` / `Route/selectMain` |
| 守卫语义与 token 实现 | `ui/TabScrollGuard.kt` 源码 |
| 对齐点与回写点的位置 | `ui/AppNav.kt:391-396` / `:404-433` / `:437-461` / `:960-985` |
| **证据等级限制** | 本节证据为 **ADB 合成手势**，非真实手指（N8）；真实手指复核列为 §8 的前置条件 |
| 取消原因的三个来源 | `AppNav.kt:960-985` 的 `catch (CancellationException)` + `tabGuard.begin/end` 语义 + `LaunchedEffect(mainPage)` 的竞争滚动 |
| `userDragging` 信号可用性 | **未验证**（N-OBS-1）；候选 API 为 `PagerState.interactionSource` 的拖动状态 |
| §6.4.3 的架构裁决 | **未裁决**；本设计按 α 描述并标注未决 |

| 改 `HorizontalPager` 架构 | 超出 OBS-2 的问题边界（CR-17 的 Pager 唯一真相源架构不能动） |
