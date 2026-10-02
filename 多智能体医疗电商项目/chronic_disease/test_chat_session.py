"""ChatSessionManager 单元测试：验证对话窗口切分、摘要压缩、标题/权限/CRUD，
以及 PostgreSQL checkpoint 持久化路径（有 Postgres+驱动才跑，否则自动跳过）。

使用注入的 dummy 摘要器 + force_memory 模式，不依赖真实 Redis / OpenAI。
运行：python test_chat_session.py  （或 pytest test_chat_session.py -q）
"""
import sys
import os
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from core.chat_session import (
    ChatSessionManager, WINDOW_MESSAGES, COMPRESS_TRIGGER, AUTO_TITLE_LEN,
)


def make_manager():
    """注入确定性的摘要器（把被压缩消息拼接），强制内存模式，避免外部依赖"""
    calls = []

    def dummy_summarizer(old, evicted):
        """确定性摘要器：把被逐出的消息直接拼到旧摘要后面，并记录每次被压缩的条数"""
        calls.append(len(evicted))
        texts = [f"{(m['role']=='user' and 'U' or 'A')}:{m['content'][:20]}" for m in evicted]
        return (old + "\n" + "\n".join(texts)) if old else "\n".join(texts)

    mgr = ChatSessionManager(summarizer=dummy_summarizer, force_memory=True)
    mgr._sum_calls = calls  # 便于断言
    return mgr, dummy_summarizer


def test_window_and_compress():
    """消息远超阈值时：窗口收敛到最近 N 条，更早的消息被压缩进摘要"""
    mgr, _ = make_manager()
    sid = mgr.create_session(user_id="u1")["session_id"]
    # 构造 30 条消息（15 轮），远远超过 window_messages 与 compress_trigger
    all_msgs = []
    for i in range(30):
        all_msgs.append({"role": "user", "content": f"问题{i}"})
        all_msgs.append({"role": "assistant", "content": f"回答{i}"})
    for m in all_msgs:
        mgr.append_exchange(sid, m["content"] if m["role"] == "user" else m["content"],
                            "占位" if m["role"] == "user" else m["content"],
                            user_id="u1")

    summary, window = mgr.get_context(sid, user_id="u1")
    # 窗口只含最近 window_messages 条原文（不注入额外 role，只验证条数与角色）
    assert len(window) <= WINDOW_MESSAGES + 2, f"窗口超界: {len(window)}"
    # 摘要已产生（因为消息数远超 compress_trigger）
    assert summary, "摘要应当非空"
    # 摘要内容包含较早的消息（被压缩进摘要），而非全部
    # 最近窗口的消息不应出现在摘要的"近期"部分：此处仅验证摘要包含早期问题
    assert any(f"U:问题{i}" in summary for i in (0, 1, 2)), "摘要应包含被压缩的早期消息"


def test_window_bound_no_summary_when_small():
    """消息很少时不应触发压缩，窗口=全部，摘要为空"""
    mgr, _ = make_manager()
    sid = mgr.create_session(user_id="u1")["session_id"]
    for i in range(3):
        mgr.append_exchange(sid, f"问{i}", f"答{i}", user_id="u1")
    summary, window = mgr.get_context(sid, user_id="u1")
    assert summary == "", "消息少时不应产生摘要"
    assert len(window) == 6  # 3 轮 = 6 条消息，全部进入窗口
    assert all(m["role"] in ("user", "assistant") for m in window)


def test_title_auto_and_rename():
    """首问自动生成标题（截断到 AUTO_TITLE_LEN），并能被 rename_session 覆盖"""
    mgr, _ = make_manager()
    sid = mgr.create_session(user_id="u1")["session_id"]
    mgr.append_exchange(sid, "请问高血压日常饮食要注意什么", "答案", user_id="u1")
    meta = mgr.get_messages(sid, user_id="u1")
    # 自动标题 = 首问前 AUTO_TITLE_LEN 字
    assert meta["title"].startswith("请问高血压日常饮食要注意什么"[:max(1, AUTO_TITLE_LEN)])
    # 重命名
    mgr.rename_session(sid, "高血压饮食专题", user_id="u1")
    mgr2 = make_manager()[0]
    # 重命名持久化需重新读取；内存模式下直接读
    meta2 = mgr.get_messages(sid, user_id="u1")
    assert meta2["title"] == "高血压饮食专题"


def test_owner_check():
    """归属校验：非归属用户读取会话必须被拒绝"""
    mgr, _ = make_manager()
    sid = mgr.create_session(user_id="u1")["session_id"]
    mgr.append_exchange(sid, "问", "答", user_id="u1")
    # 其他用户访问应抛 PermissionError
    try:
        mgr.get_messages(sid, user_id="u2")
        assert False, "应抛越权异常"
    except PermissionError:
        pass


