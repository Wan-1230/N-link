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
| `glass_tint_content` | `#D9FFFFFF` (85%) | `#BF000000` (75%) | 背景为**图片/监看画面**且静止（顶栏、贴内容的面） |
| `glass_tint_floating` | `#8FFFFFFF` (56%) | `#8F000000` (56%) | **悬浮件专用**：dock / 分段胶囊 / 选择操作坞。v2.3 首版它们共用 85%，压在白页上等于不透明 —— 模糊与折射全被 tint 吃掉，走查因此反馈"看不到底下在动"（§15.5e D3） |
| `glass_tint_moving` | `#EAFFFFFF` (92%) | `#E6000000` (90%) | 内容**正在滚动/播放**时自动提浓 |
| `glass_tint_scrim` | `#99000000` | `#99000000` | 弹窗/BottomSheet 之后的全局遮罩（沿用现值 `liveview_scrim`） |
| `glass_tint_dark` | `#BF000000` | `#BFFFFFFF` | 反色卡（现 `dark_card`）玻璃化 |
| `glass_tint_solid_sel` | `@color/primary` | `@color/primary` | **选中态必须实体**，不用玻璃（状态可辨性优先于美观） |

规范细则：

- **静止 → `content`，运动 → `moving`**，切换用 150ms `FastOutSlowIn`，不做突变（突变看起来像闪屏）。
- ** tint 越透，越依赖 §9.3 的自适应兜底 **：悬浮件起手 56%，暗底（深色系照片）上会把文字压成低对比，
  所以 `guardedTint` 把加浓行程从 3 档放宽到 6 档 ×7%，仍不达标才整面退回不透明白底。
  这条是"透"与"可读"之间唯一的安全带，不许为了好看拆掉。
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
| `glass_stroke_outer` | `#2B000000` (17%) | `#1FFFFFFF` (12%) | 0.5dp，勾边界。**只描圆角面**：通栏面（顶栏/状态栏条）不描整圈，见下方细则 |
| `glass_rim_top_start` | `#66FFFFFF` | `#40FFFFFF` | 1dp 线性渐变起点（顶端） |
| `glass_rim_top_end` | `#00FFFFFF` | `#00FFFFFF` | 渐变终点（透明） |
| `glass_rim_bottom` | `#14000000` (8%) | `#26FFFFFF` (15%) | 1px，内透光。v2.3 首版是 26% 黑，通栏面上就是一条黑线 |
| `glass_edge_lens` | `#4DFFFFFF` | `#33FFFFFF` | **四角加强**的 1.5dp 亮线（折射假象的主要来源，§3.6） |
| `glass_shadow_key` | `#33000000` | `#0A000000` | **喂给 GPU 投影管线的 spot 着色**（`outlineSpotShadowColor`），系统还会按高度再衰减一层 |
| `glass_shadow_ambient` | `#1A000000` | `#0A000000` | 同上，`outlineAmbientShadowColor` |
| `glass_shadow_floating` | `#1F000000` | `#14FFFFFF` | 预留：L3 若需再分档时用（当前 L3/L1 共用上面两档着色） |
| `glass_elevation_l3` | 8dp | — | L3 悬浮件（dock / 胶囊 / 操作坞）。原 10dp，阴影边缘偏硬，降一档 |
| `glass_elevation_l1` | 1.5dp | — | 卡片抬升（`DepthLift`）。原 2dp |

规范细则：

- **通栏面不描整圈边**（v2.3.1 修正）。`radiusPx=0` 的贴边面（页面顶栏、状态栏条）如果照 §3.4 那条"四条边"的图纸画整圈 0.5dp 描边，落到屏幕上就是**上下两条黑横线** —— 走查第 3 条报的"滑动时标题框上下两条黑线（疑似阴影）"就是它，跟阴影无关。这类面走 `fullBleed`：只留内顶高光 + 一条内底反光，分隔职责交给它；状态栏条连内底反光都不画，避免和顶栏接出一条缝。
- **阴影用 `elevation` + token 着色**（v2.3.1 对本节原条文的推翻）。初版写的是"禁止用 elevation，一律双层自绘阴影"，理由是大圆角下矩形轮廓投影脏。实现期改成走系统的 GPU 投影管线（`ViewOutlineProvider` 给圆角轮廓 + `outlineSpot/AmbientShadowColor` 按 token 着色），实测比自绘更省（不进模糊帧预算）、且**着色可控** —— 脏的不是 elevation，是默认那层"纯黑×系统 alpha"。`stateListAnimator @null` 与按钮不抬升的既有意图**保留**：本条只放开 L1 卡片与 L3 悬浮件，按压反馈仍走 §3.5 的自绘 tint。
- **静止 → `content`，运动 → `moving`** 的 tint 规则不变；**层级只表达"可悬浮的东西"**：L2 行内小控件依然不抬升（靠 rim 与 tint 区分），免得整屏都在投影。
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
  **已落地（§15.5i）**：`glass_dock_max_width=480dp` + `screenWidthDp ≥ 600` 时 dock 收宽并 `bottom|center_horizontal`
  居中；compact 下仍是 `match_parent`，手机上几何逐像素不变。切换「经典外观」时宽度与 gravity 一并复位，
  不会留下"大屏的 480dp 黑条"。
- expanded 下顶栏玻璃可让位给通高侧栏，但**同一屏仍只允许 1 个主悬浮玻璃**。
  **侧栏（≥840dp 竖排导航）未做**：那是第四个布局 + 一套新的 dock 几何，在没有任何真机/模拟器可验的
  情况下开这块，性价比远低于它引入的风险（见 §15.5g 的验证记录）。
- 配额列随之更新：顶栏玻璃撤销后（§15.5h），主界面稳定态实际只有 **2** 面真模糊
  （dock + 相册胶囊），多选态 3。`glass_max_blur_surfaces` 仍写 4（上限，不是目标值）。

### 7.2 系统栏 / 刘海 / 手势（与 D2 绑定）
- 先做真 edge-to-edge：`WindowInsetsCompat` + `setOnApplyWindowInsetsListener` 统一分发；dock 底部 = `max(margins.bottom, 8dp) + 8dp`；顶栏加 `insets.top`。
- `windowLayoutInDisplayCutoutMode=shortEdges` 已有，保留；监看/预览页横屏时 dock 需避让左右 cutout。
- 键盘：`dialog_param_picker.xml` 的手动输入 `EditText` 需 `ime` insets 抬升，玻璃 sheet 抬升用 `SPRING_SHEET`，**不得让 sheet 底部仍为模糊**（会糊住输入框）。

### 7.3 字体缩放与放大显示
- 全 App 用 `sp`，`NlText.*` 保留 `maxLines=1 + ellipsize`（`styles.xml:10-11`）。
- **在 `fontScale ≥ 1.3` 时，所有 L2 玻璃胶囊从"固定高 + 单行"改为"垂直内衬 + 允许换行"** —— 否则玻璃按钮文字必被裁。写进 AC-11。
- `densityDpi` 变化（放大显示/接外屏）需作废纹理并重算 rim 像素宽度：`strokePx = max(1, round(dp × density))`，**避免 0.5dp 描边在 mdpi 上变成 0px 直接消失**。

