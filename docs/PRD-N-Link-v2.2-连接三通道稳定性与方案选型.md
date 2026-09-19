# PRD：N-Link v2.2 连接三通道稳定性与方案选型

> 版本：v2.2.0（拟，versionCode 19） · 分支：`research-connection-prd-v2.2` · 基线：v2.1.1（8b4365c）
> 状态：**待确认**（本文档只做方案选型与规划，未改动任何业务代码）
> 定位：连接方向专项 PRD。三种连接方式（WiFi AP / WiFi STA / USB 有线）在不同机型上表现不稳，本文给出「竞品怎么做的 → 官方/开源能做到什么 → 我们缺什么 → 每条通道的最优解与落地顺序」。
> 原则延续：已跑通的功能不改动、改动面压到最小、每项独立可回滚、每个结论带证据。

---

## 摘要（八个结论，细节见各节）

1. **尼康不给第三方任何移动 SDK**，5 个竞品里 4 个必须自研 —— 我们的技术路线不是落后，是唯一可行解；且我们用的 **TCP 15740（PTP-IP，CIPA DC-005-2005）+ UDP 5353（DNS-SD）是尼康 Z 系手册白纸黑字写明的**，对外可正名（§2.1、§2.5）。
2. **AP 模式跨机型不稳的第一根因不是 ROM，而是我们「从不学网关」**：全仓无一处读 `routes/gateway/dhcpInfo`，只能靠 BLE 凭据或硬编码 `192.168.1.1`；而且刚做的地址分级**在用户实际路径上是死代码**（必须 BLE 成功才触发）。ZDROP 用「网关学习 + 稳定化 + 自地址排除」彻底解决，改法约 200 行（G1、G2）。
3. **STA 最大的两处提升**：① 扫描期不要对每个候选做 PTP Init（慢 10 倍且**占用相机唯一的配对槽**）；② 本机热点改用 `TetheringManager.TetheringEventCallback` 拿 `Network` 句柄，替代内核接口枚举 + 进程级绑定（G3、G4）。
4. **USB 是我们的结构性优势**（官方 SnapBridge 根本没有 USB），但当前「无状态机、拔一下就判死、小米 OTG 关了我们只会说『无相机』、>2GB offset 溢出」四项把它拖成了不稳（G8、G9、G10）。
5. **「不同机型表现不稳」在产品层面从未被真正治理**：全仓只有一个机型分支（Xiaomi 文案尾巴，提交自述「no behavior change」）。竞品 ZRelay 在这一点上投入最重，并且**把兼容规则放服务端下发**（`/v1/camera-rules`）。我们要把「机型×ROM×通道」矩阵当产品资产建（G11、G12、§6）。
6. **协议层有三笔欠账**：下载期心跳不豁免（会自己砍掉大文件传输）、数据阶段不抽干 event（丢加片/删除事件）、PTP-IP 标准的**第三条 data 通道**未实现（ZDROP「双通道」、TrueSight「control/data/event」都做了）（G7、§2.5-2）。
7. **落地节奏**：v2.2.0 只做 P0 十二项，**且埋点先于改逻辑**（今天 11.1 表里 8 个指标是「未知」，没有基线就无法判断任何一次「优化」是否有效）。
8. **本次未做真机运行测试**（会话内 `adb devices` 为空），9 项必须实机定标的结论已列在 §12，其中 **V-1（InitCommand 字节与 `0x952B/0x935A` 语义）**和 **V-3（相机 WMA 是否可写）**分别决定「竞品反汇编常量的可信度」与「STA 能否免手工」。

---

## 〇、研究方法与证据来源（可复现）

| 手段 | 产出物 | 说明 |
|---|---|---|
| 竞品 APK 静态解包 | `_apk_re/listing/*.txt`（ZIP 清单）、`_apk_re/badging/*.txt`（aapt 权限/feature/组件）、`_apk_re/scan/*.txt`（DEX+SO 字符串分类扫描）、`_apk_re/scan/*_cjk.txt`（中文文案）、`_apk_re/dex/classes_*.txt`（dexdump 类表）、`_apk_re/so/lib/`（原生库）+ `*.str.txt`（符号/字符串） | 5 个 APK 全部解包完成。工具：SDK `aapt 35.0.0` / `dexdump 35.0.0` / `strings`，自研脚本 `dexscan.py`、`cjk.py` |
| 原生库符号还原 | SnapBridge `libJuno.so`/`libNis.so`/`libLsSec-jni.so`；像素蛋糕 `libcamerawirelesscontrol.so`（111 个 `NikonCameraControl::` 符号）；ZRelay `libzrelay_core.so` | 通过导出符号 + 未剥离的 C++ mangled name 还原第三方 SDK 的类结构与函数语义 |
| 本项目代码通读 | 见本文第四节（逐条 `文件:行号`） | 连接层 67 个 Kotlin 文件全量走查 |
| 官方协议/SDK 调研 | 第二节（§2.1–2.3）+ 附录 B | 尼康官方能力面由 SnapBridge 反编译直接证据化；各厂商移动 SDK 可得性见 §2.3 |
| 开源先例调研 | 第三节（11 个可借鉴仓库 + 逐条「偷什么」） | GitHub 同类实现 |
| 实机运行测试 | **未执行** | 本次会话 `adb devices` 为空（无设备连接），Mobile Use 插件无法起测。竞品需要真机对比的项已列入第十二节「待实机验证清单」 |

**法律边界声明**：本文所有竞品结论均来自 APK 静态分析（公开分发物的结构与字符串），不复制任何竞品代码；`libcamerawirelesscontrol.so` 属第三方商业 SDK，仅用于理解行业做法。ZRelay 自身在关于页声明「与 Nikon 不存在官方授权」，与我们的定位相同。

---

## 一、竞品解包结论（5 个 APK 横向）

### 1.1 基本盘

| App | 包名 / 版本 | minSdk→target | 体积 | 技术栈 | 连接通道 | 官方 SDK？ |
|---|---|---|---|---|---|---|
| **SnapBridge**（尼康官方） | `com.nikon.snapbridge.cmru` 2.13.3 | 23→35 | 66MB | Java/Kotlin + 3 个 Nikon 原生库 | BLE + 蓝牙经典(BTC) + WiFi（**无 USB host**） | 是（自研全栈 + LsSec 加密 + NIS 云） |
| **像素蛋糕 PixCake** | `com.xiangtian.pixcake` 1.9.0 | 28→34 | 828MB | 商业 App + **第三方原生 SDK**（`com.truesight.camerawirelesscontrol`） | USB host + WiFi，六品牌 | 否，但**购买了第三方商业 SDK** |
| **影犀 / CameraSync Pro** | `com.camerasyncpro.app` 1.2.1 | 26→**36** | 73MB | **Flutter**（Dart 逻辑在 `libapp.so`）+ 原生 USB 插件 | USB + PTP/IP，多品牌 | 否（自研协议栈） |
| **ZDROP** | `com.zdrop.z6ii` 1.0.257 | 26→35 | 3.1MB | 纯 Kotlin/Compose，单 dex，**零第三方网络库** | AP + 手机热点 STA + 同路由 STA + USB host | 否（自研 `PtpIpZ6Gateway`） |
| **ZRelay 相机快传** | `com.geekmanlab.zrelay` 3.0.46 | 29→**36（compileSdk 36）** | 3.2MB | Kotlin + 极小 native（仅 `JNI_OnLoad`） | **BLE 配对 + WiFi**（**无 USB host**、无 `CHANGE_WIFI_MULTICAST_STATE`） | 否（自研） |

**第一层结论**：与我们同赛道（Nikon Z 专属、独立开发者）的两个产品是 **ZDROP** 和 **ZRelay**，它们分别代表两条相反的技术路线；**影犀**是 Flutter 多品牌路线；**像素蛋糕**是「花钱买 SDK 的多品牌路线」；**SnapBridge** 是官方闭环。稳定性问题的解法主要在前两者身上。

### 1.2 权限与组件矩阵（决定「能做到什么」的硬边界）

| 能力 | SnapBridge | 像素蛋糕 | 影犀 | ZDROP | ZRelay | **N-Link** |
|---|---|---|---|---|---|---|
| `usb.host` feature + `USB_DEVICE_ATTACHED` | ✗ | ✓（含 DETACHED） | ✓ | ✓ | ✗ | ✓ |
| `NEARBY_WIFI_DEVICES`（13+ 免定位扫 WiFi） | ✗（用旧 API） | ✗ | ✓ | ✓ | ✗ | ✓ |
| `CHANGE_WIFI_MULTICAST_STATE`（组播锁/mDNS） | ✓ | ✓ | ✗ | ✓ | **✗** | ✓ |
| `BLUETOOTH_SCAN/CONNECT` | ✓ | ✗ | ✗ | ✗ | ✓ | ✓ |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | ✓ | ✗ | ✓ | ✓ | ✓ | ✓ |
| `RECEIVE_BOOT_COMPLETED` | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | ✓ | ✗（仅 dataSync） | ✓ | ✓ | ✓ | ✓ |
| `NFC`（碰一碰配对） | ✓ | ✗ | ✗ | ✗ | ✗ | ✗ |
| 电池优化 + 自启动引导文案 | 少 | — | — | 少量 | **极多（逐 ROM 一套）** | 仅一个 Xiaomi 提示 |

要点：
1. **ZRelay 不声明组播锁** → 它不做 mDNS 发现，走「BLE 配对拿身份 + 固定/学习 IP」路线（证据：字符串里有 `192.168.43.1`、`192.168.1.1`、`192.168.1.50` 三个候选，以及 `WRONG_CHANNEL` 信道校验）。**这是「BLE 主导」路线，AP 稳定性最好、但要求相机支持 BLE 配对。**
2. **ZDROP 不声明任何蓝牙权限** → 纯 WiFi/USB 路线，靠 mDNS(NSD) + 子网扫描 + 网关学习找相机。**这是「无 BLE 兜底」的极端情况，也是它把网络侧工程做到极致的原因。**
3. **影犀/ZRelay 已 target 36**（Android 16），N-Link 与 ZDROP 停在 35。target 36 强制 16KB page size 与更严的 FGS/权限约束，**这是我们必须跟进的合规项**（见 §10.4）。

### 1.3 各家「找相机」的方法（三种通道的第一道坎）

