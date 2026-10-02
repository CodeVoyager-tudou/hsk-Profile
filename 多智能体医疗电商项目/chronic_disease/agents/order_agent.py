"""订单查询智能体：调用 Java 后端订单 API，格式化返回订单信息。

与 BaseAgent 不同，本智能体不走 RAG 检索，而是通过 HTTP 调用微服务获取实时数据，
再用 LLM 将结构化数据转化为自然语言回复。

SSRF 防护（服务只应访问配置指定的 shop-service）：
- SHOP_SERVICE_URL 在模块加载时校验协议（仅 http/https）并解析出主机合法 IP 集合；
- 拼入 URL 的动态路径段（order_id/user_id）做白名单字符校验，杜绝路径注入；
- 每次请求前重新解析主机并比对 IP（防 DNS rebinding）；
- 禁止跟随重定向（302 到其他主机即失败）。
"""
import json
import re
from datetime import datetime

from base.logger import logger
from base.config import Config

conf = Config()

# 微服务调用统一走 agents/microservice_client：SSRF 防护（协议/主机白名单、防 DNS rebinding、
# 禁重定向）、内部令牌、超时都在那里维护，避免每个智能体各抄一份安全代码。
# 这里保留 _get_json 这个名字，单测与调用方不用改。
from agents.microservice_client import (  # noqa: E402  (放在常量定义后，保证配置已加载)
    SHOP_SERVICE_URL,
    INTERNAL_TOKEN,
    get_json as _get_json,
)


def _unwrap_page(data: dict) -> dict:
    """把 Java 的 Result{code,message,data} 统一成 {"list": [...], "total": n}。

    Java 侧分页体是 MyBatis-Plus 的 Page：**列表在 data.records、总数在 data.total**。
    这里曾经直接读 data.list，键名对不上导致永远返回空列表 ——
    用户问"我有哪些未支付的订单"会被答成"您目前没有订单记录"。
    兼容 records/list 两种写法，避免契约再漂移。

    失败时带上 error=True：调用方必须能区分"确实没有订单"与"根本查不到"，
    否则内部令牌没配、shop-service 挂了都会被伪装成"您没有订单"。
    """
    if not isinstance(data, dict) or data.get("code") != 200:
        logger.warning(f"订单查询返回非200: {data}")
        return {"list": [], "total": 0, "error": True}
    body = data.get("data")
    if not isinstance(body, dict):
        return {"list": [], "total": 0, "error": True}
    records = body.get("records")
    if records is None:
        records = body.get("list")
    if not isinstance(records, list):
        records = []
    try:
        total = int(body.get("total"))
    except (TypeError, ValueError):
        total = len(records)
    return {"list": records, "total": total}


# 订单状态 → 展示文案（覆盖全部状态，未知状态原样展示）
ORDER_STATUS_TEXT = {
    "PENDING": "⏳ 待支付",
    "PAID": "✅ 已支付",
    "CANCELLED": "❌ 已取消",
}


# 支付窗口（分钟）：与后端 chronic.pay.pending-timeout-minutes 的"展示窗口 30 分钟"对齐。
# 后端真实关单时点是 32 分钟（含 2 分钟宽限），这里只用于提示"还剩多久"，不作为权威判定。
PAY_WINDOW_MINUTES = 30


def _status_text(status: str) -> str:
    """订单状态码转展示文案；未知状态原样展示，避免显示成空"""
    return ORDER_STATUS_TEXT.get(status, f"{status or '未知'}")


def _pay_text(pay_type: str) -> str:
    """支付方式码转展示文案（目前只有现金与积分兑换两种）"""
    return "现金" if pay_type == "CASH" else "积分兑换"


def _url_quote(value: str) -> str:
    """URL 查询参数编码（中文关键词/时间字符串不进裸 URL）"""
    from urllib.parse import quote
    return quote(str(value), safe="")


