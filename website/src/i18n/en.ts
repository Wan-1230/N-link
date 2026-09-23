import type { Dict } from './zh';

export const en: Dict = {
  meta: {
    title: 'N-Link · Android connection & remote control companion for Nikon Z cameras',
    description:
      'Open-source Android app linking browse, transfer, remote control and live view for Nikon Z50II / Z6III / Z8 / Z9 / Zf. BLE keep-alive, automatic reconnect, dual USB and WiFi high-speed channels.',
  },
  cta: {
    download: 'Download APK',
    github: 'GitHub repo',
    refresh: 'Refresh',
    copy: 'Copy',
    copied: 'Copied',
    latest: 'Latest stable',
    prerelease: 'Preview',
    viewRelease: 'View release page',
    back: 'Back',
  },
  nav: {
    sections: {
      features: 'Features',
      why: 'Why',
      how: 'Guide',
      changelog: 'Changelog',
      roadmap: 'Roadmap',
      tech: 'Tech',
      download: 'Download',
    },
    open: 'Open menu',
    close: 'Close menu',
    langLabel: '中文',
    langSwitchTo: 'zh',
  },
  hero: {
    badge: 'Open source · Android 10+',
    titleA: 'The never-drops-',
    titleB: 'Nikon remote companion',
    subtitle:
      'Connect, browse, transfer, control and monitor in one continuous chain. BLE heartbeat plus a foreground service reconnect automatically on drop; USB and WiFi run as dual high-speed channels with smart scheduling and fallback.',
    primary: 'Download v{version}',
    secondary: 'Read the changelog',
    scrollHint: 'Scroll',
    statStars: 'Stars',
    statVersion: 'Version',
    statUpdated: 'Updated',
  },
  changelog: {
    eyebrow: 'Changelog',
    title: 'Version history',
    lede: 'Version numbers and release notes are read straight from GitHub Releases, so they land here automatically after a release instead of being maintained by hand.',
    count: '{n} releases',
    latest: 'Latest',
    prerelease: 'Preview',
    sourceLive: 'Live sync',
    sourceSnapshot: 'Build snapshot',
    syncedAt: 'Synced {time}',
    refreshing: 'Syncing…',
    doRefresh: 'Sync now',
    expand: 'Show all {n} items',
    collapse: 'Collapse',
    apk: 'Download this version',
    viewOnGithub: 'Release page',
    channels: { quark: 'Quark Drive', baidu: 'Baidu Netdisk' },
    empty: 'No release notes available right now. See GitHub Releases directly.',
    langNote: 'Release notes are kept in the author’s original Chinese and are not translated.',
  },
  footer: {
    unofficial:
      'A personal open-source research project. Not affiliated with Nikon Corporation and not an official Nikon application. Nikon is a trademark of its respective owners.',
    license:
      'The repository does not yet ship a license file. Do not redistribute commercially or republish without explicit permission from the author.',
    reactbits: 'Motion components from React Bits, © David Haz, MIT + Commons Clause.',
    syncNote: 'Version and changelog auto-synced from GitHub Releases. Synced {time}.',
    issues: 'Report an issue',
    source: 'Source',
  },
};
