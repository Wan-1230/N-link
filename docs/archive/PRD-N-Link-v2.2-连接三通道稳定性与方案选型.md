# PRD：N-Link v2.2 连接三通道稳定性与方案选型

> 版本：v2.2.0（拟，versionCode 19） · 分支：`research-connection-prd-v2.2` · 基线：v2.1.1（8b4365c）
> 状态：**待确认**（本文档只做方案选型与规划，未改动任何业务代码）
> 定位：连接方向专项 PRD。三种连接方式（WiFi AP / WiFi STA / USB 有线）在不同机型上表现不稳，本文给出「协议允许什么 → 官方/开源能做到什么 → 我们缺什么 → 每条通道的最优解与落地顺序」。
> 原则延续：已跑通的功能不改动、改动面压到最小、每项独立可回滚、每个结论带证据。

---

## 摘要（八个结论，细节见各节）

1. **尼康不给任何移动 SDK**，各家实现都只能自研协议栈 —— 我们的技术路线不是落后，是唯一可行解；且我们用的 **TCP 15740（PTP-IP，CIPA DC-005-2005）+ UDP 5353（DNS-SD）是尼康 Z 系手册白纸黑字写明的**，对外可正名（§2.1、§2.5）。
2. **AP 模式跨机型不稳的第一根因不是 ROM，而是我们「从不学网关」**：全仓无一处读 `routes/gateway/dhcpInfo`，只能靠 BLE 凭据或硬编码 `192.168.1.1`；而且刚做的地址分级**在用户实际路径上是死代码**（必须 BLE 成功才触发）。用「网关学习 + 稳定化 + 自地址排除」彻底解决，改法约 200 行（G1、G2）。
3. **STA 最大的两处提升**：① 扫描期不要对每个候选做 PTP Init（慢 10 倍且**占用相机唯一的配对槽**）；② 本机热点改用 `TetheringManager.TetheringEventCallback` 拿 `Network` 句柄，替代内核接口枚举 + 进程级绑定（G3、G4）。
4. **USB 是我们的结构性优势**（官方 机身官方应用 根本没有 USB），但当前「无状态机、拔一下就判死、小米 OTG 关了我们只会说『无相机』、>2GB offset 溢出」四项把它拖成了不稳（G8、G9、G10）。
5. **「不同机型表现不稳」在产品层面从未被真正治理**：全仓只有一个机型分支（Xiaomi 文案尾巴，提交自述「no behavior change」）。这一点需要成体系地投入，并且**把兼容规则放服务端下发**（`/v1/camera-rules`）。我们要把「机型×ROM×通道」矩阵当产品资产建（G11、G12、§6）。
6. **协议层有三笔欠账**：下载期心跳不豁免（会自己砍掉大文件传输）、数据阶段不抽干 event（丢加片/删除事件）、PTP-IP 标准的**第三条 data 通道**未实现（「双通道」、第三方聚合「control/data/event」都做了）（G7、§2.5-2）。
7. **落地节奏**：v2.2.0 只做 P0 十二项，**且埋点先于改逻辑**（今天 11.1 表里 8 个指标是「未知」，没有基线就无法判断任何一次「优化」是否有效）。
8. **本次未做真机运行测试**（会话内 `adb devices` 为空），9 项必须实机定标的结论已列在 §12，其中 **V-1（InitCommand 字节与 `0x952B/0x935A` 语义）**和 **V-3（相机 WMA 是否可写）**分别决定「外部推断常量的可信度」与「STA 能否免手工」。

---

> **本节内容已按仓库规则移除**
>
> 原先记录了外部产品的横向比较、调研与静态分析取证。
> 依据 [`docs/CONTRIBUTING.md`](CONTRIBUTING.md)「外部产品信息不入库」一条，
> 这类内容不得提交到远端仓库，故整节移除，仅保留本项目自身的技术结论。
>
> 原始分析材料保存在本地工作副本中，不随仓库分发。

> **本节内容已按仓库规则移除**
>
> 原先记录了外部产品的横向比较、调研与静态分析取证。
> 依据 [`docs/CONTRIBUTING.md`](CONTRIBUTING.md)「外部产品信息不入库」一条，
> 这类内容不得提交到远端仓库，故整节移除，仅保留本项目自身的技术结论。
>
> 原始分析材料保存在本地工作副本中，不随仓库分发。

## 四、N-Link 现状盘点与根因（代码级）

### 4.1 连接层资产

`ConnectionStateMachine`(276) → `ConnectionManager`(924，编排/DI 根) → `ble/BleManager`(1030) · `wifi-ap/WifiManager`(379，包名 `wifi_ap`) · `wifi-sta/WifiScanner`(1004) · `wifi_sta/WifiDirectConnector`(609，**AP 与 STA 的唯一连接入口**) · `wifi_sta/StaNetworkRequester`(251) · `wifi_sta/WifiNetworkMonitor`(260) · `wifi_sta/LocalNetworkInterfaceResolver`(182) · `wifi/WifiEndpoint`(175) · `ptp/PtpProtocol`(1002) · `ptp/PtpSessionManager`(970) · `ptp/PtpIpProbe`(121) · `usb/UsbPtpManager`(1127) · `usb/UsbPtpProtocol`(304) · `service/ConnectionService`(190) + `ConnectionHealthWorker`(96) + `BootReceiver`(33)；上层 `TransferManager`(1725) / `CameraParameterManager`(1572) / `LiveViewManager`。全仓 67 个 Kotlin 文件，无任何网络第三方库（OkHttp/Retrofit 均未使用），全部为裸 TCP/UDP socket + GATT。

> 目录 `wifi-sta/` 与 `wifi_sta/` **都是活代码**（Kotlin 包名不能含 `-`，两目录同属 `device.wifi_sta` 包），不是重复实现，仅需统一拼写（零行为风险）。

### 4.2 四套互不知情的重试策略（稳定性第一根因）

| 层 | 策略 | 证据 |
|---|---|---|
| FSM | 1s→2s→4s…≤30s，**无限重试**（注释「永不放弃」） | `ConnectionStateMachine.kt:29-31,256-269` |
| WiFi 连接 | `MAX_ATTEMPTS=10`，`[1000,2000,4000,8000,15000×5]` ≈ 90s；连续 3 次 unreachable 提前放弃；`AWAIT_NETWORK_MS=4000`；前 3 轮额外 `PRECONNECT_WAIT_MS=3000` + 1200ms 探测 | `WifiDirectConnector.kt:64-100,240-407` |
| PTP socket | `CONNECT_TIMEOUT_MS=30000`、5 次 ×300ms，**`SocketTimeoutException` 不重试** | `PtpSessionManager.kt:37-48,142-173` |
| USB | `[1000,2000,4000,8000,15000,15000,15000]` ≈ 68s，总线无设备即停 | `UsbPtpManager.kt:365-402` |
| BLE/legacy | `repeat(3)` + `1500*(n+1)` | `ConnectionManager.kt:757-786` |

问题：同一次失败可能被上层重试 N 次 × 内层重试 M 次（最坏 10×5×… ），**没有全局尝试预算**；而 AOSP 在多次认证失败后会把该 AP 计入 `NetworkSelectionStatus` 禁用（`…_DISABLED_WRONG_PASSWORD` / `…_DISABLED_NO_INTERNET_ACCESS` / `PERMANENTLY_DISABLED`，10+ 已不可读）→ **重试越多越黑**，表现就是「前几次能连，越试越连不上，去设置里忽略网络才好」。

### 4.3 AP 通道的结构性缺陷（本次 P0）

1. **AP 没有自己的连接逻辑**。AP 页与 STA 页只有教程文案与 `hotspotMode` 标志位不同（`DashboardViewModel.kt:106`），实际都走 `WifiDirectConnector`。
2. **全仓从不读取路由/网关信息**：`grep -E "\.routes|RouteInfo|isDefaultRoute|dhcpInfo"` 在 `app/src/main` 命中 **0** 处；`wifi-ap/WifiManager.kt` 内无任何 host/gateway 推导。相机做热点时**网关就是相机**，这条最可靠的信息我们完全没用。
3. **刚做的 P0「目标地址分级」在用户实际路径上是死代码**：`resolvePtpTarget()`/`establishPtpSession()` 只能由 `observeWifiEvents()` 在 `wifi_ap` 状态 `CONNECTED` 时触发，而该状态**必须**先成功读到 BLE `0x2004` 凭据；BLE 配对不完成 → 分级、`realCameraIp` 记忆、「AP 下 192.168.1.1 合法」这条例外**全部不执行**（`ConnectionManager.kt:705-717,741-786`）。
4. **BLE 凭据读取是尽力而为**：LsSec 加密的 `0x2004` 直接丢弃，仅 `Timber.w("WiFi config is LsSec encrypted…")`，用户侧无提示（`BleManager.kt:725`）。→ 新固件机身 AP 模式无法自动加入，只能手连，于是落回第 2 条。
5. **进程死亡即失去凭据**：`wifi_ap.WifiManager.reconnect()` 依赖内存字段 `currentCredential`，重启后静默返回 false（`:224-229`）。
6. **`requestNetwork` 用法有两处坑**：用 `withTimeoutOrNull` 包协程（放弃等待但**系统侧请求仍注册**，每 UID 约 100 个 callback 上限，且污染后续请求，见 `StaNetworkRequester.kt:133` 自述 FIX-3）；未使用带超时的三参重载；未设 BSSID；释放与再请求之间无最小间隔。
7. **无 SSID 规则匹配**：不判断「手机当前连的是不是相机热点」（正则应为 `(?i)^NIKON_([0-9A-F]{8})`，已验证），也没有「手机自己热点 vs 相机 AP」的判别。
8. `WIFI_UPGRADING + ErrorOccurred → BLE_CONNECTED` 直接吞掉错误文本（`ConnectionStateMachine.kt:192`）。

