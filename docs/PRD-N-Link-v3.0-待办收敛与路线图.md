# N-Link v3.0 规划：待办收敛与路线图

| 项 | 内容 |
|---|---|
| 文档状态 | **在效**（本文件是唯一排期事实源） |
| 基线 | v3.0.0（tag 锚定 `eef9b6c`，代码树 = `07e2b9e`：FR-29~FR-34 连接加固 + v2.6.5 FTP 收图 + 监看 HUD 重构）；**FR-35 AP 一键连接收口四条已落地待真机，见 M6** |
| 取代 | PRD v2.6 §14~§18 的未收口项（原件已归档，未收口项全部迁入本文）；v2.7 草案的排期锚点（草案写于 FR-33/34 之前，按 v2.6.1→v2.7 排期已失效，其条目按新锚点重排进本文） |
| 盘点方法 | 11 份文档逐篇提取（2026-10-03），每条带出处；「已否决/已撤回」单列防复活 |

图例：`[机]`=待真机验证 `[板]`=待拍板（需用户输入）`[未]`=未动工 `[半]`=部分落地 `[过]`=口径过期需修 `[疑]`=可能已失效需确认

---

## M0 真机验证与收口（阻断级——不跑完，后面全部失真）

| # | 事项 | 出处 |
|---|---|---|
| 0.1 | FR-34 六条真机判据：`ap_panel armed→joined`、`ap_gateway source=gateway-fast`、蜂窝 lane 无 >8s socket_try、`net_req hold=true`、静置 5min 存活、全闸关=FR33 逐位一致（`ap_panel` 两条自 FR-35 起带 `path=` 字段，见 M6） | v2.6 §18 |
| 0.2 | 「谁砍的 wlan0」定谳：首轮真机掉线后读 `wlan_drop fields=`（自证式采集，第一次就能确认 ROM 下发什么）+ `link_state`，裁决 OEM 策略 vs 相机 AP 复位 | AP分析 §8 |
| 0.3 | FR-30/32/33 遗留判据 V-1~V-4 与 §14.0 回归清单一次跑完（同一次出门测全收） | v2.6 §14/§17 |
| 0.4 | 群友（无「WiFi 始终开启」开关的机型）FR-33 修复前后各一份日志 | 非上网热点 §6 |
| 0.5 | Z8+小米 15 Pro 组合零实测；报障者未给版本号的问题回访 | v2.6 §十三·七 |
| 0.6 | 监看 HUD 新 UI（`07e2b9e`）真机走查：横屏布局、快门行不再被面板盖住、状态卡让位 | 并行会话产出，本仓未验 |
| 0.7 | FR-35 五条真机判据（含**改名热点专项**，这是 FR-34① 没达成「一键」的靶心） | 本文 M6 |
| 0.8 | FR-36/FR-37 四条真机判据：三模式相机全称都是 `Nikon Z xxx`、断开不残留、会话名早于参数轮询、FTP 架构下 `arch_skip` 出现且 AP 自动重连不受影响 | 本文 M7 |

## M1 证据基建（先把"怎么算好了"定下来）

| # | 事项 | 出处 |
|---|---|---|
| 1.1 | `[未]` R1b 真机回归脚本（L2→L3 唯一门，跨文档最高频主题） | v2.6 §14 R1b |
| 1.2 | `[未]` 指标基线三项全零（首连成功率/p50 时延/超轮率没有分母，GAP-09） | 指标口径 §5 |
| 1.3 | `[过]` 指标口径升 v3.0：纳入 FR-31/32/34 新事件（net_req/sta_ifaces/wlan_drop/link_state/ptp_tx）；FR-12 改 66ms 分母；`download_done path=` 删否 `[板]` | 指标口径 |
| 1.4 | `[半]` 三闸收口：tone_tools、event_silence_hold 仍默认关，真机数据到位后定 | v2.6 §14 R3a |

## M2 连接链路剩余项

