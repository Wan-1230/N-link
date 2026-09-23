import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const ROOT = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
const BASE = process.argv[2] ?? 'http://localhost:4173/';
const OUT = path.join(ROOT, '.artifacts');

const VIEWPORTS = [
  ['desktop', 1440, 900],
  ['laptop', 1280, 800],
  ['tablet', 820, 1180],
  ['mobile', 390, 844],
];

const snapshot = JSON.parse(fs.readFileSync(path.join(ROOT, 'src/data/releases.json'), 'utf8'));
const expectVersion = snapshot.latestTag;

// 与 src/i18n/zh.ts 的 trust.metrics 对齐；改字典必须同时改这里（默认语言为 zh）
const EXPECTED_NUMS = ['<3s', '<200ms', '46档', '40个'];

const ASSETS = path.join(ROOT, 'dist/assets');
const jsGzipBytes = fs.existsSync(ASSETS)
  ? fs
      .readdirSync(ASSETS)
      .filter((f) => f.endsWith('.js'))
      .reduce((sum, f) => sum + zlib.gzipSync(fs.readFileSync(path.join(ASSETS, f))).length, 0)
  : 0;

fs.mkdirSync(OUT, { recursive: true });
const browser = await chromium.launch();
let failures = 0;

const check = (label, ok) => {
  if (!ok) failures++;
  console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${label}`);
};

for (const [name, width, height] of VIEWPORTS) {
  const page = await browser.newPage({ viewport: { width, height } });
  const noise = [];
  console.log(`\n### ${name} ${width}x${height}`);
  page.on('console', (m) => {
    // 别截太短：曾经把 "loopback" 截掉，导致环境噪音过滤器匹配不上、白绕一轮
    if (m.type() === 'error' || m.type() === 'warning') noise.push(`[${m.type()}] ${m.text().slice(0, 400)}`);
  });
  page.on('pageerror', (e) => noise.push(`[pageerror] ${String(e).slice(0, 220)}`));

  // 必须在导航前挂好观察器，否则 LCP 已经过去了
  await page.addInitScript(() => {
    window.__vitals = { lcp: 0, cls: 0 };
    new PerformanceObserver((l) => {
      for (const e of l.getEntries()) if (e.startTime > window.__vitals.lcp) window.__vitals.lcp = e.startTime;
    }).observe({ type: 'largest-contentful-paint', buffered: true });
    new PerformanceObserver((l) => {
      for (const e of l.getEntries()) if (!e.hadRecentInput) window.__vitals.cls += e.value;
    }).observe({ type: 'layout-shift', buffered: true });
  });

  await page.goto(BASE, { waitUntil: 'networkidle' });
  await page.waitForTimeout(2000);
  await page.screenshot({ path: path.join(OUT, `hero-${name}.png`) });

  // vitals 必须在任何交互之前采：后面会滚页、还会切语言，切语言改变文本长度本身
  // 就产生 layout shift，事后读到的 CLS 是探针自己制造的（实测能到 1.37）
  const vitals = await page.evaluate(() => ({ lcp: Math.round(window.__vitals.lcp), cls: Number(window.__vitals.cls.toFixed(4)) }));

  // 逐段滚到视口内再截图：入场动画未触发的段落直接截会得到空白，那不是缺陷是假象。
  // id 必须写死而不能从 `main > section` 枚举——GSAP 的 pin-spacer 会把被钉住的段
  // 挪出 main 的直接子级，那样正好漏掉最该看的 Pipeline 段。
  const SECTIONS = ['top', 'trust', 'pipeline', 'features', 'why', 'how', 'changelog', 'roadmap', 'tech', 'download'];
  for (const id of SECTIONS) {
    const found = await page.evaluate((anchor) => !!document.getElementById(anchor), id);
    if (!found) {
      console.log(`  !! 段 ${id} 不存在`);
      failures++;
      continue;
    }
    await page.evaluate((anchor) => document.getElementById(anchor)?.scrollIntoView({ block: 'start' }), id);
    await page.waitForTimeout(900);
    await page.screenshot({ path: path.join(OUT, `section-${id}-${name}.png`) });
  }
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await page.waitForTimeout(1200);
  await page.screenshot({ path: path.join(OUT, `page-end-${name}.png`) });
  await page.evaluate(() => window.scrollTo(0, 0));

  // 切到英文再扫一遍：字典形状由 TS 保证，但 fill() 的占位符替换只有跑起来才知道对不对
  await page.getByRole('button', { name: /Switch to English|切换为中文/ }).click();
  await page.waitForTimeout(1200);
  const en = await page.evaluate(() => {
    const text = document.body.innerText;
    return {
      lang: document.documentElement.lang,
      h1: document.querySelector('h1')?.innerText.replace(/\s+/g, ' ').trim() ?? '',
      leaks: ['undefined', '[object', '{version}', '{time}', '{n}'].filter((w) => text.includes(w)),
    };
  });
  await page.screenshot({ path: path.join(OUT, `hero-en-${name}.png`) });
  check('切英文后 <html lang> 同步', en.lang === 'en');
  check('标题已翻译', /never drop|nikon connection/i.test(en.h1));
  check('无未替换占位符', en.leaks.length === 0);
  if (en.leaks.length) console.log(`   leaks=${JSON.stringify(en.leaks)}`);
  await page.getByRole('button', { name: /Switch to English|切换为中文/ }).click();
  await page.waitForTimeout(400);

  // CountUp 要跑 1.8s，等太短会抓到半程值（<2s、140ms 这种）。固定等待在小屏上是竞态，
  // 所以轮询到终值出现为止；真写错了这里依然会超时失败。
  await page.evaluate(() => document.getElementById('trust')?.scrollIntoView({ block: 'center' }));
  const settled = await page
    .waitForFunction(
      (expected) =>
        [...document.querySelectorAll('.tr__num')].map((n) => n.textContent.replace(/\s+/g, '')).join('|') === expected.join('|'),
      EXPECTED_NUMS,
      { timeout: 6000 }
    )
    .then(
      () => true,
      () => false
    );
  await page.screenshot({ path: path.join(OUT, `section-trust-settled-${name}.png`) });
  const nums = await page.evaluate(() => [...document.querySelectorAll('.tr__num')].map((n) => n.textContent.replace(/\s+/g, '')));
  await page.evaluate(() => window.scrollTo(0, 0));

  const cl = await page.evaluate(() => {
    const items = [...document.querySelectorAll('.cl__item')];
    return {
      count: items.length,
      firstVer: items[0]?.querySelector('.cl__ver')?.textContent ?? '',
      firstFlagsLatest: !!items[0]?.querySelector('.cl__flag'),
      bulletCount: items[0]?.querySelectorAll('.cl__bullets li').length ?? 0,
      hasMdStars: items.some((li) => /\*\*/.test(li.textContent)),
      apkLinks: document.querySelectorAll('.cl__link--apk').length,
    };
  });

  const probe = await page.evaluate(() => {
    const badge = document.querySelector('.hero__badge');
    const canvas = document.querySelector('.hero__bg canvas');
    const skip = document.querySelector('.skip-link');
    const r = skip?.getBoundingClientRect();
    return {
      badge: badge?.innerText.replace(/\s+/g, ' ').trim() ?? '',
      canvas: canvas ? `${canvas.width}x${canvas.height}` : 'MISSING',
      bodyBg: getComputedStyle(document.body).backgroundColor,
      skipTop: r ? Math.round(r.top) : null,
      h1: document.querySelector('h1')?.innerText.replace(/\s+/g, ' ').trim() ?? '',
      docH: document.documentElement.scrollHeight,
      overflowX: document.documentElement.scrollWidth > document.documentElement.clientWidth,
    };
  });

  const nav = await page.evaluate(() => {
    const ids = ['trust', 'pipeline', 'features', 'why', 'how', 'changelog', 'roadmap', 'tech', 'download'];
    const missing = ids.filter((id) => !document.getElementById(id));
    const text = document.body.innerText;
    return {
      missing,
      sections: document.querySelectorAll('main > section').length,
      h2: document.querySelectorAll('h2').length,
      placeholders: ['TODO', 'TBD', 'Lorem', '占位', 'FIXME'].filter((w) => text.includes(w)),
      emptyLinks: [...document.querySelectorAll('a[href="#"], a:not([href])')].length,
    };
  });

  // 预算在 node 侧对 dist 逐文件 gzip 求和。不用浏览器的 encodedBodySize：
  // vite preview 默认不压缩，那样量出来的会是未压缩字节，等于没量。
  // 口径取「全部 JS chunk 之和」，比首屏更严。
  const jsBytes = jsGzipBytes;

  const a11y = await page.evaluate(() => {
    const controls = [...document.querySelectorAll('a,button')];
    const noName = controls.filter(
      (el) => !(el.textContent ?? '').trim() && !el.getAttribute('aria-label') && !el.querySelector('img[alt]:not([alt=""])')
    ).length;
    const noAlt = [...document.querySelectorAll('img')].filter((i) => i.getAttribute('alt') === null).length;
    // WCAG 2.5.8 对「句中行内链接」有豁免：给它们撑到 24px 只会毁掉排版。
    // 因此只要求非 inline 的控件达标，纯行内链接跳过。
    const smallEls = controls
      .filter((el) => getComputedStyle(el).display !== 'inline')
      .filter((el) => {
        const r = el.getBoundingClientRect();
        return r.width > 0 && r.height > 0 && (r.height < 24 || r.width < 24);
      })
      .map((el) => {
        const r = el.getBoundingClientRect();
        return `${el.tagName.toLowerCase()}.${el.className || '-'} ${Math.round(r.width)}x${Math.round(r.height)}`;
      });
    const tiny = smallEls.length;
    const levels = [...document.querySelectorAll('h1,h2,h3,h4')].map((h) => Number(h.tagName[1]));
    let jumps = 0;
    for (let i = 1; i < levels.length; i++) if (levels[i] - levels[i - 1] > 1) jumps++;
    const v = window.__vitals ?? { lcp: 0, cls: 0 };
    return { noName, noAlt, tiny, smallEls, jumps, lcp: Math.round(v.lcp), cls: Number(v.cls.toFixed(4)) };
  });

  check(`JS 合计 ${(jsBytes / 1024).toFixed(1)}KB gzip ≤ 200KB`, jsBytes <= 200 * 1024);
  check('控件都有可访问名', a11y.noName === 0);
  check('图片都有 alt', a11y.noAlt === 0);
  check('触控目标 ≥24px（WCAG 2.5.8）', a11y.tiny === 0);
  if (a11y.tiny) console.log('   tiny=' + JSON.stringify(a11y.smallEls));
  check('标题层级不跳级', a11y.jumps === 0);
  check(`CLS ${vitals.cls} < 0.1`, vitals.cls < 0.1);
  check(`LCP ${vitals.lcp}ms < 2500ms`, vitals.lcp > 0 && vitals.lcp < 2500);
  check('指标终值与字典一致（非动画半程值）', settled);
  check(`badge 含快照版本 ${expectVersion}`, probe.badge.includes(expectVersion.replace(/^v/, '')));
  check('h1 非空', probe.h1.length > 4);
  check('无横向溢出', !probe.overflowX);
  check('skip-link 藏在屏外', probe.skipTop !== null && probe.skipTop < 0);
  check('Aurora canvas 存在', probe.canvas !== 'MISSING');
  check(`日志条目数 == 快照 ${snapshot.releases.length}`, cl.count === snapshot.releases.length);
  check(`最新条目为 ${expectVersion}`, cl.firstVer === expectVersion);
  check('最新条目带「最新」标', cl.firstFlagsLatest === true);
  check('正文无未转换的 ** 粗体标记', cl.hasMdStars === false);
  check('存在 APK 直链', cl.apkLinks >= 1);
  check('9 个内容段锚点齐全', nav.missing.length === 0);
  check('无占位文案', nav.placeholders.length === 0);
  check('无空链接', nav.emptyLinks === 0);
  console.log(`  sections=${nav.sections} h2=${nav.h2} metrics=${JSON.stringify(nums)}`);
  console.log(`  badge="${probe.badge}" canvas=${probe.canvas} bg=${probe.bodyBg} docH=${probe.docH}`);
  console.log(`  changelog: ${cl.count} 条 / 首条 ${cl.firstVer} 要点 ${cl.bulletCount} / APK 链 ${cl.apkLinks}`);

  // 两类噪音不是应用缺陷：
  //  a) Function 未部署时 /functions/v1/app 的 404/409，与 headless GL 的驱动性能告警
  //  b) 本机把 api.github.com 解析到回环地址，Chrome 以 PNA 规则拦下运行时补拉
  //     （"access the `loopback` address space"）。这是这台机器的网络环境，不能据此
  //     判定真实访客也会失败，也不能据此声称线上已验证 —— 见 spec §16。
  const ENV_NOISE = ['/functions/v1/app', 'GL Driver Message', 'api.github.com', 'net::ERR_FAILED'];
  const appNoise = noise.filter((n) => !ENV_NOISE.some((w) => n.includes(w)));
  if (appNoise.length) {
    failures++;
    console.log('  CONSOLE:\n   ' + appNoise.join('\n   '));
  } else {
    console.log(`  console: ${noise.length ? '仅环境噪音已排除（未部署函数 / headless GL / 本机 PNA 拦截）' : 'clean'}`);
  }

  await page.close();
}

