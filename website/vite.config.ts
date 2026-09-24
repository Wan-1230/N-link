import fs from 'node:fs';
import path from 'node:path';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';
import type { Plugin } from 'vite';

// 上线后确定的真实来源；未部署到别处前不要改成猜的值
const SITE_ORIGIN = 'https://n-link-qd0m70ho562.qoder.zone';

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
        { tag: 'link', attrs: { rel: 'canonical', href: `${SITE_ORIGIN}/` }, injectTo: 'head' },
        // 社交抓取普遍要求绝对 URL，index.html 里那条相对 og:image 抓不到
        { tag: 'meta', attrs: { property: 'og:url', content: `${SITE_ORIGIN}/` }, injectTo: 'head' },
        { tag: 'meta', attrs: { property: 'og:image', content: `${SITE_ORIGIN}/og.png` }, injectTo: 'head' },
        { tag: 'meta', attrs: { name: 'twitter:image', content: `${SITE_ORIGIN}/og.png` }, injectTo: 'head' },
        {
          tag: 'script',
          attrs: { type: 'application/ld+json' },
          children: JSON.stringify({
            '@context': 'https://schema.org',
            '@graph': [
              {
                '@type': 'SoftwareApplication',
                '@id': `${SITE_ORIGIN}/#app`,
                name: 'N-Link',
                applicationCategory: 'MultimediaApplication',
                operatingSystem: 'Android 10+',
                softwareVersion: version,
                url: SITE_ORIGIN,
                downloadUrl: latest.apkUrl ?? latest.url,
                codeRepository: 'https://github.com/Wan-1230/N-link',
                isAccessibleForFree: true,
                dateModified: latest.date,
              },
              {
                '@type': 'Release',
                version: version,
                datePublished: latest.date,
                isReleaseOf: { '@id': `${SITE_ORIGIN}/#app` },
              },
            ],
          }),
          injectTo: 'head',
        },
      ];
    },
    writeBundle() {
      // 单页站的 sitemap 之前刻意不写，是因为那时没有真域名、写了就是编
      const iso = new Date().toISOString().slice(0, 10);
      const urls = ['/', '/releases/'];
      const xml = `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
${urls.map((u) => `  <url>\n    <loc>${SITE_ORIGIN}${u}</loc>\n    <lastmod>${iso}</lastmod>\n  </url>`).join('\n')}
</urlset>
`;
      fs.writeFileSync(path.resolve('dist/sitemap.xml'), xml);
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
