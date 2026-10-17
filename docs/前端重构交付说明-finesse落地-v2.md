# 前端重构交付说明 · finesse 规范落地 v2（八状态体系 + 空态/断连文案）

> 承接 `docs/前端重构交付说明-finesse落地-v1.md`（token/motion/配色落地）与
> `docs/设计规范摘要-finesse落地-v2.md`（重构依据）。本文记录**第二轮收尾**的改动：
> 按 finesse-skill `component-scope §3`「八状态硬门禁」补齐状态反馈，并按
> `PRODUCT.md:120-122`「断连≠为空」军规拆分空态/断连文案。
>
> 视觉规范来源：GitHub `mouse-lin/finesse-skill`（finesse-ui v0.20.0，register=product）。
> 拆解文件来源：`video-analysis/`（设计拆解视频逐帧实测报告 + 34 张关键帧）。
>
> **红线**：全程未改 `com/camerakit/**` 与 `nikon-conn-kit/**` 连接功能性代码；
> 未改业务逻辑、功能行为、状态机；仅调整前端实现与样式结构。

---

## 0. 验证结论（先给结果）

| 项 | 结果 |
|---|---|
| `./gradlew :app:assembleDebug` | **BUILD SUCCESSFUL**，产物 `app/build/outputs/apk/debug/app-debug.apk`（19.2 MB） |
| `./gradlew :app:testDebugUnitTest` | **398 用例 / 0 失败**（基线 388 + 本轮新增 10） |
| 新增 `EightStateSelectorTest` | 10 用例全绿，逐态断言 selector 渲染互不相同 |
| 视觉 | **未经真机验证**（preflight 要求明说：「我没打开看过」）。模拟器验证授权已在 v1 用完，本轮仅构建 + 单测 |

新增测试是八状态的「可证明交付」：把每个 `bg_*.xml` selector 逐态 resolve，断言 default/hover/
active/focus 渲染出的 Drawable **互不相同**。collapse 任意两态到同一 item 会立刻红 —— 这是
Web 版 `preview.html`「打开看每态」在 Android 侧的等价物，不是声称、是验证。

---

## 1. 八状态体系：两条通道的落地

finesse `component-scope §3` 要求每个可交互组件交付全部八态真实样式。八态按**承载通道**拆到
Android 最擅长的位置，而非硬塞进单一机制：

| 状态 | 承载通道 | 落地位置 |
|---|---|---|
| default | shape 底色 + 2dp 透明描边 | `bg_*.xml` selector 末项 |
| hover | 底色 + 半透明 scrim 叠层（`state_hovered`） | `bg_*.xml` selector |
| active | 底色 + 更深 scrim 叠层（`state_pressed`）；另有按压缩放 0.95 | `bg_*.xml` selector + 全局 `PressEffect.kt` |
| focus | 底色 + 2dp `focus_ring` **换色**描边（`state_focused`） | `bg_*.xml` selector |
| disabled | 三通道：`alpha .5` + 不可点 + 原生 `disabled` | `ControlStates.setEnabled` |
| loading | 换标签**预留终态宽度**防回流 | `ControlStates.setLoading` |
| error | 「原因+修法」文案 | 复用既有好样本（见 §3） |
| success | 完成态双通道（文案 + 视觉） | 复用既有 `NlFeedback` / 状态卡 |

### 1.1 焦点环零位移（硬纪律落地）

finesse 三条硬纪律之一：**状态间不改 border-width**，焦点环用 outline 而非 border，静止预留
transparent outline 防位移。Android 描边绘制在 bounds **内侧**，本就不引起位移；等价做法：

```
default：底色 + 2dp 透明描边
focus  ：底色 + 2dp focus_ring 描边   ← 宽度恒定 2dp，只换颜色
```

常驻 2dp 透明描边、聚焦只换 `focus_ring` 色 —— `border-width` 恒定、零位移，与 CSS
`outline: 2px solid transparent` → `:focus-visible` 换色同构。`EightStateSelectorTest`
的「焦点环是换色描边而非改宽度」用例钉死这一条。

### 1.2 hover/active 用 scrim 叠层，不换实底

finesse craft floor：中性色可偏向品牌色相、禁纯 `#fff`/`#000`。本项目 `primary` 已是 ramp 950
近黑，若 hover/active 再「压深换实底」会撞纯 `#000`。故改用**半透明 scrim 叠层**（`layer-list`
[底色] + [scrim]）表达提亮/压深，既保留主题翻转下的正确方向，又不越 craft floor。
方向按**填充族**分（因 `primary`/`chip` 底色随主题翻转）：

