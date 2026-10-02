"""商城与资产智能体：商品价格（现金 / 积分两条渠道）、我的积分与余额、我的优惠券。

<h3>为什么单独一个智能体</h3>
商品价格必须查商品表（`medicine.price` 现金价、`medicine.points_price` 积分价），
不能从用户历史订单里倒推 —— 历史订单只反映"当时买过什么价"。实测出现过：用户问
"阿司匹林肠溶片多少钱？用积分的话需要多少积分？"，模型拿历史订单的 12 元当现价回答，
而商品表里其实写着「现金 ¥12.00 / 积分 900 分」。同一个商品在本系统有两条购买渠道，
回答必须把两条都摆出来。

本智能体不走 RAG、不走大模型：查内部接口 + 模板拼装（与 OrderAgent 同构），
好处是数字不会被打错，且响应快（不花模型时间）。
"""
import re

from base.logger import logger
from agents.microservice_client import POINTS_BASE, get_json

# 问"多少钱/多少积分"时，把这些意图词从问句里剥掉，剩下的就是商品名候选
_PRICE_INTENT_RE = re.compile(
    r"(多少钱|什么价格|价格|售价|卖多少|怎么卖|多少积分|要多少积分|需要多少积分|多少分|"
    r"用积分|积分兑换|有卖|有吗|有没有|有哪些|请问|帮我|查一下|查询|请问一下|呢|吗|？|\?|。|，|,|的)"
)

# 订单/消费类问句：不归商城专家答，命中就保持沉默（返回空串）。
#
# 为什么需要：这类问句里常带"多少钱" —— 「我最近30天花了多少钱」含 mall 的强关键词，
# 于是商城也被叫上，商品搜索把整句话当药名去搜，答出
# "没找到与「我最近30天花了」匹配的在售药品"，既答非所问、又污染订单专家的汇总。
# 词表与 OrderAgent 的汇总意图正则、router_keywords.AGENT_KEYWORDS["order"] 同源。
ORDER_INTENT_RE = re.compile(
    r"订单|下单|退款|取消订单|待支付|待付款|已支付|已付款|已取消|物流|快递|发货|签收|"
    r"花了|消费|花费|合计|多少笔|几笔|多少单|统计"
)


def _clean_keyword(query: str) -> str:
    """从问句里抠出商品名候选：剥掉疑问/意图词后剩下的中文或字母数字片段"""
    text = _PRICE_INTENT_RE.sub(" ", query or "")
    # 只保留中文、字母、数字，其余当分隔符
    pieces = re.findall(r"[\u4e00-\u9fa5A-Za-z0-9]{2,20}", text)
    if not pieces:
        return ""
    # 取最长的一段（"阿司匹林肠溶片" 比 "药" 更可能是药名）
    return max(pieces, key=len)[:20]


