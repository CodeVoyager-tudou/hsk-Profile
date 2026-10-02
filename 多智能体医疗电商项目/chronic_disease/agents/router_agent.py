"""路由智能体：判断用户问题该交给哪些领域专家回答。

    路由质量直接决定后面所有环节的走向 —— 选错科室，专家再好也答不到点上。
    因此这里用的是「LLM 路由 + 关键词路由」的双层结构：

      1. 首选 LLM 路由：除了选专家，还能产出规范化的检索词（search_query）并拆解多路子问题；
      2. LLM 不可用（后端宕机、返回的 JSON 解析失败）时，用 core/router_keywords.py 的
         关键词命中结果兜底。若没有这层兜底，一旦大模型不可用，所有问题都会退化成
         「找疾病科普」一个源，用药/检验/生活方式/风险四类问题全部答非所问；
      3. LLM 成功时反向补充：关键词高置信命中（≥2 分）但被 LLM 漏掉的专家会并回候选
         （上限 3 路），降低 LLM 偶发误路由导致本源缺席的概率。
"""
import json
from base.logger import logger
from core.prompts import ChronicDiseasePrompts
from core.llm_config import LLM_MODEL, make_openai_client
from core.router_keywords import keyword_routes


class RouterAgent:
    """路由智能体：分析用户意图，分发到对应专家

    双层路由：
    - 优先 LLM 路由（产出 search_query 规范化表述 + 多路拆解）；
    - LLM 失败（后端不可达/JSON 解析失败）时用关键词路由兜底，替换原来无条件返回 disease 的
      逻辑——否则 Ollama 一挂，medication/lab/lifestyle/risk 的所有问题都被打进 disease；
    - LLM 成功时，把关键词高置信命中（>=2 分）但被 LLM 漏掉的专家并回候选（上限 3 路），
      防止 LLM 误路由导致本源缺席。
    """

    def __init__(self):
        """创建 LLM client（带超时上界）并初始化专家键到展示名的映射"""
        # router 是**每个请求必经**的一步，一旦这里不设超时，
        # 后端卡住时整个请求会永久挂起并占住工作线程。现在统一在 client 上设上界。
        self.client = make_openai_client()

        # 专家键 -> 展示名。图里 Send 派发只用键，输出的【】分段前缀用展示名
        self.agent_mapping = {
            "disease": "疾病知识科普",
            "medication": "用药咨询",
            "lifestyle": "饮食运动建议",
            "lab": "指标解读",
            "risk": "风险预警",
            "order": "订单查询"
        }

    def route(self, query: str, history_text: str = "") -> list:
        """返回路由结果：[{"agent": 专家键, "query": 子问题, "search_query": 检索词}, ...]，最多 3 路。

        历史对话用于理解指代与上下文（如「那它有什么副作用」需要结合上一轮才知道「它」指什么），
        因此多轮追问也必须把 history_text 一起交给路由器。
        异常路径不会向上抛：LLM 失败时返回关键词兜底结果，兜底也无命中才落到 disease。
        """
        # 历史对话上下文：多轮追问（如“那它有什么副作用”）需要结合上文才能正确路由和拆解子问题；
        # 提示词模板统一从 prompts.py 引用，避免双份维护
        history_section = f"\n历史对话（用于理解指代和上下文）：\n{history_text}\n" if history_text else ""
        prompt = ChronicDiseasePrompts.router_prompt().format(
            query=query, history_section=history_section
        )

        # 关键词高置信命中（>=2 分）：LLM 成功时也要保证这些专家在候选里
        kw_strong = keyword_routes(query, min_score=2)

        try:
            response = self.client.chat.completions.create(
                model=LLM_MODEL,
                messages=[{"role": "user", "content": prompt}],
                temperature=0.1
            )

            result_text = response.choices[0].message.content.strip()
            if result_text.startswith("```"):
                result_text = result_text.split("```")[1]
                if result_text.startswith("json"):
                    result_text = result_text[4:]
            result = json.loads(result_text)
            # 并集补充：关键词高置信命中但 LLM 漏掉的专家追加进候选（去重，上限 3 路）
            existing = {item.get("agent") for item in result if isinstance(item, dict)}
            # LLM 判定"没有匹配任何专家"时，退回低阈值关键词路由：
            # 问法稍变（如「我有哪些是已退款的」）LLM 可能给空结果，而关键词明明命中 order，
            # 沿用 min_score=2 的并集会漏掉 —— 最终落到「暂时无法回答」的兜底话术（实测踩到）。
            supplement = kw_strong if result else keyword_routes(query, min_score=1)[:3]
            for agent in supplement:
                if agent not in existing:
                    result.append({"agent": agent, "query": query})
                    existing.add(agent)
            logger.info(f"路由分析结果: {result}（关键词并集 {supplement}）")
            return result[:3]
        except Exception as e:
            # Connection error 这类外层消息不带底层原因（httpx 的真实报错在 __cause__ 里），
            # 不展开就永远排查不了「Ollama 明明在跑却连不上」这类问题
            logger.error(f"路由分析失败: {e!r}（底层原因: {e.__cause__!r}）")
            # 关键词兜底（替代原来的无条件 disease）：命中则分派到对应专家，未命中才落 disease。
            # 返回所有命中专家（上限 3 路），与 LLM 多路路由一致，复合问题可并行覆盖多个源。
            kw_fallback = keyword_routes(query, min_score=1)[:3]
            if kw_fallback:
                logger.info(f"关键词兜底路由: {kw_fallback}")
                return [{"agent": agent, "query": query} for agent in kw_fallback]
            return [{"agent": "disease", "query": query}]