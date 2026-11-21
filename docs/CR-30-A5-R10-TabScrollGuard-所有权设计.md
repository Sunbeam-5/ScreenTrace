# A-5 R10 · TabScrollGuard 同目标连续点击的守卫所有权设计（待评审，未实施）

> 分支 `exp/a5-route-probe`。**本文件只是设计，不含任何代码改动**（本任务不授权修改导航业务代码）。
> 目标：为 P2（`TabScrollGuard` 对同目标连续点击的所有权判断缺陷）给出基于 token/seq 的所有权机制、
> 状态转移说明、测试设计与最小 diff 计划，供 GPT 评审后再决定是否实施。

## 1. 问题定位（P2 的精确边界）

### 1.1 现状代码

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

最终路由仍为统计页、与视觉一致——因为两次点击目标相同、页面本来就没离开统计页，
**这次是「侥幸无害」**。但机制是错的：旧协程在 seq2 刚开始滚动时就执行了
`alignRouteToPager(pagerState.currentPage)`，写入的是**当时**的 Pager 位置；
只要同目标连击与滚动时序叠加到 Pager 已过半翻页，就会写出与视觉不符的路由。

### 1.4 缺陷边界（为什么其它五轮没暴露）

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

## 2. 设计方案：基于 token 的守卫所有权

### 2.1 核心思想

守卫状态从「只有 target 值」升级为「**带唯一身份的状态**」：每次 `begin` 发放一个单调递增、
永不复用的 **owner token**；`end` 必须携带自己的 token，只有 token 与当前 owner 一致，
本次协程才拥有「结束守卫 + 决定是否对齐路由」的权利。**协程身份由 token 决定，与 target 无关**。

### 2.2 目标 API

    class TabScrollGuard {
        /** 程序化滚动进行中（只读语义不变）。 */
        val programmatic: Boolean
        /** 本次程序化滚动要去的页索引；-1 = 无（只读语义不变）。 */
        val target: Int
        /** 当前守卫状态的所有者 token；NO_OWNER = 无守卫（可读，供测试与诊断）。 */
        val owner: Long

        /** 开始一次程序化滚动，发放唯一 token 并返回它。 */
        fun begin(index: Int): Long

        /**
         * 结束一次程序化滚动。
         * @return true  = token 命中当前 owner：由本协程按 Pager 停留页对齐路由；
         *         false = 已被更新的点击取代（或 token/index 不匹配）：
         *                **不改任何状态**，由那次点击自己收尾。
         */
        fun end(index: Int, token: Long): Boolean

        companion object {
            const val NO_OWNER: Long = 0L
            /** 纯函数口径：token 是否命中当前所有者（便于单测与变异验证）。 */
            fun ownsToken(owner: Long, issued: Long, token: Long): Boolean =
                token != NO_OWNER && token == owner && token == issued
            /** 纯函数口径不变：程序化滚动期间的回写是否忽略（只忽略中间页）。 */
            fun ignoresWrite(programmatic: Boolean, target: Int, index: Int): Boolean =
                programmatic && index != target
        }
    }

### 2.3 字段与写入顺序

    private val nextToken = AtomicLong(0)
    private val lock = Any()

    @Volatile private var ownerState: Long = NO_OWNER      // 当前活跃守卫的所有者
    @Volatile private var issuedState: Long = NO_OWNER      // 最后一次 begin 发放的 token
    @Volatile private var targetState: Int = -1
    @Volatile private var programmaticState: Boolean = false

    fun begin(index: Int): Long = synchronized(lock) {
        val token = nextToken.incrementAndGet()
        issuedState = token
        ownerState = token
        targetState = index
        programmaticState = true
        token
    }

    fun end(index: Int, token: Long): Boolean = synchronized(lock) {
        if (!ownsToken(ownerState, issuedState, token)) return false   // 不是主人：一个字段都不动
        if (targetState != index) return false                        // 防御：token 与 target 必须同时对得上
        programmaticState = false
        targetState = -1
        ownerState = NO_OWNER
        true
    }

**为什么 `begin` 先写 token 再写其余字段**：`end` 的第一道闸门是 token 比对。若 `begin` 先写
`ownerState = token`，则在 begin 之后、旧协程 end 之前，end 一定读到**新 token** → 判负 →
旧协程不得动新状态。反之若旧协程的 end 先于 begin 执行（物理顺序），它拿到对齐权是合理的
（那时用户还没点第二次），随后 begin 建立新守卫，最终由新协程收尾。两种交错都收敛。

