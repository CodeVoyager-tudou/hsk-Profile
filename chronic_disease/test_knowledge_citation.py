# -*- coding: utf-8 -*-
"""知识片段出处标注测试：文件名 + 页码的拼装规则。

    页码是本次语料改造新增的定位信息（loaders 按页产出 Document → chunk metadata.page
    → Milvus page 字段 → 检索结果 metadata），这里钉住它会话里引用标注的格式：
      · PDF 片段要带页码；
      · md/txt 文档没有页的概念，不能写出"第None页"；
      · page 为 0（老数据/无页码）时同样不得出现页码。
    运行：python test_knowledge_citation.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from langchain_core.documents import Document  # noqa: E402
from agents.base_agent import format_knowledge_header  # noqa: E402


def _doc(parent_id, page=None, source="disease"):
    return Document(page_content="正文", metadata={
        "parent_id": parent_id, "source": source, "page": page})


def test_pdf_chunk_header_carries_page():
    header = format_knowledge_header(1, _doc("中国高血压防治指南(2024年修订版).pdf_p27", page=11))
    assert header == "[资料1 | 来源: 《中国高血压防治指南(2024年修订版).pdf》 第11页]", header


def test_markdown_chunk_header_has_no_page():
    """md/txt 文档无页码：不能出现"第None页"或空页码后缀"""
    header = format_knowledge_header(2, _doc("高血压疾病科普.md_p0", page=None))
    assert header == "[资料2 | 来源: 《高血压疾病科普.md》]", header


def test_zero_page_is_treated_as_missing():
    """page=0 表示"无页码信息"（老数据），同样不标注"""
    header = format_knowledge_header(3, _doc("某标准.pdf_p1", page=0))
    assert header == "[资料3 | 来源: 《某标准.pdf》]", header


def test_falls_back_to_source_when_parent_id_missing():
    header = format_knowledge_header(4, _doc("", page=None, source="lab"))
    assert header == "[资料4 | 来源: 《lab》]", header


def test_filename_containing_underscore_p_is_not_mangled():
    """文件名里本身含 "_p" 时，只截最后一段的序号后缀"""
    header = format_knowledge_header(5, _doc("WST404.10-2022_甲状腺功能参考区间.pdf_p3", page=9))
    assert header == "[资料5 | 来源: 《WST404.10-2022_甲状腺功能参考区间.pdf》 第9页]", header


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