### 7.4 触控目标与可达性
- **现值违规项**：顶栏 40dp 无边框图标按钮（`?attr/selectableItemBackgroundBorderless`）低于 48dp。
  **已修（§15.5i）**：5 处（设备页设置入口、相册页刷新/跳过已下载、预览页返回/更多）
  40dp → **48dp 命中框**，内衬 8dp → 12dp，**字形视觉尺寸一像素没变**（24dp glyph 不变），
  只是可点区域补足。标题是 `0dp + weight=1` 且有 `maxLines=1 + ellipsize`，加宽 16dp 不会挤破 320dp 窄屏。
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

## 十五、v2.3.0 第一包实施状态与对规范的修正

> 分支 `feat-liquid-glass-ui-v2.3`，基线 `54ba902`(PRD)。构建 `:app:assembleDebug` 通过。
> 本包范围 = **M0 的引擎与闸门 + M1 全量 + M3 的 dock/chips/操作坞 + M4 的监看 HUD**。
> 落点：新增 `shared/ui/glass/`（5 个文件）+ `res/values/glass.xml` + `values-night/glass.xml` + `res/values/ids.xml`。

### 15.1 实施中得到的四个事实修正（比规范更乐观的两处、更保守的两处）

| # | 规范原设想 | 实际 | 影响 |
|---|---|---|---|
| **C1** | §9.1 需要 T1/T2/T3 三档，且 **T1（Android 10/11）要单独设计一套"没有模糊"的视觉** | **不需要分层**。背景模糊改为 CPU 在 1/8 降采样纹理上施算（`BoxBlur` 三趟可分离），Android 10 与 Android 16 是同一条代码路径。分层只在两处仍有意义：窗口级 blur-behind（API 31）、未来的 AGSL 折射（API 33） | **省掉整个 T1 视觉分支**，也去掉 `RenderEffect`/`BlurMaskFilter` 两条后端；AC-2 的三档走查简化为"窗口模糊有/无"两档 |
| **C2** | §8.2 第 5 条：监看画面是 `SurfaceView`，需要 `PixelCopy` 异步抓取 | 取景画面实际是 **`ImageView`**（`fragment_liveview.xml:8 ivLiveView`），`source.draw(canvas)` 直接可采 | HUD 真模糊走通了与相册完全相同的路径，省掉 `PixelCopy` 的异步、时序、失败兜底三块工作（原估 M4 的主要风险 R4 消失） |
| **C3** | §9.4：系统「高对比度文字」开启时**自动**开启降低透明度 | ~~`AccessibilityManager.isHighTextContrastEnabled()` 不在公开 stub 里（javap 核对过 android-35.jar），只能反射~~ → **已闭环**（§15.5i）：那个设置项本身是公开的，`Settings.Secure` 的 `accessibility_high_text_contrast_enabled`，普通应用可读、无需权限、不用反射 | `UiFlags.reduceTransparency() = 用户开关 || 系统高对比度`，值走缓存 + `ContentObserver` 失效后原地重涂。AC-11 的"自动开降低透明度"恢复为可签项 |
| **C4** | §8.2 第 4 条：fling 期间零采集，靠 `OnScrollListener` 的 IDLE 结算 | ✅ **第二包已补齐**：复用相册页已有的快速滚动保护回调（`TransferFragment` 里 `adapter.setFastScrolling` 那处），加一行 `glassBackdrop?.setScrollSuppressing(fast)`，停住时补采一次。另给每个内容源单独的采集频率预算：监看 250ms、预览 120ms、相册 150ms 默认 | 关闭该偏离项，AC-6 的"列表帧预算不被玻璃吃掉"具备条件 |

### 15.1b 第二包：折射终于按 §3.6 落地（而且不需要 API 33）

`GlassLens.warp()` —— 圆角矩形 SDF + 二次衰减的边缘外推 + 中心微放大：

- 成熟实现（AGSL `RuntimeShader` + displacement map）要 API 33，本仓 minSdk 29 用不了；
  但我们**本来就为模糊持有一张 1/8 降采样纹理**，同一套数学直接在小纹理上跑即可。
- 一块玻璃面的 footprint 只有约 135×21 ≈ 2800 像素，一次重算几十微秒；
  结果按 `generation`（纹理换代计数）+ 区域缓存，**滚动时每帧只花一次位图 blit**，比 GPU 路径更省。
- 顺带的好处：**Android 10 与 Android 16 是同一个折射效果**，不必为低版本另设计一套视觉。
- 色散（RGB 三通道分别按不同半径取样）已实现但 `UiFlags.DISPERSION` **默认关**：
  它是彩色，与 `colors.xml:39` 冲突，等 §14 Q1 定了再开。

接入折射的面（`lens()` 只对"背后真有内容"的面生效）：相册多选操作坞、监看参数条与三个胶囊、
预览页上下工具栏。dock 与 chip 是 L2/L3 但背后是页面底色，参数照常给、当前不产生活跃纹理。

### 15.1c 第二包其余落点

| 项 | 状态 | 说明 |
|---|---|---|
| 预览页上下工具栏 | ✅ | `activity_preview.xml` 补 `previewTopBar`/`previewBottomBar` 两个 id；`vpPreview` 作纹理源，**换页时 `invalidate()`** 否则背景会停在上一张 |
| 拍摄页照片/视频切换器 | ✅ | 「槽=L2玻璃、滑块=实体」（§4 分段控件规范）。这是 M2 组件合并之前的过渡做法 |
| 材质热更新 | ✅ | `material` 改为带 setter 的属性，换材质时作废透镜与对比度缓存，避免开关后拿旧半径继续画 |
| 通栏半径取 0 | ✅ | 贴屏幕边的通栏做圆角会像"漏涂"；预览页两面用 `radiusPx = 0`，层级感交给 tint + 模糊 + 内顶高光 |


### 15.2 已落地清单

