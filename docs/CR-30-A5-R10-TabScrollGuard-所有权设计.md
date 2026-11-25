# A-5 R10 · TabScrollGuard 同目标连续点击的守卫所有权设计（v3 设计 → 已实施 → 真机复核已完成，待 GPT 复审）

> **当前状态（2026-11-21 更新；下方历史设计记录全部保留，未覆盖）**
>
> | 阶段 | 状态 | 提交 / 证据 |
> | --- | --- | --- |
> | 设计 v1 | GPT 退回修订 | 本文件 §9 对照表 |
> | 设计 v2 | GPT 附条件通过（4 项编码前约束） | 本文件 §9 / §10 |
> | 设计 v3 | 4 项约束全部落实 | 文档先行提交 `79eeba1` |
> | 实现 | **已落地**：token 所有权 + 不可变快照 | 实现提交 `d9098cf`（618 测全绿 0 skip；变异 M1~M6+M7′ 共 7 项真红；S1 结构性审查通过） |
> | 证据登记 | 已入库 | `ee5c57d`（SHA256SUMS 全量校验一致） |
> | GPT 复审 | **代码实现通过；整体交付附条件通过；P2 暂不关闭，等待真机复核** | 见 `docs/CODE_REVIEW.md` 文末 |
> | 真机复核 | **已完成**：Redmi 2407FRK8EC · 仅 120Hz · 3 场景 × 3 轮 = 9 个有效轮次 | 证据目录 `docs/CR-30-A3-实验补证/evidence/a5_r10_guard_ownership_real/`（结论见该目录 `README.md`） |
>
> **真机复核结论摘要**：s5_same 3/3 轮满足 GPT 核心判据（seq1 `aligned=false`、seq2 `aligned=true`、
> `Route/align` 每轮恰好 1 次且仅由 seq2 触发、无 `A5/routeScroll`）；s7 快速横滑跨两页 3/3 轮
> **未出现 `A5/routeScroll`**（OBS-1 本轮未命中，观察项继续开放）；s8 横滑打断程序化滚动 3/3 轮
> 打断成立且**最终路由 = Pager 实际停留页**（≠ 点击目标页）。
> **P2 是否关闭、OBS-1 是否关闭，均由 GPT 复审裁决，本轮不自行宣布。**
>
> 分支 `exp/a5-route-probe`。**本文件正文仍是设计记录**（代码在 `d9098cf`，此处不复制实现代码）。
> **v2 修订说明**：按 GPT 独立复审（退回修订）逐条落实 5 项必修 + Q1~Q6 重新裁决 + T8 接线审查，
> 逐项对应见文末 §9「v1 → v2 修订对照表」。

## 1. 问题定位（P2 的精确边界）

### 1.1 现状代码（未修改）

    class TabScrollGuard {
        @Volatile var programmatic: Boolean = false
        @Volatile var target: Int = -1

        fun begin(index: Int) { target = index; programmatic = true }

        fun ignoresWrite(index: Int): Boolean = ignoresWrite(programmatic, target, index)

        fun end(index: Int): Boolean {
            if (target != index) return false      // ← 唯一的取代判据
            programmatic = false
            target = -1
            return true                           // ← true = 由本协程对齐路由
        }
    }

### 1.2 缺陷机理

`end` 用 **target index** 充当「这次协程的身份」。当新点击的 target **与旧点击相同**时，
旧协程被取消后进入 finally：此时 `target` 已被新点击改写成**同一个值**，`target != index`
判据失效 → `end` 误判旧协程仍是所有者 → ① 错误返回 true（旧协程抢到对齐权），
② 把新点击刚建立的 `programmatic`/`target` 守卫状态一并清掉 → 新协程结束时 `end` 返回 false，
真正的主人反而没有对齐权。

### 1.3 真机复现（R9 v3 s5_same，两次点击统计页，target 均为 1）

    seq1: A5/tabClick=1#1 → Pager/scrollStart → A5/scrollStart=1#1
          A5/scrollCancel=1#1 → A5/scrollFinally=1#1 aligned=true → Route/align=1   ← 旧协程抢权
    seq2: A5/tabClick=1#2 → Pager/scrollStart → A5/scrollStart=1#2
          A5/scrollEnd=1#2   → A5/scrollFinally=1#2 aligned=false                    ← 真主人被剥夺
          （seq2 没有 Route/align）

### 1.4 两个事实必须分开陈述（GPT 复审强调）

1. **所有权错误已被日志直接证明**（旧协程清掉新协程的守卫状态）。
2. **用户可见的错误路由尚未被本轮证据证明**——R9 最终页面仍是统计页，与视觉一致，
   不能把这次问题描述成「已经发生页面错位」；它是**其他滚动时序下可能产生错误路由对齐的风险**。

