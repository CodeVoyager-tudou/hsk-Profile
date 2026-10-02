# -*- coding: utf-8 -*-
"""解析层工具测试：阅读顺序择优、段落重组、表格单元格归一化。

    这三处都是"纯函数"级别的改动，正是最该被单测钉住的地方：
      · 取序择优只在证据充分时才切换，避免把单栏文件也改坏；
      · 段落重组必须把被排版切断的句子接回去，同时不能把编号条目粘成一段；
      · 表格单元格里的换行必须归一化，否则"一个物理行 = 一行表格"的前提不成立。
    运行：python test_loaders.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from document_loader.loaders import (  # noqa: E402
    COLUMN_SWITCH_MARGIN, column_switch_rate, prefer_flow_order, reflow_paragraphs,
    rows_to_markdown,
)


def test_reflow_merges_sentence_split_by_linebreak():
    """被排版切断的句子必须接回同一段（这是块边界能落在句末的前提）"""
    lines = [
        "前,我国诊室血压和家庭血压测量的规范化难以评估,",
        "血压诊断标准亦可参见表7。血压测量及高血压诊断",
        "流程见图2。",
    ]
    out = reflow_paragraphs(lines)
    # 三行本是同一段：第 1 行以逗号结尾、第 2 行未以句末标点收束，都应继续并回上一行
    assert out == ("前,我国诊室血压和家庭血压测量的规范化难以评估,"
                   "血压诊断标准亦可参见表7。血压测量及高血压诊断流程见图2。\n"), repr(out)
    assert "难以评估,血压诊断标准" in out, "断行处未被接回"


def test_reflow_breaks_before_numbered_items():
    """行首是编号/章节/题注时不得与上一行粘连（否则条目会挤成一坨）"""
    out = reflow_paragraphs([
        "高血压的分级标准如下,",
        "4.1 一级高血压:收缩压140~159mmHg。",
    ])
    assert "如下,\n\n4.1 一级高血压" in out, out
    out = reflow_paragraphs([
        "本标准规定了血细胞分析的参考区间,",
        "1 范围",
    ])
    assert "参考区间,\n\n1 范围" in out, out


def test_reflow_does_not_treat_long_number_head_as_heading():
    """正文里以数字开头的长句不能被当成章节标题劈开"""
    out = reflow_paragraphs([
        "测量方式的取值如下,",
        "3 次测量的全部血压值的平均值才是最终读数。",
    ])
    assert "如下,3 次测量的全部血压值" in out, out


def test_reflow_keeps_paragraph_per_line_when_already_ended():
    """已经以句末标点收束的行不再与下一行拼接"""
    out = reflow_paragraphs(["这是一句完整的话。", "这是另一句完整的话。"])
    assert out == "这是一句完整的话。\n\n这是另一句完整的话。\n", repr(out)


def test_reflow_ignores_blank_lines():
    out = reflow_paragraphs(["第一段内容在这里。", "", "第二段内容在这里。"])
    assert "\n\n\n" not in out
    assert "第一段内容在这里。" in out and "第二段内容在这里。" in out


def test_rows_to_markdown_one_physical_line_per_row():
    """单元格内的换行必须归一化：表格行数 == markdown 物理行数（表头 + 分隔行 + 数据行）"""
    rows = [
        ["检验项目", "单位", "参考区间"],
        ["三碘甲状腺原氨酸\n(T)\n3", "nmol/L", "1.30～\n2.40"],
        ["甲状腺素(T)\n4", "nmol/L", "70～140"],
    ]
    md = rows_to_markdown(rows)
    physical = [l for l in md.rstrip("\n").split("\n")]
    assert len(physical) == len(rows) + 1, f"物理行数 {len(physical)} != 表格行数 {len(rows)} + 1"
    assert "\n" not in md.rstrip("\n").split("\n")[2], "数据行内部仍残留换行"
    assert "1.30～ 2.40" in md, f"单元格内换行未归一化为空格: {md!r}"
    assert "三碘甲状腺原氨酸 (T) 3" in md


def test_column_switch_rate_detects_interleaving():
    """左栏右栏逐行交错 -> 1.0；先写完左栏再写右栏 -> 只有栏间那一次切换（1/3）"""
    interleaved = [("左", (0, 0, 100, 10)), ("右", (400, 0, 500, 10)),
                   ("左", (0, 20, 100, 30)), ("右", (400, 20, 500, 30))]
    contiguous = [("左", (0, 0, 100, 10)), ("左", (0, 20, 100, 30)),
                  ("右", (400, 0, 500, 10)), ("右", (400, 20, 500, 30))]
    assert column_switch_rate(interleaved, 500) == 1.0
    assert abs(column_switch_rate(contiguous, 500) - 1 / 3) < 1e-9
    assert column_switch_rate([], 500) == 0.0


def test_prefer_flow_order_requires_clear_win():
    """只有内容流顺序明显更连贯才切换取序；两者相近时保持原行为（单栏文件不受影响）"""
    assert COLUMN_SWITCH_MARGIN == 0.10
    assert prefer_flow_order(0.34, 0.03) is True    # 双栏期刊：明显更连贯
    assert prefer_flow_order(0.33, 0.32) is False   # 居中短行版式：不动
    assert prefer_flow_order(0.05, 0.04) is False   # 本来就都很好：不动


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
