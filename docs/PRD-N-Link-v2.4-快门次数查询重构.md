# PRD：N-Link v2.4 快门次数查询重构

> 版本：v2.4（基线 versionCode 21 / versionName 2.2.0 @ `b4cf780`）· 分支：`feat-glass-polish-v2.3.1`，实现提交 `20ec81c` `a295f00`
> 状态：**L0-L4 + UI 全部实现完毕**；仍开放两项：黄金夹具（§六 记了下一次该从哪继续）与 §十 第 10-12 条（同意门、机械口径）尚未真机验证，**因此暂不合入 master**
> 原则：已跑通的功能不改动，改动面压到最小，每项独立可回滚
> 结论来源：exiftool（Nikon.pm / MakerNotes.pm）、Exiv2（nikonmn_int.cpp / makernote_int.cpp）、libgphoto2（camlibs/ptp2/ptp.h、config.c、library.c、ptp.c）、exif-py、python-shutter-counter、LibRaw 逐行比对，非二手博客

---

## 一、结论先行：路只有一条，但现在的走法从根上就错了

**机身 PTP 读不到快门次数——这条既有判断成立，已再次穷举验证。** 对照 libgphoto2 `camlibs/ptp2/ptp.h` + `config.c` 的全部厂商属性表：

| 厂商 | 快门计数属性 | gphoto2 是否暴露 |
|---|---|---|
| Canon | `PTP_DPC_CANON_EOS_ShutterCounter` = `0xD1AC`（UINT32，只读） | 是，config 名 `shuttercounter` |
| Olympus | `0xD059 ShutterActuationCount` | 是 |
| Fujifilm | `0xD154 ShotCount` / `0xD310 TotalShotCount` | 是 |
| **Nikon** | **无。** 计数形状的只有 `0xD1F7 MirrorUpReleaseShootingCount`、`0xD1B4 ContinousShootingCount`（均为 UINT8 **连拍张数设置**），以及 `0xD1Bx ExposureRemaining`（剩余可拍张数，取决于存储卡容量） | — |

所以 `GetDevicePropValue` 这条路**彻底封死，不用再试**。唯一数据源是照片 EXIF 里的尼康 MakerNote。

那为什么现在查不到？——因为**解析器的偏移基准取错了值**。现代尼康机身（D 系列 + Z 系列，NEF 与 JPEG 皆然）的 MakerNote 是 `Nikon\0` + 格式标志 `0x02` 形态，**IFD 起点在 MakerNote 首字节 +18，数值偏移基准在 +10**。而 `NikonShutterCountParser.kt:160-163` 两个候选基准试的是 **+8 和 +0，两个都不是 10**；IFD 起点试的是 8 和 0，都不是 18。三个互相独立的成熟实现对此完全一致：

- exiftool `MakerNotes.pm` Nikon3 段：`Start='$valuePtr+18', Base='$start-8'`（即 base = 值起点 +10）
- Exiv2 `makernote_int.cpp:443-498`：`Nikon3MnHeader::baseOffset(mn) = mn + 10`，`start_ = 10 + th.offset()`（th.offset()=8 → 18）
- exif-py `core/exif_header.py`：`ifd = field_offset + 10 + 8`，`relative = 1`

上面两条只是「读得到」的必要条件之一。数据链完整形态是：`APP1 → TIFF → IFD0 → 0x8769 Exif 子 IFD → 0x927C MakerNote → 尼康 IFD → 0x00A7`。**旧实现在倒数第三步就断了**（只翻 IFD0、不跟 `0x8769`），因此本地解析**从未成功过**，全部落到云端兜底；云端一抖动就整体「查询失败」。

> §二 的 R0/R1 与「MakerNote 字节序可与主 TIFF 头相反」这三条，不是从文档推的，是拿 Exiv2 `test/data` 下的真机尼康文件（`Nikon.nef`、`NikonD2Hs.jpg`、`_DSC8437.exv`）跑出来的实测结果，见 §六。

---

## 二、根因清单（按影响排序，均为代码确凿）