### 4.4 STA 通道现状

已有（且做得不错）：四路并发发现（raw mDNS 5353/`224.0.0.251` 查 `_ptp._tcp.local`+`_nikon._tcp.local`、`/24` 扫 15740 并发 32/800ms、NSD resolve 串行队列、`/proc/net/arp`）+ 18 字段 `ScanStats` + 波次重扫 + 子网掩码匹配选网络 + 拿不到 Network 时回落内核路由（`defaultRouteFallback`）+ 主机注册门控（`0x952B`→8500ms→`0x935A(0x2001)`）。

缺陷：
1. **扫描阶段对每个候选做完整 PTP/IP Init 握手确认** → 慢，且**消耗相机唯一的配对槽位**（ZTransfer 原话：「发 INIT_CMD_REQ 每 IP 3s 且每次都占掉配对槽，TCP-only 快 10 倍」）。
2. 硬编码回落：11 个网关列表（`WifiScanner.kt:483-487`）+ 热点模式下 `192.168.43.1-254` 全扫（`:492-496`）——**已知机型可用，但不是学习得来的**。
3. 本机热点用内核 `NetworkInterface` 枚举兜底（`LocalNetworkInterfaceResolver`），**没用 `TetheringManager.TetheringEventCallback`**（public API，Android 11+）→ 拿不到热点对应的 `Network` 句柄，只能放弃逐 socket 绑定、退回进程级/内核路由，双卡与 WLAN+ 机型上不稳定。
4. 无「相机是否真的加入热点」的正向确认（只有超时后失败），无 AP 隔离检测分支。
5. 主机注册后固定 `8500ms` 沉降，实测是常量的经验值，未按机身区分。

### 4.5 USB 通道现状

已有：`ACTION_USB_PERMISSION` + `FLAG_MUTABLE`(S+)、`vendor-id=1200`(0x04B0 Nikon) device_filter、按 class/subclass/protocol 选接口、`OpenSession` 接受 `0x201E SESSION_ALREADY_OPEN`、`recoverStaleSession()`、bulk-IN **容器重组**（12 字节头 + 按 length 64KiB 窗读，处理跨读合并/txId 不符/50MB 上限）、直写 OutputStream、`clearHaltBothEndpoints()` 后重试一次、6 类错误文案。

缺陷：
1. **USB 完全在 FSM 之外**（`observeUsbEvents()` 只打日志，`ConnectionManager.kt:648-654`）→ 无状态、无重试预算、UI 只有文字。
2. **`DETACHED` 立刻判死**，无宽限窗（线松/hub 复位/相机休眠唤醒都会瞬断再连）。
3. **无 OTG 开关检测**：MIUI/HyperOS 的 OTG 是独立用户开关且**约 10 分钟无操作自动关闭**，关掉后 `getDeviceList()` 直接为空、无异常 → 我们现在会报「总线无相机」，用户不知道去哪开。
4. `claimInterface(intf, true)` 无失败阶梯；靠「每次先 `disconnect(silent=true)`」绕开自己泄漏的 claim（治标，`UsbPtpManager.kt:222-225`）。
5. 断点续传 offset 仍是 `Int`（`TransferManager.kt:704`），>2GB 变负；未实现 `GetPartialObject64`/`GetObjectInfoEx` 系列。
6. `device.serialNumber` 无 `SecurityException` 兜底；USB 授权回调未按设备身份键关联（detach 后到达的授权会作用到「当前设备」）。
7. 监看与文件访问的互斥靠约定（USB 期暂停 `DeviceReady` 心跳），无「相机忙」显式状态。

### 4.6 可观测性与机型适配

- 全仓**只有一个**机型相关分支：`RomDetector.isXiaomi` → 在任意错误文案后追加提示（提交 `afce0b4` 自述「hint only, no behavior change」，`ConnectionManager.kt:613-615`）。华为/荣耀/三星/OV/一加 = 0 命中。
- 错误码分家：WiFi 侧 `no_wifi_network|camera_unreachable|ptp_handshake_failed|invalid_endpoint|connect_failed|not_started`，USB 侧另一套 6 类，BLE 侧第三套；**没有统一的「阶段 × 原因」漏斗**，因此无法回答「小米 15 Pro 上 AP 成功率到底多少」。
- 日志仅本地（3×512KB ring + 导出），无上报、无聚合 → 同类产品 已有 `/v1/events/batch`、`/v1/capability-reports`、`/v1/camera-rules`（**服务端下发兼容规则**）。
- 测试只覆盖纯函数（`WifiEndpointTest`/`PtpProtocolTest`/`PtpSessionManagerTest`/`NikonBlowfishTest`）；**`WifiDirectConnector`、`ConnectionManager`、`StaNetworkRequester`、`LocalNetworkInterfaceResolver`、`UsbPtpManager` 零测试**——正是并发/重试/退避所在处。
- `HeartbeatTimeout` 事件与 `HEARTBEAT_TIMEOUT_MS=10000` 声明后无人派发（死代码）。

---

## 五、三种连接方式的最优解（本 PRD 核心）

> 统一原则：**能力探测优先于经验常量**；**每条通道都要能在「无 BLE」时工作**（新固件 LsSec 加密、部分机身 BLE 配对失败）；**每个失败都必须落到「阶段 + 原因码 + 用户下一步动作」三元组**。

### 5.1 WiFi AP（手机加入相机热点）——最优解

**结论：以「WLAN 已连 + 网关学习」为主干，BLE 凭据只做加速器，不做前提。** 这也是 （无蓝牙权限）能做好的原因，同时规避 LsSec 不可解密的问题。

**T-A1 加入阶段（有 BLE 凭据时）**
1. `0x2004` 解析出 SSID/BSSID/密码/相机 IP（现状 `[1..33]SSID [33..97]pass [97]sec [98..102]IP`，保留）。
2. 构造 `WifiNetworkSpecifier`：**同时 `setSsid()` + `setBssid()`**（BSSID 不受机型/区域/固件命名差异影响），`removeCapability(NET_CAPABILITY_INTERNET)`，WPA2/WPA3 显式分支（新增设置项 + 按机身记忆）。
3. 用**带超时的三参重载** `requestNetwork(req, cb, 15_000)`；**删除现有 `withTimeoutOrNull` 包裹**；`cb` 由 `ConnectionService` 长期持有，进程内不重复注册。
4. 记录 `lastReleaseAt`；同一 SSID 的再请求必须间隔 ≥1500ms（否则框架拆旧网络未完 → 秒回 `onUnavailable`）。
5. `onAvailable` 里 `runCatching { bind* }`；**优先使用 specifier 的 Network 句柄**，不得依赖 `getAllNetworks()` 顺序（双 WLAN 机型顺序不定）。
6. `onUnavailable` 拆三支：a) 并发 scan 里没有该 SSID → 「相机热点没开/已关」；b) scan 有但连不上且 BLE 读到 wifi status=Processing → 「正在启动，等」；c) 其余 → 「密码/WPA 版本不符」并给出手动连接深链。
7. `onLost(DisconnectedInfo)` 原样记录 code/reason，**默认动作是 unregister→重 request→重试**（不假设会自动重连）。

**T-A2 地址学习（新增，替换硬编码）**
```
候选集合（并发、谁先通过 PtpIpProbe 用谁）：
  C1 BLE 0x2004 内嵌 IP            （若可读）
  C2 LinkProperties.routes 中默认路由的 gateway   ← 新增，AP 模式下 gateway 即相机
  C3 本会话已确认过的 realCameraIp
  C4 历史 wifi:host:port（经 PtpIpProbe 验证）
  C5 192.168.1.1 / 192.168.0.1 / 相机名推导的出厂地址（兜底，且标记 fallback=true）
规则：
  - C2 与手机自身 linkAddress 相同 → 丢弃（: "AP gateway ignored because it matches handset address"）
  - C2 连续两次读取一致才认定「stabilized」（否则进入 "AP gateway changed during recovery"）
  - 就绪等待：join 后先等 1200ms 再取 gateway（"AP network readiness wait=1200ms"）
  - 169.254/224/240/127 继续排除（WifiEndpoint 现有逻辑保留）
  - 学习结果写回 `wifi:host:port`，下次跳过 C5
```
影响文件：新增 `wifi_ap/ApGatewayResolver.kt`，`ConnectionManager.resolvePtpTarget()` 接入，**并解除「只有 BLE 路径才能 establishPtpSession」的前提**（把 `establishPtpSession` 的触发点从 `wifi_ap` 状态改为「任一候选地址通过探测」）。

**T-A3 无 BLE 也能用（把 AP 从「BLE 依赖」解耦）**
- 进入 AP 页时读 `WifiManager.connectionInfo.ssid`，命中 `(?i)^NIKON[ _-]?([0-9A-F]{4,8})$` 或 `Nikon-*` → 判定「手机已在相机热点」，直接跳到 T-A2 学习 + 连接，**不再要求 BLE 配对**。
- 未命中 → 展示：① 「打开 WLAN 设置」深链（`Settings.Panel.ACTION_WIFI` / `ACTION_WIFI_SETTINGS`）② 相机端菜单指引（按机身型号出不同文案，抄 「不同 Z 系机型菜单不同」策略）③ 手动 IP 输入（保留）。

**T-A4 双频并发（DBS）探测，把硬件限制说清楚**
- 启动时探测 `WifiManager.isStaConcurrencyForLocalOnlyConnectionsSupported()`（33+）/ `is5GHzBandSupported` / `isBridgedApConcurrentSupported` 等，产出一行「本机能力」进诊断面板。
- 若不支持并发且手机正连着 2.4GHz 基础网 → **主动提示「相机 5GHz 热点会挤掉现有 WiFi，建议先关闭家里 WiFi 或用 USB/STA」**，而不是让用户以为 App 坏了。当前 band 5GHz→auto 盲试（`wifi-ap/WifiManager.kt:101-168`）保留，但结果要按机型记忆。