### 1.5 缺陷边界（为什么其它五轮没暴露）

| 场景 | 新点击 target | 旧协程 `end` 判据 `target != index` | 结果 |
|---|---|---|---|
| s1_single | 无取代者 | 不触发 | 正常 |
| s2_double | 1 → 2（不同） | 成立 → false | **旧逻辑已挡住** |
| s3_triple | 1 → 2 → 0（不同） | 成立 → false | **旧逻辑已挡住** |
| s4_reverse | 1 → 0（不同） | 成立 → false | **旧逻辑已挡住** |
| s6_other | 1 → 2（不同） | 成立 → false | **旧逻辑已挡住** |
| **s5_same** | **1 → 1（相同）** | **不成立 → 误 true** | **P2 触发** |

**结论：P2 只在「新旧点击目标相同」时穿透。** 目标不同的情况旧逻辑靠 `target != index` 侥幸挡住，
但那是「用值判身份」的偶然正确，不是所有权语义的正确表达。

## 2. 设计方案：基于 token 的守卫所有权（v2 修订）

### 2.1 核心思想

守卫状态从「只有 target 值」升级为「**带唯一身份的状态**」：每次 `begin` 发放一个单调递增、
永不复用的 **owner token**；`end` 必须携带自己的 token，只有 token 与当前 owner 一致，
本次协程才拥有「结束守卫 + 决定是否对齐路由」的权利。**协程身份由 token 决定，与 target 无关。**

### 2.2 目标 API（v2：`end` 只收 token）

    class TabScrollGuard {
        /** 程序化滚动进行中（只读语义不变）。 */
        val programmatic: Boolean
        /** 本次程序化滚动要去的页索引；-1 = 无（只读语义不变）。 */
        val target: Int
        /** 当前守卫状态的所有者 token；NO_OWNER = 无守卫（可读，供测试与诊断）。 */
        val owner: Long

        /**
         * **统一只读快照入口**——测试断言三要素必须用它取一次快照。
         * 三个派生 getter 各自独立读 state，依次调用时可能跨越状态变化；
         * 需要「同一时刻的 programmatic/target/owner」时一律读本属性。
         */
        internal val snapshot: GuardState get() = state

        /** 开始一次程序化滚动，发放唯一 token 并返回它。 */
        fun begin(index: Int): Long

        /**
         * 结束一次程序化滚动。
         * @return true  = token 命中当前 owner：由本协程按 Pager 停留页对齐路由；
         *         false = 已被更新的点击取代：完全不动状态，由那次点击自己收尾。
         */
        fun end(token: Long): Boolean

        companion object {
            const val NO_OWNER: Long = 0L
            /** 纯函数口径：token 是否命中当前所有者（便于单测与变异验证）。 */
            fun ownsToken(owner: Long, token: Long): Boolean =
                token != NO_OWNER && token == owner
            /** 纯函数口径不变：程序化滚动期间的回写是否忽略（只忽略中间页）。 */
            fun ignoresWrite(programmatic: Boolean, target: Int, index: Int): Boolean =
                programmatic && index != target
        }
    }

**为什么 `end` 不再收 index（v2 修订）**：token 已经**唯一且充分**地证明所有权。
若同时校验 index，一旦调用方传错 index，就会出现「token 正确但 index 错」→ 返回 false →
**谁也释放不了这个 owner** → 守卫永久停留在 ACTIVE（`programmatic=true` 一直忽略中间页回写），
这是比原缺陷更糟的失败模式。去掉 index 后：**token 匹配即结束并清空，不匹配则一个字段都不动**。
`target` 继续保存在状态快照里供 `ignoresWrite()` 使用；AppNav 侧继续用自己的 `target` 局部变量
做 A5 探针打点与路由业务，两者互不干扰。

