# NikonLink

NikonLink 是一款面向尼康 Z 系列相机的 Android 连接与遥控应用，提供 BLE、WiFi PTP/IP、USB PTP 三通道连接。

## 功能

- BLE 配对与保活，兼容 SnapBridge 风格 GATT 服务
- WiFi 相机发现（mDNS `_ptp._tcp` / `_nikon._tcp` + 子网扫描）
- PTP/IP 远程快门、Live View、参数读写
- USB 有线传输优先，支持 USB Live View 与遥控
- 照片列表、缩略图、断点续传与整文件回退下载
- 深色相机控制台 UI

## 构建

```bash
./gradlew :app:assembleDebug
```

调试 APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 隐私

`local.properties`、签名文件、`.tmp_bili/` 本地素材与构建产物不会上传到仓库。
