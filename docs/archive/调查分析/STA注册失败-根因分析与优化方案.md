> **归档（2026-10-03）**：本文待办/判据/未决分叉已并入 `docs/PRD-N-Link-v3.0-待办收敛与路线图.md`（M0/M2 节）。

# STA 主机注册失败 —— 根因分析与全链路优化方案

> 输入：`n-link_logs_1790754813423.txt`（vivo V2509A，av 2.6.1-debug，2026-09-30 15:47–15:53）
> + ZDROP 1.0.257 / ZRelay 3.0.46 / 影犀 三个竞品 APK 的反编译结果。
> 证据分级：**【代码确凿】**（源码/反编译可见）、**【日志实证】**（日志里能看到）、
> **【推断待验证】**（逻辑链成立但需真机确认，标注了验证方法）。

---

## 1. 原因定位

### 1.1 结论先行

注册失败发生在**注册协议握手的第 2 步**（PrepareHost 预备注册），
**不是** WiFi 认证 / 关联 / DHCP / 路由问题 —— 那几层全部成功（AP 已连、TCP 握手成功、
PTP 会话已建立、前序 PTP 操作可执行）。是**相机在协议状态层拒绝了注册事务**：
它此刻**不处于主机配置向导状态**。

### 1.2 日志直接证据

```
15:53:07.860 sta_ok gen=2 host=192.168.1.1 port=15740      ← AP 会话重建成功
15:53:16.973 hostreg phase=start
15:53:16.983 hostreg phase=prepare_fail code=0x201f        ← 10ms 秒拒
15:53:22.617 hostreg phase=start
15:53:22.627 hostreg phase=prepare_fail code=0x201f        ← 重试同结果
```

`0x201F` 的含义：**PTP 标准响应码 TransactionCancelled（事务被取消）**。
四个独立权威来源一致 —— Wireshark dissector、Apple PTPPassThrough、indigo-astronomy、
petabyt/vcam（我方 `PtpProtocol.kt:152` 命名为 `RESPONSE_TRANSACTION_CANCELLED`，
也是正确的）。**它不是尼康私有码，是"这单事务在当前状态下不被受理"**。

### 1.3 为什么确定"不是我们的协议实现错了"

**【代码确凿】** ZDROP 1.0.257 完整反编译成功（未加固）。它的注册序列在
`sources/p040n1/C0358c4.java:3107-3129`：

```
GetDeviceInfo
→ OpenSession(0x1002, param=1)
→ PrepareHost(0x952B, 无参)
→ Thread.sleep(8500)
→ ConfirmHost(0x935A, param=0x2001)
```

**与 N-Link 的实现逐字节一致**（我方 `PtpSessionManager.registerHost` 同序列同参数同等待）。
并且 ZDROP 对一切非 `0x2001` 的响应一律抛异常中止（`E():2306`）——
**它同样不接受 0x201F**。ZDROP 自己的失败文案（`:3061`）把这类拒绝归因于：

> 「相机拒绝该主机（0x…）。请让相机停留在 **STA 主机配置向导**，并等待相机完成保存。」

—— 唯一可用的同类实现，对同一种失败的解释就是**相机向导状态**。

### 1.4 相机当时处于什么状态（推断链）

1. **【日志实证】** 15:48:09 首次 AP 连接成功（`on_camera_ap=true ssid_is_nikon=true`）。
2. **【日志实证】** 15:50:23 `link_error reason=idle_timeout` —— 会话被相机端掐断。
   注册向导类会话在尼康相机上只在向导活动期间保活，idle 掐断往往意味着**向导已退出**。
3. **【日志实证】** 15:53:07 重连成功 —— 相机仍在 192.168.1.1 提供 PTP/IP 服务；
   但 15:53:16 PrepareHost 被秒拒 —— 服务在，**注册事务不受理**。
4. **【推断待验证】** 尼康相机的 AP 热点有两种入口，**都在 192.168.1.1 提供 PTP/IP**：
   - 「连接至智能设备」（SnapBridge AP）→ 可传输照片，**不接受** 0x952B
   - 主机配置向导（「连接至 PC」或新固件的「Wi-Fi 连接（STA mode）」）→ **接受** 0x952B

   我们的教程只说"切到「WiFi-AP」模式连上相机"，从没写清**相机侧应该进哪个菜单** ——
   用户按教程开的很可能就是 SnapBridge AP。这是**推断**，验证方法见 §4.4 对照实验。