| 项 | 状态 | 落点 |
|---|---|---|
| 材质 token（半径/模糊/tint/四边信息/阴影/配额常量） | ✅ | `values/glass.xml` + `values-night/glass.xml` **逐项成对**（顺手补掉 D3 的一半风险） |
| 玻璃绘制器：tint + ①外描边 ②内顶高光 ③内底反光 ④四角透镜亮线 | ✅ | `GlassSurfaceDrawable`。绘制路径**零分配**（shader 在 `onBoundsChange` 重建） |
| backdrop 采集器（1/8 缩放、长边 ≤320px、单实例、复用位图） | ✅ | `GlassCoordinator` + `BoxBlur`。一个内容源一张纹理，多面玻璃共用 |
| 对比度自适应（WCAG AA 4.5:1，加浓 3 档后退回不透明白底） | ✅ | `GlassSurfaceDrawable.guardedTint`，复用采集时的 `avgLuminance`，结果带缓存 |
| 回退闸门 | ✅ | `UiFlags`（`CLASSIC`/`REDUCE_TRANSPARENCY`/`BLUR`/`MOTION`），照抄 `ConnFlags` 的 prefs 与总闸形态 |
| **M1：27 弹窗 + 3 sheet 真模糊** | ✅ | `NlGlass.dialog/sheet`；`GlassAlertDialogBuilder.create()` 挂钩，所以 `.show()` 与 `.create().show()` 两种写法都覆盖。玻璃关闭时显式回落 `NlDialog` 原主题 |
| **dock 悬浮玻璃** | ✅ | `MainActivity.styleDock()`。**几何与材质一起切**：关闭时高度/内缩/分割线逐项还原（AC-12 要求逐像素相等） |
| chips（相册筛选 + 设备页 STA 子模式） | ✅ | `renderChipBackground()`。选中态保持实体填充；从注册表摘除，避免开关重涂把实心盖回玻璃 |
| 相册多选操作坞 | ✅ | 以 `gridPhotos` 为 backdrop 源 —— 本 App 里"背后有可透之物"最典型的一处 |
| 监看 HUD（参数条 + 三个胶囊） | ✅ | 以 `ivLiveView` 为源。**圆形图标按钮刻意没做**：圆角矩形材质会把圆画成圆角方块 |
| 设置页两个开关 | ✅ | 「液态玻璃视觉」「降低透明度」，`recreate()` 兜住常驻 Fragment 的一次性上材质 |

### 15.3 仍未做（v2.3.1 校对过，别照旧清单找）

> 这一节原来那份清单已经过期（写着「dock 未压进内容区、刻意不做」「顶栏滚动联动待 M2」，
> 而这两件事在后面的包里都做了）。下面按**当前代码**重写。

- **M2 结构合并（做了一半）**：顶栏这块从"每页各写一遍标题行 + 分割线"收敛到
  `topChrome` + `GlassChrome` + `GlassTopBar` 三件套（设备/设置两页已套，相册页未套），
  分段控件统一走 `GlassMotion.slidePill`。剩：把三件套抽成一个 `<include>` 布局单元
  （现在只是"三个页面引用同一批类"，还不是同一个组件）、重复 item 布局合并。
- **相册页覆盖式顶栏未做**：它顶部是 5 件 chrome 叠着（标题行 / 分割线 / 筛选 chip 行 /
  下载统计 / 进度条），其中两件按状态显隐，覆盖式要跟着算的是动态高度而不是一块固定板。
  做法与设备页同构，但得在真机盯住下载态才知道高度对不对 → 等有验证窗口再动，不盲改。
- **传输期降级**（§8.4 第 2 条，`bulkDepth > 0` 时 HUD 退静态 tint）：要读
  `PtpSessionManager` 的计数，属用户未提交的连接层文件，按"不碰未提交改动"的约定跳过。
  当前用监看页 250ms 采集预算兜住大部分开销。
- **D2 真 edge-to-edge（全局）**：`themes.xml:26` 的 opt-out 只在 MainActivity 逐窗解除，
  其余 Activity 仍走旧路；targetSdk 36 前必须做完（§9.6 的外部 deadline 未变）。
- **M5 动效弹簧**（`SPRING_*` 全面接入、深度转场）、**M6 大屏** `w600dp/w840dp`、
  `values-night` 其余 token 补齐、连续曲率圆角、sheen/tilt、`EXIF` sheet 组件化、
  `NlFeedback` 收 40 处 Toast。
- 设备页深色 hero 卡：**主动放弃**。纯白页底上把 `#000000` 反色卡改成半透明会变灰，
  违反 §1.2 三判据第一条。圆形图标按钮同理不做玻璃（圆角矩形材质会把圆画成圆角方块）。

### 15.4 验收项状态（v2.3.1 校对）

**可验**：AC-1（材质一致性：全部走 `GlassTokens` 工厂，无手拼参数）、AC-3（弹窗/sheet 真模糊 + 27 处行为零变化）、AC-5（对比度自适应，需构造"预览页全白图"复验）、AC-6（滚动期采集降频不冻采 —— v2.3.1 改了口径，见 §15.5d B3）、AC-8（按压与拖选共存 —— 未改 `PressEffect`，冲突面为零）、AC-12（回退闸门：几何 + 材质 + `clipToPadding` 三样都逐项还原）。
**AC-4（配额）**：debug 构建现在有硬检查 —— `GlassCoordinator.assertBlurQuota()` 在登记面时数"真在采背景"的面，超 `glass_max_blur_surfaces` 就在 logcat 报一条（不 crash，测试包崩在用户手里比超预算更糟）。配额从 3 放到 4：v2.3.1 把相册页分段胶囊提成悬浮玻璃之后，主界面稳定态就占 3（dock + 顶栏底片 + 胶囊），多选态再加一面操作坞。
**尚不可签字**：AC-2（需三台不同 Android 版本真机）、AC-7（传输期降级未做，卡在用户未提交的连接层文件）、AC-9（功耗需真机 `batterystats`）、AC-10（Monkey/旋转压测）、AC-11（**高对比度自动触发已闭环**、**触控目标已补足 48dp**；剩 TalkBack 实走与 `fontScale 1.3/2.0` 无裁切需真机）、AC-13（**dock 480dp 收宽已做**；≥840dp 侧栏与 2–3 列未做）。

> 里程碑对照：M0 ✅（除 D2 全局）、M1 ✅、M2 ⏳ 一半（顶栏三件套 + 分段控件已归一，`<include>` 组件与 item 布局未做；注：顶栏玻璃已于 §15.5h 撤销，"归一"现在指的是干净统一的一行标题 + 分割线）、M3 ✅、M4 大部分 ✅（监看 + 预览，圆形图标按钮除外）、M5 ❌、M6 ⏳（三开关 ✅、配额断言 ✅、高对比度自动触发 ✅、48dp 触点 ✅、大屏 dock 收宽 ✅；≥840dp 侧栏 ❌、fontScale 换行 ❌、三档真机走查 ⏳）。


---

## 十五·续：第三包（dock 悬浮进内容 / 自动隐藏 / 弹窗动效 / 指示器液态滑动）

> 用户走查后的反馈驱动的第三包。构建 `:app:assembleDebug` 通过。

### 15.5 这一包做了什么

