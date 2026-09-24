import https from 'node:https';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const SHA = process.argv[2];
if (!/^[0-9a-f]{40}$/.test(SHA || '')) {
  console.error('usage: node fetch-reactbits.mjs <40-char-commit-sha>');
  process.exit(1);
}
// jsDelivr 的 gh 端点只认短 SHA（全 40 位返回 403/404），清单仍走 @main
const REF = SHA.slice(0, 7);

const CHOSEN = [
  'Animations/ClickSpark',
  'Animations/Magnet',
  'Animations/AnimatedContent',
  'Backgrounds/Aurora',
  'Components/SpotlightCard',
  'Components/TiltedCard',
  'Components/Stepper',
  'Components/Dock',
  'TextAnimations/SplitText',
  'TextAnimations/BlurText',
  'TextAnimations/CountUp',
  'TextAnimations/ShinyText',
  'TextAnimations/GradientText',
];

import { fileURLToPath } from 'node:url';

// 锚到脚本所在项目的上级（website/），不受调用时 cwd 影响
const OUT_ROOT = path.resolve(fileURLToPath(new URL('..', import.meta.url)), 'src/components/reactbits');

function get(url, tries = 4) {
  return new Promise((resolve) => {
    const attempt = (n) =>
      https
        .get(url, { timeout: 30000, headers: { 'user-agent': 'nl-link-site/1.0' } }, (res) => {
          let b = '';
          res.on('data', (c) => (b += c));
          res.on('end', () => {
            if (res.statusCode === 200) return resolve(b);
            if (n < tries && (res.statusCode === 403 || res.statusCode === 429 || res.statusCode >= 500)) {
              return setTimeout(() => attempt(n + 1), 1200 * n);
            }
            resolve(null);
          });
        })
        .on('error', () => (n < tries ? setTimeout(() => attempt(n + 1), 1200 * n) : resolve(null)));
    attempt(1);
  });
}

const sha256 = (s) => crypto.createHash('sha256').update(s).digest('hex');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const listBody = await get('https://data.jsdelivr.com/v1/packages/gh/DavidHDev/react-bits@main?structure=flat');
if (!listBody) {
  console.error('[fail] 取不到上游文件清单（网络失败）。本地已拷入的组件与 SOURCE.json 未改动。');
  process.exit(1);
}
const flat = JSON.parse(listBody);
const all = flat.files.map((f) => f.name).filter((f) => f.startsWith('/src/content/'));

const manifest = { upstream: 'https://github.com/DavidHDev/react-bits', commit: SHA, license: 'MIT + Commons Clause License Condition v1.0 (SPDX: NOASSERTION)', licenseFile: 'LICENSE.upstream.txt', note: 'React Bits 上游源码逐字拷入；仅本文件与 index.ts 为本地新增。许可允许作为网站/应用的一部分使用与分发（含商用），禁止单独出售、再授权或打包重分发组件本身，须保留版权声明与出处。', components: {} };
let written = 0;
const missing = [];

for (const dir of CHOSEN) {
  const files = all.filter((f) => f.startsWith(`/src/content/${dir}/`));
  if (!files.length) {
    missing.push(dir);
    continue;
  }
  const name = dir.split('/')[1];
  for (const f of files) {
    const base = path.basename(f);
    if (!/\.(jsx|js|css|ts|tsx)$/.test(base)) continue;
    const body = await get(`https://cdn.jsdelivr.net/gh/DavidHDev/react-bits@${REF}${f}`);
    if (body == null) {
      missing.push(`${dir}/${base}`);
      continue;
    }
    const outDir = path.join(OUT_ROOT, name);
    fs.mkdirSync(outDir, { recursive: true });
    fs.writeFileSync(path.join(outDir, base), body);
    (manifest.components[name] ??= []).push({ file: base, upstreamPath: f, bytes: body.length, sha256: sha256(body) });
    written++;
    process.stdout.write(`  ok ${name}/${base} (${body.length}B)\n`);
    await sleep(120);
  }
}

manifest.filesWritten = written;
fs.mkdirSync(OUT_ROOT, { recursive: true });
fs.writeFileSync(path.join(OUT_ROOT, 'SOURCE.json'), JSON.stringify(manifest, null, 2) + '\n');

console.log(`\nwrote ${written} files from react-bits@${SHA.slice(0, 7)}`);
if (missing.length) console.log('MISSING (spec names to correct):\n  ' + missing.join('\n  '));
