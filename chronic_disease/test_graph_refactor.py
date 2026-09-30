"""LangGraph 编排的离线验证：用 FakeAgent/FakeRouter 注入，零 LLM 与检索依赖。

    覆盖：非流式单路/多路、流式的分段顺序与单路无前缀、路由过滤与截断、
    空路由兜底、专家异常兜底、并行耗时（并行应快于串行）、顺序闸门纯函数行为、
    search_query 透传。运行：python test_graph_refactor.py
"""
import sys
import os
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from core.graph import (
    DEFAULT_ANSWER,
    _stream_ordered,
    build_chronic_graph,
)
from core.coordinator import should_let_llm_merge

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


class FakeAgent:
    """模拟专家：generate_text 非流式返回全文；generate_response(stream=True) 逐 token 吐字"""

    def __init__(self, key: str, cname: str, text: str, delay: float = 0.0, raise_on_stream: bool = False):
        """delay 用于验证并行耗时；raise_on_stream=True 模拟专家执行失败"""
        self.key = key
        self.name = cname
        self.text = text
        self.delay = delay
        self.raise_on_stream = raise_on_stream
        self.calls = []  # 记录 (query, search_query) 供断言

    def generate_text(self, question, search_query=None, history_text=""):
        self.calls.append((question, search_query))
        if self.raise_on_stream:
            raise RuntimeError("llm down")
        return self.text

    def generate_response(self, question, stream=False, search_query=None, history_text=""):
        self.calls.append((question, search_query))
        if self.raise_on_stream:
            raise RuntimeError("llm down")
        time.sleep(self.delay)
        for ch in self.text:
            yield ch


class FakeRouter:
    """固定返回预设路由结果的假路由器"""

    def __init__(self, result):
        self.result = result

    def route(self, query, history_text=""):
        """忽略入参，直接返回构造时给定的路由列表"""
        return self.result


class FakeUserScopedAgent(FakeAgent):
    """模拟 order / mall：声明 user_scoped 类属性（= 需要 user_id 才能作答），并记录收到的值。

    契约（复核 P2-2 重构后）：**由 Agent 类上的 `user_scoped` 类属性声明**是否需要 user_id，
    图按它决定传不传；不再是"按路由键白名单"。RAG 系专家（FakeAgent）没有这个标记，
    多传会 TypeError —— 这就是本类存在的理由。
    """

    # 与真实 OrderAgent / MallAgent 一致：类属性声明，不是实例/键名判断
    user_scoped = True

    def __init__(self, key: str, cname: str, text: str):
        super().__init__(key, cname, text)
        self.user_ids = []

    def generate_text(self, question, search_query=None, history_text="", user_id=None):
        self.user_ids.append(user_id)
        return self.text

    def generate_response(self, question, stream=False, search_query=None, history_text="", user_id=None):
        self.user_ids.append(user_id)
        yield self.text


INVOKE_CFG = {"configurable": {"streaming": False}}
STREAM_CFG = {"configurable": {"streaming": True}}
INIT_STATE = {"history_text": "", "routes": [], "responses": [], "final_answer": ""}


def invoke_graph(g, query, routes):
    """以非流式配置跑一遍图，返回最终 state（断言 final_answer / responses 用）"""
    state = dict(INIT_STATE, user_query=query)
    return g.invoke(state, config=INVOKE_CFG)


def stream_graph(g, query):
    """以流式配置跑一遍图，经顺序闸门收集成完整文本"""
    state = dict(INIT_STATE, user_query=query)
    events = g.stream(state, stream_mode="custom", config=STREAM_CFG)
    return "".join(_stream_ordered(events))


def t1_nonstream_single():
    print("[1] 非流式单路：返回 str 且内容原样")
    agents = {"disease": FakeAgent("disease", "疾病科普员", "高血压是慢性病。")}
    g = build_chronic_graph(agents=agents, router=FakeRouter([{"agent": "disease", "query": "什么是高血压"}]))
    state = invoke_graph(g, "什么是高血压", None)
    ans = state["final_answer"]
    check("返回类型是 str（P0 回归）", type(ans) is str, f"got {type(ans)}")
    check("内容原样", ans == "高血压是慢性病。", ans)


