# PRD：N-Link v2.3 液态玻璃（Liquid Glass）视觉改版

> 版本：v2.3.0（拟，versionCode 20，顺延） · 分支：`feat-liquid-glass-ui-v2.3` · 基线：v2.2.0（b0fec6d）
> 状态：**待确认**（本文档只做视觉规范与技术选型，未改动任何代码）
> 原则：**信息架构与业务逻辑零改动**；玻璃是"材质层"不是"重画层"；每一项可独立回滚；看不出现在改版的价值就不做

---

## 摘要（八个结论，细节见各节）

1. **我们不是在画一张 iOS 截图，是在做一套 Android 上的玻璃材质。** iOS 液态玻璃的观感 = **背景采样 + 高斯模糊 + 边缘折射（lensing）+ 动态高光**四件事。Android Views 体系只提供前两件的一部分，**没有任何公开的"背景（backdrop）采样"API**。因此本 PRD 的核心不是"抄参数"，而是**定义一套三层能力分级 + 一个自建的 backdrop 采集器**，让玻璃在 API 29 到 API 36 上都有确定的、可验收的表现（§3、§9）。
2. **硬底座事实：本项目 minSdk 29（Android 10）、纯 XML Views、零 Compose。** `RenderEffect` 要 API 31、`RuntimeShader`（真折射唯一的公开路径）要 API 33。也就是说：**最新最好看的那一层，今天在部分用户机器上物理上不可能实现**。方案必须按 T1/T2/T3 分层，且 **T1（Android 10/11）要有独立的视觉设计**，不是"降级残影"（§9.1）。
3. **改主题几乎无效，必须改 drawable 层。** 15 个布局里光 `TextView` 就 **152 个**、`LinearLayout` **121 个**、`ImageView` **66 个**，而全部 Material 组件加起来只有 **13 个 `MaterialButton` + 5 个 `MaterialSwitch` + 4 个卡片视图**；表面材质全部来自 **17 个 `res/drawable/bg_*.xml` 纯色 `<shape>`**。所以本次改版的真实工作量 = **重做 bg_* 家族 + 新增玻璃材质层**，`themes.xml` 只是顺带（§2.2）。
4. **模糊要花在刀刃上：全页磨砂是这次改版唯一会失败的方式。** 玻璃必须有"可透之物"。按此把 7 个界面分成三档：**监看/预览/相册（背后是图，收益最高）→ dock/顶栏/弹窗；设备页（背后是白底，中）→ 仅 hero 卡与弹窗；设置/打赏（背后是扁平白，不做玻璃）→ 只对齐 token**。这一条是刻意的克制，写进 §1.2 非目标与 §5。
5. **性价比最高的一件事不是模糊，是弹窗。** 全仓 **27 处 `MaterialAlertDialogBuilder` + 3 处 `BottomSheetDialog`**，Android 12+ 上 `FLAG_BLUR_BEHIND` + `Window.setBackgroundBlurRadius()` 是**系统合成器做的真模糊**，零纹理管理、零自建采集器、每处约 3 行。这是 M1，也是整个项目里唯一"一天做完、全 App 立刻高级"的改动（§10.5）。
6. **灰阶规范不需要废除，且这是本 PRD 风险最低的地方。** `colors.xml:39` 的「不引入彩色」是**状态色规范**，不是材质规范；AI 修图 PRD 已确立豁免先例「效果内容彩色、UI 控件严格灰阶」。玻璃染色（tint）保持中性白/黑，进入屏幕的颜色**只来自被透射的内容本身**（照片、监看画面）——完全合规。唯一争议项是 iOS 玻璃边缘的**色散（chromatic dispersion）**，已单列为待确认 **Q1**（§3.7）。
7. **可访问性不靠自觉，靠算法 + 一个开关。** 玻璃最大的危害是**文字对比度被背后的内容吃掉**。对策不是"手动调 alpha"，而是：我们本来就为模糊采集了低分辨率背景纹理，**顺手算出玻璃矩形下的平均亮度，对比度不达 WCAG AA 就自动加浓染色，三步仍不达标就关掉这层面模糊、退回实体卡**（= 回到现设计）。再补一个设置页「降低透明度」开关，开启即整体退化为 v2.2 观感（§9.3、§9.4）。
8. **不做 Compose 迁移。** 69 个 Kotlin 文件、4 个各约 1000 行的 Fragment，且这 4 个页面直接持有连接/传输/PTP 主干；为视觉去做全量重写，回归风险与工作量都不可接受（估 6–10 周纯重写）。方案 C 已明确否决，本期也不开"新页面用 Compose"的口子（§10.1、Q7）。

---

## 〇、前置事实：三堵墙，先量清楚再谈设计

| # | 事实 | 证据 | 对设计的直接后果 |
|---|---|---|---|
| W1 | **minSdk 29**（Android 10） | `app/build.gradle.kts:22` `minSdk = 29 // BLE 5.0 full support` | 无 `RenderEffect`（API 31+）、无 `RuntimeShader`（API 33+）、无窗口级模糊（API 31+）。真折射与真背景模糊**在部分用户设备上不可得** |
| W2 | **纯 XML Views，零 Compose** | `app/build.gradle.kts:71-72` 只有 `viewBinding = true`，无 `compose` 构建特性；全仓 `@Composable` 命中 0；无 `androidx.window` | 没有 `Modifier.blur` / `graphicsLayer` / `hazeState`。一切效果必须走 `Drawable` + `RenderEffect`/`Canvas` + `ViewOutlineProvider` |
| W3 | **灰阶极简设计系统，且刻意压平** | `styles.xml:78` `cardElevation 0dp`；`NlButton.*` 全部 `stateListAnimator @null`；**89 个 `res/**/*.xml` 中 `<gradient>`/`linearGradient`/`radialGradient`/`RenderEffect`/`BlurMaskFilter` 合计命中 0**（已 grep 验证） | 现在**零模糊、零渐变、零阴影、零高光**。玻璃的四个观感要素一个都还没有 —— 这是好消息（没有半吊子实现要清理），也是坏消息（没有一处能"改参数生效"） |

> **一句话**：iOS 那套效果的 4 个成分里，Android Views 今天能白拿的只有「窗口级模糊（弹窗）」和「染色+描边+阴影」。其余必须由我们自建。承认这一点，方案才成立。

---

## 一、设计目标与适用范围

### 1.1 目标

| # | 目标 | 判据（怎么算达成） |
|---|---|---|
| G1 | 建立**一套可传承的玻璃材质语言**（token + 组件），而不是一批效果图 | 新增 `shared/ui/glass/` + `res/values/glass*.xml`；任何新界面用 3 个 class 内即可搭出合规表面 |
| G2 | **层级即信息**：用材质深度表达"悬浮操作 / 内容 / 背景"三级关系 | dock、顶栏、弹窗与内容卡视觉上可区分；盲测能说出"哪个能按" |
| G3 | 在**内容密集页**（相册/监看/预览）让控件不再遮挡内容、而与之共存 | 监看页 HUD 与预览页工具栏在玻璃化后可读性不降、画面可视面积增加 |
| G4 | **性能与电量零劣化**（本项目的用户场景是外接相机 + 长时间监看 + 大文件传输，功耗敏感） | 基准机滑动 P95 帧时长不劣于 v2.2；监看 10 分钟电量增量 ≤ 3% |
| G5 | **可访问性净提升**，不是净牺牲 | 全部玻璃面文字对比度 ≥ WCAG AA；提供"降低透明度/经典外观"开关；TalkBack 语义不回退 |
| G6 | 全程**灰阶中立**，不破 `colors.xml:39` 规范 | 除内容透射与既有 `#FFC400` 快门环外，新增 UI 彩色数 = 0 |

### 1.2 非目标（明确不做，防止改版失控）

- ❌ **不改信息架构**：Tab 数量/名称/顺序、页面归属、入口层级、文案逻辑一律不动。
- ❌ **不改业务与连接逻辑**：`ConnectionManager` / `PtpSessionManager` / `TransferManager` 状态机零改动（对齐 v2.2 §十三「明确不动」清单）。
- ❌ **不做 Compose 迁移**（§10.1 给出否决理由）。
- ❌ **不引入彩色主题 / 不换字体 / 不重绘图标**：`sans-serif` 字重层级与线性图标体系保留。
- ❌ **不引入第三方模糊库**：RenderScript 已废弃（API 31 deprecated，且有 GMS/OEM 碎片风险）、Haze 是 Compose-only。全部自研，上限 5 个文件（§10.2）。
- ❌ **设置页 / 打赏页不做玻璃**：背后是扁平白底，磨砂既不可见又白耗性能，只对齐 token 与间距（§5.5）。
- ❌ **RecyclerView item 内不做实时模糊**：硬约束，写进验收（§8、§11 AC-6）。

### 1.3 成功指标（KPI）

| 指标 | 目标值 | 测量方式 |
|---|---|---|
| 主观品质盲测（"高级感/清晰度"两问，5 分制） | ≥ 4.0，且**不低于** v2.2 基线 | 10 人盲测，同图并排对比截图 |
| 玻璃子系统每帧渲染开销 | ≤ 3.0 ms（基准机） | `dumpsys gfxinfo framestats` + `FrameMetrics` |
| 相册页长列表滑动 P95 帧时长 | 不劣于 v2.2（同一台机、同一张卡） | A/B 对比脚本，各 3×10s fling |
| 文字对比度合规率 | 100%（AA 4.5:1；大字 3:1） | 自动化截图取样脚本 |
| 玻璃相关崩溃 / ANR | 0 | 稳定性验收（AC-10） |
| 「经典外观 / 降低透明度」开关使用率 | < 5% | 埋点（若显著高于 5%，判定为设计失败而非用户偏好） |
| 视觉改版引入的功能回归 | 0 | AC-12 回归清单 |
| APK 体积增量 | ≤ +0.5 MB | 仅新增 `androidx.dynamicanimation` |
| 进程内存增量 | ≤ +8 MB（含全部玻璃纹理） | `dumpsys meminfo` 前后对比 |

---

## 二、现状盘点（代码级）：可复用的资产与必须先修的债

### 2.1 视觉资产清单

**Token（全部在 XML，无 Compose 主题对象）**

