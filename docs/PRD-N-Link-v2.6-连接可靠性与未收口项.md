# PRD · N-Link v2.6 —— 连接可靠性 + v2.5 未收口项（连接线在 `docs/` 下的唯一在效规划文档）

> 版本：v2.6.0（**已于 2026-09-29 正式发布**，versionCode 25 / tag `v2.6.0`）· 基线：v2.3.2（versionCode 24）@ `874a2d3` · 分支：`master`
> 状态：**本文件的连接改动已全部进入正式包**；下一步是 v2.6.1 收口（§十四 R1）——四处未验证项见 §14.0 的 V-1~V-4
> **本文是 v2.5 与 v2.6 的合并稿**，连接线的规划文档顶层只留这一份；合并理由与被合并文档的处置见 §十五
> （`docs/PRD-N-Link-AI修图.md` 是另一条产品线的 Draft，与本文无覆盖关系）
> 依据：**5 份真机导出日志**（4 份用户报障 + 1 份自测；09-29 的两份来自 vivo V2509A / Android 16 / Z 50II）
> \+ 对 `wifi_sta` / `wifi_ap` / `connect` / `ptp` / `usb` 五个目录的逐行只读排查 + `git` 版本考古（v1.1.0 ↔ v2.3.2）
> \+ 一次逐 FR 的**代码级**状态审计（§十三，不采信 PRD 的自我声明）
> 原则：已跑通的功能不改动，改动面压到最小，每项独立可回退
> 铁律：**先补可观测性，再动判据**。这条在本轮被两次证明——一次救回一条会造成回归的错误计划（§11.1），
> 一次拦下一条同样会造成回归的优化（§13.3）
> FR 编号：接续 v2.5（v2.5 止于 FR-17，本文自 **FR-18** 起；未收口的 v2.5 项沿用其原编号）

---

## 一、为什么是这一版

### 1.1 一条用户反馈与它证伪不了的东西

用户反馈：「1.x 后段的老版本能连，之后的新版本连不上」「9 月 24 日晚还能连，后续版本失败」。

这两句话**都无法用现有日志证实**，但也**都无法排除**——原因是导出日志里缺三样东西：机型/ROM、socket 层错误码、连接请求的调用来源（§2.4）。

而日志能证实的是另一件事：**失败与成功之间的差别是时间，不是版本。**

| 观测 | 数值 | 来源 |
|---|---|---|
| 失败收口耗时（6 次，极差 1.3s） | 20.1 / 20.8 / 21.1 / 21.1 / 21.3 / 21.4 s | 日志3（v2.3.2） |
| 成功耗时（4 次） | 26.4 / 42.1 / 43.1 / **181.3** s | 日志2（v2.3.2） |
| TCP 建链耗时（用户机，5 次） | 9.80 / 15.05 / 29.87 / 84.75 / **97.74** s | 日志2 原始时间戳 |
| TCP 建链耗时（自测机，1 次） | **0.012** s | 我的最新连接日志 22:32:41 |

**成功最短要 26.4s，而失败恒定在 21s 收口。** 这不是"连不上"，这是"还没等到就放弃了"。

### 1.2 本期目标（三条，可验收）

- **T1 让失败可解释**：一次连接失败后，导出日志能回答「手机当时在哪张网上、绑到了哪个 netId、socket 抛的是什么 errno、探测了几次、是谁触发的这一轮」。现状这五问**一个都答不出**。
- **T2 拆掉两个自我放大器**：连接请求自我风暴（80% 的样本是 `superseded`）与探测自我占坑（"第一次探通、之后 4 分钟全拒"）。两者都是我们自己造出来的失败。
- **T3 把放弃预算与实测成功耗时对齐**：预算必须来自 T1 量出的 p95，不再拍脑袋。

### 1.3 非目标（本期明确不做）

| 不做什么 | 理由 / 重启条件 |
|---|---|
| 断言"某版本引入了**协议 / 鉴权**层面的回归" | 仍然证伪不了也证实不了。⚠ **但本条原引用"v2.2.0→v2.3.2 只改了 482 行"是用错了范围**——用户说的是 1.x，正确跨度是 `git diff --stat v1.3.1 v2.3.2` 连接六个目录 = **17 文件 / +2834 / −105**，大改动确实存在。**订正后的结论见 §13.7：不是协议变了，而是 2.x 新增的三条"判死"逻辑把本来会成功的连接提前放弃了** —— 这三条全部可代码复核，也全部已修 |
| 连接成功后 `op=0x1005/0x1007` 回 `0x2013` | 4 份日志里**每次 `pair_ok` 之后都稳定复现**（GetStorageInfo / GetObjectHandles → PTP `InvalidParameter`）。这是"连上了但读不出存储列表"的独立缺陷，会让用户主观上仍觉得没连上。**单独立项，不混进本期**（§10.2 待拍板） |
| 新增第三条 socket / data 通道 | v2.2 §2.5 已裁定，本文不重开 |
| 改 PTP 事务层、下载队列 | §8.1 铁律 1 的核心区，本期零改动 |
| 引入遥测 SDK 上报这些新字段 | v2.5 §1.3 已裁定不做上报。本期所有新字段**只进本机导出**，且必须过 §四 FR-18 的脱敏验收 |
| 把 `PROBE_TCP_ONLY` / `conn22_*` 等既有闸门改名或合并 | 码值与 key 一旦发布就是日志里的稳定契约（`ConnFunnel.kt:59-61` 同款裁定） |

### 1.4 目标用户与使用场景（自 v2.5 并入，2026-09-28 补回 / 2026-09-29 迁移）

> ⚠ **这一节的来历**：原 v2.5 草稿（现 `docs/archive/PRD-N-Link-v2.5-竞品对标与差距收敛.md` §二）有「目标用户与使用场景」，被取代时丢过一次，`f7e24e0` 在 v2.5 正文里补回为 §1.4，本文合并时再搬一次——**它现在随正文一起在效，不在 archive 里**。
> **排期缺一张"为谁做"的对照表是实质缺陷**，不是格式问题 —— §三的优先级判据只回答"哪个缺陷重"，不回答"哪一类用户掉了没人替我们说话"。
> 保留 §1.4 编号是为了不动被 208 处代码注释引用的旧节号；本节内文出现的 `§九` / `§六` / `§1.3` 在本文与 v2.5 里语义一致（分别是真机验收矩阵、指标、非目标），可直接对照。
> 每一条断点都带代码/闸门证据，**已修复的不写成缺陷**。

#### 1.4.1 用户画像（按真实来源标注）

| 画像 | 设备组合 | 核心诉求 | 现在为什么留下 | 现在为什么想走 |
|---|---|---|---|---|
| **P1 婚礼/活动摄影师**（重度，最愿意付费的一类） | Z8 + 小米 15 Pro；200GB 卡、单次几千张 | 一次插线/连上就把原图与 NEF 全拿走，**当场不能掉链子** | USB 读卡 + 断点续传 + 「跳过已下载」；首屏增量（`v25_album_incr` **默认开**，`UiFlags.kt:47,92`）；机身占用可解释（FR-07 已落 `StaFailureClass.kt` / `WifiDirectConnector.kt`） | **>2GB 的 MOV 仍不能续传**（FR-05② 未完成，卡在机身 `0x95C1` 无证据，见 §FR-05 边界）；**影速传专业版已免费**（竞品分析 §2.2） |
| **P2 风光/长曝爱好者** | Z6III / Z7II + 国产旗舰 | B 门、间隔、**包围 / 焦点包围**、参数精确 | B 门全链路 + 15s–5min 长曝 + 间隔拍摄（既有能力） | **包围与焦点包围仍是全仓 0 命中**【实，2026-09-28 复核：`Bracketing`、`包围` 两个关键词在 `app/src/main/java/` 下均无文件命中】→ §1.3 已裁定本期不做；**帧澈宣称有**（停更 37 天，竞品分析 §7.2） |
| **P3 视频 / 单机创作者** | Zf / Z50II + vivo / 红米 | **监看要像监视器**：帧率、LUT、斑马、峰值、波形 | 触摸对焦、网格、直方图，**加上 09-24 落地的伪彩与 RGB 分量波形**（`PseudoColorLut.kt` / `RgbWaveform.kt` / `WaveformView.kt`，闸门 `v25_tone_tools` 默认关） | **帧率仍固定 66ms ≈ 15fps**【实 `BaselineMetrics.kt:26`，FR-12 未做】；**LUT 预览 / 斑马纹 / 安全区 / 峰值对焦一个都没有**；影控台把这些做成 ¥148 买断档的付费理由，且 4 天再发一版（竞品分析 §7.2） |
| **P4 极客 / 开源用户**（口碑放大器） | 多机型 | 可读、可审计、不上传、提了 issue 会被修 | 开源 + 纯本地 + 双网盘 + 中文说人话的失败原因码；**★55**（【实】`gh api repos/Wan-1230/N-link`，2026-09-28） | **仓库根至今没有 LICENSE 文件**【实，2026-09-28 `ls` 复核】→ 「开源可信」这一条在法务上不成立；包名 `com.nikonlink.app` 与 `com.tauber.nikonlink` 撞名（竞品分析 §2.9），**搜不到我们** |

**画像分布判断【推】**：P1 是现金流与口碑来源（也是被官方免费化打得最狠的一群），P2/P3 是被开源同路与付费对手分头拿走的一群，P4 是帮我们传播的一群。**A 批次（P0）全部服务 P1，B 批次主要服务 P2/P3，§六 的可信性指标服务 P4** —— 顺序不能反：P1 掉了，没人替我们说话；P4 掉了，没人替我们证明。

#### 1.4.2 关键场景（端到端，作验收脚本用）

| # | 场景 | 现状断点（带证据） | 目标体验 |
|---|---|---|---|
| **S1** | 婚礼现场：相机开 AP 热点，手机直连，边拍边取原图 | ~~STA 分支无超时~~ **已修**：STA 走与 AP 同一手法 `requestNetwork(request, cb, systemDeadline)`【实 `StaNetworkRequester.kt:143`，闸门 `v25_sta_timeouts` 默认开 `ConnFlags.kt:45,209`】；**Z8 + 小米 15 Pro 组合仍从未实测**（§九 V-1，`resp=5 fail_reason=1` 未复现验证） | 三通道任一入口 10s 内连上，或给出具体原因 + 一键直达系统设置 |
| **S2** | 收工：200GB 卡约 3000 张，批量下 NEF+JPG，中途息屏 | 首屏已由增量加载改善（`v25_album_incr` 开）；**>2GB 单文件退出即不能续**（FR-05② 待真机）；RAW+JPEG 成对显示与下载选择**已于 09-29 转默认开**（`v25_raw_pair`，`UiFlags.kt:83,96`；原稿写"默认关"，本轮按用户要求翻转） | 首屏 ≤3s、息屏不中断、可按「只下 NEF / 只下 JPG / 成对」三选、>2GB 可续传 |
| **S3** | 影棚：三脚架上架机身，手机当监视器 + 遥控器 | 15fps 固定（`BaselineMetrics.kt:26`）；有伪彩 + RGB 波形（默认关），**无 LUT 预览、无斑马、无峰值**；触摸对焦回显已修 | 帧率/质量可分级（FR-12）、点哪对哪且有回显、影调工具至少追平「伪彩 + 波形」这一档 |
| **S4** | 日出：机身拨盘改 A 档手机要跟着变，再打 5 张包围 | 模式双向同步已 ≤500ms（既有）；**包围不存在**（1.4.1-P2 已复核为 0 命中） | 本期不含（§1.3），等 FR-15 的 `0x9918` 探测结论再立项 |
| **S5** | 群里有人报「连不上」，开发者要能定位 | 12-Stage / 40-Reason 漏斗 + 9 项环境预检已有；导出日志含 FR-08d 字段声明与 v2.6 的 8 个新事件；**仍无任何聚合数据**，答不了「这个问题影响多少人」 | 一条漏斗记录含机型/ROM/固件/通道/Stage/Reason，用户一键带附件发出（不做上报，见 §1.3 崩溃率那条） |
| **S6** | 装过 SnapBridge 的用户第一次用我们 | 机身只允许 **1 个 PTP/IP 客户端**；FR-07 已把「被占用」做成可解释、可引导的失败分类 | 检测到占用 → 明说「相机已连 SnapBridge，请先在机身端断开」+ 分步引导（**竞品全无此能力**，是唯一能把官方冲突转成自己好评点的动作） |

**这一节怎么用于排期【推】**：S1/S2/S6 属 P1，落 A 批次；S3 属 P3，落 B 批次且是本期唯一"往对手已验证的付费档里补"的方向；S4 明确不在本期；S5 是 P4 的口碑面，属 FR-01/FR-08 已交付部分，剩下的聚合不做。**任何新增提案若指不到 P1–P4 与 S1–S6 中的某一行，按 §8.1 铁律不进本期。**

---

## 二、现状基线（全部为代码 + 日志双向可证）

### 2.1 六个必现缺陷

#### D1 绑网会绑到「到不了相机」的 WiFi，且这种情况不打任何诊断

`resolveNetwork` 三路并行竞速取第一个非 null（`WifiDirectConnector.kt:482-529`），随后 `network?.let { networkMonitor.bindProcessTo(it) }`（`:334`）把**整个进程**绑上去。而三路的选网判据都是"子网不匹配就退而求其次"：

| 路径 | 落点 | 行为 |
|---|---|---|
| `StaNetworkRequester.acquire` | `:175-179` | `if (fallback != null) { held = fallback; return fallback }` —— 返回**任意** WiFi |
| `WifiNetworkMonitor.awaitWifiNetworkFor` | `:162` | `return anyWifi` —— 同上 |
| `WifiManager.bindToActiveWifi` | `:381-387` | `:384` 直接 `bindProcessToNetwork(network)`，**把绑网当副作用**；它作为竞速第三路，即使落选也已把进程绑走，`resolveNetwork` 只 `cancel()` 协程、不撤销绑定 |

关键盲区：`in_subnet` 诊断**只在 `network == null` 分支里打**（`WifiDirectConnector.kt:276-296`）。绑到错网时 `network != null`，日志里一片安静。

**日志对应**：日志3 与日志1 **一条 `sta_fallback` 都没有** → `resolveNetwork` 每次都返回了非 null → 绑得对不对无从判断。而日志3 的 6 次失败里 socket 都是 **1.3s 内秒失败**（22:31:42.531 `connect phase=socket` → 22:31:43.795 `sta_fail`），是 EHOSTUNREACH / ECONNREFUSED 的形态，不是超时的形态。

#### D2 连接请求自我风暴：幂等守卫有 ~2.4s 盲区 + `pairingJob` 覆盖不取消

- 守卫只判 `connector.isActive`，**不判 `pairingJob`** —— `ConnectionManager.kt:239-246`
- `learnFirst` 分支要先跑 `apGatewayResolver.resolve()`（`READINESS_MS=1200` + `RESAMPLE_MS=700` + 探测，`ApGatewayResolver.kt:44,47`）才在 `:297` 调 `beginWifiPairing` → `connector.connect()`。**这段 ≥2.4s 的窗口里 `connector.isActive == false`，任何重入一律放行**
- 重入时 `pairingJob = scope?.launch { ... }`（`ConnectionManager.kt:280`）**覆盖而不取消**上一个 → 孤儿协程。每个孤儿各跑一次 `resolve()`、各调一次 `beginWifiPairing` → `connector.connect()` → `generation++` + `activeJob?.cancel()`（`WifiDirectConnector.kt:191-196`）

**日志铁证**（日志2，`sta_try gen=N` 与 `sta_cancel gen=N-1` 严格成对、间隔 ~180ms）：

| 风暴窗口 | 时长 | 开了几代 |
|---|---|---|
| 20:17:25.000 → 20:17:30.720 | 5.7 s | generation 7 → 22，**15 条 `sta_try`** |
| 20:24:02.387 → 20:24:07.322 | 4.9 s | generation 27 → 49，**22 条 `sta_try`** |

（两处都有 generation 跳号：gen=8、gen=28 在 `sta_try` 落日志前就被顶掉了，所以「代数」比「条数」多 1。）

同频刷的还有 `ap_gateway host=none`（多个孤儿 `resolve()` 并发）与 `conn_result stage=INTENT reason=superseded`。**45 个连接样本里 36 个（80.0%）是 `superseded`。**

#### D3 探测自我占坑：一次「点连接」对相机 15740 发 20 次量级的 TCP

尼康机身同时只接受**一个** PTP/IP 客户端。而当前一次连接会发出：

| 出处 | 次数 |
|---|---|
| `ApGatewayResolver.resolve` 网关探测（`:160`） | 1 |
| 同函数出厂兜底（`:180-186`，`FACTORY_DEFAULT_HOSTS` 4 个，`:74`） | 0~4 |
| `waitUntilReachable` 3s 窗口内（`WifiDirectConnector.kt:547-564`，前 3 轮才做，`:100`） | 每轮 ~2 |
| 真实连接 command / event 两条 socket（`PtpSessionManager.kt:209,262`） | 每轮 2 |
| 失败后 `probeReachable`（`WifiDirectConnector.kt:388,615-616`） | 每轮 1 |

合计 ≈ 5 + 3×(2+2+1) = **20 次量级**，再乘 D2 的孤儿倍数。

