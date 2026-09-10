# PRD — N-Link 七项优化（v1.3.0）

> 整理日期：2026-09-10 · 基线：master `aaad9f8`（v1.2.2 已发布）
> 目标版本：**v1.3.0（versionCode 13）** —— 已确认（2026-09-10 小万拍板，替代原拟 v1.2.3）
> 范围声明：**只做下述 7 项；凡已跑通的功能，其逻辑、交互、界面一律不动**（每项末尾列「不动清单」）。
> 证据等级标注：`[实证]` = 代码/二进制确凿；`[推断待日志]` = 需真机日志或截图定性。

---

## 0. 摘要表

| # | 需求 | 现状（代码锚点） | 根因 | 改动量 | 风险 |
|---|---|---|---|---|---|
| 1 | 镜头厂家显示错误 | `CameraParameterManager.kt:547-574`、`DashboardFragment.kt:114` | 无脑拼 `"NIKKOR "` 前缀；USB 通道不触发 `readAll()` | 小 | 低 |
| 2 | 相机照片页已下载标记 | `item_photo_grid.xml`（`tvDownloadedBadge`）、`PhotoGridAdapter.kt:458` | **角标已实现**，但辨识度低 + 历史记录可能被清理 | 小 | 低 |
| 3 | 删本地后状态恢复未下载 | `TransferViewModel.kt:830-846`、`NLinkDatabase.kt:47-70` | 删本地只动 MediaStore，`transfer_history` 行保留 | 中 | 低 |
| 4 | 拍摄模式远程切换无效 | `CameraParameterManager.kt:901-905`、`RemoteFragment.kt:406/438` | 已真实下发 0x500E，但相机拒写时无有效反馈/回滚 | 小~中 | **需真机日志** |
| 5 | 每拍一张即时加载（逐张） | `TransferViewModel.kt:363-370`、`TransferFragment.kt:72` | 只订阅标准 `0x4002`，且 800ms 去抖 + 全量重拉；**未处理尼康私有事件 0xC101** | 中 | 中 |
| 6 | USB 多次才连上 + 卡在"正在建立USB通道..." | `DashboardFragment.kt:567`、`ConnectionManager.kt:541-547`、`UsbPtpManager.kt:316` | 文案硬编码写入、无状态回写；USB 状态机与 WiFi 状态机两套且未接入 UI | 中 | 中 |
| 7 | 视频模式+联动画面 录像后监看断连 | `RemoteShootingManager.kt:771-830`、`LiveViewManager.kt:39/488/528` | 录像后相机停推 LV 帧 → 连续 5 次错误判死且无重连；录像路径未开宽限 | 中 | 中 |

---

## 1. 镜头厂家显示错误

### 现象
连接成功后，设备/相机信息里的「镜头」一栏显示的镜头不对。

### 根因 `[实证]`
1. **无条件拼 `NIKKOR` 前缀**：`buildLensName()` 末段固定 `"NIKKOR $specs"`（`CameraParameterManager.kt:568`）。适马 / 腾龙 / 蔡司等第三方镜头会被显示成 NIKKOR。
2. **读不到规格就退化成裸 ID**：`"镜头 ID $lensId"`（同文件 `:570`）——对用户无意义。
3. **只读尼康私有属性**：`0xD0E0`（LensID）、`0xD0E3/E4`（焦距）、`0xD0E5/E6`（光圈），`PtpProtocol.kt:257-263`。机身没有上报镜头型号字符串时，App 只能"猜"。
4. **USB 通道根本不刷新**：`paramsViewModel.readAll()` 只在 `ConnectionState.FULLY_CONNECTED` 触发（`DashboardFragment.kt:114-116`），而 USB 走 `UsbPtpManager` 自己的 `UsbConnectionState`，永远到不了这个状态 → USB 连接下镜头行不刷新（空白 / 陈旧值）。

### 预期结果
- 能拿到镜头型号时，显示相机上报的**原始型号**（不加任何品牌前缀）。
- 拿不到型号时，显示**规格串**（如 `18-50mm f/2.8`），不再冒充 NIKKOR。
- 什么都读不到时显示「未知镜头」，不显示内部 ID。
- **USB 与 WiFi 两条通道**连接成功后都会刷新镜头信息。