**T-A5 防系统把 AP 拆掉**
- 诊断项：读 `Settings.Global` 的 `network_avoid_bad_wifi`、`captive_portal_mode`（无需权限）；检测到「避开坏网络」开启 → 首次引导页给出逐 ROM 关闭路径（HyperOS WLAN+/智能切换、Samsung 智能 Wi‑Fi、EMUI WLAN+）。
- 锁：WifiLock(FULL_HIGH_PERF)+MulticastLock+PARTIAL_WAKE_LOCK 全部引用计数（现状保留）；监看期加 `FLAG_KEEP_SCREEN_ON`，**不承诺息屏后台不断**。

**T-A6 会话唯一性**
- 尼康机身同时只允许 **1 个 PTP/IP 客户端**。新增显式状态 `CAMERA_BUSY_BY_OTHER`（映射 `InitFail` 与 `RC_AccessDenied 0x200F` / `PTP_GUID_REJECTED` 类返回），文案直指「请关闭其它设备上正在连接相机的应用」。
- 客户端名（`clientName`）现用 `Build.MODEL`；增加「使用官方 App 兼容名」的实验开关（V-2 验证是否能免弹确认）。

**AP 通道参数表（现值 → 建议值）**

| 参数 | 现值 | 建议 | 依据 |
|---|---|---|---|
| specifier 请求超时 | 15000（协程包） | 15000（**系统三参重载**） | Imagededge |
| 同一 AP 再请求最小间隔 | 无 | 1500ms | Imagededge `lastReleaseAt` |
| join 后取 gateway 前等待 | 无 | 1200ms（可配） | |
| gateway 稳定化次数 | 无 | 2 次一致 | `stabilized` |
| 全局尝试预算 | 无（FSM 无限） | ≤3 次 join / 60s 窗口，之后硬停并引导「忽略此网络」 | AOSP 黑名单机制 |
| event 通道建立后沉降 | 无 | 650ms | `eventSettle=650ms`；ECONNREFUSED×2/100ms |
| 心跳在下载期 | 不豁免 | 豁免 + `selfHeal=false` | Imagededge + v2.0.2 §二-2 |

### 5.2 WiFi STA（相机连手机热点 / 同路由器）——最优解

**T-S1 热点状态改由官方回调驱动**
- 注册 `TetheringManager.registerTetheringEventCallback`（Android 11+ public）→ `onTetheringChanged(TetheringInterface)` 给出 `getNetwork()` / `getIfname()` / `getMode()`。
- 用该 `Network` 做**逐 socket `bindSocket`**（替代 `LocalNetworkInterfaceResolver` 的内核枚举兜底；后者保留为 API<30 与回调缺失时的回落）。这是把「手机自己热点」从不可见变为**可绑定**的正解，且不违反系统限制（`startTethering` 需要 `TETHER_PRIVILEGED`，第三方**不能**开关热点或指定信道 —— 这条要写进设计，避免有人再去尝试）。
- 用户操作仍是唯一入口：`Settings.ACTION_WIRELESS_SETTINGS` / 热点面板深链 + 「保持热点开启」提示。

**T-S2 正向确认「相机真的进来了」**
- 轮询热点 `Network` 的邻居/ARP（现状 `/proc/net/arp`）+ 对候选 IP 做 **TCP-only** connect（不发 Init），最多 12s；超时按 四档文案给出：接口未出现 / 无可用 IP / 本地网络未建立 / 路由未就绪，**并且不向相机发起 PTP/IP 请求**（避免污染配对槽）。
- 新增 reason code `HOTSPOT_NO_CLIENT`、`HOTSPOT_ROUTE_NOT_READY`、`HOTSPOT_LINKLOCAL`（169.254 已具备判定，纳入码表）。

**T-S3 发现阶段改为「TCP 快筛 + 单点握手」**
- `/24` 扫描：并发 32 → 保持；**每主机超时 800ms → 500ms，且只做 `Socket.connect()`**；仅对首个 TCP 成功者做 `Init` 确认。预期整轮从 ~18s 降到 ~4–6s（ZTransfer 实测 10 倍差）。
- mDNS/NSD 服务类型补齐：`_ptp._tcp`、`_nikon._tcp`、`_nikon-ptp`、`_nikon-pc`（同类实现表），并对「相机 IP 非网关」的情况保留 `.200+` 这类地址（**基础设施模式下相机不一定占网关**）。
- 路由器隔离独立分支：TCP 超时且同网段其他主机可达 → `ROUTER_CLIENT_ISOLATION`，文案「请关闭路由器 AP/客户端隔离或改用相机热点/USB」。

**T-S4 主机注册做成可验证的一等公民**
- 现状：`PrepareHost 0x952B` → 固定 8500ms → `ConfirmHost 0x935A(0x2001)`，且 `sta_host_registered` 只是一个布尔。
- 改为：注册后**立刻用 STA 目标地址做一次 PtpIpProbe + Init 验证**，成功才置位；把「注册于哪台机身、哪个固件、何时」入库；提供「重新注册」入口与「注册未生效」专属错误码。沉降时间进 quirks 表按机身取值（V-3）。

**T-S5 让 STA 免手工：把热点凭据写进相机（P1，收益最大的体验升级）**
- 官方 机身官方应用 就是在会话内用 **`GetWmaSettingAction` / `SetWmaSettingAction`（含 WpaPassphrase / WifiChannel / WifiAuth / WpsMode 字段）+ `changeCameraConnectionMode`** 来配置相机侧 WiFi（证据见 §1.3）。
- 我们的可落地版本：在 **AP 会话（或 USB 会话）内**读相机 WMA 能力 → 写入用户热点的 SSID/密码/认证 → 切连接模式 → 相机自己去加入热点 → 端侧用 T-S2 确认。
- 具体操作码/参数块**必须实机实测或机身官方应用确认**（V-3），并放入按机身生效的能力表；任一环节不支持则回落到「相机菜单手动输入」教程（式分机型菜单文案）。
- 若写凭据不可行，退而求其次：**USB 会话内完成主机注册 + 地址学习**（USB 无配对槽问题），再无线复用 —— 这是最低风险的「STA 免手工」过渡方案。

**T-S6 地址漂移与保活**
- 注册 `onLinkPropertiesChanged`，任一网络的 linkAddress/route 变化时重算目标网络；每 15s 复验 subnet（现有 `covers()` 逻辑保留）。
- Ping/Pong 判死规则保留「任何入向活动都算活」（已规避只发 ProbeRequest 的机身），补：`NO_ACTIVITY_TIMEOUT` 在批量传输期豁免（v2.0.2 已定未做）。

### 5.3 USB 有线——最优解

**结论：USB 是三通道里最可控的一条，应成为「不稳定机型」的默认推荐与自动回落终点；但必须先补上独立状态机与 OTG 引导。**

**T-U1 独立连接状态机（进 FSM）**
```
Idle → DevicePresent → PermissionRequested → Claiming → OpeningSession → Ready
     → Degraded(部分能力不可用) → GraceWindow(detach 后 3000ms) → Lost(reason)
每个迁移产出 (stage, reasonCode, ms, deviceModel, rom, attempt)
```
- 采用 PhotoSync 的 sealed state + **字符串 reason code**：`no_device / otg_disabled / permission_denied / permission_timeout / permission_after_detach / no_ptp_interface / claim_failed / session_busy / link_timeout / detached / descriptor_error`。
- `pendingPermissionDeviceId` + `UsbPtpDeviceId(vid,pid,serialOrFallback)` 关联异步授权；detach 之后到达的授权**按 id 丢弃**。
- `device.serialNumber` 包 `runCatching` → `"no-permission-$vid:$pid"`。

**T-U2 OTG 与枚举问题的显式检测（小米类机型的头号原因）**
- 「收到过 `ATTACHED` 广播但 `getDeviceList()` 为空」或「`UsbManager` 无设备且系统 USB 角色非 host」→ 报 `OTG_DISABLED`，展示 **HyperOS/MIUI 路径：设置 → 更多连接 → OTG 连接**，并提示**约 10 分钟无操作会自动关闭**（其他 ROM 文案进兼容矩阵）。
- 相机枚举成非 PTP（存储/充电模式）→ 检测 class≠`StillImage(6)` 时给「在相机菜单把 USB 连接改为 PTP/Mass 之外…」的分机型指引；第三方聚合 SDK 有 `changeToPtpMtpMode`（软件切模式）作为**长期研究项**（V-5）。
- attach/detach 风暴（30s 内同 VID/PID >3 次）→ 判定供电/线材问题，提示外接电源（EH-71P 类）+ 换数据线 + Android 16「USB 保护（锁屏禁数据）」指引。

**T-U3 资源与事务安全**
- **提交式打开**：`UsbDeviceConnection`/`interface`/endpoints 全部先存局部变量，握手成功后才写入字段；任何异常路径 `releaseInterface + close()`（消除「一次失败 → 后续永久 EBUSY」）。
- `claimInterface` 阶梯：`(false)` → `(true)` → 引导「关闭图库/文件管理器等占用相机的应用」。
- 事务锁改 `Mutex`（可取消），**禁止 `@Synchronized`**（monitor 等待不可取消、不遵守 soTimeout）；超时后：业务调用 → 强制关连接自愈；心跳 → 只记录不自愈。
- `MAX_PAYLOAD_SIZE=50MB` 保留并统一为 `MAX_PTP_DATA_BYTES`，所有长度头读取处先校验再分配（防坏头 OOM）。
- 保留纯 JVM `bulkTransfer`（**不引入 /libusb native**：LGPL/GPL + fd 模型冲突 + Android 15/16 的 16KB page size 合规成本，见 §10.4）。可选 P2：`UsbRequest.queue/requestWait` 异步环提升吞吐（须先解决 16KB 对齐与取消语义）。