| # | 事项 | 出处 |
|---|---|---|
| 2.1 | `[板]` R2a~d：USB 抖动窗口、映射纠正、「谁砍重试」复盘、settle 定值 | v2.6 §14 R2 |
| 2.2 | `[半]` R4 蜂窝黑洞判据（STA_SKIP_CELLULAR 默认关）：`sta_probe_ctx` 双记数据到位即裁决 | v2.6 §17.2⑤ / STA注册 §R4 |
| 2.3 | `[板]` FR-25 setWifiEnabled 看门狗（此前已否决一次，若 wlan_drop 定谳指向 ROM 可重提） | v2.6 §10.2 |
| 2.4 | `[半]` FR-34 P2 两条：**specifier 免面板一键连已随 FR-35④ 落地（`[机]`）**；剩「接收缓冲 3.3MB + radio 诊断」未动工 | AP分析 §6 / 本文 M6 |
| 2.5 | `[未]` 主机注册收尾：0x201F 后 ConfirmHost(R3)、§4.4 四组对照实验 | STA注册 |
| 2.6 | `[半]` FTP 架构 B：真机验证 + FTP 传输期抑制 STA 重连 | v2.6 §17.5 |
| 2.7 | `[疑]` USB 未收口：不回字节根因、R6a setConfiguration、R6b USB↔WiFi 互斥、R6c 帧共锁 | v2.6 §11.5/§16.5 |
| 2.8 | `[过]` 配网分析文档 §4「R2/R3 待拍板」口径已随 FR-32 落地，需补一行落地指针 | 配网分析 |
| 2.9 | `[未]` R4 录屏+tcpdump（相机侧动作与手机空口对齐）——需用户配合的最后一块证据 | 配网分析 |

## M3 产品功能（发版后主线）

| # | 事项 | 出处 |
|---|---|---|
| 3.1 | `[板]` AI 修图立项六问 Q1~Q6（模型来源/塑形边界/NEF/云端/体积/MediaStore）；**先修 README Phase 4「编辑器开发中」失实** | AI修图 PRD（全篇零代码） |
| 3.2 | `[未]` 监看工具组：LUT/斑马/峰值/安全区（R3d），与 FR-12 渲染链互锁，先后顺序要拍板 | v2.6 §14 R3d |
| 3.3 | `[未]` 0x101A 保护标记写侧（R3b）；RAW 附 JPEG 下载（R3c） | v2.6 §14 R3 |
| 3.4 | `[未]` 竞品差距未动工：GAP-03/06/10/11/14/15/17（包围曝光、Tethering、并发下载等） | 竞品分析 §6.1-6.2 |
| 3.5 | `[板]` GAP-25 配对码归属；`0x9714` 只查不取；`0x9A6C`；附录 B 影控台 Android 四未知（**优先级高于一切待核**） | 竞品分析 §6.3/附录B |
| 3.6 | `[未]` iPad/无障碍/i18n/死链（GAP-19~26 剩余） | 竞品分析 §6.3 |

## M4 工程与合规

| # | 事项 | 出处 |
|---|---|---|
| 4.1 | `[未]` R3e：LICENSE、CI、proguard 规则复核 | v2.6 §14 R3e |
| 4.2 | `[未]` targetSdk 35→36 适配窗口 | v2.7 草案 P1 |
| 4.3 | `[未]` 9 机型回归矩阵（含固件改设备名场景） | v2.6 §14 / 竞品 §7.3 |
| 4.4 | `[半]` R1d 兼容表 9 条 `lastVerified:null` 逐条真机核销 | rom_rules.json |

## M5 视觉与文档治理