class MallAgent:
    """商城信息与用户资产（不继承 BaseAgent：不走向量检索，走内部 HTTP 接口）"""

    # 编排特征的单一真相（core/coordinator 据此推导清单，复核 P2-2）：
    # user_scoped —— generate_text 需要 user_id（图节点据此决定是否注入，漏标=静默答错）；
    # data_source —— 回答由模板拼装、数字来自接口，不走 RAG、不参与 LLM 整合。
    user_scoped = True
    data_source = True

    def __init__(self):
        # name 是路由键（图内部按它取专家、也是 responses 里的标识）；
        # display_name 才是用户可见的分段标题 —— 两者分开，免得回答里出现"【mall】"这种内部键
        self.name = "mall"
        self.display_name = "商城与资产"
        self.role = "商城与资产"

    # ---------- 数据获取 ----------

    def search_medicines(self, keyword: str, limit: int = 5) -> list:
        """按关键词查上架商品；无关键词时返回前若干条（"你们有什么药"）"""
        try:
            data = get_json("/internal/mall/medicine/list",
                            f"keyword={_quote(keyword)}&limit={limit}" if keyword else f"limit={limit}")
        except Exception as e:
            logger.error(f"商品查询失败: {e}")
            return []
        if not isinstance(data, dict) or data.get("code") != 200:
            logger.warning(f"商品查询返回非200: {data}")
            return []
        rows = data.get("data")
        return rows if isinstance(rows, list) else []

    def my_points(self, user_id: str) -> dict:
        """积分账户（余额在 UserPoints.points 字段里）"""
        return self._asset("/internal/asset/points/" + str(user_id), user_id)

    def my_account(self, user_id: str) -> dict:
        """余额账户（现金余额）"""
        return self._asset("/internal/asset/account/" + str(user_id), user_id)

    def my_coupons(self, user_id: str, status: str = None) -> list:
        try:
            query = f"userId={user_id}" + (f"&status={status}" if status else "")
            data = get_json("/internal/mall/coupon/list", query, user_id=user_id)
        except Exception as e:
            logger.error(f"优惠券查询失败: {e}")
            return []
        if isinstance(data, dict) and data.get("code") == 200 and isinstance(data.get("data"), list):
            return data["data"]
        return []

    def _asset(self, path: str, user_id: str) -> dict:
        try:
            data = get_json(path, user_id=user_id, base=POINTS_BASE)
        except Exception as e:
            logger.error(f"资产查询失败: {e}")
            return {}
        if isinstance(data, dict) and data.get("code") == 200 and isinstance(data.get("data"), dict):
            return data["data"]
        return {}

    # ---------- 回答生成 ----------

    def generate_text(self, user_query: str, user_id: str = None, search_query: str = None,
                      history_text: str = "") -> str:
        if not user_id:
            return "抱歉，查询商城信息与账户资产需要先登录。请登录后再试。"

        q = user_query or ""
        # 1) 我的资产：先看是不是在问"我的积分/余额"（这类问句里也会出现"多少积分"，
        #    所以不能只靠排除法，要用"我/我的/账户 + 积分/余额"这种绑定关系来判）
        if re.search(r"(我|我的|账户).{0,8}(积分|余额)|(积分|余额)(有|还剩|剩余)?多少", q):
            return self._answer_assets(user_id)
        # 2) 我的优惠券
        if "优惠券" in q or "券" in q:
            return self._answer_coupons(user_id, q)
        # 3) 订单/消费类问句不归我答：这类问句里常带"多少钱"（如"我最近30天花了多少钱"），
        #    若走到下面的商品搜索，就会把整句话当药名去搜，回答成
        #    "没找到与「我最近30天花了」匹配的在售药品" —— 既答非所问又难看。
        #    返回空串 = "这一路不发言"，由拼接环节丢掉空段（见 graph.non_blank_responses）。
        if ORDER_INTENT_RE.search(q):
            logger.info(f"商城专家跳过订单/消费类问句: {q[:30]}")
            return ""
        # 4) 商品价格与信息（默认）
        return self._answer_medicines(q, user_id)

    def _answer_assets(self, user_id: str) -> str:
        points = self.my_points(user_id)
        account = self.my_account(user_id)
        if not points and not account:
            return "暂时查不到你的账户资产（内部资产接口不可用或尚未开通）。"
        lines = ["💼 你的账户资产："]
        if points:
            lines.append(f"· 积分：{points.get('points', 0)} 分"
                         + (f"（累计获得 {points.get('totalPoints')} 分）" if points.get("totalPoints") is not None else ""))
            if points.get("level"):
                lines.append(f"· 会员等级：{points.get('level')}")
        if account:
            lines.append(f"· 余额：¥{account.get('balance', 0)}")
        lines.append("\n💡 积分可以在商品页选「积分兑换」下单；余额可在收银台用于现金单支付。")
        return "\n".join(lines)

    def _answer_coupons(self, user_id: str, query: str) -> str:
        status = None
        if re.search(r"已用|用掉", query):
            status = "USED"
        elif re.search(r"过期", query):
            status = "EXPIRED"
        elif re.search(r"可用|能用|未用|还有", query):
            status = "UNUSED"
        coupons = self.my_coupons(user_id, status)
        if not coupons:
            return ("你目前没有" + ("符合条件的" if status else "") + "优惠券。"
                    "\n💡 可以在「优惠券」页领取：满减券会在下单时可选，满足门槛才抵扣。")
        lines = [f"🎟️ 你的优惠券（{len(coupons)} 张）："]
        for i, c in enumerate(coupons[:5], 1):
            lines.append(f"{i}. {c.get('couponName', '优惠券')}"
                         f"｜满 ¥{c.get('thresholdAmount', 0)} 减 ¥{c.get('discountAmount', 0)}"
                         f"｜{_coupon_status_text(c.get('status'))}")
        if len(coupons) > 5:
            lines.append(f"（仅列前 5 张，共 {len(coupons)} 张）")
        lines.append("\n💡 下单选「现金支付」时可以用券，多个券不能叠加。")
        return "\n".join(lines)

    def _answer_medicines(self, query: str, user_id: str) -> str:
        keyword = _clean_keyword(query)
        medicines = self.search_medicines(keyword, limit=5 if keyword else 3)
        if not medicines:
            hint = f"没找到与「{keyword}」匹配的在售药品。" if keyword else "商城暂时没有在售药品。"
            return hint + "\n💡 可以说具体药名（如「阿司匹林肠溶片多少钱」），或到「商城」页浏览。"

        lines = []
        for m in medicines:
            lines.append(self._format_medicine(m))
        if len(medicines) > 1:
            lines.insert(0, f"🔎 与「{keyword}」匹配的在售药品 {len(medicines)} 个：\n")
        else:
            lines.insert(0, "")
        lines.append("\n💡 同一商品有两条购买渠道：现金购买（¥，下单后到收银台付款）"
                     "与积分兑换（扣积分、不参与优惠券）。")
        return "\n".join(lines).strip()

    def _format_medicine(self, m: dict) -> str:
        name = m.get("name", "-")
        lines = [f"💊 {name}" + (f"（{m.get('genericName')}）" if m.get("genericName") else "")]
        # 两条渠道并排给出：这是本系统最容易答错的地方（历史订单价 ≠ 现价）
        lines.append(f"   · 现金价：¥{m.get('price', '-')}")
        if m.get("pointsPrice") is not None:
            lines.append(f"   · 积分兑换：{m.get('pointsPrice')} 分/件")
        if m.get("pointsReward"):
            lines.append(f"   · 现金购买返积分：{m.get('pointsReward')} 分/件")
        if m.get("stock") is not None:
            lines.append(f"   · 库存：{m.get('stock')}")
        if m.get("category"):
            lines.append(f"   · 分类：{m.get('category')}")
        if m.get("manufacturer"):
            lines.append(f"   · 厂家：{m.get('manufacturer')}")
        if m.get("indication"):
            lines.append(f"   · 适应症：{m.get('indication')}")
        if m.get("dosage"):
            lines.append(f"   · 用法用量：{m.get('dosage')}")
        return "\n".join(lines)

    def generate_response(self, user_query: str, stream: bool = False, user_id: str = None,
                          search_query: str = None, history_text: str = ""):
        """流式响应：与 OrderAgent 一致，按行分块输出"""
        text = self.generate_text(user_query, user_id=user_id, search_query=search_query,
                                  history_text=history_text)
        if stream:
            for line in text.split("\n"):
                yield line + "\n"
        else:
            yield text


def _quote(value: str) -> str:
    """URL 查询参数编码（避免中文/特殊字符直接进 URL）"""
    from urllib.parse import quote
    return quote(value or "", safe="")


COUPON_STATUS_TEXT = {"UNUSED": "可用", "USED": "已使用", "EXPIRED": "已过期"}


def _coupon_status_text(status: str) -> str:
    return COUPON_STATUS_TEXT.get(status, status or "未知")
