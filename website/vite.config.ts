import fs from 'node:fs';
import path from 'node:path';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';
import type { Plugin } from 'vite';

// 站点域名由部署方给定；不知道就整段不写，绝不用猜的域名填 JSON-LD
const SITE_URL = process.env.SITE_URL ?? '';

function readSnapshot() {
  try {
    const file = path.resolve('src/data/releases.json');
    return JSON.parse(fs.readFileSync(file, 'utf8')) as {
      latestTag: string;
      stars: number | null;
      releases: { tag: string; version: string; date: string; apkUrl: string | null; url: string }[];
    };
  } catch {
    return null;
  }
}

/** 把构建期快照的版本与日期写进 title / meta / JSON-LD，让未执行 JS 的抓取端也拿到准确版本号。 */
function siteMeta(): Plugin {
  return {
    name: 'n-link-site-meta',
    transformIndexHtml() {
      const snap = readSnapshot();
      const latest = snap?.releases.find((r) => r.tag === snap.latestTag) ?? snap?.releases[0];
      if (!latest) return [];

      const version = `v${latest.version}`;
      return [
        { tag: 'meta', attrs: { name: 'version', content: version }, injectTo: 'head' },
        {
          tag: 'script',
          attrs: { type: 'application/ld+json' },
          children: JSON.stringify({
            '@context': 'https://schema.org',
            '@graph': [
              {
                '@type': 'SoftwareApplication',
                name: 'N-Link',
                applicationCategory: 'MultimediaApplication',
                operatingSystem: 'Android 10+',
                softwareVersion: version,
                ...(SITE_URL ? { url: SITE_URL } : {}),
                downloadUrl: latest.apkUrl ?? latest.url,
                codeRepository: 'https://github.com/Wan-1230/N-link',
                isAccessibleForFree: true,
                dateModified: latest.date,
              },
              {
                '@type': 'Release',
                version: version,
                datePublished: latest.date,
                isReleaseOf: { '@id': 'https://schema.org/N-Link' },
              },
            ],
          }),
          injectTo: 'head',
        },
      ];
    },
  };
}

export default defineConfig({
  plugins: [react(), siteMeta()],
  build: {
    target: 'es2022',
    cssCodeSplit: true,
    rollupOptions: {
      output: {
        manualChunks: (id: string) => {
          if (!id.includes('node_modules')) return;
          if (id.includes('react-dom') || id.includes('/react/')) return 'v-react';
          if (id.includes('motion') || id.includes('framer')) return 'v-motion';
          if (id.includes('gsap')) return 'v-gsap';
          if (id.includes('ogl')) return 'v-ogl';
          if (id.includes('lenis')) return 'v-lenis';
          return 'v-other';
        },
      },
    },
  },
});
