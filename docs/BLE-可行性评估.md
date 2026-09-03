# N-Link BLE 连接方案可行性评估

> 评估日期：2026-09-03
> 评估对象：在本项目中以 BLE（蓝牙低功耗）作为相机连接方式的可行性与定位
> 结论：**可行 —— 作为辅助通道保留并继续投入；但不能替代 WiFi PTP/IP / USB 作为主数据通道**

---

## 一、结论

BLE 方案**可行**，且本项目已落地核心链路。但它是一个**能力边界很清晰的辅助通道**：

| 维度 | 结论 |
|------|------|
| 协议可获得性 | ✅ 已被社区完整逆向，公开可得 |
| 工程可实现性 | ✅ 已有 Kotlin / Android 可运行实现，并在真机（Z50II / Z8）验证 |
| 本项目现状 | ✅ `BleManager` 已实现扫描、配对、凭证下发、心跳保活，并接入连接状态机 |
| 能否承载实时取景 | ❌ 不能，带宽不足，必须走 WiFi |
| 能否承载原图 / RAW 传输 | ❌ 不能，必须走 WiFi 或 USB |
| 能否做配对引导 | ✅ 能，且体验优于手动输入 SSID/密码 |
| 能否做 GPS 注入 | ✅ 能（`0x2007`），对应 PRD C2b 实验特性 |
| 能否做低功耗保活 | ✅ 能，这是 BLE 相对 WiFi 的核心优势 |

**一句话定位**：BLE 负责「握手引导 + 状态注入 + 低功耗保活」，WiFi PTP/IP 与 USB 负责「搬运数据」。

---

## 二、判断依据

### 2.1 协议层面：尼康智能设备（SnapBridge）BLE 协议已被完整逆向

尼康相机菜单中把 SnapBridge 称为「智能设备」，其 BLE 协议结构已被社区逆向清楚：

| 特征 | UUID（base = `-3dd4-4255-8d62-6dc7b9bd5561`） | 用途 |
|------|---------------------------------------------|------|
| 服务 | `0000de00-` / `0000de01-` | 尼康 BLE 主服务 |
| `0x2000` | `00002000-` | AUTHENTICATION，Blowfish 挑战-响应认证写入点 |
| `0x2001` | `00002001-` | POWER_CONTROL，`03` = VALID_WAKE（机身唤醒） |
| `0x2002` | `00002002-` | CLIENT_DEVICE_NAME，写入连接方设备名 |
| `0x2003` | `00002003-` | SERVER_DEVICE_NAME，返回相机 SSID |
| `0x2004` | `00002004-` | CONNECTION_CONFIGURATION，WiFi 凭证（101 字节） |
| `0x2005` | `00002005-` | CONNECTION_ESTABLISHMENT，写 `0x01` 触发 WiFi |
| `0x2006` | `00002006-` | CURRENT_TIME，时间同步 |
| `0x2007` | `00002007-` | LOCATION_INFORMATION，GPS 注入（41 字节） |
| `0x2008` | `00002008-` | LSS_CONTROL_POINT，控制标志（notify） |
| `0x2009` | `00002009-` | LSS_FEATURE，能力位（如 `0x03FD`） |
| `0x200B` | `0000200b-` | LSS_SERIAL_NUMBER，机身序列号 |
| `0x2A19` | `00002a19-` | BATTERY_LEVEL，电量（见 §五 的存疑点） |

Blowfish 认证所需的**密钥与盐值表由 furble 项目公开发布**，无需依赖尼康私有 `LsSec` 原生库。

相机 BLE 广播的 Manufacturer Data 前两字节小端为 `0x0399` 时表示已配对模式，其后 4 字节为 device id；配对模式下该字段为空或不等于 `0x0399`。

### 2.2 工程层面：已有多个可运行的开源实现，且经真机验证

- **`gkoh/furble`** —— 基于 ESP32 / M5Stack 的多厂商相机替代方案，尼康 BLE 协议的原始逆向成果，Blowfish 密钥与盐表出处。
- **`hurui200320/nsg`（Nikon Smart GPS）** —— Kotlin PoC + Android PoC。**已在 Nikon Z50II 与 Z8 上验证通过**，可完成新设备配对、已保存设备重连，并向机身下发 GPS 载荷。
- **`wyluk30/z-pin`** —— 基于 NSG 的 Android GPS 注入应用，Jetpack Compose UI、前台常驻服务、看门狗自动重连，协议实现（Blowfish 哈希、4 阶段配对引擎、17 字节配对消息、41 字节 GEO 载荷）移植自 NSG。
- **`attilaolah/birdcam`** —— Go 语言实现，面向 Coolpix A900，含完整的 GATT 服务与特征映射。

> 这意味着 BLE 路线**不需要从零逆向**，工程风险已被社区吸收。

