"""分块工具测试：重点锁定 parent_id 的生成规则（截断不得破坏唯一性）。

    同时验证短文件名下的结果与旧实现逐字节一致 —— 这是「已入库数据无需重新入库」的前提。
    运行：python test_chunker.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from document_loader.chunker import make_parent_id, PARENT_ID_MAX_LEN


def test_short_filename_matches_legacy_format():
    """短文件名（现状）必须与旧实现逐字节一致 —— 保证已入库数据无需重新入库。

    旧实现是 f"{文件名}_p{序号}"[:90]。当 文件名+后缀 不超过 90 字符时，
    截断根本不发生，因此新实现必须返回完全相同的结果。
    """
    name = "中国高血压防治指南(2024年修订版)"
    for idx in (0, 1, 12, 999):
        expected = f"{name}_p{idx}"
        assert make_parent_id(name, idx) == expected, \
            f"短文件名结果与原实现不一致: {make_parent_id(name, idx)!r} != {expected!r}"


def test_long_filename_no_longer_collides():
    """长文件名时不同序号不得撞成同一个 id（这正是原缺陷）。

    旧实现 "A"*88 + "_p1" 与 "A"*88 + "_p12" 都会截断成 "A"*88 + "_p"，互相覆盖。
    """
    name = "A" * 88
    ids = [make_parent_id(name, i) for i in range(1, 15)]
    assert len(set(ids)) == len(ids), f"长文件名下 parent_id 发生碰撞: {ids}"


def test_parent_id_always_keeps_sequence_suffix():
    """无论文件名多长，末尾的 _p序号 都必须完整保留"""
    for length in (10, 50, 88, 100, 200):
        name = "文" * length
        for idx in (1, 12, 123):
            pid = make_parent_id(name, idx)
            assert pid.endswith(f"_p{idx}"), \
                f"序号被截断: 文件名长度={length}, idx={idx}, 结果={pid!r}"


def test_parent_id_never_exceeds_max_len():
    """结果长度不得超过上限（对应 Milvus 字段长度约束）"""
    for length in (10, 96, 500):
        pid = make_parent_id("X" * length, 12345)
        assert len(pid) <= PARENT_ID_MAX_LEN, \
            f"超长: len={len(pid)} > {PARENT_ID_MAX_LEN}, 结果={pid!r}"


def test_real_corpus_filenames_unaffected():
    """用真实语料里的文件名验证：全部走"不截断"分支，行为与旧版一致"""
    names = [
        "中国心血管病一级预防指南2020_风险评估与危险因素",
        "WST404.10-2022_甲状腺功能参考区间",
        "中国心血管病风险评估和管理指南2019_要点",
        "解读高血压合理用药指南第2版_中华高血压杂志",
        "WST404.5-2015_尿素肌酐参考区间",
        "WST405-2012_血细胞分析参考区间",
        "中国成人血脂异常防治指南2016年修订版",
        "中国高血压防治指南(2024年修订版)",
    ]
    for name in names:
        assert len(name) < PARENT_ID_MAX_LEN - 5, f"该文件名过长，需重新评估: {name}"
        assert make_parent_id(name, 3) == f"{name}_p3"


# ---------------------------------------------------------------------------
# 按页 Document（loaders 的现状）下的分块行为
# ---------------------------------------------------------------------------

def _paged_docs():
    """构造"一篇文档两个分页 Document + 另一篇文档"的输入"""
    from langchain_core.documents import Document
    return [
        Document(page_content="第一页正文内容。" * 40, metadata={
            "source": "disease", "file_path": "/data/disease/指南.pdf",
            "page": 1, "page_count": 2}),
        Document(page_content="第二页正文内容。" * 40, metadata={
            "source": "disease", "file_path": "/data/disease/指南.pdf",
            "page": 2, "page_count": 2}),
        Document(page_content="另一份文档的正文。" * 40, metadata={
            "source": "lab", "file_path": "/data/lab/另一份.pdf",
            "page": 1, "page_count": 1}),
    ]


def _split(docs):
    from document_loader.chunker import process_documents
    return process_documents(docs, parent_size=200, child_size=100, overlap=10)


def test_parent_id_stays_injective_across_pages():
    """跨分页 Document 的父块序号必须连续：不同父块绝不能共用同一个 parent_id。

    loaders 改为按页产出 Document 后，若序号在每页开头重置，
    "指南.pdf_p0" 会同时指向第 1 页和第 2 页的不同内容，
    检索去重（按 parent_id 合并）就会把其中一份当成重复丢掉。
    """
    chunks = _split(_paged_docs())
    id_to_content = {}
    for c in chunks:
        pid = c.metadata["parent_id"]
        id_to_content.setdefault(pid, set()).add(c.metadata["parent_content"])
    collided = {pid: contents for pid, contents in id_to_content.items() if len(contents) > 1}
    assert not collided, f"同一 parent_id 指向了多段不同内容: {list(collided)[:3]}"
    n_parents = len({c.metadata["parent_content"] for c in chunks})
    assert len(id_to_content) == n_parents, \
        f"父块被错误合并: 唯一 id {len(id_to_content)} != 唯一父块 {n_parents}"


def test_parent_id_sequences_are_per_file_and_continuous():
    """同一文件的序号从 0 连续递增；不同文件各自独立编号"""
    chunks = _split(_paged_docs())
    guide = sorted({c.metadata["parent_id"] for c in chunks
                    if "指南.pdf" in c.metadata["parent_id"]},
                   key=lambda s: int(s.rsplit("_p", 1)[1]))
    other = sorted({c.metadata["parent_id"] for c in chunks
                    if "另一份.pdf" in c.metadata["parent_id"]},
                   key=lambda s: int(s.rsplit("_p", 1)[1]))
    assert [s.rsplit("_p", 1)[1] for s in guide] == [str(i) for i in range(len(guide))], guide
    assert [s.rsplit("_p", 1)[1] for s in other] == [str(i) for i in range(len(other))], other


def test_chunk_metadata_carries_page():
    """页码必须透传到子块（正文块与表格块都要），否则检索结果无法溯源"""
    from langchain_core.documents import Document
    table_doc = Document(
        page_content="[表格]\n| 项目 | 参考区间 |\n| --- | --- |\n| 白细胞 | 3.5~9.5 |\n[/表格]\n",
        metadata={"source": "lab", "file_path": "/data/lab/标准.pdf", "page": 3, "page_count": 5})
    chunks = _split([_paged_docs()[0], table_doc])
    pages = {c.metadata["page"] for c in chunks if "标准.pdf" in c.metadata["parent_id"]}
    assert pages == {3}, f"表格块丢失页码: {pages}"
    text_pages = {c.metadata["page"] for c in chunks if "指南.pdf" in c.metadata["parent_id"]}
    assert text_pages == {1}, f"正文块页码错误: {text_pages}"


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
