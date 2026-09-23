# N-Link 官网（软件介绍站）设计文档

- 日期：2026-09-23
- 状态：待评审
- 交付物：`website/` 目录下的静态单页站 + 一条 GitHub Releases 自动同步管道，经 Qoder Sites 托管

---

## 0. 输入事实（设计不与之冲突，实现不另编数据）

| 项 | 事实 |
|---|---|
| 产品 | N-Link，开源 Android 尼康 Z 系列微单连接与遥控伴侣 |
| 当前版本 | v2.3.1（2026-09-21 发布） |
| 仓库 | `github.com/Wan-1230/N-link`，PUBLIC，50 stars，8 forks |
| 仓库能力 | Issues 已启用，Discussions **未**启用，**无 LICENSE 文件**，未设置 homepage |
| Release 资产 | 每个版本挂 `N-Link-v<ver>-release.apk` |
| Release 正文 | 结构化：首行 `【v2.3.1 · 快门次数收口 + 玻璃收尾】` + `· ` 中文条目 + 百度网盘/夸克网盘链接 |
| 版本记录量 | 22 个 tag、21 个 GitHub Release（v0.1.0 / v0.1.1 有 log 无 release，个别 tag 无对应 release） |
| 站点日志口径 | 以 **GitHub Releases 为唯一数据源**（自动同步的前提）。README 里更细的散文式根因记录不进站点，避免两处口径漂移 |
| 仓库现状 | 无 `.github/`，无任何站点目录，**无 App 截图**，仅 `docs/donate-wechat-qr.png` |
| 下载渠道 | GitHub Releases / 夸克 `pan.quark.cn/s/d060f7f350e8` / 百度 `pan.baidu.com/s/1if5qoCaQRjzBlCThs_sfsQ?pwd=trub` |

Roadmap 未完成项（Phase 4 AI 修图、监看色彩与 LUT、iOS）**只能出现在 Roadmap 段的未勾选位**，不得进入功能介绍段。README 已明确其为「PRD 已完成、开发中」。

---

## 1. 目标与非目标

**目标**

1. 一屏读懂 N-Link 干什么、凭什么（稳/快/全/简），并从任意位置一步到达下载或 GitHub。
2. 科技感与现代动效达到 React Bits showcase 级别，同时首屏不被动效拖慢。
3. 版本号与更新日志**零人工维护**：发新 Release 后自动出现在站上。
4. 中英双语，中文为默认。
5. 视觉上延续 App v2.3 的「黑白极简 + 液态玻璃」，让网站和 App 像同一个产品。

**非目标**

- 不做文档站/多页知识库（深链仍回 README 与 `docs/`）。
- 不做下载分发后端（APK 仍由 GitHub/网盘承载，站点只做入口）。
- 不做数据库、账号、评论、访问统计面板（Sites 自带 analytics）。
- 不做 App 内页面的功能复刻或在线试用。

---

## 2. 架构总览

```
   GitHub Releases API  (repos/Wan-1230/N-link/releases)
            │
      ┌─────┴──────────────────────────┐
      │                                │
 ① 构建期快照                      ② 运行时补拉
 scripts/fetch-releases.mjs        Qoder Sites Function
        ↓                                 ↓ GET /api/releases
 src/data/releases.json            (PAT + 600s TTL 缓存)
        ↓                                 ↓
 Vite build → dist (静态)  ──────►  浏览器 useReleases()
                                       │
                              快照先渲染 → live 覆盖 → 失败静默留快照
```

浏览器永远**先有内容**（快照打进产物），再**变准**（live 覆盖）。任何一层挂掉都不产生空屏或报错弹窗。

---

## 3. 技术选型

| 层 | 选型 | 理由 |
|---|---|---|
| 构建 | Vite + React 19 + TS（`strict: true`） | 纯静态产物，部署链路最短；React 才能直接吃 React Bits |
| 样式 | 原生 CSS + 自定义属性 token，**不引 Tailwind** | 全站核心资产是自研玻璃材质层，与 App 的 token 一一对应；Tailwind 会与之打架且抬体积 |
| 动效（滚动） | GSAP 3 + ScrollTrigger + Lenis | 钉住式横向滚动叙事与平滑滚动的当前主流解 |
| 动效（组件） | **React Bits 组件以源码拷入** `src/components/reactbits/`，取其非 Tailwind / CSS Module 变体 | 需求点名 react.bit；拷源码而非装包，避免为一个组件背一整套依赖树 |
| i18n | 自建类型化字典 | 见 §9，漏译直接编译失败，无运行时体积 |
| 图 | 手写 SVG + CSS mock，无位图依赖 | 仓库无截图，见 §7 第 2 段 |

