# 网盘发布台账（releases 通道）

> 每次发版一行。夸克为**固定分享长期复用**；百度**每版新链接**。
> v3.0.0 之前各版的台账未建立（2026-10-03 补建），不在此追记，避免凭记忆写假数据。

| 版本 | 日期 | 夸克（固定） | 百度（本版新链接） | 归档去向 |
|---|---|---|---|---|
| v3.3.1 | 2026-10-16 | https://pan.quark.cn/s/d060f7f350e8 | https://pan.baidu.com/s/1MYImdeo2ch67IK2vXd-xUA?pwd=f21e | 夸克/百度 `_archive-releases/`：v3.3.0 APK + version-info |
| v3.3.0 | 2026-10-16 | https://pan.quark.cn/s/d060f7f350e8 | https://pan.baidu.com/s/1DyneXLKntiEXsLtfQcx14g?pwd=46ym | 夸克/百度 `_archive-releases/`：v3.2.0 APK；version-info 夸克侧撞名落成 `version-info(7).txt`、百度侧已改名 `version-info-v3.2.0.txt` |
| v3.2.0 | 2026-10-15 | https://pan.quark.cn/s/d060f7f350e8 | 未记录（发版时漏建本行，链接不凭记忆补） | 于 v3.3.0 发版时归档 |
| v3.1.2 | 2026-10-14 | https://pan.quark.cn/s/d060f7f350e8 | https://pan.baidu.com/s/1E4lPN4Q-5tkxLLt2RA3fiA?pwd=57c5 | 夸克/百度 `_archive-releases/`：v3.1.1 APK + version-info-v3.1.1.txt |
| v3.1.1 | 2026-10-13 | https://pan.quark.cn/s/d060f7f350e8 | https://pan.baidu.com/s/1YhdwizMKA_a_nBvDx5F-xA?pwd=dbif | 夸克/百度 `_archive-releases/`：v3.1.0 APK + version-info-v3.1.0.txt |
| v3.1.0 | 2026-10-13 | https://pan.quark.cn/s/d060f7f350e8 | https://pan.baidu.com/s/1DZIAid5822Dk96QqbDdwGA?pwd=ecvf | 夸克/百度 `_archive-releases/`：v3.0.0 APK + version-info-v3.0.0.txt |
| v3.0.0 | 2026-10-03 | https://pan.quark.cn/s/d060f7f350e8 | https://pan.baidu.com/s/2ynD1puzNSAgJHiUFRiWlw?pwd=whet | 夸克/百度 `_archive-releases/`：v2.6.1 APK + version-info-v2.6.1.txt |

## 归档记录

- 2026-10-16（v3.3.1）：APK 4,277,954B、`version-info.txt` 809B（两盘字节一致，SHA-256 `e9887f1f…55a2`）。**夸克侧重演了 v1.2.2 的 version-info 撞名**：目录已有旧 `version-info.txt`，新文件被自动改名落成 `version-info(1).txt`；按参考文档规定顺序补救——先把旧 `version-info.txt` 与旧 `N-Link-v3.3.0-release.apk` 一并 `move` 到 `_archive-releases/`（取当次 browse 的 fid），再用 `rename`（items JSON 需 `schema_version:1` + `fid`/`old_name`/`new_name`）把 `version-info(1).txt` 改回规范名，browse 复核 releases 恰好 2 项且字节一致。夸克分享复用固定链接未新建。百度侧按「先归档腾名 → 再上传」零撞名：`mv` 旧 v3.3.0 APK 与旧 version-info → `_archive-releases/`，再上传两个规范名文件；分享**只生成一次**（`1MYImdeo2ch67IK2vXd-xUA`，`-d 0` 永久，提取码 `f21e`）。两处网盘的 body 标记已按 App 的 `QUARK_URL_REGEX` / `BAIDU_LINK_REGEX` 逐行自检通过。

- 2026-10-16（v3.3.0）：**两盘都按「先归档腾名 → 再上传新版」的顺序执行**，两个新文件均以规范名落地，全程零撞名补救（夸克 `N-Link-v3.3.0-release.apk` 4,274,218B + `version-info.txt` 1,195B；百度同两文件字节一致）。归档：夸克 releases 旧 `N-Link-v3.2.0-release.apk`（4,245,982B）与 `version-info.txt`（887B）→ `_archive-releases/`；百度同版两文件 → `_archive-releases/` 并把 version-info `rename` 成 `version-info-v3.2.0.txt`。**夸克侧如参考文档 §6 所预警没有便宜改名通道**，887B 那个进去后与归档区同名文件相撞，实测落成 `version-info(7).txt`（已 browse 核对），需整理时改用独立的 `_archive-version-info/` 目录。百度本版分享**只生成一次**（`1DyneXLKntiEXsLtfQcx14g`，`-d 0` 永久）。两盘公开校验均通过：夸克走 sharepage token(0)+detail 两层，核对到 releases 内恰好 2 项且字节一致；百度走 `transfer list --source-dir /releases`，同样恰好 2 项。**另发现本台账缺 v3.2.0 行**（2026-10-15 发版时未更新此文件），已补一行并把百度链接标为「未记录」——不凭记忆写数据。
- 2026-10-14（v3.1.2）：夸克 releases 旧 v3.1.1（4,253,202B）与 version-info.txt（801B）→ `_archive-releases/`；百度同版两文件 → `_archive-releases/`（version-info 改名 `version-info-v3.1.1.txt`）。本版百度分享**只执行一次**（`period:0` 永久），无误建多余分享。另修正本文件上一版遗留的表格缺陷（v3.1.1 与 v3.1.0 两行被并成一行）。
- 2026-10-13（v3.1.1）：夸克 releases 旧 v3.1.0（4,243,586B）与 version-info.txt（1411B）→ `_archive-releases/`（version-info 改名 `version-info-v3.1.0.txt`）；百度同版两文件 → `_archive-releases/`（version-info 改名 `version-info-v3.1.0.txt`）。本版百度分享**只生成一次**（`share_id 33968186604`，永久 `period:0`），未再复现上版误建两个分享的问题。
- 2026-10-13：夸克 releases 旧 v3.0.0（4,117,697B）与 version-info.txt（791B）→ `_archive-releases/`；百度同版两文件 → `_archive-releases/`（version-info 改名 `version-info-v3.0.0.txt`）。另：百度本次误建两个分享（`mbcIwwoLcOu32zZH3nGSKQ` 与 `1DZIAid5822Dk96QqbDdwGA`，均指向 releases、内容一致），CLI 无取消分享命令，待小万在百度网页端手动取消多余的一个。
- 2026-10-03：夸克 releases 旧 v2.6.1（4,005,836B）与 version-info.txt（912B）→ `_archive-releases/`；百度同版两文件 → `_archive-releases/`（version-info 改名 `version-info-v2.6.1.txt`）。
