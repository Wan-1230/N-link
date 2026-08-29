# N-Link v0.1.3 问题修复 PRD

| 项 | 内容 |
| --- | --- |
| 版本 | v0.1.3（versionCode 4） |
| 基线 | `master` @ `74e136f`（v0.1.2 / versionCode 3） |
| 开发分支 | `dev/v0.1.3-multi-fix` |
| 文档状态 | **待确认**（确认后方可动工） |
| 涉及模块 | 相册传输、设备连接、相机参数、设置页 |

---

## 0. 背景与结论摘要

针对 5 个待解决问题，本次排查已定位到全部根因。其中 **问题 1 存在一个此前未被发现的严重缺陷**：下载队列的重试循环缺少终止条件，导致**每一张成功下载的照片都会被实际下载并落盘两次**。这是"全选下载重复多遍"的主因，与用户操作无关。

| # | 问题 | 根因定性 | 难度 | 是否需真机验证 |
| --- | --- | --- | --- | --- |
| 1 | 全选下载重复多遍 | **代码缺陷**（重试循环无 break + 队列无去重 + 状态非线程安全） | 中 | 是 |
| 2 | 连接后相册不刷新 | **架构缺失**（Fragment 常驻 + 无连接状态订阅） | 低 | 是 |
| 3 | 快门方向反 / 光圈无限位 | **代码缺陷**（单位换算错误 + 镜头数据未结构化） | 中 | 是（多镜头） |
| 4 | 缺少检查更新 | **功能缺失** | 低 | 否 |
| 5 | 尼康云创接入 | **不可行**（无公开 API / 无第三方认证入口） | — | — |

---

## 1. 全选下载照片重复下载多遍

### 1.1 问题现象

相册页多选/全选后点击下载，同一张照片被重复下载多次：系统相册中出现 `DSC_0001.JPG` 与 `DSC_0001 (1).JPG` 两份同名文件，Toast 重复弹出"已保存"，传输速率与进度条出现二次跳变，下载总耗时约为预期两倍。

### 1.2 根因分析

调用链：`TransferFragment.btnDownload` (`:253`)
→ `TransferViewModel.downloadSelected()` (`TransferViewModel.kt:457`)
→ `TransferManager.enqueue()` (`TransferManager.kt:597`)
→ `TransferManager.processQueue()` (`TransferManager.kt:642`)
→ `TransferManager.downloadPhoto()` (`TransferManager.kt:279`)
→ `downloadVia()` (`:345`) → `saveFileToMediaStore()` (`:767`)

共 5 个缺陷，按贡献度排序：

#### RC-1（主因）重试循环缺少成功终止条件 —— `TransferManager.kt:681-703`

```kotlin
repeat(2) { attempt ->
    result = try {
        downloadPhoto(file = nextTask.file, onProgress = { ... }, targetFile = tempFile)
    } catch (e: CancellationException) { TransferResult.Cancelled }
    if (result is TransferResult.Failed && attempt == 0) {
        delay(1500)          // 只有失败才等待
    }
}
```

`repeat(2)` 无条件执行 2 轮，**没有 `if (result is Success) return` 或 `break`**。因此：

- attempt 0 成功 → `if` 条件不成立 → attempt 1 照常执行 `downloadPhoto()`
- 而首次成功后 `downloadVia()` 已在 `:383` 执行 `tempFile.delete()`，临时文件已删除
- 第二次 `downloadToFile()` (`:422`) 读取 `target.length() == 0`，判定为全新传输，走完整下载
- 第二次 `saveFileToMediaStore()` (`:767`) 再次 `resolver.insert()`，MediaStore 对同名 `DISPLAY_NAME` 自动追加后缀，生成 `xxx (1).JPG`
- `transferRepository.recordTransfer()` (`:384`) 同样执行两次

**结论：即使网络与相机完全正常，每张照片也必然下载并落盘 2 次。**

#### RC-2 入队无去重 —— `TransferManager.kt:597-606`

```kotlin
fun enqueue(files: List<CameraFile>) {
    if (files.isEmpty()) { postMessage("没有可下载的文件"); return }
    val tasks = files.map { TransferTask(it, TransferTaskStatus.PENDING) }
    transferQueue.addAll(tasks)      // 无 handle 去重
    _queue.value = transferQueue.toList()
    processQueue()
}
```

未校验 `transferQueue` 中是否已存在相同 `handle` 的 `PENDING`/`DOWNLOADING` 任务，也未在入队前查 `TransferRepository.isAlreadyTransferred()`。配合 UI 层 `btnDownload` 无防抖（`TransferFragment.kt:252-259` 无 `isEnabled` 置灰、无时间窗拦截），重复点击直接叠加任务。

补充：`downloadSelected()` 用 `displayedPhotos`（不含筛选，`TransferViewModel.kt:458`），`downloadAll()` 用 `_photoList`（**连视频也包含**，`TransferViewModel.kt:520`），两者与"全选"所依赖的 `filteredPhotos`（`:416`）语义不一致，可能把视频也拉进下载队列。

#### RC-3 状态字段非线程安全 —— `TransferManager.kt:68-75`

```kotlin
private val transferQueue = mutableListOf<TransferTask>()   // 普通可变 List
private var currentTask: TransferTask? = null
private var currentJob: Job? = null
private var isPaused = false
```

`processQueue()` 在两处被调用：

- 主线程路径：`enqueue()` (`:605`) ← UI 点击
- 协程路径：`:675` 与 `:727`（位于 `scope?.launch` 协程体内，且 `downloadPhoto()` 内部 `:294` 已切到 `Dispatchers.IO`，返回后仍在 IO 线程）

两条路径会并发读写 `transferQueue` / `currentTask` / `currentJob`。`processQueue()` 的唯一守卫是 `:643` 的 `currentJob?.isActive == true`，而 `currentJob` 在 `:722` 被置 null 之后才在 `:727` 递归调用，中间存在可被打断的窗口。并发下可能出现两个 job 同时处理同一任务，或 `ConcurrentModificationException`。

#### RC-4 去重检查时机过晚 —— `TransferManager.kt:666`

`transferRepository.isAlreadyTransferred()` 在任务**开始执行时**才检查，而非入队时。已下载文件仍会进入队列、占用 `currentTask`、被计为一次 `COMPLETED`，用户看到"已下载过，自动跳过"的提示，但队列长度与进度展示已被污染。

#### RC-5 数据库无唯一约束 —— `NLinkDatabase.kt:10-17, 39-40`

`TransferRecord` 主键是 `id: Long` (`autoGenerate = true`)，`file_handle` 上**没有唯一索引**，`@Insert(onConflict = REPLACE)` 对自增主键永不触发冲突。因此数据库层面无法阻止同一 handle 写入多条记录，只能靠 `isTransferred()` 的 `EXISTS` 查询兜底。

### 1.3 涉及模块

| 文件 | 职责 |
| --- | --- |
| `camera/gallery/TransferManager.kt` | 队列、并发、落盘（核心改造点） |
| `shared/data/NLinkDatabase.kt` | 去重索引、DAO 查询 |

### 1.4 解决方案

**S1. 修复重试循环（对应 RC-1，必须）**

将 `repeat(2)` 改为显式重试语义，仅在失败时重试：

