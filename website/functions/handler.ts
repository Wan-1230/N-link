import { buildFeed, type GhRelease } from './release-feed.ts';

const API = 'https://api.github.com/repos/Wan-1230/N-link';
const TTL_MS = 600_000;
const UPSTREAM_TIMEOUT_MS = 8_000;

declare const Deno: { env: { get(key: string): string | undefined } };

function secret(name: string): string | undefined {
  return typeof Deno === 'undefined' ? undefined : Deno.env.get(name);
}

const cache: { value: unknown; at: number } = { value: null, at: 0 };

function json(body: unknown, status: number, cacheControl: string, extra?: Record<string, string>): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': cacheControl, ...extra },
  });
}

async function upstream<T>(path: string, token: string | undefined): Promise<T | null> {
  try {
    const res = await fetch(`${API}${path}`, {
      headers: {
        accept: 'application/vnd.github+json',
        'user-agent': 'n-link-site-function',
        ...(token ? { authorization: `Bearer ${token}` } : {}),
      },
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    });
    if (!res.ok) return null;
    return (await res.json()) as T;
  } catch {
    return null;
  }
}

/** 网关会把逻辑名 app 换成上游物理名但保留后缀，所以按后缀判定而不是匹配完整路径。 */
function isReleasesRoute(pathname: string): boolean {
  return pathname.endsWith('/releases') || pathname === '/' || pathname.endsWith('/app');
}

export async function handler(request: Request): Promise<Response> {
  if (request.method !== 'GET') {
    return json({ ok: false, error: 'method_not_allowed' }, 405, 'no-store', { allow: 'GET' });
  }

  const { pathname } = new URL(request.url);
  if (!isReleasesRoute(pathname)) {
    return json({ ok: false, error: 'not_found' }, 404, 'no-store');
  }

  if (cache.value && Date.now() - cache.at < TTL_MS) {
    return json(cache.value, 200, 'public, s-maxage=600, stale-while-revalidate=3600');
  }

  const token = secret('GITHUB_TOKEN');
  const [releases, repo] = await Promise.all([
    upstream<GhRelease[]>('/releases?per_page=100', token),
    upstream<{ stargazers_count?: number }>('/', token),
  ]);

  if (!Array.isArray(releases) || releases.length === 0) {
    if (cache.value) {
      return json({ ...(cache.value as object), stale: true }, 200, 'public, s-maxage=60');
    }
    return json({ ok: false, error: 'upstream_unavailable' }, 502, 'no-store');
  }

  const feed = buildFeed(releases, typeof repo?.stargazers_count === 'number' ? repo.stargazers_count : null, 'live');
  cache.value = feed;
  cache.at = Date.now();

  return json(feed, 200, 'public, s-maxage=600, stale-while-revalidate=3600');
}
