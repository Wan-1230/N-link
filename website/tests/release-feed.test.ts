import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { buildFeed, compareVersions, emptyFeed, isReleasesFeed, latestEntry, parseRelease } from '../src/lib/release-feed.ts';

// 夹具取自仓库真实 Release 正文（v2.3.1 / v2.2.0 / v1.1.0），三种格式并存是实测结论，不是臆造。
const BODY_231 = [
  '【v2.3.1 · 快门次数收口 + 玻璃收尾】',
  '',
  '· 快门次数查询重写：设备页可直接读出机身快门数，全程离线、不耗流量',
  '· 隐私收口：读不出时不再静默上传照片，改为先征得你的同意，并说明上传什么、给谁',
  '· 电子快门机型（Z8 / Z9 等）同时给出「仅机械快门」次数，两个口径分得清',
  '· 液态玻璃收尾：移除全部覆盖式顶栏，标签切换改为同步换页，底部导航悬浮与自动隐藏',
  '· 系统提示统一收口，各页气泡与弹窗风格一致，不再一处一个样',
  '· 无障碍：大字档下相册胶囊自动长高、系统高对比度自动降级、顶栏按钮点击区域加大',
  '· 传输大文件期间玻璃特效自动降级，下载不再被特效抢占而变慢',
  '',
  '百度网盘：https://pan.baidu.com/s/1if5qoCaQRjzBlCThs_sfsQ?pwd=trub',
  '',
  '夸克网盘：https://pan.quark.cn/s/d060f7f350e8',
  '',
].join('\n');

const BODY_220 = ['【N-Link v2.2.0 更新内容】', '· AP 连接加固：自动学习相机热点地址，关着蓝牙也能连', '· USB 有线监看不再周期性掉线', ''].join('\n');

const BODY_110 = [
  '# N-Link v1.1.0',
  '',
  '六大模块专项修复 + 长曝光与快门次数增强（含 B 门修复、六模块修复与两轮真机反馈修复的合并）。',
  '',
  '## B 门 / 长曝光',
  '- **全链路重构**：自动切换 Bulb 档 → 多级触发指令 → 可靠收门',
  '- **定时长曝光**：15 秒 ~ 5 分钟可选',
  '',
].join('\n');

describe('parseRelease · 三种真实正文格式', () => {
  it('v2.3.1 括号头：取到 version / title / 7 条要点 / 双渠道 / APK', () => {
    const r = parseRelease({
      tag_name: 'v2.3.1',
      name: 'N-Link v2.3.1',
      body: BODY_231,
      published_at: '2026-09-21T06:16:09Z',
      html_url: 'https://github.com/Wan-1230/N-link/releases/tag/v2.3.1',
      assets: [{ name: 'N-Link-v2.3.1-release.apk', size: 3941780, browser_download_url: 'https://gh/apk' }],
    });

    assert.equal(r.version, '2.3.1');
    assert.equal(r.title, '快门次数收口 + 玻璃收尾');
    assert.equal(r.bullets.length, 7);
    assert.equal(r.channels.quark, 'https://pan.quark.cn/s/d060f7f350e8');
    assert.equal(r.channels.baidu, 'https://pan.baidu.com/s/1if5qoCaQRjzBlCThs_sfsQ?pwd=trub');
    assert.equal(r.apkUrl, 'https://gh/apk');
    assert.equal(r.apkSize, 3941780);
    assert.equal(r.summary, null, '括号头版式的散文位是空的，不该硬造摘要');
    for (const b of r.bullets) {
      assert.ok(!b.startsWith('·'), '要点不应带项目符号');
      assert.ok(!/网盘/.test(b), '渠道行不应混进要点');
    }
  });

  it('v2.2.0 旧括号头：title 为 null，version 回落 tag，摘要不硬造', () => {
    const r = parseRelease({ tag_name: 'v2.2.0', body: BODY_220, published_at: '2026-09-20T00:00:00Z' });
    assert.equal(r.title, null);
    assert.equal(r.version, '2.2.0');
    assert.equal(r.summary, null);
    assert.equal(r.bullets.length, 2);
    assert.equal(r.apkUrl, null);
  });

  it('v1.1.0 真 Markdown：抽出散文摘要，标题行不进要点，粗体标记留给渲染层', () => {
    const r = parseRelease({ tag_name: 'v1.1.0', body: BODY_110, published_at: '2026-09-06T00:00:00Z' });
    assert.equal(r.title, null);
    assert.match(r.summary ?? '', /^六大模块专项修复/);
    assert.equal(r.bullets.length, 2);
    assert.ok(!r.bullets.some((b) => b.startsWith('#')), '## 标题行不应成为要点');
    assert.match(r.bullets[0], /^\*\*全链路重构\*\*/, '粗体标记应原样保留，由 UI 转节点');
  });
});

