"""向量库封装（Milvus + BGE-M3 嵌入 + BGE-Reranker 重排）。

【小白先看：什么是「向量检索」，为什么要它】
    传统搜索是「关键词匹配」：搜「血压高」就只会找含「血压高」三个字的段落，
    文档里写「高血压」就搜不到。
    向量检索则是把每段文字变成一串数字（叫「向量」），语义相近的文字，
    它们的向量在数学上也靠得近。于是搜「血压高」也能找到「高血压」的段落。

    本项目用两个模型配合：
        · BGE-M3        —— 把文字转成向量（嵌入 / embedding），支持稠密+稀疏混合检索
        · BGE-Reranker  —— 把初步召回的候选段落重新精细排序（重排 / rerank），
                           因为「粗略相似」不等于「真的回答了问题」

【数据放在哪】
    Milvus 服务端（192.168.100.128:19530）
      └─ 数据库 chronic_disease
           └─ 集合 chronic_disease_rag（本项目的知识库，约 7384 条）
    ⚠️ 用 pymilvus 连接时**必须带 db_name='chronic_disease'**，
       否则会连到默认的 default 库（里面只有一个无关的 demo2 集合），
       从而误以为"数据不见了"。

【本文件在整个流程中的位置】
    入库（一次性）：文档 -> 解析 -> 分块 -> BGE-M3 转向量 -> 存进 Milvus
    查询（每次提问）：问题 -> BGE-M3 转向量 -> Milvus 找相似段落
                     -> Reranker 重排 -> 返回最相关的几段给大模型做参考
"""
import hashlib
import threading
import time
from typing import List, Optional

import numpy as np
from langchain_core.documents import Document
from pymilvus import MilvusClient, DataType, AnnSearchRequest, WeightedRanker
from base.logger import logger
from base.config import Config

# 重依赖（torch / sentence_transformers / milvus_model）**不在此处导入**：
# 它们合计约占 19s 的导入耗时。改为在 VectorStore.__init__ 中按需导入，
# 使 `import document_loader.vector_store` 与 `import app` 保持轻量、快速。
torch = None
CrossEncoder = None
BGEM3EmbeddingFunction = None


def _load_heavy_deps():
    """按需导入模型相关重依赖（幂等，首次调用时加载）"""
    global torch, CrossEncoder, BGEM3EmbeddingFunction
    if torch is not None:
        return
    import torch as _torch
    from sentence_transformers import CrossEncoder as _CrossEncoder
    from milvus_model.hybrid import BGEM3EmbeddingFunction as _BGEM3
    torch = _torch
    CrossEncoder = _CrossEncoder
    BGEM3EmbeddingFunction = _BGEM3

# 默认配置
_CHRONIC_DB = "chronic_disease"
_CHRONIC_COLLECTION = "chronic_disease_rag"
_BGE_M3_PATH = r"D:\xuexi\model\bge-m3"
_RERANKER_PATH = r"D:\xuexi\model\bge-reranker-large"


