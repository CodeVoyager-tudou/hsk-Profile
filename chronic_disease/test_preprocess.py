"""preprocess.py 单元测试：问候检测、急症关键词快筛、通用知识拦截"""
import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from core.preprocess import check_greeting, check_emergency, check_general_knowledge, EMERGENCY_KEYWORDS


# ========== 问候检测 ==========

class TestCheckGreeting:
    def test_hello_cn(self):
        assert check_greeting("你好") is not None
        assert check_greeting("您好") is not None

    def test_hello_en(self):
        assert check_greeting("hi") is not None
        assert check_greeting("hello") is not None
        assert check_greeting("Hello World") is not None

    def test_who_are_you(self):
        assert check_greeting("你是谁") is not None
        assert check_greeting("你叫什么") is not None
        assert check_greeting("你的名字") is not None

    def test_are_you_there(self):
        assert check_greeting("在吗") is not None
        assert check_greeting("在不在") is not None
        assert check_greeting("有人吗") is not None

    def test_greeting_with_whitespace(self):
        assert check_greeting("  你好  ") is not None

    def test_non_greeting_returns_none(self):
        assert check_greeting("高血压吃什么药") is None
        assert check_greeting("") is None

    def test_response_contains_keyword(self):
        resp = check_greeting("你好")
        assert "慢性病" in resp or "助手" in resp


# ========== 急症关键词快筛 ==========

class TestCheckEmergency:
    def test_chest_pain(self):
        assert check_emergency("我胸痛得厉害") is not None

    def test_breathing_difficulty(self):
        assert check_emergency("呼吸困难怎么办") is not None

    def test_unconscious(self):
        assert check_emergency("老人突然意识模糊") is not None

    def test_stroke_symptoms(self):
        assert check_emergency("口角歪斜言语不清") is not None

    def test_bleeding(self):
        assert check_emergency("大出血止不住") is not None

    def test_seizure(self):
        assert check_emergency("抽搐不止") is not None

    def test_emergency_tip_prefix(self):
        resp = check_emergency("胸痛")
        assert resp.startswith("⚠️")

    def test_non_emergency_returns_none(self):
        assert check_emergency("高血压怎么控制") is None
        assert check_emergency("今天天气怎么样") is None

    def test_empty_query_returns_none(self):
        assert check_emergency("") is None

    def test_all_keywords_covered(self):
        for kw in EMERGENCY_KEYWORDS:
            resp = check_emergency(kw)
            assert resp is not None, f"关键词 '{kw}' 未触发急症提示"


# ========== 通用知识拦截 ==========

class TestCheckGeneralKnowledge:
    def test_math_calculation(self):
        assert check_general_knowledge("1+1等于几") is not None
        assert check_general_knowledge("3 * 5 = ?") is not None
        assert check_general_knowledge("10÷2") is not None

    def test_programming_keywords(self):
        assert check_general_knowledge("python怎么学") is not None
        assert check_general_knowledge("java代码") is not None
        assert check_general_knowledge("数据库设计") is not None
        assert check_general_knowledge("linux命令") is not None

    def test_weather_time(self):
        assert check_general_knowledge("今天天气怎么样") is not None
        assert check_general_knowledge("现在几点了") is not None
        assert check_general_knowledge("今天星期几") is not None

    def test_entertainment(self):
        assert check_general_knowledge("推荐一部电影") is not None
        assert check_general_knowledge("打游戏") is not None
        assert check_general_knowledge("讲个笑话") is not None

    def test_finance(self):
        assert check_general_knowledge("股票怎么买") is not None
        assert check_general_knowledge("基金定投") is not None

    def test_food_travel(self):
        assert check_general_knowledge("旅游攻略") is not None
        assert check_general_knowledge("菜谱大全") is not None

    def test_medical_query_not_intercepted(self):
        assert check_general_knowledge("高血压吃什么药") is None
        assert check_general_knowledge("糖尿病血糖高怎么办") is None

    def test_empty_query_returns_none(self):
        assert check_general_knowledge("") is None

    def test_response_contains_disclaimer_hint(self):
        resp = check_general_knowledge("1+1=?")
        assert "慢性病" in resp or "专业范围" in resp