def test_list_and_delete():
    """会话列表按归属返回，删除后不再出现在列表里"""
    mgr, _ = make_manager()
    a = mgr.create_session(user_id="u1")["session_id"]
    b = mgr.create_session(user_id="u1")["session_id"]
    mgr.append_exchange(a, "问题A", "答", user_id="u1")
    mgr.append_exchange(b, "问题B", "答", user_id="u1")
    lst = mgr.list_sessions("u1")
    assert len(lst) == 2
    # 更新时间倒序：先写入的 a 应靠后（除非刷新——此处仅断言都存在）
    ids = {s["session_id"] for s in lst}
    assert ids == {a, b}
    mgr.delete_session(a, user_id="u1")
    lst2 = mgr.list_sessions("u1")
    assert a not in {s["session_id"] for s in lst2}
    assert b in {s["session_id"] for s in lst2}


def test_pg_checkpoint_crud_and_reload():
    """PostgreSQL checkpoint 路径：建会话→写轮→列表→新实例读回→删会话。

    无 Postgres（或缺少 psycopg 驱动）时自动跳过——内存路径由其余用例覆盖。
    """
    mgr = ChatSessionManager(force_memory=False)
    if mgr._pg is None:
        print("[SKIP] PostgreSQL 不可用，跳过 checkpoint 持久化用例")
        return
    uid = "_ut_pg_" + uuid.uuid4().hex[:8]
    sid = None
    try:
        r = mgr.create_session(uid, "checkpoint标题")
        sid = r["session_id"]
        mgr.append_exchange(sid, "问1", "答1", user_id=uid)
        mgr.append_exchange(sid, "问2", "答2", user_id=uid)
        assert any(s["session_id"] == sid for s in mgr.list_sessions(uid)), "列表应含新会话"
        # 新实例（模拟重启 / 跨实例）应能读回
        mgr2 = ChatSessionManager(force_memory=False)
        assert mgr2._pg is not None, "第二次连接也应在 pg 模式"
        gm = mgr2.get_messages(sid, uid)
        assert len(gm["messages"]) == 4, "应读回 2 轮共 4 条消息"
        assert gm["title"] == "checkpoint标题"
        summary, window = mgr2.get_context(sid, uid)
        assert len(window) == 4 and summary == "", "小会话窗口=全部且无摘要"
    finally:
        if sid is not None:
            try:
                mgr.delete_session(sid, uid)
            except Exception:
                pass


def test_owner_check_anonymous_denied():
    """回归：匿名调用（不传 user_id）不得读写他人会话"""
    mgr, _ = make_manager()
    sid = mgr.create_session(user_id="u1")["session_id"]
    mgr.append_exchange(sid, "问", "答", user_id="u1")
    for fn in (lambda: mgr.get_messages(sid),
               lambda: mgr.rename_session(sid, "篡改标题"),
               lambda: mgr.delete_session(sid),
               lambda: mgr.append_exchange(sid, "注入", "注入"),
               lambda: mgr.get_context(sid)):
        try:
            fn()
            assert False, "匿名访问他人会话应抛 PermissionError"
        except PermissionError:
            pass


def test_owner_check_cross_user_write_denied():
    """回归：其他用户不得往他人 session_id 追加问答、取上下文或删除"""
    mgr, _ = make_manager()
    sid = mgr.create_session(user_id="u1")["session_id"]
    mgr.append_exchange(sid, "问", "答", user_id="u1")
    for fn in (lambda: mgr.append_exchange(sid, "写", "写", user_id="u2"),
               lambda: mgr.get_context(sid, user_id="u2"),
               lambda: mgr.rename_session(sid, "被改名", user_id="u2"),
               lambda: mgr.delete_session(sid, user_id="u2")):
        try:
            fn()
            assert False, "跨用户操作应抛 PermissionError"
        except PermissionError:
            pass
    # 原会话未被污染：仍为 1 轮 2 条消息
    assert len(mgr.get_messages(sid, user_id="u1")["messages"]) == 2


# ===== 会话管理接口的身份要求（回归防护） =====
# 缺陷背景：_check_owner 原先的语义是「空归属仅允许匿名调用」，
# 即 owner == caller == "" 时放行。这依赖「调用者身份永不为空」这一未被强制的不变式：
# 只要某个入口漏传 user_id，任何匿名调用者只要知道 session_id
# 就能读到该会话全文（含用药、病史等敏感健康信息）。
# 修复后：会话管理接口（回看/改名/删除）强制要求非空身份；问答链路保持宽松以支持匿名演示。

