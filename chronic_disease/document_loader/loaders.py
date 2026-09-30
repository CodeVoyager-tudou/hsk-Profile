"""文档解析：把 PDF / 图片 / 文本读成 LangChain Document（供后续分块与向量化）。

【PDF 为什么要做这么多额外处理】
    直接用现成库抽 PDF 文本，会得到一堆噪声，直接影响检索质量，因此这里做了七件事：
      1. 表格单独抽出并转成 Markdown，正文里跳过表格区域，避免同一份内容出现两次；
      2. 跨页表格合并：续表常重复表头甚至连上页被截断的那一行也重复，需要去重后再拼；
      3. 跨页页眉页脚过滤：边缘带内、在多页反复出现的文本行判为页眉页脚；页码另行按格式匹配；
      4. 图片 OCR：页面里占比够大的图片才送去 OCR，避免把装饰性图标也识别成文字；
      5. 阅读顺序择优：双栏排版按 y 排序取块会把左右栏逐行交错（实测交错率 34%~43%），
         本文件改为逐页比较两种取序，只有在内容流顺序明显更连贯时才采用它（见 pick_body_lines）；
      6. 段落重组：抽出来的是"视觉行"（实测 98 页 27747 行、平均行长 9.7 字，多数断在句子中间），
         直接按行切分会让块边界落在句子中间，这里把同一段落内的视觉行拼回去（见 reflow_paragraphs）；
      7. 按页产出 Document：metadata 带 page，让检索结果能溯源到具体页，
         同时父块天然以页为界（扫描版本来就是这个行为）。
    输出中保留 `[表格]...[/表格]` 与 `[图片]...[/图片]` 标记，
    分块阶段（chunker.py）据此做保护性切分。
"""
import os
import re
import difflib
import cv2
import numpy as np
from collections import Counter
from datetime import datetime
from typing import List
from tqdm import tqdm
from PIL import Image
import fitz
from langchain_core.documents import Document
from base.logger import logger

# 阅读顺序择优的判定余量：只有当内容流顺序的跨栏交错率比按 y 排序至少低这么多时，
# 才改用内容流顺序；否则保持原有行为（避免在"两种取序本就差不多"的文件上无谓地改变输出）。
COLUMN_SWITCH_MARGIN = 0.10

# OCR 结果缓存目录（按 文件md5/页码_xref 缓存识别文本）。
# 全量重建时膳食指南这类纯扫描件有 378 页要重新识别；缓存让"改切分/改解析后重跑"
# 不必每次都付这份几分钟到几十分钟的代价。
_OCR_CACHE_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                              ".cache", "ocr")

# 段落重组：行首是这些形态说明它是新的语义单元（编号条目/章节/图表题注），不与上一行合并
NEW_PARA_RE = re.compile(
    r"^([（(]?[0-9一二三四五六七八九十]{1,3}[、.．)）]"
    r"|[0-9]+(\.[0-9]+)+"
    r"|[表图]\s*[0-9]"
    r"|注\s*[0-9]"
    r"|附录"
    r"|第[一二三四五六七八九十]+[章节])"
)
# 行尾出现这些标点即认为该行已经收束，不再与下一行拼接
SENTENCE_END = "。！？：；"


def get_ocr(use_cuda: bool = True):
    """获取 OCR 引擎：优先 GPU 版 rapidocr_paddle，未安装则退回 CPU 版 onnxruntime"""
    try:
        from rapidocr_paddle import RapidOCR
        return RapidOCR(det_use_cuda=use_cuda, cls_use_cuda=use_cuda, rec_use_cuda=use_cuda)
    except ImportError:
        from rapidocr_onnxruntime import RapidOCR
        return RapidOCR()


def rotate_img(img, angle):
    """按给定角度绕图片中心旋转（用于把带 /Rotate 标记的 PDF 页面摆正后再 OCR）"""
    h, w = img.shape[:2]
    M = cv2.getRotationMatrix2D((w / 2, h / 2), angle, 1.0)
    return cv2.warpAffine(img, M, (w, h), flags=cv2.INTER_CUBIC)


def normalize_text(text) -> str:
    """文本归一化（去空白），用于重复行统计与相似度比较"""
    return re.sub(r"\s+", "", str(text or ""))


