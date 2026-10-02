"""LangGraph 多智能体编排（慢性病 RAG）。

【小白先看：LangGraph 是干什么的】
    大模型只会「你问我答」一次。但真实问诊需要好几步：
    先判断问题该由哪个科室回答 -> 可能有多个科室都需要参与 -> 最后把几段回答整合起来。
    这种「多个步骤 + 分支 + 并行」的流程，用 if/else 手写会非常乱。
    LangGraph 让你像画流程图一样把步骤连起来，它负责按图执行。

【本文件的流程图（这是整个 AI 服务的大脑）】
        START
          |
        route（路由：这一步判断问题属于哪些科室，最多选 3 个）
          |
          ├── 没有匹配到任何科室 ──────────────> END
          |
          └── Send 派发（并行！多个专家同时干活，总耗时 ≈ 最慢的那一个）
                 ├─ expert（疾病专家）  ─┐
                 ├─ expert（用药专家）  ─┤ 各自去检索知识库 + 调大模型
                 └─ expert（…）        ─┘
                          |
                     synthesize（整合：把几段回答合成一份）
                          |
                         END

    举例：用户问「高血压吃什么药要注意什么」
      route 判定需要「疾病」+「用药」两个专家
      -> 两个专家同时工作（不是排队，所以更快）
      -> synthesize 把两段合并成一份完整回答

【「并行执行、有序输出」是怎么做到的】
    多个专家同时向同一个输出流写内容，谁先谁后是不确定的，
    用户可能先看到「用药」再看到「疾病」，读起来很乱。
    所以设计了 _stream_ordered() 这个「顺序闸门」：
    虽然专家是并行跑的，但输出会按固定顺序依次放出，看起来就像串行生成的。

【设计要点】
- LangGraph 只做编排，LLM 调用复用现有的 BaseAgent / RouterAgent（原生 OpenAI SDK + prompts.py）
- 并行专家的 token 事件经 stream_mode="custom" + get_stream_writer() 上报，
  由 facade 层的 _stream_ordered() 顺序闸门保证输出严格有序（并行执行、有序输出）
- 同步图：Python 3.10 下 get_stream_writer() 在异步上下文会 raise RuntimeError，
  且 LLM SDK 为同步，故锁定"同步图 + 工作线程消费"，不使用 astream/astream_events
"""
import time
from typing import Annotated, Any, Callable, Dict, Generator, Iterable, List, Optional, TypedDict

from langgraph.config import get_config, get_stream_writer
from langgraph.graph import END, START, StateGraph
from langgraph.types import Send

from base.logger import logger

DEFAULT_ANSWER = "抱歉，暂时无法回答您的问题。请尝试重新描述您的问题。"
FALLBACK_SINGLE = "抱歉，系统暂时无法处理您的问题。"
FALLBACK_SECTION = "抱歉，该部分暂时无法生成。"
# 出过错（expert_error）的段与"本来就是空回复"必须不同文案：
# 医疗问答里"这段是系统故障"和"这段没有内容"是两回事（复核 P2-13）
FALLBACK_SECTION_ERROR = "抱歉，该部分生成失败，请稍后重试。"


def _display_name(agent) -> str:
    """专家对用户可见的显示名。

    RAG 专家的 name 本身就是显示名（构造时传入人设名）；order/mall 的 name 是路由键，
    显示名另存 display_name —— 不加这层区分，分段回答里会直接出现「【mall】」这种内部键。
    """
    return getattr(agent, "display_name", None) or agent.name


def non_blank_responses(responses: List[Dict]) -> List[Dict]:
    """过滤掉"没发言"的专家段（response 为空白）。

    专家返回空串是**有意设计**：商城专家遇到订单/消费类问句时没有该说的话，
    返回空串表示"这一路不参与回答"。输出层必须把这种段连同标题一起去掉，
    否则用户会看到一个空的「【商城与资产】」。
    """
    return [r for r in responses if str(r.get("response") or "").strip()]