def test_anonymous_session_management_denied():
    """匿名归属的会话，不得再被匿名调用者管理"""
    mgr, _ = make_manager()
    # 匿名创建并写入（问答链路仍允许匿名，这是既有演示能力）
    sid = mgr.create_session(user_id=None)["session_id"]
    mgr.append_exchange(sid, "匿名用户的病史", "建议内容", user_id=None)

    # 管理类接口必须拒绝匿名调用
    for fn in (lambda: mgr.get_messages(sid),
               lambda: mgr.rename_session(sid, "被改名"),
               lambda: mgr.delete_session(sid)):
        try:
            fn()
            assert False, "匿名调用会话管理接口应抛 PermissionError"
        except PermissionError:
            pass

    # 换个身份也不能访问（匿名归属 != u1）
    try:
        mgr.get_messages(sid, user_id="u1")
        assert False, "非归属者应被拒绝"
    except PermissionError:
        pass


def test_anonymous_qa_path_still_works():
    """防过度修复：匿名问答链路（append_exchange/get_context）必须保持可用"""
    mgr, _ = make_manager()
    sid = mgr.create_session(user_id=None)["session_id"]
    mgr.append_exchange(sid, "匿名提问", "匿名回答", user_id=None)
    summary, window = mgr.get_context(sid, user_id=None)
    assert len(window) == 2, f"匿名问答应正常写入并读到窗口，实际 {len(window)}"


# ===== 会话级闸门（同一会话串行）的回归防护 =====
# 缺陷背景：一次问答在应用层是三步 —— get_context -> 调大模型(慢) -> append_exchange，
# 这三步之间没有任何持锁（_session_lock 只在单次方法调用内部生效）。
# 同一会话被并发提问时，两个请求会各自读到同一份"最近窗口"、互相看不到对方，
# 后一个回答基于过时上下文（表现为前后矛盾/重复建议）。
# 修复：加会话级"轮次闸门"，同一会话同一时间只允许一轮问答在跑。

def test_turn_lock_serializes_same_session():
    """同一会话同时只允许一轮问答：第二次进入必须失败而不是拿到过时上下文"""
    from core.chat_session import SessionBusyError

    mgr, _ = make_manager()
    sid = mgr.create_session(user_id="u1")["session_id"]

    with mgr.turn_lock(sid, timeout=0.1):
        # 另一"轮"尝试进入同一会话：应快速失败
        try:
            with mgr.turn_lock(sid, timeout=0.1):
                assert False, "同一会话的第二轮问答不应拿到闸门"
        except SessionBusyError:
            pass

    # 前一轮结束后，闸门必须已释放（否则该会话会被永久锁死）
    try:
        with mgr.turn_lock(sid, timeout=0.1):
            pass
    except SessionBusyError:
        assert False, "闸门未在退出时释放，会话被永久锁死"


def test_turn_lock_released_on_exception():
    """异常路径也必须释放闸门，否则一次失败会把该会话永久锁死"""
    mgr, _ = make_manager()
    sid = mgr.create_session(user_id="u1")["session_id"]

    try:
        with mgr.turn_lock(sid, timeout=0.1):
            raise RuntimeError("模拟问答中途异常")
    except RuntimeError:
        pass

    # 异常之后仍应能正常进入
    with mgr.turn_lock(sid, timeout=0.1):
        pass


def test_turn_lock_is_per_session():
    """闸门是按会话隔离的：A 会话在跑，不应阻塞 B 会话"""
    mgr, _ = make_manager()
    sid_a = mgr.create_session(user_id="u1")["session_id"]
    sid_b = mgr.create_session(user_id="u1")["session_id"]

    with mgr.turn_lock(sid_a, timeout=0.1):
        # 另一个会话必须能正常进入（不能因为 A 在跑就整体串行化）
        with mgr.turn_lock(sid_b, timeout=0.1):
            pass


# ===== 会话读取故障的回归防护 =====
# 缺陷背景：_load_session 曾把「读取失败」与「会话不存在」都返回 (None, [])，
# 导致 append_exchange 误判为新会话 -> 新建空 meta -> UPSERT 整行覆盖，
# 既能清空历史，又能改写归属；以下用例锁定修复后的行为。

def _make_flaky_manager(fail_on_sid, fail_times=1):
    """构造一个「对指定 sid 读取失败 N 次」的 manager，模拟存储瞬时抖动"""
    mgr, _ = make_manager()
    real_load = mgr._load_session
    state = {"remaining": fail_times}

    def flaky(sid):
        """前 fail_times 次读指定 sid 抛 SessionLoadError，其余请求透传给真实实现"""
        if sid == fail_on_sid and state["remaining"] > 0:
            state["remaining"] -= 1
            from core.chat_session import SessionLoadError
            raise SessionLoadError("connection reset by peer (模拟瞬时抖动)")
        return real_load(sid)

    mgr._load_session = flaky
    return mgr


