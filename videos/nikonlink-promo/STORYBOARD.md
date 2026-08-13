---
format: 1920x1080
duration: 60s
message: "一次配对、永不断联——尼康相机的手机伴侣，就该这么稳"
arc: 痛点钩子 → 痛点共鸣 → 品牌亮相 → 连接演示 → 传图演示 → 遥控演示 → 数据信任 → 引流 CTA
audience: 尼康 Z 系列用户、摄影新手、Android 开发者
mode: collaborative
music: none  # 离线无 HeyGen 凭证，BGM 检索不可用；发布时建议在剪映/抖音叠加：轻电子极简科技 ~120BPM、无 vocals
---

## Video direction

- palette system（源自 frame.md）：canvas #FFFFFF · ink #000000 · 内黑卡 #1A1A1A/#262626 · 灰阶 #525252/#8C8C8C/#A3A3A3 · 发丝线 #EBEBEB · 次级面 #F5F5F5；全片无彩色强调（单色纪律），状态仅用灰阶与黑白反转表达。
- type roles：display = Inter Tight 800 + Noto Sans SC 900（大字/字标）；body = Inter + Noto Sans SC 400/500（界面与字幕）；mono = IBM Plex Mono（编号、STEP、数据读数、仓库行）；数字 tabular-nums。
- stage grammar：1920x1080 白舞台；手机 mock 固定左 44% 列（圆角 40px、1px #262626 描边，屏内 1:1 还原 app 布局）；右 56% 放字幕/STEP 编号/数据读数；全部内容置于上 83%，底部 17% 留给字幕药丸。
- caption skin：居中黑药丸（radius 999px）白 body 字，逐 cue 按时间硬切+60ms 淡入切换；暗底镜头（F3 前半）反转为白药丸黑字。
- motion grammar：power3 长尾缓动为基；入场 spring-pop（卡片/胶囊/勾选）； inplace 文字硬切词交换；进度条/计数 linear→power2；所有点击带 4% press-release 回弹；hold 期间无镜头漂移（stillness is the payload），至多一次极轻 jitter。
- rhythm / holds：F2 逐句静持 1.5s+ 作节奏鼓点；F4/F5 末 1.5-2s 静持确认结果；F7 末 2s 静持；F8 尾 1.5s 全静收帧；其余帧按 cue 持续揭示，避免均匀繁忙。
- negative list：无渐变/bokeh/紫蓝 AI 感；无镜头光晕、无粒子、无 emoji；无尼康商标 Logo；无彩色状态点；无真实浏览器 chrome（手机 mock 属刻意 UI 重建，豁免）；禁止两种失败模式——slideshow（front-load 后冻结）与 screensaver（各自漂浮）。

## Frame 1 — 钩子：断连三连击

- scene: 纯白画布上超大黑体字逐拍砸入：「断连。」「重连。」「再断连。」，最后一行小字反问浮现
- voiceover: "断连。重连。再断连。你的相机 App，也这样？"
- duration: 5s
- poster: 3.5s
- transition_in: cut
- status: animated
- src: compositions/frames/01-hook.html
- type: hook
- persuasion: Pain validation
- beat: frustration → curiosity
- blueprint: kinetic-type-beats (Reproduce)
- asset_candidates: 

narrativeRole: 开场 3 秒制造痛点共鸣与悬念，用观众自己的糟糕经历抓住注意力。
keyMessage: 现有相机 App 的连接体验糟糕透顶。
字幕 cues: [0.0-1.2s] 断连。 / [1.2-2.4s] 重连。 / [2.4-3.6s] 再断连。 / [3.6-5.0s] 你的相机 App，也这样？
画面: 1920x1080 纯白舞台；黑体 900 大字居中逐词硬切替换（词交换即动效），第三拍文字轻微放大砸落；反问行以 1px 发丝线下划线滑入。无产品、无 logo，只有文字张力。
Shot:
Scene 1 (0.0–1.2s): 纯白画布，仅「断连。」display 900 居中 spring-pop 落版（Centered，单深度层，字高约 18% 画布）；左上 mono 灰字 01 同拍滴入。
Scene 2 (1.2–2.4s): inplace 硬切词交换为「重连。」，4% 尺度回弹；编号步进 02。
Scene 3 (2.4–3.6s): 硬切「再断连。」以更重一级 drop（y+2% 沉降）落版；编号 03。
Scene 4 (3.6–5.0s): 大字上滑清场；反问「你的相机 App，也这样？」body 500 滑入，1px 发丝下划线左→右画线；字幕药丸同 cue 落底；静止持守至切点。

