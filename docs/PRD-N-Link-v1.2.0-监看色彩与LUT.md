# N-Link v1.2.0 – v1.4.0 PRD — 监看色彩与创作工作流

> 版本：v1.2.0 – v1.4.0（草稿 · 待评审）
> **基准版本：v1.1.0 已发布**（`versionName = "1.1.0"`，versionCode 9）
> 撰写日期：2026-09-06
> 调研方法：四款竞品 APK 静态逆向（aapt2 / DEX / native so / assets 提取）+ GitHub 开源项目普查 + 公开用户反馈聚合 + N-Link 现有代码实读
> 状态：**方案类文档，需小万确认后再动工**

---

## 0. 阅读须知与可信度声明

本文档所有结论分三级标注，请勿混用：

| 标记 | 含义 | 处理方式 |
| --- | --- | --- |
| **【实测】** | 直接从你提供的 APK / N-Link 源码 / `gh api` 提取，可复现 | 可直接作为决策依据 |
| **【外部】** | 来自公开网页 / 第三方聚合站 | 需二次核验后再对外引用 |
| **【推断】** | 基于上述材料的工程判断 | 必须与小万对齐后再执行 |

**版本命名口径（已按 v1.1.0 基准统一）**

| 阶段 | 版本 | 主题 |
| --- | --- | --- |
| 已发布 | **v1.1.0** | 当前线上版本（versionCode 9） |
| 下一版 | **v1.2.0** | 地基：帧率与渲染管线 |
| 再下一版 | **v1.3.0** | 监看色彩：LUT 引擎 |
| 后续 | **v1.4.0 / v1.5.0+** | 出片闭环 / 专业监看 |

> 📌 **历史文件命名待清理**：根目录 `PRD-N-Link-v0.1.4-连接与监看修复.md`、`PRD-N-Link-v0.2.0-工作流版.md` 仍沿用 0.x 旧口径，实际对应的是 1.1.0 前后的工作。建议后续统一重命名为 1.x，避免与发版号长期脱节。本文档中出现的 `v0.2.0 PRD` 均指上述既有文件。

---

## 1. 背景

### 1.1 市场存在一个明确的"官方软件失灵"窗口

Nikon 官方 SnapBridge 的口碑是**灾难级**的，这不是主观感受，是数据：

| 指标 | 数值 | 来源 |
| --- | --- | --- |
| App Store 综合评分 | **1.9 / 5** | 【外部】第三方聚合站 mwm.ai |
| 评论总量 | ~15,000 条 | 同上 |
| 1 星占比 | 9,659 / 15,000 ≈ **64%** | 同上 |
| 5 星占比 | 1,799 ≈ 12% | 同上 |
| 评论对应版本 | **v2.13.3** | 与你提供的 APK 版本号**完全一致** |

> ⚠️ 【外部·待核实】1.9/5 与评论分布来自第三方聚合页，非 Google Play 官方抓取。建议你在 Google Play 后台或 App Store 页面用"评分分布"再核一次，确认后再写进对外宣传材料。**但该版本评论与你手上 APK 版本号一致，交叉验证成立，趋势可信。**

用户抱怨高度集中在三类（【外部】dpreview 论坛 + 应用商店评论 + 摄影博主长文）：

1. **连接稳定性**（占压倒性多数）：配对步骤多、连上后随机断、重连要 30–60 秒、Android 后台被系统挂起导致 GPS/自动传输失效
2. **传输体验**：必须逐张点击选择、断连后不区分"已导入/未导入"、RAW 不支持下载
3. **UI 陈旧**：有用户直接写"Outdated UI"，认为显著劣于索尼/其他厂商

**结论：N-Link 的核心差异化不是"功能更多"，而是"比官方更可靠"。** 所有规划都应围绕这条主线，功能扩张不能侵蚀稳定性。

### 1.2 直接竞品格局：一个必须正视的对手

调研中发现一个**高度重合的开源竞品**，需要你优先处理：

| 项目 | 状态 | 说明 |
| --- | --- | --- |
| **ZENCHE（帧澈）** `Tauber01/ZENCHE` | 【实测】121 stars / 13 forks / MIT / Swift 主仓 / 创建于 2026-07-28 | 五端（macOS·Windows·Android·HarmonyOS·iOS）相机控制 + 传输；支持 50 款 Nikon/Sony/Canon 机型；已含**实时监看、AI 修图、专业显影、RGB 波形、条纹、自定义 LUT、2x 超采样、FTP/HTTP/WebDAV 收件、BLE 快门、XMP GPS 标记** |