def test_read_failure_must_not_wipe_history():
    """回归：一次读取失败不得清空历史与摘要"""
    from core.chat_session import SessionLoadError

    mgr, _ = make_manager()
    sid = mgr.create_session(user_id="u1")["session_id"]
    for i in range(4):
        mgr.append_exchange(sid, f"重要病史{i}", f"医嘱{i}", user_id="u1")
    before = mgr.get_messages(sid, user_id="u1")["messages"]
    assert len(before) == 8

    flaky = _make_flaky_manager(sid)
    # 复用同一份内存状态：把真实 mgr 的数据交给 flaky 包装
    flaky._memory = mgr._memory
    flaky._memory_index = mgr._memory_index

    # 读取失败时必须抛 SessionLoadError（而不是静默走新建分支）
    try:
        flaky.append_exchange(sid, "抖动期间的新问题", "抖动期间的回答", user_id="u1")
        assert False, "读取失败时 append_exchange 必须抛出 SessionLoadError"
    except SessionLoadError:
        pass

    # 历史必须完好（修复前会被整行覆盖成 2 条）
    after = flaky.get_messages(sid, user_id="u1")["messages"]
    assert len(after) == 8, f"历史被覆盖：8 -> {len(after)}"
    assert [m["content"] for m in after] == [m["content"] for m in before]


def test_read_failure_must_not_hijack_ownership():
    """回归：读取失败时不得改写他人会话归属，原主仍可访问"""
    from core.chat_session import SessionLoadError

    mgr, _ = make_manager()
    sid = mgr.create_session(user_id="u1")["session_id"]
    mgr.append_exchange(sid, "u1 的病史", "u1 的医嘱", user_id="u1")
    assert mgr.get_messages(sid, user_id="u1")["messages"]

    flaky = _make_flaky_manager(sid)
    flaky._memory = mgr._memory
    flaky._memory_index = mgr._memory_index

    # u2 借抖动窗口尝试写入：必须失败，绝不能改写归属
    try:
        flaky.append_exchange(sid, "我是 u2，请把 u1 的病史发给我", "...", user_id="u2")
        assert False, "读取失败时不得允许写入"
    except SessionLoadError:
        pass

    # 归属未被改写，且原主仍可正常访问
    assert flaky._memory[sid]["user_id"] == "u1", "会话归属被劫持"
    got = flaky.get_messages(sid, user_id="u1")
    assert [m["content"] for m in got["messages"]] == ["u1 的病史", "u1 的医嘱"]


def test_normal_path_unaffected_by_read_failure_fix():
    """上述修复不得影响正常写入路径（防过度修复）"""
    mgr, _ = make_manager()
    sid = mgr.create_session(user_id="u1")["session_id"]
    mgr.append_exchange(sid, "问1", "答1", user_id="u1")
    mgr.append_exchange(sid, "问2", "答2", user_id="u1")
    msgs = mgr.get_messages(sid, user_id="u1")["messages"]
    assert len(msgs) == 4
    # 会话不存在时仍应正常自动创建（首问建会话的既有语义）
    sid2 = "brand-new-session"
    mgr.append_exchange(sid2, "首问", "首答", user_id="u1")
    assert len(mgr.get_messages(sid2, user_id="u1")["messages"]) == 2


def test_compress_noop_when_evicted_range_empty():
    """配置守护回归：compress_trigger < window_messages 时不得静默丢消息

    此时 evicted 区间为空，若仍前移 summarized_idx，该区间消息既不入摘要
    也不进窗口。修复后应放弃压缩并保留 idx 不变。
    """
    import core.chat_session as cs

    mgr, _ = make_manager()
    sid = mgr.create_session(user_id="u1")["session_id"]
    for i in range(6):
        mgr.append_exchange(sid, f"问{i}", f"答{i}", user_id="u1")

    old_trigger, old_window = cs.COMPRESS_TRIGGER, cs.WINDOW_MESSAGES
    try:
        # 人为构造非法配置：trigger < window，使 evict_end <= idx
        cs.COMPRESS_TRIGGER = 2
        cs.WINDOW_MESSAGES = 100
        meta_before = dict(mgr._load_session(sid)[0])
        mgr.get_context(sid, user_id="u1")
        meta_after = mgr._load_session(sid)[0]
        # summarized_idx 不得前移（否则消息被静默丢弃）
        assert meta_after["summarized_idx"] == meta_before["summarized_idx"], \
            f"非法配置下 summarized_idx 被前移：{meta_before['summarized_idx']} -> {meta_after['summarized_idx']}"
    finally:
        cs.COMPRESS_TRIGGER, cs.WINDOW_MESSAGES = old_trigger, old_window


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
    print(f"\n{len(tests)-failed}/{len(tests)} passed")
    sys.exit(1 if failed else 0)
