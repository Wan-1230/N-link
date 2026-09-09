# WiFi STA 通道逆向分析

分析对象：官方 SnapBridge v2.13.3、ZDROP 1.0.190、ZRelay 3.0.46
工具：apktool 2.9.3 反编译 smali
基线：N-Link v1.2.1

---

## 一、结论摘要（先看这里）

**官方 SnapBridge 的 PTP/IP 实现是纯 Java 的，不在 native 库里。** 三个 native 库
（`libJuno.so` / `libNis.so` / `libLsSec-jni.so`）负责的是图像处理与安全，与连接链路无关。
连接层完整位于：

```
com/nikon/snapbridge/cmru/ptpclient/actions/connections/ConnectWifiAction   ← 连接编排
snapbridge/ptpclient/w7                                                     ← socket 封装
snapbridge/ptpclient/qa                                                     ← 心跳（probe）
com/nikon/snapbridge/cmru/backend/data/repositories/camera/connection/impl/c ← mDNS 发现
com/nikon/snapbridge/cmru/backend/utils/WifiEnabler                          ← WiFi 开关与 SSID 比较
```

对照后，N-Link 缺失的三项**已在本分支补齐**，另有两项确认我们的做法优于或等价于官方。

---

## 二、官方连接链路（逐参数还原）

### 2.1 socket 建立（`w7.a()`）

```java
Socket s = socketFactory.createSocket();   // 未连接的 socket
s.setTcpNoDelay(true);                     // 唯一设置的 socket 选项
s.connect(new InetSocketAddress(host, port), 30000);   // 0x7530 = 30s
```

| 参数 | 官方值 | 来源 |
|---|---|---|
| 默认端口 | 15740 (`0x3d7c`) | ConnectWifiAction 构造函数 |
| 连接超时 | **30 000 ms** (`0x7530`) | w7.a() |
| TCP_NODELAY | **true**（data + event 两条通道都设） | w7.a():635 |
| SO_TIMEOUT | **完全不设**（读无限阻塞） | 全局仅一处 setTcpNoDelay，无 setSoTimeout |
| 客户端 GUID | `00112233-4455-6677-8899-AABBCCDDEEFF` | ConnectWifiAction 默认值 |
| 客户端名 | `Android Device` | 同上 |

### 2.2 连接重试（关键，本次最大收获）

`ConnectWifiAction` 静态字段：`l = 5`（次数）、`m = 0x12c = 300`（间隔 ms）。
data 与 event 通道各自独立跑同一套循环：

```
尝试 connect
├─ 成功 → log "connecting data connection" → 返回成功
└─ 失败
   ├─ 是 SocketTimeoutException → 立即放弃，不重试
   └─ 其他 IOException → sleep(300ms) → retryCount++ → 重试（上限 5 次）
      └─ 超过 5 次 → log "failed to connect the data connection" → internalDisconnect
```

**为什么区分这两类异常**：相机 WiFi 关联成功后，机身 PTP 服务不是立刻 listen 15740 的，
此时 connect 拿到的是 ECONNREFUSED（立即返回，不是超时）——主机在、服务没起来，值得等。
而 SocketTimeoutException 意味着对端根本不可达（IP 错、跨网段、AP 客户端隔离），
重试只是白等 5 × 30s。

> N-Link 原实现完全没有重试，一次 ECONNREFUSED 就整条链路失败。
> 这是 STA 首连成功率低的直接原因。

### 2.3 心跳（`qa.smali`，继承自定时器基类 `p7`）

```java
// Timer.schedule(task, delay=9000, period=9000)
public void run() {
    Connection conn = controller.getConnection();
    if (conn == null) { log("uninitialized connection error"); stop(); return; }
    if (conn instanceof q7) {                    // q7 = PTP/IP(WiFi) 连接
        Result r = executor.execute(new x9(conn));  // 同步执行 probe，等结果
        switch (r) {
            case SUCCESS: break;
            case TIMEOUT: log("probe request timeout"); break;   // 仅日志
            default:      log("failed to send probe");  break;   // 仅日志
        }
    }
}
```

| 维度 | 官方 | N-Link（本分支） |
|---|---|---|
| 间隔 | 9 s | 8 s |
| 判据 | **同步执行 probe 并等结果** | 发 Ping 后看 event 通道 2.5s 内有无入站 |
| 失败处置 | **只记日志，绝不主动判死** | 连续 3 轮无应答 → 判死（约 30s） |

