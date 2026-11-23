# 全项目工程审计 · 技术债务登记

| 项 | 值 |
|---|---|
| 编号 | CR-30-TD（Gemini 全项目审计登记） |
| 阶段 | **阶段 A · 只登记，不修** |
| 基线 | 分支 `exp/a5-route-probe` @ `ead51d6`；全量单测 **620 / 0 fail / 0 skip** |
| 范围 | 10 项技术债务，每项记录 **7 个属性**：当前代码事实 / 风险是否已实际复现 / 优先级与理由 / 影响范围 / 建议验证方式 / 是否需立即修 / 与其他任务的依赖关系 |
| 本文档改动 | **无任何生产代码 / Gradle / Manifest / 备份格式改动** |
| 重要声明 | 本登记中**多数项未实测**：凡未实测的一律标「**否（未实测）**」，**不得**被当成结论使用 |

---

## 0. 登记原则

### 0.1 优先级定义

| 级别 | 含义 |
|---|---|
| **P0** | 已经造成用户可见故障或数据错误，必须立即修 |
| **P1** | 不影响当前使用，但**发布前必修**（阻塞发行或即将触发的崩溃/审计问题） |
| **P2** | 有明确风险面且触发条件现实存在，但当前不影响主流程 |
| **P3** | 可维护性债务，不修也不会出事，但会持续增加后续批次的成本 |

### 0.2 三条纪律

1. **不把「没写」等同于「不存在」**：AGP 默认存在 `release` 构建类型；本项目没显式声明 ≠ 没有 Release。
2. **不把「未复现」写成「没问题」**：未实测的一律标「否（未实测）」，并给出验证方式。
3. **内存类债务先出基准测试设计，不直接重写**：流式化是一次高风险重构，没有数据支撑就不能动。

### 0.3 汇总

| 编号 | 债务项 | 风险已复现？ | 优先级 | 需立即修 |
|---|---|---|---|---|
| TD-01 | Release 构建 / R8 / 正式签名 | 否（未实测构建） | **P1** | 否（发布前必修） |
| TD-02 | Shizuku 大文本峰值内存 | 否（未实测峰值） | **P2** | 否 |
| TD-03 | 备份 JSON 序列化峰值内存 | 否（未实测峰值） | **P2** | 否 |
| TD-04 | Shizuku AIDL 版本兼容 | 否（当前版本正常） | **P1** | 否（升级依赖时必修） |
| TD-05 | AppViewModel 职责过多 | 否（可维护性债务） | **P3** | 否 |
| TD-06 | BackupRepository 职责过多 | 否（可维护性债务） | **P3** | 否 |
| TD-07 | WebDAV URL 路径编码 | 否（当前路径不含特殊字符） | **P2** | 否（支持自定义目录名前必修） |
| TD-08 | WebDAV Basic Auth 的 HTTPS 边界 | 否 | **P2** | 否 |
| TD-09 | GitHub Actions CI | **是（保障缺失本身）** | **P2** | 否 |
| TD-10 | QUERY_ALL_PACKAGES 发行政策 | 否（功能需要，政策未评估） | **P1** | 否（上架 Play 前必修） |

---

## TD-01 · Release 构建 / R8 / 正式签名

### 当前代码事实

| 事实 | 依据 |
|---|---|
| `app/build.gradle.kts` **未显式声明** `buildTypes { release { … } }` | 源码 |
| **但 AGP 默认就存在 `release` 构建类型**——「没显式声明」**不等于**「不存在 Release」 | 实测 `./gradlew :app:tasks --all` 输出含 `assembleRelease` / `bundleRelease` / `assembleReleaseUnitTest` |
| 未配置任何 `signingConfigs` | 全仓库 grep `signingConfig` **零命中** |
| 仓库内**没有任何 keystore 文件** | 递归搜索 `*.jks` / `*.keystore` **零结果**；`.gitignore` 也没有忽略 keystore 的条目 |
| 未配置 `isMinifyEnabled` / `isShrinkResources` → 采用 AGP 默认值：**R8 关闭**、资源压缩关闭 | 源码 |
| 仓库内**没有 `proguard-rules.pro`**，全仓库 grep `proguard` 零命中 | 源码 |
| `buildFeatures { buildConfig = true }` 已开（`PerfProbe` 用 `BuildConfig.DEBUG` 区分打点） | 源码 |

**必须区分的五件事**（GPT 明确要求）：

| 维度 | 现状 | 是否已实测 |
|---|---|---|
| ① **能否构建** | `assembleRelease` 任务**存在**；构建本身**未执行** | 任务存在已实测；能否构建成功**未实测** |
| ② **是否正式签名** | **否** —— 无 signingConfig，产出将是 `app-release-unsigned.apk`，**不能直接安装、不能分发** | 未实测（由配置事实直接推出） |
| ③ **是否启用 R8** | **否** —— 默认 `isMinifyEnabled = false` | 未实测（由配置事实直接推出） |
| ④ **规则是否正确** | **无规则可谈** —— 仓库内没有 proguard 文件 | 未实测 |
| ⑤ **混淆后能否启动 / 采集 / 备份 / 恢复** | **完全未验证** | 未实测 |

**一旦开启 R8，本项目的高危点**（按风险排序）：

1. **Shizuku 内部 AIDL**：`moe.shizuku.server.IShizukuService.Stub.asInterface` 依赖 AIDL 生成的 `Stub` / `Proxy` 类名与签名 → 混淆或裁剪后 `asInterface` 直接失效（见 TD-04）；
2. **kotlinx.serialization**：`@Serializable` 类靠生成的 `Companion.serializer()` 与 `$$serializer` 反射构造 → 备份 JSON 的**编解码**是本项目的命脉（导出 / 恢复 / 云同步全靠它），混淆后极可能抛 `SerializationException`；
3. **Room**：生成的 `*_Impl` / `Database_Impl` 通过反射实例化 → 混淆后数据库打不开；
4. **`BuildConfig`**：需保留（`PerfProbe` 用 `BuildConfig.DEBUG`）；
5. Compose / AndroidX：一般安全，但 `androidx.compose.runtime` 的部分内部类在极端优化下有已知问题。

### 风险是否已实际复现

**否（未实测）**。本阶段**未构建过 release、未安装过 release、未复现任何故障**。

已实测的只有一条：release 构建类型与相关 Gradle 任务**存在**。

### 优先级与理由

**P1**。

- 它**不会**造成用户数据错误，因此不是 P0；
- 但第 ⑤ 项一旦出问题，是**功能性事故**（App 起不来 / 采集失效 / 备份恢复失效），而且是在**发布当天**才暴露；
- 第 ①–③ 项的事实已经清楚（未签名 + 未开 R8），**修复路径明确、成本可控**，不应拖到最后。

