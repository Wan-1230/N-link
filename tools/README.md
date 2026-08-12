# NikonLink connection verification tools

The app's WiFi channel speaks PTP/IP (ISO 15740 over TCP port 15740). These
tools give you a repeatable way to verify the wire protocol without needing an
Android emulator or a second phone.

## 1. Unit/integration tests (no hardware)

```powershell
# gradlew.bat 的 wrapper jar 已损坏，用本地发行版构建（GRADLE_USER_HOME 指向 D:\gradle-home）
$env:GRADLE_USER_HOME="D:\gradle-home"; $env:JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot"
& "D:\Android\gradle-8.11.1\bin\gradle.bat" -p d:\N-Link :app:testDebugUnitTest --console=plain
```

The tests start an in-process mock camera and run the app's real
`PtpSessionManager` through the full PTP/IP handshake, data commands and
SetDevicePropValue data phase.

## 2. Mock Nikon camera (phone + PC on the same WiFi)

```powershell
python tools\mock_nikon_ptp.py --advertise
```

- Allow inbound TCP `15740` and UDP `5353` in the Windows firewall when asked.
- The mock advertises `NIKON-LINK-MOCK` over mDNS, so the app's WiFi scan can
  discover it directly.
- Tap the mock camera in the app and every PTP/IP packet the app sends is
  printed on the PC. This shows exactly what the real camera would receive.

## 3. Probe a real camera WiFi IP

```powershell
python tools\ptp_probe.py --host 192.168.1.1
python tools\ptp_probe.py --host 192.168.1.1 --keepalive 5
```

The probe is read-only and prints each response code. It is the ground-truth
check: if the probe succeeds while the app fails, the bug is in the app; if the
probe fails too, check camera WiFi state, another connected host, or the
camera's pairing prompt.

## 4. Android device logs

With the phone connected over USB:

```powershell
D:\Android\Sdk\platform-tools\adb.exe logcat -s BleManager:* PtpSession:* ConnectionMgr:* WifiManager:* UsbPtp:*
```

The log tags above cover the BLE pairing stages, PTP/IP handshake, connection
state machine and USB keepalive.

## 5. AI 修图工具（PRD-AI修图）

M5 真机验收一键脚本（安装 APK → 推送测试照片 → 启动编辑器 → 采集耗时打点 → 截屏取证）：

```powershell
.\tools\m5_acceptance.ps1                              # 单设备，默认测试图
.\tools\m5_acceptance.ps1 -Device <序列号> -Image D:\photos\test_24mp.jpg
```

模型发布门槛校验（models.json 回填 url/sha256 后，发布前运行）：

```powershell
python tools\check_models.py
```