### 方案（二选一，见「待确认」Q1）
- **A（保守，推荐先做）**：去掉 `NIKKOR` 硬编码，规格串优先；`lensId` 兜底改为「未知镜头」。零新增数据表。
- **B（准确）**：在 A 之上加 `LensID → 官方型号名` 映射表（尼康原厂型号可精确显示，如 `NIKKOR Z 24-70mm f/2.8 S`）；第三方镜头回落到规格串。表可由机型实测逐步补全。

### 验收标准
1. 挂第三方镜头连接 → 镜头行**不再**出现 `NIKKOR`。
2. 挂原厂镜头连接 → 显示与镜头一致（B 方案下为官方型号）。
3. USB 连接后 5 秒内镜头行有值（不再是空白/上次残留）。

### 不动清单
快门/光圈/ISO/EV/WB 读取链路、直方图、监看。

---

## 2. 相机照片页右下角「已下载」标记

### 现象
在「相机照片」页，已下载到手机的照片没有可辨认的已下载标记。

### 现状（重要）`[实证]`
**这个角标在代码里已经存在**：
- 布局：`item_photo_grid.xml` 右下角 `tvDownloadedBadge`（`✓` 胶囊，9sp，底色 `#AA000000`）。
- 绑定：`PhotoGridAdapter.applyDownloaded()`（`:458-460`）+ 局部刷新 payload `PAYLOAD_DOWNLOADED`（`:71`）。
- 数据源：`TransferViewModel._downloadedHandles`（`:160`），来自 Room 表 `transfer_history`。
- 引入时间：commit `958b97b`（v0.2.0 主题 F），早已随版本发布。

所以问题不是"没实现"，而是**你没看到 / 不够明显 / 部分场景下确实不显示**。已定位的「确实不显示」的情形 `[实证]`：
1. `cleanOldRecords()` 会删除 **30 天前**的传输记录（`NLinkDatabase.kt:68-69`）→ 老照片角标消失。
2. 数据库迁移 `MIGRATION_1_2` 失败时的兜底会 `DELETE FROM transfer_history`（`NLinkDatabase.kt:222`）→ 全部角标消失。
3. 在旧版本下载、或换机/清数据后，历史缺失 → 无角标。

### 预期结果
相机照片页每张**手机本地确实存在**的照片，右下角有清晰可辨的「已下载」标记；标记与本地文件实际存在性一致（与第 3 项联动，形成自愈）。

### 方案
- **A（推荐）**：保留 ✓ 语义，提高辨识度 —— 角标内 **✓ + 「已下载」文字**（白字、深色 60% 底、圆角胶囊），格子过窄时自动降级为纯 ✓（`layout` 用 `wrap_content` + 字号 10sp）。
- **B**：只放大加粗现有 ✓（改动最小，但"是什么"仍需用户猜）。

### 验收标准
1. 下载 1 张 → 该张右下角立即出现「已下载」标记。
2. 关闭再打开 App → 标记仍在。
3. 开启「不重复下载已下载照片」→ 这些照片被隐藏；关闭后回来标记仍正确。

### 不动清单
已标记星标（左上）、多选对勾（右上）、格式角标（左下）、排序与分页。

---

## 3. 删除本地照片后，相机照片页状态自动恢复为「未下载」

### 现象
把手机本地已下载的照片删掉后，相机照片页仍按「已下载」对待（角标不减、开启"不重复下载"时它仍被隐藏、剩余进度不回升）。

### 根因 `[实证]`
- App 内删除：`deleteLocalSelected()`（`TransferViewModel.kt:830-846`）只做两件事 —— `contentResolver.delete(localContentUri(handle))` + 内存 `_localPhotos` 过滤；**从不删 `transfer_history` 行**。
- App 外删除（系统相册/文件管理器）：App 完全不知情，`transfer_history` 依旧保留。
- 判定路径单一：`queryDownloadedHandles()` → `findTransferredHandlesBatch()`（`TransferManager.kt:940/954`）**只读数据库**，从不校验本地文件是否还在。