```kotlin
var result = runCatching { downloadPhoto(...) }.getOrElse { TransferResult.Failed(it.message ?: "未知错误") }
if (result is TransferResult.Failed) {
    Timber.tag(TAG).w("Task retry after failure: ${nextTask.file.fileName}")
    delay(1500)
    result = runCatching { downloadPhoto(...) }.getOrElse { TransferResult.Failed(it.message ?: "未知错误") }
}
```

**S2. 入队去重（对应 RC-2，必须）**

在 `enqueue()` 入口做两层过滤，并用屏幕锁保护：

```kotlin
fun enqueue(files: List<CameraFile>) = queueMutex.withLock {
    val inFlight = transferQueue.mapTo(mutableSetOf()) { it.file.handle }
    val accepted = mutableListOf<CameraFile>()
    for (file in files) {
        if (file.handle in inFlight) continue                       // 队列内去重
        if (runCatching { transferRepository.isAlreadyTransferredSync(file.handle) }
                .getOrDefault(false)) continue                       // 已传输去重
        inFlight += file.handle
        accepted += file
    }
    if (accepted.isEmpty()) { postMessage("所选照片均已在队列中或已下载"); return }
    transferQueue.addAll(accepted.map { TransferTask(it, TransferTaskStatus.PENDING) })
    _queue.value = transferQueue.toList()
}
```

注意：去重查询改为在**入队时**批量执行一次（先取出已传输 handle 集合），避免 N 次 IO。

**S3. 队列状态收敛到单一锁内（对应 RC-3，必须）**

- 引入 `private val queueMutex = Mutex()`
- `enqueue()` / `pause()` / `resume()` / `cancelAll()` / `processQueue()` 全部走 `queueMutex.withLock`
- `processQueue()` 内的递归自调用改为 `scope?.launch { processQueue() }`，避免在持锁路径上重入
- `transferQueue` 改为在锁内统一读写，`_queue.value` 的快照也在锁内生成

**S4. 统一"全选"与"下载全部"的数据源（对应 RC-2 附）**

在 `TransferViewModel` 中把 `downloadAll()` (`:518`) 的数据源从 `_photoList.value` 改为 `filteredPhotos.value`，与 `selectAllFiltered()` (`:416`) 保持一致，避免把视频/非筛选项拉入下载。

**S5. 数据库唯一约束（对应 RC-5，建议）**

- `@Entity(tableName = "transfer_history", indices = [Index(value = ["file_handle"], unique = true)])`
- `@Insert(onConflict = OnConflictStrategy.IGNORE)`
- 数据库 `version` 由 1 升至 2，并提供 `Migration(1, 2)`：建唯一索引前先按 `file_handle` 去重保留 `transfer_time` 最大的一条

> 该改动涉及 Room schema 升级，若担心迁移风险可降级为"仅 S1+S2+S3+S4"，见回退方案。

### 1.5 验收标准

| # | 场景 | 期望 |
| --- | --- | --- |
| AC-1 | 全选 50 张照片下载一次 | 系统相册 `N-Link` 目录新增文件数 == 50，无 ` (1)` 后缀副本 |
| AC-2 | 下载过程中连续点击"下载" 5 次 | 队列不发生膨胀，Toast 提示"所选照片均已在队列中或已下载"，同一 handle 不重复落盘 |
| AC-3 | 下载完成后再次全选下载 | 全部任务在入队阶段被跳过，不发起任何 PTP 传输（`download_start` 事件数为 0） |
| AC-4 | 单张下载中途断连后重连 | 重试 1 次；重试成功只落盘 1 次；重试失败提示中文失败原因，不产生副本 |
| AC-5 | 下载中切 Tab / 退后台再回前台 | 队列状态不丢失，进度连续，无并发重复下载 |
| AC-6 | 日志核查 | `AppEventLogger` 导出的日志中，同一 handle 的 `download_start` 条数 ≤ `download_done` 条数，且成功场景下两者均为 1 |

### 1.6 回退方案

| 层级 | 动作 | 影响 |
| --- | --- | --- |
| L1 | 仅回退 S5（数据库迁移） | 去掉 `@Entity` 索引与 `Migration`，回退 `version = 1`。去重退化为应用层 `EXISTS` 查询，功能无损，仅失去兜底约束 |
| L2 | 回退 S1–S4 | 恢复重复下载行为，**不可接受**，需整分支回滚 |
| L3 | 整分支回滚 | `git revert` 该分支合并提交；传输功能回到 v0.1.2 行为 |

数据兜底：S5 的 `Migration(1,2)` 执行前先对 `transfer_history` 全表备份到 `transfer_history_bak_1`；若迁移抛异常，捕获后 `fallbackToDestructiveMigration()` 仅限于该表重建（历史记录可重建，不影响已落盘照片）。

---

## 2. 相机连接成功后相册未自动刷新

### 2.1 问题现象

在「设备」页完成相机连接后切到「相册」页，列表为空或停留在上一次的缓存，必须手动点击刷新按钮或下拉才会加载。USB 有线连接时表现更明显。

### 2.2 根因分析

#### RC-1（主因）Fragment 常驻，`onViewCreated` 只执行一次

`MainActivity.kt:49-52` 四个 Fragment 是 Activity 的**成员字段**，`:108-115` 用 `add()` 一次性加入并 `hide()`，`:151-160` 切 Tab 只做 `hide()/show()`：

```kotlin
private fun switchFragment(target: Fragment) {
    if (target == activeFragment) return
    supportFragmentManager.beginTransaction().apply {
        setCustomAnimations(R.anim.tab_enter, R.anim.tab_exit)
        hide(activeFragment); show(target)
    }.commit()
    activeFragment = target
}
```

因此 `TransferFragment` 的 `onViewCreated` **整个 App 生命周期内只执行一次**，其中 `:71` 的 `viewModel.fetchPhotos()` 也只跑一次 —— 时机是 App 冷启动、相机尚未连接。

首次调用走 `TransferViewModel.fetchPhotos()` (`:117`)：`hasActiveSession()` 为 false → 落到 `:152-163` 的 WiFi 分支，显示"正在建立 WiFi 通道..."，`awaitPtpSession()` 超时后仅设置 `_message`，**之后没有任何重试机制**。

#### RC-2 已有连接状态流却无人订阅

`TransferViewModel.kt:108` 已经暴露了连接状态：

```kotlin
val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState
```

但 `TransferFragment.observe()` (`:323-434`) 收集了 `photoFilter`、`activeAlbum`、`filteredPhotos`、`selectedHandles`、`thumbnails`、`isLoading`、`message`、`managerMessage`、`usbState`、`transferState` —— **唯独没有 `connectionState`**。

`:390-398` 收集的 `usbState` 也只用于更新空态文案，不触发加载：

```kotlin
viewModel.usbState.collect {
    if (viewModel.activeAlbum.value == AlbumSource.CAMERA && viewModel.photoList.value.isEmpty()) {
        binding.tvMessage.text = "连接相机后查看相册 · 当前通道: ${viewModel.activeChannel()}"
    }
}
```

#### RC-3 连接成功回调不触达相册层

连接成功的判定链路：`BleManager/PtpSession → ConnectionStateMachine` → `ConnectionState.FULLY_CONNECTED`（`ConnectionStateMachine.kt:99`）→ `ConnectionManager.connectionState` (`ConnectionManager.kt:85`)。

