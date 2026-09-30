"""生活方式智能体：回答饮食、运动、体重管理、戒烟限酒、作息等生活方式类问题。

    公共链路继承自 BaseAgent，本文件仅声明人设提示词与知识源。
"""
from .base_agent import BaseAgent
from core.prompts import ChronicDiseasePrompts


class LifestyleAgent(BaseAgent):
    """饮食运动师（知识源 lifestyle）"""

    def __init__(self):
        """固定展示名与路由键（role 同时作为知识源标识）"""
        super().__init__(name="饮食运动师", role="lifestyle")

    def get_system_prompt(self) -> str:
        # 提示词统一从 prompts.py 引用，避免双份维护；免责声明由应用层统一追加，不在模板内要求
        return ChronicDiseasePrompts.lifestyle_system_prompt()

    def get_knowledge_source(self) -> str:
        return "lifestyle"