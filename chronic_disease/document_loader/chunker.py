"""文档分块：把解析后的长文档切成适合检索的「父子块」，并把表格单独保护起来。

【为什么要分块】
    整篇指南有几十页，直接整篇做向量检索既不准（一个向量概括不了全篇）也塞不进提示词。
    切成小块后，问题只需匹配到最相关的那几块即可。

【为什么要「父子块」】
    小块的向量更聚焦、匹配更准，但内容太短、缺乏上下文。
    因此每个父块（约 1000 字）再切成若干子块（约 200 字）：
    用子块去匹配问题，命中后回退返回父块全文 —— 既保证匹配精度，又保证给大模型的上下文完整。

【为什么要单独处理表格】
    表格一旦被 200 字的子块拦腰切断，就会丢掉表头，切出来的行数据无法理解。
    因此表格走保护性分块：小表整块保留，大表按行切分但每块都重新带上表头。

【为什么父块序号要按"文件"而不是按"Document"计数】
    loaders 现在**按页**产出 Document（每页一个，metadata 带 page）。
    如果序号在每页开头重置，同一篇文档每页的块都会得到 `文件_p0`、`文件_p1`……
    不同页之间大量撞号，检索去重（按 parent_id 合并）会把它们当成同一段而丢弃内容。
    所以 p_idx 必须跨页连续，按 file_path 记在 p_idx_by_doc 里。
"""
import os
import re
from datetime import datetime
from typing import List
from langchain_core.documents import Document
from langchain_text_splitters import RecursiveCharacterTextSplitter
from base.logger import logger

# 页码标记（如 "--- 第 3 页 ---"）：对检索无价值，入库前清洗掉，避免污染向量与上下文
PAGE_MARK_RE = re.compile(r"-{2,}\s*第\s*\d+\s*页\s*-{2,}")
# 表格块标记：loaders 提取的表格以 [表格]...[/表格] 包裹，需保护性分块，避免表格被拦腰切断失去表头
TABLE_BLOCK_RE = re.compile(r"\[表格\]\n(.*?)\[/表格\]", re.S)

# parent_id 的长度上限（对应 Milvus 里该字段的长度）
PARENT_ID_MAX_LEN = 90


def make_parent_id(doc_id: str, p_idx: int, max_len: int = PARENT_ID_MAX_LEN) -> str:
    """生成块的稳定标识 parent_id，形如「文件名_p序号」。

    【为什么需要这个函数】
        原来两处都是直接写 `f"{文件名}_p{序号}"[:90]`，即对**整串**截断。
        文件名很长时（>= 88 字符）会把末尾的序号也切掉：
            "AAAA…(88个A)_p1"  -> [:90] -> "AAAA…_p"
            "AAAA…(88个A)_p12" -> [:90] -> "AAAA…_p"     ← 和上面撞成同一个 id！
        后果：同一篇文档的不同块在检索去重时被当成"重复"而互相覆盖，内容被丢掉。

        修复方式：**只截断文件名部分，完整保留 "_p序号" 后缀**，唯一性优先。

    【对已有数据的影响】
        现在语料里最长的文件名只有 26 字符，根本不会触发截断，
        因此本函数对短文件名返回的结果与旧实现**逐字节相同** ——
        已入库的 7384 条数据无需重新入库。
    """
    name = os.path.basename(doc_id or "")
    suffix = f"_p{p_idx}"
    keep = max_len - len(suffix)
    if keep <= 0:
        # 极端配置：max_len 比后缀还短。此时唯一性优先，只保留后缀。
        return suffix[-max_len:] if max_len > 0 else suffix
    if len(name) > keep:
        name = name[:keep]
    return name + suffix


