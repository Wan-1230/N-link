import fs from 'node:fs';
import path from 'node:path';
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

fs.mkdirSync(OUT, { recursive: true });
const browser = await chromium.launch();
let failures = 0;

for (const [name, width, height] of VIEWPORTS) {
  const page = await browser.newPage({ viewport: { width, height } });
  const noise = [];
  page.on('console', (m) => {
    if (m.type() === 'error' || m.type() === 'warning') noise.push(`[${m.type()}] ${m.text().slice(0, 180)}`);
  });
  page.on('pageerror', (e) => noise.push(`[pageerror] ${String(e).slice(0, 220)}`));

  await page.goto(BASE, { waitUntil: 'networkidle' });
  await page.waitForTimeout(2000);
  await page.screenshot({ path: path.join(OUT, `hero-${name}.png`) });

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

  const check = (label, ok) => {
    if (!ok) failures++;
    console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${label}`);
  };

  console.log(`\n### ${name} ${width}x${height}`);
  check(`badge 含快照版本 ${expectVersion}`, probe.badge.includes(expectVersion.replace(/^v/, '')));
  check('h1 非空', probe.h1.length > 4);
  check('无横向溢出', !probe.overflowX);
  check('skip-link 藏在屏外', probe.skipTop !== null && probe.skipTop < 0);
  check('Aurora canvas 存在', probe.canvas !== 'MISSING');
  console.log(`  badge="${probe.badge}" canvas=${probe.canvas} bg=${probe.bodyBg} docH=${probe.docH}`);

  // /api/releases 未部署时的 404、以及 headless GL 的驱动性能告警，都不是应用层错误
  const appNoise = noise.filter((n) => !n.includes('/api/releases') && !n.includes('GL Driver Message'));
  if (appNoise.length) {
    failures++;
    console.log('  CONSOLE:\n   ' + appNoise.join('\n   '));
  } else {
    console.log(`  console: clean${noise.length ? '（已排除 /api/releases 404 与 headless GL 告警）' : ''}`);
  }

  await page.close();
}

await browser.close();
console.log(`\n${failures === 0 ? 'PASS' : `FAIL (${failures})`} — 截图见 ${path.relative(process.cwd(), OUT)}`);
process.exit(failures === 0 ? 0 : 1);
