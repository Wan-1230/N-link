import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const ROOT = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
const BASE = (process.argv[2] ?? 'http://localhost:4188').replace(/\/$/, '');
const OUT = path.join(ROOT, '.artifacts');

const VIEWPORTS = [
  ['desktop', 1440, 900],
  ['laptop', 1280, 800],
  ['tablet', 820, 1180],
  ['mobile', 390, 844],
];

const HOME_SECTIONS = ['trust', 'pipeline', 'features', 'why', 'how', 'roadmap', 'tech'];

const snapshot = JSON.parse(fs.readFileSync(path.join(ROOT, 'src/data/releases.json'), 'utf8'));
const expectVersion = snapshot.latestTag;

// 与 src/i18n/zh.ts 的 trust.metrics 对齐；改字典必须同时改这里（默认语言为 zh）
const EXPECTED_NUMS = ['<3s', '<200ms', '46档', '40个'];

// 体积在 node 侧对 dist 逐文件 gzip 求和：vite preview 默认不压缩，
// 用浏览器 encodedBodySize 量到的是未压缩字节，等于没量。
const ASSETS = path.join(ROOT, 'dist/assets');
const jsBytes = fs.existsSync(ASSETS)
  ? fs
      .readdirSync(ASSETS)
      .filter((f) => f.endsWith('.js'))
      .reduce((sum, f) => sum + zlib.gzipSync(fs.readFileSync(path.join(ASSETS, f))).length, 0)
  : 0;