`ConnectionManager.observeStateMachine()` (`:477-510`) 在状态变更时**只更新 `_statusMessage` 与 `connectedSince`**，不触发任何相册加载。现有唯一与相册相关的钩子是 `transferManager.scheduleAutoSync()`（`:376`、`:421`），但它首行即 `if (!settings.autoDownload) return`（`TransferManager.kt:154`），默认关闭时完全不生效。

#### RC-4 USB 通道不进连接状态机

`ConnectionManager.observeUsbEvents()` (`:535-539`) 只打日志：

```kotlin
usbPtpManager.usbState.collect { state ->
    Timber.tag(TAG).d("USB state changed: $state")
}
```

USB 有线连接不会让 `ConnectionState` 变为 `FULLY_CONNECTED`，所以即便订阅了 `connectionState`，纯 USB 场景仍然收不到信号 —— 必须同时订阅 `usbState`。

#### RC-5 加载无幂等守卫

`TransferViewModel.loadPhotos()` (`:168-183`) 没有并发守卫：

```kotlin
private fun loadPhotos() {
    _isLoading.value = true
    viewModelScope.launch {
        val photos = transferManager.fetchPhotoList(onPage = { page -> _photoList.value = page })
        _photoList.value = photos
        ...
    }
}
```

`_isLoading` 仅用于 UI 展示，不做拦截。若新增自动刷新后与手动刷新叠加，会并发跑两个 `fetchPhotoList()`，产生列表闪烁与重复分页回调。

### 2.3 涉及模块

| 文件 | 职责 |
| --- | --- |
| `camera/gallery/TransferViewModel.kt` | 新增自动刷新触发、加载守卫 |
| `camera/gallery/TransferFragment.kt` | 订阅连接状态、保留手动入口 |

> 本任务**不修改** `ConnectionManager.kt`。理由：`connectionState` 与 `usbState` 已由 `TransferViewModel` 暴露，改造可以完全在相册层闭环，避免与连接层耦合、避免与其它分支抢文件。

### 2.4 解决方案

**S1. 在 ViewModel 中合并"连接就绪"信号**

```kotlin
/** 任一通道就绪即视为可加载相册 */
val cameraReady: StateFlow<Boolean> = combine(
    connectionState, usbState
) { conn, usb ->
    conn == ConnectionState.FULLY_CONNECTED ||
    conn == ConnectionState.BLE_CONNECTED ||
    usb == UsbConnectionState.CONNECTED
}.stateIn(viewModelScope, SharingStarted.Eagerly, false)
```

`UsbConnectionState.CONNECTED` 已确认存在（`UsbPtpManager.kt:702`）。

> 注：`BLE_CONNECTED` 也纳入判定，是因为该状态下相机已在线、可经 BLE 侧触发 WiFi 升级；纳入后相册能更早出内容。若实测发现 `BLE_CONNECTED` 阶段 PTP 尚未就绪导致加载必失败，则移除该项、只保留 `FULLY_CONNECTED || usb == CONNECTED`。

**S2. 边沿触发自动刷新（只在"从未就绪→就绪"时加载一次）**

```kotlin
private var lastReady = false
fun onCameraReadyChanged(ready: Boolean) {
    val rising = ready && !lastReady
    lastReady = ready
    if (rising && _activeAlbum.value == AlbumSource.CAMERA) loadPhotos()
}
```

只取上升沿，避免 `FULLY_CONNECTED` 期间反复轮询导致重复加载；也避免从相册 Tab 切走再切回时无谓重刷。

**S3. 加载幂等守卫**

```kotlin
private var loadJob: Job? = null
private val loadingGuard = AtomicBoolean(false)

private fun loadPhotos(force: Boolean = false) {
    if (!force && !loadingGuard.compareAndSet(false, true)) return
    loadJob?.cancel()
    loadJob = viewModelScope.launch {
        try { ... } finally { loadingGuard.set(false) }
    }
}
```

`refreshActiveAlbum()` (`:201`) 走 `force = true`，保证手动刷新永远能打断并重新加载。

**S4. Fragment 侧订阅 + 保留手动入口**

`TransferFragment.observe()` 新增：

```kotlin
viewLifecycleOwner.lifecycleScope.launch {
    viewModel.cameraReady.collect { ready -> viewModel.onCameraReadyChanged(ready) }
}
```

手动入口**完全保留、不改行为**：

- `binding.btnRefresh` (`:229-236`) → `viewModel.refreshActiveAlbum()`
- `binding.swipeRefresh` 下拉 (`:199-201`) → `viewModel.refreshActiveAlbum()`

并在连接就绪自动加载时给出可见反馈（`_message` 置为"相机已连接，正在加载相册…"），避免用户以为卡住。

### 2.5 验收标准

| # | 场景 | 期望 |
| --- | --- | --- |
| AC-1 | 冷启动 → 连相机 → 切到相册 Tab | 无需人工操作，相册自动加载出照片；`isLoading` 指示正确出现与消失 |
| AC-2 | 已连相机时冷启动 App → 切到相册 | 相册自动加载（取 `cameraReady` 初始值，配合 `SharingStarted.Eagerly`） |
| AC-3 | 相册已加载后，断开再重连相机 | 重连后自动刷新一次，能读到断连期间新拍的照片 |
| AC-4 | 相册已加载后，切到别的 Tab 再切回 | **不**重复加载（无 loading 闪烁，列表不重置） |
| AC-5 | 手动点击刷新按钮 / 下拉刷新 | 立即重新加载，可打断进行中的自动加载，行为与 v0.1.2 一致 |
| AC-6 | 自动加载进行中手动下拉刷新 | 不产生并发请求，列表最终结果唯一，无重复条目 |
| AC-7 | USB 有线连接（不走 BLE/WiFi 状态机） | 同样能自动加载 |
| AC-8 | 自动下载开关关闭时 | 相册自动刷新仍然生效（不依赖 `scheduleAutoSync`） |

### 2.6 回退方案

- **L1**：移除 Fragment 中的 `cameraReady` 收集（1 处 `launch` 块），自动刷新关闭，手动刷新不受影响。
- **L2**：回退 ViewModel 的 `loadPhotos` 守卫，回到无守卫版本；风险仅为重复加载，不影响数据正确性。
- **L3**：整分支回滚。由于未触碰 `ConnectionManager.kt`，回滚不影响连接链路与其它分支。

---

## 3. 快门方向相反 / 光圈无限位

### 3.1 问题现象

- 开启监看画面后，调节快门速度时"调快"实际变慢、"调慢"实际变快，方向与界面刻度相反。
- 光圈可以调到镜头物理上不支持的档位（例如套机头调到 f/1.4），相机侧无响应或写入失败，界面数值与实际不符。
- 光圈候选档位是固定的 11 档，与当前安装的镜头无关。

### 3.2 根因分析

#### 3.2.1 快门方向反转

**单位约定**（`CameraParameterManager.kt:655` 注释）：`PROP_EXPOSURE_TIME` 以 **1/10000 秒**为单位，`1/250s = 40`。

**档位表**（`LiveViewFragment.kt:68-71`，`RemoteFragment.kt:516-517` 与 `CameraParameterManager.kt:718` 同值）：

