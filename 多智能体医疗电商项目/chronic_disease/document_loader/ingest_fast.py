"""入库脚本：把 data/<知识源>/ 下的原始文档解析、分块、向量化后写进 Milvus。

    用法（在 chronic_disease 目录下）：
        python document_loader/ingest_fast.py                 # 全量重建（先删集合再重建索引）
        python document_loader/ingest_fast.py --incremental   # 增量入库（保留集合与索引，按指纹跳过未变文件）
        python document_loader/ingest_fast.py --source lab     # 只处理某一个知识源

    增量模式靠 ingest_manifest.json 里的文件指纹（内容 md5 + 产生的块 id）工作：
    未变更的文件整体跳过；变更的文件重新入库并清理旧版本遗留的块；
    磁盘上已删除的文件，其块也会被一并清理，避免脏数据留在库里。

    另：PDF 的图片 OCR 结果按「文件 md5 / 页码_xref」缓存在 chronic_disease/.cache/ocr，
    因此全量重建（会重置指纹清单、重新解析所有文件）时，纯扫描件也只需识别一次。
"""
import os
import sys
import json
import hashlib
import argparse

project_root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, project_root)

from base.config import Config
from base.logger import logger
from document_loader.loaders import load_pdf_with_ocr, load_image_with_ocr, load_txt_file
from document_loader.chunker import process_documents
from document_loader.vector_store import VectorStore

SUPPORTED_EXTS = {'.pdf', '.jpg', '.png', '.jpeg', '.txt', '.md'}
# 文件指纹清单：记录每个文件的内容 md5 与其产生的块 id，实现"未变更文件直接跳过"
MANIFEST_PATH = os.path.join(project_root, "chronic_disease", "ingest_manifest.json")


def file_md5(path: str) -> str:
    """计算文件内容 md5（分块读取，避免大 PDF 一次性读进内存）"""
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def chunk_id(text: str) -> str:
    """块主键：与 VectorStore.add_documents 的缺省规则一致（内容 md5）"""
    return hashlib.md5(text.encode()).hexdigest()


def load_manifest() -> dict:
    """读取文件指纹清单；文件不存在或损坏时返回空清单（宁可重新入库，不可漏入库）"""
    if os.path.exists(MANIFEST_PATH):
        try:
            with open(MANIFEST_PATH, encoding="utf-8") as f:
                return json.load(f)
        except Exception as e:
            logger.warning(f"指纹清单读取失败，视为空清单重新入库: {e}")
    return {}


def save_manifest(manifest: dict):
    """写回文件指纹清单（每个文件处理完立即调用，中途中断也能保住已完成的进度）"""
    with open(MANIFEST_PATH, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)


def load_file_docs(fp: str, ext: str, st: str, doc_cfg: dict, file_md5: str = None) -> list:
    """按扩展名解析单个文件为文档列表（PDF 传入内容 md5 以便复用 OCR 缓存）"""
    if ext == '.pdf':
        return load_pdf_with_ocr(
            fp, st,
            ocr_threshold_w=doc_cfg.get('pdf_ocr_threshold_w', 0.6),
            ocr_threshold_h=doc_cfg.get('pdf_ocr_threshold_h', 0.6),
            use_cuda=doc_cfg.get('ocr_use_cuda', True),
            file_md5=file_md5
        )
    if ext in {'.jpg', '.png', '.jpeg'}:
        return load_image_with_ocr(fp, st, use_cuda=doc_cfg.get('ocr_use_cuda', True))
    if ext in {'.txt', '.md'}:
        return load_txt_file(fp, st)
    return []