**T-U4 大文件与 >2GB**
- offset 全链路 `Long`；优先 `GetPartialObject64`/`GetObjectInfoEx`（同类实现已证可用），不支持时按机身 quirks 回退整对象 `GetObject`。
- 自适应分片：首段实测吞吐过低 → 自动缩小分片（「首段异常慢速，改用小范围重试」），分片在 1–4MB 之间按 p50 调整。
- 数据阶段循环内**抽干 event**（`ptp_ptpip_check_event` 模式），保证下载中「新照片/删除/状态」事件不丢。

**T-U5 USB 独占与监看互斥**
- 显式 `BUSY(reason)` 状态 + 文案（同类实现：「监看中，请先停止监看后再访问相机文件」），不再靠静默排队；
- 监看期心跳暂停保留，但**监看结束必须有一次显式 resync**（DeviceReady + GetEventEx）。

### 5.4 跨通道：自动择优与回落顺序

**新增 `ConnectionOrchestrator`（一键连接）**，按「已成功过的通道优先」而非固定顺序：
```
1) 若 USB 设备在场且 OTG 正常      → USB（最快、无配对槽、最稳）
2) 若手机已连 NIKON_XXXX 热点       → AP（网关学习）
3) 若 BLE 已配对且能读到凭据        → AP（specifier 自动加入）
4) 若本机热点在跑                   → STA(热点)
5) 若已连第三方 WiFi 且扫到相机      → STA(同路由)
6) 都不成立                         → 按机型历史成功率排序给建议
每次自动切换都记录 `channel_fallback(from,to,reason)`（现有事件名已具备，扩成漏斗字段）
```
现有 `TransferManager.downloadVia`（550-566）已有 USB↔WiFi 单文件回退，应上升为**会话级**策略（会话级回退需保证目标机身的 GUID 一致性：USB 与 WiFi 的 PTP/IP GUID 不同，切换等于新主机 → 会触发注册门，必须提示）。

---

## 六、机型稳定性专项（本次最大痛点）

> 「同样代码在不同机型上表现不同」的差异，**99% 来自平台与 ROM 的三件事**：① 谁拥有默认路由；② 谁允许你在后台活着；③ 谁替你决定这个没网关的 WiFi「坏掉了」。以下按可实施条目组织。

### 6.1 新增 `PreflightGate`：把「权限不足」拆成可动作的原因码

现状只有一个 Xiaomi 文案尾巴（`ConnectionManager.kt:613-615`）。改为连接前逐项检查、每项独立 code + 独立动作，**任一硬阻断都不进入重试循环**（避免 §4.2 的越试越黑）：

| 检查项 | API / 读法 | 阻断级别 | 失败码 | 用户动作 |
|---|---|---|---|---|
| 定位总开关（API ≤32 扫 WiFi 必需） | `LocationManager.isProviderEnabled(GPS)` + `isLocationEnabled` | 硬 | `LOC_SWITCH_OFF` | 打开位置服务深链 |
| `NEARBY_WIFI_DEVICES`（13+） | `checkSelfPermission` | 硬 | `PERM_NEARBY_WIFI` | 授权对话框 |
| `ACCESS_FINE_LOCATION`（≤12） | 同上 | 硬 | `PERM_LOCATION` | 同上 |
| `BLUETOOTH_SCAN/CONNECT`（12+） | 同上 | 软（AP 可降级） | `PERM_BT` | 同上 |
| 电池优化豁免 | `PowerManager.isIgnoringBatteryOptimizations(pkg)` | 软 | `BATT_RESTRICTED` | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（相机伴侣属 Play 允许用例，上架描述要写明 companion/IoT sync） |
| ROM 自启动/后台策略 | 逐 ROM 深链 + **读回验证**（见 6.2） | 软 | `AUTOSTART_BLOCKED` | 深链 |
| OTG 总开关（小米/一加/realme） | 「ATTACHED 收到但 `getDeviceList()` 为空」推断 | 硬（USB） | `OTG_DISABLED` | 设置路径 + 10 分钟自动关闭提示 |
| 「避开坏网络」 | `Settings.Global.getString(cr,"network_avoid_bad_wifi")`、`captive_portal_mode` | 软 | `AVOID_BAD_WIFI_ON` | 逐 ROM 关闭指引 |
| VPN / 双卡默认路由 | `NetworkCapabilities.hasCapability(NET_CAPABILITY_VPN)`、蜂窝默认路由 | 软 | `VPN_OR_CELLULAR_ROUTE` | 关 VPN / 提示 |
| 双频并发能力 | `isStaConcurrencyForLocalOnlyConnectionsSupported()` 等 | 信息 | — | 诊断面板一行 |
| 已连网络是不是相机 | `WifiInfo.ssid` 正则 + `BSSID` | — | `NOT_ON_CAMERA_AP` | 引导连接 |

### 6.2 ROM 兼容矩阵作为**产品资产**（对照 `/v1/camera-rules`）

1. **数据驱动，不硬编码**：把矩阵做成 asset JSON（`assets/compat/rom_rules.json` + `camera_rules.json`），条目结构：
   `{match:{manufacturer,brand,romProp,apiMin,apiMax}, impact, severity, tricks:[{id,text,intent,component,fallbackUrl,lastVerified}], cameraRules:[{modelRegex, defaultIp, wmaWritable, hostRegSettleMs, partialObject64, lvVariant, ssidRegex, quirks:[]}]}`
2. **来源**：OEM 侧直接采用 `https://dontkillmyapp.com/api/v1/output.json` 的离线快照（含 `intent`/`component`），随版更新；相机侧种子用 **`camlibs/ptp2/cameras/nikon-z*.txt` + `device-flags.h`** 的真人 dump（`NIKON_BROKEN_CAPTURE`、`NO_CAPTURE_COMPLETE`、`DONT_CLOSE_SESSION`、`IGNORE_HEADER_ERRORS` 等），加上我们自己真机标定。
3. **深链实现**：用 `autostarter` 式封装（`resolveActivity()` + try/catch + `ACTION_APPLICATION_DETAILS_SETTINGS` 兜底），逐 ROM 已知组件：`com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity`、`com.coloros.safecenter/.permission.startup.StartupAppListActivity`、`com.vivo.permissionmanager/.activity.BgStartUpManagerActivity`、`com.huawei.systemmanager/.optimize.process.ProtectActivity`。**每条带 `lastVerified`，OTA 后失效要能单独降级为文案指引。**
4. **热更新**（P1）：矩阵支持从我们自己的静态 JSON（百度盘/GitHub raw）拉取覆盖，**不发版修兼容** —— 这是 已经验证过的护城河。
5. **用户回报通道**（P1）：机型×ROM×通道×结果 的本地记录，导出脱敏 CSV；后续可选 opt-in 上报（对照 `/v1/capability-reports`）。

### 6.3 后台存活与 FGS 的正确姿势（Android 14→16）

- 会话期 FGS 用 **`connectedDevice`**（其前置条件我们满足：`CHANGE_WIFI_STATE`/`CHANGE_NETWORK_STATE` 或 `BLUETOOTH_CONNECT/SCAN` 或调用过 `UsbManager.requestPermission`）；**只有用户主动发起的批量下载才用 `dataSync`** —— `dataSync` 有 **6 小时/24 小时配额**（超了抛 `TimeException`），且 **Android 15 起 `BOOT_COMPLETED` 不允许启动 `dataSync`/`camera` FGS，但允许 `connectedDevice`** → `BootReceiver` 必须走 connectedDevice 分支（现状 `BootReceiver.kt:26` 有 `>= O` 死判断，minSdk 29）。
- **sticky 重启但当前无设备/无网络时不要 `startForeground`**（会 `SecurityException` 反复崩重启）→ 先判条件，不满足直接 `START_NOT_STICKY`。
- Android 16 起并发 FGS + Job 有共享配额，长下载要显式让位给交互。
- 不承诺「息屏后台不断」；监看期 `FLAG_KEEP_SCREEN_ON`；三把锁引用计数保留。

### 6.4 BLE 专项（路线的代价与收益）

- **绑定而不是每次重扫**：优先 `CompanionDeviceManager.associate()` 建立绑定关系（同时解决 12+ 免定位、扫描节流 5 次/30s、OEM 更严的后台扫描限制）；`ReconnectingAfterBond`、`stale_bond_remove_timeout`、`classic_bond_timeout_without_confirmation` 说明**绑定态失效**是他们专门建状态处理的问题——我们也必须有对应码（`BLE_STALE_BOND`、`BLE_BOND_NO_CONFIRM`）。
- `connect()` 前先 `BluetoothManager.getDevice`；`status 0x8/0x133` → `close()` + 新建 `BluetoothGatt`（老 GATT 句柄复用是 Android 通病）；MTU 一次协商 247–517；`CONNECTION_PRIORITY_HIGH` **只在凭据交换窗口**用，之后回 BALANCED（省电 + 避免栈异常）。
- LsSec 加密的 `0x2004`：不解密（法律与技术都不可行），改为**明确告知 + 走 T-A3 的无 BLE 主干**。这是 §5.1 结论的直接原因。

### 6.5 「首次连接准备」向导（投入最重的一处）

用一整套中文文案覆盖每个 ROM 的电池/自启动路径（证据：`公开产品文案.txt` 里 12 条以上逐 ROM 指引），并把**相机端菜单差异**写进流程（`不同 Z 系列机型的"连接到计算机"菜单不同…是否存在"连接类型"`、`部分机型（如 Z9）必须先按 OK，手机才能继续连接`）。

