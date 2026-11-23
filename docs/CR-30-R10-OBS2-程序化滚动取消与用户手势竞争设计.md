# CR-30 · R10-OBS2 程序化滚动取消与用户手势竞争设计

| 项 | 值 |
|---|---|
| 编号 | CR-30-R10-OBS2 |
| 阶段 | **阶段 A · 只出设计，未实施**（等 GPT 评审裁决后才允许动代码） |
| 关联 | `docs/CR-30-A5-R10-TabScrollGuard-所有权设计.md`（P2 已通过并真机验证）、`docs/CR-30-A3-实验补证/evidence/a5_r10_guard_ownership_real/` |
| 基线 | 分支 `exp/a5-route-probe` @ `ead51d6`；全量单测 **620 / 0 fail / 0 skip** |
| 本文档改动 | **无任何生产代码 / Gradle / Manifest / 导航行为改动** |
| 状态 | **只登记，不修**（OBS-2 由 R10 真机复核发现并登记，等待本设计评审） |

---

## 0. 背景与本文档要回答的核心问题

### 0.1 OBS-2 是什么

R10 真机复核（Redmi 2407FRK8EC，120Hz）发现并已登记 **OBS-2**：

> **程序化滚动被用户手势打断时，旧协程仍然执行了 `alignRouteToPager()`，把路由对齐到「打断瞬间的过渡页」，造成一次短暂的路由抖动。**

本文档**只做设计**，把「谁有权对齐路由」这件事讲清楚，并给出可实施、可验证、可回滚的方案。

### 0.2 核心问题（GPT 点名要求回答）

> **程序化滚动被用户手势打断时，旧协程是否有权执行 `alignRouteToPager()`？**

**本文档的答案**：

**没有无条件对齐权。** 除「token 命中当前 owner」之外，还必须同时满足三个条件才允许对齐：

1. **本次滚动不是被取消的**（`scrollCancel` 路径一律不对齐）；
2. **对齐目标不是「守卫忽略的中间页」**（中间页在程序化滚动中是噪声，不该成为路由值）；
3. **对齐发生在 Pager 已稳定之后**（用 `settledPage` 而不是过渡态的 `currentPage`），否则会**抢先覆盖用户未完成的导航意图**。

### 0.3 任务书给定的禁止项（本设计全程遵守）

| 编号 | 禁止项 |
|---|---|
| N1 | 不得改 `HorizontalPager` 架构 |
| N2 | 不得改 `beyondBoundsPageCount` |
| N3 | 不得改主 tab 动画规格（`TabMotion.scrollSpecFor`） |
| N4 | 不得「关动画」绕开问题 |
| N5 | 不得用「延迟固定毫秒」掩盖问题 |
| N6 | 不得用整页 `Crossfade` / `AnimatedContent` 做数据过渡（会重建子树、重播 `remember { Animatable(0f) }` → 闪一帧） |
| N7 | 不得重开 R10 原 P2（token 所有权机制已通过审查并真机验证通过） |
| N8 | 不得把 ADB 合成手势冒充真实手指 |

---

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
fun ownsToken(owner: Long, token: Long): Boolean = token != NO_OWNER && token == owner
fun ignoresWrite(programmatic: Boolean, target: Int, index: Int): Boolean =
    programmatic && index != target
```

守卫解决的是「**协程身份**」问题：同目标连点时，用 `target` 当身份会失效，改成唯一 token 后旧协程的 `finally` 动不了新协程的状态。**这一层已被 R10 真机验证通过，不在本设计改动范围内（N7）。**

**盲区**：守卫回答的是「**这个协程是不是当前的主人**」，没有回答「**现在适不适合对齐路由**」。

当前的 `finally` 逻辑把两者混为一谈：

```text
finally {
    aligned = tabGuard.end(token)     // ← 「我是不是主人」
    if (aligned) alignRouteToPager(currentPage)   // ← 「我现在就按当前页对齐」
}
```

`end(token)` 在**取消路径**上同样返回 `true`（token 仍是自己发的、没被新点击取代），于是**被用户手势打断的旧协程照样行使了对齐权**——而且它对齐的目标是 `pagerState.currentPage`，即**打断瞬间的过渡值**。

### 1.4 取消的三个来源

当前代码**无法区分** `animateScrollToPage` 被取消的三种原因：

| 来源 | 机制 | 当前行为 | 期望行为 |
|---|---|---|---|
| **(a) 用户手势接管** | 用户拖动通过 `MutatorMutex` 取消了正在进行的 `scroll()` | `finally` → `alignRouteToPager(currentPage)` → **对齐到过渡页** | **放弃对齐**，等手势结束、稳定后再对齐 |
| **(b) 竞争的程序化滚动** | `LaunchedEffect(mainPage)` 或另一次点击发起新的 `animateScrollToPage`，取消前一个 | 同上 | 新滚动自己收尾；旧的不该对齐 |
| **(c) 协程作用域被取消** | `tabScope` 随组合离开而取消 | 同上 | 不应对齐 |

**这是设计的关键约束**：修复不能只针对 (a) 打补丁，否则 (b) / (c) 仍然会用过渡值对齐。

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

> **证据等级必须如实标注**：本节的日志是**脚本驱动的合成手势**产生的。按任务书 N8，**不得**把它当作「真实手指验证」。R10 的 OBS-1 与本节的 OBS-2 **都仍需真实手指复核**。
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

**三轮的 `Route/guardIgnore` 均为 1 次、`A5/scrollCancel` 均恰好 1 次** → 打断成立、守卫正常收尾。

**共同结论**：

1. `scrollFinally aligned=true` —— **token 判定认为旧协程仍然是主人**（这符合设计：没有新点击取代它）；
2. `Route/align` 在**每一次**取消后都发生 —— 旧协程**无条件行使了对齐权**；
3. 对齐目标在 r2 / r4 是**被守卫判定为应忽略的中间页** —— 守卫的「忽略中间页」规则在 `finally` 里被自己推翻；
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
```