def t2_nonstream_multi_order():
    print("[2] 非流式三路：整合函数按路由顺序收到全文")
    agents = {
        "a": FakeAgent("a", "甲", "A内容"),
        "b": FakeAgent("b", "乙", "B内容"),
        "c": FakeAgent("c", "丙", "C内容"),
    }
    seen = {}

    def synth(query, responses):
        seen["responses"] = responses
        return "整合结果"

    g = build_chronic_graph(agents=agents, router=FakeRouter([
        {"agent": "c", "query": "q3", "search_query": "s3"},
        {"agent": "a", "query": "q1"},
        {"agent": "b", "query": "q2"},
    ]), synthesize_fn=synth)
    ans = invoke_graph(g, "q", None)["final_answer"]
    check("返回整合结果（走 synthesize_fn 而非拼接）", ans == "整合结果", ans)
    order = [r["agent"] for r in seen.get("responses", [])]
    # 路由顺序即 c(丙)=0, a(甲)=1, b(乙)=2，responses 应按 order 排序
    check("整合收到按 order 排序的 responses", order == ["丙", "甲", "乙"], str(order))
    by_name = {ag.name: ag for ag in agents.values()}
    check("整合收到全文", all(r["response"] == by_name[r["agent"]].text for r in seen["responses"]))


def t3_stream_multi_ordered():
    print("[3] 流式三路：乱序完成仍严格按序输出分段")
    agents = {
        "a": FakeAgent("a", "甲", "AAAA", delay=0.30),
        "b": FakeAgent("b", "乙", "BB", delay=0.05),
        "c": FakeAgent("c", "丙", "CCC", delay=0.15),
    }
    g = build_chronic_graph(agents=agents, router=FakeRouter([
        {"agent": "a", "query": "q1"},
        {"agent": "b", "query": "q2"},
        {"agent": "c", "query": "q3"},
    ]))
    out = stream_graph(g, "q")
    expected = "【甲】\nAAAA\n\n【乙】\nBB\n\n【丙】\nCCC\n\n"
    check("分段前缀+顺序+分隔符精确相等", out == expected, repr(out))


def t4_stream_single_no_prefix():
    print("[4] 流式单路：无前缀、无分隔符")
    agents = {"disease": FakeAgent("disease", "疾病科普员", "答案内容")}
    g = build_chronic_graph(agents=agents, router=FakeRouter([{"agent": "disease", "query": "q"}]))
    out = stream_graph(g, "q")
    check("单路输出无【前缀】", out == "答案内容", repr(out))


def t5_router_filter_and_truncate():
    print("[5] 路由：非法键过滤 + 最多3路截断")
    agents = {k: FakeAgent(k, k.upper(), f"{k}文本") for k in ("a", "b", "c", "d")}
    g = build_chronic_graph(agents=agents, router=FakeRouter([
        {"agent": "a", "query": "q1"},
        {"agent": "ghost", "query": "q2"},  # 非法键，应被过滤
        {"agent": "b", "query": "q3"},
        {"agent": "c", "query": "q4"},
        {"agent": "d", "query": "q5"},      # 第4路，应被截断
    ]))
    state = invoke_graph(g, "q", None)
    routed = sorted(r["agent"] for r in state["responses"])
    check("非法键被过滤且截断为3路", routed == ["A", "B", "C"], str(routed))
    check("被截断的第4路从未被调用", agents["d"].calls == [])


def t6_empty_routes():
    print("[6] 空路由：非流式与流式均返回默认话术")
    agents = {"a": FakeAgent("a", "甲", "A")}
    g = build_chronic_graph(agents=agents, router=FakeRouter([]))
    # 契约：空路由时 final_answer 为空，由 facade 层（coordinator.query）负责 or DEFAULT_ANSWER 兜底
    raw = invoke_graph(g, "q", None)["final_answer"]
    ans = raw or DEFAULT_ANSWER
    check("非流式返回默认话术", ans == DEFAULT_ANSWER, repr(raw))
    out = stream_graph(g, "q")
    check("流式返回默认话术", out == DEFAULT_ANSWER, repr(out))


