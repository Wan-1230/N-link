export const REPO = {
  owner: 'Wan-1230',
  name: 'N-link',
  url: 'https://github.com/Wan-1230/N-link',
  latest: 'https://github.com/Wan-1230/N-link/releases/latest',
  issues: 'https://github.com/Wan-1230/N-link/issues',
  readme: 'https://github.com/Wan-1230/N-link#readme',
  api: 'https://api.github.com/repos/Wan-1230/N-link',
} as const;

export const SITE_ORIGIN = 'https://n-link-qd0m70ho562.qoder.zone';

/**
 * 路由元数据唯一出处：构建期 vite.config 生成静态 head，运行时 router 同步
 * document.title / canonical 都读这里。宿主只按精确文件路径给静态文件
 * （/releases/index.html 才对，/releases 与 /releases/ 都回退到根 index.html），
 * 所以运行时这一遍是必需的，否则两条入口路径会带着首页的元数据。
 * 与语言无关，不进 i18n。
 */
export const ROUTE_META = {
  '/': {
    title: 'N-Link · 尼康 Z 系列微单的 Android 连接与遥控伴侣',
    description:
      '开源 Android 应用，为尼康 Z50II / Z6III / Z8 / Z9 / Zf 打通连接、浏览、传输、遥控、监看的完整链路。版本号与更新日志自动同步 GitHub Releases。',
    canonical: `${SITE_ORIGIN}/`,
  },
  '/releases/': {
    title: 'N-Link {version} 下载与更新日志 · 最新版本与全部历史版本',
    description:
      '下载 N-Link {version} APK，查看完整版本历史。开源 Android 尼康 Z 系列微单连接与遥控伴侣，支持 Z50II / Z6III / Z8 / Z9 / Zf。',
    canonical: `${SITE_ORIGIN}/releases/`,
  },
} as const;

export type RouteKey = keyof typeof ROUTE_META;

export function routeMeta(key: RouteKey, version: string) {
  const fill = (s: string) => s.replaceAll('{version}', version);
  return { title: fill(ROUTE_META[key].title), description: fill(ROUTE_META[key].description), canonical: ROUTE_META[key].canonical };
}

export const CHANNELS = {
  quark: 'https://pan.quark.cn/s/d060f7f350e8',
  baidu: 'https://pan.baidu.com/s/1if5qoCaQRjzBlCThs_sfsQ?pwd=trub',
} as const;

export const DEVICE_MODELS = ['Z50II', 'Z6III', 'Z8', 'Z9', 'Zf'] as const;

/** 首页正文段落，同时也是 scrollspy 的观测目标。 */
export const HOME_SECTIONS = ['trust', 'pipeline', 'features', 'why', 'how', 'roadmap', 'tech'] as const;

/** 「产品 ▾」里的分组；标签与说明文案在 i18n，这里只放结构，避免两套语言各存一份。 */
export const PRODUCT_GROUPS = [
  { key: 'capability', items: ['features', 'pipeline', 'why'] },
  { key: 'adopt', items: ['how'] },
  { key: 'engineering', items: ['tech', 'roadmap'] },
] as const;

export type ProductItemKey = (typeof PRODUCT_GROUPS)[number]['items'][number];
export type ProductGroupKey = (typeof PRODUCT_GROUPS)[number]['key'];