### 1.5 次要问题（会加剧误导，独立存在）

- **【代码确凿】InitFail 原因不分级**（`ConnectionManager.observeHostRegistrationHint` :710-720）：
  任何 InitFail 都置「需要注册」。日志 15:50:24 `fail_reason=1` 实际是
  **connection_in_use**（机身唯一的 PTP/IP 客户端槽被上一代会话的尸体占着），
  正确动作是等待重试，却被报成"需要注册" —— 引导用户在错误时机去点注册。
- **【日志实证】网络上下文混乱时的空转**：15:50:40 起手机已离开相机热点
  （`ifaces=ap0 10.184.219.24/24,ccmni4 10.56.31.140/8`，无 192.168.1.x），
  App 仍对 192.168.1.1 发起**蜂窝口**连接（`from /10.56.31.140`），
  从 15:50:45 空转到 15:51:38（~53s）才等到手机回连相机热点。

---

## 2. 现状分析 —— STA 连接完整实现流程

### 2.1 模块调用链

```
DashboardFragment（三模式 tab + STA 子模式 chip + 注册按钮 + 教程）
  └─ DashboardViewModel.registerStaHost()
       └─ ConnectionManager.registerStaHost()        ← 要求 ptpSession.isConnected()
            └─ PtpSessionManager.registerHost()      ← PrepareHost → 8.5s → ConfirmHost
                 └─ PtpProtocol 打包/解包 + PtpIdentityStore（GUID 固化）

DashboardFragment 连接按钮
  └─ ConnectionManager.connectToWifiCamera()
       ├─ 端点解析 → 重入守卫（loopRunning / learning）
       ├─ PreflightGate 预检（硬阻断豁免相机热点 / 软告警 VPN 等）
       ├─ learnFirst 分支：ApGatewayResolver.resolve() 网关学习
       └─ beginWifiPairing → WifiDirectConnector.connect()
            ├─ resolveNetwork → StaNetworkRequester.acquire()（绑网）
            ├─ 退避循环（progress hints）
            ├─ isCameraPortOpen 端口探测（sta_preconnect）
            └─ PtpSessionManager.connect()
                 ├─ TCP → InitCommand（GUID 持久化 + clientName）
                 ├─ InitEvent 通道
                 ├─ GetDeviceInfo（presession，仅记大小，未解析）
                 └─ OpenSession(1) → sta_ok / sta_classify 分级

发现：WifiScanner（NSD + mDNS 5353 组播 + ARP 表 + 子网扫）
状态：ConnectionStateMachine（事件驱动）+ ConnFunnel（分阶段计时）+ EventLogger
恢复：observeStaRecovery / observeReconnectTrigger / recoverWifiSession
```

### 2.2 状态流转

```
Disconnected
  →(用户点连接) Connecting（funnel: DISCOVER→ROUTE→TCP→HANDSHAKE→FIRST_COMMAND）
  → Ready / WifiConnected
  →(InitFail)   staRegisterNeeded=true +（FR-06）撤销"已注册"标记
  →(注册成功)   staHostRegistered=true（写 PREFS，跨重启保留）
  →(链路错误)   wifi_recover_start → sta_try gen+1（resume，指数退避）
  →(网络丢失)   network_lost → 等待 networkAvailable 重建
```

### 2.3 关键函数职责

| 函数 | 位置 | 职责 |
|---|---|---|
| `connectToWifiCamera` | ConnectionManager:224 | 连接总入口：端点解析、重入守卫、预检、AP 网关学习、派发直连器 |
| `beginWifiPairing` | :385 | 启动一轮配对（connector.connect + funnel.begin） |
| `registerStaHost` | :644 | 注册入口：未连接则拒绝，成功则写 PREFS |
| `observeHostRegistrationHint` | :710 | **任何 InitFail → staRegisterNeeded**（问题点，见 §1.5） |
| `promoteAddressOnInitFail` | :738 | 被拒也证明该 IP 是相机，记为 realCameraIp |
| `revokeStaleHostClaim` | :755 | FR-06：被拒即撤销"已注册"标记 |
| `WifiDirectConnector.run` | :260 | 每代连接循环：绑网→探测→建链→分级→退避 |
| `StaNetworkRequester.acquire` | :93 | requestNetwork 绑定到含目标地址的 WiFi 网络 |
| `PtpSessionManager.connect` | ~:250 | InitCommand/InitEvent/GetDeviceInfo/OpenSession |
| `PtpSessionManager.registerHost` | :464 | PrepareHost→(失败即中止)→8.5s→ConfirmHost |
| `resolvePtpTarget` | :1081 | 四级目标地址：BLE 凭证 > realCameraIp > 历史可达 > 兜底 |