| 文件 | 行数 | 内容 |
|---|---|---|
| `res/values/colors.xml` | 61 | 日间 **39** 个 `<color>`，注释自陈「黑白双色设计系统」 |
| `res/values-night/colors.xml` | 57 | 夜间 **37** 个 → **日夜 token 不成对**（缺 `switch_track_off`、`switch_thumb_off`） |
| `res/values/themes.xml` | 71 | `Theme.NLink` / `Theme.NLink.Preview` / `NlDialog`(r12) / `Widget.NlSwitch` / `NlWindowAnim` |
| `res/values/styles.xml` | 89 | `NlText.*` 字重层级（全 `sans-serif`，仅字重区分）、`NlButton.*` ×4、`NlCard`、`NlPicker` |
| `res/values/dimens.xml` | 11 | **仅 7 个**：`page_margin 24` / `module_gap 16` / `group_gap 12` / `card_radius 12` … |

**材质实体 = 17 个 `bg_*.xml` `<shape>`**（这是真正的"组件库"）：`bg_card`(r12，**14 处引用**，绝对主力)、`bg_chip`/`bg_chip_selected`(r20 胶囊)、`bg_floating_bar`(r28)、`bg_card_dark`、`bg_info_grid_item`(r8)、`bg_liveview`(r16)、`bg_lv_pill`(r14)、`bg_lv_icon`、`bg_fullscreen_button`、`bg_tutorial_bubble`(r10)、`bg_icon_circle`、`bg_circle_primary`、`bg_status_dot`、`bg_shutter_ring`、`bg_focus_box`(vector)、`bg_grid_thirds`(vector)。

**已存在的"proto-glass"——重要证据，说明方向不突兀**

监看 HUD 已经是**半透明黑底 + 半透明白描边**的雏形，只是没有模糊：

```
bg_lv_pill.xml      #B3000000 填充 + 1dp #33FFFFFF 描边，r14
bg_lv_icon.xml      #B3000000  + 1dp #33FFFFFF，圆形
bg_fullscreen_button.xml  #99000000 + 1dp #66FFFFFF
liveview_scrim      #99000000（60% 黑）
liveview_text       #E6FFFFFF（90% 白）
fragment_liveview.xml:222,248,274,300,326  5 × #99FFFFFF 硬编码
```

> 结论：**这个项目其实已经在往玻璃走**，只是停在了"半透明"这一步、并且散落成硬编码 hex。本次改版的性质是"把已有的直觉系统化"，不是推翻设计语言。这一点要在评审时讲清楚。

### 2.2 根因：为什么"换个主题"这条路不存在

| # | 根因 | 证据 |
|---|---|---|
| 1 | **表面材质不走主题属性，走 `android:background="@drawable/bg_*"`** | `@drawable/bg_card` 被 **14 处**布局直接引用（`fragment_dashboard.xml` 9 / `fragment_settings.xml` 4 / `dialog_param_picker.xml` 1）；`@color/text_primary` 直接引用 **70 处**，而 `@color/surface` 仅 **3 处** → 表面色是通过 bg_* 的 `fill` **间接**绑定的，改 `colorSurface` 主题属性传导不到界面上 |
| 2 | 表面由**裸 View + `<shape>` 背景**构成，不是组件 | 15 个 layout 合计：`TextView` **152** / `LinearLayout` **121** / `ImageView` **66**，而 Material 组件仅 `MaterialButton` **13** / `MaterialSwitch` **5** / 卡片视图 **4** |
| 3 | 阴影被**主动清零** | `styles.xml:78` `cardElevation 0dp`；`NlButton.*` 全部 `stateListAnimator @null`（:47, :59）——想靠 elevation 造层次，得先把这层"刻意的平"改掉 |
| 4 | 渐变/模糊/高光**全仓 0 命中** | 89 个 `res/**/*.xml` 中 `<gradient>`/`linearGradient`/`radialGradient` 命中 0；`RenderEffect`/`BlurMaskFilter` 命中 0（`res/` + `java/` 一并 grep） |
| 5 | "组件"是**复制出来的**，不是抽出来的 | 同一套「滑动指示胶囊 Tab」在三处各写一遍：`fragment_dashboard.xml:52 modeTabContainer`、`fragment_transfer.xml:218 tabIndicator`、`fragment_remote.xml:21-42 btnModePhoto/btnModeVideo`（后者直接复用 `bg_chip*`）；`item_recent_device.xml` 与 `item_wifi_candidate.xml` 近乎逐行重复 |

**因此：任何"只做玻璃、不碰结构"的方案都会失败** —— 同一个视觉改动要在 3 处分别实现，必然不一致。合并结构是涂材质的前置条件，见 §10.5 的 M2 顺序。

### 2.3 必须先修的技术债（玻璃的前置，不是附赠）

| ID | 债 | 证据 | 为什么玻璃必须先修它 |
|---|---|---|---|
| D1 | **`PressEffect` 抢占 `OnTouchListener`** | `shared/ui/PressEffect.kt:19` `view.setOnTouchListener { … }` 且回调内返回 `false` | 玻璃按压要同时驱动"缩放 + 染色变浓 + 模糊变浅"三个属性，需要独占输入语义；而相册页有 `DragSelectController`（`camera/gallery/DragSelectController.kt:25`）长按拖选，两套 `OnTouchListener` 互踩。**改玻璃前必须改成 `onTouchEvent` 委托式** |
| D2 | **未真正 edge-to-edge** | `themes.xml:26` `windowOptOutEdgeToEdgeEnforcement true` | 玻璃 dock 必须能压到系统栏上才有质感。**官方口径：targetSdk 36 时该开关"已废弃且失效"；targetSdk 35 + Android 15 上目前仍有效** → 下一次 targetSdk 升级就会炸。这是**明确的外部 deadline**，正好借本次改版做对（§9.6、风险 R5） |
| D3 | **日夜 token 不成对** | day **39** / night **37**（缺 `switch_track_off`、`switch_thumb_off`） | 玻璃 token 数量是现在的好几倍，缺一个夜间值 = 夜间白底玻璃直接翻车。必须先建"成对强制"机制 |
| D4 | **硬编码 hex 逃逸** | `item_photo_grid.xml:46 #33000000`、`:96 #AA000000`、`:117 #CC000000`；`activity_support.xml:66 #666666`；监看 5×`#99FFFFFF`(`fragment_liveview.xml:222/248/274/300/326`) | 这些正是"本该是玻璃面"的地方。不收敛就无法统一调参 |
| D5 | **dimens 形同虚设** | 仅 7 个 token，但布局硬编码：`paddingHorizontal 18dp` ×21、`layout_marginStart 8dp` ×15、`18dp` ×14、`10dp` ×11；设置行高 `52dp` 在 `fragment_settings.xml` 一个文件里重复 **18 次** | 玻璃有"内衬随圆角变化"的同心规则（§3.5），间距不收敛 → 圆角一改就穿帮 |
| D6 | **无任何共享反馈组件** | **40 处 `Toast.makeText`**（RemoteFragment 14 / LiveViewFragment 10 / PreviewActivity 5 / SupportActivity 4 / TransferFragment 3 / SettingsFragment 2 / MainActivity 1 / DashboardFragment 1）；`Snackbar` 全仓 0 | 悬浮反馈是玻璃语言最显眼的一类；先用 `NlFeedback` 单入口封住，材质才能一处改全局生效 |
| D7 | **导航无 NavHost** | `res/navigation/` 不存在，`navigation-*` 依赖已声明但零使用；`MainActivity.kt:158-164` 四 Fragment `add()` 后 `hide()/show()` 常驻 | 页面转场只能走 `windowAnimationStyle` + Fragment `hide/show`。想做 iOS 式深度转场，得先在 `hide/show` 上做手工动画（§6.4），别指望 Navigation 组件 |

---

## 三、液态玻璃视觉规范（Design Tokens）

> **单位约定**：本文所有模糊半径、描边、阴影均以 **dp** 规定。实现层必须在**降采样的背景纹理**上按 `px = dp / 8 × density` 施算（§8.2），因此**视觉上密度无关、开销上恒定**。这条不写清，xxhdpi 上 24dp 会糊成一片、mdpi 上等于没糊——玻璃移植最常见的翻车点。

### 3.1 材质分层模型（先定层级，再定参数）

只有四层。**任何一面玻璃都必须能归到某一层，归不进去就不该做玻璃。**

| 层 | 名称 | 定义 | 允许动效 | 允许实时模糊 |
|---|---|---|---|---|
| **L0** | 内容层 Content | 照片网格、监看画面、预览图、表单正文 | — | ❌（永不） |
| **L1** | 贴合玻璃 Tinted Fill | 与 L0 同滚动的信息面（hero 卡、深色设备卡） | 形变 | ⚠️ 仅静态采样（滚动中不刷新） |
| **L2** | 控件玻璃 Control Glass | chip、按钮、分段控件、行内小面 | 按压/抬起 | ❌（只吃 L3 的模糊结果做染色近似） |
| **L3** | 悬浮玻璃 Floating Glass | 底部 dock、顶栏、弹窗/BottomSheet、选择操作坞、监看 HUD | 全部 | ✅（受配额限制） |

**玻璃配额（硬规则，违反即评审不通过）**

1. 同屏**实时模糊面 ≤ 3**。
2. 同屏**正在做动画的玻璃面 ≤ 2**。
3. 单屏玻璃覆盖面积 ≤ **45% 视口**（监看页 HUD 因背景是视频，例外上限 60%）。
4. **不做玻璃套玻璃**：L3 上只允许 L2（实体或半透），不允许再嵌 L1/L3。
5. 玻璃面上**不得放置 13sp 以下的次要文字**（那是本项目现状里的常见字号）。
6. 一面玻璃与最近可读内容的间距 ≥ **8dp**，否则边缘高光会和文字打架。

### 3.2 模糊（Blur）

| Token | 值（dp） | 用途 | T1(API29-30) | T2(31-32) | T3(33+) |
|---|---|---|---|---|---|
| `glass_blur_none` | 0 | L2 / 降低透明度态 / 配额耗尽 | 同 | 同 | 同 |
| `glass_blur_s` | 12 | L1 贴合玻璃 | 跳过（仅染色） | 可分离 box | `RenderEffect` |
| `glass_blur_m` | 24 | **L3 默认**：dock、顶栏 | CPU 两遍（可选） | `RenderEffect` | `RenderEffect` |
| `glass_blur_l` | 40 | 弹窗、BottomSheet | **窗口级模糊不可用 → 降级 `glass_blur_none` + 提浓染色** | `Window.setBlurBehindRadius` | 同 T2 |
| `glass_blur_xl` | 60 | 监看 HUD、全屏沉浸遮罩 | 跳过 | `RenderEffect` | `RenderEffect` |
| `glass_blur_max` | 100（px 上限） | 保护常量 | — | `radius clamp` | `radius clamp` |