| App | AP 模式（手机连相机热点） | STA/热点模式 | 关键证据（字符串级） |
|---|---|---|---|
| SnapBridge | BLE 读凭据 → 系统 WiFi 配置；`WifiNetworkSpecifier` 与旧 `WifiConfiguration/addNetwork` 双路径 | 用 **PTP 厂商操作读写相机 WMA 设置**（SSID/WPA 密码/信道/认证/WPS）+ `changeCameraConnectionMode` 把相机切到目标模式；Bonjour(mDNS) 解析 `_ptp._tcp.` 找相机 | `GetWmaSettingAction`/`SetWmaSettingAction`/`GetWmaInfoAction`/`FactoryResetWmaSettingAction`、`setWmaSettingWpaPassphrase`/`setWmaSettingWifiChannel`/`setWmaSettingWifiAuth`/`setWmaSettingWpsMode`、`_ptp._tcp.`、`BonjourPairingTask`/`BonjourConnectGuid`/`connectByWiFiBonjour`、`WiFiStationActiveCameraInfo`/`WiFiStationRegisteredCamera`、`isAddNullToEndSSIDCamera`（**机型专属坑：部分机身 SSID 结尾带 NUL**）、`addAccessPoint`/`updateAccessPoint`/`removeNetwork networkId` |
| 像素蛋糕(TrueSight SDK) | SDK 内部（native） | **SSDP/UPnP `M-SEARCH` 多网卡发现**（`Found %zu valid interface(s) for M-SEARCH`、`GetInterfaceIpAndBroadcast`）+ `UPnPSubscriptionManager`/`UPnPEventServer` + WebSocket | `SSDPDeviceDiscover`、`M-SEARCH * HTTP/1.1`、`MAN: "ssdp:discover"`、`_camera-canon._tcp` 等 |
| 影犀 | — | **九种 mDNS 服务类型枚举**：`_ptp._tcp`、`_nikon._tcp`、`_nikon-ptp`、`_nikon-pc`、`_canon-bjnp1._tcp`、`_camera-canon._tcp`、`_fujifilm._tcp`、`_sony._tcp`、`_panasonic._tcp`、`_gopro-web._tcp`、`_scalarwebapi._tcp` | 同左；另有 `nikonStaInitAckOnly` / `nikonStaPairingConfirmed` / `NikonStaConnectionIntent` 三个 **STA 子状态** |
| **ZDROP** | **不硬编码 IP：从已连网络的 DHCP/路由信息学出相机地址**（网关即相机），并做「稳定化」与自地址排除 | NSD `NsdManager` 发现 + resolve 队列 + `/24` 扫描 + `PtpIpProbe`；热点模式额外等相机拿到租约 | `AP gateway detected host=`、`AP gateway stabilized host=`、`AP gateway changed during recovery host=`、`AP gateway ignored because it matches handset address=`、`AP network readiness wait=1200ms gateway=`、`; using canonical fallback=192.168.1.1`、`SSDK…` 无（零第三方网络库）、`(?i)^NIKON_([0-9A-F]{8})`（**相机热点 SSID 规则**）、`NsdManager$ResolveListener` 串行化 |
| ZRelay | BLE 配对 → 直接按已知地址连；`WRONG_CHANNEL` 校验 2.4/5GHz 信道 | **`TetheringManager` 主动开/感知本机热点**（`TetheringEventCallback`、`TetheringInterface`），再用 BLE 把热点 SSID/密码写给相机 | `Landroid/net/TetheringManager$TetheringEventCallback;`、`Landroid/net/TetheringInterface;`、`192.168.43.1`、`reconnect_scan_window_elapsed`、`reconnect_candidate_matched`/`rejected` |

> **对本项目的直接冲击**：我们在 AP 模式**完全没有读取路由/网关信息**（全仓 `grep .routes|RouteInfo|isDefaultRoute|dhcpInfo` = 0 命中），AP 连接后只能靠 BLE 凭据 / 本会话记忆 / 历史 IP / 硬编码 `192.168.1.1` 兜底（`ConnectionManager.kt:69-72,798-824`）。ZDROP 用「学网关 + 稳定化 + 排除手机自身地址」把这件事彻底解决了，这是 **AP 通道跨机型不稳的第一根因**。

### 1.4 各家连接「保活 / 重连 / 错误码」工程水位

| 维度 | 竞品最佳做法 | 证据 |
|---|---|---|
| 重连状态机 | ZRelay：BLE 扫描窗口 + 候选打分 + 拒绝原因（`reconnect_scan_window_elapsed`、`reconnect_candidate_matched/rejected`、`ReconnectingAfterBond`、`RECONNECT_AND_RETRY`）；ZDROP：按阶段(phase)打点重试（`… retry requested phase=`、`channel retry scheduled failedAttempt=`） | `scan/ZRELAY.txt`、`scan/ZDROP.txt` |
| 通道分离 | ZDROP「标准双通道」+ 失败回落：`AP standard dual-channel attempt=` / `,standard dual-channel retry requested phase=` | ZDROP |
| 事件通道建立后的稳定期 | ZDROP `:15740 eventSettle=650ms`（开完 event 通道后强制等 650ms 再发命令） | ZDROP |
| 逐 socket 绑定 | ZDROP `:15740 bindNetwork=`（每个 socket 显式绑网络，而非进程级） | ZDROP |
| 分片/续传 | 影犀 `Nikon_GetPartialObject64`/`Nikon_PartialObject64`/`Nikon_GetObjectSize`（**64 位偏移**，>2GB 正确）；ZDROP「首段异常慢速，改用小范围重试」(**自适应分片**)、`停止并保留续传`、`已清除过期的待续传任务` | YINGXI_dart.txt、ZDROP_cjk.txt |
| 相机忙/独占 | 影犀 `监看中，请先停止监看后再访问相机文件`、`设备忙碌中，分块传输无法获取连接`、`正在结束上一段传输，请稍后重试`；TrueSight `waitBusy(int,int,cb)`/`startLiveViewWaitBusy` | YINGXI_cjk.txt、cwc 符号 |
| 配对/授权门 | SnapBridge `BonjourConnectGuid`+`PtpAutoTransferInstructionLssEvent`；影犀 `Nikon_GetPairingStatus`/`Nikon_ConfirmPairing`；ZRelay 6 位配对码 + `classic_bond_timeout_without_confirmation`/`stale_bond_remove_timeout`；ZDROP 未声明（走主机配置） | 各家 |
| USB 授权竞态 | 影犀 `已有 USB 授权请求正在等待系统确认`（去重，不重复弹系统框） | YINGXI_cjk.txt |
| 失败原因可见化 | ZRelay 把「相机侧菜单差异」写进文案：`不同 Z 系列机型的"连接到计算机"菜单不同。请按相机菜单中是否存在"连接类型"来选择`、`③ 部分机型（如 Z9）必须先按 OK，手机才能继续连接并完成检查`；ZDROP 细分到 12 种热点未就绪文案 | 各家 cjk |
| 诊断自测 | **ZDROP 内置协议诊断模式**：`仅用于协议诊断。运行前会断开当前无线会话，完成后保持未连接状态`；日志导出且明确脱敏：`导出文件不含照片或 Wi-Fi 密码，可用于定位连接断开原因` | ZDROP_cjk.txt |
| 性能内建度量 | ZDROP `STA thumbnail perf count=500 p50=`（**APP 内自己统计 p50 延迟**） | ZDROP |
| 云端兼容规则热更新 | **ZRelay 后端有 `/v1/camera-rules`、`/v1/capability-reports`、`/v1/events/batch`、`/v1/logs`、`/v1/licenses/heartbeat`** → 机型/机身兼容规则由服务端下发，不必发版 | ZRELAY http_path |

### 1.5 竞品功能差距（连接之外的顺带发现，供路线图取用）

- **相机侧能力**：TrueSight/影犀均实现 `setCameraRating`/`getAllRatingInSdcard`（**在相机内给照片评级/筛选**，tethering 工作流刚需）、`deleteCameraObject`、`Nikon_StartTracking/EndTracking`（Z8/Z9 对象跟踪）、`Nikon_GetObjectMetadata`、`changeToPtpMtpMode`（相机 USB 模式不对时**主动切换**）。
- **画质/带宽**：影犀有 `nikon_live_view_resolution_android` + `nikon_live_view_quality_android` + `Nikon_GetLiveViewImageEx` 与 `…Legacy` 双实现 → **监看分辨率/质量可调、按固件分支**。我们固定 66ms 轮询 `0x9203`。
- **文件侧**：ZDROP `NikonMovPreviewLayout(moovOffset=…)` → **moov 位置感知的视频边下边播**；`NikonMakerNoteLensSpec(brand=…)` → 从 maker note 解出镜头型号（相册可按镜头筛选）。
- **产品侧**：ZRelay 有「离线授权至…」「专业版授权不可用」「多相机配对」「RAW 优先，JPEG 后台继续」；影犀有独立收款页（`pay.ldxp.cn`）；ZDROP 明确隐私声明「不提供云端存储，不会自动上传照片、相机信息、网络信息或诊断日志」。

---

## 二、这些软件是否用了「厂商 SDK」——以及我们到底能拿到什么接口

### 2.1 直接回答：五款 App 的 SDK 使用情况

| App | 用厂商 SDK 吗 | 实际用的是什么 | 证据 |
|---|---|---|---|
| **SnapBridge**（尼康官方） | **不适用**——它就是接口的持有者 | 尼康自研内部库：`libJuno.so`（EXIF/IPTC/缩略图/HLG，`jp.co.nikon.juno.JunoClass`）、`libNis.so`（`NkLNisApiAbstract.getKey/getIv/getEncryptedData/getEncryptedAppMemberTokenSecretKey` = 云端凭证加解密）、`libLsSec-jni.so`（BLE/配对安全）+ 私有云 `webclient/{nis,ncas,npns,nms,clm,ga}` | SO 导出符号、`com/nikon/snapbridge/cmru/**` 2985 个类、包树 `backend/{presentation,data,domain}`+`ptpclient/actions`(413 类)+`bleclient/characteristics` |
| **像素蛋糕 PixCake** | **是（唯一一个买第三方 SDK 的）** | **非尼康官方**，是商业聚合 SDK `com.truesight.camerawirelesscontrol`：六品牌 `Nikon/Sony/Canon/Fuji/Ricoh/Panasonic + TrueSight` 的 `CameraControl`/`DeviceDiscover` 类，native C++（asio+websocketpp+libcurl+libssh2+OpenSSL），传输含 PTP/IP、HTTP、WebSocket、SSDP/UPnP、SSH | `libcamerawirelesscontrol.so` 的 111 个 `NikonCameraControl::` 符号 + `M-SEARCH * HTTP/1.1` + `Java_..._NativeCameraControl_*` |
| **影犀 CameraSync Pro** | 否 | 自研 PTP/IP + USB 栈（Dart 层 + 原生 USB 插件），多品牌靠 mDNS 服务类型区分 | `YINGXI_dart.txt`：`Nikon_*` 46 个操作、9 种 mDNS 类型、`PtpIpClient`、`ptp_client_guid.bin` |
| **ZDROP** | 否 | 完全自研，零第三方网络库，单 dex 3265 类里只有 androidx/kotlin | `scan/ZDROP.txt`（`third_pkg`=0）、`PtpIpZ6Gateway`、`NsdManager` 原生 API |
| **ZRelay** | 否 | 自研（`libzrelay_core.so` 仅 236KB、只导出 `JNI_OnLoad`，不是协议栈；协议在 Kotlin 层） | SO 符号表 + `com/kw/ztransfer/NativePtpCore` |
| **N-Link（我们）** | 否 | 自研 PTP/IP + PTP-over-USB + SnapBridge 兼容 GATT | 全仓无第三方网络库 |