def t7_expert_error():
    print("[7] 专家异常：流式该段补兜底文案；非流式返回兜底文案")
    agents = {
        "a": FakeAgent("a", "甲", "正常内容", delay=0.02),
        "b": FakeAgent("b", "乙", "BB", raise_on_stream=True),
    }
    g = build_chronic_graph(agents=agents, router=FakeRouter([{"agent": "a", "query": "q1"},
                                                              {"agent": "b", "query": "q2"}]))
    out = stream_graph(g, "q")
    # 期望文案是 FALLBACK_SECTION_ERROR（"生成失败，请稍后重试"）而不是 FALLBACK_SECTION：
    # 复核 P2-13 把"出过错"与"本来就是空回复"分开，这条断言当时没跟着改
    # （这两个独立脚本 pytest 不收集，所以一直没人发现 —— 第 2 轮复核记录在案）
    check("流式：异常段兜底、正常段完整",
          out == "【甲】\n正常内容\n\n【乙】\n抱歉，该部分生成失败，请稍后重试。\n\n", repr(out))


def t7b_single_expert_error_no_default_append():
    """回归（复核 R2-6）：**单专家**出错时只给"生成失败"文案，绝不能追加默认话术。

    R2-5 把收尾判据从 routes 改成 emitted 时引入的回归：emitted 只统计真 token，
    出错段的 FALLBACK_SECTION_ERROR 不算数 → 单专家 + 出错时用户收到
    "该部分生成失败，请稍后重试。抱歉，暂时无法回答您的问题。" 两句互相矛盾的话。
    t7 抓不到它（两路，正常路填了 emitted），t13 也抓不到（沉默路不走 errored 分支）——
    漏的正是两者之间的格子，这条用例把格子钉住。
    """
    print("[7b] 单专家出错：只输出失败文案，不追加默认话术")
    agents = {"b": FakeAgent("b", "乙", "BB", raise_on_stream=True)}
    g = build_chronic_graph(agents=agents, router=FakeRouter([{"agent": "b", "query": "q2"}]))
    out = stream_graph(g, "q")
    check("单专家出错输出恰为失败文案", out == "抱歉，该部分生成失败，请稍后重试。", repr(out))
    check("不追加默认话术（R2-6 回归点）", DEFAULT_ANSWER not in out, repr(out))


def t8_parallel_timing():
    print("[8] 并行时序：三路各 0.5s，总耗时应≈0.5s 而非 1.5s")
    agents = {k: FakeAgent(k, k.upper(), "内容" * 5, delay=0.5) for k in ("a", "b", "c")}
    g = build_chronic_graph(agents=agents, router=FakeRouter([{"agent": k, "query": "q"} for k in ("a", "b", "c")]))
    t0 = time.time()
    stream_graph(g, "q")
    cost = time.time() - t0
    check(f"墙钟 {cost:.2f}s < 1.2s（并行生效）", cost < 1.2, f"{cost:.2f}s")


def t9_gate_pure():
    print("[9] 顺序闸门纯单元：交错事件流 → 有序输出")
    events = [
        {"type": "routes", "routes": [{"order": 0, "agent": "甲"}, {"order": 1, "agent": "乙"}]},
        {"type": "expert_delta", "order": 1, "agent": "乙", "token": "乙1"},
        {"type": "expert_delta", "order": 0, "agent": "甲", "token": "甲1"},
        {"type": "expert_delta", "order": 1, "agent": "乙", "token": "乙2"},
        {"type": "expert_done", "order": 1, "agent": "乙"},
        {"type": "expert_delta", "order": 0, "agent": "甲", "token": "甲2"},
        {"type": "expert_done", "order": 0, "agent": "甲"},
    ]
    out = "".join(_stream_ordered(iter(events)))
    check("闸门输出严格有序", out == "【甲】\n甲1甲2\n\n【乙】\n乙1乙2\n\n", repr(out))


def t10_search_query_passthrough():
    print("[10] search_query 透传：检索改写词到达 generate_text / generate_response")
    agents = {"a": FakeAgent("a", "甲", "A")}
    g = build_chronic_graph(agents=agents, router=FakeRouter([{"agent": "a", "query": "子问题", "search_query": "医学改写词"}]))
    invoke_graph(g, "原问题", None)
    check("非流式透传", agents["a"].calls and agents["a"].calls[0] == ("子问题", "医学改写词"),
          str(agents["a"].calls))