| # | 根因 | 证据 | 影响 |
|---|---|---|---|
| **R0** | **MakerNote 找错了 IFD**：`0x927C` 按 EXIF 规范挂在 **Exif 子 IFD（指针 `0x8769`）**，旧实现只在 **IFD0** 里找、且从不跟随 `0x8769` | 旧 `parseTiffContainer` 只把 `ifd0Offset` 传给 `readTagValueBytes`；**真机样张已证实**：Exiv2 `test/data` 的 `Nikon.nef` / `NikonD2Hs.jpg` / `_DSC8437.exv` 里 `0x927C` **只存在于 Exif 子 IFD**，IFD0 中没有 | **头号原因。**不跟 `0x8769` 指针，后面每一步做得多对都是 null |
| **R1** | **IFD 位置取错**：候选 base 用 8/0、IFD 起点用 8/0（且 `base=8,ifdOffset=0` 与 `base=0,ifdOffset=8` 两条算出的 IFD 起点**都是 8**），从未试现代 D/Z 的 **+18** | `NikonShutterCountParser.kt:160`（`base = 8`）、`:163`（`ifdOffset = 8, base = 0`）；真机三张样张全部命中「内嵌 TIFF 头@10 → IFD@18」 | v02xx 笔记必然读不到 |
| **R2** | **内嵌 TIFF 头分支提前 `return`**：找到第一个结构合法的内嵌头就 `return readNikonTag(...)?.takeIf{...}` —— 取不到合理值时返回 null，**永远走不到 R1 那两条兜底分支** | `:151`（`return` 而非 `?.let { if(...) return it }` 并继续） | 兜底逻辑形同虚设 |
| **R3** | **无签名形态被整段丢弃**：`if (!hasNikonHeader) return null` | `:138` | D1、E99x、部分 Coolpix 恒失败（exiftool 用 `Make =~ /^NIKON/i` 而非签名判定，见 §四形态 C） |
| **R4** | **取样键选错，导致数值本身就是错的**：`photos.minByOrNull { it.format == JPEG }...minByOrNull { it.size }` —— 按「文件最小」挑样张 | `CameraParameterManager.kt:421-423` | **正确性缺陷，不只是失败**：快门次数是「拍摄该张照片那一刻」的计数。挑到一张旧照片（哪怕属于这台相机的早期，甚至换过机身/卡里存留的别的机器照片）就报出一个**偏低的假数值**，且显示为「查询成功」 |
| **R5** | **整对象下载**：`downloadPhotoToCache` → `downloadToFile` 按 4MB 分片循环直到 `file.size`，无字节上限；`partialObject` 虽在 `CameraTransport` 接口里（`:1688`，WiFi/USB 两路 `:1716`/`:1735` 均已接好 `getPartialObject`），但**没有任何「只读前 N 字节」的公开入口** | `TransferManager.kt:580`、`:675`、`:745` | Z8/Z9 的 NEF 45MB+ 在 WiFi PTP 上超时/被心跳判死；Z50II 实测数据点亦在此处掉线 |
| **R6** | **无能力协商**：`GetDeviceInfo` 的 `OperationsSupported` 数组被直接 `skipU32Array` 跳过，全仓从未解析 | `CameraParameterManager.kt:495-497` | 机身不声明 `0x101B` 时无从得知，只能盲目尝试后失败 |
| **R7** | **缺备用 tag 与哨兵**：只找 `0x00A7`，无 `0x0037` / `0x00A5`+`0x00A6` 退路，也无「n/a」哨兵值处理 | `:25-29`、`readNikonTag` 全文 | 电子快门机型与 D2X/D2H/D2Hs/D200 恒失败；异常值被判成合法 |
| **R8** | **云端兜底掩盖了本地 bug**：R0/R1 让本地路径**恒**失败，于是 Digeeker 从「兜底」变成「主路径」，第三方的可用性/接口变更直接变成产品故障 | `:439-461` | 可靠性与隐私双输 |
| **R9** | **测试夹具与实现同源**：旧测试把 MakerNote 挂在 **IFD0**、IFD 只放 **@8** —— 与实现的错误假设一模一样，所以 R0/R1 在一路绿色的测试下活了很久 | 旧 `NikonShutterCountParserTest.exifBlob` 只写 IFD0；仓内**没有任何真机 fixture** | 「有测试却仍是错的」的根因 |

> **一句话**：R0+R1 让它读不出来，R9 让这两个错在绿色测试下活了很久，R4 让它在读出来的时候可能是错的，R5 让它在真机大文件上跑不完。

---

## 三、方案：五层瀑布，每层只补一个失效模式