### 影响范围

- `app/build.gradle.kts`（需加 `signingConfigs` + `buildTypes { release { … } }`）；
- 新增 `app/proguard-rules.pro`（或 `consumer-rules.pro`）；
- 密钥材料（keystore）**必须放仓库外**并补进 `.gitignore`（当前 `.gitignore` 没有这条，属**待补的安全项**）；
- 风险面：Shizuku AIDL、kotlinx.serialization、Room、BuildConfig。

### 建议验证方式

**分步验证，不得一步到位**：

| 步 | 动作 | 通过标准 |
|---|---|---|
| V1 | 只加 `buildTypes { release { isMinifyEnabled = false; … } }` + 正式签名 | `assembleRelease` 产出**已签名** APK，`apksigner verify` 通过 |
| V2 | 用 V1 的 APK 真机安装（Redmi，需放行） | 启动 → 拉取 → 备份导出 → 备份恢复，四步全通 |
| V3 | 开启 `isMinifyEnabled = true`，**逐条**加 keep 规则 | 同 V2，且 APK 体积明显小于未混淆版 |
| V4 | 用 `apkanalyzer dex packages` 检查关键类 | `moe.shizuku.server.**`、`kotlinx.serialization.**`、`*_Impl`、`BuildConfig` 均在 |

**红线**：**不得**未经验证就整包加 `-keep class com.screentrace.app.** { *; }`。那等于**放弃 R8 的收益却承担混淆的全部风险**。正确粒度是**按需要逐个加**，每条规则都要写明「为什么需要」。

### 是否需立即修

**否**（本阶段不修）。**任何对外分发之前必须完成**。

### 与其他任务的依赖关系

- **依赖 TD-04**（Shizuku AIDL 兼容）先定 keep 规则 —— 否则 R8 一开，Shizuku 通道静默失效；
- **依赖 TD-03**（备份 JSON 序列化）确认序列化类的 keep 规则 —— 备份往返是命脉；
- **依赖 TD-10**（发行渠道）—— 签名与目标渠道是一起决定的（GitHub Releases 需要可安装的签名包）；
- **依赖 TD-09**（CI）—— 发布构建最好由 CI 产出同一份可归档的产物。

---

## TD-02 · Shizuku 大文本峰值内存

### 当前代码事实

| 事实 | 依据 |
|---|---|
| `ShizukuShell.readAll()` 从 `ParcelFileDescriptor.AutoCloseInputStream` **按 64KB 分块读**进 `ByteArrayOutputStream(1 shl 20)`（初始 1MB，自动扩容），最后 `buffer.toString("UTF-8")` **一次性转成 String** | `ShizukuShell.kt:120-145` |
| 代码注释自述体积：「10 天 `dumpsys usagestats --checkin` 约 **13MB** 文本」「10 天 checkin 约 13MB + 明文 **2.4MB**」 | `ShizukuShell.kt` 类注释 |
| 真机实测（同 ROM）：`dumpsys usagestats` → **4.17MB** 明文；`--checkin` → **561KB**（该 ROM 的 `--checkin` 只吐元信息） | `ShizukuUsageProvider.kt` 注释 |
| **每次拉取取两路 dump，两路文本同时驻留内存**（`checkin` 与 `human` 都是局部 val，直到 `parse` 返回才释放） | `ShizukuUsageProvider.load()` |
| `DumpShape.of(checkin)` / `DumpShape.of(human)` 各做一次**全文字符串扫描** | `ShizukuUsageProvider.load()` |
| `UsageDumpParser.parse(checkinText, humanText, …)` 把两路文本**同时作为参数**接收，内部再构造 `checkinEvents` / `intervals` / `screens` / `foreground.spans` 等集合 | `UsageDumpParser.kt` |
| 已有防截断机制：`checkedOutput` 把「被看门狗截断」变成**可见失败**（`ShizukuExecException`），不是静默半份数据 | `ShizukuShell.kt` |

**峰值内存的估算（未实测，仅为量级判断）**：

```text
  ByteArrayOutputStream 内部缓冲   ≥ 13MB（扩容后可能 16MB）
  toString("UTF-8") 的字符存储    ≈ 13MB × 2 = 26MB（UTF-16）
  String 底层的 byte[] 副本       ≈ 13MB（Android 上 String 用 UTF-16，但紧凑字符串场景另算）
  解析期的事件 / 区间集合          视 dump 内容，几 MB 到十几 MB
  两路 dump 并存                   ×2
  ─────────────────────────────────────────
  量级                            ≈ 60–100MB（单次拉取）
```

### 风险是否已实际复现

**否（未实测）**。

- 没有做过 `adb shell dumpsys meminfo com.screentrace.app` 的峰值采样；
- 没有在低端机（2–3GB RAM）上验证过；
- 真机（Redmi 2407FRK8EC）上拉取与解析**均正常**，没有观察到 OOM 或卡顿异常。

### 优先级与理由

**P2**。

- 当前真机表现正常，**没有用户可见故障**；
- 但低端机上的 OOM 是**现实存在**的风险面（13MB 双路 dump 对 2GB 机型不是小数目）；
- **批 13「多机型适配」正要开始** —— 本项应当作为该批的输入，而不是事后补课。

### 影响范围

| 位置 | 角色 |
|---|---|
| `ShizukuShell.readAll` | 读取 + **String 化**（最大的一跳） |
| `ShizukuUsageProvider.load` | 两路并存 + 两次全量扫描 |
| `UsageDumpParser.parse` | 文本在场 + 解析期集合 |
| `IngestPipeline.persist` | 事务期峰值（另算，见 TD-03） |

### 建议验证方式

**先出基准测试设计，不得直接重写为流式。** 基准必须把**六个阶段**分开测，不能只看一个总数：

| 阶段 | 测什么 | 怎么测 |
|---|---|---|
| ① **读取** | fd → 字节的峰值 | `ByteArrayOutputStream` 分配量 + `meminfo` 采样 |
| ② **String 转换** | 字节 → String 的额外副本 | `toString` 前后 `meminfo` 差值 |
| ③ **解析** | 文本 → 事件 / 区间集合的峰值 | 解析前后 `meminfo` 差值 |
| ④ **入库** | 集合 → Room 的事务期峰值 | 事务前后 `meminfo` 差值 |
| ⑤ **备份序列化** | Room → JSON 文本 | 见 TD-03 |
| ⑥ **WebDAV 上传** | JSON 文本 → 网络字节 | 见 TD-07 / TD-08 |

**采样手段**：`adb shell dumpsys meminfo com.screentrace.app` 轮询（间隔 ≤ 200ms，取峰值），或进程内用 `Debug.getNativeHeapAllocatedSize()` + `ActivityManager.MemoryInfo` 在各阶段边界打点。

