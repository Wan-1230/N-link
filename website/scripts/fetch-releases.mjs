import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildFeed, emptyFeed } from '../src/lib/release-feed.ts';

const OWNER = 'Wan-1230';
const REPO = 'N-link';
const API = 'https://api.github.com';
const OUT = path.resolve(fileURLToPath(new URL('..', import.meta.url)), 'src/data/releases.json');

const headers = {
  accept: 'application/vnd.github+json',
  'user-agent': 'n-link-site-release-snapshot',
  ...(process.env.GITHUB_TOKEN ? { authorization: `Bearer ${process.env.GITHUB_TOKEN}` } : {}),
};

async function json(url) {
  const res = await fetch(url, { headers, signal: AbortSignal.timeout(20000) });
  if (!res.ok) throw new Error(`${url} -> HTTP ${res.status}`);
  return res.json();
}

function readExisting() {
  try {
    return JSON.parse(fs.readFileSync(OUT, 'utf8'));
  } catch {
    return null;
  }
}

function write(feed) {
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, JSON.stringify(feed, null, 2) + '\n');
}

try {
  // 两跳并发；repo 元信息只关系到 stars，失败不该拖垮日志
  const [releases, repo] = await Promise.all([
    json(`${API}/repos/${OWNER}/${REPO}/releases?per_page=100`),
    json(`${API}/repos/${OWNER}/${REPO}`).catch(() => null),
  ]);

  const feed = buildFeed(releases, typeof repo?.stargazers_count === 'number' ? repo.stargazers_count : null, 'snapshot');
  write(feed);
  console.log(`releases.json ← ${feed.releases.length} 条，latest ${feed.latestTag}，stars ${feed.stars ?? 'n/a'}`);
} catch (error) {
  const existing = readExisting();
  if (existing?.releases?.length) {
    write({ ...existing, source: 'snapshot', stale: true });
    console.warn(`[warn] 拉取失败，复用旧快照（${existing.releases.length} 条，latest ${existing.latestTag}）：${error.message}`);
  } else {
    write(emptyFeed());
    console.warn(`[warn] 拉取失败且无旧快照，写入空日志，站点降级为「暂无日志」态：${error.message}`);
  }
}