```kotlin
private val shutterValues = listOf(
    10, 13, 15, 20, 25, 30, 40, 50, 60, 80, 100, 125, 160, 200,
    250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000
)
```

按 1/10000s 换算，真实物理值：

| 索引 | raw | 真实曝光 | 界面显示（`LiveViewFragment.kt:257`） |
| --- | --- | --- | --- |
| 0 | 10 | **1/1000 s** | `1/10 s` ❌ |
| 5 | 30 | **1/333 s** | `1/30 s` ❌ |
| 8 | 60 | **1/167 s** | `1/60 s` ❌ |
| 13 | 200 | **1/50 s** | `1/200 s` ❌ |
| 26 | 4000 | **0.4 s** | `1/4000 s` ❌ |

**RC-1（主因）显示映射漏了 1/10000 换算** —— `LiveViewFragment.kt:256-258`：

```kotlin
displayValues = shutterValues.map { "1/$it s" },
```

把 raw 值当成"分母"直接显示，正确应为 `10000 / raw`。

**RC-2 由此导致的顺序反转** —— 这是"方向相反"的直接来源：

- raw 序列升序（10 → 4000）= 曝光时间递增 = **物理上快门越来越慢**
- 显示文本序列（`1/10 s` → `1/4000 s`）= 分母递增 = **读起来快门越来越快**

两者方向完全相反。用户按界面刻度向下滚动选"更快的快门"，实际下发的是更长的曝光时间。

**RC-3 当前值永远定位失败** —— `LiveViewFragment.kt:318`（`RemoteFragment.kt:232` 同）：

```kotlin
val currentIdx = displayValues.indexOfFirst { it == current }
picker.value = if (currentIdx >= 0) currentIdx else 0
```

`current` 来自 `CameraParameterManager.readShutterSpeed()` (`:506-521`)，那里的换算是**正确**的（`value / 10000.0`），产出形如 `1/250`。而 `displayValues` 里是 `1/40 s`，字符串永远匹配不上，`currentIdx` 恒为 `-1`，滚轮**每次都跳回 index 0**（实际 1/1000s）。这进一步放大了"调节方向不对"的观感。

**RC-4 与"开启监看画面后"的关联**

`RemoteFragment`（Tab3 内嵌监看）与 `LiveViewFragment`（全屏监看页）用的是**同一份错误档位表**，缺陷本身与是否开监看无关。差异在于：不开监看时用户多数不会去调快门；一旦进入监看页，`cellShutter` 是主操作区，且 `binding.tvShutterValue` (`:517-519`) 显示的是**正确**值、`showParamPicker` 里显示的是**错误**值，两处口径不一致，立即暴露为"方向反了"。

**RC-5 步进方向语义未定义** —— `CameraParamsViewModel.kt:83-97`：

```kotlin
fun adjustShutter(direction: Int) {
    val current = paramManager.shutterSpeed.value.rawValue
    val list = paramManager.commonShutterSpeeds
    val newIdx = stepIndex(current, list, direction)
    paramManager.setShutterSpeed(list[newIdx])
}

private fun stepIndex(currentRaw: Int, values: List<Int>, direction: Int): Int {
    val idx = values.indexOfFirst { it >= currentRaw }
    val base = if (idx == -1) values.lastIndex else idx
    return (base + direction).coerceIn(0, values.lastIndex)
}
```

`direction = +1` 在快门上意味着"曝光变长 = 变慢"，与 ISO/光圈（`+1` = 数值变大 = 感光度更高/光圈更小）的直觉不一致，且代码与 UI 均未标注语义。

#### 3.2.2 光圈无限位、未与镜头联动

**RC-6 光圈档位硬编码** —— 三处各自维护一份、完全不联动镜头：

- `CameraParameterManager.kt:717` `commonApertures = listOf(140, 180, 200, 280, 350, 400, 560, 800, 1100, 1600, 2200)`（f/1.4 … f/22）
- `LiveViewFragment.kt:67` `apertureValues`（同值，复制一份）
- `RemoteFragment.kt:513` `ParamCatalog.commonApertures`（同值，再复制一份）

**RC-7 写入无任何校验** —— `CameraParameterManager.kt:644-651`：

```kotlin
suspend fun setAperture(fStopX100: Int): Boolean {
    if (_paramsLocked.value) return false
    val data = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        .putShort(fStopX100.toShort()).array()
    val success = writeDeviceProp(PtpConstants.PROP_F_NUMBER, data)
    if (success) readAperture()
    return success
}
```

无上下限 clamp、无镜头范围校验、无失败回读比对。`setShutterSpeed()` (`:657-664`) 同样无 clamp（当前依赖 `stepIndex` 的 `coerceIn` 兜底，直接按值设置时无保护）。

**RC-8 镜头数据读了但没结构化保存** —— `CameraParameterManager.kt:222-237`：

```kotlin
private suspend fun readLensInfo() {
    val lensId = readDeviceProp(PtpConstants.PROP_NIKON_LENS_ID)?.let(::readUInt)
    val focalMinData = readDeviceProp(PtpConstants.PROP_NIKON_FOCAL_LENGTH_MIN)
    val focalMaxData = readDeviceProp(PtpConstants.PROP_NIKON_FOCAL_LENGTH_MAX)
    val apMinData = readDeviceProp(PtpConstants.PROP_NIKON_MAX_AP_AT_MIN)   // 0xD0E5
    val apMaxData = readDeviceProp(PtpConstants.PROP_NIKON_MAX_AP_AT_MAX)   // 0xD0E6
    ...
    val lensName = buildLensName(lensId, focalMin, focalMax, apMin, apMax)
    if (lensName.isNotBlank()) {
        _cameraInfo.value = _cameraInfo.value.copy(lensName = lensName)     // ← 只存了字符串
    }
}
```

光圈上下限 `apMin` / `apMax` 被拼进 `lensName` 展示字符串后**即被丢弃**。`CameraInfo` 数据类（`:757-767`）只有 `lensName: String`，**没有任何结构化光圈/焦距字段**，因此 UI 层无从消费。这就是"已读取到镜头参数但光圈档位不联动"的根因。

**RC-9 无 `GetDevicePropDesc` 能力**

全仓库不存在 `getPropertyDesc` / `GetPropertyDesc` 实现（`PtpSessionManager`、`UsbPtpManager` 均只有 `getDevicePropValue` / `setDevicePropValue`）。因此**无法直接向相机索取"可用光圈列表"**，只能由镜头属性推导。

可用作替代的已有常量：`PROP_NIKON_MAX_AP_AT_MIN = 0xD0E5`（广角端最大光圈）、`PROP_NIKON_MAX_AP_AT_MAX = 0xD0E6`（长焦端最大光圈）、`PROP_FOCAL_LENGTH = 0x5008`（标准 PTP，当前焦距 ×100，可用于变焦镜头插值）、`PROP_NIKON_FOCAL_LENGTH_MIN/MAX = 0xD0E3/0xD0E4`。最小光圈（如 f/22）**没有对应 PTP 属性**，需按行业惯例取 f/22 兜底。

### 3.3 涉及模块

