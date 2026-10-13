# -*- coding: utf-8 -*-
"""视T6 第二步：DashboardFragment 的 binding 引用改到 include 子命名空间。

顺序（关键）：
 A. 先把面板根的 View 用途改成 .root（原始代码里子 id 从不带面板前缀，安全）；
 B. 再把子 id 改成 binding.<panel>.<id>（长名优先，防前缀吞并）。
幂等：以 view_*.xml 存在 + Fragment 未改过为前置；重复运行会二次加 .root，
故用「已含 .root 则跳过」自保护。
"""
import io
import re

P = 'app/src/main/java/com/nikonlink/app/device/DashboardFragment.kt'
PANEL = {
    # 2026-10-13 连接页精简：AP 页签的候选区（tvApCandidatesHeader / apCandidateList）已删，
    # 这里随之收缩 —— 本表是「子 id 属于哪个面板」的唯一出处，留死名字会误导后续改动。
    'panelWifiAp': ['tvApGuide'],
    'panelWifiSta': [
        'staArchPill', 'staArchIndicator', 'tabStaArchPtp', 'tabStaArchFtp',
        'layoutStaPtp', 'tvStaGuide', 'staModeRow', 'chipStaSameWifi',
        'chipStaHotspot', 'btnStaHostRegister', 'tvStaHostRegStatus',
        'recentDeviceList', 'staCandidateList', 'layoutStaFtp', 'tvFtpGuide',
        'tvFtpState', 'tvFtpEndpoint', 'tvFtpUser', 'tvFtpPass', 'btnFtpToggle',
        'tvFtpHint', 'tvFtpStats', 'ftpLogList',
    ],
    'panelUsb': ['tvUsbGuide', 'tvUsbDevice'],
    'cardDevice': [
        'viewStatusIndicator', 'tvConnectionStatus', 'progressConnecting',
        'tvStatusMessage', 'tvCameraName', 'rowBattery', 'tvBattery',
        'rowStorage', 'pbStorage', 'tvStorage', 'rowShotsRemaining',
        'tvShotsRemaining', 'rowLens', 'tvLens', 'btnConnect', 'btnDisconnect',
    ],
}


def main():
    s = io.open(P, encoding='utf-8').read()
    # A：面板根 → .root
    a = 0
    for pid in PANEL:
        pat = re.compile(r'binding\.%s\b(?!\.root)' % pid)
        a += len(pat.findall(s))
        s = pat.sub('binding.%s.root' % pid, s)
    # B：子 id → binding.<panel>.<id>，长名优先（防 heroBadge/heroBadgeDot 式前缀吞并）
    b = 0
    pairs = sorted(((kid, pid) for pid, kids in PANEL.items() for kid in kids),
                   key=lambda kv: -len(kv[0]))
    for kid, pid in pairs:
        pat = re.compile(r'binding\.%s\b' % kid)
        b += len(pat.findall(s))
        s = pat.sub('binding.%s.%s' % (pid, kid), s)
    io.open(P, 'w', encoding='utf-8', newline='').write(s)
    print('root refs=%d, child refs=%d' % (a, b))


if __name__ == '__main__':
    main()
