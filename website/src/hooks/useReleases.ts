import { useCallback, useEffect, useMemo, useState } from 'react';
import snapshot from '../data/releases.json';
import { emptyFeed, isReleasesFeed } from '../lib/release-feed';
import type { ReleasesFeed } from '../lib/release-feed';

// Sites 的 Edge Function 以逻辑名 app 暴露，浏览器只走同源相对路径
const LIVE_ENDPOINT = '/functions/v1/app/releases';
const LIVE_TIMEOUT_MS = 4000;

export type FeedStatus = 'snapshot' | 'live' | 'error';

function initialFeed(): ReleasesFeed {
  return isReleasesFeed(snapshot) ? snapshot : emptyFeed();
}

export function useReleases() {
  const [feed, setFeed] = useState<ReleasesFeed>(initialFeed);
  const [status, setStatus] = useState<FeedStatus>('snapshot');

  const pullLive = useCallback(async () => {
    try {
      const res = await fetch(LIVE_ENDPOINT, { signal: AbortSignal.timeout(LIVE_TIMEOUT_MS) });
      if (!res.ok) {
        setStatus('error');
        return;
      }
      const data: unknown = await res.json();
      if (!isReleasesFeed(data) || !data.releases.length) {
        setStatus('error');
        return;
      }
      setFeed((prev) =>
        // 版本没变就别换 releases 数组，否则整条时间轴会无谓重渲染、CountUp 重跑
        prev.latestTag === data.latestTag ? { ...data, releases: prev.releases } : data
      );
      setStatus(data.stale ? 'snapshot' : 'live');
    } catch {
      setStatus('error');
    }
  }, []);

  useEffect(() => {
    void pullLive();
  }, [pullLive]);

  return useMemo(
    () => ({ feed, status, refresh: pullLive }),
    [feed, status, pullLive]
  );
}