### 2.3 不可变状态快照（v2 修订：替代 v1 的多字段 @Volatile）

    /**
     * 守卫状态快照。**`internal` 可见**：同模块测试可读、可断言；外部无法改写
     * （字段全是 val，构造器随 internal 一起受限）。
     */
    internal data class GuardState(
        val programmatic: Boolean,
        val target: Int,
        val owner: Long
    )

    @Volatile
    private var state = GuardState(programmatic = false, target = -1, owner = NO_OWNER)

    private val nextToken = AtomicLong(0)
    private val lock = Any()

    val programmatic: Boolean get() = state.programmatic
    val target: Int get() = state.target
    val owner: Long get() = state.owner

    // 注：上面三个 getter 各自独立读 state（跨状态变化时可能读到不同时刻的组合）；
    //     需要同一时刻的三要素时用 snapshot（internal），不要逐个 getter 连读。

    fun begin(index: Int): Long = synchronized(lock) {
        val token = nextToken.incrementAndGet()
        state = GuardState(programmatic = true, target = index, owner = token)
        token
    }

    fun end(token: Long): Boolean = synchronized(lock) {
        val snapshot = state
        if (!ownsToken(snapshot.owner, token)) return false   // 不是主人：快照一个字段都不动
        state = GuardState(programmatic = false, target = -1, owner = NO_OWNER)
        true
    }

    fun ignoresWrite(index: Int): Boolean {
        val snapshot = state                              // ← 只读一次快照
        return ignoresWrite(snapshot.programmatic, snapshot.target, index)
    }

**为什么改成不可变快照（v2 修订）**：v1 用三个独立 `@Volatile` 字段，**单字段可见性 ≠ 状态整体一致性**——
读取期间若恰好发生状态更新，理论上可能读到 `programmatic=true / target=-1 / owner=NO_OWNER` 这种
**非法业务状态**。改成「一个不可变对象 + 一次快照读取」后，
**任何一次读取拿到的都是某个时刻的合法完整状态**，不存在撕裂读。
`ignoresWrite()` 也从「分别读三个字段」改为「读一次快照」。

**为什么仍保留 `synchronized`**：`begin`/`end` 是 **read-modify-write 复合操作**（读快照 → 判断 → 换快照），
需要互斥才能保证「判断」与「替换」之间没有其它协程插进来。当前调用全在主线程，属**纵深防御**：
将来守卫若被挪到后台线程，语义不依赖字段写入顺序。`ignoresWrite()` 是纯读，不需要进锁。

### 2.4 为什么不用 PerfProbe.nextSeq() 当 token

`PerfProbe.nextSeq()` 是 A-5 探针的序号（`AtomicInteger a5Seq`），语义属于**观测埋点**。
业务守卫若复用它，探针被移除或重构就会连带影响业务逻辑（探针只读原则要求业务不依赖探针）。
守卫自带 `AtomicLong` 计数器，与探针完全解耦。

### 2.5 状态字段只保留两份（v2 修订：删除 `issuedState`）

v1 同时维护 `ownerState` 与 `issuedState`，但 `begin()` 总是同时更新两者，
于是 `token == owner && token == issued` **是在重复检查同一个事实**——没有独立语义。
v2 删除 `issuedState`，只保留：

| 字段 | 作用 |
|---|---|
| `nextToken: AtomicLong` | 生成下一次 token（单调递增、永不复用） |
| `state.owner` | 当前有效 token（= 最后一次 `begin` 发放者；守卫结束后为 NO_OWNER） |

### 2.6 不变式（实现后必须成立，可被单测直接断言）

| # | 不变式 |
|---|---|
| I1 | `programmatic == true` ⟹ `owner != NO_OWNER` ∧ `target >= 0` |
| I2 | `owner != NO_OWNER` ⟹ 该 owner 必然是**最后一次 begin 发放**的 token |
| I3 | `end` 返回 false ⟹ `programmatic` / `target` / `owner` **一个字段都没变**（快照整体不变） |
| I4 | `end` 返回 true ⟹ token 命中 owner；随后状态整体回到 IDLE 快照 |
| I5 | token 在同一 guard 实例内单调递增且不复用 |
| I6 | 任何一次读取（`programmatic`/`target`/`owner`/`ignoresWrite`）拿到的都是**合法完整快照**，
不会出现 `programmatic=true` 而 `target=-1` / `owner=NO_OWNER` 的非法组合 |
| I7 | `ignoresWrite` 口径不变：程序化期间只忽略中间页，目标页照常回写 |

## 3. 状态转移说明

### 3.1 状态定义

    IDLE        : GuardState(programmatic=false, target=-1, owner=NO_OWNER)
    ACTIVE(t,i) : GuardState(programmatic=true,  target=i,   owner=t)

### 3.2 转移表（v2：`end` 事件只带 token）

| 当前状态 | 事件 | 判据 | 下一状态 | end 返回 |
|---|---|---|---|---|
| IDLE | `begin(i)` → token t | — | ACTIVE(t, i) | — |
| ACTIVE(t1, i) | `begin(j)` → token t2 | 新点击取代 | ACTIVE(t2, j)；**t1 立即失效** | — |
| ACTIVE(t1, i) | `end(t1)` | t1 命中 owner | IDLE | true（**唯一获得对齐权者**） |
| ACTIVE(t2, j) | `end(t1)` | t1 ≠ owner | **ACTIVE(t2, j) 不变** | false |
| ACTIVE(t2, j) | `end(t2)` | t2 命中 owner | IDLE | true |
| IDLE | `end(t1)` | 无守卫 | IDLE 不变 | false |

