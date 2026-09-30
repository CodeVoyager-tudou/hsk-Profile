"""端到端冒烟测试：路由、各领域智能体初始化、提示词模板与协调器问答。

    依赖真实向量库与大模型（Ollama/DashScope），因此向量库不可导入时自动跳过相关用例。
    运行：python test_chronic_disease.py  （或 pytest test_chronic_disease.py -q）
"""
import os
import sys
import unittest

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from base.logger import logger
from agents.router_agent import RouterAgent
from agents.disease_agent import DiseaseAgent
from agents.medication_agent import MedicationAgent
from agents.lifestyle_agent import LifestyleAgent
from agents.lab_agent import LabAgent
from agents.risk_agent import RiskAgent
from core.coordinator import ChronicDiseaseCoordinator
from core.prompts import ChronicDiseasePrompts

try:
    from document_loader.vector_store import create_vector_store
    HAS_VECTOR_STORE = True
except ImportError:
    HAS_VECTOR_STORE = False
    create_vector_store = None


class TestRouterAgent(unittest.TestCase):
    """测试路由智能体"""

    def setUp(self):
        self.router = RouterAgent()

    def test_route_disease_query(self):
        result = self.router.route("高血压是什么病")
        self.assertIsInstance(result, list)
        self.assertGreater(len(result), 0)
        agents = [r["agent"] for r in result]
        self.assertIn("disease", agents)
        logger.info(f"疾病查询路由结果: {result}")

    def test_route_medication_query(self):
        result = self.router.route("二甲双胍怎么吃")
        self.assertIsInstance(result, list)
        self.assertGreater(len(result), 0)
        agents = [r["agent"] for r in result]
        self.assertIn("medication", agents)
        logger.info(f"用药查询路由结果: {result}")

    def test_route_lifestyle_query(self):
        result = self.router.route("糖尿病患者能吃什么")
        self.assertIsInstance(result, list)
        self.assertGreater(len(result), 0)
        agents = [r["agent"] for r in result]
        self.assertTrue(any(a in agents for a in ["lifestyle", "disease"]))
        logger.info(f"饮食运动查询路由结果: {result}")

    def test_route_lab_query(self):
        result = self.router.route("空腹血糖7.2正常吗")
        self.assertIsInstance(result, list)
        self.assertGreater(len(result), 0)
        agents = [r["agent"] for r in result]
        self.assertIn("lab", agents)
        logger.info(f"指标查询路由结果: {result}")

    def test_route_risk_query(self):
        result = self.router.route("突然胸痛怎么办")
        self.assertIsInstance(result, list)
        self.assertGreater(len(result), 0)
        agents = [r["agent"] for r in result]
        self.assertIn("risk", agents)
        logger.info(f"风险查询路由结果: {result}")


class TestAgentInit(unittest.TestCase):
    """测试各智能体初始化"""

    def test_disease_agent_init(self):
        agent = DiseaseAgent()
        self.assertEqual(agent.name, "疾病科普员")
        self.assertEqual(agent.role, "disease")
        self.assertIsNotNone(agent.get_system_prompt())
        self.assertEqual(agent.get_knowledge_source(), "disease")

    def test_medication_agent_init(self):
        agent = MedicationAgent()
        self.assertEqual(agent.name, "用药顾问")
        self.assertEqual(agent.role, "medication")
        self.assertIsNotNone(agent.get_system_prompt())
        self.assertEqual(agent.get_knowledge_source(), "medication")

    def test_lifestyle_agent_init(self):
        agent = LifestyleAgent()
        self.assertEqual(agent.name, "饮食运动师")
        self.assertEqual(agent.role, "lifestyle")
        self.assertIsNotNone(agent.get_system_prompt())
        self.assertEqual(agent.get_knowledge_source(), "lifestyle")

    def test_lab_agent_init(self):
        agent = LabAgent()
        self.assertEqual(agent.name, "指标解读员")
        self.assertEqual(agent.role, "lab")
        self.assertIsNotNone(agent.get_system_prompt())
        self.assertEqual(agent.get_knowledge_source(), "lab")

    def test_risk_agent_init(self):
        agent = RiskAgent()
        self.assertEqual(agent.name, "风险预警员")
        self.assertEqual(agent.role, "risk")
        self.assertIsNotNone(agent.get_system_prompt())
        self.assertEqual(agent.get_knowledge_source(), "risk")


class TestPrompts(unittest.TestCase):
    """测试提示词模板"""

    def test_router_prompt_format(self):
        # 模板新增 history_section 占位符（多轮对话支持），格式化时需一并提供（可为空）
        prompt = ChronicDiseasePrompts.router_prompt().format(query="测试问题", history_section="")
        self.assertIn("测试问题", prompt)

    def test_disease_prompt_format(self):
        # 专家模板已拆分为纯角色提示词（参考知识/问题由 base_agent 的 answer_prompt 组装）
        prompt = ChronicDiseasePrompts.disease_system_prompt()
        self.assertIn("医学科普专家", prompt)

    def test_disclaimer(self):
        self.assertIsInstance(ChronicDiseasePrompts.disclaimer, str)
        self.assertGreater(len(ChronicDiseasePrompts.disclaimer), 0)


@pytest.mark.slow  # 需要真实向量库 + Ollama（无环境时 setUp 自动 skip；-m "not slow" 可整体排除）
class TestCoordinator(unittest.TestCase):
    """测试协调器"""

    def setUp(self):
        if not HAS_VECTOR_STORE:
            self.skipTest("milvus_model 未安装，跳过向量库相关测试")
        try:
            # 查询模式加载已有集合；切勿用默认重建模式，否则会清空已入库数据
            self.vector_store = create_vector_store(rebuild=False)
        except Exception as e:
            self.skipTest(f"无法连接向量库: {e}")

    def test_coordinator_init(self):
        coordinator = ChronicDiseaseCoordinator(self.vector_store)
        self.assertIsNotNone(coordinator.router)
        # 7 个专家：5 个 RAG 专家 + order + mall（后者曾漏更新导致此断言过期）
        self.assertEqual(len(coordinator.agents), 7)
        self.assertIn("disease", coordinator.agents)
        self.assertIn("medication", coordinator.agents)
        self.assertIn("lifestyle", coordinator.agents)
        self.assertIn("lab", coordinator.agents)
        self.assertIn("risk", coordinator.agents)
        self.assertIn("order", coordinator.agents)
        self.assertIn("mall", coordinator.agents)

    def test_coordinator_query(self):
        coordinator = ChronicDiseaseCoordinator(self.vector_store)
        answer = coordinator.query("高血压患者应该注意什么")
        self.assertIsInstance(answer, str)
        self.assertGreater(len(answer), 0)
        logger.info(f"协调器查询结果: {answer[:200]}...")


if __name__ == "__main__":
    unittest.main(verbosity=2)