## Frame 2 — 痛点：三句补刀

- scene: 三句痛点陈述依次单独落版，每句独占纯白画布，灰色小字编号递增
- voiceover: "配对十分钟，拍照五分钟。传一张图，重启一次 App。相机不敢关机，手机不敢走远。"
- duration: 6s
- poster: 3s
- transition_in: crossfade
- status: animated
- src: compositions/frames/02-pain.html
- type: pain_point
- persuasion: Pain agitation
- beat: frustration + overwhelm
- blueprint: kinetic-type-beats (Reproduce)
- asset_candidates: 

narrativeRole: 把钩子的共鸣具体化为三个真实场景，积蓄「该换了」的情绪。
keyMessage: 旧体验的摩擦无处不在。
字幕 cues: [0-2s] 配对十分钟，拍照五分钟。 / [2-4s] 传一张图，重启一次 App。 / [4-6s] 相机不敢关机，手机不敢走远。
画面: 每句黑体主文案 spring-pop 落版后静止 1.5s+，左上角 IBM Plex Mono 灰字编号 01/02/03 步进；句间硬切，节奏 2s/句。
Shot:
Scene 1 (0.0–2.0s): 白底；左侧 mono 灰编号 01 钉住，句一 display 800 居左 70/30 非对称落版后静持 1.5s+（held beat）。
Scene 2 (2.0–4.0s): 硬切；编号 02 + 句二同式落版静持。
Scene 3 (4.0–6.0s): 硬切；编号 03 + 句三落版；左侧编号列 01/02/03 累积成小轨（grid 深度层），全画面静持收束。

## Frame 3 — 品牌亮相：NikonLink 登场

- scene: 黑色画布上发丝线网格收敛，"NikonLink" 字标自组装，翻转为白底黑字并落出标语；手机框从右侧滑入承载下一幕
- voiceover: "NikonLink。一次配对，永不断联。"
- duration: 6s
- poster: 4.5s
- transition_in: zoom-through
- status: animated
- src: compositions/frames/03-intro.html
- type: product_intro
- persuasion: Negative contrast（与上一幕痛点形成黑白反转）
- beat: clarity + confidence
- blueprint: logo-assemble-lockup (Adapt)
- asset_candidates: 

narrativeRole: 痛点之后给出答案与品牌名，黑白反转制造「新世界」的仪式感。
keyMessage: NikonLink = 一次配对、永不断联。
字幕 cues: [0-2.5s] NikonLink / [2.5-6s] 一次配对，永不断联。
画面: 黑底（#000）上 1px 灰线网格向中心收敛 → 字标 Inter Tight 800 白色逐字母 cascade 落位 → 整屏黑白反转（zoom-through 进入白底）→ 标语黑字滑入；右侧手机框（圆角 40px、1px 描边）携 Tab1 设备页滑入待命。合规：仅文字字标，无尼康商标。
Shot:
Scene 1 (0.0–2.0s): 黑场（layered-depth：1px 灰发丝网格自四边向中心收敛）；「NikonLink」白 display 800 逐字母 cascade 落位（签名动作：字标自组装）。
Scene 2 (2.0–3.5s): 签名反转：整屏黑白一次扫换（黑→白），字母同拍翻为 ink；标语「一次配对，永不断联。」body 500 自下滑入字标下方；字幕药丸反转为白底黑字再回黑药丸。
Scene 3 (3.5–6.0s): 字标缩为左上角 lockup；手机框（圆角 40px、1px ink 描边）自右缘滑入左 44% 列，屏内 Tab1 设备页静态待命（dim 10%）；静止持守。

