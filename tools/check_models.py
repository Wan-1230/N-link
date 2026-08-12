#!/usr/bin/env python3
"""models.json 发布校验脚本（PRD-AI修图 8.5 模型交付策略）

用途：模型文件上传 OSS/CDN 并回填 assets/models.json 后，
在发布前运行本脚本做清单完整性检查：
  - 条目数量与方案 D 清单一致（8 个能力）
  - url 不再是占位地址（*.example）
  - sha256 为 64 位十六进制（非 PENDING_RELEASE）
  - size_bytes 为正数
  - tier 合法（builtin / on_demand）
  - capability 无重复

用法：
  python tools/check_models.py [path/to/models.json]
  （默认读取 app/src/main/assets/models.json）

退出码：0=通过，1=存在问题（逐项打印）。
"""
import json
import re
import sys
from pathlib import Path

EXPECTED_CAPABILITIES = {
    "auto_enhance",     # Image-Adaptive LUT（内置）
    "scene_classify",   # EfficientNet-Lite2（内置）
    "denoise",          # NAFNet-w64（按需）
    "detail_restore",   # Real-ESRGAN_x4plus（按需）
    "face_detect",      # SCRFD-10M（按需）
    "face_parse",       # BiSeNet（按需）
    "low_light",        # RetinexFormer（按需）
    "dehaze",           # MPRA-Net（按需）
}
SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")
VALID_TIERS = {"builtin", "on_demand"}


def main() -> int:
    default_path = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "models.json"
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else default_path
    if not path.exists():
        print(f"[FAIL] 清单不存在: {path}")
        return 1

    data = json.loads(path.read_text(encoding="utf-8"))
    models = data.get("models", [])
    problems = []

    caps = [m.get("capability") for m in models]
    if set(caps) != EXPECTED_CAPABILITIES:
        missing = EXPECTED_CAPABILITIES - set(caps)
        extra = set(caps) - EXPECTED_CAPABILITIES
        if missing:
            problems.append(f"缺少能力条目: {sorted(missing)}")
        if extra:
            problems.append(f"多余能力条目: {sorted(extra)}")
    if len(caps) != len(set(caps)):
        problems.append("capability 存在重复")

    for m in models:
        mid = m.get("id", "<no-id>")
        url = m.get("url", "")
        sha = m.get("sha256", "")
        size = m.get("size_bytes", 0)
        tier = m.get("tier", "")

        if ".example" in url or not url.startswith("https://"):
            problems.append(f"{mid}: url 仍为占位或非法 -> {url}")
        if not SHA256_RE.match(sha):
            problems.append(f"{mid}: sha256 非法/未回填 -> {sha}")
        if not isinstance(size, int) or size <= 0:
            problems.append(f"{mid}: size_bytes 非法 -> {size}")
        if tier not in VALID_TIERS:
            problems.append(f"{mid}: tier 非法 -> {tier}")
        if not str(m.get("version", "")).isdigit() or m.get("version", 0) < 1:
            problems.append(f"{mid}: version 非法 -> {m.get('version')}")

    if problems:
        print(f"[FAIL] {len(problems)} 个问题待处理:")
        for p in problems:
            print(f"  - {p}")
        return 1

    print(f"[PASS] models.json 校验通过: {len(models)} 个模型条目均已回填且格式合法")
    return 0


if __name__ == "__main__":
    sys.exit(main())
