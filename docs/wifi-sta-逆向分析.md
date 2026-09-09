# 尼康 WiFi STA 通道逆向分析（ZDROP / ZRelay / SnapBridge）

> 目的：N-Link 的 WiFi STA 通道一直未跑通，本文通过解包三个可正常工作的
> 安装包，还原其 STA 链路的通信机制，作为重构依据。
> 工具：apktool 2.9.3 反编译 + Manifest/资源/字符串常量交叉比对。
> 证据等级：**代码确凿** = 反编译代码/常量直接可见；**推断** = 由调用关系推测。

## 1. 样本画像

| 样本 | 包名 | 定位 | 核心实现 |
|---|---|---|---|
| SnapBridge v2.13.3 | `com.nikon.snapbridge.cmru` | **官方** | BLE 优先 + WiFi 辅助，大量原生库（libJuno/libNis） |
| ZDROP 1.0.190 | `com.zdrop.z6ii` | 第三方（Z6II 专用） | **mDNS/NSD 发现 + requestNetwork + PTP/IP 保活**，纯 Kotlin/Compose |
| ZRelay 3.0.46 | `com.geekmanlab.zrelay` | 第三方（中继） | `com.kw.ztransfer` + 原生 `libzrelay_core.so`，BLE 扫描 + PTP/IP EventPump |

权限对比（与 STA 相关的关键项）：

| 权限 | SnapBridge | ZDROP | ZRelay | N-Link |
|---|---|---|---|---|
| ACCESS_FINE_LOCATION | ✅ | ✅ | ✅ | ✅ |
| CHANGE_WIFI_STATE | ✅ | ❌ | ✅ | ✅ |
| CHANGE_WIFI_MULTICAST_STATE | ✅ | ✅ | ❌ | ✅ |
| CHANGE_NETWORK_STATE | ✅ | ✅ | ❌ | ✅ |
| WAKE_LOCK | ✅ | ✅ | ✅ | ✅ |
| NEARBY_WIFI_DEVICES | ❌ | ✅ | ❌ | ❌ |

> ZDROP 不申请 `CHANGE_WIFI_STATE`：**它从不主动切 WiFi**，只做"发现 + 连接"，
> 说明 STA 模式下"让手机连上相机所在网络"这一步可以由用户在系统设置里完成，
> 也可以由 BLE 触发，不必由 App 强控。

## 2. 发现机制：mDNS/NSD 是 STA 下唯一可靠手段

**代码确凿**：ZDROP `t1/U1.smali` 注册了两组 NSD 发现：

```
const-string v1, "_nikon._tcp."
const-string v2, "_ptp._tcp."
...
invoke-virtual {v1, v9, v7, v10}, NsdManager->discoverServices(String, int, DiscoveryListener)
```
（第 2 个参数为 `NsdManager.PROTOCOL_DNS_SD`），配套 `S1`(DiscoveryListener)、
`T1`(ResolveListener)、`R1`(结果模型)，UI 文案为
`"正在通过 Wi-Fi 搜索 Nikon Z 系列相机…"`。

解析结果里端口取 `0x3d7c` = **15740**（PTP/IP 标准端口），与
`t1/p3.1.smali` 里的 `":15740"`、`t1/q3.1.smali` 的 `":15740 bindNetwork="` 一致。

**关键结论**：STA 模式（相机与手机同连路由器）下，网段扫描跨子网不可行、
ARP 表只能看到同网段设备，唯一稳定的发现手段就是 mDNS/NSD。
N-Link 原有 `WifiScanner` 已覆盖 `_ptp._tcp.` / `_nikon._tcp.`（裸 mDNS + 系统 NSD 双路），
方向正确；本次补强的是**查询重发**与**服务名利用**。

## 3. 路由选择：requestNetwork + 进程级绑网 + 子网匹配

**代码确凿**：ZDROP `t1/s.1.smali`、`t1/q.1.smali`、`t1/k.1.smali`

```
ConnectivityManager->requestNetwork(NetworkRequest, NetworkCallback)   // s.1:279
ConnectivityManager->bindProcessToNetwork(Network)                     // s.1:551 / q.1:296
NetworkCallback.onLinkPropertiesChanged -> LinkProperties.getLinkAddresses  // q.1:50/111
```

