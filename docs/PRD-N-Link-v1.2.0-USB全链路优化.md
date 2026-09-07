# N-Link v1.2.0 — USB 连接与照片管理全链路优化

日期：2026-09-07
分支：`feature/usb-link-optimization`
基线：`f2bdc87`（v1.1.0 之后的 USB 事务层修复）

---

## 一、诊断：相册「持续转圈」的真正根因

### 1.1 现象
USB 连接后只识别到一次相机基本信息（型号/固件），相册照片长期转圈无法加载。

### 1.2 链路耗时拆解

`fetchPhotoListDetailed` 的流程是：

1. `GetStorageIDs`（0x1004）—— 1 次往返
2. `GetObjectHandles`（0x1007）—— 每存储 1 次往返
3. **`GetObjectInfo`（0x1008）× N** —— N = 存储卡对象总数
4. `GetThumb`（0x100A）/ 0x90C4 —— 每个可见项 1 次往返

第 3 步是瓶颈。USB PTP 的命令通道被 `commandMutex` 强制串行（`UsbPtpManager.kt:471`），
`TransferManager` 里的「页内并发 4」只在协程层面排队，落到 USB 端点上仍然是**一次一个**。

单次 `GetObjectInfo` 往返按 40~100ms 估算（PTP over USB 典型值，含相机固件响应延迟）：

| 卡上对象数 | 逐个 GetObjectInfo 耗时 |
|---|---|
| 500 | 20 ~ 50 s |
| 1000 | 40 ~ 100 s |
| 2000 | 80 ~ 200 s |

> 以上为按单次往返耗时的**推算值**，非实测。真机实测数据待补。

而 `TransferViewModel` 的整体硬顶是 60s（`LOAD_HARD_DEADLINE_MS`）。
**结论：只要卡上对象数上到几百，逐个读取必然超出任何合理等待**——这就是「转圈」。
`GetDeviceInfo` 只有 1 次请求、瞬间成功，所以表现是「只识别一次基本信息」。

### 1.3 为什么之前的事务层修复没解决

`f2bdc87` 修的是**单个容器跨多次 bulkTransfer 的重组**（>64KB 容器被截断导致必然 5s 超时）。
这个 bug 是真的，修好了下载与监看。但它不改变**请求次数**——N 次往返的复杂度没变，
所以相册在对象数多时依然慢。

### 1.4 参考实现的做法

解包 PixCake（v1.9.0）后确认，它内嵌 `com.truesight.cameraptp` SDK，结构上有：

- `MtpGetObjectPropList` —— **MTP 0x9805，一次事务取回全部对象属性**
- `GetImageAndThumbInChunksAction` / `AbstractChunkedStreamAction` —— 分块流式
- `InterruptEventLoop` —— 中断端点事件循环
- `PtpActionQueue` / `CommandWorker` —— 命令队列 + 工作线程
- `NikonCameraControl` —— 尼康适配

其中 **0x9805 批量元数据** 正是把「N 次往返」压成「1 次往返」的关键。
N-Link 此前完全没有这个操作码。

---

## 二、改动清单

### 模块 1：USB 传输层（`device/ptp`、`device/usb`）

| 文件 | 改动 |
|---|---|
| `PtpProtocol.kt` | 新增 MTP 操作码（0x9805 等）；新增 `MtpObjectProp` / `MtpDataType` / `MtpObjectProps` / `MtpObjectPropListParser`（含日期解析） |
| `UsbPtpProtocol.kt` | 新增 `parseDeviceInfoModel()`：从 `GetDeviceInfo` 解析机身自报 Model；跳过 u16 数组与 PTP 字符串的工具方法 |
| `UsbPtpManager.kt` | 新增 `getObjectPropList()`（0x9805）；会话建立后用 DeviceInfo Model 回填相机名 |

**关键设计：批量路径失败即回退**
0x9805 是 MTP 扩展，并非所有机身/USB 模式都支持。返回 `null` 是**正常结果而非错误**，
调用方回退到逐个 `GetObjectInfo`，行为与旧版一致，不会更差。

### 模块 2：相册列表加载（`TransferManager.kt`）

- `CameraTransport` 接口新增 `objectPropList()`，USB/WiFi 两个实现各接一条
- `fetchPhotoListDetailed` 改为**双路径**：
  - 快路径：0x9805 批量（15s 兜底超时）→ 直接构建 `CameraFile` 列表
  - 回退路径：原有的分页逐个读取（行为完全不变）
- 新增 `cameraFileFromProps()`：MTP 属性 → `CameraFile`，文件名/时间缺失各有兜底

### 模块 3：缩略图渐进式加载（`TransferManager` / `TransferViewModel` / `TransferFragment`）

原实现所有缩略图都走「0x90C4 高清优先」，单张数百 KB、并发窗口仅 3 → 首屏必然排长队。

改为两段式：