> ⚠️ **两个需要你决策的风险点**
> 1. **命名冲突**：该仓库 `Tauber01/NikonLink` 曾以此为名（现重定向到 ZENCHE，改名时间不明）。你的项目叫 **N-Link**、仓库 `github.com/Wan-1230/N-link`、包名 `com.nikonlink.app`。三者与对方历史名高度接近。**建议尽快确认是否需要提前做品牌/商标层面的区隔**，避免后续被要求改名时成本过高。
> 2. **功能重合度**：对方 README 自述已覆盖"自定义 LUT + 实时监看 + AI 修图"，与你在 `PRD-N-Link-AI修图.md` 和 v0.2.0 的 C4 LUT 预留高度重合。但其 README 高度疑似 AI 生成、实机验证范围自述有限（"重要拍摄请始终保留机内存储卡"），**实际完成度待核实**。不建议因为对方写了就改变路线，但也不建议无视。

### 1.3 GitHub 开源生态普查（可参考的实现）

| 项目 | 语言 / Stars | 对 N-Link 的参考价值 |
| --- | --- | --- |
| `LennyObez/studio-camera` | Kotlin Multiplatform | **架构参考首选**：六品牌（Sony/Canon/Nikon/Fuji/Panasonic/OM）协议分层，Nikon 走 PTP/IP；含网格/直方图/峰值/斑马/安全框 overlay；KMP + Decompose + Koin。可作为 N-Link 多品牌扩展的架构蓝本 |
| `Tauber01/ZENCHE` | Swift/KMP，121★ | 直接竞品，见 1.2 |
| `devcxl/PhotoSync` | Kotlin + C++/JNI | **USB PTP 直连参考**：LibRaw + lcms2 + OpenCV 解码 20+ RAW 格式，minSdk 24；N-Link 若要补 RAW 解码可抄其 native 模块结构 |
| `JulianSchroden/cine_remote` | Dart/Flutter，68★ | Canon PTP/IP 无线控制，Flutter 实现（与影犀同技术栈） |
| `mmattes/ptpip`（56★）、`feklee/ptp.js`（49★）、`laheller/ptplibrary`（36★） | Python / JS / Java | PTP/IP 协议细节对照，协议层 bug 排查时的交叉参考 |
| `abc0922001/LutCam` | Kotlin + CameraX + **OpenGL ES 3.0** | **LUT 实现直接参考**：`.cube` 导入 + 自定义 GLSL shader 实时绑定预览，宣称"零延迟所見即所得" |

### 1.4 四款 APK 实测结果

以下全部为**【实测】**，来自 aapt2 / DEX 字符串提取 / assets 枚举。

#### 总览对比

| 维度 | 影犀 | ZDROP | ZRelay | SnapBridge（官方） |
| --- | --- | --- | --- | --- |
| 包名 | `com.camerasyncpro.app` | `com.zdrop.z6ii` | `com.geekmanlab.zrelay` | `com.nikon.snapbridge.cmru` |
| 版本 | 1.1.0 (vCode 20) | 1.0.190 (vCode 186) | 3.0.32 (vCode 332) | 2.13.3 (vCode 21333000) |
| 包体 | **79.2 MB** | 2.8 MB | 3.1 MB | 68.6 MB |
| 技术栈 | **Flutter**（libflutter.so / libapp.so） | 原生 Kotlin（几乎无 native） | 原生 Kotlin + **`libzrelay_core.so`（C++ PTP 内核）** | 原生 + `libJuno.so` / `libNis.so` / `libLsSec-jni.so` |
| minSdk / targetSdk | — / 36 | — / 35 | — / 36 | — / 35 |
| 定位 | **联机拍摄 + 后期创作** | **纯传输工具**（Z 系列） | **快传 + 遥控** | 官方全功能 |

#### 逐个拆解

**① 影犀（79.2 MB，Flutter）— 最值得研究的对手**