def is_page_number(text):
    """判断是否为页码"""
    text = text.strip()
    # 覆盖纯数字、- 3 -、· 3 ·、• 3 •、第 3 页、Page 3、3/100、中文数字页码等常见格式
    patterns = [
        r"^[\-–—]\s*\d+\s*[\-–—]$",
        r"^[·•●]\s*\d+\s*[·•●]$",
        r"^第\s*[0-9〇零一二三四五六七八九十百]+\s*页",
        r"^Page\s*\d+$",
        r"^\d+\s*[/／]\s*\d+$",
        r"^\d+$",
    ]
    return any(re.match(p, text, re.IGNORECASE) for p in patterns)


def is_in_edge(bbox, page_rect, header_ratio, footer_ratio) -> bool:
    """判断区域是否位于页面顶部/底部边缘带"""
    ph = page_rect.height
    return bbox[3] < ph * header_ratio or bbox[1] > ph * (1 - footer_ratio)


def collect_edge_lines(page, page_rect, header_ratio, footer_ratio) -> List[str]:
    """收集页面顶部/底部边缘带内的文本行，作为跨页页眉页脚识别的候选"""
    lines = []
    for block in page.get_text("dict")["blocks"]:
        if block.get("type") != 0 or "lines" not in block:
            continue
        if not is_in_edge(block["bbox"], page_rect, header_ratio, footer_ratio):
            continue
        for line in block["lines"]:
            text = "".join(span["text"] for span in line["spans"]).strip()
            if text:
                lines.append(text)
    return lines


def _cell_text(cell) -> str:
    """单元格文本归一化为单行：find_tables 会把同一格里换行的内容原样带回
    （如 "罗氏\\n分析系统"、"1.30～\\n2.40"）。如果直接写进 Markdown，
    一行数据会被拆成多个物理行，"一个物理行 = 一行表格"的前提就不成立，
    chunker.split_table_block 的"表头=前两行、按行分组"随之错位。"""
    return re.sub(r"\s+", " ", str(cell or "")).strip()


def rows_to_markdown(rows) -> str:
    """将表格原始单元格数据转为 Markdown 表格（每行数据严格占一个物理行）"""
    header = rows[0]
    md = "| " + " | ".join(_cell_text(c) for c in header) + " |\n"
    md += "| " + " | ".join("---" for _ in header) + " |\n"
    for row in rows[1:]:
        md += "| " + " | ".join(_cell_text(c) for c in row) + " |\n"
    return md


def rows_similar(row_a, row_b, threshold: float = 0.8) -> bool:
    """比较两行单元格内容是否相似（用于识别续表重复表头/截断行）"""
    if not row_a or not row_b or len(row_a) != len(row_b):
        return False
    a = "|".join(normalize_text(c) for c in row_a)
    b = "|".join(normalize_text(c) for c in row_b)
    if not a or not b:
        return False
    return difflib.SequenceMatcher(None, a, b).ratio() >= threshold


def merge_table_rows(prev_rows, curr_rows) -> List:
    """跨页表格合并：续表首行若与前表表头/末行重复则去重，其余行追加"""
    merged = [row[:] for row in prev_rows]
    start = 0
    if len(curr_rows) > 0 and (
        rows_similar(merged[0], curr_rows[0], threshold=0.75)
        or rows_similar(merged[-1], curr_rows[0], threshold=0.85)
    ):
        start = 1  # 续表重复了表头，或首行是上页被截断行的重复，跳过
    merged.extend(curr_rows[start:])
    return merged


def extract_body_lines(page, table_rects, repeating_lines, header_ratio, footer_ratio,
                       sort: bool) -> List[tuple]:
    """按给定取序抽取页面正文行（排除表格区域、页眉页脚与页码），返回 [(文本, bbox), ...]。

    sort=True  —— 按坐标（先 y 后 x）排序取块：单栏排版正确，双栏排版会把左右栏逐行交错；
    sort=False —— 按 PDF 内容流顺序取块：双栏期刊多数就是"写完左栏再写右栏"的书写顺序。
    """
    page_rect = page.rect
    out = []
    for block in page.get_text("dict", sort=sort)["blocks"]:
        if block.get("type") == 1 or "lines" not in block:  # 图片块后面单独 OCR
            continue
        block_rect = fitz.Rect(block["bbox"])
        # 与表格区域重叠的文本块跳过，避免表格内容在正文中重复出现
        if any(block_rect.intersects(r) for r in table_rects):
            continue
        for line in block["lines"]:
            line_text = "".join(span["text"] for span in line["spans"]).strip()
            if not line_text:
                continue
            # 边缘带内的行：重复文本行（跨页页眉页脚）或页码格式则过滤；
            # 非边缘带中部的纯数字行保留（可能是正文数据）
            if is_in_edge(line["bbox"], page_rect, header_ratio * 1.5, footer_ratio * 1.5):
                if normalize_text(line_text) in repeating_lines or is_page_number(line_text):
                    continue
            if any(fitz.Rect(line["bbox"]).intersects(r) for r in table_rects):
                continue
            out.append((line_text, line["bbox"]))
    return out