def detect_time_range(query: str, now=None):
    """把"今天/昨天/本周/上周/本月/上月/最近N天"翻译成 (start, end) ISO 字符串。

    识别不到返回 (None, None)。与后端同一台机器，直接用本地时间，不涉及跨时区。
    """
    import datetime as _dt
    q = query or ""
    now = now or _dt.datetime.now()
    m = re.search(r"(?:最近|近)\s*(\d{1,2})\s*天", q)
    if m:
        days = int(m.group(1))
        start = (now - _dt.timedelta(days=days)).replace(hour=0, minute=0, second=0, microsecond=0)
        return start.isoformat(), now.isoformat()
    if re.search(r"今天|今日", q):
        start = now.replace(hour=0, minute=0, second=0, microsecond=0)
        return start.isoformat(), now.isoformat()
    if re.search(r"昨天|昨日", q):
        d = (now - _dt.timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0)
        return d.isoformat(), (d + _dt.timedelta(days=1) - _dt.timedelta(seconds=1)).isoformat()
    if re.search(r"前天", q):
        d = (now - _dt.timedelta(days=2)).replace(hour=0, minute=0, second=0, microsecond=0)
        return d.isoformat(), (d + _dt.timedelta(days=1) - _dt.timedelta(seconds=1)).isoformat()
    this_monday = (now - _dt.timedelta(days=now.weekday())).replace(hour=0, minute=0, second=0, microsecond=0)
    if re.search(r"本周|这周|这个星期", q):
        return this_monday.isoformat(), now.isoformat()
    if re.search(r"上周|上个星期", q):
        last_monday = this_monday - _dt.timedelta(days=7)
        return last_monday.isoformat(), (this_monday - _dt.timedelta(seconds=1)).isoformat()
    first_this_month = now.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
    if re.search(r"本月|这个月|这月", q):
        return first_this_month.isoformat(), now.isoformat()
    if re.search(r"上月|上个月", q):
        first_last_month = (first_this_month - _dt.timedelta(days=1)).replace(day=1)
        return first_last_month.isoformat(), (first_this_month - _dt.timedelta(seconds=1)).isoformat()
    return None, None


def _describe_period(query: str) -> str:
    """把时间词归一化进表头（"我这个月花了多少" → "本月消费汇总"）；识别不到就是"全部" """
    alias = {"今天": "今天", "今日": "今天", "昨天": "昨天", "昨日": "昨天", "前天": "前天",
             "本周": "本周", "这周": "本周", "上周": "上周",
             "本月": "本月", "这个月": "本月", "这月": "本月", "上月": "上月", "上个月": "上月"}
    for word, label in alias.items():
        if word in (query or ""):
            return label
    m = re.search(r"(?:最近|近)\s*(\d{1,2})\s*天", query or "")
    return f"最近{m.group(1)}天" if m else "全部"


def _extract_order_no(text: str) -> str:
    """从文本里找订单号：32 位字母数字串（Java 侧 IdUtil.fastSimpleUUID 生成）"""
    m = re.search(r"\b([A-Za-z0-9]{32})\b", text or "")
    return m.group(1) if m else ""


# 提取药品名时要剥掉的意图词（否则"我买过阿司匹林吗"整句都会被当成关键词）
_ORDER_STOP_RE = re.compile(
    r"(有没有买过|买过|买过吗|买了|购买|下过|下单|订单|我|的|查|查询|看看|一下|"
    r"哪些|什么|这些|那些|有没有|有吗|是|吗|呢|了|？|\?|。|，|,|、|\s)")

# 这些词是从问句里剥剩下的"业务通用词"，不是药名：当成关键词去搜必然搜不到，
# 还会把"查看订单详情"变成"搜含'详情'的订单"（多此一举）
_KEYWORD_BLACKLIST = {"详情", "状态", "信息", "列表", "记录", "单号", "情况", "内容", "明细", "编号"}


def extract_order_keyword(query: str) -> str:
    """从"我买过阿司匹林吗"里抠出药品名候选（剥掉意图词后取最长片段）"""
    pieces = re.findall(r"[\u4e00-\u9fa5A-Za-z0-9]{2,20}", _ORDER_STOP_RE.sub(" ", query or ""))
    if not pieces:
        return ""
    candidate = max(pieces, key=len)[:20]
    return "" if candidate in _KEYWORD_BLACKLIST else candidate


