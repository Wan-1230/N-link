# N-Link 夸克网盘发布记录

> 固定目录：夸克网盘 `/N-Link/releases/`（**恒只保留最新版 APK + version-info.txt**）。
> 旧版本 APK 归档到 `/N-Link/_archive-releases/`（2026-09-09 起，发版上传后自动执行）。
> 链接与提取码为公开信息（随 Release body 下发给用户），入库无敏感性。
> App 端解析规则见 `shared/update/UpdateChecker.kt`（QUARK_URL_REGEX / QUARK_CODE_REGEX）。
> 文件夹分享是动态的：新文件传入自动出现在分享页，旧文件移出自动消失，无需重建分享。

| 版本 | 分享链接 | 提取码 | 有效期 | 发布日期 | 备注 |
|---|---|---|---|---|---|
| v1.3.1 | https://pan.quark.cn/s/a04626b6e249 | （无，公开链接） | 永久 | 2026-09-10 | 同一 releases 文件夹的分享（AI 经 quarkclouddrive CLI 上传）；APK 3,787,794 字节与 GitHub 资产一致，SHA-256 e3cfe503…c2486。归档：v1.3.0 APK → `_archive-releases`，旧 `version-info.txt`(1,272B, v1.3.0 内容) → `_archive-version-info`，新内容（781 字节）rename 回规范名 |
| v1.3.0 | https://pan.quark.cn/s/a04626b6e249 | （无，公开链接） | 永久 | 2026-09-10 | 同一 releases 文件夹的分享（AI 经 quarkclouddrive CLI 上传）；APK 3,787,118 字节与 GitHub 资产一致，SHA-256 cf2ea969…96c04。同时按约定归档：v1.2.2 APK → `_archive-releases`，旧 `version-info.txt`(839B, v1.2.2 内容) → `_archive-version-info`，新内容（1,272 字节）rename 回规范名 |
| v1.2.2 | https://pan.quark.cn/s/a04626b6e249 | （无，公开链接） | 永久 | 2026-09-09 | 同一 releases 文件夹的分享；APK 3,777,262 字节与 GitHub 资产一致，SHA-256 c8dd80f4…4a643。本次清理：历史重复的 version-info.txt(v1.2.0)/version-info(1).txt(v1.2.1) 移入 `/N-Link/_archive-version-info`，v1.2.2 内容（839 字节）改回规范名 `version-info.txt` |
| v1.2.1 | https://pan.quark.cn/s/a04626b6e249 | （无，公开链接） | 永久 | 2026-09-09 | 同一 releases 文件夹的分享（AI 经 quarkclouddrive CLI 自动上传）；APK 3,770,382 字节与 GitHub 资产一致，SHA-256 e414e1fc…a1df |
| v1.2.0 | https://pan.quark.cn/s/a04626b6e249 | （无，公开链接） | 永久 | 2026-09-07 | 同一 releases 文件夹的分享；APK 3,763,710 字节与 GitHub 资产一致，SHA-256 cbab8f1f…76c5 |
| v1.1.0 | https://pan.quark.cn/s/fcbccaf42e70 | （无，公开链接） | 永久 | 2026-09-06 | 同一 releases 文件夹的分享；v1.1.0 APK 3,717,244 字节与 GitHub 资产一致，SHA-256 与 Release 声明一致 |
| v1.0.2 | https://pan.quark.cn/s/8af03310fbfd | （无，公开链接） | 永久 | 2026-09-05 | 同一 releases 文件夹的分享；APK 3,671,480 字节与 GitHub 资产一致 |

> 历史：v1.0.2 曾先用带码分享 `858e1ffbde5b`（提取码 WTtr），后统一改为免提取码分享。
> 三条文件夹分享指向同一目录，均保持有效（旧链接分别供 v1.0.2 / v1.1.0 body 使用）。

## 发版操作备忘（每版本）

> **自动化已打通（2026-09-07）**：夸克官方 skill quarkclouddrive 1.0.18 已安装并授权
> （CLI `~/.workbuddy/skills/quarkclouddrive/scripts/quark-drive.cjs`，账号夸父3732），
> upload / share / create-folder 全链路演练通过。标准流程固化在
> `~/.workbuddy/skills/android-release-publish/SKILL.md` §7（含 releases 目录 fid、
> 免提取码永久分享参数、公开接口校验、CAC 授权码短时效与 reg.exe 黑名单坑位）。
> 以下手动流程仅作 CLI 不可用时的兜底。

1. `app/build/outputs/apk/release/app-release.apk` 重命名为 `N-Link-v{版本}-release.apk`，
   连同 `version-info.txt`（版本号/versionCode/日期/tag/摘要）一起传入 `/N-Link/releases/`。
   **注意（v1.2.2 实测）**：目录里已有同名 `version-info.txt` 时，上传会被自动改名为
   `version-info(2).txt`。上传后必须把旧的重名文件移出（v1.2.2 起放
   `/N-Link/_archive-version-info/`），再把新文件 rename 回规范名，保证分享内
   `version-info.txt` 恒为最新版内容。
2. 对 `releases` 文件夹新建分享：**免提取码（公开链接）+ 永久有效期**（2026-09-05 用户拍板，
   免码点击直达体验更好）。
3. Release body 固定标记行：`夸克网盘：<链接>`（无提取码时不含提取码字段，
   App 端 `quarkCode=null` 自动隐藏提取码提示）。
4. 本表新增一行归档；用无登录态 API 校验（公开链接 token 接口传空 passcode 应 200）。
5. 上传失败时不写标记（宁可无通道不写假链接）。
6. **归档旧版本（2026-09-09 起）**：上传校验通过后，把 releases 里非最新版 APK
   `move` 到 `/N-Link/_archive-releases/`，使分享页恒只呈现最新版。CLI 无 delete 命令，
   move 是既定替代（数据保留可回滚）；归档目录 fid
   `~1_08AzX-HgMoKnQiHi0SI0n06NFOqUrsC9PNOekiYIsIuNcrevpHZNsWHFG3_Km_wE8cpzJya1XuCnVkNpT5Yfo`。
   归档前用当次 browse 的 fid（别名每次查询不同），并注意 browse 的 `total` 可能大于
   单次 `file_list` 条数——逐文件名核对，防止漏归档。

## 归档记录

| 日期 | 操作 | 内容 |
|---|---|---|
| 2026-09-10 | v1.3.1 发版归档 | releases 移出 v1.3.0 APK(3,787,118) → `_archive-releases`；旧 `version-info.txt`(1,272B) → `_archive-version-info`；v1.3.1 内容(781B) rename 回 `version-info.txt`。公开分享页 a04626b6e249 复核：仅 `N-Link-v1.3.1-release.apk`(3,787,794) + `version-info.txt`(781) |
| 2026-09-10 | v1.3.0 发版归档 | releases 移出 v1.2.2 APK(3,777,262) → `_archive-releases`；旧 `version-info.txt`(839B) → `_archive-version-info`；v1.3.0 内容(1,272B) rename 回 `version-info.txt`。公开分享页 a04626b6e249 复核结果：仅 `N-Link-v1.3.0-release.apk`(3,787,118) + `version-info.txt`(1,272) |
| 2026-09-09 | 首次清理 | releases 移出 4 个历史 APK → `_archive-releases`：v1.0.2(3,671,480) / v1.1.0(3,717,244) / v1.2.0(3,763,710) / v1.2.1(3,770,382)；releases 保留 v1.2.2(3,777,262) + version-info.txt(839)。公开分享页 a04626b6e249 外部复核已同步只剩最新版 |