```
L0 采样选择   → 修 R4：最新的 NEF 优先，而不是最小的 JPEG
L1 头部局读   → 修 R5：只取 EXIF 所需的前 128KB，必要时二次精确窗口
L2 解析器重写 → 修 R1/R2/R3：三形态 + 各自正确的 (ifdStart, base)
L3 自校验     → 修 R7：备用 tag 交叉验证，用一致性判定"读对了"而非"值看着合理"
L4 云端降级   → 修 R8：仅在 L2/L3 明确失败时启用，且 UI 明示来源与原因
```

### L0 采样选择（新增，独立可单测）

排序键按优先级：

1. **`OdfDatetimeTaken` 最新的对象**（来自 `GetObjectInfo`，`fetchPhotoList()` 已在解析该字段）——这是唯一能保证「读到的就是当前计数」的键。
2. 日期时间缺失时退化到文件名序号（`DSC_NNNN` / `_DSCNNNN` 取最大），**标记为弱信号**：序号在 9999 回卷，且不同目录各自计数。
3. 格式优先级：**NEF > JPEG**。理由（exiftool / python-shutter-counter 一致做法）：NEF 的 MakerNote 不会被机身侧 JPEG 压缩环节裁剪；小尺寸/Basic 质量 JPEG 的笔记有被截断的风险。
4. JPEG 内部改为**偏大优先**（与现状相反）。因为 L1 之后传输成本与文件大小解耦（都只读头部），所以"挑最稳的"不再需要以"挑最小的"为代价。

**交叉验证**：取最新 2~3 张分别解析，要求读数**互差不大于阈值且随拍摄时间单调不减**。不一致时不显示单一数字，而是显示区间并提示「检测到读数不一致」。

**卡空场景**（按用户决策）：**不做现拍兜底**，直接 `FAILED(NO_CARD)` 并给出明确文案「存储卡内没有照片，无法读取快门次数」。理由：快门次数正是用户关心的数字，用一个会让它 +1 的操作去查询它在逻辑上自相矛盾，且相机可能对着裤兜拍出一张废片。相关能力已在代码里备好但**不接线**：`PtpConstants` 已声明 `OP_INITIATE_CAPTURE 0x100E`、`OP_NIKON_INITIATE_CAPTURE_REC_IN_SDRAM 0x90C0`（拍到 SDRAM 不落卡）、`OP_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA 0x9207`、`EVENT_NIKON_CAPTURE_COMPLETE_REC_IN_SDRAM 0xC102`（`PtpProtocol.kt:59,92,100,232`），未来若翻案可低成本启用。

### L1 头部局读

`GetPartialObject` = **`0x101B`**（参数序 `ObjectHandle, Offset32, MaxBytes32`；数据阶段响应，响应 `Param1` = 实际发送字节数；**按返回值推进，返回 0 即停**，libgphoto2 `ptp.c` 即此写法）。项目已实现：`PtpConstants.OP_GET_PARTIAL_OBJECT`（`PtpProtocol.kt:65`）、`PtpSessionManager.getPartialObject()`（`:650-667`）、`UsbPtpManager.getPartialObject()`（`:1093`）。且它已正确 `bulkDepth.incrementAndGet()`（`:656`），传输期心跳不判死——**这一层几乎零新增协议代码，只需把 `TransferManager` 的 private `partialObject` 收敛成一个公开入口**。

两步读取：

1. 读前 **128KB** → 定位 APP1 `Exif\0\0`（JPEG）或文件头 TIFF（NEF）→ IFD0 → 取 `0x927C` MakerNote 的 `{offset, size}`（**只要条目本身，不取值**）。
2. 若 `offset + size` 超出已读窗口，用一次 `getPartialObject(makerOffset, size)` 精确补取该段。
   - 形态 A/B 的偏移基准在 MakerNote 内部，所以独立 blob 可自洽解析；
   - **形态 C 的基准在主 TIFF 头**，脱离第 1 步的窗口就无法解释——这类笔记实测都很小且紧贴开头，落在 128KB 内，故实现上「只有第 1 步窗口内可解析」即可，不必为 C 做二次读取。

