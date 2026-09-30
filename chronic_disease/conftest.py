"""pytest 全局夹具：统一 sys.path（复核 P2-10）。

本仓库 14 个 test_*.py 曾有两套 import 约定（`from chronic_disease.core...` 与
`from core...`）+ 各文件自带 sys.path.insert 脚手架，仓库根又有 __init__.py，
默认 import 模式下整目录 pytest 收集会互相踩。现在统一为裸导入约定，
本文件只负责一件事：把仓库根插进 sys.path 一次，让 `from core...` /
`from agents...` 在任何 cwd、任何 pytest 调用方式下都可导入。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
