#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
安装 / 卸载仓库内容卫生检查的 git hook。

用法：
    python tools/install_hooks.py            # 安装 pre-commit
    python tools/install_hooks.py --uninstall # 卸载

安装后，每次 git commit 会自动运行 tools/check_repo_hygiene.py --staged，
发现外部产品表述时拒绝提交并打印违规位置。
"""
from __future__ import print_function

import io
import os
import stat
import subprocess
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 仓库可能是 worktree，.git 可能是文件而非目录 —— 两种都处理
def resolve_git_dir():
    """返回当前 worktree 的 git 目录（.git 可能是文件，指向 worktrees/<name>）。"""
    out = subprocess.check_output(
        ["git", "rev-parse", "--absolute-git-dir"], cwd=REPO_ROOT
    )
    return out.decode("utf-8").strip()


def resolve_hooks_dir():
    """
    返回 hooks 目录。用 `git rev-parse --git-path hooks`，
    它会把 worktree 的 hooks 正确解析到共享的 common dir —— 这样主仓与
    所有 worktree 共用一个 pre-commit，符合「规则全局生效」的意图。
    """
    out = subprocess.check_output(
        ["git", "rev-parse", "--git-path", "hooks"], cwd=REPO_ROOT
    )
    p = out.decode("utf-8").strip()
    if not os.path.isabs(p):
        p = os.path.join(REPO_ROOT, p)
    return os.path.normpath(p)


HOOK_TEMPLATE = """#!/bin/sh
# N-Link 仓库内容卫生检查（由 tools/install_hooks.py 生成）
# 依据 docs/CONTRIBUTING.md「外部产品信息不入库」一条
# 手动运行：python tools/check_repo_hygiene.py --staged
# 临时跳过：git commit --no-verify

set -e

REPO_ROOT="$(git rev-parse --show-toplevel)"
CHECKER="$REPO_ROOT/tools/check_repo_hygiene.py"

if [ ! -f "$CHECKER" ]; then
    echo "[pre-commit] 检查脚本不存在，跳过：$CHECKER"
    exit 0
fi

PY=""
for c in python3 python py; do
    # command -v 会被微软商店的 python3 空壳骗过去（能解析到 WindowsApps 路径，一跑就退 49，
    # 检查脚本根本没执行）。必须真跑一次冒烟测试再采用，否则提交会被报成「发现违规」，
    # 而实际违规数为 0 —— 排查的人会去找不存在的内容问题。
    if "$c" -c "pass" >/dev/null 2>&1; then PY="$c"; break; fi
done

if [ -z "$PY" ]; then
    echo "[pre-commit] 未找到 python 解释器，跳过内容卫生检查。"
    echo "[pre-commit] 请手动运行：python tools/check_repo_hygiene.py --staged"
    exit 0
fi

if ! "$PY" "$CHECKER" --staged; then
    echo ""
    echo "[pre-commit] 提交被拦截：检测到外部产品相关表述。"
    echo "[pre-commit] 请按 docs/CONTRIBUTING.md 改写或删除后再提交。"
    echo "[pre-commit] 确认无误要强制提交：git commit --no-verify"
    exit 1
fi

exit 0
"""


def main():
    if not os.path.isdir(REPO_ROOT):
        print("[hooks] 错误：未在仓库根目录下运行。")
        return 2

    try:
        hooks_dir = resolve_hooks_dir()
    except Exception as e:
        print("[hooks] 错误：无法定位 hooks 目录：%s" % e)
        return 2

    hook_path = os.path.join(hooks_dir, "pre-commit")

    if "--uninstall" in sys.argv:
        if os.path.exists(hook_path):
            with io.open(hook_path, "r", encoding="utf-8") as f:
                if "install_hooks.py" in f.read():
                    os.remove(hook_path)
                    print("[hooks] 已卸载 pre-commit。")
                else:
                    print("[hooks] pre-commit 不是本脚本生成的，未改动。")
        else:
            print("[hooks] pre-commit 不存在，无需卸载。")
        return 0

    if not os.path.isdir(hooks_dir):
        os.makedirs(hooks_dir)

    # 已存在且不是本脚本生成的 → 拒绝覆盖
    if os.path.exists(hook_path):
        with io.open(hook_path, "r", encoding="utf-8", errors="replace") as f:
            existing = f.read()
        if "install_hooks.py" not in existing:
            print("[hooks] 错误：.git/hooks/pre-commit 已存在且非本脚本生成。")
            print("[hooks] 请手动合并内容，未做改动。")
            return 1

    with io.open(hook_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(HOOK_TEMPLATE)

    # POSIX 下需要可执行位；Windows 上 git 会用 sh 解释，不强求
    if os.name != "nt":
        st = os.stat(hook_path)
        os.chmod(hook_path, st.st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

    print("[hooks] 已安装 pre-commit：%s" % hook_path)
    print("[hooks] 提交时将自动校验；手动运行：python tools/check_repo_hygiene.py --staged")
    print("[hooks] 注意：worktree 的 hooks 目录可能随主仓变化，重建 worktree 后请重跑本脚本。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
