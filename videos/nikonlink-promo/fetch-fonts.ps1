$base = "https://cdn.jsdelivr.net/npm"
$out = "d:\1\N-Link\videos\nikonlink-promo\assets\fonts"
$out2 = "d:\1\N-Link\videos\nikonlink-promo\compositions\frames\assets\fonts"
New-Item -ItemType Directory -Force -Path $out | Out-Null
New-Item -ItemType Directory -Force -Path $out2 | Out-Null
$map = @{
  "IBMPlexMono-Medium.woff2"      = "$base/@fontsource/ibm-plex-mono/files/ibm-plex-mono-latin-500-normal.woff2"
  "InterTight-ExtraBold.woff2"    = "$base/@fontsource/inter-tight/files/inter-tight-latin-800-normal.woff2"
  "InterTight-Medium.woff2"       = "$base/@fontsource/inter-tight/files/inter-tight-latin-500-normal.woff2"
  "InterTight-SemiBold.woff2"     = "$base/@fontsource/inter-tight/files/inter-tight-latin-600-normal.woff2"
  "InterTight[wght].woff2"        = "$base/@fontsource-variable/inter-tight/files/inter-tight-latin-wght-normal.woff2"
  "NotoSansSC-Black.woff2"        = "$base/@fontsource/noto-sans-sc/files/noto-sans-sc-chinese-simplified-900-normal.woff2"
  "NotoSansSC-Bold.woff2"         = "$base/@fontsource/noto-sans-sc/files/noto-sans-sc-chinese-simplified-700-normal.woff2"
  "NotoSansSC-Medium.woff2"       = "$base/@fontsource/noto-sans-sc/files/noto-sans-sc-chinese-simplified-500-normal.woff2"
  "NotoSansSC-Regular.woff2"      = "$base/@fontsource/noto-sans-sc/files/noto-sans-sc-chinese-simplified-400-normal.woff2"
  "NotoSansSC[wght].woff2"        = "$base/@fontsource-variable/noto-sans-sc/files/noto-sans-sc-chinese-simplified-wght-normal.woff2"
}
foreach ($k in $map.Keys) {
  $dest = Join-Path $out $k
  try {
    Invoke-WebRequest -Uri $map[$k] -OutFile $dest -TimeoutSec 60
    Copy-Item $dest (Join-Path $out2 $k) -Force
    "{0} -> {1} bytes" -f $k, (Get-Item $dest).Length
  } catch {
    "FAIL {0}: {1}" -f $k, $_.Exception.Message
  }
}