**能力探测**：把 `parseDeviceInfo` 里被跳过的 `OperationsSupported`（`CameraParameterManager.kt:495`）真正解析成 `Set<Int>` 缓存到 session。libgphoto2 的真实机身 dump（`camlibs/ptp2/cameras/*.txt`）显示，凡带 supported-operations 清单的尼康机型**全部声明了 0x101B**（D100/D200/D3/D300/D90/D3000-D5300/D5000/D5100/D5300/D600/D700/D7100/D750/D800/D810/D780/Z6/Z9）。未声明时回退整对象下载，但加 **2MB 硬上限**：读到上限即视为样本不足并给出明确失败原因，不做「悄悄拖完 45MB」。

**128KB 预算已被真机数据校准**：实测 MakerNote 本体位置与长度 —— D2Hs `@808 / 2180B`、NEF `@1744 / 4374B`、`_DSC8437` `@1076 / 7736B`、D70 JPEG `@1014 / 29322B`。全部落在 128KB 内，且都紧贴文件开头。
反过来，第 2 步的必要性也被同一个数据点证实：D70 的笔记长 29KB，一旦本地副本不完整（我这边这张就是截断到 12KB 的），`offset + size` 越界、解析直接放弃 —— **必须按发现到的 `{offset, size}` 精确补取，而不是赌一个更大的固定前缀**。

> **注意**：USB 与 WiFi 两条通道要**分别探测**。PTP/IP（TCP 15740）的 `GetDeviceInfo` 清单不保证与 USB 一致（Nikon WU-1x 走固定 GUID `0011223344556677`）。

### L2 解析器重写 → 见 §四规范

### L3 自校验（本方案的质量支点）

现状的合理性检查是 `count in 1..50_000_000`（`:28-29,202`）——太松，误读到别的 LONG 字段照样通过。改成**结构一致性校验**：

- **主判据**：尼康出厂固件满足 **`0x00A5 ImageCount + 0x00A6 DeletedImageCount == 0x00A7 ShutterCount`**（D2X/D2Hs/D2H/D200 上 exiftool 明确记载该恒等式，较新机型仍普遍成立）。用它判定「基准取对了」远比数值区间可靠。
- **备用 tag**：`0x0037 MechanicalShutterCount`（int32u，只计机械快门）。Z8/Z9 等电子快门机型上 `0x00A7` 含电子触发次数，与用户预期的"机械快门数"不一致（Z7II 用户投诉同源），需在 UI 上区分口径。
- **哨兵**：识别到异常大/负语义值（如 `0xFFFFF7FF`）时判为「机身未提供」而非成功。该哨兵值**待实机确认**——来源为社区实现，exiftool 标签页未直接记载（见 §九）。
- **单调性**：同一会话内，新照片读数 ≥ 旧照片读数。违反则报不一致，不显示单值。

### L4 云端降级

保留 `DigeekerShutterCountClient` 但**降级为显式选项**：默认关闭，L2/L3 失败且用户同意「上传一张照片到第三方解析」后才调用，UI 标注「云端解析」。`0x00A7` 本身从不加密（exiftool 恰恰用 `0x001d` SerialNumber + `0x00A7` ShutterCount 作为**解密其他块的密钥**，`Nikon.pm:14199`），所以修好 L1-L3 后云端几乎无用武之地。

---

## 四、EXIF 解析规范（可直接落码的真源表）

数据链：`APP1 "Exif\0\0"` → TIFF 头（`II`/`MM`）→ IFD0 → **`0x8769` Exif 子 IFD** → tag **`0x927C`** MakerNote → MakerNote 内 IFD → tag **`0x00A7`**，类型 `int32u`、count 1、**值内联在 12 字节条目里**。NEF 是 TIFF 容器，无 APP1，文件头即 TIFF 头，其余同构。

> **`0x927C` 不在 IFD0。** 三张真机样张实测一致；只翻 IFD0 是旧实现的第一号错误。IFD0 只能当宽松兜底。
> **因为这几个 tag 全是 `count=1` 的 LONG/SHORT、值内联，偏移基准（相对 MakerNote 还是相对主 TIFF 头）对读取结果毫无影响 —— 真正要紧的只有 IFD 起点与字节序两件事。** 表里的「数值偏移基准」列留给 L1 做二次窗口时定位 MakerNote 本体用。

