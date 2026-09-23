import { buildFeed, type GhRelease } from '../src/lib/release-feed.ts';

const API = 'https://api.github.com/repos/Wan-1230/N-link';
const TTL_MS = 600_000;
const UPSTREAM_TIMEOUT_MS = 8_000;

interface CacheSlot<T> {
  value: T | null;
  at: number;
}

const feedCache: CacheSlot<unknown> = { value: null, at: 0 };

const headers = () => ({
  accept: 'application/vnd.github+json',
  'user-agent': 'n-link-site-function',
  ...(process.env.GITHUB_TOKEN ? { authorization: `Bearer ${process.env.GITHUB_TOKEN}` } : {}),
});

async function upstream<T>(path: string): Promise<T | null> {
  try {
    const res = await fetch(`${API}${path}`, {
      headers: headers(),
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    });
    if (!res.ok) return null;
    return (await res.json()) as T;
  } catch {
    return null;
  }
}

function json(body: unknown, status: number, cacheControl: string): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': cacheControl,
    },
  });
}

/**
 * GET /api/releases → ReleasesFeed（source: 'live'）。
 * 上游失败但有缓存时回吐旧值并标 stale，前端据此静默降级，不弹错。
 */
export async function GET(): Promise<Response> {
  const fresh = Date.now() - feedCache.at < TTL_MS;
  if (fresh && feedCache.value) {
    return json(feedCache.value, 200, 'public, s-maxage=600, stale-while-revalidate=3600');
  }

  const [releases, repo] = await Promise.all([
    upstream<GhRelease[]>('/releases?per_page=100'),
    upstream<{ stargazers_count?: number }>('/'),
  ]);

  if (!releases || !Array.isArray(releases) || releases.length === 0) {
    if (feedCache.value) {
      return json({ ...(feedCache.value as object), stale: true }, 200, 'public, s-maxage=60');
    }
    return json({ error: 'upstream' }, 502, 'no-store');
  }

  const feed = buildFeed(releases, typeof repo?.stargazers_count === 'number' ? repo.stargazers_count : null, 'live');
  feedCache.value = feed;
  feedCache.at = Date.now();

  return json(feed, 200, 'public, s-maxage=600, stale-while-revalidate=3600');
}
