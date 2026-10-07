# -*- coding: utf-8 -*-
"""视T6：把 fragment_dashboard.xml 的四个自包含块抽取为 include 布局。

用法：python tools/extract_dashboard_blocks.py
产出：view_panel_wifi_ap/sta/usb.xml、view_device_card.xml + dashboard 内 include 替换。
幂等性：按 view_*.xml 是否已存在判断，重复运行直接退出。
"""
import io
import re
import os

SRC = 'app/src/main/res/layout/fragment_dashboard.xml'
BLOCKS = ['panelWifiAp', 'panelWifiSta', 'panelUsb', 'cardDevice']


def find_block(s, idname):
    for m in re.finditer(r'<LinearLayout\b[^>]*>', s):
        if 'android:id="@+id/%s"' % idname in m.group(0):
            open_end = m.end()
            depth = 1
            # 注意：块内存在自闭合 <LinearLayout ... />（如 apCandidateList），
            # group(3)=='/' 的标签不计深度，否则深度泄漏永不归零
            for t in re.finditer(r'<(/?)LinearLayout\b([^>]*?)(/?)>', s[open_end:]):
                if t.group(3) == '/':
                    continue
                depth += -1 if t.group(1) == '/' else 1
                if depth == 0:
                    close = s.find('>', open_end + t.start()) + 1
                    return m.start(), close, m.group(0)
            return None
    return None


def find_block_card(s, idname):
    # cardDevice 是 MaterialCardView
    for m in re.finditer(r'<com\.google\.android\.material\.card\.MaterialCardView\b[^>]*>', s, re.S):
        if 'android:id="@+id/%s"' % idname in m.group(0):
            open_end = m.end()
            depth = 1
            for t in re.finditer(
                    r'<(/?)com\.google\.android\.material\.card\.MaterialCardView\b([^>]*?)(/?)>',
                    s[open_end:]):
                if t.group(3) == '/':
                    continue
                depth += -1 if t.group(1) == '/' else 1
                if depth == 0:
                    close = s.find('>', open_end + t.start()) + 1
                    return m.start(), close, m.group(0)
            return None
    return None


def main():
    for name in BLOCKS:
        if os.path.exists('app/src/main/res/layout/view_%s.xml' % name):
            print('skip(已存在):', name)
            return
    s = io.open(SRC, encoding='utf-8').read()
    blocks = {}
    for name in BLOCKS:
        r = (find_block(s, name) if name != 'cardDevice' else find_block_card(s, name))
        assert r, name
        blocks[name] = r
        print(name, 'start=%d end=%d len=%d' % (r[0], r[1], r[1] - r[0]))

    for name in sorted(blocks, key=lambda k: -blocks[k][0]):
        start, end, open_tag = blocks[name]
        block = s[start:end]
        root_attrs = dict(re.findall(r'([\w:]+)="([^"]*)"', open_tag))
        layout_keys = [k for k in root_attrs if k.startswith('android:layout_')]
        tagname = re.match(r'<(\w+)', open_tag).group(1)

        inc_attrs = ['android:id="@+id/%s"' % name, 'layout="@layout/view_%s"' % name]
        inc_attrs += ['%s="%s"' % (k, root_attrs[k]) for k in layout_keys]
        inc = ('<!-- 视T6：本块抽取为独立布局（内容零改动，回退=revert 本提交） -->\n'
               '    <include\n        ' + '\n        '.join(inc_attrs) + ' />')

        keep = {k: v for k, v in root_attrs.items()
                if k != 'android:id' and not k.startswith('android:layout_')}
        attrs_str = ''.join('\n    %s="%s"' % (k, v) for k, v in keep.items())
        close = '</%s>' % tagname
        inner = block[len(open_tag):block.rfind(close)]
        newfile = ('<?xml version="1.0" encoding="utf-8"?>\n'
                   '<!-- 视T6 自 fragment_dashboard 抽取：原块内容零改动 -->\n'
                   '<%s%s>\n%s\n%s>\n' % (tagname, attrs_str, inner, tagname))
        io.open('app/src/main/res/layout/view_%s.xml' % name, 'w',
                encoding='utf-8', newline='').write(newfile)
        s = s[:start] + inc + s[end:]

    io.open(SRC, 'w', encoding='utf-8', newline='').write(s)
    print('dashboard new size:', s.count('\n') + 1)


if __name__ == '__main__':
    main()