代码自己的注释就写着代价：`WifiDirectConnector.kt:571-577` 记录 Z50II + vivo（2026-09-19）实测——探测留下的半开会话要**约 35s** 才被机身收掉。而 `PROBE_SETTLE_MS = 400ms`（`ApGatewayResolver.kt:57`、`WifiDirectConnector.kt:97`）只覆盖约 **1%**。

**日志铁证**（日志3）：22:31:25.353 `ap_gateway host=192.168.1.1 stable=true source=gateway`（探测**成功**）→ 5 秒后 22:31:30.306 `sta_preconnect reachable=false` → 之后 5 次尝试全部 `probe=rejected`，一路持续到 22:37（**4 分钟**）。这是"探通了反而连不上"的教科书记录。

#### D4 放弃预算 < 成功所需时间

| 常量 | 值 | 落点 | 引入时间 |
|---|---|---|---|
| `UNREACHABLE_GIVE_UP` | 3 | `WifiDirectConnector.kt:108` | `9a25ba1`（09-16） |
| `RETRY_BUDGET` | 8 | `ConnectionStateMachine.kt:42` | `c31d695`（09-19） |
| `MAX_ATTEMPTS` | 10 | `WifiDirectConnector.kt:66` | v1.3.1 已有 |

v1.3.1（09-10）**前两个常量都不存在**，且 `grep PRECONNECT|probeReachable|isCameraPortOpen` = **0 行**、`ApGatewayResolver` **文件不存在**。也就是说老版本：跑满 10 次退避（≈90s+）、零额外探测、永不提前收口。

**这就是"老版本能连、新版本连不上"最省假设的解释**：不需要假设任何协议/鉴权回归。同一条慢链路，老版本表现为"转很久但最后连上了"，新版本表现为"21 秒报未找到相机"。

#### D5 `hasLocalWifiLikeInterface()` 把蜂窝/VPN 网口当成「类 WiFi」

实现是 `localAddresses().isNotEmpty()`（`LocalNetworkInterfaceResolver.kt:163`），而 `localAddresses()` 只过滤 `isUp` / 回环 / 169.254（`:82-100`）。于是 `ccmni4`（蜂窝）、`vgate0`（VPN）都算数，`defaultRouteFallback` 被判为 true（`WifiDirectConnector.kt:276-277`）→ `bindProcessTo(null)` → socket 走蜂窝/VPN 去打 192.168.1.1 → 黑洞 → 30s 超时 × 重试。日志2 的 84.75s / 97.74s 就是这个形态。

**全部 15 次 `sta_fallback` 无一例外 `in_subnet=false`**（4 份日志里 `in_subnet=true` 出现 **0** 次）：

| 日志 | 次数 | ifaces 实测 |
|---|---|---|
| 日志2 | 11 | `ccmni4 10.3.9.110/8, vgate0 172.30.235.180/32`（**无 wlan0，有 VPN**） |
| 我的日志 09-24 | 3 | `ccmni2 10.32.64.21/8`（无 wlan0） |
| 我的日志 09-28 | 1 | `ap0 10.184.219.24/24, ccmni4 10.56.31.140/8`（手机自开热点） |
| 日志1 / 日志3 | 0 | **一条都没有** → `resolveNetwork` 每次都返回了非 null，见 D1 |

#### D6 FR-01 的分阶段耗时口径失真（测量缺陷，会误导后续所有决策）

`ConnFunnel.stageGaps` 取 `steps[i].atMs - steps[i-1].atMs`（`ConnFunnel.kt:216-227`），而 `TCP` 的上一步是 `DISCOVER`，`DISCOVER` 卡是在 `beginWifiPairing` **入口**打的（`ConnectionManager.kt:317`）。所以 `TCP` 段实际包含了 `resolveNetwork`(4s) + `preconnect`(3s) + 真实建链 + 全部重试。`docs/指标口径-v2.5.md:20` 写的"相邻两个阶段的时间差，即该阶段自身耗时"在多阶段重访时不成立。

**反证**：日志2 gen=49 报 `HANDSHAKE=121527ms`，而原始时间戳 `conn_stage TCP` 20:25:52.796 → `HANDSHAKE` 20:25:53.372 只差 **576ms**。

**结论：现有基线里的 `TCP p95=178927ms`、`HANDSHAKE p95=121527ms` 两个数不可信，只有 `total` 可信。** 本文 §1.1 的所有耗时一律用原始时间戳重算，不引用这两个数。

### 2.2 两处环境放大器（不是缺陷，但会放大 D1/D5）

- **Android 智能切网**：相机热点无 Internet，ROM 会把默认路由留在蜂窝上。`PreflightGate` 有 `AVOID_BAD_WIFI`（`:149`）与 `vpnNotice`（`:238-250`）两项检查，但 `ConnectionManager.kt:253` 的条件是 `if (PREFLIGHT && !apGatewayResolver.isOnCameraAp())` —— **在相机热点上时全部预检被跳过**，而日志2 那台机器正好有 `vgate0`。
- **息屏冻结**：我的日志 09-24 gen=2，02:04:42.535（`connect phase=socket`）→ 02:48:44.611（下一条日志）之间 **44 分钟零日志**，恢复后才在 02:50:37 收口（`sta_fail ... fallback=true`）。用户看到的就是"一直转圈"。

### 2.3 「我为什么在当前版本能连上」

自测成功那次：`DISCOVER=2342ms、TCP=440ms（漏斗口径）/ 12ms（原始口径）、HANDSHAKE=9ms、FIRST_COMMAND=40ms，total=2831ms`。三个触发条件一个都没满足——手机确实在相机热点上、`resolveNetwork` 拿到的网络覆盖 192.168.1.0/24、相机槽位空着。

**但 D5 的形态在我的日志里复现过两次**（09-24 `ccmni2` 空转 46 分钟、09-28 `ap0+ccmni4` `in_subnet=false`），只是运气好第二次 14s 连上了。**不是"我没这个 bug"，是"这个 bug 在我这儿没致败"。**

另有一次 09-28 22:30:14 `conn_fail stage=PREFLIGHT reason=no_wifi_network detail=WLAN 处于关闭状态 ms=4` —— 这是 v2.3.2 新增 PREFLIGHT **正常工作**的样子，也是 FR-22 要对齐的目标形态。

### 2.4 现有日志答不出的五个问题（本文最硬的约束）

| # | 问题 | 为什么答不出 |
|---|---|---|
| 1 | socket 到底抛的是 ECONNREFUSED / EHOSTUNREACH / ETIMEDOUT / ENETUNREACH？ | `connectSocketWithRetry` 的四处失败只进 Timber（`PtpSessionManager.kt:168,174,177` 与成功分支 `:163`），**从不进 `eventLogger`** → 导出日志里一行都没有。**这是当前最大的盲区，直接决定 D1 与 D5 谁主导致败** |
| 2 | `reachable=false` 是端口拒绝、超时、还是路由不可达？ | `PtpIpProbe` 有 `ProbeResult` 五档（`:30`），但 `tcpConnectOnly` 是 `catch (e: Exception)` 全吞（`:135-138`），`WifiDirectConnector.kt:351-354` 又把它压成布尔 |
| 3 | 是谁每 180ms 触发一次连接？ | `conn_attempt`（`ConnFunnel.kt:151`）不带调用来源。**D2 的放大机制可证，触发者不可证** |
| 4 | 报障的是什么手机？ | 三份用户日志**一份都判断不出机型**。`ConnFunnel.kt:149` 的 INTENT step 里有"机型/ROM"，但 `conn_result` 事件不带、导出的指标基线也不按机型分组 |
| 5 | 用户当时在不在相机热点上？ | `isOnCameraAp()` 的结果从不落日志；`resolveNetwork` 三路各自返回什么、最终绑到哪个 netId，也从不落日志 |

---

## 三、优先级判据与总表

### 3.1 判据

1. 沿用 v2.5 §3.1：**影响"能不能连上"的排最前**；触碰核心区的必须带闸门 + 真机矩阵。
2. 本期新增一条：**没有数据的判据改动一律后置**。D1/D3/D5 的修法都依赖"失败时 socket 抛什么"和"用户是什么机型"，这两项数据现在为零 → FR-18 必须先单独发一版诊断包。
3. 收敛类（拆掉我们自己造的风暴与占坑）优先于放宽类（把预算调大）。前者是纯收益，后者是拿"等更久"换"成功率"，必须配原因码细分才不劣化体验。

### 3.2 缺陷 ↔ FR 对应表

| 缺陷 | 对应 FR | 批次 | 粗量 | 风险 |
|---|---|---|---|---|
| §2.4 五问全答不出 + D6 口径失真 | **FR-18** 连接链路可观测性补齐 | A/P0 | M | 低 |
| D2 连接请求自我风暴 | **FR-19** 连接请求全链路串行化 | B/P1 | S | 低 |
| D1 绑到不覆盖相机的网络 | **FR-20** 绑网前强制子网校验 | B/P1 | M | **中** |
| D3 探测自我占坑 | **FR-21** 探测预算化 | B/P1 | M | **中高** |
| D4 放弃预算 < 成功耗时 | **FR-22** 放弃预算与实测 p95 对齐 | C/P2 | S | 中 |
| D5 蜂窝/VPN 被当类 WiFi | **FR-23** 本地接口按性质分类 | C/P2 | M | 中 |
| §2.2 预检被整体跳过 | **FR-24** PREFLIGHT 软硬分离 | C/P2 | S | 低 |
| §1.1 单次建链 84.75s 无上限 | **FR-25** 建链外层看门狗 | C/P2 | S | 中 |

S/M/L ≈ 0.5 / 1.5 / 4 人日。A 批次 ≈ 20%，B 批次 ≈ 45%，C 批次 ≈ 35%。

### 3.3 批次间的硬依赖

```
FR-18（诊断包，先发）
   ├─→ FR-21 需要 probe_count 量出机身回收半开会话的真实耗时，才能定 PROBE_SETTLE_MS
   ├─→ FR-22 需要 TCP 段真实 p95，才能定放弃预算下限
   ├─→ FR-23 需要 ap_ctx 收集足够机型样本，才能定接口分类名单
   └─→ FR-25 需要 socket_try 量出单次 connect 的真实分布，才能定看门狗总预算
FR-19 / FR-20 / FR-24 不依赖 FR-18，可与之并行
```

**排期结论**：FR-18 单独发一个诊断包给报障用户，拿到一轮回填数据后再开 B/C 批次。FR-19/20/24 三项低风险收敛可以跟 FR-18 同车。

---

## 四、功能需求

> 每个 FR 固定六段：**现状证据 / 目标 / 范围与边界 / 验收标准 / 闸门 / 风险**。

### A 批次（P0）

#### FR-18 连接链路可观测性补齐

- **现状**：§2.4 五问全答不出；`stageGaps` 口径已被 gen=49 的 576ms vs 121527ms 证伪。
- **目标**：一次连接失败后，导出日志能独立回答"手机在哪张网、绑到哪个 netId、socket 抛什么、探测几次、谁触发的、什么机型"。
- **范围与边界**：**只加日志与修口径，零行为改动**。八个子项：

| 子项 | 落点 | 新增/修改 |
|---|---|---|
| 18a socket 级错误码 | `PtpSessionManager.kt:163,168,174,177` | 四处 Timber 旁并列 `eventLogger.event("socket_try", "n" to attempt, "err" to e.javaClass.simpleName, "msg" to e.message, "ms" to elapsed)` |
| 18b 探测分级 | `PtpIpProbe.kt:122-139`、`WifiDirectConnector.kt:351-354` | `tcpConnectOnly` 返回 `ProbeResult` 而非布尔（内部保留布尔重载供既有调用点）；`sta_preconnect` 带 `result=` 五档 |
| 18c 调用来源 | `ConnectionManager.kt:250` + `ConnFunnel.begin` | `conn_attempt` 加 `caller=`，枚举 `user_tap` / `reconnect_trigger` / `health_worker` / `restore_paired` / `wifi_state` / `ptp_session` |
| 18d 绑网决策 | `WifiDirectConnector.kt:334` | 新增 `net_bind`：三路各自返回的 netId、胜出者、`covers(host)` 真假、`bindProcessToNetwork` 返回值 |
| 18e 环境上下文 | 每次 `conn_attempt` | 新增 `ap_ctx`：`on_camera_ap=`、`ssid_is_nikon=`（**只记布尔**）、`cellular_on=`、`vpn_present=`、`hotspot_iface=` |
| 18f 设备标识 | `ConnFunnel.kt:198-204` | `conn_result` 加 `model` / `rom` / `sdk` / `app_version`；`BaselineMetrics` 的连接段按 ROM 家族分组 |
| 18g 口径修正 | `ConnFunnel.kt:216-227`、`ConnectionManager.kt:317` | `beginWifiPairing` 之后、`ptpSession.connect` 之前补一个 `ROUTE` 卡点（`Stage.ROUTE` 已存在，`ConnFunnel.kt:49`），让 TCP 段只含真实建链；`stageGaps` 改为按 stage 名归并同名重访 |
| 18h 探测计数 | 新增 per-generation 计数器 | `conn_result` 带 `probe_count=`；指标基线连接段加一行"每次尝试的探测次数 p50/p95" |

- **验收**：
  ① 自测机跑一次连接，导出日志里 `socket_try` / `net_bind` / `ap_ctx` / `caller` / `probe_count` 五类新事件**全部出现且字段非空**；
  ② 18g 可核对：同一次尝试的 `TCP=` 与"原始时间戳 `connect phase=socket` → `conn_stage TCP` 之差"误差 < 200ms；
  ③ **脱敏**：导出物里搜不到 SSID 原文、BSSID、MAC、相机序列号（`docs/指标口径-v2.5.md:63-67` 的既有承诺）；`ap_ctx` 只有布尔；
  ④ `docs/指标口径-v2.5.md` §1 的阶段表同步改写（新增 `ROUTE` 一行、订正"相邻阶段时间差"的说法），并升版为 `指标口径-v2.6.md`；
  ⑤ 现有单测全绿，`ConnFunnel.Reason` 码值零变更（`:59-61` 的契约）。
- **闸门**：**不设**。纯观测，无用户可见新行为（同 v2.5 对 FR-01/07/09/15 的裁定）。
- **风险**：**低**。唯一注意点是日志环只有 3 × 512KB（`指标口径-v2.5.md:9`），18a/18b 会显著增加行数 → 需要评估是否把 `socket_try` 限制为"只记失败与首次成功"，避免把用户真正需要的上下文挤出去。**这条要在实现时定量核对，不许凭感觉。**

### B 批次（P1）

#### FR-19 连接请求全链路串行化

- **现状**：D2。守卫盲区 ≥2.4s + `pairingJob` 覆盖不取消 → 5.7s 内 15 条 `sta_try` / 4.9s 内 22 条，80% 样本 `superseded`。
- **目标**：一次用户意图 = 一个 generation。`superseded` 占比降到接近 0。
- **范围与边界**：
  ① `ConnectionManager.kt:239` 的守卫补 `pairingJob?.isActive == true` 判据（与 `:834`、`:1058` 两处已有守卫的写法对齐——那两处**已经**判了 `pairingJob`，只有 `:239` 漏了）；
  ② `learnFirst` 分支（`:275-301`）覆盖 `pairingJob` 前先 `cancel()`；
  ③ 更彻底的做法是把"学地址"整段收进 `WifiDirectConnector`，让它也受 `generation` 令牌管——**本期选①②的最小改法**，③留到 FR-20 一起评估；
  ④ 依赖 FR-18c 的 `caller` 字段先确认真实触发者，**若数据显示触发者是用户狂点，则①②已足够；若是程序自触发，必须追加定位**。
- **验收**：① "5 秒内连点连接按钮 5 次"用例，日志里只产生 1 个 generation、0 条 `sta_cancel`；② 复现日志2 的两段风暴条件（拔插 WiFi 触发 `network_restored`），`superseded` 占比 < 5%；③ 守卫按 endpoint 区分的行为不回归——用户手动改 IP 重连仍能立即生效。
- **闸门**：`v26_conn_serialize`（`ConnFlags`，默认**开**）。关掉 = 回到 v2.3.2 的守卫。
- **风险**：**低**。纯收敛，不碰协议、不碰网络。

#### FR-20 绑网前强制子网校验

- **现状**：D1。三路选网都会返回"任意 WiFi"，`bindToActiveWifi` 还把绑网当副作用。
- **目标**：绑网前必须确认这张网覆盖相机 IP；不覆盖就不绑，落到已有的 `defaultRouteFallback` 分支（它至少会打 `in_subnet` 诊断）。
- **范围与边界**：
  ① 删 `StaNetworkRequester.kt:175-179` 的 `return fallback` 与 `WifiNetworkMonitor.kt:162` 的 `return anyWifi`，改为返回 null；
  ② 摘掉 `WifiManager.kt:384` 的 `bindProcessToNetwork` 副作用，绑定动作只留 `WifiDirectConnector.kt:334` 一处；`bindToActiveWifi()` 退化为纯查询（改名或标注，避免"bind"这个动词骗人）；
  ③ **必须保留** `isHotspotTopology()`（`LocalNetworkInterfaceResolver.kt:171-181`）分支：手机自开热点时热点接口属 Tethering、不在 `allNetworks` 里，此时子网校验必然失败，只能走默认路由。这是 v2.0.1 修好的场景，不许打回去；
  ④ 新增原因码 `bind_failed` 的实际使用（`ConnFunnel.Reason.BIND_FAILED` 已定义于 `:85`，当前**零调用点**）。