---

## 3. 方案对比

| 实现 | STA 注册 | 与我们的差异 |
|---|---|---|
| **N-Link（本仓）** | ✅ PrepareHost→8.5s→ConfirmHost(0x2001) | 基线 |
| **ZDROP 1.0.257**（完整解包） | ✅ 同序列同参数 | InitCommand 名称 `ZDROP-Android`；GetDeviceInfo 编排在注册流程**内部**；成功宣言只在 Confirm 之后；对 0x201A 按"忙"重试 6 次；失败文案指向 STA 向导 |
| **ZRelay 3.0.46** | 未读到 | **Bangcle 加固**（真实 DEX 运行时解密），本轮未 dump；同类产品线（`ZRelay-Android` 命名） |
| **影犀**（com.camerasyncpro） | ❌ **无实现** | 全仓无 0x952B/0x935A，只有 LiveView prepared sequence → 不走 STA 注册这条路（AP/USB/FTP 类） |
| **gphoto2/libgphoto2** | ❌ **无实现** | pixls.us 实证：Z 系列 STA 只能握手、相机不保存配置（注册缺位），桌面端只能靠尼康官方 WTU |
| **NikonLink / 帧澈ZENCHE** | ❌ **另一条路** | 相机 **FTP 推送**收图 + USB PTP 控制，完全绕开 STA 注册（`连接到FTP服务器` 是尼康官方菜单项） |
| **laheller/ptplibrary** | ❌ 无实现 | 通用 PTP/IP（GUID+name 初始化），无尼康注册 |
| **像素蛋糕**（教程佐证） | — | 相机侧两条官方路径：「连接至智能设备 → Wi-Fi连接（STA mode）」/「连接到计算机 → 照相机控制」；且**配对完成后"连接会断开，按 OK 再连一次"** —— 佐证注册握手结束会断会话 |

**结论：当前设计没有方向性错误。** 0x952B/0x935A 序列与唯一跑通的同类逐字节一致；
问题集中在**相机侧引导文案**与**状态容错**，不在协议本身。
（若长期看，FTP 推送是业界另一条被验证的路，可作为 v3.0 的备选架构，见 §4.5。）

---

## 4. 优化建议

**约束遵守**：AP 连接、USB 连接、传输链路**零改动**；所有新行为挂 `ConnFlags`
开关（沿用 `STA_TIMEOUTS` / `HOSTREG_VERIFY` 的既有模式），`DEFAULTS` 默认 `false`
—— 未开即与现版本**逐位等价**，随时可关回。按风险从低到高排：

### R0【文案，零风险，立即】把相机侧入口写对

- 教程按机型/固件区分入口：
  Z8 / 新固件 =「连接至智能设备 → Wi-Fi连接（STA mode）」；
  Z50II / 旧固件 =「连接至 PC」向导（像素蛋糕教程佐证两条路径都存在）。
- 明确一句：**注册握手必须发生在相机停在「等待计算机 / 正在等待连接」的画面**，
  不是普通 WiFi 已连状态，更不是「连接至智能设备」的 SnapBridge AP。
- `prepare_fail code=0x201f` 的 UI 文案改成：
  「相机拒绝了注册事务（0x201F）：它现在不在主机配置向导。请在相机上重新进入向导、
  停在等待画面后再点注册。」（现为笼统的"确认相机屏幕处于可接受连接的画面"）

### R1【状态判定，低风险】InitFail 原因分级

`ConnectionManager.observeHostRegistrationHint` 按 reason 分流（新开关 `HOSTREG_HINT_V2`）：

| reason | 含义 | 正确动作（现状一律置"需要注册"） |
|---|---|---|
| 1 connection_in_use | 机身单槽被旧会话占着 | 提示"相机正忙，等 10~15s 自动重试"；**不**置 staRegisterNeeded |
| 2 connection_denied | 门控拒绝 | 才置 staRegisterNeeded |
| 3 host_already_connected | 本机已注册且在连 | 直接置 staHostRegistered=true |