我们落地为一次性的 3 屏向导（可跳过、可在设置里重跑）：
1. **本机准备**：权限、定位开关、电池豁免、（小米系）OTG 与 WLAN+ 关闭 —— 每项带「去设置」和「已生效 ✓」读回。
2. **相机准备**：按识别到的机身型号出图出文案（Z8/Z9 与 Z50II/Z30 菜单路径不同；睡眠设为不；发送中保持电源开启）。
3. **通道选择**：一句话推荐（「你的机型 + 这台相机，历史成功率最高的是 USB」），并可当场跑 §7.3 的自检。

---

## 七、可观测性、诊断与量化指标

### 7.1 统一连接漏斗（新增 `ConnFunnel`，取代现在三套分散错误码）

```
S1 intent → S2 preflight → S3 join_requested → S4 dialog_answered → S5 associated/joined
→ S6 ip_obtained → S7 route_bound → S8 discovered(mdns|scan|gateway) → S9 tcp_15740
→ S10 ptp_handshake(init|event|opensession) → S11 first_command → S12 first_frame / first_thumb
→ S13 steady(N min) → S14 reconnect(outcome)
USB 分支：U2 preflight → U3 device_present → U4 permission → U5 claim → U6 session → U7 first_command
```
每条记录 `(stage, reasonCode, ms, channel, rom, api, phoneModel, cameraModel, fw, attempt#)`。
**reason code 全量收口为一张枚举表**，并把现有 WiFi 码（`no_wifi_network|camera_unreachable|ptp_handshake_failed|invalid_endpoint|connect_failed|not_started`）、`InitFail` 子码、USB 6 类映射进去；上层文案 = `code → 分级文案 → 下一步动作`（现有「失败原因分级 + Xiaomi hint」的实现方式作为第一版基础）。

### 7.2 日志与导出

- 保留 `AppEventLogger`（3×512KB ring）但改结构化 `stage k=v`；
- **导出必须显式脱敏并在 UI 声明**（照抄 做法：「导出文件不含照片或 Wi-Fi 密码，可用于定位连接断开原因」）；
- 崩溃日志合并进同一导出包；可选附带 `bugreport` 选择器（支持工单用）。

### 7.3 内置「连接自检」（行业内已有，我们还没有）

有协议诊断模式（`仅用于协议诊断。运行前会断开当前无线会话，完成后保持未连接状态`）。我们实现为设置页一个按钮，15–30 秒跑完：
`Preflight 全项 → 候选网络枚举 → join/复用 → gateway 学习 → TCP 探测 → PTP Init → OpenSession → DeviceInfo → 1 次属性读 → 1 张缩略图 → 1MB 速率采样` → 输出一张「本机 + 本相机」的能力与耗时报告，可分享。
**这也是 §6.2 矩阵的数据来源**（矩阵 → 指导用户；自检 → 产出矩阵条目）。

### 7.4 性能内建度量

在 App 内统计并按操作打点（`STA thumbnail perf count=500 p50=` 的做法）：缩略图 p50/p95、单张 NEF 下载速率、监看实际帧率与端到端延迟、连接各阶段耗时。本地滚动统计 + 自检报告可见，不需要服务端也能驱动优化决策。

---

## 八、连接层架构改造（支撑上面所有条目）

### 8.1 单一事务层 `PtpWire`（消除两套实现）

```kotlin
interface PtpWire {
  val kind: Kind           // USB_BULK | PTP_IP
  suspend fun open()
  suspend fun transact(op: Int, params: List<Long>, timeoutMs: Long, selfHeal: Boolean): PtpResponse
  suspend fun transactStream(op: Int, params: List<Long>, sink: OutputStream,
                             onProgress: (Long, Long) -> Unit, onEvent: (PtpEvent) -> Unit): PtpResponse
  suspend fun close(bestEffortMs: Long = 2000)
}
class PtpProtocolException(val stage: Stage, code: Int, msg: String) : Exception
```
- `BulkPtpWire`（12 字节容器 + 容器重组，现 `UsbPtpManager` 的逻辑搬进来）与 `PtpIpWire`（`length+ptype` 封装，现 `PtpSessionManager` 的逻辑搬进来）**共享** `PtpTransaction`/对象模型/事件解析。
- 数据阶段循环内回调 `onEvent`（模式）→ 下载期不再丢事件。
- `transact` 的 `selfHeal` 语义：业务调用超时→关链路自愈；心跳/探测超时→只上报（防止心跳砍掉大文件）。
- 保留我们已有且正确的东西：`generation` token、`activeJob` 槽、容器重组、`TcpNoDelay`+4MB 接收缓冲。
- **收益**：USB 与 WiFi 的行为差异被收进 2 个类里；机型 quirks 只需在 `PtpWire` 之下生效一次；测试可用 `FakeWire` 覆盖全部重试/超时/并发路径（补 §4.6 的测试空洞）。

### 8.2 机身能力与 quirks 表（消灭经验常量的滥用）

`device/CameraProfile.kt`：`{modelRegex(抄 (?:NIKON\s*)?Z\s*(50|30|[5-9])\s*(III|II|3|2)?), productIds[], defaultIpCandidates[], ssidRegex, wmaWritable, hostRegSettleMs, partialObject64, lvVariant(EX|LEGACY), lvQualitySteps, thumbStrategy, chunkSize, quirks[FLAGS]}`
- 内置种子来自 dump + 我们的真机标定；运行期「实测成功的值」写回本地覆盖表（学 `eventSettle=650ms`、`AP network readiness wait=1200ms` 这类可学习参数）。
- `PtpProtocol.kt` 中来源为「同类产品反汇编」的常量（`0x952B/0x935A/0x9435/0x9431`）**迁入此表并标注 confidence + 来源**，不允许再散落硬编码。

### 8.3 会话对象与状态收敛

`ConnectionSession { channel, wire, cameraInfo, profile, networkHandle?, localIp?, guid, openedAt, health }` —— 取代目前散落在 `ConnectionManager` 的 `realCameraIp`/`apConnected`/`sta_host_registered`/`isConnected()` 等标志位；USB 并入同一状态机（§5.3 T-U1）。

### 8.4 明确不做

- ❌ 不引入 ``/`libusb` native（许可证 + fd 模型 + 16KB page size 三重成本，且我们纯 JVM 已解决容器问题）；
- ❌ 不做 OkHttp/Retrofit 化（我们根本不说 HTTP，裸 socket 是对的）；
- ❌ 不尝试 `TetheringManager.startTethering` / `WRITE_ACCESS_POINTS`（需 `TETHER_PRIVILEGED`，第三方不可得，只做观测）；
- ❌ 不破解 LsSec / 不还原 机身官方应用 加密；
- ❌ 不上线任何「未经真机验证」的厂商操作码（一律先 V-* 验证）。

---

> **本节内容已按仓库规则移除**
>
> 原先记录了外部产品的横向比较、调研与静态分析取证。
> 依据 [`docs/CONTRIBUTING.md`](CONTRIBUTING.md)「外部产品信息不入库」一条，
> 这类内容不得提交到远端仓库，故整节移除，仅保留本项目自身的技术结论。
>
> 原始分析材料保存在本地工作副本中，不随仓库分发。

## 十、落地路线（按可独立发版切分）

### 10.1 v2.2.0 —— 「AP 与热点打通 + 可诊断」（P0 全量，G1–G12）
1. `PreflightGate` + 统一 reason code + 漏斗埋点（先埋点后改逻辑，让基线可测）；
2. AP：`ApGatewayResolver`（网关学习+稳定化+1200ms 就绪）+ 无 BLE 主干（SSID 判定→直接走学习）+ specifier 修正（三参超时/BSSID/最小间隔）+ `establishPtpSession` 解耦；
3. STA：`TetheringEventCallback` 网络句柄 + TCP-only 快筛两段化 + 进热点正向确认 + 隔离检测分支；
4. 传输：下载期心跳豁免 + 数据期抽 event + event 通道 `settle` 与 ECONNREFUSED 重试；
5. USB：detach 宽限窗 + OTG 检测引导 + `serialNumber` 兜底 + offset 改 Long；
6. 全局尝试预算（≤3 次/60s）+「忽略此网络」恢复指引；
7. 逐 ROM 引导第一版（asset JSON，先覆盖小米/HyperOS、华为、三星、OV、vivo）。
- 退出条件见第十一节 AC-1～AC-7。**改动集中在连接层，不动 UI 主干与已跑通的相册/参数链路（第十三节）。**

### 10.2 v2.2.1 —— 「相机端能力治理」（P1 前半）
机身 `CameraProfile`（含把 `0x952B/0x935A/0x9435/0x9431` 收表 + 置信标注）→ 主机注册回验 → 自检面板 → p50/p95 度量 → 监看质量阶梯与分辨率档位 → **BLE 时间同步（G24，低成本高感知，可提前到 v2.2.0）**。

### 10.3 v2.3.0 —— 「免手工 + 吞吐」
WMA 写凭据（若 V-3 通过）或 USB 侧完成注册（回退方案）；双通道/数据通道分离（PTP/IP 第三通道）+ PartialObject64 自适应分片；会话级通道择优 `ConnectionOrchestrator`。

### 10.4 v2.3.x/v2.4 —— 「规模化与合规」
`PtpWire` 统一事务层重构 + `FakeWire` 回归测试矩阵；兼容矩阵热更新 + opt-in 聚合上报（隐私开关默认关）；**targetSdk 36 合规**：`compileSdk/targetSdk 36`、若将来引入 native 需 NDK r27 + `-Wl,-z,max-page-size=16384` + CI 对齐检查、Android 16 USB 保护（锁屏禁数据）文案、`BOOT_COMPLETED→connectedDevice` FGS 校验、并发 FGS 配额。