依赖上限：`react` `react-dom` `gsap` `lenis` + 构建期的 `vite` `typescript` `@vitejs/plugin-react`，Playwright 仅 dev。不加组件库、不加状态库、不加 UI framework。

---

## 4. 目录结构

```text
website/
├── index.html                      # meta / JSON-LD 构建期注入点
├── vite.config.ts                  # 含 transformIndexHtml 插件
├── package.json
├── tsconfig.json
├── functions/
│   └── releases.ts                 # Sites Function：GET /api/releases 代理
├── scripts/
│   ├── fetch-releases.mjs          # 生成 src/data/releases.json
│   └── make-og.mjs                 # Playwright 截 Hero → public/og.png
├── public/
│   ├── og.png  favicon.svg  robots.txt  sitemap.xml
│   └── donate-wechat-qr.png        # 从 docs/ 拷入
└── src/
    ├── main.tsx  App.tsx
    ├── styles/
    │   ├── tokens.css              # 颜色/间距/时长/缓动/字体
    │   ├── glass.css               # 玻璃材质：blur + 折射 + 自适应 tint
    │   └── base.css
    ├── components/
    │   ├── reactbits/              # SplitText BlurText ShinyText CountUp
    │   │                           # Magnet SpotlightCard GlareHover TiltedCard
    │   │                           # Aurora SilkyFloatingBalls ClickSpark StaggeredMenu
    │   └── ui/                     # GlassCard Nav LangToggle PhoneMock SectionTitle
    ├── sections/                   # 12 个 section，一文件一 section
    ├── hooks/
    │   ├── useReleases.ts          # 快照 + live 合并
    │   └── useReducedMotion.ts
    ├── i18n/
    │   ├── zh.ts  en.ts  index.ts  # en: Dict 由 zh 的形状约束
    ├── data/
    │   └── releases.json           # 构建期快照（自动生成，入库）
    └── lib/
        ├── release-feed.ts         # ReleasesFeed 类型 + 解析 + semver 比较
        └── format.ts               # 日期/数字 locale
```

拆分原则：每个 section 自包含、只依赖 `i18n` + `useReleases` + `ui`，不互相 import。改任一段不动其它段。

---

## 5. 数据契约与自动同步

### 5.1 类型

```ts
type ReleaseChannel = 'github' | 'quark' | 'baidu';

interface ReleaseEntry {
  tag: string;                 // "v2.3.1"
  version: string;             // "2.3.1"
  name: string;                // "N-Link v2.3.1"
  title: string | null;        // "快门次数收口 + 玻璃收尾"
  date: string;                // ISO8601
  bullets: string[];           // 已按条目拆好的中文要点
  apkUrl: string | null;       // assets 中首个 .apk 的 browser_download_url
  apkName: string | null;
  apkSize: number | null;      // bytes
  channels: Partial<Record<ReleaseChannel, string>>;
  prerelease: boolean;
  url: string;                 // release html
}

interface ReleasesFeed {
  generatedAt: string;         // ISO8601
  latestTag: string;           // 最新「正式版」，prerelease/draft 不计
  releases: ReleaseEntry[];    // 时间降序
  stars: number | null;        // 取不到则 null，UI 隐藏该指标
  source: 'snapshot' | 'live';
  stale: boolean;              // live 层回吐的过期缓存
}
```

快照文件与 Function 响应**共用这一个形状**，`useReleases()` 内部无分支。`source` 由产生方写入：快照文件写 `'snapshot'`，Function 写 `'live'`。

### 5.2 解析规则（精确，不留解释空间）

按顺序对 `release.body` 逐行处理：

