"""图状态内存上界与默认兜底话术的回归测试。

    内存上界：官方 InMemorySaver 以 thread_id(=session_id) 为键、无 TTL 无上限地累积
            GraphState，进程长期运行必然单调增长至 OOM。改用 BoundedInMemorySaver
            （LRU 容量上限 + 空闲 TTL）后，需验证淘汰确实生效、且不影响图执行。
    兜底话术：_stream_ordered 曾在 routes 事件缺失时无条件追加 DEFAULT_ANSWER，
            导致在**正确回答之后**又出现「暂时无法回答」的误导话术（医疗场景危害大）。

    运行：python test_graph_bounds.py
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from typing import TypedDict

from langgraph.graph import END, START, StateGraph

from core.checkpointer import BoundedInMemorySaver
from core.graph import DEFAULT_ANSWER, _stream_ordered


class _St(TypedDict):
    """最小图状态：只有一个自增计数，用于观察检查点行为"""
    n: int


def _cfg(tid):
    """构造 LangGraph 运行配置（thread_id 即会话 ID）"""
    return {'configurable': {'thread_id': tid, 'checkpoint_ns': ''}}


def _build_app(checkpointer):
    """构造一个单节点自增图，节点每次执行把 n+1"""
    def node(state):
        return {'n': state.get('n', 0) + 1}

    g = StateGraph(_St)
    g.add_node('n', node)
    g.add_edge(START, 'n')
    g.add_edge('n', END)
    return g.compile(checkpointer=checkpointer)


# ---------------- 内存上界 ----------------

def test_checkpointer_capacity_is_bounded():
    """容量上限：超过 max_threads 后必须淘汰，不得无界增长"""
    cp = BoundedInMemorySaver(max_threads=3, ttl_seconds=0)
    app = _build_app(cp)
    for i in range(10):
        app.invoke({'n': 0}, config=_cfg(f't{i}'))
    assert cp.tracked_threads <= 3, f"未受上限约束: {cp.tracked_threads}"
    assert cp.evicted_total == 7, f"淘汰计数异常: {cp.evicted_total}"


def test_official_saver_is_unbounded_control_group():
    """对照组：官方 InMemorySaver 无上限（证明该缺陷真实存在）"""
    from langgraph.checkpoint.memory import InMemorySaver

    cp = InMemorySaver()
    app = _build_app(cp)
    for i in range(10):
        app.invoke({'n': 0}, config=_cfg(f'u{i}'))
    assert len(cp.storage) == 10, "对照组预期应保留全部 10 个"


def test_checkpointer_evicts_least_recently_used():
    """LRU：淘汰最久未使用者，而非最新者"""
    cp = BoundedInMemorySaver(max_threads=2, ttl_seconds=0)
    app = _build_app(cp)
    for tid in ('a', 'b'):
        app.invoke({'n': 0}, config=_cfg(tid))
    cp.get_tuple(_cfg('a'))                    # 访问 a -> b 成最久未用
    app.invoke({'n': 0}, config=_cfg('c'))     # 插入 c -> 应淘汰 b
    assert set(cp._lru.keys()) == {'a', 'c'}, f"LRU 异常: {set(cp._lru.keys())}"


def test_checkpointer_ttl_expires_idle_threads():
    """TTL：空闲超时的会话被清理"""
    cp = BoundedInMemorySaver(max_threads=0, ttl_seconds=1)
    app = _build_app(cp)
    app.invoke({'n': 0}, config=_cfg('old'))
    assert cp.tracked_threads == 1
    time.sleep(1.2)
    app.invoke({'n': 0}, config=_cfg('new'))   # 触发惰性淘汰
    assert cp.tracked_threads == 1, f"TTL 未生效: {cp.tracked_threads}"


def test_checkpointer_limits_can_be_disabled():
    """限制可关闭：max_threads<=0 且 ttl<=0 时不淘汰（保持灵活性）"""
    cp = BoundedInMemorySaver(max_threads=0, ttl_seconds=0)
    app = _build_app(cp)
    for i in range(50):
        app.invoke({'n': 0}, config=_cfg(f'x{i}'))
    assert cp.tracked_threads == 50, f"限制未被正确关闭: {cp.tracked_threads}"


def test_eviction_does_not_break_graph_execution():
    """淘汰只丢图内部状态，不得影响功能：被淘汰的会话重跑应正常"""
    cp = BoundedInMemorySaver(max_threads=1, ttl_seconds=0)
    app = _build_app(cp)
    r1 = app.invoke({'n': 0}, config=_cfg('s1'))
    app.invoke({'n': 0}, config=_cfg('s2'))       # 挤掉 s1
    r2 = app.invoke({'n': 0}, config=_cfg('s1'))  # s1 重建
    assert r1['n'] == 1 and r2['n'] == 1, f"淘汰导致功能异常: {r1} / {r2}"


# ---------------- 默认兜底话术 ----------------

def test_routes_missing_does_not_append_misleading_text():
    """回归：routes 事件丢失但已有内容时，不得追加「暂时无法回答」"""
    events = [
        {'type': 'expert_delta', 'order': 0, 'agent': 'disease', 'token': '内容甲'},
        {'type': 'expert_delta', 'order': 0, 'agent': 'disease', 'token': '内容乙'},
        {'type': 'expert_done', 'order': 0, 'agent': 'disease'},
    ]
    out = ''.join(_stream_ordered(events))
    assert DEFAULT_ANSWER not in out, f"仍在追加误导话术: {out!r}"
    assert '内容甲' in out and '内容乙' in out, f"内容丢失: {out!r}"


def test_empty_stream_still_gets_default_answer():
    """未过度修复：完全无产出时仍应兜底默认话术"""
    out = ''.join(_stream_ordered([]))
    assert out == DEFAULT_ANSWER, f"空回答未兜底: {out!r}"


def test_normal_multi_expert_stream_unchanged():
    """既有行为无回归：多专家分段顺序与格式保持不变"""
    events = [
        {'type': 'routes', 'routes': [{'order': 0, 'agent': 'disease'},
                                      {'order': 1, 'agent': 'medication'}]},
        {'type': 'expert_delta', 'order': 0, 'agent': 'disease', 'token': 'A段'},
        {'type': 'expert_delta', 'order': 1, 'agent': 'medication', 'token': 'B段'},
        {'type': 'expert_done', 'order': 0, 'agent': 'disease'},
        {'type': 'expert_done', 'order': 1, 'agent': 'medication'},
    ]
    out = ''.join(_stream_ordered(events))
    assert out.index('A段') < out.index('B段'), f"分段顺序异常: {out!r}"
    assert '【disease】' in out and '【medication】' in out, f"前缀缺失: {out!r}"


if __name__ == "__main__":
    import traceback

    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    failed = 0
    for t in tests:
        try:
            t()
            print(f"[PASS] {t.__name__}")
        except Exception:
            failed += 1
            print(f"[FAIL] {t.__name__}")
            traceback.print_exc()
    print(f"\n{len(tests) - failed}/{len(tests)} passed")
    sys.exit(1 if failed else 0)