def split_table_block(table_md: str, max_size: int) -> List[str]:
    """表格块分块：小于阈值的整块保留；超大的按行分组，每块重新携带表头，保证每块自含语义"""
    lines = [line for line in table_md.split("\n") if line.strip()]
    if len(table_md) <= max_size:
        return [table_md]
    # 表头 = 前两行（标题行 + 分隔行）
    header_lines = lines[:2]
    header_len = sum(len(line) for line in header_lines)
    chunks = []
    current = list(header_lines)
    current_len = header_len
    for line in lines[2:]:
        # 当前块已容纳至少一行数据且即将超限：先落盘，新块仍带表头继续累积（避免单行超长死循环）
        if current_len + len(line) > max_size and len(current) > 2:
            chunks.append("\n".join(current))
            current = list(header_lines)
            current_len = header_len
        current.append(line)
        current_len += len(line)
    if len(current) > 2:
        chunks.append("\n".join(current))
    return chunks


def process_documents(documents: List[Document], parent_size: int = 1000,
                      child_size: int = 200, overlap: int = 30,
                      separators: List[str] = None,
                      keep_separator: bool = True) -> List[Document]:
    """父子分块（参数化）：正文走父子分块；表格块保护性分块（小块整块保留，大块按行带表头切分）

    documents 可以是一篇文档的多个分页 Document（loaders 的现状），
    此时父块序号跨页连续，块的 metadata 会带上文档级字段（source/file_path/page/page_count）。
    """
    if separators is None:
        separators = ["\n\n", "\n", "。|！|？", "；|;\s", "，|,\s", " ", ""]

    chunks = []
    parent_splitter = RecursiveCharacterTextSplitter(
        chunk_size=parent_size, chunk_overlap=overlap,
        separators=separators, keep_separator=keep_separator, is_separator_regex=True
    )
    child_splitter = RecursiveCharacterTextSplitter(
        chunk_size=child_size, chunk_overlap=overlap,
        separators=separators, keep_separator=keep_separator, is_separator_regex=True
    )

    # 每篇文档（按 file_path 区分）的父块序号，跨该文档的所有分页 Document 连续累加
    p_idx_by_doc = {}

    for doc in documents:
        if not doc.page_content or not doc.page_content.strip():
            continue
        source = doc.metadata.get("source", "unknown")
        doc_id = doc.metadata.get("file_path", "")
        page_count = doc.metadata.get("page_count", 1)

        # 清洗页码标记等噪声，并按表格标记切分为 正文段/表格段 交替序列，表格单独保护性分块，
        # 避免 200 字子块把表格拦腰切断、失去表头后检索质量差的问题。
        text = PAGE_MARK_RE.sub("\n", doc.page_content)
        segments = []
        last_end = 0
        for match in TABLE_BLOCK_RE.finditer(text):
            if match.start() > last_end:
                segments.append(("text", text[last_end:match.start()]))
            segments.append(("table", match.group(1)))
            last_end = match.end()
        if last_end < len(text):
            segments.append(("text", text[last_end:]))

        # 每篇文档独立计数父块序号，保证 parent_id 唯一（跨分页 Document 连续）
        p_idx = p_idx_by_doc.get(doc_id, 0)
        for kind, seg in segments:
            if kind == "table":
                # 表格块：小块整块保留，大块按行分组且每块带表头；每块自成父子块（子=父）
                # metadata 继承文档级字段（含 page），便于检索结果溯源到页码
                for table_chunk in split_table_block(seg, parent_size):
                    chunks.append(Document(
                        page_content=table_chunk,
                        metadata={
                            **doc.metadata,
                            "parent_id": make_parent_id(doc_id, p_idx),
                            "parent_content": table_chunk,
                            "source": source,
                            "timestamp": datetime.now().isoformat(),
                            "page_count": page_count
                        }
                    ))
                    p_idx += 1
            else:
                # 正文段：父块切分，每个父块再切子块，子块携带父块信息用于检索后回退上下文（原有逻辑）
                for parent in parent_splitter.split_documents(
                        [Document(page_content=seg, metadata=doc.metadata)]):
                    for child in child_splitter.split_documents([parent]):
                        child.metadata.update({
                            "parent_id": make_parent_id(doc_id, p_idx),
                            "parent_content": parent.page_content,
                            "source": source,
                            "timestamp": datetime.now().isoformat(),
                            "page_count": page_count
                        })
                        chunks.append(child)
                    p_idx += 1
        p_idx_by_doc[doc_id] = p_idx

    logger.info(f"分块完成，共 {len(chunks)} 个子块")
    return chunks
