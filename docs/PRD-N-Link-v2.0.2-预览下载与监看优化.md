# PRD：N-Link v2.0.2 预览下载与远程监看优化

> 版本：v2.0.2（versionCode 16，顺延） · 分支：`fix-preview-liveview-gallery` · 基线：v2.0.1（67fc9fe）
> 状态：**待确认**（根因已完成只读排查，未改动任何代码）
> 原则：已跑通的功能不改动，改动面压到最小，每项独立可回滚

---

## 一、预览页下载进度条：重新进入后丢失

### 现象
文件放大预览界面点下载 → 底部显示进度条；退出预览再进入同一文件 → 进度条消失（即使文件仍在下载或已下载完成）。

### 根因（代码确凿）

| # | 根因 | 证据 |
|---|---|---|
| 1 | 状态只存在 Activity 实例里，无状态保存 | `PreviewActivity.kt:104` `private val downloadResults = mutableMapOf<Int, String>()`；全文无 `onSaveInstanceState`/读取 `savedInstanceState` |
| 2 | 进度是**一次性 suspend 回调**，不是状态流 | `PreviewActivity.kt:543-562` `lifecycleScope.launch { withContext(IO){ transferManager.downloadPhoto(file, onProgress={…}) } }`；页面从未订阅 `TransferManager.transferState` / `queue`（两个 StateFlow 在 `TransferManager.kt:159-163`） |
| 3 | 不查已持久化记录 | 已完成下载已入库 `TransferRepository.recordTransfer`（`TransferManager.kt:637`），但预览页不查 `isAlreadyTransferred(handle)`，已完成也显示「下载」 |
| 4 | **下载实际被中断**：预览页下载走裸 `lifecycleScope`，未入队 | 退出 Activity → 协程取消 → 下载被真正杀掉。所以「重进后仍在下载」当前并不成立 |

`syncDownloadUi()`（`PreviewActivity.kt:596`）只读内存 map，重建后 map 为空 → 复位成「下载」。

### 方案

- **方案 A（推荐）**：预览页下载改走 `transferManager.enqueue(file)`（单例队列，不受页面生命周期影响）；UI 改用 `file.handle` 匹配 `transferManager.transferState`（`Downloading(file, received, total)` 自带 handle）驱动进度；进入时额外查一次 `isAlreadyTransferred` 显示「已完成」。
  - 改动面：`PreviewActivity` 约 60 行，**`TransferManager` 零改动**；风险低。
- 方案 B（不推荐）：`TransferManager` 新建 handle→进度 registry 并落 Room 做断点态。进入曾修过 S1/S3/S5 队列缺陷的核心区，回归风险高；仅当需要跨进程/后台续传才做。

### 验证
① 开始下载 → 退出预览 → 重新进入，进度条继续走（非复位）；② 下载完成 → 退出重进 → 显示「已完成」；③ 下载中杀掉 Activity，下载不被中断（队列继续）。

---

## 二、视频文件无法正常下载

### 根因（按置信度排序）

| # | 层 | 根因 | 证据 |
|---|---|---|---|
| 1 | 业务（高置信，最可能主因） | `saveFileToMediaStore` **无条件**插入 `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`，而 `getMimeType` 对 MOV/MP4 返回 `video/quicktime`/`video/mp4` | `TransferManager.kt:1385-1401`；`getMimeType` `:1473-1474`；全工程**无任何 `MediaStore.Video` 引用**。视频 MIME 插进 Images 集合会被 MediaProvider 拒绝/误分类 → `insert` 返回 null 或抛异常 → `:1656` `Failed("保存失败…")` |
| 1b | 业务（同源隐患） | 设置里选「Download 目录」时 `RELATIVE_PATH=Download/N-Link`，对 Images 集合是非法主目录 | `AppSettings.kt:24-26`（**此设置下连照片也会失败**） |
| 2 | 协议（视频专属） | 心跳判死把下载杀掉：`NO_ACTIVITY_TIMEOUT_MS=30000`、`MAX_MISSED_BEATS=3` × `KEEP_ALIVE_INTERVAL_MS=8000` → `markLinkError()` → `closeSockets()`。视频传输数分钟、相机忙传大对象时不回 event 通道 → 判死并关掉正在传数据的命令通道；**心跳无「批量传输中」豁免** | `PtpSessionManager.kt:58-68`、`:848-868`、`:877-885` |
| 3 | 协议 | 命令通道读超时 10s，大 MOV 首包可能超时 | `PtpSessionManager.kt:328` `soTimeout = 10000`；配合 `PtpProtocol.kt:568-576 readFully` 逐次计时 |
| 4 | 协议 | 断点续传 offset 用 Int，>2GB 变负数 | `TransferManager.kt:704` `partialObject(..., totalReceived.toInt(), ...)`；`:699` `coerceAtMost(Int.MAX_VALUE)` 未兜住 |