即三步：
1. **主动申请**一张 WiFi 网络并持有（`requestNetwork`），而不是被动遍历 `allNetworks`；
2. 在回调里用 **LinkProperties 的链路地址与相机 IP 做同网段匹配**选网；
3. 选中后 **进程级绑定** `bindProcessToNetwork`，一次性解决所有 socket 的路由问题。

日志常量佐证（`t1/q3.1.smali`）：
`"socketFactory=bound"` / `"socketFactory=default-route-fallback"`。

**为什么必须这么做**：相机 AP / 相机所在 STA 网络通常**没有 Internet**，
Android 不会把它选为默认网络，双卡机更是直接把默认路由交给蜂窝；
不显式申请，这张网随时可能被系统回收（表现为连上后几十秒掉线）。

## 4. 保活：PTP/IP 层心跳 + transport 异常分类

**代码确凿**：ZDROP `t1/v.1.smali`（heartbeat 失败处理）与 `t1/n2.1.smali`（socket 层）

```
const-string v11, "heartbeat failure transport="   // v.1:483
const-string v3,  "PTP/IP 保活失败："                // v.1:525
Socket->setTcpNoDelay(true)                        // n2.1:17158
Socket->setKeepAlive(true)                         // n2.1:17160
Socket->setSoTimeout(...)                          // n2.1:17164
```

心跳失败时**先给异常分类**（`v.1:405-433`）：沿 cause 链查找
`SocketTimeoutException` / `EOFException` / `SocketException`，命中则判定为
**transport 层故障**（区别于协议层错误），并维护连续失败计数 `m`
（`U2.m`），超过阈值才真正断开重建。

**这套分类正是 N-Link 缺失的**：我们的旧心跳只检查 `write()` 是否成功，
而 TCP 半开连接下 `write` 永远成功 —— 详见 `fix-remote-monitor-disconnect` 分支。

## 5. 锁与前台服务

- ZDROP `CameraSessionService`：会话级持有 `WifiLock`（`WIFI_MODE_FULL_HIGH_PERF`）；
- ZRelay `NikonTransferService` / `GalleryDownloadService`：同样持有 WifiLock，
  锁 tag 为 `:NikonTransferWifiLock` / `:NikonTransferWakeLock`；
- 三方全部声明 `FOREGROUND_SERVICE_CONNECTED_DEVICE`，把传输放在前台服务里。

**代码确凿**：三方一致选择 `WIFI_MODE_FULL_HIGH_PERF`，且是**会话级**而非连接级持锁。

## 6. 设备识别

- SSID/主机名：`NIKON_` + 8 位 HEX（正则 `(?i)^NIKON_([0-9A-F]{8})`，ZDROP `t1/K1:7300`）；
- 服务名前缀：`nikon-z-`、`nikon-z6ii-`（ZDROP `t1/u4`）；
- ZRelay 侧通过 BLE 广播名 `NIKON_REMOTE_` 识别。

## 7. 落实到 N-Link 的改动（本分支）

| 三方做法 | N-Link 原状 | 本次改动 |
|---|---|---|
| `requestNetwork` 主动申请并持有网络 | 仅被动遍历 `allNetworks` | 新增 `StaNetworkRequester` |
| 按相机 IP 子网匹配选网 | 已有（部分） | 保留并在 Requester 内复用 |
| 进程级绑网 | 有，但**连接成功即解绑** | 改为会话级保持（`sessionBound`） |
| 会话级 WifiLock | 连接尝试级 | 引用计数 + `retainLocks()` |
| mDNS 查询 | 只发一次 | 2s 周期重发 |
| NSD 服务名当相机名 | 一律显示"尼康相机" | 取 `serviceName` |
| 网络丢失事件 | 无 | `networkLost` SharedFlow |

## 8. 未采用的做法与原因

- **ZRelay 的原生 `libzrelay_core.so`**：核心逻辑在 native 层，逆向成本高且不可移植，
  其公开行为（BLE 触发 + PTP/IP）与 ZDROP 结论一致，不额外引入；
- **SnapBridge 的 BLE 优先策略**：官方走 BLE 下发 WiFi 凭证再切网，
  N-Link 已有 `BleManager` 的同类实现，本次不重复改造。
