# N-Link 夸克网盘发布记录

> 固定目录：夸克网盘 `/N-Link/releases/`（APK + version-info.txt）。
> 链接与提取码为公开信息（随 Release body 下发给用户），入库无敏感性。
> App 端解析规则见 `shared/update/UpdateChecker.kt`（QUARK_URL_REGEX / QUARK_CODE_REGEX）。

| 版本 | 分享链接 | 提取码 | 有效期 | 发布日期 | 备注 |
|---|---|---|---|---|---|
| v1.0.2 | https://pan.quark.cn/s/8af03310fbfd | （无，公开链接） | 永久 | 2026-09-05 | 分享对象=releases 文件夹；APK 3,671,480 字节与 GitHub 资产一致；url_type=1 免提取码，用户点击直达 |

> 历史：v1.0.2 曾先用带码分享 `858e1ffbde5b`（提取码 WTtr），后统一改为免提取码分享；
> 旧分享如不再使用建议在网盘端取消。

## 发版操作备忘（每版本）

1. `app/build/outputs/apk/release/app-release.apk` 重命名为 `N-Link-v{版本}-release.apk`，
   连同 `version-info.txt`（版本号/versionCode/日期/tag/摘要）一起传入 `/N-Link/releases/`。
2. 对 `releases` 文件夹新建分享：**免提取码（公开链接）+ 永久有效期**（2026-09-05 用户拍板，
   免码点击直达体验更好）。
3. Release body 固定标记行：`夸克网盘：<链接>`（无提取码时不含提取码字段，
   App 端 `quarkCode=null` 自动隐藏提取码提示）。
4. 本表新增一行归档；用无登录态 API 校验（公开链接 token 接口传空 passcode 应 200）。
5. 上传失败时不写标记（宁可无通道不写假链接）。