- **验收**：① 日志3 同型机器复跑，`sta_preconnect result=` 非 OK 时**必须**同时出现 `in_subnet` 与 `net_bind`；② SSID 非 `NIKON_*` 且网关探测被拒时，不再发起连接，直接 `not_on_camera_ap`；③ 手机自开热点 + 相机连上来的拓扑**单独回归**，连接成功率不低于基线。
- **闸门**：`v26_bind_subnet_check`（默认**开**）。关掉 = 回到"任意 WiFi 也绑"的 v2.3.2 行为。
- **风险**：**中**。热点拓扑是雷区，`LocalNetworkInterfaceResolver.kt:18-27` 的类注释就是当年踩坑的记录。必须 ③ 单独回归，且闸门保留。

#### FR-21 探测预算化

- **现状**：D3。一次连接 20 次量级 TCP 探测，`PROBE_SETTLE_MS=400ms` 只覆盖实测回收耗时（~35s）的 1%。
- **目标**：把探测次数与间隔从"隐含在控制流里"变成"有硬上限的显式预算"。
- **范围与边界**：
  ① 一个用户发起的连接内，对 15740 的 TCP 探测总数设硬上限（**建议 ≤3，最终值由 FR-18h 的 `probe_count` 基线定**），用 per-generation 计数器兜住，超限直接跳过探测进入真实连接；
  ② `ApGatewayResolver` 的 4 次出厂兜底（`:180-186`）只在"网关完全学不到"时做；`probe=rejected`（网关学到了但端口不通）时**不再逐个试**——那 4 个地址与刚被拒的网关没有信息差；
  ③ 失败后的 `probeReachable`（`WifiDirectConnector.kt:388`）与下一轮的 `waitUntilReachable`（`:347`）合并，同一轮内不重复探；
  ④ `PROBE_SETTLE_MS` **不在本期定值**：先用 FR-18h 量出机身回收半开会话的真实耗时分布，再在 v2.6.x 定值。拍脑袋从 400ms 改到 35s 会让每次连接白等半分钟，比现在更糟。
- **验收**：① 同型机器连续 10 次冷启动连接，首连成功率与基线对比**不下降**；② 导出日志里单次尝试的 `probe_count` p95 ≤ 上限；③ `PROBE_TCP_ONLY` 闸门开/关各 10 次做 A/B，结论写回 §十；④ 日志3 那种"第一次探通、之后 4 分钟全 rejected"不再复现。
- **闸门**：`v26_probe_budget`（默认**开**）。关掉 = 回到 v2.3.2 的探测密度。
- **风险**：**中高**。探测减少会让"相机休眠需要唤醒"的场景更容易首轮失败——而这正是 `PRECONNECT_WAIT_MS` 当初存在的理由（`WifiDirectConnector.kt:83-88` 引 ZDROP `refreshing discovery before connect`）。A/B 必须做，闸门必须留。

### C 批次（P2，依赖 FR-18 的回填数据）

#### FR-22 放弃预算与实测 p95 对齐

- **现状**：D4。失败恒定 21s 收口，成功需要 26.4~181.3s。
- **目标**：预算来自实测，不来自直觉。
- **范围与边界**：
  ① `UNREACHABLE_GIVE_UP` 的判据从"连续 3 次不可达"改为"连续 3 次不可达 **且** 累计已等 ≥ `GIVE_UP_FLOOR_MS`"，`GIVE_UP_FLOOR_MS` 取 FR-18 回填的 TCP 段 p95（**按日志2 现有数据 ≥120s，最终值以回填为准**）；
  ② UI 把剩余预算显式化（"第 2/3 次尝试，约还需 40 秒"），别让用户对着静止的转圈狂点——狂点又触发 D2；
  ③ **必须与 FR-20 的原因码细分同车上线**。否则只是把 21s 的明确失败换成 2 分钟的模糊等待，体验净劣化。
- **验收**：① 日志2 里那批 26~181s 才成功的样本，在新版上不再被判失败；② "相机真的不在"的场景仍在 ≤30s 内给出 `not_on_camera_ap` / `camera_unreachable` 的明确文案，不出现"等 2 分钟然后报未知错误"；③ UI 预算文案与代码常量同源（不许两处各写一个数）。
- **闸门**：`v26_giveup_floor`（默认**开**）。关掉 = 回到"连续 3 次即收口"。
- **风险**：**中**。等待变长与"快速失败"是一对真实的取舍，只能靠 ③ 的原因码细分兜住。

#### FR-23 本地接口按性质分类

- **现状**：D5。15/15 次 `sta_fallback` 全 `in_subnet=false`，ifaces 里全是 `ccmni*` / `vgate0` / `ap0`。
- **目标**：`hasLocalWifiLikeInterface()` 名副其实——只认 WiFi 客户端接口与热点接口，排除蜂窝与 VPN。
- **范围与边界**：
  ① 判据从"有任意非回环 IPv4 接口"改为"有非蜂窝、非 VPN、非回环的 IPv4 接口"；
  ② **优先不按名字判**：用 `ConnectivityManager` 的 `NetworkCapabilities.TRANSPORT_CELLULAR` + `LinkProperties.interfaceName` 与内核枚举做索引比对；接口名前缀（`ccmni*` / `rmnet*` / `tun*` / `tap*` / `vgate*`）只做兜底；
  ③ `LocalNetworkInterfaceResolver.kt:34-35` 的注释自己就警告过"不要按接口名硬编码"——本期不许违背它，名单必须由 FR-18e 的 `ap_ctx` 回填数据支撑；
  ④ WiFi 关着只开移动数据时，应在 PREFLIGHT 就 5ms 收口（对齐我的日志 09-28 22:30:14 那条 `stage=PREFLIGHT detail=WLAN 处于关闭状态 ms=4`），不再空转 30s×N。
- **验收**：① 关 WiFi + 开移动数据，点连接 → ≤1s 内报 `no_wifi_network`，日志里 0 条 `connect phase=socket`；② 开 VPN + 连相机热点 → `ap_ctx` 里 `vpn_present=true`，且 FR-24 的软告警上屏；③ 手机自开热点拓扑不回归（同 FR-20 ③）；④ 接口分类名单在 §十 留档，注明每个前缀来自哪台机型的回填数据。
- **闸门**：`v26_iface_classify`（默认**开**）。关掉 = 回到"任意非回环 IPv4 即算类 WiFi"。
- **风险**：**中**。判据收紧后，把热点接口叫奇怪名字的机型可能退回 RC-0 误判（`LocalNetworkInterfaceResolver.kt:25-27` 记录的"9 轮尝试全 `no_wifi_network`、恒定 16.2s"）。**必须先有回填数据再定名单。**

#### FR-24 PREFLIGHT 软硬分离

- **现状**：`ConnectionManager.kt:253` 的 `!apGatewayResolver.isOnCameraAp()` 让**全部**预检在相机热点上被跳过，包括 `VPN_ACTIVE`（`PreflightGate.kt:238-250`）与 `AVOID_BAD_WIFI`（`:149`）。而日志2 那台机器正好有 `vgate0`。
- **目标**：硬阻断沿用现有跳过规则（在相机热点上拿权限项拦用户是倒退，`:251-252` 的注释成立），软告警一律执行。
- **范围与边界**：① `PreflightGate.Item` 增加 `severity`（`BLOCKING` / `WARNING`）；② `firstBlocking`（`:81`）只返回 `BLOCKING`；③ 新增 `warnings(channel)` 返回全部 `WARNING`，写进 `ap_ctx` 与 UI 状态行；④ VPN / 智能切网 / 电池优化三项降为 `WARNING`。
- **验收**：① 开 VPN 连相机热点，UI 出现"检测到 VPN 通道，相机流量可能被劫持"且**不阻断**连接；② 关 WLAN 仍然 5ms 硬阻断（我的日志 22:30:14 的行为不回归）；③ 软告警不改变任何连接/重试/绑定行为（与 v2.1.1 小米引导那次"仅改文案"的裁定同款）。
- **闸门**：`v26_preflight_soft`（默认**开**）。关掉 = 相机热点上跳过全部预检（现状）。
- **风险**：**低**。

#### FR-25 建链外层看门狗

- **现状**：`CONNECT_TIMEOUT_MS=30000`（`PtpSessionManager.kt:40`）只是传给 `Socket.connect` 的参数（`:161`），`soTimeout` 也是 per-read 的。`connectSocketWithRetry`（`:150-181`）的名义上限 = 5×30s + 4×0.3s ≈ **151.2s**（`CONNECT_RETRY_MAX=5` @ `:48`，间隔 300ms @ `:51`，仅当每次都抛非 `SocketTimeoutException` 的 IOException；抛超时则 `:166-169` 不重试，上限 30s）。实测单次 socket 段 84.75s / 97.74s 落在名义上限内，**但单次 `connect` 明显超过了 30s** —— 而 §2.4 问题 1 说明我们现在无法知道它抛的是什么。
- **目标**：ROM/内核不遵守超时时，整条连接仍有一个我们自己的上限。
- **范围与边界**：① `withTimeoutOrNull(总预算)` 包住 command / event 两条 socket 的建立，超时主动 `close()` 并记 `socket_watchdog` 事件；② 总预算必须 > FR-22 量出的 p95，两者一起调；③ **先看 FR-18a 的回填数据再决定要不要做**——如果数据显示 84.75s 是"3 次各 30s 的合法重试"，那看门狗只会杀掉慢但能成的连接，本项应当降级或撤销。
- **验收**：① 构造一个黑洞地址（同网段不存在的 IP），连接必须在总预算内收口并给出原因码，不出现 97s 无日志；② 日志2 的 84.75s 那次（最终成功）在看门狗下**仍然成功**——总预算设置正确性的唯一判据。
- **闸门**：`v26_conn_watchdog`（默认**关**）。核心区改动，按 §8.1 铁律 1 默认关，真机矩阵过了再开。
- **风险**：**中**。看门狗会杀掉"慢但能成"的连接。这是本期唯一一项"可能不该做"的 FR，③ 的前置判断必须诚实执行。

---

## 五、闸门清单（新增，登记进 `ConnFlags`）

| key | 所属 FR | 默认 | 关闭后的行为 |
|---|---|---|---|
| `v26_conn_serialize` | FR-19 | 开 | 守卫只判 `connector.isActive`，`pairingJob` 覆盖不取消（v2.3.2 现状） |
| `v26_bind_subnet_check` | FR-20 | 开 | 子网不匹配也返回任意 WiFi 并绑网（v2.3.2 现状） |
| `v26_probe_budget` | FR-21 | 开 | 探测次数无上限，出厂兜底 4 个地址照试（v2.3.2 现状） |
| `v26_giveup_floor` | FR-22 | 开 | 连续 3 次不可达即收口，不看累计等待（v2.3.2 现状） |
| `v26_iface_classify` | FR-23 | 开 | 任意非回环 IPv4 接口即算"类 WiFi"（v2.3.2 现状） |
| `v26_preflight_soft` | FR-24 | 开 | 相机热点上跳过全部预检（v2.3.2 现状） |
| `v26_ip_promote` | FR-26a | 开 | 只有整条连接成功才记真实相机 IP，重启回落 192.168.1.1（v2.3.2 现状） |
| `v26_usb_skip_recovery` | FR-26b | 开 | 机身不应答时仍做残留会话恢复，单次失败 16s 而非 5.1s（v2.3.2 现状） |
| `v26_usb_reconnect_ramp` | FR-27 | 开 | 每轮重连都从 1000ms 重新开始，273 秒敲机身 45 次（v2.2~v2.3.2 现状） |
| `v26_conn_watchdog` | FR-25 | **关** | 只靠 `Socket.connect` 的 30s 参数，无外层上限（v2.3.2 现状） |

- FR-18 **明确不设闸门**（纯观测，无用户可见新行为），而非漏写。
- 全部走 `ConnFlags`（既有先例 `conn22_*` / `v25_*`），受设置页「v2.2 连接改进」总闸 `conn22_enabled`（`ConnFlags.kt:108`）管辖。新键统一 `v26_` 前缀，登记进 `DEFAULTS`（`ConnFlags.kt:110`）。
- 调试包的设置页 →「开发选项」面板（`971c60d` 引入）需列出本期 7 项，验收的人在手机上就能拨。

---

## 六、指标

| 指标 | 现值（出处） | 责任 FR | 目标 |
|---|---|---|---|
| `superseded` 占比 | **80.0%**（日志2，n=45） | FR-19 | < 5% |
| 单次尝试 TCP 探测次数 p95 | 未采集 | FR-18h 定义，FR-21 改进 | ≤ 预算上限 |
| TCP 建链 p50 / p95（**原始时间戳口径**，最近秩法） | p50 = **29.87s**，p95 = **97.74s**（日志2，n=5，升序 9.80/15.05/29.87/84.75/97.74）；自测机 0.012s | FR-18g 修正口径，FR-20/23/25 改进 | 回填后重定，**不预设百分比** |
| 失败收口耗时 | 恒定 20.1~21.4s（日志3，n=6） | FR-22 | 与 TCP p95 对齐 |
| 首连成功率（同型机器 10 次冷启动） | 日志2 4/45 = 8.9%（含风暴污染，**不可作为基线**） | FR-19/20/21 | FR-18 诊断包回填后重定基线 |
| `in_subnet=false` 时的诊断完备率 | **0%**（绑错网时一条不打） | FR-18d + FR-20 | 100% |
| 导出日志可回答 §2.4 五问 | **0 / 5** | FR-18 | 5 / 5 |

⚠ 日志2 的 8.9% 是**被 D2 污染的数字**（45 个样本里 36 个是自我风暴产生的 `superseded`，根本不是独立尝试）。**不许拿它当基线对外引用**，也不许拿它算"提升了多少倍"。真实基线必须由 FR-18 诊断包重新采集。

---

## 七、里程碑

| 里程碑 | 内容 | 门槛 |
|---|---|---|
| N0 | **FR-18 诊断包**单独发一版（可含 FR-19/20/24 三项低风险收敛同车） | 验收 ①~⑤ 全过；导出物脱敏自查过 |
| N1 | 诊断包发给 3 位报障用户 + 自测，按 §九 步骤回填 | **拿到 ≥3 台不同机型、≥5 次失败的完整日志**。数据不够就不进 N2，宁可等 |
| N2 | FR-21（探测预算）+ FR-22（放弃预算）+ FR-23（接口分类） | 三项的常量值全部来自 N1 回填数据，**每一个数字在 §十 留出处** |
| N3 | FR-25（看门狗）—— **仅在 N1 数据显示确有必要时** | §四 FR-25 ③ 的前置判断结论写回 §十 |
| N4 | v2.6.0 发版 | §九 真机矩阵全过；README 与双网盘同步（§8.1 铁律 7） |

> 本版**不设日历日期**。门槛是"N1 的数据到没到"，不是"几号发"。这与 v2.5 §七 的裁定一致：把未验证项混进已验证项一起发，出事时说不清是哪一项造成的。

---

## 八、范围铁律、风险与回退

### 8.1 铁律（沿用 v2.5 §8.1，逐条生效）

1. 曾被真机修过的核心区（PTP 事务锁、下载队列状态机、**连接状态机**）改动必须带闸门；FR-25 额外要求默认关。
2. 不为提速回退既有防跳底 / 防抖 / 幂等设计。**FR-19 是加幂等，不是减。**
3. 不引入新网络栈、不引入遥测 SDK。FR-18 的所有新字段只进本机导出。
4. 硬编码文案一律进资源。
5. 发版必同步 README 与双网盘，沿用 `android-release-publish` 流程。
6. **本期新增**：任何常量值（预算、上限、间隔）必须在 §十 注明"来自哪份日志的哪个分位数"。**不许出现没有出处的数字。**

### 8.2 风险登记

| 风险 | 影响 | 缓解 |
|---|---|---|
| N1 回填数据拿不到（用户不愿按步骤复现） | **高**，且是唯一不由我们控制的一条 | FR-18 的诊断包本身要能"用户随便用、我们顺便收"——`ap_ctx` / `net_bind` / `socket_try` 都是被动记录，不要求用户配合特定操作。§九 的四组对照只是"最好有" |
| FR-20 打回热点拓扑（v2.0.1 的成果） | 中高 | `isHotspotTopology()` 分支强制保留 + 单独回归 + 闸门 |
| FR-21 让休眠相机首轮失败率上升 | 中高 | `PROBE_TCP_ONLY` A/B 各 10 次；`PROBE_SETTLE_MS` 本期不定值 |
| FR-22 把"快速失败"换成"漫长等待" | 中 | 强制与 FR-20 原因码细分同车；验收 ② 卡死"相机真不在"的场景 |
| FR-23 接口名单在某些 ROM 上误伤 | 中 | 优先用 `TRANSPORT_CELLULAR` + 索引比对，名字只兜底；名单必须有机型出处 |
| FR-25 杀掉慢但能成的连接 | 中 | 默认关；总预算 > FR-22 的 p95；验收 ② 用日志2 的 84.75s 那次做判据 |
| FR-18 新增日志把 512KB×3 的日志环挤爆，反而丢掉关键上下文 | 中 | 验收时定量核对行数；`socket_try` 只记失败与首次成功 |
| 把 8.9% 当基线对外引用，之后"提升 10 倍"的说法站不住 | 中 | §六 已明写该数字被 D2 污染、不许引用 |