### R2【前置检查，中风险】注册前查 SupportedOperations

`GetDeviceInfo` 已在 presession 调用（`PtpSessionManager.kt:360`）但只记了字节数、
**没解析**。解析 `OperationsSupported` 数组：若不含 0x952B，直接报
「相机当前模式不支持主机注册（不在向导）」而**不盲发** PrepareHost。
新开关 `HOSTREG_PRECHECK`。同时把 SupportedOperations dump 进 hostreg 日志行，
让下次的根因定位不再靠猜。

### R3【容错，中风险，先验证再动】0x201F 后的可选策略

现状"失败即中止"与 ZDROP 一致，**保守保持**。可选验证项：
用 ZDROP 在"已注册过的相机"上重跑一次注册，观察 0x201F 之后 ConfirmHost
是否照样成功；若成功，加 `HOSTREG_CONFIRM_ANYWAY`（默认关）。
**未验证前不动这条链。**

### R4【网络，中风险】蜂窝口探测防护

`sta_preconnect` 若源地址是蜂窝口（如 10.56.x ccmni 类），直接判不可达并跳过
socket 尝试 —— 省掉日志里 15:50:45–15:51:38 那种 30s×N 的纯浪费。
与既有 `sta_fallback` 记录呼应，开关沿用 `CONN_SERIALIZE` 族。

### 4.4 根因对照实验（验证 §1.4 的推断）

1. **Z50II（自有）**：相机进「连接至 PC」向导 → AP 直连 → 注册 → 预期 prepare OK。
2. **对照组**：相机进「连接至智能设备」→ AP 直连 → 注册 → **预期复现 0x201F**。
   若复现，根因即锁定为"相机在 SnapBridge AP 而非配置向导"。
3. **Z8（群友）**：「Wi-Fi连接（STA mode）」向导 → 同网 → 注册。
4. 每组都导出日志，抓 `hostreg` 行 +（R2 落地后）SupportedOperations dump。

### 4.5 长期备选架构（不进本版本）

尼康官方有「连接到FTP服务器」菜单（Z 系列全系）：相机主动把照片**推**到手机 FTP 服务，
**不需要任何注册**。NikonLink / 帧澈ZENCHE 走的就是这条路。若 STA 注册在更多机型上
持续踩坑，FTP 推送可作为「无线收图」的 v3.0 备选架构（与现有 PTP 控制链路并存）。

---

## 附：本轮产物索引

- 日志：`n-link_logs_1790754813423.txt`（174 行，已逐行读完）
- ZDROP 反编译：`/tmp/zdrop-out/sources/p040n1/C0358c4.java`（注册序列 :3040-3145，错误处理 :2055-2335）
- 我方相关代码：`device/ptp/PtpSessionManager.kt:464-514`、`device/connect/ConnectionManager.kt:644-770`、`device/ptp/PtpProtocol.kt:104-119,152`

---

## 5. 实施记录（v2.6.3）

本轮把 §4 的 R1/R2/R4 与 §4.5 的备选架构一起落地。**R3 刻意未做**（§4 明确写了
"未验证前不动这条链" —— 需要先用 ZDROP 在已注册过的相机上验证 0x201F 之后
ConfirmHost 是否照样成功）。

### 5.1 入口与切换控件

两种架构**共用同一个「WiFi STA」页签**，顶部加一枚药丸分段控件切换，不新增页面、不新增路由。

```
panelWifiSta (LinearLayout)
 ├ staArchPill            ← 药丸：FrameLayout 底(bg_chip, 3dp padding) + 指示器(bg_chip_selected)
 │   ├ staArchIndicator       宽度 = 行宽/2，运行时算，位移走 GlassMotion.slidePill
 │   ├ tabStaArchPtp  「PTP/IP 直连」   选中 = 黑底 + 白字 + 加粗
 │   └ tabStaArchFtp  「FTP 推送收图」
 ├ layoutStaPtp           ← 架构 A：原实现原样搬入（逻辑零改动）
 └ layoutStaFtp           ← 架构 B：FTP 专属（教程卡 + 服务器卡 + 接收记录）
```