def t11_user_id_reaches_mall_too():
    """回归：user_id 曾经只传给 order 专家，商城专家拿不到就只回一句"需要先登录"。

    这个 bug 不报错、日志里也只是 0.00s 的"已响应"，最终表现为整合模型
    说"价格资料里没提到"——所以必须有断言把它钉住：order/mall 都要收到 user_id，
    而没有该形参的 RAG 专家不能被多传（多传会 TypeError）。
    """
    print("[11] user_id 传递：order 与 mall 都要收到，RAG 专家不能收到")
    agents = {
        "order": FakeUserScopedAgent("order", "订单查询", "订单内容"),
        "mall": FakeUserScopedAgent("mall", "商城与资产", "商城内容"),
        "disease": FakeAgent("disease", "疾病科普员", "医学内容"),
    }
    routes = [{"agent": "order", "query": "q1"},
              {"agent": "mall", "query": "q2"},
              {"agent": "disease", "query": "q3"}]
    g = build_chronic_graph(agents=agents, router=FakeRouter(routes))
    state = dict(INIT_STATE, user_query="q", user_id="42")
    g.invoke(state, config=INVOKE_CFG)
    check("订单专家收到 user_id", agents["order"].user_ids == ["42"], str(agents["order"].user_ids))
    check("商城专家收到 user_id（回归点）", agents["mall"].user_ids == ["42"], str(agents["mall"].user_ids))
    check("RAG 专家仍不被多传 user_id", agents["disease"].calls and agents["disease"].calls[0] == ("q3", None),
          str(agents["disease"].calls))

    # 流式路径走的是 generate_response 分支，同样要带上 user_id（否则前端流式问答仍答不出价格）。
    # 注意：stream() 是生成器，必须把事件消费掉专家节点才会真正执行
    agents["mall"].user_ids.clear()
    list(g.stream(dict(INIT_STATE, user_query="q", user_id="42"), stream_mode="custom", config=STREAM_CFG))
    check("流式路径商城专家也收到 user_id", agents["mall"].user_ids == ["42"], str(agents["mall"].user_ids))


def t12_interface_data_experts_and_display_names():
    """接口数据型专家的两条用户可见约定：

    1. 分段标题必须是显示名（订单查询 / 商城与资产），不能是内部路由键（order / mall）；
    2. 一旦它们参与回答就不交给 LLM 整合 —— 模型会把具体金额改写成"请咨询药店"，
       这正是 e2e 里价格时而答不出来的原因。
    """
    print("[12] 接口数据专家的显示名与「不交给 LLM 复述」策略")
    agents = {
        "order": FakeUserScopedAgent("order", "订单查询", "现金价：¥12.0"),
        "mall": FakeUserScopedAgent("mall", "mall", "现金价：¥12.0"),
    }
    # 真实 OrderAgent/MallAgent 用 display_name 存显示名（name 是路由键）
    agents["order"].display_name = "订单查询"
    agents["mall"].display_name = "商城与资产"

    g = build_chronic_graph(agents=agents, router=FakeRouter([
        {"agent": "order", "query": "q1"}, {"agent": "mall", "query": "q2"}]))
    state = g.invoke(dict(INIT_STATE, user_query="q", user_id="1"), config=INVOKE_CFG)
    names = sorted(r["agent"] for r in state["responses"])
    keys = sorted(r.get("key") for r in state["responses"])
    check("分段标题用显示名而非路由键", names == ["商城与资产", "订单查询"], str(names))
    check("responses 保留路由键供整合阶段判断", keys == ["mall", "order"], str(keys))

    # 流式路径的分段标题同样取显示名（前端用户看到的就是这个）
    events = list(g.stream(dict(INIT_STATE, user_query="q", user_id="1"),
                           stream_mode="custom", config=STREAM_CFG))
    routes_ev = next((e for e in events if e.get("type") == "routes"), None)
    check("流式 routes 事件也是显示名",
          routes_ev and sorted(r["agent"] for r in routes_ev["routes"]) == ["商城与资产", "订单查询"],
          str(routes_ev))

    # 整合策略：含订单/商城就不整合；纯医学多路仍走 LLM 整合
    check("含商城专家 → 不交给 LLM 整合",
          should_let_llm_merge([{"agent": "用药顾问", "key": "medication", "response": "x"},
                                {"agent": "商城与资产", "key": "mall", "response": "y"}]) is False)
    check("纯 RAG 多路 → 仍然整合",
          should_let_llm_merge([{"agent": "用药顾问", "key": "medication", "response": "x"},
                                {"agent": "疾病科普员", "key": "disease", "response": "y"}]) is True)
    check("老结构（没有 key 字段）不应崩，按可整合处理",
          should_let_llm_merge([{"agent": "用药顾问", "response": "x"}]) is True)