| 文件 | 职责 |
| --- | --- |
| `camera/params/CameraParameterManager.kt` | 镜头数据建模、档位生成、写入校验（核心） |
| `camera/params/CameraParamsViewModel.kt` | 对外暴露档位、步进语义、校验入口 |
| `camera/liveview/LiveViewFragment.kt` | 全屏监看页参数选择器 |
| `capture/RemoteFragment.kt` | Tab3 参数滚轮选择器 |

### 3.4 解决方案

**S1. 建立唯一档位真源，删除三处重复硬编码**

在 `CameraParameterManager` 内新增 `ShutterCatalog` / `ApertureCatalog`，`LiveViewFragment` 与 `RemoteFragment` 改为从 `CameraParamsViewModel` 读取，彻底消除三份复制。

**S2. 修正快门显示换算**

```kotlin
/** raw 以 1/10000s 为单位；<1s 显示为 1/N，≥1s 显示为 N.Ns */
fun formatShutter(rawX10000: Int): String {
    val seconds = rawX10000 / 10000.0
    if (seconds <= 0) return "--"
    return if (seconds >= 1.0) String.format(Locale.US, "%.1fs", seconds)
           else "1/${(1.0 / seconds).roundToInt()}"
}
```

同时修 `readShutterSpeed()` (`:515`) 的 `"1/${(1.0 / seconds).toInt()}"`，改为 `roundToInt()` 并复用同一函数，保证读取值与滚轮刻度**口径完全一致**。

**S3. 统一列表方向语义**

约定：**列表按"曝光时间升序"排列 = 从快到慢**（index 0 = 最快，lastIndex = 最慢），与现有 raw 顺序一致，避免改动底层顺序引入新风险。

对外 API 明确语义，禁止用含糊的 `+1/-1`：

```kotlin
/** @param step >0 向"曝光变长/变慢"方向步进，<0 向"曝光变短/变快"方向步进 */
fun adjustShutter(step: Int)
fun adjustAperture(step: Int)   // >0 向"光圈收小/f 值变大"方向
```

UI 文案随之标注：滚轮标题下方增加"↑ 更快 / ↓ 更慢"提示，消除歧义。

**S4. 修正滚轮当前值定位**

不再用显示字符串反查，改用 raw 值匹配：

```kotlin
val currentIdx = rawValues.indexOfFirst { it >= param.rawValue }.let { if (it < 0) rawValues.lastIndex else it }
picker.value = currentIdx
```

**S5. 镜头数据建模**

`CameraInfo` 增加结构化字段：

```kotlin
data class CameraInfo(
    ...
    val lensFocalMinMm: Double = 0.0,     // 0 = 未知
    val lensFocalMaxMm: Double = 0.0,
    val lensMaxApAtMinFocalX100: Int = 0, // 广角端最大光圈（f 值 ×100）
    val lensMaxApAtMaxFocalX100: Int = 0, // 长焦端最大光圈
    val lensApertureRangeKnown: Boolean = false
)
```

`readLensInfo()` 在拼 `lensName` 的同时写入这些字段，并置 `lensApertureRangeKnown`。

**S6. 按镜头动态生成光圈档位**

```kotlin
/** 生成当前镜头可用光圈档位（f 值 ×100），按 f 值升序（大光圈→小光圈） */
fun apertureOptions(currentFocalMm: Double? = null): List<Int> {
    val info = _cameraInfo.value
    if (!info.lensApertureRangeKnown) return COMMON_APERTURES_FALLBACK  // f/1.4–f/22

    // 变焦镜头：按当前焦距在广角端/长焦端最大光圈之间线性插值
    val maxAp = interpolateMaxAperture(info, currentFocalMm)   // 最大光圈 = f 值下限
    val minAp = DEFAULT_MIN_APERTURE_X100                       // 2200 (f/22)，PTP 无此属性，按惯例兜底
    return COMMON_APERTURES_FALLBACK.filter { it in maxAp..minAp }
           .let { if (it.isEmpty()) listOf(maxAp) else it }
}
```

- `currentFocalMm` 由 `PROP_FOCAL_LENGTH (0x5008)` 读取；读不到时退化为取 `lensMaxApAtMinFocalX100`（保守取最大光圈较小的一侧，避免给出镜头不支持的档位）
- 定焦镜头 `lensMaxApAtMinFocalX100 == lensMaxApAtMaxFocalX100`，插值自然退化为定值
- 镜头信息未知（老机身 / CPU 镜头 / 转接）时回退到现有 11 档，行为与 v0.1.2 一致

**S7. 写入限位与失败回读**

```kotlin
suspend fun setAperture(fStopX100: Int): Boolean {
    if (_paramsLocked.value) return false
    val options = apertureOptions()
    val clamped = clampToNearestOption(fStopX100, options)   // 越界取最近合法档
    if (clamped != fStopX100) Timber.tag(TAG).w("Aperture clamped: $fStopX100 -> $clamped")
    val ok = writeDeviceProp(PtpConstants.PROP_F_NUMBER, leShort(clamped))
    if (ok) {
        readAperture()
        if (_aperture.value.rawValue != clamped) {           // 回读校验：相机可能拒绝
            Timber.tag(TAG).w("Aperture write not applied, device reports ${_aperture.value.rawValue}")
        }
    }
    return ok
}
```

`setShutterSpeed()` 同步加 `coerceIn(list.first(), list.last())`，防止按值设置时越界。

**S8. UI 联动**

`LiveViewFragment` / `RemoteFragment` 的光圈选择器改为订阅 `paramsViewModel.apertureOptions`，并：

- 档位为空或镜头未知时，显示"未获取到镜头信息，使用通用档位"
- 越界档位在滚轮中直接不出现（而非出现后报错）

### 3.5 验收标准

| # | 场景 | 期望 |
| --- | --- | --- |
| AC-1 | 打开快门滚轮 | 刻度显示正确物理值：`raw 10` 显示 `1/1000`，`raw 40` 显示 `1/250`，`raw 4000` 显示 `0.4s` |
| AC-2 | 滚轮定位 | 当前相机快门为 1/250 时，打开滚轮默认停在 `1/250`，而非跳到首项 |
| AC-3 | 快门方向 | 从 `1/250` 向"变慢"方向调一档 → 相机 `PROP_EXPOSURE_TIME` 变大、`readShutterSpeed()` 回读变慢；反之亦然。相机取景器/肩屏数值与 App 显示一致 |
| AC-4 | 首尾限位 | 调到最快一档后继续"变快"无变化（不回绕、不越界）；最慢一档同理 |
| AC-5 | 光圈档位联动（定焦 f/1.8） | 滚轮只出现 ≥ f/1.8 的档位（f/1.8、f/2、f/2.8 … f/22），f/1.4 不出现 |
| AC-6 | 光圈档位联动（变焦 24-70 f/2.8-4） | 24mm 端最小 f 值为 2.8；转到 70mm 端刷新后最小 f 值变为 4.0 |
| AC-7 | 光圈写入校验 | 强行写入 f/1.4（f/1.8 镜头）→ 被 clamp 到 f/1.8 并打日志；相机侧实际生效值与 App 显示一致 |
| AC-8 | 镜头信息未知 | 回退到 f/1.4–f/22 通用档位，功能与 v0.1.2 一致，不崩溃 |
| AC-9 | 参数锁定开启 | `setAperture` / `setShutterSpeed` 全部返回 false，不写入 |
| AC-10 | 一致性 | 拍摄页、全屏监看页两处的快门/光圈刻度口径完全一致 |