### 3.3 关键路径（对照 P2 场景）

    同目标连击 s5_same（新设计）：

      begin(1) → t1            ACTIVE(t1, 1)
      begin(1) → t2            ACTIVE(t2, 1)   ← t1 在此被剥夺所有权
      旧协程 end(t1)            token≠owner → false，快照不变   ← 修复点（旧逻辑此处误 true）
      新协程 end(t2)            token==owner → true → IDLE + 对齐

    不同目标连击 s2_double（新设计）：

      begin(1) → t1            ACTIVE(t1, 1)
      begin(2) → t2            ACTIVE(t2, 2)
      旧协程 end(t1)            token≠owner → false，快照不变
      新协程 end(t2)            true → IDLE + 对齐

    手势打断（无取代者）：

      begin(1) → t1            ACTIVE(t1, 1)
      协程被手势取消 end(t1)    token==owner → true → IDLE + 按停留页对齐  ← 保留原语义

### 3.4 边界时序：新协程先结束、旧协程 finally 后执行

      begin(1) → t1 / begin(1) → t2
      新协程 end(t2) → true → IDLE（owner=NO_OWNER）
      旧协程 finally end(t1) → token≠owner → false，**不得复活任何状态**

这是旧实现最容易出错的地方：旧实现里旧协程的 end 会把 `programmatic=false` 写回去，
若旧协程此时**晚于**新协程执行，就会把已经结束的状态重新污染。token 机制从结构上排除。

## 4. 测试设计

### 4.1 文件与原则

- 新增 `app/src/test/java/com/screentrace/app/ui/TabScrollGuardOwnershipTest.kt`（8 条新用例）。
- 既有 `TabScrollGuardTest.kt` 适配新签名（`begin` 返回 token、`end` 收 token），
  其 `ignoresWrite` 三条口径断言**原样保留不放宽**。
- **每条用例必须同时断言 `programmatic`、`target`、`owner` token**，不得只断言 Boolean 返回值
  （P2 正是「只测返回值口径」掩盖了状态被错误清除的事实）。

### 4.2 两条测试边界声明（GPT 复审明确要求）

**边界一：直接调用 begin/end ≠ 真实协程取消测试。**
本组用例验证的是**守卫状态机**在「取消后的 finally 语义」下的正确性；它**不等于**验证
Compose 中真实的协程取消、`Pager` 动画互相中断与路由回写的时序。
真实链路验证属**实施后的真机复核**范畴（Q6：代码评审通过后单独申请 Redmi），
**本轮不引入 Compose UI 测试框架**。

**边界二：T8 的「横滑不受影响」不靠 UI 测试证明。**
采用**静态调用链审查**（见 §4.4）+ 守卫 IDLE 态断言，**不引入庞大的 Compose UI 测试框架**。

### 4.3 八条用例（v2：T3 时序已修正）

| # | 用例 | 关键断言（含状态三要素） |
|---|---|---|
| T1 | 不同目标被新点击取代 | `end(t1)`=false；`programmatic`=true、`target`=2、`owner`=t2；随后 `end(t2)`=true 且三要素清空 |
| T2 | **相同目标被新点击取代（P2 核心）** | `end(t1)`=**false**；`programmatic`=true、`target`=1、`owner`=t2；`end(t2)`=true |
| T3 | **旧协程先结束、新协程后结束（v2 修正时序）** | `begin(1)`→t1；**`end(t1)`=true（它仍是当时唯一的所有者，理应成功释放）**，状态回 IDLE（`programmatic`=false、`target`=-1、`owner`=NO_OWNER）；随后 `begin(2)`→t2、`end(t2)`=true 再次回 IDLE |
| T4 | 新协程先结束后旧协程 finally 执行 | `end(t2)`=true → 三要素清空；`end(t1)`=false → 三要素**不得被复活** |
| T5 | 连续三次相同目标 | t1/t2 的 `end` 均 false；`owner` 始终为 t3；仅 `end(t3)`=true |
| T6 | 取消异常路径（**状态机时序测试**） | (a) 取消后被新点击取代 → `end(t1)`=false、快照不变；(b) 取消但**无**取代者 → `end(t1)`=true（保留「按停留页对齐」原语义） |
| T7 | 正常单次点击 | begin 后三要素齐备；`end(t)`=true 后 `programmatic`=false、`target`=-1、`owner`=NO_OWNER |
| T8 | 横滑期间不受程序化守卫影响 | ① 守卫 IDLE 时 `ignoresWrite` 恒 false（滑动跟手不受影响）；② **静态调用链审查结论**：横滑路径**不调用 begin**（见 §4.4） |