def t13_silent_expert_sections_are_dropped():
    """专家"不发言"（返回空串）时段落要整段丢掉 —— 非流式与流式两条路径都要丢。

    为什么必须有这条：商城专家遇到订单/消费类问句时会返回空串，表示"这一路没话可说"。
    输出层若照旧加标题，用户会看到空的「【商城与资产】」；流式路径更糟 ——
    闸门会给"没内容也没出错"的段补一句「该部分暂时无法生成」，等于凭空报错。
    """
    print("[13] 空回答的专家：段落整段丢弃（非流式 + 流式）")

    agents = {
        "order": FakeUserScopedAgent("order", "订单查询", "订单笔数：3 笔"),
        "mall": FakeUserScopedAgent("mall", "商城与资产", ""),      # 不发言
    }
    agents["order"].display_name = "订单查询"
    agents["mall"].display_name = "商城与资产"
    g = build_chronic_graph(agents=agents, router=FakeRouter([
        {"agent": "order", "query": "q1"}, {"agent": "mall", "query": "q2"}]))
    ans = g.invoke(dict(INIT_STATE, user_query="我最近30天花了多少钱", user_id="1"),
                   config=INVOKE_CFG)["final_answer"]
    check("非流式：沉默的段不出现在回答里", "商城与资产" not in ans and "订单笔数" in ans, repr(ans))

    out = "".join(_stream_ordered(list(g.stream(
        dict(INIT_STATE, user_query="我最近30天花了多少钱", user_id="1"),
        stream_mode="custom", config=STREAM_CFG))))
    check("流式：沉默的段不补兜底文案", "无法生成" not in out and "商城与资产" not in out, repr(out))
    check("流式：有内容那一路照常输出", "订单笔数：3 笔" in out, repr(out))

    # 全体沉默：必须给兜底话术，不能吐空流（流式）或空回答（非流式）
    silent = {
        "a": FakeUserScopedAgent("a", "甲", ""),
        "b": FakeUserScopedAgent("b", "乙", ""),
    }
    g2 = build_chronic_graph(agents=silent, router=FakeRouter([
        {"agent": "a", "query": "q1"}, {"agent": "b", "query": "q2"}]))
    check("非流式：全空回兜底话术",
          g2.invoke(dict(INIT_STATE, user_query="q", user_id="1"),
                    config=INVOKE_CFG)["final_answer"] == DEFAULT_ANSWER)
    out2 = "".join(_stream_ordered(list(g2.stream(
        dict(INIT_STATE, user_query="q", user_id="1"),
        stream_mode="custom", config=STREAM_CFG))))
    check("流式：全空也回兜底话术（不吐空流）", out2.strip() == DEFAULT_ANSWER, repr(out2))


if __name__ == "__main__":
    for t in (t1_nonstream_single, t2_nonstream_multi_order, t3_stream_multi_ordered,
              t4_stream_single_no_prefix, t5_router_filter_and_truncate, t6_empty_routes,
              t7_expert_error, t7b_single_expert_error_no_default_append, t8_parallel_timing,
              t9_gate_pure, t10_search_query_passthrough, t11_user_id_reaches_mall_too,
              t12_interface_data_experts_and_display_names,
              t13_silent_expert_sections_are_dropped):
        t()
    print("=" * 50)
    print(f"结果: {PASS} 通过 / {FAIL} 失败")
    sys.exit(1 if FAIL else 0)
