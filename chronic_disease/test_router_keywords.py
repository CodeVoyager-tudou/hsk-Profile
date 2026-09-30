"""路由关键词兜底的离线验证：零 LLM / 检索依赖。

    覆盖三件事：
      1. keyword_route / keyword_routes 对评测集 18 题的期望源判定是否一致；
      2. LLM 不可用时 RouterAgent 是否走关键词兜底（注入会抛异常的假 client）；
      3. LLM 成功但漏掉高置信专家时，是否把关键词命中的专家并回候选
         （注入返回缺 lab 路由的假 client）。
    运行：python test_router_keywords.py
"""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from core.router_keywords import (
    AGENT_KEYWORDS,
    STRONG_KEYWORDS,
    keyword_route,
    keyword_routes,
)
from agents.router_agent import RouterAgent

PASS = 0
FAIL = 0


def check(name, cond, detail=""):
    """断言并累加统计（本文件不用 pytest，自带极简计数器）"""
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  PASS  {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name}  {detail}")


# ---------- 1) 关键词层：18 评测题的期望源 ----------
# expected_source 与 run_eval.py 一致；emergency/greeting 在真实链路里由前置拦截处理，
# 关键词层不应误路由到任何专家（返回 None）。
EVAL_CASES = [
    ("高血压的诊断标准是多少？", "disease"),
    ("高血压有哪些常见症状？", "disease"),
    ("什么是原发性高血压？", "disease"),
    ("高血压的危害有哪些？", "disease"),
    ("血压多少需要开始吃降压药？", "medication"),
    ("常用降压药有哪几大类？", "medication"),
    ("降压药能自己停吗？", "medication"),
    ("高血压患者每天吃多少盐合适？", "lifestyle"),
    ("高血压患者适合什么运动？", "lifestyle"),
    ("高血压患者饮食上要注意什么？", "lifestyle"),
    ("空腹血糖的正常范围是多少？", "lab"),
    ("低密度脂蛋白偏高说明什么？", "lab"),
    ("血钾偏低有什么风险？", "lab"),
    ("高血压患者出现哪些情况要马上去医院？", "risk"),
    ("血压突然升得很高怎么办？", "risk"),
    # emergency 在真实链路里由紧急拦截先处理（eval 0.00s 返回 120 提示）；关键词层命中 risk
    # 是第二道安全网——路由到风险预警专家好过落到 disease。Q18 问候语无关键词 → None。
    ("突然胸痛呼吸困难怎么办", "risk"),
    ("我老爸今天晕倒了怎么办", "risk"),
    ("你好", None),
]


def t1_keyword_route():
    print("[1] keyword_route：18 题命中源与期望一致")
    for q, exp in EVAL_CASES:
        agent, score = keyword_route(q)
        check(f"route({q[:12]}…) = {agent}", agent == exp,
              f"expect {exp}, got {agent} (score={score})")


def t2_keyword_routes_fallback_and_strong():
    print("[2] keyword_routes：兜底(min=1) 与强命中(min=2) 语义")
    # Q08 盐/吃多少 → lifestyle 2 分：min=2 强命中
    check("Q08 强命中含 lifestyle", "lifestyle" in keyword_routes("高血压患者每天吃多少盐合适？", min_score=2))
    # Q05 降压药 → medication 1 分：min=2 不应命中（低置信不并入 LLM 结果）
    check("Q05 非强命中", "medication" not in keyword_routes("血压多少需要开始吃降压药？", min_score=2))
    # Q14 去医院/医院/危险 → risk 3 分：兜底应返回且 risk 排在首
    fb = keyword_routes("高血压患者出现哪些情况要马上去医院？", min_score=1)
    check("Q14 兜底首源是 risk", fb and fb[0] == "risk", str(fb))

    # 高辨识度词（STRONG_KEYWORDS）单命中就要达到"强命中"：
    # "阿司匹林肠溶片多少钱？" 里 mall 只命中"多少钱"一个词，若按 1 分算就够不上
    # min_score=2，LLM 又常常只选 medication → 商城专家缺席、回答里没有价格（实测踩到）。
    check("单价问句单命中即强命中 mall",
          "mall" in keyword_routes("阿司匹林肠溶片多少钱？", min_score=2))
    check("我的积分/优惠券单命中即强命中 mall",
          "mall" in keyword_routes("我有多少积分", min_score=2)
          and "mall" in keyword_routes("我的优惠券还有几张", min_score=2))
    check("订单类单命中即强命中 order",
          "order" in keyword_routes("我有哪些未支付的订单", min_score=2)
          and "order" in keyword_routes("我有哪些是已退款的", min_score=2))
    # 反向：纯医学问句不该被数据专家抢走
    check("医学问句不误入 mall/order",
          keyword_routes("什么是原发性高血压？", min_score=2) == []
          and keyword_routes("高血压患者每天吃多少盐合适？", min_score=2) == ["lifestyle"],
          str(keyword_routes("高血压患者每天吃多少盐合适？", min_score=2)))