| 形态 | 识别条件 | MakerNote 内 IFD 起点 | 数值偏移基准 | 字节序 |
|---|---|---|---|---|
| **A. `Nikon\0` + `0x02`**（现代 D/Z，**主流，覆盖当前全部目标机型**） | bytes 0-5 == `Nikon\0` 且 byte 6 == `0x02` | **+18**（= 内嵌 TIFF 头@10 + 头内 ifdOffset 8；三张真机样张全部命中） | **+10** | **由内嵌 `II*\0`/`MM\0*` 决定，且可以与主 TIFF 头相反** —— `NikonD2Hs.jpg`/`exiv2-nikon-d70.jpg` 的主 TIFF 是小端、笔记是大端 |
| B. `Nikon\0` + `0x01`（E 系列老机型） | byte 6 == `0x01` | +8 | +0 | 小端（注：`exiv2-nikon-e950.jpg` 形态 B 的笔记里未出现 `0x00A7`，老 Coolpix 可能压根不写） |
| C. 无签名头（D1、E99x、部分 Coolpix） | 主 IFD 的 `Make` 匹配 `^NIKON/i` **但笔记无 `Nikon\0` 签名**（不能因缺签名就 return null，旧实现 `:138` 错在此） | +0 | **主 TIFF 头起点**（绝对于 APP1 内） | 多为小端，**D1 为大端** |

补充事实（避免走弯路）：

- `0100/0102/0103/0200/0210/0221/0250/0270/03xx/04xx/08xx` 是 `MakerNoteVersion`（tag **`0x0001`**）字符串，会在 tag `0x0096`/`0x0097`/`0x0088` 头部重复出现；它们**只用于选 ColorBalance/FlashInfo/ShotInfo 子表，与 `0x00A7` 的位置无关**。不要按版本号查表。
- NEF 会多出 `0x00A4`（"version number found only in NEF images"），但 `0x00A7` 位置不变。
- 尼康 `0x00A7` **永不加密**；LibRaw 只把它当 XOR 密钥用、**从不对外暴露计数**（`src/metadata/nikon.cpp:835`）——所以不要引入 LibRaw 作为数据源。

实现要点：候选 `(ifdStart, base)` 按 **A → B → C** 优先级枚举（形态 A 内部再按「有/无内嵌 TIFF 头」两分支），每个候选解析出的值**必须过 §L3 的一致性校验才接受**，全部失败才落 L4。禁止在第一个结构合法的候选上提前 `return`（即 R2 的反面）。

---

## 五、改动面

**落地状态**（分支 `feat-shutter-count-v2.4` @ `20ec81c`）：L0/L1/L2/L3/UI 全部实装，`assembleDebug` 与 95 例单测通过。三处与原方案不同，都是实现期判断：

1. **前缀预算取 1MB 而非 128KB，且不做 §L1 的第二步精确窗口**。真机实测 MakerNote 最长 29KB、最远落在 `@1744`，1MB 富余足够；为覆盖一个尚未观测到的越界情形引入第二次 PTP 事务，收益低于复杂度。
2. **§L1 的 `OperationsSupported` 能力协商不做**，改为会话内 `partialReadUnsupported` 标志：局读首次失败即记住，后续样本直接走整文件下载。`parseDeviceInfo` 是监看/相册/参数共享的路径，为省一次往返去改它不值 —— 效果等价、风险归零。
3. ~~§L4 云端「需用户同意」的门未做~~ —— **已做**（`a295f00`）：默认只走本机；本机解不出时报 `CONSENT_REQUIRED`，
   点该行弹框说明「上传一张最新样张、给 digeeker.com、原片含序列号与可能的 GPS、授权一次持久生效」，
   同意才继续。未授权路径连整文件下载都不做，并删除留档样本 —— 不让用户原片停在 cache 里。
   同时 `0x0037` 仅机械读数不再被丢弃，两个值不等时并排显示为「N 次（本机解析） · 机械 M」。

