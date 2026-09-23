export type ReleaseChannel = 'github' | 'quark' | 'baidu';

export interface ReleaseEntry {
  tag: string;
  version: string;
  name: string;
  title: string | null;
  summary: string | null;
  date: string;
  bullets: string[];
  apkUrl: string | null;
  apkName: string | null;
  apkSize: number | null;
  channels: Partial<Record<ReleaseChannel, string>>;
  prerelease: boolean;
  url: string;
}

export interface ReleasesFeed {
  generatedAt: string;
  latestTag: string;
  releases: ReleaseEntry[];
  stars: number | null;
  source: 'snapshot' | 'live';
  stale: boolean;
}

interface GhAsset {
  name?: string;
  size?: number;
  browser_download_url?: string;
}

export interface GhRelease {
  tag_name?: string;
  name?: string;
  body?: string | null;
  published_at?: string;
  created_at?: string;
  prerelease?: boolean;
  draft?: boolean;
  html_url?: string;
  assets?: GhAsset[];
}

const HEAD_LINE = /^【v?(\d+\.\d+\.\d+)(?:\s*·\s*([^】]+))?】$/;
const BULLET_LINE = /^\s*[·\-•]\s+(.+)$/;
const CHANNEL_LINE = /^(夸克网盘|百度网盘|GitHub)[：:]\s*(https?:\/\/\S+)/;
const ANY_BRACKET_HEAD = /^【[^】]*】$/;
const MD_HEADING = /^#{1,6}\s/;
const HR = /^-{3,}$/;
const SUMMARY_MAX = 160;

const CHANNEL_KEYS: Record<string, ReleaseChannel> = {
  夸克网盘: 'quark',
  百度网盘: 'baidu',
  GitHub: 'github',
};

export function parseRelease(raw: GhRelease): ReleaseEntry {
  const tag = typeof raw.tag_name === 'string' ? raw.tag_name : '';
  const body = typeof raw.body === 'string' ? raw.body : '';
  const lines = body.split(/\r?\n/);

  let version = tag.replace(/^v/, '');
  let title: string | null = null;
  let summary: string | null = null;
  const bullets: string[] = [];
  const channels: Partial<Record<ReleaseChannel, string>> = {};

  for (const rawLine of lines) {
    const line = rawLine.trim();
    if (!line || HR.test(line)) continue;

    const channel = line.match(CHANNEL_LINE);
    if (channel) {
      const key = CHANNEL_KEYS[channel[1]];
      if (key && !channels[key]) channels[key] = channel[2];
      continue;
    }

    const head = line.match(HEAD_LINE);
    if (head) {
      version = head[1];
      title = head[2]?.trim() ?? null;
      continue;
    }

    // 形如【N-Link v2.2.0 更新内容】的旧版头：只吞掉，不当摘要
    if (ANY_BRACKET_HEAD.test(line) || MD_HEADING.test(line)) continue;

    const bullet = rawLine.match(BULLET_LINE);
    if (bullet?.[1]) {
      bullets.push(bullet[1].trim());
      continue;
    }

    if (!summary) summary = line.length > SUMMARY_MAX ? `${line.slice(0, SUMMARY_MAX)}…` : line;
  }

  if (raw.html_url) channels.github = raw.html_url;

  const apk = (raw.assets ?? []).find((a) => typeof a.name === 'string' && a.name.endsWith('.apk'));

  return {
    tag,
    version,
    name: typeof raw.name === 'string' && raw.name ? raw.name : tag,
    title,
    summary,
    date: raw.published_at ?? raw.created_at ?? '',
    bullets,
    apkUrl: apk?.browser_download_url ?? null,
    apkName: apk?.name ?? null,
    apkSize: typeof apk?.size === 'number' ? apk.size : null,
    channels,
    prerelease: raw.prerelease === true,
    url: raw.html_url ?? '',
  };
}

export function compareVersions(a: string, b: string): number {
  const pa = a.split('.').map((n) => Number.parseInt(n, 10) || 0);
  const pb = b.split('.').map((n) => Number.parseInt(n, 10) || 0);
  for (let i = 0; i < 3; i++) {
    const diff = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (diff !== 0) return diff > 0 ? 1 : -1;
  }
  return 0;
}

export function buildFeed(raws: GhRelease[], stars: number | null, source: ReleasesFeed['source']): ReleasesFeed {
  const releases = (raws ?? [])
    .filter((r) => r.draft !== true)
    .map(parseRelease)
    .sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : compareVersions(b.version, a.version)));

  const latest = releases.find((r) => !r.prerelease) ?? null;

  return {
    generatedAt: new Date().toISOString(),
    latestTag: latest?.tag ?? '',
    releases,
    stars,
    source,
    stale: false,
  };
}

const EMPTY: ReleasesFeed = {
  generatedAt: '',
  latestTag: '',
  releases: [],
  stars: null,
  source: 'snapshot',
  stale: false,
};

export function isReleasesFeed(value: unknown): value is ReleasesFeed {
  if (typeof value !== 'object' || value === null) return false;
  const v = value as Record<string, unknown>;
  return typeof v.latestTag === 'string' && Array.isArray(v.releases);
}

export function emptyFeed(): ReleasesFeed {
  return { ...EMPTY, releases: [] };
}

export function latestEntry(feed: ReleasesFeed): ReleaseEntry | null {
  return feed.releases.find((r) => r.tag === feed.latestTag) ?? feed.releases[0] ?? null;
}