**v1 → v2 的 T3 修正说明**：v1 写的「旧协程先结束、新协程后结束」却配了
「旧 end=false / 新点击状态不变 / 新 end=true」的断言——那是**新点击先发生、旧协程后结束**（= T2）。
**照 v1 描述实现会把正常的所有权释放误判成失败**。v2 的 T3 改为：旧协程先结束时它**确实拥有**
所有权，`end(t1)` **必须返回 true** 并把状态释放回 IDLE；随后新的一次点击独立走完整生命周期。

补充断言（建议随 T2/T5 一并加）：`t1 != t2`（token 唯一）、`owner` 初始为 NO_OWNER、
`end` 传入**过期 token**返回 false 且快照整体不变。

### 4.4 T8 的 AppNav 静态调用链审查（v2 新增，替代 UI 测试）

**审查目标**：证明「横滑」路径**不会**创建 owner（不会调用 `begin`），即守卫所有权只由
「点 tab」与「路由回滚」两类程序化滚动产生。

**结论（依据当前 `AppNav.kt` 源码）**：

    调用链（AppNav.kt 行号以分支 HEAD 为准）：

      ① 横滑入口：HorizontalPager(userScrollEnabled = detailPage == null)  // AppNav.kt:599
         用户手指拖动 → Pager 内部滚动 → currentPage 变化
      ② 回写：snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect  // :439-440
         → tabGuard.ignoresWrite(index)   // :446（IDLE 时恒 false，不忽略）
         → viewModel.selectMain(target)   // :452 → mainPage 变化
      ③ LaunchedEffect(mainPage) 重启   // :402
         → if (pagerState.currentPage != target) { ... }   // :411 ← **关键守卫**
            横滑刚回写的 currentPage 此刻**就等于** target → 条件不成立 → **不进入 begin**

    对照 begin 的两个唯一入口：
      (a) FreshNavBar onSelect → tabGuard.begin(index)              // :966（点 tab）
      (b) LaunchedEffect(mainPage) 内 currentPage != target 时      // :416（路由回滚 / 二级页返回）

**结论（正常横滑时序）**：横滑只走 ①→②→③，而 ③ 的 `currentPage != target` 守卫使横滑
**在正常时序下不调用 begin**。`alignRouteToPager()` 触发的 `selectMain` 同样受该守卫保护，
**不会自激**产生新 owner。因此横滑期间守卫通常恒为 IDLE，`ignoresWrite` 恒 false →
**跟手行为不受程序化守卫影响**。

**收敛声明（GPT 复审要求，不使用绝对结论）**：

**不再使用「横滑永远不会调用 begin」这样的绝对说法。** 上面的推导成立于「`LaunchedEffect(mainPage)`
重启的瞬间，Pager 的 `currentPage` 与该 effect 的 `target` 一致」这一前提；而
`LaunchedEffect` 的重启与 Pager 手势进度**不是同一个原子过程**，快速滑动 / 连续跨页 / 路由状态更新
与 effect 重启**可能交错**。因此正确的表述是：

> **正常横滑时序下不建立程序化 owner；快速横滑存在时序窗口，不能排除意外建立 owner。**

**快速横滑的交错分析（`isScrollInProgress` / `currentPage` / 路由状态）**：

    用户快速连续跨两页（今日 → 日历）时的可能序列：

      t0 手指拖动，isScrollInProgress=true
      t1 currentPage 0 → 1（过半）→ snapshotFlow 回写 index=1
         → selectMain(Stats) → 路由状态待更新（mainPage 仍是 0 或 1）
      t2 手指继续拖，currentPage 1 → 2（此时 mainPage 可能尚未重组到 1）
      t3 LaunchedEffect(mainPage) 因路由状态更新而重启：
         此刻 currentPage 已经是 2，而该 effect 的 target 取自 mainPage（可能是 1）
         → **currentPage(2) != target(1) → 进入回滚分支 → begin(1) + animateScrollToPage(1)**
         → **意外建立程序化 owner，且把用户正滑向的第 2 页拽回第 1 页**

该窗口是否存在取决于「路由状态更新 → 重组 → effect 重启」相对「Pager 继续滚动」的相对时序，
**本轮不修改导航架构、不引入 `isScrollInProgress` 门禁**（超出授权范围），如实登记为待观察项：