class VectorStore:
    """慢性病向量存储（Milvus）"""

    def __init__(self, db_name: str = _CHRONIC_DB,
                 collection_name: str = _CHRONIC_COLLECTION,
                 model_path: str = _BGE_M3_PATH,
                 reranker_path: str = _RERANKER_PATH,
                 dense_dim: int = 1024, use_fp16: bool = False,
                 rebuild: bool = True,
                 load_reranker: bool = True,
                 retrieval_k: int = 5, candidate_m: int = 2,
                 dense_weight: float = 1.0, sparse_weight: float = 0.7,
                 valid_sources: list = None, per_source_k: int = 2):
        """加载嵌入/重排模型与 Milvus 连接。

        参数含义（默认值取 config.ini，见 create_vector_store）：
          rebuild=True   入库模式：删除并重建集合；False 查询模式：加载已有集合。
          load_reranker  入库时不需要重排，置 False 可省下约 1.4GB 模型加载。
          retrieval_k/candidate_m  每源召回条数 / 最终交给大模型的块数。
          dense_weight/sparse_weight  稠密与稀疏检索的融合权重。
          valid_sources/per_source_k  全局检索的跨源平衡参数，见 hybrid_search_with_reranker。
        """
        self.db_name = db_name
        self.collection_name = collection_name
        self.dense_dim = dense_dim
        # 检索参数（可由 config.ini 配置，不再写死）
        self.retrieval_k = retrieval_k
        self.candidate_m = candidate_m
        self.dense_weight = dense_weight
        self.sparse_weight = sparse_weight
        # 全局检索跨源平衡参数：valid_sources 列出所有知识源，
        # 全局检索（无 source_filter）时每个源各取 per_source_k 个候选，避免大块数源垄断候选池
        self.valid_sources = valid_sources or ["disease", "medication", "lifestyle", "lab", "risk"]
        self.per_source_k = per_source_k
        # page 是后加的字段：老集合（未重建）没有它，查询时请求不存在的字段会直接报错。
        # 因此延迟探测一次并缓存，保证"代码先上线、重建还没跑"或"回滚到旧集合"时检索仍可用。
        self._has_page_field = None

        conf = Config()
        # 实例化时才导入 torch / sentence_transformers / milvus_model：
        # 模块级导入它们会让 `import document_loader.vector_store` 耗时约 19s，
        # 进而阻塞任何 `import app`。此处按需加载，语义不变。
        _load_heavy_deps()
        # BGE-M3 嵌入与 CrossEncoder 重排序推理均非线程安全（LangGraph 并行专家同时检索
        # 会触发 "Already borrowed" 崩溃），模型推理必须串行化；Milvus 检索本身可并发
        self._infer_lock = threading.Lock()
        # 显存预算（RTX 4060 8GB）：Ollama LLM 常驻约 5GB，嵌入用 fp16（约 1.1GB）放 GPU，
        # 重排序器（fp32 约 2.2GB）固定 CPU，避免三者同卡 OOM
        device = 'cuda' if torch.cuda.is_available() else 'cpu'
        self.embedding = BGEM3EmbeddingFunction(
            model_name_or_path=model_path, use_fp16=(device == 'cuda'), device=device
        )
        # 入库场景不做检索，可跳过加载 1.4GB 重排序模型以节省内存/显存（load_reranker=False）
        self.reranker = CrossEncoder(reranker_path, device="cpu") if load_reranker else None
        self.client = MilvusClient(
            uri=f"http://{conf.MILVUS_HOST}:{conf.MILVUS_PORT}", db_name=db_name
        )
        # rebuild=True 入库模式：删除并重建集合；False 查询模式：加载已有集合（勿重建，否则清空数据）
        if rebuild:
            self._init_collection()
        else:
            self._prepare_for_search()

    def _prepare_for_search(self):
        """查询模式：校验集合存在并加载到内存"""
        if not self.client.has_collection(self.collection_name):
            raise RuntimeError(
                f"集合 {self.db_name}.{self.collection_name} 不存在，"
                f"请先运行 chronic_disease/document_loader/ingest_fast.py 完成入库与索引构建"
            )
        self.client.load_collection(self.collection_name)
        logger.info(f"集合 {self.collection_name} 已加载，可执行检索")

    def _init_collection(self):
        """入库模式：删除同名旧集合并按当前 schema 重建（会清空数据，故只用于重建入库）"""
        if self.client.has_collection(self.collection_name):
            logger.info(f"删除旧集合 {self.collection_name}")
            self.client.drop_collection(self.collection_name)

        schema = self.client.create_schema(auto_id=False, enable_dynamic_field=True)
        schema.add_field("id", DataType.VARCHAR, max_length=100, is_primary=True)
        schema.add_field("text", DataType.VARCHAR, max_length=65535)
        schema.add_field("dense_vector", DataType.FLOAT_VECTOR, dim=self.dense_dim)
        schema.add_field("sparse_vector", DataType.SPARSE_FLOAT_VECTOR)
        schema.add_field("parent_id", DataType.VARCHAR, max_length=100)
        schema.add_field("parent_content", DataType.VARCHAR, max_length=65535)
        schema.add_field("source", DataType.VARCHAR, max_length=50)
        schema.add_field("timestamp", DataType.VARCHAR, max_length=50)
        # 页码：医学引用场景需要能回溯到指南的具体页（loaders 按页产出 Document 时写入）
        schema.add_field("page", DataType.INT64)

        self.client.create_collection(collection_name=self.collection_name, schema=schema)
        logger.info(f"集合 {self.collection_name} 创建成功")

    def add_documents(self, documents: List[Document], batch_size: int = 512, ids: Optional[List[str]] = None):
        """写入向量。ids 与 documents 一一对应；缺省时按内容 md5 生成（幂等 upsert）"""
        texts = [doc.page_content for doc in documents]
        emb = self.embedding(texts)
        data = []
        for i, doc in enumerate(documents):
            sparse = {}
            row = emb['sparse'][[i]]
            for idx, val in zip(row.indices, row.data):
                sparse[int(idx)] = float(val)
            data.append({
                "id": ids[i] if ids else hashlib.md5(doc.page_content.encode()).hexdigest(),
                "text": doc.page_content,
                "dense_vector": np.asarray(emb['dense'][i], dtype=np.float32),
                "sparse_vector": sparse,
                "parent_id": doc.metadata.get("parent_id", "")[:90],
                "parent_content": doc.metadata.get("parent_content", ""),
                "source": doc.metadata.get("source", "unknown"),
                "timestamp": doc.metadata.get("timestamp", "unknown"),
                # 页码缺失（老数据、md/txt 单文档）记 0，检索端据此判断"无页码信息"
                "page": int(doc.metadata.get("page") or 0)
            })
        # 分批写入，避免单次请求超过 Milvus 消息大小限制（大批量文档入库时尤其必要）
        for start in range(0, len(data), batch_size):
            batch = data[start:start + batch_size]
            self.client.upsert(collection_name=self.collection_name, data=batch)
            logger.info(f"已保存 {start + len(batch)}/{len(data)} 条向量")

    def delete_by_ids(self, ids: List[str], batch_size: int = 1000):
        """按主键批量删除（用于清理文件变更/删除后遗留的旧块）"""
        removed = 0
        for start in range(0, len(ids), batch_size):
            batch = ids[start:start + batch_size]
            self.client.delete(collection_name=self.collection_name, ids=batch)
            removed += len(batch)
            logger.info(f"已删除旧块 {removed}/{len(ids)} 条")
        return removed

    def build_index(self, nlist: int = 128, drop_ratio: float = 0.2):
        """入库后构建索引"""
        logger.info("构建索引...")
        ip = self.client.prepare_index_params()
        # pymilvus 2.6 的 add_index 位置参数受限，metric_type/params 必须用关键字传递，否则报 TypeError
        ip.add_index(field_name="dense_vector", index_name="dense_index", index_type="IVF_FLAT", metric_type="IP", params={"nlist": nlist})
        ip.add_index(field_name="sparse_vector", index_name="sparse_index", index_type="SPARSE_INVERTED_INDEX", metric_type="IP",
                     params={"drop_ratio_build": drop_ratio})
        self.client.create_index(self.collection_name, index_params=ip)
        self.client.load_collection(self.collection_name)
        logger.info("索引构建完成，集合已加载")

    def _output_fields(self) -> List[str]:
        """检索请求的返回字段：集合里有 page 字段才请求它（见 __init__ 的说明）"""
        base = ["text", "parent_id", "parent_content", "source", "timestamp"]
        if self._has_page_field is None:
            try:
                fields = self.client.describe_collection(self.collection_name).get("fields", [])
                self._has_page_field = any(f.get("name") == "page" for f in fields)
            except Exception as e:
                logger.warning(f"集合字段探测失败，按无 page 字段处理: {e}")
                self._has_page_field = False
        return base + (["page"] if self._has_page_field else [])

    def _hybrid_candidates(self, dense_vector, sparse_vector, k: int,
                           expr: str) -> List[Document]:
        """带过滤条件（expr 为空=全库）的混合检索候选，返回去重前 Document 列表。

        供单源检索与全局跨源平衡检索复用；含 Milvus 健壮性重试（服务重启后集合被卸载时自动重载）。
        """
        # Milvus 2.6 新表达式解析器要求 ==（单 = 会报 token recognition error）
        sparse_req = AnnSearchRequest(
            data=[sparse_vector], anns_field="sparse_vector",
            param={"metric_type": "IP"}, limit=k, expr=expr
        )
        dense_req = AnnSearchRequest(
            data=[dense_vector], anns_field="dense_vector",
            param={"metric_type": "IP", "params": {"nprobe": 10}}, limit=k, expr=expr
        )
        ranker = WeightedRanker(self.dense_weight, self.sparse_weight)
        out_fields = self._output_fields()
        try:
            result = self.client.hybrid_search(
                collection_name=self.collection_name,
                reqs=[dense_req, sparse_req], ranker=ranker, limit=k,
                output_fields=out_fields
            )[0]
        except Exception as e:
            logger.warning(f"检索失败（{e}），尝试重新加载集合后重试")
            self.client.load_collection(self.collection_name)
            result = self.client.hybrid_search(
                collection_name=self.collection_name,
                reqs=[dense_req, sparse_req], ranker=ranker, limit=k,
                output_fields=out_fields
            )[0]
        return [self._doc_from_hit(hit["entity"]) for hit in result]

    def hybrid_search_with_reranker(self, query: str, k: int = None,
                                     source_filter: str = None,
                                     candidate_m: int = None) -> List[Document]:
        """混合检索 + 重排序（k/候选数/权重默认取构造参数，可临时覆盖）。

        单源检索（agent 各自的 source_filter，如 lab 只搜 lab 源）：行为不变，取本源 k 个候选重排。
        全局检索（source_filter=None，评测与兜底检索）：跨源平衡——对每个知识源各取 per_source_k 个候选
        再统一重排，避免 disease 等大块数源（占库 70%+）垄断候选池、让 lab/lifestyle/medication/risk
        的小块数源永远无法进入最终结果。
        """
        t0 = time.time()
        k = k or self.retrieval_k
        candidate_m = candidate_m or self.candidate_m
        with self._infer_lock:
            emb = self.embedding([query])
        # fp16 嵌入模型输出 float16，Milvus 集合字段是 FLOAT（fp32），统一转回 fp32
        dense_vector = np.asarray(emb["dense"][0], dtype=np.float32)
        sparse_vector = {}
        row = emb["sparse"][[0]]
        for idx, val in zip(row.indices, row.data):
            sparse_vector[int(idx)] = float(val)

        if source_filter:
            chunks = self._hybrid_candidates(
                dense_vector, sparse_vector, k, f"source == '{source_filter}'")
        else:
            chunks = []
            for src in self.valid_sources:
                try:
                    chunks.extend(self._hybrid_candidates(
                        dense_vector, sparse_vector, self.per_source_k, f"source == '{src}'"))
                except Exception as e:
                    logger.warning(f"跨源平衡检索 {src} 失败: {e}")
            logger.info(f"全局平衡检索候选: {len(chunks)} 块"
                        f"（{len(self.valid_sources)} 源 × {self.per_source_k}）")

        # 去重。
        #
        # 【为什么不能用正文当键】
        #   不同来源的不同文档里，极可能出现**完全相同的句子**
        #   （例如各份指南都会写"限制每日食盐摄入在 5 克以下"）。
        #   用正文作键会把它们判定为同一块而丢掉，结果是：
        #     · 来源被单一化 —— 本来有 3 份指南可以互相印证，最后只剩 1 份；
        #     · 重排序的候选池变小，答案的佐证强度下降。
        #
        # 【改用什么】
        #   parent_id 是切块时生成的**稳定标识**（形如「文件名_p序号」），
        #   它能唯一确定"这是哪一段"，相同段落才叫重复。
        #   若个别数据缺 parent_id，则退化为内容哈希，保证仍然能去重。
        seen = {}
        for c in chunks:
            if not c.page_content:
                continue
            key = c.metadata.get("parent_id")
            if not key:
                key = hashlib.md5(c.page_content.encode("utf-8")).hexdigest()
            # setdefault：保留先到的那个。候选池靠前的通常相关度更高。
            seen.setdefault(key, c)
        parent_docs = list(seen.values())
        if len(parent_docs) < 2 or self.reranker is None:
            # 不足 2 块无需重排；入库模式未加载重排序器时直接返回原始排序结果
            return parent_docs
        pairs = [[query, d.page_content] for d in parent_docs]
        with self._infer_lock:
            scores = self.reranker.predict(pairs)
        # 重排序：按得分降序排列
        parent_docs = [d for _, d in sorted(zip(scores, parent_docs),
                                            key=lambda x: x[0], reverse=True)]
        scores_sorted = sorted(scores, reverse=True)
        # 命中日志：记录检索来源与重排序得分，方便评估召回质量、调权重阈值
        top_sources = [d.metadata.get("parent_id", "") for d in parent_docs[:candidate_m]]
        top_scores = [f"{s:.2f}" for s in scores_sorted[:candidate_m]]
        logger.info(f"检索命中 {len(parent_docs)} 块 | 取 {candidate_m} | 来源 {top_sources} | rerank分 {top_scores}"
                    f" | 耗时 {time.time() - t0:.2f}s")
        return parent_docs[:candidate_m]

    @staticmethod
    def _doc_from_hit(hit) -> Document:
        """把 Milvus 返回的一条命中转成 LangChain Document。

        正文取 parent_content（父块全文）而非命中的子块：子块只用于精准匹配，
        交给大模型的应是上下文更完整的父块，否则会出现「一句话没头没尾」。
        page 用于引用溯源（0 表示该块无页码信息，如 md 文档或未重建的老数据）。
        """
        return Document(
            page_content=hit.get("parent_content"),
            metadata={
                "parent_id": hit.get("parent_id"),
                "source": hit.get("source"),
                "timestamp": hit.get("timestamp"),
                "page": hit.get("page") or None
            }
        )