# ---------- 2/3) RouterAgent：LLM 失败兜底 + 成功并集 ----------
class BoomClient:
    """模拟 Ollama 不可达：调用即抛异常"""
    def chat(self, **kwargs):
        raise ConnectionError("Ollama down")


class DummyClient:
    """模拟 LLM 正常返回：content 为预设 JSON 字符串"""
    def __init__(self, content: str):
        self.content = content

    def chat(self, **kwargs):
        class Resp:
            class Choice:
                class Msg:
                    def __init__(self, c):
                        self.content = c
                def __init__(self, c):
                    self.message = self.Msg(c)
            def __init__(self, c):
                self.choices = [self.Choice(c)]
        return Resp(self.content)


def t3_llm_down_fallback():
    print("[3] LLM 失败：关键词兜底替换无条件 disease")
    r = RouterAgent()
    r.client = BoomClient()
    # Q05 → medication
    res = r.route("血压多少需要开始吃降压药？")
    agents = [x["agent"] for x in res]
    check("Q05 兜底到 medication", "medication" in agents, str(agents))
    # Q13 → lab
    res = r.route("血钾偏低有什么风险？")
    agents = [x["agent"] for x in res]
    check("Q13 兜底到 lab", "lab" in agents, str(agents))
    # Q15 → risk（"突然"修复后）
    res = r.route("血压突然升得很高怎么办？")
    agents = [x["agent"] for x in res]
    check("Q15 兜底到 risk", "risk" in agents, str(agents))
    # 完全无关键词 → 仍落 disease（保底不报错）
    res = r.route("哦这样啊")
    agents = [x["agent"] for x in res]
    check("无关键词仍兜底 disease", "disease" in agents, str(agents))


def t4_llm_ok_merge():
    print("[4] LLM 成功：关键词强命中但被漏掉的专家并回候选")
    r = RouterAgent()
    # LLM 只返回 disease（漏掉 lab），但 Q13 强命中 lab → 应并入且上限 3 路
    r.client = DummyClient('[{"agent": "disease", "query": "血钾偏低有什么风险？"}]')
    res = r.route("血钾偏低有什么风险？")
    agents = [x["agent"] for x in res]
    check("并集后含 lab", "lab" in agents, str(agents))
    check("并集后不超过 3 路", len(res) <= 3, str(res))
    # 正常单路（无强命中专家被漏）：结果原样
    r.client = DummyClient('[{"agent": "disease", "query": "高血压有哪些常见症状？"}]')
    res = r.route("高血压有哪些常见症状？")
    agents = [x["agent"] for x in res]
    check("无强命中不追加", agents == ["disease"], str(agents))
    # 去重：LLM 已含 lab 时不重复追加
    r.client = DummyClient('[{"agent": "lab", "query": "血钾偏低有什么风险？"}]')
    res = r.route("血钾偏低有什么风险？")
    agents = [x["agent"] for x in res]
    check("已含 lab 不重复", agents == ["lab"], str(agents))