### 10.5 测试与真机矩阵（每个里程碑都要跑）
- **纯逻辑单测**（新增）：`ApGatewayResolver`、`StaRouteProbe`、`SpecifierRetryPolicy`、`ReasonCodeMapping`、`CameraProfile` 匹配；
- **假传输集成测**：超时→自愈 vs 心跳不自愈、detach 宽限窗、预算耗尽、并发 connect 取消；
- **真机矩阵最小集**：小米 15 Pro/HyperOS（已有 Z8）+ 一台三星 One UI + 一台原生/Pixel + 一台 2.4GHz-only 老机型；机身至少覆盖 Z8、Z6II、Z30/Z50II（菜单与 WMA 差异最大）。

---

## 十一、验收标准与量化指标

### 11.1 SLO（v2.2.0 出口）

| 指标 | 现状 | 目标 |
|---|---|---|
| AP 一次连接成功率（Z8，近 12 月失败样本归零后） | 未知（无埋点） | ≥95%，小米 HyperOS 单独 ≥90% |
| 无 BLE（LsSec 机身）AP 可用性 | **0%** | ≥90%（靠网关学习） |
| STA 发现到首命令 P50 / P95 | ~18s / >30s | **≤6s / ≤12s** |
| 热点模式一次成功率 | 未知 | ≥85% |
| USB 首命令 P50/P95 | 未知 | ≤800ms / ≤2s |
| 监看首帧 P50/P95（5GHz） | 未知 | ≤1.5s / ≤4s |
| 重连 P50/P95 | 未知 | ≤2s / ≤8s |
| 1 小时连续传输掉线率 | 未知 | ≤1%/会话（**下载期心跳误杀 = 0**） |
| >2GB 单文件下载成功率 | 0% | ≥95%（不支持时明确降级提示） |
| 授权弹窗放弃率 | 未知 | ≤3% |
| 崩溃自由会话率 | 未知 | ≥99.9% |

> 「未知」列必须先变成已知 —— 埋点是 v2.2.0 的第一项交付，不是最后。

### 11.2 验收清单（逐条可执行）

- **AC-1 网关学习**：Z8 开热点、手机系统层手动连接（不经过 App）→ App 应不问 BLE、不输 IP，直接连上；把相机改为非 `192.168.1.1` 网段（不同机身/固件默认段）仍应连上。
- **AC-2 无 BLE 主干**：关掉手机蓝牙，AP 页仍能完成连接。
- **AC-3 二次连接竞态**：连接→断开→立刻再扫，必须成功（现在会秒回 `onUnavailable`）；连续失败 3 次后 App **停手**并给「忽略此网络」指引，而不是继续静默重试。
- **AC-4 热点通道**：手机开热点→相机加入→App 报告「相机已加入（IP=…）」而非超时；热点未开/相机没进来时文案能区分（4 档）。
- **AC-5 下载不被心跳杀**：下载一段 >60s 的 MOV，全程无 `link_error`；期间新拍一张照片，相册收到增量事件。
- **AC-6 USB 韧性**：传输中拔插一次（<3s）→ 自动恢复且不报错；HyperOS 关 OTG 后 → 明确提示去开启而不是「无相机」；`serialNumber` 不可读机型不崩。
- **AC-7 漏斗可见**：设置页能看到本次连接的阶段/耗时/原因码；导出日志不含密码与照片名。
- **AC-8 回归**：v2.0.1/v2.1.x 已跑通行为（相册排序、F1 标记、缩略图两段式、AF 驱动、参数读写、百度网盘更新通道）零回归。

---

## 十二、待实机验证清单（无法从静态分析结论化的部分）

> 本次会话 **无 ADB 设备**（`adb devices` 空），因此以下必须在接入 Z8 + 小米 15 Pro 后用 Mobile Use / 手动方式补齐。同类产品运行时对比同样待做（、同类实现各跑 20 次三通道连接，记录 §11.1 指标）。

| # | 待验证 | 方法 | 影响哪个决策 |
|---|---|---|---|
| V-1 | `InitCommandReq` 精确字节布局（是否带尾部 `u32=65536`）、`0x952B/0x935A/0x9435/0x9431` 真实语义 | 机身官方应用（另一台手机做中间人/或 root `tcpdump`）+ 与 机身官方应用/同机对照 | G13 机身表、STA 主机注册 |
| V-2 | 客户端 friendly name 是否影响相机「免弹确认」记忆 | 改 `clientName` 为官方风格名，观察配对提示 | T-A6 |
| V-3 | 相机 WMA 设置是否可被第三方写入（SSID/密码/信道/认证）+ 各机身沉降时间 | 依次试读/试写厂商操作，观察相机菜单变化 | T-S5（免手工）、G17 |
| V-4 | LsSec 加密 `0x2004` 的机身分布（Z8 是否已加密） | Z8/Z6II/Z30 各读一次 | G2、是否必须无 BLE 主干 |
| V-5 | 相机 USB 模式能否软件切换（第三方聚合 有 `changeToPtpMtpMode`） | 描述符枚举 + 尝试厂商操作 | T-U2 |
| V-6 | 各 ROM 深链是否仍然有效（HyperOS 2.x / ColorOS 15 / OriginOS 5） | 逐条点击 + 读回状态 | 6.2 矩阵 `lastVerified` |
| V-7 | DBS（双频并发）与 5GHz-only 相机 AP 的真实失败面 | 多机型跑同一 AP 加入 | T-A4 |
| V-8 | 事件通道 `settle` 与 ECONNREFUSED 重试在 Nikon 机身上的必要性 | 打点重做 20 次连接 | AP 参数表 |
| V-9 | >2GB 单文件（4K 长 MOV）在真机上的可下载性 | 实拍长片 | G10 |

---

## 十三、明确不动的清单（已跑通，防倒退）

- **BLE 尼康配对协议实现**：`NikonAuthPairing`(Blowfish/ECB) + `0x2004` 布局 + 「stage 4 即停（Z50II/Z8 真机实测）」结论、`NikonRemotePairing` 0x2007 回落 —— 主干保留，只加失败原因码。
- **并发治理已有成果**：`WifiDirectConnector` 的 `generation` token、`activeJob` 单飞槽、`Mutex`（RC-1/2/7）。
- **子网匹配选网络与 `defaultRouteFallback`**（`fix-sta-hotspot-subnet` 的成果，`StaNetworkRequester.covers()`、`WifiNetworkMonitor.awaitWifiNetworkFor`）。
- **bulk-IN 容器重组**（RC1）与 `MAX_PAYLOAD_SIZE` 守卫、直写 `OutputStream` 的下载路径。
- **PTP 常量与命令集**（0x90xx/0x92xx 已验证部分）、`OP_NIKON_DEVICE_READY` 判活、Ping 以「任何入向活动算活」的规则、`EVENT_READ_TIMEOUT_MS=45000` 半开唤醒。
- **监看与 AF 链路**：`0x500E=0x8012` / `0xD1AC=3` 前置、4000×3000 AF 坐标域、JPEG SOI 定位、`updateFrameSize` 实际像素校正。
- **相册/传输/参数链路**：v2.0.2 §五 列出的不动清单（队列去重、`runTask` 失败只重试一次、缩略图两段式 + 两级缓存、排序管线、`prepareFileToSave` 只对 JPEG 重编码）。
- **三把锁引用计数 + 15 分钟 `ConnectionHealthWorker` 校平**。
- **无第三方网络库、无云端依赖的架构**（隐私与包体优势，除非 §10.4 合规需要否则不引入）。

---

## 十四、风险与待确认事项

### 14.1 风险

| 风险 | 等级 | 缓解 |
|---|---|---|
| 尼康固件变更（LsSec 覆盖更多机身、STA 门控收紧） | 高 | 无 BLE 主干（G2）+ 兼容表热更新（G21）；USB 作为不受影响的第三条腿 |
| 从同类产品反汇编得到的常量在新固件失效 | 中 | V-1 定标 + 机身表 + 置信标注 + 自检快速定位 |
| `PtpWire` 重构引入回归 | 中 | 放到 v2.4，且先有 `FakeWire` 测试矩阵再动 |
| 逐 ROM 深链被新版 Settings 收归（Android 15+ Restricted settings） | 中 | 矩阵带 `lastVerified`，失败降级为纯文案 + 手动路径截图 |
| 埋点/日志的隐私与商店合规 | 中 | 默认仅本地、导出脱敏并声明；上报改 opt-in 且后置到 v2.4 |
| 一次性铺开 23 项差距导致版本失稳 | 高 | 严格按 §10 切分，v2.2.0 只做 G1–G12，每项独立可回滚 |
| 无测试设备窗口（本机当前无 ADB 设备） | 高 | 接入设备后先跑 §10.5 基线，再动 §5 逻辑 |

### 14.2 待你确认（决定后我按此实施）

1. **v2.2.0 范围**：是否按 G1–G12（P0 全量，含 USB 与埋点）一次性做？还是先「AP 两招（G1+G2）+ 埋点」最小可发版？
2. **优先级**：小米 15 Pro + Z8 是**唯一复现环境**吗？能否再凑一台三星/一台 Pixel 级原生机做矩阵基线？
3. **STA 免手工（T-S5）**：愿意承担「实测 WMA 写操作」的验证成本吗？不做的话退化为「USB 会话内完成主机注册」的折中方案。
4. **监看质量阶梯 / PartialObject64**：是否列入 v2.2.0（性能向，但会碰 `LiveViewManager`/`TransferManager` 主干）？
5. **埋点**：纯本地（学 隐私声明作为卖点），还是预留 opt-in 上报（学 `capability-reports`）？
6. **版本号**：v2.2.0（versionCode 19）？
7. 是否要我把 §九 的 **P2 功能差距**（相机内评级、镜头信息、moov 边下边播、多相机 UI）另开一份功能 PRD？
8. **要不要评估「买 SDK」这条路**（§2.1 结论二）：第三方证明存在可采购的多品牌原生栈（第三方聚合：六品牌 + control/data/event 三通道 + SSDP/WebSocket/SSH + 自适应传输）。接它可省掉 §八 的大部分重构，代价是许可费、包体、黑盒不可控、与「无云端依赖 + 3MB 包体」的卖点冲突。需要我去摸商务口径吗？
9. **多品牌是否在视野内**？同类实现/第三方都已多品牌。按 §2.4，扩张的真实成本是「实测 + 兼容矩阵运营」而非接 SDK；若 12 个月内不扩品牌，则 §6.2 的矩阵就按「手机 ROM × Nikon 机身」两维建，范围能小一半。
10. **真机窗口**：什么时候能接上 Z8 + 小米 15 Pro？（§12 的 V-1/V-3/V-4 不定标，G13/G18 两项就只能停在纸面。）

