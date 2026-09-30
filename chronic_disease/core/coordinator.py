"""协调器：对上层暴露「一次问答」的入口，内部用 LangGraph 编排多个专家智能体。

【职责边界】
    真正的编排（路由 → 并行专家 → 整合）都在 core/graph.py 的 StateGraph 里；
    本类只做三件事：
      1. 组装依赖：创建路由智能体与 6 个领域智能体，并给需要检索的智能体注入向量库；
      2. 选择检查点器：挂 BoundedInMemorySaver，让同一会话的图状态跨请求保留（进程内）；
      3. 提供稳定的对外接口 query() / query_stream()，使 app 层与评测脚本与编排实现解耦。

【单个问答的执行路径】
    query/query_stream → graph.invoke/stream → route 节点（选科室，最多 3 路）
    → Send 并行派发 expert 节点（各自检索知识库 + 调大模型）→ synthesize 整合 → END
"""
from openai import OpenAI
from base.logger import logger
from agents.router_agent import RouterAgent
from agents.disease_agent import DiseaseAgent
from agents.medication_agent import MedicationAgent
from agents.lifestyle_agent import LifestyleAgent
from agents.lab_agent import LabAgent
from agents.risk_agent import RiskAgent
from agents.order_agent import OrderAgent
from agents.mall_agent import MallAgent
from core.session import format_history
from core.prompts import ChronicDiseasePrompts
from core.llm_config import LLM_MODEL, LLM_API_KEY, LLM_BASE_URL, LLM_TIMEOUT
from core.graph import (
    DEFAULT_ANSWER,
    build_chronic_graph,
    non_blank_responses,
    _stream_ordered,
)

# 键 → Agent 类的唯一映射：__init__ 里的 self.agents 与下方两个派生清单都由它推导，
# 新增专家只改这一处 + agents 包里的类定义。要不要注入 user_id / 跳过 LLM 整合，
# 由各类上的 user_scoped / data_source 类属性声明，不再维护第二份字面量清单
# （复核 P2-2：两处清单漏改任意一处都是静默答错，正是 A1/A3 的同源结构）。
_AGENT_CLASSES = {
    "disease": DiseaseAgent,
    "medication": MedicationAgent,
    "lifestyle": LifestyleAgent,
    "lab": LabAgent,
    "risk": RiskAgent,
    "order": OrderAgent,
    # 商城与资产：商品价格（现金 / 积分两条渠道）、我的积分与余额、我的优惠券。
    # 它不走 RAG —— 数据来自微服务内部接口，不能让模型凭常识编价格。
    "mall": MallAgent,
}

# 接口数据型专家（回答由模板拼装、数字直接来自微服务接口，不经模型生成）。
# 一旦它们参与回答，就不再交给大模型"整合"——实测模型会把"现金价 ¥12.0 / 900 分/件"
# 这类确定数字改写成"价格请咨询药店"（e2e 里偶发，非必现），这等于把接口数据弄丢。
# 这几路回答本身已是完整话术，直接分段拼接即可，且结果确定、可回归。
# 真相源是各类上的 data_source 类属性，此处只做键名展开（保持既有导入路径兼容）。
DATA_SOURCE_AGENT_KEYS = tuple(
    key for key, cls in _AGENT_CLASSES.items() if getattr(cls, "data_source", False))

# 需要 user_id 才能作答的专家：数据来自微服务内部接口，按用户维度查询。
# graph.expert_node 直接读 agent 实例的 user_scoped 属性；此常量供测试断言
# 两份派生清单与类属性保持一致（test_router_keywords.py t5）。
USER_SCOPED_AGENT_KEYS = tuple(
    key for key, cls in _AGENT_CLASSES.items() if getattr(cls, "user_scoped", False))


def should_let_llm_merge(responses: list) -> bool:
    """多路回答是否适合交给 LLM 整合：只要有一路是接口数据型专家，就改为分段拼接"""
    return not any(r.get("key") in DATA_SOURCE_AGENT_KEYS for r in responses)


def _data_source_first(responses: list) -> list:
    """稳定重排：接口数据型专家的段提到最前，其余保持原序（复核 P2-8）。

    只调展示顺序、不改合并策略：LLM 路由成功时段落顺序由模型决定，常把
    order/mall 排到医学专家后面，用户明明在问价格，价格段却要翻屏才看得到。
    sorted 是稳定排序，同组内相对顺序不变。不要为此改 should_let_llm_merge
    的一刀切 —— 那会把"模型改写金额"的 A3 类 bug 请回来。
    """
    return sorted(responses, key=lambda r: r.get("key") not in DATA_SOURCE_AGENT_KEYS)


def _join_sections(responses: list) -> str:
    """按【专家名】分段拼接（与 graph.py 的 join_sections 同格式，数据段在前）。

    空回答的段直接丢弃 —— 专家可以"不发言"（如商城专家遇到订单/消费类问句返回空串），
    留着会输出一个空标题（见 graph.non_blank_responses）。
    """
    return "\n\n".join(f"【{r['agent']}】\n{r['response']}"
                       for r in _data_source_first(non_blank_responses(responses)))