def _order_marker(order: dict) -> str:
    """待支付订单的"可操作标记"：前端识别到 [order:<id>] 会渲染成「去支付」按钮，点击直达收银台。

    只给待支付订单加标记——已支付/已取消没有可执行动作。
    用轻标记而不是塞一段 URL：回答是纯文本流到前端的，一个前端能识别的约定更可靠；
    即使前端不识别，它也只是一段方括号文字，不会破坏回答可读性。
    """
    if order.get("status") != "PENDING" or order.get("id") is None:
        return ""
    return f" [order:{order['id']}]"


# 状态过滤意图：用户问"我有哪些已退款的"就不能把最近订单整段列出来（答非所问）。
# 顺序敏感：「已退款」要排在「已支付」前面判，否则"退款"问句会被更宽的关键词抢走。
STATUS_INTENTS = [
    (r"已退款|退过款|退款成功|退了款|退款了", "REFUNDED"),
    (r"未支付|待支付|待付款|没付|还没付|未付款", "PENDING"),
    (r"已支付|已付款|付过款|已付款的", "PAID"),
    (r"已取消|取消的|取消过|关单", "CANCELLED"),
]
STATUS_LABELS = {
    "PENDING": "待支付",
    "PAID": "已支付",
    "CANCELLED": "已取消",
    "REFUNDED": "已退款",
}


def detect_status_intent(query: str) -> str:
    """从问句里识别"只看某类订单"；识别不到返回空串（等价于"看最近订单"）"""
    text = query or ""
    for pattern, status in STATUS_INTENTS:
        if re.search(pattern, text):
            return status
    return ""


def _matches_status(order: dict, want: str) -> bool:
    """判断订单是否属于 wanted 状态。

    「已退款」不是独立状态，而是"CANCELLED 且真的退过钱"（refund_amount > 0）——
    超时关单的取消单没有退款金额，两者在界面上必须区分开。
    """
    if want == "REFUNDED":
        refund = order.get("refundAmount")
        try:
            return order.get("status") == "CANCELLED" and float(refund or 0) > 0
        except (TypeError, ValueError):
            return False
    return order.get("status") == want


def _refund_note(order: dict) -> str:
    """已退款订单在列表里也要看得出退款金额与时间，否则和"超时关单"混为一谈"""
    refund = order.get("refundAmount")
    try:
        amount = float(refund or 0)
    except (TypeError, ValueError):
        amount = 0.0
    if amount <= 0:
        return ""
    when = str(order.get("refundTime") or "")[:19]
    return f"（已退款 ¥{amount:g}" + (f" · {when}" if when else "") + "）"