describe('parseRelease · 畸形输入不抛错', () => {
  it('body 为 null、tag 为空、assets 非数组，全部降级而不炸', () => {
    const r = parseRelease({ tag_name: '', body: null, assets: undefined });
    assert.equal(r.version, '');
    assert.equal(r.title, null);
    assert.equal(r.summary, null);
    assert.deepEqual(r.bullets, []);
    assert.equal(r.apkUrl, null);
    assert.equal(r.prerelease, false);
    assert.equal(r.date, '');
  });

  it('括号头缺标题、只有版本号时也成立', () => {
    const r = parseRelease({ tag_name: 'v9.9.9', body: '【v9.9.9】\n· 一条\n' });
    assert.equal(r.version, '9.9.9');
    assert.equal(r.title, null);
    assert.equal(r.bullets.length, 1);
  });

  it('超长散文行截断到 160 字符加省略号', () => {
    const long = 'x'.repeat(400);
    const r = parseRelease({ tag_name: 'v1.0.0', body: `${long}\n` });
    assert.equal((r.summary ?? '').length, 161);
    assert.ok((r.summary ?? '').endsWith('…'));
  });
});

describe('buildFeed', () => {
  const raw = [
    { tag_name: 'v1.0.0', name: 'A', body: '· a', published_at: '2026-09-01T00:00:00Z', draft: false },
    { tag_name: 'v2.0.0', name: 'B', body: '· b', published_at: '2026-09-05T00:00:00Z' },
    { tag_name: 'v2.1.0-beta', name: 'C', body: '· c', published_at: '2026-09-09T00:00:00Z', prerelease: true },
    { tag_name: 'v9.9.9', name: 'D', body: '· d', published_at: '2026-09-30T00:00:00Z', draft: true },
  ];

  it('丢掉 draft、按时间降序、latestTag 只认正式版', () => {
    const feed = buildFeed(raw, 50, 'snapshot');
    assert.deepEqual(
      feed.releases.map((r) => r.tag),
      ['v2.1.0-beta', 'v2.0.0', 'v1.0.0']
    );
    assert.equal(feed.latestTag, 'v2.0.0', '预发布虽排在最前，但不算最新正式版');
    assert.equal(feed.stars, 50);
    assert.equal(feed.source, 'snapshot');
  });

  it('空数组与 null 都不炸', () => {
    assert.deepEqual(buildFeed([], null, 'live').releases, []);
    assert.equal(buildFeed([], null, 'live').latestTag, '');
  });

  it('latestEntry 命中正式版，而不是排在最前的预发布', () => {
    const feed = buildFeed(raw, null, 'snapshot');
    assert.equal(latestEntry(feed)?.tag, 'v2.0.0');
    assert.equal(latestEntry(emptyFeed()), null);
  });
});

describe('compareVersions', () => {
  it('逐段比较而非字符串比较', () => {
    assert.equal(compareVersions('2.10.0', '2.9.0'), 1);
    assert.equal(compareVersions('2.3.1', '2.3.1'), 0);
    assert.equal(compareVersions('1.9.9', '2.0.0'), -1);
  });
});

describe('isReleasesFeed', () => {
  it('只认形状相符的对象', () => {
    assert.equal(isReleasesFeed({ latestTag: 'v1', releases: [] }), true);
    assert.equal(isReleasesFeed(null), false);
    assert.equal(isReleasesFeed('<html>'), false);
    assert.equal(isReleasesFeed({ releases: [] }), false);
    assert.equal(isReleasesFeed({ latestTag: 'v1' }), false);
  });
});
