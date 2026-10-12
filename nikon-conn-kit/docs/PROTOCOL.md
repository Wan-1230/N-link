# Nikon 相机连接协议笔记（nikon-conn-kit）

本文档记录本工程实现所依据的协议细节。**唯一参考依据**：基准实现 v1.0.257 逆向实证 + GitHub 开源项目（见文末）。

## 1. PTP/IP（Wi-Fi AP / STA 共用）

### 1.1 包结构（CIPA DC-X005 / gphoto 文档 / 基准实现 静态分析逐字段互证）

包头 8 字节小端：`length u32`（含头全长，校验 8..32MB）+ `type u32`。

| type | 名称 | 载荷（偏移相对载荷起点） |
|---|---|---|
| 1 | InitCommand | GUID(16B) + UTF-16LE 主机名（含 `\0`）+ 协议版本 u32 = `0x00010000` |
| 2 | InitCommandAck | ConnectionID u32@0 + 相机 GUID 16B@4 + UTF-16LE 相机名@20 |
| 3 | InitEvent | ConnectionID u32@0（**第二条 TCP 连接**发往同一端口） |
| 4 | InitEventAck | 空 |
| 5 | InitFail | 错误码 u32@0 |
| 6 | CmdRequest | DataOut u32@0 + opcode u16@4 + transId u32@6 + params n×u32@10 |
| 7 | CmdResponse | code u16@0 + transId u32@2 + params@6 |
| 8 | Event | code u16@0 + params@2 |
| 9 | StartData | transId u32@0 + 总长 u32@4 |
| 10 / 12 | Data / EndData | transId u32@0 + 数据@4 |
| 11 | Cancel | transId u32@0 |
| 13 / 14 | Ping / Pong | 空（基准实现 收到 13 回 14 空载荷） |

**基准实现 实证细节**：
- CmdRequest 的 DataOut 字段**恒为 1**（硬编码 `putInt(1)`，与 gphoto 文档示例一致）；
- 事务号匹配校验：响应/数据包事务号不匹配报「事务编号不匹配」；超时取消后迟到响应需容忍；
- 包长度越界抛「包长度异常」；数据包缺事务号抛协议错。

### 1.2 连接与会话序

```
命令 socket ── connect(host, 15740)
           ── InitCommand(GUID, 主机名)      → InitCommandAck(connectionId, 相机GUID, 名) | InitFail(码)
事件 socket ── connect(host, 15740)   // 第二条连接，同一端口
           ── InitEvent(connectionId)        → InitEventAck
随后：OpenSession(0x1002, **随机会话号**) → GetDeviceInfo(0x1001) → 业务
（基准实现 v1.0.257 诊断日志实证：`open-session-send id=804956382` 先于 DeviceInfo；随机会话号避免与相机残留会话冲突）
```

- 端口：**15740**（CIPA DC-X005 / 尼康 Z6III 手册：TCP 15740 + UDP 5353）；
- AP 模式相机固定 `192.168.1.1`；
- mDNS 服务类型 **`_ptp._tcp`**（NsdManager / DNSServiceBrowse），端口缺失时回退 15740；
- 网络绑定：`requestNetwork(TRANSPORT_WIFI, removeCapability(INTERNET))` + `network.socketFactory`（相机热点无 internet 能力；基准实现 同款）。

### 1.3 主机配对（基准实现「PC 主机注册」，逆向自 `M0` 流程）

```
握手 → GetDeviceInfo → OpenSession(1)
     → 0x952B 预备（data-in，载荷前 8 字节为头，其后 UTF-16LE 字符串 = 相机侧展示文本）
     → 等待用户在相机「主机确认」界面确认（基准实现 等待 8500ms）
     → 0x935A 确认（参数 0x2001）
     → 完成（相机保存主机；GUID + 主机名 = 会话令牌，持久化复用）
```

- 相机拒绝时 InitFail 错误码非 0（基准实现 提示「请让相机停留在 STA 主机配置向导」）；
- 首次连接未配对时可能表现为「存储卡不可用」，配对后重连即恢复。

### 1.4 分段读取

- 标准：`0x101B` GetPartialObject(handle, offset, maxBytes)；
- 尼康扩展：`0x9431`（设备能力 `operationsSupported` 含此码时启用；基准实现 策略：0x101B 失败/空载荷 → 0x9431）；
- 空载荷 = 范围边界/EOF；大对象按 256KB 分段流式读取。