- **内置 40 个 `.cube` LUT，全部 `LUT_3D_SIZE 33`**，命名包括 Teal and Orange / Amelie / Godfather / Blade Runner / Matrix V1-V2 等
- 内嵌 `exiftool`（`assets/exiftool/README.md`）→ 做 EXIF 读写
- 10 个**水印模板**（`assets/watermark_templates/`：cinema_black / glass_plaque / frosted_frame 等）
- DEX/so 中提取到的关键符号：`3D LUT`、`40 Look LUTs`、`CameraConnectionMonitor`、`CameraWifiNetwork`、`liveViewSample=`、`is_raw` 数据库字段、`0x9431/raw-preview`、`0x100A/raw`
- Flutter 自带 shader 只有 `ink_sparkle.frag` / `stretch_effect.frag`，**未见自定义 LUT 着色器**

> 🔴 **两条必须注意的结论**
> 1. **版权风险**：这 40 个 LUT 的文件头明确写着 `#Created by: Denver Riddle` / `#Copyright: (C) Copyright Color Grading Central LLC`——这是**商业付费 LUT 包**。影犀是否为授权分发无法从静态分析判断，但 **N-Link 绝不能照抄这批资源**。自研 LUT 或使用明确 CC0/自售授权的资源。
> 2. **LUT 大概率非 GPU 实时**：APK 内没有自定义 GLSL/SPV 着色器，且 Flutter 的 `ColorFilter` 不支持 3D LUT。结合 33³ 文本格式（每个 ~950 KB）推测，这 40 个 LUT **更可能用于照片后期/导出，而非监看实时预览**。【推断】

**② ZDROP（2.8 MB，纯 Kotlin）— 极简传输工具**

- 无 native 库（仅 `libandroidx.graphics.path.so`），纯 Kotlin 实现 PTP/IP
- 功能文案聚焦："标准双通道连接失败"、"一键加载相册"、"缩略图与导入优先"、"后台连接保护"、"传输状态和诊断信息仅保存在本机"
- 明确自我声明"是非尼康官方的相机照片传输工具"
- **特点：把"连接诊断"做成卖点**，有传输计时、断点续传、相机事件监听

**③ ZRelay（3.1 MB，Kotlin + C++ 内核）— 技术含量最高的传输方案**

- `libzrelay_core.so` + `com/kw/ztransfer/NativePtpCore` → **PTP 协议栈用 C++ 写在 native 层**
- 功能覆盖：遥控拍摄、快门序列、图库下载、二维码、连接记忆、品牌定制（`assets/brand/`）
- 文案细节到位：考虑到了"大视频仅手动下载"、"不会删除传输历史和已接收文件"、"校验同一台相机后重新连接"

**④ SnapBridge（68.6 MB，官方）**

- 三个自研 native 库：`libJuno.so`（推测为影像/连接核心）、`libNis.so`（Nikon Imaging Service）、`libLsSec-jni.so`（安全）
- `assets/master/common/category*/` 下有**全机型资产图**（Z50II / Z5II / Z6III / ZR / D850 / D500 等），机型覆盖极广
- 声明权限 24 项，含 NFC、蓝牙全套、前台服务三类型

**竞品功能矩阵**

| 能力 | N-Link（现状） | 影犀 | ZDROP | ZRelay | SnapBridge | ZENCHE【外部】 |
| --- | --- | --- | --- | --- | --- | --- |
| PTP/IP 连接 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| USB PTP | ✅ | — | — | — | — | ✅ |
| BLE | ✅ | — | — | — | ✅ | ✅ |
| 实时监看 | ✅（~15fps） | ✅ | ❌ | ✅ | ✅ | ✅ |
| 直方图 | ✅ | 【推断】有 | ❌ | ❌ | ❌ | ✅ |
| 斑马纹/峰值 | ❌ | 待确认 | ❌ | ❌ | ❌ | ✅（条纹） |
| **实时 LUT** | ❌ | 【推断】否 | ❌ | ❌ | ❌ | ✅（自述） |
| 静态 LUT / 滤镜 | ❌ | **✅ 40 个** | ❌ | ❌ | ❌ | ✅ |
| RAW 传输 | ✅ | ✅ | ✅ | ✅ | ❌（用户主要抱怨点） | ✅ |
| 水印模板 | ❌ | **✅ 10 个** | ❌ | ❌ | ❌ | ❌ |
| 传输断点续传 | 部分 | — | ✅ | ✅ | ❌ | ✅ |
| 连接诊断 | ✅ | ✅ | **✅ 强** | ✅ | ❌ | ✅ |
| AI 修图 | 计划中 | — | ❌ | ❌ | ❌ | ✅（自述） |

