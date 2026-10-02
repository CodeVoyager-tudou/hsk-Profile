"""疾病科普智能体：回答病因、症状、诊断标准、分型等「这个病是什么」类问题。

    只做知识检索与生成，公共链路（检索 → 组装提示词 → 调模型）全部继承自 BaseAgent，
    本文件仅声明人设提示词与知识源。
"""
from .base_agent import BaseAgent
from core.prompts import ChronicDiseasePrompts


class DiseaseAgent(BaseAgent):
    """疾病科普员（知识源 disease，也是路由全部失败时的最终兜底方向）"""

    def __init__(self):
        """固定展示名与路由键（role 同时作为知识源标识）"""
        super().__init__(name="疾病科普员", role="disease")

    def get_system_prompt(self) -> str:
        # 提示词统一从 prompts.py 引用，避免双份维护；免责声明由应用层统一追加，不在模板内要求
        return ChronicDiseasePrompts.disease_system_prompt()

    def get_knowledge_source(self) -> str:
        return "disease"