def create_vector_store(rebuild: bool = False) -> VectorStore:
    """根据项目 config.ini 创建 VectorStore

    rebuild=True  -> 入库模式：重建集合，供 ingest_fast.py 使用
    rebuild=False -> 查询模式：加载已有集合，供 app 检索使用
    """
    chronic_conf = Config()
    db_name = chronic_conf.config.get("milvus", "database_name", fallback=_CHRONIC_DB)
    collection_name = chronic_conf.config.get("milvus_collection", "collection_name",
                                              fallback=_CHRONIC_COLLECTION)
    model_path = chronic_conf.config.get("models", "bge_m3_path", fallback=_BGE_M3_PATH)
    reranker_path = chronic_conf.config.get("models", "bge_reranker_path", fallback=_RERANKER_PATH)
    dense_dim = chronic_conf.config.getint("milvus_collection", "dense_dim", fallback=1024)
    # 检索参数接线：配置修改后直接生效，不再需要改代码（之前这些配置项全是死配置）
    retrieval_k = chronic_conf.config.getint("milvus_collection", "retrieval_k", fallback=5)
    candidate_m = chronic_conf.config.getint("milvus_collection", "candidate_m", fallback=2)
    dense_weight = chronic_conf.config.getfloat("milvus_collection", "dense_weight", fallback=1.0)
    sparse_weight = chronic_conf.config.getfloat("milvus_collection", "sparse_weight", fallback=0.7)
    # 全局检索跨源平衡：每源候选数 + 知识源清单（与 [chronic_disease] valid_sources 同源）
    per_source_k = chronic_conf.config.getint("milvus_collection", "per_source_k", fallback=2)
    valid_sources = [s.strip() for s in chronic_conf.config.get(
        "chronic_disease", "valid_sources",
        fallback="disease,medication,lifestyle,lab,risk").split(",") if s.strip()]

    return VectorStore(
        db_name=db_name, collection_name=collection_name,
        model_path=model_path, reranker_path=reranker_path,
        dense_dim=dense_dim, rebuild=rebuild,
        retrieval_k=retrieval_k, candidate_m=candidate_m,
        dense_weight=dense_weight, sparse_weight=sparse_weight,
        valid_sources=valid_sources, per_source_k=per_source_k
    )