### 3.2 状态定义

```text
  滚动协程生命周期：
    START ──► ANIMATING ──┬──► SETTLED      （scrollEnd）
                          │
                          ├──► CANCELLED_U  （scrollCancel：用户手势接管）
                          ├──► CANCELLED_P  （scrollCancel：竞争的程序化滚动）
                          └──► CANCELLED_S  （scrollCancel：作用域取消）

  守卫状态：
    IDLE(programmatic=false, target=-1, owner=NO_OWNER)
    OWNED(programmatic=true, target=T, owner=TOKEN)

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
 t2     P: CancellationException       currentPage=1   OWNED              今日        A5/scrollCancel=2
 t2     P: finally { end(#7)=true }   currentPage=1   IDLE               今日        A5/scrollFinally aligned=true
 t2     P: alignRouteToPager(1)       currentPage=1   IDLE               统计        ★★ OBS-2：对齐到被忽略的中间页
 t3     W: Route/currentPage=0        currentPage=0   IDLE               统计        回写
 t3     W: Route/selectMain=Today      currentPage=0   IDLE               今日        最终正确，但用户看到过「统计」
```

### 3.4 三个必须同时成立才安全的判据

```text
                       ┌───────────────────────────────────────────┐
                       │ finally 到达                              │
                       └───────────────────┬───────────────────────┘
                                           │
                        ┌──────────────────▼───────────────────┐
                        │ 判据 1：本次是 SETTLED 还是 CANCELLED？ │
                        └──┬────────────────────────────┬─────┘
                     SETTLED│                            │CANCELLED(_U / _P / _S)
                           ▼                            ▼
            ┌──────────────────────────┐   ┌──────────────────────────────────────┐
            │ 判据 2：align 的目标页是   │   │ 判据 2'：一律不对齐                   │
            │ 「守卫忽略的中间页」吗？    │   │ （取消瞬间的 currentPage 是过渡值）    │
            └────┬────────────────┬────┘   └──────────────────┬───────────────────┘
             不是 │                │ 是                       │
                 ▼                ▼                          ▼
        ┌──────────────┐  ┌──────────────┐      ┌──────────────────────────────┐
        │ 判据 3：Pager │  │ 不允许对齐   │      │ 把对齐权交给「稳定后对齐器」： │
        │ 已稳定？      │  │（中间页不应  │      │  等 isScrollInProgress=false  │
        │ settledPage== │  │ 成为路由值） │      │  且 currentPage==settledPage │
        │ currentPage？ │  └──────────────┘      │  再对齐到 settledPage         │
        └──┬────────┬──┘                        └──────────────────────────────┘
        是 │        │ 否
           ▼        ▼
   ┌───────────┐  ┌──────────────┐
   │ 允许对齐   │  │ 不允许对齐   │
   │（幂等）    │  │ 交给稳定后   │
   └───────────┘  │ 对齐器       │
                  └──────────────┘
```

### 3.5 判据 1 的必要性（为什么不能只靠 token）

token 回答的是「**我是谁**」，不是「**现在能不能动**」。R10 已经证明 token 能正确区分「旧协程 vs 新协程」（s5_same r4：旧协程 `aligned=false`，没有产生 `Route/align`）。

但 OBS-2 的场景里**没有新点击**——token #7 从头到尾都是主人，于是 `end(#7)` 返回 `true`。

**结论**：token 判据**必须保留**（N7），但**不够**；需要补上「本次是 SETTLED 还是 CANCELLED」这一层。

### 3.6 判据 2 的必要性（为什么中间页不能成为路由值）

守卫的 `ignoresWrite` 已经明确表达了产品口径：

> 「程序化滚动途中经过的中间页，不应成为路由值（否则标题会先跳中间页再跳回，观感＝卡一下）。」

r2 / r4 里 `Route/align=1` 正是**把守卫刚刚忽略的中间页又写了回去**——守卫的规则在自己的 `finally` 里被推翻了。

**但要注意一个角色切换**：

- 在**程序化滚动期间**，中间页是噪声（不该成为路由）；
- 在**用户手势期间**，Pager 当前停在哪页就是哪页——此时**没有「中间页」概念**，因为用户接管了 Pager。

因此「不能对齐到中间页」这条约束的**适用范围是程序化滚动期间**；取消之后由稳定后对齐器按 `settledPage` 对齐是合法的（`settledPage` 不是过渡值）。这一点必须在实施时写清，否则会被误读成「永远不许对齐到中间页」。
---

## 4. 十个场景的预期行为

### 4.0 记号与前提

```text
  页面编号：0 = 今日，1 = 统计，2 = 日历
  目标页 targetPage：本次程序化滚动要去的页（= guard.target）
  token owner：守卫当前所有者 token；NO_OWNER = 0 表示无守卫
  路由状态：viewModel.page 的主页面
  「现状」= 当前代码的行为；「期望」= 本设计实施后的行为
```

**全局不变式**（所有场景都必须满足）：

- **INV-1**：Pager 始终是唯一真相源；最终路由 == Pager 的实际停留页。
- **INV-2**：任何**过渡态**的 `currentPage` 都不得成为路由值。
- **INV-3**：被守卫判定为「中间页」的页，在**程序化滚动期间**不得成为路由值。
- **INV-4**：用户手势期间不得发生任何「抢先覆盖用户意图」的路由写。
- **INV-5**：token 所有权机制与不可变快照实现**不被改动**。

---

### S1 · 正常点击完成

**场景**：在「今日」(0) 点「日历」(2)，动画走完，全程无手势。

