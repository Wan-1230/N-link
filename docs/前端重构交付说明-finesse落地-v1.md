# 前端重构交付说明 · finesse 规范落地 v1

对应 ADR：`docs/ADR-001-前端重构-finesse规范落地-v1.md`
基线：`e2a85fa`（v3.2.0 / versionCode 31）
本次 4 个 commit：`ccf170c` → `89da1df` → `875c334` → `b303a85`

**构建与测试实测结果**：`BUILD SUCCESSFUL`；67 个测试类 / **388 个用例 / 0 失败 0 错误**；
APK 产物 `app/build/outputs/apk/debug/app-debug.apk`（21.3MB）。
**视觉效果未经真机验证**——本机无真机，模拟器两次尝试均失败（ADR §4.3）。
按 `preflight.md:91-107` 的要求：静态检查与单测通过，**我没打开看过**。

---

## 一、改了什么

### commit 1 `ccf170c` · test(deps)：修复既有破损（不是我引入的）

基线提交 `e2a85fa` 带进了 `UsbPtpLifecycleTest.kt`（`@RunWith(RobolectricTestRunner::class)`）
却没提交依赖声明，导致 `testDebugUnitTest` 源集**整体编译失败**——
67 个测试文件一个都跑不了，文档声称的单测覆盖实际为零。

补 3 处：`libs.versions.toml` 的 version + library 声明、`build.gradle.kts` 的
`testImplementation(libs.robolectric)` 与 `testOptions.unitTests.isIncludeAndroidResources`。
依赖已在本地 Gradle 缓存（4.16.1），可离线解析；`testImplementation` 作用域不进 APK。

补齐后 388 个用例全部可跑且全绿。

### commit 2 `89da1df` · docs(design)：finesse 项目记忆

| 文件 | 作用 | 谁读 |
|---|---|---|
| `PRODUCT.md` | finesse `init` 产物。锁 register / dials / accent / 字体 / 反参照 | 后续每个 UI 任务开工前 |
| `design-model.yaml` | finesse `document` 产物。记录决策与实测数值 + 8 条 drift 登记 | 同上；新增页面比对用 |
| `docs/ADR-001-*.md` | 平台错配清单、四项裁决、ramp 选型依据、六动效合规映射、闸门 | 人 |
| `.finesse/log.json` | 五轴坐标记录（**已 gitignore**） | finesse 自身 |

`design-model.yaml` 刻意**不复制色值**——真相源仍是 `res/values/*.xml`。
再建一份 YAML 镜像等于制造第二真相源，而项目文档已把「文档滞后于代码」列为系统病。
这是对 `design-model.md:93-101` 两级布局的有意偏离，理由写在文件头。

### commit 3 `875c334` · style(tokens)：视觉令牌层

**配色**（`values/colors.xml` + `values-night/colors.xml`）

| 项 | 改动前 | 改动后 | 依据 |
|---|---|---|---|
| page / background | `#FFFFFF` 纯白 | `#F7F7F6` | craft floor 禁纯白 |
| surface（卡片） | `#FFFFFF` | `#FCFCFB` | 卡片浮起、页面退后 |
| surface_variant / panel_2 | `#F5F5F5` | `#EFEFEE` | 内嵌面下沉 |
| chip_unselected | `#F0F0F0` | `#E2E2E0` | 保持四级明度序 |
| text_primary | `#000000` | `#171716` | craft floor 禁纯黑 |
| text_tertiary | `#8C8C8C`（**3.36:1 不达标**） | `#5C5C59`（6.54:1） | 既有无障碍缺陷 |
| nav_inactive_icon | `#9E9E9E`（**2.68:1 不达标**） | `#777773`（4.19:1） | WCAG 1.4.11 图形 ≥3:1 |
| outline | `#E4E4E4` 硬灰 | `#1A000000`（10% 黑） | hairline 必须是 alpha |
| divider | `#EBEBEB` 硬灰 | `#14000000`（8% 黑） | 8% 黑叠白 = `#EBEBEB`，**像素级无损** |
| 夜间 text_tertiary | `#7A7A7A` | `#B4B4B0` | 落在 variant 上要 5.73:1 |
| switch_track_off / thumb_off | **无夜间值** | 已补齐 | 违反项目成对军规 |