### 3.6 回退方案

| 层级 | 动作 | 影响 |
| --- | --- | --- |
| L1 | 关闭镜头联动（`apertureOptions()` 恒返回 `COMMON_APERTURES_FALLBACK`） | 回到 v0.1.2 的固定 11 档；快门显示修正与限位仍生效。**建议作为默认降级开关**，置于 `AppSettings` |
| L2 | 回退 S7 的 clamp | 越界值直写给相机，由相机自行拒绝；App 侧可能显示值与实际不符 |
| L3 | 回退 S2 显示换算 | 不推荐 —— 会重新引入方向反转 |
| L4 | 整分支回滚 | 参数模块回到 v0.1.2；因未触碰 `PtpProtocol.kt` / `PtpSessionManager.kt`，不影响传输与连接 |

风险提示：`PROP_NIKON_MAX_AP_AT_MIN/MAX` 为尼康厂商私有属性，部分机身/转接镜头可能返回 0 或不支持。所有读取点必须 `runCatching` 包裹，取到 0 视为"未知"并走回退路径（AC-8）。

---

## 4. 新增检查更新功能

### 4.1 问题现象

App 无版本更新发现能力。用户无法得知新版本发布，只能手动到 GitHub Releases 页面查看。

### 4.2 根因分析

**纯功能缺失**，无既有代码路径。现状核查：

| 项 | 现状 | 位置 |
| --- | --- | --- |
| 版本号 | `versionCode = 3` / `versionName = "0.1.2"` | `app/build.gradle.kts:26-27` |
| debug 变体 | `versionNameSuffix = "-debug"` → `BuildConfig.VERSION_NAME == "0.1.2-debug"` | `app/build.gradle.kts:54` |
| 版本展示 | 「关于」对话框，仅显示版本号 | `SettingsFragment.kt:182-191` |
| 更新入口 | **无** | `res/layout/fragment_settings.xml:365` (`rowAbout`) 之后无相关行 |
| 网络库 | 无 OkHttp / Retrofit；有 Gson 2.11.0 | `app/build.gradle.kts:127` |
| 网络权限 | `INTERNET` 已声明 | `AndroidManifest.xml:33` |
| 仓库地址 | `https://github.com/Wan-1230/N-link` | `git remote` |

结论：**无需新增任何依赖**，`HttpURLConnection` + `Gson` 即可完成，不需要改动 `build.gradle.kts`，也不触碰 `AndroidManifest.xml`。

### 4.3 涉及模块

| 文件 | 类型 | 职责 |
| --- | --- | --- |
| `shared/update/ReleaseInfo.kt` | 新增 | 数据模型 + 版本号比对 |
| `shared/update/UpdateChecker.kt` | 新增 | 网络请求、解析、降级 |
| `settings/SettingsFragment.kt` | 修改 | 入口 UI、对话框 |
| `res/layout/fragment_settings.xml` | 修改 | 新增「检查更新」行 |
| `shared/di/AppModule.kt` | 修改 | 提供 `UpdateChecker` 单例 |

> 不改动 `AndroidManifest.xml`（`INTERNET` 已存在）、不改动 `build.gradle.kts`（不引入新依赖）、不改动任何 `strings.xml`（沿用设置页现有的 XML 内硬编码中文风格）。

### 4.4 解决方案

**S1. 数据源**

GitHub Releases 最新版本接口（无需 token，只读公开仓库）：

```
GET https://api.github.com/repos/Wan-1230/N-link/releases/latest
Accept: application/vnd.github+json
```

成功响应关键字段：`tag_name`、`name`、`body`、`html_url`、`assets[].browser_download_url`、`assets[].name`、`assets[].size`、`published_at`、`prerelease`、`draft`。

> 未认证请求限额 60 次/小时/IP，对本场景（用户手动触发 + 低频自动检查）完全够用。

**S2. 版本号解析与比对**

```kotlin
/** 支持 v1.2.3 / 1.2.3 / 1.2.3-debug / v1.2 / 1.2.3-beta.2 */
data class SemVer(val major: Int, val minor: Int, val patch: Int, val preRelease: String?) : Comparable<SemVer>
```

比对规则：

1. 去掉前缀 `v`/`V`，取 `-` 之前的 `major.minor.patch`，缺失位补 0
2. `major` → `minor` → `patch` 逐级比较
3. **预发布版本低于同号正式版**（`1.0.0-beta` < `1.0.0`）；双方都是预发布时按字典序比较标识符
4. 解析失败（如 tag 写成 `release-2026`）：若 `tag_name` 与本地 `versionName` 字面量不等，则**保守提示"发现新版本"**并引导跳转网页，不做自动判断
5. `prerelease = true` 或 `draft = true` 的 release **默认不提示**（`AppSettings` 留开关）

**S3. 检查流程**

```
用户点击「检查更新」
   └─> 防抖（1.5s 内重复点击忽略）+ 按钮进入 loading 态
        └─> withContext(Dispatchers.IO) { HttpURLConnection }
             ├─ 8s 连接超时 / 8s 读取超时
             ├─ HTTP 200 → Gson 解析 → 版本比对
             │     ├─ 有新版 → 更新对话框（版本号 / 更新说明 / 更新 / 稍后）
             │     └─ 无新版 → Toast「已是最新版本」
             └─ 异常/非 200/解析失败 → 降级（见 S5）
```

自动检查（可选，默认关闭）：`MainActivity.onCreate` 中若距上次检查 > 24h 则静默检查一次，仅在新版本时弹窗，失败完全静默。

**S4. 下载与跳转**

优先取 `assets` 中第一个 `.apk` 的 `browser_download_url`；没有 apk 则退化为 `html_url`。

```kotlin
val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
```

用 `runCatching { startActivity(intent) }`，失败（无浏览器）时改为弹出对话框展示完整 URL 供用户复制。**不实现 APK 自下载与安装**（避免 `REQUEST_INSTALL_PACKAGES` 权限与 Android 8+ 未知来源安装的合规成本，也避免与 GitHub 直连下载的不稳定性耦合）。

**S5. 失败降级矩阵**

| 失败类型 | 判定 | 降级行为 | 用户可见 |
| --- | --- | --- | --- |
| 无网络 | `UnknownHostException` / `IOException` | 提示"无法连接服务器" | Toast + 提供「前往发布页」按钮 |
| 超时 | `SocketTimeoutException` | 同上 | 同上 |
| HTTP 403/429 | GitHub 限流 | 提示"检查过于频繁，请稍后再试" | Toast + 「前往发布页」 |
| HTTP 404 | 仓库无 release | 提示"暂无发布版本" | Toast |
| HTTP 5xx | 服务端错误 | 提示"服务器异常" | Toast + 「前往发布页」 |
| JSON 解析失败 | `JsonSyntaxException` | 视为"检查失败"，**不误报新版本** | Toast + 「前往发布页」 |
| 版本解析失败 | tag 非语义化 | 保守提示"发现新版本"，跳转网页由用户判断 | 对话框 |

**任何失败路径都不得误报"有更新"**，也不得导致设置页崩溃 —— 全部 `runCatching` 包裹，异常写入 `AppEventLogger` 便于导出排查。

**S6. UI 落点**