**读表结论**：影犀主打"联机 + 出片"、ZDROP/ZRelay 主打"传得稳"、官方主打"机型全但不稳"。**N-Link 目前是唯一同时具备 USB PTP + 蓝牙 + 监看 + 传输的选手，但缺"出片"这一环**——这正是 v1.3.0–v1.4.0 要补的。

### 1.5 N-Link 现状（源码实读）

| 项目 | 实测值 |
| --- | --- |
| 代码规模 | 21,621 行 Kotlin（53 个 .kt 文件） |
| minSdk / targetSdk / compileSdk | 29 / 35 / 35 |
| 架构 | MVVM + Hilt + Room + Coroutines/Flow |
| 监看取帧 | `LiveViewManager` 走 PTP `0x9203` 逐帧拉 JPEG，`FRAME_INTERVAL_MS = 66L` → **约 15 fps** |
| 监看渲染 | `BitmapFactory.decodeByteArray` → `ImageView.setImageBitmap`（**纯 CPU 解码 + 系统控件渲染，无 OpenGL**） |
| 直方图 | `HistogramView.setFrame(bitmap)`：`drawBitmap` 缩到 scratch → `getPixels()` → **CPU 全像素采样** |
| overlay | `ViewfinderOverlayView` Canvas 自绘（网格/对焦框） |
| 已有 LUT 规划 | v0.2.0 PRD **C4「LUT 接口预留」**：仅数据模型 + 挂点，无 UI |

> 代码注释里保留了诚实的痕迹：帧率目标写的是 `≥ 30fps`，实际常量是 `66ms ≈ 15fps`，注释解释为"预留保活通道余量防断联"。**这是当前最大的体验欠账。**

---

## 2. 目标用户与场景

### 2.1 主用户画像

| 画像 | 特征 | 核心诉求 | N-Link 现状 |
| --- | --- | --- | --- |
| **A. 外出创作摄影师**（主力） | 尼康 Z/单反用户，拍完要马上发片 | 快速导片 + 现场调色出图 | 传输可用，出片链路缺失 |
| **B. 棚拍/联机用户** | 固定场景，手机当监视器 | 监看稳定、构图辅助、所见即所得 | 15fps 偏弱，无色彩预览 |
| **C. 视频/Log 拍摄者** | 拍 N-Log，需要监看还原 | **实时 LUT 还原** | ❌ 完全空白 |
| **D. 被官方 App 劝退的用户** | 用过 SnapBridge，已放弃 | 一个"能连上"的工具 | ✅ 已是核心卖点 |

### 2.2 关键场景（按价值排序）

1. **【C 类 · 最高价值差异化】Log 拍摄实时还原**：拍 N-Log 时手机监看套还原 LUT，现场判断曝光和色彩——这是目前**所有竞品在 Android 端都没有做扎实**的点（影犀的 LUT 疑为后期用，ZENCHE 自述有但完成度待核实）
2. **【A 类 · 最大盘子】拍完 → 选片 → 调色 → 水印 → 出图**闭环
3. **【D 类 · 获客】"比 SnapBridge 靠谱"**：首连成功率、断线自愈、后台保活
4. **【B 类】监看辅助**：斑马纹、峰值对焦、安全框

---

## 3. 核心功能规划

### 3.1 版本总览

| 版本 | 主题 | 一句话 | 周期（建议） |
| --- | --- | --- | --- |
| **v1.2.0** | 地基 | 把 15fps 提到 25–30fps，LUT 才有意义 | 3 周 |
| **v1.3.0** | 监看色彩 | LUT 引擎 + 监看实时预览 + 自定义导入 | 5 周 |
| **v1.4.0** | 出片闭环 | LUT 落盘导出 + 水印模板 + EXIF | 4 周 |
| v1.5.0+ | 专业监看 | 斑马纹 / 峰值 / 波形 / 安全框 | 待定 |

### 3.2 v1.2.0 — 地基（P0，先于 LUT）

