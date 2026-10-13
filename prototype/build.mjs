#!/usr/bin/env node
/**
 * 原型构建脚本（审计 P2-15）—— 零依赖，只用 Node 内置模块。
 *
 * 做法与取舍：
 * - CSS：去注释 + 折叠空白（安全，规则之间无语义空白）。
 * - HTML：去注释 + 折叠标签间空白（保留 <pre>/<textarea> 的语义，本文件没有）。
 * - JS：**只去注释，不动标识符与空白** —— 原生原型 JS 里有模板字符串与正则，
 *   任何"聪明"的 JS 压缩都可能悄悄改语义；为省几百字节引入这种风险不划算。
 * - 输出 prototype/dist/index.html，并打印原始 / 构建后 / gzip 三档体积。
 *
 * 用法: node build.mjs
 */
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { gzipSync } from "node:zlib";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const srcPath = join(here, "index.html");
const outDir = join(here, "dist");
const outPath = join(outDir, "index.html");

let html = readFileSync(srcPath, "utf8");
const rawBytes = Buffer.byteLength(html, "utf8");

// 1) CSS：去注释 + 折叠空白
html = html.replace(/<style>([\s\S]*?)<\/style>/g, (_m, css) => {
  let out = css.replace(/\/\*[\s\S]*?\*\//g, "");
  out = out.replace(/\s*([{}:;,>])\s*/g, "$1").replace(/\s{2,}/g, " ").trim();
  return `<style>${out}</style>`;
});

// 2) HTML：去掉注释（保留 <!--[if]> 之类，本文件无），折叠标签之间的空行与缩进
html = html.replace(/<!--(?!\[)[\s\S]*?-->/g, "");
html = html.replace(/>\s{2,}</g, "><").replace(/^\s+|\s+$/gm, "");

// 3) JS：只去注释，不动代码
html = html.replace(/<script>([\s\S]*?)<\/script>/g, (_m, js) => {
  let out = js.replace(/\/\*[\s\S]*?\*\//g, "");
  out = out
    .split("\n")
    .filter((line) => {
      const t = line.trim();
      // 整行注释去掉；含协议的 URL（"://"）所在行原样保留
      if (t.startsWith("//")) return false;
      return true;
    })
    .map((line) => {
      if (line.includes("://")) return line;
      return line.replace(/\s+\/\/[^\n]*$/, "");
    })
    .join("\n");
  return `<script>${out}</script>`;
});

if (!existsSync(outDir)) mkdirSync(outDir, { recursive: true });
writeFileSync(outPath, html, "utf8");

const outBytes = Buffer.byteLength(html, "utf8");
const gzipBytes = gzipSync(Buffer.from(html, "utf8")).length;
const kb = (n) => (n / 1024).toFixed(1) + " KB";

console.log("原型构建完成");
console.log(`  源  文件: ${kb(rawBytes)}`);
console.log(`  构建产物: ${kb(outBytes)}  (-${(100 - (outBytes / rawBytes) * 100).toFixed(0)}%)`);
console.log(`  gzip 后 : ${kb(gzipBytes)}`);
console.log(`  输出路径: ${outPath}`);
