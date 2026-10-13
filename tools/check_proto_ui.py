# -*- coding: utf-8 -*-
"""
原型 UI token 检查（审计 P2-14）—— 与 app 侧 tools/check_ui_tokens.py 共用同一套红线。

用法:
    python tools/check_proto_ui.py            # 红线全 0 才算过
    python tools/check_proto_ui.py --warn     # 只打印，不置退出码

四条红线:
  1. 裸字号: CSS 里 font-size 不走 --fs-* token
  2. 字符图标: HTML 里出现 ✓ ◐ ℹ › 这类字符当图标
  3. 硬编码色: token 定义区之外的 #rrggbb（相机矢量图色板等已登记的例外除外）
  4. 对比度: 三级/二级文字在页面/卡片/chip 底上未达 WCAG AA 4.5:1

只依赖标准库，CI 与提交前都能跑。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROTO = ROOT / "prototype" / "index.html"

# 相机矢量图、品牌色、玻璃层等刻意硬编码的例外（登记在案，不是遗漏）
ALLOWED_HEX = {
    "#FFE100", "#000000", "#FFFFFF", "#1A1B1E",
}
ICON_CHARS = ["✓", "◐", "ℹ", "›", "⟶", "▓"]


def blank(m: re.Match) -> str:
    """把一段内容抹成等长空白（保留换行），这样偏移量仍映射到原文，行号才准。"""
    return "".join(ch if ch == "\n" else " " for ch in m.group(0))


def strip_css_comments(css: str) -> str:
    """去掉 /* */ 注释但保留换行——注释里的「font-size:20px」是说明文字，不是渲染内容。"""
    return re.sub(r"/\*.*?\*/", blank, css, flags=re.S)

# 对比度矩阵：(文字 token, 背景 token) —— 覆盖三级/二级文字的实际使用场景
CONTRAST_PAIRS = [
    ("--text-primary", "--bg-page"), ("--text-primary", "--bg-card"),
    ("--text-secondary", "--bg-page"), ("--text-secondary", "--bg-card"),
    ("--text-tertiary", "--bg-page"), ("--text-tertiary", "--bg-card"),
    ("--text-tertiary", "--bg-card-2"), ("--text-tertiary", "--bg-chip"),
]
AA_THRESHOLD = 4.5


def srgb(c: float) -> float:
    c /= 255.0
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def luminance(rgb: tuple[int, int, int]) -> float:
    r, g, b = rgb
    return 0.2126 * srgb(r) + 0.7152 * srgb(g) + 0.0722 * srgb(b)


def contrast(fg: tuple[int, int, int], bg: tuple[int, int, int]) -> float:
    l1, l2 = luminance(fg), luminance(bg)
    hi, lo = max(l1, l2), min(l1, l2)
    return (hi + 0.05) / (lo + 0.05)


def hex_to_rgb(h: str) -> tuple[int, int, int]:
    h = h.lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)


def extract_tokens(css: str, selector: str) -> dict[str, str]:
    """抽取 :root / [data-theme="dark"] 块内的 --token: 值。"""
    m = re.search(re.escape(selector) + r"\s*\{(.*?)\}", css, re.S)
    if not m:
        return {}
    out: dict[str, str] = {}
    for name, val in re.findall(r"(--[\w-]+)\s*:\s*([^;]+);", m.group(1)):
        out[name] = val.strip()
    return out


def main() -> int:
    if not PROTO.exists():
        print(f"找不到原型文件: {PROTO}")
        return 2
    html = PROTO.read_text(encoding="utf-8")

    problems: list[str] = []

    # 采样口径：所有「屏蔽」操作都抹成等长空白而不是删字符，
    # 于是任意偏移量都能直接数回原文件行号，报告才不会指错地方。
    def line_at(s: str, off: int) -> int:
        return s[:off].count("\n") + 1

    # ---- 红线 1：裸字号 ----
    # 只保留 <style> 内部（注释已屏蔽），其余位置一律空白
    css = [ch if ch == "\n" else " " for ch in html]
    for m in re.finditer(r"<style>(.*?)</style>", html, re.S):
        seg = strip_css_comments(m.group(1))
        css[m.start(1):m.end(1)] = list(seg)
    css = "".join(css)
    for m in re.finditer(r"font-size\s*:\s*([^;}]+)", css):
        raw = m.group(1).strip()
        if "var(--fs-" in raw or raw in ("inherit", "0", "1em"):
            continue
        if re.search(r"\d+(px|rem|em|sp)\b", raw):
            problems.append(f"[裸字号] 文件第 {line_at(css, m.start())} 行: font-size:{raw}")

    # ---- 红线 2：字符图标 ----
    # 屏蔽 <style> 与 HTML 注释。script 不能整块屏蔽——JS 模板字符串拼出来的
    # “✓ / ▓▓”照样会渲染成图标，只屏蔽 script 内部的注释即可。
    rendered = [ch if ch == "\n" else " " for ch in html]
    for pat in (r"<style>.*?</style>", r"<!--.*?-->"):
        for m in re.finditer(pat, html, re.S):
            for i in range(m.start(), m.end()):
                rendered[i] = " " if html[i] != "\n" else "\n"
    for m in re.finditer(r"<script>(.*?)</script>", html, re.S):
        base = m.start(1)
        rendered[base:base + len(m.group(1))] = list(m.group(1))  # 先放回正文
        for cm in re.finditer(r"/\*.*?\*/|(?<![:/])//[^\n]*", m.group(1), re.S):
            for i in range(base + cm.start(), base + cm.end()):
                if html[i] != "\n":
                    rendered[i] = " "
    rendered = "".join(rendered)
    for ch in ICON_CHARS:
        for m in re.finditer(re.escape(ch), rendered):
            problems.append(f"[字符图标] 文件第 {line_at(rendered, m.start())} 行: “{ch}”")

    # ---- 红线 3：token 定义区（:root 与 [data-theme="dark"]）之外的硬编码色 ----
    # 注意：两个块都要排除，否则深色 token 会被当成「token 区外的裸色」误报。
    outside = [c for c in css]
    for m in re.finditer(r':root\s*\{.*?\}|\[data-theme="dark"\]\s*\{.*?\}', css, re.S):
        for i in range(m.start(), m.end()):
            outside[i] = " " if outside[i] != "\n" else "\n"
    outside = "".join(outside)
    for m in re.finditer(r"#[0-9a-fA-F]{6}\b|#[0-9a-fA-F]{3}\b", outside):
        val = m.group(0)
        if val.upper() in {h.upper() for h in ALLOWED_HEX}:
            continue
        problems.append(f"[硬编码色] 文件第 {line_at(css, m.start())} 行: {val}")

    # ---- 红线 4：对比度 ----
    light = extract_tokens(css, ':root')
    dark = extract_tokens(css, '[data-theme="dark"]')
    for theme_name, merged in (("浅色", dict(light)), ("深色", {**light, **dark})):
        for fg_tok, bg_tok in CONTRAST_PAIRS:
            fg_raw = merged.get(fg_tok)
            bg_raw = merged.get(bg_tok)
            if not fg_raw or not bg_raw:
                continue
            if not (re.fullmatch(r"#[0-9a-fA-F]{6}", fg_raw)
                    and re.fullmatch(r"#[0-9a-fA-F]{6}", bg_raw)):
                continue
            ratio = contrast(hex_to_rgb(fg_raw), hex_to_rgb(bg_raw))
            if ratio < AA_THRESHOLD:
                problems.append(
                    f"[对比度] {theme_name} {fg_tok} on {bg_tok} = {ratio:.2f}:1 "
                    f"(< {AA_THRESHOLD})"
                )

    if problems:
        print(f"✗ {len(problems)} 处红线未过：")
        for p in problems:
            print("  -", p)
        return 1 if "--warn" not in sys.argv else 0

    print("✓ 原型 UI token 四条红线全部为 0")
    print(f"  字号/图标/硬编码色/对比度（{len(CONTRAST_PAIRS)} 组合 × 双主题，阈值 {AA_THRESHOLD}:1）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