原型与相册页底部 `albumTabPill` 完全一致（同 drawable、同指示器算法、同 `GlassMotion` 动画），
所以是"既有分段控件的第二处使用"，不是新控件。

### 5.2 架构 B：手机侧 FTP 服务器

`device/ftp/FtpPushServer.kt`，自研最小实现，**不引入第三方依赖**。

| 项 | 取值 | 说明 |
|---|---|---|
| 端口 | **2121** | 绑定 <1024 在 Android 上需要 root，这是**唯一需要用户在相机侧改配置**的地方 |
| 用户名 | `nlink`（固定） | |
| 密码 | 安装后首次启动随机生成并持久化 | 避免每次开机都要去相机里重填 |
| 登录方式 | 被动（PASV / EPSV） | 主动模式回 502；相机默认走 PASV |
| 保存位置 | `AppSettings.savePath` → `DCIM/N-Link` 或 `Download/N-Link` | 与相册页下载的照片落在同一处 |

两个实现要点：

1. **227/229 上报的 IP 取控制连接的 `localAddress`**，不是遍历网卡猜出来的。
   相机是从那条连接找过来的，那个地址对它必然可达；在"手机开着热点 + 连着相机热点 +
   蜂窝"三网并存的现场，猜网卡几乎必错（我们 STA 侧 `in_subnet=false` 的日志就是同一个坑）。
2. **服务器持有自己的 CoroutineScope**，不挂在 ViewModel 上 —— 切页 / 进后台都不会中断
   正在进行的推送。只有用户点停止、切回 PTP 架构、或进程死亡才结束。

### 5.3 状态隔离

| 状态 | 归属 | 切换时的处理 |
|---|---|---|
| 连接状态 / 进度 / 扫描结果 | ConnectionManager（架构 A） | 切到 B 时 `stopScan()`，把在途扫描收干净 |
| FTP 服务器 / 客户端数 / 接收记录 | FtpPushServer 独立 StateFlow | 切回 A 时 `stopFtpServer()`，释放端口 |
| 错误提示 | A 走状态行，B 走 FTP 卡内的 tvFtpState/tvFtpHint | 互不写对方的视图 |
| 扫描 / 连接按钮行 | 共享 `actionContainer` | 判据含 `currentMode`：只有「STA + B」隐藏，AP/USB **恒为 VISIBLE** |

### 5.4 R1 / R2 / R4（全部挂 ConnFlags，默认关 = 与 v2.6.2 逐位等价）

| 编号 | 开关 | 行为 |
|---|---|---|
| R1 | `HOSTREG_HINT_V2` | InitFail 按 reason 分级：1→提示"相机正忙，等待自动重试"（不置待注册）；2→置待注册 + 撤销标记；3→直接置已注册 |
| R2 | `HOSTREG_PRECHECK` | 注册前用 `PtpDeviceInfo` 解析 `OperationsSupported`，不含 0x952B 就直接给出"相机不在主机配置向导"的结论，**不发** PrepareHost |
| R4 | `STA_SKIP_CELLULAR` | 探测前判"本机是否有网卡落在相机网段"，没有就判不可达（蜂窝上打私网是黑洞）。**已在相机热点上时豁免**，AP 链路不受影响 |

R0（文案）不挂开关，直接生效：PTP 教程、注册弹窗、失败提示三处都改成明确指向
**相机侧主机配置向导**（「Wi-Fi连接（STA mode）」/「连接至 PC」→ 网络设定），
并点明"普通的「连接至智能设备」热点不接受注册"。0x201F 单独给一句能照着做的诊断。

### 5.5 自检结论

- **架构切换**：PTP↔FTP 来回切换，指示器滑动、容器显隐、按钮行显隐、教程更换四件事同步；
  切换时各自清理对方的在途中间态（扫描 / 服务器）。
- **教程一致性**：FTP 教程里的端口、保存路径、被动模式、切换即停服务器四条与实现逐条对应；
  PTP 教程的相机侧入口与 §1.4 的根因结论一致。
- **AP / USB 不受影响**：`actionContainer` 判据带 `currentMode`；R4 带 `isOnCameraAp()` 豁免；
  R1/R2 整段包在默认关的开关内；`WifiDirectConnector` 仅新增一个构造参数。
- 编译：`assembleDebug` **BUILD SUCCESSFUL**。