| 观察项 | 值 |
|---|---|
| 事件时间线 | `A5/tabClick=2` → `A5/scrollStart=2` → `Route/currentPage=1` + `Route/guardIgnore=1` → `Route/currentPage=2` + `Route/selectMain=Calendar` → `A5/scrollEnd=2` → `A5/scrollFinally aligned=true` → `Route/align=2` |
| `currentPage` | 0 → 1 → 2 |
| `targetPage` | 2（全程不变） |
| `isScrollInProgress` | t0..t3 为 true；t3 之后 false |
| 路由状态 | 今日 →（中间页被忽略，仍是今日）→ 日历 → 日历（幂等） |
| token owner | t0 起 = #7；t3 `end(#7)` 成功后 = NO_OWNER |
| 是否允许 `alignRouteToPager()` | **允许**（SETTLED + 目标已是稳定页 + 幂等） |
| 最终页面与选中态 | Pager 停在「日历」；底部导航选中「日历」；标题「日历」 |
| 现状 vs 期望 | **完全一致**——本场景不得有任何行为变化（回归基线） |

---

### S2 · 点击后立即横滑打断（★ OBS-2 主场景）

**场景**：在「今日」(0) 点「日历」(2)，程序化滚动途中用户立即右滑回「今日」。**对应真机 r2 / r4。**

| 观察项 | 值 |
|---|---|
| 事件时间线 | `A5/tabClick=2` → `A5/scrollStart=2` → `Route/currentPage=1` + `Route/guardIgnore=1` → **用户拖动** → `A5/scrollCancel=2` → `A5/scrollFinally aligned=true` → **（现状：`Route/align=1`）** → `Route/currentPage=0` + `Route/selectMain=Today` |
| `currentPage` | 0 → 1（中间页）→（用户拖动中）→ 0 |
| `targetPage` | 2（取消瞬间仍为 2，但目标已失效） |
| `isScrollInProgress` | t0 起 true（程序化动画）→ 取消瞬间**仍为 true**（用户的拖动接管了 scroll）→ 手势结束后 false |
| 路由状态 | 今日 →（guardIgnore 保持今日）→ **现状：被写成「统计」** → 回写改回今日 |
| token owner | #7 直到 `finally` 才被 `end()` 清空 |
| 是否允许 `alignRouteToPager()` | **不允许**。取消路径一律不对齐：此刻的 `currentPage=1` 是**过渡值**，且正是守卫刚判定为「应忽略」的中间页 |
| 最终页面与选中态 | Pager 停在「今日」；底部导航选中「今日」；标题「今日」。**全程不应出现「统计」** |
| 现状 vs 期望 | 现状：标题 / 胶囊闪一下「统计」（r2 / r4 的 `Route/align=1`）。期望：**无抖动** |

---

### S3 · 横滑回原目标

**场景**：点「日历」(2) 后，用户手势把 Pager 推回并最终停在「日历」(2)。

| 观察项 | 值 |
|---|---|
| 事件时间线 | `A5/tabClick=2` → `A5/scrollStart=2` → `Route/currentPage=1` + `guardIgnore=1` → 用户拖动 → `A5/scrollCancel=2` → `A5/scrollFinally aligned=true` →（现状：`Route/align=2`）→ `Route/currentPage=2`（若此前未写过目标页则此处补写） |
| `currentPage` | 0 → 1 → 2 |
| `targetPage` | 2 |
| `isScrollInProgress` | true →（取消瞬间仍 true）→ 手势结束 false |
| 路由状态 | 今日 →（忽略中间页）→ 日历 |
| token owner | #7 → NO_OWNER |
| 是否允许 `alignRouteToPager()` | **不允许**（取消路径）。对齐权移交稳定后对齐器，最终对齐到 `settledPage=2`（与目标一致，**幂等**） |
| 最终页面与选中态 | Pager 停在「日历」；选中「日历」 |
| 现状 vs 期望 | 现状与期望的**最终态相同**，差别是现状多了一次 `Route/align=2`（若当时路由已是日历则为幂等写；若路由还是今日则这��写是「抢跑」——它写的是**过渡中的目标页**，而 Pager 其实还在动） |

---

### S4 · 横滑到另一页

**场景**：点「日历」(2) 后，用户手势把 Pager 推回并停在「统计」(1)。**对应真机 r3。**

| 观察项 | 值 |
|---|---|
| 事件时间线 | `A5/tabClick=2` → `A5/scrollStart=2` → `Route/currentPage=1` + `guardIgnore=1` → `Route/currentPage=2` + `selectMain=Calendar` → 用户拖动 → `A5/scrollCancel=2` → `A5/scrollFinally aligned=true` →（现状：`Route/align=2`）→ `Route/currentPage=1` + `selectMain=Stats` |
| `currentPage` | 0 → 1 → 2 → 1 |
| `targetPage` | 2 |
| `isScrollInProgress` | true →（取消瞬间仍 true）→ 手势结束 false |
| 路由状态 | 今日 → 日历（目标页合法回写）→ **现状：再次写「日历」** → 统计 |
| token owner | #7 → NO_OWNER |
| 是否允许 `alignRouteToPager()` | **不允许**（取消路径）。稳定后对齐器对齐到 `settledPage=1` |
| 最终页面与选中态 | Pager 停在「统计」；选中「统计」。**最终路由必须等于「统计」**（= Pager 实际停留页 ≠ 点击目标「日历」，这是正确行为） |
| 现状 vs 期望 | 现状：`Route/align=2` 是**多余写**（路由当时已是日历），86ms 后回写改「统计」。期望：直接落到「统计」，少一次抖动 |

---

### S5 · 横滑中再点 tab

**场景**：用户正在横滑（无程序化滚动）时，点击底部导航的另一个 tab。