### 8.3 回退

- 单项：关对应 `v26_*` 闸门即恢复 v2.3.2 行为。
- 整版：每个 FR 独立提交，逐条 revert。
- 三级回退沿用 `ConnFlags.kt:14` 的既有约定：① 闸门 → ② 逐项 revert → ③ `dist/` 里的旧包。

---

## 九、真机验收矩阵

### 9.1 四组对照（每组：冷启动 App → 点一次连接 → 3 分钟内不操作 → 导出日志）

| # | 条件 | 主要验哪条 |
|---|---|---|
| 1 | 关移动数据 + 关 VPN + 只连相机热点 | 基线成功路径；FR-18 五类新事件齐全 |
| 2 | 开移动数据、**不连**相机热点 | FR-23：应 ≤1s 报 `no_wifi_network`，0 条 `connect phase=socket` |
| 3 | 装一个 VPN 并保持连接，再连相机热点 | FR-24：软告警上屏且不阻断；对照日志2 的 `vgate0` 形态 |
| 4 | 手机自开热点、相机连上来（热点拓扑） | FR-20 ③ / FR-23 ③ 的不回归 |

### 9.2 每次必须附带

机型 · Android/HarmonyOS 版本 · 相机型号与固件版本 · 是否装过 SnapBridge · 点连接时相机屏幕停在哪个画面 · 是否装过 VPN/网络加速类 App。

### 9.3 机型组合（沿用 v2.5 §九，本期重点列）

| 组合 | 重点 |
|---|---|
| Z8 + 小米 15 Pro | AP / STA 双模；FR-22 的预算文案、FR-24 的软告警 |
| Z50II + vivo | **FR-21 的主战场**：`WifiDirectConnector.kt:571-577` 记录的"探测占坑 ~35s"就是这台机器 + 这个机身量出来的 |
| 日志2 那台机器（`ccmni4` + `vgate0`，机型待 FR-18f 回填） | FR-20 / FR-23 / FR-25 的全部判据都以它的回填数据为准 |
| 日志3 那台机器（6/6 `tcp_timeout`、无 `sta_fallback`，机型待回填） | **D1 的唯一样本**。FR-20 验收 ① 必须在它上面复跑 |

### 9.4 通用回归脚本

连接 5 次取分布 → 传 20 张 RAW → 传输中拔 WiFi 一次 → 重启 App 复连 → 导出诊断 → **核对 `probe_count` 与 `socket_try` 行数**。
矩阵结果写回 §十，任何未跑项标注"未验证"，**不允许以"编译通过"顶替**。

---

## 十、变更记录与待拍板

### 10.1 本文的证据边界（诚实声明）

| 结论 | 证据等级 |
|---|---|
| D2 重试风暴的**放大机制**（守卫盲区 + 孤儿 job） | **已验证**：代码 `ConnectionManager.kt:239,280` + 日志2 两段风暴的 `sta_try`/`sta_cancel` 成对时间戳 |
| D2 风暴的**触发者**（谁每 180ms 调一次） | **未确定**。`conn_attempt` 不记调用来源，静态阅读排除了 UI 点击（三处调用点全是 click handler）与 HealthWorker（15min 周期），但无法唯一锁定 → FR-18c 补齐后回填 |
| D3 探测自我占坑 | **已验证**：日志3 22:31:25 探通 → 之后 4 分钟全拒；代码注释 `WifiDirectConnector.kt:571-577` 有同形态的 09-19 实测记录 |
| D4 预算 < 成功耗时 | **已验证**：失败 20.1~21.4s（n=6）vs 成功 26.4~181.3s（n=4）；v1.3.1 无 `UNREACHABLE_GIVE_UP`/`RETRY_BUDGET` 已由 `git log -S` 与 `git show v1.3.1:` 双向确认 |
| D6 漏斗口径失真 | **已验证**：gen=49 原始 576ms vs 上报 121527ms |
| D1 绑错网是日志3 的致败主因 | **推断待验证**。链路完整（三路都返回任意 WiFi + `in_subnet` 只在 null 分支打 + 日志3 零条 `sta_fallback` + socket 秒失败），但缺 socket errno 无法定谳 → FR-18a |
| D5 走蜂窝黑洞是日志2 84.75s/97.74s 的致败主因 | **推断待验证**。形态吻合（`in_subnet=false` + 无 wlan0 + 有 VPN + 单次 connect 超 30s），同样缺 errno → FR-18a |
| "老版本能连、新版本连不上"是版本回归 | **未证实，且现有日志倾向于否定**。更省假设的解释是 D4。需要该用户报出具体版本号 + §九 老包/新包同条件对比 |
| "9 月 24 日晚还能连" | **无对应日志**。日志1 那位用户 09-23（v2.3.1）就已连续失败 |

### 10.2 决议（2026-09-29，由工程侧拍板；用户只承担最终打包件的测试）

| # | 议题 | 决议 | 理由 |
|---|---|---|---|
| 1 | FR-18 要不要**单独**发一版诊断包 | **不发**，FR-18 + FR-19 + FR-20 + FR-24 同车出 | 用户只做最终测试，不该承担多轮装机成本。三项收敛都是低风险、可独立关闸的，出问题回退单项即可 |
| 2 | FR-25 建链看门狗要不要做 | **本期不做**，等 `socket_try` 数据回填后重新立项 | §四 FR-25 ③ 自己就写了：如果实测的 84.75s 是「5 次各 ~17s 的合法重试序列」，看门狗只会杀掉慢但能成的连接。现在没有 errno 数据，做一个会杀成功路径的东西、还默认关着没人开，等于死代码 |
| 3 | 连接后 `op=0x1005/0x1007` 回 `0x2013` 并入还是单独立项 | **单独立项**，不进 v2.6 | 它属 PTP 命令层不属连接层；混车会让 FR-20/21 的回归判据分不清是哪一项起的作用 |
| 4 | `PROBE_SETTLE_MS` 本期定不定值 | **不定值**，保持 400ms | 定值需要"探测后间隔 N 秒再连"的真机扫描（N=0.4/2/5/10/20/35 × 多次），那是独立的一轮真机工作量，不该压给只做最终测试的人。FR-18h 先把 `probe_count` 量出来 |

### 10.2b 实现期对本文的三处偏离（已落地，如实记录）

| 本文原写 | 实际实现 | 为什么改 |
|---|---|---|
| FR-18h「对 15740 的探测总数设硬上限（建议 ≤3）」 | **只做 ②③**（出厂兜底收敛 + 同轮探测复用），不加抽象计数器上限 | ②③ 本身就是把上限从 ~20 压到 ~6 的具体实现；再叠一个没有数据支撑的阈值属于为假想的未来做设计。等 `probe_count` 基线出来，若仍不够再立 |
| FR-24 ①「`PreflightGate.Item` 增加 `severity`（BLOCKING/WARNING）」 | **不加新字段**，`warnings()` 直接建在既有的 `Item.blocking: Boolean` 上 | `blocking` 本来就在表达"硬阻断还是提醒"这层意思（vpnNotice / smartSwitchAvoid / batteryExemption / autostartPolicy 四项早已是 `blocking=false`）。再叠一个枚举是同一件事记两遍，迟早对不上 |
| FR-18 ④「口径文档升版为 `指标口径-v2.6.md`」 | **原文件就地修订** + 标题标注「v2.6 修订见 §6」 | PRD v2.5 与代码注释多处按文件名引用 `指标口径-v2.5.md`，改名会制造一批死链；内容与代码仍然一一对应即可 |

### 10.3 变更记录（本文自身的修订，倒序）

| 日期 | 变更 |
|---|---|
| 2026-09-29（第四次） | 新增 **§十六 FR-28 USB 通道全链路**：先否定"机身不应答"这个笼统叙述（27 次 1–26ms 即刻失败 vs ≈30 次 5s 真超时），再修两条 API 误用（`claimInterface` 返回值被丢、`bulkTransfer` 只判 `<0`）+ 两条节律（OpenSession 重试走到 clearHalt、拆链路前发 CloseSession）。四款竞品只落到"它们都有 CloseSession"这一条硬事实；`setConfiguration` 那条是**误命中、已撤回**，`changeToPtpMtpMode` 反汇编取不到、不作实现依据。**用户真机实测 USB 已连通**，据此发 v2.6.1 |
| 2026-09-29（第三次） | **v2.6.0 正式发布**；§十四 重排为 14.0 发布事实 + R1 收口 / R2 等日志 / R3 竞品驱动 / R4 竞品逆向 / R5 不做；新增 §13.6（逆向对照：主机注册已与 ZDROP 同形，撤销 T-S4 那半）；用户解除"竞品逆向不做"，该项从 R4「不做」移入 R4「要做」 |
| 2026-09-29（第二次） | 文档合并：v2.5 正文并入（新增 §十三 逐项审计、§十四 唯一排期、§十五 合并说明），v2.5 原稿移入 `docs/archive/`；§1.4「目标用户与使用场景」自 v2.5 迁入并订正 `v25_raw_pair` 默认值；顺手删掉尾部一份重复的 §10.3 残片 |
| 2026-09-29 | §10.2 四项拍板落定 + 新增 §10.2b 记三处实现期偏离。A/B/C 三批次的 FR-18/19/20/21(②③)/22/23/24 已实现，FR-25 撤销出本期 |
| 2026-09-28 | 首版。基于 4 份真机日志（`n-link_logs_1790385975120(1)` / `1790426146869` / `1790519817936` / 我的最新连接日志）+ v2.3.2@`874a2d3` 代码只读排查 + v1.3.1↔v2.3.2 git 考古 |
| 2026-09-28 | 自查修正 4 处数字：`sta_fallback` 12→**15** 次（`in_subnet=true` 出现 0 次）；TCP p50 15.0s→**29.87s**（按仓库自身的最近秩法，n=5 秩=3）；风暴窗口 2 结束戳改 `20:24:07.322` 并把「16 代/23 代」订正为「15 条/22 条 `sta_try`」（gen=8、gen=28 是落日志前就被顶掉的跳号）；息屏零日志区间改为 `02:04:42.535 → 02:48:44.611` |

### 10.4 实现落地记录（2026-09-29）

改动面 15 个源文件 + 3 个新测试类 + 2 份文档。提交：`995f564`（FR-18~24 主体）与紧随其后的补充提交（FR-20④ / FR-22②）。

| FR | 状态 | 说明 |
|---|---|---|
| FR-18 ①~⑧ | **全部完成** | 18a `socket_try`（errno 首次进导出日志）· 18b `ProbeResult.NO_ROUTE` + `tcpScreen` + `ap_probe`/`sta_preconnect` 带档位 · 18c `caller` 六档枚举 · 18d `net_bind` · 18e `ap_ctx` · 18f `conn_result` 带 `model/rom/sdk/av` + 基线「设备分布」行 · 18g `ROUTE` 分界卡 + `revisits` 脏样本过滤 · 18h `recordProbe` + 探测次数百分位行 |
| FR-19 | **完成** | 守卫补 `pairingJob` 判据（与同类 `:834`/`:1058` 两处写法对齐）+ learnFirst 覆盖前 `cancel()` |
| FR-20 ①~④ | **完成** | 含 ④：`BIND_FAILED` 原为**零调用点的 dead branch**，现已接上；`describeStaFailure` 增加与 `no_wifi_network` 分开的文案 |
| FR-21 ②③ | **完成**（①④ 按 §10.2 决议不做） | 出厂兜底只在网关完全学不到时才试；同轮探活结论复用到失败归类，不重复探第二次 |
| FR-22 ①②③ | **完成** | `GIVEUP_FLOOR_MS = 120_000`（出处已写进常量注释）· ② 剩余秒数经 `onRetry` 上屏 · ③ 与 FR-20 同车 |
| FR-23 | **完成** | 主判据 = `ConnectivityManager` transport，前缀表只兜底；`subnets()` 一并改为排除蜂窝（`ccmni4 10.3.9.110/8` 这种 /8 曾被并进盲扫网段） |
| FR-24 | **完成** | 未新增 `severity` 字段，复用既有 `Item.blocking`（见 §10.2b） |
| FR-25 | **本期不做** | 见 §10.2 决议 2 |

验证证据：

| 项 | 结果 |
|---|---|
| 基线（改动前） | `BUILD SUCCESSFUL`，APK 20,656,756 字节；单测 **208 / 0 failures / 0 errors / 1 skipped** |
| 改动后全量单测 | **222 / 0 failures / 0 errors / 1 skipped**（净增 14 例，零回归） |
| 新增测试 | `PtpIpProbeGradeTest`（errno→档位 5 例，含"route 分支必须抢在 timeout 之前"的顺序钉死）· `LocalInterfaceClassifyTest`（前缀表 + `ap0`/`wlan0`/`swlan0` 不许误排的 RC-0 护栏 3 例）· `ConnFunnelV26Test`（ROUTE 切分 TCP、revisits 标脏、probe_count、superseded 收口 6 例） |
| `lintDebug` | 7 个 Error，**经逐行核对全部先于本次改动存在**：`NlGlass.kt:138`、`UpdateChecker.kt:323`、`UsbPtpManager.kt:1268`、`themes.xml:26`、`wifi-ap/WifiManager.kt:221/340`（我的改动在 377~393 区间）。按 §8.1 铁律「修复最小化范围、不夹带重构」本期不修 |
| **未验证** | **全部真机行为。** 本期改动落在绑网时序、探测节奏、放弃判据上，JVM 单测一行都覆盖不到 —— 构建通过 ≠ 连得上相机 |

`ConnFunnelStageGapTest` 的三个既有断言**未作修改**：`stageGaps` 的算法对线性序列本来就是对的，问题出在样本被风暴污染。所以选择"标出脏样本并剔出百分位"，而不是改算法去迁就数字。

### 10.5 本期遗留（下一轮）
1. **`camera_unreachable → TCP_TIMEOUT` 这条映射是错的**。`ConnectionManager.kt:872` 把"相机不可达"归进 `tcp_timeout`，于是日志3 那 6 次 socket **1.3 秒秒断**的失败在漏斗里全写着 `reason=tcp_timeout`。FR-18b 现在已把真实档位记进 `sta_classify`，但映射链还没用上它 —— 需要让 `onFail` 把档位带下来，而不是只带一个粗粒度字符串。**没在下手是因为只有映射、没有数据源就会改成猜的**，等 §九 回填一轮再动。
2. `PROBE_SETTLE_MS` 定值（§10.2 决议 4）。
3. FR-21 ① 的探测总上限（等 `probe_count` 基线）。
4. FR-25 看门狗（等 `socket_try` 的 errno 分布）。
5. 连接后 `op=0x1005/0x1007` 回 `0x2013` 单独立项（§10.2 决议 3）。

---

## 十一、v2.6 首轮真机回填（2026-09-29，vivo V2509A / Android 16 / Z 50II）

FR-18 的诊断包第一次跑在真机上，**当场回答了 §2.4 五问里的四问**，也当场推翻了我自己的一条修复计划。

### 11.1 一条被证据推翻的假设（记下来，别再提）

原计划 S1：「`InitFail(fail_reason=1)` 是确定性拒绝，应立即收口不再重试」。

**跨 9 份历史日志统计 `phase=init ok=false`：共 138 次，其中 6 份日志最终 `pair_ok`。**

| 日志 | InitFail 次数 | 最终 pair_ok |
|---|---|---|
| 1789632439230 | 44 | 1 |
| 1789576920029 | 26 | 2 |
| 1789638392202 | 16 | 3 |
| 1789628558566 | 17 | 1 |
| 1790426146869 | 1 | 4 |
| 1789620448543 / 1789576281082 | 11 / 14 | 0 / 0 |

**重试恰恰是这条链路历史上唯一恢复成功过的路径。** 若按 S1 实现，会把 44 次失败后成功、26 次后成功这两类样本全部判死。S1 撤销。

### 11.2 真问题：重试预算从来没跑完过

同一份新日志里，4 个 generation **全部在 attempt 2~4 就被 `sta_cancel` 砍掉**，`conn_result` 一律 `superseded`，`MAX_ATTEMPTS=10` 一次都没用满：

| generation | 被砍时的 attempt | conn_result |
|---|---|---|
| gen=4 | 2 | superseded（60.6s） |
| gen=6 | 2 | superseded（53.8s） |
| gen=8 | 2 | superseded（9.1s） |
| gen=10 | 4 | superseded（152.1s） |

`recoverWifiSession` 直接调 `connector.connect()`、**不经漏斗**，所以它砍人时一行日志都不留。本轮加了 `sta_supersede` 事件（记 `new_gen` / `source` / `mode`）与 `connect(source=...)` 参数，下一轮日志就能直接读出是谁砍的。**这条仍未定谳。**

### 11.3 FR-18 当场兑现的价值