| 文件 | 改动 | 状态 |
|---|---|---|
| `camera/params/NikonShutterCountParser.kt` | 跟随 `0x8769` 子 IFD；三形态 (IFD 位置 × 字节序) 枚举 + 恒等式裁决；备用 tag；`parseFile` 只读前 1MB；返回 `Reading(shutterCount/mechanicalCount/layout/verified)` | 已完成 |
| `camera/params/CameraParameterManager.kt` | `ShutterSamplePicker`（L0）+ 瀑布编排 + `ShutterFailReason` + `ShutterCountSource` 枚举化 + 局读降级标志 + 失败样本留档 + 云端同意门 | 已完成 |
| `camera/gallery/TransferManager.kt` | 新增公开 `readObjectHead(file, maxBytes)`，内部复用既有 private `partialObject`。**队列状态机零改动** | 已完成 |
| `shared/common/AppSettings.kt` | `shutterCloudConsent`（默认 false），与 `shareKeepGps` 同一套隐私默认 | 已完成 |
| `device/DashboardFragment.kt` + `res/values/strings.xml` | 显示来源 / 未校验 / 四类失败原因 / 机械口径；授权弹框走 `NlGlass.dialog`；快门文案全部进 strings.xml | 已完成 |
| 测试 | 解析器 17 例（夹具按真机结构重建）+ 样张挑选 6 例 | 已完成 |
| 真机黄金夹具 | **需用户自拍的 NEF + JPEG** 进 `app/src/test/resources/`，见 §九.3 | **待提供** |

---

## 六、验证

### 已完成（L2/L3 第一批）

**拿真机文件验证了 shipped 代码本身**，不只是验证 python 交叉实现。从 Exiv2 `test/data` 取的三张**完整**尼康文件，用重写后的 `NikonShutterCountParser` 实跑：

| 样张 | 读数 | 命中形态 | 备注 |
|---|---|---|---|
| `Nikon.nef`（裸 TIFF 容器） | **3619** | 内嵌头@10 → IFD@18 | 与独立 python 走查逐字节一致 |
| `NikonD2Hs.jpg` | **2** | 同上，**大端笔记 + 小端主 TIFF** | `0x00A5=2 + 0x00A6=0 == 0x00A7=2` → `verified=true`，恒等式在真机上成立 |
| `_DSC8437.exv` | **8541**（机械 **3487**） | 同上 | 顺带取到 `0x0037` |

三张全部证实：`0x927C` **只在 Exif 子 IFD**（IFD0 里没有），MakerNote 内嵌 TIFF 头在 +10、IFD 在 +18。`NikonD2Hs`/`exiv2-nikon-d70` 还证实了**笔记字节序可与主 TIFF 头相反** —— 所以「尼康一律小端」是错的，两种字节序必须都试。

合成夹具（`NikonShutterCountParserTest`，17 例）已按上述实测结构重建：MakerNote 挂进 Exif 子 IFD、形态 A/B/C 各自的位置、LE 容器 + BE 笔记、`0x0037`、恒等式成立/不成立、哨兵与不合理值、IFD0 宽松兜底、SHORT 型条目。旧夹具「按实现手的形状捏」的问题（R9）由此堵住。

**真机 fixture 进仓的阻碍**（已替这个坑做过排除，**别再走弯路**）：exiftool 自家 `t/images/Nikon*.jpg|nef` 四张**全部被剥掉 MakerNote**（无 `0x927C`，公开再分发的协议原因）；Wikimedia Commons 按「Nikon D750」搜到的样本里，取样的两张一张 EXIF 被剥、另一张机身其实是 `Panasonic DMC-G6` —— **标题写 Nikon 不代表机身是 Nikon**。目前唯一带真机 MakerNote 且可下载的就是 Exiv2 `test/data`，但它作者与授权链不明，只适合做**本地一次性验证**（本节表格就是这么来的），不宜塞进产品仓库。

→ 所以黄金夹具只能由用户自己拍。**好消息是夹具不需要整张原片**：真机实测 MakerNote 全部落在文件头 30KB 以内，提交约 64KB 的**前缀切片**即可作为字节级回归夹具，仓库不会因此变大。

还有一条**没走完就该走的路**，下次接手直接从这里开始：别再按标题搜（会捞到 Panasonic），要用 Commons 按 EXIF 自动归类的那个分类树
—— `Category:Images taken with Nikon D750`（API：`generator=category&gcmtitle=Category:Images taken with Nikon D750&prop=imageinfo&iiprop=url|size|extmetadata`，
从 `extmetadata` 里读 `LicenseShortName` 挑 PD/CC0，再 `curl -r 0-65535` 只取前 64KB）。本次这台机器的出口代理对该域名 TLS 不稳定
（同一查询第一次成功、之后连续 `SEC_E_WRONG_PRINCIPAL` / connection reset），所以这条路**未被证伪、只是没跑完**。