| 观察项 | 值 |
|---|---|
| 事件时间线 | （用户手势中）`Route/currentPage=1` + `selectMain=Stats` → **点击「日历」** → `A5/tabClick=2` → `A5/scrollStart=2` → `A5/scrollCancel=2`（手势与动画互抢，谁后到谁赢）→ `A5/scrollFinally aligned=true` →（现状：`Route/align=?`） |
| `currentPage` | 1 →（动画途中）→ ? |
| `targetPage` | 2 |
| `isScrollInProgress` | true（用户手势）→ 动画期间 true |
| 路由状态 | 统计 →（动画途中 `currentPage=2` 时写「日历」） |
| token owner | #8（新点击的 token），旧手势**不持有 token**（纯手势路径不调 `begin`） |
| 是否允许 `alignRouteToPager()` | **不允许**（取消路径）。稳定后对齐器对齐到最终稳定页 |
| 最终页面与选中态 | 取决于谁赢：**用户的拖动若最终停下 → 以 Pager 停留页为准**；若动画赢了 → 「日历」。无论哪种，最终路由 == Pager 停留页（INV-1） |
| 现状 vs 期望 | 现状：`Route/align` 的目标取自取消瞬间的 `currentPage`，**可能与最终停留页不一致** → 抖动 |

---

### S6 · 连续快点不同 tab

**场景**：点「统计」(1)，还没到位就点「日历」(2)。

| 观察项 | 值 |
|---|---|
| 事件时间线 | `A5/tabClick=1` → `A5/scrollStart=1` → `A5/tabClick=2` → `A5/scrollStart=2` → 第一次滚动被第二次**竞争取消** → `A5/scrollCancel=1` → `A5/scrollFinally=1 aligned=false`（token 已被 #2 取代）→ `A5/scrollEnd=2` → `A5/scrollFinally=2 aligned=true` → `Route/align=2` |
| `currentPage` | 0 → 1 → 2 |
| `targetPage` | 1（第一次）→ 2（第二次） |
| `isScrollInProgress` | true（贯穿，可能不落 false） |
| 路由状态 | 今日 → 统计（第一次目标页回写）→ 日历 |
| token owner | #9 → 被 #10 取代 → NO_OWNER |
| 是否允许 `alignRouteToPager()` | 第一次：**不允许**（token 不匹配，`aligned=false`，现状已如此）。第二次：**允许**（SETTLED + token 命中） |
| 最终页面与选中态 | Pager 停在「日历」；选中「日历」 |
| 现状 vs 期望 | **一致**——token 机制已经正确处理了本场景（R10 s5_same 的同型验证）。本场景是**回归基线，不得变化** |

---

### S7 · 连续快点相同 tab

**场景**：点「统计」(1) 两次。**对应真机 s5_same r3/r4/r5。**

| 观察项 | 值 |
|---|---|
| 事件时间线 | `A5/tabClick=1#1` → `A5/scrollStart=1#1` → `Route/currentPage=1` + `selectMain=Stats` → `A5/tabClick=1#2` → `A5/scrollStart=1#2` → `A5/scrollEnd=1#2` → `A5/scrollFinally=1#2 aligned=true` → `Route/align=1` → `A5/scrollEnd=1#1` → `A5/scrollFinally=1#1 aligned=false` |
| `currentPage` | 0 → 1 |
| `targetPage` | 1（两次相同） |
| `isScrollInProgress` | true →（#2 完成）false；#1 随后完成 |
| 路由状态 | 今日 → 统计 → 统计 |
| token owner | #11 → 被 #12 取代 → NO_OWNER |
| 是否允许 `alignRouteToPager()` | #1：**不允许**（`aligned=false`，现状已如此）。#2：**允许**（SETTLED + token 命中 + 幂等） |
| 最终页面与选中态 | Pager 停在「统计」；选中「统计」 |
| 现状 vs 期望 | **完全一致**——这是 R10 P2 的核心判据，**严禁破坏** |

---

### S8 · 手势刚结束时旧协程 finally

**场景**：用户手势在旧协程 `finally` 执行**前一刻**结束（最坏时序）。

| 观察项 | 值 |
|---|---|
| 事件时间线 | `A5/scrollCancel=2`（取消）→ **用户手势在此刻结束** → `A5/scrollFinally aligned=true` →（现状：`Route/align=<取消瞬间的 currentPage>`）→ `Route/currentPage=<最终页>` |
| `currentPage` | 取消瞬间 = 过渡值；手势结束后 = 最终页 |
| `targetPage` | 2（已失效） |
| `isScrollInProgress` | 取消瞬间可能已为 **false**（手势刚结束） |
| 路由状态 | 取决于手势最终停在哪页 |
| token owner | #13 → NO_OWNER |
| 是否允许 `alignRouteToPager()` | **不允许**。注意：**即使此刻 `isScrollInProgress` 已经是 false，也仍然不允许**——因为 `currentPage` 可能是「手��刚停下的那一页」而非用户最终意图停留页（例如手势被吸附回弹）。**这正是「用 `isScrollInProgress` 当唯一判据」方案的漏洞** |
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
| 是否允许 `alignRouteToPager()` | **不适用**——纯手势路径不经过 `finally`，不产生 `Route/align`（真机 s7_swipe_fast 已验证：3/3 轮无 `A5/scrollCancel`、无 `Route/align`） |
| 最终页面与选中态 | Pager 最终停留页；路由 == 停留页 |
| 现状 vs 期望 | **完全一致**——本场景是**回归基线，不得变化** |

---

### S10 · 取消后 Pager 未稳定窗口

**场景**：程序化滚动被取消后，Pager 处于「已停止滚动但尚未落到稳定页」的窗口（例如弹簧动画的回弹阶段，或 `settledPage` 尚未更新）。