| # | 反馈 | 根因（代码级） | 处理 |
|---|---|---|---|
| **F1** | 底部导航条没延伸进内容区、看不到透景 | ① `activity_main.xml` 根是**竖向 LinearLayout** + `fitsSystemWindows` → 内容容器在 dock 之上就结束了，dock 背后只有页面底色；② dock 从没拿到 `GlassCoordinator` | 逐窗 `setDecorFitsSystemWindows(false)`（**只改 MainActivity**，其他页仍受 `themes.xml` opt-out 保护，避开 R5 全局 insets 回归）；容器用**负 bottomMargin** 多占一条 dock 高度；dock 的纹理源 = `fragmentContainer`。**⚠ 已被 B1 推翻**：负 margin 在竖向 LinearLayout 里要靠父级权重算术生效，实测内容并没有真的铺到底 → dock 底下和它下面一条全是白的。根布局已换成 FrameLayout + dock 覆盖层 |
| **F2** | dock 背景里有一个多余白条 | **三条白底叠在一起**：① dock 自己的 `@color/nav_background` 不透明；② `fragment_transfer.xml` 的 `albumTabRow` 带 `@color/background` 实心底（贴在 dock 上方，视觉上就是 dock 后面一条白带）；③ 非 edge-to-edge 留下的系统导航条白底 | ①→玻璃材质（T2+ 由 tint/rim 承担分隔）；②→改 `transparent` 并让 `albumTabRow` 自己也成玻璃面（透过它看得见网格在糊）；③→`statusBarColor`/`navigationBarColor` 透明 + 状态栏条玻璃底 |
| **F3** | 内容会不会被悬浮 dock 盖住 | 悬浮后内容区变长，各页底部 chrome / 列表末项会躲到 dock 底下 | 新增 `GlassInsetAware` + `DockInset`：三个可滚动页（设备/相册/设置）把 dockSpace 叠成 `paddingBottom`（配 `clipToPadding=false`，这正是"内容滚到玻璃底下"的前提），相册的分段标签行用 `bottomMargin` 抬起来。**首次构造时捕获原值**，所以「经典外观」下逐值还原（AC-12） |
| **F4** | dock 自动隐藏 | 无 | ~~上滑（内容向前浏览）下沉隐藏、下滑或**点一下**回弹、3.8s 无操作自动收起~~ **已整条撤销**（见 §15.5d B2）：悬浮玻璃的分寸感来自"它就固定在那儿，内容从它底下过"，dock 自己再跟着动，两层位移叠在一起读作抖动而不是流动，而且隐藏态下 dock 采到的纹理是过期的一帧 |
| **F5** | 弹窗与背景模糊不同步、动画生硬 | MDC 自带转场只动面板，而 `blurBehindRadius` 与 `dimAmount` 是**开局即满值** → 背景先糊了、面板后才飘进来 | `NlGlass.applyBlurBehind`：关掉窗口自带转场，把**模糊半径 / 暗度 / 面板 alpha / 0.94→1 缩放**挂在同一条 `ValueAnimator`（220ms，emphasized 曲线）上一起推进。BottomSheet 自己会上滑，故只让它同步模糊与暗度（`animatePanel=false`），避免双重动画 |
| **F6** | 开关重开闪屏 | 设置页用 `activity.recreate()` 让常驻 Fragment 重上材质 → 整页销毁重建，先闪一帧 `windowBackground` 白底 | 删掉 `recreate()`，改为 `UiFlags.observe(owner){}` **原地重涂注册表**（按 owner 做键，`unobserve` 精确摘除）。各页在 observer 里重涂 chips/操作坞/分段行并重算内衬 |
| **F7** | 分段控件切换生硬 | 只有 `translationX` + 固定时长 + `Decelerate`，位置到位就停 | `GlassMotion.slidePill`：位置**轻微过冲** + 途中 `scaleX` 拉伸 18%、`scaleY` 压扁 6%，落位弹回 —— 让胶囊像一滴有表面张力的液体而不是贴纸。已接相册三 tab 与设备页三种连接方式 |

**新增能力位**：`UiFlags.LENS`（折射，默认开）、`UiFlags.DISPERSION`（色散，默认关 —— 灰阶规范）。
**新增纹理源**：`fragmentContainer`（dock）、`gridPhotos`（操作坞 + 分段标签行）、`ivLiveView`（监看 HUD）、`vpPreview`（预览工具栏）。同屏仍是"一个内容源一张纹理"。

### 15.5b 第四包：顶栏随滚动浮现（§4 顶栏项 / §6.5 滚动联动的可达成）

`GlassTopBar` + `GlassSurfaceDrawable.plateAlpha`。两个实现期发现的坑，记下来免得重踩：

1. **不能用 `View.alpha` 淡顶栏** —— 那会把标题和图标一起淡掉。所以给 drawable 加了
   `plateAlpha`（只作用在底片的 tint/rim/描边上），另加 `applyGlass(register=false)`：
   顶栏不进 `GlassRegistry`，否则经典外观下重涂会画出一块 v2.2 并不存在的白底。
2. **`ScrollView` 的 `oldScrollY` 本来就是上一帧的值**，`scrollY - oldScrollY` 直接可用，
   不需要自己记 `lastScrollY`（第一版我多此一举加了字段，已删）。

**诚实地说清楚做到了哪一步**：设备页与相册页的顶栏**不是覆盖式** —— 设备页顶栏底下压着连接模式三选行、
相册页压着筛选 chip 行，所以列表内容不会真的从标题底下穿过。这里实现的是 iOS 实际给用户的两件事：
底片随滚动从全透明浮到不透明、标题轻微上移 + 缩到 0.92（不消失，保留定位感），
并把 1px 分割线交给玻璃的四条边信息。设置页的 `ScrollView` 直接跟在头部下面，
是三者里唯一"内容真从标题底下滚过"的页面。要做成前两页那样，需要把页面根换成
FrameLayout + 覆盖式顶栏，属结构改造，仍未做。

`GlassMotion.slidePill` 已接相册三 tab 与设备页三种连接方式（过冲 + 途中横向拉伸 18%）。
**拍摄页照片/视频那处仍没接**：它现在不是滑动指示器而是直接换实心底，
必须先做成真指示器（M2 组件化）才谈得上液态滑动。


### 15.5c 第五包：收尾反馈里的三条（滑块 / 沉浸渐隐 / 立体感）

| 项 | 做法 | 关键取舍 |
|---|---|---|
| **拍摄页照片/视频切换不再硬切** | `modeToggleSlot` 换成 FrameLayout，选中态从"给标签换实心底"改成**背后一枚真滑块**，位移与宽度跟着目标标签走，接 `GlassMotion.slidePill` | 滑块在**两种外观下都是实心** `bg_chip_selected`（选中态必须实体，§3.3），两态只差"有没有动画"，所以经典外观天然等价于 v2.2。标签是 `wrap_content`，首帧宽度为 0 → `post` 一次并以 `animated=false` 归位，避免进页面时滑块从左边飞一下 |
| **预览页沉浸浏览**（§5.3） | 单击画面 → 上下工具栏与系统栏同步渐隐/浮现 | 用 `onSingleTapUp` 而不是 `onSingleTapConfirmed`：本页没有双击缩放，不必等 300ms 判定。出现 220ms `Decelerate`、消失 180ms `Accelerate`（**消失比出现快**，§6.3）。只在玻璃态生效，经典外观下工具栏常驻，不改既有交互 |
| **卡片立体感**（§3.4；"玻璃不明显处至少给阴影"） | `DepthLift`：这些面背后是页面底色，做真玻璃等于什么都透不出来（§1.2 三判据），所以改给**真实 GPU 软阴影** | 认卡片的方式刻意保守——**圆角恰为 `card_radius`(12dp) 的矩形 GradientDrawable**，于是 `bg_card`/`bg_card_dark` 命中，chip(20dp)、格子(8dp)、已上玻璃的面（非 GradientDrawable）都不命中。前提已核对：**全仓 `res/layout` 里 `android:elevation` 命中 0 次**，所以"经典外观 → 归零"是逐值还原（AC-12）。阴影走 `ViewOutlineProvider` 默认取 background 轮廓，圆角卡片自动得到正确的圆角投影，不需要自绘，也不进模糊帧预算 |