**结论一：尼康不给第三方移动端 SDK，所以 5 个竞品里有 4 个必须自研 —— 我们的技术路线不是「落后」，是唯一可行解。**
**结论二：唯一能「买」到的路是像像素蛋糕那样接第三方聚合 SDK（TrueSight）。** 值得评估其商务可行性（拿到六品牌 + 已调优的 native 栈），但要付代价：包体、许可费、闭源黑盒不可控、与我们「无云端依赖 + 小包体」卖点冲突。（→ 待确认事项 Q8）

### 2.2 尼康官方 App 揭示的「本机侧接口全貌」（我们应当对齐的能力面）

SnapBridge 反编译出的连接侧动词，就是尼康相机实际暴露给手机的能力清单（全部通过 **PTP 厂商扩展操作 + 属性** 完成，与我们同一层）：

- `GetWmaInfoAction` / `GetWmaSettingAction` / `SetWmaSettingAction` / `FactoryResetWmaSettingAction` —— 读写相机的 **WMA（无线移动适配器）设置**，字段含 `WpaPassphrase`、`WifiChannel`、`WifiAuth`、`WpsMode`、**SSID**（日志：`Execute SetWmaSettingAction SSID -> Parameter[0x%08X]`）。
- `changeCameraConnectionMode` / `getCameraConnectionMode` —— **让手机把相机切到目标连接模式**（官方 App 就是靠它免去用户在菜单里翻）。
- `WiFiStation{ActiveCameraInfo,RegisteredCamera}` + `WiFiStation{Progress,ErrorCode}` —— **相机作 STA** 的主机记忆与进度/错误体系；`CameraBtcCooperationModeSetting` —— **蓝牙经典与 WiFi 协同模式**。
- ⚠️ `WmuCameraOperationRepository`（旧 **WMU** HTTP 通道）在 SnapBridge 里仍存在，但**官方明确 WMU「Cannot be operated with a SnapBridge-compatible camera」**，支持列表止于 D610/D750/D7200/D5/Df/Nikon 1/Coolpix/WU-1a/b → **Z8/Z9/Z6III/f6 上没有 HTTP 兜底路，PTP/IP 是唯一路**。任何「走相机内置 Web Server」的想法在 Z 系上是死路，不要浪费工时。
- 发现：**Bonjour/mDNS `_ptp._tcp.`**（`startDiscoveryBonjourService`、`Bonjour{Pair,Connect}Task`、`BonjourConnectGuid`、`connectByWiFiBonjour`）→ **与我们用 `_ptp._tcp` 找相机是同一机制，官方背书。**
- 手机侧加入热点：**`WifiNetworkSpecifier` 与旧 `WifiConfiguration`/`addNetwork`/`removeNetwork` 双路径**（`addAccessPoint`/`updateAccessPoint`/`saveWiFiConfig`），并有**机型 quirk 表**：`isAddNullToEndSSIDCamera`（部分机身 SSID 结尾多一个 NUL）。
- 后台传输：`AutoTransferImage` / `CameraImageAutoTransfer{Setting,Status}` / `CameraAutoTransferStatus` / `PtpAutoTransferInstructionLssEvent` —— 自动传输是**独立子系统**（设置 + 状态 + 指令事件三件套），不是一个 bool。
- 我们已对齐的部分：`0xDE00` GATT 服务族、`0x2000/0x2002/...` 特征、PTP/IP 15740 + GUID 会话、`GetEventEx 0x90C7`、`DeviceReady 0x90C8`。**我们缺的部分见 §9.2（G2/G17/G18 等）。**

> **两个可以直接摘的现成功能（实测码表比对）**：`BleManager.kt:40-56` 声明了尼康完整特征表（`0x2001 POWER_CONTROL`、`0x2006 CURRENT_TIME`、`0x2007 LOCATION_INFORMATION`、`0x2009 LSS_FEATURE`、`0x200A CABLE_ATTACHMENT`、`0x200B LSS_SERIAL_NUMBER`），其中 **6 个声明后零使用**。而 ZRelay 的 dex 里出现 `0x2000/0x2002/0x2006/0x2007/0x2008` + 一个 `com.kw.ztransfer.gpspoc.service.BluetoothGpsRelayService` 类 → 强烈指向两件我们没做、尼康已给入口的事：
> - **`0x2006` 相机时间同步**（连接时一次性写手机时间；用户高频痛点，实现成本极低）；
> - **`0x2007` 位置信息写入 = 机内地理标记**（手机 GPS → BLE → 相机写进 NEF/JPEG，比事后用 App 打标记更专业，且完全不占 PTP/IP 通道）。
> 这两项列入 §9.2 G24/G25。

### 2.3 三通道各自「能拿到的最好接口」定性结论

| 通道 | 官方支持度 | 第三方可行路径 | 天花板由什么决定 |
|---|---|---|---|
| **WiFi AP** | 中（协议公开可逆向，官方 App 有实现可参考行为） | 相机 AP + PTP/IP 15740 + GUID 会话；BLE 只做加速 | **Android 平台**：specifier 请求、无网关网络被系统判定「坏」、DBS 并发能力 |
| **WiFi STA** | 中低（要求主机注册/配对门控，且**只允许 1 个 PTP/IP 客户端**） | 相机加入手机热点/路由器 + 主机注册 + mDNS 发现 | **相机端门控**（注册是否被认可）+ **路由器隔离** + 官方 App 抢占 |
| **USB 有线** | **最高**（PTP over USB 是 PIMA-15740 标准，机身无需网络门控、无配对槽冲突） | `UsbManager` 裸 bulk + 自研容器（我们已具备） | **OEM ROM**（OTG 开关、授权弹窗策略、内核 USB 栈），以及相机 USB 模式枚举 |

→ 由此得出本 PRD 的**通道优先级策略**：不稳定机型上，**USB 是确定性最高的兜底**（这也是我们相对 ZRelay/ZDROP 的结构性优势：官方 SnapBridge 根本没有 USB）；AP 是体验最好的主路径（协议最直、地址最确定）；STA 灵活但受相机门控与路由器环境影响最大，必须靠「正向确认 + 免手工配置」来补。

### 2.4 各厂商「移动端 SDK」可得性（多品牌路线图的前置事实）