| # | 事项 | 出处 |
|---|---|---|
| 5.1 | `[半]` 视觉规范 §4 三条未做 + §1.1「强调色只两处」口径已被监看 4 处黄打破，正文需登记例外 | 视觉规范 |
| 5.2 | `[疑]` 液态玻璃遗留：§6-1/2 死 token 与描边硬编码、§6-4 AGSL 等 minSdk33；监看 HUD 玻璃已随 `07e2b9e` 下线，缺陷5/§5.2/§7.4 是否作废需确认 | 液态玻璃审计 |
| 5.3 | `[过]` `LiveViewFragment.kt:106` 注释仍引用已删除的 applyGlassHud——顺手修 | 07e2b9e 新失实 |
| 5.4 | `[过]` GAP-01 soTimeout=0 口径过期（已被 STA 显式超时取代）；「文档滞后于代码」是系统病，本文档每次落地即回填 | 竞品分析 §6.1 |

## M6 FR-35：AP 一键连接收口（2026-10-03 已落地，`[机]` 待真机）

**成因**：用户核查「免扫描一键连接」与「绕过厂商定制系统」两项是否落地。结论——
FR-34① 的代码链路是通的，但**真机上大概率走不到**，另有三处失分：UI 文案仍写「先点扫描」；
面板拉起路径（AOSP 面板 vs OEM 全设置页）没有日志，无法定谳「绕过」是否兑现；
回连判定只认 NIKON SSID，热点改名或无「附近的设备」权限时会**死循环**
（点了面板回来不起轮次、武装标记不消费、再点还是弹面板）。四条全部闸门化，关闸=FR-34 行为。

| # | 条目 | 落点 | 关闸行为 |
|---|---|---|---|
| 35.1 | 面板拉起路径落日志：`launchWifiPanel()` 返回 `panel`/`settings`/`none`，写入 `ap_panel phase=armed path=` | `DashboardFragment.kt` launchWifiPanel / launchApWifiPanel；`ConnectionManager.armApPanelConnect(path)` | 无日志区分（FR-34 原状） |
| 35.2 | AP 文案与主次收口：热点教程去掉「必须先扫描」、空候选提示改指「连接相机」、AP 页签扫描按钮文案「扫描（可选）」且连接按钮权重 1.6:1 | `fragment_dashboard.xml` tvApGuide；`DashboardFragment` applyActionEmphasis / 空候选提示；`SettingsFragment` 使用教程 AP 行 | 仅文案（纯静态，无运行时开关） |
| 35.3 | `AP_JOINED_LOOSE=v35_ap_joined_loose`：武装状态下的在网判定放宽为「SSID 命中 **或** 有 WiFi 网络且其网关是私网 IPv4」；纯函数 `ApGatewayResolver.looksJoinedAp()` 出 JVM 单测 2 条 | `ApGatewayResolver.kt` looksJoinedAp / isOnApNetwork；`ConnectionManager.tryStartArmedApConnect()` | 只认 SSID（死角复现） |
| 35.4 | `AP_CRED_SPECIFIER=v35_ap_cred_specifier`：本会话有 BLE 下发的 WiFi 凭证时，点「连接相机」直接走 `WifiNetworkSpecifier` 免面板一键连（复用 v2.2 通路），失败/超时回调 UI 补拉面板；在途标记防确认框风暴 | `ConnectionManager.connectApWithCredential(onFailed)`；`DashboardFragment.apOneTapConnect()` 三分流 | 一律走系统面板 |

**判定口径（为什么首次点按仍用严格 SSID）**：35.3 的放宽只用在**武装状态下**（用户刚在面板
或系统确认框里点过热点）。若把它用在 `apOneTapConnect` 的分流上，用户在家用路由器上点
「连接相机」就会盲打路由器网关，把「该去连热点」的引导吞掉——所以两处刻意不同。

**超出 ZDROP 的部分**：35.4。ZDROP 全 dex 里 specifier / addNetworkSuggestions 匹配数为 0
（AP分析 §5·3 `已验证`），即竞品不做免确认自动连网；本条用系统确认框换掉「进 WLAN 列表翻热点」，
是增量而非同构，故独立设闸。

**真机判据（出门测一次跑完，补进 M0 §0.7）**：