**对照机型**：至少一台 6GB 机型 + 一台 3–4GB 机型。**vivo `10CEB40M5G001DK` 正在跑自动化，全程不可用** → 需另找设备或等放行。

**决策原则**：**只有当峰值逼近机型限制时**，才讨论「分块读 + 流式解析」。**不得**在基准数据出来之前就把 `readAll` 改写成流式 —— 那是一次高风险重构，且没有数据支撑。

### 是否需立即修

**否**。

### 与其他任务的依赖关系

- 与 **TD-03** 共享「备份序列化峰值」这一段，两者可能**叠加**（拉取后紧接着导出备份）；
- 是 **批 13 多机型适配** 的前置输入；
- 若将来真要改，**不得破坏** `checkedOutput` 的「截断必须变成可见失败」判据与 `StreamCloser` 的关流机制（那是 R10 之外已验证的正确性保障）。
---

## TD-03 · 备份 JSON 序列化峰值内存

### 当前代码事实

| 事实 | 依据 |
|---|---|
| `BackupRepository.buildFile(appVersion, at)` 先把**全部表**读进 `BackupFile` 的各个 List（`daySummary` / `appUsage` / `hourUsage` / `pullLog` / `unlockEvent` / `notificationEvent` / `screenSession` / `appLimit` / `sources[]`） | `BackupRepository.kt:126` |
| `BackupCodec.encode(file)` = `json.encodeToString(BackupFile.serializer(), file)`，Json 配置 `prettyPrint = true`（**体积放大**）+ `encodeDefaults = true` | `BackupModels.kt` `BackupCodec` |
| `exportJson(appVersion)` 返回 `Pair<String, String>`（文件名 + JSON 文本）→ **整份 JSON 以 String 形式驻留内存**，随后才写文件 | `BackupRepository.kt:186` |
| `AppViewModel.buildHtmlReport` 还会**再生成一份 HTML 字符串** | `AppViewModel.kt:874` |
| 云同步上传走同一份 JSON 文本（`SyncManager` → provider `upload(path, bytes)`） | `WebDavProvider.upload` |
| 当前备份 `schemaVersion = 6`（`BackupCodec.SCHEMA_VERSION`） | `BackupModels.kt:172` |

**体积量级（估算，未实测）**：真机现约 8–10 天数据，`daySummary` 数十行、`appUsage` 数百行、`hourUsage` 数千行、`unlockEvent` / `notificationEvent` 数百至数千行 → JSON 文本**几十 KB 量级**，远未到危险区。

**风险随数据量线性增长**：`unlockEvent` / `notificationEvent` 是**事件表**，长期使用 + 跨设备合并后可以到十万级。

### 风险是否已实际复现

**否（未实测）**。没有做过大数据量备份的内存 / 耗时采样。

### 优先级与理由

**P2**。

- 当前数据量下**完全没有症状**（JSON 只有几十 KB）；
- 但它与 TD-02 是**同一类风险**，且增长曲线更陡（事件表是时间累积的）；
- 它还与 **R11 的备份兼容设计**（`docs/CR-30-R11-长亮屏与跨日数据可信度修复设计.md` §7）**共用同一批文件**，必须**同批改**而不是分两轮。

### 影响范围

`BackupRepository.buildFile` / `exportJson` / `exportCsv`；`BackupCodec.encode`；`AppViewModel.buildBackupJson` / `buildHtmlReport`；云同步上传链；`SyncManager`。

### 建议验证方式

| 编号 | 动作 | 通过标准 |
|---|---|---|
| V1 | 造**大数据量**备份（合成 10 万条通知 + 10 万条解锁 + 1 万个会话） | `buildFile` → `encode` → 写文件全程无 OOM，峰值内存有数值记录 |
| V2 | 测 `prettyPrint = true` 的体积放大倍数 | 给出实测倍数（通常 1.3–1.8×），作为「是否关闭 prettyPrint」的决策依据 |
| V3 | 测「导出 + WebDAV 上传」连做 | 峰值不高于单段峰值的 2×（说明没有出现「两份文本同时在场」） |
| V4 | 若超标，评估 `Json.encodeToStream` 写文件 | 证明写文件路径可流式化，**不改变备份格式** |

**红线**：**不得**在本轮改备份 `schemaVersion` 或备份结构 —— 会与 R11 §7 的备份兼容设计（是否加质量标记列、是否升到 v7）**撞车**，两轮改动落在同一批文件上极易互相覆盖。

### 是否需立即修

**否**。

### 与其他任务的依赖关系

- 与 **TD-02** 同批做基准（两段可能叠加）；
- 与 **R11 §7 备份兼容**同批改文件，**不得**分成两轮；
- 与 **TD-01**（R8 keep 规则）相关：`@Serializable` 类的 keep 规则要一起定；
- 与 **TD-07 / TD-08**（WebDAV）在「上传」阶段联动。

---

## TD-04 · Shizuku AIDL 版本兼容

### 当前代码事实

| 事实 | 依据 |
|---|---|
| `ShizukuShell` **直接依赖 Shizuku 的内部包**：`import moe.shizuku.server.IRemoteProcess` / `import moe.shizuku.server.IShizukuService` | `ShizukuShell.kt` |
| 通过 `IShizukuService.Stub.asInterface(binder)` 调用 `newProcess(String[], String[], String)` | `ShizukuShell.kt:110-117` |
| 注释明确说明这是**有意为之**：api 13.1.5 已移除公共的 `Shizuku.newProcess`，而 binder 单次事务上限 1MB，10 天 checkin 约 13MB，**只有走 `newProcess` 拿 fd 才能读完整** | `ShizukuShell.kt` 类注释 |
| 依赖版本：`dev.rikka.shizuku:api:13.1.5` + `dev.rikka.shizuku:provider:13.1.5` | `app/build.gradle.kts` |
| 失败路径是**降级**而不是崩溃：`pingBinder()` 失败 → `ShizukuExecException` → `PullCoordinator` 降级到 UsageStats 通道 | `ShizukuShell.start()` / `PullCoordinator` |
| **但** AIDL 签名变化 / `moe.shizuku.server` 包名变化 / `newProcess` 语义变化会导致 `NoSuchMethodError` / `ClassNotFoundException` —— 这是**链接期错误**，不是 `ShizukuExecException`，**当前 catch 覆盖不到** | 代码结构推断 |
| 全仓库对 `moe.shizuku.server` 的引用**集中在 `ShizukuShell.kt` 一处** | grep 结果 |

### 风险是否已实际复现

**否**。当前依赖 13.1.5 运行正常；**未验证** Shizuku 升级到 14.x 后该内部包是否还在、签名是否变。

### 优先级与理由