| 观察项 | 值 |
|---|---|
| 事件时间线 | `A5/scrollCancel=2` → `A5/scrollFinally aligned=true` →（现状：`Route/align=<过渡值>`）→ … Pager 回弹落定 … → `Route/currentPage=<最终页>` |
| `currentPage` | 取消瞬间 = 过渡值；窗口期内可能继续变 |
| `targetPage` | 2（已失效） |
| `isScrollInProgress` | 窗口期内**可能为 false**（动画已结束），但 `currentPage != settledPage` |
| 路由状态 | 窗口期内可能是**过时值** |
| token owner | #14 → NO_OWNER |
| 是否允许 `alignRouteToPager()` | **不允许**（取消路径）。且稳定后对齐器必须**同时**满足「`isScrollInProgress == false`」与「`currentPage == settledPage`」才动作——**只看 `isScrollInProgress` 会在本窗口内误判为已稳定** |
| 最终页面与选中态 | 窗口结束后由回写 / 稳定后对齐器给出；最终路由 == Pager 停留页 |
| 现状 vs 期望 | 现状：在窗口期内写过渡值。期望：窗口期内**不写**，窗口结束后写最终稳定页 |
---

## 5. 候选方案对比

### 5.1 方案清单

| 编号 | 方案 | 做法 |
|---|---|---|
| **A1** | **取消让权 + 取消路径专属的一次性稳定对齐器** | `finally` 区分 SETTLED / CANCELLED；CANCELLED 时**不**对齐，改为启动一个只跑一次的「稳定后对齐器」：等 `isScrollInProgress == false` 且 `currentPage == settledPage` 后，对齐到 `settledPage` |
| **A2** | 取消让权 + 全局稳定观察器 | 同上，但稳定观察器**常驻**，所有翻页都由它统一对齐；协程 `finally` 不再对齐 |
| **B** | 取消让权 + 完全不对齐 | `finally` 在取消路径下什么都不做，对齐完全交给 `snapshotFlow { currentPage }` 回写 |
| **C** | 取消让权 + 立即按 `settledPage` 对齐 | 取消瞬间就用 `settledPage`（而非 `currentPage`）对齐 |
| **D** | `finally` 里加 `isScrollInProgress` 判据 | 只在 `!isScrollInProgress` 时才 `alignRouteToPager(currentPage)` |
| **E** | 区分取消原因 | 用「用户手势 vs 其它取消」区分：只有用户手势取消才对齐（且对齐到 `settledPage`），竞争取消 / 作用域取消不对齐 |
| **F** | 禁止改架构类方案（**均不推荐**） | 整页 `Crossfade`、关动画、固定延迟毫秒、改 `beyondBoundsPageCount`、改主 tab 动画规格 |

### 5.2 逐项对比

| 维度 | A1 | A2 | B | C | D | E |
|---|---|---|---|---|---|---|
| 修掉 OBS-2（r2/r4 的 `Route/align=1`） | ✅ | ✅ | ✅ | ✅ | ⚠️ 见 §5.4 | ✅ |
| 不写过渡态 `currentPage` | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ |
| 覆盖「取消后 Pager 未稳定窗口」（S10） | ✅ | ✅ | ⚠️ 见 §5.3 | ❌ | ❌ | ⚠️ |
| 覆盖「手势刚结束」（S8） | ✅ | ✅ | ⚠️ | ❌ | ❌ | ⚠️ |
| 保证最终路由 == Pager 停留页（INV-1） | ✅ | ✅ | ⚠️ | ❌ | ❌ | ⚠️ |
| 对 S1 / S6 / S7 / S9 的行为影响 | **无**（仅取消路径新增分支） | **有**（常态多一次检查） | **无** | **无** | **无** | **有** |
| 回归面 | 小 | 中 | 小 | 小 | 小 | 中 |
| 引入新的「路由写入者」 | 1 个（取消路径专属，一次性） | 1 个（常驻） | 0 | 0 | 0 | 0 |
| 实现复杂度 | 中 | 中 | 极低 | 低 | 低 | 中高 |
| 违反 N1–N8 | 否 | 否 | 否 | 否 | 否 | 否 | **是** |

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

### 5.4 方案 D 的漏洞（为什么 `isScrollInProgress` 不能当唯一判据）

方案 D 的直觉是：「取消瞬间如果 Pager 还在滚动，说明是用户在拖，就不对齐」。但有三个问题：

1. **不区分用户手势与程序化滚动**：`isScrollInProgress` 对两者都为 true。竞争的程序化滚动（来源 b）也会被判成「用户在拖」→ 恰好也是不对齐，**结果凑巧正确**，但理由是错的，将来容易改错；
2. **取消瞬间的时序不确定**：`CancellationException` 抛出后到 `finally` 执行之间，`isScrollInProgress` 可能已经从 true 变成 false（手势刚好结束，见 S8）；也可能仍然是 true（用户还在拖）。**同一个判据在两个时刻给出不同答案**，无法作为可靠依据；
3. **不覆盖 S10**：`isScrollInProgress == false` 但 `currentPage != settledPage` 的未稳定窗口里，方案 D 会**认为已稳定并对齐到过渡值**——正好是它想避免的情形。

**结论**：`isScrollInProgress` 只能作为**辅助**信号（稳定后对齐器里的一个必要条件），**不能**作为取消路径的**唯一**判据。取消与否必须由「本次协程是否抛出 `CancellationException`」这个**确定**事实来判断。

### 5.5 方案 C 的漏洞

方案 C 在取消瞬间对齐到 `settledPage`。问题是：**用户手势进行中，`settledPage` 往往还是「手势开始前的那一页」**（Compose 的定义：drag 中 `settledPage` 是拖动前停留的页）。用它对齐 = 把路由拉回**用户正要离开的旧页**，比现状更糟（现状至少对齐到了 `currentPage`）。

**结论**：必须**等待稳定**（`currentPage == settledPage`）之后再用 `settledPage`，不能立即用。

### 5.6 方案 E 的定位

方案 E（区分取消原因）在**理论上最精确**，但代价大：