### 15.5d 第六包：dock 真悬浮的三处修正（走查反馈：悬浮是假的）

反馈原话：「导航栏覆盖的地方和下边都是空白的」，要求**去掉随滚动移动**、**钉死在底部**、**看得见内容从底下划过**。三处根因，全部在代码级：

| # | 根因 | 处理 |
|---|---|---|
| **B1** | **几何是假的**。`activity_main.xml` 根是竖向 `LinearLayout`，dock 靠内容容器的**负 bottomMargin** 挤进内容区 —— 加权子 View 的负 margin 要经过 `measureVertical` 的剩余空间算术，实测内容没有真的铺到屏幕底，于是 dock 那一条和它下面到屏幕底的一条都是页面底色 | 根换成 **FrameLayout**：`contentColumn`（状态条 + 容器 + 分割线）`match_parent` 铺满，dock 作为**同级覆盖层** `layout_gravity="bottom"`。两种外观都靠它，不再有任何 margin 魔术 —— 玻璃态 `contentColumn.paddingBottom=0`（内容通到底），经典态垫 `dock 高度 + navInset`（逐值等于 v2.2，AC-12） |
| **B2** | **位移是多余的**。自动隐藏（上滑下沉 / 点按回弹 / 3.8s 收起）让 dock 自己也在动，两层位移叠加读作抖动；隐藏态下 dock 采到的还是过期纹理 | **整条删除**（`reportContentScroll`/`showDock`/`hideDock`/`animateDock`/空闲计时/`dispatchTouchEvent` 的 tap 判定一并移除），`dockSpace()` 从"当前位移量"变成常量 = dock  footprint（高度 + 四周内缩 + navInset）。对齐 iOS 26 悬浮标签栏与 Material3 边到边导航的事实做法：**栏固定，动的是内容** |
| **B3** | **纹理是定格的**。`GlassCoordinator` 只在 `mapRect` 失败时请求重采，页面滚动既不 invalidate dock、dock 也不 invalidate 自己 → 背景从头到尾是进页那一帧的快照，"划过"根本无从谈起。相册页更糟：fling 期间 `setScrollSuppressing` **完全停采** | ① 新增 `requestRefresh()`，三个可滚动页在各自的滚动回调里驱动重采（采集本身按 `minRefreshMs` 节流，不会每帧一次）；② `setScrollSuppressing` 的语义从"冻采"改成"**降频**"：滚动期 70ms（≈14fps），静止期 150ms，停住时补采一次对齐位置。一张 1/8 纹理连采带糊 ≈1~2ms，占空比 ≈3%，换来的是背景跟着内容走 |
| **B4** | 相册页的网格被约束在标签行**之上**（`swipeRefresh` → `bottomToTopOf=albumTabRow`），所以那块最该有透景的地方压根没有内容进过 dock 底下 | 玻璃态把 `swipeRefresh` 底边改锚 parent、网格 `paddingBottom` 连标签行那一条一起让出（`clipToPadding=false` 早就备着）；经典态锚回 `albumTabRow`，保持 v2.2 的"网格止于标签行之上" |
| **B5** | 同屏两张纹理：标签行/操作坞采 `gridPhotos`、dock 采 `fragmentContainer`，两糊度不同步，上下紧挨着接缝一眼可见 | 统一到一个内容源（`fragmentContainer`），`TransferFragment.glassBackdrop` 改取 `MainActivity.dockBackdrop` |

**没有一并做的事**：拍摄页 dock 底下仍是空白 —— 那里背后本来就没有可透之物（§1.2 三判据），把监看画面拉到底下属于页面结构改造，不是这次的 bug。

### 15.5e 第七包：阴影与悬浮件走查（v2.3.1）

第二轮真机走查的 4 条反馈，逐条落到代码级根因：

| # | 反馈 | 根因 | 处理 |
|---|---|---|---|
| **D1** | 阴影生硬、不协调 | ①`glass_shadow_*` 三个 token 定下来之后**从来没被接进渲染**（附录 A 里有、代码里没人读），实际用的是系统默认"纯黑 × 高度衰减"那套；②L3 10dp 对一枚 62dp 的药丸偏重，卡片 2dp 在浅底上贴边成一条硬灰边 | `View.applySoftShadow()`：`outlineSpotShadowColor`/`outlineAmbientShadowColor` 按 token 着色（API 28+，本仓 minSdk 29 直接调），`applyGlass` 与 `DepthLift` 都接上；L3 10→8dp、L1 2→1.5dp；`glass_rim_bottom` 26%→8% 黑 |
| **D2** | 相册页底部三个选项要"和导航栏一致"：悬浮、去白条、玻璃化 | 旧实现给整行铺了一层通栏 flat 玻璃，**里面又压了一枚 `bg_chip`（#F0F0F0 实心）** —— 那层实心底就是走查里的"白条"，玻璃被它盖得一点透不出来 | 玻璃面从"整行"挪到**那枚 40dp 药丸本身**（`albumTabPill`，新增 id）：`GlassTokens.tabPill()` = dock 同套材质（真模糊 24dp + 折射 + L3 抬升），半径跟着高度收成 r20 整圆端，左右内缩改用 `glass_dock_inset_h`(12dp) 与 dock 对齐 → 上下两枚同语言的悬浮件。实心底只留给经典外观（AC-12：`setPaddingRelative(20dp)` + `bg_chip` 逐值还原） |
| **D3** | 悬浮件要半透明，滚动时看得见底下的模糊内容在动 | `dock`/`floatingBar` 用的是 `glass_tint_content` = **85% 白**，压在白页上等于不透明，模糊与折射全被 tint 吃掉 | 新增 `glass_tint_floating`（日/夜均 56%）给 dock、分段胶囊、选择操作坞；配套把 `guardedTint` 的加浓行程从 3 档放宽到 6 档 ×7%（暗照片上仍守 WCAG AA 4.5:1，兜底退回不透明白底不变） |
| **D4** | 滑动时标题框上下两条黑线（疑似阴影） | **不是阴影**。是 §3.4"四条边"图纸落到 `radiusPx=0` 的通栏面上：整圈 0.5dp × 20% 黑描边在贴屏幕边的顶栏上就是上下两条横线，状态栏条再叠一条底反光 → 标题框被框成一条灰带 | `GlassMaterial.fullBleed`：通栏面跳过整圈外描边与四角透镜，只留内顶高光 + 一条更轻的内底反光；`statusStrip` 连内底反光也不画（否则与顶栏接出一条缝）。见 §3.4 细则 |
| **D5** | 标题缩放动画生硬 | `settle()` 是**逐帧硬赋值**：进度线性映射滚动偏移（起始几像素就跳一大截）、fling 中每帧被硬拽、落位时又突然补满 | `GlassTopBar` 改 **smoothstep 映射 + 追帧平滑**（每帧向目标推进 22%，收敛后 `postOnAnimation` 自动停）；幅度收小：缩放 0.92→0.955、上移 6dp→4dp（标题是定位锚，缩狠了读作"被压下去"）。关闭动效开关时一步到位，不追帧 |
| **D6** | **采集自反馈**（顺手挖出来的，不修则 D3 立刻翻车） | dock 与相册页悬浮件的纹理源换成 `fragmentContainer` 之后，玻璃面**自己就在被采集的子树里** → 每采一次把上一帧的自己糊进去，越采越浓，看久了像玻璃起雾 | `GlassSurfaceDrawable.suppressedDraw`：`refreshNow()` 采集期间让所有登记过的面自我屏蔽（`draw()` 直接 return），那几块在纹理里画成父层底色，采到的才是它们身下的真背景。零额外布局/绘制轮次 —— 比 `setVisibility(INVISIBLE)` 那套做法便宜（后者每次采集多一轮 requestLayout） |
| **D7** | **新采的纹理没人重画**（同上，顺手挖出来的） | `GlassCoordinator.hosts` 从头到尾**没有一处登记**：`refreshNow()` 遍历它回调重绘，但 `applyGlass` 只 `reportBlurNeed`+`invalidate` 过一次 → 采到新背景之后没有任何面会被叫回来画，玻璃实际停在第一帧 | `applyGlass` 里 `coordinator.addHost(surface)`（弱引用 + 去重 + 64 上限）。这条补上之后 §15.5d B3 的滚动驱动才真正闭环 |