| 问题（§2.4） | 本轮答案 |
|---|---|
| socket 抛什么 errno | `socket_try lane=command ok=false n=1 err=TIMEOUT ms=30027 ... from /10.56.31.140` —— 源地址是**蜂窝**口，30s 白等 |
| 探测为什么失败 | `ap_probe grade=TIMEOUT`（4 个出厂地址全 TIMEOUT）· `sta_preconnect grade=ERROR errno=ConnectException:...ECONNABORTED` |
| 谁发起的连接 | `caller=user_tap` / `caller=restore_paired` / USB 侧 `caller=unknown`（已补 `usb_state`） |
| 什么机型 | `model=V2509A rom=VIVO sdk=36` + 基线「设备分布：V2509A/VIVO×3」 |
| 在不在相机热点上 | `ap_ctx on_camera_ap=false ssid_is_nikon=false`，而 `sta_fallback ... in_subnet=true`（相机在手机自己的热点 10.184.219.14 上） |

顺带**验证了 FR-20/23**：`in_subnet=true` 是 15 份日志里第一次出现（此前 15/15 全 false），`ap0` 被正确保留为类 WiFi 接口，`net_bind bound=true covers=true` 与 `ROUTE=30ms` 都对。`revisits=1` 的脏样本过滤也如期打出了 ⚠ 行。

### 11.4 本轮改动（5 项，均已实现）

| # | 改动 | 证据 |
|---|---|---|
| 1 | `InitFail` 即提升 `realCameraIp`（`camera_ip_promoted`） | InitFail 是**机身自己发的包**，比"端口开着"强得多。旧实现只在整条连接成功时才记，于是重启后回落到 192.168.1.1：01:11:07→01:11:31 对 4 个出厂地址全 TIMEOUT + 一次 30s socket 超时，而相机一直在 10.184.219.14 |
| 2 | `sta_supersede` + `connect(source=)` | §11.2 |
| 3 | USB 原因码改用机器可读 `usbFailCode`，新增 `Reason.USB_PTP_NO_RESPONSE` | `funnelReasonForUsbError` 按**文案关键词**匹配，而那句提示里写着"确认 USB 模式为 PTP/MTP"→ 必然命中 `contains("模式")` → 判成 `no_usb_interface`。可同一秒 `usb_open ok=true model=Z 50II` 已证伪。8 次失败全被这么误报，用户被指去改 USB 模式 |
| 4 | USB `conn_attempt` 补 `caller=usb_state` | 此前全是 `caller=unknown`，"用户插了一次线"与"退避循环重试 8 轮"在日志里无法区分 |
| 5 | 机身不应答时跳过 `recoverStaleSession` | 25 次 `stale_session_recovery` **全部失败、0 次成功**；历史成功案例 OpenSession 只用 **5ms**，失败每次 16s 读超时。对不应答的相机再发 CloseSession+DeviceReady 只是把两次超时串起来：01:08:38→01:09:09 实测单次失败被从 16s 拖到 31s |

### 11.5 仍未定谳的两件事

1. **谁在砍重试**（§11.2）。要下一轮带 `sta_supersede` 的日志才能回答。
2. **USB 为什么不应答 PTP**。`usb_open ok=true model=Z 50II` 说明枚举与 claim 都正常，但 OpenSession 16s 无应答。历史上 USB 成功过 4 次（09-17 ×3、09-19 ×1）对 118 次失败，**长期就不稳**，不是 v2.6 引入的。09-17 那三次成功都发生在 WiFi 侧正在失败/已断的时段，所以"机身单槽跨 USB/WiFi 互斥"这个猜测**被证伪**（USB 成功时 WiFi 并没占着槽）。
   **2026-09-29 用户补充：失败当时相机屏幕是点亮的、没有同时连着别的设备、数据线没换过** —— 三条最常见的成因全部排除。剩下的候选只有：机身 USB 侧 PTP 服务未起（需要相机菜单里 USB 模式确实是 PTP/MTP，而不是"连接至智能设备"占着）、或 `UsbPtpManager` 的事务层在 claim 之后没把 OpenSession 送出去。**需要下一轮日志带 USB 事务层的收发包计数才能定，本轮不动。**

> 本节所有耗时均为原始时间戳差值，不引用漏斗的分阶段数字（§2.1 D6）。

---

## 十二、第二轮：「谁砍断了重试」定谳（2026-09-29）

§11.2 留下的开放问题，靠日志 signature + 代码结构定谳了，**不是**被新连接顶掉。

### 12.1 证据

4 次 `sta_cancel` 的位置完全同构 —— 都在"上一轮 `sta_classify` 之后 1~2.5 秒"，也就是下一轮 `resolveNetwork` 刚跑起来的地方：

| generation | init 失败 | sta_classify | sta_cancel | 分类后间隔 |
|---|---|---|---|---|
| gen=4 | 01:03:50.962 | 01:03:51.372 | 01:03:53.232（attempt=2） | 1.9s |
| gen=6 | 01:04:51.776 | 01:04:52.188 | 01:04:54.457（attempt=2） | 2.3s |
| gen=8 | 01:05:46.261 | 01:05:46.816 | 01:05:47.886（attempt=2） | 1.1s |
| gen=10 | 01:06:19.023 | 01:06:19.464 | 01:06:21.323（attempt=4） | 1.9s |

决定性的一点：**gen=4 死掉之后 53 秒内没有任何新 `sta_try`**。若它是被新的 `connect()` 顶掉的，`connect()` 会同步 `generation++` 并立刻打出下一条 `sta_try` —— 日志里没有。所以是这一代自己没了。

### 12.2 根因

`resolveNetwork` 的三路竞速里，**只有第三路包了 `runCatching`**；`networkMonitor.awaitWifiNetworkFor` 是裸调，而它内部 `allNetworks` / `getNetworkCapabilities` / `getLinkProperties` 三个 binder 调用一个都没兜 —— 网络正在拆除的瞬间它们会抛。

一旦抛出：`coroutineScope` 取消整个父协程 → `run()` 命中 `catch (CancellationException)` → 记一行 `sta_cancel` → rethrow → job 结束。而 `run()` **只有这一个 catch**，其余异常直接穿透到 `launch`：

- 不进日志（除了那行语义含糊的 `sta_cancel`）
- 不上 UI（用户看到的是"转了一下就不动了"）
- 不关漏斗（于是 `conn_result` 只能等到下一次 `begin()` 才收口，记成 `superseded`）
- **没有任何东西重启它**

这也解释了 §11.2 里那个"4 个 `conn_result` 全是 `superseded`"的怪象：它们不是被顶掉的，是死掉的那一代在用户下次点击时才被动收口。`caller=user_tap` 是真的 —— 4 次点击间隔 61s / 54s / 9s，是人在等不到反馈之后反复点。

### 12.3 FR-26c 修复

| 改动 | 作用 |
|---|---|
| 三路竞速**各自** `runCatching` + `net_resolve_fail` 事件（记 `path` / `err` / `msg`） | 一路抛异常只降级成"这一路返回 null"，不再打死整代连接 |
| `run()` 增加 `catch (e: Exception)` 兜底 → `sta_loop_error` + `ErrorOccurred` + `onFail("loop_error")` | 任何未归类异常都不再静默消失：进日志、上 UI、关漏斗 |

**不设闸门**（同 FR-18 的处理）：这是纯粹的"别静默死掉"，关掉它等于保留静默死亡，没有可回退的旧行为值得保留。

顺带把上一轮加的 `sta_supersede` 留住了 —— 它现在能明确区分"被顶掉"（有 `sta_supersede`）与"自己崩了"（有 `sta_loop_error` / `net_resolve_fail`），这两种此前在日志里长得一模一样。

### 12.4 验证与未验证

- 单测 222 / 0 failures（与基线一致，零回归）；`assembleDebug` 通过
- **真机未验证**：`net_resolve_fail` 到底会不会出现、出现在哪一路，要下一轮日志才知道。如果它一次都不出现，说明 §12.2 的推断错了，真正的抛出点在别处 —— 但 §12.3 的兜底无论如何都该留着，因为"静默死掉"这个失效模式本身已经被日志证明了

---

## 十三、第三轮真机回填 + v2.5 逐项审计（2026-09-29 第二次）

### 13.1 本轮日志证明生效的改动

| 改动 | 证据 |
|---|---|
| FR-18c USB `caller` | 装新包后 `conn_attempt channel=USB caller=usb_state`（此前全是 `unknown`） |
| FR-26b 跳过残留会话恢复 | `usb_session ok=false reason=no_answer_skip_recovery` 大量出现；**单次失败从 16.0s 降到 5.1s**（= `BULK_TIMEOUT_MS=5000` 一次），基线里 USB p50 从 16033ms 变 5134ms |
| FR-20 / FR-23 | `in_subnet=true`（相机在手机热点 10.184.219.14 上）被正确判定并放行；`net_bind bound=true covers=true` |
| FR-18g 脏样本过滤 | `⚠ 另有 1 条样本的阶段被重访…已从分阶段统计剔除` 如期打印，`revisits=1/2` 生效 |
| av 双后缀修复 | 12:38 起 `av=2.3.2-debug`（此前 `2.3.2-debug-debug`） |

**并且证实了 §12.2 的一个关键前提**：`no_answer_skip_recovery` 出现 ⇒ `response == null` ⇒ 机身**真的一个字都没回**。不是"回了拒绝码"。所以 USB 的方向应该是"为什么 PTP 服务不应答"，不是"为什么拒绝"。

### 13.2 本轮新发现的严重缺陷：USB 退避数组从未生效

日志 12:41:18 → 12:45:51，**273 秒内 USB 重连 45 次**，平均 6 秒一次，一次都不退让。

机制（`UsbPtpManager.scheduleReconnect`）：`openConnection()` 会**同步**把 `_usbState` 置为 `CONNECTING`，于是退避循环末尾那句 `if (_usbState.value == CONNECTING) return@launch` 立刻命中、job 结束；约 5 秒后异步的 OpenSession 超时，失败分支再调一次 `scheduleReconnect()`，此时 job 已不 active → **从 `delays[0] = 1000ms` 重新开始**。那条号称 ≈68 秒窗口的 `[1000,2000,4000,8000,15000,15000,15000]` 永远走不出第 0 项。

危害不只是费电：每次重连都要 open 设备两遍，并在机身 PTP 会话可能还活着的时候 `releaseInterface` + `connection.close()` —— **这正是把相机留在"卡住不应答"状态的标准做法，我们在用自己的重试维持它**。这与 §11.1 里 STA 那条"别砍重试"的结论不矛盾：那边是"确定性拒绝也要等，因为历史证明会成功"，这边是"明确不应答时越敲越糟"。

→ **FR-27**：退避轮次改为跨调用单调递增（`reconnectRound`），并设 8 轮上限后停手等物理重插；`usb_attach` / `usb_detach` / `usb_ptp(op/code/answered)` 三个事件进导出日志（此前 attach/detach 只进 Timber，"总线物理抖动"与"我们自己 disconnect 造成的软件抖动"分不开）。闸门 `v26_usb_reconnect_ramp`。

### 13.3 被日志拦下的一条优化（不要实现）

我原计划做 S3：「`in_subnet=false` 时快速失败，不等 30s socket 超时」——看起来证据充分：12:38:45 `in_subnet=false`、12:39:18 `socket_try err=TIMEOUT ms=30033`。

**但同一次连接在 12:39:56 成功了**（`sta_ok`，`reason=ok`），而那一刻 `in_subnet` 仍然是 false。也就是说在这台 vivo 上，`localAddresses()` **看不见实际承载相机流量的接口**（只列出 `ap0` + `ccmni4`），`in_subnet` 是**假阴性**。

所以任何以 `in_subnet=false` 为依据的硬失败都会把能连上的场景判死。FR-20 之所以安全，是因为它只在"要绑哪张网"时用子网判据、失败后仍走默认路由；**S3 撤销，不进任何版本**。同理 FR-21 的出厂地址扫描也不该用 `in_subnet` 去 gate。

### 13.4 v2.5 逐项审计（不采信文档自我声明，逐项对代码）

> 判定档位沿用 v2.5 §2.1：**L1 编译+单测过 / L2 真机单次 / L3 真机矩阵+回归脚本**。该文档自己就承认过三处"文档与代码不一致"，所以它的勾选状态一律经代码复核。

| FR | 内容 | 状态 | 关键证据 |
|---|---|---|---|
| FR-01 | 指标基线 | L2 部分 | 三处接入 `ConnFunnel`/`TransferManager`/`LiveViewManager`；阶段表首次真机导出是空的，`601caa5` 才补卡点 |
| FR-02 | STA 显式超时 | **L2 完成** | `StaTimeoutPolicy` 三处消费，闸门默认开，随 v2.3.2 发布 |
| FR-03 | 相册增量首屏 | L2 部分 | 代码与默认开均到位；但"首屏 p50 −30%"因 FR-01 无基线数值而**无法判定** |
| FR-04 | 传输期 event 静默容忍 | **L1，未上线** | `EventStreamPump` + 10 条单测齐；但 `EVENT_SILENCE_HOLD` 默认 **false** → 按 §2.1 不算完成 |
| FR-05 | >2GB + 续传 | L1 / ②未开始 | `ResumePolicy` 5 条单测（**PRD 写"7 个"，虚高**）；②64 位续传：**`v25_partial64` 这个闸门在代码里根本不存在**，offset 仍是 `Int`（`TransferManager.kt:1788,1814,1833`） |
| FR-06 | 主机注册可解释 | L2 ① / ②未做 | 撤销逻辑与三态 UI 到位；②固定等待**仍是 8500ms** |
| FR-07 | 争抢引导 | L1–L2 | 分类与原因码接上；**Z50II+SnapBridge 复现记录为零** |
| FR-08 | 可信性 a/b/c/d | L1–L2 | a/d 到位；b 的 3 处 `delete()` 现全在成功出口、留档路径成立，但**"App 内一键导出该样张"未实现**；c 到位 |
| FR-09 | targetSdk 36 | **未开始** | `app/build.gradle.kts:23` 仍是 35 |
| FR-10 | 快门互验 | L2 ① / ③未开始 | 夹具目录**只有 `manifest.txt`，无任何 bin**，整组测试自我跳过 |
| FR-11 | 影像馆 | 已撤销 | 全仓 grep 零命中 |
| FR-12 | 监看帧率/质量阶梯 | **未开始** | 帧间隔仍是常数 66ms；**`v25_lv_quality_ladder` 闸门不存在** |
| FR-13 | 伪彩 + RGB 波形 | **L1，未上线** | 实现与 15 条单测齐；但 `TONE_TOOLS` 默认 **false**，关着时连按钮都不生成 |
| FR-14 | 兼容矩阵远程热更 | **未开始** | `CompatRules` 只读本地 asset，无任何 http/url；**`v25_rules_remote` 闸门不存在**（PRD §五 却登记为"默认关"） |
| FR-15 | 私有命令码探测 | **未开始** | 四组 op 全仓零代码零夹具 |
| FR-16 | 保护标记只读筛选 | **L1，未上线** | 实现与 12 条单测齐；`PROTECT_SELECT` 默认 **false**；写入侧 `0x101A` 确实全无 |
| FR-17 | RAW+JPEG 成对 | L1，**默认值本轮已改** | 实现与 13 条单测齐；PRD 写"默认关"，本轮按用户要求**改为默认开**（`UiFlags.kt:96`），真机命名前缀 `DSC_`/`_DSC` 仍未验证 |

**汇总（必须直说）**：17 项里 **0 项达到 L3**。达 L2 的只有随 v2.3.2 发布那批。三处"PRD 登记了但代码里不存在"的闸门（`v25_partial64` / `v25_lv_quality_ladder` / `v25_rules_remote`）比"默认关"更弱——它们根本不存在。**v2.5 §八 自己定的前置"M0 先建立可复现的真机回归脚本"至今没有任何实现**（仓内无 `.sh`/`.ps1`，`tools/` 只有两个 Python 探针），这一条不补，后面每一项都只能停在 L1/L2。

默认 false 的闸门精确清单：`ConnFlags` 23 键中 1 个（`v25_event_silence_hold`）；`UiFlags` 11 键中 5 个（其中真正"新功能没上线"的是 `v25_protect_select`、`v25_tone_tools` 两项，另三个 `ui_classic`/`ui_reduce_transparency`/`ui_dispersion` 属回退与无障碍性质）。

### 13.5 「USB 照着 1.x 抄」这条路走到头了——逐字比对的结果是不一样不了

用户三次指路「1.x.x 老版本能连上，参考它」。这次把 USB 这条链路按 tag 直接 diff，结论与预期相反，**必须先记录，免得下一轮再花一遍时间**：

| 比对项 | v1.3.1 | v2.3.2 | 判定 |
|---|---|---|---|
| `scheduleReconnect()` 全函数体 | — | — | **`diff` 退出码 0，逐字节相同**【实】 |
| 退避数组 | `longArrayOf(1000,2000,4000,8000,15000,15000,15000)`（`:370`） | 同一组值（`:452`） | 相同 |
| `openConnection()` 开头同步置 `CONNECTING` | `:220` | `:263` | 相同 → **§13.2 的退避失效在 1.3.1 同样成立** |
| `BULK_TIMEOUT_MS` | 5000（`:45`） | 5000 | 相同 |
| `recoverStaleSession()` 参与判定（`sessionOk \|\| recoverStaleSession()`） | `:312` | 同 | 相同 |
| `scheduleReconnect()` 调用点数 | 8 | 8 | 相同 |
| 2.x 独有新增 | — | `enterDetachGraceWindow`（v2.2 T-U1）、`confirmLinkDead`、`adaptiveFrameTimeoutMs` | 均**不参与重连节律**：`ConnectionManager.kt:762` 的 `usbState` 观察者在 2.x 只多了漏斗记账（G12），**不回头调 `openConnection`**【实，两版对照读取】 |

