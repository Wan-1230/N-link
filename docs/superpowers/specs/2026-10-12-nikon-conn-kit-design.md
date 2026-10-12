# Nikon 连接模块（nikon-conn-kit）设计文档

日期：2026-10-12
状态：已获用户批准（方案一：统一 PTP 核心 + 双链路）

## 1. 目标与约束

- 为尼康 Z 系列相机（Z9 / Z8 / Z6III / Z5II / Zf / Z50II / ZR）提供一套**独立、完整、接口明确**的 Android 连接模块，支持三种连接方式：
  1. **USB 有线**（PTP over Android UsbHost）
  2. **Wi-Fi AP 直连**（手机加入相机热点，PTP/IP 到 `192.168.1.1:15740`）
  3. **Wi-Fi STA 局域网**（相机入网，mDNS `_ptp._tcp` 发现 + PTP/IP）
- **唯一参考依据**：基准实现 v1.0.257（`基准实现包名`）逆向分析 + GitHub 开源项目（见 §8）。禁止参考 N-Link 现有连接实现。
- 交付：独立 Android Studio 工程 `nikon-conn-kit/`（`:conn-core` 库 + `:demo` 验证 App），可构建安装真机验证。

## 2. 逆向与调研结论（设计输入）

### 2.1 基准实现 v1.0.257 逆向实证（jadx 静态分析）

| 项 | 结论 |
|---|---|
| USB 通道 | Android `MtpDevice` + UsbManager 权限/插拔广播（本设计改走原生 UsbHost bulk，见 §3 权衡） |
| 无线通道 | PTP/IP；命令 socket + 事件 socket 双连接；mDNS `_ptp._tcp.`；默认端口 **15740** |
| 包头 | 8 字节 LE：`length u32`（含头全长，校验 8..32MB）+ `type u32` |
| Init Command 载荷 | GUID(16B) + UTF-16LE 主机名（含 `\0`）+ 协议版本 `0x00010000` |
| InitCommandAck 载荷 | ConnectionID `u32@0` + 相机 GUID 16B `@4` + UTF-16LE 相机名 `@20`；type 5 = InitFail（`u32@0` 错误码） |
| InitEventRequest | type 3，载荷 = ConnectionID（u32 LE），**第二条 TCP 连接**发往同一端口，等 type 4 |
| 业务序 | GetDeviceInfo `0x1001` → OpenSession `0x1002`(sessionId=1) |
| 配对（PC 主机注册） | `0x952B` 预备（data-in，返回字符串，载荷跳过前 8 字节解码）→ 等待相机端确认（约 8.5s）→ `0x935A` 参数 `0x2001` 确认 |
| 分段读 | 标准 `0x101B` GetPartialObject；设备能力含 `0x9431` 时走扩展路径（大文件分段） |
| 保活/恢复 | 五态事件：临时失败 → 传输丢失 → 自动恢复 → 会话令牌变更 → 断开；Ping/心跳；`reconnectLastWireless` 自动重连 |
| 网络绑定 | `requestNetwork(TRANSPORT_WIFI, removeCapability(INTERNET))` + `network.getSocketFactory()`（相机热点无 internet，必须显式请求） |
| 端点模型 | `CameraEndpoint(displayName, host, port=15740, service)`；发现时跳过 link-local/不可达/与手机地址冲突的端点 |

### 2.2 开源调研互证