**为什么还要 `synchronized`**：当前 begin/end 全部发生在主线程，理论上无并发；加锁是**纵深防御**，
避免将来守卫被挪到后台线程时引入撕裂读。同时也让「begin 覆盖旧 owner」与「end 判定 owner」
成为两个互斥的临界区，语义不依赖字段写入顺序。

### 2.4 为什么不用 PerfProbe.nextSeq() 当 token

`PerfProbe.nextSeq()` 是 A-5 探针的序号（`AtomicInteger a5Seq`），语义属于**观测埋点**。
业务守卫若复用它，探针被移除/重构就会连带影响业务逻辑（探针只读原则要求业务不依赖探针）。
守卫自带 `AtomicLong` 计数器，与探针完全解耦。

### 2.5 不变式（实现后必须成立，可被单测直接断言）

| # | 不变式 |
|---|---|
| I1 | `programmatic == true` ⟹ `owner != NO_OWNER` ∧ `target >= 0` |
| I2 | `owner != NO_OWNER` ⟹ `owner == issued`（最后一次 begin 发放者即当前所有者） |
| I3 | `end` 返回 false ⟹ `programmatic` / `target` / `owner` **一个字节都没变** |
| I4 | `end` 返回 true ⟹ `token` 命中 owner ∧ `target` 命中 index |
| I5 | token 全局单调递增且不复用（同一 guard 实例内） |
| I6 | `ignoresWrite` 口径不变：程序化期间只忽略中间页，目标页照常回写 |

### 2.6 与既有不变式的关系（不越界）

- **Pager 唯一真相源不变**：对齐仍写 `pagerState.currentPage`，点 tab 仍不直接写路由（CR-17 红线）。
- **横滑跟手不变**：滑动手势期间不调 begin/end，守卫保持 IDLE，`ignoresWrite` 恒 false。
- **CR-22 ① 口径不变**：程序化滚动期间只忽略中间页回写、目标页照常回写。
- **手���打断仍按停留页对齐**：无新点击取代时，`end` 仍返回 true（token 命中 owner）。
- **不涉及**：CR-28 数据架构、Room、备份、主题、性能、Pager 预加载。

## 3. 状态转移说明

### 3.1 状态定义

    IDLE    : programmatic=false, target=-1, owner=NO_OWNER
    ACTIVE(t, i) : programmatic=true,  target=i,   owner=t

### 3.2 转移表

| 当前状态 | 事件 | 判据 | 下一状态 | end 返回 |
|---|---|---|---|---|
| IDLE | `begin(i)` → token t | — | ACTIVE(t, i) | — |
| ACTIVE(t1, i) | `begin(j)` → token t2 | 新点击取代 | ACTIVE(t2, j)；**t1 立即失效** | — |
| ACTIVE(t1, i) | `end(i, t1)` | t1 命中 owner 且 target==i | IDLE | true（**唯一获得对齐权者**） |
| ACTIVE(t2, j) | `end(i, t1)` | t1 ≠ owner | **ACTIVE(t2, j) 不变** | false |
| ACTIVE(t2, j) | `end(j, t2)` | t2 命中 owner 且 target==j | IDLE | true |
| IDLE | `end(i, t1)` | 无守卫 | IDLE 不变 | false |
| ACTIVE(t2, j) | `end(k, t2)`，k≠j | token 命中但 index 不符 | **不变** | false（防御） |

### 3.3 关键路径（对照 P2 场景）

    同目标连击 s5_same（新设计）：

      begin(1) → t1            ACTIVE(t1, 1)
      begin(1) → t2            ACTIVE(t2, 1)   ← t1 在此被剥夺所有权
      旧协程 end(1, t1)         token≠owner → false，状态不动   ← 修复点（旧逻辑此处误 true）
      新协程 end(1, t2)         token==owner → true → IDLE + 对齐

    不同目标连击 s2_double（新设计）：

      begin(1) → t1            ACTIVE(t1, 1)
      begin(2) → t2            ACTIVE(t2, 2)
      旧协程 end(1, t1)         token≠owner → false，状态不动
      新协程 end(2, t2)         true → IDLE + 对齐

    手势打断（无取代者）：

      begin(1) → t1            ACTIVE(t1, 1)
      协程被手势取消 end(1,t1)  token==owner → true → IDLE + 按停留页对齐  ← 保留原语义

