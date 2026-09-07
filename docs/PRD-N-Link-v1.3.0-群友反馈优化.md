# N-Link v1.3.0 — 群友反馈问题专项优化

> 输入：`docs/feedback-v1.2.0-20260907.md`（反馈交流群，2026-09-07）
> 整理日期：2026-09-08 · 版本目标：v1.3.0（versionCode 11）
> 开发分支：`feat-v130-feedback-optimize`（基线 = v1.2.0 发布提交 `c7e929c`，可一键回退）
> 证据等级：**【实】**= 已在本仓代码中核实（附文件行号）；**【推】**= 基于代码推断，需真机日志确认
> 决策状态：**D1 取消下载页，改为「剩余下载进度」** · D2 不做 · D3 自动关闭筛选 · D5 做手动 IP · **B1/N2、N1 本期不做**

---

## 〇、范围铁律

1. **只做本文范围内的项**，逐条对应反馈编号，不做顺手重构。
2. **已跑通的功能一律冻结**：逻辑、交互、界面、样式保持原样——连接页（除 STA 发现外）、监看/全屏监看、遥控拍摄、设置页、本地相册、预览页、多选/标记、排序与日期分组、样式体系：**不动**。
3. 每处改动在提交信息里标注对应编号（B2~B5 / O2~O4 / 相机页按钮 / 剩余下载进度）。
4. 标注 **【推】** 的方案，拿不到真机日志时**只做可诊断性改造**，不猜着改业务逻辑。

### 本期不做（明确延后）

| 项 | 内容 | 原因 |
|---|---|---|
| **B1 / N2** | Z6II / Z50II 看不了相册 | 用户决定延后；根因为【推】，需真机日志定位后再单独立项 |
| **N1** | 200GB 大卡窗口化/分页加载 | 用户决定延后；改动面最大 |
| **O1 (D2)** | 「下载中遮罩」 | 代码核查**不存在全屏遮罩**（仅 `progressDownload` 3dp 横条），本条关闭 |
| **下载管理页（原 D1）** | 独立下载页 + `TransferRecord.status` 扩展（失败/中断落库） | 用户决定取消：本地照片页即已下载集合，相机页开启隐藏后剩下的就是未下载项，无需重复建设 |
| **B3** | 滑到底后要滑回去 | 原方案依赖 N1 窗口化（已延后）→ 本期**只由 B2 的主线程节流间接缓解**，列入观察；若真机仍复现，下一版与 N1 合并处理 |

### 冻结面清单

| 冻结对象 | 说明 |
|---|---|
| 排序、日期分组、筛选 chip 行 | 仅 O4 空结果兜底可动 |
| 缩略图缓存结构（LruCache 64MB / `thumbnails_v2` / 长边 1024） | 不动，B2 只改请求校验与刷新节奏 |
| 下载链路（串行队列、断点续传、速度统计、历史库 `TransferRecord`） | **完全不动**（失败/中断落库随下载页一并取消） |
| 已标记页 / 本地页现有行为 | 不动；新增按钮仅在相机页生效 |
| AP / USB / BLE 连接路径 | 不动，B4 只改 STA 发现与失败提示 |
| MainActivity 四个常驻 Fragment 结构 | 不动（本期不新增页面/不插 tab） |

---

## 一、反馈 → 改动映射总表