- 需要可靠地区分「用户手势接管」与「竞争的程序化滚动」；
- 可用信号：`PagerState.interactionSource` 的拖动状态（`collectIsDraggedAsState`，**需先确认在本项目的 Compose BOM 2024.06 / foundation 版本上可用**）；
- 即使能区分，**竞争取消本来就不该对齐**（新的滚动自己会收尾），所以「区分」的价值主要是**诊断价值**（打点更清楚），不是行为价值。

**结论**：E **不作为独立方案**，但**建议把「取消原因」加入打点**（`A5/scrollCancel` 带上原因分类），便于真机复核时区分场景。行为上 A1 已经覆盖了 E 的目标。

### 5.7 方案 F（禁止项）逐一说明为何不采用

| 禁止项 | 为何不采用 |
|---|---|
| 整页 `Crossfade` / `AnimatedContent` | 会重建子树、重播 `remember { Animatable(0f) }` → **闪一帧**（项目 Compose 红线） |
| 关动画 | 破坏「滑动跟手」的既有体验，且**不解决路由写错**这个根因 |
| 延迟固定毫秒 | 用时间窗掩盖竞态；设备性能、动画时长、手势速度一变就失效；**是掩盖不是修复** |
| 改 `beyondBoundsPageCount` | 与路由对齐无关；且会改变预加载行为，影响性能基线 |
| 改主 tab 动画规格（`TabMotion.scrollSpecFor`） | 与路由对齐无关；R10 已验证的动画手感不能动 |
---

## 6. 推荐方案

### 6.1 选定：**A1 · 取消让权 + 取消路径专属的一次性稳定对齐器**

**一句话**：

> **程序化滚动被取消时，旧协程放弃对齐权；改由一个「等 Pager 稳定后再对齐」的一次性对齐器，把路由对齐到 `settledPage`。**

**为什么选 A1 而不是 A2**：A2 更优雅（对齐路径唯一），但它**改变了常态行为**（每一次翻页都多走稳定观察器），回归面从「取消路径」扩大到「全部翻页」。在本项目「R10 已真机验证通过、必须保持」的约束下，**A1 把改动限制在「本来就出错的分支」**，风险最小。

### 6.2 实现要点

#### 要点 1 · 区分 SETTLED / CANCELLED

```kotlin
// ui/AppNav.kt  onSelect
var outcome = ScrollOutcome.SETTLED
try {
    ... animateScrollToPage(...)
    PerfProbe.a5("scrollEnd", index, seq)
} catch (e: kotlinx.coroutines.CancellationException) {
    outcome = ScrollOutcome.CANCELLED
    PerfProbe.a5("scrollCancel", index, seq)
    throw e
} finally {
    val owned = tabGuard.end(guardToken)
    PerfProbe.a5Finally(index, seq, owned)
    when {
        !owned -> Unit                                   // 已被新点击取代（现状语义不变）
        outcome == ScrollOutcome.SETTLED -> alignRouteToPager(pagerState.currentPage)  // 现状语义不变
        else -> startSettleAligner()                    // ★ 新增：取消路径让权
    }
}
```

**注意**：`scrollCancel` 的打点保留原样（`A5/scrollCancel=index#seq`），**不破坏既有真机证据的可比对性**；取消原因分类作为**新增**字段（见要点 4）。

#### 要点 2 · 稳定后对齐器（取消路径专属，一次性）

```kotlin
// ui/AppNav.kt  新增
fun startSettleAligner() {
    tabScope.launch {
        // 1) 等 Pager 静止
        snapshotFlow { pagerState.isScrollInProgress }.first { !it }
        // 2) 等「当前页 == 稳定页」——这一步同时挡住 S8 与 S10
        snapshotFlow { pagerState.currentPage == pagerState.settledPage }.first { it }
        // 3) 再等一次静止（防止第 2 步与第 3 步之间又被手势打断）
        if (pagerState.isScrollInProgress) return@launch
        // 4) 幂等对齐
        val settled = pagerState.settledPage
        val target = MAIN_PAGES.getOrNull(settled) ?: return@launch
        if (AppPage.mainOf(viewModel.page.value) != target) alignRouteToPager(settled)
    }
}
```

**为什么第 2 步不可省**：

- S8（手势刚结束）：`isScrollInProgress` 已 false，但 `currentPage` 可能还在回弹途中 → `currentPage != settledPage` → 继续等；
- S10（未稳定窗口）：同样 `currentPage != settledPage` → 继续等；
- 只有 `currentPage == settledPage` 时，Pager 才**真的**停在某一页上，这时对齐才是有意义的。

**为什么第 3 步不可省**：第 2 步与第 4 步之间，用户可能又发起新手势。补一次静止检查，避免「刚稳定就又被拖走」的竞态。

#### 要点 3 · 决策抽成纯函数（便于单测与变异验证）

```kotlin
// ui/TabScrollGuard.kt  新增（与守卫同文件，保持内聚）
internal enum class ScrollOutcome { SETTLED, CANCELLED }

internal object ScrollAlignPolicy {
    /**
     * 是否允许此刻对齐路由。纯函数——便于单测与变异验证。
     *
     * 规则（缺一不可）：
     *  1. tokenOwned：本次协程仍是守卫主人（否则是别人的收尾）；
     *  2. outcome == SETTLED：被取消的滚动一律不对齐（过渡态不是真相）；
     *  3. !isScrollInProgress：Pager 已静止；
     *  4. currentPage == settledPage：已落到稳定页（挡住未稳定窗口）；
     *  5. routePage != currentPage：幂等——已经是目标页就不必再写。
     */
    fun shouldAlign(
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
}
```

**注意**：这个纯函数**只表达「协程收尾时能不能对齐」**。稳定后对齐器是另一条路径（它对齐到 `settledPage`，且只在取消路径启动），两者共用「幂等 + 稳定」两条约束，但**不共用「SETTLED」这一条**——取消路径正是靠对齐器来收尾的。

