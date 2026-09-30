"""风险预警智能体：识别危险信号与急症征兆，给出就医/急救建议。

    与 app 层的急症关键词快筛互补：快筛负责「第一时间弹提示」，本智能体负责
    「结合知识库说清为什么要就医、该看哪个科」。
"""
from .base_agent import BaseAgent
from core.prompts import ChronicDiseasePrompts


class RiskAgent(BaseAgent):
    """风险预警员（知识源 risk）"""

    def __init__(self):
        """固定展示名与路由键（role 同时作为知识源标识）"""
        super().__init__(name="风险预警员", role="risk")

    def get_system_prompt(self) -> str:
        # 提示词统一从 prompts.py 引用，避免双份维护；免责声明由应用层统一追加，不在模板内要求
        return ChronicDiseasePrompts.risk_system_prompt()

    def get_knowledge_source(self) -> str:
        return "risk"