**P1**。

- Shizuku 是**主通道**（§2.1），AIDL 一旦断裂，用户**直接掉到降级通道** —— 体验降级、数据变少（降级通道拿不到 `totalTimeUsed` 与区间）；
- 但它**不影响已经入库的数据**，且有降级兜底 → **不是 P0**；
- Shizuku 大版本升级是**外部事件**，时间不可控 → 需要**提前加固**而不是等出事。

### 影响范围

`ShizukuShell`（**唯一**直接依赖内部包的地方，已经集中在适配层）；`ShizukuUsageProvider`；`PullCoordinator` 的降级链。

### 建议验证方式

| 编号 | 动作 | 通过标准 |
|---|---|---|
| V1 | **适配层隔离检查**：`grep -r "moe.shizuku.server" app/src/main` | 只命中 `ShizukuShell.kt`（满足「调用集中在适配层」） |
| V2 | 在 `start()` 外层加**宽 catch**（`Throwable`，含 `Error`），把 `NoSuchMethodError` / `ClassNotFoundException` 转成 `ShizukuExecException` | 模拟该异常时**走既有降级链**而不是崩 |
| V3 | 跟踪 Shizuku 上游 changelog；依赖升级时跑真机拉取冒烟 | 升级后 `checkin` 仍能读到完整 dump |
| V4 | 确认 R8 keep 规则覆盖 `moe.shizuku.server.**` | 见 TD-01 的 V4 |

**红线**：**不得**在未经验证的情况下改用**反射**调用内部 AIDL。注释里已经论证过反射的两个问题：① 引入新的不确定性；② **不解决 1MB binder 事务上限**，读 13MB 仍然必须走 fd。若将来真要「隐藏内部包依赖」，正确方向是**等官方公共 API 支持 fd / 文件传递后再迁移**，而不是反射。

### 是否需立即修

**否**（V2 的宽 catch 属低成本加固，可在下一个实现批次顺带做）。**升级 Shizuku 依赖时必须做**。

### 与其他任务的依赖关系

- 与 **TD-01** 强相关：`moe.shizuku.server.*` 的 AIDL Stub 类**必须 keep**，否则混淆后 `asInterface` 失效；
- 与 **TD-02** 共享 `ShizukuShell` 的读流逻辑（改流式化时不能破坏关流与截断判据）。

---

## TD-05 · AppViewModel 职责过多

### 当前代码事实

`AppViewModel.kt` 共 **1517 行**，同时承担 **13 类职责**：

| # | 职责 | 主要成员 |
|---|---|---|
| 1 | **页面路由状态机** | `page` / `selectMain` / `openRanking` / `openRankingFor` / `openPeriod` / `setPeriodType` / `openCloudSync` / `openSettings` / `openUnlockDetail` / `openNotificationDetail` / `goBack` |
| 2 | **日期选择与筛选** | `selectionFor` / `currentSelection` / `applySelection` ×2 / `clearFilter` / `setCalendarMonth` |
| 3 | **拉取编排** | `pullOnOpen` / `retryPull` / `refreshFromPull` / `applyPullOutcome` / `dismissPullError` / `showNotice` / `consumeTransient` |
| 4 | **数据加载与装配** | `loadAll` / `loadPeriod` / `loadMonthLevels` / `setAllEmpty` |
| 5 | **主题与外观设置** | `setThemeVariant` / `setDarkTheme` / `setDynamicColor` / `setCustomThemeSeed` / `setDailyGoalMin` / `setPullFrequency` / `setLowThresholdMin` |
| 6 | **壁纸** | `loadWallpaper` / `onWallpaperPicked` / `setWallpaperDim` / `setWallpaperBlur` / `setWallpaperColorScope` / `setWallpaperCrop` / `resetWallpaperCrop` / `clearWallpaper` |
| 7 | **云同步** | `setWebdavConfig` / `setWebdavPassword` / `setSyncFrequency` / `setSyncWifiOnly` / `testCloudConnection` / `uploadToCloud` / `downloadFromCloud` / `refreshSyncStatus` |
| 8 | **设备管理** | `setDeviceNickname` / `listCloudDevices` / `inspectCloudDevice` / `switchToDevice` / `mergeDeviceIntoLocal` |
| 9 | **备份 / 恢复** | `buildBackupJson` / `backupCompatWarning` / `buildCsvFiles` / `decodeBackup` / `restoreBackup` |
| 10 | **HTML 报告** | `buildHtmlReport` |
| 11 | **应用分类** | `openAppCategories` / `loadAppCategories` / `setAppCategory` |
| 12 | **引导与提示** | `completeOnboarding` / `consumeNotice` / `isTransientNotice` |
| 13 | **持久化封装** | `persist(block)` |

补充事实：

- `loadAll(cancelPrevious)` **一次装配今日页 / 统计页 / 日历页 / 排行页四套状态**（`TodayData` / `StatsData` / `CalendarData` / `RankingData`）；
- 依赖是**直接 new 出来的具体实现**（Room、DataStore、Shizuku），**没有接口抽象** → 单测只能靠 Robolectric 走全链路；
- `TodayData` / `StatsData` / `CalendarData` / `RankingData` 四个**数据类与 ViewModel 定义在同一个文件**里。

### 风险是否已实际复现

**否**。当前**没有故障、没有性能问题、没有崩溃**。这是**可维护性**债务。

### 优先级与理由

**P3**。

- **行数不是唯一标准**（见下），1517 行本身不是问题；
- 功能全绿、真机复核稳定 → **重构收益不足以抵消回归风险**；
- 但它是后续所有功能批次（批 10b / 11 / 12 / 13 / 14）的**摩擦源** —— 每加一个功能都要在这 1517 行里找位置。

**拆分判据（不以行数为唯一标准）** —— 必须分析这四件事：

| 维度 | 分析 |
|---|---|
| **职责耦合** | `page` 状态被路由、日期选择、壁纸、备份**共用**；`pullOnOpen` 触发的数据刷新会同时改四套页面状态 → 这些耦合是**真耦合**，拆开要引入协调器 |
| **状态所有权** | 壁纸状态（`wallpaperState` / `wallpaperConfig`）本质是**本机 UI 偏好**，与统计状态无关 → 天然的独立边界 |
| **依赖方向** | 期望 `UI → ViewModel → 仓库接口 → 数据源` 单向；当前 ViewModel **反向依赖具体实现**（直接 new Room / DataStore）→ 拆分的**第一步是接口化**，不是搬文件 |
| **测试边界** | 备份 / 恢复有天然的**往返测试边界**（导出 → 恢复 → 再导出应一致）；云同步有**契约边界**（provider 接口已有）→ 这两块最适合先独立 |

### 影响范围