#### 要点 4 · 打点增强（可观测性，不改行为）

| 打点 | 现状 | 建议 |
|---|---|---|
| `A5/scrollCancel` | `A5/scrollCancel=<index>#<seq>` | 保留原格式，**追加**取消原因分类：`A5/scrollCancel=<index>#<seq>:<reason>`（`reason` ∈ `gesture` / `programmatic` / `scope`）。**格式变更必须向后兼容**（既有真机证据按原格式比对） |
| `Route/align` | `Route/align=<page>` | 保留；**追加**对齐来源：`Route/align=<page>:<src>`（`src` ∈ `finally` / `settle`），便于真机复核区分两条路径 |
| 新增 | — | `Route/alignSkip=<reason>`：记录「判定为不许对齐但最终确实需要对齐」的时刻，用于验证 §5.3 的缺口是否被覆盖 |

#### 要点 5 · 明确不做的事

- **不改** `TabScrollGuard` 的 token 机制、不可变快照实现（INV-5 / N7）；
- **不改** `HorizontalPager` 架构、`beyondBoundsPageCount`、主 tab 动画规格、关动画行为（N1–N4）；
- **不加**任何固定延迟（N5）；
- **不用**整页 `Crossfade` / `AnimatedContent`（N6）；
- **不重开** R10 原 P2（N7）。

### 6.3 与 CR-17 红线的关系

CR-17 定的是：「**点 tab 只 `animateScrollToPage`，路由由 `snapshotFlow { currentPage }` 单向回写，禁止双向写**」。

本方案**不违反**它：

| CR-17 的约束 | 本方案是否遵守 | 说明 |
|---|---|
| Pager 是唯一真相源 | ✅ | 对齐目标始终取自 `pagerState` |
| 点 tab 不直接写路由 | ✅ | 点 tab 仍然只 `animateScrollToPage`；路由写发生在 `finally` / 稳定后对齐器，且**取的是 Pager 的位置** |
| 路由回写单向（Pager → 路由） | ✅ | 稳定后对齐器也是 **Pager → 路由**，不引入「路由 → Pager」方向的写 |
| 禁止双向写 | ✅ | 没有新增「路由 → Pager」的写路径 |
| 回写与对齐的幂等性 | ✅ | `shouldAlign` 的第 5 条 + 对齐器的幂等检查 |

**需要 GPT 确认的一点**：稳定后对齐器是**第二条**「Pager → 路由」的写路径（第一条是 `snapshotFlow` 回写）。它与 CR-17 的「单向」不冲突（方向相同），但**确实新增了一个写入者**。如果 GPT 认为「Pager → 路由」只能有一条写路径，则应改用 **A2**（把回写本身改造为稳定后回写，而不是并行走第二条路）。**这一点是本方案唯一需要产品 / 架构裁决的地方。**

---

## 7. 单测与 Compose 集成测试

### 7.1 纯函数单测（`app/src/test`，JVM）

| 编号 | 输入 | 期望 | 备注 |
|---|---|---|---|
| P1 | `tokenOwned=false, SETTLED, 静止, current==settled, route≠current` | `false` | 旧协程不得抢新协程的收尾 |
| P2 | `tokenOwned=true, CANCELLED, 静止, current==settled, route≠current` | **`false`** | ★ OBS-2 的核心判据 |
| P3 | `tokenOwned=true, SETTLED, 静止, current==settled, route≠current` | `true` | 正常路径仍可对齐 |
| P4 | `tokenOwned=true, SETTLED, **滚动中**, current≠settled, route≠current` | `false` | 滚动中不对齐 |
| P5 | `tokenOwned=true, SETTLED, 静止, **current≠settled**, route≠current` | `false` | ★ 未稳定窗口（S10） |
| P6 | `tokenOwned=true, SETTLED, 静止, current==settled, **route==current**` | `false` | 幂等：已是目标页就不写 |
| P7 | `tokenOwned=true, CANCELLED, 静止, current==settled, route==current` | `false` | 取消 + 已一致 → 无需写 |

**表驱动**实现：一个 `@Test` 遍历上表全部行；每行一个命名 case，失败信息里带 case 名。

### 7.2 现有守卫单测（回归）

`TabScrollGuard` 的既有单测（token 唯一性、`end` 的幂等与不越权、`ignoresWrite` 的中间页口径、不可变快照）**必须全部保持通过**，且**实现不得改动**（N7 / INV-5）。

新增一条：`begin` 之后 `end(旧 token)` 返回 false 且**不改动任何字段**（已有覆盖则不重复加）。

### 7.3 Compose 集成测试（`app/src/test` + Robolectric，或 `androidTest`）

用真实的 `HorizontalPager` + `PagerState`（不 mock），构造出 S1 / S2 / S4 三种时序，断言**最终路由**：

| 编号 | 场景 | 断言 |
|---|---|---|
| X1 | S1 点 tab 走完 | 路由 == 目标页 |
| X2 | S2 动画途中拖动 | **路由在整个过程中从未等于「中间页」**；最终路由 == Pager 停留页 |
| X3 | S4 拖回另一页 | 最终路由 == Pager 停留页（≠ 点击目标） |
| X4 | 幂等 | 连续两次「路由已等于停留页」时不产生额外写入 |

**X2 的断言方式**：订阅路由状态，收集所有取值，断言集合中**不含**中间页（或不含 Pager 从未真正停留过的页）。

### 7.4 变异验证（强制）

每个新测试都要做：

1. 改坏实现（例如把 `outcome == SETTLED` 去掉）→ 测试**必须变红**；
2. **不许**用编译失败冒充变异命中（改动必须能编译）；
3. 无条件还原，还原后全量单测必须全绿。

建议的变异点清单：