| 编号 | 反馈问题 | 根因判断 | 方案 | 优先级 | 主要改动文件 |
|---|---|---|---|---|---|
| **B2** | 快速滑动卡住 / 花屏 / 堆叠 | 【实】`onBind` 无在途取消、无 handle 校验（PhotoGridAdapter L279-290）；【实】每来一张缩略图就全量 `submit` → `buildItems` O(n) + `DiffUtil` 主线程 O(n)（Fragment L704-714） | handle 绑定校验 + 占位清空 + 缩略图刷新节流合并 | P0 | `PhotoGridAdapter.kt`、`TransferFragment.kt` |
| **B3** | 滑到底要滑回去 | 【实】无分页/预取 + 主线程 DiffUtil 风暴 | **本期仅随 B2 缓解**，列入观察项（N1 延后） | P2 | 无（同 B2） |
| **B4** | STA 模式发现不了相机 | 【实】AP 有硬编码 `192.168.1.1` 兜底（ConnectionManager L629-631）+ 已知网关表；STA 无先验 IP，发现失败**完全静默** | 失败给明确原因 + **手动输入相机 IP** + 扫描增强 | P0 | `WifiScanner.kt`、`ConnectionManager.kt`、`DashboardFragment.kt` |
| **B5 / O3** | 导入后手机相册看不到 | 【实】`saveFileToMediaStore()`（TransferManager L1329）写完**从未调用任何媒体库扫描** | 落盘后补 `MediaScannerConnection.scanFile` | P0 | `TransferManager.kt` |
| **O2** | 希望有「还剩多少张未下载」计数 / 剩余下载进度 | 【实】无现成文案；⚠️ `tvMessage` 在 `layoutEmpty` 内（fragment_transfer.xml L162-171），**列表非空时不可见**，不能挂在那里 | 新增**剩余下载进度统计行**（chip 行下方、下载进度条上方），仅相机照片源可见 | P1 | `fragment_transfer.xml`、`TransferFragment.kt`、`TransferViewModel.kt` |
| **O4** | 筛选态全下完看不到照片 | 【实】过滤后为空 → 直接进空态 | **自动关闭筛选 + Toast 提示**（D3 定稿） | P1 | `TransferViewModel.kt` / `TransferFragment.kt` |
| ~~新增下载页~~ | ~~下载管理页~~ | — | **取消**：本地照片页即已下载集合；相机页开启「不重复下载」后看到的就是未下载项，无需独立下载页 | — | — |
| **新增** | 相机页「不重复下载已下载照片」按钮 | — | 复用现有按钮控件与状态（§3.1） | P0 | `fragment_transfer.xml`、`TransferFragment.kt`、`TransferViewModel.kt` |
| ~~O1~~ | ~~下载遮罩~~ | 【实】不存在遮罩 | **关闭（D2）** | — | — |
| ~~B1/N2~~ | ~~机型看不了相册~~ | 【推】需日志 | **延后** | — | — |
| ~~N1~~ | ~~大库窗口化~~ | 【实】全量枚举 + 全量元数据 | **延后** | — | — |

---

## 二、现状核实（实施直接引用）

> 注：§2.1 / §2.2 中关于队列与 Activity 范本的调研，原为「下载管理页」方案准备；
> 该方案已取消，**本期不落地这两节的任何改动**，仅作存档（失败/中断持久化不再需要）。

### 2.1 下载链路与数据模型【实】

- 队列：**串行，并发 = 1**。`TransferManager.transferQueue: MutableList<TransferTask>`（L129）、`currentJob` 单例（L131）；`processQueue()`（L1045-1097）只取首个 `PENDING`。
- 任务：`TransferTask(file, status, tempFile)`（L1436-1440）；`TransferTaskStatus = PENDING / DOWNLOADING / COMPLETED / FAILED / CANCELLED`（L1442-1444）。
- 对外状态：`TransferState = Idle / Downloading(file, received, total) / Paused / Completed(file)`（L1449-1454）——**无 Failed 态**；失败走 `TransferResult.Failed(reason)`（L1459-1463）+ Toast/通知。
- 暂停/恢复：`pause()`（L976-994）取消 job 置 `isPaused`，`resume()`（L999-1009）继续队列；被取消任务保留 `tempFile`，`downloadToFile`（L650-704）以 `tempFile.length()` 续传。
- 自动重试：`runTask`（L1119-1124）首次失败 `delay(1500)` 重试一次；**无用户手动重试入口**。
- 持久化：`TransferRecord(id, fileHandle, fileName, fileSize, localPath, transferTime, status)`（`NLinkDatabase.kt` L19-31），`TransferHistoryDao.getAll(): Flow<List<TransferRecord>>`（L48-49）、`findTransferredHandles`（L58-62）。**⚠️ 只有成功才落库（status 默认 completed），失败/中断不入历史。**
- 可观测流：`transferState`（L157-158）、`queue`（L160-161）、`transferSpeedBps`（L172-173）。批量下载 = 逐个串行，界面目前只显示单文件名 + 百分比（Fragment L848-861），**无 N/M 计数**。