`res/layout/fragment_settings.xml`「通用设置」组内，`rowAbout` (`:365`) **之前**插入 `rowCheckUpdate`，结构复用现有三列式（标题 + 右侧值 `tvUpdateValue` + 箭头图标）：

- 默认右侧显示 `BuildConfig.VERSION_NAME`
- 检查后：无新版 → `已是最新`；有新版 → `发现 vX.Y.Z`（文字色改为强调色）
- 「关于」对话框追加一行"当前版本 v0.1.2"

### 4.5 验收标准

| # | 场景 | 期望 |
| --- | --- | --- |
| AC-1 | 仓库 latest release 版本 > 本地 | 弹更新对话框，含版本号、更新说明（body 前 500 字）、「更新」「稍后」 |
| AC-2 | 仓库 latest release 版本 == 本地 | Toast「已是最新版本」，右侧值变为"已是最新" |
| AC-3 | 仓库 latest release 版本 < 本地（本地更新） | 无提示，不误报 |
| AC-4 | 断开网络后点击检查 | 800ms 内给出"无法连接服务器"，不 ANR、不崩溃，提供「前往发布页」 |
| AC-5 | 连续快速点击 5 次 | 只发起 1 次请求（防抖生效） |
| AC-6 | 主线程阻塞测试 | 网络请求期间 UI 可正常滚动、可切 Tab（StrictMode 无 `DiskRead`/`NetworkOnMainThread` 告警） |
| AC-7 | release 带 apk 资产 | 「更新」按钮跳转 apk 直链 |
| AC-8 | release 无 apk 资产 | 「更新」按钮跳转 release 页面 |
| AC-9 | 仓库无任何 release（404） | Toast「暂无发布版本」，不崩溃 |
| AC-10 | debug 包（versionName = `0.1.2-debug`） | 版本解析正确剥离 `-debug` 后缀，与 tag `v0.1.2` 正确比对 |
| AC-11 | prelease/draft | 默认不提示；打开设置开关后提示 |
| AC-12 | 日志 | 所有检查行为写入 `AppEventLogger`，可在「导出日志」中看到 `update_check` 事件与结果 |

### 4.6 回退方案

| 层级 | 动作 | 影响 |
| --- | --- | --- |
| L1 | 移除 `rowCheckUpdate` 的点击监听（保留行但置灰） | 功能隐藏，UI 不报错 |
| L2 | 移除 Fragment 中的调用，保留 `UpdateChecker` 类 | 死锁代码，无运行期影响 |
| L3 | 整分支回滚 | 设置页回到 v0.1.2；因 `AppModule.kt` 仅新增 `@Provides`，回滚干净 |

服务端兜底：即便 GitHub API 变更或仓库改名，`UpdateChecker` 的请求 URL 抽为常量 `RELEASES_API_URL` 集中管理；任一失败路径都引导用户跳转 `html_url`（该地址不依赖 API），保证用户永远有手动获取更新的通路。

---

## 5. 尼康云创（Nikon Imaging Cloud）接入可行性评估

### 5.1 问题

调研并评估接入尼康云创的可行性，明确所需接口、认证方式与数据同步范围。

### 5.2 调研结论：**当前不可行**

#### 5.2.1 服务形态

尼康云创（Nikon Imaging Cloud，官网 `https://imagingcloud.nikon.com`，中国区 `https://imagingcloud.nikon.com.cn`）是尼康自家的**相机直连云服务平台**，提供四类能力：

| 能力 | 说明 | 数据流向 |
| --- | --- | --- |
| 影像传输 | 照片暂存云端，自动转发到第三方网盘（Lightroom / Dropbox / Google Photos / OneDrive / NIKON IMAGE SPACE）。免费 30 天存储 | **相机 → 云 → 网盘** |
| 成像配方 / 色彩方案 | 云端 Picture Control 预设，下载到机身（最多 9 个） | 云 → 相机 |
| 固件更新 | 云端推送固件到相机 | 云 → 相机 |
| 真实性服务（C2PA） | 为照片附加内容凭证 | 相机侧 |

兼容机型：Z6III / Z50II / Z5II / Zf / ZR。

**关键点：所有能力的发起方是相机或 Web 浏览器，不存在"第三方 App 调用云 API"的设计。**

#### 5.2.2 认证方式（实测）

`https://auth.cld.nikon.com/.well-known/openid-configuration` 的跳转链（实测）：

```
auth.cld.nikon.com  --302-->  accounts.cld.nikon.com/login
                    --302-->  /login/complete
                    --302-->  /sso/login
                    --302-->  auth.accounts.cld.nikon.com/auth/realms/user/protocol/openid-connect/auth
                              ?response_type=code
                              &client_id=c0001
                              &redirect_uri=https://accounts.cld.nikon.com/sso/login
                              &scope=openid
```

即：Nikon ID 基于 **Keycloak**（realm `user`），前端 `client_id=c0001` 是尼康第一方 Web 应用的公共客户端。

#### 5.2.3 技术阻塞点

| # | 阻塞点 | 说明 |
| --- | --- | --- |
| **B1** | **无公开 API** | 尼康未发布任何 Nikon Imaging Cloud 的 REST/GraphQL API 文档、OpenAPI 规格或开发者门户。功能全部由 Web 前端内部调用，接口未对外暴露 |
| **B2** | **无第三方 OAuth 客户端注册入口** | Keycloak realm `user` 下没有面向第三方开发者的 client 注册流程。App 若要拿 token，只能复用 `client_id=c0001`（第一方 Web client），这属于**未授权访问他人服务** |
| **B3** | **合规与 ToS 风险** | 逆向并复用第一方 client_id、模拟 Web 前端调用，违反尼康服务条款；且属"绕过技术保护措施"。对于一个开源项目，这是不可接受的法律风险 |
| **B4** | **数据同步范围不可介入** | 「影像传输」是**相机直连 Wi-Fi 上传到云**完成的，云端只是中转。照片流不经过手机 App，第三方 App 在链路上没有位置，无法"接管"或"同步"这部分数据 |
| **B5** | **凭据无法合法获取** | 即便逆向成功，token 有效期、刷新机制、风控策略均由尼康单方面控制，随时失效，无法作为产品功能交付 |
| **B6** | **机型覆盖面过窄** | 仅 Z6III/Z50II/Z5II/Zf/ZR 五款支持云创，而 N-Link 面向的机型更广（Z6/Z7/Z5/Z50/Z30/Z8/Z9/Zf 等），接入收益面有限 |

#### 5.2.4 唯一官方开放渠道（不含云服务）

`sdk.nikonimaging.com/apply/` 提供尼康 SDK 免费下载（需申请流程），内容是**数码单反/微单的遥控功能 Library Programs 与 Command API 规格**（桌面端 Windows/macOS）。官方明确声明**不提供技术支持**。该 SDK 覆盖的是**本机遥控**，**不包含任何云创/云服务接口**。

### 5.3 结论

> **不实现尼康云创接入。** 阻塞点为核心级（B1/B2/B3），非工程量问题，短期无解。

按 PRD 约定，本任务交付**可行性评估报告**，不改动任何业务代码，因此不占用与其它任务重叠的文件。

### 5.4 建议的替代路径（另立需求，不在本版本范围）