def column_switch_rate(lines, page_width: float) -> float:
    """相邻行在页面左右半区之间来回跳的比例：0 表示阅读顺序连贯。

    这是"两种取序哪个更像正常阅读顺序"的判据：双栏 PDF 若按 y 排序，
    正文行会在左右半区之间反复横跳（实测高血压指南 34.2%、糖尿病指南 38.1%）；
    按内容流顺序则基本停留在一栏内（3%~6%）。
    """
    if len(lines) < 2:
        return 0.0
    half = page_width / 2
    sides = [0 if (b[0] + b[2]) / 2 < half else 1 for _, b in lines]
    switches = sum(1 for a, b in zip(sides, sides[1:]) if a != b)
    return switches / (len(sides) - 1)


def prefer_flow_order(rate_sorted: float, rate_flow: float,
                      margin: float = COLUMN_SWITCH_MARGIN) -> bool:
    """内容流顺序（sort=False）是否比按坐标排序（sort=True）明显更连贯。

    要求"至少低 margin"而不是"更低就行"：像 WS/T 标准这类居中短行的文件，
    两种取序的交错率本来就相近（实测都 30% 上下，这个指标在单栏窄行版式上会误报），
    此时保持原行为更稳。
    """
    return rate_flow + margin <= rate_sorted


def pick_body_lines(page, table_rects, repeating_lines, header_ratio, footer_ratio) -> List[tuple]:
    """逐页择优取正文行：默认沿用 sort=True，仅当内容流顺序明显更连贯时才切换"""
    sorted_lines = extract_body_lines(page, table_rects, repeating_lines,
                                      header_ratio, footer_ratio, sort=True)
    flow_lines = extract_body_lines(page, table_rects, repeating_lines,
                                    header_ratio, footer_ratio, sort=False)
    if not sorted_lines or not flow_lines:
        return sorted_lines or flow_lines
    rate_sorted = column_switch_rate(sorted_lines, page.rect.width)
    rate_flow = column_switch_rate(flow_lines, page.rect.width)
    return flow_lines if prefer_flow_order(rate_sorted, rate_flow) else sorted_lines


def is_new_para(text: str) -> bool:
    """该行是否是一个新语义单元的开头（编号条目 / 章节标题 / 图表题注 / 短标题）。

    除了 NEW_PARA_RE 这类强标记，还认"1 范围"式的空格分隔编号章节标题，
    但只在该行足够短时才认 —— 正文里以数字开头的长句（"3 次测量的全部血压值…"）
    不能被当成标题，否则句子会被从中间劈开。
    """
    if NEW_PARA_RE.match(text):
        return True
    return len(text) <= 12 and re.match(r"^[0-9]{1,2}\s+\S", text) is not None


def reflow_paragraphs(lines: List[str]) -> str:
    """把"视觉行"拼回段落，段落之间用空行分隔。

    PDF 抽出来的每一行是排版行而不是语义行：实测高血压指南 98 页 27747 行、
    平均行长 9.7 字符，86% 的行不以终止标点收尾（句子被排版切断）。
    如果直接把每行都补一个换行交给 chunker，分隔符优先级里"换行"又高于句末标点，
    切分边界就会几乎全部落在句子中间（实测改造前父块仅 7.6% 落在句末）。
    拼接规则：上一行没有以句末标点收束、且当前行不是新的编号/章节/题注时，并入上一行。
    """
    paragraphs: List[str] = []
    for text in lines:
        if paragraphs:
            prev = paragraphs[-1]
            if (prev and prev[-1] not in SENTENCE_END and len(prev) >= 6
                    and not is_new_para(text)):
                paragraphs[-1] = prev + text
                continue
        if text.strip():
            paragraphs.append(text)
    return "\n\n".join(paragraphs) + "\n"