def t5_structural_consistency():
    """结构性一致性断言（复核 P1-7 / P2-2）：一次编写，永久拦住"写了等于没写"的静默失效"""
    print("[5] 结构一致性：STRONG ⊆ AGENT_KEYWORDS；user_scoped 标记与签名一致")
    # 1) STRONG_KEYWORDS 的每个词必须出现在对应 AGENT_KEYWORDS 里：
    #    _score 只遍历后者，STRONG 里独有的词永远不会被计分。
    #    待付款/已支付/已取消 曾整组静默失效（"我有已付款的单子吗"路由不到订单专家）。
    for agent, strong in STRONG_KEYWORDS.items():
        missing = [kw for kw in strong if kw not in AGENT_KEYWORDS.get(agent, [])]
        check(f"STRONG[{agent}] ⊆ AGENT_KEYWORDS[{agent}]", not missing, f"死条目: {missing}")

    # 2) user_scoped 类属性必须与 generate_text 签名一致：签名收 user_id 的专家
    #    必须标 user_scoped=True（漏标 = 静默答错，A1 bug 的根因），反之亦然。
    #    新增专家自动被本断言覆盖，无需手工登记。
    import inspect
    from core.coordinator import _AGENT_CLASSES
    for key, cls in _AGENT_CLASSES.items():
        accepts_uid = "user_id" in inspect.signature(cls.generate_text).parameters
        check(f"{key}: user_scoped 标记与 generate_text 签名一致",
              accepts_uid == bool(getattr(cls, "user_scoped", False)),
              f"accepts_user_id={accepts_uid}, marked={getattr(cls, 'user_scoped', False)}")

    # 3) 两份派生清单必须一致（本项目的接口数据型专家同时都是 user-scoped），
    #    且恰好覆盖 order/mall —— 清单互相漂移时在这里炸，而不是线上静默答错。
    from core.coordinator import DATA_SOURCE_AGENT_KEYS, USER_SCOPED_AGENT_KEYS
    check("USER_SCOPED 与 DATA_SOURCE 清单一致",
          set(USER_SCOPED_AGENT_KEYS) == set(DATA_SOURCE_AGENT_KEYS) == {"order", "mall"},
          f"scoped={USER_SCOPED_AGENT_KEYS}, data={DATA_SOURCE_AGENT_KEYS}")
    # 4) 修复 P1-7 后的状态类问句必须能路由到订单专家
    check("状态类问句路由到 order",
          keyword_routes("我有已付款的单子吗", 1) == ["order"]
          and keyword_routes("查一下待付款的单子", 1) == ["order"],
          str(keyword_routes("我有已付款的单子吗", 1)))
    # 5) 消费/汇总类问句必须路由到订单专家（第 2 轮发现：路由词表缺"花了/消费"，
    #    「我最近30天花了多少钱」只被商城（"多少钱"）接走，订单专家根本没参与）
    check("汇总类问句强命中 order（花了/消费）",
          "order" in keyword_routes("我最近30天花了多少钱？", 2)
          and "order" in keyword_routes("我这个月消费了多少", 2)
          and "order" in keyword_routes("我一共花了多少钱", 2),
          str(keyword_routes("我最近30天花了多少钱？", 2)))
    check("汇总类问句也命中 mall（含'多少钱'），两路都要在",
          "mall" in keyword_routes("我最近30天花了多少钱？", 2),
          str(keyword_routes("我最近30天花了多少钱？", 2)))
    # 反向：用药问句里的"一共"不该把订单专家拉进来（"一共"只在弱词表里）
    check("用药问句不被'一共'带进 order",
          "order" not in keyword_routes("这个药一天一共吃几次", 2),
          str(keyword_routes("这个药一天一共吃几次", 2)))


if __name__ == "__main__":
    for t in (t1_keyword_route, t2_keyword_routes_fallback_and_strong,
              t3_llm_down_fallback, t4_llm_ok_merge, t5_structural_consistency):
        t()
    print("=" * 50)
    print(f"结果: {PASS} 通过 / {FAIL} 失败")
    sys.exit(1 if FAIL else 0)