**当前测试的可信度边界，说清楚**：17 + 6 例合成测试已经把**真机验证过的结构**钉死了（子 IFD 定位、IFD@18、双字节序、恒等式、E-series 与无签名形态），
所以偏移被改错会立刻红。它们缺的只是「我们自己之外的第一方字节」——即用户机身实拍样张那一层的外部一致性，
而那正是 §十 第 3 条（与 exiftool / 售后口径对数）存在的理由。

### 待做

1. **L0 采样用例**：卡上同时存在「小但旧」与「大但新」两张时，必须选后者。
2. `./gradlew :app:testDebugUnitTest`（本机需 `--offline --gradle-user-home .gradle-home` 复用缓存）。
3. **真机数据点矩阵**（每格需一个可复现读数）：Z50II（已有 USB 监看数据点）、Z8/Z9（电子快门口径分歧）、D750 或 D810（形态 A 主力）、D200/D2X（恒等式）、Coolpix 或 D1（形态 C）。**双通道各跑一遍**（USB + WiFi PTP），因为 §L1 的能力清单按通道不同。
4. **交叉验证**：debug 构建里对同一样本同时走本机与云端，读数不一致即报警——用它验证 L2 的长期正确性。
5. **可诊断性**：失败时样本被 `target.delete()`（`:447,462`）删掉，无线索可回溯。需保留副本 + 导出诊断（`Make/Model/Firmware`、MakerNote 前 32 字节 hex、探测到的形态与 IFD 位置、各候选解出的值）。

---

## 七、明确不做（省时间）

- 任何 `GetDevicePropValue` 找尼康快门计数属性 —— §一 已穷举否证。
- 把 `0x101F` 当 `GetPartialObject` —— `0x101F` 是 `StopObjectEnumeration`，`GetPartialObject` 是 **`0x101B`**。
- 指望 `0x1038 GetObjectPropList` —— 实测**无任何机型在 `OperationsSupported` 里声明它**。枚举走 `0x1004 GetStorageIDs → 0x1007 GetObjectHandles → 0x1008 GetObjectInfo`；项目现有的 MTP `0x9805` 快路径可用，但要保留逐对象回退。
- 用 `GetThumb (0x100A)` / `0x90C4 GetLargeThumb` 当样本 —— 缩略图 JPEG **不含 MakerNote**。
- 引入 LibRaw 作数据源 —— 它只把 `0x00A7` 当解密密钥，不暴露计数。
- 拿后期处理过的文件（Lightroom/NX 导出）读数 —— 会被改写或剥掉 MakerNote。
- 期望从机身菜单读出总快门数 —— **无任何尼康机身在用户菜单/固件里提供该读数**，官方路径只有售后拆机读内部计数器。这条要写进帮助文案，别让用户白找。

---

## 八、风险与回滚

| 风险 | 处置 |
|---|---|
| 部分机身 `0x101B` 不声明 → L1 全废 | 回退整对象下载 + 2MB 上限，失败原因码 `PARTIAL_UNSUPPORTED`；L2 不受影响 |
| 形态 A 的字节序 | **已实测确认**：真机样张一律带位于 +10 的内嵌 TIFF 头，字节序由该头给出，且**可与主 TIFF 头相反**（D2Hs/D70 = 大端笔记 + 小端机身）。双字节序枚举 + L3 校验已覆盖 |
| 提前中断 `GetObject` 数据阶段会让相机侧事务滞留 | 只在 `0x101B` 不可用时才走整对象路径，且**读完即正常结束事务**，不中途掐流 |
| 电子快门机型读数与用户预期口径不符（`0x00A7` 含电子触发） | UI 明标口径 + `0x0037` 仅机械值并列展示 |
| 恒等式 `0xA5+0xA6==0xA7` 在较新固件上未必成立 | 作为**加分验证**而非硬门槛：不成立但值合理时接受，并置 `verified=false` |
| 整体回滚 | L2/L3 是纯函数、L1 是纯新增，两批均可独立 revert，不动已跑通的下载/监看主链路 |

---

## 九、开放问题（需真机或用户拍板）