def ocr_image_cached(ocr, img_array, cache_key: str = None) -> List[str]:
    """对单张图片做 OCR，带磁盘缓存（缓存键形如 "<文件md5>/<页码>_<xref>"）。

    纯扫描件（如膳食指南 378 页）的每个页面内容都来自 OCR，
    每改一次解析/切分就要重新识别一遍；缓存后只有首次付出这份代价。
    """
    path = os.path.join(_OCR_CACHE_DIR, cache_key + ".txt") if cache_key else None
    if path and os.path.exists(path):
        try:
            with open(path, encoding="utf-8") as f:
                return [line for line in f.read().split("\n") if line.strip()]
        except Exception as e:  # 缓存损坏不该影响入库
            logger.warning(f"OCR 缓存读取失败（忽略缓存重新识别）: {e}")
    result, _ = ocr(img_array)
    lines = [line[1] for line in result] if result else []
    if path:
        try:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as f:
                f.write("\n".join(lines))
        except Exception as e:
            logger.warning(f"OCR 缓存写入失败（不影响本次识别结果）: {e}")
    return lines


def load_pdf_with_ocr(file_path: str, source: str,
                      ocr_threshold_w: float = 0.6, ocr_threshold_h: float = 0.6,
                      use_cuda: bool = True,
                      header_ratio: float = 0.08,
                      footer_ratio: float = 0.08,
                      file_md5: str = None) -> List[Document]:
    """加载 PDF（文字提取 + 图片 OCR + 跨页表格合并 + 跨页页眉页脚过滤）。

    每一页产出一个 Document（metadata 带 page），而不是整本拼成一个 Document：
      · 检索结果能溯源到页码；
      · 父块天然以页为界（纯扫描件本来就是这个行为：页间空行是唯一的段落分隔）。
    file_md5 用于给 OCR 结果建缓存键；不传则不做缓存。
    """
    documents = []
    try:
        doc = fitz.open(file_path)
        ocr = get_ocr(use_cuda=use_cuda)
        full_text = ""
        page_texts = []
        timestamp = datetime.now().isoformat()

        # 第一遍：跨页统计页眉页脚——边缘带内、在多页重复出现的文本行视为页眉页脚；
        # 页码因每页数字不同，另行用格式匹配兼位置判断兜底（不纳入重复率统计）
        edge_counter = Counter()
        for page in doc:
            for line in collect_edge_lines(page, page.rect, header_ratio * 1.5, footer_ratio * 1.5):
                if not is_page_number(line):
                    edge_counter[normalize_text(line)] += 1
        hf_threshold = max(3, int(doc.page_count * 0.3))
        repeating_lines = {norm for norm, cnt in edge_counter.items() if cnt >= hf_threshold}
        if repeating_lines:
            logger.info(f"跨页页眉页脚识别: 命中 {len(repeating_lines)} 种重复文本行")

        # 跨页表格状态：待合并表格的原始行数据与列数（写入正文前可能被下一页续表合并）
        pending_table_rows = None
        pending_table_cols = 0

        b_unit = tqdm(total=doc.page_count, desc=f"处理PDF: {os.path.basename(file_path)}")
        for page_num, page in enumerate(doc):
            page_rect = page.rect
            completed_tables = []

            # 1. 提取表格（保留原始单元格数据，支持跨页合并）
            try:
                tabs = page.find_tables()
            except Exception:
                tabs = []
            for tab in tabs:
                rows = tab.extract()
                if not rows:
                    continue
                bbox = tab.bbox
                cols = len(rows[0])
                # 整表落在页眉页脚边缘带内的（如页脚的简表）跳过
                if is_in_edge(bbox, page_rect, header_ratio, footer_ratio):
                    continue
                if pending_table_rows is not None and cols == pending_table_cols and (
                    rows_similar(pending_table_rows[0], rows[0], threshold=0.75)   # 续表重复了表头
                    or rows_similar(pending_table_rows[-1], rows[0], threshold=0.85)  # 首行是上页截断行的重复
                    or all(not str(c or "").strip() for c in rows[0])              # 首行全空（纯续行）
                ):
                    pending_table_rows = merge_table_rows(pending_table_rows, rows)
                else:
                    if pending_table_rows is not None:
                        completed_tables.append(rows_to_markdown(pending_table_rows))
                    pending_table_rows = rows
                    pending_table_cols = cols

            # 2. 提取正文文本（逐页择优的阅读顺序；排除表格区域、页眉页脚与页码），
            #    并把视觉行拼回段落——两者缺一不可：取序错则左右栏交错，不重组则句中被切断
            table_rects = [fitz.Rect(tab.bbox) for tab in tabs]
            body_lines = pick_body_lines(page, table_rects, repeating_lines,
                                        header_ratio, footer_ratio)
            text_portion = reflow_paragraphs([text for text, _ in body_lines])

            # 3. 页面文本组装：已完结的表格先写入，正文随后；未完结的表格留给下一页继续合并

            page_text = ""
            for t in completed_tables:
                page_text += f"\n[表格]\n{t}[/表格]\n"
            page_text += text_portion

            # 4. 提取图片并 OCR（带缓存：同一文件同一页只识别一次）
            for img in page.get_image_info(xrefs=True):
                if xref := img.get("xref"):
                    bbox = img["bbox"]
                    pw, ph = page_rect.width, page_rect.height
                    iw, ih = bbox[2] - bbox[0], bbox[3] - bbox[1]
                    if iw / pw < ocr_threshold_w or ih / ph < ocr_threshold_h:
                        continue
                    pix = fitz.Pixmap(doc, xref)
                    if int(page.rotation) != 0:
                        img_array = np.frombuffer(pix.samples, dtype=np.uint8).reshape(pix.height, pix.width, -1)
                        img_array = cv2.cvtColor(rotate_img(Image.fromarray(img_array), 360 - page.rotation), cv2.COLOR_RGB2BGR)
                    else:
                        img_array = np.frombuffer(pix.samples, dtype=np.uint8).reshape(pix.height, pix.width, -1)
                    cache_key = f"{file_md5}/{page_num + 1}_{xref}" if file_md5 else None
                    ocr_lines = ocr_image_cached(ocr, img_array, cache_key)
                    if ocr_lines:
                        ocr_text = "\n".join(ocr_lines)
                        page_text += f"\n[图片]\n{ocr_text}\n[/图片]\n"

            # 添加页面标记，并把本页单独作为一个 Document 输出（metadata 带 page）
            page_text = f"\n--- 第 {page_num + 1} 页 ---\n" + page_text
            page_texts.append(page_text)
            full_text += page_text
            documents.append(Document(
                page_content=page_text,
                metadata={"source": source, "file_path": file_path, "file_type": ".pdf",
                          "page": page_num + 1, "page_count": doc.page_count,
                          "timestamp": timestamp}
            ))
            b_unit.update(1)
        b_unit.close()

        # 文档处理完毕，冲刷最后一个仍在等待合并的跨页表格（用当前所在页号定位）
        if pending_table_rows is not None:
            page_texts[-1] += f"\n[表格]\n{rows_to_markdown(pending_table_rows)}[/表格]\n"
            full_text = "".join(page_texts)
            documents[-1].page_content = page_texts[-1]
        doc.close()

        if full_text.strip():
            logger.info(f"成功提取: {os.path.basename(file_path)} "
                        f"({len(full_text)} 字符 / {len(documents)} 页)")
        else:
            documents = []
    except Exception as e:
        logger.error(f"PDF 处理失败 {file_path}: {e}")
        documents = []
    return documents


