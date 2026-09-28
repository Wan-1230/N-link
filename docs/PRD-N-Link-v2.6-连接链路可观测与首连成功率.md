# PRD · N-Link v2.6 —— 连接链路可观测性与首连成功率

> 版本：v2.6 · 基线：**v2.3.2（versionCode 24）**@ `874a2d3` · 分支：`master`
> 状态：**代码已完成，真机待验**——FR-18/19/20/21(②③)/22/23/24 全部落地（`995f564` 起），FR-25 经 §10.2 决议撤出本期。
> 单测 208→222 零回归，但**本期改动全在绑网时序 / 探测节奏 / 放弃判据上，JVM 单测覆盖不到一行真机行为**；里程碑 N0 的门槛是"测试包连得上相机且新事件齐全"，不是"编译通过"
> 依据：**4 份真机导出日志**（3 份用户报障 + 1 份自测）+ 对 `wifi_sta` / `wifi_ap` / `connect` / `ptp` 四个目录的逐行只读排查 + `git` 版本考古（v1.3.1 ↔ v2.3.2）
> 与 v2.5 的关系：**不取代、不续写**。v2.5 的 FR-01/02/06/07 已交付的埋点与超时是本期的地基；本文只处理「连接为什么失败」这条线上 v2.5 没覆盖的 6 个必现缺陷。FR 编号接续 v2.5（v2.5 止于 FR-17，本文从 **FR-18** 起）
> 原则：沿用 §8.1 铁律。**先补可观测性，再动判据**——没有机型与 socket 错误码数据就改判据，等于拿已跑通的功能赌运气

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
| 断言"某版本引入了协议/鉴权回归" | 现有日志证伪不了也证实不了。v2.2.0 → v2.3.2 连接目录只改了 482 行、`WifiDirectConnector` 改动 22 行（`git diff --stat`），**不存在能解释 100% 失败的大改动**。真要查，需要那位用户报出具体版本号并按 §九 步骤做老包/新包同条件对比 |
| 连接成功后 `op=0x1005/0x1007` 回 `0x2013` | 4 份日志里**每次 `pair_ok` 之后都稳定复现**（GetStorageInfo / GetObjectHandles → PTP `InvalidParameter`）。这是"连上了但读不出存储列表"的独立缺陷，会让用户主观上仍觉得没连上。**单独立项，不混进本期**（§10.2 待拍板） |
| 新增第三条 socket / data 通道 | v2.2 §2.5 已裁定，本文不重开 |
| 改 PTP 事务层、下载队列 | §8.1 铁律 1 的核心区，本期零改动 |
| 引入遥测 SDK 上报这些新字段 | v2.5 §1.3 已裁定不做上报。本期所有新字段**只进本机导出**，且必须过 §四 FR-18 的脱敏验收 |
| 把 `PROBE_TCP_ONLY` / `conn22_*` 等既有闸门改名或合并 | 码值与 key 一旦发布就是日志里的稳定契约（`ConnFunnel.kt:59-61` 同款裁定） |

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
2. **USB 为什么不应答 PTP**。`usb_open ok=true model=Z 50II` 说明枚举与 claim 都正常，但 OpenSession 16s 无应答。历史上 USB 成功过 4 次（09-17 ×3、09-19 ×1）对 118 次失败，**长期就不稳**，不是 v2.6 引入的。09-17 那三次成功都发生在 WiFi 侧正在失败/已断的时段，所以"机身单槽跨 USB/WiFi 互斥"这个猜测**被证伪**（USB 成功时 WiFi 并没占着槽）。需要的补充信息：失败当时相机屏幕是否点亮、是否被 SnapBridge 或电脑占着、USB 线是否供电不足。

> 本节所有耗时均为原始时间戳差值，不引用漏斗的分阶段数字（§2.1 D6）。

### 10.3 变更记录（本文自身的修订，倒序）

| 日期 | 变更 |
|---|---|
| 2026-09-28 | 首版。基于 4 份真机日志（`n-link_logs_1790385975120(1)` / `1790426146869` / `1790519817936` / 我的最新连接日志）+ v2.3.2@`874a2d3` 代码只读排查 + v1.3.1↔v2.3.2 git 考古 |