所有引用 `AppViewModel` 的 UI 层：`AppNav.kt`（1254 行）、`TodayScreen.kt`、`CalendarScreen.kt`（448 行）、`SettingsScreen.kt`（906 行）、排行 / 周期总结 / 详情 / 壁纸编辑等屏。

### 建议验证方式

**低风险渐进方案（不推荐一次性大拆分）**：

| 步 | 动作 | 风险 | 通过标准 |
|---|---|---|---|
| **第 0 步** | 只加**接口抽象**（`SettingsStore` / `BackupService` / `SyncService` / `WallpaperStore` 各抽一个接口），**不改任何行为** | 极低 | 全量单测全绿；diff 只含新增接口 |
| **第 1 步** | 搬**壁纸**（10 个方法、状态单一、天然边界清晰）到独立 `WallpaperViewModel` | 低 | 全量单测全绿 + 真机壁纸全流程复核 |
| **第 2 步** | 搬**备份 / 恢复**到 `BackupViewModel`（有天然往返测试边界） | 低 | 备份往返测试通过 + 真机导出/恢复复核 |
| **第 3 步** | 搬**云同步 / 设备管理**到 `SyncViewModel` | 中 | 真机云同步 + 设备合并复核 |

**每一步独立提交、独立全量单测、独立真机复核**；单步 diff 建议控制在 ~600 行以内。

### 是否需立即修

**否**。应排在**所有功能批次之后**。

### 与其他任务的依赖关系

- 与 **R11 的 UI 诚实化**有交叉：R11 会改 `TodayData`（`dayPercent: Int → Int?`）。**顺序必须是「先做 R11 的 UI 诚实化，再做拆分」**，否则两次改动撞在同一片代码上；
- 与 **TD-06** 同源（都属「文件过大 / 职责过多」类），但 **TD-06 的合并语义风险更高**，应排在 TD-05 之后；
- 是 **批 11 / 12 / 13 / 14** 的前置便利项，不是前置**必要**项。
---

## TD-06 · BackupRepository 职责过多

### 当前代码事实

`BackupRepository.kt` 共 **783 行**，**34 个成员**，承担 11 类职责：

| # | 职责 | 成员 |
|---|---|---|
| 1 | **备份文件构建** | `buildFile` |
| 2 | **JSON 导出** | `exportJson` |
| 3 | **CSV 导出** | `exportCsv` / `escapeCsv` |
| 4 | **旧备份兼容警告** | `legacyCompatWarning` |
| 5 | **恢复（覆盖）** | `restore` → `overwrite` |
| 6 | **恢复（合并）** | `merge` → `mergeGroup` → `mergeAccumulate` → `mergeSessions` |
| 7 | **幂等指纹** | `dayFingerprints` / `dayEventKeys` |
| 8 | **来源分组** | `sourceGroupsOf` / `sourceDeviceOf` / `topSourceOf` / `allowedIn` |
| 9 | **各表插入** | `insertDaySummaries` / `insertAppUsage` / `insertHourUsage` / `insertPullLog` |
| 10 | **设置恢复** | `applySettings` |
| 11 | **日期范围** | `dateRange` |

**关键事实**：

- **CR-28 的合并语义全部集中在这里**：`mergeAccumulate` 与 `device_merge_ledger` 同事务（CR-27 的防重复记账）；
- `mergeSessions` 单独处理会话区间合并（**跨来源不合并**、同来源按 CR-28 规则）；
- `BackupRepository` 与 `SourceRebuild.kt`（640 行）**在「按来源独立处理」上逻辑重复** —— 两条路径都实现了来源维度的处理规则。

### 风险是否已实际复现

**否**。当前**没有故障**。但「与 `SourceRebuild` 逻辑重复」是**真实的一致性风险**（将来改一边忘了另一边），只是尚未发生。

### 优先级与理由

**P3**。

- 与 TD-05 同类，但**它的合并逻辑是 CR-28 的核心红线**（跨来源 SUM / 会话 UNION 不相加 / legacy 只读），**动它的风险比动 `AppViewModel` 高得多**；
- 它是 **R11 备份兼容（§7）** 的落点文件 —— 那一轮必然要改它，所以「完全不碰」也不现实；
- 因此正确做法是：**R11 那轮只加列、不重构**；重构单独排期，且**必须与 `SourceRebuild` 一起改、同一次提交验证**。

### 影响范围

备份导入 / 导出 / 云同步 / 跨设备合并四条链；`SourceRebuild`（历史重建工具）；`AppViewModel` 的备份相关 5 个方法。

### 建议验证方式

**第一步必须是「行为刻画」，不是「搬文件」**：

| 编号 | 动作 | 通过标准 |
|---|---|---|
| V1 | 为 `overwrite` / `merge`（含 `mergeAccumulate` / `mergeSessions`）补**端到端往返测试**：导出 → 恢复 → 再导出 | 两次导出**逐字段一致**（legacy 分量也一致） |
| V2 | 为 CR-28 的三条红线各补一条断言：跨来源 SUM、会话 UNION 不相加、legacy 只读 | 三条断言能**变红**（变异验证） |
| V3 | 与 `SourceRebuild` 的行为做**交叉验证**：同一批数据，走备份恢复与走重建，结果应一致 | 差异为零或差异被显式解释 |
| V4 | 真机走一遍：导出 → 清空 → 恢复 → 再导出；以及跨设备合并 | 数据完全回来，来源归属正确 |

**第二步（重构）建议的切分**：`BackupWriter`（构建 / 导出）/ `BackupRestorer`（覆盖 / 合并）/ `BackupMerger`（CR-28 合并语义）。**合并语义那一块必须最后动**，且必须与 `SourceRebuild` 一起改。

### 是否需立即修

**否**。

### 与其他任务的依赖关系

- 与 **R11 §7 备份兼容**强耦合 —— R11 若决定给备份加质量标记列，**必须在这一轮就把 `BackupRepository` 改掉**，不要之后再开一轮；
- 与 **TD-03**（备份序列化峰值）同文件；
- 与 **CR-28 的任何后续变更**强耦合（合并语义改动必须同时覆盖 `BackupRepository` 与 `SourceRebuild`）。

---

## TD-07 · WebDAV URL 路径编码

### 当前代码事实