1. 热点模式不点扫描，直接点「连接相机」→ 状态行出现三种之一：`正在连接相机热点网关…`（已在热点）/
   `正在请求连接相机热点，请在系统弹窗点「允许」…`（有凭证）/ `请在弹出面板里点相机热点…`（面板）；
2. 日志 `ap_panel phase=armed path=` 的取值即「vivo 到底弹的是哪个入口」的定谳证据（`panel`=兑现绕过，`settings`=没兑现）；
3. **改名热点专项**（35.3 的靶心）：把机身热点名改成非 NIKON 名 → 面板点完回 App 应当 ≤2s 出现
   `ap_panel phase=joined path=panel` 并起轮次；FR-34 旧行为是「什么都没发生」；
4. 35.4 需要 BLE 先拿到凭证（相机配对画面过一轮）：无凭证时 `ap_join` 不出现、直接走面板，属正常；
5. 全闸关（`v35_ap_joined_loose`+`v35_ap_cred_specifier`+`v34_ap_one_tap`）→ 行为逐位回到 FR-33 版（出 FR33 包对比）。

**产物**：`N-Link-v3.0.0-debug-FR35.apk`（debug，未混淆，Lab 面板可逐条关闸二分）。
单测 238/1 红/1 跳：唯一红仍是画廊 `ProtectionFilterTest`（并行会话既有基线，非本批引入）。

## M7 FR-36 相机官方名称 + FR-37 架构真的决定连接（2026-10-03 已落地，`[机]` 待真机）

用户追加两条：①设备页要么不显示相机名、要么显示的不是官方名称；②STA 切换连接模式后
没按新模式连接，还是直接连相机。

### FR-36 设备页相机全称（三条已验证成因，逐条对治）

| 成因（`已验证`，代码级） | 对治 |
|---|---|
| 设备页直接写机身自报原文：PTP `GetDeviceInfo.Model` 形如 `NIKON Z 50II`（`UsbPtpProtocol:232` 注释实证），全大写带品牌前缀，不是官方写法；未知 USB PID 显示 `Nikon Camera (0x0FFF)` | 新增 `device/model/CameraModelCatalog`：`NIKON Z 6_III → Nikon Z 6III`、`Z6III_12345678 → Nikon Z 6III`（广播名剥序列号）；**认不出机型返回 null，绝不猜**（IP/MAC/路由器名/热点 SSID 一律不装成型号） |
| 布局默认写死 `Nikon`（`fragment_dashboard.xml:607`），而两处赋值都只在拿到值时才写 → GetDeviceInfo 未回来就永久停在 "Nikon" | 相机全称收敛为唯一出口 `DashboardFragment.renderCameraName()`，五级来源链：参数 Model → 会话 Model → USB → BLE 广播机型 → 配对记录 |
| 参数管理器从不清 `modelName`，而 `readFirmwareAndModel` 又「取到空就保留旧值」→ 换机身时 B 显示 A 的名字 | `readAllParameters()` 与 `resetModeSwitchSupport()` 同处按「新会话」复位 `modelName/firmwareVersion` |
| 配对记录那列历史上存的是 **IP**（`savePairedDevice(address, name, endpoint.host)` 第三参是 `model`） | 改存机身 Model 原文，deviceName 存归一化后的官方全称；取不到保留旧行为。「最近连接」卡片优先显示型号，旧行（IP）自动退回原显示 |

新增提前量：`PtpDeviceInfo.parseModel()` 解规范 §13.2 的 Model 字段（跳过 5 个 AUINT16 + Manufacturer），
`PtpSessionManager.lastDeviceModel` 在 **OpenSession 前那次** GetDeviceInfo 就记下型号（v2.6.3 只解析了
OperationsSupported，Model 被丢掉了）→ 连上瞬间就有名字，不必等参数轮询那一整轮；同时落 `device_model` 事件。