// 社交抓取要绝对 URL，相对 og:image 等于没有；这几项查的是产物 HTML 本身
{
  const html = fs.existsSync(path.join(ROOT, 'dist/index.html'))
    ? fs.readFileSync(path.join(ROOT, 'dist/index.html'), 'utf8')
    : '';
  const ogImg = html.match(/property="og:image" content="([^"]+)"/)?.[1] ?? '';
  const canonical = html.match(/rel="canonical" href="([^"]+)"/)?.[1] ?? '';
  const ld = html.match(/application\/ld\+json">([^<]+)</)?.[1] ?? '';
  console.log('\n### SEO 产物');
  check('canonical 为绝对地址', /^https:\/\/.+\/$/.test(canonical));
  check('og:image 为绝对地址', /^https:\/\/.+\/og\.png$/.test(ogImg));
  check('无残留相对 og:image', !html.includes('content="/og.png"'));
  check('JSON-LD 带 softwareVersion', /"softwareVersion":"v\d+\.\d+\.\d+"/.test(ld));
  check('Release.isReleaseOf 指向本站 @id', ld.includes('#app') && !ld.includes('https://schema.org/N-Link'));
  check('sitemap.xml 已生成', fs.existsSync(path.join(ROOT, 'dist/sitemap.xml')));
  check('robots.txt 指向 sitemap', (fs.readFileSync(path.join(ROOT, 'dist/robots.txt'), 'utf8') || '').includes('Sitemap: https://'));
}

