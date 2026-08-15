# NikonLink

> 为尼康 Z 系列微单打造的 Android 连接与遥控伴侣 —— 稳定连接 · 极速传图 · 全功能遥控。

![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?style=flat-square&logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF?style=flat-square&logo=kotlin)
![minSdk](https://img.shields.io/badge/minSdk-29-00ACC1?style=flat-square)
![targetSdk](https://img.shields.io/badge/targetSdk-35-00897B?style=flat-square)
![Version](https://img.shields.io/badge/version-0.1.0-546E7A?style=flat-square)

NikonLink 是一款面向尼康 Z 系列微单（Z50II / Z6III / Z8 / Z9 / Zf 等）的开源 Android 应用，打通 **连接 → 浏览 → 传输 → 遥控 → 监看** 的完整链路，以「永不断联」为核心卖点：

- **稳**：BLE 心跳保活 + 前台服务常驻，断线自动重连（< 3s）
- **快**：USB / WiFi 双高速通道，智能调度与自动回退
- **全**：照片传输 + 远程快门 + 实时取景 + 参数管理四合一
- **简**：一次配对、自动恢复、零上手成本

---

## ✨ 功能特性

### 三通道连接

| 通道 | 能力 |
|------|------|
| **BLE** | 配对与心跳保活，采用尼康 BLE 配对协议，支持尼康吹鱼（Blowfish）加密 |
| **WiFi** | 相机发现（mDNS `_ptp._tcp` / `_nikon._tcp` + 子网扫描），PTP/IP（ISO 15740）全量会话 |
| **USB** | 有线优先通道，相机插入自动唤起，keepalive 保活，支持 USB Live View 与遥控 |

### 照片浏览与传输

- 相机存储卡照片列表，18 张/页分页加载 + 缩略图实时预览
- 格式筛选（全部 / 照片 / 视频 / RAW / JPG）
- 单张 / 批量 / 筛选 / 全部下载，传输队列支持暂停、恢复、取消
- **断点续传**：中断自动从断点恢复，失败自动重试并回退备用通道，传输去重
- 归档至 `DCIM/NikonLink`（MediaStore / Scoped Storage），「本地照片」Tab 随时回看

### 远程遥控拍摄

- 两段式快门：半按对焦 → AF 收敛后自动释放快门，确保合焦
- **B 门**遥控（实时曝光计时）、**定时拍摄**（2s/5s/10s/自定义）、**间隔拍摄**（延时摄影）
- 曝光三要素（光圈 / 快门 / ISO）、白平衡、对焦模式、测光等参数实时读写

### 实时取景（Live View）

- 实时画面传输，端到端低延迟（目标 < 200ms @ WiFi 5GHz）
- 触摸对焦、手动对焦驱动、双指缩放
- 构图网格叠加（三分线等）、全屏沉浸体验、重力感应横竖屏

### 连接稳定性

- 前台连接服务（`connectedDevice|dataSync`）保活 + 开机自启 + WorkManager 健康检查
- 连接状态机：通道升级、失败回退、指数退避重连
- 实时连接状态仪表盘与厂商后台策略适配

### 其他

- **快门次数查询**：机身 PTP 不支持时自动经 EXIF 解析（Digeeker 协议）
- 拍摄参数预设管理、传输完成通知、RAW 处理策略
- 黑白极简设计语言：DayNight 自适应主题、8px 圆角、PressEffect 按压反馈

---

## 🚀 快速开始

### 环境要求

- Android Studio（JDK 17 + Android SDK 35）
- Android 10（API 29）及以上真机（建议支持 BLE 5.0 与 5GHz WiFi）

### 构建

```bash
cd N-Link
./gradlew :app:assembleDebug
```

调试 APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 开发工具

无需真机即可联调协议栈：

```bash
# 在 PC 上启动模拟尼康相机（mDNS 广播 + PTP/IP 握手）
python tools/mock_nikon_ptp.py --advertise

# 探测相机 WiFi 的 PTP/IP 连通性（只读、逐包打印响应码）
python tools/ptp_probe.py --host 192.168.1.1
```

详细说明见 [tools/README.md](tools/README.md)。

---

## 🧱 技术栈

| 类别 | 选型 |
|------|------|
| 语言 / 构建 | Kotlin 2.1.0 · AGP 8.7.3 · Gradle Wrapper · KSP |
| UI | ViewBinding · Material Components 1.12 · ConstraintLayout · Navigation 2.8.5 |
| 架构 | MVVM + Repository，单向数据流（StateFlow / LiveData） |
| 依赖注入 | Hilt 2.53.1 |
| 异步 | Kotlin Coroutines 1.9.0 |
| 本地存储 | Room 2.6.1 · Gson |
| 后台任务 | WorkManager 2.10.0（hilt-work）· 前台服务 |
| 图片加载 | Coil 2.7.0 |
| 日志 | Timber |
| 测试 | JUnit4 · MockK 1.13.14 · coroutines-test |

## 🏗️ 项目结构

```text
app/src/main/java/com/nikonlink/app/
├── core/          # 连接协议核心：BLE / PTP/IP / USB / WiFi
├── data/          # Room 数据库与仓库层
├── di/            # Hilt 依赖注入模块
├── feature/       # 功能模块：仪表盘 / 实时取景 / 遥控 / 传输 / 设置
├── service/       # 前台连接服务、开机自启、健康检查
└── ui/            # 通用 UI 组件（PressEffect 等）
```

## 🧪 测试

```bash
./gradlew :app:testDebugUnitTest
```

覆盖 BLE 吹鱼加密、PTP 协议编解码、PTP/IP 会话状态机、相机文件格式判定等核心逻辑。

---

## 🔒 隐私与安全

- 纯本地通信：不依赖尼康云服务，默认不上传用户照片至第三方服务器
- 本地照片遵循 Android Scoped Storage 规范
- BLE 配对 Just Works + 应用层校验，WiFi 通道 WPA2/WPA3 加密
- `local.properties`、签名文件（`*.jks` / `.signing/`）、`.tmp_bili/` 素材、构建产物均不入库

## 🛣️ Roadmap

- [x] Phase 1：三通道连接 + 照片传输（MVP）
- [x] Phase 2：远程快门 / B 门 / 定时 / 间隔拍摄 + 参数读写
- [x] Phase 3：Live View 实时取景
- [ ] Phase 4：**AI 修图**（PRD 已完成，编辑器开发中）
- [ ] 批量编辑、会话恢复、HEIC / RAW 评估
- [ ] 更多机型适配（Z6III / Z8 / Z9 / Zf）、iOS 规划

---

## 🤝 贡献

欢迎参与共建：

1. 遵循现有代码风格（Kotlin 官方风格、中文注释、统一 `Timber` 日志 Tag）
2. 核心协议改动请附带单元测试，真机行为变更请补充说明
3. 提交前确保 `:app:testDebugUnitTest` 通过

## 📄 许可与声明

本项目为**个人开源研究项目**，与尼康公司无任何关联，非尼康官方应用；`Nikon` 商标归其各自所有者所有。

> ⚠️ 仓库当前尚未附带开源许可证文件。在明确授权之前，请勿用于商业分发或二次发布，如需授权请联系作者。

*如果这个项目对你有帮助，欢迎反馈连接稳定性问题，帮助我们一起改进。*
