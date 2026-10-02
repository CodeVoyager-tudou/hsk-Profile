# -*- coding: utf-8 -*-
"""评测判据测试：关键词覆盖率与"共现窗口"判定。

    max_span 判据是为了补上 keyword_coverage 的盲区：词都出现过、但被拆到相隔很远的句子
    （页眉残留、表格单元格与正文交错、父子块拼接）时，覆盖率高不代表答案可读。
    这里钉住它的边界行为，避免判据本身产生假通过 / 假失败。
    运行：python test_eval_judge.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from run_eval import keyword_coverage, keyword_locality  # noqa: E402


def test_coverage_counts_distinct_keywords():
    assert keyword_coverage("家庭血压135/85", ["135", "85"]) == 1.0
    assert keyword_coverage("只有135", ["135", "85"]) == 0.5
    assert keyword_coverage("随便什么", []) == 1.0


def test_locality_disabled_without_max_span():
    """题集不写 max_span 时判据必须完全不起作用（老题行为不变）"""
    assert keyword_locality("135 远在天边 85", ["135", "85"], None) is True
    assert keyword_locality("135 远在天边 85", ["135", "85"], 0) is True


def test_locality_missing_keyword_fails():
    assert keyword_locality("只有135", ["135", "85"], 100) is False


def test_locality_accepts_any_keyword_order():
    """窗口左端不能只认第一个关键词：语料里"血红蛋白"写在"白细胞"之前也要能通过"""
    text = "血红蛋白 120~160 g/L；白细胞 3.5~9.5；血小板 125~350"
    assert keyword_locality(text, ["白细胞", "血红蛋白", "血小板"], 60) is True


def test_locality_rejects_words_far_apart():
    """词都在、但相隔很远（被拆到不同段落/不同文档）时必须判不通过"""
    text = "家庭血压" + "填充" * 200 + "135" + "填充" * 50 + "85"
    assert keyword_coverage(text, ["135", "85"]) == 1.0
    assert keyword_locality(text, ["135", "85"], 80) is False
    assert keyword_locality(text, ["135", "85"], 400) is True


if __name__ == "__main__":
    import traceback

    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    failed = 0
    for t in tests:
        try:
            t()
            print(f"[PASS] {t.__name__}")
        except Exception:
            failed += 1
            print(f"[FAIL] {t.__name__}")
            traceback.print_exc()
    print(f"\n{len(tests) - failed}/{len(tests)} passed")
    sys.exit(1 if failed else 0)