| 事实 | 依据 |
|---|---|
| `WebDavProvider.buildUrl(cfg, path)`：`base = cfg.baseUrl.trimEnd('/') + "/" + cfg.remoteDir.trim('/') + "/"`；`return base + path.trimStart('/')` | `WebDavProvider.kt` |
| **`path` 完全没有 URL 编码** —— 没有 `HttpUrl` 的 path segment 编码，也没有 `URLEncoder` | 同上 |
| 调用方传入的 `path` 形如 `devices/<deviceId>/…`（`ensureDirsFor` 逐级 `MKCOL` 建目录） | `ensureDirsFor` |
| 反向解析时用 `java.net.URLDecoder.decode(it, "UTF-8")` 解 `href` | `parsePropfindEntries` |
| 预置地址是 `https://dav.jianguoyun.com/dav/`；`setWebdavConfig(url, username, remoteDir)` 由用户输入 URL | `WebDavProvider.JIANGUOYUN_URL` / `AppViewModel.setWebdavConfig` |

**三个具体风险点**：

1. **特殊字符破坏 URL 语义**：`deviceId` 若含空格 / 中文 / `#` / `?` / `%` / `&`，则 `#` 之后变成 fragment、`?` 之后变成 query、`%` 之后变成转义序列 → **请求打到错误的路径**；
2. **编解码不对称**：`URLDecoder` 是 **form-urlencoded** 语义（`+` → 空格），而 URL path 段里 `+` 是**字面加号** → 用它解 path 会把合法的 `+` 解成空格 → **往返不对称**；
3. **异常吞掉整份列表**：`URLDecoder.decode` 遇到非法 `%` 会抛 `IllegalArgumentException`，而它在 `parsePropfindEntries` 里被 `catch (e: Exception)` 包着 → **整份目录列表解析失败**（`SyncFailure.FORMAT`），表现为「设备列表永远是空的」。

### 风险是否已实际复现

**否**。真机批 C 用坚果云实测过列表与备份归位，**但当时使用的路径不含需要编码的字符**。

### 优先级与理由

**P2**。

- 功能上「多数情况下能用」，当前**不触发**；
- 但设备 ID **不是用户可控的干净字符串**（可能来自 OEM 序列号等含特殊字符的来源）→ 一旦命中就是「备份同步到错误路径 / 列表为空」的**数据可见性故障**，而且**用户极难自查**（会以为是服务端问题）；
- 修起来很便宜（改用 `HttpUrl.Builder.addPathSegment`），**不做**的成本会随着设备数增长而上升。

### 影响范围

`WebDavProvider.upload` / `download` / `list` / `listIn` / `delete` / `ensureDirsFor` / `buildUrl`；`parsePropfindEntries`；`SyncPaths`；设备目录发现（批 C 的核心功能）。

### 建议验证方式

**有现成依赖，不需要真机**：项目已引入 `com.squareup.okhttp3:mockwebserver:4.12.0`（testImplementation）。

| 编号 | 动作 | 通过标准 |
|---|---|---|
| V1 | 构造 `deviceId` 含 空格 / 中文 / `+` / `#` / `?` / `%` / `&` / `/` 的用例 | 请求 URL 的 path 段**逐段正确编码** |
| V2 | 断言 `parsePropfindEntries` 对含 `+` 与含 `%20` 的 `href` 的解析结果 | 解析正确，且与编码侧**对称** |
| V3 | 构造非法 `%` 的 `href` | **不吞掉整份列表**（要么该条跳过并记录，要么整份失败但有明确原因） |
| V4 | 真机坚果云复跑一遍设备列表与备份上传 | 与批 C 的结果一致 |

**正确做法**：

- **请求侧**：用 OkHttp 的 `HttpUrl.Builder.addPathSegment()`（逐段编码，天然处理 `/` 与特殊字符）；
- **解析侧**：用 `HttpUrl.parse(href)` 或 `java.net.URI` 的 `getPath()` 逐段解码，**不要**用 `URLDecoder`；
- **不要**用 `URLEncoder.encode` —— 它是 form 编码，会把空格编成 `+`。

### 是否需立即修

**否**（当前路径不含特殊字符时不触发）。但**在支持任何「用户可自定义目录名」之前必须修**。

### 与其他任务的依赖关系

- 与 **TD-08** 同批改（同一文件、同一批单测）；
- 与 R11 的备份兼容无直接关系，但若 R11 引入新的备份路径层级，两者需一起确认。

---

## TD-08 · WebDAV Basic Auth 的 HTTPS 边界

### 当前代码事实

| 事实 | 依据 |
|---|---|
| `WebDavProvider.request()` **无条件**加 `builder.header("Authorization", Credentials.basic(cfg.username, cfg.password))` | `WebDavProvider.kt` |
| **没有任何 scheme 检查**：`cfg.baseUrl` 可以是 `http://`，代码照发 Basic Auth（Base64 编码，**等同明文**） | 同上 |
| 预置地址是 `https://`；但 `setWebdavConfig(url, …)` 的 URL **由用户输入** | `AppViewModel.setWebdavConfig` |
| 凭据**本地**存储是加密的：`EncryptedSharedPreferences`（`androidx.security:security-crypto:1.1.0-alpha06`） | `app/build.gradle.kts` 依赖 + `SyncCredentials` |
| 但**传输**不强制 TLS —— 加密只保护本地存储，不保护网络 | 推导 |
| 其它 HTTP 权限声明正常：`INTERNET` + `ACCESS_NETWORK_STATE` | `AndroidManifest.xml` |

### 风险是否已实际复现

**否**。

### 优先级与理由

**P2**。

- 这是**安全**问题（凭据在局域网内可被嗅探），但**触发需要用户自己填一个 `http://` 地址** → 是**用户自己的选择**；
- **不能**在没有确认局域网场景之前就「禁止所有 HTTP」—— 很多自建 WebDAV（NAS、路由器）只在局域网内可达，用 HTTP 是常见且可接受的做法；
- 但**必须**让用户知道这件事正在发生。

### 影响范围

`WebDavProvider.request`（**唯一**加 Authorization 的地方）；`SettingsScreen` 的 WebDAV 配置页；`SyncCredentials`；`AppViewModel.setWebdavConfig`。

### 建议验证方式与处置建议

| 编号 | 建议 | 说明 |
|---|---|---|
| V1 | **必须做**：在 `http://` 时**显式提示风险**（设置页一行警告 + 首次填写时确认） | 诚实告知，不阻断 |
| V2 | **必须做**：同步日志（`pull_log` 或同步流水）记录「本次使用了明文传输」 | 出问题时可追溯 |
| V3 | **可选做**：允许用户显式勾选「我信任这个局域网地址」后才允许 http，勾选状态存 DataStore | 把决定权交给用户 |
| V4 | **建议做**：支持自签 / 自定义 CA 的 HTTPS（OkHttp `ConnectionSpec` + `TrustManager`） | 很多自建 WebDAV 用自签证书；当前会直接握手失败 |
| V5 | **不做**：直接禁用所有 http | 会打断真实存在的局域网 NAS 场景 |
| V6 | **不做**：把 Basic Auth 换成别的机制 | 服务端协议约束 |