| 编号 | 待观察项 | 性质 | 处置 |
|---|---|---|---|
| OBS-1 | 快速跨页横滑时 `LaunchedEffect(mainPage)` 的 `currentPage != target` 是否可能成立并触发回滚滚动、建立程序化 owner | **CR-17 注释里「回写触发的 mainPage 变化满足 currentPage == target」这一假设在快速横滑下可能不成立** | **本轮只登记不修**（改导航架构超出授权）；实施后真机重点检查「横滑是否意外建立 owner」；若成立另立条目裁决 |

**实施后的接线自查（替代 UI 测试，作为 T8 的可执行部分）**：

- 静态自查：`begin` 的调用点仍只有两处（点 tab、路由回滚），`TabScrollGuard` 内不留任何由手势直接触发的入口。
- 真机自查（Q6 另申请设备后）：快速连续横滑跨两页，检查 logcat 是否出现 `A5/routeScroll`（该事件只由
  `LaunchedEffect(mainPage)` 的回滚分支发射）——**若快速横滑出现 `A5/routeScroll`，即说明 OBS-1 命中**，
  届时按 OBS-1 处置。
- 本轮**不要求**引入 Compose UI 测试框架。


### 4.5 变异验证计划（v3：只保留**可执行**变异）

| 变异 | 改动 | 预期变红的用例 | 可执行性 |
|---|---|---|---|
| M1 | `end` 去掉 token 校验，退回 `target != index` 口径 | **T2**（旧实现必红）、T4 | 可执行 |
| M2 | `end` 在非 owner 时也清空快照 | T1、T3、T4 | 可执行 |
| M3 | `end` 在非 owner 时把 owner 改回旧 token | T4 | 可执行 |
| M4 | `begin` 不更新 owner（只改 target） | T2、T5 | 可执行 |
| M5 | `begin` 的 token 不递增（复用上一次的） | token 唯一性断言、T5 | 可执行 |
| M6 | `end` 返回 true 时不清空 programmatic/target | T7 | 可执行 |
| **M7′** | **`begin` 写入的快照不自洽**（`owner` 写成 `NO_OWNER` 或与 token 不符） | **T1、T2、T5、T7**（owner 断言） | **可执行**（改一个赋值，行为立刻可观察） |

**M7′ 取代原 M7；原 M8 删除（v3 修订）**：

- 原 M7（「把实现改回三个独立 `@Volatile` 字段」）**不是有效变异**——单线程 JVM 单测无法稳定
  构造撕裂读，改回多字段后测试可能依旧全绿；**不允许仅把实现改回旧字段就声称变异被测试杀死**。
- 原 M8（「`end` 同时校验 index」）在 `end(token)` 新 API 下**没有 index 参数**：要恢复它必须先改
  方法签名与全部调用方 → **编译失败**，属于纪律明令禁止的「用编译失败冒充变异命中」→ **删除**。
- 撕裂读防线改由**结构性审查项 S1** 承担（**不计入变异命中数**），见下。

**结构性审查项 S1（不计入变异命中数）**

- 审查内容：`TabScrollGuard` 内**只允许一个** `@Volatile` 字段（`state`）；不得再有第二个可变状态字段；
  所有只读出口（`programmatic` / `target` / `owner` / `snapshot` / `ignoresWrite`）**都只读这一份快照**；
  `begin` / `end` 只做**整体替换**。
- 验证方式：代码审查 + grep 确认（`@Volatile` 计数、`state` 的写入点计数）。
- **不作为变异命中计数**，因为 JVM 单线程下不可稳定观察——如实标注，不虚报命中数。

**纪律要求**：变异必须**改变可观测结果**（如 M1 必红 T2），不接受「非行为变异」与「编译红冒充」；
每项变异改坏 → 跑 → 确认红 → **无条件还原**。

### 4.6 与 A-5 证据链的衔接

- 探针 `PerfProbe.a5Finally(target, seq, aligned)` 的 `aligned` **字段名不改**（Q2 裁决：保留，
  不破坏 R9 历史证据的解析口径）；其**语义**已在 R9 §5.1 澄清为「守卫所有权」而非页面对齐结果。
- **Q3 裁决：暂不增加 owner token 探针**，避免扩大探针修改范围。

## 5. 最小 diff 计划（v2）

### 5.1 生产代码

