# 设计规范摘要 · N-Link 前端重构依据（finesse 落地 v2）

> 本摘要按重构要求「先通读拆解文件，梳理可复用的设计语言，输出设计规范摘要作为重构依据」产出。
> 两个来源：① `video-analysis/`（源视频 13436525070222148.mp4，1080×1920@30fps 逐帧实测的设计拆解）；
> ② `finesse-ui` skill（`mouse-lin/finesse-skill` v0.20.0）的设计规范。
> 二者与 N-Link 现有实现的每一次冲突，本摘要都给出**取舍理由**（第 6 节）。
> 面向 Android 原生（View 体系，非 Compose）；register = **product**（见 `PRODUCT.md`）。

---

## 0. 一句话坐标

**instrument panel（仪表盘）· 单强调色（尼康黄）· 真中性灰阶 · 单手拇指区优先 · 状态反馈分段 · 每拍动效都有静止终态。**
正参照 = 相机机身/专业监看工具的克制机械感；反参照 = 糖果色 pastel、玻璃拟态、全黑+荧光青橙的「相机 App 套路」。

---

## 1. 色彩（Color）

### 1.1 结构：True Neutral 灰阶 + 唯一强调色
- **灰阶 = True Neutral ramp**（60°/0.4% 饱和度，逐档向品牌色相偏一点点，不用纯 `#000`/`#fff` 做界面中性色）：
  `50 #F7F7F6 · 100 #EFEFEE · 200 #E2E2E0 · 300 #CBCBC8 · 400 #9D9D99 · 500 #777773 · 600 #5C5C59 · 700 #4A4A47 · 800 #373736 · 900 #262625 · 950 #171716`。
- **唯一强调色 = 尼康黄 `#FFE100`**，且**只允许两处**：① 连接状态指示灯 ② 主功能按钮填充。
  例外登记：LiveView 页 4 处（刻度尺游标 / 分段选中段 / 跟焦轮驱动位 / 监看中状态点）——取景画面亮度不可控，纯灰选中态读不出来。
- 语义色严格保留：**红=异常、绿=成功**，绝不表示品牌。

### 1.2 对比度硬卡（AA/AAA 实测值，见 `values/colors.xml` 注释）
| 用途 | 组合 | 对比 | 门槛 |
|---|---|---|---|
| 正文 primary | `text_primary`/卡 | 17.48:1 | AAA ✓ |
| 次要 secondary | `text_secondary`/卡 | 8.66:1 | AAA ✓ |
| 三级 tertiary | `text_tertiary`/卡 | 6.54:1 | AA ✓ |
| 黄底文字 | `on_nikon_yellow`/黄 | 13.69:1 | AAA ✓ |
| 黄做细线/文字 | 黄/浅底 | **1.28:1** | ✗ **禁用**（黄永不作文字/细线） |
| 断连指示灯 | `status_disconnected`/卡 | 4.19:1 | AA ✓ |

### 1.3 craft floor（finesse 通用底线，product 也适用）
每个中性色向品牌色相偏一点；**无纯 `#fff`/`#000`**（`white`/`black`/`lv_*` 是已登记豁免的专业监看绝对参考值，不在此列）；hairline 边框；无脏灰。

---

## 2. 间距与信息密度（Spacing & Density）

- **间距阶梯**：`space_4 / 8 / 12 / 16 / 20 / 24`（布局禁裸 dp/sp，一律走 `@dimen`）。
- **圆角单一阶梯**：`radius_control 8 · radius_card 12 · radius_overlay 16 · radius_sheet 20 · radius_dialog 28 · radius_pill 999`。
  组件只能取阶梯上的值，不许出现阶梯外的魔法圆角。主行动点（CTA）用 `radius_sheet 20`，与卡片 `radius_card 12` 拉开两档，靠半径+纯色填充自证最高层级。
- **信息密度 = DENSITY 5**（product 偏密）：留白服务于扫读节奏，不为「大厂感」铺张。页边距统一 `page_margin`（≈24dp，源自视频卡左右 24px / 88% 屏宽）。
- **响应式**：88% 流式宽度 + 固定 padding 自然适配；页签/顶栏加 max-width 防超宽拉伸；字号固定 sp（不随屏缩放），大屏偏小时靠信息密度而非放大字号找补。

---

## 3. 层级（Hierarchy）

- **三级墨**（ramp 950/700/600）承载文字主次；不用 500 档做正文（差 0.12 不达 AA）。
- **字号六档**：`font_display 24sp · title 16 · body 14 · caption 13 · nav 12 · foot 11`。数字主角可用 `tnum`（等宽数字）——唯一允许的例外，功能理由（参数跳变时不抖动）。
- **表面色阶**：`page #F7F7F6 → surface #FCFCFB → surface_variant/panel #EFEFEE`，向上步进（夜间 `#171716 → #262625 → #373736`）。
- **卡片层级靠色阶 + 1dp hairline，不靠阴影**（v2.7.2 玻璃下线，层次实体化）。
- **主次操作**：每屏**恰好一个 primary**（finesse-brief 硬门禁）。primary=纯色高对比填充；secondary/tertiary=描边或文字按钮。

---

## 4. 组件形态（Component Form）

- **按钮**：黑白反相（近黑填充 + 近白字），不与强调色抢戏；主按钮可吃尼康黄（黑字）。
- **Chip/标签**：选中=近黑填充白字；未选中=浅灰填充深字（`chip_unselected`）。
- **卡片**：`surface` 底 + `outline` 1dp 描边 + `radius_card`，无阴影。
- **空态**（`NlEmptyState`）：图标 + 标题 + 说明 + 至多一个 primary 动作；禁 emoji（v2.7 已拆）；图标纯装饰（对无障碍隐藏）。
- **一致性纪律**：同一语义在同一屏里只用一种形态；35 处圆角别名全部指向 `radius_*` 阶梯。

