# Asset inventory — NikonLink promo

No web capture was performed (no-capture path: the product is a local Android app, not a website).
All on-screen product visuals are high-fidelity HTML mocks rebuilt 1:1 from the app's real layout XML
(`app/src/main/res/layout/*.xml`) and design tokens (`app/src/main/res/values/colors.xml`),
rendered inside a phone frame on the 1920x1080 stage.

## Rebuilt screens (as video assets)

- screen-device.html-mock — Tab1 设备页：模式药丸 Tab、教程卡、扫描/连接按钮、纯黑设备核心卡片、2x2 快捷网格
- screen-album.html-mock — Tab2 相机照片页：3 列影像网格、分类胶囊、多选悬浮栏、下载进度条
- screen-remote.html-mock — Tab3 拍摄页：实时取景区（scrim 信息条、对焦点、快门闪白）、参数控制区
- screen-settings.html-mock — Tab4 设置页：参数列表（光圈/快门/ISO/白平衡/对焦）

## Graphic assets

- Brand wordmark: text "NikonLink" set in Inter Tight 800 (no Nikon trademark/logo used)
- Generic camera glyph: simple stroke camera icon (matches app ic_nav_camera style)
- Photo grid stand-ins: monochrome gradient/duotone placeholder "photos" (landscape/street/portrait silhouettes) generated as CSS/SVG, not stock photos

## Audio

- No narration (user chose 字幕+BGM)
- BGM mood: light minimal-tech electronic, ~120 BPM, no vocal hooks; local MusicGen attempt, fallback silent + publish-time music note