| 文件 | 改动 | 估计 |
|---|---|---|
| `app/src/main/java/com/screentrace/app/ui/TabScrollGuard.kt` | 引入 `GuardState` 不可变快照 + `nextToken`；`begin(index): Long` 返回 token；**`end(token): Boolean`**；`programmatic`/`target`/`owner` 改为只读 val（读快照）；**新增 `internal val snapshot: GuardState` 统一只读快照入口**（测试断言三要素必须用它一次取值，不逐个 getter 连读）；companion 纯函数 `ownsToken(owner, token)`；**移除 `ownsTarget`**（Q1 裁决：删除，token 取代；已 grep 确认仅本文件与既有测试引用，无生产调用方） | +52 / −22 |
| `app/src/main/java/com/screentrace/app/ui/AppNav.kt` | 2 处调用点：点 tab 处 `val guardToken = tabGuard.begin(index)`（:966）+ `tabGuard.end(guardToken)`（:979）；routeScroll 处 `val guardToken = tabGuard.begin(target)`（:416）+ `tabGuard.end(guardToken)`（:428）。**其余一行不动** | +4 / −4 |

生产代码净改动 ≈ **2 文件、约 30 行**，不触碰任何业务分支逻辑、不改导航动画与预加载策略。

### 5.2 测试代码

| 文件 | 改动 | 估计 |
|---|---|---|
| `app/src/test/java/com/screentrace/app/ui/TabScrollGuardTest.kt` | 适配新签名（4 处调用）；`ownsTarget` 两条断言改为 `ownsToken` 纯函数断言；`ignoresWrite` 三条口径断言原样保留 | +6 / −6 |
| `app/src/test/java/com/screentrace/app/ui/TabScrollGuardOwnershipTest.kt` | **新增**：T1~T8 共 8 条 + 3~4 条补充断言 | +120 |

### 5.3 实施顺序（若获批）

1. **文档先行**：
   - `docs/需求文档-屏幕使用时间App.md` **§5.2**（Pager 同步条）补一条口径——
     「程序化滚动的守卫所有权按**点击 token** 判定；同目标连续点击时**后一次点击独占所有权**，
     前一次协程被取消后不得清除后一次点击的守卫状态、不得抢先对齐路由」。
   - **§7 新增第 69 条**（**当前 §7 为 68 条**，非 v1 文档所写的 54 条）：
     「**程序化滚动守卫所有权**：点 tab 的程序化滚动期间，守卫所有权按点击 token 判定——
     同目标连续点击时**后一次点击独占所有权**，前一次协程被取消后不得清除后一次点击的守卫状态、
     不得抢先对齐路由；最终路由对齐由**最后一次点击**的协程按 Pager 实际停留页完成；
     不同目标连续点击、手势打断程序化滚动、横滑跟手三类既有行为不变；
     守卫状态以不可变快照整体替换，读取不得出现「programmatic=true 但 target=-1」这类非法中间态。」
   - §7 条数 **68 → 69**（需求文档头部同步登记该条）。
2. 实现 `TabScrollGuard` 快照 + token 化（先生产后测试适配）。
3. 新增 T1~T8 用例 → 全量单测 `--rerun-tasks` 保持全绿。
4. **变异验证 M1~M6 + M7′**（共 7 项**可执行**变异）逐项验证会红（每项改坏→跑→确认红→无条件还原）；**S1 结构性审查项**（撕裂读防线：单一 `@Volatile` 状态 + 只读出口只读该快照 + `begin`/`end` 整体替换）走代码审查 + grep，**不计入变异命中数**。
5. 真机复核（**Q6：代码评审通过后单独申请 Redmi**）：s5_same 场景 ≥3 轮，确认 seq1 `aligned=false`、
   seq2 `aligned=true`、`Route/align` 只出现在末击。**本轮不申请、不擅自启动真机。**
6. 分笔提交：文档一笔 → 实现+测试一笔 → 真机收口一笔（沿用 A-5 分支工作流）。

## 6. Q1~Q6 裁决结果（按 GPT 复审，v1 开放项已全部关闭）

| # | 事项 | 最终裁决 | 落点 |
|---|---|---|---|
| Q1 | `ownsTarget` | **删除**，token 取代 | §2.2 / §5.1 / §5.2 |
| Q2 | 探针 `aligned` 字段 | **保留**，不破坏 R9 证据兼容性（语义澄清见 R9 §5.1） | §4.6 |
| Q3 | 增加 owner token 探针 | **暂不增加**，避免扩大探针修改范围 | §4.6 |
| Q4 | 修复时机 | **A-5 完整矩阵之前** | §5.3 步骤 3（矩阵会大量复现同目标连击场景） |
| Q5 | 新增验收条目 | **是**，**§7-69**（§7 由 **68 → 69** 条） | §5.3 步骤 1 |
| Q6 | 真机复测 | **代码评审通过后，再单独申请 Redmi**（本轮不申请） | §5.3 步骤 5 |