### 预期结果
手机本地文件消失后（无论 App 内删还是系统相册删），下次回到相机照片页：
- 该张角标消失；
- 「仅显示未下载」下它重新出现；
- 顶部「已下载 M/N · 剩余 K 张」计数正确回升。

### 方案
1. **App 内删除即时回收**：新增 `TransferHistoryDao.deleteByHandles(List<Int>)`，在 `deleteLocalSelected()` 里一并删除对应行（口径与下载记录严格一致）。
2. **外部删除自愈**：在「全量列表到手后」这一步（现有 `refreshDownloadedHandles()` 调用点，`TransferViewModel.kt:550`）追加一次轻量校验 —— 只针对 `_downloadedHandles` 里那批 handle，用一次 MediaStore 批量查询判断本地是否还在；不在的从集合剔除并清理 DB 行。
   - 复用现有能力：`localContentUri()`（`:805`）、`queryLocalMedia()`（`:677-733`）、`hasMediaPermission()`（`:818`）。
   - 无媒体权限时不校验（避免误清），只做 App 内删除那条路径。
3. **30 天清理策略**（见待确认 Q5）：建议把 `cleanOldRecords()` 改为「只清理**本地文件已不存在**的记录」，或延长到 180 天，避免正常用户的角标随时间消失。

### 验收标准
1. App 内删除 1 张已下载照片 → 相机照片页该张角标消失、未下载筛选下重新出现。
2. 在系统相册删除 1 张 → 回到相机照片页，**无需手动刷新**，角标消失。
3. 删除后「剩余 K 张」数值 +1。

### 不动清单
下载队列与去重口径（仍以 `transfer_history` 为唯一权威）、缩略图缓存、批量下载流程。

---

## 4. 监看页拍摄模式无法真正远程切换

### 现象
监看页点「模式」后弹出「拍摄模式（远程切换）」对话框，选完没有实际效果。

### 现状（和"没实现"不同）`[实证]`
功能**确实下发了 PTP**：
```
RemoteFragment.kt:380 onParamClick("模式") → :406 弹窗（标题硬编码"拍摄模式（远程切换）"）
 → :422 applyExposureProgram() → CameraParamsViewModel.kt:61
 → CameraParameterManager.kt:901-905 setExposureProgram(mode) → writeDeviceProp(0x500E, mode)
 → :438 等 0x500E 回读 3s 校验；失败提示"相机未响应"
```
`0x500E`（ExposureProgramMode）是标准属性，复用统一写入通道 `writeDeviceProp()`（`:988`）。**代码链路是通的**，问题出在相机侧接受度：

- `[推断待日志]` 多数尼康机身把拍摄模式视作**物理拨盘**，通过 PTP 写 `0x500E` 会被拒（`AccessDenied/NotSupported`），或写入后立即被固件回滚。
- 现有 UI 在拒写时只给一句"相机未响应"，用户感知就是"点了没反应"。
- 另外弹窗标题「远程切换」暗示"一定能切"，与实际能力不符。

### 预期结果
- 相机**支持**时：选完模式后相机真机模式同步改变（机身屏幕也变），并给出成功反馈。
- 相机**不支持**时：明确告知「该机型不支持从 App 切换拍摄模式（请用机身拨盘）」，并把 UI **回滚**到相机上报的真实模式，不再留下"已切换"的假象。
- 绝不再出现"显示已切换但相机没变"。

### 方案（需真机日志定性，执行顺序见「待确认」Q3）
1. **第一步（必做）**：加诊断 —— 记录 `0x500E` 写入的响应码、回读值、耗时；失败按响应码分级提示（设备忙 / 不支持 / 权限拒绝）。
2. **第二步**：若确为拒写，尝试替代路径并逐条实测：
   - Nikon 私有「应用模式/曝光模式」属性（如 `0x9435` 应用模式或 `0xD1xx` 系列），失败即回滚；
   - 尝试"先切到 Remote 应用模式再写 0x500E"的顺序。
3. **第三步**：仍不行 → 该机型判定为不支持，UI 上把「模式」入口置为不可点并给出说明文案。

### 验收标准
1. 支持切换的机型：切换后机身屏幕模式同步变化。
2. 不支持的机型：给出明确原因文案 + UI 与机身一致；控制台无异常堆栈。
3. 快门/光圈/ISO/EV 等既有参数写入行为完全不变。

