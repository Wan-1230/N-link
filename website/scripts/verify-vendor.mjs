import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
const DIR = path.join(ROOT, 'src/components/reactbits');
const manifest = JSON.parse(fs.readFileSync(path.join(DIR, 'SOURCE.json'), 'utf8'));

const onDisk = new Set();
for (const comp of fs.readdirSync(DIR, { withFileTypes: true })) {
  if (!comp.isDirectory()) continue;
  for (const f of fs.readdirSync(path.join(DIR, comp.name))) {
    onDisk.add(`${comp.name}/${f}`);
  }
}

const inManifest = new Set();
let bad = 0;

for (const [name, files] of Object.entries(manifest.components)) {
  for (const entry of files) {
    const key = `${name}/${entry.file}`;
    inManifest.add(key);
    const p = path.join(DIR, name, entry.file);
    if (!fs.existsSync(p)) {
      console.log(`MISSING  ${key}  清单里有，磁盘上没有`);
      bad++;
      continue;
    }
    const sum = crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
    if (sum !== entry.sha256) {
      console.log(`DRIFTED  ${key}  本地内容与 pin 住的上游不一致`);
      bad++;
    }
  }
}

for (const key of onDisk) {
  if (!inManifest.has(key)) {
    console.log(`EXTRA    ${key}  磁盘上有，清单没记`);
    bad++;
  }
}

const declared = Object.values(manifest.components).reduce((n, l) => n + l.length, 0);
console.log(
  `\n${bad === 0 ? 'PASS' : 'FAIL'} — 清单 ${declared} 项 / 磁盘 ${onDisk.size} 项 / 不一致 ${bad}（commit ${manifest.commit?.slice(0, 7)}）`
);
process.exit(bad === 0 ? 0 : 1);