| 优先级 | 方案 | 说明 | 依赖 |
| --- | --- | --- | --- |
| P1 | **云创网页入口** | 设置页增加「尼康云创」入口卡片，用 `ACTION_VIEW` 跳转 `https://imagingcloud.nikon.com(.cn)`。只读、零认证、零数据、零风险 | 无 |
| P2 | **标准网盘直传** | App 内已下载的照片一键/自动上传到用户自己的网盘（OneDrive / Dropbox / Google Photos / 阿里云盘 / 腾讯云 COS）。这些平台均有公开 API 与正规 OAuth 注册 | 各平台 OAuth 应用注册 |
| P2 | **申请尼康官方 SDK** | 向 `sdk.nikonimaging.com` 提交申请，获取 Command API 规格，用于增强本机遥控能力（非云） | 尼康审批 + 无官方支持 |
| P3 | **持续观望** | 关注尼康是否开放开发者计划；若开放，重新评估 | — |

> 若希望在当前版本就落地 P1（网页入口），需与任务 4 协商 `SettingsFragment.kt` / `fragment_settings.xml` 的改动顺序（建议串行：T4 先合入，P1 后基于最新代码改），以维持"文件互不重叠"约束。

### 5.5 验收标准

| # | 期望 |
| --- | --- |
| AC-1 | 产出评估报告，包含上述 B1–B6 阻塞点、认证链路实测记录、官方 SDK 渠道说明 |
| AC-2 | 每条阻塞点附可核查的信息来源（官网 / 实测 curl / SDK 页面） |
| AC-3 | 给出 P1–P3 替代路径及各自前置依赖 |
| AC-4 | 明确结论："不实现"，且理由可追溯到具体阻塞点而非"调研不深" |

---

## 6. 分支规划与文件归属

### 6.1 分支结构

```
master (74e136f, v0.1.2)
└── dev/v0.1.3-multi-fix          ← 集成主干，本 PRD 所在分支
    ├── fix/album-download-dedup         ← T1
    ├── fix/album-auto-refresh-on-connect← T2
    ├── fix/shutter-aperture-control     ← T3
    ├── feat/app-update-checker          ← T4
    └── research/nikon-cloud-feasibility ← T5
```

所有子分支从 `dev/v0.1.3-multi-fix` 切出，各自独立实现，完成后由集成主干按 T1 → T3 → T4 → T2 → T5 顺序合并（把改动面最大、最需要真机验证的放前面）。

### 6.2 文件归属矩阵（**零重叠**）

| 文件 | T1 下载去重 | T2 自动刷新 | T3 快门光圈 | T4 检查更新 | T5 云创调研 |
| --- | --- | :-: | :-: | :-: | :-: | :-: |
| `camera/gallery/TransferManager.kt` | ✅ **独占** | — | — | — | — |
| `shared/data/NLinkDatabase.kt` | ✅ **独占** | — | — | — | — |
| `camera/gallery/TransferViewModel.kt` | — | ✅ **独占** | — | — | — |
| `camera/gallery/TransferFragment.kt` | — | ✅ **独占** | — | — | — |
| `camera/params/CameraParameterManager.kt` | — | — | ✅ **独占** | — | — |
| `camera/params/CameraParamsViewModel.kt` | — | — | ✅ **独占** | — | — |
| `camera/liveview/LiveViewFragment.kt` | — | — | ✅ **独占** | — | — |
| `capture/RemoteFragment.kt` | — | — | ✅ **独占** | — | — |
| `settings/SettingsFragment.kt` | — | — | — | ✅ **独占** | — |
| `res/layout/fragment_settings.xml` | — | — | — | ✅ **独占** | — |
| `shared/di/AppModule.kt` | — | — | — | ✅ **独占** | — |
| `shared/update/ReleaseInfo.kt`（新增） | — | — | — | ✅ 新增 | — |
| `shared/update/UpdateChecker.kt`（新增） | — | — | — | ✅ 新增 | — |
| `docs/nikon-cloud-评估.md`（新增） | — | — | — | — | ✅ 新增 |

**明确不改动的文件**（避免跨任务冲突）：

- `device/connect/ConnectionManager.kt` —— T2 通过 ViewModel 已有的 `connectionState` / `usbState` 闭环，不碰连接层
- `device/ptp/PtpProtocol.kt` / `PtpSessionManager.kt` —— T3 复用已有的 `0xD0E5/0xD0E6/0x5008`，不新增 PTP 操作码
- `AndroidManifest.xml` / `build.gradle.kts` —— T4 复用已有 `INTERNET` 权限与 Gson，不新增依赖
- `MainActivity.kt` —— 本版本不动（T2 的自动刷新在 Fragment 层闭环）

### 6.3 各子任务自测要求

每个分支完成后必须提交：

1. **改动说明**：改了哪些文件、每个文件的改动摘要、新增/删除的函数
2. **自测结果**：逐条对照该任务的验收标准（AC-1 … AC-n），标注 ✅ 通过 / ⚠️ 未验证（附原因，如"需真机"）/ ❌ 不通过
3. **编译验证**：`./gradlew :app:assembleDebug` 通过，无新增 warning 升级为 error
4. **冲突检查**：`git diff dev/v0.1.3-multi-fix --stat` 确认改动文件集合与 6.2 矩阵完全一致
5. **回退说明**：该分支的 L1 回退点在哪一行

### 6.4 待确认项（动工前需明确）

| # | 事项 | 影响 | 建议 |
| --- | --- | --- | --- |
| ~~Q1~~ | ~~`UsbConnectionState` 枚举项名称~~ | — | ✅ 已确认：`UsbConnectionState.CONNECTED`（`UsbPtpManager.kt:702`） |
| Q2 | T1 是否执行 S5（Room 版本升到 2 + 唯一索引） | 涉及数据库迁移，有一定风险 | 建议**执行**，但先做备份表；若求稳可拆到 v0.1.4 |
| Q3 | T3 是否需要按当前焦距实时插值（读 `0x5008`） | 变焦镜头体验；增加 PTP 轮询压力 | 建议**只在打开光圈滚轮时读一次**，不进轮询 |
| Q4 | T4 是否启用"启动时静默自动检查" | 增加启动耗时与网络请求 | 建议**默认关闭**，留 `AppSettings` 开关 |
| Q5 | T4 是否需要支持 `prerelease` 提示 | 内测分发 | 建议**默认关闭**，留开关 |
| Q6 | 是否落地 5.4 的 P1（云创网页入口） | 会与 T4 抢 `SettingsFragment.kt` | 建议**放 v0.1.4**，或串行排在 T4 之后 |

---

## 7. 交付节奏

| 阶段 | 内容 | 产出 |
| --- | --- | --- |
| D0（当前） | 代码库排查 + PRD 编写 | 本文档 + `dev/v0.1.3-multi-fix` 分支 |
| **D1** | **等待确认** | 用户对 PRD 与 6.4 待确认项给出意见 |
| D2 | 切出 5 个子分支，子 agent 并行实现 | 5 个分支 + 各自改动说明与自测结果 |
| D3 | 按序合并到 `dev/v0.1.3-multi-fix`，跑编译 | 集成构建 |
| D4 | 汇总交付 | 合并改动说明 + 各分支自测结果汇总 + 真机验证清单 |

**D1 未确认前不动工。**