- gphoto 官方 PTP/IP 文档与 [mmattes/ptpip](https://github.com/mmattes/ptpip)：包结构/握手序与上表逐字段一致；Cmd_Request 载荷 = `dataOut u32@8 + opcode u16@12 + transID u32@14 + params@18`；Response = `code u16@8 + transID u32@10`；Data/EndData = `transID@8 + 数据@12`；Ping/Pong = type 13/14。
- [开源 PTP 协议实现](https://github.com/gphoto/开源 PTP 协议实现) `ptp.h`：尼康私有 opcode（`0x90C7` CheckEvent、`0x90C8` DeviceReady、`0x9201-9203` LiveView、`0x9207` 拍摄）。
- USB PTP 容器：12 字节头（`len u32` + `type u16` + `code u16` + `transID u32`，type 1=Command 2=Data 3=Response 4=Event）；接口选 `Imaging(6)/StillImage(1)/PTP(1)`；需 `claimInterface` 抢占系统服务；ZLP（零长包）视为正常帧结尾。
- mDNS 服务 `_ptp._tcp`（CIPA DC-X005）；尼康官方手册：TCP 15740 / UDP 5353；AP 模式固定 `192.168.1.1`。
- 兼容性边界：[NikonLink](https://github.com/big-watermalon/NikonLink) 称部分 Z 机型需 BLE GATT 激活才开放 15740；基准实现 无蓝牙代码，走相机菜单“连接至智能设备/连接到 PC”自开 Wi-Fi 即可直连。本模块采用后者（用户在相机侧开启 Wi-Fi），不引入 BLE。

## 3. 方案选型

- **方案一（采用）**：统一 PTP 操作核心 + 双链路（`PtpIpLink` / `PtpUsbLink`）。三种连接方式 = 三个 transport，共用同一 `PtpSession` 会话层；USB 原生 UsbHost bulk 可发任意 opcode（配对/私有 opcode 不受限）。
- 方案二（弃）：USB 走 `MtpDevice`——API 阉割，无法发任意 opcode，三通道会话层割裂。
- 方案三（弃）：三链路独立实现——逻辑重复三份。

## 4. 架构与接口

```
demo (验证 App)
 └── NikonCameraClient（门面，统一三通道入口）
      ├── discovery: UsbCameraDiscovery / MdnsCameraDiscovery / ApEndpointResolver
      ├── net: NetworkBinder（cameraNetworkSocketFactory）
      ├── pairing: HostRegistrar + HostIdentity（GUID 持久化）
      └── PtpSession（事务引擎 / 能力 / 目录 / 读取）
           └── PtpLink（接口）
                ├── PtpIpLink（命令 socket + 事件 socket + Ping/Pong + Cancel）
                └── PtpUsbLink（bulk 容器 + interrupt 事件 + ZLP 容错）
```

公共 API（`com.camerakit.api`）：

- `NikonCameraClient`：`discover(method)`、`connect(method, endpoint?)`、`session()`、`disconnect()`；`state: StateFlow<CameraState>`、`events: SharedFlow<CameraEvent>`
- `CameraSession`：`getDeviceInfo()`、`listStorages()`、`listObjects()`、`getObjectInfo()`、`readRange(handle, offset, len)`、`readObject(handle, sink)`、`getThumbnail(handle)`、`deleteObject(handle)`（显式开启才生效）
- `CameraState`：`Disconnected / Discovering / Connecting(phase) / AwaitingHostConfirm / Connected(info) / Recovering / Failed(error)`
- `CameraEvent`：`TransportLost / SessionRecovered / SessionTokenChanged / KeepaliveFailed / WirelessDisconnected`
- `CameraError`：`CameraRejected / PairingRejected / TransportLost / Timeout / PtpError(code) / UsbPermissionDenied / NoCameraFound / StorageUnavailable`

## 5. 协议实现要点

1. **PTP/IP 编解码**：`PtpIpCodec` 严格按 §2 常量打包/解包；所有入包校验长度（8..32MB）与事务号匹配，异常抛结构化 `PtpError`。
2. **握手**：InitCommand(Guid, name) → type2/5 → 第二连接 InitEvent(connId) → type4 → GetDeviceInfo → OpenSession(1)。
3. **配对**：仅在相机拒绝/存储不可用/用户显式请求时执行 `HostRegistrar`（0x952B → 等待 → 0x935A(0x2001)）；GUID+主机名持久化（SharedPreferences），复用为会话令牌。
4. **事务引擎**：事务号自增（u32）；data-in 聚合上限 32MB，大对象用 `readObject(sink)` 分段流式（`0x101B`/`0x9431` 分段，短包/空载荷视为边界）；支持 Cancel（type 11 / USB CancelTransaction）。
5. **事件**：PTP/IP type 8 → `SharedFlow`；USB interrupt IN 轮询 → 同一事件模型。
6. **保活**：Ping（type 13）周期 20s；失败阶梯产生 §4 事件；无线断链按同 GUID 有限次自动重连。
7. **USB**：VID `0x04B0` 过滤 + 接口 `6/1/1` 优先；`claimInterface(force=true)`；bulk 读写按 16KB 分块；0 字节读（ZLP）重试后继续；插拔广播 + 权限 PendingIntent。
8. **网络绑定**：`requestNetwork(WIFI, removeCapability(INTERNET))` 拿相机热点网络的 `SocketFactory` 绑定 PTP/IP socket；无独立网络时回退默认。

## 6. 错误处理与测试

- 所有协议异常映射为 `CameraError`；事务级重试（幂等读）+ 会话级恢复阶梯。
- 单元测试（JVM）：编解码往返、数据集（PTP string/数组）、事务号匹配、假链路（FakeLink）驱动的会话操作。
- 真机验证（demo App 清单）：三通道发现/连接/配对/设备信息/存储列表/对象枚举/缩略图/分段读/断线恢复。

## 7. 交付结构

```
nikon-conn-kit/
├── settings.gradle.kts / build.gradle.kts / gradle.properties / gradle wrapper
├── conn-core/          # com.camerakit.core：连接库（本设计全部协议实现）
├── demo/               # com.camerakit.demo：三通道验证 App（Compose 不引入，平台 View）
├── docs/PROTOCOL.md    # 协议笔记（常量表 + 参考文献）
└── README.md           # 构建与验证步骤
```

- 构建基线：AGP 8.7.3 / Kotlin 2.1.0 / Gradle 8.11.1 / compileSdk 35 / minSdk 26，与本机环境一致。
- 运行时依赖仅 `kotlinx-coroutines`；无 Hilt/Room/Compose，保证可构建性。

## 8. 参考依据（唯一参考源清单）

- 基准实现 v1.0.257 静态分析产物（本地 `_apk_re/`：包编解码 `n1.M`、会话核心 `n1.c4`、USB 会话 `n1.O4`、配对 `M0`、网络绑定 `n1.A`、mDNS 解析 `n1.s3`）
- [gphoto/开源 PTP 协议实现](https://github.com/gphoto/开源 PTP 协议实现)（LGPL-2.1）ptp.h / ptp-pack.c / ptpip.c
- [mmattes/ptpip](https://github.com/mmattes/ptpip)（PTP/IP 参考实现）
- [Fimagena/libptp](https://github.com/Fimagena/libptp)（Java 包编解码）
- [big-watermalon/NikonLink](https://github.com/big-watermalon/NikonLink)（Z 系列真机踩坑）
- theta4j（GUID 配对 + 事件通道生命周期）
- [sboisson/wireshark-ptpip](https://github.com/sboisson/wireshark-ptpip)（抓包互验）
- CIPA DC-X005（`_ptp._tcp` / TCP 15740）、尼康 Z6III 无线 LAN 手册

## 9. 风险与边界

- BLE GATT 激活路径不在范围内（A 方案）；若某机型仅接受 BLE 激活后开放 PTP/IP，需在相机侧通过菜单开启 Wi-Fi。
- 相机需设 USB → MTP/PTP 模式；系统 MTP 服务抢占接口时用 `claimInterface(force=true)`。
- 配对窗口约 8.5s 可配置；不同固件确认时长可能不同，失败自动重试 3 次。
