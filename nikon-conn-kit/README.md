# nikon-conn-kit — 尼康相机连接模块（三通道）

独立的 Android 连接库工程：**USB 有线 / Wi-Fi AP 直连 / Wi-Fi STA 局域网**三种连接方式，共享统一 PTP 会话层。
设计文档：[docs/superpowers/specs/2026-10-12-nikon-conn-kit-design.md](../docs/superpowers/specs/2026-10-12-nikon-conn-kit-design.md)；
协议笔记：[docs/PROTOCOL.md](docs/PROTOCOL.md)。

> 参考依据：基准实现 v1.0.257 逆向实证 + GitHub 开源项目（开源 PTP 协议实现 / ptpip / NikonLink 等，见 PROTOCOL.md §5）。
> 未参考任何现有 App 的连接实现。

## 工程结构

```
nikon-conn-kit/
├── conn-core/    # 连接库（唯一集成面，包 com.camerakit.*）
│   ├── api/        NikonCameraClient 接口、CameraSession、状态/事件/错误模型
│   ├── ptp/        PtpConsts、PtpIpCodec、PtpUsbCodec、PtpDataset、PtpLink、PtpSession
│   ├── ptpip/      PtpIpLink（命令+事件双通道握手/事务/保活）
│   ├── usb/        UsbPtpLink（bulk 容器/ZLP/中断事件）、UsbCameraDiscovery
│   ├── discovery/  MdnsCameraDiscovery（_ptp._tcp）、ApEndpointResolver（192.168.1.1）
│   ├── net/        NetworkBinder（相机热点网络绑定）
│   └── pairing/    HostRegistrar（PC 主机注册 0x952B→0x935A）、HostIdentityStore
├── demo/         # 专用测试软件（demo-debug 包）：三通道连接/主机注册/读取测试，后续测试固定用它
└── docs/         # 协议笔记
```

## 构建

要求：JDK 17+、Android SDK（compileSdk 35）、Gradle 8.11.1（wrapper 自带）。

```powershell
cd nikon-conn-kit
.\gradlew.bat :conn-core:testDebugUnitTest   # 单元测试（编解码/数据集/会话）
.\gradlew.bat :demo:assembleDebug            # 构建验证 APK
# 产物：demo\build\outputs\apk\debug\demo-debug.apk
```

安装：`adb install -r demo\build\outputs\apk\debug\demo-debug.apk`

## 真机验证清单（demo App）

| # | 步骤 | 预期 |
|---|---|---|
| 1 | 相机设 USB → MTP/PTP，OTG 连线，点「USB 连接」 | 系统授权弹窗 → 显示已连接 + 设备信息 |
| 2 | 「读取测试」 | 存储卡 / 对象数 / 缩略图 / 分段读字节数 |
| 3 | 相机菜单「连接至智能设备」开相机 Wi-Fi（AP 模式），手机加入相机热点，点「AP 直连」 | 首次出现主机配对（相机确认界面确认）→ 已连接 |
| 4 | 相机「STA mode」入同路由器/手机热点，点「STA 发现」 | mDNS 发现相机 → 连接成功 |
| 5 | 连接后关闭相机 Wi-Fi / 拔线 | 事件流出现传输丢失；无线通道自动恢复 ≤3 次 |
| 6 | 「配对」 | 相机主机确认界面出现本机主机名，确认后提示注册完成 |
| 7 | 相机进 STA 主机配置向导停在等待画面，点「STA 主机注册」 | 无需先连接即完成注册，随后自动发起 STA 连接 |

## 作为库集成

```kotlin
val client = CameraConnectionFactory.create(context) { log(it) }

// 连接（任选其一）
client.connect(ConnectionMethod.Usb)
client.connect(ConnectionMethod.WifiAp())                 // 相机热点 192.168.1.1:15740
client.connect(ConnectionMethod.WifiSta())                // mDNS 自动发现

// 订阅状态与事件
lifecycleScope.launch { client.state.collect { /* UI */ } }
lifecycleScope.launch { client.events.collect { /* 日志 */ } }

// 业务
val s = client.requireSession()
val info = s.getDeviceInfo()
val storages = s.listStorages()
val handles = s.listObjectHandles(storages[0].storageId)
s.readObject(handles[0], expectedSize) { chunk -> out.write(chunk) }

client.disconnect()
```

集成方需声明权限（demo 的 AndroidManifest.xml 有完整清单）：
`INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_MULTICAST_STATE`、`NEARBY_WIFI_DEVICES`，
以及 `android.hardware.usb.host` feature 与 `USB_DEVICE_ATTACHED` intent-filter（配 `res/xml/device_filter.xml`，vendor-id 1200）。

## 许可与声明

本工程为独立实现，协议常量来自公开标准（CIPA DC-X005）、开源项目与对成熟软件的逆向研究，仅用于个人、非商业测试用途；与尼康公司无关联。