## Frame 4 — 演示一：十秒连接

- scene: 手机框内 Tab1 设备页 1:1 还原：点「扫描相机」→ 发现 Z50II → 点「连接相机」→ 纯黑设备卡状态点变实、相机名/电量/存储行依次滑入
- voiceover: "打开 App，选连接方式。扫描，发现你的相机。点连接——十秒，稳了。"
- duration: 10s
- poster: 8s
- transition_in: crossfade
- status: animated
- src: compositions/frames/04-connect.html
- type: feature_showcase
- persuasion: Friction reduction + Show-don't-tell proof
- beat: ease + control
- blueprint: device-surface-showcase (Reproduce)
- asset_candidates: 

narrativeRole: 用真实界面完成核心循环，证明「新手友好、一步到位」。
keyMessage: 三步十秒，连接从未如此简单。
字幕 cues: [0-3s] 打开 App，选连接方式。 / [3-5.5s] 扫描，发现你的相机。 / [5.5-8s] 点连接—— / [8-10s] 十秒，稳了。
画面: 16:9 舞台左侧手机框（竖屏 mock），右侧留白放动态字幕与步骤编号（Mono 灰字 STEP 1/2/3）。界面按真实布局还原：顶部「我的设备」+ 发丝线；药丸 Tab（WiFi AP 选中黑底白字）；教程卡；「扫描相机」描边按钮 + 「连接相机」纯黑按钮。光标圆点（12px 黑圆）点击扫描 → 按钮微缩回弹 → 已发现列表弹出一行「Z50II」；光标点击连接 → 纯黑设备卡内状态点由灰变黑、progress 环转 0.8s → 「Nikon Z50II」24sp 黑体亮起，电量 82% / 存储 64% 进度条 / 剩余可拍 1284 行依次 120ms 阶梯滑入。微动效：仅元素级 spring，无镜头炫技。
Shot:
Scene 1 (0.0–3.0s): 手机框已就位于左 44% 列，屏内 Tab1：顶栏「我的设备」+ 发丝线、药丸 Tab（WiFi AP 黑底白字选中）、教程卡、双按钮卡；右侧 mono 灰字 STEP 1 滴入；光标黑圆自右滑入悬停「扫描相机」；字幕 cue 1。
Scene 2 (3.0–5.5s): 光标按压「扫描相机」（4% press-release）；已发现列表 spring-pop 弹出一行「Z50II」；STEP 2 步进；字幕 cue 2。
Scene 3 (5.5–8.0s): 光标按压「连接相机」纯黑按钮；黑设备卡内 progress 环旋 0.8s，状态点灰→黑；STEP 3；字幕 cue 3。
Scene 4 (8.0–10.0s): 设备卡行阶梯滑入（120ms stagger）：「Nikon Z50II」display 24sp 亮、电量 82%、存储 64% 进度条、剩余可拍 1284；右侧 STEP 3 翻为勾；字幕「十秒，稳了。」；静持 1.5s+ 收束。

## Frame 5 — 演示二：全速传图

- scene: 手机框切到 Tab2 相机照片页：3 列网格缩略图阶梯式填充（单色剪影占位），多选两张 → 悬浮操作栏升起 → 点下载 → 顶部 3px 进度条走满 → 角标打勾
- voiceover: "整卡照片，缩略图秒级加载。勾选，下载——WiFi 5GHz，10MB/s 直达手机。"
- duration: 10s
- poster: 7s
- transition_in: push-slide LEFT
- status: animated
- src: compositions/frames/05-transfer.html
- type: feature_showcase
- persuasion: Show-don't-tell proof + Feature-to-benefit translation
- beat: excitement + ease
- blueprint: cursor-ui-demo (Reproduce)
- asset_candidates: 

