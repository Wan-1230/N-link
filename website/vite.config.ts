import fs from 'node:fs';
import path from 'node:path';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';
import type { Plugin } from 'vite';

const SITE_ORIGIN = 'https://n-link-qd0m70ho562.qoder.zone';
const REPO_LATEST = 'https://github.com/Wan-1230/N-link/releases/latest';
const MARKER = '<!--head:meta-->';

interface Snapshot {
  latestTag: string;
  stars: number | null;
  releases: { tag: string; version: string; date: string; title: string | null; apkUrl: string | null; url: string }[];
}

function readSnapshot(): Snapshot | null {
  try {
    return JSON.parse(fs.readFileSync(path.resolve('src/data/releases.json'), 'utf8')) as Snapshot;
  } catch {
    return null;
  }
}

const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');

/**
 * 每个路由一份独立的 head。之前两条路由共用同一个 title，且 /releases 的 canonical
 * 指向首页 —— 搜索引擎会把它判成重复页，永远不会单独收录。
 * 这里在构建期直接产出 dist/releases/index.html，不依赖运行时改 DOM，
 * 对不执行 JS 的抓取端同样有效。
 */
function headFor(route: '/' | '/releases/', snap: Snapshot, version: string): string {
  const url = `${SITE_ORIGIN}${route}`;
  const ogImage = `${SITE_ORIGIN}/og.png`;
  const latest = snap.releases.find((r) => r.tag === snap.latestTag) ?? snap.releases[0];

  const home = route === '/';
  const title = home
    ? 'N-Link · 尼康 Z 系列微单的 Android 连接与遥控伴侣'
    : `N-Link v${version} 下载与更新日志 · 尼康 Z 系列遥控伴侣`;
  const description = home
    ? '开源 Android 应用，为尼康 Z50II / Z6III / Z8 / Z9 / Zf 打通连接、浏览、传输、遥控、监看的完整链路。版本号与更新日志自动同步 GitHub Releases。'
    : `下载 N-Link v${version} APK，查看完整版本历史。开源 Android 尼康 Z 系列微单连接与遥控伴侣，支持 Z50II / Z6III / Z8 / Z9 / Zf。`;

  const jsonLd = home
    ? {
        '@context': 'https://schema.org',
        '@graph': [
          {
            '@type': 'SoftwareApplication',
            '@id': `${SITE_ORIGIN}/#app`,
            name: 'N-Link',
            alternateName: 'N-Link 尼康遥控伴侣',
            applicationCategory: 'MultimediaApplication',
            operatingSystem: 'Android 10+',
            softwareVersion: `v${version}`,
            url: `${SITE_ORIGIN}/`,
            downloadUrl: latest?.apkUrl ?? latest?.url ?? REPO_LATEST,
            codeRepository: 'https://github.com/Wan-1230/N-link',
            isAccessibleForFree: true,
            dateModified: latest?.date ?? '',
          },
          {
            '@type': 'Release',
            version: `v${version}`,
            datePublished: latest?.date ?? '',
            isReleaseOf: { '@id': `${SITE_ORIGIN}/#app` },
          },
        ],
      }
    : {
        '@context': 'https://schema.org',
        '@type': 'CollectionPage',
        '@id': url,
        name: 'N-Link 下载与更新日志',
        url,
        isPartOf: { '@id': `${SITE_ORIGIN}/#app` },
      };

  return [
    `<title>${esc(title)}</title>`,
    `<meta name="description" content="${esc(description)}" />`,
    `<meta name="version" content="v${version}" />`,
    `<link rel="canonical" href="${url}" />`,
    `<meta property="og:type" content="${home ? 'website' : 'article'}" />`,
    `<meta property="og:site_name" content="N-Link" />`,
    `<meta property="og:locale" content="zh_CN" />`,
    `<meta property="og:title" content="${esc(title)}" />`,
    `<meta property="og:description" content="${esc(description)}" />`,
    `<meta property="og:url" content="${url}" />`,
    `<meta property="og:image" content="${ogImage}" />`,
    `<meta name="twitter:card" content="summary_large_image" />`,
    `<meta name="twitter:title" content="${esc(title)}" />`,
    `<meta name="twitter:description" content="${esc(description)}" />`,
    `<meta name="twitter:image" content="${ogImage}" />`,
    `<script type="application/ld+json">${JSON.stringify(jsonLd)}</script>`,
  ].join('\n    ');
}

function siteMeta(): Plugin {
  return {
    name: 'n-link-site-meta',
    transformIndexHtml(html) {
      const snap = readSnapshot();
      if (!snap) return html;
      const version = (snap.releases.find((r) => r.tag === snap.latestTag) ?? snap.releases[0])?.version ?? '';
      return html.replace(MARKER, headFor('/', snap, version));
    },
    writeBundle() {
      const snap = readSnapshot();
      const file = path.resolve('dist/index.html');
      if (!snap || !fs.existsSync(file)) return;

      const html = fs.readFileSync(file, 'utf8');
      const version = (snap.releases.find((r) => r.tag === snap.latestTag) ?? snap.releases[0])?.version ?? '';
      const homeHead = headFor('/', snap, version);
      if (!html.includes(homeHead)) {
        console.warn('[site-meta] 首页 head 未命中，跳过 /releases 生成——两条路由会共用元数据');
        return;
      }

      fs.mkdirSync(path.resolve('dist/releases'), { recursive: true });
      fs.writeFileSync(path.resolve('dist/releases/index.html'), html.replace(homeHead, headFor('/releases/', snap, version)));

      const iso = new Date().toISOString().slice(0, 10);
      const urls = ['/', '/releases/'];
      fs.writeFileSync(
        path.resolve('dist/sitemap.xml'),
        `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls
          .map((u) => `  <url>\n    <loc>${SITE_ORIGIN}${u}</loc>\n    <lastmod>${iso}</lastmod>\n  </url>`)
          .join('\n')}\n</urlset>\n`
      );
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