**所以：USB 连不上不是从 1.x 到 2.x 的回归。** §13.2 那个"45 次重连/273 秒"的退避失效，在能连上的老版本里**一模一样地存在过**——它是陈年缺陷，不是新坑。FR-27 因此是**首次修复**，而不是"退回老版本行为"；这也意味着它不会是把 USB 变好的那把钥匙。

**推论（对排期的实际影响）**：既然节律代码两版相同，那 1.x 与现在 USB 观感差异只剩两种可能——① 相机侧状态（机身 PTP 服务是否应答，与 App 版本无关）；② 我们看不见的东西（这正是 `usb_ptp op/code/answered` 与 `usb_attach/usb_detach` 三个新事件要回答的，此前 attach/detach 只进 Timber，导出日志里根本没有）。
**因此 R1a 的验收口径必须收窄**：FR-27 达标的标志是"重连次数从 45 次/273 秒降到 ≤8 轮并停手"，**不是**"USB 连上了"。连不连得上要等 R2a 拿 `answered` 分布才能判。

### 13.6 主机注册这条线：逆向的结论是"我们已经和对手同形"，抄不出东西

v2.2 §5.2 把 T-S4 记成一条待改缺陷：**"主机注册发令即置位——命令 ACK 就当注册成功"**，并点名 `ConnectionManager.kt:564-579` 的固定 8.5s 等待（`PtpSessionManager.kt:394-396`）是"盲等"。按这个描述，改造方向是：去掉定长等待，改成收到确认再置位。

这次静态复核把两边实现摆在一起看【实】，ZDROP（这条赛道里连接工程做得最重的一家）的完整序列是：

```
command socket → INIT_COMMAND → 读容器（type 5 走错误分支，type 2 才取 ConnectionId）
→ 固定 settle 650ms → 开 event socket（soTimeout=1000）→ INIT_EVENT
→ GetDeviceInfo → OpenSession(0x1002)
→ PrepareHost 0x952B（无参）→ Thread.sleep(8500) → ConfirmHost 0x935A(参数 0x2001)
```
（`D:/1/N-Link/_apk_re/dex/raw_ZDROP_1.0.257/classes.dex`，偏移 `0x183268–0x183750`、`0x18369e`、`0x1836be`；字符串 `:15740 eventSettle=650ms`@`0x19fd4e`）

| 步骤 | ZDROP | 本仓（逐项对照） |
|---|---|---|
| 预备 | `PrepareHost 0x952B`（无参） | `PtpProtocol.kt:115` `OP_NIKON_HOST_REGISTRATION_PREPARE = 0x952B` |
| 等待 | `Thread.sleep(8500)` | `PtpProtocol.kt:120` `HOST_REGISTRATION_SETTLE_MS = 8500L` → `PtpSessionManager.kt:492` `delay(...)` |
| 确认 | `ConfirmHost 0x935A(0x2001)` | `PtpProtocol.kt:116` `0x935A` / `:118` `HOST_REGISTRATION_CONFIRM_OK = 0x2001` |
| 成功判据 | 只看 `0x935A` 正常返回，**不校验机身是否真落库**；机身侧确认靠用户点（`scan/ZDROP_cjk.txt:494,499`） | FR-06① 已改成"`resp=5` 即撤销已注册，三态由机身证据决定" |

**两个结论，方向相反，都要记：**

1. **8.5 秒定长等待是行业基线，不是我们的独漏。** T-S4 中"去掉 8.5s 盲等"这一半**撤销，不进任何版本**：省下的 8.5 秒要拿唯一一条已跑通的配对链路去换时序，不划算。真正有害的那部分（机身拒绝之后还声称已注册）已被 FR-06① 修掉。
2. **顺带纠正我们自己的对外表述。** ZDROP 的成功判据同样不校验落库 —— 所以"发令即置位"不是我们的缺陷，是这条赛道的共同做法；我们比对手多做的只有"看到 `resp=5` 就撤销"。不要把这条写成"修了一个别人也有的 bug"，写成"机身拒绝时我们不再谎报已注册"才准确。

**所以逆向在连接这条线上的真实价值不是抄实现**（实现已经同形），而是另外三件事：R4b 暴露出"init→event 之间对手留 650ms、我们只留 9~12ms"这个待验差异（**注意：它不等于我们的 `PROBE_SETTLE_MS`，别抄错量，见 R2d**）；R4c 给出"放弃后降频"这一比停手更安全的节律设计（已落成 R1e）；R4d 给出"拒绝码交回用户"这一条与"自己重试"并列的处理路线（R2c 定谳时一并评估）。

这条记进 §8.1 铁律的第三次兑现：前两次是 §11.1（撤销"立即收口"）与 §13.3（撤销 `in_subnet` 快失败），这次是 T-S4。**三次全是"看起来合理的优化被证据拦下"，其中两次由自家日志、一次由竞品静态分析。**

---

## 十三·七、版本考古定谳：「老版本能连、新版本连不上」到底是不是真的

> 用户四次提到这句话。§1.3 一度把它归为"证伪不了也证实不了"，那是**因为我比错了范围**（拿 v2.2.0→v2.3.2 的 482 行去回答一个关于 1.x 的命题）。这次按正确的跨度重做，结论分三条，**两条是"是"，一条是"否"**。

### 差异确实存在，而且能指到具体判据【实，代码逐版比对】

`git diff --stat v1.3.1 v2.3.2` 连接六个目录 = **17 个文件、+2834 行、−105 行**。STA 连接循环里三条"判死"逻辑的引入轨迹：

| 判据 | v1.3.1 | v2.0.1 | v2.3.2 | 引入点（提交 / 首个包含它的 tag） |
|---|---|---|---|---|
| TCP 预探测（连一次 15740 再决定要不要正式连） | **0 处引用** | 6 处 | 12 处 | `d70422c` 2026-09-12「探测分级」把 `PtpIpProbe` 带进 STA 循环；`6eefafa` 09-15 补上 preconnect 等待环 → 均在 **v2.0.1** |
| `UNREACHABLE_GIVE_UP`（连续不可达就提前收口） | **不存在** | `= 3` | `= 3`（`WifiDirectConnector.kt:108/404`） | `9a25ba1` 2026-09-16（RC-6 默认路由回落）→ **v2.0.1** |
| 重试预算 `RETRY_BUDGET` | **不存在**，只有 `MAX_ATTEMPTS = 10` + 退避数组 | 不存在 | 有 | `c31d695`「v2.2 剩余 P0——预检 / 漏斗 / **重试预算**」→ **v2.2.0** |

**拐点版本 = v2.0.1（2026-09-12 ~ 09-16 三个提交）**，不是某一次大重构。这解释了为什么"9 月 24 日还能连、之后不行"与"1.x 能连"两句话都半真半假：真正的分界在 9 月 12—16 日那三天，而用户的感知滞后到换包那天。

把三条串起来就是因果链：**1.x 是"闷头试满 10 次"**，而 2.x 变成"**先探一次机身端口 → 探测本身占掉机身唯一的 PTP/IP 客户端槽 → 下一次正式连接被拒 → 判为不可达 → 累计 3 次直接放弃**，且整个过程的预算只有 20 秒上下"。

**"探测抢机身会话槽"不是我的推测，本仓库自己记过两次【实】**：

1. `451bbd8 fix(conn): 修 AP 连接「前两次失败、第三次才成功」——探测不再抢机身会话槽`（v2.2.0）。标题写 AP，**实际两边都改了**：AP 侧可达性判据降到 TCP 层（`tcpConnectOnly`），STA 侧 `waitUntilReachable` 换成 `isCameraPortOpen`，并新增闸门 `conn22_probe_tcp_only`（现默认开，`ConnFlags.kt:178/221`）。
2. 但**那次没解决密度问题**：`451bbd8` 只把"探测=握手"降级成"探测=TCP"，`PROBE_SETTLE_MS` 仍是 400ms，而机身收掉探测留下的半开会话要 **~35 秒** —— 这个数量级差是代码注释自己写的（`ConnFlags.kt:96-99`：一次"点连接"要发 **20 次量级**的 TCP 探测 = 网关 1 + 出厂兜底 4 + 每轮 preconnect 2 + 每轮失败后 1 + 真实 2，×3 轮；实测"第一次探通，之后 4 分钟全 `probe=rejected`"）。

**所以真正的回归不是"加了探测"，而是"探测次数随重试轮数线性膨胀，而机身只有一个槽"** —— 1.x 没有探测，所以不存在这个问题；2.2 把单次探测降档，但没管总次数。这条由本期 **FR-21③**（同轮探活结论复用、不再第二次探测）+ **FR-22**（放弃预算 120 秒）收敛。

### 与 §1.1 的数据严丝合缝【实，日志】

失败恒定在 **20.1–21.4 秒**收口，而成功需要 **26.4 / 42.1 / 43.1 / 181.3 秒**。老版本没有预算、能试满 10 次退避，所以"等得到相机回话的那一秒"；新版本在相机还没腾出手时就判死了。**这就是"老版本能连、新版本连不上"最省事的解释，不需要假设任何协议变化。**

### 对应的修复（全部已在 v2.6.0 正式包里）

| 判据 | 修于 |
|---|---|
| 预算过短 | FR-22 `GIVEUP_FLOOR_MS = 120_000`（出处 = 日志2 原始时间戳 TCP p95 97.74s 向上取整），并把剩余秒数上屏 |
| 探测抢槽 / 重复探测 | FR-21③ 同轮结论复用（`carryOver`/`reused_preconnect`） |
| 绑错网导致的假"不可达" | FR-20 绑网前子网校验 + FR-23 接口分类（蜂窝 `ccmni` 不再被当WiFi） |
| 一轮里自己把自己顶掉 | FR-19 串行化 + `sta_supersede` 埋点；FR-26c 选网异常不再打死整代 |
| 脏样本让 p50/p95 失真 | FR-18g `ROUTE` 分界卡 + `revisits` 过滤 |

### 仍然没有定论的两件事（别当成已解决）

1. **USB 不在这条因果链里。** §13.5 已逐字证明 v1.3.1 与 v2.3.2 的 `scheduleReconnect()` 完全相同，所以"老版本 USB 能连"若属实，**不可能由我们的代码变化解释**；而"机身对 `OpenSession` 一个字节不回"至今只有埋点、没有根因。
2. **原始报障那位用户始终没给版本号，老包/新包同条件对比没做过。** 上面整条链是"我们的机型 + 我们的日志"闭环，**因果成立但外推到用户机未验证** —— 用户机是 Z8+小米 15 Pro（§1.4 S1 至今零实测）。R1a 收口时仍要这一条。

**所以 §1.3 原表的判定从"非目标"改为"已定谳"**：不是"某版本改了协议"，而是"某版本加了放弃判据，且判据的预算比实测成功耗时短"。前者我们查不了，后者我们改得了、也已经改了。

---

## 十四、接下来的优化路径（合并后的唯一排期）

排序判据不变：影响"能不能连上、能不能传完"的在前；**没数据支撑的判据改动一律后置**；每项可独立回退。
**2026-09-29 重排一次**：v2.6.0 已正式发布（见 §14.0），原 R1a 兑现完毕；同时按 `docs/竞品分析-2026Q3.md` §6 + §7.2（09-28 一手复核）把竞品压力并进排期，新增 R3；用户 09-29 撤掉了"竞品逆向不做"这条约束，逆向分析改列 R4 并给出边界。

### 14.0 v2.6.0 发布事实（2026-09-29，本版已承担的风险清单）

| 项 | 值 |
|---|---|
| 版本 | v2.6.0 / versionCode **25**（原 24）· tag `v2.6.0` → 提交 `b58b10d` |
| 产物 | `N-Link-v2.6.0-release.apk` **4,004,044 字节** · SHA-256 `0a0a35fe…b459076` |
| 签名 | `CN=N-Link` 证书指纹 `e9b09c8e…d6e206`，**与 v2.3.2 正式包同一把** → 老用户可直接覆盖安装、数据保留（实测比对，非推断） |
| 单测 | 222 / 0 failures / 0 errors / 1 skipped |
| 下载通道 | GitHub Release（Latest）+ 夸克固定分享 + 百度本版新链接，两条标记行均已被 App 侧正则命中 |

**发布时仍未真机验证的四处**（这是 2.6.1 必须收的债，不是"发完就完"）：

| # | 未验证项 | 已知的最坏情况 |
|---|---|---|
| V-1 | FR-27 的 **8 轮上限** | 相机长时间休眠后若 USB 设备**不重新枚举**，自动重连会在约 2 分钟后停手。停手时**不是静默**：`UsbPtpManager.kt:546-548` 会给出"重新插拔 / 先关掉相机 WiFi"的指引，所以最差体验是"要多插一次"，不是"永远卡在正在连接"。旧行为是无限锤击——两者谁对用户更好，**没有数据** |
| V-2 | FR-17 **RAW+JPEG 配对前缀** `DSC_`/`_DSC` | 本版默认开。若前缀规则与机身实际命名不符，会把不同次的拍摄错配成一条，用户看到的是"下载到的不是这张" |
| V-3 | 五个新事件（`usb_ptp`/`usb_attach`/`usb_detach`/`sta_supersede`/`net_resolve_fail`）**是否真落进导出日志** | 落不进去，R2 全部项继续瞎子摸象 |
| V-4 | 正式包（无 `.debug` 后缀）下的 `av=` 字段与预检提示 | 本轮所有观测都来自 debug 包；`applicationIdSuffix=.debug` 使调试包与正式包是两个应用，不能互升 |

### R1 v2.6.1 收口（不写新功能，只把上面四条关掉）

| # | 项 | 判据 |
|---|---|---|
| R1a | 读一份 **v2.6.0 正式包**的导出日志，逐条核 V-1~V-4 | V-3 五个事件至少出现过一次；V-2 配对结果与用户肉眼判断一致 |
| R1b | **真机回归脚本**（v2.5 的 M0 欠账，至今零实现：仓内无 `.sh`/`.ps1`，`tools/` 只有两个 Python 探针） | §九 的四组对照能一键跑完并落一份可 diff 的结果；这是 L2→L3 唯一那道门 |
| R1c | 把 §13.4 三处"PRD 登记了但代码里不存在"的闸门（`v25_partial64` / `v25_lv_quality_ladder` / `v25_rules_remote`）从文档删除或补实现 | 文档失实会误导下一次排期 |
| R1d | 补齐 `rom_rules.json` 里 `lastVerified: null` 的 **9 条机型** | 兼容矩阵目前是"我们说支持"，不是"验过支持" |
| R1e | **把 FR-27 的"8 轮后停手"改成"8 轮后降频继续"**（闸门 `v26_usb_reconnect_ramp` 内改，不新增闸门） | V-1 的更安全解法；对手有先例（ZRelay「连续失败 3 次…继续低频重试」，R4c）。停手对用户是"要我拔线"，降频对用户是"它还在试" |

### R2 等一轮真机日志再做（原 R2 平移，依赖已由 v2.6.0 提供）

| # | 项 | 依赖 |
|---|---|---|
| R2a | USB 到底是"总线物理抖动"还是"我们自己 disconnect 抖动" | `usb_attach`/`usb_detach` 回填 |
| R2b | STA `camera_unreachable → tcp_timeout` 这条映射纠正（§10.5 第 1 条遗留） | `sta_classify grade=` 回填；**只有映射没有数据源就会改成猜的** |
| R2c | 谁砍断 STA 重试（§12 之后仍未定谳） | `sta_supersede` / `net_resolve_fail` 回填 |
| R2d | `PROBE_SETTLE_MS` 定值（§10.2 决议 4） | 现值 **400ms**（`ApGatewayResolver.kt:58`），而机身收掉半开会话实测要 **~35s**（`ConnFlags.kt:98`）—— **差两个数量级，任何"设个更长的 settle"都救不了**，能救的只有把探测总次数压下来（FR-21③）。ZDROP 的 650ms（R4b）**不是同一个量**：那是 `INIT_COMMAND` 之后开 event socket 的间隔，不是"给机身回收探测连接"的时间，不能直接抄成我们的值。本项改为：**先量 400ms 与 2000ms 两档下的 `probe=rejected` 次数**，再决定要不要留这个常量 |

### R3 竞品压力驱动的补齐（新增，来源为竞品分析 §6 与 §7.2）

判据用 §1.4 的画像表：**指不到 P1–P4 / S1–S6 中某一行的，不进本期**。