---

## 十五、v2.2.0 实施状态（验证包 `dist/N-Link-v2.2.0-release-连接P0.apk`）

> 分支 `research-connection-prd-v2.2`，提交：`e29ac85`(PRD) → `96eb34e`(代码) → `a11d685`(版本号 19)。
> 构建 `:app:assembleRelease` 通过，`:app:testReleaseUnitTest` 通过，release 签名校验为 N-Link 证书。

| 差距项 | 状态 | 落点 |
|---|---|---|
| G1 AP 网关学习 | ✅ 已实现 | 新增 `device/wifi_ap/ApGatewayResolver.kt`（1200ms 就绪 + 两次采样稳定化 + 排除自身/169.254/广播 + 出厂地址 TCP 试探），接入 `resolvePtpTarget`（新来源 `ap_gateway`）与 `connectToWifiCamera` 的「先学地址再配对」路径 |
| G2 AP 摆脱 BLE 前提 | ✅ 已实现 | `ApGatewayResolver.isOnCameraAp()`（SSID 判相机热点）+ `WifiManager.alreadyOnThisAp()` 收养已连网络；`reconnect()` 不再因内存凭证丢失静默失败 |
| G3 TCP-only 快筛 | ✅ 已实现 | `PtpIpProbe.tcpConnectOnly()` + `WifiScanner` 网段扫/ARP 扫两段式（`STA_TCP_ONLY`） |
| G5 specifier 加固 | ✅ 部分 | 改用带超时的三参 `requestNetwork`、去掉协程超时包裹、释放后 ≥1500ms 才重请求。**未做**：BSSID 精确请求与 WPA3 显式分支（需先确定 `0x2004` 里 BSSID/安全字段的真实偏移，属 V-4/V-1 范畴） |
| G7 传输期心跳保护 | ✅ 已实现 | `PtpSessionManager.bulkDepth`（`getObject`/`getPartialObject` 计数），在途时不累计 `missedBeats`、不触发 30s idle 判死；写失败仍判死 |
| G8 USB detach 宽限窗 | ✅ 已实现 | `enterDetachGraceWindow()` 3s 轮询，命中即静默重连 |
| G9 OTG/枚举分类文案 | ✅ 已实现 | 「总线全空」vs「有设备无尼康」分流，小米系给 OTG 路径与 10 分钟自动关闭提示 |
| G10 >2GB 续传 | ✅ 安全化 | 偏移超出 32 位范围时退出续传改走整文件下载（修掉 `toInt()` 回绕写坏文件）。**真正的 `GetPartialObject64` 需按机身定标 → V-9** |
| 回退闸门 | ✅ 已实现 | `ConnFlags` 总闸 + 设置页「v2.2 连接改进」开关（关闭即回 v2.1.1 行为）+ 单项 key |
| G4 热点 `TetheringEventCallback` | ⛔ 本包受阻 | `android.net.TetheringManager` 不在 compileSdk 34/35 的**公开** stub 中（`android-36/android.jar` 才收录），AGP 8.7.3 不支持 compileSdk 36；不做隐藏 API 反射 hack。随 §10.4 targetSdk 36 一并做 |
| G6 全局尝试预算 | ✅ 已实现（二轮提交 `c31d695`） | `ConnectionStateMachine.RETRY_BUDGET=8`，超限停手并提示「忽略此网络后重连」；开关 `conn22_attempt_budget` |
| G11 PreflightGate | ✅ 已实现 | `device/connect/PreflightGate.kt`：WLAN 开关 / 附近的设备 / 定位权限与总开关 / 电池豁免 / VPN / 「避开不良网络」/ OTG（小米·vivo·OPPO），含阻塞级别 + 直达设置入口；已在相机热点上时跳过硬阻断 |
| G12 统一原因码与漏斗 | ✅ 已实现 | `ConnFunnel`：12 阶段 + 40 个原因码收口原三套文案；内存保留 12 次尝试；设置页「连接诊断」时间线可复制（AC-7）；状态行追加「停在：阶段 · 原因码」 |
| G11 之 ROM/机身 asset JSON 与热更新 | ✅ ROM 侧已做（§十七） | `assets/compat/rom_rules.json` + `CompatRules`；`dontkillmyapp` 快照已折成 `killRating`。机身侧 `camera_rules.json` 与热更新仍推迟（原因见 §17.3） |

**真机数据点（v2.2.0）**：Z50II + vivo —— AP 模式稳定（AC-1/AC-2 通过，且 v2.1.1 在该组合下本就能连，说明 AP 主干改造未引入回归）。USB 监看周期性掉线为 **v2.1.1 既有缺陷**（`LiveViewManager` 未被本次改动触及），根因与修复见 §5.3 补充与提交 `dd54385`：USB 单帧失败要 2.5s，旧逻辑不分「机身没新帧」与「链路断了」，5 次（≈13s）即自停监看；弱光慢快门 / AF 搜索 / 写卡期间的正常静默必然触发。小米 15 Pro + Z8 组合仍未测。

**验证包对应的验收项**：AC-1（换网段仍能连上）、AC-2（关蓝牙仍可连 AP）、AC-3（断开后马上再连）、AC-5（>60s 大文件不被心跳掐断）、AC-6（拔插一次自动恢复 + OTG 关闭时的提示文案）。

---

## 十六、v21 验证包修复：AP 前两次连不上 + 下载变慢/切页中断

> 数据点来自用户导出的真机日志 `n-link_logs_1789823584116.txt`（Z50II + vivo，2026-09-19 21:08–21:13）。

### 16.1 AP「前两次无法连接、第三次才成功」——**是软件问题**

日志时间线（同一次点击序列）：

```
21:10:34.138  ap_gateway host=192.168.1.1 stable=true probe=OK      ← 我们的探测握手成功，机身唯一的 PTP/IP 客户端槽被自己占住
21:10:34.146  sta_try gen=2 mode=pair host=192.168.1.1              ← 8ms 后发起真实配对
21:10:37.372  sta_preconnect gen=2 attempt=1 reachable=false        ← 相机已经不接受第二个客户端
21:10:40.058  conn_result stage=INTENT ms=7871 reason=ok            ← 第 1 次失败（漏斗把「被打断」记成了 ok）
21:10:41.973  ap_gateway host=fe80::1 stable=true probe=rejected    ← 第 2 轮学到 IPv6 链路本地网关，把可用的 IPv4 网关挤掉
21:11:00.275  sta_fail gen=3 reason=camera_unreachable attempt=3    ← 第 2 次失败
21:11:15.029  sta_preconnect gen=5 attempt=1 reachable=true         ← 距首次探测约 35s，机身自己收掉半开会话
21:11:15.043  connect phase=init ok=true session=3                  ← 第 3 次点击成功，13ms 建链
```

判定为软件的三条依据：① `probe=OK` 说明相机热点与 15740 监听**在第一次点击时就完全正常**，不是硬件/网络没起来；② 失败期间连裸 TCP 都被拒（`probe=rejected`），是「槽被占满」而非「地址错」的特征；③ 同一地址、同一网络、用户没有做任何其它操作，35s 后一次成功——与机身回收半开会话的时间尺度吻合。

| 根因 | 修改点 |
|---|---|
| **自抢会话槽**：`ApGatewayResolver.resolve()` 与 `WifiDirectConnector` 的连接前探活、失败后归类都用 `probeDetailed`（发 `InitCommand`），而代码里 `tcpConnectOnly` 的注释早就写明「尼康机身同时只允许一个 PTP/IP 客户端，握手会挤占配对槽」。探测成功后 8ms 就去连，等于自己把门关上 | AP 路径可达性判据全部降到 **TCP 层**（`PtpIpProbe.tcpConnectOnly`），握手只由真实连接发一次；新增 `PROBE_SETTLE_MS=400` 让机身先回收探测用的 TCP；`resolve()` 出厂地址兜底循环里的第二次握手探测一并去掉 |
| **IPv6 网关误学**：`isCameraGateway` 只排除 `169.254.`/`.0`/`.255`/`127.`，`fe80::1` 全部通过 → 两次采样把可用 IPv4 换成用不了的 link-local IPv6 | `isCameraGateway` 改为**只收点分十进制 IPv4**；新增 `ApGatewayResolverTest` 回归（含 `fe80::1`/`::1`/`fd00::1`/畸形 IPv4） |
| **点击即另起一轮**：每次点连接都会 `generation++` 并 cancel 掉仍在指数退避中的旧循环、把退避从 1s 清零，30s 内 4 轮互相打断 | `connectToWifiCamera` 对**同一地址且循环仍在跑**的请求不再另起一轮，只提示「相机正在重试连接中」（自动重连路径若被跳过会死在 CONNECTING，故不采用冷却跳过方案） |
| 稳定网关 + 已在相机热点上时，还要拿 4 个出厂地址各试一遍（每次都是对同一台相机的新连接） | 新增 `source=gateway-unverified`：机身没起监听时交给真实连接自己的重试去等，不再叠加试探 |
| 漏斗把「被打断」写成 `reason=ok`，日志误导排查 | 新增原因码 `superseded`；`finish()` 收口时给出诚实结论 |