**单测**：`baseUrl = "http://…"` 时应产生「明文传输」标记；`https://` 时无标记。

### 是否需立即修

**否**。

### 与其他任务的依赖关系

- 与 **TD-07** 同批改 `WebDavProvider`；
- 与 TD-01 / TD-10 无直接关系。
---

## TD-09 · GitHub Actions CI

### 当前代码事实

| 事实 | 依据 |
|---|---|
| **仓库内没有 `.github/` 目录** —— 全仓库 glob 无任何 workflow 文件。**当前零 CI** | glob 结果为空 |
| 本地构建链：Gradle wrapper **8.7** / AGP **8.5.2** / Kotlin **1.9.24** / `compileOptions` 与 `kotlinOptions` 的 `jvmTarget = "17"` | `gradle/wrapper` + `app/build.gradle.kts` |
| 本机 JDK 是 **JBR 21**（能编 17 目标），但**目标字节码是 17** | `JAVA_HOME=C:\Users\Administrator\.jdks\jbr-21.0.11` |
| 测试命令：`./gradlew :app:testDebugUnitTest --rerun-tasks`；当前基线 **620 测 / 0 fail / 0 skip** | 项目常用命令 |
| 构建命令：`./gradlew :app:assembleDebug` | 同上 |
| 已有可用依赖：`mockwebserver`（testImplementation）→ CI 可跑单测 | `app/build.gradle.kts` |

### 风险是否已实际复现

**是 —— 但要说清楚「复现的是什么」**：

- **不是**某个具体 bug 已经因为没有 CI 而漏过；
- **而是保障本身缺失**：目前**所有**验证（620 个单测、assembleDebug、真机复核）都**只靠人工在本机执行**。风险是「忘记跑」「忘了推送」「本机环境与 CI 不一致」「只有一台机器能编过」。

### 优先级与理由

**P2**。

- 单人项目、无外部贡献者 → 达不到 P0 / P1；
- 但 **批 9「§7 42 条验收逐条签核」**、**批 13「多机型适配」**开始后，**回归验证频率会显著上升**；
- 手动跑全量单测的成本会变成瓶颈（当前一次全量约 1–3 分钟，叠加多分支会迅速失控）；
- 因此建议在**下一个功能批次开始前**加上，而不是等出事再加。

### 影响范围

新增 `.github/workflows/*.yml`；不改任何生产代码。风险面：CI 机器上的 JDK / Gradle / SDK 版本与本机不一致，导致「CI 红了但本机是绿的」。

### 建议验证方式与落地内容

**最小可用集**（按 GPT 要求的五项展开）：

| # | 要求 | 落地 |
|---|---|---|
| 1 | **PR Debug 单测** | `on: pull_request` → `./gradlew :app:testDebugUnitTest` |
| 2 | **PR Debug 构建** | 同 job 或独立 job → `./gradlew :app:assembleDebug` |
| 3 | **Release 构建验证**（**配置成熟后**再开） | `./gradlew :app:assembleRelease` + `apksigner verify`；**依赖 TD-01 完成** |
| 4 | **失败阻止合并** | 在仓库设置里把 CI job 设为 required status check（GitHub 侧一次性操作） |
| 5 | **结果与 APK 归档** | JUnit XML 作为 artifact；Debug / Release APK 作为 artifact（**真机复现能拿到与 CI 同一份产物**） |

**环境固定**：

- JDK 用 `actions/setup-java` 固定 **17**（与 `jvmTarget = "17"` 对齐；本机用 JBR 21 也能编，但 CI 应锁 17 以减少差异）；
- Gradle **用 wrapper**（`./gradlew`），**不要**另装 Gradle —— 版本由 `gradle-wrapper.properties` 唯一决定；
- Android SDK：`ANDROID_HOME` + `sdkmanager` 安装 `platforms;android-34` 与对应 `build-tools`；
- **不需要**缓存 Key（Gradle cache 可用 `gradle/actions/setup-gradle` 自带缓存）。

### 是否需立即修

**否**（不阻塞开发）。建议在**下一个功能批次开始前**加上。

### 与其他任务的依赖关系

- 第 3 条（Release 构建验证）**依赖 TD-01**（签名 + R8 配置成熟）；
- 其余四条可**独立先上**；
- 与 TD-10（发行政策）共同决定「发布产物由谁产出」。

---

## TD-10 · QUERY_ALL_PACKAGES 发行政策

### 当前代码事实

| 事实 | 依据 |
|---|---|
| `AndroidManifest.xml` 声明 `<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" tools:ignore="QueryAllPackagesPermission" />` | `AndroidManifest.xml:37-38` |
| 同时还声明了 `<queries>`（`MAIN` + `LAUNCHER` intent）—— Android 11+ 包可见性的常规做法 | 同上 |
| Manifest 注释给出的理由：「屏幕使用时间里有大量**时长很长却没有桌面入口**的包 —— 系统服务、互联组件、桌面小部件（实测 `com.xiaomi.mi_connect_service` 占 8 次通知，界面只能显示成包名和一个系统默认图标）。只有 QUERY_ALL_PACKAGES 才能拿到它们的名称与图标」 | Manifest 注释 |
| **但同一段注释的前半句自相矛盾**：「不用 QUERY_ALL_PACKAGES，避免申请一个过宽的权限」 —— 这是历史遗留（前半句对应的是 `<queries>` 方案，后半句是后来的决策） | Manifest 注释 |
| 代码已具备**降级能力**：查不到 `ApplicationInfo` 时退回显示包名 | 既有实现（注释自述） |

### 风险是否已实际复现

**否**。功能上确实需要它（不声明则排行页退化成包名列表）；但**发行政策层面尚未评估、未申请、未上架**。

### 优先级与理由

**P1**。

- 这是**发行阻塞项**，不是运行期 bug —— 不会让 App 变慢或变错；
- 但 Google Play 的同意流程**需要改产品**（新增首次启动说明 + 同意状态存储 + 隐私政策同步），**周期长**，必须提前启动；
- 拖到最后 = 上架被拒 / 被要求修改 / 临时下架。

### 分渠道分析（**不预判 Play 一定通过或拒绝**）