规范细则：

- **模糊不是"越大越好"**：本项目内容是照片，背后是高对比细节。半径 >40dp 后视觉收益趋零而开销线性上涨。L3 默认锁 24dp。
- **模糊与染色互补**：T1 拿不到模糊时，**同一面的 tint alpha 提升 15%、外描边加深一档**（§3.3/§3.4），保证"层级感"仍在。降级态必须是**完整的设计**，不是残缺。
- **纹理更新时机**：只在 `SETTLING→IDLE` 后 1 帧、或几何变化去抖 120ms 后采集；**fling 期间零采集**（§8.2）。滚动中看到的是"上一次采样的模糊"——iOS 自身也是这个策略，人眼在快速滚动时无法分辨。
- **`WindowManager.isBlurEnabled()` 返回 false 时（部分低端/OEM 会关）走 T1 路径**，不视为异常。

### 3.3 透明度与染色（Tint / Opacity）

染色永远是**中性色**（白/黑），色彩只允许从背后透上来（G6）。

| Token | 日间 | 夜间 | 说明 |
|---|---|---|---|
| `glass_tint_flat` | `#F2FFFFFF` (95%) | `#F2000000` (95%) | 背景为**扁平底色**时（等价于实体卡，只是边缘有 rim/shadow） |
| `glass_tint_content` | `#D9FFFFFF` (85%) | `#BF000000` (75%) | 背景为**图片/监看画面**且静止 |
| `glass_tint_moving` | `#EAFFFFFF` (92%) | `#E6000000` (90%) | 内容**正在滚动/播放**时自动提浓 |
| `glass_tint_scrim` | `#99000000` | `#99000000` | 弹窗/BottomSheet 之后的全局遮罩（沿用现值 `liveview_scrim`） |
| `glass_tint_dark` | `#BF000000` | `#BFFFFFFF` | 反色卡（现 `dark_card`）玻璃化 |
| `glass_tint_solid_sel` | `@color/primary` | `@color/primary` | **选中态必须实体**，不用玻璃（状态可辨性优先于美观） |

规范细则：

- **静止 → `content`，运动 → `moving`**，切换用 150ms `FastOutSlowIn`，不做突变（突变看起来像闪屏）。
- 玻璃化**不得改变任何文字颜色 token**（`text_primary/secondary/tertiary` 原样）。可读性只靠 tint 与 §9.3 的自适应解决。这条让"回退到经典外观"变得极其简单： tint 关掉即回到现设计。
- 夜间**阴影几乎不可见**，故夜间层级职责**转交给 rim 高光**（§3.4）：夜间 rim 强度 ×1.6，阴影 ×0.6。

### 3.4 高光与阴影（Rim & Specular & Shadow）—— 玻璃之所以是玻璃

一面玻璃由**四条边信息**定义，缺一条就退化成"半透明灰块"：

```
┌─ ①外描边 stroke ───────────────────────┐
│ ②内顶高光 rim_top（1dp，上→下渐隐）      │
│                                          │
│           tint + blur                    │
│                                          │
│ ③内底反光 rim_bottom（0.5dp，均匀）      │
└──────────────────────────────────────────┘
      ↓ ④双层阴影（不做 elevation）
```

| Token | 日间 | 夜间 | 规格 |
|---|---|---|---|
| `glass_stroke_outer` | `#33000000` (20%) | `#24FFFFFF` (14%) | 0.5dp，勾边界 |
| `glass_rim_top_start` | `#66FFFFFF` | `#40FFFFFF` | 1dp 线性渐变起点（顶端） |
| `glass_rim_top_end` | `#00FFFFFF` | `#00FFFFFF` | 渐变终点（透明） |
| `glass_rim_bottom` | `#1A000000` | `#33FFFFFF` | 0.5dp，内透光 |
| `glass_edge_lens` | `#4DFFFFFF` | `#33FFFFFF` | **四角加强**的 1.5dp 亮线（折射假象的主要来源，§3.6） |
| `glass_shadow_key` | `#14000000`，y 2dp，blur 8dp | `#0A000000` | 主阴影 |
| `glass_shadow_ambient` | `#0F000000`，y 8dp，blur 24dp | `#0A000000` | 环境阴影 |
| `glass_shadow_floating` | `#1F000000`，y 12dp，blur 40dp | `#14FFFFFF` | **仅 L3** |

规范细则：

- **禁止用 `elevation`/`stateListAnimator` 表达层级**。现有 `stateListAnimator @null` 与 `cardElevation 0dp` 的意图（压平）**保留**，层次一律由「双层自绘阴影 + rim」表达。原因：elevation 阴影是矩形轮廓投影，圆角大时脏；且会隐式引入 Material 的触摸反馈，与 D1 的按压重写打架。
- 阴影用 `ViewOutlineProvider` + `elevation` 只在 **L3** 允许（需要真模糊的面已经有硬件层，边际成本低）；L1/L2 必须走 Drawable 自绘（两层 `layer-list` 渐变模拟）。
- rim 高光在**按压时被削弱、抬起时最强**（§6.2）——这是"玻璃被按下去"这一错觉的关键，比缩放更重要。**宁可砍缩放，也不能砍 rim。**

### 3.5 圆角（Corner）

| Token | 值 | 从哪来 |
|---|---|---|
| `glass_radius_xxs` | 8dp | 沿用现按钮/`bg_info_grid_item` |
| `glass_radius_s` | 12dp | 沿用 `@dimen/card_radius` |
| `glass_radius_m` | 16dp | 新；`NlDialog` 从 12dp 提到 16dp |
| `glass_radius_l` | 20dp | 新；L3 面板 |
| `glass_radius_xl` | 28dp | 沿用 `bg_floating_bar`（dock 唯一材质半径） |
| `glass_radius_pill` | 999dp | 沿用 `bg_chip*` 的 20dp 胶囊语义 |

规范细则：

- **同心圆规则（强制）**：内层半径 = 外层半径 − 内衬。例：dock r28 + 内衬 12dp → 内部按钮最大 r16。**现在 dock(28) 里塞 8dp 按钮是"不统一"，但塞 20dp 按钮会变成"错乱"** —— 所以内衬必须先收敛（D5）。
- **iOS 的连续曲率（continuous corners）在 Views 上无公开 API**，MDC 只有 `CornerFamily.ROUNDED`（圆弧）。取舍：≤16dp 用圆弧（视觉差不可辨）；**≥20dp 的面（dock、弹窗、hero 卡）用自绘 `Path` 三次贝塞尔近似超椭圆**，控制点系数 k=0.5523→实测取 0.63 更接近苹果。列为 **P2**，不做也不影响验收（避免把它当成前置）。
- 圆角改动**分两批**：先 12→12/16 对齐，再统一 radius token。一次性放大所有圆角会让 `bg_lv_pill`(14dp) 之类的既有面变形。

### 3.6 折射与动态反光（Lensing & Dynamic Specular）

这是"液态玻璃"最出圈、也最容易被吹成做不到的一部分。诚实拆成三档：

| 档 | 手段 | 平台 | 成本 | 判定 |
|---|---|---|---|---|
| **A. 边缘折射假象** | `glass_edge_lens` 四角亮线 + 描边宽度渐变 + 玻璃面**下方内容 2–3% 的放大采样** | **全 API 可用**（纯 Canvas/Shader） | ≈0 | ✅ **P0 就做**，观感贡献约 70% |
| **B. 真·位移折射** | `RuntimeShader`（AGSL）在玻璃自己的 render node 上做 displacement，链式接 `createBlurEffect` | **API 33+**（`RenderEffect.createRuntimeShaderEffect`） | AGSL 一次性编译 ≤6ms | ✅ P2，作为 T3 的"加分层"，**不得作为设计依赖** |
| **C. 动态反光（sheen）跟随** | 线性渐变高光位置由**滚动速度**或**设备倾角**（`TYPE_ACCELEROMETER`，60ms 采样上限≈16fps）驱动 | 全 API（Shader 动画） | 每帧 ≤0.3ms | ⚠️ **仅限 dock + 监看 HUD 两处**；省电模式/降低动效下强制关闭 |

- 关于「放大采样」：Android Views 无 backdrop 折射，我们能做的是**让玻璃面自己"看起来"在折射** —— 用 rim 的位置/强度梯度暗示光路。这一条必须写进规范文档并对设计走查说明，否则会出现"为什么我们的玻璃不'透'"的反复拉扯。
- **色散（边缘 RGB 分离）单列为 Q1**：iOS 玻璃棱边有细微色散，但它是**彩色**，与 `colors.xml:39` 直接冲突。我的建议：**默认关闭**；若要开，理由记为"色散色彩由被透射内容派生，属内容而非 UI 控件"（沿用 AI 修图 PRD 的豁免逻辑）。

### 3.7 灰阶规范的处理（关键澄清，请在评审第一条过）

`colors.xml:39` 原文是「状态色：仅灰度表达（规范：不引入彩色）」——约束对象是**状态色**，不是材质。

- 玻璃 tint = 中性白/黑 → **合规**。
- rim/高光 = 带 alpha 的白/黑 → **合规**。
- 透过玻璃看到的照片/监看画面 = **内容**，先例已确立（AI 修图 PRD §风险：「效果内容彩色、UI 控件严格灰阶，设计走查纳入验收」）→ **合规**。
- 唯一彩色 `#FFC400`（`bg_shutter_ring.xml` 取景器快门环）：它是状态指示物，**保持原样、禁止玻璃化**；并按可访问性补一条非色彩冗余（形态/文字），列 P3。
- **不新增语义色**：连接成功/失败/警告继续用灰度深浅 + 图标 + 文字表达。

> 结论：**不需要修改灰阶规范**，也就不存在"为了好看破例开彩"的争议。这是本 PRD 最重要的成本节约。

---

## 四、组件级应用场景（映射到真实文件，逐件可查）