class ChronicDiseaseCoordinator:
    """慢性病管理多智能体协调器（LangGraph 编排 facade）

    编排逻辑在 core/graph.py 的 StateGraph 中：路由 → Send 并行专家(≤3路) → 整合。
    本类保留原有对外接口 query()/query_stream()，app 层与评测脚本无需改动。
    """

    def __init__(self, vector_store):
        """vector_store: 供各领域专家检索知识库（订单智能体不走 RAG，不注入）"""
        self.router = RouterAgent()

        self.agents = {key: cls() for key, cls in _AGENT_CLASSES.items()}

        # 接口数据型智能体（order/mall）不走 RAG（数据来自微服务内部接口），不需要向量库
        for key, agent in self.agents.items():
            if key not in DATA_SOURCE_AGENT_KEYS:
                agent.set_vector_store(vector_store)

        # 整合步骤也要有超时上界，避免后端卡死时请求永久挂起
        self.client = OpenAI(
            api_key=LLM_API_KEY,
            base_url=LLM_BASE_URL,
            timeout=LLM_TIMEOUT
        )

        # LangGraph 检查点器：thread_id=会话ID 时同一会话的图状态跨请求保留（进程内）。
        # 会话正文/摘要的持久化由 ChatSessionManager(PostgreSQL) 负责；此处 InMemorySaver
        # 保留图内部状态，多实例部署如需跨进程可换 langgraph-checkpoint-postgres。
        #
        # 为什么不用原生 InMemorySaver：它把 thread_id（=session_id）当作键，**无 TTL、无上限**地
        # 累积 GraphState，进程长期运行必然单调增长至 OOM。改用带 LRU 上限 + TTL 的子类，
        # 见 core/checkpointer.py。上限/TTL 可由 config.ini [graph] 覆盖。
        checkpointer = None
        try:
            from core.checkpointer import BoundedInMemorySaver
            checkpointer = BoundedInMemorySaver()
        except Exception as e:
            logger.warning(f"LangGraph 检查点器不可用，图状态不跨请求保留: {e}")

        self.graph = build_chronic_graph(
            agents=self.agents, router=self.router, synthesize_fn=self._synthesize_answer,
            checkpointer=checkpointer
        )

    def query(self, user_query: str, history: list = None, history_text: str = None,
              session_id: str = None, user_id: str = None) -> str:
        """非流式问答：多路专家并行执行（原顺序执行改为 Send fan-out，墙钟≈最慢一路）。

        history_text：应用层已组合好的上下文（滚动摘要+窗口），直传优先；
        session_id：作为 LangGraph thread_id 关联检查点。
        """
        if history_text is None:
            history_text = format_history(history or [])
        state = self.graph.invoke(
            {"user_query": user_query, "history_text": history_text,
             "routes": [], "responses": [], "final_answer": "",
             "user_id": user_id or ""},
            config=self._config(False, session_id)
        )
        return state.get("final_answer") or DEFAULT_ANSWER

    def query_stream(self, user_query: str, history: list = None, history_text: str = None,
                     session_id: str = None, user_id: str = None):
        """流式响应生成器：逐 token 产出答案。

        单个专家时直接流式输出（无前缀）；多专家时按 【专家名】 分段流式输出，
        流式模式不做二次整合（与既有语义一致）。并行专家的 token 事件经
        _stream_ordered 顺序闸门重排，输出顺序与旧版逐段串行完全一致。
        """
        if history_text is None:
            history_text = format_history(history or [])
        events = self.graph.stream(
            {"user_query": user_query, "history_text": history_text,
             "routes": [], "responses": [], "final_answer": "",
             "user_id": user_id or ""},
            stream_mode="custom",
            config=self._config(True, session_id)
        )
        yield from _stream_ordered(events)

    @staticmethod
    def _config(streaming: bool, session_id: str) -> dict:
        """构造 LangGraph 运行配置。

        thread_id 即会话 ID：未传 session_id（匿名请求）时生成随机 uuid，
        因此匿名请求各自持有独立的图状态，不会互相污染。
        streaming 标志供图内节点判断「该不该上报 custom 事件」。
        """
        return {"configurable": {"streaming": streaming,
                                 "thread_id": session_id or str(__import__("uuid").uuid4())},
                "max_concurrency": 3}

    def _synthesize_answer(self, query: str, responses: list) -> str:
        """非流式多专家整合：把几路专家回答合成一份完整答案。

        单路直接返回原文，不额外调用大模型；多路走 LLM 整合，
        整合失败时降级为「按专家分段拼接」，保证一定有答案返回。

        例外：回答里含接口数据型专家（订单 / 商城）时不整合，直接分段拼接 ——
        理由见 DATA_SOURCE_AGENT_KEYS 处注释（模型会改写具体金额/价格）。

        先丢掉"没发言"的段（空串）：图那边（synthesize_node）已经过滤过一轮，
        这里再挡一次是为了**直接调用本方法的场景**（测试、未来的新调用方）也拿到同一口径。
        """
        responses = non_blank_responses(responses or [])
        if not responses:
            return DEFAULT_ANSWER

        if len(responses) == 1:
            return responses[0]["response"]

        if not should_let_llm_merge(responses):
            logger.info("回答含接口数据型专家，跳过 LLM 整合改为分段拼接: %s",
                        [r.get("agent") for r in responses])
            return _join_sections(responses)

        # 整合模板统一从 prompts.py 引用（已去掉“末尾添加免责声明”要求，声明由应用层统一追加）
        synthesis_prompt = ChronicDiseasePrompts.synthesis_prompt().format(
            query=query,
            responses=_join_sections(responses)
        )

        try:
            response = self.client.chat.completions.create(
                model=LLM_MODEL,
                messages=[{"role": "user", "content": synthesis_prompt}],
                temperature=0.3
            )
            return response.choices[0].message.content
        except Exception as e:
            logger.error(f"答案整合失败: {e}")
            return _join_sections(responses)