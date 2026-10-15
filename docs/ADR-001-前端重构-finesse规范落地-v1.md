# ADR-001 · 前端界面重构（finesse 规范落地）

状态：已批准（用户 2026-10-16 四项裁决定案）
适用版本：v3.2.0（versionCode 31）基线 `e2a85fa`
决策依据：`finesse-ui` v0.20.0 + `finesse-brief` v0.5.0 + `docs/视觉规范-v2.6.5-强调色.md`
+ 视频解析《高级交互和控件 · SIX TOUCHES IN ONE APP》逐帧实测报告

---

## 一、背景

需要对现有前端做端到端重构，覆盖三层：整体视觉语言（配色、间距、层级、排版、组件形态）、
交互动效（参照视频解析的逐帧实测参数）、全屏监看界面全链路。

约束条件有四个来源，且**互相冲突**，因此必须先出 ADR 定边界（项目文档要求大改先出 ADR）。

### 1.1 两份 Skill 与本项目存在平台错配

`finesse-ui` v0.20.0 是 **Web/H5 技能**：22 个范例全是 HTML，动效六条 route 里
R2（`animation-timeline`）、R4（GSAP ScrollTrigger）、R5（Canvas/WebGL）、R6（CSS 3D）
在 Android 上无对应物；`scripts/detect.mjs` 是 HTML/CSS 正则扫描器，对本工程完全不可用。

`finesse-brief` v0.5.0 更明确——SKILL.md:14 与 502-504 写明产出物**是网页不是原生 App**，
其整套 daily-hook / 留存机制的前提是「网页没有推送可兜底」，而 N-Link 是有推送的原生 App，
前提直接消失；handoff.md:337 的 `surface: phone` 指手机浏览器页面。

**结论**：不存在「严格按其规范执行」的字面可能。本 ADR 的做法是**迁移规范层、重写实现层**：

| 可迁移（采纳） | 不可迁移（弃用） |
|---|---|
| craft floor：无纯黑白、中性色带色相、alpha hairline、带色相阴影、color lock | CSS 布局机制：`overflow-x`、`minmax(0,1fr)`、`position:sticky`、`min-h-dvh` |
| motion 预算：≤1 重拍 / ≤4 拍 / 拍不同族 / 每拍有静止版 | R2/R4/R5/R6 四条动效路由及其体积模型 |
| anti-cheap 黑名单中平台无关的条目 | 自绘状态栏与 home indicator（命中「重画环境 chrome」HARD BAN） |
| component-scope 的 8 个强制状态 | `env(safe-area-inset-*)` 的具体数值（方法论可搬，数值不可搬） |
| product-palettes 的 token 契约与对比度门禁 | `detect.mjs` 整个脚本 |
| h5 furniture 约定（TabBar 选中态是填充形状、抽屉带抓取把手、主操作下沉） | eyebrow 计数、layout family ≥4（营销长页概念） |
| preflight 的 Gate 0/1 与「绝不声称没跑过的通过」 | `.preview.html` 组件预览文件 |
| finesse-brief 的空态门禁：断连≠为空、禁 `--`、禁假数据、恰好一个 primary | `.workbench/spec.md` 整个产物（hook/cold_start/dials/seam 字段对本 App 空转） |

### 1.2 本地 Skill 版本严重过期

`~/.qoder-cn/skills/finesse-ui/` 装的是 **v0.9.0**（SKILL.md 27KB），仓库最新是 **v0.20.0**（79KB）。
本地版缺 `motion.md`、`h5-mobile.md`、`product-palettes.md`、`divergence.md`、
`component-scope.md`、`mobile-floor.md` 等 12 个 reference。`finesse-brief` 本地未安装。
**本次全程依据新克隆的 v0.20.0 / v0.5.0 执行**，不依据本地版。

### 1.3 工作区曾有一场同题的在途重构

开工时工作区有 39 个文件未提交（+2308/−1975）+ 15 项未跟踪文件，范围与本任务完全重叠，
含 `EntranceMotion.kt` / `ShutterPulse.kt` / `RevealMotion.kt`。经用户裁定为废弃，
已备份后回退至 `e2a85fa`。

备份位置：`C:\Users\21085\AppData\Local\Temp\nlink-revert-backup-20261016\`
（`tracked-changes.patch` 361KB + `untracked/` 15 个文件 + `base-commit.txt`）。
回退时**精确排除**了 4 组与重构无关的未跟踪内容：`.agents/`、`.claude/`、`agent/` 下的
baidu-drive skill（36 文件）、`.firecrawl/` 的 PTP/IP 调研资料（13 文件）、`skills-lock.json`。

值得记录的一点：那批废弃改动把 `page` 从 `#FFFFFF` 改成了 `#F4F5F7`——**方向是对的**
（消除纯白符合 craft floor），但 `#F4F5F7` 是**冷**色调，而品牌强调色 `#FFE100` 是暖色，
违反 `product-palettes.md:59`「tint 必须匹配 accent 色相」。本次改走 True Neutral（色相 60°）。

---

## 二、决策

### 2.1 用户四项裁决（2026-10-16）

