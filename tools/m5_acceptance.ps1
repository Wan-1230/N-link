<#
.SYNOPSIS
NikonLink AI 修图 M5 验收一键脚本（PRD-AI修图 §10.1 / §10.2）

.DESCRIPTION
在已连接真机上自动执行：
  1. 安装 debug APK（可 -SkipInstall 跳过）
  2. 推送测试照片到应用私有外部目录（免存储权限，应用可直接读取）
  3. 清空日志后启动编辑器（携带来源 URI 与文件名）
  4. 抓取耗时打点（PRD 9.1: 编辑器打开 <1s、预览渲染耗时、全分辨率渲染耗时）
  5. 截屏取证保存至 acceptance/ 目录

脚本无法代劳的手动验收项（对照 PRD §10.1 清单逐项勾选）：
  - 滑杆拖动实时性（<100ms 体感）、长按对比、分屏对比拖动分割线
  - 撤销/重做 ≥20 步、一键增强/一键修复/场景/滤镜全流程
  - 保存对话框（另存/覆盖 + 画质选择）、覆盖前备份与 SAF 授权弹窗
  - 未保存退出拦截三选项
  - 抓包确认处理过程零网络（静态审计已通过：唯一网络代码为
    ModelRegistry 按需下载路径，当前占位地址不可达）

.PARAMETER Device
adb 设备序列号（多设备时必填，单设备可省略）

.PARAMETER Apk
APK 路径，默认 app/build/outputs/apk/debug/app-debug.apk

.PARAMETER Image
测试照片路径，默认使用 .tmp_bili/frame_2064.jpg（存在时）

.EXAMPLE
.\tools\m5_acceptance.ps1
.\tools\m5_acceptance.ps1 -Device R58N123ABC -Image D:\photos\test_24mp.jpg
#>
param(
    [string]$Device = "",
    [string]$Apk = "",
    [string]$Image = "",
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$pkg = "com.nikonlink.app.debug"
$component = "$pkg/com.nikonlink.app.feature.edit.EditActivity"
$deviceImgPath = "/storage/emulated/0/Android/data/$pkg/files/nl_acceptance_test.jpg"
$deviceImgUri = "file://$deviceImgPath"

# ---------- 工具与设备 ----------
function Resolve-Adb {
    $candidates = @(
        (Get-Command adb -ErrorAction SilentlyContinue).Source,
        "D:\Android\Sdk\platform-tools\adb.exe",
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    )
    foreach ($c in $candidates) { if ($c -and (Test-Path $c)) { return $c } }
    throw "未找到 adb，请安装 platform-tools 或修正路径"
}

$adb = Resolve-Adb
function Adb { param([string[]]$Args2) & $adb @Args2 }
function AdbShell { param([string]$Cmd) & $adb $(if ($Device) { @("-s", $Device) } else { @() }) shell $Cmd }

if (-not $Device) {
    $online = (& $adb devices | Select-String "device$")
    if ($online.Count -eq 0) { throw "没有在线设备：请先连接真机并授权 USB 调试" }
    if ($online.Count -gt 1) { throw "多台设备在线：请用 -Device 指定序列号" }
}

Write-Host "[1/6] 设备就绪" -ForegroundColor Green

# ---------- 安装 ----------
if (-not $SkipInstall) {
    if (-not $Apk) { $Apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk" }
    if (-not (Test-Path $Apk)) { throw "APK 不存在: $Apk（先执行 assembleDebug）" }
    Write-Host "[2/6] 安装 APK: $Apk"
    $installArgs = @("install", "-r", "-d", $Apk)
    if ($Device) { $installArgs = @("-s", $Device) + $installArgs }
    $out = & $adb @installArgs
    if ($out -notmatch "Success") { throw "安装失败: $out" }
} else {
    Write-Host "[2/6] 跳过安装" -ForegroundColor Yellow
}

# ---------- 推送测试照片 ----------
if (-not $Image) {
    $defaultImg = Join-Path $root ".tmp_bili\frame_2064.jpg"
    if (Test-Path $defaultImg) { $Image = $defaultImg }
}
if (-not $Image -or -not (Test-Path $Image)) {
    throw "缺少测试照片：请用 -Image 指定一张 JPG（建议 24MP 原片以覆盖 PRD 9.1 指标）"
}
# 确保应用外部私有目录存在（应用可免权限读取）
AdbShell "mkdir -p /storage/emulated/0/Android/data/$pkg/files" | Out-Null
$pushArgs = @("push", $Image, $deviceImgPath)
if ($Device) { $pushArgs = @("-s", $Device) + $pushArgs }
& $adb @pushArgs | Out-Null
Write-Host "[3/6] 测试照片已推送: $deviceImgPath"

# ---------- 启动编辑器并采集日志 ----------
AdbShell "logcat -c" | Out-Null
$amCmd = "am start -n $component --es source_uri `"$deviceImgUri`" --es source_name `"nl_acceptance_test.jpg`""
AdbShell $amCmd | Out-Null
Write-Host "[4/6] 编辑器已启动，等待加载与预览渲染…"
Start-Sleep -Seconds 6

# 耗时打点（PRD 9.4 日志规范: EditEngine/EditVM 标签）
$logs = AdbShell "logcat -d -s EditEngine:V EditVM:V TfLiteRuntime:V AiEnhancer:V"
Write-Host "----- 耗时打点 -----" -ForegroundColor Cyan
$timing = $logs | Select-String "in \d+ms|loaded|Source|Enhance|thumbnails"
if ($timing) { $timing | ForEach-Object { Write-Host $_.Line } } else { Write-Host "（未捕获到打点，请确认 Activity 正常启动）" -ForegroundColor Yellow }

# ---------- 截屏取证 ----------
$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$remote = "/sdcard/nl_acceptance_$stamp.png"
AdbShell "screencap -p $remote" | Out-Null
$outDir = Join-Path $root "acceptance"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$local = Join-Path $outDir "edit_screen_$stamp.png"
$pullArgs = @("pull", $remote, $local)
if ($Device) { $pullArgs = @("-s", $Device) + $pullArgs }
& $adb @pullArgs | Out-Null
AdbShell "rm $remote" | Out-Null
Write-Host "[5/6] 截屏已保存: $local"

# ---------- 手动验收清单 ----------
Write-Host "[6/6] 请在设备上完成以下手动验收（PRD §10.1）:" -ForegroundColor Green
@"
  [ ] 细节 Tab: 五项基础调节 + 清晰度/色彩增强/降噪滑杆拖动实时（<100ms 体感）
  [ ] 一键 Tab: AI 自动增强（分析中→强度滑杆）、一键修复（缺陷提示）
  [ ] 场景 Tab: 人像优化/风光优化（策略说明 + 强度）
  [ ] 滤镜 Tab: 10 款缩略图 <1.5s、滤镜强度滑杆
  [ ] 对比: 长按画布看原图、对比按钮分屏 + 拖动分割线（缩放位置保持）
  [ ] 撤销/重做 ≥20 步、重置
  [ ] 保存: 另存（DCIM/NikonLink/Edited 可见 + 已修角标）、覆盖（备份 + SAF 授权）
  [ ] 未保存退出: 保存/不保存退出/取消三选项
  [ ] 预设: 存为预设 → 应用 → 长按删除
"@