| 目标字段 | 规则 |
|---|---|
| `version` / `title` | 首行匹配 `/^【v?(\d+\.\d+\.\d+)(?:\s*·\s*([^】]+))?】$/` → 组 1 = version，组 2 = title；不匹配则 `version` 从 `tag` 去掉前导 `v`，`title = null` |
| `bullets` | 每个匹配 `/^\s*[·\-•]\s+(.+)$/` 的行压入一条，去掉首尾空白；空数组合法 |
| `channels.quark` | `/^夸克网盘[：:]\s*(https?:\/\/\S+)/` |
| `channels.baidu` | `/^百度网盘[：:]\s*(https?:\/\/\S+)/` |
| 其它行 | **忽略**。不把未识别的散文塞进 UI，避免排版失控 |
| `apkUrl` | `assets.find(a => a.name.endsWith('.apk'))`；无则 `null`，下载按钮降级为「查看 Release 页」 |

排序 `published_at` 降序。`draft` 一律丢弃；`prerelease` **保留在列表里**（打上「预览」标），但 `latestTag` 只从非 prerelease 中取 —— 与 App 内「检查更新仅提示正式版」的既有行为对齐。

### 5.3 构建期快照 `scripts/fetch-releases.mjs`

- `GET https://api.github.com/repos/Wan-1230/N-link/releases?per_page=100`（当前 21 条，一页装得下）
- `stars` 另取 `GET https://api.github.com/repos/Wan-1230/N-link` 的 `stargazers_count`；该请求失败**不影响**日志，`stars` 写 `null`，导航栏指标位隐藏
- 有 `GITHUB_TOKEN` 环境变量则带 `Authorization: Bearer`，否则匿名
- 结果写 `src/data/releases.json`，`source: 'snapshot'`
- **失败不阻断构建**：旧快照存在 → 复用并在 stdout 打 warning；无快照 → 写 `{releases:[],latestTag:""}`，站点进入「日志暂缺」态，其余 11 段不受影响
- 该文件**入库**，让不看 GitHub 的读者 clone 下来也能 build 出完整站点

### 5.4 Sites Function `GET /api/releases`

契约（实现时按 sites-building 规范落文件，以下为必须满足的行为）：

| 情形 | 响应 |
|---|---|
| 正常 | `200` + `ReleasesFeed(source:'live')`，`Cache-Control: public, s-maxage=600, stale-while-revalidate=3600` |
| 上游失败但内存有旧值 | `200` + 旧值 + `stale: true`（stale-while-revalidate） |
| 上游失败且无旧值 | `502` + `{error:'upstream'}`，前端静默回退快照 |
| 非 GET | `405` |

- 上游为两跳（releases 列表 + repo 元信息取 stars），两跳并发发起；repo 元信息失败仅致 `stars: null`
- Secret `GITHUB_TOKEN` 可选；缺省走匿名（Function 是服务端出口，全站共享一份配额池，不再是每访客 60 次/小时）
- Function 无状态内存缓存只在同一实例内生效，**冷启动首次请求可能穿透到上游**，属预期。
- 启用 Functions 需 `ensure_backend` 分配云端资源 → 实施时**单独征求你的同意**，不默认开。

### 5.5 前端合并 `useReleases()`

```ts
{ feed: ReleasesFeed; status: 'snapshot' | 'live' | 'error'; refresh: () => void }
```

1. `t=0` 同步返回快照，首屏即刻有内容。
2. 后台 `fetch('/api/releases', { signal: AbortSignal.timeout(4000) })`。
3. 成功且 `latestTag !== feed.latestTag` → 整体替换，触发版本徽章的 CountUp/Shiny 过渡。
4. 成功但版本一致 → 仅更新 `source/generatedAt`，不动 DOM，避免闪一下。
5. 超时/非 2xx/形状不符 → 保持快照，**不弹 toast、不显示错误文案**。页脚一行小字「日志同步于 <generatedAt>」承载全部诚实性。
6. 整页一次请求，不做轮询。用户主动点日志段的「刷新」按钮才重新拉（`refresh()`）。

### 5.6 已接受的限制（明确记账）