### 不动清单
快门/光圈/ISO/EV/WB/对焦/测光等已跑通的参数下发、以及它们的轮询回读。

---

## 5. 相机每拍一张，相册即时逐张加载

### 现象
机身拍了一张照片，相册不是马上出现，而是"过一会儿/有时才刷新"（间歇性）。

### 根因 `[实证]`
- 目前只订阅**标准事件 `0x4002 ObjectAdded`**：`TransferViewModel.kt:363-370`（WiFi/USB 两条事件通道都订阅了）。
- 收到后不是"插入这一张"，而是 `scheduleCaptureSync()` → **800ms 合并窗口**（`TransferFragment.kt:72 CAPTURE_SYNC_WINDOW_MS`）→ `loadPhotos(silent=true, holdPages=true)` **全量重拉**（objectHandles + 批量元数据）。
- **缺口**：尼康机身上报的是**私有事件** `0xC101 ObjectAddedInSDRAM`（参数 = 新对象句柄）与 `0xC102 CaptureCompleteRecInSdram`；`0x400D CaptureComplete` 常量虽已定义（`PtpProtocol.kt:184`）却**无人消费**。0x4002 在部分机型上延迟或干脆不来 → 表现为"间歇性"。
- 事件对象本身已带参数（`PtpEvent.parameters: List<Int>`，`PtpSessionManager.kt:871-875`），所以"拿到句柄立即取单张"是现成可行的。

### 参考实现实证（4 个 APK + 协议依据）
| 来源 | 做法 |
|---|---|
| PixCake（`com.truesight.cameraptp`） | 同时处理 `NikonObjectAddedInSdram` 与 `onRamObjectAdded`/`onEventObjectAdded`，**每个事件单独取 objectInfo 立即插入**；`NikonGetEventEx` 拉事件 |
| 影犀（Flutter + pigeon） | 以 `Nikon_ObjectAddedInSDRAM` 为触发；有 `Nikon GetEventEx` 与 USB 侧 IRQ 端点 200ms 轮询 |
| ZRelay / ZDROP | 均出现 `ObjectAddedInSdram` / `ObjectAdded`，ZDROP 为 Z6II 专用 |
| libgphoto2 `ptp.h`（权威） | `PTP_EC_Nikon_ObjectAddedInSDRAM = 0xC101`、`PTP_EC_Nikon_CaptureCompleteRecInSdram = 0xC102`；Param1 = 对象句柄，`0xffff0001` 表示"无句柄"（NEF+RAW 等双拍场景需靠标准 0x4002 补齐） |

### 预期结果
- 机身实体快门或 App 遥控，**每拍一张**：该张在写入完成后 **1 秒内**出现在相册（新照片在最前），逐张追加。
- 连拍 5 张：逐张出现，不重复、不乱序、不整表闪烁（不打断当前滚动位置）。
- USB 与 WiFi 两条通道都成立。

### 方案
1. **事件层新增**：`0xC101`（带 handle）→ **只取这一张** 的 ObjectInfo（+缩略图）→ 增量插入列表头部；`0xC102` / `0x400D` 作为"本张已完成"的辅助信号（可与 0xC101 配对减少无效查询）。
2. **去重**：同一 handle 在短窗口（如 3s）内重复事件只处理一次；0xC101 与 0x4002 同时到达时以 handle 去重，避免重复插入。
3. **兜底（保留现状）**：0x4002 + 800ms 合并窗口继续保留 —— 当事件缺失或 handle 为 `0xffff0001` 时退化为现有全量同步，保证不倒退。
4. 增量插入需复用现有排序/分页锚点逻辑（`Adapter.submit` + DiffUtil 已支持），**不重写列表管线**。

### 验收标准
1. 单张拍摄 → 相册 1s 内出现该张（USB/WiFi 各测 3 次）。
2. 连拍 5 张 → 5 张逐张出现，无重复、顺序正确。
3. 拍照期间正在浏览列表 → 滚动位置不被重置。
4. 事件缺失场景（可关闭事件通道模拟）→ 仍能靠兜底刷新出现，不倒退。

