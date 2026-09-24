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