**这一包顺带纠正了两处"看起来实现了其实没接上"**：D6/D7 都是上一包（统一纹理源 + 滚动驱动采集）落地时才暴露的链路缺口 —— 单独看代码像已经工作，真机上是"玻璃不糊 / 玻璃起雾"。测试必须上真机，编译通过不代表视觉链路通。



### 15.5f 第八包：覆盖式顶栏（§15.6 第 1 条）+ AC-4 配额断言

顶栏这块玻璃从前几包起就"淡入了一块什么都没遮的白底" —— 三个页面的标题区都是竖向排列
（顶栏 → 分割线 → 滚动区），列表内容**永远不会经过标题底下**，所以 §4 承诺的"滚动时标题栏
玻璃化"实际只是"标题栏换了个会淡的半透明白"。这一包把它变成真的。

| 项 | 做法 | 关键取舍 |
|---|---|---|
| **结构** | 设备页 / 设置页的根从竖向 `LinearLayout` 换成 `FrameLayout`：滚动区满高，`topChrome`（标题行 + 分割线 + 设备页的连接模式三选行）作为覆盖层压在上面。滚动区的 `paddingTop` 由 `GlassChrome` 按 `topChrome` **实测高度**给（首次布局量不到 → post 重试） | 底片挂在**整块 topChrome** 上，不是只挂标题行：设备页标题行下面就是模式三选行，分开做会在两行之间露出一条会跟着内容移动的缝 |
| **回退闸门** | 玻璃态 `clipToPadding=false`（内容从标题底下滚过）；经典外观 `clipToPadding=true`（内容在 padding 边裁掉）+ 底片不注册、背景清空、分割线恢复 | 这是 AC-12 的关键一环：覆盖式布局下若 `clipToPadding` 不跟着切，经典外观会露出"内容压在透明顶栏底下"的穿帮。`View` 只有 `setClipToPadding` 没有 getter，所以这个开关由**调用方**在自己的具体类型上设，`GlassChrome.apply` 只返回该不该裁 |
| **真模糊** | `topChrome` 的 `applyGlass` 第一次拿到 coordinator（内容列那张纹理） | 之前材质里 `blurDp=24` 是写了等于没写 —— 没源就不采。顺带说明 §15.5e D6/D7 两个 bug 为什么必须同包修：顶栏在内容列**子树里**，不自带屏蔽就会自反馈起雾；hosts 不登记就没人重画 |
| **下拉刷新** | `GlassChrome` 顺手把 `SwipeRefreshLayout.setProgressViewOffset` 整体下移一条 chrome 高度（默认 20/64dp 是相对容器顶边的，容器现在铺到屏幕顶） | 不抬的话转圈动画会藏在覆盖式顶栏底下，表现为"下拉了但看不到进度" |
| **AC-4** | `GlassCoordinator.assertBlurQuota()`：debug 构建下数"真在采背景"的面（有源 + 材质生效 + `blurDp>0`），超 `glass_max_blur_surfaces` 报一条 `Timber.e`，一次进程只报一次 | 不 crash：测试包崩在用户手里比超预算严重得多。配额 3→4，理由写在 token 注释里（悬浮对 + 顶栏底片是 v2.3.1 的新基线，多选态 4 面） |


### 15.5g 第九包：顶栏采样跟随 + tint 档位粘滞 + dock 跨页残留

三条都是"上一步修完才暴露"的下游问题，根因各不同：

| # | 症状 | 根因 | 处理 |
|---|---|---|---|
| **G1** | 设备页顶栏玻璃背景错位、采样区不跟随滚动 | 纹理 150ms 才重采一次，而底片 alpha 与内容都在 60fps 动 → 背景是**一格一格的幻灯片**。**不是**没接纹理源（第八包接上了），是刷新节奏跟不上 | 列表滚动是刚体平移：某块玻璃底下此刻该有的背景，就是**上一张纹理里偏移 Δ 的位置**。于是把每帧 `dy` 累计进 `GlassCoordinator.shiftY`，`mapRect` 里直接平移采样窗口 —— **零额外采集、零额外帧预算**。钳制时保持窗口高度，所以贴底的 dock 自动不动（它要的新内容还没进视口，那张纹理里根本没有），贴顶的顶栏会跟着滚 —— 恰好各是其正确的行为。重采只负责纠正残余漂移（换行/插入/下拉刷新位移），自愈 |
| **G2** | 顶栏"闪烁 / 玻璃在呼吸" | 和 G1 是两件事：`guardedTint` 每次都用**整屏平均亮度**重新决定 tint 档位，滚动时平均亮度大幅摆动 → tint 在 56%↔70%↔56% 之间来回跳 | 亮度做 EMA 平滑（0.75/0.25）+ **档位粘滞**：已选档只要还达到 4.2（AA 4.5 留 0.3 容差）就不换档。只修 G1 不修这条照样闪 |
| **G3** | 某个页面激活玻璃后切到别的页，dock 还挂着上一页的模糊 | 切页只 `invalidate()` 一次，而那一刻正处在 250ms 淡入淡出**中间**，画进纹理的是"上一页淡出 + 下一页淡入"的叠加态；动画结束后没有任何人再采 → 残留定格 | 过渡结束后（320ms）补采一次。另外离开相册页时复位采集态 —— fling 中途切走会把"滚动中"的降频档一直带到别的页面（`onHiddenChanged` 里 `setScrollSuppressing(false)`，顺带触发一次对齐重采） |