| 议题 | 裁决 |
|---|---|
| 在途 39 文件 | 废弃，备份后回退重来 |
| 配色冲突 | **保留 `#FFE100` 单强调色，只取视频的动效与结构** |
| 突破边界 | **可动布局与信息架构，不换技术栈** |
| 验证方式 | 再试一次模拟器（上限 2 次） |

### 2.2 register 判定

按 `finesse-ui` SKILL.md §0.A 的解析器走：

- **surface 问题**：这个界面有桌面形态吗？没有 → 但不是 h5，是原生 App。
  OS 提供 furniture，自绘状态栏会命中 `anti-cheap.md:31` HARD BAN。
- **后台 vs 工作台**：它围着什么转？围着「他自己反复在做的一件事」——
  连相机、取景、拍摄、传图。**是工作台，不是后台**。
- **载体**：手机上开 → morph A（app shell）。
- **dials**：SOUL 6 / SPECTACLE 2 / DENSITY 5，按页面偏离见 `PRODUCT.md`。

SPECTACLE 定 2 是硬结论不是保守：SKILL.md §1.A 明写 product register 走 1–4，
`motion.md:138` 明写 state transition 是唯一属于 product register 的动效族。

### 2.3 中性色 ramp 选型（本次最关键的一个决策）

`product-palettes.md:59` 要求 tint 匹配 accent 色相 → 黄色是暖色 → 该配 Warm Stone。
**但 Warm Stone 100 = `#F0ECE5`（色相 38°、饱和度 4.3%），与 `anti-cheap.md:64-68`
明令禁用的米色家族 `#EFEAE0`（40°、5.9%）几乎同值。**照规则字面执行会直接踩进禁令。

解法：改用 **True Neutral**（`product-palettes.md:233`，原文「任何 accent；品牌很吵时的安全选择」）。
实测数值：

| ramp | 色相 | 饱和度 | 判定 |
|---|---|---|---|
| 禁用米色 `#EFEAE0` | 40° | 5.9% | 禁令命中 |
| Warm Stone 100 `#F0ECE5` | 38° | 4.3% | 与禁用值几乎同值，弃用 |
| **True Neutral 100 `#EFEFEE`** | **60°** | **0.4%** | **采用** |
| 现基线 `#FFFFFF` | — | 0.0% | 违反「无纯白」 |

True Neutral 的色相 60° 正好呼应 `#FFE100` 的 53°（同为黄向），饱和度却只有 0.4%——
既满足「每个中性色都向品牌色相偏一点」，又比米色线低一个数量级。
这是**用数值把两条互相冲突的规则同时满足**，不是折中妥协。

### 2.4 视频解析六动效的合规映射

| # | 视频动效 | 族 | 采纳 | 依据 |
|---|---|---|---|---|
| 01 | 完成反馈按钮（5 段） | state transition + particle | **部分**：保留按压收缩/塌圆/画勾，**去掉粒子迸发** | `motion.md:138` product register 只带 state transition |
| 02 | 勾选联动（三处同步） | state transition | **采纳思路，换实现**：保留「离散跳档不连续渐变」，但用单强调色不透明度分档 + 既有灰阶状态色 | color lock（SKILL.md:369）禁止引入新色相 |
| 03 | 叠卡展开（错峰扇出） | state transition | **完整采纳**：展开自上而下 80–100ms/张、收起自下而上 40ms/张 | 错峰方向不对称是实测最干净的一条 |
| 04 | 吸附卡加画线 | physics + geometric | **采纳原则**：两段动作之间用完成回调而非固定 delay | 视频解析列为 ★★★「最容易漏」 |
| 05 | 惯性滚轮 | physics / inertia | **完整采纳**：步进间隔 100→430ms 单调递增（4.3 倍衰减） | 项目已有 `TickRulerView`/`ArcDialView`，是最高价值匹配点 |
| 06 | 圆形扩散转场 | state transition | **完整采纳**：去程 ease-out / 回程 ease-in-out 且更快 | Android 有 `ViewAnimationUtils.createCircularReveal` 原生支持 |

预算核算（`motion.md:206-211`）：重拍 0 个（上限 1）、总拍 4 个（上限 4）、
四拍分属 state transition / physics-inertia / state transition / state transition——
**注意这里有一处需要说明的偏离**：预算要求「no two beats from one family」，
而 01/06/03 三拍都属 state transition 族。

处理：`motion.md` 的族预算是为**营销长页**防「一个炫酷 hero + 三个通用 fade-up」而设。
在 app shell 里，state transition 本就是 product register 唯一允许的族
（`motion.md:138`、`preflight.md:88`），强行凑族数只能引入被禁的族。
因此本项目按 `divergence.md:29` 的 furniture 豁免逻辑处理：**族预算不适用，
改为约束「不许有两拍做同一件事」**——快门反馈、页面转场、面板开合是三种不同的状态变化，
不构成重复。此偏离已在 `design-model.yaml` 的 motion 段登记。

### 2.5 明确否决项