### 3.4 边界时序：新协程先结束、旧协程 finally 后执行

      begin(1) → t1 / begin(1) → t2
      新协程 end(1, t2) → true → IDLE（owner=NO_OWNER）
      旧协程 finally end(1, t1) → token≠owner → false，**不得复活任何状态**

这是旧实现最容易出错的地方：旧实现里旧协程的 end 会把 `programmatic=false` 写回去，
若旧协程此时**晚于**新协程执行，就会把已经结束的状态重新污染。token 机制从结构上排除。

## 4. 测试设计

### 4.1 文件与原则

- 新增 `app/src/test/java/com/screentrace/app/ui/TabScrollGuardOwnershipTest.kt`（8 条新用例）。
- 既有 `TabScrollGuardTest.kt` 适配新签名（`begin` 返回 token、`end` 收 token），
  其 `ignoresWrite` 三条口径断言**原样保留不放宽**。
- **每条用例必须同时断言 `programmatic`、`target`、`owner` token**，不得只断言 Boolean 返回值
  （P2 正是「只测返回值口径」掩盖了状态被错误清除的事实）。
- 守卫生命周期与协程生命周期解耦：测试直接按「begin → end」的时序驱动守卫，不依赖真实协程；
  取消异常路径用「旧协程 finally 调用 end」的等价时序表达。

### 4.2 八条用例（对应 GPT 指定的覆盖点）

| # | 用例 | 关键断言（含状态三要素） |
|---|---|---|
| T1 | 不同目标被新点击取代 | `end(1,t1)`=false；`programmatic`=true、`target`=2、`owner`=t2；随后 `end(2,t2)`=true 且三要素清空 |
| T2 | **相同目标被新点击取代（P2 核心）** | `end(1,t1)`=**false**；`programmatic`=true、`target`=1、`owner`=t2；`end(1,t2)`=true |
| T3 | 旧协程先结束、新协程后结束 | 旧 end=false 后新点击的 `programmatic`/`target`/`owner` **完全不变**；新 end=true 后全清 |
| T4 | 新协程先结束后旧协程 finally 执行 | 新 end=true → 三要素清空；旧 end=false → 三要素**不得被复活** |
| T5 | 连续三次相同目标 | t1/t2 的 end 均 false；`owner` 始终为 t3；仅 t3 的 end=true |
| T6 | 取消异常路径 | (a) 取消后被新点击取代 → end=false、状态不动；(b) 取消但**无**取代者 → end=true（保留「按停留页对齐」原语义） |
| T7 | 正常单次点击 | begin 后三要素齐备；end=true 后 `programmatic`=false、`target`=-1、`owner`=NO_OWNER |
| T8 | 横滑期间不受程序化守卫影响 | 守卫 IDLE 时 `ignoresWrite` 恒 false（滑动跟手不受影响）；程序化期间只忽略中间页、目标页照常回写 |

补充断言（建议随 T2/T5 一并加）：`t1 != t2`（token 唯一）、`owner == NO_OWNER` 初始值、
`end` 传入「token 对但 index 错」返回 false 且状态不动。

### 4.3 变异验证计划（测试必须能变红）

| 变异 | 改动 | 预期变红的用例 |
|---|---|---|
| M1 | `end` 去掉 token 校验，退回 `target != index` 口径 | **T2**（旧实现必红）、T4 |
| M2 | `end` 在非 owner 时也清空 `programmatic`/`target` | T1、T3、T4 |
| M3 | `end` 在非 owner 时把 `owner` 改回旧 token | T4 |
| M4 | `begin` 不更新 `owner`（只改 target） | T2、T5 |
| M5 | `begin` 的 token 不递增（复用上一次的） | token 唯一性断言、T5 |
| M6 | `end` 返回 true 时不清空 `programmatic` | T7 |
| M7 | `end` 忽略 index 校验（只查 token） | 「index 错配」补充断言 |

纪律要求：变异必须**改变可观测结果**（如 M1 必红 T2），不接受「非行为变异」与「编译红冒充」。

### 4.4 与 A-5 证据链的衔接（不破坏既有口径）

- 探针 `PerfProbe.a5Finally(target, seq, aligned)` 的 `aligned` **字段名不改**（改了会破坏既有
  logcat 解析口径与 R9 历史证据）；其**语义**已在 R9 §5.1 澄清为「守卫所有权」而非页面对齐结果。
- 实施后 A-5 探针可**额外**记录 owner token（例如 `A5/scrollFinally=…#… owned=true`），
  用于真机直接观察所有权是否正确流转——列为**可选项**，待 GPT 决定是否纳入（涉及探针格式变更）。