强调色 `#FFE100` 与「只允许两处」约束**一字未动**。

`white` / `black` 两个 token（54+6 处引用）**刻意保留纯值**：
它们几乎全在取景背板、快门环、照片查看器底色上。给取景背板加暖色偏会
直接骗到正在判断曝光与色彩的人——这两个值必须是绝对的。属 `lv_*` 同一类已登记豁免。

**圆角**：`glass_radius_*`(8/12/16/20/28) + `card_radius`(12) + `lv_radius_*`(8/12/16)
三套重叠体系 → `dimens.xml` 的 `radius_control/card/overlay/sheet/dialog/pill` 单一阶梯，
原 9 个 token 全部改为**引用别名**（35 处调用点零改动）。
11 个 drawable 的硬编码圆角归位；仅 2 处刻意微调（`18→20dp`、`10→12dp`，各 +2dp，均在 ≥44dp 大面上）。

**排版**：监看页 6 个参数读数（`tvShutterValue`/`tvApertureValue`/`tvIsoValue`/`tvEvValue`/
`tvWbValue`/`tvDialValue`）在竖屏与横屏布局各加 `android:fontFeatureSettings="tnum"`，共 12 处。
理由是**功能的**：五格并排时比例数字会让竖线左右跳动。未换字体族。

### commit 4 `b303a85` · feat(motion)：四拍动效

新增 `shared/ui/` 四个文件 + 重写 `TickRulerView` + 接线两个 Fragment/Controller。

| 拍 | 文件 | 接入点 | 视频依据 |
|---|---|---|---|
| ① 快门分段反馈 | `ShutterFeedback.kt` | `LiveViewFragment.setupShutter` | 01 Squeeze/Tick/Burst |
| ② 转盘惯性吸附 | `TickRulerView.kt`（重写） | 曝光/白平衡面板的三条尺子 | 05 步进 100→430ms |
| ③ 触点圆形扩散 | `CircleReveal.kt` | `LiveViewPanelsController.arrivePanel` | 06 去 ease-out / 回 ease-in-out |
| ④ 面板内容错峰 | `StaggerMotion.kt` | 同上 | 03 错峰方向不对称 |
| 纯数学 + 单测 | `InertiaMath.kt` / `InertiaMathTest.kt` | 16 条用例全绿 | — |

---

## 二、遵循的 Skill 规范（逐条对应）