| token | 日间 | 夜间 | 用于 |
|---|---|---|---|
| `state_hover_light` / `state_active_light` | `#0A000000` / `#14000000`（恒压深） | 同 | `bg_lv_action`（恒白底，lv 豁免族） |
| `state_hover_chip` / `state_active_chip` | `#0A000000` / `#14000000`（压深） | `#0AFFFFFF` / `#14FFFFFF`（提亮） | `bg_chip` |
| `state_hover_primary` / `state_active_primary` | `#14FFFFFF` / `#0AFFFFFF`（提亮） | `#0A000000` / `#14000000`（压深） | `bg_cta_primary` / `bg_chip_selected` |
| `focus_ring` | `#5C5C59`（ramp600） | `#CBCBC8`（ramp300） | 全部 selector |

`focus_ring` 取值依据：日间 `#5C5C59` 为 mid-neutral —— 压过浅底 6.5:1（≥3:1 达标）又能从近黑
按钮上分离；夜间 `#CBCBC8` 压近黑底 12:1。

---

## 2. 逐文件改动要点 + 设计决策依据

### 2.1 样式层（selector 重写）

- **`app/src/main/res/values/colors.xml`** + **`values-night/colors.xml`**
  新增八状态 token（3 叠层族 + `focus_ring`，见 §1.2 表）。决策：**叠层用半透明而非换实底**
  （理由：primary 已近黑，压深撞纯 #000）；**方向按填充族分**（理由：primary/chip 底色随主题翻转）。

- **`app/src/main/res/drawable/bg_chip.xml`** · **`bg_cta_primary.xml`** ·
  **`bg_chip_selected.xml`** · **`bg_lv_action.xml`**
  由静态 shape 重写为**八状态 `<selector>`**。item 顺序 =
  `disabled(state_enabled=false→pinned 底色)` → `focus(state_focused→底色+2dp focus_ring)` →
  `active(state_pressed→layer-list[底色+2dp 透明描边]+[scrim])` → `hover(state_hovered→同结构 scrim)` →
  `default(底色+2dp 透明描边)`。
  决策：
  1. **常驻 2dp 透明描边、聚焦只换色**（§1.1，零位移）；
  2. disabled 项只 **pinned 底色**、不做半透明 —— 半透明由 `ControlStates` 施加，drawable 只负责
     「禁用态不误命中 hover/focus」，职责单一；
  3. `bg_lv_action` 恒白无夜间翻转（`lv_*` 豁免族），故 hover/active 用 light 族**恒压深**。

### 2.2 状态层（运行时通道）

- **`app/src/main/java/com/nikonlink/app/shared/ui/ControlStates.kt`**（新建，`object ControlStates`）
  - `setEnabled(view: View, enabled: Boolean)` —— disabled 三通道：
    `view.isEnabled`（原生 disabled）+ `view.isClickable`（`cursor:not-allowed` 等价）+
    `view.isFocusable`（禁用退出 tab 焦点序，无障碍）+ `view.alpha(if enabled 1f else 0.5f)`（opacity .5）。
    决策：finesse 明言**纯 alpha 不是 disabled**，只是「变淡的 enabled」；必须可点性一并关掉。
    Android 无 cursor 概念，`cursor:not-allowed` 等价于 `isClickable=false`。
  - `setDisabled(view, disabled)` —— 反向别名。
  - `setLoading(view: TextView, loading: Boolean, loadingLabel: CharSequence, restingLabel: CharSequence)`
    —— loading 时 `minimumWidth = view.width`（预留终态宽度防 label swap 让邻居回流）+ 切
    `loadingLabel`；结束还原 `restingLabel` + `minimumWidth = 0`。决策：宽度取静止态实测宽（final
    width），加载标签更长时**至少不塌缩** —— 回流最危险的方向是缩。

### 2.3 禁用点接线（3 个真禁用 + 2 个假禁用纠正）

全部改为 `ControlStates.setEnabled`（补齐全三通道，业务逻辑零改动）：

- **`SettingsFragment.kt:669/677`** —— `rowCheckUpdate` 检查更新时禁用。原本只 `isEnabled=false`
  无视觉降级，现补 alpha .5 + 不可点。决策：纯前端状态反馈增强。
- **`DashboardFragment.kt:280/290/295/300`** —— `btnStaHostRegister` 主机注册 Running 时禁用。
- **`LiveViewFragment.kt:901/907/913`（`applyLvCard`）** —— 状态卡三态切换。STARTING「启动中」用
  `setLoading`（预留宽度）+ `setEnabled(false)`（禁用三通道）；STOPPED/else 用 `setEnabled(true)` +
  `setLoading(false,…)` 还原文案并释放宽度锁。决策：这是「换标签防回流」的示范点（按钮
  「开始监看」↔「启动中」↔「重试监看」宽度会变，邻居不该跳）。
