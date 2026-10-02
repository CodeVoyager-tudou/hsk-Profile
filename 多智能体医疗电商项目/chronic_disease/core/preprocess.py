"""请求预处理：问候语识别、急症关键词快筛、非慢性病问题的友好拦截。

    这三类问题都不需要走「检索 + 多专家 + 大模型」的完整链路：
      · 问候语 → 直接回固定话术；
      · 急症   → 第一时间给急救提示（提示语来自 config.ini，见 EMERGENCY_TIP）；
      · 非慢性病问题（数学/编程/天气/娱乐等）→ 直接说明不在服务范围，省下整条推理链路。
    独立成模块是为了让 app.py 与评测脚本共用同一套判定口径，避免评测时另写一份造成结论不一致。
"""
import re
from typing import Optional

from base.config import Config

chronic_conf = Config()

GREETING_PATTERNS = [
    {
        "pattern": r"(你好|您好|hi|hello)",
        "response": "你好！我是慢性病管理助手，可以为你提供疾病科普、用药咨询、饮食运动建议、指标解读等服务。请问有什么可以帮你的？"
    },
    {
        "pattern": r"(你是谁|您是谁|你叫什么|你的名字|who are you)",
        "response": "我是慢性病管理助手，一个基于多智能体协作的智能健康问答系统，专注于高血压、糖尿病等慢性病的健康管理。"
    },
    {
        "pattern": r"(在吗|在不在|有人吗)",
        "response": "我在！我是慢性病管理助手，随时为你解答慢性病相关问题！"
    }
]

# 急症快速通道：命中关键词时第一时间给出急救提示，不等待检索/LLM 耗时流程（提示语来自配置文件）
EMERGENCY_TIP = chronic_conf.config.get(
    "chronic_disease", "emergency_tip",
    fallback="如出现胸痛、呼吸困难、意识模糊等紧急情况，请立即拨打120或前往急诊。"
)
EMERGENCY_KEYWORDS = [
    "胸痛", "胸闷", "呼吸困难", "意识模糊", "意识丧失", "昏迷", "晕厥", "晕倒",
    "抽搐", "大出血", "呕血", "咯血", "心跳骤停", "窒息", "休克",
    "剧烈头痛", "口角歪斜", "言语不清", "一侧肢体无力", "半身不遂"
]


# 订单/支付类问句的关键词。命中时不做问候短路——"你好，帮我查订单"同时含问候语与业务意图，
# 按问候语短路会让用户拿到一句自我介绍而不是订单列表（实测踩到过）。
# 这里只决定"要不要跳过问候"，真正的意图路由仍由 RouterAgent 负责。
ORDER_INTENT_KEYWORDS = ["订单", "待支付", "未支付", "支付", "付款", "物流", "发货", "收货", "退款"]


def is_order_query(query: str) -> bool:
    """问句是否在问订单/支付（供问候短路判断用，不参与意图路由）"""
    query_text = (query or "").strip()
    return any(kw in query_text for kw in ORDER_INTENT_KEYWORDS)


def check_greeting(query: str) -> Optional[str]:
    """问候语识别：命中则返回固定问候话术，未命中返回 None（继续走正常问答）"""
    query_text = query.strip()
    if is_order_query(query_text):
        return None
    for pattern_info in GREETING_PATTERNS:
        if re.search(pattern_info["pattern"], query_text, re.IGNORECASE):
            return pattern_info["response"]
    return None


def check_emergency(query: str) -> Optional[str]:
    """急症关键词快筛：命中则立即返回急救提示"""
    query_text = query.strip()
    if any(kw in query_text for kw in EMERGENCY_KEYWORDS):
        return f"⚠️ {EMERGENCY_TIP}"


def check_general_knowledge(query: str) -> Optional[str]:
    """通用知识拦截：非慢性病相关问题返回友好提示"""
    query_text = query.strip()
    # 数学计算类
    if re.search(r'\d+\s*[\+\-\*\/\×\÷]\s*\d+', query_text):
        return "你好！我是慢性病管理助手，专注于高血压、糖尿病等慢性病的健康管理。数学计算问题不在我的专业范围内，建议你使用计算器或搜索引擎哦。"
    # 编程/技术类
    tech_keywords = ["python", "java", "代码", "编程", "算法", "数据库", "服务器", "linux", "windows", "mac"]
    if any(kw in query_text.lower() for kw in tech_keywords):
        return "你好！我是慢性病管理助手，专注于高血压、糖尿病等慢性病的健康管理。技术问题不在我的专业范围内，建议你查阅相关技术文档哦。"
    # 天气/时间类
    if any(kw in query_text for kw in ["天气", "几点", "时间", "日期", "星期"]):
        return "你好！我是慢性病管理助手，专注于高血压、糖尿病等慢性病的健康管理。天气和时间查询不在我的专业范围内，建议你查看手机天气应用哦。"
    # 娱乐/闲聊类
    entertainment_keywords = ["电影", "电视剧", "游戏", "音乐", "明星", "八卦", "笑话", "段子", "唱歌", "跳舞"]
    if any(kw in query_text for kw in entertainment_keywords):
        return "你好！我是慢性病管理助手，专注于高血压、糖尿病等慢性病的健康管理。娱乐话题不在我的专业范围内，但如果你有关于慢性病的问题，我很乐意为你解答！"
    # 其他非医疗类
    other_keywords = ["股票", "基金", "投资", "理财", "房价", "房价", "旅游", "美食", "菜谱", "怎么做菜"]
    if any(kw in query_text for kw in other_keywords):
        return "你好！我是慢性病管理助手，专注于高血压、糖尿病等慢性病的健康管理。这个话题不在我的专业范围内，但如果你有关于慢性病的问题，我很乐意为你解答！"
    return None