class OrderAgent:
    """订单查询智能体：查询用户订单并以自然语言回复"""

    # 编排特征的单一真相（core/coordinator 据此推导清单，复核 P2-2）：
    # user_scoped —— generate_text 需要 user_id（图节点据此决定是否注入，漏标=静默答错）；
    # data_source —— 回答由模板拼装、数字来自接口，不走 RAG、不参与 LLM 整合。
    user_scoped = True
    data_source = True

    def __init__(self):
        """本智能体不继承 BaseAgent：它不走向量检索，而是通过 HTTP 查订单，故没有 vector_store"""
        # name 是路由键（图内部按它取专家、也是 responses 里的标识）；
        # display_name 才是用户可见的分段标题 —— 两者分开，免得回答里出现"【order】"这种内部键
        self.name = "order"
        self.display_name = "订单查询"
        self.role = "订单查询"

    def query_orders(self, user_id: str, page_num: int = 1, page_size: int = 10,
                     status: str = None, keyword: str = None,
                     start_time: str = None, end_time: str = None) -> dict:
        """调用 shop-service 内部接口查询用户订单列表，返回 {"list": [...], "total": n}。

        状态 / 药品名关键词 / 时间区间都下推到 SQL（接口侧用条件构造器绑定参数）——
        早期做法是"取最近一页再在本地过滤"，那样"我 3 月买过什么"只能查到最近几笔。

        status 可以是 PENDING/PAID/CANCELLED，也可以是查询别名 REFUNDED
        （后端把它翻成"CANCELLED 且 refund_amount > 0"，同样在 SQL 里筛）。
        """
        try:
            page_num = max(1, int(page_num))
            page_size = min(50, max(1, int(page_size)))
            parts = [f"userId={user_id}", f"pageNum={page_num}", f"pageSize={page_size}"]
            if status:
                parts.append(f"status={status}")
            if keyword:
                parts.append("keyword=" + _url_quote(keyword))
            if start_time:
                parts.append("startTime=" + _url_quote(start_time))
            if end_time:
                parts.append("endTime=" + _url_quote(end_time))
            data = _get_json("/internal/order/list", "&".join(parts), user_id=user_id)
            return _unwrap_page(data)
        except ValueError as e:
            logger.error(f"订单查询参数非法: {e}")
            return {"list": [], "total": 0, "error": True}
        except Exception as e:
            logger.error(f"订单查询失败: {e}")
            return {"list": [], "total": 0, "error": True}

    def order_summary(self, user_id: str, status: str = None,
                      start_time: str = None, end_time: str = None) -> dict:
        """订单汇总（笔数/各状态笔数/实付合计/积分消耗），用于"我这个月花了多少"这类问题。

        status 与 query_orders 同义，同样可用查询别名 REFUNDED（"我一共退了多少"）。
        """
        try:
            parts = [f"userId={user_id}"]
            if status:
                parts.append(f"status={status}")
            if start_time:
                parts.append("startTime=" + _url_quote(start_time))
            if end_time:
                parts.append("endTime=" + _url_quote(end_time))
            data = _get_json("/internal/order/summary", "&".join(parts), user_id=user_id)
            if isinstance(data, dict) and data.get("code") == 200 and isinstance(data.get("data"), dict):
                return data["data"]
            logger.warning(f"订单汇总返回非200: {data}")
            return {}
        except Exception as e:
            logger.error(f"订单汇总失败: {e}")
            return {}

    def query_order_detail(self, order_no: str, user_id: str = None) -> dict:
        """按订单号查询订单详情。

        订单号是 32 位十六进制串（Java 侧 IdUtil.fastSimpleUUID()），
        而 /shop/order/{id} 收的是数据库自增主键 —— 拿订单号去打这个路径必然查不到，
        所以这里改走按订单号查的内部接口。
        """
        try:
            data = _get_json(f"/internal/order/no/{order_no}", f"userId={user_id}" if user_id else "",
                             user_id=user_id)
            if isinstance(data, dict) and data.get("code") == 200:
                body = data.get("data")
                return body if isinstance(body, dict) else {}
            logger.warning(f"订单详情返回非200: {data}")
            return {}
        except Exception as e:
            logger.error(f"订单详情查询失败: {e}")
            return {}

    def generate_text(self, user_query: str, user_id: str = None,
                      search_query: str = None, history_text: str = "") -> str:
        """根据用户意图查询订单并生成自然语言回复"""
        if not user_id:
            return "抱歉，查询订单需要先登录。请登录后再试。"

        # 解析用户意图：查看全部订单 or 指定订单。
        # 订单号必须是含数字的标识符：中文语境下 \w 会匹配汉字，
        # "查看订单详情"会被误提取成订单号"详情"，故捕获组要求至少一位数字。
        # 取消请求必须排在"按订单号查详情"之前：用户说"取消订单 <订单号>"时句子里也含订单号，
        # 若先走详情分支就永远到不了取消提案（实测踩到）。
        # 同时排除"已取消/取消的订单"这类查询（问历史，不是要取消）。
        if re.search(r"取消|退掉|不想要", user_query) and not re.search(r"已取消|被取消|取消过|取消的订单|取消记录", user_query):
            return self._answer_cancel(user_id, user_query, history_text)

        order_id_match = re.search(
            r'(?:订单号|订单|order\s*no|order)[：:\s]*([A-Za-z0-9_-]*\d[A-Za-z0-9_-]*)',
            user_query, re.IGNORECASE)

        if order_id_match:
            order_id = order_id_match.group(1)
            order = self.query_order_detail(order_id, user_id=user_id)
            if not order:
                return f"未找到订单号为 {order_id} 的订单，请检查订单号是否正确。"
            return self._format_single_order(order)

        # 先看用户是不是在问某一类订单（"已退款的""未支付的"…）
        # 「已退款」虽然不是表里的状态，但后端认这个查询别名（等价于
        # status='CANCELLED' AND refund_amount > 0），所以整类问句都能下推 SQL ——
        # 早期是"取最近 50 笔回来在内存里筛"，那样更早的退款单永远查不到。
        status_intent = detect_status_intent(user_query)
        real_status = status_intent or None

        # 汇总类问题（"我这个月花了多少""一共几笔"）→ 走聚合接口，而不是列一堆明细
        if re.search(r"花了|消费|花费|合计|总共|一共|多少笔|几笔|多少单|统计", user_query):
            t_start, t_end = detect_time_range(user_query)
            return self._answer_summary(user_id, real_status, t_start, t_end, user_query)

        # 时间范围与药品名关键词都下推给 SQL：命中任一条件时多取一些，保证筛完还有内容
        t_start, t_end = detect_time_range(user_query)
        keyword = extract_order_keyword(user_query)
        broad = bool(status_intent or keyword or t_start)
        result = self.query_orders(user_id, page_num=1,
                                   page_size=50 if broad else 5,
                                   status=real_status,
                                   keyword=keyword or None,
                                   start_time=t_start, end_time=t_end)
        orders = result.get("list", [])
        total = result.get("total", 0)

        # 查不到 ≠ 没有订单：内部令牌没配或 shop-service 异常时必须明说，不能伪装成"您没有订单"
        if result.get("error"):
            return ("订单查询服务暂时不可用，请稍后再试。"
                    "（内部调用需要 shop-service 的 /internal/** 接口与正确的内部令牌）")

        if status_intent:
            # 服务端已按该类筛过（含 REFUNDED 别名），这里再筛一遍是防御性的。
            # 注意旧后端不认识 REFUNDED 别名时会走 status='REFUNDED' 字面量比较，
            # 而表里只有 PENDING/PAID/CANCELLED，查询恒返回 0 行 —— 所以
            # 「已退款 + total==0」不能直接下结论"你没有"：退回一次无状态查询
            # 在内存里兜底筛（只覆盖最近 50 笔），措辞里说明这个边界，见复核 P1-2。
            matched = [o for o in orders if _matches_status(o, status_intent)]
            label = STATUS_LABELS.get(status_intent, "该状态")
            fallback_note = ""
            if not matched and status_intent == "REFUNDED" and not total:
                recent = self.query_orders(user_id, page_num=1, page_size=50)
                if not recent.get("error"):
                    scanned = recent.get("list", [])
                    matched = [o for o in scanned if _matches_status(o, status_intent)]
                    if matched:
                        fallback_note = (f"（后端未识别「已退款」筛选，以下为最近 "
                                         f"{len(scanned)} 笔订单里筛出的结果）\n\n")
            if not matched:
                if status_intent == "REFUNDED":
                    return ("我在最近 50 笔订单里没有看到退款单；若你在这之前退过款，"
                            "可能是后端版本较旧、不支持按退款筛选，请联系管理员升级后重试。")
                tip = (f"提醒：待支付订单超过 {PAY_WINDOW_MINUTES} 分钟未付款会自动取消并退回库存。"
                       if status_intent == "PENDING"
                       else "可以问我「我有哪些待支付 / 已支付 / 已取消 / 已退款的订单」分别查看。")
                return f"你没有「{label}」的订单。\n{tip}"
            # total 由聚合查询给出（该类订单总数），列表本身只带一页；
            # 兜底筛路径下 total 是"全部订单数"而非该类总数，只能按命中数显示
            shown_total = total if not fallback_note else len(matched)
            return fallback_note + self._format_order_list(
                matched, shown_total or len(matched), title=f"{label}的订单")

        if not orders:
            if keyword or t_start:
                return (f"没找到符合条件的订单（{_describe_period(user_query)}"
                        + (f"、含「{keyword}」" if keyword else "")
                        + f"）—— 已在你的订单里查过。")
            return "您目前没有订单记录。您可以在商城浏览商品并下单。"

        text = self._format_order_list(orders, total)
        if re.search(r"物流|快递|发货|签收|什么时候到", user_query):
            # 本演示没有对接快递单号：先说明查不到轨迹，再把订单状态给出去，
            # 避免用户以为"这个助手能查物流"却拿到一段和问题无关的订单列表
            text = "📦 本演示没有对接快递单号，查不到物流轨迹，只能看到订单状态：\n\n" + text
        return text

    def _answer_summary(self, user_id: str, status: str, start: str, end: str, query: str) -> str:
        """消费/笔数汇总（数据来自 /internal/order/summary，聚合在 SQL 侧完成）"""
        data = self.order_summary(user_id, status, start, end)
        if not data:
            return "订单汇总服务暂时不可用，请稍后再试。"
        label = STATUS_LABELS.get(status, "") if status else ""
        lines = [f"📊 {_describe_period(query)}{label}订单汇总：",
                 f"· 订单笔数：{data.get('totalOrders', 0)} 笔",
                 f"· 实付金额合计：¥{data.get('paidAmount', 0)}",
                 f"· 积分消耗合计：{data.get('pointsUsed', 0)} 分"]
        if data.get("refundedAmount"):
            lines.append(f"· 已退款金额合计：¥{data.get('refundedAmount')}（已取消订单里真正退过的那部分）")
        by_status = data.get("byStatus") or {}
        if by_status:
            lines.append("· 状态分布：" + "、".join(
                f"{STATUS_LABELS.get(k, k)} {v} 笔" for k, v in by_status.items()))
        return "\n".join(lines)

    def _answer_cancel(self, user_id: str, query: str, history_text: str) -> str:
        """取消订单：只产出"待确认"的提案（前端渲染成按钮，用户点了才会真的取消）"""
        order_no = _extract_order_no(query) or _extract_order_no(history_text)
        if not order_no:
            pending = self.query_orders(user_id, page_num=1, page_size=10, status="PENDING")
            rows = pending.get("list", []) if not pending.get("error") else []
            if not rows:
                return ("想取消哪一笔？把订单号发给我就行（「我的订单」里能看到）。"
                        "你当前没有待支付订单，若是要退已支付的单，也给一下订单号，我确认后由你点按钮执行。")
            lines = ["想取消哪一笔？把订单号发给我就行（「我的订单」里能看到）。当前待支付的有："]
            for o in rows[:5]:
                lines.append(f"· {o.get('orderNo')}｜{o.get('medicineName')} × {o.get('quantity')}"
                             f"｜¥{o.get('totalAmount')}")
            return "\n".join(lines)

        detail = self.query_order_detail(order_no, user_id=user_id)
        if not detail:
            return f"没找到订单号 {order_no} 的订单，请核对一下订单号。"
        status = detail.get("status")
        order_id = detail.get("id")
        if status not in ("PENDING", "PAID"):
            return f"订单 {order_no} 当前状态是「{_status_text(status)}」，不需要取消。"
        note = ("这笔还没支付，取消只会退回库存与优惠券（秒杀单同时释放名额）。"
                if status == "PENDING"
                else "这笔已支付，取消会按支付方式退回：余额退回账户、现金单走退款台账。")
        return (f"确认要取消这笔订单吗？\n"
                f"· 订单号：{order_no}\n"
                f"· 商品：{detail.get('medicineName')} × {detail.get('quantity')}\n"
                f"· 金额：¥{detail.get('totalAmount')}\n"
                f"{note}\n\n点下面的按钮才会真正取消：\n[cancel:{order_id}]")

    def generate_response(self, user_query: str, stream: bool = False,
                          user_id: str = None, search_query: str = None,
                          history_text: str = ""):
        """流式响应：将订单结果分块输出"""
        text = self.generate_text(user_query, user_id=user_id,
                                  search_query=search_query, history_text=history_text)
        if stream:
            # 按行分块输出，模拟流式
            for line in text.split("\n"):
                yield line + "\n"
        else:
            yield text

    def _pending_suffix(self, order: dict) -> str:
        """待支付订单补一句剩余时间，让用户知道来不来得及付款"""
        if order.get("status") != "PENDING" or not order.get("createTime"):
            return ""
        try:
            created = datetime.strptime(str(order["createTime"])[:19], "%Y-%m-%dT%H:%M:%S")
        except ValueError:
            return ""
        left = PAY_WINDOW_MINUTES - int((datetime.now() - created).total_seconds() // 60)
        return "（支付窗口已过，即将自动取消）" if left <= 0 else f"（约剩 {left} 分钟）"

    def _format_order_list(self, orders: list, total: int, title: str = None) -> str:
        """将订单列表格式化为易读文本：待支付排最前，并提示剩余支付时间。

        title 非空表示这是"某一类订单"的列表（如"已退款的订单"），此时表头与提示都按该类来写。
        """
        # 待支付优先：问"我有哪些订单"时，用户最关心的是"我该付哪一笔"
        ordered = ([o for o in orders if o.get("status") == "PENDING"]
                   + [o for o in orders if o.get("status") != "PENDING"])
        header = (f"📋 {title}（{total} 笔）：\n" if title
                  else f"📋 您最近的订单（共 {total} 笔）：\n")
        lines = [header]
        for i, order in enumerate(ordered, 1):
            status = _status_text(order.get("status"))
            pay_type = _pay_text(order.get("payType"))
            lines.append(
                f"{i}. 订单号：{order.get('orderNo', '-')}\n"
                f"   商品：{order.get('medicineName', '-')} × {order.get('quantity', 0)}\n"
                f"   金额：¥{order.get('totalAmount', 0)}（{pay_type}）\n"
                f"   状态：{status}{_refund_note(order)}{self._pending_suffix(order)}{_order_marker(order)}\n"
                f"   下单时间：{order.get('createTime', '-')[:19] if order.get('createTime') else '-'}"
            )
        if total > len(orders):
            lines.append(f"\n（显示最近 {len(orders)} 笔，共 {total} 笔订单）")
        pending_count = sum(1 for o in orders if o.get("status") == "PENDING")
        if pending_count:
            lines.append(f"\n💡 有 {pending_count} 笔待支付：在「我的订单」点「继续支付」可进收银台付款，"
                         f"超过 {PAY_WINDOW_MINUTES} 分钟未支付会自动取消并退回库存。")
        # 让用户知道"下一步怎么问"：直接给出一个可复制的订单号，比只说"告诉我具体订单号"有用
        sample = next((o.get("orderNo") for o in ordered if o.get("orderNo")), None)
        lines.append("\n💡 想看某笔订单的详情，把订单号发给我即可"
                     + (f"（如 {sample}）。" if sample else "。"))
        return "\n".join(lines)

    def _format_single_order(self, order: dict) -> str:
        """将单个订单格式化为详细文本"""
        status = _status_text(order.get("status"))
        pay_type = _pay_text(order.get("payType"))
        lines = [
            f"📦 订单详情：\n",
            f"订单号：{order.get('orderNo', '-')}",
            f"商品：{order.get('medicineName', '-')}",
            f"数量：{order.get('quantity', 0)}",
            f"单价：¥{order.get('unitPrice', 0)}",
            f"总金额：¥{order.get('totalAmount', 0)}",
            f"支付方式：{pay_type}",
            f"订单状态：{status}{_order_marker(order)}",
        ]
        if order.get("discountAmount") and float(order.get("discountAmount", 0)) > 0:
            lines.append(f"优惠金额：¥{order.get('discountAmount')}")
        if order.get("refundAmount") and float(order.get("refundAmount", 0)) > 0:
            lines.append(f"退款金额：¥{order.get('refundAmount')}（已取消）")
        if order.get("pointsEarned") and order.get("pointsEarned") > 0:
            lines.append(f"获得积分：{order.get('pointsEarned')}")
        if order.get("pointsUsed") and order.get("pointsUsed") > 0:
            lines.append(f"使用积分：{order.get('pointsUsed')}")
        lines.append(f"下单时间：{order.get('createTime', '-')[:19] if order.get('createTime') else '-'}")
        return "\n".join(lines)