def ingest_documents(data_dir: str, source_types: list, vs, chunk_cfg: dict, doc_cfg: dict,
                     incremental: bool = False) -> int:
    """入库主流程（参数化）。

    incremental=True 时启用文件级指纹清单：
    - 内容 md5 未变的文件整体跳过（不解析、不向量化、不写入）
    - 变更的文件重新入库，并删除旧版本遗留、新版本不存在的块
    - 已从磁盘消失的文件，清理其全部块
    """
    manifest = load_manifest() if incremental else {}
    total, skipped = 0, 0
    for st in source_types:
        src_dir = os.path.join(data_dir, st)
        if not os.path.exists(src_dir):
            continue
        files = [f for f in os.listdir(src_dir) if os.path.splitext(f)[1].lower() in SUPPORTED_EXTS]
        if not files:
            continue

        logger.info(f"处理 {st}，共 {len(files)} 个文件")
        pending_docs, pending_ids = [], []
        for fn in files:
            fp = os.path.join(src_dir, fn)
            rel_key = f"{st}/{fn}"
            fingerprint = file_md5(fp)
            old_entry = manifest.get(rel_key)

            if incremental and old_entry and old_entry.get("md5") == fingerprint:
                skipped += 1
                logger.info(f"跳过未变更文件 {rel_key}（{len(old_entry.get('chunk_ids', []))} 块已在库）")
                continue

            ext = os.path.splitext(fn)[1].lower()
            docs = load_file_docs(fp, ext, st, doc_cfg, file_md5=fingerprint)
            if not docs:
                continue
            chunks = process_documents(
                docs,
                parent_size=chunk_cfg.get('parent_chunk_size', 1000),
                child_size=chunk_cfg.get('child_chunk_size', 200),
                overlap=chunk_cfg.get('chunk_overlap', 30)
            )
            if not chunks:
                continue
            ids = [chunk_id(c.page_content) for c in chunks]
            pending_docs.extend(chunks)
            pending_ids.extend(ids)

            if incremental and old_entry:
                # 变更文件：删除旧版本遗留、新版本不存在的块，避免脏数据残留
                stale = [i for i in old_entry.get("chunk_ids", []) if i not in set(ids)]
                if stale:
                    logger.info(f"{rel_key} 内容变更，清理旧块 {len(stale)} 条")
                    vs.delete_by_ids(stale)
            # 逐文件登记指纹并立即落盘（中途被中断，已完成文件下次也能跳过）
            manifest[rel_key] = {"md5": fingerprint, "chunk_ids": ids}
            save_manifest(manifest)

        if pending_docs:
            vs.add_documents(pending_docs, ids=pending_ids)
            total += len(pending_docs)
            logger.info(f"{st} 入库 {len(pending_docs)} 块")

        # 清理已从磁盘消失的文件：删块 + 移除指纹
        live_keys = {f"{st}/{f}" for f in files}
        for key in [k for k in manifest if k.startswith(f"{st}/") and k not in live_keys]:
            stale = manifest.pop(key).get("chunk_ids", [])
            if stale:
                logger.info(f"{key} 已删除，清理其在库中的 {len(stale)} 块")
                vs.delete_by_ids(stale)

    if incremental:
        save_manifest(manifest)
    logger.info(f"本次入库 {total} 块，跳过未变更文件 {skipped} 个")
    return total


