"""检验指标解读智能体：解读化验单上的指标含义、参考范围与异常值的可能原因。

    公共链路继承自 BaseAgent，本文件仅声明人设提示词与知识源。
"""
from .base_agent import BaseAgent
from core.prompts import ChronicDiseasePrompts


class LabAgent(BaseAgent):
    """指标解读员（知识源 lab）"""

    def __init__(self):
        """固定展示名与路由键（role 同时作为知识源标识）"""
        super().__init__(name="指标解读员", role="lab")

    def get_system_prompt(self) -> str:
        # 提示词统一从 prompts.py 引用，避免双份维护；免责声明由应用层统一追加，不在模板内要求
        return ChronicDiseasePrompts.lab_system_prompt()

    def get_knowledge_source(self) -> str:
        return "lab"