let failures = 0;
const check = (label, ok) => {
  if (!ok) failures++;
  console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${label}`);
};

const browser = await chromium.launch();
fs.mkdirSync(OUT, { recursive: true });

for (const [name, width, height] of VIEWPORTS) {
  const ctx = await browser.newContext({ viewport: { width, height } });
  const page = await ctx.newPage();
  const noise = [];
  const badUrls = [];
  page.on('console', (m) => {
    // 别截太短：曾经把 "loopback" 截掉，导致环境噪音过滤器匹配不上
    if (m.type() === 'error' || m.type() === 'warning') noise.push(`[${m.type()}] ${m.text().slice(0, 400)}`);
  });
  page.on('pageerror', (e) => noise.push(`[pageerror] ${String(e).slice(0, 260)}`));
  // Chrome 对失败请求会另打一条不含 URL 的 "Failed to load resource"，只按文本匹配
  // 会漏，所以把真实失败的 URL 单独记下来用于关联判断
  page.on('response', (r) => {
    if (r.status() >= 400) badUrls.push(r.url());
  });
  page.on('requestfailed', (r) => badUrls.push(r.url()));

  await page.addInitScript(() => {
    window.__vitals = { lcp: 0, cls: 0 };
    new PerformanceObserver((l) => {
      for (const e of l.getEntries()) if (e.startTime > window.__vitals.lcp) window.__vitals.lcp = e.startTime;
    }).observe({ type: 'largest-contentful-paint', buffered: true });
    new PerformanceObserver((l) => {
      for (const e of l.getEntries()) if (!e.hadRecentInput) window.__vitals.cls += e.value;
    }).observe({ type: 'layout-shift', buffered: true });
  });

  console.log(`\n### ${name} ${width}x${height}`);
  check(`JS 合计 ${(jsBytes / 1024).toFixed(1)}KB gzip ≤ 200KB`, jsBytes <= 200 * 1024);

  /* ---------- 首页 ---------- */
  await page.goto(`${BASE}/`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);

  const vitals = await page.evaluate(() => ({ lcp: Math.round(window.__vitals.lcp), cls: Number(window.__vitals.cls.toFixed(4)) }));
  await page.screenshot({ path: path.join(OUT, `home-hero-${name}.png`) });

  const home = await page.evaluate((ids) => {
    const text = document.body.innerText;
    const controls = [...document.querySelectorAll('a,button')];
    const levels = [...document.querySelectorAll('h1,h2,h3,h4')].map((h) => Number(h.tagName[1]));
    let jumps = 0;
    for (let i = 1; i < levels.length; i++) if (levels[i] - levels[i - 1] > 1) jumps++;
    return {
      missing: ids.filter((id) => !document.getElementById(id)),
      hasChangelog: !!document.getElementById('changelog'),
      h1: document.querySelector('h1')?.innerText.replace(/\s+/g, ' ').trim() ?? '',
      badge: document.querySelector('.hero__badge')?.innerText.replace(/\s+/g, ' ').trim() ?? '',
      overflowX: document.documentElement.scrollWidth > document.documentElement.clientWidth,
      skipTop: Math.round(document.querySelector('.skip-link')?.getBoundingClientRect().top ?? -999),
      canvas: !!document.querySelector('.hero__bg canvas'),
      noName: controls.filter(
        (el) => !(el.textContent ?? '').trim() && !el.getAttribute('aria-label') && !el.querySelector('img[alt]:not([alt=""])')
      ).length,
      noAlt: [...document.querySelectorAll('img')].filter((i) => i.getAttribute('alt') === null).length,
      tiny: controls
        .filter((el) => getComputedStyle(el).display !== 'inline')
        .filter((el) => {
          const r = el.getBoundingClientRect();
          return r.width > 0 && r.height > 0 && (r.height < 24 || r.width < 24);
        }).length,
      jumps,
      placeholders: ['TODO', 'TBD', 'Lorem', '占位', 'FIXME'].filter((w) => text.includes(w)),
      emptyLinks: [...document.querySelectorAll('a[href="#"], a:not([href])')].length,
    };
  }, HOME_SECTIONS);

  check('首页 7 段锚点齐全', home.missing.length === 0);
  check('首页不含更新日志段（已移到 /releases）', home.hasChangelog === false);
  check(`badge 含快照版本 ${expectVersion}`, home.badge.includes(expectVersion.replace(/^v/, '')));
  check('h1 非空', home.h1.length > 4);
  check('无横向溢出', !home.overflowX);
  check('skip-link 藏在屏外', home.skipTop < 0);
  check('Aurora canvas 存在', home.canvas);
  check('控件都有可访问名', home.noName === 0);
  check('图片都有 alt', home.noAlt === 0);
  check('触控目标 ≥24px（行内链接按 WCAG 2.5.8 豁免）', home.tiny === 0);
  check('标题层级不跳级', home.jumps === 0);
  check('无占位文案', home.placeholders.length === 0);
  check('无空链接', home.emptyLinks === 0);
  check(`CLS ${vitals.cls} < 0.1`, vitals.cls < 0.1);
  check(`LCP ${vitals.lcp}ms < 2500ms`, vitals.lcp > 0 && vitals.lcp < 2500);

  /* ---------- 产品 mega-menu：hover 展开 + 点击带锚点跳转 ---------- */
  const mega = page.locator('#nav-product-menu');
  if (width > 960) {
    await page.getByRole('button', { name: /产品|Product/ }).hover();
    await page.waitForTimeout(450);
    const leaves = await mega.locator('.nav__leaf').count();
    const visible = await mega.evaluate((el) => getComputedStyle(el).visibility);
    check('hover 展开 mega-menu', visible === 'visible');
    check('菜单含 6 个带说明的条目', leaves === 6);
    await page.screenshot({ path: path.join(OUT, `nav-mega-${name}.png`) });

    await mega.locator('.nav__leaf').nth(3).click();
    await page.waitForTimeout(1200);
    const landed = await page.evaluate(() => ({
      path: location.pathname,
      top: Math.round(document.getElementById('how')?.getBoundingClientRect().top ?? -1),
    }));
    check('点菜单项跳到首页对应段', landed.path === '/' && Math.abs(landed.top) < 200);
  }

  /* ---------- 数字滚动终值 ---------- */
  await page.evaluate(() => window.scrollTo(0, 0));
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
  check('指标终值与字典一致（非动画半程值）', settled);

  /* ---------- /releases ---------- */
  await page.goto(`${BASE}/releases`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  await page.screenshot({ path: path.join(OUT, `releases-top-${name}.png`) });

  const rel = await page.evaluate(() => ({
    path: location.pathname,
    title: document.title,
    canonical: document.head.querySelector('link[rel="canonical"]')?.href ?? '',
    h1: document.querySelector('h1')?.innerText.trim() ?? '',
    ver: document.querySelector('.lr__ver')?.innerText.trim() ?? '',
    apk: [...document.querySelectorAll('.lr__ch')].length,
    items: document.querySelectorAll('.cl__item').length,
    latestFlag: !!document.querySelector('.cl__item--latest .cl__flag'),
    hasHero: !!document.querySelector('.hero'),
    hasMdStars: [...document.querySelectorAll('.cl__item')].some((li) => /\*\*/.test(li.textContent)),
    apkLinks: document.querySelectorAll('.cl__link--apk').length,
  }));

  check('直接访问 /releases 不被 404（SPA fallback）', rel.path.startsWith('/releases'));
  // 断言渲染后的 DOM 而不是本地 dist：宿主按精确路径给静态文件，
  // /releases 回退到根 index.html，只有运行时同步才能让这条入口带上自己的元数据
  check('/releases 渲染后 title 是自己的', rel.title.includes('下载与更新日志'));
  check('/releases 渲染后 canonical 自指', rel.canonical.endsWith('/releases/'));
  check(`最新版卡置顶且为 ${expectVersion}`, rel.ver.replace(/\s/g, '').includes(expectVersion.replace(/^v/, '')));
  check('置顶卡含 4 个下载渠道', rel.apk === 4);
  check(`日志条目数 == 快照 ${snapshot.releases.length}`, rel.items === snapshot.releases.length);
  check('首页内容未混入 /releases', rel.hasHero === false);
  check('正文无未转换的 ** 粗体标记', rel.hasMdStars === false);
  check('存在 APK 直链', rel.apkLinks >= 1);
  void rel.h1;
  void rel.latestFlag;

  /* ---------- 语言切换 ---------- */
  await page.goto(`${BASE}/`, { waitUntil: 'networkidle' });
  await page.evaluate(() => window.scrollTo(0, 0));
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
  await page.screenshot({ path: path.join(OUT, `home-en-${name}.png`) });
  check('切英文后 <html lang> 同步', en.lang === 'en');
  check('标题已翻译', /never drop|nikon connection/i.test(en.h1));
  if (!/never drop|nikon connection/i.test(en.h1)) console.log(`   h1="${en.h1}"`);
  check('无未替换占位符', en.leaks.length === 0);
  if (en.leaks.length) console.log(`   leaks=${JSON.stringify(en.leaks)}`);

  const BENIGN = ['/functions/v1/app', 'api.github.com'];
  const onlyBenignFailures = badUrls.every((u) => BENIGN.some((b) => u.includes(b)));
  const appNoise = noise.filter((n) => {
    // 三类噪音各自判定，别混在一个白名单里靠 includes 猜：
    //  a) 不含 URL 的通用资源失败行 —— 只能靠真实失败 URL 列表关联判断
    //  b) headless GL 的驱动性能告警
    //  c) 带 URL 的行 —— 命中白名单 URL 才放行
    if (n.includes('Failed to load resource')) return !onlyBenignFailures;
    if (n.includes('GL Driver Message')) return false;
    return !BENIGN.some((w) => n.includes(w));
  });
  if (appNoise.length) {
    failures++;
    console.log('  CONSOLE:\n   ' + appNoise.join('\n   '));
    if (badUrls.length) console.log('   badUrls=' + JSON.stringify([...new Set(badUrls)].slice(0, 4)));
  } else {
    console.log(`  console: ${noise.length || badUrls.length ? `仅环境噪音（失败请求 ${badUrls.length} 个，均在白名单内）` : 'clean'}`);
  }

  await ctx.close();
}

/* ---------- SEO 产物：社交抓取要绝对 URL ---------- */
{
  const html = fs.readFileSync(path.join(ROOT, 'dist/index.html'), 'utf8');
  const ogImg = html.match(/property="og:image" content="([^"]+)"/)?.[1] ?? '';
  const canonical = html.match(/rel="canonical" href="([^"]+)"/)?.[1] ?? '';
  const ld = html.match(/application\/ld\+json">([^<]+)</)?.[1] ?? '';
  console.log('\n### SEO 产物');
  check('canonical 为绝对地址', /^https:\/\/.+\/$/.test(canonical));
  check('og:image 为绝对地址', /^https:\/\/.+\/og\.png$/.test(ogImg));
  check('JSON-LD 带 softwareVersion', /"softwareVersion":"v\d+\.\d+\.\d+"/.test(ld));
  check('Release.isReleaseOf 指向本站 @id', ld.includes('#app') && !ld.includes('https://schema.org/N-Link'));
  check('sitemap.xml 已生成', fs.existsSync(path.join(ROOT, 'dist/sitemap.xml')));
  check('sitemap 含两个路由', (fs.readFileSync(path.join(ROOT, 'dist/sitemap.xml'), 'utf8').match(/<loc>/g) ?? []).length === 2);

  // 两条路由必须有各自的 title 与自指 canonical，否则 /releases 会被判成重复页永不收录
  const rel2 = fs.existsSync(path.join(ROOT, 'dist/releases/index.html'))
    ? fs.readFileSync(path.join(ROOT, 'dist/releases/index.html'), 'utf8')
    : '';
  const homeTitle = html.match(/<title>([^<]*)<\/title>/)?.[1] ?? '';
  const relTitle = rel2.match(/<title>([^<]*)<\/title>/)?.[1] ?? '';
  const relCanon = rel2.match(/rel="canonical" href="([^"]+)"/)?.[1] ?? '';
  check('/releases 有独立产物 HTML', rel2.length > 0);
  check('两条路由 title 不同', relTitle.length > 0 && relTitle !== homeTitle);
  check('/releases canonical 自指', relCanon.endsWith('/releases/'));
  check('/releases 不指回首页', !html.match(/rel="canonical"[^/]*\/releases/) && relCanon !== canonical);
}

/* ---------- reduced-motion 对照 ---------- */
{
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce' });
  const noise = [];
  page.on('pageerror', (e) => noise.push(String(e).slice(0, 200)));
  await page.goto(`${BASE}/`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1200);
  await page.screenshot({ path: path.join(OUT, 'home-reduced-motion.png') });

  const rm = await page.evaluate(() => {
    const h1 = document.querySelector('h1');
    const chars = [...document.querySelectorAll('.hero__line span, .hero__line')];
    return {
      canvas: !!document.querySelector('.hero__bg canvas'),
      staticBg: !!document.querySelector('.hero__bg-static'),
      h1Visible: !!h1 && getComputedStyle(h1).visibility !== 'hidden' && h1.innerText.trim().length > 4,
      hiddenChars: chars.filter((c) => getComputedStyle(c).opacity === '0').length,
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