不加闸门（纯显示归一化 + 身份复位，零连接行为变化），决定记录在此：闸门体系是用来做现场二分的，
名称串没有值得二分的现场失败模式。

### FR-37 架构选择必须真的决定连接

`已验证`：`AppSettings.staArchitecture` 在 v3.0.0 全仓**只被 DashboardFragment 读**（切面板显隐、
起停 FTP 服务器），连接层四个入口（`connectToWifiCamera`、重连触发、状态机重试闭环、配对恢复）
一处都不看它。于是选了「FTP 推送收图」之后，后台仍然会自动发 PTP/IP InitCommand 敲机身——
而机身只有一个 PTP/IP 客户端槽（FR-21 实测收回要 ~35s），推送就被打断。用户侧就是「切了没切」。

新闸 `STA_ARCH_GATE = "v37_sta_arch_gate"`（默认开）：`caller != USER_TAP` 且架构=FTP 且手机**不在**相机
热点上（AP 链路不受任何影响）→ 跳过该轮并记 `arch_skip`，同时给出可操作提示。用户主动点「连接相机」
照旧放行，因为监看/相册只有 PTP 能做。FTP 教程补一条说明，免得被当成 App 卡住。
关闸 = v3.0.0 行为（架构只管界面）。

**并行会话事实（2026-10-03 22:0x 核查）**：另一个会话曾在主副本实现「连接模式」下拉菜单
（`widget/NlDropdown.kt` + `drawable/dropdown_menu_bg.xml` + `MenuAnchor` 包装，并改 `StaArch.kt`
构造签名为 `(id, displayName, subtitle, note)`），**现已整体回退**：主副本工作树干净，
`git log --all` 里没有任何分支含这些文件。所以「下拉列表」这个控件当前不在代码树中；
FR-37 的门读的是持久化的架构值，将来无论用药丸还是下拉列表都照样生效。

### 真机判据（并入 M0 出门测）

1. AP / STA-PTP / USB 三种模式各连一次，相机全称都显示成 `Nikon Z xxx`（不是 `NIKON Z 6_III`、
   不是 `Nikon`、不是 IP）；
2. 断开后不残留上一台的名字；换机身连一次，名字跟着换；
3. 会话名先到：连上瞬间就有名字（`device_model name=` 事件应早于参数轮询）；
4. FR-37：选 FTP 推送架构 + 手机与相机在同一路由器 → 日志应出现 `arch_skip caller=...`，
   且机身 FTP 推送不再被 PTP 敲门打断；切回 PTP/IP 架构后 `arch_skip` 消失；
   AP 热点模式下自动重连**必须仍然工作**（这是这条闸门的硬边界）。

**产物**：`N-Link-v3.0.0-debug-FR36.apk`。单测 248/1 红/1 跳（新增 10 条全绿；
唯一红仍是 `ProtectionFilterTest`）。


## 已否决 / 已撤回（防复活清单）
- 已否决：去 8.5s 盲等、InitFail 快收口、in_subnet 快失败、6s 超时、STA 连接池、64 位续传、热更、帧率阶梯（v2.6 §11.1/§13/§17.3/R5）
- 已撤回：ZTransfer 速度威胁、影控台评分增速、iOS 不能直连、影像馆硬日期锚点、GAP-05 原判断（竞品分析 §7.1/§7.3/§8）
- 判据修订：FR-33「validated=false 出现」仅适用未验证 ROM；P0-6「无心跳」前提被证伪（已有 8s Ping+60s 刷新，改 10s 快档）

## 拍板队列（等用户输入，按阻塞面排序）

1. M0 出门测窗口（一次跑完 0.1~0.7，FR-35 包已就绪）
2. 3.5 影控台四未知 + GAP-25 配对码
3. 3.1 AI 修图六问（决定 M3 主线走向）
4. 2.3 看门狗重提与否、2.4 剩余半条（缓冲/radio）——FR-35④ specifier 已拍板实现，只剩真机读数
5. 1.3 `download_done path=` 隐私口径