> **为什么必须先做这个**：在 15fps 的监看画面上加 LUT，等于给一辆限速 60 的车换涡轮。用户感知到的是"卡"，不是"好看"。

| ID | 功能 | 优先级 | 要点 |
| --- | --- | --- | --- |
| G1 | **帧率提升：15fps → 25–30fps** | **P0** | 见 4.4 的性能预算。核心是把 JPEG 解码和渲染从主线程/ImageView 挪到专用渲染线程 |
| G2 | 解码/渲染线程分离 | P0 | 解码放 `Dispatchers.Default`，渲染走 GPU 线程，UI 线程只做状态更新 |
| G3 | 直方图移到采样降频 | P1 | 当前 `getPixels()` 全像素扫描；改为降采样到 1/4 后统计，或直接在 GPU 侧读回 |
| G4 | 首连成功率埋点 | P1 | 记录"首次配对成功 / 重试次数 / 失败原因"，这是后续所有稳定性优化的度量基础 |

**验收标准**

| AC | 场景 | 预期 |
| --- | --- | --- |
| AC-1 | Z6II 5GHz Wi-Fi 连续监看 5 分钟 | 平均帧率 ≥ 25fps，无掉帧到 0 的停顿 |
| AC-2 | 监看中操作参数滚轮 | UI 无卡顿（掉帧 ≤ 2 帧） |
| AC-3 | 开启直方图 | 帧率下降 ≤ 10% |

### 3.3 v1.3.0 — 监看色彩（本 PRD 核心章节）

| ID | 功能 | 优先级 | 说明 |
| --- | --- | --- | --- |
| **L1** | **实时 LUT 监看** | **P0** | 详见第 4 章可行性论证 |
| L2 | LUT 开关 + 强度滑杆（0–100%） | P0 | 强度在 shader 里 `mix(orig, luted, intensity)` |
| L3 | 内置基础 LUT 包（8–10 个） | P0 | **自研或明确授权**，见 4.5 版权约束 |
| L4 | 用户导入 `.cube` | P1 | 解析 17³ / 33³ / 64³，DOMAIN_MIN/MAX 兼容 |
| L5 | LUT 快速切换（左右滑 / 缩略条） | P1 | 监看页底部横向胶囊条，实时切换不中断 |
| L6 | 前后对比（长按看原图） | P2 | |
| D4' | 斑马纹（v0.2.0 D4 已列，优先级上调） | P1 | 与 LUT 同处一条 shader 链，一起做成本最低 |

**验收标准**

| AC | 场景 | 预期 |
| --- | --- | --- |
| AC-1 | 开启 LUT，1080p 监看 | 帧率相对不开 LUT 下降 ≤ 5% |
| AC-2 | 切换 LUT | 切换延迟 < 100ms，不黑屏、不断流 |
| AC-3 | 导入 33³ `.cube` | 3 秒内完成解析并生效，色彩与桌面端（如 Lightroom）肉眼一致 |
| AC-4 | 导入 64³ / 非标准 DOMAIN 的 cube | 不崩溃，给出明确提示 |
| AC-5 | 低端机（骁龙 6 系 / 天玑 700） | 自动降级到 17³ 采样或关闭实时 LUT 并提示 |

### 3.4 v1.4.0 — 出片闭环

| ID | 功能 | 优先级 | 说明 |
| --- | --- | --- | --- |
| C1 | **LUT 落盘导出** | P0 | 把 LUT 应用到已下载的照片导出为新文件（不覆盖原片，对齐 ZENCHE"只创建副本"的做法） |
| C1b | 水印模板（对齐影犀的 10 款思路） | P1 | 自研模板，不复制对方设计 |
| C5 | 导出质量/尺寸选择 | P1 | 长边压缩、画质档位 |
| C6 | 批量导出 | P1 | 复用 v0.2.0 的 F1 批量选择 |

---

## 4. 专项论证：实时 LUT 预览是否可行？

### 4.1 结论先行