class ExpertTask(TypedDict, total=False):
    """Send 派发给单个专家副本的任务载荷（直接作为 expert 节点输入）"""
    agent: str                  # 已校验的合法专家键
    query: str                  # 子问题（路由未给则回退 user_query）
    search_query: Optional[str]  # 检索改写词（路由器产出）
    order: int                  # 路由顺序号，输出必须按此排序
    history_text: str           # format_history 产物（避免专家节点重复格式化）
    user_id: str                # 用户ID（订单查询等需要）


# responses 通道的重置哨兵。
# 必须可被 checkpointer 序列化：coordinator 挂 InMemorySaver 时，langgraph 结束会把
# 各任务 pending writes 落盘（msgpack），自定义对象会抛 "TypeError: not msgpack serializable"。
# 该通道的合法更新值恒为列表（专家节点写 list），因此字符串哨兵不会与任何合法值混淆；
# \x00 前缀保证与正常文本/内容绝不相等。
_RESPONSES_RESET = "\x00__responses_reset__\x00"


def _merge_responses(left, right):
    """responses 通道的合并器：普通拼接，收到重置哨兵则清空。

    由 GraphState 的 Annotated 直接引用（必须传函数对象，见下方注释）。
    """
    if right == _RESPONSES_RESET:
        return []
    return (left or []) + (right or [])


class GraphState(TypedDict):
    """图在节点之间传递的状态。

    字段的读写约定：
      · user_query / history_text / user_id 由入口传入，全程只读；
      · routes   由 route 节点单写（无 reducer，覆盖语义）；
      · responses 由各并行专家副本同时写，故必须配 reducer 合并（见下方 Annotated）；
      · final_answer 由 synthesize 节点单写。
    """
    user_query: str
    history_text: str
    user_id: str
    routes: List[Dict]                              # route 节点单写，无 reducer
    # 并行 fan-out 的收敛关键：多个专家副本同时返回时必须用 add 合并，否则互相覆盖只剩一个。
    # 挂 checkpointer 后状态跨轮保留，普通 add 会让上一轮的 responses 残留到下一轮，
    # 因此用带重置哨兵的合并器：route 节点每轮先置 _RESPONSES_RESET 清空。
    # 注意必须直接引用函数对象而非字符串 "_merge_responses"：langgraph 1.0.x 挂
    # checkpointer 后无法解析字符串名 reducer，并行写该通道会抛 InvalidUpdateError
    # （"Can receive only one value per step"）。
    responses: Annotated[List[Dict], _merge_responses]
    final_answer: str


def _safe_stream_writer():
    """get_stream_writer() 在非 runnable 上下文直接抛 RuntimeError，一律经此包裹"""
    try:
        return get_stream_writer()
    except RuntimeError:
        return None


def _is_streaming() -> bool:
    """是否处于流式执行上下文。

    注意不能用 get_stream_writer() 是否为 None 判断：langgraph 在 invoke() 时
    writer 同样存在（只是发射被丢弃），无法区分。改由 facade 经 configurable
    显式传入 streaming 标志。
    """
    try:
        cfg = get_config()
        return bool(cfg.get("configurable", {}).get("streaming", False))
    except RuntimeError:
        return False


