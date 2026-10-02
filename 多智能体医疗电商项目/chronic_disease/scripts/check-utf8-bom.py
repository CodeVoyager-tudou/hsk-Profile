#!/usr/bin/env python
"""CI 守卫：源码文件禁止带 UTF-8 BOM（PowerShell 写入 BOM 会让 javac/解析器报错）。"""

import sys
from pathlib import Path

BOM = b"\xef\xbb\xbf"
SUFFIXES = {".py", ".java", ".xml", ".yml", ".yaml", ".sql", ".md", ".properties", ".js", ".vue", ".ps1"}
SKIP_DIRS = {"target", "node_modules", "dist", ".git", "__pycache__", ".venv", "venv", ".idea", "logs"}


def main(roots):
    """扫描 roots 下所有受管后缀的文件，返回 0=全部无 BOM，1=存在带 BOM 的文件"""
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
    print("BOM 检查通过")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:] or ["."]))