> ## ✅ 可行，建议做，但必须排在 v1.2.0 帧率优化之后。
>
> - **技术可行性**：成熟方案，Android 上有大量已验证实现（LutCam 用 OpenGL ES 3.0 做实时 LUT 绑定；GPUImage LookupFilter 是十年老方案）
> - **性能开销**：**可忽略**。1080p 单帧 LUT 采样在中端 GPU 上约 0.5–1.0 ms，相对当前 66ms 帧间隔占比 **1.5% 以内**。真正的瓶颈是 JPEG 解码（8–15ms）和 15fps 的取帧节奏，不是 LUT
> - **设备兼容性**：推荐方案（GLES 2.0 + 2D 瓦片 LUT）覆盖 **minSdk 29 全部设备，无兼容风险**；3D 纹理方案和 AGSL 方案均有覆盖率/黑屏风险，**不作为主路径**
> - **最大风险不是技术，是包体和版权**：影犀 79MB APK 里约 38MB 是 LUT 文本——**不能这么干**

### 4.2 三种技术方案对比

| 方案 | 原理 | 兼容性 | 性能 | 风险 | 结论 |
| --- | --- | --- | --- | --- | --- |
| **A. GLES 2.0 + 2D 瓦片 LUT**（推荐） | 把 33³ 展开成 `33×33` 瓦片拼成的 2D 纹理（1089×33，对齐到 2048×64），shader 内双瓦片插值 | **100%**（ES 2.0 = Android 全体） | 每像素 2 次采样 | 瓦片边缘需半像素内缩，否则串色 | ✅ **主路径** |
| B. GLES 3.0 + `sampler3D` | `glTexImage3D` 直接上传 3D 纹理，shader 用 `texture(lut, color.rgb)` 硬件三线性插值 | ~99%（需 ES 3.0，Android 4.3+） | 每像素 1 次采样，精度最优 | 【外部】有实测文章指出部分机型/`#extension GL_OES_texture_3D` 下**黑屏**、iOS 不生效；LutCam 用 ES 3.0 未报问题但仅测 Pixel | ⚠️ 可作为高配机增强路径 |
| C. AGSL `RuntimeShader`（API 33+） | Canvas 管线内着色 | **API 33+ 约 55–60% 覆盖率**（2026 年）【推断·待核实】 | 好 | AGSL uniform **不支持 sampler3D**，只能手动做 2D 瓦片插值；且 minSdk 29 需写降级分支 | ❌ 不作为主路径 |
| D. CPU（`ColorMatrix` / RenderScript） | — | — | ❌ 3D LUT 无法用 ColorMatrix 表达；RenderScript 已废弃 | — | ❌ 排除 |

**推荐 A，并且预留 B 作为高配机可选路径**（能力探测命中 ES 3.0 时走 B，精度更好）。

### 4.3 实现路径（对接现有代码）

当前链路：

```
PTP 0x9203 → ByteArray(JPEG) → BitmapFactory.decodeByteArray → ImageView.setImageBitmap
                                        ↑ 8-15ms CPU               ↑ 系统纹理上传
```

改造后：

```
PTP 0x9203 → ByteArray(JPEG)
                 ↓ 解码线程（Dispatchers.Default）
            BitmapFactory.decodeByteArray
                 ↓
        GLSurfaceView / TextureView 渲染线程
                 ↓ texImage2D（帧纹理，复用同一 texture id 避免反复分配）
        ┌────────────────────────────┐
        │ Fragment Shader            │
        │  1. 采样帧纹理             │
        │  2. 采样 LUT 瓦片纹理 ×2   │
        │  3. mix(orig, lut, intensity) │
        │  4. （可选）斑马纹叠加      │
        └────────────────────────────┘
                 ↓
              上屏
```

**改造代价**：`fragment_liveview.xml` 的 `ivLiveView`（ImageView）→ 替换为自定义 `LutSurfaceView`；`LiveViewFragment.kt:543-554` 的 `latestFrame.collect` 分支改投渲染线程。**约 3 个文件改动，与 v1.2.0 的 G1/G2 高度重合，可一起做。**

### 4.4 性能开销验算（1080p / 1920×1080）

| 环节 | 计算 | 中端机（~4 Gtexel/s） | 低端机（~1 Gtexel/s） |
| --- | --- | --- | --- |
| 帧像素数 | 1920 × 1080 | 2,073,600 px | 2,073,600 px |
| LUT 纹理采样次数 | 2 次/px（双瓦片插值） | 4.15 M texel | 4.15 M texel |
| **LUT shader 耗时** | 采样数 ÷ 填充率 | **≈ 1.04 ms** | **≈ 4.15 ms** |
| ALU（mix/坐标计算，~10 条指令） | 20.7M instr | < 0.5 ms | < 1.5 ms |
| **LUT 总开销** | | **≈ 1.5 ms** | **≈ 5.7 ms** |
| 占当前帧间隔（66ms）比例 | | **2.3%** | **8.6%** |
| 占 30fps 帧间隔（33ms）比例 | | **4.5%** | **17%** |