def main(config_path: str = None, source_filter: str = None, incremental: bool = False):
    """主入口

    incremental=False（默认）：全量重建（删集合重建 + 重建索引）
    incremental=True：增量入库（保留已有集合与索引；未变更文件按指纹直接跳过，
    变更文件重新入库并清理旧块，适合新增/更新少量文档后快速入库）
    """
    conf = Config(config_path) if config_path else Config()
    chronic_conf = Config(os.path.join(project_root, "chronic_disease", "config.ini"))

    # 从配置读取参数
    db_name = chronic_conf.config.get("milvus", "database_name", fallback="chronic_disease")
    collection_name = chronic_conf.config.get("milvus_collection", "collection_name", fallback="chronic_disease_rag")
    model_path = chronic_conf.config.get("models", "bge_m3_path", fallback=r"D:\xuexi\model\bge-m3")
    dense_dim = chronic_conf.config.getint("milvus_collection", "dense_dim", fallback=1024)
    nlist = chronic_conf.config.getint("milvus_collection", "nlist", fallback=128)
    drop_ratio = chronic_conf.config.getfloat("milvus_collection", "drop_ratio_build", fallback=0.2)

    all_sources = [s.strip() for s in chronic_conf.config.get("chronic_disease", "valid_sources", fallback="disease,medication,lifestyle,lab,risk").split(",")]
    # 如果指定了 source_filter，只处理该源
    source_types = [source_filter] if source_filter and source_filter in all_sources else all_sources
    data_dir = os.path.abspath(os.path.join(project_root, chronic_conf.config.get("document", "data_dir", fallback="chronic_disease/data")))

    chunk_cfg = {
        "parent_chunk_size": chronic_conf.config.getint("chunk", "parent_chunk_size", fallback=1000),
        "child_chunk_size": chronic_conf.config.getint("chunk", "child_chunk_size", fallback=200),
        "chunk_overlap": chronic_conf.config.getint("chunk", "chunk_overlap", fallback=30),
    }
    doc_cfg = {
        "pdf_ocr_threshold_w": chronic_conf.config.getfloat("document", "pdf_ocr_threshold_w", fallback=0.6),
        "pdf_ocr_threshold_h": chronic_conf.config.getfloat("document", "pdf_ocr_threshold_h", fallback=0.6),
        "ocr_use_cuda": chronic_conf.config.getboolean("document", "ocr_use_cuda", fallback=True),
    }

    print(f"向量模型: BGE-M3 | 数据库: {db_name} | 集合: {collection_name} | 模式: {'增量' if incremental else '全量重建'}")
    print(f"数据目录: {data_dir}")
    print("-" * 50)
    for st in source_types:
        sd = os.path.join(data_dir, st)
        if os.path.exists(sd):
            fs = [f for f in os.listdir(sd) if os.path.splitext(f)[1].lower() in SUPPORTED_EXTS]
            if fs:
                print(f"  {st}/: {', '.join(fs)}")
    print("-" * 50)
    print("开始入库...")

    # 增量模式：不删集合（rebuild=False），跳过重排序模型加载节省内存；
    # 全量模式：删库重建，同样不加载重排序器（入库不检索）
    try:
        vs = VectorStore(db_name=db_name, collection_name=collection_name, model_path=model_path,
                         dense_dim=dense_dim, rebuild=not incremental, load_reranker=False)
    except RuntimeError:
        # 首次增量入库时集合尚不存在：自动降级为创建模式（此时库本就是空的，无数据丢失风险）
        print("集合尚不存在，自动切换为创建模式")
        vs = VectorStore(db_name=db_name, collection_name=collection_name, model_path=model_path,
                         dense_dim=dense_dim, rebuild=True, load_reranker=False)
    if not incremental:
        # 全量重建后集合是全新的，旧指纹全部失效，重置清单避免误跳过
        save_manifest({})
        logger.info("全量重建模式：已重置文件指纹清单")
    count = ingest_documents(data_dir, source_types, vs, chunk_cfg, doc_cfg, incremental=incremental)
    print(f"\n入库完成，共 {count} 个文档块")

    if incremental:
        # 增量模式不重建索引：新增数据直接写入已有索引（IVF_FLAT 增量可查），避免分钟级重建耗时；
        # 大量新增后如需优化召回，可单独跑一次全量模式重建索引
        print("增量模式：跳过索引重建（新数据已写入现有索引）")
    else:
        print("\n构建索引...")
        vs.build_index(nlist=nlist, drop_ratio=drop_ratio)
    print("全部完成！")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="文档入库脚本")
    parser.add_argument("--config", type=str, help="配置文件路径")
    parser.add_argument("--source", type=str, help="只处理指定知识源（如 lifestyle）")
    parser.add_argument("--incremental", action="store_true",
                        help="增量入库：保留已有集合与索引，按内容 md5 幂等 upsert，不删库不重建索引")
    args = parser.parse_args()
    main(config_path=args.config, source_filter=args.source, incremental=args.incremental)