## 7. 允许保留的设计方向（GPT 复审确认，不改）

- 每次 begin 发放独立 token。
- 只有当前 owner 可以结束守卫。
- 旧协程 finally 不得影响新 owner。
- Pager 仍是唯一真相源。
- 不修改导航动画和预加载策略。

## 8. 本轮边界声明

- 本轮**未修改任何导航业务代码**（`TabScrollGuard.kt` / `AppNav.kt` / `TabScrollGuardTest.kt` 均未改动）。
- 本轮**未执行** Redmi 60Hz、未执行 vivo、未启动完整性能矩阵、未合入 main、**未擅自启动真机实验**。
- A-5 R9 原始证据（v1 旧 APK 轮 / v2 坐标缺陷轮 / v3 身份对账轮）**全部保留，不覆盖、不删除**。
- **不自行宣布 A-5 通过**；待 GPT 复审本 v2 设计后再决定是否进入实现。

## 9. v1 → v2 修订对照表（逐条对应 GPT 退回意见）

| GPT 意见 | v2 落实位置 | 状态 |
|---|---|---|
| P1-1 T3 时序写错 | §4.3 T3 改为「旧协程先结束 → `end(t1)` 必须 true → 状态回 IDLE」，并加 §4.3 末尾修正说明 | 已修 |
| P1-2 `issuedState` 无独立语义 | §2.5 删除 `issuedState`，只留 `nextToken` + `state.owner`；§2.2 纯函数改为 `ownsToken(owner, token)` | 已修 |
| P1-3 多字段读取无整体一致性 | §2.3 改为不可变 `GuardState` 快照；`ignoresWrite` 单次快照读取；新增不变式 I6；新增变异 M7 | 已修 |
| P1-4 `end(index, token)` 双校验致守卫残留 | §2.2 API 改为 `end(token)`；§2.2 末段说明「token 已充分证明所有权」；§4.3 补充断言「过期 token 返回 false 且快照不变」；新增变异 M8 | 已修 |
| §7 编号修正（68 → 69） | §5.3 步骤 1 给出 §7-69 完整验收文案；§6 Q5 标注 §7 当前为 **68** 条 | 已修 |
| T8 需补 AppNav 接线审查 | §4.4 给出静态调用链审查结论（横滑不调用 begin，附行号与关键守卫）；§4.2 边界二声明不引入 Compose UI 测试框架 | 已修 |
| T6 明确为状态机时序测试 | §4.2 边界一 + §4.3 T6 标注 | 已修 |
| 直接调用 begin/end ≠ 真实协程取消测试 | §4.2 边界一（真机复核归 Q6） | 已修 |

## 10. v2 → v3 收口（GPT 附条件通过的四项编码前约束）

| GPT 约束 | v3 落实位置 | 状态 |
|---|---|---|
| ① 增加统一状态快照读取接口，测试必须基于**同一快照**断言三个字段 | §2.2 新增 `internal val snapshot: GuardState`（一次取值）；§2.3 注释提醒「不要逐个 getter 连读」；§4.3 全部用例改用 `guard.snapshot` 一次取值后断言三要素 | 已落实 |
| ② 修订 M7，使其成为真实可执行的变异测试，或明确改为结构性审查 | 原 M7（改回多字段）**删除**，改为**结构性审查项 S1**（单一 `@Volatile` + 只读出口同源 + 整体替换；代码审查 + grep，**不计入变异命中数**）；另**新增可执行的 M7′**（`begin` 写入快照不自洽 → 被 T1/T2/T5/T7 杀死） | 已落实 |
| ③ 删除当前不可执行的 M8，不能用编译失败作为变异命中 | **M8 已删除**：新 API 无 index 参数，恢复它必然改签名 → 编译失败 → 属禁止的「编译红冒充」 | 已落实 |
| ④ 收敛 T8 的绝对化结论，补充快速横滑时序分析 | §4.4 删除「永远不调用 begin」，改为「**正常横滑时序下不建立 owner；快速横滑存在时序窗口，不能排除意外建立 owner**」；补 `isScrollInProgress` / `currentPage` / 路由状态的交错分析，登记 **OBS-1**（CR-17 注释里「回写触发的 mainPage 变化满足 currentPage == target」在快速横滑下可能不成立）；给出实施后的静态自查 + 真机自查（看是否出现 `A5/routeScroll`） | 已落实 |

**附：本轮实施新发现（超出 v2 设计预期，如实登记）**：OBS-1 是 v2 静态分析未覆盖的时序窗口。
本轮**只登记不修**（改导航架构超出授权范围），实施后真机重点复核。

