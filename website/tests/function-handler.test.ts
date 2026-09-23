import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { handler } from '../functions/handler.ts';

// 只断言不依赖网络的分支。真实上游那一条留到部署后用一次同源请求验，
// 在本机把 GitHub 拉进单测只会得到网络抖动导致的假失败。
describe('Sites Function handler · 无需网络的分支', () => {
  it('非 GET 一律 405 且带 Allow', async () => {
    const res = await handler(new Request('https://site.example/functions/v1/app/releases', { method: 'POST' }));
    assert.equal(res.status, 405);
    assert.equal(res.headers.get('allow'), 'GET');
    assert.equal((await res.json()).ok, false);
  });

  it('未知路径 404，不会一律返回成功', async () => {
    const res = await handler(new Request('https://site.example/functions/v1/app/delete-everything'));
    assert.equal(res.status, 404);
    assert.equal((await res.json()).error, 'not_found');
  });

  it('物理名被网关换掉时仍按后缀命中 releases 路由', async () => {
    const res = await handler(new Request('https://site.example/functions/v1/phys-abc123/releases'));
    assert.notEqual(res.status, 404);
  });

  it('响应声明了缓存策略', async () => {
    const res = await handler(new Request('https://site.example/functions/v1/app/releases'));
    const cc = res.headers.get('cache-control') ?? '';
    assert.ok(res.status === 200 || res.status === 502, `unexpected status ${res.status}`);
    if (res.status === 200) assert.match(cc, /s-maxage=600/);
    else assert.equal(cc, 'no-store');
  });
});