## 5. 最小 diff 计划

### 5.1 生产代码

| 文件 | 改动 | 估计 |
|---|---|---|
| `app/src/main/java/com/screentrace/app/ui/TabScrollGuard.kt` | 字段 token 化（owner/issued/nextToken/lock）；`begin` 返回 token；`end(index, token)`；`programmatic`/`target` 改为只读 val；companion 新增 `ownsToken` 纯函数；**移除 `ownsTarget`**（语义已被 token 取代，仅本文件与既有测试引用，已 grep 确认无生产调用方） | +45 / −18 |
| `app/src/main/java/com/screentrace/app/ui/AppNav.kt` | 2 处 begin/end 调用点携带 token（点 tab 处 `AppNav.kt:966/979`；routeScroll 处 `AppNav.kt:416/428`）。**其余一行不动** | +4 / −4 |

生产代码净改动 ≈ **2 文件、约 27 行**，不触碰任何业务分支逻辑。

### 5.2 测试代码

| 文件 | 改动 | 估计 |
|---|---|---|
| `app/src/test/java/com/screentrace/app/ui/TabScrollGuardTest.kt` | 适配新签名（4 处调用）；`ownsTarget` 两条断言改为 `ownsToken` 纯函数断言；`ignoresWrite` 三条口径断言原样保留 | +6 / −6 |
| `app/src/test/java/com/screentrace/app/ui/TabScrollGuardOwnershipTest.kt` | **新增**：T1~T8 共 8 条 + 3 条补充断言 | +120 |

### 5.3 实施顺序（若获批）

1. **文档先行**：在 `docs/需求文档-屏幕使用时间App.md` §5.2（Pager 同步条）补一条口径——
   「程序化滚动的守卫所有权按点击 token 判定，同目标连续点击时旧协程不得夺取新协程的对齐权」；
   §7 是否追加验收条目由 GPT 裁决（建议追加 1 条，§7 由 54 条增至 55 条）。
2. 实现 `TabScrollGuard` token 化（先改生产，后改测试适配）。
3. 新增 8 条所有权用例 → 全量单测 `--rerun-tasks` 保持全绿。
4. **变异验证 M1~M7** 逐项验证会红（每项改坏→跑→确认红→无条件还原）。
5. 真机复核（可选）：Redmi 上跑 s5_same 场景 ≥3 轮，确认 seq1 `aligned=false`、seq2 `aligned=true`、
   `Route/align` 只出现在末击。**需负责人放行设备**（Redmi 曾用于自动化测试）。
6. 分笔提交：文档一笔 → 实现+测试一笔 → 真机收口一笔（沿用 A-5 分支工作流）。

## 6. 待 GPT 裁决点

| # | 事项 | 建议 |
|---|---|---|
| Q1 | 是否保留 `ownsTarget` 纯函数 | **移除**（语义已被 token 取代；无生产调用方）；如需保留作历史口径请指示 |
| Q2 | 探针 `aligned` 字段是否改名（如 `owned`） | **不改名**（破坏 R9 历史证据的解析口径），仅文档澄清 |
| Q3 | 是否在探针里额外输出 owner token | 建议**纳入**（真机可直接观察所有权流转），但涉及探针格式变更，需裁决 |
| Q4 | 实施时机 | 建议**在 A-5 完整矩阵之前**先修 P2：矩阵会大量复现同目标连击场景，带着缺陷跑矩阵会产生一批「无法解释」的样本 |
| Q5 | §7 是否追加验收条目 | 建议**追加 1 条**（54 → 55 条），口径＝「同目标连续点击时对齐权归末次点击」 |
| Q6 | 真机复核设备与时机 | 待负责人放行 Redmi；vivo 仍 `PENDING_VERIFICATION`，不动 |

## 7. 本轮边界声明

- 本轮**未修改任何导航业务代码**（`TabScrollGuard.kt` / `AppNav.kt` / `TabScrollGuardTest.kt` 均未改动）。
- 本轮**未执行** Redmi 60Hz、未执行 vivo、未启动完整性能矩阵、未合入 main。
- A-5 R9 原始证据（v1 旧 APK 轮 / v2 坐标缺陷轮 / v3 身份对账轮）**全部保留，不覆盖、不删除**。
- **不自行宣布 A-5 通过**；待 GPT 复审本设计后再决定是否实施与是否扩大实验范围。
