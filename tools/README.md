# NikonLink connection verification tools

The app's WiFi channel speaks PTP/IP (ISO 15740 over TCP port 15740). These
tools give you a repeatable way to verify the wire protocol without needing an
Android emulator or a second phone.

## 1. Unit/integration tests (no hardware)

```powershell
& "$env:TEMP\gradle-8.11.1\bin\gradle.bat" :app:testDebugUnitTest --console=plain --no-daemon
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