### 不动清单
下载队列与去重、缩略图两级缓存、分组排序口径、分页锚点恢复。

---

## 6. USB 通道：多次才连上 + 卡在「正在建立USB通道...」

### 现象
- USB 常常要插拔/点好几次才连上。
- 连上之后，设备页状态一直显示「正在建立USB通道...」，永远不变成"已连接"。

### 根因（两条，均有代码实证）`[实证]`
**A. 文案不会更新（100% 复现）**
- `DashboardFragment.kt:567` 直接 `binding.tvStatusMessage.text = "正在建立 USB 通道..."` —— **硬编码赋值**，不是状态流。
- 驱动 `tvStatusMessage` 的 `statusMessage` StateFlow 只由**无线状态机**发射（`ConnectionManager.kt:486-505`，枚举 `ConnectionState` 里根本没有 USB 态）。
- `ConnectionManager.observeUsbEvents()`（`:541-547`）收到 USB 状态变化后**只打日志**：`Timber...d("USB state changed: $state")`。
- USB 侧其实已经置了 `CONNECTED`（`UsbPtpManager.kt:316`），但 UI 不消费 → 文案永远停在"正在建立…"。

**B. 首次连接易失败** `[实证 + 推断待日志]`
- 连接序列：`requestPermission`（`:195`）→ `openDevice`（`:210`）→ `claimInterface`（`:259`）→ 打开 PTP 会话（`:316` 置 CONNECTED）。
- 已有重连退避 `scheduleReconnect()`（`:344-381`，1/2/4/8/15/15/15s，共 7 次≈68s 窗口），但**失败原因不反馈到 UI**，用户只能反复插拔。
- `[推断待日志]` 典型失败点：权限回调时序（`ACTION_USB_PERMISSION` 广播晚到）、`openDevice` 返回 null（设备被前一次会话占用）、`claimInterface` 失败（接口未释放）、`OpenSession` 返回 DeviceBusy（相机还没从上次会话退出）。
- 参考：影犀有 `USB LiveView transport failed, trying one session recovery` / `fresh PTP session failed after stale-session recovery` —— 即"残留会话 → 清理后重建"；PixCake 的 USB 层有独立的请求权限/端点队列与恢复路径。

### 预期结果
- 连上后 **2 秒内**状态行变为「已连接（USB）· <机型>」；断开后回到「未连接」。
- 失败时显示**具体原因**（权限被拒 / 打开设备失败 / 接口占用 / 相机会话忙）并允许一键重试。
- 连续 5 次插拔，**首次即成功**（不需要反复插拔）。

### 方案
1. **状态统一（先做，收益最大）**：`tvStatusMessage` 改为由状态驱动 —— 新增 USB→文案映射（`DISCONNECTED/REQUESTING_PERMISSION/PERMISSION_DENIED/CONNECTING/CONNECTED/ERROR`），删除 `:567` 的硬编码写入；USB 状态与 WiFi 状态合并成统一的"连接状态行"（USB 优先）。
2. **失败分类与重试**：对 `openDevice == null`、`claimInterface == false`、`OpenSession` 忙三类分别处理：
   - 先 `releaseInterface` + `close` 清理 → 等待 300ms~800ms 重新枚举 → 重试（有限次，复用现有退避）；
   - `DeviceBusy` 时向相机发一次结束会话/复位再重试（对齐影犀的 stale-session recovery）。
3. **权限时序加固**：`requestPermission` 的 `PendingIntent` 回调落地后校验设备是否仍在线；离线则等待重新 ATTACHED 再继续，而不是直接失败。

### 验收标准
1. 连续 5 次「插入 → 连接 → 拔出」：每次首次点击即连上（允许 1 次内部重试，但用户无感）。
2. 连接成功后 ≤2s，状态行显示已连接 + 机型。
3. 拔出/断开后状态行变为未连接（不再残留"正在建立USB通道..."）。
4. 权限拒绝场景显示明确文案，不再无限转圈。

### 不动清单
WiFi（PTP/IP）与 BLE 连接链路及其状态文案、mDNS/STA 扫描、手动 IP 直连。

---

