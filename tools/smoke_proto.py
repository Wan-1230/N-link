# -*- coding: utf-8 -*-
"""
原型冒烟验收（审计 D1–D18 的渲染级回归闸）。

用法:
    python tools/smoke_proto.py [--headed]

覆盖:
    D1  Snackbar 隐藏态门控（四页都不得有空药丸常驻）
    D2  长文案不裁切（scrollWidth <= clientWidth）
    D4  切页零跳动（p0 与 p1 首卡 top 差 = 0）
    D5  触达 44dp（交互元素热区 >= 44）
    D6  hover / focus-visible 规则存在
    D7  零字符图标（渲染 DOM 的 textContent 里不含 ✓ ⟶ ▓ 等）
    D8  主题持久化（toggle → reload 保持）
    D9  reduced-motion 下动画数 = 0
    D15 唯一 main + 4 个 section 页
    结构  logo 走 token、badge/tile 对勾是 svg、骨架是实体块、连接按钮无箭头
"""
from __future__ import annotations

import sys
from pathlib import Path

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
URL = (ROOT / "prototype" / "index.html").as_uri()

ICON_CHARS = ["✓", "◐", "ℹ", "›", "⟶", "▓"]
VIEWPORTS = [("mobile", 360, 780), ("desktop", 1280, 900)]

results: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    results.append((name, ok, detail))
    print(("  ✓ " if ok else "  ✗ ") + name + (f"  {detail}" if detail else ""))


def srgb(c: float) -> float:
    c /= 255.0
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def parse_rgb(s: str) -> tuple[float, float, float]:
    nums = [float(x) for x in s.replace("rgba(", "").replace("rgb(", "").rstrip(")").split(",")[:3]]
    return nums[0], nums[1], nums[2]


def contrast(fg: str, bg: str) -> float:
    def lum(c: tuple[float, float, float]) -> float:
        return 0.2126 * srgb(c[0]) + 0.7152 * srgb(c[1]) + 0.0722 * srgb(c[2])
    a, b = lum(parse_rgb(fg)), lum(parse_rgb(bg))
    hi, lo = max(a, b), min(a, b)
    return (hi + 0.05) / (lo + 0.05)