> 即便在最差情况（低端机 + 30fps），LUT 开销仍在可接受范围。**对照：JPEG 软解码 8–15ms 才是绝对大头。**

**内存占用**

| 项 | 计算 | 大小 |
| --- | --- | --- |
| 33³ LUT（RGBA8，运行时） | 1089 × 33 × 4 B | **143.7 KB**（对齐 2048×64 后 512 KB） |
| 17³ LUT（RGBA8） | 289 × 17 × 4 B | **19.7 KB** |
| 帧纹理（1080p ARGB_8888） | 1920×1080×4 | 8.3 MB（现有 Bitmap 已在用，不新增） |
| **LUT 新增内存** | 单张常驻 | **< 0.5 MB** |

**包体验算（关键约束）**

| 存储方式 | 单个 33³ LUT | 40 个 | 说明 |
| --- | --- | --- | --- |
| `.cube` 文本（影犀做法） | ~950 KB（实测 901–976 KB） | **≈ 38 MB** | 🔴 影犀 79MB APK 中约 49% 是它 |
| 二进制 RGBA8 打包 | 143.7 KB | 5.75 MB | 省 85% |
| 二进制 + 降 17³ | 19.7 KB | **0.79 MB** | 电影风格 LUT 用 17³ 足够 |

> **建议**：内置 8–10 个自研 17³ LUT（**< 200 KB**），33³ 精品包走**按需下载**（复用你已有的夸克网盘更新通道），用户 `.cube` 导入走运行时解析。
> 这样 v1.3.0 的**包体增量目标 < 500 KB**。

### 4.5 版权与合规约束（必读）

- 🔴 影犀内置 40 个 LUT 的文件头标注 `#Copyright: (C) Copyright Color Grading Central LLC`（Denver Riddle），**这是商业付费资源**。N-Link **不得复制、转换或再分发**这批文件。
- 自研 LUT 建议：基于公开标准色彩科学（如 Rec.709 / N-Log 还原）自行生成，或使用明确标注 CC0 / 自购商用授权的资源，**并在应用内保留 LUT 来源署名**。
- 应用内"导入自定义 `.cube`"属用户自带内容，责任主体为用户，需在设置页加一句免责说明。

### 4.6 风险登记

| 风险 | 等级 | 应对 |
| --- | --- | --- |
| 帧率不足导致 LUT 体验打折 | **高** | 强制排在 v1.2.0 之后；AC-1 卡帧率下降 ≤5% |
| 低端机 GPU 填充率不足 | 中 | 能力探测：ES 3.0 + 高填充率走 B，否则 17³ 采样，再降级则关闭实时 LUT 并提示（沿用 v0.2.0 B2 能力矩阵） |
| 瓦片串色（LUT 边缘渗色） | 中 | shader 内半像素内缩 `0.5/lutWidth`，标准做法，单测用纯色图验证 |
| Log 素材需要 LUT 前先做 Log→Linear 变换 | 中 | v1.3.0 只做"LUT 已是 Rec.709 输入输出"的常规 LUT；N-Log 专用还原 LUT 归入 v1.4.0，需单独验证 |
| 与 ZENCHE 功能/命名冲突 | 中 | 见 1.2，需你决策 |

---

## 5. 优先级与排期

### 5.1 排期总表（单人开发，按周，2026-09-08 起）

| 周次 | 版本 | 任务 | 产出 |
| --- | --- | --- | --- |
| W1–W3 | **v1.2.0** | G1 帧率提升、G2 线程分离、G3 直方图降采样、G4 埋点 | 帧率 ≥25fps 的监看基线 |
| W4–W5 | **v1.3.0** | L1 LUT 引擎（方案 A）+ 渲染管线改造 + L2 强度滑杆 | 能跑通的单 LUT 实时预览 |
| W6–W7 | | L5 快速切换 + L3 内置 LUT 包 + 低端机降级策略 | 可内测版本 |
| W8 | | L4 用户导入 `.cube` + D4' 斑马纹 + 真机回归 | **v1.3.0 发布候选** |
| W9–W12 | **v1.4.0** | C1 LUT 落盘导出、C1b 水印、C5/C6 导出选项 | 出片闭环 |