### 1.5 保活与恢复（基准实现 五态事件模型）

- PTP/IP：Ping(type13) → Pong(type14)，消费于命令通道（本实现于事务锁内短窗排空）；
- 事件阶梯：保活临时失败 → 传输丢失 → 自动恢复（同 GUID 重连 ≤3 次）→ 会话令牌变更（缓存失效）→ 断开；
- USB：无 Ping，用轻量只读事务（GetDeviceInfo）保持活性。

## 2. USB PTP（Android UsbHost）

容器 12 字节小端：`length u32`（含头全长）+ `type u16` + `code u16` + `transactionId u32`。

| type | 名称 | 说明 |
|---|---|---|
| 1 | Command | code = opcode，载荷 = n×u32 参数 |
| 2 | Data | code = opcode，载荷 = 数据段 |
| 3 | Response | code = PTP 响应码，载荷 = n×u32 参数 |
| 4 | Event | code = 事件码（interrupt IN 端点） |

要点（开源共同踩坑点）：
- 设备过滤：VID `0x04B0`（尼康）或接口 `Imaging(6)/StillImage(1)/PTP(1)`；
- `claimInterface(force=true)` 抢占系统 MTP 服务；
- bulk IN / bulk OUT / interrupt IN 三端点；bulkTransfer 按 16KB 分块；
- 传输长度恰为端点 maxPacket 整数倍时相机可能发 **ZLP（0 字节包）**，视为正常帧结尾重试续读，非错误；
- 相机需设 USB → MTP/PTP 模式。

## 3. 尼康私有 opcode（开源 PTP 协议实现 `ptp.h`）

| opcode | 用途 |
|---|---|
| 0x90C7 | CheckEvent（轮询事件） |
| 0x90C8 | DeviceReady |
| 0x9201 / 0x9202 / 0x9203 | StartLiveView / EndLiveView / GetLiveViewImage |
| 0x9207 | InitiateCaptureRecInMedia（拍摄） |
| 0x9431 | 扩展分段读（基准实现 实证） |
| 0x952B / 0x935A | 主机注册预备 / 确认（参数 0x2001）（基准实现 实证） |

## 4. 兼容性边界

- 部分 Z 机型经 **BLE GATT 激活**后才开放 15740（NikonLink 实测）；本方案不引入 BLE，要求用户在相机菜单「连接至智能设备 / 连接到 PC」中自开 Wi-Fi（基准实现 同路径，实测可行）。
- 同路由器场景：手机与相机须同一非访客 Wi-Fi，关闭 AP 隔离，否则 mDNS 不可见。

## 5. 参考依据

**本地逆向（基准实现 v1.0.257，`_apk_re/`）**：包编解码 `n1.M`、会话/握手/配对 `n1.c4`（`M0`/`D`/`L0`）、USB 会话 `n1.O4`、网络绑定 `n1.A`、mDNS 解析 `n1.s3`、保活事件 `n1.f5`。

**GitHub / 公开文档**：
- [gphoto/开源 PTP 协议实现](https://github.com/gphoto/开源 PTP 协议实现)（LGPL-2.1）：`ptp.h` opcode 全集、`ptp-pack.c`、`ptpip.c`
- [gphoto PTP/IP 文档](https://gphoto.github.io/doc/ptpip/)
- [mmattes/ptpip](https://github.com/mmattes/ptpip)：PTP/IP 双队列参考实现（Nikon D5300 实测）
- [Fimagena/libptp](https://github.com/Fimagena/libptp)：Java 包编解码
- [big-watermalon/NikonLink](https://github.com/big-watermalon/NikonLink)：Z 系列真机踩坑（BLE 激活边界）
- theta4j：GUID 配对 + 事件通道生命周期
- [sboisson/wireshark-ptpip](https://github.com/sboisson/wireshark-ptpip)：抓包互验
- [michaelzoech/remoteyourcam-usb](https://github.com/michaelzoech/remoteyourcam-usb)、[whoozle/android-file-transfer-linux](https://github.com/whoozle/android-file-transfer-linux)：USB PTP 容器/ZLP
- CIPA DC-X005（`_ptp._tcp`）、尼康 Z6III 无线 LAN 手册（TCP 15740 / UDP 5353）
