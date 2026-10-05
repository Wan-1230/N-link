import { execFile } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import { buildFeed, emptyFeed } from '../src/lib/release-feed.ts';

const OWNER = 'Wan-1230';
const REPO = 'N-link';
const API = 'https://api.github.com';
const OUT = path.resolve(fileURLToPath(new URL('..', import.meta.url)), 'src/data/releases.json');

const run = promisify(execFile);

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

/**
 * 兜底通道：hosts 把 api.github.com 黑洞到 127.0.0.1 的机器上，Node 直连必然拿到
 * 本地服务返回的 403，快照会被永久标成 stale，前端因此显示「快照」而不是「实时」。
 * gh 有自己的解析与凭证，这里按短路径重试一次；没装 gh 的环境抛错，由外层照旧降级。
 */
async function viaGh(shortPath) {
  const { stdout } = await run('gh', ['api', shortPath], { maxBuffer: 32 * 1024 * 1024 });
  return JSON.parse(stdout);
}

async function fetchJson(url, shortPath) {
  try {
    return await json(url);
  } catch (error) {
    if (!shortPath) throw error;
    return viaGh(shortPath);
  }
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
    fetchJson(`${API}/repos/${OWNER}/${REPO}/releases?per_page=100`, `repos/${OWNER}/${REPO}/releases?per_page=100`),
    fetchJson(`${API}/repos/${OWNER}/${REPO}`, `repos/${OWNER}/${REPO}`).catch(() => null),
  ]);

  const feed = buildFeed(releases, typeof repo?.stargazers_count === 'number' ? repo.stargazers_count : null, 'snapshot');
  write(feed);
  console.log(`releases.json ← ${feed.releases.length} 条，latest ${feed.latestTag}，stars ${feed.stars ?? 'n/a'}`);
} catch (error) {
  const existing = readExisting();
  if (existing?.releases?.length) {
    write({ ...existing, source: 'snapshot', stale: true });
    console.warn(`[warn] 直连与 gh 两通道都失败，复用旧快照（${existing.releases.length} 条，latest ${existing.latestTag}）：${error.message}`);
  } else {
    write(emptyFeed());
    console.warn(`[warn] 两通道都失败且无旧快照，写入空日志，站点降级为「暂无日志」态：${error.message}`);
  }
}