| 规范条目 | 落地方式 |
|---|---|
| `SKILL.md:369` color lock | 单强调色 `#FFE100` 未动；视频五色 pastel 已否决 |
| `preflight.md §B` 无纯黑白 / 中性带色相 / hairline alpha | commit 3 全部落地，数值见上表 |
| `product-palettes.md:59` tint 匹配 accent 色相 | True Neutral（色相 60°，呼应黄的 53°） |
| `anti-cheap.md:64-68` 禁米色家族 | **避开**：Warm Stone 100 `#F0ECE5`(38°/4.3%) ≈ 禁用值 `#EFEAE0`(40°/5.9%)，故弃用；True Neutral 仅 0.4% 饱和度 |
| `product-palettes.md:254/352` lime/amber 填充用近黑字 | 项目原本就合规（`on_nikon_yellow` 黑字），本次保留并把纯黑换成 `#171716`（13.69:1） |
| `product-palettes.md:94-105` 深色面板向上步进 | 夜间 `page 950 → card 900 → variant 800 → chip` 明度递增，实测阶梯成立 |
| `motion.md:138` product register 只带 state transition | 四拍分属 state-transition 与 physics/inertia，**粒子迸发已否决** |
| `motion.md:206-211` 预算 | 重拍 0（上限 1）、总拍 4（上限 4） |
| `motion.md:22-25` 每拍有静止版终态 | 四拍全部走 `UiFlags.motionEnabled`/`pressMotionEnabled`，关闭时直落终值 |
| `h5-mobile.md:461-465` 氛围层减弱动效下移除而非冻结 | 本项目无氛围层，规则简化为「直落终值」，已登记 |
| `anti-cheap.md:53` 禁混用圆角体系 | 三套收敛为一套 |
| `anti-cheap.md:59` 「因为高级所以用衬线」是反射性拒绝 | 视频建议的衬线 Didone 数字**已否决** |
| `divergence.md:29` 绝不在「东西在哪儿」上 diverge | TabBar 位置/结构未动；选中态原本就是「反白药丸」，正合 `h5-mobile.md:384`，**保留不改** |
| `divergence.md:203` 品牌事实压倒禁令表 | `#FFE100` 饱和度 100% 超 80% 上限，据此豁免 |
| `divergence.md:112` `.finesse/log.json` 要 gitignore | 已加 |
| `divergence.md:118-129` 构建 stamp | 因无 CSS，置于 `values/colors.xml` 文件头（ADR §1.1 记录的迁移约定） |
| `preflight.md:91-107` 绝不声称没跑过的通过 | 本文件开头即声明「我没打开看过」 |
| `redesign-mode.md:71-77` 先立保护规则 | ADR §3.3 列出四项不碰的东西 |
| `component-scope.md:58-71` 8 状态 | **未做**，见下节 |
| finesse-brief `hook-engineering.md:198` 断连 ≠ 为空 | **未做**，见下节 |

---

## 三、明确没做的（以及原因）

诚实登记，不假装完成：

1. **`component-scope.md` 的 8 个强制状态未系统落地。**
   现有控件的 `disabled` 多数只有 `alpha .5`，缺 `clickable=false` 与
   `cursor` 等价物的三通道；`focus-visible` 环未统一。这需要逐个控件过一遍并
   **看得见**才能确认，无验证通道时做等于盲改。

2. **空态 / 断连文案未改。**
   finesse-brief 的门禁（「未连接相机」「WiFi 已断开」「SD 卡已满」
   「传输中断，已传 12/40 张」各写各的，禁 `--`、禁假数据）已写进 `PRODUCT.md`，
   但具体文案要逐屏核对现状，属独立一轮。

3. **全屏监看的 HUD 布局未重排。**
   用户授权了「可动布局与信息架构」，但 ADR §3.3 保护「已真机验收的布局骨架」，
   且 `fragment_liveview.xml` 竖屏 1123 行 / 横屏 1084 行是双份维护。
   在无法验证的前提下重排 HUD，风险远高于收益。

4. **718 处裸 `dp` 未归位。**
   `dimens.xml:12-15` 已记录该债务。逐值归位需逐页视觉 QA，同上理由不做。

5. **Tab 切换未加圆形扩散。** 这是**主动否决**，不是遗漏——三条独立理由见 ADR §2.4 与
   `MainActivity.switchFragment` 的既有注释（真机走查曾因转场延迟吃过投诉）。

6. **HUD 唤醒未加错峰。** 同为主动否决：`motion.md` 四道闸要求每拍有动机，
   而轻点取景器唤出控件的动机是「立刻」，错峰与之相反。

---

## 四、本地集成步骤

已直接提交在 `master`，无需手工集成。若要在别处复现：

```bash
# 1. 确认基线
git log --oneline -5
# 应看到 b303a85 / 875c334 / 89da1df / ccf170c / e2a85fa

# 2. 构建
./gradlew :app:assembleDebug
# 期望：BUILD SUCCESSFUL，产物 app/build/outputs/apk/debug/app-debug.apk

# 3. 跑全量单测
./gradlew :app:testDebugUnitTest
# 期望：67 个测试类 / 388 个用例 / 0 失败

# 4. 装包
"<AndroidSdk>/platform-tools/adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
```

注意：`adb` 不在 PATH，需用绝对路径（本机为
`C:\Users\21085\AppData\Local\Android\Sdk\platform-tools\adb.exe`）。

---