narrativeRole: 展示「快」与「实用」：浏览、多选、下载一气呵成。
keyMessage: 选图下载，全速直达，无需等待。
字幕 cues: [0-3s] 整卡照片，缩略图秒级加载。 / [3-6s] 勾选，下载—— / [6-10s] WiFi 5GHz，10MB/s 直达手机。
画面: 手机框内 Tab2：顶栏「相机照片」+ 多选胶囊；分类胶囊行（全部/JPEG/RAW/视频）；3 列方形网格以 60ms 阶梯 spring-pop 填充 9 格单色剪影占位（山脊/街影/人像侧影 SVG，灰阶 duotone）。光标点「多选」→ 两格左上黑圆勾选弹入 → 底部悬浮栏（下载/删除/分享/全选）自下滑入 → 光标点下载 → 网格上方 3px 黑色进度条 0→100%（2.2s，缓动）→ 两格右上角打勾翻转 → 右侧舞台数字读数「10.4 MB/s」Mono 字计数跳升。
Shot:
Scene 1 (0.0–3.0s): 屏内切 Tab2：顶栏 + 分类胶囊行先落；3 列网格 9 格单色剪影占位以 60ms 阶梯 spring-pop 填充（grid 密度层）；字幕 cue 1。
Scene 2 (3.0–6.0s): 光标点「多选」→ 两格左上黑圆勾选 spring-pop；底部悬浮栏（下载/删除/分享/全选）自下滑入；光标按压下载；字幕 cue 2。
Scene 3 (6.0–8.5s): 网格上方 3px ink 进度条 0→100%（2.2s linear→power2）；右舞台 mono 读数 0→10.4 MB/s 计数跳升；字幕 cue 3。
Scene 4 (8.5–10.0s): 两格右上打勾翻转；悬浮栏下滑收起；满网格静持收束。

## Frame 6 — 演示三：手机即取景器

- scene: 手机框切到 Tab3 拍摄页：取景区亮起单色山景，触摸对焦框跟随点击收缩锁定，拨动 ISO 拨盘读数实时跳变，按下快门 → 全屏闪白 → 已拍计数 +1
- voiceover: "手机就是你的取景器。触摸对焦，远程快门，参数指尖直调——延迟低于 0.2 秒。"
- duration: 10s
- poster: 7.5s
- transition_in: push-slide LEFT
- status: animated
- src: compositions/frames/06-remote.html
- type: feature_showcase
- persuasion: Show-don't-tell proof
- beat: power + control
- blueprint: panel-edit-live-sync (Adapt)
- asset_candidates: 

narrativeRole: 展示「全」：取景、对焦、参数、快门闭环，手势与画面实时耦合。
keyMessage: 遥控拍摄，所见即所得，低于 0.2 秒。
字幕 cues: [0-3s] 手机就是你的取景器。 / [3-5.5s] 触摸对焦，远程快门， / [5.5-8s] 参数指尖直调—— / [8-10s] 延迟低于 0.2 秒。
画面: 手机框内 Tab3：照片/视频药丸；取景区（2/3 高）淡入单色山景图（SVG 剪影 + 渐变），顶部 scrim 信息条（电量/剩余/已连接）；光标点击画面右侧 → 56px 对焦框落位收缩一次并锁定（白描边）；下方参数区 ISO 拨盘 +1 档 → 读数「400→800」翻牌跳变（手势→读数实时镜像，panel-edit-live-sync 签名动作）；光标按纯黑快门圆钮 → 取景闪白 120ms → 顶部「已拍 1」计数 +1 弹跳。右侧舞台同步放大显示「<200ms」Mono 大字一次。
Shot:
Scene 1 (0.0–3.0s): 屏内 Tab3：照片/视频药丸落位；取景区淡入单色山景（SVG 剪影 + 灰渐变，layered-depth），顶部 scrim 信息条（电量/剩余/已连接）滴入；字幕 cue 1。
Scene 2 (3.0–5.5s): 光标点击取景右侧三分点 → 56px 白描边对焦框落位收缩一次锁定；字幕 cue 2。
Scene 3 (5.5–8.0s): 参数区：光标拨 ISO 一档 → 读数 400→800 同拍翻牌（手势→镜像耦合，签名动作）；光标按压纯黑快门圆钮 → 取景闪白 120ms → 「已拍 1」计数 +1 弹跳；字幕 cue 3。
Scene 4 (8.0–10.0s): 右舞台「<200ms」mono 大字 spring-pop 一次后静持；字幕 cue 4；收束。