def build_chronic_graph(agents: Dict[str, Any],
                        router: Any,
                        synthesize_fn: Optional[Callable[[str, List[Dict]], str]] = None,
                        checkpointer: Any = None):
    """构建慢性病多智能体图。

    agents: {"disease": DiseaseAgent, ...}（由 coordinator 注入，含 vector_store）
    router: RouterAgent 实例（内部含 JSON 解析失败→disease 兜底）
    synthesize_fn: 非流式多专家整合函数（coordinator._synthesize_answer，含拼接兜底）；
                   未注入时退化为纯拼接
    checkpointer: LangGraph 检查点器（如 InMemorySaver）。配合 thread_id=会话ID，
                  同一会话的图状态跨请求保留（进程内）。会话正文与摘要的持久化
                  由 core/chat_session.py（PostgreSQL）负责，两者互补。
    """

    def join_sections(responses: List[Dict]) -> str:
        """按【专家名】分段拼接多路回答（流式路径与整合兜底共用同一格式）。

        接口数据型专家（data_source 类属性）的段稳定排最前，其余保持原序：
        LLM 路由常把 order/mall 排到医学专家后面，用户问价格却要翻屏（复核 P2-8）。

        空回答的段直接丢弃（{@link non_blank_responses}）：专家可以"不发言"——
        例如商城专家遇到订单/消费类问句会返回空串（它没有该说的话），
        这时若还输出一个光秃秃的「【商城与资产】」标题，比答错更让人困惑。
        """
        sections = non_blank_responses(responses)
        ordered = sorted(sections, key=lambda r: not getattr(
            agents.get(r.get("key")), "data_source", False))
        return "\n\n".join(f"【{r['agent']}】\n{r['response']}" for r in ordered)

    def route_node(state: GraphState) -> dict:
        """路由：调 RouterAgent（解析失败内部兜底 disease），过滤非法键、截断最多 3 路"""
        t0 = time.time()
        raw = router.route(state["user_query"], history_text=state["history_text"])
        # 校验合法智能体键，非法的静默丢弃；限制最多 3 路，避免延迟/费用倍增
        tasks = [item for item in raw
                 if isinstance(item, dict) and item.get("agent") in agents][:3]
        logger.info(f"路由结果: {raw}（耗时 {time.time() - t0:.2f}s）")
        if _is_streaming():
            writer = _safe_stream_writer()
            if writer is not None:
                # 显式上报路由事件：流式闸门需要知道专家数量、顺序与显示名（custom 模式下无事件则闸门无从得知）
                writer({"type": "routes",
                        "routes": [{"order": i, "agent": _display_name(agents[t["agent"]])}
                                   for i, t in enumerate(tasks)]})
        return {"routes": tasks, "responses": _RESPONSES_RESET}

    def dispatch(state: GraphState):
        """条件边：有任务 → Send 列表（动态 fan-out 并行）；无任务 → END"""
        if not state["routes"]:
            return "empty"
        return [Send("expert", ExpertTask(
            agent=t["agent"],
            query=t.get("query") or state["user_query"],
            search_query=t.get("search_query"),
            order=i,
            history_text=state["history_text"],
            user_id=state.get("user_id", ""),
        )) for i, t in enumerate(state["routes"])]

    def expert_node(task: ExpertTask) -> dict:
        """专家节点：Send payload 直接作为输入（非 GraphState）。
        流式上下文逐 token 上报 custom 事件；invoke 上下文直接返回全文"""
        agent = agents[task["agent"]]
        streaming = _is_streaming()
        writer = _safe_stream_writer() if streaming else None
        if streaming and writer is None:
            # 纵深防御（复核 P1-1）：流式上下文里拿不到 writer（异步上下文下
            # get_stream_writer() 抛 RuntimeError 被 _safe_stream_writer 吞成 None）时，
            # 必须整体退回非流式分支。否则下面逐 token 的 writer(...) 会在第一个 token
            # 就抛 TypeError，被 except 吞掉后整段静默降级成兜底文案 ——
            # 而专家其实已经检索到内容、正在往 buf 里写。
            streaming = False
        t0 = time.time()
        text = ""
        try:
            # 订单/商城等按用户维度取数的专家需要 user_id：由各 Agent 类上的
            # user_scoped 类属性声明（清单见 coordinator.USER_SCOPED_AGENT_KEYS）。
            # 路由专家（BaseAgent 系）的 generate_text 没有 user_id 形参，多传会
            # TypeError，所以不能给所有专家都传。漏标的后果不是报错而是静默答错：
            # 拿不到 user_id 会立刻回"需要先登录"，整合模型再当成"资料里没提到价格"。
            extra_kwargs = ({"user_id": task.get("user_id", "")}
                            if getattr(agent, "user_scoped", False) else {})
            if not streaming:
                text = agent.generate_text(
                    task["query"], search_query=task["search_query"],
                    history_text=task["history_text"], **extra_kwargs) or ""
            else:
                buf: List[str] = []
                for tok in agent.generate_response(
                        task["query"], stream=True,
                        search_query=task["search_query"],
                        history_text=task["history_text"], **extra_kwargs):
                    buf.append(tok)
                    # key 装路由键，与 responses 条目命名对齐（"agent" 字段装显示名）。
                    # 之前这里叫 "agent" 却装路由键，与 routes 事件同字段两种语义（复核 P2-12）。
                    writer({"type": "expert_delta", "order": task["order"],
                            "key": agent.name, "token": tok})
                text = "".join(buf)
        except Exception as e:
            # 流式生成器中途出错会静默结束（generate_response 内部已 yield 兜底文案），
            # 非流式异常走到这里统一上报错误事件，兜底文案由闸门按段补齐
            logger.error(f"{agent.name} 执行失败: {e}")
            if writer is not None:
                writer({"type": "expert_error", "order": task["order"], "agent": agent.name})
            text = text or FALLBACK_SECTION
        if writer is not None:
            writer({"type": "expert_done", "order": task["order"], "agent": agent.name})
        logger.info(f"{_display_name(agent)} 已响应（耗时 {time.time() - t0:.2f}s）")
        # key 保留路由键：整合阶段据此判断"这路回答是不是接口数据型专家"（见 coordinator）
        return {"responses": [{"agent": _display_name(agent), "key": task["agent"],
                              "order": task["order"], "response": text}]}

    def synthesize_node(state: GraphState) -> dict:
        """整合：按 order 排序收齐 responses。
        非流式多路走注入的 synthesize_fn（含 LLM 整合 + 拼接兜底）；
        流式上下文短路为纯拼接（与既有 query_stream 语义一致，不多调一次 LLM）。

        先丢掉"没发言"的段（空串）：丢完只剩一路就直接返回原文，全空才回兜底话术——
        否则「订单专家答了 + 商城专家沉默」会被当成"两路回答"走一次整合，或者更糟：
        沉默那路兜底文案 NOT 触发，用户看到空标题。"""
        responses = sorted(non_blank_responses(state["responses"]), key=lambda r: r["order"])
        if not responses:
            return {"final_answer": DEFAULT_ANSWER}
        if len(responses) == 1:
            return {"final_answer": responses[0]["response"]}
        if _is_streaming():
            return {"final_answer": join_sections(responses)}
        return {"final_answer": synthesize_fn(state["user_query"], responses) if synthesize_fn
                else join_sections(responses)}

    builder = StateGraph(GraphState)
    builder.add_node("route", route_node)
    builder.add_node("expert", expert_node)
    builder.add_node("synthesize", synthesize_node)
    builder.add_edge(START, "route")
    builder.add_conditional_edges("route", dispatch, {"expert": "expert", "empty": END})
    # expert 的所有并行副本都完成后走一次 synthesize
    builder.add_edge("expert", "synthesize")
    builder.add_edge("synthesize", END)
    return builder.compile(checkpointer=checkpointer)