1. **小图秒出**：先取 `GetThumb`（0x100A，几 KB）填满网格，消除转圈
2. **后台升高清**：独立的小并发窗口（2）在后台用 0x90C4 覆盖同一缓存键并通知重绘

**最终展示的仍是高清预览，画质不降低**，只是把「等待高清」从阻塞式改成后台替换式。
高清通道与小图通道**分离**，避免高清抢占首屏带宽把首屏重新堵死。

UI 侧新增 `thumbUpgradeTick`：高清替换时 handle 集合不变（Set 相等不触发 StateFlow），
用递增计数通知 Fragment 只重绘**可见范围**（十几项），不做全量 DiffUtil。

### 模块 4：排序置顶（`TransferFragment.kt`）

**根因**：`showSortMenu` 原本调用 `captureScrollAnchor()` 记住首个可见项的 handle，
排序后 `restoreScrollAnchor()` 把它滚回可见。当排序维度切换（时间→类型）导致分组结构
重排时，同一 handle 的新位置可能大幅后移 → 表现为「翻到尾页」。

**改法**：改为 `pendingScrollToTop = true`，复用已有的置顶机制——它在最终列表 submit
**之后**才 `post { scrollToPosition(0) }`，避免「跳底再弹回」的两段运动。
同时删除已无调用方的锚点代码。

### 模块 5：「跳过已下载」移到右上角（`fragment_transfer.xml` / `TransferFragment.kt`）

功能本身没丢，只是做成了底部 `chipSkipRow` 里的 chip。现移到顶部 `topBar`，
置于刷新键 `btnRefresh` **左侧**；新建 `ic_skip_downloaded.xml`（下载箭头 + 斜杠）。
仅在「已标记」源可见，开启态为黑底白图标（项目为纯灰度主题，accent = #000000）。

### 模块 6：照片预览左右滑动（`PreviewActivity.kt` / `activity_preview.xml` / 依赖）

引入 ViewPager2（`androidx.viewpager2:viewpager2:1.1.0`）。
`CameraFile` 非 Parcelable 且不在本次改动范围，因此 Intent 传**并行基本类型数组**
（handles/names/sizes/formatCodes/storageIds/captureTimes）+ position，Activity 内重建列表。
保留单张 `start(ctx, file)` 重载兼容既有调用方。每页懒加载，含进度与失败提示。

### 模块 7：远程拍摄模式（`LiveViewFragment.kt` / `RemoteFragment.kt`）

0x500E 的写入链路本就存在，问题在于**没有回读校验**：相机「接受写入但不真切」会被误判成功。

新增下发后的**回读确认**：等待 0x500E 回读值等于目标值（3s 超时），
超时给出明确文案而非静默失败；切换中禁用入口；未连接/未监看/列表为空时置灰。

### 模块 8：相机名称（`UsbPtpProtocol.kt` / `UsbPtpManager.kt`）

**根因**：显示名来自 USB PID 静态映射表（`NIKON_PRODUCT_IDS`），
该表既不可能覆盖全部机身，也存在一 PID 对应多市场名的情况 → 「名称不正确」。

**改法**：优先采用**机身自报的 `GetDeviceInfo.Model`**（机身固件写入，天然准确），
PID 表降级为兜底。这样不需要维护/猜测 PID 映射，也不会引入错误数据。

---

## 三、风险与回退

| 风险 | 处置 |
|---|---|
| 机身不支持 0x9805 | 自动回退逐个 GetObjectInfo，行为与旧版一致 |
| 0x9805 返回载荷异常 | 解析器遇到未知 DataType 即整体放弃（返回 null），回退；不产生脏数据 |
| 批量结果为空 | 回退（日志 `MTP batch yielded no usable file -> fallback`） |
| 小图占位后高清失败 | 网格保留小图，可用性不受影响 |
| `GetDeviceInfo` 解析失败 | 保留 PID 兜底名 |

---

## 四、真机验证清单（待小万执行）

1. **USB 连接**：插上相机 → 观察是否有 `MTP 0x9805 ok: N objects / XB in one round-trip`
   日志（logcat 过滤 `UsbPtp`）。若看到 `unsupported/empty -> fallback` 说明该机身不支持
   MTP 批量，请记录相机型号。
2. **相册首屏**：记录从「连接成功」到「首屏缩略图出现」的耗时，以及卡上照片数。
3. **缩略图画质**：确认最终显示的是高清预览（不是 160×120 拉伸的糊图）。
4. **排序**：切换「时间↑/↓、类型↑/↓」四种排序，每次都应停在顶部。
5. **跳过已下载**：进「已标记」页，确认右上角刷新键左侧出现按钮，开关生效。
6. **预览滑动**：单击照片后左右滑动切上/下一张，看页码指示是否正确。
7. **拍摄模式**：监看中点模式标签切换，确认成功后相机机身显示同步变化。
8. **相机名称**：对比 App 显示名与相机机身/菜单里的实际型号。