## Frame 7 — 信任：数字说话

- scene: 白底上四枚数据卡阶梯自组装：重连 <3s / 后台存活 99%+ / 传输 10MB/s / Live View <200ms，数字滚动计数落定
- voiceover: "断联？3 秒内自动重连。后台存活 99% 以上——你只管拍，剩下的交给它。"
- duration: 7s
- poster: 5s
- transition_in: zoom-through
- status: animated
- src: compositions/frames/07-proof.html
- type: social_proof
- persuasion: Statistical proof
- beat: trust + confidence
- blueprint: dataviz-countup (Adapt)
- asset_candidates: 

narrativeRole: 用 PRD 硬指标把「稳」量化，建立信任。
keyMessage: 稳定不是口号，是数字。
字幕 cues: [0-2.5s] 断联？3 秒内自动重连。 / [2.5-5s] 后台存活 99% 以上—— / [5-7s] 你只管拍，剩下的交给它。
画面: 2x2 数据卡（12dp 圆角、1px 发丝描边）以 150ms 阶梯 spring 落位；卡内数字 Mono 计数滚动（3s/99%/10MB/s/200ms）后静止；最后一句字幕落版时四卡整体轻微 zoom-out 收进版心。
Shot:
Scene 1 (0.0–2.5s): 白底；2x2 发丝描边数据卡以 150ms 阶梯 spring 落位（grid，主视觉 ≥40%）；卡一数字 0→3 计数滚动与字幕 cue 1 同拍。
Scene 2 (2.5–5.0s): 其余读数按 cue 节拍滚动落定：99% / 10 / 200（tabular-nums）；字幕 cue 2。
Scene 3 (5.0–7.0s): 四卡整体极轻 zoom-out 收进版心；字幕 cue 3 落版；静持 2s 收束。

## Frame 8 — CTA：永远在线

- scene: 字标居中凝缩为一枚纯黑药丸按钮「下载体验」，光标落点按下回弹；下方落出搜索/仓库指引行，画面静止收尾
- voiceover: "NikonLink——让你的相机，永远在线。现在下载体验。"
- duration: 6s
- poster: 4.5s
- transition_in: crossfade
- status: animated
- src: compositions/frames/08-cta.html
- type: cta
- persuasion: Risk reversal + urgency-to-act
- beat: motivation + peace of mind
- blueprint: cta-morph-press (Reproduce)
- asset_candidates: 

narrativeRole: 品牌收束 + 明确行动指令，完成种草闭环。
keyMessage: 现在下载 NikonLink，让相机永远在线。
字幕 cues: [0-2.5s] NikonLink——让你的相机，永远在线。 / [2.5-6s] 现在下载体验。
画面: 白底；「NikonLink」字标居中 → 字母向中心凝缩 morph 为纯黑药丸按钮「下载体验」（cta-morph-press 签名动作）→ 光标圆点移入按下（按钮 4% 缩回 + 发丝涟漪）→ 按钮下方 Mono 灰字落出「GitHub 搜索 Wan-1230/N-link」；最后 1.5s 全画面静止持守至尾帧。
Shot:
Scene 1 (0.0–2.5s): 白底；「NikonLink」字标居中；字母向中心凝缩 morph 为纯黑药丸按钮「下载体验」（签名动作）；字幕 cue 1。
Scene 2 (2.5–4.5s): 光标黑圆移入按压（4% 缩回 + 1px 发丝涟漪）；按钮下 mono 灰字「GitHub 搜索 Wan-1230/N-link」打字落出；字幕 cue 2。
Scene 3 (4.5–6.0s): 全画面静止持守至尾帧（held end card）。