**验证状态（必须写清楚）**：这一包的三条修复**没有跑通自动化视觉验证**。为它装了整套模拟器（`emulator` + `system-images;android-35;google_apis;x86_64`，4.6GB，`-accel-check` 报 WHPX 可用），结论是本机这个会话起不来：GL 初始化失败（`Software OpenGL failed. Falling back to system OpenGL` + D3D11 设备创建两次回退），qemu 进程能拉起来但 CPU 停在 ≈0.2%（VM 实际没在跑），控制台端口 5554/5555 从未监听，adb 始终看不到设备。**换 `-no-window`/`-gpu off`/`-read-only` 组合都是同一结局**（前者直接静默退出）。

所以 AC-2（三档 Android 版本真机）、AC-9（功耗）、AC-10（Monkey/旋转）、AC-11（TalkBack）以及所有视觉签字项，**仍然只能用真机**。可行的替代路径：手机开 USB 调试并授权这台电脑，`platform-tools/adb.exe` 已就位，那我就能按下面的矩阵自动跑并截图判读 ——

1. 设备页分段滚动：顶栏底片区域在两个滚动位置各自的截图，与"经典外观"同位置对照（判据：跟随连续、无鬼影、无硬线）。
2. 相册（深色照片压底）↔ 设置（白底）来回切，各等 600ms 后取 dock 矩形**平均亮度**：切过去之后应当翻转（残留判据，数值化，不靠眼睛）。
3. 四个 Tab + 弹窗 + 监看 HUD 全状态截图目视，`logcat` 过 AC-4 配额断言与异常栈。



### 15.5h 第十包：顶栏玻璃整条撤掉 + 主行动点升级 + 换页改同步

design-review 走查后的四条。第一条是对我上一包（§15.5f 覆盖式顶栏 + §15.5g 采样跟随）的**方向性否决**：
修到第十包，顶栏玻璃的收益仍然是零（白页上透出白），代价却一直是真的（采样慢一帧 → 错位、闪烁、
切页残留）。所以按"若无法稳定修复就移除"处理，并且移除得干净：

| # | 改动 | 根因/取舍 |
|---|---|---|
| **H1** | **四个位置的顶栏玻璃全部删除**：设备页/设置页的 `topChrome`、相册页的 `topBar`、以及 MainActivity 的状态栏条，一律恢复 `@color/background` 不透明底 + 1px 分割线，两种外观（液态玻璃/经典）下**完全一致** | 残留与错位在结构上不可能再发生 —— 没有背景采样、没有 alpha 动画、没有覆盖层透出。连带删掉 `GlassTokens.topBar()` / `statusStrip()` 两个工厂、`GlassMaterial.fullBleed` 与 `GlassSurfaceDrawable.plateAlpha`（都只为通栏底片存在，不留死配置）。`GlassTopBar` 只剩标题让位（上移 4dp + 缩到 0.955，追帧平滑），那是与材质无关、已经验过的手感。覆盖式布局**保留**（滚动区满高 + 顶栏压上），`clipToPadding` 两种外观都是 `false`，行为等同普通 Toolbar |
| **H2** | 「支持开发 · 请喝杯咖啡」提为设置页唯一主行动点：60→68dp、18dp 圆角新 `bg_cta_primary`（原 `bg_card_dark` 12dp 与卡片同档）、内边距 18→22dp、图标 20→24dp、文字 15→17sp、次要箭头降到 `on_dark_card_variant` | 拉开的是**两档**而不是一档：常规行是 52~60dp / 12dp 圆角 / 白底描边。对比度实测：白字黑底 21:1，次要箭头 8.3:1，都远超 AA。窄屏防溢出用 `maxLines=1 + ellipsize`（权重挤不下时截断） |
| **H3** | Tab 切换从「250ms 交叉淡出 + 10px 位移」改成**同步换页、零动画**（`commitNow` + 去掉 `setCustomAnimations`，两个 anim 资源删除） | 旧页在屏幕上多留 250ms 就是"点了没反应"的直接来源；覆盖式内容区下淡入还会透出白底，更像闪。`dockBackdrop` 的补采时机随之从 320ms 收到 160ms（不再是等动画结束，只是给目标页一次布局），`invalidate()` 仍然立即采一次 |
| **H4** | dock 选中项加一枚**胶囊底**（`bg_nav_selected`，四周内缩 6/8dp，日 `#14000000` / 夜 `#26FFFFFF`） | 之前选中态只有"实心图标 + 加粗"，压在 56% 白玻璃上层级不够 —— 看不出当前在哪个页。实测：最暗背景（L=0.05 的照片）下 dock 面 #95、黑字 7.0:1，加胶囊后 #89、黑字 6.0:1，**AA 仍然过**，所以不需要 `guardedTint` 提浓、半透明目标不被牺牲。按压时胶囊跟着图标一起缩放（`PressEffect` 作用在整个栏位） |

**顺带记一笔未做的（超出本次四条的范围，留给用户决定）**：未选中图标 `#9E9E9E` 压在白 dock 上是 2.68:1，
低于 WCAG 对"有意义图形"的 3:1 —— 这是 v2.2 就有的值，不是这次引入的。改到 `#8A8A8A` 约 3.4:1 即可达标。

**AC-12 回退闸门的口径变化（必须写明）**：顶栏现在两态一致（不再参与回退差异）；设置页主行动点与
dock 选中胶囊在**经典外观下也是新的**（阴影/尺寸/圆角/胶囊底）。所以"经典外观 == v2.2 逐像素相等"
这条对这三处不再成立 —— 是本轮明确要求的结果，不是回归。玻璃面数量随之下降：主界面稳定态从
3 面真模糊降到 **2**（dock + 相册胶囊），配额断言仍按 4 报。



### 15.5i 第十一包：把不依赖视觉判断的 PRD 条目清掉（a11y + 大屏 + 触点）

顶栏玻璃撤掉之后，剩下的 PRD 项里有一批**不需要肉眼看效果**就能定对错的 —— 在"本机跑不了模拟器、
只能等用户真机"的窗口期，先把这些关掉，把只能靠眼睛的项留给真机（分工而非拖延）：