Qoder Sites 没有 `release.published` webhook，也没有确认定时任务能力，因此同步是**请求驱动 + 10 分钟 TTL**，即「你发版后 ≤10 分钟站上自动可见」，不是秒级。秒级需要 GitHub Actions 事件触发，那与「云端托管」选型冲突，本设计不做妥协，按 10 分钟定版。快照层的新鲜度只在重新发布站点时刷新，真实新鲜度由 live 层负责 —— 两者职责不重叠。

---

## 6. 设计系统

延续 App 的 v2.3 语言，关键词：**黑白极简为体，玻璃为面，单一强调色为眼**。

| Token | 值 | 用途 |
|---|---|---|
| `--bg` | `#08090A` | 深空底 |
| `--bg-elev` | `#0E1011` | 段间起伏 |
| `--surface-glass` | `rgba(255,255,255,.045)` | 玻璃面 |
| `--hairline` | `rgba(255,255,255,.09)` | 1px 内描边 |
| `--fg` / `--fg-dim` | `#F2F3F4` / `#9BA0A5` | 正文 / 次级 |
| `--accent` | `#FFE100`（尼康黄） | 仅 CTA、active 态、版本徽章 |
| `--r` | `8px` | 与 App 圆角一致 |
| `--dur-1/-2/-3` | `.18s / .35s / .7s` | 微反馈 / 展开 / 段落入场 |
| `--ease-out-expo` | `cubic-bezier(.16,1,.3,1)` | 全站主缓动 |

- **玻璃材质**：`backdrop-filter: blur(20px) saturate(160%)` + 顶部高光渐变 + conic-mask 边缘折射。遵守从 App 带来的硬规则：**列表项与长文本容器一律不加实时模糊**（性能与可读性），只有导航、dock、卡片、弹窗用。
- **对比度兜底**：任何玻璃面文字达不到 WCAG AA 4.5:1 时，该面退回不透明底 —— 与 App v2.3.0 同一条规则，不做例外。
- **强调色纪律**：`--accent` 上的文字恒为 `#08090A`；正文永不上色。整页强调色像素占比 < 3%。
- **字体**：中文走系统栈（`PingFang SC / Microsoft YaHei / system-ui`）—— 中文 webfont 是 MB 级，直接否决。拉丁与数字用 Space Grotesk（子集，仅拉丁）；版本号、日期、代码走等宽 `JetBrains Mono`（子集）。
- **栅格**：`max-width: 1200px`，gutter 24/32，8px 基线。断点 390 / 768 / 1024 / 1440。

---

## 7. 信息架构（12 段）

导航锚点与 section 一一对应，`id` 即锚点名。

