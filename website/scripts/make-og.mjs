import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const ROOT = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
const BASE = process.argv[2] ?? 'http://localhost:4188/';
const OUT = path.join(ROOT, 'public/og.png');

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1200, height: 630 }, deviceScaleFactor: 2 });
await page.goto(BASE, { waitUntil: 'networkidle' });
await page.waitForTimeout(2600);

// 只截 Hero 首屏，不整页；og.png 要的是能代表品牌的那一屏
await page.screenshot({ path: OUT, clip: { x: 0, y: 0, width: 1200, height: 630 } });
await browser.close();

console.log(`og.png ← ${BASE} (1200x630 @2x)`);