- **`RemoteFragment.kt:532-534（`updateModeCellState`）/ 1083-1091（`applyBulbLock`）** —— 原本已设
  `isEnabled + isClickable + alpha`（0.4），改为 `ControlStates.setEnabled` 统一口径：归一 alpha 到
  spec 的 `.5`、补 `isFocusable` 通道。决策：集中化避免各处手搓口径漂移，行为零变化。

**保留不动（重要取舍）**：`RemoteFragment.kt:296（`applyHistogramToggle`）` 的 `alpha = if (enabled)
1f else 0.55f` **不是** disabled，而是直方图**开关双态**的对比表达（关闭态下按钮仍须可点以便重新
开启）。故**刻意不套** `setEnabled`（那会让关闭后点不开 = 改行为），已在代码注释登记该取舍。
`ToolItemView.kt:78` 的 `alpha = if active …` 是激活态对比非禁用，同样不套。

### 2.4 空态/断连文案拆分（「断连≠为空」军规）

- **`TransferFragment.kt`** —— 新增 `emptyMessage(): String` 私有助手，按 `viewModel.cameraReady`
  （连接就绪，含 FULLY_CONNECTED/BLE_CONNECTED/USB 三源）分支，替换原先两处内联 `when`
  （tab 切换默认文案 :820、空列表收集器 :897）。原 `MARKED` 一句「标记的照片都已下载，**或**相机
  尚未连接」把断连与空混在一句 —— 按军规拆开：

  | 源 | 断连（原因+怎么办） | 真·空（为什么空+下一步） |
  |---|---|---|
  | CAMERA | 连接相机后查看相册 | 相机相册里没有照片 |
  | MARKED | 相机未连接，标记的照片无法加载 | 暂无标记照片：在相机相册长按多选后点「标记」／标记的照片都已下载到手机 |
  | LOCAL | （纯本地无连接依赖） | 尚未下载照片到手机 |

  决策：断连写「原因+怎么办」、真空写「为什么空+下一步」，两者不混一句、不写 `--`、不编假数据
  （PRODUCT.md:120-122 + finesse error 门禁「永不写 Invalid」）。

---

## 3. 冲突取舍（拆解文件 vs 现有实现）

| # | 冲突 | 取舍 | 理由 |
|---|---|---|---|
| C1 | 视频五色 pastel vs 项目单强调色尼康黄 | 保留 `#FFE100` 单强调色 | v1 已定，PRODUCT.md 禁糖果色 pastel |
| C2 | finesse Web 状态（伪类）vs Android 无伪类 | selector `state_*` + `ControlStates` 双通道承载 | 平台迁移规范层、重写实现层（ADR-001） |
| C3 | disabled 视觉：drawable 半透明 vs ControlStates alpha | `ControlStates` 施加、drawable 只 pinned 底色 | 职责单一，drawable 管 hover/focus 不误命中 |
| C4 | hover/active 压深换实底 vs scrim 叠层 | scrim 叠层 | primary 已近黑，压深撞纯 #000（craft floor） |
| C5 | `btnHistogram` alpha 像假禁用 | 认定为开关双态、**不**改真禁用 | 改了就点不开 = 改行为，违反 req4 |
| C6 | 断连文案 vs 空态文案混写 | 按 `cameraReady` 拆分 | PRODUCT.md:120-122 军规「断连≠为空」 |
| C7 | active 通道：PressEffect 缩放 vs selector scrim | 两者叠加 | 缩放是主反馈（motion），scrim 是补强 |

---

## 4. 未做 / 风险

1. **视觉未经真机验证**：本轮仅构建 + 单测，模拟器验证授权已用完。八状态的**逻辑与样式结构**已由
   `EightStateSelectorTest` 逐态证明；但 scrim 提亮度、焦点环对比、loading 回流的**像素级观感**
   需真机确认。真机验证清单沿用 v1 §五 31 条 + 本轮新增：hover/active 叠层方向、focus_ring 双主题
   对比、STARTING 换标签不回流。
2. **718 处裸 dp 债务**未归位（v1 已登记，非本轮范围）。
3. **HUD 布局未重排**、**Tab 切换圆形扩散**、**HUD 唤醒错峰**：v1 已主动否决，维持。
4. error/success 两态复用既有好样本（`ConnFunnel.kt:183` 三元组、`TransferManager.kt:738/780`、
   `BulbPolicy.kt:129`、`shutter_count_failed_*`），未新增 error 文案 —— 既有已达「原因+修法」标准。

---

## 5. 回退

本轮改动集中在：4 个 `bg_*.xml` selector、`ControlStates.kt`（新增）、5 个 Fragment 的禁用点接线、
`TransferFragment` 空态助手、`EightStateSelectorTest`（新增）。均为前端状态/样式层，回退不影响
连接功能。`git diff` 即可逐文件核对。
