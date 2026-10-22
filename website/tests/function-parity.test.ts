import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it } from 'node:test';

// Sites 的打包器不解析跨目录 import，函数只能自带一份解析层。
// 副本是必要的坏味道，这个测试就是用来防止两份悄悄走偏。
const ROOT = path.resolve(fileURLToPath(new URL('..', import.meta.url)));

const strip = (file: string) =>
  fs
    .readFileSync(path.join(ROOT, file), 'utf8')
    .split('\n')
    .filter((line) => !line.startsWith('// '))
    .join('\n')
    .trim();

describe('Sites Function 与前端解析层保持同源', () => {
  it('functions/release-feed.ts 正文与 src/lib/release-feed.ts 逐字一致', () => {
    assert.equal(strip('functions/release-feed.ts'), strip('src/lib/release-feed.ts'));
  });
});
