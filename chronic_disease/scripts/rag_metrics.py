# -*- coding: utf-8 -*-
"""RAG 语料质量指标：跨栏交错率 / 块边界落点 / 表格 markdown 保真度。

    用途：改造 loaders / chunker 前后各跑一次，用同一口径对比。
        python chronic_disease/scripts/rag_metrics.py --label before --out before.json
        python chronic_disease/scripts/rag_metrics.py --label after  --out after.json --diff before.json

    三个指标（都是"越高越好"之外的诊断量，改造前后看变化方向）：

    1. cross_col_switch —— 相邻文本行在页面左右半区之间来回跳的比例。
       双栏 PDF 若按 y 坐标排序取块，正文会与邻栏逐行交错，该值可达 30% 以上；
       按正确的阅读顺序取块应接近 0。对每个 PDF 同时报告 sort=True/False 两种取序。

    2. sentence_end_rate —— 父块 / 子块以句末标点（。！？）收尾的比例。
       偏低说明切分边界落在句子中间：loaders 给每个视觉行都补了 "\\n"，
       而分隔符又把换行排在句末标点之前，于是"按逻辑边界递归切分"实际没有生效。

    3. table_row_fidelity —— find_tables 抽出的表格，markdown 物理行数与表格行数是否一致。
       不一致说明单元格内部的换行把 markdown 行拆散了，
       chunker.split_table_block 的"表头=前两行、按行分组"假设随之失效。

    注意：默认把 OCR 阈值设为 2.0（等于关闭图片 OCR），因此纯扫描版 PDF 会被跳过——
    它们的内容全部来自 OCR，要跑就得先花几十分钟识别整本。
"""
import argparse
import json
import os
import statistics
import sys
import time

import fitz

project_root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, project_root)
sys.path.insert(0, os.path.join(project_root, "chronic_disease"))

from document_loader.loaders import (  # noqa: E402
    load_pdf_with_ocr, prefer_flow_order, rows_to_markdown,
)
from document_loader.chunker import process_documents  # noqa: E402

DATA_DIR = os.path.join(project_root, "chronic_disease", "data")
SOURCES = ["disease", "medication", "lifestyle", "lab", "risk"]
END_PUNCT = "。！？”"


def iter_pdfs():
    """遍历各知识源目录下的 PDF（只取顶层文件，与 ingest_fast 口径一致）"""
    for src in SOURCES:
        d = os.path.join(DATA_DIR, src)
        if not os.path.isdir(d):
            continue
        for fn in sorted(os.listdir(d)):
            if fn.lower().endswith(".pdf"):
                yield src, fn, os.path.join(d, fn)


def page_lines(page, sort):
    """按给定顺序取页面文本行，返回 [(文本, bbox), ...]"""
    out = []
    for block in page.get_text("dict", sort=sort)["blocks"]:
        if block.get("type") != 0 or "lines" not in block:
            continue
        for line in block["lines"]:
            text = "".join(s["text"] for s in line["spans"]).strip()
            if text:
                out.append((text, line["bbox"]))
    return out


def cross_col_switch(lines, page_width):
    """相邻行在页面左右半区之间切换的比例（0 = 阅读顺序连贯）"""
    if len(lines) < 2:
        return 0.0
    half = page_width / 2
    side = [0 if (b[0] + b[2]) / 2 < half else 1 for _, b in lines]
    sw = sum(1 for a, b in zip(side, side[1:]) if a != b)
    return sw / (len(side) - 1)


def fitz_pass(fp):
    """只用 PyMuPDF 的指标：页数、文本层字符数、两种取序的跨栏交错率、表格保真度。

    cross_col_switch_effective 是"按 loaders.pick_body_lines 的规则逐页择优后"的交错率：
    改造前 loaders 固定用 sort=True，等价于 rate_sorted；改造后按页取更连贯的那个。
    """
    doc = fitz.open(fp)
    chars = 0
    sw_true = sw_false = 0
    pairs = 0
    eff_sw = eff_pairs = 0
    tables = bad_tables = 0
    physical_extra = 0
    for page in doc:
        chars += len(page.get_text("text").strip())
        for sort, key in ((True, "true"), (False, "false")):
            lines = page_lines(page, sort)
            if len(lines) < 2:
                continue
            half = page.rect.width / 2
            side = [0 if (b[0] + b[2]) / 2 < half else 1 for _, b in lines]
            sw = sum(1 for a, b in zip(side, side[1:]) if a != b)
            if key == "true":
                sw_true += sw
            else:
                sw_false += sw
            if key == "true":
                pairs += len(side) - 1
        lines_true = page_lines(page, True)
        lines_false = page_lines(page, False)
        if len(lines_true) >= 2 and len(lines_false) >= 2:
            chosen = lines_false if prefer_flow_order(cross_col_switch(lines_true, page.rect.width),
                                                      cross_col_switch(lines_false, page.rect.width)) \
                else lines_true
            half = page.rect.width / 2
            side = [0 if (b[0] + b[2]) / 2 < half else 1 for _, b in chosen]
            eff_sw += sum(1 for a, b in zip(side, side[1:]) if a != b)
            eff_pairs += len(side) - 1
        try:
            tabs = page.find_tables().tables
        except Exception:
            tabs = []
        for tab in tabs:
            rows = tab.extract()
            if not rows:
                continue
            tables += 1
            physical = len([l for l in rows_to_markdown(rows).rstrip("\n").split("\n")])
            expected = len(rows) + 1  # 表头行 + markdown 分隔行 + 每行数据一行
            if physical != expected:
                bad_tables += 1
                physical_extra += physical - expected
    pages = doc.page_count
    doc.close()
    return {
        "pages": pages,
        "textlayer_chars": chars,
        "cross_col_switch_sort_true": round(sw_true / pairs, 4) if pairs else 0.0,
        "cross_col_switch_sort_false": round(sw_false / pairs, 4) if pairs else 0.0,
        "cross_col_switch_effective": round(eff_sw / eff_pairs, 4) if eff_pairs else 0.0,
        "tables": tables,
        "tables_with_broken_rows": bad_tables,
        "extra_physical_lines": physical_extra,
    }