**官方为何不判死**：probe 无应答存在正常情况（机身忙于写卡/长曝光），主动断开会误杀
健康会话。官方把断连交给"业务命令失败"和"event 通道 TCP 层 RST/FIN"去发现。

**我们为何仍保留判死**：官方策略的代价是断连感知慢——要等下一次业务命令超时（10s+）
才暴露，监看画面早已冻结。我们的判据比"只认 Pong"宽松（相机主动发的任何 event 都计入
活动），误杀风险已被压低。这是一处**有意识的偏离**，若真机出现误杀应把 `MAX_MISSED_BEATS`
从 3 放宽到 5。

### 2.4 服务发现（`connection/impl/c.smali`）

- `NsdManager`（系统 "servicediscovery" 服务）
- 服务类型**只用 `_ptp._tcp.`**
- `MulticastLock("multicastLock")` + **`setReferenceCounted(true)`**（交系统管计数）

### 2.5 路由与网络选择

官方主 PTP 路径用的是 `SocketFactory.getDefault()`——即**依赖系统默认路由指向 WiFi**，
`setSocketFactory()` 没有任何外部调用者。只有 web 上传线程会显式挑网络：

```java
for (Network n : cm.getAllNetworks())
    if (cm.getNetworkInfo(n).getType() == TYPE_WIFI)
        socketFactory = n.getSocketFactory();
```

官方不用 `requestNetwork`，也不用 `bindProcessToNetwork`。这在"手机只连相机 WiFi"的
理想场景够用，但双卡/移动数据常开的机子上，无 Internet 的相机网会被系统降级，默认路由
回落到蜂窝——这正是 ZDROP 用 `requestNetwork` + 进程级绑网解决的问题（见第三节）。

---

## 三、ZDROP（`com.zdrop.z6ii`）补位的部分

官方没做、但 STA 实战必需的三件事，ZDROP 都做了：

| 机制 | ZDROP 实现 |
|---|---|
| 主动持网 | `ConnectivityManager.requestNetwork` 申请并**持有**无 Internet 的 WiFi 网络，防系统回收 |
| 路由锁定 | 按相机 IP 子网匹配挑网 + `bindProcessToNetwork` |
| 发现 | `NsdManager.discoverServices("_nikon._tcp." / "_ptp._tcp.")` 双类型 |
| 锁 | 会话级 `WifiLock(WIFI_MODE_FULL_HIGH_PERF)` |
| 命名 | mDNS `serviceName` 直接作为相机展示名；机身 BLE 名形如 `NIKON_` + 8 位 HEX |

ZRelay（`com.geekmanlab.zrelay`）核心在 `libzrelay_core.so`，Java 层只剩壳，
可读信息有限；BLE 广播名 `NIKON_REMOTE_`。

---

## 四、本分支据此落地的改动

### 已补齐（官方有、我们没有）

1. **连接重试**：`PtpSessionManager.connectSocketWithRetry()`——5 次 × 300ms，
   `SocketTimeoutException` 不重试，每次重试重建 Socket（`Socket` 是一次性的，
   connect 失败后不能复用同一实例）。
2. **连接超时 10s → 30s**：对齐官方，避免相机刚上电时过早放弃。
3. **event 通道补 `tcpNoDelay = true`**：原来只有 command 通道设了，
   event 包被 Nagle 压在缓冲区白等几十毫秒。

### 确认我们更优 / 等价，不改

| 项 | 官方 | N-Link | 判断 |
|---|---|---|---|
| 网络选择 | 依赖默认路由 | `requestNetwork` 主动持网 + 子网匹配 + 会话级绑网 | 我们更强（双卡场景官方会掉到蜂窝） |
| MulticastLock | `setReferenceCounted(true)` 系统管 | `false` + 自管 `lockRefs` 计数 | 等价，自管更可控 |
| NSD 服务类型 | 仅 `_ptp._tcp.` | `_ptp._tcp.` + `_nikon._tcp.` | 我们更全 |
| event 读超时 | 不设（无限阻塞） | 45s | 我们能主动醒来，官方半开连接下读线程永久挂死 |

---

## 五、真机验证要点

1. **首连成功率**：相机刚开 WiFi 立刻点连接（原来大概率失败，现在应被 5 次重试兜住）。
   日志关键行：`retry connect [n/5]` → `connect ok after n retries`。
2. **超时不重试**：故意输错 IP，应在 30s 内一次失败退出，不能卡 2.5 分钟。
3. **双卡/移动数据常开**：连上后持续监看 5 分钟，验证 `requestNetwork` 持网是否防住了
   系统把默认路由切回蜂窝。
