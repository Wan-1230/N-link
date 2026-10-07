# -*- coding: utf-8 -*-
"""视T2/T9：间距裸 dp 的**精确值归位**到 space_* 标尺 token。

只替换值恰为 4/8/12/16/20/24 的 margin/padding 属性（零视觉位移）；
非栅格值（10dp 等）保持原样——改档会移像素，需要逐页视觉 QA。
"""
import io
import re
import glob

MAP = {4: 'space_4', 8: 'space_8', 12: 'space_12', 16: 'space_16',
       20: 'space_20', 24: 'space_24'}
ATTRS = ('layout_margin', 'layout_marginStart', 'layout_marginEnd',
         'layout_marginTop', 'layout_marginBottom',
         'layout_marginHorizontal', 'layout_marginVertical',
         'padding', 'paddingStart', 'paddingEnd', 'paddingTop',
         'paddingBottom', 'paddingHorizontal', 'paddingVertical')
PAT = re.compile(r'android:(%s)="(\d+)dp"' % '|'.join(ATTRS))


def main():
    total = 0
    for f in glob.glob('app/src/main/res/layout*/*.xml'):
        s = io.open(f, encoding='utf-8').read()
        cnt = [0]

        def rep(m):
            v = int(m.group(2))
            if v in MAP:
                cnt[0] += 1
                return 'android:%s="@dimen/%s"' % (m.group(1), MAP[v])
            return m.group(0)

        s2 = PAT.sub(rep, s)
        if s2 != s:
            io.open(f, 'w', encoding='utf-8', newline='').write(s2)
            total += cnt[0]
            print('%-52s %d' % (f, cnt[0]))
    print('spacing replaced total:', total)


if __name__ == '__main__':
    main()