| 渠道 | 政策现状 | 需要的材料 | 与本项目的匹配度 | 结论 |
|---|---|---|---|---|
| **Google Play** | `QUERY_ALL_PACKAGES` 受 Play「允许权限」政策约束：仅限**核心功能**用途，且必须**在应用内披露 + 提供用户同意流程** | Play Console **权限声明表**（用途说明）+ 应用内**同意界面** + 隐私政策中说明读取包名 / 图标 | **中高**：本应用核心功能就是「哪个 App 用了多久」，属于合理用途；但**当前没有同意流程** | **不是必然被拒**，但**必须补同意流程 + 权限声明**，否则大概率被拒或被要求修改 |
| **GitHub Releases** | 无政策审核 | 无 | 完全可用 | ✅ 无障碍，但分发的是 APK → **需要可安装的签名包**（依赖 TD-01；当前 release 未签名） |
| **F-Droid** | 有**元数据**审核；原则上反对「不必要的」权限，但通常**接受** `QUERY_ALL_PACKAGES`，要求在元数据里**解释用途**并偏好最少权限 | `fastlane/metadata/android/<lang>/full_description.txt` 等写明用途；建议附截图说明「无桌面入口应用只能显示包名」 | **中**：需要一份**诚实**的元数据说明；可能要说明「可降级到只用 LAUNCHER queries」的方案 | ⚠️ 可行，但**元数据必须写清楚**，否则可能被要求移除该权限 |
| **国内渠道**（小米 / 华为 / 应用宝等） | 各自的软著 / 备案 / 隐私合规要求 | 软著、备案、隐私政策 | 取决于发行主体与主体资质 | **待定**，需按实际发行渠道逐个确认 |

### 影响范围

- `AndroidManifest.xml`（权限声明本身）；
- **首次启动流程**（新增同意界面，与现有 `completeOnboarding()` 衔接）；
- 隐私政策 / 应用内隐私说明；
- 各渠道的**商店元数据**。

### 建议验证方式

| 编号 | 动作 | 通过标准 |
|---|---|---|
| V1 | **先确认现状**：复核是否已有隐私政策页与首次启动引导（`completeOnboarding` 的内容） | 明确「有 / 没有」，作为后续工作量基线 |
| V2 | **做可降级设计**：把「不使用 QUERY_ALL_PACKAGES」做成**用户可选开关**（默认开，并解释收益） | 代码已具备退化能力（显示包名），把选择权交给用户 |
| V3 | **写 Play Console 权限声明** | 逐字说明「用于显示各应用的使用时长、名称与图标」，与屏幕使用时间的核心功能对应 |
| V4 | **写 F-Droid / GitHub Releases 的元数据** | 与 V3 同源，说明用途 + 提供截图 |
| V5 | **不要**在补齐同意流程前上架 Play | — |

### 是否需立即修

**否**（不阻塞开发）。但**必须在计划上架 Google Play 之前完成**（预计需新增 UI 流程）。

### 与其他任务的依赖关系

- 与 **TD-01**（正式签名）强相关 —— GitHub Releases 分发需要可安装的签名包；
- 与 **TD-09**（CI）相关 —— 发布构建最好由 CI 产出同一份可归档产物；
- 与 **TD-05**（AppViewModel 拆分）相关 —— 同意流程的状态应落在新拆出的设置 / 偏好组件里，而不是继续塞进 `AppViewModel`；
- 与 **R11 的 UI 诚实化**不冲突（不同域）。

---

## 9. 处置顺序建议

```text
【发布准备链】
  TD-04（宽 catch 加固）  →  TD-01（签名 + R8 + keep 规则 + 混淆后冒烟）
                          →  TD-09 第 3 条（CI 的 Release 构建验证）
                          →  TD-10（同意流程 + 渠道元数据 + 上架）

【质量前置】
  TD-02 + TD-03（六阶段内存基准测试设计）  →  据数据决定是否流式化
  TD-07 + TD-08（WebDAV 同批改，同一批单测）

【可维护性】
  TD-05 第 0 步（接口化）  →  第 1 步（壁纸）  →  第 2 步（备份）  →  第 3 步（同步）
  TD-06（行为刻画测试先行  →  最后与 SourceRebuild 一起拆）
```

**约束**：本轮**不实施任何一项**。上表只是顺序建议，实施需另行批准。

---

## 10. 本登记的局限与诚实性声明

1. **本登记没有修复任何一项**。所有「建议验证方式」都**未执行**；
2. **只有 TD-09 是「已复现」**，且复现的是「**保障缺失**」本身，不是某个具体 bug；
3. **TD-02 / TD-03 的内存数字是估算量级**，不是实测峰值；
4. **TD-01 只实测了「release 任务存在」**，没有构建过 release、没有验证混淆后能否启动 / 采集 / 备份 / 恢复；
5. **TD-10 的渠道结论是政策匹配度分析**，不是法律意见，也不是任何渠道的审核结果；
6. **TD-04 的「链接期错误逃过 catch」是基于代码结构的推断**，未构造实际异常验证；
7. **TD-07 的三个风险点是代码审阅结论**，未构造含特殊字符的路径实测；
8. 真机相关的验证项（TD-01 V2、TD-02 低端机对照、TD-07 V4）**都需要 Redmi 放行**，本阶段**未占用设备**；
9. **vivo `10CEB40M5G001DK` 全程未连接、未触碰**。

---

## 附：证据索引

| 结论 | 证据 |
|---|---|
| release 构建类型**存在** | 实测 `./gradlew :app:tasks --all` 输出含 `assembleRelease` / `bundleRelease` / `assembleReleaseUnitTest` |
| 无 `signingConfigs` / 无 `isMinifyEnabled` / 无 proguard 规则 | 全仓库 grep `signingConfig` / `isMinifyEnabled` / `proguard` 均零命中；`app/build.gradle.kts` 源码 |
| 无 keystore 文件 | 递归搜索 `*.jks` / `*.keystore` 零结果 |
| Shizuku 读流实现与体积自述 | `ShizukuShell.kt:110-145`（`readAll` / 64KB chunk / `ByteArrayOutputStream(1 shl 20)` / `toString("UTF-8")`）与类注释 |
| Shizuku 内部包依赖集中在适配层 | grep `moe.shizuku.server` 只命中 `ShizukuShell.kt` |
| 备份序列化配置 | `BackupModels.kt` `BackupCodec`（`prettyPrint = true` / `encodeDefaults = true`）、`BackupRepository.exportJson` |
| `AppViewModel` 的 13 类职责 | `AppViewModel.kt`（1517 行）的成员清单 |
| `BackupRepository` 的 11 类职责 | `BackupRepository.kt`（783 行，34 个成员）的成员清单 |
| WebDAV 未做 URL 编码 | `WebDavProvider.buildUrl` / `ensureDirsFor` / `parsePropfindEntries` |
| WebDAV 未做 scheme 检查 | `WebDavProvider.request` 无条件 `Credentials.basic` |
| 无 CI | `.github/**` glob 结果为空 |
| `QUERY_ALL_PACKAGES` 声明与自相矛盾的注释 | `AndroidManifest.xml:37-38` 及其上下文注释 |
| Room / 备份版本 | `ScreenTraceDatabase.kt`（version = 8）、`BackupModels.kt:172`（SCHEMA_VERSION = 6） |