## 7. 视频模式 + 联动画面：开始录像后监看断连

### 现象（严格限定场景）
监看页处于**视频模式**且显示模式为**联动画面**时，点快门开始录像 → 监看画面断开，但快门键仍能响应。

### 根因 `[实证]`
- 录像链路：`RemoteFragment.kt:754 startVideo()` → `RemoteShootingManager.startVideoRecording()`：
  `changeApplicationMode(0x9435, 1)`（`:781`）→ `delay(VIDEO_MODE_SETTLE_MS)` → `movieRecordingCommand(0x920A, start=true)`（`:787`）。
- 相机进入录像应用模式后**停止推送 LV 帧**：帧循环连续超时/取空累计到 `MAX_CONSECUTIVE_ERRORS = 5`（`LiveViewManager.kt:39`）→ 判死 `ERROR` + `frameJob.cancel()`（`:509-535`），**且没有任何自动重连机制**。
- 已有的**宽限机制没罩住录像**：`grantFrameErrorGrace()`（`LiveViewManager.kt:488`）只在单张拍照路径使用（`RemoteShootingManager.capture()`）；录像开始/停止前后都没有开宽限，也没有"录像期间 LV 需要重启"的补链路。
- 快门仍响应是正常的：`onShutterPressed` 与 LV 状态无关（`RemoteFragment.kt:746`）——所以用户看到"画面没了但还能停录"。
- 参考实现：libgphoto2 在收到 `CaptureComplete`/`CaptureCompleteRecInSdram` 时若处于 liveview 会**重启 liveview**；影犀有 `USB remote LiveView restart after camera mode failure` / `restart after config retry` / `trying one session recovery` 等针对"应用模式切换后 LV 需重启"的处理。

### 预期结果（**只改这个场景**）
- 视频模式 + 联动画面下点快门开始录像：监看画面**不中断**。
- 若某机型录像期间确实不提供 LV 帧：画面**保持最后一帧**并显示「录像中」状态，而不是报断连错误。
- 停止录像后监看**自动恢复**，无需用户手动点「开始监看」。
- 其他场景（单张拍照、参数调节、遥控画面模式、非录像状态）行为**完全不变**。

### 方案
1. **录像全程开宽限**：在 `startVideoRecording()` 成功前后到 `stopVideoRecording()` 之间调用 `grantFrameErrorGrace()`（时长为整个录制期，或提供"持续宽限"模式），使"因进入录像模式导致的停流"不计入连续错误。
2. **补上 LV 重启链路**：录像启动完成后、停止录像后各检查一次 LV 是否在跑，必要时 `ensureRunning()`（单次、带短退避）；对齐 libgphoto2「应用模式变化后重启 liveview」的做法。
3. **状态语义**：录像期间 LV 处于宽限期 → `LiveViewState` 不落到 ERROR；UI 用「录像中」覆盖提示，停止后恢复原状态。
4. 恢复相机侧状态沿用现有 `restoreExposureModeIfNeeded`（0x500E）与显示模式（0x90C2）逻辑，不新增属性写入。

### 验收标准
1. 视频模式 + 联动画面：开始录像 → 画面不断（或保持最后一帧且不报错）；停止录像 → 画面自动恢复。
2. 遥控画面模式下录像：行为与改动前一致（该项不在本次范围内）。
3. 单张拍照 + 快速连拍：帧错误宽限行为不变（仍不误判断连）。
4. 录像 30s 期间无 `consecutiveErrors>=5` 导致的 ERROR 日志。

### 不动清单
单张拍照宽限、触摸对焦、参数写入、直方图、遥控画面模式链路、WiFi 监看（本次仅针对该场景，若 WiFi 同样出现再单列）。

---

## 8. 参考 APK 分析结论（素材与依据）