1. **`0xFFFFF7FF`「n/a」哨兵**的确切语义与出现机型 —— 来源为社区实现，exiftool 标签页未直接记载，需真机样本确认。
2. ~~形态 A 无内嵌 `II*\0` 时的字节序~~ —— **已答**：真机样张一律带 +10 处的内嵌头；且笔记字节序可与主 TIFF 头相反，故不做任何「尼康一律小端」假设。
3. **真机黄金夹具**：需要用户自己拍的一张 NEF + 一张 JPEG 进 `src/test/resources`（开源仓库的样张要么剥了 MakerNote、要么授权链不明）。
4. **跨会话缓存：决定不做**（v2.4 定稿）。理由三条：① 键选错就是硬伤 —— 机身序列号才是唯一可靠的键，
   用 model 或 handle 做键会在「换机身 / 换卡」时把**另一台机器的快门数**显示成这台的历史值，
   这类错比"读数旧一点"严重得多；② 收益本就很小 —— 一次查询只是一趟 1MB 头部局读，秒级返回，
   而 `shutterQueryState != NONE` 已经保证同一连接内不重复查；③ 一旦持久化就必须回答
   「显示 X 分钟前的读数」这个口径问题，等于把一个功能变成两个。
   → 保持每次连接现读。若日后真要做，前提是 `CameraInfo` 先把 serialNumber 存下来并按它键控。
5. ~~分支基点~~ —— 已切 `feat-shutter-count-v2.4`（基线 `b4cf780`，即 v2.3 玻璃分支收尾后的 HEAD），实现提交 `20ec81c`。合并目标 master，由用户真机测试通过后执行。

---

## 十、真机测试清单（交付前）

连上机身 → 设备页点「快门次数」那一行。**每次都要看 `adb logcat -s CameraParams` 的 `Shutter count N from <file> (layout=… verified=…)`**，`layout` 与 `verified` 是判断"读对了"还是"碰巧读到个数"的唯一依据。

| # | 场景 | 预期 |
|---|---|---|
| 1 | 卡上有照片（USB） | 出数，「（本机解析）」；日志 `layout=inner@10`；**秒级返回**，不该看到整张 NEF 的下载进度 |
| 2 | 同上，WiFi PTP | 出数、同样秒级。这条是 §L1 的主要收益点 |
| 3 | **读数对不对**：机身菜单/售后口径或 exiftool 对同机一张照片的读数 | 必须一致。**不一致就是还有第三个错**，抓那张原片给我 |
| 4 | 新拍一张后再查 | 读数 ≥ 上一次（同机身单调不减） |
| 5 | 卡内只有 JPEG、无 NEF | 出数（样张挑选应选中 JPEG） |
| 6 | 清空存储卡后查 | 文案「存储卡内没有照片，无法读取快门次数」，**不是**「查询失败」了事 |
| 7 | 拍摄中途/链路断开时查 | 「读取失败，点击重试」；日志 `READ_FAILED` |
| 8 | 老机身（D70/D200/Coolpix/E995 一类） | 出数并 `verified=true`，或明确 `PARSE_FAILED`；**不应**出现「未校验」却数字离谱 |
| 9 | **默认不上传**（`a295f00` 起） | 全新安装后无论本机是否解得出，都不该有对 `api.digeeker.com` 的请求（抓包或 `dumpsys netstats` 确认） |
| 10 | 本机解不出的机身（老机型最易命中） | 该行显示「本机无法解析 · 点击可授权云端解析」，日志 `CONSENT_REQUIRED`；**点下去先弹授权框**，说清上传一张、给谁、含序列号/GPS；点「不用了」不应发生任何上传 |
| 11 | 同意之后 | 走云端并显示「（云端解析）」；此后**不再弹框**（授权持久），且 `cache/n-link_shutter/` 里不应残留样张 |
| 12 | Z8/Z9 等电子快门机身 | 若 `0x0037` 与 `0x00A7` 不等，应显示「N 次（本机解析） · 机械 M」；两者相等时**不该**重复出现「机械」字样 |

**要留档给我的一手资料**：任何一条不通过的用例，除了 logcat，请把 `cache/n-link_shutter/` 下的留档样本一并拉出来（`adb pull`），那是离线定位的唯一线索。
另外请**自拍一张 NEF + 一张 JPEG**、各截前 ~64KB 作为黄金夹具放进 `app/src/test/resources/`（切片即可，不需要整张原片；真机实测 MakerNote 都在文件头 30KB 以内）。开源图库这条路我已经替你把过关：exiftool 的样张被剥了 MakerNote，Commons 按机型搜到的还可能名不副实（见 §六）。