### 2.2 导航结构【实】

- `MainActivity`：4 个常驻 Fragment（`DashboardFragment / TransferFragment / RemoteFragment / SettingsFragment`，L75-86），`add` 后 `hide/show` 切换（L158-165 / L201-210），**非 BottomNavigationView、非 ViewPager2**。→ 新页做成独立 Activity 最安全，不动 MainActivity。
- `PreviewActivity` 范本：Manifest 声明 `android:exported="false"`（AndroidManifest.xml L94-96）；全局右推入转场由 `Theme.NLink` 的 `windowAnimationStyle = NlWindowAnim`（themes.xml L35 / L48-53）提供，无需单设；静态 `start(context, files, position)`（L60-76）用基本类型数组传参；返回 `binding.btnBack.setOnClickListener { finish() }`（L123）。

### 2.3 相册与开关（同上一版核实，未变）【实】

- `_onlyNotDownloaded`（VM L171，默认 false）已在 `filteredPhotos` 生效（L209-211），入口是 chipRow 的 `chipNotDownloaded`（Fragment L216 / L212）。
- `btnSkipDownloaded`（fragment_transfer.xml L29-38）位于 **topBar 内、标题（weight=1）右侧、btnRefresh 左侧**，40dp ImageView，默认 gone，仅 MARKED 可见。
- topBar 现有顺序：标题 → btnSkipDownloaded → btnRefresh → btnSort → btnMultiSelect（高 56dp，`page_margin` = 24dp）。

### 2.4 可复用样式资源【实】

`styles.xml`：`NlText.Title`(L5) / `SectionTitle`(L14) / `Subtitle`(L21) / `GroupTitle`(L28)；`NlButton.Primary`(L39) / `Outline`(L51) / `Contrast`(L63)；`NlCard`(L75)。
Drawable：`bg_chip`、`bg_chip_selected`、`bg_card`、`bg_info_grid_item`、`bg_floating_bar`；图标 `ic_download`、`ic_check`、`ic_refresh`、`ic_chevron_right`。
尺寸：`page_margin` 24dp、`divider_height` 1dp、颜色 `@color/divider`；栏高惯例 56dp；列表项惯例 `paddingVertical` 10dp。可参考布局：`item_recent_device.xml`（图标 + 标题 + 副标题 + 操作按钮）、`item_photo_date_header.xml`（分组标题）。

---

## 三、改动设计

### 3.1 模块 A：相机照片页「不重复下载已下载照片」按钮

**不新增控件、不新增状态。**

| 项 | 结论 |
|---|---|
| 控件 | 复用 `btnSkipDownloaded`（位置正是标题右侧、刷新键左侧） |
| 状态 | 复用 `_onlyNotDownloaded`（VM L171），与 chipRow「未下载」chip 共享状态、天然联动 |
| 默认值 | `false`（关闭）—— 沿用相机页现有逻辑，进页行为与今天一致 |
| 可见性 | 「仅 MARKED」→「MARKED 或 CAMERA」；LOCAL 仍隐藏 |
| 样式 | 完全沿用现有渲染（Fragment L250-263），不新增 drawable、不改尺寸色值 |

**改动点（4 处）**

1. `renderSkipDownloadedChip()`（L250-253）可见性条件改为 `MARKED || CAMERA`。
2. 点击按源分发：CAMERA → `setOnlyNotDownloaded(!...)`；MARKED → 维持 `setSkipDownloadedInMarks(!...)`；入参 `enabled` 按源取对应 StateFlow。
3. 状态订阅与切源刷新：`activeAlbum.collect`（L609-628）与 `onlyNotDownloaded.collect`（L606）都调渲染；修正 `renderNotDownloadedChip()`（L235-247）末尾固定传 `skipDownloadedInMarks` 的写法。
4. **防御性改动**：`filteredPhotos` 的 combine（L209-211）加 `activeAlbum == CAMERA` 判断，避免开关误作用于本地页/已标记页。

