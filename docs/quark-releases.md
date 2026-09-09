# N-Link 夸克网盘发布记录

> 固定目录：夸克网盘 `/N-Link/releases/`（各版本 APK + version-info.txt）。
> 链接与提取码为公开信息（随 Release body 下发给用户），入库无敏感性。
> App 端解析规则见 `shared/update/UpdateChecker.kt`（QUARK_URL_REGEX / QUARK_CODE_REGEX）。

| 版本 | 分享链接 | 提取码 | 有效期 | 发布日期 | 备注 |
|---|---|---|---|---|---|
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
2. 对 `releases` 文件夹新建分享：**免提取码（公开链接）+ 永久有效期**（2026-09-05 用户拍板，
   免码点击直达体验更好）。
3. Release body 固定标记行：`夸克网盘：<链接>`（无提取码时不含提取码字段，
   App 端 `quarkCode=null` 自动隐藏提取码提示）。
4. 本表新增一行归档；用无登录态 API 校验（公开链接 token 接口传空 passcode 应 200）。
5. 上传失败时不写标记（宁可无通道不写假链接）。