| 项 | 做法 | 为什么可以盲改 |
|---|---|---|
| **C3 → AC-11 自动高对比** | `UiFlags.reduceTransparency() = 用户开关 \|\| 系统高对比度`。走 `Settings.Secure` 的公开设置项 `accessibility_high_text_contrast_enabled`（无需权限、**不反射**），值缓存 + `ContentObserver` 失效后 `notifyChanged()` 原地重涂 | 是行为契约不是观感：开了系统高对比度 → tint 不透明、模糊关。判定只读一个布尔 |
| **§7.4 → AC-11 触点** | 5 处顶栏无边框图标按钮 40dp→48dp 命中框，内衬 8→12dp（glyph 仍 24dp，视觉零变化） | 尺寸是 XML 常量，可静态核对；宽出 16dp 由 `weight=1 + maxLines=1 + ellipsize` 的标题吸收 |
| **§7.1 → AC-13 dock 收宽** | 新 `glass_dock_max_width=480dp`；`screenWidthDp ≥ 600` 时 dock 取 `min(屏宽-2×内缩, 480dp)` 并 `bottom\|center_horizontal`；compact 保持 `match_parent` | 断点是配置量，可算不靠看。经典外观下宽度与 gravity 一并复位，避免"480dp 黑条留在通栏 dock 上"这类跨态残留 |
| **§15.6-1 相册页覆盖式顶栏** | **撤销该条**（不是延后）：顶栏既然不再是玻璃，覆盖式与竖向排列的观感完全相同（内容都被不透明顶栏遮住），继续做只换来一次高风险布局改造 | 这条曾经是"为了让顶栏玻璃能透到内容"才需要的结构前提；前提被 §15.5h 抽掉了，条目自然作废 |
| **配额口径** | 顶栏退出采样后主界面稳定态真模糊面 3→2，多选态 3；`glass_max_blur_surfaces` 保持 4 当上限而非目标 | 数量由 `assertBlurQuota()` 在 debug 下实测，不靠估算 |

**明确没做的**：`fontScale ≥ 1.3` 下 L2 胶囊从"固定高+单行"换成"垂直内衬+可换行"（§7.3）。
它确实是个真实的裁字风险，但改完必须用 1.3/2.0 两档字缩放各看一遍才知道对不对，
而这正是本机验不了的那一类 → 宁可留着当已知项，也不要盲改后当成已交付。
≥840dp 竖排侧栏同理（要新增一整套布局）。


### 15.5j 第十二包：按钮立体感（§15.6-4）+ 顶栏几何收进 style（M2 可行部分）

| 项 | 做法 | 取舍 |
|---|---|---|
| **J1 按钮层级** | `NlButton.Primary` / `.Contrast` 加 `elevation 2dp` + 按 token 着色的 spot/ambient 软阴影；`NlButton.Outline` / `.ContrastOutline` **刻意不加** | 补上的是三级层级的中间档：卡片 1.5dp、按钮 2dp、悬浮件 8dp —— 之前按钮是 0dp 全平，比卡片还平，"能按的东西"读不出来。描边按钮保持贴平，**填充=浮起 / 描边=贴平**本身就是主次行动点的区分手段，两边都抬就又平了 |
| **J2 `stateListAnimator @null` 保留** | 按压反馈仍归 `PressEffect`（0.95 缩放）统一管 | 这是 v2.2 刻意压平的原意（不让 Material 的抬升动画和自绘按压打架）。压平是"不要 Material 的动画"，不是"永远不许有厚度" —— 静态 elevation + 着色阴影拿到厚度，又不动交互模型。PRD §3.4 那句"禁止用 elevation 表达层级"已在 §15.5e 推翻，这里补上按钮这一档 |
| **J3 顶栏几何收进 style** | 新增 `NlTopChrome` / `NlTopBar` / `NlTopDivider` 三个 style，三页的标题行（56dp / 内衬 page_margin / center_vertical）、分割线（divider_height + divider 色）、覆盖层底色全部引用它们；两个 Fragment 里的 `setBackgroundColor` / `elevation = 0` / `visibility = VISIBLE` 等"代码补底色"的兜底随之删掉 | M2 的"抽成一个组件"选了 **style 而不是 `<include>`**：三个顶栏的**操作区各不相同**（设置页只有标题、设备页一个设置键、相册页三个键），做通用 slot 要把按钮改成运行时动态塞进容器，比现在的重复更贵。M2 真正要的是"一处改全局生效"，几何/配色收进 style 就达成目的了 —— 剩下的 `<include>` 抽壳记为**按决定关闭**，不是没做 |
| **J4 相册页顶栏补 explicit 底色** | 它的顶栏没有 `topChrome` 包裹，直接在 XML 上写 `@color/background` | 覆盖式那两页由 `NlTopChrome` 统一带底；相册页是非覆盖式，一行属性解决，不再靠代码画 |

**AC-12 口径再补一处**：按钮从 0dp 变 2dp 阴影，**经典外观下也是新的**（与前轮的 CTA、dock 选中胶囊同类）。"经典外观 == v2.2 逐像素相等"现在只对**玻璃材质层**成立（关掉总闸即回到实体表面 + 无采样），几何/阴影类的这四处是明确的设计更新。

### 15.6 反馈里**没做完**的事（别当成已交付）

1. ~~三页顶栏的滚动渐隐~~ → 顶栏玻璃已在 §15.5h **整条撤销**（收益零、代价真），改回干净统一底色。
   ~~**内容真从标题底下滚过**~~ → 设备页与设置页做成过覆盖式（§15.5f），但顶栏去玻璃后
   （§15.5h）覆盖式与竖向排列观感相同，**该条作废**：相册页不再需要跟进改造（§15.5i）。
   两页保留 FrameLayout + `topChrome` 结构（已验过无回归），不再往第三页推。
   顺带更正两处旧结论：① 这一节从前写着"设置页 ScrollView 直接跟在头部后面，已经是覆盖式效果"，
   **那是错的** —— 它与设备页一样是竖向排列；② §15.5b/§15.5f 里"顶栏玻璃随滚动浮现"的描述
   均已失效，现行行为是"顶栏恒定不透明、只有标题轻微让位"。
2. **监看页 chrome 渐隐未做**：预览页单击切 chrome 没有冲突（§15.5c 已做）；监看页**单击=对焦**
   （`setupTouchAndScale` + `viewFocusIndicator`），拿同一个手势做渐隐会和对焦抢语义。
   要做得换触发条件（双指缩放中 / 录像开始后自动收起），方案未定所以没写。
3. ~~拍摄页切换的液态滑动~~ → **已做**（§15.5c：改成真滑块 + `slidePill`）。
4. ~~内容卡片的立体感~~ → **已做**（§15.5c：`DepthLift`）。~~**按钮**仍未铺~~ → **已做**
   （§15.5j J1：填充按钮 2dp 着色软阴影，描边按钮刻意保持贴平做主次区分；
   `stateListAnimator @null` 保留，按压反馈仍归 `PressEffect`）。

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
**v2.3.1 实际落地差异**（改动了上面这些值，走查驱动，理由见 §15.5e）：`stroke_outer #2B000000`/夜 `#1FFFFFFF`、`rim_bottom #14000000`/夜 `#26FFFFFF`、`shadow_key #33000000`、`shadow_ambient #1A000000`（这两个现在真的被 `outlineSpot/AmbientShadowColor` 读取）、新增 `tint_floating #8FFFFFFF`/夜 `#8F000000`、`elevation_l3 8dp`、`elevation_l1 1.5dp`。表里 `glass_radius_pill`/`glass_blur_max_px`/`glass_max_anim_surfaces` 等仍未落地（配额靠代码约定，见 §8.2）。

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