def load_image_with_ocr(file_path: str, source: str, use_cuda: bool = True) -> List[Document]:
    """加载图片并 OCR"""
    documents = []
    try:
        ocr = get_ocr(use_cuda=use_cuda)
        img = cv2.imread(file_path)
        if img is None:
            return documents
        result, _ = ocr(img)
        if result:
            text = "\n".join([line[1] for line in result])
            documents.append(Document(
                page_content=text,
                metadata={"source": source, "file_path": file_path,
                          "file_type": os.path.splitext(file_path)[1], "timestamp": datetime.now().isoformat()}
            ))
            logger.info(f"成功提取图片: {os.path.basename(file_path)} ({len(text)} 字符)")
    except Exception as e:
        logger.error(f"图片处理失败 {file_path}: {e}")
    return documents


def load_txt_file(file_path: str, source: str) -> List[Document]:
    """加载文本文件"""
    documents = []
    try:
        with open(file_path, "r", encoding="utf-8") as f:
            text = f.read()
        if text.strip():
            documents.append(Document(
                page_content=text,
                metadata={"source": source, "file_path": file_path,
                          "file_type": os.path.splitext(file_path)[1], "timestamp": datetime.now().isoformat()}
            ))
            logger.info(f"成功加载: {os.path.basename(file_path)} ({len(text)} 字符)")
    except Exception as e:
        logger.error(f"文本加载失败 {file_path}: {e}")
    return documents