def _stream_ordered(events: Iterable[Dict]) -> Generator[str, None, None]:
    """顺序闸门：custom 模式下并行专家的 token 事件任意交错，本生成器保证输出严格有序。

    - 队首专家（order == next_emit）的 token 实时透传（首段零延迟）
    - 其余专家 token 按序缓冲，前序专家 expert_done 后立即追平放行
    - 多专家时补 【专家名】\\n 前缀与 \\n\\n 分隔；单专家无前缀（复刻既有输出语义）
    - 空段/异常段补兜底文案；流结束时冲刷所有未输出段
    """
    routes: List[tuple] = []          # [(order, agent_name), ...]
    buffers: Dict[int, List[str]] = {}  # order -> 已收到的 token
    emitted: Dict[int, int] = {}      # order -> 已实际输出的 token 数
    done: set = set()                 # 已完成产出（含出错）的 order
    errored: set = set()
    next_emit = 0
    produced = False                  # 是否已向用户输出过任何内容（token 或兜底文案都算）——
                                      # 收尾判据用它而不是 emitted，见函数末尾的注释（复核 R2-6）

    def emit_section(order: int, live: bool):
        """输出 order 段。live=True 时只输出新增 token（实时透传）；
        live=False（收尾）时冲刷剩余 token、补兜底文案与前缀/分隔符，且只调用一次。

        空白内容（专家"不发言"）分三种情形处理 —— 判空看**内容**而不是 token 个数：
        专家不发言时会吐出空串或一个换行（MallAgent 流式实现是 split("\\n") 后逐个 yield，
        空文本会得到 "\\n"），用 len(tokens) 判会把它当"有内容"，留下一个只有标题的空段。
          · live 阶段：先什么都不发 —— 标题必须等第一个非空 token 一起出去；
          · 收尾 + 出过错：补"生成失败"文案（与"有意不发言"区分，复核 P2-13）；
          · 收尾 + 没出错：整段丢弃，连标题都不出（这一路有意不发言）。
        """
        nonlocal emitted, produced
        name = next((n for o, n in routes if o == order), None)
        multi = len(routes) > 1
        tokens = buffers.get(order, [])
        sent = emitted.get(order, 0)
        if not "".join(tokens).strip():
            if live:
                return []
            if order in errored:
                # 出过错的段要保留标题 + "生成失败"文案（与"有意不发言"区分，复核 P2-13）
                failed = []
                if multi and name:
                    failed.append(f"【{name}】\n")
                failed.append(FALLBACK_SECTION_ERROR)
                if multi:
                    failed.append("\n\n")
                produced = True     # 失败文案也是"给用户的话"（复核 R2-6：否则单专家出错时会在它后面追加默认话术）
                return failed
            return []
        out = []
        if multi and sent == 0 and name:
            out.append(f"【{name}】\n")
        new_tokens = tokens[sent:]
        if new_tokens:
            out.extend(new_tokens)
            emitted[order] = sent + len(new_tokens)
        if multi and not live:
            out.append("\n\n")
        if out:
            produced = True
        return out

    def catch_up():
        """前序专家完成后追平放行所有连续完成的段"""
        nonlocal next_emit
        while next_emit in done:
            for piece in emit_section(next_emit, live=False):
                yield piece
            next_emit += 1

    for ev in events:
        etype = ev.get("type")
        if etype == "routes":
            routes = [(r["order"], r["agent"]) for r in ev["routes"]]
        elif etype == "expert_delta":
            order = ev["order"]
            buffers.setdefault(order, []).append(ev["token"])
            yield from catch_up()
            if order == next_emit:
                for piece in emit_section(order, live=True):
                    yield piece
        elif etype in ("expert_done", "expert_error"):
            if etype == "expert_error":
                errored.add(ev["order"])
            done.add(ev["order"])
            yield from catch_up()

    # 流结束安全网：冲刷所有未输出段（路由事件缺失/未完成段）
    while routes and next_emit < len(routes):
        for piece in emit_section(next_emit, live=False):
            yield piece
        next_emit += 1
    # 完全没有向用户输出过任何内容时才补默认话术（判据是 produced，不是 emitted —— 复核 R2-6）：
    #  · routes 事件由 route_node 发出，受 `_is_streaming()` 与 `writer is not None` 双重保护 ——
    #    `_safe_stream_writer()` 可能把 RuntimeError 吞成 None 使事件丢失，此时专家内容其实已产出，
    #    若无条件补一句「抱歉，暂时无法回答您的问题」，会在正确回答后追加误导（P1-1 的教训）；
    #  · 所有段都被判为"有意不发言"（全空）时，必须给用户一句话，不能吐空流；
    #  · **出错段的"生成失败"文案也算产出**（R2-6）：emitted 只统计真 token，用它判的话，
    #    "单专家 + 出错"会在 FALLBACK_SECTION_ERROR 后面再追加一句 DEFAULT_ANSWER ——
    #    两句话术语义冲突（"这段失败了" vs "整个回答作废"），医疗问答里会误导用户。
    #    曾因 t7 只覆盖两路（正常路填了 emitted）、t13 只覆盖"沉默不覆盖出错"而双双漏过。
    if not produced:
        yield DEFAULT_ANSWER