// reduced-motion 对照：动效关掉之后必须仍然可读，且不该偷偷留下 canvas 或隐藏文字
{
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce' });
  const noise = [];
  page.on('pageerror', (e) => noise.push(String(e).slice(0, 200)));
  await page.goto(BASE, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1200);
  await page.screenshot({ path: path.join(OUT, 'hero-reduced-motion.png') });

  const rm = await page.evaluate(() => {
    const h1 = document.querySelector('h1');
    const chars = [...document.querySelectorAll('.hero__line span, .hero__line')];
    return {
      canvas: !!document.querySelector('.hero__bg canvas'),
      staticBg: !!document.querySelector('.hero__bg-static'),
      h1Visible: !!h1 && getComputedStyle(h1).visibility !== 'hidden' && h1.innerText.trim().length > 4,
      hiddenChars: chars.filter((c) => getComputedStyle(c).opacity === '0').length,
      shineAnimated: getComputedStyle(document.querySelector('.hero__badge span') ?? document.body).animationName,
    };
  });
  console.log('\n### reduced-motion 1440x900');
  check('无 WebGL canvas', rm.canvas === false);
  check('静态渐变背景顶上', rm.staticBg === true);
  check('标题立即可读', rm.h1Visible === true);
  check('没有停在 opacity:0 的文字', rm.hiddenChars === 0);
  if (noise.length) {
    failures++;
    console.log('  pageerror:\n   ' + noise.join('\n   '));
  }
  await page.close();
}

await browser.close();
console.log(`\n${failures === 0 ? 'PASS' : `FAIL (${failures})`} — 截图见 ${path.relative(process.cwd(), OUT)}`);
process.exit(failures === 0 ? 0 : 1);
