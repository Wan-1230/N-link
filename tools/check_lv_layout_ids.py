# -*- coding: utf-8 -*-
"""
横竖屏布局 ID 契约的机械校验（C3 前置项 / L1）。

背景
----
`res/layout-land/fragment_liveview.xml` 文件头写着一句口头契约：
「ID 集合必须与 portrait 一致」。2013 行复制维护两份、靠人记住这句话，
改一头忘一头是迟早的事——而漏掉的那一头在编译期**不会报错**，
要等到那一种方向下点某个按钮才崩（NoSuchElementException / null binding）。

这个脚本把口头契约变成 CI 可跑的机械检查。

用法
----
    python tools/check_lv_layout_ids.py                 # 校验默认布局 fragment_liveview
    python tools/check_lv_layout_ids.py fragment_remote # 校验指定布局
    python tools/check_lv_layout_ids.py --all           # 校验所有存在 -land 变体的布局

退出码：0 = 契约成立；1 = 有差异（会逐项列出）；2 = 文件缺失。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LAYOUT_DIR = ROOT / "app" / "src" / "main" / "res" / "layout"
LAND_DIR = ROOT / "app" / "src" / "main" / "res" / "layout-land"

DEFAULT_LAYOUT = "fragment_liveview"


def declared_ids(xml: str) -> set[str]:
    """布局里**定义**的 id（@+id/xxx）。"""
    return set(re.findall(r'@\+id/(\w+)', xml))


def referenced_ids(xml: str) -> set[str]:
    """布局里**引用**的 id（@id/xxx，不带加号）。"""
    return set(re.findall(r'(?<![\w+])@id/(\w+)', xml))


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def check_one(name: str) -> list[str]:
    portrait = LAYOUT_DIR / f"{name}.xml"
    land = LAND_DIR / f"{name}.xml"

    if not portrait.exists():
        return [f"缺少竖屏布局: {portrait}"]
    if not land.exists():
        # 没有横屏变体 = 两个方向共用一份，天然一致，不算违约
        return []

    p_xml, l_xml = read(portrait), read(land)
    p_ids, l_ids = declared_ids(p_xml), declared_ids(l_xml)

    problems: list[str] = []

    only_p = sorted(p_ids - l_ids)
    only_l = sorted(l_ids - p_ids)
    if only_p:
        problems.append(f"{name}: 只在竖屏定义的 id（横屏会拿不到 binding）: {only_p}")
    if only_l:
        problems.append(f"{name}: 只在横屏定义的 id（竖屏会拿不到 binding）: {only_l}")

    # 引用了但两边都没定义的 id：编译期能抓到，提前在这里给出更可读的提示
    for label, xml, ids in (("竖屏", p_xml, p_ids), ("横屏", l_xml, l_ids)):
        missing = sorted(referenced_ids(xml) - ids)
        if missing:
            problems.append(f"{name}: {label}引用了本文件未定义的 id: {missing}")

    return problems


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    names = args or [DEFAULT_LAYOUT]
    if "--all" in sys.argv:
        names = sorted(p.stem for p in LAND_DIR.glob("*.xml"))
        if not names:
            print(f"layout-land 下没有布局，跳过: {LAND_DIR}")
            return 0

    problems: list[str] = []
    for n in names:
        problems += check_one(n)

    if problems:
        print(f"✗ 横竖屏 ID 契约不成立（{len(problems)} 处）：")
        for p in problems:
            print("  -", p)
        return 1

    checked = []
    for n in names:
        land = LAND_DIR / f"{n}.xml"
        if land.exists():
            p_ids = declared_ids(read(LAYOUT_DIR / f"{n}.xml"))
            l_ids = declared_ids(read(land))
            checked.append(f"{n}: {len(p_ids)}/{len(l_ids)} 个 id 一致")
        else:
            checked.append(f"{n}: 无横屏变体（两向共用）")
    print("✓ 横竖屏 ID 契约成立")
    for c in checked:
        print("  -", c)
    return 0


if __name__ == "__main__":
    sys.exit(main())