> 排期为【推断】，基于单人全职开发 + 需要真机（尼康机身）验证。**每个版本末尾预留 3–5 天真机回归**，联机类功能的 bug 几乎全在实机上暴露。

### 5.2 优先级判断依据

| 功能 | 优先级 | 判断依据 |
| --- | --- | --- |
| 帧率优化 | **P0（最高）** | 一切监看体验的前提；N-Link 现为 15fps，低于自设目标 |
| 实时 LUT | **P0** | 唯一能形成"竞品都没做扎实"差异化的功能；技术可行且成本可控 |
| 连接稳定性 | **P0（持续）** | SnapBridge 1.9 分的根因，N-Link 的获客基本盘，不可中断 |
| 斑马纹/峰值 | P1 | 与 LUT 同链，边际成本低；棚拍用户刚需 |
| 水印 | P1 | 影犀已验证需求，但非差异化点 |
| 多品牌支持 | P2 | studio-camera 已铺好架构参考，但会稀释尼康深耕优势 |

---

## 6. 需要你决策的问题

| # | 问题 | 我的建议 |
| --- | --- | --- |
| 1 | **是否与 ZENCHE 做品牌区隔？** 对方曾用 NikonLink 名，你的 N-Link / `com.nikonlink.app` 高度接近 | 建议尽早确认商标与仓库命名，改名的成本随用户量增长 |
| 2 | **LUT 资源从哪来？** 自研 / 购买商用授权 / 用户自带 | 建议：v1.3.0 只做**引擎 + 用户导入**，内置包推迟到 v1.4.0 并走自研或授权。这样能最快验证需求，且不背版权风险 |
| 3 | **是否先做"照片 LUT"还是"监看 LUT"？** | 建议先做监看（差异化），落盘导出放 v1.4.0。但**先做静态照片 LUT 的技术风险更低**——如果实机验证发现监看帧率撑不住，可回退到静态 |
| 4 | **历史 PRD 文件是否重命名？** 根目录 `PRD-N-Link-v0.1.4-*.md`、`PRD-N-Link-v0.2.0-*.md` 仍为 0.x 命名，实际对应 1.1.0 前后的工作 | 建议归档或重命名为 1.x；新文件一律用 1.x，长期两套口径会误导自己 |

---

## 附录 A：调研可复现命令

```bash
# 四款 APK 基础信息
aapt2 dump badging <apk> | grep -E "^package|uses-permission|native-code|launchable"

# 影犀 LUT 资源枚举
unzip -l 影犀.apk | grep "assets/luts/"        # 40 个

# LUT 分辨率确认
unzip -p 影犀.apk "assets/flutter_assets/assets/luts/Amelie.cube" | grep LUT_3D_SIZE
# → LUT_3D_SIZE 33

# ZENCHE 仓库实况
gh api repos/Tauber01/ZENCHE --jq '{stars:.stargazers_count,forks:.forks_count,lang:.language,lic:.license.spdx_id}'

# N-Link 帧率常量
grep -n "FRAME_INTERVAL_MS" app/src/main/java/.../LiveViewManager.kt   # 66L
```

## 附录 B：N-Link 监看关键代码位置

| 位置 | 内容 |
| --- | --- |
| `camera/liveview/LiveViewManager.kt:38` | `FRAME_INTERVAL_MS = 66L`（≈15fps） |
| `camera/liveview/LiveViewManager.kt:397` | `startFrameLoop()` 取帧循环 |
| `camera/liveview/LiveViewFragment.kt:543-554` | `BitmapFactory.decodeByteArray` → `setImageBitmap` |
| `camera/liveview/HistogramView.kt:114-125` | `getPixels()` CPU 全像素统计 |
| `res/layout/fragment_liveview.xml:8` | `ivLiveView`（ImageView，改造点） |
| `PRD-N-Link-v0.2.0-工作流版.md:309` | C4 LUT 接口预留（已有规划） |