## 五、真机验证清单

**这一节是本次交付最重要的部分**——所有改动只保证编译通过与静态检查通过，
视觉效果必须由人在真机上确认。逐条给出「看哪里 / 预期什么 / 不符合说明什么」。

### 5.1 全局配色（日夜间各走一遍）

| # | 看哪里 | 预期 | 不符合说明 |
|---|---|---|---|
| 1 | 四个 Tab 页的页面底色 | 极浅的暖灰，**不是纯白**；卡片比页面略亮、有浮起感 | 若卡片与页面同色 → `surface`/`background` 关系接反 |
| 2 | 任意卡片边框 | 极细、若隐若现的浅灰线 | 若边框明显变粗或变深 → alpha 值算错 |
| 3 | 分隔线 | 与改动前**看不出区别**（8% 黑叠白 = 原 `#EBEBEB`） | 若变浅/变深 → alpha 与预期不符 |
| 4 | 辅助小字（时间、说明文字） | 比改动前**明显更清楚** | 若反而更淡 → `text_tertiary` 取错档 |
| 5 | 底部 dock 未选中图标 | 比改动前更清楚（2.68→4.19:1） | — |
| 6 | 主按钮 | 仍是黄底**黑字**，黄色只出现在主按钮与连接指示灯两处 | 若别处冒出黄色 → color lock 被破坏 |
| 7 | 夜间模式全部页面 | 页面最暗、卡片稍亮、内嵌面更亮（**明度递增**） | 若卡片比页面暗 → 夜间阶梯接反 |
| 8 | 夜间辅助小字 | 清楚可读（5.73:1） | 若发灰读不出 → `#B4B4B0` 需再提亮 |
| 9 | 连接英雄图（设备页相机插画） | 与改动前**完全一致** | 若变了 → `hero_cam_*` 被误改 |
| 10 | 全屏监看与照片查看器 | 底色仍是**纯黑**，快门环仍是**纯白** | 若发暖 → `white`/`black` 被误并入 ramp（这是严重问题，会误导曝光判断） |

### 5.2 圆角

| # | 看哪里 | 预期 |
|---|---|---|
| 11 | 全部卡片、chip、按钮 | 与改动前一致（等值替换） |
| 12 | 设置页「支持」大卡片、底部 dock 选中胶囊 | 圆角**略大一点点**（18→20dp），不应有形状突变 |
| 13 | 教程气泡 | 圆角略大（10→12dp） |
| 14 | 弹窗 / 底部抽屉 | 与改动前一致 |

### 5.3 监看参数读数

| # | 看哪里 | 预期 |
|---|---|---|
| 15 | 竖屏与横屏监看页的五个参数格 | 数字**等宽对齐**；改档位时（1/250↔1/60）竖线不左右跳动 |
| 16 | 若字体没变化 | 说明系统字体不支持 `tnum` 特性 → 需改用打包字体或放弃此项 |

### 5.4 四拍动效

| # | 看哪里 | 预期 | 不符合说明 |
|---|---|---|---|
| 17 | 全屏监看按快门 | **三段可分辨**：按下去缩一下 → 松手弹回并轻微过冲 → 然后才白闪 | 若按下与白闪同时 → 接线顺序错了 |
| 18 | 快门实际出片时机 | 与改动前**没有可感知的延迟** | 若有延迟 → `capture()` 被误放进回调（严重，必须改回） |
| 19 | 曝光面板拖动刻度尺后甩动 | 尺子**继续滑一段再停下**，逐格变慢，最后吸附到整格 | 若立即停 → 惯性没生效 |
| 20 | 刻度尺拖到最左/最右继续拖 | 有**阻力感**，松手弹回边界，且**不触发**选中回调 | 若弹回后档位变了 → 橡皮筋路径误触发回调 |
| 21 | 刻度尺未选中项 | 离中心越远**越淡**（原来是所有未选中项一样亮） | — |
| 22 | 点工具栏五个工具之一 | 面板从**被点的那个工具**位置扩散展开，内容逐行到场 | 若从面板中心展开 → 圆心坐标算错 |
| 23 | 连点不同工具 | 面板互斥切换正常，不会两个面板同时出现或都消失 | 若卡住 → `arrivePanel` 与 `closeInternal` 时序问题 |
| 24 | 面板收起 | **瞬时**收起（刻意不做动画），参数条与工具栏正常复位 | 若复位错乱 → `onOpenStateChanged` 时序问题 |