### 方案

- **方案 A（推荐，全量）**：
  1. `saveFileToMediaStore` 按 `file.format` 分流 **Images / Video / Downloads** 三种 collection 与对应 column（顺带修 1b：Download 目录场景改用 Downloads 集合）；
  2. `PtpSessionManager` 增加「批量传输中」标记：传输期间**暂停心跳判死**，并把 `soTimeout` 临时提到 60s、结束后还原；
  3. 断点续传 offset 改 `Long`，>2GB 时退化为整对象 GetObject 或做分片起点校验。
  - 改动集中在 3 个函数，**不动队列状态机**。
- 方案 B（保守）：只修 1 + 3。视频 ≤2GB 可成功，>2GB 仍失败；4K 长片会残留问题。**不建议单独采用**。

### 验证
① 下载一段 1080p/4K MOV（<2GB）成功进相册且能被系统播放器识别；② 下载时长 >30s 的大文件，不发生 `link_error`；③ 切换「Download 目录」设置后照片与视频都能落盘；④ 照片下载行为与现状一致（回归）。

---

## 三、远程监看对焦点与相机端不一致

### 根因

| # | 根因 | 证据/置信度 |
|---|---|---|
| 1 | **手机端从不回读相机 AF 状态**：对焦框是画在「点击坐标」上的本地 View，750ms 后自动消失；相机把点吸附到 AF 点格或自选对焦点时手机端毫无感知 | 代码确凿：全仓无 AF 区域/对焦框属性读取（已 grep 确认），`viewFocusIndicator` 为本地绘制 |
| 2 | `FocusTapMapper.displayedImageRect` 是**死代码** → 「相机 AF 框 → 屏幕坐标」的反算路径根本不存在 | 代码确凿 |
| 3 | `AF_COORD_MAX_X=4000 / AF_COORD_MAX_Y=3000` 来源是「参考影犀日志·约」，历史上从 320×240 改过来，**无实机标定**；与可能非 4:3 的 LV 帧比例相乘会造成系统性偏移 | **推断待验证** |

### 方案

- **方案 A（推荐）**：用已有的 `getDevicePropValue` 枚举 AF 区域属性**回读相机真实对焦点**，按相机返回值绘制 AF 框；`0x9205`（ChangeAfArea）失败时不画框（避免显示与相机不符的假框）。
  - 改动面：LiveView 对焦框绘制 + 一次属性回读；风险：需真机确认属性码（可能要枚举几个候选属性试读）。
- 方案 B：九点标定出真实 `X_MAX/Y_MAX`，仅修正坐标比例。改动小但不解决「相机吸附/自选焦点」导致的不一致。

> 建议：先做 B 的标定（低成本、立刻改善偏移），再用 A 做真值回读。

### 验证
① 点击画面四角与中心，相机屏幕对焦点与手机框位置一致；② 相机使用「自选对焦点」时，手机端框跟随相机而非点击位置；③ 0x9205 失败时不显示框（不显示假状态）。

---

## 四、照片过多时加载超时

### 根因

链路：`TransferViewModel.loadPhotos()`（`:540`）→ `TransferManager.fetchPhotoListDetailed()`（`:345`）→ `storageIds` → `objectHandles` → 先试 MTP 批量 `objectPropList(0x9805)`（1 次往返），失败回退逐张 `objectInfo(0x1008)`。

| # | 根因 | 证据 |
|---|---|---|
| 1 | **快路径在 WiFi/PTP-IP 下基本不可用** | `PtpProtocol.kt:67-73` 注释自承「尼康机身在 **USB 连接时**普遍同时暴露 MTP 操作」；`PtpTransport.objectPropList` 非 Success 即返回 null（`:1602-1608`）→ 永远走逐张回退 |
| 2 | 回退路径 N 次**串行**往返，并发无效 | `TransferManager.kt:408` `handles.chunked(PAGE_SIZE=18).forEach`；`OBJECT_INFO_CONCURRENCY=4`（`:81`）被 `PtpSessionManager.kt:86 commandMutex` + `:461 withLock` 串行化，4 个 async 全部排队 |
| 3 | 硬顶截断且**整份丢弃** | `TransferViewModel.kt:80 LOAD_HARD_DEADLINE_MS=60_000`；`:568` `withTimeoutOrNull` 包住整次拉取；`:579` `fetch ?: return@launch` 超时即丢弃已读到的全部 |
| 4 | 已有的 `onPage` 增量回调被三个入口全部关闭 | `:570` `onPage = if (holdPages) null else …`；三个入口均 `holdPages=true`：`TransferFragment.kt:93`、`TransferViewModel.kt:742`、`:778` |