| # | 项 | 谁已验证有需求 | 我们的现状 | 碰不碰已跑通的 |
|---|---|---|---|---|
| R3a | **开三个已存在的闸门**：`v25_event_silence_hold`（FR-04）/ `v25_tone_tools`（FR-13 伪彩+RGB 波形）/ `v25_protect_select`（FR-16 保护筛选） | 影控台整套监看工具并据此收 ¥148【官】；ZDROP 双通道判死豁免【实】 | 代码与单测**全齐**，只差真机结论。注意：「开发选项」面板是 debug-only，**正式包里的默认关 = 用户永远打不开**，所以"关着"不等于"灰度" | 不碰：三条各自独立闸门 |
| R3b | **GAP-27 写侧 `0x101A`**（把"机内保护"从只读变成可下发） | 影控台 v2.0.0「速传」+ v2.4.0 仍在修同一条链路【官】——对手独立做了一遍，是最强需求信号 | 读侧到位 `TransferManager.kt:869`；`0x101A` **全仓 0 命中**（码表止于 `0x101B`） | 低：纯新增写命令，不动读侧 |
| R3c | **GAP-13 RAW 落地**：分享附 JPEG、「仅 RAW」筛选 | 影控台 1.1/1.2/1.3.1 + 2.4.0【官】、帧澈【实】 | 成对显示+三选已做；`PreviewShareExporter.kt:74` 仍写"本版未实现" | 低：同文件扩展 |
| R3d | **GAP-11 影调工具补齐**：`.cube` LUT 预览 / 斑马纹 / 安全区 / 峰值对焦 | 影控台整套并据此定价【官】、帧澈有【实】 | 伪彩+波形在码（闸门关）；`.cube`/zebra/peaking/safeArea **全仓 0 命中**；直方图仍只算亮度 `HistogramView.kt:116` | 低：叠加层新增 |
| R3e | **合规三件**：补 `LICENSE`、清 `proguard-rules.pro:4` 那条 keep 不存在的 `app.core.ptp`、加最小 CI | 帧澈 MIT、ZRelay/影犀 targetSdk 36【实】 | 仓库根**无 LICENSE** → "开源可信"在法务上不成立；无 `.github/`、无 `androidTest` | 极低：发版配置类 |

**R3 里排在 R1 之后，不是并列**：R1b 不补，R3 每一项做完也只能停在 L1。


### R4 竞品逆向（09-29 从"不做"移出，用户解除该约束）

**边界先立住**：只做**公开分发物的结构分析**（manifest / DEX 类清单 / 字符串 / 未剥离原生库符号 / 反汇编读时序），目的是理解协议行为与对手的设计取舍；**不复制任何一行竞品代码**。闭源竞品的 EULA 普遍含反向工程禁止条款，所以：**能用开源来源回答的问题，一律优先用开源来源**；从闭源物得到的结论只作为"该不该做这件事"的判断依据，实现必须自己写、且能在尼康官方文档或自有真机上找到第二处支撑。

| 来源 | 许可 | 能直读到什么 | 恰好回答哪条 |
|---|---|---|---|
| **帧澈 ZENCHE** `github.com/Tauber01/ZENCHE` | **MIT** | 全源码（含 `CubeLut.java`） | R3d 的 LUT/矢量示波/斑马/峰值、GAP-10 可调帧率、R3c RAW 处理 —— **四件事一个仓库全包**，且已停更 37 天 |
| **aero-shutter** `github.com/subhashraveendran/aero-shutter` | **MIT** | 全源码 | 4MiB 分块 + `TCP_NODELAY` + 平坦内存；**其 issue #1 是"下载并发>1"的反例证据**（同型死锁） |
| ZTransfer `github.com/RealCaCl2/ZTransfer` | **无 License = 保留所有权利** | 可读不可引 | 私有命令码 `0x9714/0x9A6C/0x9918` **只能当线索**，须自行真机探测复核 |
| 已在盘的闭源取证产物 `D:/1/N-Link/_apk_re/`（121MB，未入库） | 静态分析 | ZDROP/ZRelay/影犀/PixCake/SnapBridge 的 DEX、字符串、badging、原生符号 | R4a–R4d 下面四条 |

**R4 已产出的四条结论**（均为 `dexdump -d` 现场复核，引用给文件内偏移；`zdrop_hostreg_disasm.txt` 是 1.0.190 旧产物，已用 1.0.257 复验）：

| # | 结论 | 证据 | 对排期的影响 |
|---|---|---|---|
| R4a | **主机注册的 8.5 秒固定等待是行业基线，不是我们的缺陷** | ZDROP `classes.dex@0x183268–0x183750`：`PrepareHost 0x952B`（无参）→ `Thread.sleep(8500)`@`0x18369e` → `ConfirmHost 0x935A(0x2001)`@`0x1836be` | **撤销 v2.2 §5.2 T-S4「发令即置位」这项改造**（详见 §13.6）；省下一条会引入回归的改动 |
| R4b | `INIT_COMMAND` 之后开 event socket 前有一个固定 settle，对手取 **650ms** | 同段 + 字符串 `:15740 eventSettle=650ms`@`0x19fd4e`，event socket `soTimeout=1000` | ⚠ **不可直接当作我们 `PROBE_SETTLE_MS` 的答案**（那是"给机身回收探测连接"的时间，两个量不同，见 R2d）。它的真实用处是：**对手的 init→event 之间有 650ms 固定间歇，而我们 `connect phase=init`→`phase=event` 之间是 9~12ms**（日志 `HANDSHAKE=9`）—— 值得单独验一次这个差是否与 `resp=5` 有关 |
| R4c | **放弃判据不一定要停手，可以降频** | ZRelay `scan/ZRELAY_cjk.txt:973`「连续失败 3 次；保留连续任务并继续低频重试」、`:421`「传输异常，2 秒后重建会话并重试当前队列项」；ZDROP 外层重试上界 4、间隔 `long[]{0,2000,4000,6000}`@`0x16dfa4`（线性，总预算 12s） | **V-1 的更安全解法**：FR-27 到 8 轮后改为低频继续（而不是彻底停手）。进 R1e，比现在的"停手等重插"用户体验更好，且有对手实现背书 |
| R4d | **`resp=5` 机身拒绝：对手一律不自动重试，而是把动作交回用户** | ZDROP 抛 `J3`@`0x17f56c` 不在其四类可重试 stage 内 → 立即放弃并显示「相机拒绝该主机（0x…）。请让相机停留在 STA 主机配置向导，并等待相机完成保存。」@`0x1837b8`；SnapBridge `all_strings.txt:26847` 同款归为用户动作 | 与 §11.1「历史证明重试是唯一出路」**不矛盾但需要并读**：对手的做法是"拒绝码给用户看 + 让用户动机身"，我们的做法是"自己再试"。R2c 定谳时把这条当作第三选项评估 |

**扫遍没有的（证据边界，别当成"对手没有能力"）**：
- `0x9520/0x9521` 五个 dex 全 0 命中（含小端字节形态扫）；
- **没有任何一家的文案或代码处理"机身对 OpenSession 一个字节不回"** —— 全行业都假设机身会回包。这既说明 USB 这个卡点没有现成答案可抄，也说明**做出来就是差异点**；
- USB 通道只有影犀（自建 raw PTP / `UsbRequest`）与像素蛋糕（`UsbRequest` + `PtpCameraControl` + 原生 `libcamerawirelesscontrol.so`，含 `changeToPtpMtpMode` 符号）在做；**ZDROP 走框架 `android.mtp.MtpDevice.open()`**@`0x195702–0x195722`（全 dex 无 `bulkTransfer`/`UsbRequest`，且是唯一声明 `android.hardware.usb.host` 的竞品）；SnapBridge 与 ZRelay **完全没有 USB**（`usb_api=0`）。
- 影犀/PixCake 的注册时序常量在 `libapp.so`/native 机器码里，字符串层取不到。

**因此逆向的第一优先级不是抄连接，而是抄 R3d 那批"看得见的功能"**：MIT 的 ZENCHE 一个仓库覆盖 LUT/示波/斑马/峰值/可调帧率，许可干净、可直接读，而我们最缺的恰好是这些；连接这条线相反——**对手没有可抄的答案，只能靠自己的新埋点**。

### R5 明确不做

FR-05② 64 位续传（先要有 >2GB 真机样本）、FR-12 帧率阶梯（依赖 FR-04 结论，同一条链路不能同时改两处）、FR-14 远程热更（新攻击面，且当前无运营需求）、FR-15 命令码探测（无测试机）、**多品牌 / 买 SDK / HTTP 兜底**（竞品分析 §4 路线 C·D 已判死）、**复用官方 GUID 蹭已配对状态**（aero-shutter 做法，合规与信任风险）、**照抄影犀的 40 个 `.cube` 素材**（版权）、**订阅/付费墙**（§1.3 非目标）。

> 原 R4 里的「竞品逆向（用户 09-29 明确先不做）」已按用户同日新指示移入上面的 **R4**，不再属于"不做"清单。


---

## 十五、文档合并说明

| 项 | 处置 |
|---|---|
| 合并结果 | v2.5 + v2.6 → 本文一份 |
| `PRD-N-Link-v2.5-差距收敛与可信性基建.md` | **移入 `docs/archive/`，不物理删除** |
| 为什么不删 | v2.5 头部自己记着：代码里 **208 处 `PRD §x.x` 引用按节号指向旧版文档**。物理删除会让这些注释全部指向不存在的文件。归档保留了内容、断掉了"顶层只有一份在效规划"的歧义，正是 v2.5 归档历史 PRD 时用的同一手法 |
| FR 编号 | v2.5 用 FR-01~17，v2.6 用 FR-18~27，无冲突，沿用原编号不重排（重排会让 208 处引用失效） |
| v2.5 的 §一~§三（竞品压力、非目标、GAP↔FR 对应） | 未并入正文——那是一次性调研，已完成使命，需要时查 archive |
| v2.5 的 **§1.4 目标用户与使用场景** | **已并入本文 §1.4**（`f7e24e0` 由用户在 master 上补回，不能被归档动作埋掉）。并入时订正 1 处：原写 `v25_raw_pair` 默认关，本轮已转默认开 |
| 第 4 处文档/代码失实 | v2.5 §五与 FR-17 写 `RAW_PAIR` 默认关，本轮按用户要求改为默认开。已在 §13.4 记为"默认值本轮已改"，不算遗留失实 |
| 若要物理删除 archive | 一次性动作：`git rm docs/archive/PRD-N-Link-v2.5-差距收敛与可信性基建.md`。**在此之前请确认那 208 处 `PRD §x.x` 注释可接受指向缺失文件** |

---

## 十六、FR-28 USB 通道全链路（2026-09-29，用户第 5 次追同一问题后转守为攻）

用户指示：**参考四款竞品的 USB 有线方案做全链路优化，其他功能不动**。APK 由用户当场提供
（ZDROP 1.0.257 / ZRelay 3.0.46 / PixCake 1.9.0-269 / 影犀），影犀包与盘上旧产物
`sha256` 一致（`3f4997fd…75d8092`），可直接复用。

### 16.1 先否定"机身不应答"这个笼统叙述【实，日志量化】

对 `n-link_logs_1790657151677.txt` 里 575 条 USB 事件做间隔统计：

| 形态 | 次数 | 真实含义 |
|---|---|---|
| `usb_open → usb_session` 间隔 **1–26ms** | **27** | 不是超时。要么 `usbConnection ?: throw`（`UsbPtpManager.kt:761`）即刻失败，要么写阶段立刻返回 |
| 间隔 **5.0–5.2s** | ≈30 | 真·等满 `BULK_TIMEOUT_MS=5000` 无应答 |

**所以"机身一个字节都不回"只解释了一半失败，另一半是我们自己瞬间失败。** 之前几轮全靠
`ms=` 收口值反推，从没把这两类分开——这是 §四 FR-18 可观测性欠的那块账。

### 16.2 两条 API 误用（不是判据分歧）【实，本机 android.jar + javap 实证】

```
public boolean claimInterface(android.hardware.usb.UsbInterface, boolean);
public int     bulkTransfer(android.hardware.usb.UsbEndpoint, byte[], int, int);
```

| # | 缺陷 | 后果 |
|---|---|---|
| FR-28① | `claimInterface` **返回 boolean、不抛异常**，旧写法 `runCatching{}.onFailure{}` 结构上接不住 `false` | 接口没claim成功照样发 OpenSession → 表现为"openDevice 成功、机身不回"。**旧的「USB 接口被占用」提示永远不会出现**，日志里 `claim_failed` 一次都没有，与这条一致 |
| FR-28② | `bulkTransfer` 返回**实际字节数**，旧判据只认 `< 0` | 返回 0（未写入）与短写被当成发送成功 → "没送到"记成"送到了没人答"，接着空等 5s，即上表那 ≈30 次 |
| FR-28③ | OpenSession `maxAttempts=1`，而 `clearHaltBothEndpoints()` 只在 `attempt>0` 分支 | 失败路径一次都不清 stall/不排空 → 残留字节引发 txid mismatch → 永久失步自锁。OpenSession 无副作用且 `SESSION_ALREADY_OPEN` 已当成功，重试安全 |
| FR-28④ | 正常拆链路（保活判死 → ERROR → `disconnect(silent)`）**从不发 CloseSession** | 机身认为会话还在自己手里 → 下一次 OpenSession 没人答。**四款竞品都有 CloseSession**（ZDROP `sendCloseSession`、像素蛋糕 SDK 内甚至有品牌专用的 `NikonCloseSessionAction`/`CloseSessionCommand`） |

### 16.3 逆向这条路上被推翻/否掉的东西（重要，别再走）

| 结论 | 证据 | 处置 |
|---|---|---|
| ~~ZDROP 用 `setConfiguration` 切 USB 配置~~ | 那 3 处命中其实是 **`setConfigurationChangeObserver`**，另一个 API | **误命中，已撤回**。竞品没有任何配置切换证据 |
| ~~`changeToPtpMtpMode` 可以抄~~ | 符号确在 `libcamerawirelesscontrol.so`（`cwc.str.txt:770/771`，且构造子带 `CameraConnectMode` 参数），**但类在 `ptpip` 命名空间下，语义无法确定；本机 mingw objdump 不支持 aarch64，反汇编取不到** | **不作为实现依据**。仅记为待验线索 |
| 配置枚举能力是存在的 | `javap` 实证 `getConfigurationCount()` / `getConfiguration(i)` / `setConfiguration(cfg)` **全是公开 API**，而我们全仓零调用 | 不进本轮（改了会碰唯一跑通的通道），列为 **R6a**，等真机 `bConfigurationValue` dump |
| USB/WiFi 通道互斥缺失 | `ConnectionManager` 里没有任何"切 USB 前关 WiFi 会话"的代码；机身只有一套 PTP 引擎 | 列为 **R6b**，需真机决策（会动 WiFi 那条跑通路，不盲改） |

外部实操旁证【据外部资料，需核实】：照片直播行业「尼康 Z8 + 安卓有线」教程要求机身
**USB 数据连接=MTP/PTP、USB 连接优先=拍摄、USB 电力输送=OFF、WiFi/FTP=OFF**。
最后一条与 R6b 同向。

### 16.4 闸门与验证

`v28_usb_claim_check`、`v28_usb_write_strict`、`v28_usb_opensession_retry`、`v28_usb_close_session`
默认全开，均已进调试包「开发选项」面板；关任一位即回到 v2.6.0 对应行为。
可观测性同步补：`usb_open` 带 `claim=`/`cfg_total=`/`if_total=`，`usb_ptp` 带 `err=`/`retried=`，
`usb_session` 新增 `reason=closed_on_teardown`；隐私声明同步登记这三个字段。

| 项 | 结果 |
|---|---|
| 单测 | **224 / 0 failures / 0 errors / 1 skipped**（基线 222，+2 例 `UsbWriteRuleTest` 钉判据规则；既有断言一条未改） |
| 构建 | `assembleDebug` BUILD SUCCESSFUL；包内已验 `v28_usb_*` / `closed_on_teardown` 在 dex 中（该包 17 个 dex） |
| 真机 | **用户实测 USB 连接成功**（2026-09-29，FR-28 调试包）→ 这是本轮唯一一次真机兑现，也是发 2.6.1 的依据 |
| 范围 | 只动 `device/usb/`、`ConnFlags`（闸门登记）、调试面板、隐私声明 + 1 个测试文件。**其他功能一行未动** |

### 16.5 新增遗留

| # | 项 | 为什么这轮不做 |
|---|---|---|
| R6a | USB **配置枚举与选择**（`getConfigurationCount`/`setConfiguration`） | 公开 API 已证实存在，但没有任何一家的证据说它们需要切配置；在已跑通的通道上盲切配置，风险 > 收益。需要一次真机 `bConfigurationValue` + 全描述符 dump 才能立项（`getRawDescriptors()` 也是公开的） |
| R6b | **USB ↔ WiFi 通道互斥**：开 USB 前把 PTP/IP 会话关干净 | 会动 WiFi 那条跑通链路，属 §8.1 铁律保护面。先取一轮日志看 `usb_session` 失败与 WiFi 会话存续的时间相关性再定 |
| R6c | 监看帧与命令共用 `commandMutex`，单帧最长持锁 8s | 属监看面，用户要求不动 |

---

## 十七、FR-29 STA 专项（2026-10-01，基线 = 远端 `51795fa`「v2.6.5」）