| 变异 | 预期变红的测试 |
|---|---|
| 去掉 `outcome == SETTLED` | P2 / P7 |
| 去掉 `currentPage == settledPage` | P5 |
| 去掉 `!isScrollInProgress` | P4 |
| 去掉 `routePage != currentPage` | P6 |
| 去掉 `tokenOwned` | P1 |
| 把 `settledPage` 换回 `currentPage` | X2 / X3 |
---

## 8. 真机复核矩阵

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

### 8.2 复核矩阵

| 场景 | 对应 §4 | 操作 | 期望打点序列 | 期望最终态 | 判定 |
|---|---|---|---|---|---|
| **M1** | S1 | 「今日」点「日历」，不动 | `tabClick=2` → `scrollStart=2` → `guardIgnore=1` → `selectMain=Calendar` → `scrollEnd=2` → `scrollFinally aligned=true` → `align=2` | Pager=日历，路由=日历，选中日历 | `align` 恰好 1 次且目标 == 停留页 |
| **M2** | S2 | 「今日」点「日历」，动画途中右滑回「今日」 | `tabClick=2` → `scrollStart=2` → `guardIgnore=1` → **`scrollCancel=2`** → `scrollFinally aligned=true` → **（期望：无 `Route/align`）** → `currentPage=0` → `selectMain=Today` | Pager=今日，路由=今日，选中今日 | **全程无 `Route/align`**；日志中**不得出现**「统计」或「日历」的路由写 |
| **M3** | S3 | 「今日」点「日历」，滑到「日历」停 | 同 M2 但最终 `currentPage=2` | Pager=日历，路由=日历 | 稳定后对齐器对齐到 2；`align` 来源标记为 `settle` |
| **M4** | S4 | 「今日」点「日历」，滑到「统计」停 | 同 M2，最终 `currentPage=1` | Pager=统计，路由=统计 | 最终路由 == Pager 停留页（≠ 点击目标，正确） |
| **M5** | S5 | 手势中点另一个 tab | 出现 `tabClick` + `scrollCancel` | 以 Pager 最终停留页为准 | 路由 == 停留页；无过渡态写入 |
| **M6** | S6 | 快点不同 tab | `scrollFinally aligned=false`（旧）+ `aligned=true`（新） | Pager=最后点击的页 | 旧协程**不得**产生 `align` |
| **M7** | S7 | 快点相同 tab（回归 R10 s5_same） | seq1 `aligned=false`、seq2 `aligned=true`；`Route/align` 恰好 1 次且只归 seq2 | Pager=目标页 | **必须与 R10 完全一致** |
| **M8** | S9 | 纯横滑（回归 R10 s7_swipe_fast） | **无** `A5/scrollCancel`、**无** `Route/align` | 路由跟随翻页 | **必须与 R10 完全一致** |
| **M9** | S8 / S10 | 拖动到边界后立刻松手（回弹） | `scrollCancel` → 无 `align` → 回弹落定后 `currentPage` → `selectMain` | 路由 == 落定页 | **不得**在回弹窗口内出现 `Route/align` |
| **M10** | 全局 | 取消动画 + 连点等组合 | —— | —— | 不崩溃、不卡死、导航胶囊与标题始终一致 |

### 8.3 通过判据（量化）

| 编号 | 判据 |
|---|---|
| V1 | M2 的 3 个有效轮次中，**`Route/align` 出现次数为 0**（现状是 1 次，且目标是中间页） |
| V2 | M2 / M3 / M4 的最终路由 **== Pager 的 `settledPage`** |
| V3 | M2 / M4 的日志中，**路由取值序列里不出现「统计」**（M2）或「日历」（M4，出现在点击目标页那一瞬除外） |
| V4 | M7 的 `aligned` 归属与 `Route/align` 次数与 R10 一致 |
| V5 | M8 仍无 `A5/scrollCancel`、无 `Route/align` |
| V6 | 全程无 ANR、无崩溃、无「标题与页面不一致」的截图 |
| V7 | 证据文件齐备且 SHA256SUMS 全量校验通过 |

### 8.4 复核的诚实性要求

- **不得**把合成手势的轮次计为「真实手指验证」；
- **不得**只报告「没看到抖动」而不给原始 logcat —— 必须能逐行比对；
- 若某个场景**无法稳定复现**（例如真机上手指速度过快），必须记录尝试次数与失败轮次，**不得**只留成功轮次。

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

### 9.3 禁止的「修复」方式

以下做法会让 R10 的判据看似通过、实际是退化，**明令禁止**：

- 为了让 `Route/align` 消失而**去掉整个对齐逻辑**（会让「标题与页面不一致」在其它路径复现）；
- 把 `guardIgnore` 的中间页规则放宽成「任何页都回写」（会重现 CR-22 的「点 tab 卡一下」）；
- 改 `Route/align` 的目标页定义（会让真机证据无法与历史轮次比对）；
- 用固定延迟把 `finally` 推迟（N5）。

---

## 10. 回滚方案

### 10.1 提交分级

```text
提交 1：文档 —— 本设计 + 需求文档对应章节的登记（口径不变则不动需求文档）
提交 2：实现 —— 纯函数 + finally 分支 + 稳定后对齐器 + 打点增强 + 单测
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

### 10.3 回滚的前置条件

1. 实现提交**必须可独立 revert**（不与文档、真机收口混在一笔）；
2. `ScrollAlignPolicy` 是**新增**的纯函数，不改动 `TabScrollGuard` 的既有实现 → revert 时不会牵连 R10 已验证的 token 机制；
3. `finally` 的改动是**分支新增**而非重写 → revert 后代码回到原样，**不需要**重建；
4. 回滚后必须**重跑全量单测**，确认仍全绿；
5. **不使用** `git checkout --` 撤销未提交改动（项目纪律明令禁止；曾误删过未提交代码）。

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

| 改 `HorizontalPager` 架构 | 超出 OBS-2 的问题边界（CR-17 的 Pager 唯一真相源架构不能动） |



