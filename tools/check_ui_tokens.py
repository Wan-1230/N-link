# -*- coding: utf-8 -*-
"""视S3/T9：UI token 渗透检查（PRD「lint 规则」的仓库落地形式）。

本仓没有自定义 Android Lint 框架，引入成本高于收益；用可重复执行的
检查脚本等价达成——CI/提交前跑一次，口径与 PRD §4 指标表一致：

  python tools/check_ui_tokens.py          # 全部检查，非零退出 = 有回归
检查项（红线，输出 0 才算过）：
  1. layout 裸 textSize 数（目标 0）
  2. 动效裸时长数（`setDuration([0-9]` 目标 0）
  3. layout 硬编码色数（非 @color/@drawable 的 " 开头色值，目标 0）
  4. 栅格间距裸 dp 数（4/8/12/16/20/24 未走 space_*，目标 0）
输出同时给出非栅格间距（10dp 等）的存量数供趋势观察（不设红线）。
"""
import io
import re
import glob
import sys


def count_files(pattern_files, regex):
    total = 0
    per = {}
    for f in pattern_files:
        s = io.open(f, encoding='utf-8').read()
        n = len(re.findall(regex, s))
        if n:
            per[f] = n
            total += n
    return total, per


def main():
    layouts = glob.glob('app/src/main/res/layout*/*.xml')
    kt = glob.glob('app/src/main/java/**/*.kt', recursive=True)
    grid = {4, 8, 12, 16, 20, 24}

    fail = 0

    # 1 裸 textSize
    n, per = count_files(layouts, r'android:textSize="[^@]')
    print('[1] 裸 textSize:', n)
    fail += n

    # 2 动效裸时长
    n, per = count_files(kt, r'setDuration\([0-9]')
    print('[2] 动效裸时长:', n)
    fail += n

    # 3 硬编码色
    n, per = count_files(layouts, r'="(?:#[0-9A-Fa-f]{3,8})"')
    print('[3] layout 硬编码色:', n)
    fail += n

    # 4 栅格间距裸 dp
    pat = re.compile(r'android:(?:layout_)?(?:margin|padding)\w*="(\d+)dp"')
    n_grid = 0
    n_odd = 0
    for f in layouts:
        s = io.open(f, encoding='utf-8').read()
        for v in pat.findall(s):
            if int(v) in grid:
                n_grid += 1
            else:
                n_odd += 1
    print('[4] 栅格间距裸 dp:', n_grid, '（目标 0）')
    print('    非栅格间距存量:', n_odd, '（趋势观察项，不设红线）')
    fail += n_grid

    print('== 合计违规:', fail, '==')
    sys.exit(1 if fail else 0)


if __name__ == '__main__':
    main()