**隐藏 / 恢复与滚动位置保持**

- 关闭时（点击回调内、列表重算前）记录锚点 `anchorHandle` = 当前第一个可见照片的 handle，置 `pendingRestoreAnchor`（沿用现有 `pendingScrollToTop` 的 post 模式，不新增机制）。
- `uiPhotos` 收集器在 `adapter.submit()` 之后 `post { }`：新列表里 `indexOfFirst { it.handle == anchorHandle }`，命中则 `scrollToPositionWithOffset(index, 0)`，未命中（照片已不在列表）退化为 `scrollToPosition(0)`。
- 过滤只筛除、不改相对顺序 → 「按原顺序恢复」天然满足。

**验收**：相机页标题右侧出现该按钮、样式同已标记页、默认关闭；点开后已下载项消失且其余顺序不变；关闭后回到原位（±1 行）；切到本地/已标记页不受影响；与 chip 任一操作两入口选中态同步。

---

### 3.2 模块 B：快速滑动渲染修复（B2，间接缓解 B3）

**3 处改动，均不动缓存结构**

1. **绑定校验**：`onBindViewHolder`（PhotoGridAdapter L279-290）触发 `onRequestThumb` 前把 `file.handle` 记入 ViewHolder 的 `boundHandle`；缩略图回调落地时校验 `vh.boundHandle == file.handle`，不一致直接丢弃。
2. **占位清空**：`onBind` 时内存未命中则先把 ImageView 置占位/空（消除复用残留位图 —— **花屏/堆叠的直接成因**）。
3. **刷新节流**：`thumbnails.collect`（Fragment L704-714）改为 **100ms 合并窗口**，窗口内用 `notifyItemRangeChanged` 局部刷新（沿用 HD 升级 L718-726 的做法），窗口结束再发一次全量 submit。

**验收**：快速滑动 200+ 张无花屏/堆叠/卡住；单张与首屏加载速度不下降。B3 是否缓解请真机顺带观察记录。

---

### 3.3 模块 C：导入后媒体库即时刷新（B5 / O3）

- `saveFileToMediaStore()`（TransferManager L1329）落盘成功后，对真实路径调用 `MediaScannerConnection.scanFile(context, arrayOf(realPath), null, null)`。
- 真实路径：`DCIM/N-Link/<fileName>` 或 `Download/N-Link/<fileName>`（按 `settings.savePath`，L1331-1335）；优先用 `OpenOutputStream` 的 Uri 反推，取不到则退化为 `ACTION_MEDIA_SCANNER_SCAN_FILE` 广播携带 Uri。

**验收**：下载完成 3s 内系统相册 / Google 相册刷新即可见，无需手动刷新。

---

### 3.4 模块 D：STA 发现失败不再静默（B4）

| 级别 | 内容 | 证据 |
|---|---|---|
| **P0（必做）** | ① 候选为空时给**具体原因**：「未发现相机 · 已扫描 X 个地址（mDNS/NSD/网段均无响应）」；② **手动输入相机 IP** 入口，直连 `15740`（相机机身菜单可查 IP）；③ 各通道命中数写日志 | 【实】当前失败完全静默（仅 Timber.w） |
| **P1（建议）** | ① ARP 命中条目**全部**走 `PtpIpProbe`；② mDNS `soTimeout` 1000ms → 2500ms 并重试 1 次；③ 总超时 12s → 18s（后台线程，不卡 UI） | 【实】参数确凿；【推】能否救回 STA 需真机验证 |
| **P2（诊断）** | 抓 `WifiScanner` / `PtpIpProbe` 日志，区分**组播被过滤** vs **客户端隔离（AP isolation）** —— 后者无解，只能走手动 IP 或 AP 模式 | 【推】 |