### 2.3 本项目现状：核心链路已落地

`app/src/main/java/com/nikonlink/app/device/ble/BleManager.kt`（约 1000 行）已实现：

1. **扫描**：过滤尼康服务 UUID，15s 窗口
2. **4 阶段 Blowfish 配对握手**：写入 `0x2000`，处理盐值校验与最终 `0x2008` OK
3. **读取 WiFi 凭证**：解析 `0x2004` 的 101 字节结构（flags / SSID / password / security / IP），端口固定 15740
4. **触发 WiFi 建立**：向 `0x2005` 写入 `0x01`
5. **RSSI 心跳保活**：5s 间隔，连续 3 次读取失败判定掉线
6. **电量与文件事件通知**：`0x2008` LSS_CONTROL_POINT 的 notify
7. **接入层**：Hilt `AppModule.provideBleManager` → `ConnectionManager`，贡献 `ChannelType.BLE` 与 `BLE_CONNECTED` 状态

`app/src/test/java/com/nikonlink/app/device/ble/NikonBlowfishTest.kt` 使用**真机抓包数据**校验 Blowfish 哈希实现，是协议正确性的直接证据。

### 2.4 竞品佐证：三款参考软件全部不依赖 BLE

对本地三款 APK 做 manifest 与 dex 字符串分析（`D:\1\N-Link\.workbuddy\apk-analysis\`）：

| 软件 | 包名 | 蓝牙权限 | 蓝牙代码 | 实际连接方式 |
|------|------|---------|---------|-------------|
| **ZDROP** 1.0.190 | `com.zdrop.z6ii`（专攻尼康 Z6II） | **无** | **无** | WiFi + mDNS 服务发现（`_ptp._tcp.`） |
| **ZRelay 相机快传** 3.0.14 | `com.geekmanlab.zrelay` | **无** | **无** | PTP/IP 原生库 `libzrelay_core.so` + USB PTP |
| **影犀** 1.1.0 | `com.camerasyncpro.app`（Flutter） | **无** | 仅第三方库残留符号 | 未启用 BLE |

**推论**：BLE **不是**实现「相机连接 + 素材传输」的必要条件——三款竞品在不碰蓝牙的前提下都做成了完整产品。反过来说，N-Link 已具备的 BLE 能力（GPS 注入、低功耗保活、免输入配对引导）恰恰是这三款都**没有**的差异化空间。

---

## 三、适用场景（BLE 能做什么）

1. **配对引导**：BLE 握手后直接从 `0x2004` 读出相机 AP 的 SSID / 密码 / IP，免去手动输入与相机菜单翻找。
2. **GPS 地理标记注入**：向 `0x2007` 写 41 字节坐标，机身直出照片即带 EXIF 地理信息 —— 对应 PRD C2b 实验特性。对国内机型（法规限制内置 GPS）是刚需路径。
3. **时间同步**：`0x2006`，校正机身时钟。
4. **低功耗保活**：BLE 维持连接的功耗远低于常驻 WiFi，可在相机休眠时保持在线感知，快门唤醒响应更快。
5. **遥控快门**：`0x2083` REMOTE_SHUTTER（UUID 已定义，尚未接线）。
6. **状态读取**：电量、序列号、机身能力位（`0x2009`）。
7. **文件事件通知**：`0x2008` notify，新照片即时感知。

---

## 四、主要限制与风险

### 4.1 带宽硬约束（最核心）

BLE 5.0 物理层理论 2 Mbps，但 GATT 实际有效吞吐通常只有**数十 kbps** 量级。因此：

- **实时取景（Live View）不可行** —— 尼康官方也明确 LiveView 需切到 WiFi。
- **原图 / RAW / 全分辨率 JPEG 传输不可行** —— SnapBridge 经 BLE 自动下载的照片被压缩到 2MP（部分新机 8MP），原始尺寸必须切 WiFi 手动下载。

### 4.2 首次配对可能需要经典蓝牙（Bluetooth Classic）bonding

COOLPIX A1000 的实测记录显示：BLE 5 阶段认证全部通过后，机身仍在等待 **Bluetooth Classic 安全绑定**；缺少这一步，向 `0x2005` 写 `0x01` 无法唤起 WiFi。NSG 项目同样指出，ESP32-S3 因**只有 BLE、没有 Classic 蓝牙**而无法完成尼康配对，最终改用初代 ESP32（双栈）。

> ⚠️ **本项目现状**：`BleManager` 使用 `device.connectGatt(..., TRANSPORT_LE)`，**全文件未见 `createBond()` / `ACTION_BOND_STATE_CHANGED` 等 Classic bonding 相关调用**。这是一个待真机验证的潜在缺口——若目标机型要求 Classic bonding，则「BLE 触发 WiFi」这条路径可能不稳定。

### 4.3 机型与固件差异

- 需机身支持 BLE 智能设备模式；老机型（部分 DSLR、早期 COOLPIX）能力受限或需升级固件。
- 不同机型的 `LSS_FEATURE`（`0x2009`）位图不同，需按能力位做特性开关。

### 4.4 状态机复杂度

SnapBridge 实际是 **BLE → Bluetooth Classic → WiFi 三级门控**，切换还需在机身网络菜单中配合操作，异常路径多、重试与回退逻辑复杂，是稳定性问题的主要来源。

### 4.5 STA 模式下 BLE 受限

官方说明：切到 Wi-Fi **STA 模式会自动关闭机身 BLE**，且 STA 下不支持蓝牙遥控、自动下载限 2MP。AP 模式与 STA 模式的能力集并不一致。

### 4.6 Android 平台权限

Android 12（API 31）起需 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` / `BLUETOOTH_ADVERTISE` 运行时权限；Android 10–11 扫描需定位权限。N-Link 已在 `AndroidManifest.xml` 声明（含 `android.hardware.bluetooth_le` 且 `required="false"`）。