| # | `id` | 目的 | 内容来源 | 关键组件与动效 |
|---|---|---|---|---|
| 1 | `nav` | 任意位置可达下载/GitHub | — | 玻璃吸顶；滚出 Hero 后收缩；`StaggeredMenu` 移动菜单；语言切换；star 数 |
| 2 | `hero` | 30 秒说清是什么 | §0 + README 标语 | `Aurora`+`SilkyFloatingBalls` 背景；`SplitText`/`BlurText` 标题；`ShinyText` **实时版本徽章**；双 CTA（下载 APK / GitHub）+ `Magnet`；**CSS/SVG 手机活 mock**（`TiltedCard`，内含玻璃 dock、监看 HUD、两段式快门）；`ClickSpark` |
| 3 | `trust` | 建立可信 | README 机型/指标 | 机型带（Z50II/Z6III/Z8/Z9/Zf）；`CountUp` 指标：重连 `<3s`、监看 `<200ms`、快门 `46` 档、`40` 个原因码。** `<200ms` 在站上必须标为「目标值」**——README 原文是「目标 < 200ms @ WiFi 5GHz」，不是实测；`<3s` 重连同源，标「自动重连」。不给出无出处的实测口吻数字 |
| 4 | `pipeline` | 讲清完整链路 | README 功能特性 | ScrollTrigger **钉住横向滚动** 五步：连接→浏览→传输→遥控→监看 |
| 5 | `features` | 功能介绍主体 | README「功能特性」 | 三通道能力矩阵表 + 四组 `SpotlightCard`：相册传输 / 遥控拍摄 / Live View / 连接稳定性 |
| 6 | `why` | 凭什么选它 | README 稳/快/全/简 | 四象限 + 「与其它影像云方案的差异」表。**该表只陈述 N-Link 自身可证事实**（纯本地、不经服务器、无后台上传），**不对 SnapBridge / Image Space 作任何事实性负面断言**——我们没有可引用的对方口径，写了就是编。尼康 Image Space 停服一事仅在确有 README 依据处平实陈述，不做成叙事板块 |
| 7 | `how` | 使用说明 | README「快速开始」+ STA 教程 | 可交互 stepper：普通用户下载 → WiFi STA 三步（含「先注册主机」门控）→ 开发者构建；**失败提示对照表**（`fail_reason=1` 等）；复制命令按钮 |
| 8 | `changelog` | 更新日志（自动同步） | `ReleasesFeed` | 时间轴 `<ol>`；最新一条高亮并标「自动同步于 …」；折叠展开；每条挂 APK 与网盘直链；`refresh()` 按钮 |
| 9 | `roadmap` | 预期管理 | README Roadmap | 已勾选/未勾选两态；AI 修图与 LUT 明确标「开发中/待开发」 |
| 10 | `tech` | 开源项目可信度 | README 技术栈/结构 | 技术栈表 + 模块结构图（device/camera/capture/settings/shared，SVG 绘制） |
| 11 | `download` | 收口转化 | §0 | 渠道矩阵（GitHub Releases / 夸克 / 百度）+ App 内更新说明；反馈入口 = **GitHub Issues**（Discussions 未启用，不放该链接）+ App 内反馈说明；捐赠微信二维码 |
| 12 | `footer` | 免责与诚实 | README 许可段 | **与尼康无关联**声明；**仓库尚无 LICENSE**、勿用于商业分发；同步时间戳 |

Hero 用活 mock 而非截图，是因为仓库无截图而落地页无图即塌。mock 用 CSS/SVG 复刻 App 真实界面元素，之后拿到真机截图可整块替换 `<PhoneMock>` 而不改布局。

---

## 8. 动效方案

**React Bits（拷入源码）**：`SplitText` `BlurText` `ShinyText` `CountUp` `Magnet` `SpotlightCard` `GlareHover` `TiltedCard` `Aurora` `SilkyFloatingBalls` `ClickSpark` `StaggeredMenu`。逐个只取所需 props，不改其行为。

**滚动层**：Lenis 全站平滑滚动（`prefers-reduced-motion` 下关闭）；ScrollTrigger 驱动 ① 段背景视差 ② `pipeline` 钉住横滚 ③ 各段 `IntersectionObserver` 分层 reveal（错峰 ≤ 80ms）；顶部滚动进度条。

**微交互**：CTA 磁吸 + 按压回弹（对应 App 的 `PressEffect`）；导航下划线液态滑动（对应 App 的液态胶囊）；表格行 hover 高亮；复制成功就地变勾选再复原，不弹提示。

**性能约束**：ScrollTrigger 只注册视口内元素；背景 canvas 组件在 `hardwareConcurrency ≤ 4` 或命中 reduced-motion 时降级为 CSS 渐变；移动端砍掉折射层，保留 blur。

---

## 9. 国际化

```ts
export type Dict = typeof zh;
export const en: Dict = { /* ... */ };   // 漏译 → 编译错误
```

- 中文默认；切英文写 `localStorage.nl-lang` + 同步 `<html lang>` + **保持当前锚点不跳**。
- **更新日志正文不翻译**：它来自 GitHub Release 的中文原文，是事实数据，机翻会篡改技术表述。英文版该段整体保留中文，只翻译框架文案（「最新版」「自动同步于」「下载」）并在段首给一句英文说明。
- 日期与数字走 `Intl` + 当前 locale。

---

## 10. SEO 与元数据

- `index.html`：静态 title/description 打底，Vite `transformIndexHtml` 插件读快照，把 `latestTag` 与日期写进 `<title>`、`description`、`<meta name="version">`。
- JSON-LD：`SoftwareApplication`（`softwareVersion` / `dateModified` / `downloadUrl` / `operatingSystem=Android`）+ `Release`。
- `robots.txt` + `sitemap.xml`（单 URL）。OG/Twitter card；**`og.png` 由 `scripts/make-og.mjs` 用 Playwright 截 Hero 生成 1200×630**，不引入图像处理依赖。
- 站建好后把 Sites 域名回填到 GitHub 仓库 homepage 字段（一次 `gh repo edit`，需你确认）。