| App | 包名 | 版本 | 体积 | 技术线索 | 对本次的价值 |
|---|---|---|---|---|---|
| ZRelay | `com.geekmanlab.zrelay` | 3.0.46 | 3.1 MB | PTP / Nikon / LiveView 字符串、`ObjectAddedInSdram` | 印证尼康事件驱动刷新 |
| ZDROP | `com.zdrop.z6ii` | 1.0.190 | 2.7 MB | **Z6II 专用**、`NIKKOR`×34、`MtpDevice`、`ObjectAdded` | 单机型精调的做法（镜头名映射可借鉴） |
| 影犀 | `com.camerasyncpro.app` | 1.2.1 | 72.9 MB | Flutter+pigeon、`ChangeApplicationMode 0x9435`、`MovieRecProhibitionCondition`、LV restart / session recovery、`Get/SetDevicePropValueEx`、`LiveViewSelector` | **第 4/6/7 项的主要参考** |
| PixCake | `com.xiangtian.pixcake` | 1.9.0 | 828 MB | `com.truesight.cameraptp` PTP 库、`NikonObjectAddedInSdram`、`NikonGetEventEx`、`onRamObjectAdded`、多品牌事件表 | **第 5 项的主要参考** |

协议权威依据（libgphoto2 `camlibs/ptp2/ptp.h` 与 issue #846）：
- `0xC101 PTP_EC_Nikon_ObjectAddedInSDRAM`（Param1 = 对象句柄；`0xffff0001` = 无句柄）
- `0xC102 PTP_EC_Nikon_CaptureCompleteRecInSdram`
- 收到 `CaptureComplete` 系列事件且处于 liveview → **重启 liveview**

> 注：参考 APK 仅做静态字符串/结构分析（`aapt2 badging` + dex 字符串池），未运行、未修改、未向外发送任何内容。

---

## 9. 数据与兼容性

- 第 3 项新增 DAO 方法（`DELETE ... WHERE file_handle IN (...)`），**无表结构变更，DB version 保持 3**，不影响老用户升级。
- 无新增系统权限；第 3 项复用现有 MediaStore 查询能力，无权限时自动跳过自愈校验。
- 第 5 项新增事件码为**新增分支**，未识别设备走原路径，不改变既有行为。
- 第 6/7 项均在现有状态机内扩展，不引入新的连接/监看路径。

---

## 10. 待你确认（5 条）

| # | 问题 | 我的建议 |
|---|---|---|
| Q1 | 第 1 项：镜头行现在具体显示成什么？（方便的话给一张截图）期望口径是"显示真实品牌型号"还是"至少别瞎标 NIKKOR"？ | 先做 A 方案（去 NIKKOR、规格优先），B 方案的型号映射表按你机身实测再补 |
| Q2 | 第 2 项：现有的右下角 ✓ 角标你**是否见过**？如果从没见过，请给一张相机照片页截图 | 若无截图，我按 A 方案"✓+已下载文字"加强，并同时修掉历史记录被清理导致的丢失 |
| Q3 | 第 4 项：用的哪台机身？切模式时是否出现「相机未响应」提示？ | 先加诊断日志，你跑一次把日志给我，我再定是走私有属性还是做机型禁用 |
| Q4 | 第 5/7 项：测试即时加载与录像断连时，用的是 **WiFi 还是 USB**？机身型号？ | 两条通道我都会覆盖；录像断连本次先只处理你描述的场景 |
| Q5 | 版本号定为 **v1.3.0（versionCode 13）**（小万 2026-09-10 拍板）；`transfer_history` 清理策略改为"只清理本地已不存在的记录" | **已执行**：时间口径的 `cleanOldRecords()` 已删除，改为 `reconcileWithLocalMedia()` 事实对账 |

---

## 11. 工程与交付流程（确认后执行）

1. **分支**：`feat-v130-seven-fixes`（扁平名 —— 本机 git 不支持带 `/` 的分支名）。
2. **实施顺序**（低风险→高风险，可分批给你测）：
   - 批 1：第 1、2、3 项（设备信息 + 相册状态一致性）
   - 批 2：第 6 项（USB 连接与状态）
   - 批 3：第 5 项（逐张实时加载）
   - 批 4：第 7 项（录像监看）+ 第 4 项（模式切换，依赖你的日志）
3. **自验**：每项按上文「验收标准」逐条跑；编译零错误、无控制台异常；APK 打完后按既定方法验 dex/资源确认新代码已打包。
4. **交付**：先给 debug APK 真机验证 → 通过后 bump 版本 → `assembleRelease` → GitHub Release + 夸克网盘同步。