---

## 五、现存代码问题（顺带发现，需真机验证）

`NikonBleProfile` 中电量特征定义疑似有误：

```kotlin
// BleManager.kt:52 当前写法
val BATTERY_LEVEL = UUID.fromString("00002a19$BASE")   // BASE = "-3dd4-4255-8d62-6dc7b9bd5561"
```

`0x2A19`（Battery Level）是 **Bluetooth SIG 标准特征**，其标准 base UUID 应为 `00002a19-0000-1000-8000-00805f9b34fb`，而非尼康私有 base。当前写法很可能读不到电量。

> 建议：保留两种 base，在 GATT 服务发现后按实际枚举到的特征匹配；或直接在真机上 dump 一次服务列表确认。

---

## 六、建议

1. **保留 BLE 记录与代码**，定位为「引导 + 注入 + 保活」的辅助通道，主数据通道维持 WiFi PTP/IP + USB。
2. **PRD C2b（GPS 注入）按计划推进**，作为默认关闭的实验特性；注入前校验定位新鲜度（PRD 已定义 5 分钟阈值）。
3. **补两项真机验证**：
   - Classic bonding 缺口：确认目标机型（Z50II / Z6III / Z8 等）是否要求 Classic bonding 才能触发 WiFi。
   - `BATTERY_LEVEL` 的 UUID base 是否正确。
4. **不要**把 LiveView 或大文件传输规划到 BLE 上，避免方向性返工。
5. 长期可考虑把未接线的 `REMOTE_SHUTTER`（`0x2083`）与 `CURRENT_TIME`（`0x2006`）接上，进一步拉开与竞品的差距。

---

## 七、参考来源

**官方文档**

1. 尼康 SnapBridge 官方说明 —— <https://imaging.nikon.com/lineup/software/snapbridge/>
2. 尼康在线说明书 · Z7II/Z6II 无线连接（BLE 技术规格 4.2、AP/STA 模式差异） —— <https://onlinemanual.nikonimglib.com/z7II_z6II/>
3. 尼康在线说明书 · 连接至智能设备（配对流程） —— <https://onlinemanual.nikonimglib.com/p1100/zh-cn-prc/13-02.html>

**开源项目**

4. `gkoh/furble` —— ESP32/M5Stack 多厂商相机替代方案，尼康 BLE 协议原始逆向成果与 Blowfish 密钥/盐表出处
5. `hurui200320/nsg`（Nikon Smart GPS）—— Kotlin + Android PoC，已在 Nikon Z50II / Z8 验证 —— <https://github.com/hurui200320/nsg>
6. `wyluk30/z-pin` —— 基于 NSG 的 Android GPS 注入应用 —— <https://github.com/wyluk30/z-pin>
7. `attilaolah/birdcam` —— Go 实现，Coolpix A900 —— <https://pkg.go.dev/github.com/attilaolah/birdcam/nikon/coolpix>

**技术分析文章**

8. 《【歪门邪道】用 ESP32 替代尼康 SnapBridge — 协议篇》 —— BLE UUID 清单、广播 Manufacturer Data `0x0399` 语义 —— <https://skyblond.info/archives/1115.html>
9. 《Investigating PC Control for the Nikon COOLPIX A1000》 —— GATT 特征映射（Post-Auth）、Blowfish 5 阶段认证、Classic bonding 卡点 —— <https://lilting.ch/en/articles/nikon-coolpix-a1000-pc-connection>

**本项目本地资料**

10. 三款参考 APK 的 manifest / dex 字符串逆向分析 —— `D:\1\N-Link\.workbuddy\apk-analysis\`（`analyze.py` 与 `*_matched.txt`）
