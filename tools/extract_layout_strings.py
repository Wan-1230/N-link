# -*- coding: utf-8 -*-
"""视S3/T7：布局内中文 android:text 抽离进 strings.xml。

规则：
- 命名 = 视图 id 派生（tv_battery → tv_battery_text）；同名不同值自动 _2 后缀；
- 教程长文（tvApGuide/tvStaGuide/tvUsbGuide/tvFtpGuide/tvGuide 系）保留内联——
  它们仍在按功能迭代，先抽会返工（PRD §1 #5 修订口径）；
- 含 %s/%d 的文本需要后续人工补 formatted 属性，本脚本跳过它们并报告。
"""
import io
import re
import glob

SKIP_IDS = ('tvApGuide', 'tvStaGuide', 'tvUsbGuide', 'tvFtpGuide', 'tvHint')
RES = 'app/src/main/res/values/strings.xml'


def main():
    s = io.open(RES, encoding='utf-8').read()
    existing = dict(re.findall(r'<string name="([^"]+)">([^<]*)</string>', s))
    used = set(existing)
    inserts = []
    skipped_fmt = []
    replaced = 0

    def snake(name):
        return re.sub(r'(?<!^)(?=[A-Z])', '_', name).lower()

    for f in sorted(glob.glob('app/src/main/res/layout*/*.xml')):
        src = io.open(f, encoding='utf-8').read()
        out = src
        for m in re.finditer(r'<(\w+)([^>]*?android:text="([^"]+)"[^>]*?)(/?)>', src, re.S):
            tag, attrs, text = m.group(1), m.group(2), m.group(3)
            if not re.search(r'[一-龥]', text):
                continue
            idm = re.search(r'android:id="@\+id/(\w+)"', attrs)
            vid = idm.group(1) if idm else None
            if vid and any(vid.startswith(p) for p in SKIP_IDS):
                continue
            if '%s' in text or '%d' in text:
                skipped_fmt.append((f, text[:30]))
                continue
            base = (snake(vid) + '_text') if vid else 'txt_' + snake(re.sub(r'\W', '', text[:6]))
            name = base
            i = 2
            while name in used and existing.get(name, text) != text:
                name = '%s_%d' % (base, i)
                i += 1
            if name not in used:
                # XML 转义
                val = text.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
                inserts.append((name, val))
                existing[name] = text
                used.add(name)
            old = 'android:text="%s"' % text
            new = 'android:text="@string/%s"' % name
            out = out.replace(old, new, 1)
            replaced += 1
        if out != src:
            io.open(f, 'w', encoding='utf-8', newline='').write(out)

    if inserts:
        block = '\n'.join('    <string name="%s">%s</string>' % kv for kv in inserts)
        s = s.replace('</resources>', '    <!-- 视S3/T7：布局文案批量抽离（名字由视图 id 派生） -->\n' + block + '\n</resources>')
        io.open(RES, 'w', encoding='utf-8', newline='').write(s)
    print('replaced=%d new_strings=%d skipped_fmt=%d' % (replaced, len(inserts), len(skipped_fmt)))
    for x in skipped_fmt[:5]:
        print('  fmt-skip:', x)


if __name__ == '__main__':
    main()