def chunk_pass(fp, source):
    """走真实 loader + chunker（关闭 OCR），统计块数与边界落点"""
    docs = load_pdf_with_ocr(fp, source, ocr_threshold_w=2.0, ocr_threshold_h=2.0, use_cuda=False)
    if not docs:
        return None
    chunks = process_documents(docs, parent_size=1000, child_size=200, overlap=30)
    if not chunks:
        return None
    parents = {}
    for c in chunks:
        parents.setdefault(c.metadata.get("parent_id"), c.metadata.get("parent_content", ""))
    p_texts = [t for t in parents.values() if t and t.strip()]
    c_texts = [c.page_content for c in chunks if c.page_content and c.page_content.strip()]
    ends = lambda ts: round(sum(1 for t in ts if t.rstrip()[-1] in END_PUNCT) / len(ts), 4) if ts else 0.0
    pages = [c.metadata.get("page") for c in chunks]
    body = "\n".join(d.page_content for d in docs)
    body_lines = [l for l in body.split("\n") if l.strip() and not l.startswith("[")]
    return {
        "docs": len(docs),
        "parents": len(p_texts),
        "children": len(c_texts),
        "parent_sentence_end_rate": ends(p_texts),
        "child_sentence_end_rate": ends(c_texts),
        "parent_avg_chars": round(statistics.mean(len(t) for t in p_texts), 1),
        "child_avg_chars": round(statistics.mean(len(t) for t in c_texts), 1),
        # 解析输出的"物理行"平均长度：段落重组生效后应远大于视觉行的 ~10 字符
        "avg_paragraph_chars": round(statistics.mean(len(l) for l in body_lines), 1) if body_lines else 0.0,
        "chunks_with_page_metadata": sum(1 for p in pages if p is not None),
    }


def main():
    ap = argparse.ArgumentParser(description="RAG 语料质量指标")
    ap.add_argument("--label", default="run", help="本次运行的标签，写入结果 JSON")
    ap.add_argument("--out", help="结果 JSON 输出路径")
    ap.add_argument("--diff", help="与既有 JSON 对比并打印差异")
    args = ap.parse_args()

    result = {"label": args.label, "time": time.strftime("%Y-%m-%d %H:%M:%S"), "files": {}}
    for src, fn, fp in iter_pdfs():
        key = f"{src}/{fn}"
        try:
            m = fitz_pass(fp)
            if m["textlayer_chars"] > 0:
                m.update(chunk_pass(fp, src) or {})
            else:
                m["skipped_reason"] = "纯扫描版（文本层 0 字符），需 OCR 才能统计块指标"
        except Exception as e:  # 单个文件失败不影响整体
            m = {"error": f"{type(e).__name__}: {e}"}
        result["files"][key] = m
        print(f"[{key}] {json.dumps(m, ensure_ascii=False)}")

    text_files = [m for m in result["files"].values() if m.get("children")]
    if text_files:
        result["aggregate"] = {
            "files_with_chunks": len(text_files),
            "children": sum(m["children"] for m in text_files),
            "parents": sum(m["parents"] for m in text_files),
            "child_sentence_end_rate": round(
                sum(m["child_sentence_end_rate"] * m["children"] for m in text_files)
                / sum(m["children"] for m in text_files), 4),
            "parent_sentence_end_rate": round(
                sum(m["parent_sentence_end_rate"] * m["parents"] for m in text_files)
                / sum(m["parents"] for m in text_files), 4),
            "cross_col_switch_sort_true": round(
                statistics.mean(m["cross_col_switch_sort_true"] for m in text_files), 4),
            "cross_col_switch_sort_false": round(
                statistics.mean(m["cross_col_switch_sort_false"] for m in text_files), 4),
            "cross_col_switch_effective": round(
                sum(m["cross_col_switch_effective"] * m["pages"] for m in text_files)
                / sum(m["pages"] for m in text_files), 4),
            "avg_paragraph_chars": round(
                statistics.mean(m["avg_paragraph_chars"] for m in text_files), 1),
            "tables": sum(m.get("tables", 0) for m in result["files"].values()),
            "tables_with_broken_rows": sum(m.get("tables_with_broken_rows", 0) for m in result["files"].values()),
        }
        print("\n== 汇总 ==")
        print(json.dumps(result["aggregate"], ensure_ascii=False, indent=1))

    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=1)
        print(f"\n结果已写入: {args.out}")

    if args.diff and os.path.exists(args.diff):
        with open(args.diff, encoding="utf-8") as f:
            old = json.load(f)
        print("\n== 对比 %s → %s ==" % (old.get("label"), args.label))
        old_agg = dict(old.get("aggregate", {}))
        # 老版本 JSON 没有"有效取序"这一项：改造前 loaders 固定按 y 排序，等价于 sort_true
        old_agg.setdefault("cross_col_switch_effective", old_agg.get("cross_col_switch_sort_true"))
        for k in ("children", "parents", "child_sentence_end_rate", "parent_sentence_end_rate",
                  "cross_col_switch_sort_true", "cross_col_switch_effective",
                  "avg_paragraph_chars", "tables", "tables_with_broken_rows"):
            if k in old_agg and k in result.get("aggregate", {}):
                print("  %-32s %-10s -> %s" % (k, old_agg[k], result["aggregate"][k]))


if __name__ == "__main__":
    main()