| 组件 | 现状证据 | 玻璃化方案 | 层 | 半径 | 模糊 | 优先级 |
|---|---|---|---|---|---|---|
| **底部导航 dock** | `activity_main.xml:22-25` 60dp 不透明 `@color/nav_background`，4 个手写 `LinearLayout` 单元（非 `BottomNavigationView`） | 悬浮玻璃 dock：左右内缩 12dp、底部 `insets.bottom + 8dp`、r28、tint `content`、真模糊、双层阴影；选中项滑块=实体 | **L3** | 28dp | m(24) | **P0** |
| **弹窗 ×27** | 7 文件 27 处 `MaterialAlertDialogBuilder`（`SettingsFragment` 独占 11 处）；`NlDialog` r12 | `NlGlassDialogs` 工厂 + **`FLAG_BLUR_BEHIND` + `Window.setBackgroundBlurRadius(40dp)`**：系统合成器做的真模糊，零纹理管理。r 12→16 | **L3** | 16dp | l(40)* | **P0** |
| **BottomSheet ×3** | `RemoteFragment.kt:542`、`LiveViewFragment.kt:486`（共用 `dialog_param_picker.xml` 91ln）、`PreviewActivity.kt:403` | 同上 + 顶部 r20 玻璃抓手；`NumberPicker` 去默认分割线改 rim | **L3** | 20dp | l(40) | **P0** |
| **监看 HUD** | `bg_lv_pill.xml`(#B3000000+1dp #33FFFFFF)、`bg_lv_icon.xml`、`bg_fullscreen_button.xml`、5×`#99FFFFFF`(`fragment_liveview.xml:222/248/274/300/326`) | **backdrop = `PixelCopy` 抓 SurfaceView**，真玻璃全套；硬编码 hex 收进 token。现值已是 proto-glass，改完观感跃升最大 | **L3** | 14dp | xl(60) | **P0** |
| **分段控件（3 处重复）** | `fragment_dashboard.xml:52 modeTabContainer`、`fragment_transfer.xml:218 tabIndicator`、`fragment_remote.xml:21-42` | **先合并成 `SegmentedGlassControl` 一个组件**，再涂材质：槽=L2玻璃，滑块=实体 | L2 | 12dp | none | **P0**（前置=合并） |
| **`PressEffect` 全局按压** | `shared/ui/PressEffect.kt:19` `setOnTouchListener`，SCALE 0.95 / 100ms | 升级为 `GlassPress`（`onTouchEvent` 委托式，**修 D1**）：按压缩放 + tint 提浓 + rim 削弱 + 模糊变浅四路联动 | — | — | — | **P0**（前置技术债） |
| **顶栏 ×3** | `fragment_dashboard.xml:11-39`、`fragment_transfer.xml:10-85`、`fragment_settings.xml` 头部；各自 56dp + 1px divider | 抽 `<include>` 的 `NlGlassTopBar`：滚过内容前完全透明，之后 tint 60%→85% + 模糊；**1px 分割线删除**，职责交给 rim 高光 | **L3** | 0→20 | m(24) | P1 |
| **`bg_card`（14 处引用）** | `bg_card.xml` r12 + `@color/surface` + 1dp `@color/outline` | **分裂为两类**（不许全量变玻璃）：`NlCard.Filled`（表单/设置/正文，保持现样）与 `NlCard.Glass`（悬浮信息面、L1） | L1/L2 | 12dp | s(12) | P1 |
| **深色设备卡** | `fragment_dashboard.xml:412 cardDevice` + `bg_card_dark` `#000000` | "深玻璃"：`glass_tint_dark` + rim + 内层 `dark_card_inner` 保持实体（**玻璃不套玻璃**） | L1 | 12dp | s→m | P1 |
| **筛选 chips** | `TransferFragment.kt:227 setupChips` / `:266 renderChips` / `:269` 换 `bg_chip*` | `GlassChip`：未选=L2 半透+描边；**选中仍实体填充**（状态可辨优先） | L2 | pill | none | P1 |
| **选择操作坞** | `bg_floating_bar.xml` r28 + 1dp outline | 与 dock 同材质同参数（一处 token，天然一致） | **L3** | 28dp | m(24) | P1 |
| **预览页工具栏 + EXIF** | `PreviewActivity.kt:400 showInfoSheet`，:404-450 **纯代码 `new LinearLayout/TextView/dp()` 造面板** | 先抽成 `NlInfoSheet` 组件（第二处 sheet 真相源必须收口），再玻璃化 | **L3** | 20dp | l(40) | P1 |
| **列表 item 角标** | `item_photo_grid.xml:46 #33000000`、`:96 #AA000000`、`:117 #CC000000` | 只做**半透明+rim 近似**，**禁止实时模糊**（硬约束 §1.2）；hex 收进 token | L2 | 8dp | none | P2 |
| **重复 item 布局** | `item_recent_device.xml` ≡ `item_wifi_candidate.xml`（近乎逐行重复） | 合并为 `item_action_row.xml`，玻璃 token 一处定义 | L2 | 8dp | none | P2 |
| **空/加载/错误态** | `fragment_transfer.xml:164 layoutEmpty` + `:179 tvMessage`；`fragment_dashboard.xml:449 progressConnecting`、`:462 tvStatusMessage`(160dp 单行 ellipsize) | 空态用 `Filled`；进度指示器在玻璃上必须 ≥3:1；诊断类长文本（`DashboardFragment.renderWifiCandidates` 里 6 行的 ARP/mDNS 信息）**不得放玻璃面** | L2 | — | none | P2 |
| **`NlPicker` 滚轮** | `styles.xml:84-88`，`NumberPicker` 160dp | 玻璃槽 + 选中行高亮条 | L2 | 12dp | none | P2 |
| **Toast ×40** | 见 D6 | `NlFeedback` 单入口封装（内部仍 Toast，或 12+ 走玻璃 Snackbar）。**不逐个替换**，只封边界 | L3 | pill | m | P2 |
| **`bg_shutter_ring` `#FFC400`** | 全 App 唯一彩色 | **不动、不玻璃化**；补非色彩状态冗余 | — | — | — | P3 |
| **设置页/打赏页** | `fragment_settings.xml`(775ln)、`activity_support.xml`(139ln，仓库唯一位图 QR) | **不做玻璃**；仅 `52dp` 行高等硬编码收进 dimens | L2 | 12dp | none | P3 |

---

## 五、页面级应用场景

### 5.1 相册页「相机照片」（Tab 1，收益最高）
`TransferFragment.kt`(1064 行) / `fragment_transfer.xml`(347 行) / `PhotoGridAdapter.kt`(546 行)
- **做**：dock（L3）、顶栏滚动联动（L3）、筛选 chips（L2）、选择操作坞（L3）、分段控件。
- **不做**：网格 cell 本体（L0）、cell 上的下载/标记角标实时模糊。
- **风险点**：长列表 fling 是本 App 最密集的性能场景，而 backdrop 采集源恰好就是这个列表 → **必须 IDLE 才采集**（§8.2）。首屏 3s 出图是 v2.0.2 的既有承诺，玻璃不得让它退化（AC-6）。

### 5.2 监看页（Tab 2 拍摄 + LiveViewActivity）
`LiveViewFragment.kt` / `LiveViewActivity.kt` / `Theme.NLink.Preview`（纯黑沉浸）
- **核心收益页**：背后是实时视频流，模糊与透射最可见。
- **做**：全部 HUD 真玻璃（backdrop 用 `PixelCopy` 抓 SurfaceView，1/8 缩放、30ms 采样上限）、参数 sheet 窗口级模糊、sheen 跟随（受省电约束）。
- **不做**：不得为玻璃改动 `HistogramView`/`ViewfinderOverlayView` 的 canvas 绘制路径；对焦框 `bg_focus_box.xml` 是**矢量括号不是容器**，禁止玻璃化（会削弱"贴在被摄物上"的语义）。
- **硬约束**：监看是**实时视频 + USB/WiFi 双通道 + 大文件在途**的三重负载场景，玻璃开销上限见 §8.4。

### 5.3 预览页
`PreviewActivity.kt`(690 行)：ViewPager2 图片 + 工具栏 + EXIF
- **做**：上下工具栏玻璃化（内容被"穿过"的效果是本页价值所在）、EXIF sheet。
- **必须**：图片页与玻璃之间加 20% scrim（`glass_tint_scrim`），否则白色照片直接顶到玻璃下沿时文字消失（§9.3 自适应的第一号用例）。

### 5.4 设备页（Tab 0，收益中等，克制）
`DashboardFragment.kt`(1031 行) / `fragment_dashboard.xml`(**948 行，全仓最大布局**)
- **做**：dock、弹窗、深色设备卡（L1）、连接态进度区。
- **不做**：正文信息区全量磨砂 —— 这一页背景是纯白，磨砂不可见、白耗性能、还伤对比度。**明确保留 `Filled` 卡**。
- 948 行单布局意味着改版面风险高 → 本次**只换 background/加 include，不重排层级**（附录 B）。

### 5.5 设置页 / 打赏页（Tab 3，**不做玻璃**）
`fragment_settings.xml`(775 行)：背后是白底文字列表 → 玻璃三判据（§1.2）第一条即不满足。
- 只做：token 对齐、硬编码收进 `dimens.xml`、`52dp` 行高提取、新增「外观」分组（承载 §9.4 的三个开关）。
- **这是刻意的"不做"，写进非目标，防止改版蔓延。**

### 5.6 跨页原则
- **Tab 切换不做全屏重绘**：四 Fragment 常驻（`MainActivity.kt:158-165` `hide()/show()`），玻璃纹理跨 Tab 复用会串色 → 切 Tab 时强制作废纹理并重采一次（单次，非每帧）。
- **深色页与浅色页的玻璃不共享 token**：`Theme.NLink.Preview` 是纯黑，其玻璃用"暗面参数"（tint 提黑、rim 提亮），不得套用日间白玻璃。

---

## 六、交互动效与过渡规范

### 6.1 物理模型：弹簧，不是时长（除注明外）
新增 `androidx.dynamicanimation:dynamicanimation`（AndroidX 官方、零传递依赖、API 16+）。以 `response`(s) + `dampingRatio` 描述，替代裸 `duration`：

| ID | response | dampingRatio | 用途 |
|---|---|---|---|
| `SPRING_TAP` | 0.22 | 0.70 | 按压回弹（轻微过冲 1.0→1.015→1.0，"液体的余韵"） |
| `SPRING_PRESS_IN` | — | — | 按入**不用弹簧**：100ms `FastOutSlowIn`（**保留现 `PressEffect` 手感，不得改**） |
| `SPRING_SEG` | 0.35 | 0.55 | 分段控件滑块滑动（可见过冲 = "液态"主要来源） |
| `SPRING_SHEET` | 0.40 | 0.80 | BottomSheet 落位 |
| `SPRING_MORPH` | 0.30 | 1.00 | 玻璃面尺寸/圆角形变（**过冲会让玻璃看起来"抖"，必须临界阻尼**） |
| `SPRING_DOCK` | 0.30 | 0.62 | dock 图标选中弹跳 |

### 6.2 按压态：玻璃的"下沉"（本规范的签名交互）
四路同时驱动，100ms 进入 / 弹簧退出：

| 属性 | 静止 | 按下 | 为什么 |
|---|---|---|---|
| 缩放 | 1.00 | **0.97**（现 0.95 → 收小） | 玻璃是硬壳，0.95 像橡胶 |
| `rim_top` alpha | 100% | **45%** | 光被打散 —— 按下去的主要线索 |
| tint alpha | 85% | **+8%**（提浓） | "变厚了" |
| 模糊半径 | 24dp | **→12dp（`glass_blur_s`）** | 贴合背景了 —— 只有 L3 做这路 |
| 阴影 | floating | **→ key（收缩）** | 离屏幕更近 |
| 触觉 | — | `EFFECT_TICK`（一次） | 玻璃是"咔"不是"嗡"；禁用 `DUAL`/连续振动 |

> 配额紧张时（§3.1 规则 1/2）**优先砍"模糊变浅"，绝不砍 rim 与 tint**。

### 6.3 状态过渡矩阵

| 从 → 到 | 时长 | 曲线 | 属性 |
|---|---|---|---|
| 静止 → 悬浮（hover/焦点） | 150ms | `FastOutSlowIn` | rim+、阴影+，**不位移**（Android 无稳定 hover 语义，只为外接键鼠/DeX） |
| 静止 → 按下 | 100ms | `FastOutSlowIn` | §6.2 |
| 玻璃面出现 | 250ms | `EmphasizedDecelerate` | alpha + scale 0.94→1 + 模糊半径 12→24（**"从背景里凝出来"**） |
| 玻璃面消失 | 200ms | `EmphasizedAccelerate` | 反向，且**消失比出现快**（不对称是苹果的手感来源） |
| 内容滚动到玻璃下 | 150ms | `Linear` | tint `content`→`moving` |
| dock 切 Tab | 350ms | `SPRING_SEG` | 滑块形变 + 图标弹跳 |
| 弹窗背后模糊建立 | 220ms | `FastOutSlowIn` | `blurRadius` 0→40dp（T2+ 才有此项） |
| Tab 内容互换 | 220ms | `FastOutSlowIn` | 淡入淡出 + 玻璃材质**不重绘** |

### 6.4 页面转场
| 场景 | 现状 | 方案 |
|---|---|---|
| 一级 Tab 切换 | 无动画（`hide/show`） | 内容**交叉淡入 + 3% 横向位移**（subtle parallax）；玻璃层不重播动画 |
| 二级页推入 | `NlWindowAnim`：右推入/左滑出 0.3s（4 个 `res/anim/` 文件） | 改**深度转场**：进入页 scale 0.92→1 + alpha，同时退出页 scale 1→0.96 + 变暗 scrim；退出反向。**这是 iOS 导航质感的第一来源** |
| 预览页进入 | 无共享元素 | **P2**：相册格 → 预览页做 bounds morph（`TransitionManager` + `ChangeBounds`，或手工 `ValueAnimator` 驱动 outline）；失败即回落到淡入，不得阻塞 |
| 监看页进入 | 无 | 方向锁 `sensor` 的转场必须与玻璃动画解耦，避免横屏时 dock 位置动画打架 |

### 6.5 滚动联动
- 内容穿过顶栏玻璃下沿时：tint 0→85%、模糊 0→24dp，**阈值式而非连续式**（连续驱动 = 每帧改 render node = 卡）。
- **fling 期间冻结全部玻璃参数**（含 tint 提浓切换），待 `IDLE` 一次性结算。
- 不做惯性视差（列表 + 玻璃双层视差在低端机上必掉帧）。

### 6.6 对系统设置的服从（硬要求）
| 系统状态 | 行为 |
|---|---|
| `Settings.Global.ANIMATOR_DURATION_SCALE == 0` | 全部弹簧与过渡退化为**瞬态切换**，且**禁用 sheen/tilt** |
| `windowAnimationScale == 0` | 保留转场终态，跳过动画过程 |
| `ValueAnimator` 缩放 | **禁止**在自研动画里硬编码时长绕过系统缩放 |
| `isHighTextContrastEnabled` | 自动开启「降低透明度」（§9.4） |
| `isPowerSaveMode` / `ADAPTIVE_POWER` | 玻璃降到 T1 路径，禁用 tilt sheen 与实时模糊 |

---

## 七、响应式与多设备适配

### 7.1 断点与玻璃配额
新增 `values-w600dp/` + `values-w840dp/` 资源限定符（**不引 `androidx.window`**，与本项目"手写为主"的风格一致；折叠态感知需求留 Q6）。

| 断点 | 宽度 | dock | 顶栏 | 卡片 | 同屏实时模糊上限 |
|---|---|---|---|---|---|
| compact | <600dp | 悬浮、居中、**max-width 480dp** | 单行 | 单列 | 3 |
| medium | 600–839dp | 悬浮、居中、480dp，两侧留白 | 单行 | 2 列 | 3 |
| expanded | ≥840dp | **左侧竖向玻璃导航栏**（r28，宽 260dp） | 单行 | 2–3 列 | 4 |

- **禁止把手机 dock 拉成 12 英寸的横条** —— 大屏最大的玻璃失败模式。max-width 480dp 是硬要求。
- expanded 下顶栏玻璃可让位给通高侧栏，但**同一屏仍只允许 1 个主悬浮玻璃**。

### 7.2 系统栏 / 刘海 / 手势（与 D2 绑定）
- 先做真 edge-to-edge：`WindowInsetsCompat` + `setOnApplyWindowInsetsListener` 统一分发；dock 底部 = `max(margins.bottom, 8dp) + 8dp`；顶栏加 `insets.top`。
- `windowLayoutInDisplayCutoutMode=shortEdges` 已有，保留；监看/预览页横屏时 dock 需避让左右 cutout。
- 键盘：`dialog_param_picker.xml` 的手动输入 `EditText` 需 `ime` insets 抬升，玻璃 sheet 抬升用 `SPRING_SHEET`，**不得让 sheet 底部仍为模糊**（会糊住输入框）。

### 7.3 字体缩放与放大显示
- 全 App 用 `sp`，`NlText.*` 保留 `maxLines=1 + ellipsize`（`styles.xml:10-11`）。
- **在 `fontScale ≥ 1.3` 时，所有 L2 玻璃胶囊从"固定高 + 单行"改为"垂直内衬 + 允许换行"** —— 否则玻璃按钮文字必被裁。写进 AC-11。
- `densityDpi` 变化（放大显示/接外屏）需作废纹理并重算 rim 像素宽度：`strokePx = max(1, round(dp × density))`，**避免 0.5dp 描边在 mdpi 上变成 0px 直接消失**。

### 7.4 触控目标与可达性
- **现值违规项**：顶栏 40dp 无边框图标按钮（`?attr/selectableItemBackgroundBorderless`）低于 48dp。玻璃化时**统一 48dp 可视或 48dp 透明 hit-slop**。
- dock 悬浮后单元高度 60dp 足够，但**底部内缩会让"贴底"操作变远** → dock 需可被用户移到顶部？（不做，列 Q5）；至少：dock 单元 hit-slop 上下各外扩 8dp。
- 单手操作：dock 保持底部；主 CTA（快门/记录）在监看页仍贴拇指弧区，玻璃不改变几何位置。

---

## 八、动效与渲染性能约束（硬性预算）

### 8.1 帧预算
| 项 | 值 |
|---|---|
| 基准帧时长 | 60Hz 16.6ms / 90Hz 11.1ms / 120Hz 8.3ms |
| **玻璃子系统每帧总开销** | **≤ 3.0 ms**（基准机：骁龙 7 系，对齐 AI 修图 PRD §8.3 口径） |
| 单帧内模糊面积 | ≤ 视口面积 45% |
| 每帧 render node 属性变更次数 | ≤ 2（超过即改阈值式） |
| 动画中并发动画数 | ≤ 4 |

### 8.2 backdrop 采集器（本方案的性能命门）
> 唯一可行的做法：**永远不要在原始分辨率上模糊。**

1. 把玻璃面下方的内容子树 `draw()` 到一张**复用的低分辨率 Bitmap**：**1/8 缩放、长边 ≤320px、ARGB_8888、不 new、原地复用**。1080×2400 → 135×300 ≈ 158KB。
2. 在这张小图上做模糊：24dp 半径换算到小图只有 ~9px → 开销近乎恒定。
3. 把小图**双线性放大**画进玻璃面 → 视觉上正是一个柔和的高斯背景（这正是人眼分辨不出来的部分）。
4. **采集时机**：`SETTLING→IDLE` 后 1 帧；几何/可见性变化去抖 120ms；**fling 中不采集**；同屏 ≤1 个采集器实例（不是每面玻璃一个）。
5. 监看页例外：`SurfaceView` 无法 `draw()`，用 `PixelCopy`（1/8 目标位图，**采样间隔 ≥30ms → 33fps 上限**），失败（权限/时序）**静默退化为纯色 tint**，不提示、不崩。
6. 弹窗/sheet 例外：**不采集**，直接用窗口级 `FLAG_BLUR_BEHIND`（系统侧完成，成本归零）。

### 8.3 资源上限
| 项 | 上限 |
|---|---|
| 玻璃纹理 Bitmap 数量 | ≤ 4 张，常驻 ≤ 1.5 MB |
| 整个玻璃子系统内存增量 | ≤ 8 MB |
| `onTrimMemory` | `TRIM_MEMORY_RUNNING_LOW` 起即释放纹理（下次采集重建）；`UI_HIDDEN` 停全部动画 |
| APK 体积 | ≤ +0.5 MB；**禁止**引入 .so、模型、字体、位图材质贴图 |
| `LAYER_TYPE_HARDWARE` | **只允许加在 L3 的容器上**；禁止加在 RecyclerView item（内存爆）与 ScrollView 本体（整屏层） |
| AGSL 编译 | 一次性 ≤ 6ms，在 `IdleHandler`/后台预热，**禁止在 `onCreate` 主线程**；缓存复用 |
| 反射/隐藏 API | **禁止**（对齐 v2.2 §G4 的既有立场：不做隐藏 API 反射 hack） |
| 主线程 IO | 禁止（含读取 token 资源以外的任何文件） |

### 8.4 场景专项：监看 + 传输在途（本项目独有的真实负载）
这是本 App 区别于普通 UI 的地方，必须单列：
- PTG 解码 + 上屏已经吃满 GPU/带宽；玻璃在此之上加实时模糊会直接体现在**监看帧率**上。
- 因此监看页额外规则：
  1. **监看帧率 > 0 时，同屏实时模糊面 ≤ 2**（dock 让位给 HUD）。
  2. 大文件传输在途（`PtpSessionManager.bulkDepth > 0`，v2.2 G7 已有此计数）时，**HUD 自动切 T1 静态 tint 路径**，传输结束恢复。
  3. tilt sheen 在监看页默认关闭（省电 + 防抖动手感）。
- 功耗验收见 AC-9：监看 10 分钟，基准机电量增量相比 v2.2 ≤ 3%。

### 8.5 度量方法（必须可复现，不靠感觉）
| 工具 | 用途 |
|---|---|
| `adb shell dumpsys gfxinfo com.nikonlink.app framestats` | 滑动 P50/P95、jank 帧数、超帧率预算帧数 |
| `android.window/FrameMetrics API`（`Window.addFlags` 走 `OnFrameMetricsAvailableListener`） | 采集 `DURATION`/`COMMAND_ISSUE`/`DURATION` 分项，定位是否是玻璃 |
| `GPU 呈现模式分析`（开发者选项）+ 录屏 240fps 慢放 | 人眼复核 |
| `dumpsys meminfo` | 玻璃开关前后 PSS 差值 |
| `dumpsys batterystats --charged` | 监看 10min 功耗差 |
| 对比脚本 | **同一台机、同一张卡、同一份照片列表**，玻璃总闸 on/off 各跑 3×10s fling |

---

## 九、兼容性与可访问性

### 9.1 能力分层（`GlassTier`，运行时判定一次 + 可被用户覆盖）
| 层 | 条件 | 可得 | 必须自建 |
|---|---|---|---|
| **T3** | API ≥ 33 且 `isBlurEnabled` | `RuntimeShader`(AGSL) + `RenderEffect` 链 + 窗口模糊 + **真边缘折射** | backdrop 采集器 |
| **T2** | API 31–32 且 `isBlurEnabled` | `RenderEffect.createBlurEffect` + 窗口模糊 | backdrop 采集器、折射（降级为 §3.6-A 假象） |
| **T1** | API 29–30，或 `isBlurEnabled()==false`，或省电模式 | **无 `RenderEffect`** | 见下 |

**T1 是一套完整设计，不是残缺态**（本项目 minSdk 29 意味着 T1 是真实用户群，Android 10/11 存量不可忽略）：
- 材质：tint 提浓一档（`content`→`moving` 值）+ **加强 rim 与双层阴影** + 边缘亮线。层级信息全部转移到"描边 + 阴影 + 染色"三个不依赖模糊的通道上。
- 可选：**仅对 dock 与弹窗**做 CPU 可分离 box-blur（1/8 小图上两遍，≈1–2ms，只在 settle 时跑一次）。默认关，留一个 `UiFlags` key。
- **禁用 RenderScript** 做 T1 模糊：API 31 起废弃、部分 OEM ROM 移除、增加 .so 与体积，与 §8.3 冲突。
- **禁用 `BlurMaskFilter`** 做实时模糊：CPU Skia 路径，全屏开销不可控。
- 验收要求：**Android 10 真机截图必须进入视觉走查基线**（AC-2），不允许"T3 好看就行"。

### 9.2 OEM 与失效场景清单
| 场景 | 行为 |
|---|---|
| `WindowManager.isBlurEnabled()` false | 整体走 T1 |
| 窗口被系统标记不可模糊（画中画、省电、部分安全场景） | T1 + 记录一次日志 |
| `PixelCopy` 抓 SurfaceView 失败/时序错位 | 静默退化纯色 tint，**不弹 Toast、不重试成风暴** |
| 硬件层创建失败（低端机纹理内存不足） | 该面退回 `Filled` |
| 折叠屏铰链区 / 分屏小窗 | 作废纹理重采；宽 <360dp 时 dock 退化为不悬浮（避免挤压） |
| 无障碍放大（magnification）中 | 停 sheen/tilt，避免高光在放大画面里闪烁 |

### 9.3 对比度自适应（本 PRD 的护城河）
**免费午餐**：§8.2 已经采集了低分辨率背景纹理 → 顺手算亮度。

1. 玻璃面出现/纹理更新后，在 1/8 纹理上取玻璃矩形对应子区域，**每 4px 抽样**（≈4k 样本，<1ms，后台线程）。
2. 计算平均相对亮度 `L`（sRGB 线性化后按 WCAG 公式）与粗对比范围（p5–p95）。
3. 计算「文字色 vs（tint 与 L 的合成结果）」对比度。
4. **不达标 → tint alpha +5% 一档，最多 3 档**。
5. **仍不达标 → 这一面关掉模糊与透明，改用实体 `@color/surface` + 1px `@color/outline`** —— 即完全退回现设计规范，安全且已被验证过。
6. 结果缓存 200ms，避免每次采样都调。
7. 阈值：正文/图标 **4.5:1**；≥20sp bold 或 ≥24sp **3:1**；纯装饰 rim 不设阈值。
- 特别用例：**预览页白色照片**（L≈1.0）是日间白玻璃最危险的时刻，此项必须有单测（§10.2 `ContrastGuardTest`）。

### 9.4 用户开关（放在设置页，三个）
| 开关 | 默认 | 效果 |
|---|---|---|
| **经典外观** | 关 | 总回退闸：整个玻璃子系统失效，观感回到 v2.2（附录 B 保证可逆） |
| **降低透明度** | 关；`isHighTextContrastEnabled` 时**自动开** | 保留层级（rim/阴影/圆角），但 tint→不透明、模糊全关 |
| **动态效果**（sheen/tilt/过冲） | 开 | 关闭后所有弹簧转固定时长曲线、禁用 tilt；`ANIMATOR_DURATION_SCALE==0` 时自动等效关闭 |

- 复用现成模式：`SettingsFragment.kt:230` 已有三态「浅色/深色/跟随系统」对话框（`AppCompatDelegate.setDefaultNightMode`），UI 与代码路径可直接照抄；`values-night` 与 `AppCompatDelegate` 联动无需新机制。
- 开关必须**即时生效**（不重启 Activity 也能重绘），且状态入 `AppSettings`（Room/DataStore 已有）。

### 9.5 无障碍其余项
- **TalkBack**：dock 单元需 `role`/`stateDescription`（"已选中"），玻璃化不得吞掉 `contentDescription`；现状 `activity_main.xml` 里中文 `android:text`/`contentDescription` **硬编码未走 `@string`** → 本次顺手收进 `strings.xml`（低风险、必要）。
- **信息不得只靠动效传达**：rim 变化/tint 变化不能是唯一状态线索；选中/连接态必须有文字或形状。
- **色彩无关性**：状态点 `bg_status_dot.xml`（8dp 灰点）+ 唯一彩色 `#FFC400` → 补文字或形状冗余（P3）。
- **焦点与键鼠/DeX**：`focusable` 元素需可见焦点环；玻璃面上焦点环用 **2dp 实心 + 外描边**（半透明焦点环在玻璃上不可见）。
- **前庭敏感**：禁用大位移视差、禁用 dock 弹跳超过 8%、sheen 不做全屏扫动。
- **触控目标** ≥48dp（§7.4）。

### 9.6 targetSdk 时间线（明确的外部约束）
- 现 `targetSdk = 35`（`app/build.gradle.kts:23`）。`windowOptOutEdgeToEdgeEnforcement` 在 **targetSdk 35 + Android 15 上目前仍生效**，但**官方已声明在 targetSdk 36（Android 16）"deprecated and disabled"**。
- 结论：**必须在本次完成真 edge-to-edge**，否则 v2.4 升 targetSdk 时玻璃与系统栏会同时出问题。这是 D2 的到期日，也是把它列进 P0 的理由。
- 同期注意：`compileSdk 35` 下 `TetheringManager` 类公开 API 缺失的前例（v2.2 §G4）说明**不要用未收录 API**，AGSL 与 `RenderEffect` 均是公开 API，安全。

---

## 十、实现建议（可落地）

### 10.1 为什么不迁 Compose（方案对比）
| 方案 | 内容 | 工作量 | 风险 | 判定 |
|---|---|---|---|---|
| **A** | **Views 原生玻璃层**：`Drawable` + `RenderEffect`/AGSL + 自建 backdrop 采集 + 资源 token | 约 8 周（§12） | **低**：不触碰连接/传输主干；逐面可回滚 | ✅ **采用** |
| B | 新页面用 Compose、老页面 Views，混合 | 每个新页 +30% 联调 | 中（`ComposeView` 嵌 View 树，玻璃纹理跨边界） | ⏸ 本期不开口，未来有全新大功能页再议（Q7） |
| C | 全量 Compose 重写后做玻璃 | 6–10 周**纯重写**，不含新功能（69 个 Kotlin 文件、15 个布局、4 个 Fragment 各约 1000 行） | **高**：这 4 个页面直接持有 PTP/传输状态机，重写等于回归 v2.2 全部已修缺陷 | ❌ **否决**：视觉收益不值这个回归风险 |

### 10.2 新增代码结构（**上限 5 个文件**，防膨胀）
```
com/nikonlink/app/shared/ui/glass/
  GlassTier.kt        // 能力判定：SDK_INT + isBlurEnabled + powerSave + 用户覆盖；一次算好缓存
  GlassBackdrop.kt    // 唯一采集器：1/8 复用 Bitmap + 节流 + draw() / PixelCopy 双路 + 亮度统计
  BlurBackend.kt      // sealed: RenderEffectBackend(31+) / CpuBoxBackend(29-30) / NoneBackend
  GlassSurfaceDrawable.kt  // 材质实体：tint + 4 条 rim + 双层阴影 + sheen + radius；bg_*.xml 的唯一替代者
  GlassMotion.kt      // SpringForce 参数表 + MotionSpec 时长曲线 + 降级判定 + GlassPress(替代 PressEffect)
res/values/glass.xml + res/values-night/glass.xml   // 成对 token，缺项 CI 报错（修 D3）
```
配套（**不新增文件**，在现有文件上改）：
- `NlGlassDialogs.kt` 挂到 `shared/ui/`：`fun dialog(ctx): MaterialAlertDialogBuilder` / `fun sheet(ctx): BottomSheetDialog`，内部做 `FLAG_BLUR_BEHIND` + `setBackgroundBlurRadius` → **27 + 3 处只改工厂引用，不改逻辑**。
- `SegmentedGlassControl`（合并 3 处分段 Tab）、`NlGlassTopBar`（`<include>` 合并 3 处顶栏）。

**关键签名草案**
```kotlin
object GlassTier { val tier: Tier /* T1|T2|T3 */; fun blurEnabled(wm: WindowManager): Boolean }

class GlassBackdrop(private val contentRoot: View) {
  fun attach(glass: GlassSurfaceView, src: Src)          // Src.Content(view) | Src.Surface(surfaceView)
  fun onScrollStateChanged(state: Int)                   // 只在 IDLE 结算
  fun invalidate()                                       // 切 Tab / 尺寸变化 / insets 变化
  val avgLuminance: Float                                // 供 ContrastGuard
}

data class GlassMaterial(
  val blur: Dp, val tint: Int, val radius: Dp,
  val rim: Rim, val shadow: ShadowLevel /* KEY|FLOATING|NONE */
) { companion object { val Dock; val Chip; val CardGlass; val Hud; val Dialog } }

fun View.applyGlass(m: GlassMaterial)   // 一个入口，内部按 tier 决定实现
```
单测要求：`GlassTierTest`（3 层分支）、`ContrastGuardTest`（白照片 / 黑监看 / 纯白底三例的 tint 步长结论）、`BlurBackendTest`（radius clamp 与 dp→px 换算在 160/420/640dpi 的值）。

### 10.3 资源层改动清单（顺序敏感）
1. 建 `glass.xml` / `values-night/glass.xml`，**先补齐现有日夜 token 成对（D3）**，加 CI 校验脚本（对齐 `_verify_regex.py` 的既有习惯，放 `tools/`）。
2. `bg_*.xml` 17 个 → 逐个决定归属：`Glass / Filled / 不动`，产出一张对照表进 PRD 附录（防"改到哪算哪"）。
3. **不删任何旧 drawable**（回退闸门依赖它们，见 10.4）。
4. 硬编码 hex（D4）与硬编码 dp（D5）在本步一并收口。
5. 连续曲率 `Path` 工具放 `GlassSurfaceDrawable` 内，不独立成库。

### 10.4 灰度与回退闸门（**照抄 v2.2 已验证的模式**）
- v2.2 用 `ConnFlags` 总闸 + 设置页「v2.2 连接改进」开关（关闭即回 v2.1.1 行为）+ 单项 key —— 已证明可行。
- 本次建 `UiFlags`：`GLASS_ROOT`（总闸）+ 单项 key（`GLASS_DOCK` / `GLASS_DIALOG_BLUR` / `GLASS_HUD` / `GLASS_TOPBAR` / `GLASS_PRESS` / `GLASS_MOTION` / `GLASS_CONTRAST_GUARD`）+ 用户可见的「经典外观」开关（§9.4）。
- **闸门语义必须是"关掉后逐像素等于 v2.2"**，不是"关掉后大致没变"。这决定了能否快速止血，也是 AC-12 的核心。
- 玻璃化通过**覆盖层实现**（新 drawable/新 style 名 + 视图工厂），旧资源全保留 → 回滚是换 flag，不是 revert 代码。

### 10.5 分期与实施顺序（顺序错了会返工）
| 期 | 内容 | 为什么这个顺序 |
|---|---|---|
| **M0 地基** | `GlassTier`/`BlurBackend`/`GlassBackdrop`/token 文件；**D1 `PressEffect` 重写**；**D2 真 edge-to-edge** | 三者都是"后面对齐"的前提，且 D1/D2 各自有回归风险，必须**单独 PR、单独验收** |
| **M1 免费模糊** | 27 弹窗 + 3 sheet 走 `NlGlassDialogs` + 窗口级模糊 | 改动最小、全 App 立刻见效、无纹理管理 → **用最少的钱证明方向对** |
| **M2 结构合并** | `SegmentedGlassControl`×3、`NlGlassTopBar`×3、重复 item 布局合并 | **先合并再涂材质**，否则同一效果实现三遍且必然不一致 |
| **M3 dock + chips + 操作坞 + hero 卡** | 第一批真玻璃面 | 依赖 M0 采集器与 M2 的 token 收口 |
| **M4 监看/预览 HUD + 采集器实战** | `PixelCopy` 路、对比度自适应 | 最难，放最后但有前三期垫底 |
| **M5 动效与转场** | `SPRING_*` 全面接入、深度转场 | 视觉已定，才谈手感调参 |
| **M6 a11y + 大屏 + 性能验收** | 三开关、`w600dp/w840dp`、断言预算、三档真机走查 | 收口 |

**P0 = M0–M3**（可独立发版：只有弹窗+dock+控件变玻璃，风险最小、可见收益最大）。M4–M6 为 P1/P2。

---

## 十一、验收标准

> 前缀 **AC**；「必」= P0 发版门槛。验证包命名沿用仓库既有格式：`dist/N-Link-v2.3.0-release-液态玻璃P0.apk`。

- **AC-1 材质一致性（必）**：全仓 grep 后 `res/layout` + Kotlin 中**硬编码 hex 材质色 = 0**（现状 `#AA000000/#CCFFFFFF/#99FFFFFF/#33000000/#666666` 等全部收口）；同一玻璃 token 在 dock / 操作坞 / HUD 上渲染参数逐项相等（截图取色比对 ΔE < 2）。
- **AC-2 三档真机走查（必）**：Android 10(T1) / 12(T2) / 14+(T3) 各一台，7 个界面 × 深浅色 × 「经典外观 关/开」共 **56 张截图**入库为基线。**T1 截图不得出现"什么都没变的灰块"或明显对比度失败**。
- **AC-3 弹窗免费模糊（必）**：任选 3 个 `MaterialAlertDialogBuilder` 场景 + 参数 sheet，T2+ 上窗口背后真模糊生效；T1/省电模式下退化为不透明 sheet 且文字 ≥4.5:1；**27 处调用点行为与文案零变化**（回归）。
- **AC-4 玻璃配额（必）**：静态检查 + 运行时断言（debug 构建）：同屏实时模糊面 ≤3、动画中玻璃 ≤2、覆盖面积 ≤45%；超限 debug 构建抛断言、release 静默降级并打点。
- **AC-5 对比度自适应（必）**：自动化取样脚本跑 7 界面 × 深浅 × 10 组真实照片，**文字对比度 AA 100% 通过**；构造用例「预览页全白 JPEG」必须触发 tint 加浓或 `Filled` 回退；`ContrastGuardTest` 绿。
- **AC-6 相册性能与首屏（必）**：同机同卡 A/B（总闸 on/off）：fling P95 帧时长不劣化 >5%、jank 帧占比不升高；**列表 item 零实时模糊**（`dumpsys SurfaceFlinger` 层数核对）；v2.0.2 的「首屏 <3s 出图」不回归。
- **AC-7 监看链路（必）**：监看 10 分钟：帧率不劣于 v2.2；`bulkDepth>0`（大文件在途）时 HUD 自动切 T1 且恢复；`PixelCopy` 失败路径构造测试（人为断开）→ 静默降级、**无 Toast 风暴、无崩溃**。
- **AC-8 按压与拖选共存（必）**：`GlassPress` 重写后，相册长按拖选（`DragSelectController`）在 20 次连续操作中选择集正确；禁用元素无反馈；按压四路属性（缩放/rim/tint/模糊）在 slowmo 下可辨且**无一处用 `elevation` 表达层级**。
- **AC-9 功耗与内存（必）**：监看 10min 基准机电量增量 ≤ v2.2 +3%（`dumpsys batterystats`）；玻璃子系统 PSS 增量 ≤ 8MB；`onTrimMemory(RUNNING_LOW)` 后纹理释放且下次采集可重建（不白屏）。
- **AC-10 稳定性（必）**：三档真机各跑 Monkey 10 万次事件（含 dock、chips、HUD、27 弹窗入口）零崩溃零 ANR；切 Tab 500 次无内存上涨、无纹理串色；连续旋转 200 次（监看 `sensor`）。
- **AC-11 可访问性（必）**：TalkBack 走查四 Tab + dock（选中态可读）；`fontScale 1.3/2.0` 下无文字裁切（L2 胶囊自动换行）；触控目标 ≥48dp；焦点环在玻璃上可见（2dp 实心）；`ANIMATOR_DURATION_SCALE=0` 下 sheen/tilt 关闭；`isHighTextContrastEnabled` 自动开「降低透明度」。
- **AC-12 回退与零回归（必）**：「经典外观」开启后**逐像素等于 v2.2**（截图 diff 全平）；v2.2/v2.0.1 已跑通功能（AP 地址学习、关蓝牙连 AP、>60s MOV 不被心跳掐断、拔插自愈、相册排序、F1 标记、两段式缩略图、AF 驱动、参数读写、百度网盘更新通道）**零回归**；连接层/传输层文件**未被本次改动触碰**（`git diff --stat` 佐证）。
- **AC-13 大屏（P1）**：≥840dp 下 dock 变侧栏、max-width 480dp 不拉伸、2–3 列、模糊上限 4 生效；分屏小窗与折叠悬停无内容遮挡。

---

## 十二、里程碑

| 里程碑 | 周期 | 交付 | 出口判据 |
|---|---|---|---|
| M0 地基 | 第 1 周 | 玻璃三件套 + `GlassTier` + token 文件 + D1 按压重写 + D2 edge-to-edge | AC-8、单测绿、insets 四页无压栏 |
| M1 免费模糊 | 第 2 周（0.5 周） | `NlGlassDialogs`，27+3 接入 | AC-3 |
| M2 结构合并 | 第 2–3 周 | `SegmentedGlassControl` / `NlGlassTopBar` / item 合并 | 三处行为逐条回归；净删行数 ≥ 新增 |
| M3 dock 与控件 | 第 4–5 周 | dock、chips、操作坞、hero 卡 | AC-1、AC-4、AC-6 |
| M4 监看与预览 | 第 6 周（1.5 周） | HUD + `PixelCopy` + 预览工具栏 + `ContrastGuard` | AC-5、AC-7 |
| M5 动效转场 | 第 7 周 | `SPRING_*` 全面接入 + 深度转场 | AC-9、盲测≥4.0 |
| M6 打磨验收 | 第 8 周 | 三开关、大屏、性能与 a11y 全量、灰度验证包 | **AC-1…AC-13 全绿 + AC-12** |

约 8 周（含真机走查等待）。**P0 出口 = M0–M3 完成（第 5 周），可独立发 v2.3.0**；M4–M6 可滑入 v2.3.1。

---

## 十三、风险与应对

| # | 风险 | 概率/影响 | 应对 |
|---|---|---|---|
| R1 | **全页磨砂** → 可读性与性能双输 | 高/高 | §3.1 配额 + §1.2 三判据 + AC-4/5/6 硬门槛；设置页/设备页明确不做 |
| R2 | **T1（Android 10/11）被当成残废态** | 高/中 | T1 独立设计（§9.1）+ AC-2 强制 T1 截图走查；T1 用户占比需在真机数据里确认（Q2） |
| R3 | **backdrop 采集器成本失控**（每帧 `draw()` 子树 = 全列表重绘） | 高/高 | 只在 IDLE 采集 + 1/8 缩放 + 单实例；预算 ≤3ms/帧；超标即整体退回"仅窗口模糊 + 静态 tint"（M1 独立完成的价值就在此） |
| R4 | **`PressEffect` 重写破坏拖选** | 中/高 | 单独 PR、单独验收 AC-8；不通过则玻璃按压改为"只动 rim/tint 不动 scale" |
| R5 | **去 opt-out 引发全局 insets 回归** | 中/高 | D2 前置到 M0 并**先于任何玻璃改动**；四页 + 两 Activity 逐页验收；必要时 `windowOptOutEdgeToEdgeEnforcement` 保留到 v2.4 |
| R6 | **监看 + 传输在途掉帧** | 中/高 | §8.4 三条硬规则（≤2 面、`bulkDepth>0` 切 T1、tilt 默认关）+ AC-7 |
| R7 | **低端机 `LAYER_TYPE_HARDWARE` 纹理内存爆** | 中/中 | 只允许加在 L3 容器；≤4 纹理；`onTrimMemory` 释放；AC-9/10 |
| R8 | **玻璃化把"状态可辨性"吃掉**（选中/连接态靠深浅，玻璃一糊就没了） | 中/高 | 选中态**必须实体填充**（§3.3 `glass_tint_solid_sel`）；文字/形状冗余优先于色彩与材质 |
| R9 | **视觉改版掩盖功能回归** | 中/高 | AC-12 强制「功能零改动」+ 逐面截图 diff + `git diff --stat` 佐证未碰连接层 |
| R10 | 边缘色散破灰阶规范引发反复拉扯 | 低/中 | 默认关闭，列 Q1，不进入 P0/P1 |
| R11 | 40 处 Toast 逐个替换造成大面积 diff | 中/低 | §10.5：只封 `NlFeedback` 边界，不逐个改，列 P2 |
| R12 | 改版蔓延（顺手重排 948 行 `fragment_dashboard.xml`） | 中/高 | §1.2 非目标 + 附录 B「明确不动」；本期只允许换 background / 加 include |

---

## 十四、待确认问题

| # | 问题 | 我的建议 | 影响 |
|---|---|---|---|
| **Q1** | 允许玻璃边缘**色散**（iOS 有，属彩色，与 `colors.xml:39` 冲突）吗？ | **默认关闭**；如开，理由记为"派生自被透射内容" | 小 |
| **Q2** | **minSdk 能否升到 31？** | **建议不升**，但要真机用户数据。升 31 可删掉整个 T1 分支 + CPU 模糊后端（估省 1.5–2 周），代价是放弃 Android 10/11 | **大** |
| **Q3** | 接受「设置页 / 设备页正文**不做玻璃**」这个克制吗？ | **接受**。否则工作量与风险翻倍而观感不增 | 中 |
| **Q4** | 版本号：v2.3.0（推荐）还是 v2.2.x？ | **v2.3.0 / versionCode 20**。纯视觉但是跨 7 界面的大改动 | 小 |
| **Q5** | 「经典外观」开关**永久保留**还是灰度 2 个版本后删？ | **永久保留**。它同时是 §9.4 的可访问性回退手段，删了会伤低视力用户 | 中 |
| **Q6** | 折叠屏/大屏要不要引 `androidx.window`（当前 0 依赖）做 `WindowInfoTracker`？ | **先不引**，用 `w600dp/w840dp` 限定符（§7.1）。真出现铰链误判再议 | 小 |
| **Q7** | 新页面是否允许试点 Compose（方案 B）？ | **本期不开口**。先让 A 走完，避免同期维护两套材质 | 中 |

---

## 附录 A：Token 速查表（可直接抄进 `res/values/glass.xml` + `values-night/glass.xml`）

```xml
<!-- 半径 -->
<dimen name="glass_radius_xxs">8dp</dimen>   <dimen name="glass_radius_s">12dp</dimen>
<dimen name="glass_radius_m">16dp</dimen>    <dimen name="glass_radius_l">20dp</dimen>
<dimen name="glass_radius_xl">28dp</dimen>   <dimen name="glass_radius_pill">999dp</dimen>
<!-- 模糊（dp；实现层按 §8.2 在 1/8 纹理上换算 px） -->
<dimen name="glass_blur_s">12dp</dimen>  <dimen name="glass_blur_m">24dp</dimen>
<dimen name="glass_blur_l">40dp</dimen>  <dimen name="glass_blur_xl">60dp</dimen>
<dimen name="glass_backdrop_downscale">0.125</dimen>
<integer name="glass_backdrop_max_long_edge">320</integer>
<integer name="glass_blur_max_px">100</integer>
<!-- 描边 / 高光（day → values-night 见下表） -->
<color name="glass_stroke_outer">#33000000</color>  <color name="glass_rim_top">#66FFFFFF</color>
<color name="glass_rim_bottom">#1A000000</color>    <color name="glass_edge_lens">#4DFFFFFF</color>
<color name="glass_shadow_key">#14000000</color>    <color name="glass_shadow_ambient">#0F000000</color>
<color name="glass_shadow_floating">#1F000000</color>
<color name="glass_tint_flat">#F2FFFFFF</color>     <color name="glass_tint_content">#D9FFFFFF</color>
<color name="glass_tint_moving">#EAFFFFFF</color>   <color name="glass_tint_dark">#BF000000</color>
<color name="glass_tint_solid_sel">@color/primary</color>
<color name="liveview_scrim">#99000000</color>      <!-- 沿用现值，改为被 glass 引用 -->
<!-- 配额 / 预算（运行时断言用） -->
<integer name="glass_max_blur_surfaces">3</integer>
<integer name="glass_max_anim_surfaces">2</integer>
<integer name="glass_max_coverage_pct">45</integer>
<integer name="glass_backdrop_max_bitmaps">4</integer>
<integer name="glass_backdrop_debounce_ms">120</integer>
<integer name="glass_pixelcopy_min_interval_ms">30</integer>
<integer name="glass_tilt_sample_ms">60</integer>
```
**夜间差异（`values-night`）**：`stroke_outer #24FFFFFF`、`rim_top #40FFFFFF`、`rim_bottom #33FFFFFF`、`edge_lens #33FFFFFF`、`shadow_key #0A000000`、`shadow_ambient #0A000000`、`shadow_floating #14FFFFFF`（夜间用亮环代阴影）、`tint_flat #F2000000`、`tint_content #BF000000`、`tint_moving #E6000000`、`tint_dark #BFFFFFFF`。
**大屏差异（`values-w840dp`）**：`glass_max_blur_surfaces` → 4；`glass_dock_max_width` → 480dp；`glass_dock_orientation` → vertical。

## 附录 B：明确不动的清单

- **业务主干**：`ConnectionManager` / `PtpSessionManager` / `TransferManager` / `ThumbnailCache` / 队列与去重 / 通道回退 / F1 标记分区（对齐 v2.2 §十三、v2.0.2 §五）。
- **`fragment_dashboard.xml` 的 948 行层级结构**：只允许换 `background` 与 `<include>` 顶栏，**不重排、不拆层**。
- **`NlText.*` 字重层级与 `sans-serif` 字体**；**线性图标体系**（`ic_*`）。
- **既有功能语义**：`selectedState=2`、`onNikonObjectAdded` 增量、`scheduleCaptureSync` 300ms 去抖、`scheduleAutoSync` 限幅、`LOAD_HARD_DEADLINE_MS`、`PAGE_SIZE=18`、`OBJECT_INFO_CONCURRENCY=4`、40 处 Toast 的文案与时序。
- **`elevation`/`stateListAnimator` 现状（0/@null）保持**：层次由 rim + 自绘阴影表达。
- **`bg_focus_box.xml`、`bg_grid_thirds.xml` 矢量取景辅助**：取景框不是容器，不玻璃化。
- **`bg_shutter_ring.xml` 的 `#FFC400`**：唯一彩色，原样保留。
- **`PressEffect` 的 100ms 按入时长与 0.95→0.97 的"手感基线"**：按入时间不改，只改幅度与联动属性。
- **17 个旧 `bg_*.xml` 不删除**：回退闸门依赖它们（§10.4）。

---

### 变更记录
| 日期 | 内容 |
|---|---|
| 2026-09-19 | 初稿：视觉规范 / 组件与页面映射 / 动效 / 适配 / 性能预算 / 兼容与可访问性 / 实现与验收，含 Q1–Q7 待确认 |
