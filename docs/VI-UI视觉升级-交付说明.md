# VI 批次 / CR-07 视觉升级 · 交付说明（Step 1–3、Step 8）

> 覆盖区间：本地 `7824186` → `d12d3c6`（2026-11-12 16:30 ~ 20:51），共 7 个提交。
> 里程碑：VI-1 四步完成 → 负责人引入《屏迹前端实现规格》→ 立 CR-07 → 按规格 §7.2 完成 Step 1–3。
> 当前测试 **282 例 / 0 失败**。本文件与 `docs/项目文档地图.md` 配套阅读。

---

## 1. 规格变更链（最容易读错的一段）

1. 16:12 产出 `docs/给MiMo-UI视觉升级提示词.md`，把 Claude 的三页设计稿转成 VI-1/2/3 任务书，圆环方案为「外环目标进度 + 内环超目标」。
2. 负责人勘误：圆环改 **24h 全天环**（弧长 = 当日使用 / 24h）→ `33690a9` 落地，记为 CR-06。
3. 19:36 负责人贴入 Claude 写的《屏迹 ScreenTrace 前端实现规格（给 AI 开发者）》，AI 比对后列出两版规格冲突；19:38 负责人裁定 **「完全按新规格」**。
4. 按项目纪律「先改文档再改代码」，先立 CR-07 并修订需求条款，再回滚圆环 → `df9d71b`。
5. 规格全文原只在对话里，21:25 从 Android Studio 的 Claude Code GUI 会话记录恢复，落盘 `docs/屏迹前端实现规格-给AI开发者.md`。

**现行结论**：圆环 = 目标环双层（`progress = 使用/目标`，允许超 1、上限 2），超目标部分用 `ringOver` 从 12 点起叠画第二圈。CR-06 的 24h 全天环已作废，仅作为变更历史保留。

---

## 2. 逐提交清单

| 提交 | 内容 |
|---|---|
| `7824186` VI-1-1 | Manrope 四个字重（`res/font/manrope_{regular,medium,semibold,extrabold}.ttf`，OFL）；`FreshType` 拆数字/中文 family，数字加 `tnum` 等宽；中文继续走系统字体（不打包 8–10MB 中文字体） |
| `3c3592d` VI-1-2 | 页面背景渐变 + 浅色卡片轻阴影；抽出 `uikit/FreshCard.kt` 统一 24dp 圆角容器，替换四页里 20 多处重复 `clip+background+padding` |
| `33690a9` VI-1-3 | 圆环改 24h 全天环（**后被 CR-07 推翻**）；新增 `uikit/RingMath.kt` 纯函数几何 + `RingMathTest` |
| `e1f6bff` VI-1-4 | 顶部三圆按钮收敛为「日期 + 设置」两枚；设置页风格选择升级为色块网格、点选即生效；修复圆环空白的根因 |
| `a798351` CR-07 Step1 | 令牌校准：白卡 24→28dp、`pageTitle` 32sp、`heroNumber` 46sp/800、新增 `statsNumber` 40sp、`metricNumber` 30sp（统计卡 28sp）、三段背景渐变、双层阴影近似；新增 `FreshTypeFontTest` |
| `df9d71b` CR-07 Step2 | 圆环回滚目标环双层：单一动画值 0→progress（900ms FastOutSlowIn）同时驱动两圈与数字；超目标第二圈用 `ringOver`；减少动画直接落终值 |
| `d12d3c6` CR-07 Step3 | 状态标签三色（超目标珊瑚 `overTarget` / 对比中性 `chipSurface` / 下降绿 `downSurface`）+ 可下钻卡图标结构；新增 `uikit/StatusChip.kt`、`StatusChipTest` |

`FreshPalette` 新增语义 token（均有默认值，九套预设与 `paletteFromSeed` 同步派生）：`gradientTop` / `gradientMid` / `shadow` / `ringGradientEnd` / `ringOver` / `chipSurface` / `chipOn` / `downSurface` / `downOn` / `overTarget`。UI 层无裸 `Color(0xFF…)`。

---

## 3. 验证

- `./gradlew :app:testDebugUnitTest` → **282 例 / 0 失败**（21:2x 在当前 HEAD 复跑确认）。
- 新增测试：`FreshTypeFontTest`、`RingMathTest`、`StatusChipTest`；`FreshPaletteContrastTest` 同步扩展。
- 真机结论（来自会话记录）：Step 2 验证 4.5× 目标时双圈满叠且不溢出；Step 3 验证 chips 与挂锁/铃铛图标块显示正常；VI-1-4 修复了圆环空白问题。
- **已知笔误**：`d12d3c6` 的提交信息把「282 测全绿」写成 283。按项目纪律不 amend，以本文件为准。

---

## 4. 未完成（按规格 §7.2）

| Step | 内容 | 备注 |
|---|---|---|
| 4 | 应用构成卡：色带 + 行式图例（图标底色对应段色、时长、占比） | 现有仍是纯文字图例 |
| 5 | 统计页：柱图、图表上方固定详情条、时段分布 2×2 | 现有浮层仍是「落选中柱对侧」 |
| 6 | 日历页：热力四档、今日描边、图例 | **口径已统一**：按使用/目标比值 100%/140%/175%，见需求 §5.7；代码仍是三档 `coerceIn(0,3)` |
| 7 | 导航胶囊 + 底部 contentPadding | 未动 |
| 9 | 深色模式降饱和 + 九套主题逐套巡检 | 未动 |

**Step 8 已完成**（VI-1-4 提前做了顶栏两按钮 + 设置页主题入口），不要再作为待办重复实现。

其它不在本批次：8b-2 Google Drive、批 11/12/13/14b、14a PNG 长图、批 9 收尾。

---

## 5. 接手注意

- 执行依据是规格 §4 / §7.2 / §7.3，**不是** `给MiMo-UI视觉升级提示词.md`（那份的圆环方案与分批编号已失效）。
- 规格 §4.8 明确「点某一天沿用原项目跳转详情，改底部面板不在本次范围」；任务书里那条底部面板建议不执行。
- 不改 `contract/` 与 `data/` 业务逻辑、不硬编码颜色、不加运行时依赖、不改 README；每步一个提交并真机复核。
- 本批 7 个提交与 8b-1 等共 **23 个提交仍未推送**，`origin/main` 停在 `7650966`。推送由负责人执行。