- **视频的五色 pastel 配色**（青柠/淡紫/珊瑚/periwinkle/浅绿）——撞穿 v2.6.5 规范 + color lock
- **衬线 Didone 数字**——`anti-cheap.md:59` 反射性拒绝 + 打包字体增体积
- **粒子迸发**——product register 禁令
- **Jetpack Compose 迁移**——用户裁定不换技术栈
- **自绘状态栏 / home indicator**——OS 已提供，自绘命中 HARD BAN
- **生成任何图片素材**——本 App 图像全是相机实时取景与用户照片，非设计资产

### 2.6 采纳但不引入新色相的一条

视频解析 02 的「背景色离散跳档 5 档」思路保留（状态可辨比线性插值信息量大），
实现改为**单强调色的不透明度分档**（如 8% / 20% / 40% / 100%）+ 项目既有灰阶状态色。
视觉上是「同一支黄逐级变实」，不引入第二色相。

---

## 三、闸门与回退

### 3.1 分阶段独立 commit

按 `redesign-mode.md` Step 4 的杠杆排序（高杠杆→低风险），每阶段一个独立 commit，
任一阶段出问题可单独 `git revert` 而不牵动其他阶段：

1. 令牌层（配色 day/night + 圆角阶梯 + hairline）——杠杆最高、结构风险为零
2. 交互态（按压/焦点/禁用三通道，`component-scope.md` 的 8 状态）
3. 动效层（四拍 + 静止版）
4. 全屏监看专项
5. 空态/断连文案（finesse-brief 门禁）

### 3.2 一行回退点

- 强调色整体回退：把 `nikon_yellow` 与 `status_connected` 改回 `#000000`/`#FFFFFF`
  即还原纯黑白（v2.6.5 规范已记录此法）
- 监看强调色回退：`lv_accent` 改成 `@color/white`，无需动布局（`colors.xml:101` 已记录）
- 中性色 ramp 回退：True Neutral 全部改回原纯灰值，token 名不变故布局零改动

### 3.3 不碰的东西（`redesign-mode.md` Step 3 保护规则）

- 监看帧率 66ms ≈ 15fps（`BaselineMetrics.kt:26`）——改它必须同 commit 改指标口径文档
- 「同一条渲染链不能同时改两处」铁律
- 已真机验收过的布局骨架
- `nikon-conn-kit` 协议层（无 UI，不参与构建）

### 3.4 共链双页验证

`RemoteFragment`（拍摄页嵌入监看）与 `LiveViewFragment`（全屏监看）共享渲染链。
任何涉及监看的改动**必须两页都验**，只验一页不算完成。

---

## 四、后果

### 4.1 正面

- 消除 5 项 craft floor 违规（纯白/纯黑表面、硬灰 hex 边框、未带色相中性灰）
- 修复 2 项**既有**无障碍缺陷（`text_tertiary` 3.36:1、`nav_inactive_icon` 2.37:1），
  均实测数值，非估算
- 动效从「散点、覆盖不均」收拢到四拍 + 单一时长出口（`Motion.kt` 四档）
- 设计决策有了机器可读记录（`PRODUCT.md` + `design-model.yaml`），
  后续新增页面不需要重新推断

### 4.2 负面与代价

- **视觉验证通道缺失**：模拟器两次尝试均失败（详见 §4.3），本机无真机。
  所有改动只能保证**编译通过 + 静态检查通过**，视觉效果**未经真机验证**。
  按 `preflight.md:91-107` 的要求，交付时必须明说「我没打开看」，
  绝不把 `BUILD SUCCESSFUL` 当作效果达成。
- 中性色从纯灰改带色相，是**全局可见**的变化，日光下与室内观感可能不同，
  需真机复核。
- `dp` 裸值约 718 处的债务**不在本次范围**——逐值归位需逐页视觉 QA，
  无验证通道时做这件事是不负责任的。已在 `design-model.yaml` 的 drift 段诚实登记。

### 4.3 模拟器失败记录（供后续复用）

第 1 次：`FATAL | Running multiple emulators with the same AVD` ——
残留 `qemu-system-x86_64-headless.exe`（PID 50396，内存仅 252K 的僵死进程）占住
`nlink_glass.avd/hardware-qemu.ini.lock` 与 `multiinstance.lock`。
注意 `taskkill //IM qemu-system-x86_64.exe` **杀不掉它**，进程名带 `-headless` 后缀，
必须按 PID 精确杀。锁文件是**目录**，`find -exec rm -rf {} +` 在占用下会静默失败。

第 2 次：清锁后启动，adb 在 t=12s 报 `state=device`，但 `sys.boot_completed` 始终为空，
t=36s 进程消失。崩溃日志：`ERROR | Unable to connect to adb daemon on port: 5037`。
**根因是时序错误**：第 1 次清理时执行了 `adb kill-server`，第 2 次启动模拟器时
adb daemon 未起。修法是先 `adb start-server` 再启模拟器。

2 次授权已用完，未擅自加试。若需第 3 次，按上述修法执行。

---

## 五、待确认

- [ ] 真机视觉走查（用户执行，清单见交付说明）
- [ ] 是否把本地 `finesse-ui` 从 v0.9.0 升到 v0.20.0（涉及覆盖用户目录，需单独授权）
- [ ] `dp` 裸值 718 处的归位是否排入后续轮次
