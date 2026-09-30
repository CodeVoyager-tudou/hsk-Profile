#!/usr/bin/env python
"""CI 守卫：源码文件禁止带 UTF-8 BOM。

背景：Windows 上 `Set-Content -Encoding UTF8` 会写入 BOM，javac 读到会直接报
"非法字符: \ufeff"，曾导致整个仓库编译不过却没人发现。此脚本把这类问题挡在合并前。
用法：python scripts/check-utf8-bom.py .
"""

import sys
from pathlib import Path

BOM = b"\xef\xbb\xbf"
SUFFIXES = {".java", ".py", ".xml", ".yml", ".yaml", ".sql", ".md", ".properties", ".js", ".vue", ".ps1"}
SKIP_DIRS = {"target", "node_modules", "dist", ".git", "__pycache__", ".venv", "venv", ".idea"}


def main(roots):
    offenders = []
    for root in roots:
        for path in Path(root).rglob("*"):
            if not path.is_file() or path.suffix.lower() not in SUFFIXES:
                continue
            if any(part in SKIP_DIRS for part in path.parts):
                continue
            try:
                if path.read_bytes()[:3] == BOM:
                    offenders.append(path)
            except OSError:
                continue
    if offenders:
        print("以下文件带 UTF-8 BOM，请去掉后再提交：")
        for path in offenders:
            print("  " + str(path))
        return 1
    print("BOM 检查通过：未发现带 BOM 的源码文件")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:] or ["."]))