**验收**：STA 失败时能看到明确原因；已知相机 IP 时手动输入可连上。

---

### 3.5 模块 E：剩余下载进度（O2，替代原「下载管理页」方案）

> **变更说明**：原 D1 方案是新增下载管理页，现取消。理由：本地照片页本身就是已下载集合，
> 且相机页开启「不重复下载已下载照片」后，列表里剩下的就是未下载项 —— 两者相加已覆盖
> 「看下载状态」的诉求，独立下载页属于重复建设。
> **连带收益**：原先为下载页准备的「`TransferRecord.status` 扩展 + 失败/中断落库」**一并取消**，
> 不做无关持久化改动（符合 §0 铁律）。

#### 3.5.1 展示位置（定稿）

新增**一行统计条**，位置：**筛选 chip 行下方、下载进度条上方**
（纵向顺序 = 顶栏 → 分隔线 → chip 行 → **统计行** → `progressDownload`(3dp) → 网格）。

- 为什么不放 `tvMessage`：**【实】** `tvMessage` 位于 `layoutEmpty` 空态容器内
  （fragment_transfer.xml L162-171），而 `layoutEmpty` 的可见性由列表是否为空决定
  （Fragment L666-667 `if (list.isEmpty()) VISIBLE else GONE`）→ **有照片时它根本不显示**，
  挂在那里等于白做。
- 布局改动量（最小）：
  1. 新增 `tvDownloadStats`（`TextView`，高 **40dp**，`match_parent` 宽，左右 `page_margin`
     24dp，文字 13sp、`@color/text_secondary`，左对齐）；
  2. `tvDownloadStats` 顶部约束到 `chipScroll` 底部；
  3. `progressDownload` 的顶部约束由 `@id/chipScroll` 改为 `@id/tvDownloadStats`（**仅此一处约束改动**）；
  4. 网格（`swipeRefresh`）仍约束到 `progressDownload` 底部，**不动**。
- **不重叠核算**：统计行占 40dp 固定高度，位于 chip 行与网格之间，三者为纵向线性约束关系，
  无浮层、无绝对定位 → 不存在重叠；下载进度条 3dp 独立一行，也不与之重叠。

#### 3.5.2 文案与数据

- 文案：`已下载 M/N · 剩余 K 张`（下载进行中末尾追加实时百分比：` · 62%`）。
- 数据源（**无需新持久化**）：
  - `N` = 当前相机照片页**未过滤**的照片总数（`_photoList` / `uiPhotos` 原始长度）；
  - `M` = `N` 中 handle ∈ `downloadedHandles` 的数量（VM L160 已有，随传输状态增量刷新）；
  - `K = N - M`。
- 计算位置：在 `TransferViewModel` 里用 `combine(uiPhotos 原始源, downloadedHandles)` 派生一个
  `StateFlow<DownloadStats>`（纯派生，不改现有列表管线）。

#### 3.5.3 可见性与交互

- **仅「相机照片」源可见**；本地照片源、已标记源隐藏（本地页本就是已下载集合，无剩余概念）。
- 开关「不重复下载已下载照片」**开启时**：列表只剩未下载项，统计行仍显示完整 `已下载 M/N · 剩余 K`
  —— 用户能同时看到「还剩多少」和「已下载多少」，正好闭环。
- 统计行**不可点击**（本版不做下载页）；下载中的实时百分比沿用现有 `progressDownload`。

#### 3.5.4 验收

- 相机照片页：chip 行下方出现统计行，显示「已下载 M/N · 剩余 K 张」，数字与「开启隐藏按钮后
  消失的照片数」一致（K = 被隐藏的张数）。
- 下载一张后：M +1、K −1 自动刷新，无需手动刷新页面。
- 切到本地照片 / 已标记页：统计行隐藏；布局无错位、无重叠。
- 与 chip 行、下载进度条、网格的间距正常，快速滑动时统计行不闪烁。