> 触发：用户要求对 STA 做专项优化，授权解包 ZDROP 1.0.257 / ZRelay 3.0.46，硬约束
> **只动 STA、已跑通功能零改动**。分支 `sta-hardening`，提交 `f306568` / `58c1474` / `f4e69e8`。
>
> ⚠ 基线须知：`51795fa` 的提交名写「v2.6.5」，但 `build.gradle.kts` 仍是
> `versionCode 26 / versionName 2.6.1`，tag 与 GitHub Release 也停在 `v2.6.1` ——
> **2.6.2~2.6.5 是内部迭代记号，没有发布过**。另：该提交带一个红灯测试
> （`ProtectionFilterTest.kt:65` 期望 `"已保护"`，而 `51795fa` 把 `TransferViewModel.kt:1587`
> 改成了 `PROTECTED("已筛选")` 未同步测试）。属相册模块，按范围约束**未在此修**，
> 本轮回归基线定义为「1 个既有失败（相册）+ 0 个新增失败」。

### 17.1 竞品对照的结论：**大部分做法我们已经有**

ZDROP 完整反编译（`/tmp/zdrop-out/sources/`，未加固）后的逐条比对：

| ZDROP 的做法 | 证据 | 我们的状态 |
|---|---|---|
| 绑到「相机 IP 落在其 LinkAddress 子网内」的 WiFi，socket 全走 `Network.getSocketFactory()` | `A.java:180`、`C0528y.java:47-62`、`B6.java:51` | **已有**：FR-20 `BIND_SUBNET_CHECK` + `networkCoversHost` + `ptpSession.connect(network = usable)` |
| `requestNetwork` 显式 `removeCapability(NET_CAPABILITY_INTERNET)` | `A.java:180` | **等价、无需改**：移除该能力位是因为相机热点无公网；我们从未 `addCapability(INTERNET)`，请求同样不要求公网 |
| host GUID 持久化复用（`host_guid`，一次生成长期用） | `o7.java:477-495` | **已有**：`PtpIdentityStore`，`KEY_GUID = "ptp_ip_client_guid"`，注释里就写着随机会被机身当新设备 |
| mDNS 发现 `_nikon._tcp.` + `_ptp._tcp.`，TXT 取端口、缺省 15740 | `C0492t3.java:104`、`C0484s3.java:52` | **已有**：`WifiScanner.kt:89-90` 两个类型都订，`:678` 一起用 |
| 注册重试 ≤4 次、间隔 `{0,2000,4000,6000}`，且**只对 socket/INIT 阶段重试**；`PrepareHost` 被拒视为终态 | `o7.java:3936`、`:3889` | **方向一致**：我们也是 prepare 失败即 `return`（`PtpSessionManager:516`），不原地重试 |
| 单飞闸 + 2000ms 冷却（`elapsedRealtime()+2000`） | `o7.y0():6025-6055`、`:2809` | **已有**：FR-19 `CONN_SERIALIZE`（`ConnectionManager:271`）、`FALLBACK_COOLDOWN_MS = 180_000` |
| INIT_COMMAND → event socket 之间 settle **首次 350ms / 重试 650ms** | `o7.java:3889` | 我们是常量 `PROBE_SETTLE_MS = 400`，落在两者之间。**不动**：§14 R2d 已说明 settle 治不了探测密度问题（机身回收半开会话要 ~35s，差两个数量级） |

**竞品也没有的东西（扫遍未命中，别当成"对手有秘诀"）**：注册前读设备属性判断向导态（ZDROP 全类无 `0x1014/0x1015`，也无 `0x9435/0x90C2/0x90C8`）、注册前发 CloseSession 清场、`PrepareHost` 返回 `0x201F` 的专用处置、`InitFail reason=1` 的独立分类、相机休眠检测与唤醒。**ZRelay 3.0.46 的 dump 里 `0x952B/0x935A` 计数为 0 —— 它根本不实现主机注册**，无从对比。

→ 结论：`docs/STA注册失败-根因分析与优化方案.md` §1.4 的判断成立，**0x201F 是相机侧状态问题，不是我们协议实现的缺陷**。竞品能"成功"是因为它们同样要求相机停在向导页，且它们把这件事写进了文案（ZDROP：「请让相机停留在 STA 主机配置向导，并等待相机完成保存」）。

### 17.2 本轮落地的 7 项

| # | 项 | 性质 |
|---|---|---|
| ① | `camera_unreachable` 改挂自己的 Reason，不再映射到 `TCP_TIMEOUT`（该码**全仓零生产者**） | 可归因。`ConnFunnel` 只增不改，`TCP_TIMEOUT` 保留 |
| ② | `loop_error` / `round_budget_exhausted` 各自成码；硬闸出口原来把**中文文案当机器码**传给 `onFail`，整类失败在漏斗里全是 `unknown` | 可归因 |
| ③ | `WifiNetworkMonitor` 三个 binder 调用不再裸调（按"单张网卡查不到就跳过"的粒度兜） | 加固。代码注释自己记着真机曾有 4 个 generation 全死在此，但修法只在调用方包 `runCatching` |
| ④ | 三路选网竞争落选时归还 `requester` 申请的网络 | 修泄漏。只有它注册 NetworkCallback 并留 `held`；胜出者不是它时无人认领，而**成功路径永不 `release()`** |
| ⑤ | R4 蜂窝黑洞判据从网卡枚举改为 `ConnectivityManager`，规则收紧为"默认路由在蜂窝 **且** 无任何 WiFi 覆盖相机"两条同时成立 | 修判据（闸门仍默认关，本轮对现网零影响） |
| ⑥ | `HOSTREG_HINT_V2` / `HOSTREG_PRECHECK` 转**默认开** | 让 v2.6.3 已写好的两条真正生效（面板是 debug-only，默认关 = 正式包永远不生效） |
| ⑦ | 新增 `sta_probe_ctx` 事件，把新旧两个蜂窝判据并排记录，**不改行为** | 取证据。判定规则：`cm_cellular_hole=true` 的轮次里只要有成功的，R4 永久不能开 |

### 17.3 三条**否决**的改动（都有反证，别再提）

| 提议 | 否决依据 |
|---|---|
| 学 ZDROP 把 connect 超时从 30000ms 缩到 6000ms | §1.1 记着真机**成功**建链耗时 9.80 / 15.05 / 29.87 / 84.75 / **97.74** 秒。缩到 6 秒会把本来能连上的判死 |
| 学 ZDROP 做连接前路由预检、拿不到同网地址就中止 | 与 §13.3 撤销 S3 同一个理由：判据来源在主力机上是假阴性，硬中止会杀掉能连上的场景 |
| 用户清单里的「多 STA 并发调度与连接池管理」 | 机身同时只接受 **1 个 PTP/IP 客户端**，全程 1 台相机 1 条会话。连接池是没人调用的机器，还会给已跑通的传输链路增加并发风险 |

另有一条**我自己中途撤回**的写法：原想在 `StaNetworkRequester.acquire` 的 `finally` 里"发现协程已取消就无条件释放 callback"，但 `held = fallback; return fallback` 与取消观测之间无法区分"调用方拿到了"和"调用方超时丢了"——前者被注销 callback 会**打断 STA 的断链检测**（`networkLost` 收不到），那是改一条跑通的路。④ 因此改到真正知道自己丢了结果的调用方去修。

### 17.4 验证与范围

| 项 | 结果 |
|---|---|
| 单测 | **231 / 1 failed / 0 errors / 1 skipped**（基线 224，新增 7 例全绿：`ConnFunnelReasonContractTest` 4 例 + `CellularBlackholeRuleTest` 3 例）。唯一失败 = `ProtectionFilterTest:65`，先于本轮存在，属相册 |
| 构建 | `compileDebugKotlin` / `assembleDebug` 均 BUILD SUCCESSFUL；调试包 21,268,954 字节，`sta_probe_ctx`/`cm_cellular_hole`/`cellular_blackhole`/`round_budget_exhausted` 均已验入 dex（该包 17 个 dex） |
| 改动面 | `ConnFunnel`（只增枚举）、`ConnectionManager`（映射表 5 行）、`wifi_sta/` 三个文件、`ConnFlags`（文档+2 个默认值）、2 个新测试文件 |
| **零改动** | AP 通道、USB 通道、传输/相册/监看业务、UI 布局、`build.gradle.kts`、启动流程；**无新增第三方依赖** |
| 未验证 | 全部真机行为。⑥ 转默认开的两条、⑤ 的新判据、⑦ 的双记，都要一轮真机日志才算数 |

### 17.5 下一步（按证据强度排）

1. **拿一轮真机日志**，看 `sta_probe_ctx` 的 `nic_covers` 与 `cm_cellular_hole` 在成功/失败轮次里的分布 → 定 R4 生死（⑦ 就是为这件事装的）。
2. 看 `hostreg` + `device_caps`（`lastSupportedOperations`）在 0x201F 现场的实际取值 → 验证 R2 是否真能提前拦住，以及 §4.4 那组对照实验（相机进「连接至 PC」向导 vs 进「连接至智能设备」）。
3. R3（`0x201F` 之后是否仍发 `ConfirmHost`）**仍未做**：需要先用 ZDROP 在已注册过的相机上跑一次，观察 `0x201F` 后 ConfirmHost 是否照样成功。无此证据不动注册主链路。
4. FTP 架构（B）下抑制 STA PTP 自动重连：`staArchitecture` 在 `ConnectionManager` **0 命中**，三条自动路径（`reconnectLastDeviceIfPaired:1239`、`observeReconnectTrigger:1040`、HealthWorker `:552`）仍会发起最长 240s 的 STA 连接轮，而 `bindProcessToNetwork` 是**进程级**的，会影响 FTP 监听 socket 的出口选择。要动 `ConnectionManager` 的自动重连闭环，属"已跑通功能"，**先不碰**，等 FTP 那条线自己稳定。

### 17.6 FR-30：STA 未连接态自主注册（2026-10-02，修「相机未连接…请先走 AP」误判）

**用户现场**：相机已连上手机热点、停在主机配置向导，点「STA 主机注册」却被判
「相机未连接。请先通过 WiFi 连上相机（AP 模式），再执行 STA 主机注册」。
正确行为应与 ZDROP 一致：此现场可直接注册，注册后 STA 连接可用。

**根因（已验证，代码行号）**：`ConnectionManager.registerStaHost` 旧实现第一行是
`if (!ptpSession.isConnected()) return Failure("相机未连接…AP 模式…")` —— 注册被绑死在
「先有一条已建立的 PTP 会话」上，而该会话历史上只有 AP 连接能建立。于是
「相机在热点上停在向导」这个本该直接注册的现场被判死。

**ZDROP 诊断日志反证（2026-10-01，host-register 段）**：注册是一条**独立链路**，
不依赖任何既有会话 —— 发现 endpoint（`endpoint-subnet-matched-local-hotspot-interface`）→
自建 command/event 双通道（init-command-ack → pre-event-settle 350ms → init-event-ack）→
GetDeviceInfo（ops 含 `0x952b`/`0x935a` = 向导模式）→ OpenSession → `0x952B` →
等相机确认 → `0x935A` → 直接拆通道（`sendCloseSession=false`）。

**落地（最小改动，3 改 2 增）**：
- 新增 `wifi_sta/StaHostRegistrar.kt`：自有 socket + 自有事务号复刻上述独立链路；
  不碰 `PtpSessionManager` 单例会话状态（不置 CONNECTED、不起心跳/事件监听、不打连接漏斗）。
- `ConnectionManager.registerStaHost` 改双路径：已连接走原路径（逐行未动）；
  未连接先 `scanWifiCameras(8s)` 受限发现 → `pickRegistrationCandidate`（优先 awake 候选）→ 交 registrar。
- 发现不到时给两条可照做路径（STA 直注 / AP 注册），不再判死。
- `DashboardFragment` 注册弹窗文案同步为「不需要先连相机」。

**范围铁律复核**：AP 连接、既有 STA 连接、已连接态注册三条已跑通路径零改动；
新分支只在旧代码必然返回 `not_connected` 失败的位置插入，回归面≈0。

**验证**：`assembleDebug` BUILD SUCCESSFUL；单测 234 通过 / 1 失败（既有
`ProtectionFilterTest` 红，属 gallery 另一会话）/ 1 跳过；新增 `StaHostRegCandidateTest` 3 条全绿。
真机待验：相机连热点停向导 → 点注册应直接成功；注册后 STA 连接可用。

### 17.7 FR-31：扫描提速 + AP 一次连上（2026-10-02，三组真机日志定谳）

**日志证据**：

| 现象 | 来源 | 关键行 |
|---|---|---|
| ZDROP STA 发现代际 1.4s | ZDROP-…868572516 | 23:27:01.923 DISCOVERING → 23:27:03.326 discovery committed |
| 我们 discover 段恒跑满 8~18s | n-link-…8220927 | discover_start 01:18:47 → discover_hit 01:19:05（17.7s） |
| 扫描的 InitCommand 占槽 → 注册被拒 | 同上 | discover_hit → `init_fail reason=1`，间隔 83/327/121ms（连 4 次） |
| AP 两败轮死在链路抖动 | 同上 | 01:35:24 `ifaces=` 空 + ENONET；01:36:08 ENETUNREACH；两轮均 11~13s `no_wifi_network` 收口 |
| 热点回归后自动路径 1.1s 连上 | 同上 | 01:37:23 `network_restored` → 01:37:24 established |
| ZDROP AP 同样 INIT_EVENT reset，7s 后重试中 | ZDROP-…876494037 | 01:40:19 reset → `retry … delay=7000ms` → 第三次点击 attempt2 拿 init-event-ack |
| ZDROP AP 就绪等待 | 同上 | `AP network readiness wait=1200ms gateway/local … stable=true` |

**落地（只动扫描与 AP/STA 连接两条链）**：
1. **链路就绪门** `awaitLinkReady`（FR-31①）：绑网后等「内核路由 + 绑定网口」双覆盖相机地址，
   预算 5s；正常链路首检即过、零附加时延。
2. **热点回归等待** `awaitLinkReturn`（FR-31①）：仅「轮初在相机热点上」的轮启用，预算 120s；
   触发点二：选网无收敛（旧 `no_wifi_network` 收口点前）、探活 errno 判链路死
   （`linkLooksDown`：ENONET / ENETUNREACH，与相机侧 TIMEOUT/REFUSED 严格分开，单测钉住）。
3. **注册发现去占槽**（FR-31③）：`scan(ptpConfirm=false)` 全链路只 TCP 筛探不发 InitCommand
   （mDNS/网段/ARP/NSD/历史 IP 五处确认点同步降级）；`ptpConfirm=true` 时逐行等价旧行为。
4. **扫描早退**（FR-31④）：强候选命中 + 1.5s 宽限即收工（默认开）；注册路径叠加
   `ptpConfirm=false + earlyExit=true`，discover 段预期从 8~18s 降到 1~3s。
5. **INIT_EVENT 冷重试 7s**（FR-31②）：`PtpSessionManager.lastFailPhase` 进度标记
   （socket/init/init_event/opensession，成功置 null）；`init_event` 失败退避改 7000ms
   并出 `sta_backoff reason=event_cold`，其余失败照旧退避表。

**验证**：`assembleDebug` BUILD SUCCESSFUL；单测 236 通过 / 1 失败（既有红）/ 1 跳过；
新增 `LinkDownRuleTest` 2 条全绿。真机待验：① AP 一次连上（中途热点重启应原地等回并连上）；
② 注册 discover 段 <3s 且不再出现 `reason=1`；③ STA 页常规扫描不漏相机（早退宽限 1.5s）。

### 17.8 FR-31 真机回归回退 + FR-32 重优化（2026-10-02 凌晨）

**回归事实（n-link_logs_1790881032213）**：FR-31 全量包 3 轮 AP 全 `superseded` 零成功
（ROUTE p50=90.7s，用户 4 次点击被 `loop_running` 拒）；FR-31① 的 120s 热点回归等待
把旧包「快收口 → network_restored 自动轮 1.1s 接住」的胜利路径改成轮内死等。
**同时证伪「响应内容致相机关热点」**：gen=1 的 `network_lost` 发生在 `net_bind` 后 0.2s、
任何相机方向报文发出之前；后续每轮 drop 与 socket connect 同刻（0.2~0.8s）。
触发点在手机链路层/相机 AP ~50s 自周期，不在报文内容（分析文档 §6）。

**处置**：坏包快照 `backup/fr31-field-regression-20261002`(ba539ec) → 回退 `7517d65`
（删 ① 与 linkLooksDown/LinkDownRuleTest；早退挂闸默认关）→ 重优化 `a34c380`（FR-32）：
- R2：注册 reason=1 冷却 35s（`Failure.initFailReason` 新字段承载判据）；
- R3：`net_bind` 带 ifaces、`net_unbind`、建链后 15s `sta_ifaces` 采样、`ptp_tx` opcode+响应码
  —— 纯日志零行为，专答「谁杀了 wlan0」与「断热点前最后发了哪条包」两个悬案；
- ④ 重落地：早退闸 `v32_scan_early_exit` 默认开（注册发现 8~18s → 1~3s）。
**回退手段三层**：git 分支/提交；调试面板三闸（v32_scan_early_exit /
v32_reg_reason1_cooldown / v32_link_diag）；dist 内 FR-30 旧包 APK 实物。
AP 连接循环保持回退后行为（= 前一版），本轮不动。