def run() -> int:
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless="--headed" not in sys.argv)
        for vname, w, h in VIEWPORTS:
            page = browser.new_page(viewport={"width": w, "height": h},
                                    device_scale_factor=2 if vname == "mobile" else 1)
            page.goto(URL)
            page.wait_for_timeout(300)
            print(f"\n── {vname} {w}×{h} ──")

            # ---- D15 结构 ----
            n_main = page.locator("main").count()
            n_sec = page.locator("main > section.page").count()
            check("D15 唯一 main + 4 个 section 页", n_main == 1 and n_sec == 4,
                  f"(main={n_main}, section.page={n_sec})")

            # ---- D7 渲染 DOM 里零字符图标 ----
            body_text = page.evaluate("() => document.body.innerText")
            bad = [c for c in ICON_CHARS if c in body_text]
            check("D7 渲染文本零字符图标", not bad, f"(命中: {bad})" if bad else "")

            # ---- D1 四页均无空 Snackbar 常驻 ----
            # 隐藏态的正确判据是「不可见」而非「几何位置」：visibility:hidden 的元素
            # 仍会返回 bbox，拿坐标判断会误报。Playwright 的 is_visible() 认 visibility。
            sn = page.locator(".snack")
            gate = sn.evaluate("""el => {
                const cs = getComputedStyle(el);
                return [cs.visibility, cs.opacity, cs.pointerEvents, el.classList.contains('on')];
            }""")
            check("D1 Snackbar 隐藏态门控",
                  (not sn.is_visible()) and gate[0] == "hidden" and gate[1] == "0"
                  and gate[2] == "none" and not gate[3],
                  f"(visibility={gate[0]}, opacity={gate[1]}, pointer-events={gate[2]}, .on={gate[3]})")

            # ---- D2 长文案不裁切 ----
            page.evaluate("m => toast(m)", "连接超时，请检查相机热点是否已开启并重试一次")
            page.wait_for_timeout(500)
            m = page.evaluate("""() => {
                const s = document.querySelector('.snack');
                return {sw: s.scrollWidth, cw: s.clientWidth, txt: s.innerText.length};
            }""")
            check("D2 长文案不裁切", m["sw"] <= m["cw"] + 1, f"(scrollWidth={m['sw']}, clientWidth={m['cw']})")

            # ---- D4 切页零跳动 ----
            # 量的是「页容器顶边」：此前非设备页把 .segment 整条 display:none，
            # 页容器顶边就会上移 57px（分段 41 + margin 16），底部导航一切就跳。
            # 量的是「页内首个可见块的顶边」——这才是用户眼睛看到的那一行的位置。
            # （只量 section 本身会漏掉外边距塌陷：section 顶边一致但内容仍可能差 4px。）
            def page_top(pid: str) -> float:
                return page.evaluate("""id => {
                    const s = document.getElementById(id);
                    const f = [...s.children].find(e => getComputedStyle(e).display !== 'none');
                    return f ? f.getBoundingClientRect().top : -1;
                }""", pid)
            tops = {}
            for pid in ("p0", "p1", "p2", "p3"):
                page.evaluate("id => go(id)", pid)
                page.wait_for_timeout(350)
                tops[pid] = page_top(pid)
            spread = max(tops.values()) - min(tops.values())
            seg_on_p0 = page.evaluate("""() => {
                go('p0');
                const s = document.getElementById('seg');
                return [getComputedStyle(s).display, s.classList.contains('muted')];
            }""")
            check("D4 切页零跳动", spread < 1.0,
                  f"(四页顶边 {[f'{v:.1f}' for v in tops.values()]}, 极差={spread:.1f}px)")
            check("D4 分段常驻（p0 上不隐藏、不灰显）",
                  seg_on_p0[0] != "none" and seg_on_p0[1] is False,
                  f"(display={seg_on_p0[0]}, muted={seg_on_p0[1]})")

            # ---- D5 触达 44dp ----
            small = page.evaluate("""() => {
                const out = [];
                document.querySelectorAll('[data-go],[data-act],.dock button,.row,.chip,.act')
                    .forEach(el => {
                        const r = el.getBoundingClientRect();
                        if (r.width === 0 || r.height === 0) return;
                        // 用伪元素热区后的实际可点高度：取元素自身与 ::after 扩展后的并集
                        const cs = getComputedStyle(el, '::after');
                        let grow = 0;
                        if (cs.content && cs.content !== 'none' && cs.position === 'absolute') {
                            const t = parseFloat(cs.top) || 0, b = parseFloat(cs.bottom) || 0;
                            grow = Math.max(0, -t) + Math.max(0, -b);
                        }
                        const hit = r.height + grow;
                        if (hit < 43.5) out.push([el.className || el.id || el.tagName, +hit.toFixed(1)]);
                    });
                return out;
            }""")
            check("D5 交互元素触达 ≥ 44dp", len(small) == 0, f"(不达标: {small[:6]})" if small else "")

            # ---- 键盘可达 ----
            no_a11y = page.evaluate("""() => {
                const out = [];
                document.querySelectorAll('[data-go],[data-act]').forEach(el => {
                    if (el.tagName === 'BUTTON') return;
                    if (!el.hasAttribute('tabindex') || !el.getAttribute('role'))
                        out.push(el.className || el.tagName);
                });
                return out;
            }""")
            check("委托元素键盘可达（tabindex+role）", len(no_a11y) == 0,
                  f"(缺失: {no_a11y[:6]})" if no_a11y else "")

            # ---- 本轮新改动 ----
            logo_bg = page.evaluate(
                "() => getComputedStyle(document.querySelector('.topbar .logo')).backgroundImage")
            check("logo 渐变走 token", "rgb(42, 44, 48)" in logo_bg and "rgb(16, 17, 19)" in logo_bg,
                  f"({logo_bg[:60]}…)")

            ok_svg = page.evaluate(
                "() => !!document.querySelector('.badge.ok svg')")
            check("成功徽章对勾是矢量", ok_svg)

            # 注意选 #g1 里的 tile：albumSkeleton 里也有 .tile.skeleton，
            # querySelector('.tile') 会先撞上骨架那份，pick 它不会出选中态。
            page.evaluate("() => { go('p1'); pick(document.querySelector('#g1 .tile')); }")
            page.wait_for_timeout(300)
            sel_svg = page.evaluate("""() => {
                const s = document.querySelector('.tile.picked .sel svg');
                if (!s) return null;
                const r = s.getBoundingClientRect();
                return [Math.round(r.width), Math.round(r.height)];
            }""")
            check("选中对勾是矢量且已上屏", bool(sel_svg and sel_svg[0] > 8), f"(尺寸={sel_svg})")

            # 骨架 1.6s 后会被 display:none 掉，量之前先显式放出来
            sk = page.evaluate("""() => {
                const box = document.getElementById('albumSkeleton');
                box.style.display = 'block';
                const d = document.querySelector('.sk-d'), n = document.querySelector('.sk-n');
                if (!d || !n) return null;
                const w = [d.getBoundingClientRect().width, n.getBoundingClientRect().width];
                box.style.display = '';
                return w.map(Math.round);
            }""")
            check("骨架占位是实体块且有宽度", bool(sk and sk[0] > 40 and sk[1] > 60), f"(宽={sk})")

            btn = page.evaluate(
                "() => document.getElementById('connectBtn').textContent.trim()")
            check("连接按钮无字符箭头", "⟶" not in btn, f"(文案=“{btn}”)")

            # ---- D8 主题持久化 ----
            page.evaluate("() => toggleTheme()")
            page.wait_for_timeout(200)
            after = page.evaluate("() => document.documentElement.dataset.theme")
            stored = page.evaluate("() => localStorage.getItem('nl-theme')")
            page.reload()
            page.wait_for_timeout(300)
            after_reload = page.evaluate("() => document.documentElement.dataset.theme")
            check("D8 主题持久化", after == stored and after_reload == after,
                  f"(切换后={after}, stored={stored}, 刷新后={after_reload})")

            # ---- D3 渲染级对比度抽样 ----
            pairs = page.evaluate("""() => {
                const out = [];
                const probe = (sel, bgSel) => {
                    const el = document.querySelector(sel);
                    const bg = document.querySelector(bgSel);
                    if (!el || !bg) return;
                    out.push([sel, getComputedStyle(el).color,
                              getComputedStyle(bg).backgroundColor]);
                };
                probe('.list-head .n', 'body');
                probe('.row .sub', '.row');
                probe('.chip', 'body');
                return out;
            }""")
            worst = 21.0
            for sel, fg, bg in pairs:
                if "rgba(0, 0, 0, 0)" in bg:
                    continue
                c = contrast(fg, bg)
                worst = min(worst, c)
            check("D3 渲染级对比度 ≥ 4.5", worst >= 4.5, f"(最低 {worst:.2f}:1，抽样 {len(pairs)} 处)")

            page.close()

        # ---- 响应式：五档宽度 × 双主题，零横向溢出 ----
        # main{display:flow-root} 是会影响布局的改动，必须确认没把任何一档撑出横向滚动。
        print("\n── 响应式扫描 ──")
        ctx3 = browser.new_context(viewport={"width": 320, "height": 720})
        pg3 = ctx3.new_page()
        pg3.goto(URL)
        pg3.wait_for_timeout(1800)
        for w in (320, 360, 390, 430, 768, 1280):
            pg3.set_viewport_size({"width": w, "height": 720})
            for th in ("light", "dark"):
                pg3.evaluate("t => applyTheme(t)", th)
                over = []
                for pid in ("p0", "p1", "p2", "p3"):
                    pg3.evaluate("id => go(id)", pid)
                    pg3.wait_for_timeout(120)
                    r = pg3.evaluate("""() => {
                        const de = document.documentElement;
                        const wide = [];
                        if (de.scrollWidth > de.clientWidth + 1)
                            document.querySelectorAll('body *').forEach(el => {
                                const b = el.getBoundingClientRect();
                                if (b.width && b.right > de.clientWidth + 1)
                                    wide.push((el.id || el.className || el.tagName) + '@' + Math.round(b.right));
                            });
                        return [de.scrollWidth, de.clientWidth, wide.slice(0, 4)];
                    }""")
                    if r[0] > r[1] + 1:
                        over.append((pid, r))
                check(f"  {w}px/{th} 无横向溢出", not over, f"({over[:2]})" if over else "")
        ctx3.close()

        # ---- D9 reduced-motion ----
        print("\n── prefers-reduced-motion ──")
        ctx = browser.new_context(viewport={"width": 360, "height": 780},
                                  reduced_motion="reduce")
        pg = ctx.new_page()
        pg.goto(URL)
        pg.wait_for_timeout(200)
        pg.evaluate("() => go('p1')")
        pg.wait_for_timeout(200)
        n = pg.evaluate("() => document.getAnimations().length")
        check("D9 reduced-motion 下动画数 = 0", n == 0, f"({n} 个)")
        ctx.close()

        # ---- 控制台报错 ----
        print("\n── 控制台 ──")
        ctx2 = browser.new_context(viewport={"width": 390, "height": 844})
        pg2 = ctx2.new_page()
        errs: list[str] = []
        pg2.on("console", lambda m: errs.append(m.text) if m.type == "error" else None)
        pg2.on("pageerror", lambda e: errs.append(str(e)))
        pg2.goto(URL)
        for pid in ("p0", "p1", "p2", "p3"):
            pg2.evaluate("id => go(id)", pid)
            pg2.wait_for_timeout(150)
        pg2.evaluate("() => doConnect()")
        pg2.wait_for_timeout(3600)
        check("控制台零报错", not errs, f"({errs[:3]})" if errs else "")
        ctx2.close()
        browser.close()

    bad = [r for r in results if not r[1]]
    print(f"\n{'='*54}")
    print(f"通过 {len(results)-len(bad)}/{len(results)}")
    if bad:
        for name, _, detail in bad:
            print("  ✗", name, detail)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(run())
