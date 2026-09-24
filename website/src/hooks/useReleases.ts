import { useCallback, useEffect, useMemo, useState } from 'react';
import snapshot from '../data/releases.json';
import { buildFeed, emptyFeed, isReleasesFeed } from '../lib/release-feed';
import type { GhRelease, ReleasesFeed } from '../lib/release-feed';
import { REPO } from '../lib/site';

const LIVE_TIMEOUT_MS = 4000;

/**
 * 运行时补拉的来源，按优先级：
 * 1. VITE_RELEASES_API —— Sites Function 代理（需后端配额，可避开匿名限流）
 * 2. GitHub 公开 API —— 拿不到后端时的等价路径。未认证限 60 次/小时/IP，但每位访客
 *    本页只发一次，且失败即静默留在构建快照，不会白屏。
 * 没有配置 1 时不去敲一个注定 409 的函数路由。
 */
function liveSource(): string {
  const proxied = import.meta.env.VITE_RELEASES_API as string | undefined;
  return proxied && proxied.length > 0 ? proxied : `${REPO.api}/releases?per_page=100`;
}

export type FeedStatus = 'snapshot' | 'live' | 'error';

function initialFeed(): ReleasesFeed {
  return isReleasesFeed(snapshot) ? snapshot : emptyFeed();
}

// 直连 GitHub 拿不到 stars，沿用快照里的值，别让它退化成 null
function normalize(data: unknown): ReleasesFeed | null {
  if (isReleasesFeed(data) && data.releases.length) return data;
  if (Array.isArray(data) && data.length) return buildFeed(data as GhRelease[], initialFeed().stars, 'live');
  return null;
}

export function useReleases() {
  const [feed, setFeed] = useState<ReleasesFeed>(initialFeed);
  const [status, setStatus] = useState<FeedStatus>('snapshot');

  const pullLive = useCallback(async () => {
    try {
      const res = await fetch(liveSource(), { signal: AbortSignal.timeout(LIVE_TIMEOUT_MS) });
      if (!res.ok) {
        setStatus('error');
        return;
      }
      const next = normalize(await res.json());
      if (!next) {
        setStatus('error');
        return;
      }
      setStatus(next.stale ? 'snapshot' : 'live');
      setFeed((prev) =>
        // 版本没变就别换 releases 数组，否则整条时间轴会无谓重渲染、数字滚动重跑
        prev.latestTag === next.latestTag ? { ...next, releases: prev.releases } : next
      );
    } catch {
      setStatus('error');
    }
  }, []);

  useEffect(() => {
    void pullLive();
  }, [pullLive]);

  return useMemo(() => ({ feed, status, refresh: pullLive }), [feed, status, pullLive]);
}