---

## 5. 交互反馈（Interaction Feedback）—— 八状态契约

> 这是 finesse `component-scope.md §3` 的**唯一硬门禁**：**每个可交互组件都必须交付全部八个状态的真实样式**，不可只声称。
> N-Link 是触摸优先的手机 App，hover 天然触摸门控（`state_hovered` 只在鼠标/触控笔悬停触发，不粘手）。

| 状态 | 视觉 | N-Link 落地 |
|---|---|---|
| **default** | 静止态 | base fill（现状不变） |
| **hover** | 指针反馈 | `state_hovered` + 半透明叠层（触摸门控，不粘） |
| **focus** | 焦点环 **≥3:1**，**不许动效出现**，用 outline 不用 border | 常驻 2dp 透明描边，聚焦换 `focus_ring` 色，零几何位移 |
| **active** | 按压：1px 位移或填充加深 | `PressEffect` 全局 0.95 缩放回弹（已有）+ 填充叠层加深 |
| **disabled** | **三通道**：`opacity .5` + 不可点 + 原生 disabled | `ControlStates.setEnabled`（alpha .5 + `isClickable=false` + `isEnabled=false`） |
| **loading** | **预留终态宽度**防回流，>300ms 才给反馈 | `ControlStates.setLoading` 锁定宽度换标签；骨架屏/ProgressBar 走既有 |
| **error** | **原因 + 修法**，永不写「无效」 | 文案规范（第 6 节 + `PRODUCT.md:110-122`） |
| **empty** | 空态视图（view 级，非单控件） | `NlEmptyState`，且 **断连 ≠ 为空** |

**三条实现纪律（component-scope 硬规则）：**
1. **状态间不改 `border-width`**（改宽度会让相邻元素位移）——状态只动 background / border-color / outline / shadow。
2. **焦点环用 outline**，静止态预留透明 outline，聚焦零位移；Android 用**常驻透明描边换色**等价实现（描边在 bounds 内绘制，本就不位移）。
3. **disabled 不是「变淡的 enabled」**——纯 alpha 而不可点，只是把 enabled 调淡了；必须三通道齐备。

**动效预算（源自视频拆解的六拍 + finesse motion）：**
- 因果等待（两段动作间用完成回调，非固定 delay）★、展开错峰不对称（展开慢看、收起利落）★、正反向不同缓动（去慢回快）★；
- 每拍**必须有静止版终态**（动效关闭时显式置终值，否则 opacity:0 起始元素永久不可见）；
- 减弱动效：系统 `animator_duration_scale=0` 或 App 动效关 → 动画直设终值（`UiFlags.pressMotionEnabled` 已内建）。

---

## 6. 冲突取舍（拆解文件 × finesse × 现有实现）

| # | 冲突 | 取舍 | 理由 |
|---|---|---|---|
| C1 | 视频五色 pastel（lime/violet/green/coral/periwinkle） vs 单强调色 | **否决 pastel，留尼康黄** | 五色铺开=强调失效、重归嘈杂；黄是尼康真实品牌色，语义色（红/绿）必须独占 |
| C2 | finesse = Web/H5 技能 vs Android 原生 | **迁移规范层、重写实现层** | craft floor / motion 预算 / anti-cheap / component-scope 8 态 / product-palettes token 契约可迁移；CSS 布局、自绘状态栏、`detect.mjs` 等弃用（ADR-001） |
| C3 | 视频衬线 Didone 数字主角 | **否决，用 sans + `tnum`** | 不打包第三方字体（禁区）；`tnum` 等宽数字已满足「参数跳变不抖动」的功能诉求 |
| C4 | 视频粒子迸发/多色庆祝 | **否决** | 与「克制机械感」soul 相悖；成功态用分段反馈+对勾描线即可 |
| C5 | 自绘状态栏/手势条 | **否决，交给 OS** | Android 系统家具，自绘反而是回归 |
| C6 | finesse「hover 焦点环动效」vs「focus 不许动效」 | **focus 无动效，即时出现** | 键盘/无障碍用户需要焦点瞬时可见（component-scope §3 明文） |
| C7 | 断连文案 vs 空态文案（各说各的） | **断连 ≠ 为空，拆开写** | PRODUCT.md:120-122 军规；断连写原因+修法，空态写「空」+出口 |
| C8 | disabled 用 alpha 0.55/0.4 假禁用（现状） | **改真三通道禁用** | 现状 dimmed 控件仍可点，状态不诚实；改法=isEnabled+isClickable+alpha（属状态反馈修正，非业务逻辑变更） |
| C9 | Compose 迁移 / 大重排 | **不动技术栈、不大重排** | 禁区；只改前端实现与样式结构 |
| C10 | 718 处裸 dp | **登记债务、不在本轮归位** | 归位是独立机械改动，风险>收益，已登记 |

---

## 7. 重构范围红线

- **不改连接功能性代码**（`com/camerakit/**` PTP/AP/USB 协议层、`nikon-conn-kit/**`）。
- 不改业务逻辑与功能行为，仅调前端实现与样式结构；空态/断连文案、状态反馈、样式、动效在范围内。
- 强调色不铺第三处；不打包字体；不引 CDN；不上埋点。

---

*配套：`PRODUCT.md`（设计记忆）· `docs/ADR-001-前端重构-finesse规范落地-v1.md`（迁移裁决）· `docs/前端重构交付说明-finesse落地-v1.md`（上一轮交付）*