### 5.5 减弱动效（无障碍，必测）

系统设置 → 无障碍 → 移除动画（或「动画程序时长缩放」设为 0），然后重测 17–24：

| # | 预期 |
|---|---|
| 25 | 快门**仍然能拍**、白闪仍在，只是没有缩放补间 |
| 26 | 刻度尺仍能拖动、仍吸附到整格，只是不甩 |
| 27 | 面板**直接出现**（无扩散、无逐行），且内容完整可见 |
| 28 | **不允许**出现任何元素停在半透明或半展开状态 |

第 28 条是最容易出问题的一条：`opacity:0` 起始的元素在动效关闭时若没被显式置终值，
会**永久不可见**。`StaggerMotion.expand` 与 `CircleReveal.reveal` 都写了这个分支，
但必须实测确认。

### 5.6 双页共链验证（ADR §3.4 强制）

`RemoteFragment`（拍摄页嵌入监看）与 `LiveViewFragment` 共享渲染链。
凡涉及监看的改动**两页都要验**：

| # | 检查 |
|---|---|
| 29 | 拍摄页的嵌入式监看画面正常、帧率无可感下降 |
| 30 | 从拍摄页进全屏、再返回，画面不黑屏、不卡死 |
| 31 | 全屏监看连续运行 5 分钟，机身温度与帧率与改动前无差异 |

第 31 条尤其重要：第 2 拍的惯性动画会占用主线程，而监看链固定 15fps。
虽然已刻意避开每帧回调（用解析投影 + 插值器），仍需实测确认无掉帧。

---

## 六、回退方式

按阶段独立 commit，任一阶段可单独 `git revert`：

| 想回退 | 命令 | 影响面 |
|---|---|---|
| 只退动效 | `git revert b303a85` | 四拍全部消失，令牌与文档保留 |
| 只退面板扩散、保留错峰 | 把 `LiveViewPanelsController.panelRevealEnabled` 改 `false` | 一行，无需重新构建配置 |
| 只退圆角收敛 | `git revert 875c334` 后单独 cherry-pick colors.xml | 圆角回到三套体系 |
| 只退配色 | 同上 | — |
| 强调色整体回退 | `nikon_yellow`/`status_connected` 改回 `#171716`/`#FCFCFB` | 还原纯黑白灰 |
| 监看强调色回退 | `lv_accent` 改 `@color/white` | 无需动布局 |
| 退依赖修复 | `git revert ccf170c` | 单测源集重新不可编译（不建议） |

---

## 七、遗留与建议

1. **推送未执行。** 本次改动包含全局可见的配色变化且**未经真机视觉验证**，
   推到 `master` 会让其他人拿到未验证的视觉。建议先走完 §5 清单再推。
2. **`finesse-ui` 本地版本过期**：`~/.qoder-cn/skills/finesse-ui/` 是 v0.9.0，
   仓库最新 v0.20.0，缺 12 个 reference（含 `motion.md`、`h5-mobile.md`、
   `product-palettes.md`）。本次全程依据新克隆版执行。升级需覆盖用户目录，要单独授权。
3. **`finesse-brief` 未安装**，且其产出物是网页规格，对原生 App 只借门禁不生成 spec。
4. **模拟器**：第 2 次失败根因是时序（`adb kill-server` 后未 `start-server` 就启模拟器）。
   若需第 3 次尝试，先 `adb start-server` 再启 `nlink_glass` AVD。
5. 第三节的 6 项未做工作，建议按「8 状态 → 空态文案 → HUD 重排 → dp 归位」顺序排后续轮次，
   前两项不依赖真机验证通道，可以先做。
