#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
N-Link 仓库内容卫生检查（提交前置门禁）。

依据 docs/CONTRIBUTING.md「外部产品信息不入库」一条，扫描所有被 git 跟踪的
源码与文档文件，发现可识别第三方产品的表述时以非零退出码阻止提交。

用法：
    python tools/check_repo_hygiene.py            # 检查全仓
    python tools/check_repo_hygiene.py --staged   # 只检查暂存区
    python tools/check_repo_hygiene.py README.md  # 检查指定文件

退出码：
    0 = 通过
    1 = 发现违规
    2 = 执行出错
"""
from __future__ import print_function

import io
import os
import re
import subprocess
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SCAN_EXT = (".md", ".kt", ".kts", ".xml", ".py", ".gradle", ".properties")

# 本仓库自身的产物与自有文档，允许出现的词（逐字匹配）
ALLOW = {
    "N-Link", "NLink", "NIKON", "Nikon", "nikon", "PTP", "PTP-IP", "PIPA",
    "CIPA", "Android", "Google", "Kotlin", "Gradle", "Hilt", "Room",
    "OkHttp", "Compose", "GitHub", "Cloudflare", "夸克", "百度",
    "CONTRIBUTING", "本节内容已按仓库规则移除", "外部产品信息不入库",
    "外部产品对比内容不得提交到远端仓库",
}

# ── 规则表 ─────────────────────────────────────────────────────────
# (规则名, 正则, 说明)
#
# 重要：本文件自身也在清理范围内，关键词一律用码点拼接构造。
# 若写成字面量，会被清理规则改写掉，导致正则退化成空匹配（误报满天飞）。
def K(*cs):
    return "".join(chr(c) for c in cs)


_SOFTWARE = "|".join([
    "ZDROP", "ZRelay", "PixCake", "digiCamControl", "libgphoto2", "gphoto2",
    "SnapBridge", "Lightroom", "Snapseed", "VSCO", "Capture\\s?One",
    "darktable", "rawTherapee", "Blackmagic", "Final\\s?Cut", "DaVinci",
    "OneNote", "ComfyUI", "Stable\\s?Diffusion",
])
_CN_ALIAS = "|".join([
    K(0x5F71, 0x732B),                                # 影犀
    K(0x5F71, 0x63A7, 0x53F0),                        # 影控台
    K(0x50CF, 0x7D20, 0x86CB, 0x8C61),                # 像素蛋糕
    K(0x76F8, 0x673A, 0x5FEB, 0x4F20),                # 相机快传
    K(0x5F71, 0x901F, 0x4F20),                        # 影速传
    K(0x98CE, 0x98CE, 0x76F8, 0x673A),                # 飓风相机
])
_PACKAGES = (r"\bcom\.(nikon\.snapbridge|xiangtian|zdrop|geekmanlab"
             r"|camerasyncpro|truesight|tauber)\b")
_COMPARE = "|".join([
    K(0x7ADE, 0x54C1),                                # 竞品
    K(0x7ADE, 0x5BF9),                                # 竞对
    K(0x53CB, 0x5546),                                # 友商
    K(0x5BF9, 0x6807),                                # 对标
    K(0x6A2A, 0x5411, 0x5BF9, 0x6BD4),                # 横向对比
])
_REVERSING = "|".join([
    K(0x5B57, 0x8282, 0x7801),                        # 字节码
    K(0x53CD, 0x7F16, 0x8BD1),                        # 反编译
    K(0x8131, 0x58F3),                                # 脱壳
    K(0x89E3, 0x5305, 0x4EA7, 0x7269),                # 解包产物
    "dexdump", "smali",
])

RULES = [
    ("第三方软件名称", r"(?i)\b(%s)\b" % _SOFTWARE, "直接点名了外部软件"),
    ("第三方产品中文名/代号", r"(%s)" % _CN_ALIAS, "外部产品中文名或内部代号"),
    ("第三方应用包名", _PACKAGES, "外部应用包名"),
    ("竞品对比措辞", r"(%s)" % _COMPARE, "竞品分析视角的措辞"),
    ("静态分析/逆向痕迹", r"(%s)" % _REVERSING, "逆向工程痕迹"),
    (
        "外部产品链接",
        r"(nikonimglib\.com/snbr|dpreview\.com|xiaomi-cam|"
        r"com\.camerasyncpro|com\.zdrop|com\.geekmanlab)",
        "指向外部产品的链接",
    ),
]

# 「同款 / 借鉴 / 参考实现」等词在描述**本项目自有代码**时是合法的，
# 只有与外部产品名同现才违规 —— 由 COMBO_RULES 单独处理
COMBO_RULES = [
    (
        "外部产品 + 对照措辞",
        r"(?i)(%s|%s)"
        r".{0,20}(同款|同源|一致实现|对齐|借鉴|照搬|复刻)"
        % (_SOFTWARE, _CN_ALIAS),
        "把外部产品的做法写进了注释",
    ),
]

COMPILED = [(n, re.compile(p), d) for n, p, d in RULES]
COMPILED_COMBO = [(n, re.compile(p, re.S), d) for n, p, d in COMBO_RULES]

# 检查脚本自身与规则文档必然包含这些词，跳过
SELF_EXEMPT = {
    "tools/check_repo_hygiene.py",
    "tools/install_hooks.py",
    "docs/CONTRIBUTING.md",
    ".gitignore",
}

# 构建产物 / 生成目录天然含大量无关字符串
SKIP_DIRS = {
    "build", ".gradle", ".kotlin", ".git", ".workbuddy", "node_modules",
    "captures", "dist", "release_extract",
}


def git_files(staged=False):
    """取待检查的文件列表。"""
    cmd = ["git", "ls-files", "-z"]
    if staged:
        cmd = ["git", "diff", "--cached", "--name-only", "-z", "--diff-filter=ACMR"]
    try:
        out = subprocess.check_output(cmd, cwd=REPO_ROOT)
    except Exception:
        out = subprocess.check_output(["git", "ls-files", "-z"], cwd=REPO_ROOT)
    return [p for p in out.decode("utf-8", "replace").split("\0") if p]


def skip_path(rel):
    norm = rel.replace("\\", "/")
    if norm in SELF_EXEMPT:
        return True
    parts = norm.split("/")
    if any(p in SKIP_DIRS for p in parts):
        return True
    if not norm.endswith(SCAN_EXT):
        return True
    return False


def scan_file(rel):
    """返回 [(行号, 规则名, 说明, 行内容)]。"""
    path = os.path.join(REPO_ROOT, rel)
    try:
        with io.open(path, "r", encoding="utf-8", errors="replace") as f:
            lines = f.read().split("\n")
    except (IOError, OSError):
        return []
    hits = []
    for i, line in enumerate(lines, 1):
        if not line.strip():
            continue
        for name, rx, desc in COMPILED:
            m = rx.search(line)
            if m:
                hits.append((i, name, desc, m.group(0), line.strip()))
        for name, rx, desc in COMPILED_COMBO:
            m = rx.search(line)
            if m:
                hits.append((i, name, desc, m.group(0)[:60], line.strip()))
    return hits


def main():
    argv = sys.argv[1:]
    staged = "--staged" in argv
    explicit = [a for a in argv[1:] if not a.startswith("--")]

    if explicit:
        files = explicit
    else:
        files = [f for f in git_files(staged) if not skip_path(f)]

    if not files:
        print("[hygiene] 无待检查文件，通过。")
        return 0

    total_hits = []
    for rel in files:
        if skip_path(rel):
            continue
        for hit in scan_file(rel):
            total_hits.append((rel,) + hit)

    if not total_hits:
        print("[hygiene] 通过：%d 个文件，未发现第三方产品表述。" % len(files))
        return 0

    # 按文件聚合输出
    by_file = {}
    for rel, ln, name, desc, word, text in total_hits:
        by_file.setdefault(rel, []).append((ln, name, desc, word, text))

    print("")
    print("=" * 72)
    print("[hygiene] 发现 %d 处违规，涉及 %d 个文件" % (len(total_hits), len(by_file)))
    print("=" * 72)
    for rel in sorted(by_file):
        print("")
        print("  %s" % rel)
        for ln, name, desc, word, text in by_file[rel]:
            print("    L%-5d [%s] %s" % (ln, name, desc))
            print("           命中: %r" % word)
            print("           原文: %s" % (text[:150]))
    print("")
    print("-" * 72)
    print("依据 docs/CONTRIBUTING.md：外部产品信息不得入库。")
    print("处理方式：")
    print("  1. 能改写的 -> 改写为中立的技术描述（保留技术语义）")
    print("  2. 整段都是外部产品内容的 -> 删除整段，替换为占位说明")
    print("  3. 拿不准的 -> 从严处理，先删")
    print("")
    print("临时跳过（不推荐）：git commit --no-verify")
    print("=" * 72)
    return 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(2)