---

## 11. 可访问性与降级

AA 4.5:1（含玻璃面，不达即转不透明底）；触控目标 ≥ 48px；skip-link；焦点环不隐藏；`prefers-reduced-motion` 关闭所有位移与 canvas；背景 canvas/装饰 SVG `aria-hidden="true"`；时间轴用有序列表语义；`CountUp` 的最终值以文本形式留在 DOM 供读屏；浏览器字号放大 200% 不截断（复刻 App 大字档经验）。控制台零 error。

---

## 12. 测试与验收

| 项 | 判据 |
|---|---|
| 类型 | `tsc --noEmit` 干净 |
| 构建 | `vite build` 成功；首屏 JS gzip < 200KB（动效依赖占大头，超了先削 canvas 组件） |
| 解析 | `lib/release-feed.ts` 单测：用 v2.3.1 真实 body 作夹具，断言 `title`/`bullets.length`/`channels` 三项；再造畸形 body 断言不抛错且降级 |
| 降级 | mock `/api/releases` 超时 → 快照完整渲染、无错误 UI、页脚时间戳仍在 |
| 视觉 | **Playwright 真截图**：12 段 × (1440, 390) 两档全出图并逐屏核对；断言 Hero 版本徽章 == `feed.latestTag` |
| 动效 | reduced-motion 开/关各截一组对照 |
| 指标 | Lighthouse（移动端节流）Performance ≥ 85、Accessibility ≥ 95、Best Practices ≥ 90、CLS < 0.1 |

按既有约定，UI 验收**必须交真截图**，不以「类型和构建通过」代替「界面正确」。

---

## 13. 实施分期（能力全要，靠拆阶段控风险）

| 阶段 | 内容 | Done 判据 |
|---|---|---|
| **P1** | 脚手架、tokens/glass 材质、i18n 骨架、导航、Hero（含手机 mock 与 Aurora） | 本地起站，中英可切，Hero 真截图过 |
| **P2** | 快照脚本、`release-feed` 解析 + 单测、`useReleases`、Function 代理、changelog 段接通、构建期 meta 注入 | 改 GitHub 测试 body 后 ≤10 分钟站上反映；断网降级验过 |
| **P3** | 其余 9 段内容全量（trust/pipeline/features/why/how/roadmap/tech/download/footer） | 12 段齐全，双语文案齐，无占位文字 |
| **P4** | 动效打磨、a11y、Lighthouse、`make-og`、12×2 截图验收、`prepare_site`→`publish_site` 上线 | 指标达标 + 交付截图集 + 线上 URL |

P2 之后任何时刻站上都是可看的，不存在「半站」。

---

## 14. 风险与对策

| 风险 | 对策 |
|---|---|
| Sites Function 的文件声明/路由写法未在文档中确认 | 实现前读 sites-building 规范；Function 不可用则整站退回纯快照模式（§5.3 已设计），仅新鲜度降级，不出故障 |
| 无真机截图 | `<PhoneMock>` 抽象成可替换单元，接口留好 |
| 仓库无 LICENSE | 页脚按 README 原文写「尚无许可证、勿商业分发」，**不**写 MIT/Apache |
| GitHub 匿名限流 60/h/IP | 走 Function 代理；快照兜底 |
| 中文长文案小屏溢出 | 390 档全段截图验收，不抽查 |
| 当前 worktree 处于 detached HEAD | 写代码前先建分支（`feat/website`），否则提交会成游离 commit |
| 夸大宣传 | 站上每条功能表述须能指回 README 或代码；未完成项只进 Roadmap |

---

## 15. 部署

`vite build` → `prepare_site`（打包上传不可变草稿）→ 校验 `canPublish` → `publish_site` → 复核 `published=true` → `show_publish_confirmation` 给链接。

两步需你单独点头，不默认执行：① `ensure_backend` 分配 Functions 云资源；② 访问策略设为公开（`public` + `confirmPublic`）。
