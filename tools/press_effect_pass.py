# -*- coding: utf-8 -*-
"""视S3/#6：给纯 View（TextView/ImageView）点击补 pressEffect 缩放反馈。

口径（PRD §1 #6）：MaterialButton 自带 ripple、自绘开关/行有自己的反馈，均跳过；
只对布局里声明为 TextView/ImageView 的 binding/item 引用，在其
setOnClickListener 之前插入 `.pressEffect()`（幂等：近邻已有 pressEffect 跳过）。
"""
import io
import re
import glob

# 1) id -> tag 类别表（从所有 layout 收集）
TAGS = {}
for f in glob.glob('app/src/main/res/layout*/*.xml'):
    s = io.open(f, encoding='utf-8').read()
    for m in re.finditer(r'<(\w[\w.]*)\b([^>]*?)>', s, re.S):
        tag, attrs = m.group(1), m.group(2)
        if tag in ('TextView', 'ImageView'):
            idm = re.search(r'android:id="@\+id/(\w+)"', attrs)
            if idm:
                TAGS[idm.group(1)] = tag

TARGETS = [
    'app/src/main/java/com/nikonlink/app/device/DashboardFragment.kt',
    'app/src/main/java/com/nikonlink/app/camera/gallery/TransferFragment.kt',
    'app/src/main/java/com/nikonlink/app/capture/RemoteFragment.kt',
    'app/src/main/java/com/nikonlink/app/settings/SettingsFragment.kt',
    'app/src/main/java/com/nikonlink/app/camera/gallery/PhotoGridAdapter.kt',
]

LINE_RE = re.compile(r'^(\s*)(?:(?:val|var)\s+\w+\s*=\s*)?'
                     r'((?:binding|item|holder)\.\w+)\.setOnClickListener')


def main():
    total = 0
    for p in TARGETS:
        try:
            lines = io.open(p, encoding='utf-8').read().split('\n')
        except FileNotFoundError:
            continue
        out = []
        added = 0
        for i, line in enumerate(lines):
            m = LINE_RE.match(line)
            if m:
                recv = m.group(2)
                vid = recv.split('.')[-1]
                cls = TAGS.get(vid)
                # 近邻已有**同一 receiver** 的 pressEffect 则跳过（不同控件不互相屏蔽）
                nearby = '\n'.join(lines[max(0, i - 4):i])
                if cls and (recv + '.pressEffect()') not in nearby:
                    indent = m.group(1)
                    out.append('%s%s.pressEffect()' % (indent, recv))
                    added += 1
            out.append(line)
        if added:
            io.open(p, 'w', encoding='utf-8', newline='\n').write('\n'.join(out))
        total += added
        print('%-64s +%d' % (p.split('/')[-1], added))
    print('pressEffect added:', total)


if __name__ == '__main__':
    main()
