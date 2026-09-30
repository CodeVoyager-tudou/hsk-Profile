"""领域智能体基类：把「检索知识库 + 组装提示词 + 调大模型」这条公共链路固化下来。

    子类只需要声明两件事：本领域的人设提示词（get_system_prompt）与要检索的知识源
    （get_knowledge_source，对应 Milvus 里的 source 字段）。疾病/用药/生活方式/检验/风险
    五个智能体都是这样派生出来的，因此各领域之间只差提示词与知识源。

    一次生成的完整流程：
        问题 --query_knowledge--> 检索到的资料片段 --_build_prompt--> 提示词
             --chat.completions--> 答案（非流式 generate_text / 流式 generate_response）
"""
from abc import ABC, abstractmethod
from base.logger import logger
from core.prompts import ChronicDiseasePrompts
from core.llm_config import LLM_MODEL, make_openai_client


def format_knowledge_header(idx: int, doc) -> str:
    """把一条命中片段标注成「[资料N | 来源: 《文件名》 第M页]」。

    parent_id 形如「文件名_p序号」（分块时生成），文件名从它截出来；
    页码来自 loaders 按页产出的 metadata.page（一个父块只属于一页，所以页码是确定的）。
    md/txt 文档没有页的概念（page 为 None/0），此时只标文件名。
    """
    parent_id = doc.metadata.get("parent_id") or ""
    file_name = parent_id.rsplit("_p", 1)[0] if "_p" in parent_id else (
        parent_id or doc.metadata.get("source", ""))
    page = doc.metadata.get("page")
    locator = f" 第{page}页" if page else ""
    return f"[资料{idx} | 来源: 《{file_name}》{locator}]"


class BaseAgent(ABC):
    """领域智能体抽象基类。

    name 是展示用名（如「疾病科普员」，会写进流式输出的【】分段前缀），
    role 是路由键（disease/medication/...，与知识源 source 字段同名）。
    """

    def __init__(self, name: str, role: str):
        """name 用于展示（流式输出的【】分段前缀），role 是路由键与知识源标识"""
        self.name = name
        self.role = role
        # 在 client 上统一设超时上界（兜底）。
        # 各调用点仍显式传 timeout=LLM_TIMEOUT，这里是防止将来新增调用时漏传。
        self.client = make_openai_client()
        self.vector_store = None

    def set_vector_store(self, vector_store):
        """注入向量库（由 coordinator 在启动时统一注入；订单智能体不走 RAG，不注入）"""
        self.vector_store = vector_store

    @abstractmethod
    def get_system_prompt(self) -> str:
        """返回本领域的人设提示词（子类实现，统一从 core/prompts.py 取）"""
        pass

    @abstractmethod
    def get_knowledge_source(self) -> str:
        """返回本领域检索的知识源标识（对应 Milvus 的 source 字段）"""
        pass

    def query_knowledge(self, question: str) -> str:
        """按本领域知识源检索，把命中的片段拼成带出处的资料文本。

        本知识源无命中时放宽到全库检索兜底（例如该知识源尚未入库文档），
        避免直接空手回答；两条路径都没有结果才返回空串（由 _build_prompt 走无知识模板）。
        """
        if self.vector_store:
            docs = self.vector_store.hybrid_search_with_reranker(
                question,
                source_filter=self.get_knowledge_source()
            )
            if not docs:
                # 本知识源无命中（如该知识源尚未入库文档），放宽到全库检索兜底，避免直接空手回答
                docs = self.vector_store.hybrid_search_with_reranker(question, source_filter=None)
                if docs:
                    logger.info(f"[{self.name}] 本知识源无命中，全库兜底检索到 {len(docs)} 个片段")
                else:
                    logger.warning(f"[{self.name}] 知识库未检索到相关知识")
                    return ""
            else:
                logger.info(f"[{self.name}] 检索到知识片段数: {len(docs)}")
            # 每个片段标注来源文件与页码（parent_id 形如 “文件名_p序号”，page 由 loaders 按页写入），
            # 供答案引用出处、增强可信度；医学引用场景下页码是可核对的定位信息
            parts = []
            for idx, doc in enumerate(docs, 1):
                parts.append(f"{format_knowledge_header(idx, doc)}\n{doc.page_content}")
            return "\n\n".join(parts)
        return ""

    def _build_prompt(self, question: str, knowledge: str, history_text: str) -> str:
        """组装 user 提示词：有知识走 RAG 模板，无知识走"声明非权威"兜底模板"""
        # 历史对话上下文：支持多轮追问（如"那它有什么副作用"）；模板统一从 prompts.py 引用
        history_section = f"\n历史对话：\n{history_text}\n" if history_text else ""
        if knowledge:
            return ChronicDiseasePrompts.answer_prompt().format(
                system_prompt=self.get_system_prompt(),
                history_section=history_section,
                knowledge=knowledge,
                question=question
            )
        # 知识库无据可依时，明确要求模型声明回答非来自权威指南，避免用户误信
        return ChronicDiseasePrompts.no_knowledge_prompt().format(
            system_prompt=self.get_system_prompt(),
            history_section=history_section,
            question=question
        )

    def _messages(self, prompt: str) -> list:
        """组装对话消息：system 放人设提示词，user 放检索资料 + 问题"""
        return [
            {"role": "system", "content": self.get_system_prompt()},
            {"role": "user", "content": prompt}
        ]

    def generate_text(self, question: str, search_query: str = None, history_text: str = "") -> str:
        """非流式生成，返回 str。

        注意不要在 generate_response 的非流式分支上打补丁：该函数体内含 yield，
        是生成器函数，stream=False 时调用同样返回 generator 而 return 值会被丢弃，
        因此非流式必须走本方法（LangGraph 专家节点专用）。
        """
        # 检索用规范化表述（路由器产出），未提供时退回原问题；回答仍基于用户原始问题，保持口语对齐
        knowledge = self.query_knowledge(search_query or question)
        prompt = self._build_prompt(question, knowledge, history_text)
        try:
            completion = self.client.chat.completions.create(
                model=LLM_MODEL,
                messages=self._messages(prompt),
                temperature=0.3,
                # 本地 Ollama（CPU 推理）首 token 常超 30s，短超时会导致 Request timed out
                timeout=300
            )
            return completion.choices[0].message.content
        except Exception as e:
            logger.error(f"{self.name} 调用失败: {e!r}（底层原因: {e.__cause__!r}）")
            return "抱歉，系统暂时无法处理您的问题。"

    def generate_response(self, question: str, stream: bool = False,
                          search_query: str = None, history_text: str = ""):
        """流式生成（生成器，逐 token 产出）。

        本函数是生成器函数：任何调用都返回 generator，仅供 stream=True 使用；
        非流式请调用 generate_text()。
        """
        # 检索用规范化表述（路由器产出），未提供时退回原问题；回答仍基于用户原始问题，保持口语对齐
        knowledge = self.query_knowledge(search_query or question)
        prompt = self._build_prompt(question, knowledge, history_text)

        try:
            completion = self.client.chat.completions.create(
                model=LLM_MODEL,
                messages=self._messages(prompt),
                temperature=0.3,
                # 同上：流式超时指首字节等待，本地 CPU 推理需放宽
                timeout=300,
                stream=True
            )
            for chunk in completion:
                if chunk.choices and chunk.choices[0].delta.content:
                    yield chunk.choices[0].delta.content
        except Exception as e:
            logger.error(f"{self.name} 调用失败: {e!r}（底层原因: {e.__cause__!r}）")
            yield "抱歉，系统暂时无法处理您的问题。"