耗时：代码注释自陈「2000 个对象约 80~200 秒」→ 单对象 40~100ms。1000 张 ≈ 40~100s，1500+ 必然撞上 60s 硬顶。缩略图已是懒加载 + 两级缓存（`ThumbnailCache.kt`），**不是瓶颈**。

### 方案

- **方案 A（推荐）**：首屏批优先 + 增量提交。
  1. `fetchPhotoListDetailed` 增加「首屏批」回调（首批 ~60 个 handle 完成即 emit）；
  2. `loadPhotos:570` 改为：列表为空时无论 `holdPages` 都接受首屏批并写入 `_photoList`，全量完成后再一次性提交排序结果（保持现有最终排序语义）；
  3. `:579` 超时分支改为「**保留已读到的部分列表 + 提示缺口**」，不再整份丢弃；硬顶可放宽到 120s 或改为「首屏 15s 内必出图、全量后台续拉」。
  - 改动面：2 处（TransferManager 1 处 + TransferViewModel 1 处）；风险低（首屏时列表为空，不受「已有旧列表 + 逐页追加逆序」的跳底根因影响）。
  - 预期：首屏 18~60 张 ≈ 0.7~2.5s 出图，剩余后台续拉，用户可滚可看。
- 方案 B（边际）：仅调常量（`PAGE_SIZE` 18→60）与放宽超时。吞吐上限被 `commandMutex` 锁死，收益 <20%。

### 验证
① 1000+ 张的内存卡，首屏 <3s 出图；② 加载中不出现「相册加载超时」；③ 加载完成后排序、筛选、已下载标记、F1 分区与现状一致；④ 下拉刷新与拍摄后增量插入行为不变。

---

## 五、明确不动的清单（已跑通）

- **队列与去重**：`TransferManager.enqueueInternal`（915-958）双层去重；`runTask`（1175-1180）「只在失败时重试一次」（防 `xxx (1).JPG` 二次落盘，勿改回 `repeat(2)`）；`finishTask`（1235-1258）`NonCancellable` + 锁外派发 `processQueue()`
- **通道回退与增量**：`downloadVia`（550-566）USB↔WiFi 回退；`onNikonObjectAdded`（:691）单张插入；`scheduleCaptureSync`（:628）；`scheduleAutoSync` 限幅 20 张 + 60s 去抖
- **下载状态维护**：`deleteSelected`（:1308）、`refreshDownloadedHandles`（:418）、`reconcileWithLocalMedia`（:442）、`photoMarkRepository.reconcile`（:601）、F1 标记分区（:128-180）、`clearDownloadedMarks`（:1153）
- **缩略图**：两段式懒加载（0x100A 小图 → 0x90C4 高清）+ `ThumbnailCache` 两级缓存（64MB LruCache + 磁盘 `thumbnails_v2`）
- **保存前处理**：`prepareFileToSave`（:1353）只对 JPEG 重编码，视频/RAW 原样保存（勿加解压逻辑）
- **AF 驱动链路**：`halfPressFocus` / `capture` / `startContinuousFocus`、`FocusTapMapper` 的黑边与缩放换算、LV 启动与帧循环
- **排序管线**：`filteredPhotos`（:219-243）与 `holdPages` 最终一次性提交（:611）的语义

---

## 六、待确认事项

1. **视频下载范围**：方案 A 全量（含 >2GB 与心跳豁免）还是 B 最小修复？
2. **对焦同步**：A（回读相机 AF 属性）单独做，还是先 B（标定坐标）再 A？
3. **照片读取**：是否按方案 A（首屏批 + 超时保留部分）实施？硬顶放宽到多少？
4. **版本号**：v2.0.2（versionCode 16）？

---

## 七、遗留风险（与本次改动无关，需另行处理）

当前工作区存在一批**未提交的删除与 README 改写**（`docs/`、`tools/`、`videos/nikonlink-promo/` 大量文件被 staged 为删除），疑似另一次「项目清理」未完成。本次改动前需先确认其归属，避免误提交造成文件丢失。