---

### 3.6 模块 F：筛选兜底（O4）

- **O4 兜底（D3 定稿）**：「未下载」筛选（含相机页新按钮与 chip）后列表为空 **且** 原始列表非空
  → **自动关闭筛选**并 Toast「已全部下载，已显示全部照片」。

---

## 四、决策记录（已拍板）

| # | 决策 | 结论 |
|---|---|---|
| D1 | 「还剩多少张未下载」怎么给 | **取消下载页**；改为在相机页 chip 行下方加**剩余下载进度统计行**（§3.5） |
| D2 | O1 下载遮罩 | **不做**（核查无遮罩，本条关闭） |
| D3 | O4 空结果兜底 | **自动关闭筛选 + Toast** |
| D4 | 大库窗口阈值 | **取消**（N1 延后） |
| D5 | STA 手动输入 IP | **做** |

---

## 五、实施顺序

1. **P0-1**：模块 A（相机页按钮，独立低风险）→ 可单独打包自测
2. **P0-2**：模块 E（剩余下载进度行，与模块 A 同源，建议同批做同批自测）
3. **P0-3**：模块 C（媒体库扫描，一行级改动）
4. **P0-4**：模块 B（滑动渲染，纯 UI 层）
5. **P1**：模块 D（STA 提示 + 手动 IP）、模块 F（O4 筛选兜底）
6. 每步单独 `assembleDebug` 自验 + 真机自测通过后再进入下一步

---

## 六、自查与验收清单

### 6.1 编译与运行

- [ ] `./gradlew assembleDebug` **BUILD SUCCESSFUL**（沙箱放开）
- [ ] Logcat **无新增 Error / 异常堆栈**（重点 `TransferViewModel`、`PhotoGridAdapter`、`TransferManager`、`WifiScanner`）
- [ ] 无新增 Lint error（不要求清零历史 warning）

### 6.2 回归点（已跑通功能必须仍正常）

| # | 回归项 | 期望 |
|---|---|---|
| 1 | 相册加载、排序、日期分组、下拉/右上角刷新、排序切换落顶 | 与 v1.2.0 一致 |
| 2 | 已标记页「跳过已下载」按钮（默认开） | 行为与样式不变 |
| 3 | 本地页 | 新按钮不出现；隐藏开关不影响本地页 |
| 4 | 缩略图渐进加载（小图→高清）、预览左右滑动、长按多选、标记、批量下载 | 不受影响 |
| 5 | 下载串行队列、断点续传、速度统计、下载通知 | 不受影响（仅新增失败/中断落库） |
| 6 | AP / USB / BLE 连接、监看、遥控拍摄 | 完全不受影响 |
| 7 | 拍摄后自动同步相册 | 仍只发一次最终列表、不跳底 |
| 8 | MainActivity 四个 tab 切换与转场 | 不受影响（本期不新增页面） |

### 6.3 改动 → 反馈对照（交付时逐条说明）

| 改动 | 对应反馈 |
|---|---|
| `btnSkipDownloaded` 相机页可见 + 按源绑定 `_onlyNotDownloaded` + 锚点滚动恢复 | 需求 2、需求 3 |
| `filteredPhotos` 增加 CAMERA 源判断 | 需求 3「不影响其他页面」 |
| `onBind` handle 校验 + 占位 + 缩略图刷新节流 | B2（并观察 B3） |
| `saveFileToMediaStore` 后 `scanFile` | B5、O3 |
| STA 扫描失败原因提示 + 手动 IP + 扫描增强 | B4 |
| 新增 `tvDownloadStats` 统计行 + `DownloadStats` 派生 flow（`已下载 M/N · 剩余 K`） | O2（剩余下载进度） |
| 筛选空结果自动关闭 + Toast | O4 |
| ~~O1 / B1 / N2 / N1~~ | 本期不做（见 §0） |
| ~~下载管理页 / `TransferRecord.status` 扩展~~ | 已取消（见 §3.5 变更说明） |
