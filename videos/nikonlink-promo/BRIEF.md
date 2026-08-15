---
workflow: product-launch-video
flow: automation
storyboard: yes
message: "一次配对、永不断联——尼康相机的手机伴侣，就该这么稳"
destination: youtube
aspect: 1920x1080
language: zh
length: 60s
angle: 痛点反差（官方应用频繁断联）→ 新手友好/功能实用/高效便捷三价值闭环
audience: 尼康 Z 系列用户、摄影新手、Android 开发者
---

## Intent

为 N-Link（尼康 Z 系列相机 Android 连接/遥控应用）制作一支约 60 秒的官方宣传种草视频，面向普通终端用户与开发者用户，核心目的是吸引下载体验与长期使用。风格取「简约科技 / 极简商务」：动态微动效、界面演示真实还原软件操作、无浮夸特效、沉浸式展示使用体验。结构遵循完整种草闭环：开头悬念/痛点引入 → 中段功能价值展示（一键连接、照片传输、遥控拍摄）→ 结尾引流引导。

## Assets

- app/src/main/res/values/colors.xml — 应用真实设计令牌（纯黑白单色系：#FFFFFF 画布 / #000000 墨色 / #525252、#8C8C8C 灰阶 / #EBEBEB 发丝线），视频画面品牌色以此为准
- app/src/main/res/layout/*.xml — 四个真实界面（设备/相机照片/拍摄/设置）的结构蓝本，视频内以 HTML mock 1:1 还原演示

## Customizations

- 字幕 + BGM 方案，无配音；字幕为中文动态字幕；BGM 风格：轻电子 / 极简科技、120BPM 左右、无强烈人声
- 无网站可抓取，走 no-capture 路径：界面演示用 HTML 高保真 mock 重建（手机框内竖屏界面 + 16:9 舞台构图）

## Notes

- 环境离线且 HeyGen 未登录：BGM 先尝试本地 MusicGen，若不可用则交付无音轨成片，并在交付说明中告知发布时在剪映/抖音添加推荐风格 BGM
- 合规：不使用尼康商标/Logo，仅出现 "N-Link" 文字与通用相机图形；相机型号文字（如 Z50II）仅作描述性演示
- 用户确认：16:9 横屏、约 60 秒、字幕+BGM