| 品牌 | 官方移动 SDK？ | 实际可得 | 网络侧接口 | 对我们的意义 |
|---|---|---|---|---|
| **Nikon** | **无** | 仅有发给 tethering 授权方的**桌面** SDK（Adobe 社区证实 Lightroom 用的 Nikon SDK 曾缺 macOS 13 支持）；Nikon Imaging Cloud 是自家 App 的设置/配方服务，不是控制 API | **TCP 15740（PTP-IP）+ UDP 5353（DNS-SD）——Z9 手册原文写明** | 只能自研；协议本身是**公开标准 + 官方文档过的端口**，风险在固件漂移而非不可知 |
| Canon | 门控 | ED-SDK（**Win/mac 桌面**）+ **CCAPI（HTTP over IP，功能与 EDSDK 同级：设置/监看/触发/取片）**；均需企业申请、按区域门户 | PTP/IP TCP 15740 + **SSDP/UPnP `239.255.255.250:1900`** 发现；PTP 文档「可来函索取」 | 多品牌第二站可走 CCAPI 申请；否则仍是逆向 |
| Sony | **不支持 Android** | Camera Remote SDK 仅 host PC（Win/mac/Linux x86/arm）；老机身 Camera API 2.0 为社区文档化的 **HTTP/JSON on TCP 8080**（`/sony/…`，SSDP `ScalarWebAPI:1` + mDNS） | 同左 | 若做 Sony，走社区文档化的 8080 HTTP，而非官方 SDK |
| **Fujifilm** | **有，且含 Android** | X/GFX **Digital Camera Control SDK**（Win/mac/Linux/**Android 11–15**），但 **USB only**；个人使用**会使机身保修失效**（官方明示） | 网络为相机↔电脑 TCP/IP；XApp 手机协议未公开（社区逆向） | 唯一「官方 Android 通道」案例，但代价条款要在商务评估里写明 |
| Panasonic / OM System | 无 | LUMIX Sync / OI.Share | 机身 **HTTP JSON on TCP 80** + mDNS `_lumix._http._tcp`；**首次连接需机身确认**，非认可客户端被回 `err-unsuitable-app` | 说明「相机端认客户端身份」是行业普遍行为，不只是尼康的注册门 |
| Sigma | 无（Windows，fp，USB） | SIGMA Camera Control SDK | — | 忽略 |
| **Blackmagic** | **最开放** | 官方 Camera Control Protocol PDF + 社区机器可读版；BLE service `291d567a-6d75-11e6-8b77-86f30ca893d3` | BLE / USB-C 串口 / 以太网 | 若扩品牌，BM 是唯一「官方文档 + 移动可控」的样板 |
| GoPro | 官方开放文档 | Open GoPro：HTTP on **TCP 8080**，SoftAP `10.5.5.9:8080`，USB RNDIS `172.2x.1y z.51:8080`（由序列号推导），mDNS **`_gopro-web`**，BLE Control&Query 做配对/下发，RTSP 拉流 | 同左 | **值得抄的三通道范式**：BLE 配网 + SoftAP/RNDIS 双形态 + mDNS + HTTP |
| DJI / Insta360 | 有但是申请制 | DJI Mobile SDK（注册 + 每 App key）；Insta360 Android SDK（在线申请/企业门户） | 私有协议 | 需商务准入，不是技术选型问题 |

**结论**：做 Nikon 之外的品牌时，只有富士（USB）与 Blackmagic/GoPro（文档开放）存在「官方路」；其余仍是标准 + 观察扩展。→ **多品牌扩张的真实成本是「协议逆向 + 兼容矩阵运营」，不是接 SDK**，这反过来印证 §6.2（把兼容矩阵当资产）才是长期护城河，也解释了 TrueSight 这类聚合 SDK 的商业空间。

### 2.5 我们实际站在哪个标准上（对外说明与内部约束的定位声明）

1. **PTP**：PIMA 15740 → **ISO 15740**；传输标准化为两条 —— USB（Still Image Device Class bulk 端点）与 **CIPA DC-005-2005 PTP-IP**（2005 发布、2019 复核，FotoNation 提案，尼康当年与之联合发布）。
2. **PTP-IP 规范定义 3 条并行 TCP 通道：command / data / event**，4 字节 PTP/IP 头 + Init/PairInfo 握手，容器与 USB 相同。→ **我们目前只开 2 条 socket、把对象数据塞在命令通道上，是对标准的欠实现**（ZDROP 的「双通道」、TrueSight 的 `ControlChannel/DataChannel/EventChannel` 都补齐了这一层）。列入 §10.3。
3. **端口与发现有官方背书**：尼康 Z9 手册直接写明 **TCP 15740 + UDP 5353**。这是我们对外解释「为什么是这个端口/为什么要做 mDNS」的权威依据。
4. **厂商扩展走 registry 而非猜测**：live view、AF 区域、包围、卡/对象语义等差异都属 PTP Vendor Extension（imaging.org 注册）→ **设计上必须以「能力枚举 + 缓存」为主（`GetDeviceInfo` → 操作/属性列表 → 机身 profile），硬编码表只做例外**。这既是 §8.2 的规范依据，也是对「ZTransfer 说网上流传的操作码全是错的」的正确回应。
5. **Android 侧没有可用的公开 PTP 主机 API**：`android.mtp.MtpDevice`（API 12）只能取对象/缩略图/删对象，**不能发任意命令、不能 `SetDevicePropValue`** → 遥控与监看必然走 `UsbManager` + `claimInterface` + `bulkTransfer/UsbRequest` 自研。UVC/`UsbCamera` 是「相机当摄像头」，不适用。**这一条要写进架构说明，防止后来者重复尝试 MTP API。**
6. **组播/发现**：DNS-SD(mDNS UDP 5353) 与 SSDP(UDP 1900 / 组播 `239.255.255.250`) 都需要 **`MulticastLock`**（`CHANGE_WIFI_MULTICAST_STATE`）；组播锁费电、必须引用计数并释放；`NsdManager.discoverServices` 同样需要。
7. **为什么没有 Wi-Fi Direct、为什么 BLE 只能当辅助通道**：相机固件普遍只做 AP/STA/WPS（P2P group owner 栈太重、且厂商不愿在局域网开控制面）；GATT 带宽扛不动监看与大文件。**因此 BLE 在本项目中的定位固定为：握手 / 唤醒 / 状态 / 保活 / 凭据下发 —— 永不承载图像数据。**
8. **合规与表述**：不内建任何厂商 SDK、不在产品名与应用内图形中使用尼康/机型商标；对外声明用「兼容 Nikon Z 系列」并在关于页加与 ZRelay 同级的免责声明（`尼康及相关型号名称归其权利人所有，相关名称仅用于说明设备兼容性` —— 直接借鉴其表述）；把「主机注册/信任」这类**未被任何文档描述**的行为在项目内明确标注为「实测观察」，不得写成厂商规范。

---

## 三、开源先例：逐条「偷什么」

只列与本项目缺陷一一对应的项。★=本次必做，☆=路线图做。

| 仓库 | 语言/活跃度 | 可移植的具体做法 | 对应我们的问题 |
|---|---|---|---|
| [RealCaCl2/ZTransfer](https://github.com/RealCaCl2/ZTransfer) | Dart+Kotlin，2026-08 活跃 | `NetworkRouteSelector`：**绝不把目标地址回落到任意候选网络**（`RouteInfo.matches()` 对默认 `0.0.0.0/0` 返回 true，会绑到家里 WiFi 导致相机超时）；`LocalAddressSelector`：部分 OEM 热点接口暴露为 **/32 主机前缀**，需按最大前缀匹配再回落 `/24`；`rememberDiscoveredRoute(target, localIp)`：**用探测成功的那个源地址发起正式 socket** | 热点/双卡机型下 `no_wifi_network`、绑错网络 |
| [subhashraveendran/aero-shutter](https://github.com/subhashraveendran/aero-shutter) | Go | **每机型 Profile**：`{defaultIp, partialObjectSupport, thumbnailStrategy, chunkSize}` + 一个「保守通用 PTP/IP Profile」兜底；**并发**解析候选（网关 + 出厂默认 + 已存 + 子网扫描，谁先应答用谁）；**伪装成尼康官方 App 的 friendly name**，相机当作「已配对主机」不再弹确认 | 硬编码 `192.168.1.1` 单点、串行候选、每次都要在相机上按 OK |
| [mvpzac/Imagededge](https://github.com/mvpzac/Imagededge) `CameraWifiManager.kt`/`PtpChannel.kt` | Kotlin，2026-09 活跃 | ① 用**带超时的三参重载** `requestNetwork(req, cb, 15_000)`（无超时版本在密码错/AP 不在时**永久挂起**）；② `lastReleaseAt` + **释放后 ≥800–1500ms 才允许再请求同一 AP**（框架还在拆旧网络时立刻重请求会秒回 `onUnavailable`——即「断开后扫第二次就连不上」的根因）；③ `onAvailable` 在 binder 线程且 Network 可能已回收 → `bindProcessToNetwork` 必须 `runCatching`；④ `ptpCall(timeoutMs, selfHeal)`：超时**强制关 socket** 自愈，但**心跳调用传 `selfHeal=false`**（否则心跳超时会砍掉正在传的 25MB 下载）；⑤ `silence(6000)`：拍完照/录完像**静默 6s 不轮询**，让相机写卡 | 我们用的是 `withTimeoutOrNull` 包协程（放弃等待但**系统请求仍挂着**→ 泄漏 callback）；心跳在下载期判死杀会话（v2.0.2 §二-2 已诊断未修） |
| [KonradIT/osmosis](https://github.com/KonradIT/osmosis) `ApJoiner.kt` | Kotlin，2026-09 活跃 | 记录在案的事后复盘：**`WifiNetworkSpecifier` 请求不会自动重连**，`onLost` 后进程仍绑在死网络上、所有 socket `ENONET` → 必须 `unregisterNetworkCallback` → 重新 `requestNetwork`，且**重试策略归调用方**；`onUnavailable()` 是「密码错 / AP 不在 / 用户取消」三合一的模糊信号，要拆成三条分支不同文案；WPA2/WPA3 必须是**显式字段**不能猜 | AP 掉线后不自愈；失败文案笼统 |
| [LAJENUNESSE/IkunBridge](https://github.com/LAJENUNESSE/IkunBridge)（Nikon **Z30** PTP/IP，Kotlin）+ ZTransfer `PtpIpHandshakePolicy` | Kotlin | `InitCommandAck` 布局（两家独立验证）：`connectionNumber=u32@0`、`cameraGUID=bytes[4..20]`、`cameraName=UTF-16LE NUL-terminated @20`；`initiatorName.length ∈ 1..39` 是硬前置；**若在 `InitCommandAck` 之前收到 Event 包(type 8) ⇒ 说明新 socket 上挂着上一个会话 ⇒ 关掉换新 socket + 线性退避**；`connectionGeneration` 计数防止旧 eventLoop 踩新会话；逐 socket `Network.bindSocket` 优于进程级；**断开时 `withTimeoutOrNull(2000){ CloseSession }`（尽力而为，不能阻塞拆除）** | 我们的 `WifiDirectConnector` 已有 generation，但「先收到 Event」这一 stale-session 分支未处理 |
| [gphoto/libgphoto2](https://github.com/gphoto/libgphoto2) `camlibs/ptp2/` | C，20 年积累 | ① `PTPIP.TXT`：PTP/IP **有 3 条逻辑连接 control/data/event**（type 1–14，13=Ping/14=Pong），我们只开 2 条 socket、把数据塞在命令通道上；② `ptp_ptpip_check_event()` **在数据阶段循环内抽干事件**（多数客户端在这里死锁）；③ event 通道连接遇 `ECONNREFUSED` **重试 2 次 ×100ms**（Ricoh 实机「相机没立刻就绪」，Nikon 同理）；④ `device-flags.h`/`ptp-bugs.h`：**每台机身一组位掩码 quirks**（`NIKON_BROKEN_CAPTURE`、`NO_CAPTURE_COMPLETE`、`DONT_CLOSE_SESSION`、`IGNORE_HEADER_ERRORS`…）；⑤ `cameras/*.txt` 里有 **`nikon-z30/z5/z6/z6-2/z6-iii/z7/z9/zf` 的真机属性 dump** | 我们没有「机身 quirks 表」，全靠运行时试探；下载期事件不抽干（相册/相机加片事件在长下载中丢失） |
| [pulsar-trigger/pulsar-trigger](https://github.com/pulsar-trigger/pulsar-trigger) `PtpWire` | Kotlin | **`PtpWire` 接口即抽象终点**：「一次 PTP 事务在所有传输上形状相同（请求→可选数据阶段→带码+≤5 参数响应），只有封装不同」。USB=12 字节容器，PTP/IP=容器外再套 `length(4)+ptype(4)`；`transactStream(op,params,sink,onProgress)` 直写 `OutputStream`；`MAX_PTP_DATA_BYTES` 防「坏长度头 → 申请 4GB」；`PtpProtocolException(stage, msg)` **每个网络层失败都带协议阶段** | 我们 `PtpSessionManager`(WiFi) 与 `UsbPtpManager` **两套平行实现、两份事务层代码**，是稳定性成本的主要来源 |
| [argallo/sony-camera-android](https://github.com/argallo/sony-camera-android) | Kotlin | ① `RECONNECT_GRACE_MS`：`USB_DEVICE_DETACHED` **不立刻判死**，进宽限窗轮询（线松/ hub 复位/相机自动睡眠唤醒都会瞬断）；② **所有已 claim 的资源放在局部变量里直到握手成功才提交字段**，避免中途抛异常泄漏 claim（之后每次 `claimInterface` 都 EBUSY）；③ Android 14 FGS 正确姿势：**sticky 重启且当前无设备时不要 `startForeground`（抛 SecurityException）并返回 `START_NOT_STICKY`** | 我们 `openConnection` 每次都先 `disconnect(silent=true)` 绕开泄漏（治标）；`BootReceiver` 起 FGS 无设备态风险未验证 |
| [devcxl/PhotoSync](https://github.com/devcxl/PhotoSync) | Kotlin | sealed `UsbPtpConnectionState{Idle\|PermissionRequested\|Connecting\|Connected\|Disconnected(reason)\|Error(reason)}` + **字符串 reason code**；`pendingPermissionDeviceId` 用**设备身份键**关联异步授权回调（授权在 detach 之后到达要按 id 丢弃）；`device.serialNumber` 必须 `try/catch(SecurityException)` → `"no-permission-${vid}:${pid}"`（部分 OEM 无权限时直接抛） | 我们 USB 无独立状态机（FSM 明确排除 USB）；未见 serialNumber 兜底 |
| [urbandroid-team/dont-kill-my-app](https://github.com/urbandroid-team/dont-kill-my-app) | 数据 | **有 JSON API**：`https://dontkillmyapp.com/api/v1/output.json`、`/api/v2/[vendor].json`，每条含 `{vendor, affected versions, severity, impact, tricks[]}`，trick 带 `intent`/`component` 深链目标 → 作为 asset 打包并驱动「去设置」按钮，**不要在代码里硬编码 MIUI Component** | 我们只有一个 Xiaomi 文案提示 |
| [android/betocq](https://github.com/android/betocq) | Google | 把「无线能力」当测试输入：`isDbsSupported`（**双频并发**：2GHz 基础网 + 5GHz 直连能否同时存在）——这正是「手机连着家里 WiFi 又去连相机 5GHz AP」成败的预测因子 | 我们没有 DBS 探测，band 策略是盲试 |

**必须自我修正的一条**：ZTransfer 明确写道「网上流传的尼康厂商操作码（`0x9010, 0x9435, 0x935A, 0x952B, 0x9431, 0x9400`）**全是错的**」。我们 `PtpProtocol.kt` 里的 STA 主机注册三件套（`0x952B PrepareHost` / `0x935A ConfirmHost`）标注来源是「ZDROP 1.0.190 反汇编实锤」，属于**从竞品二进制里读出来的常量**，可用但不可解释。→ 本次必须做一次**抓包定标**（第十二节 V-1），并把这类常量收进「按机身 ID 生效的 quirks 表」而不是全局常量。

---

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
7. **无 SSID 规则匹配**：不判断「手机当前连的是不是相机热点」（正则应为 `(?i)^NIKON_([0-9A-F]{8})`，ZDROP 已验证），也没有「手机自己热点 vs 相机 AP」的判别。
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
- 日志仅本地（3×512KB ring + 导出），无上报、无聚合 → 竞品 ZRelay 已有 `/v1/events/batch`、`/v1/capability-reports`、`/v1/camera-rules`（**服务端下发兼容规则**）。
- 测试只覆盖纯函数（`WifiEndpointTest`/`PtpProtocolTest`/`PtpSessionManagerTest`/`NikonBlowfishTest`）；**`WifiDirectConnector`、`ConnectionManager`、`StaNetworkRequester`、`LocalNetworkInterfaceResolver`、`UsbPtpManager` 零测试**——正是并发/重试/退避所在处。
- `HeartbeatTimeout` 事件与 `HEARTBEAT_TIMEOUT_MS=10000` 声明后无人派发（死代码）。

---

## 五、三种连接方式的最优解（本 PRD 核心）

> 统一原则：**能力探测优先于经验常量**；**每条通道都要能在「无 BLE」时工作**（新固件 LsSec 加密、部分机身 BLE 配对失败）；**每个失败都必须落到「阶段 + 原因码 + 用户下一步动作」三元组**。

### 5.1 WiFi AP（手机加入相机热点）——最优解

**结论：以「WLAN 已连 + 网关学习」为主干，BLE 凭据只做加速器，不做前提。** 这也是 ZDROP（无蓝牙权限）能做好的原因，同时规避 LsSec 不可解密的问题。

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
  - C2 与手机自身 linkAddress 相同 → 丢弃（ZDROP: "AP gateway ignored because it matches handset address"）
  - C2 连续两次读取一致才认定「stabilized」（否则进入 "AP gateway changed during recovery"）
  - 就绪等待：join 后先等 1200ms 再取 gateway（ZDROP "AP network readiness wait=1200ms"）
  - 169.254/224/240/127 继续排除（WifiEndpoint 现有逻辑保留）
  - 学习结果写回 `wifi:host:port`，下次跳过 C5
```
影响文件：新增 `wifi_ap/ApGatewayResolver.kt`，`ConnectionManager.resolvePtpTarget()` 接入，**并解除「只有 BLE 路径才能 establishPtpSession」的前提**（把 `establishPtpSession` 的触发点从 `wifi_ap` 状态改为「任一候选地址通过探测」）。

**T-A3 无 BLE 也能用（把 AP 从「BLE 依赖」解耦）**
- 进入 AP 页时读 `WifiManager.connectionInfo.ssid`，命中 `(?i)^NIKON[ _-]?([0-9A-F]{4,8})$` 或 `Nikon-*` → 判定「手机已在相机热点」，直接跳到 T-A2 学习 + 连接，**不再要求 BLE 配对**。
- 未命中 → 展示：① 「打开 WLAN 设置」深链（`Settings.Panel.ACTION_WIFI` / `ACTION_WIFI_SETTINGS`）② 相机端菜单指引（按机身型号出不同文案，抄 ZRelay 的「不同 Z 系机型菜单不同」策略）③ 手动 IP 输入（保留）。

**T-A4 双频并发（DBS）探测，把硬件限制说清楚**
- 启动时探测 `WifiManager.isStaConcurrencyForLocalOnlyConnectionsSupported()`（33+）/ `is5GHzBandSupported` / `isBridgedApConcurrentSupported` 等，产出一行「本机能力」进诊断面板。
- 若不支持并发且手机正连着 2.4GHz 基础网 → **主动提示「相机 5GHz 热点会挤掉现有 WiFi，建议先关闭家里 WiFi 或用 USB/STA」**，而不是让用户以为 App 坏了。当前 band 5GHz→auto 盲试（`wifi-ap/WifiManager.kt:101-168`）保留，但结果要按机型记忆。

**T-A5 防系统把 AP 拆掉**
- 诊断项：读 `Settings.Global` 的 `network_avoid_bad_wifi`、`captive_portal_mode`（无需权限）；检测到「避开坏网络」开启 → 首次引导页给出逐 ROM 关闭路径（HyperOS WLAN+/智能切换、Samsung 智能 Wi‑Fi、EMUI WLAN+）。
- 锁：WifiLock(FULL_HIGH_PERF)+MulticastLock+PARTIAL_WAKE_LOCK 全部引用计数（现状保留）；监看期加 `FLAG_KEEP_SCREEN_ON`，**不承诺息屏后台不断**。

**T-A6 会话唯一性**
- 尼康机身同时只允许 **1 个 PTP/IP 客户端**。新增显式状态 `CAMERA_BUSY_BY_OTHER`（映射 `InitFail` 与 `RC_AccessDenied 0x200F` / `PTP_GUID_REJECTED` 类返回），文案直指「请关闭 SnapBridge 或另一台手机上的连接」。
- 客户端名（`clientName`）现用 `Build.MODEL`；增加「使用官方 App 兼容名」的实验开关（V-2 验证是否能免弹确认）。

**AP 通道参数表（现值 → 建议值）**

| 参数 | 现值 | 建议 | 依据 |
|---|---|---|---|
| specifier 请求超时 | 15000（协程包） | 15000（**系统三参重载**） | Imagededge |
| 同一 AP 再请求最小间隔 | 无 | 1500ms | Imagededge `lastReleaseAt` |
| join 后取 gateway 前等待 | 无 | 1200ms（可配） | ZDROP |
| gateway 稳定化次数 | 无 | 2 次一致 | ZDROP `stabilized` |
| 全局尝试预算 | 无（FSM 无限） | ≤3 次 join / 60s 窗口，之后硬停并引导「忽略此网络」 | AOSP 黑名单机制 |
| event 通道建立后沉降 | 无 | 650ms | ZDROP `eventSettle=650ms`；gphoto2 ECONNREFUSED×2/100ms |
| 心跳在下载期 | 不豁免 | 豁免 + `selfHeal=false` | Imagededge + v2.0.2 §二-2 |

### 5.2 WiFi STA（相机连手机热点 / 同路由器）——最优解

**T-S1 热点状态改由官方回调驱动**
- 注册 `TetheringManager.registerTetheringEventCallback`（Android 11+ public）→ `onTetheringChanged(TetheringInterface)` 给出 `getNetwork()` / `getIfname()` / `getMode()`。
- 用该 `Network` 做**逐 socket `bindSocket`**（替代 `LocalNetworkInterfaceResolver` 的内核枚举兜底；后者保留为 API<30 与回调缺失时的回落）。这是把「手机自己热点」从不可见变为**可绑定**的正解，且不违反系统限制（`startTethering` 需要 `TETHER_PRIVILEGED`，第三方**不能**开关热点或指定信道 —— 这条要写进设计，避免有人再去尝试）。
- 用户操作仍是唯一入口：`Settings.ACTION_WIRELESS_SETTINGS` / 热点面板深链 + 「保持热点开启」提示。

**T-S2 正向确认「相机真的进来了」**
- 轮询热点 `Network` 的邻居/ARP（现状 `/proc/net/arp`）+ 对候选 IP 做 **TCP-only** connect（不发 Init），最多 12s；超时按 ZDROP 的四档文案给出：接口未出现 / 无可用 IP / 本地网络未建立 / 路由未就绪，**并且不向相机发起 PTP/IP 请求**（避免污染配对槽）。
- 新增 reason code `HOTSPOT_NO_CLIENT`、`HOTSPOT_ROUTE_NOT_READY`、`HOTSPOT_LINKLOCAL`（169.254 已具备判定，纳入码表）。

**T-S3 发现阶段改为「TCP 快筛 + 单点握手」**
- `/24` 扫描：并发 32 → 保持；**每主机超时 800ms → 500ms，且只做 `Socket.connect()`**；仅对首个 TCP 成功者做 `Init` 确认。预期整轮从 ~18s 降到 ~4–6s（ZTransfer 实测 10 倍差）。
- mDNS/NSD 服务类型补齐：`_ptp._tcp`、`_nikon._tcp`、`_nikon-ptp`、`_nikon-pc`（影犀表），并对「相机 IP 非网关」的情况保留 `.200+` 这类地址（**基础设施模式下相机不一定占网关**）。
- 路由器隔离独立分支：TCP 超时且同网段其他主机可达 → `ROUTER_CLIENT_ISOLATION`，文案「请关闭路由器 AP/客户端隔离或改用相机热点/USB」。

**T-S4 主机注册做成可验证的一等公民**
- 现状：`PrepareHost 0x952B` → 固定 8500ms → `ConfirmHost 0x935A(0x2001)`，且 `sta_host_registered` 只是一个布尔。
- 改为：注册后**立刻用 STA 目标地址做一次 PtpIpProbe + Init 验证**，成功才置位；把「注册于哪台机身、哪个固件、何时」入库；提供「重新注册」入口与「注册未生效」专属错误码。沉降时间进 quirks 表按机身取值（V-3）。

**T-S5 让 STA 免手工：把热点凭据写进相机（P1，收益最大的体验升级）**
- 官方 SnapBridge 就是在会话内用 **`GetWmaSettingAction` / `SetWmaSettingAction`（含 WpaPassphrase / WifiChannel / WifiAuth / WpsMode 字段）+ `changeCameraConnectionMode`** 来配置相机侧 WiFi（证据见 §1.3）。
- 我们的可落地版本：在 **AP 会话（或 USB 会话）内**读相机 WMA 能力 → 写入用户热点的 SSID/密码/认证 → 切连接模式 → 相机自己去加入热点 → 端侧用 T-S2 确认。
- 具体操作码/参数块**必须实机逆向或抓包确认**（V-3），并放入按机身生效的能力表；任一环节不支持则回落到「相机菜单手动输入」教程（ZRelay 式分机型菜单文案）。
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
- 相机枚举成非 PTP（存储/充电模式）→ 检测 class≠`StillImage(6)` 时给「在相机菜单把 USB 连接改为 PTP/Mass 之外…」的分机型指引；TrueSight SDK 有 `changeToPtpMtpMode`（软件切模式）作为**长期研究项**（V-5）。
- attach/detach 风暴（30s 内同 VID/PID >3 次）→ 判定供电/线材问题，提示外接电源（EH-71P 类）+ 换数据线 + Android 16「USB 保护（锁屏禁数据）」指引。

**T-U3 资源与事务安全**
- **提交式打开**：`UsbDeviceConnection`/`interface`/endpoints 全部先存局部变量，握手成功后才写入字段；任何异常路径 `releaseInterface + close()`（消除「一次失败 → 后续永久 EBUSY」）。
- `claimInterface` 阶梯：`(false)` → `(true)` → 引导「关闭图库/文件管理器等占用相机的应用」。
- 事务锁改 `Mutex`（可取消），**禁止 `@Synchronized`**（monitor 等待不可取消、不遵守 soTimeout）；超时后：业务调用 → 强制关连接自愈；心跳 → 只记录不自愈。
- `MAX_PAYLOAD_SIZE=50MB` 保留并统一为 `MAX_PTP_DATA_BYTES`，所有长度头读取处先校验再分配（防坏头 OOM）。
- 保留纯 JVM `bulkTransfer`（**不引入 libgphoto2/libusb native**：LGPL/GPL + fd 模型冲突 + Android 15/16 的 16KB page size 合规成本，见 §10.4）。可选 P2：`UsbRequest.queue/requestWait` 异步环提升吞吐（须先解决 16KB 对齐与取消语义）。

**T-U4 大文件与 >2GB**
- offset 全链路 `Long`；优先 `GetPartialObject64`/`GetObjectInfoEx`（影犀已证可用），不支持时按机身 quirks 回退整对象 `GetObject`。
- 自适应分片：首段实测吞吐过低 → 自动缩小分片（ZDROP「首段异常慢速，改用小范围重试」），分片在 1–4MB 之间按 p50 调整。
- 数据阶段循环内**抽干 event**（gphoto2 `ptp_ptpip_check_event` 模式），保证下载中「新照片/删除/状态」事件不丢。

**T-U5 USB 独占与监看互斥**
- 显式 `BUSY(reason)` 状态 + 文案（影犀：「监看中，请先停止监看后再访问相机文件」），不再靠静默排队；
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

### 6.2 ROM 兼容矩阵作为**产品资产**（对标 ZRelay `/v1/camera-rules`）

1. **数据驱动，不硬编码**：把矩阵做成 asset JSON（`assets/compat/rom_rules.json` + `camera_rules.json`），条目结构：
   `{match:{manufacturer,brand,romProp,apiMin,apiMax}, impact, severity, tricks:[{id,text,intent,component,fallbackUrl,lastVerified}], cameraRules:[{modelRegex, defaultIp, wmaWritable, hostRegSettleMs, partialObject64, lvVariant, ssidRegex, quirks:[]}]}`
2. **来源**：OEM 侧直接采用 `https://dontkillmyapp.com/api/v1/output.json` 的离线快照（含 `intent`/`component`），随版更新；相机侧种子用 **libgphoto2 `camlibs/ptp2/cameras/nikon-z*.txt` + `device-flags.h`** 的真人 dump（`NIKON_BROKEN_CAPTURE`、`NO_CAPTURE_COMPLETE`、`DONT_CLOSE_SESSION`、`IGNORE_HEADER_ERRORS` 等），加上我们自己真机标定。
3. **深链实现**：用 `autostarter` 式封装（`resolveActivity()` + try/catch + `ACTION_APPLICATION_DETAILS_SETTINGS` 兜底），逐 ROM 已知组件：`com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity`、`com.coloros.safecenter/.permission.startup.StartupAppListActivity`、`com.vivo.permissionmanager/.activity.BgStartUpManagerActivity`、`com.huawei.systemmanager/.optimize.process.ProtectActivity`。**每条带 `lastVerified`，OTA 后失效要能单独降级为文案指引。**
4. **热更新**（P1）：矩阵支持从我们自己的静态 JSON（百度盘/GitHub raw）拉取覆盖，**不发版修兼容** —— 这是 ZRelay 已经验证过的护城河。
5. **用户回报通道**（P1）：机型×ROM×通道×结果 的本地记录，导出脱敏 CSV；后续可选 opt-in 上报（对标 `/v1/capability-reports`）。

### 6.3 后台存活与 FGS 的正确姿势（Android 14→16）

- 会话期 FGS 用 **`connectedDevice`**（其前置条件我们满足：`CHANGE_WIFI_STATE`/`CHANGE_NETWORK_STATE` 或 `BLUETOOTH_CONNECT/SCAN` 或调用过 `UsbManager.requestPermission`）；**只有用户主动发起的批量下载才用 `dataSync`** —— `dataSync` 有 **6 小时/24 小时配额**（超了抛 `TimeException`），且 **Android 15 起 `BOOT_COMPLETED` 不允许启动 `dataSync`/`camera` FGS，但允许 `connectedDevice`** → `BootReceiver` 必须走 connectedDevice 分支（现状 `BootReceiver.kt:26` 有 `>= O` 死判断，minSdk 29）。
- **sticky 重启但当前无设备/无网络时不要 `startForeground`**（会 `SecurityException` 反复崩重启）→ 先判条件，不满足直接 `START_NOT_STICKY`。
- Android 16 起并发 FGS + Job 有共享配额，长下载要显式让位给交互。
- 不承诺「息屏后台不断」；监看期 `FLAG_KEEP_SCREEN_ON`；三把锁引用计数保留。

### 6.4 BLE 专项（ZRelay 路线的代价与收益）

- **绑定而不是每次重扫**：优先 `CompanionDeviceManager.associate()` 建立绑定关系（同时解决 12+ 免定位、扫描节流 5 次/30s、OEM 更严的后台扫描限制）；ZRelay 的 `ReconnectingAfterBond`、`stale_bond_remove_timeout`、`classic_bond_timeout_without_confirmation` 说明**绑定态失效**是他们专门建状态处理的问题——我们也必须有对应码（`BLE_STALE_BOND`、`BLE_BOND_NO_CONFIRM`）。
- `connect()` 前先 `BluetoothManager.getDevice`；`status 0x8/0x133` → `close()` + 新建 `BluetoothGatt`（老 GATT 句柄复用是 Android 通病）；MTU 一次协商 247–517；`CONNECTION_PRIORITY_HIGH` **只在凭据交换窗口**用，之后回 BALANCED（省电 + 避免栈异常）。
- LsSec 加密的 `0x2004`：不解密（法律与技术都不可行），改为**明确告知 + 走 T-A3 的无 BLE 主干**。这是 §5.1 结论的直接原因。

### 6.5 「首次连接准备」向导（竞品最重的一处投入）

ZRelay 用一整套中文文案覆盖每个 ROM 的电池/自启动路径（证据：`ZRELAY_cjk.txt` 里 12 条以上逐 ROM 指引），并把**相机端菜单差异**写进流程（`不同 Z 系列机型的"连接到计算机"菜单不同…是否存在"连接类型"`、`部分机型（如 Z9）必须先按 OK，手机才能继续连接`）。

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
- **导出必须显式脱敏并在 UI 声明**（照抄 ZDROP 的做法：「导出文件不含照片或 Wi-Fi 密码，可用于定位连接断开原因」）；
- 崩溃日志合并进同一导出包；可选附带 `bugreport` 选择器（支持工单用）。

### 7.3 内置「连接自检」（竞品已有，我们没有）

ZDROP 有协议诊断模式（`仅用于协议诊断。运行前会断开当前无线会话，完成后保持未连接状态`）。我们实现为设置页一个按钮，15–30 秒跑完：
`Preflight 全项 → 候选网络枚举 → join/复用 → gateway 学习 → TCP 探测 → PTP Init → OpenSession → DeviceInfo → 1 次属性读 → 1 张缩略图 → 1MB 速率采样` → 输出一张「本机 + 本相机」的能力与耗时报告，可分享。
**这也是 §6.2 矩阵的数据来源**（矩阵 → 指导用户；自检 → 产出矩阵条目）。

### 7.4 性能内建度量

在 App 内统计并按操作打点（ZDROP `STA thumbnail perf count=500 p50=` 的做法）：缩略图 p50/p95、单张 NEF 下载速率、监看实际帧率与端到端延迟、连接各阶段耗时。本地滚动统计 + 自检报告可见，不需要服务端也能驱动优化决策。

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
- 数据阶段循环内回调 `onEvent`（gphoto2 模式）→ 下载期不再丢事件。
- `transact` 的 `selfHeal` 语义：业务调用超时→关链路自愈；心跳/探测超时→只上报（防止心跳砍掉大文件）。
- 保留我们已有且正确的东西：`generation` token、`activeJob` 槽、容器重组、`TcpNoDelay`+4MB 接收缓冲。
- **收益**：USB 与 WiFi 的行为差异被收进 2 个类里；机型 quirks 只需在 `PtpWire` 之下生效一次；测试可用 `FakeWire` 覆盖全部重试/超时/并发路径（补 §4.6 的测试空洞）。

### 8.2 机身能力与 quirks 表（消灭经验常量的滥用）

`device/CameraProfile.kt`：`{modelRegex(抄 ZDROP 的 (?:NIKON\s*)?Z\s*(50|30|[5-9])\s*(III|II|3|2)?), productIds[], defaultIpCandidates[], ssidRegex, wmaWritable, hostRegSettleMs, partialObject64, lvVariant(EX|LEGACY), lvQualitySteps, thumbStrategy, chunkSize, quirks[FLAGS]}`
- 内置种子来自 libgphoto2 dump + 我们的真机标定；运行期「实测成功的值」写回本地覆盖表（学 `eventSettle=650ms`、`AP network readiness wait=1200ms` 这类可学习参数）。
- `PtpProtocol.kt` 中来源为「竞品反汇编」的常量（`0x952B/0x935A/0x9435/0x9431`）**迁入此表并标注 confidence + 来源**，不允许再散落硬编码。

### 8.3 会话对象与状态收敛

`ConnectionSession { channel, wire, cameraInfo, profile, networkHandle?, localIp?, guid, openedAt, health }` —— 取代目前散落在 `ConnectionManager` 的 `realCameraIp`/`apConnected`/`sta_host_registered`/`isConnected()` 等标志位；USB 并入同一状态机（§5.3 T-U1）。

### 8.4 明确不做

- ❌ 不引入 `libgphoto2`/`libusb` native（许可证 + fd 模型 + 16KB page size 三重成本，且我们纯 JVM 已解决容器问题）；
- ❌ 不做 OkHttp/Retrofit 化（我们根本不说 HTTP，裸 socket 是对的）；
- ❌ 不尝试 `TetheringManager.startTethering` / `WRITE_ACCESS_POINTS`（需 `TETHER_PRIVILEGED`，第三方不可得，只做观测）；
- ❌ 不破解 LsSec / 不还原 SnapBridge 加密；
- ❌ 不上线任何「未经真机验证」的厂商操作码（一律先 V-* 验证）。

---

## 九、与竞品的能力对比 & 差距清单

### 9.1 对比矩阵（✓ 有 / △ 部分 / ✗ 无 / ? 未知）

| 能力 | SnapBridge(官方) | ZDROP | ZRelay | 影犀 | 像素蛋糕 | **N-Link 现状** |
|---|---|---|---|---|---|---|
| USB 有线（PTP over bulk） | ✗ | ✓ | ✗ | ✓ | ✓ | **✓（我们的差异化资产）** |
| AP：BLE 凭据自动加入 | ✓ | ✗（无蓝牙） | ✓ | ? | ? | △（LsSec 加密时失效） |
| AP：**从网关/DHCP 学习相机地址** | ✓ | **✓（含稳定化+自地址排除）** | ✗（固定候选） | ? | ? | **✗** ← P0 |
| AP：无 BLE 也能用（已连 WLAN 即可） | ? | ✓ | ? | ? | ? | **✗** ← P0 |
| AP 掉线自愈（unregister→re-request） | ✓ | ✓ | ✓ | ? | ? | △（依赖 FSM 无限重试） |
| 热点：用 `TetheringEventCallback` 拿 Network | ? | ? | **✓** | ? | ? | **✗** ← P0 |
| 热点：**正向确认相机已加入/DHCP 分配** | ? | **✓（4 档文案）** | △ | ? | ? | ✗ |
| 发现：**TCP-only 快筛**（不占配对槽） | ? | ✓ | ✓ | ? | ? | **✗（每候选做握手）** ← P0 |
| mDNS 类型覆盖度 | `_ptp._tcp`(Bonjour) | NSD | ✗（不做组播） | **9 种类型** | SSDP/UPnP | △（2 种） |
| PTP/IP 通道模型 | ? | **双通道+失败回落标准通道** | ? | ? | **control/data/event 三通道** | △（命令+事件 2 socket，数据走命令通道） |
| event 通道沉降/重试参数化 | ? | **`eventSettle=650ms`** | ? | ? | ? | ✗ |
| 下载期抽干 event | ? | ? | ? | ? | ✓(loopThread) | **✗** |
| 下载期心跳豁免 | ? | ✓（推断） | ✓ | ✓ | ✓(waitBusy) | **✗（v2.0.2 已诊断未修）** |
| 续传 offset 64 位 / PartialObject64 | ? | ✓ | ? | **✓** | ✓ | **✗（Int，>2GB 溢出）** |
| 自适应分片大小 | ? | **✓（首段过慢→缩小重试）** | ? | ✓（分块校验） | ✓(chunk) | ✗（4MB→1MB 固定阶梯） |
| 监看分辨率/质量可调 | ✗ | ? | ? | **✓（Ex/Legacy 双路径）** | ✓ | ✗（固定 15fps 轮询） |
| 「相机忙/独占」显式状态 | ? | ✓ | ✓ | **✓（文案分级）** | ✓ | △（内部排队，无状态无文案） |
| 机内在相机端评级/删除 | ? | ? | ? | ✓ | **✓（rating/删除）** | ✗ |
| 镜头信息（maker note）解析 | ✓(Juno EXIF) | **✓** | ? | ? | ? | ✗ |
| 视频 moov 感知（边下边播） | ? | **✓** | ? | ? | ? | ✗ |
| 内置协议自检/诊断模式 | ? | **✓** | ✓(reconnectStatView) | ? | ✓(setLogInfo) | **✗** ← P1 |
| App 内 p50/p95 度量 | ? | **✓** | ✓ | ? | ? | ✗ |
| 逐 ROM 后台/电池引导 | 少 | 少 | **✓✓（最重投入）** | ✓ | ✗ | △（1 条 Xiaomi 文案） |
| **兼容规则服务端下发**（camera-rules） | ✓ | ✗ | **✓** | ? | ? | **✗** ← P1 |
| 日志脱敏声明 + 导出 | ✓ | **✓** | ✓ | ? | ? | △（有导出，无脱敏声明） |
| 多相机/多主机管理 | ✓ | ✓ | ✓ | ? | ? | △（Room 表，UI 弱） |
| 隐私承诺文案（不上传） | ✗ | **✓** | ✗ | ✗ | ✗ | ✗（未声明） |
| OTG 开关（小米系）检测与引导 | — | ? | ? | ? | ? | **✗（行业也少见 → 可成首发点）** |
| target 36（Android 16）合规 | 35 | 35 | **36** | **36** | 34 | 35 |

### 9.2 差距清单（P0=本次必做，P1=紧随其后，P2=路线图）

| # | 差距 | 级 | 影响 | 改动面 | 依据 |
|---|---|---|---|---|---|
| G1 | AP 无网关学习，硬编码 `192.168.1.1` | **P0** | AP 跨机型不稳的第一因 | 新 `ApGatewayResolver.kt` + `resolvePtpTarget` 接入（~200 行） | ZDROP；§5.1 T-A2 |
| G2 | AP 路径依赖 BLE `0x2004` 成功，否则地址分级死代码 | **P0** | 新固件/LsSec 机身上 AP 完全不可自动 | 改触发点 + `NOT_ON_CAMERA_AP` 判定 | §4.3-3 |
| G3 | 扫描期对每候选做 PTP Init（慢 + 占配对槽） | **P0** | STA 首连慢至 18s，且相机端状态被污染 | `WifiScanner` 两段化（~120 行） | ZTransfer/ZDROP；§5.2 T-S3 |
| G4 | 本机热点无 `TetheringEventCallback` 网络句柄 | **P0** | 只能进程级绑定/内核路由回落，双卡与 WLAN+ 机型不稳 | 新 `HotspotNetworkWatcher`（~120 行） | ZRelay；§5.2 T-S1。**实施受阻**：`android.net.TetheringManager` 不在 compileSdk 34/35 的公开 stub 里（`android-36/android.jar` 才收录），AGP 8.7.3 不能升 compileSdk 36 → 随 §10.4（targetSdk 36）一起做，本包不做反射 hack |
| G5 | `requestNetwork` 无超时重载 + callback 泄漏 + 无最小重请求间隔 | **P0** | 「第二次扫就连不上」；系统请求配额耗尽 | `wifi-ap/WifiManager` + `StaNetworkRequester`（~80 行） | Imagededge；§4.3-6 |
| G6 | 无全局尝试预算（FSM 无限重试 × 内层重试） | **P0** | 越试越黑（AOSP 网络禁用） | FSM 加预算 + Preflight 硬阻断不进循环 | §4.2 |
| G7 | 下载期心跳不豁免 + 数据期不抽 event | **P0** | 大文件/MOV 传输被自己判死；事件丢失 | `PtpSessionManager`（v2.0.2 §二-2 已定方案） | gphoto2/Imagededge |
| G8 | USB 不在 FSM、无 detach 宽限窗 | **P0** | 线松即断、无状态可视 | `UsbConnectionOrchestrator`（中等） | sony-camera-android/PhotoSync |
| G9 | 无 OTG 检测与引导（小米系） | **P0** | 小米 15 Pro 上「总线无相机」无法自助 | PreflightGate 一项 | HyperOS 10 分钟自动关 |
| G10 | 续传 offset `Int`，>2GB 失败 | **P0** | 4K 长片必现 | 全链路 `Long`（v2.0.2 §二-4） | 影犀 `PartialObject64` |
| G11 | 机型适配只有一个 Xiaomi 文案（无行为差异） | **P0** | 「不同机型不稳」在产品层面完全没被治理 | `PreflightGate` + `assets/compat/*.json` | ZRelay/dontkillmyapp |
| G12 | 失败码三套分散、无阶段漏斗 | **P0** | 无法定位机型差异，工单靠猜 | `ConnFunnel` + 码表 | §7.1 |
| G13 | 竞品反汇编来的操作码无常量化/无置信标注 | **P1** | 固件变更即失效，且无法解释 | `CameraProfile` 表 | §三 修正条 |
| G14 | 无内置自检 | **P1** | 用户无法自证环境问题 | 设置页一项 | ZDROP |
| G15 | 无 App 内性能度量 | **P1** | 优化无基线 | 统计模块 | ZDROP p50 |
| G16 | 监看固定 15fps 轮询、无质量阶梯 | **P1** | 2.4GHz/拥塞环境下卡顿；USB 带宽浪费 | `LiveViewManager` + 机身 profile | 影犀 LV Ex/Legacy + quality |
| G17 | 主机注册仅布尔、固定 8500ms | **P1** | STA 成功率的最大不确定项 | 注册后回验 + 按机身取值 | §5.2 T-S4 |
| G18 | STA 免手工（写相机 WMA）未实现 | **P1** | 用户仍要在相机菜单输热点密码（体验差 2 个量级） | 依赖 V-3 结论 | SnapBridge 证据 |
| G19 | 无会话级通道择优/回落 | **P2** | 不稳定机型不能自动选最优 | `ConnectionOrchestrator` | §5.4 |
| G20 | 事务层双实现（USB/WiFi 各一套） | **P2** | 每个修复要做两遍、测试覆盖不到 | `PtpWire` 重构（大） | pulsar-trigger |
| G21 | 兼容矩阵无热更新 | **P2** | 每次 OTA/新 ROM 都要发版 | 静态 JSON 拉取 | ZRelay `/v1/camera-rules` |
| G22 | 评级/镜头信息/moov 预览/多相机 UI 等功能差距 | **P2** | 专业向粘性 | 各自独立 | §1.5 |
| G23 | targetSdk 35（16 即将强制） | **P1** | 合规与商店更新受阻 | Gradle + 16KB 检查（纯 JVM 无 native 风险） | §10.4 |
| G24 | **BLE 相机时间同步**（`0x2006` 已声明、零使用） | **P1** | 用户高频痛点、实现成本极低、不占数据通道 | `BleManager` 一次写 + 连接后触发 | ZRelay 用 `0x2006`；§2.2 码表比对 |
| G25 | **BLE 位置写入 = 机内地理标记**（`0x2007` 已声明、零使用） | **P2** | 专业向差异化（NEF/JPEG 直接带 GPS，无需后处理） | `BleManager` + 定位策略 + 后台功耗评估 | ZRelay `BluetoothGpsRelayService` |
| G26 | `0x2001/0x2009/0x200A/0x200B` 四个已声明未使用特征 | P2 | 无（清理项，别当功能） | 删或标注 | 代码走查 |

---

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

> 本次会话 **无 ADB 设备**（`adb devices` 空），因此以下必须在接入 Z8 + 小米 15 Pro 后用 Mobile Use / 手动方式补齐。竞品运行时对比同样待做（ZDROP、ZRelay、影犀各跑 20 次三通道连接，记录 §11.1 指标）。

| # | 待验证 | 方法 | 影响哪个决策 |
|---|---|---|---|
| V-1 | `InitCommandReq` 精确字节布局（是否带尾部 `u32=65536`）、`0x952B/0x935A/0x9435/0x9431` 真实语义 | 抓包（另一台手机做中间人/或 root `tcpdump`）+ 与 SnapBridge/ZDROP 同机对照 | G13 机身表、STA 主机注册 |
| V-2 | 客户端 friendly name 是否影响相机「免弹确认」记忆 | 改 `clientName` 为官方风格名，观察配对提示 | T-A6 |
| V-3 | 相机 WMA 设置是否可被第三方写入（SSID/密码/信道/认证）+ 各机身沉降时间 | 依次试读/试写厂商操作，观察相机菜单变化 | T-S5（免手工）、G17 |
| V-4 | LsSec 加密 `0x2004` 的机身分布（Z8 是否已加密） | Z8/Z6II/Z30 各读一次 | G2、是否必须无 BLE 主干 |
| V-5 | 相机 USB 模式能否软件切换（TrueSight 有 `changeToPtpMtpMode`） | 描述符枚举 + 尝试厂商操作 | T-U2 |
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
| 从竞品反汇编得到的常量在新固件失效 | 中 | V-1 定标 + 机身表 + 置信标注 + 自检快速定位 |
| `PtpWire` 重构引入回归 | 中 | 放到 v2.4，且先有 `FakeWire` 测试矩阵再动 |
| 逐 ROM 深链被新版 Settings 收归（Android 15+ Restricted settings） | 中 | 矩阵带 `lastVerified`，失败降级为纯文案 + 手动路径截图 |
| 埋点/日志的隐私与商店合规 | 中 | 默认仅本地、导出脱敏并声明；上报改 opt-in 且后置到 v2.4 |
| 一次性铺开 23 项差距导致版本失稳 | 高 | 严格按 §10 切分，v2.2.0 只做 G1–G12，每项独立可回滚 |
| 无测试设备窗口（本机当前无 ADB 设备） | 高 | 接入设备后先跑 §10.5 基线，再动 §5 逻辑 |

### 14.2 待你确认（决定后我按此实施）

1. **v2.2.0 范围**：是否按 G1–G12（P0 全量，含 USB 与埋点）一次性做？还是先「AP 两招（G1+G2）+ 埋点」最小可发版？
2. **优先级**：小米 15 Pro + Z8 是**唯一复现环境**吗？能否再凑一台三星/一台 Pixel 级原生机做矩阵基线？
3. **STA 免手工（T-S5）**：愿意承担「逆向 WMA 写操作」的验证成本吗？不做的话退化为「USB 会话内完成主机注册」的折中方案。
4. **监看质量阶梯 / PartialObject64**：是否列入 v2.2.0（性能向，但会碰 `LiveViewManager`/`TransferManager` 主干）？
5. **埋点**：纯本地（学 ZDROP 的隐私声明作为卖点），还是预留 opt-in 上报（学 ZRelay 的 `capability-reports`）？
6. **版本号**：v2.2.0（versionCode 19）？
7. 是否要我把 §九 的 **P2 功能差距**（相机内评级、镜头信息、moov 边下边播、多相机 UI）另开一份功能 PRD？
8. **要不要评估「买 SDK」这条路**（§2.1 结论二）：像素蛋糕证明存在可采购的多品牌原生栈（TrueSight：六品牌 + control/data/event 三通道 + SSDP/WebSocket/SSH + 自适应传输）。接它可省掉 §八 的大部分重构，代价是许可费、包体、黑盒不可控、与「无云端依赖 + 3MB 包体」的卖点冲突。需要我去摸商务口径吗？
9. **多品牌是否在视野内**？影犀/像素蛋糕都已多品牌。按 §2.4，扩张的真实成本是「逆向 + 兼容矩阵运营」而非接 SDK；若 12 个月内不扩品牌，则 §6.2 的矩阵就按「手机 ROM × Nikon 机身」两维建，范围能小一半。
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
| G11 之 ROM/机身 asset JSON 与热更新 | ⏸ 仍推迟 | 现为代码内枚举 + `RomDetector.family`；`assets/compat/*.json` 与 `dontkillmyapp` 快照、`/v1/camera-rules` 式热更新留到 v2.2.1 |

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

## 附录 A：证据文件索引

| 文件 | 内容 |
|---|---|
| `_apk_re/badging/<App>.txt`、`<App>.manifest.txt` | aapt 权限 / uses-feature / 组件与 intent-filter |
| `_apk_re/scan/<App>.txt` | DEX+SO 字符串分类扫描（IP、URL、HTTP 路径、SSID、WiFi/USB/BT/NSD API、UUID、PTP、keepalive、中文文案） |
| `_apk_re/scan/<App>_cjk.txt` | 完整中文文案（ZDROP 572 / ZRelay 2633 / 影犀 71 条） |
| `_apk_re/scan/YINGXI_dart.txt` | Flutter `libapp.so` 里的 Dart 侧协议常量（9 种 mDNS 类型、46 个 `Nikon_*` 操作、STA 子状态） |
| `_apk_re/dex/classes_*.txt` | dexdump 类表（SnapBridge `com/nikon/**` 2985 个类；包树见正文） |
| `_apk_re/so/lib/cwc.str.txt` | 像素蛋糕第三方 SDK（51966 行符号/字符串：`NikonCameraControl::` 等） |
| `_apk_re/so/lib/zrelay.str.txt` | ZRelay native（仅 `JNI_OnLoad`，非协议栈） |
| `_apk_re/dexscan.py`、`_apk_re/cjk.py` | 可复现扫描脚本（换 APK 即可复用） |

## 附录 B：主要参考

- 尼康官方帮助：[SnapBridge 连接问题](https://nikonimglib.com/snbr/onlinehelp/en/connection_issues_48.html) · [WT-4SU/无线配对帮助](https://nikonimglib.com/wt4su/onlinehelp/en/02_pairing_01.html)（ZRelay 直接引用了它） · [Z8 无线 LAN 手册](https://onlinemanual.nikonimglib.com/z8/en/computers_connecting_via_wireless_lan_90.html) · [DPReview：Z8 + SnapBridge WiFi 掉线](https://www.dpreview.com/forums/threads/z8-and-snapbridge-wifi-connection-dropping.4748464/)
- 平台文档：[Wi-Fi Network Request API](https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap) · [Wi-Fi suggestion](https://developer.android.com/develop/connectivity/wifi/wifi-suggest) · [WiFi 扫描权限](https://developer.android.com/develop/connectivity/wifi/wifi-scan) · [AOSP mDNS/.local](https://source.android.com/docs/core/ota/modular-system/dns-resolver#mdns-local-resolution) · [TetheringManager](https://developer.android.com/reference/android/net/TetheringManager) · [Soft AP](https://source.android.com/docs/core/connect/wifi-softap) · [USB host](https://developer.android.com/develop/connectivity/usb/host) · [`UsbDeviceConnection.claimInterface`（force 的真实语义）](https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection) · [`android.mtp.MtpDevice`（只能传对象，不能发命令）](https://developer.android.com/reference/android/mtp/MtpDevice) · [`MulticastLock`](https://developer.android.com/reference/android/net/wifi/WifiManager.MulticastLock) · [NSD](https://developer.android.com/develop/connectivity/nsd) · [FGS 类型](https://developer.android.com/develop/background-work/services/fg-service-types) · [16KB page size](https://developer.android.com/guide/practices/page-sizes) · [Android 15/16 行为变更](https://developer.android.com/about/versions/15/behavior-changes-15) · [Android 16 USB 保护](https://support.google.com/android/answer/16778864) · [小米 OTG 自动关闭](https://www.mi.com/global/support/article/KA-07213/)
- 标准与协议：[imaging.org PTP 标准页（PIMA 15740 → ISO 15740 + 传输 + 厂商扩展注册表）](https://www.imaging.org/IST/IST/Standards/PTP_Standards.aspx) · [CIPA 标准清单（DC-005-2005 PTP-IP）](https://www.cipa.jp/e/std/std-sec.html) · [Picture Transfer Protocol（维基百科，PTP/IP 谱系）](https://en.wikipedia.org/wiki/Picture_Transfer_Protocol) · [Bigioi 等：PTP/IP 传输规范论文](https://researchrepository.universityofgalway.ie/bitstreams/f04d6561-bc24-46d7-b111-db5efe98290a/download) · [尼康 Z 系无线连接方案总览](https://www.nikonusa.com/learn-and-explore/c/tips-and-techniques/z-series-wireless-connectivity-options) · [Z9 手册：连接电脑/FTP（明示 TCP 15740 与 UDP 5353）](https://onlinemanual.nikonimglib.com/z9/en/11_connecting_to_computers_or_ftp_servers_03.html) · [SnapBridge：Wi-Fi AP 模式](https://nikonimglib.com/snbr/onlinehelp/en/direct_wifi_connection_5.html) / [STA 模式](https://nikonimglib.com/snbr/onlinehelp/en/wifi_st_mode_connection_6.html) / [蓝牙配对](https://nikonimglib.com/snbr/onlinehelp/en/bluetooth_pairing_4.html) · [WMU 下载页（明示不支持 SnapBridge 机型）](https://downloadcenter.nikonimglib.com/en/download/sw/197.html) · [Nikon 与 FotoNation/Microsoft 联合发布 PTP/IP（2004）](https://www.dpreview.com/articles/9871487277/nikonptpip/)
- 各品牌 SDK 官方页（多品牌路线用）：[Canon 亚洲开发者门户（ED-SDK / CCAPI HTTP-over-Wi-Fi）](https://asia.canon/en/campaign/developerresources/sdk) · [Canon 欧洲：如何获取 Camera SDK](https://developers.canon-europe.com/developers/s/article/How-to-get-access-camera) · [Sony Camera Remote SDK（仅 host PC）](https://support.d-imaging.sony.co.jp/app/sdk/en/index.html) · [FUJIFILM X/GFX Camera Control SDK（含 Android，USB only，个人使用影响保修）](https://www.fujifilm-x.com/en-us/camera-control-sdk/) · [Panasonic LUMIX Tether](https://av.jpn.support.panasonic.com/support/global/cs/soft/download/d_lumixtether.html) · [Blackmagic Camera Control SDK/协议](https://www.blackmagicdesign.com/developer/products/camera/sdk-and-software) · [Open GoPro HTTP API](https://gopro.github.io/OpenGoPro/http/) · [DJI Mobile SDK](https://developer.dji.com/mobile-sdk/documentation/introduction/mobile_sdk_introduction.html) · [Insta360 Android SDK](https://github.com/Insta360Develop/Android-SDK)
- 开源先例：见第三节表格内链接；OEM 限制矩阵 [dontkillmyapp.com](https://dontkillmyapp.com/) / [Xiaomi](https://dontkillmyapp.com/xiaomi) / [autostart 深链清单](https://gist.github.com/GransonO/8fd3bfa0521c914597e46ebaf3ae39d3) / [AutoStarter](https://github.com/judemanutd/AutoStarter)；其它同类实现补充 [laheller/ptplibrary（PTP/IP + PTP/USB 双传输）](https://github.com/laheller/ptplibrary) · [NICO（Android USB 控制尼康 Z，需 PTP USB 模式）](https://github.com/haecksenwerk/nico) · [Canon PTP/IP 配对过程解析](https://julianschroden.com/post/2023-05-10-pairing-and-initializing-a-ptp-ip-connection-with-a-canon-eos-camera/) · [fuji-cam-wifi-tool](https://github.com/hkr/fuji-cam-wifi-tool) · [olympus-wifi](https://github.com/joergmlpts/olympus-wifi) · [gopro-wifihack](https://github.com/KonradIT/goprowifihack)