开关：`conn22_probe_tcp_only`（总闸同 v2.2）。关掉后 `resolve`/探活回到「探测即握手」，可二分定位。

**验收**：Z50II + vivo 上连点三次连接 → 期望第一次点击就成功；导出日志中不再出现 `probe=rejected`、`host=fe80::`、`sta_preconnect reachable=false` 与同毫秒的 `conn_attempt` 串；`connect phase=init ok=true` 应落在第一个 `conn_attempt` 之内。

### 16.2 下载：批量单张变慢 + 切页中断

两个现象**同源于一条设计缺口**：PTP 命令通道全局串行（`PtpSessionManager.commandMutex`），而缩略图与原图下载是同一条队伍里的平等请求；同时全屏页下载又绕过应用级队列、挂在页面生命周期上。

| 现象 | 根因 | 修改点 |
|---|---|---|
| 网格里批量下载单张明显变慢，全屏页单张却快 | ① 网格可见项的 0x100A/0x90C4 请求排在下一次 4MB 分块前面（并发窗口 3+2，一次插队一整队）；② **自激回路**：每下一张 → 本地重生成缩略图 → `thumbnailUpgrades` → collector 反过来 `requestThumbnail` → 向相机**重取一次 0x90C4** → bump 升级计数 → 网格**无 payload 全量重绑** → 被 LruCache 驱逐的格子再补发一批请求；③ 全尺寸 JPEG 解码+重编码卡在队列关键路径上 | `PtpSessionManager` 新增缩略图专用道 `thumbLane`（缩略图收敛为同时在途一张）；collector 改为只重绘、绝不再走 PTP，并记入 `localHdThumbs` 使已本地生成高清图的格子永不再要 0x90C4；`thumbUpgradeTick` 改走 `notifyThumbRangeChanged`（带 payload）；`regenerateThumbnailFromLocal` 挪到队列之外的后台任务；`isBulkTransferRunning()` 期间新缩略图请求延后入 `deferredThumbs`，传输停了整批补发（缓存命中不受影响，不会白格） |
| 全屏页点下载到一半切回缩略图 → 下载停住，必须重新进入这张再点一次才保存 | `PreviewActivity.download()` 在 `lifecycleScope` 里直接调 `downloadPhoto()`：退出页面即取消协程、传输就地被杀，半截临时文件无人续（v2.0.2 PRD §一 方案 A 一直未落地） | 预览页与 `TransferViewModel.downloadPhoto` 统一改走 `transferManager.enqueue()`（挂在 ConnectionService 的应用级 scope），按钮态/进度由既有的 `observeDownloadProgress(handle)` 单一数据源驱动；`Completed` 回填按 handle 定位页位，避免翻到别页时写错格子 |
| （顺带）压缩画质下载把 `_compressed.jpg` 永久留在缓存目录 | `prepareFileToSave` 产出的副本没人删 | 落进相册后立即回收 |

开关：`conn22_thumb_yield`（关掉即回到缩略图与下载抢通道的旧行为）。

**验收**：① 选 10 张批量下载，对比完成通知里的 MB/s 与 `download_start`/`download_done` 时间戳，单张耗时应与全屏页单张同量级（≤15% 差）；② 全屏页点下载后立刻退回网格、再按 Home 退后台，回来后该张应自行完成（网格角标 + 系统相册可见），无需再点；③ 批量结束后原本转圈的格子应补齐缩略图（验证 deferred 排空）；④ 下载过程中网格滚动不出现永久白格。

---

## 十七、v2.2.1 第一包：逐 ROM 兼容矩阵落为数据资产（§6.2 / 落地路线 10.1 第 7 条）

> 这一条本来就是 v2.2.0 的 P0 清单里的第 7 项（「逐 ROM 引导第一版，asset JSON，先覆盖小米/HyperOS、华为、三星、OV、vivo」），
> 当时只做到「代码内枚举 + 一条小米文案」就发了版，§十五 记为「仍推迟」。本包补完 ROM 侧。

### 17.1 改了什么

| 层 | 落点 | 内容 |
|---|---|---|
| 数据 | `assets/compat/rom_rules.json`（新增） | 6 条规则：小米/HyperOS、华为·荣耀、vivo·iQOO、OPPO·一加·realme、三星、原生兜底。每条 = 匹配条件 + 查杀强度 + 若干 `tricks`（自启动、电池策略、OTG、WLAN+/智能切换） |
| 读取 | `device/connect/CompatRules.kt`（新增） | 一生一次懒加载；`manufacturer` / `brand` / 系统属性三种旁证取**或**，按顺序取第一条命中（兜底条写在最后）；`hint()` 给文案、`intent()` 给**校验过**的系统入口、`staleSuffix()` 给失效提醒 |
| 消费 | `PreflightGate.kt` | ① 新增「自启动 / 后台白名单」提醒项（§6.1 表里那行 `AUTOSTART_BLOCKED`；它既然读不到状态也不该拦连接，原因码就叫 `autostart_reminder`）；② 电池优化 / 「避开不良网络」两条的文案改为**逐 ROM 路径**；③ OTG 提示优先读表、读不到退回 `RomDetector.otgHint()` |
| 闸门 | `ConnFlags.COMPAT_RULES`（`conn22_compat_rules`，默认开） | 关掉 = 表完全不参与，回到 v2.2.0 的代码内枚举文案 |
| 旁证 | `RomDetector` | `readSystemProperty` 从私有提到文件级 `internal`，表匹配与家族探测共用一个读取口 |

三条不变式（写进代码注释与测试，不靠自觉）：

1. **只影响文案与入口，不影响连接行为**。兼容表一旦能改重试路径，就变成了第二条隐形的连接状态机，而它是**可热改的数据**。
2. `component` 只是候选入口：一律 `resolveActivity()` 通过才给出去。系统 OTA 改名 / 区域变体 / 精简 ROM → 自动退回纯文案，就是 §6.2.3 要求的「失效能单独降级」。
3. 表读不到 / JSON 写坏 / 闸门关闭 → 返回空 → 用内置兜底。**一条规则写坏只丢那一条**，不让整张表陪着作废。

覆盖面刻意与 `RomDetector.otgHint()` 严格一致（小米/vivo/OPPO 三家）：预检里「USB 总线为空」算不算硬阻断，判断依据正是「这台机器有没有 OTG 独立开关」这条提示 —— 表里多写一家，就多一批本该继续重试却被拦下的用户。`CompatRulesTest` 里钉死了这个集合。

### 17.2 为什么 `killRating` 决定谁被提醒

自启动/后台白名单这一项**读不到状态**：系统没有公开 API 告诉你「自启动是否已允许」（§6.5 向导设想的「已生效 ✓ 读回」在 OEM 设置页上根本做不到）。所以它永远只是提醒（`ok=false` + `blocking=false`，面板上是 `!` 而不是 `✗`），并且只在 `dontkillmyapp` 口径查杀强度 ≥3 的 ROM 上出现 —— 原生/Pixel 用户不该多出这一行噪声。

### 17.3 本包**没做**的三件事（别当成已交付）

| 项 | 为什么不做 |
|---|---|
| `camera_rules.json`（机身侧矩阵） | §6.2.1 设想的字段（`wmaWritable` / `partialObject64` / `lvVariant` / `hostRegSettleMs`）现在**一个都没有消费方**：机身差异治理要先有 V-3/V-9 的实机标定数据，且各自的消费点（G17 主机注册回验、G10 续传 64 位、G16 监看质量阶梯）还没做。先建表就是往仓库里放一份没人读的硬编码，还会诱导「改了表就以为生效」。留到 §10.2 的 `CameraProfile` 一起做 |
| 兼容矩阵热更新（§6.2.4 / G21） | 需要：拉取源（raw URL）、缓存与版本协商、字段白名单与体积上限、失败静默。属 §10.4 的规模化条目，单独一包做 |
| 「去设置」按钮 | `Item.settingsIntent` 现在是真的可跳转深链了（表里有组件且本机解析成功时），但预检面板与错误提示目前只印文字、没有落点。要在设置页「连接诊断」与设备页状态行各加一个按钮 —— 那是 UI 主干，按 v2.3 的并行进度排 |

### 17.4 验收

1. 小米/华为/vivo 任一真机 → 设置 → 连接诊断：`!` 行里出现**本机品牌的具体路径**（不是通用文案），且各条互不串行。
2. 同一台机器 `adb shell run-as com.nikonlink.app sh -c '...'` 把 `conn22_compat_rules` 写 false → 面板回到 v2.2.0 的两行通用文案，`!` 自启动行消失。
3. 把表里某个 `component` 改成不存在的包名 → 该项不崩、不显示成可跳转，只当纯文案（`resolveActivity` 拦下）。
4. 非相机 USB 空总线场景（拔掉线再点连接）在小米/vivo/OPPO 上仍给 `OTG 连接` 指引；在 Pixel/三星上**不该**给（`otg` 覆盖面不变式）。
5. `./gradlew :app:testDebugUnitTest --tests "*CompatRulesTest"` 6 项绿（含「读的是真资产」这一条：表与测试必须一起改）。

---

## 附录：参考资料

> **附录内容已按仓库规则移除。**
>
> 原先包含外部产品的静态分析产物索引与相关链接。依据
> [`docs/CONTRIBUTING.md`](../../CONTRIBUTING.md)「外部产品信息不入库」一条，
> 这类索引与链接不得提交到远端仓库，故整节移除。
>
> 协议标准与平台官方文档属公开规范，可按需重新检索；原始索引保存在本地工作副本。
