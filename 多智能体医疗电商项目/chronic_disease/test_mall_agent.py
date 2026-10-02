"""商城与资产智能体 + 订单筛选/汇总/取消提案的单元测试。

这些是"AI 查商城与订单数据"新增能力的回归：价格必须来自商品表（现金价 + 积分价两条渠道），
订单查询条件必须下推 SQL（时间/关键词），取消必须只产出"待确认提案"。
"""
import sys
import os
from unittest.mock import patch

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))


class TestMallAgent:
    def setup_method(self):
        from agents.mall_agent import MallAgent
        self.agent = MallAgent()

    def test_price_answer_shows_both_channels(self):
        """同一商品的两条渠道都要摆出来：现金价 + 积分兑换价（这是最容易答错的地方）"""
        medicine = {
            "id": 12, "name": "阿司匹林肠溶片", "genericName": "阿司匹林",
            "category": "慢病用药", "price": 12.00, "pointsPrice": 900, "pointsReward": 12,
            "stock": 999, "manufacturer": "拜耳医药",
            "indication": "用于降低心肌梗死等血栓事件风险",
        }
        with patch.object(self.agent, "search_medicines", return_value=[medicine]):
            text = self.agent.generate_text("阿司匹林肠溶片多少钱？用积分的话需要多少积分？", user_id="1")
        assert "现金价：¥12.0" in text or "现金价：¥12" in text
        assert "积分兑换：900 分/件" in text
        assert "两条购买渠道" in text

    def test_keyword_stripping_for_price_question(self):
        """问价格时要把"多少钱/用积分"这类意图词剥掉，留下的才是药名"""
        from agents.mall_agent import _clean_keyword
        assert _clean_keyword("阿司匹林肠溶片多少钱？") == "阿司匹林肠溶片"
        assert _clean_keyword("二甲双胍用积分要多少积分") == "二甲双胍"

    def test_no_medicine_found(self):
        with patch.object(self.agent, "search_medicines", return_value=[]):
            text = self.agent.generate_text("不存在的药多少钱", user_id="1")
        assert "没找到" in text

    def test_stays_silent_on_order_and_spending_questions(self):
        """订单/消费类问句不归商城答：返回空串（这一路不发言），而不是把整句话当药名去搜。

        第 2 轮发现：'我最近30天花了多少钱' 含 mall 的强关键词'多少钱'，商城被叫上后
        答出"没找到与「我最近30天花了」匹配的在售药品" —— 答非所问且污染订单专家的汇总。
        """
        for q in ("我最近30天花了多少钱？", "我有哪些未支付的订单", "帮我看看退款到账了吗",
                  "我的订单一共几笔"):
            assert self.agent.generate_text(q, user_id="1") == "", q

    def test_price_question_still_answered(self):
        """反向锁：真正的商品问句不能被"沉默"规则误伤"""
        medicine = {"id": 12, "name": "阿司匹林肠溶片", "price": 12.0, "pointsPrice": 900}
        with patch.object(self.agent, "search_medicines", return_value=[medicine]):
            text = self.agent.generate_text("阿司匹林肠溶片多少钱？", user_id="1")
        assert "现金价：¥12" in text

    def test_assets_answer(self):
        with patch.object(self.agent, "my_points", return_value={"points": 1327, "level": "银卡"}), \
             patch.object(self.agent, "my_account", return_value={"balance": 88.5}):
            text = self.agent.generate_text("我有多少积分和余额", user_id="1")
        assert "1327" in text and "88.5" in text

    def test_coupons_answer(self):
        with patch.object(self.agent, "my_coupons",
                          return_value=[{"couponName": "满100减10", "thresholdAmount": 100,
                                         "discountAmount": 10, "status": "UNUSED"}]):
            text = self.agent.generate_text("我有哪些优惠券", user_id="1")
        assert "满100减10" in text and "可用" in text

    def test_requires_login(self):
        assert "登录" in self.agent.generate_text("阿司匹林多少钱", user_id=None)


class TestOrderFiltersAndProposals:
    def setup_method(self):
        from agents.order_agent import OrderAgent
        self.agent = OrderAgent()

    def test_detect_time_range(self):
        import datetime as dt
        from agents.order_agent import detect_time_range
        now = dt.datetime(2026, 9, 28, 15, 30, 0)   # 周一
        assert detect_time_range("今天买了什么", now=now)[0] == "2026-09-28T00:00:00"
        assert detect_time_range("昨天买了什么", now=now)[0] == "2026-09-27T00:00:00"
        # 本周从周一算起（9/28 本身是周一）
        assert detect_time_range("本周买了什么", now=now)[0] == "2026-09-28T00:00:00"
        # 上周是 9/21（周一）到 9/27 结束
        start, end = detect_time_range("上周买了什么", now=now)
        assert start == "2026-09-21T00:00:00" and end.startswith("2026-09-27T23:59:59")
        assert detect_time_range("本月花了多少", now=now)[0] == "2026-09-01T00:00:00"
        assert detect_time_range("最近7天花了多少", now=now)[0] == "2026-09-21T00:00:00"
        assert detect_time_range("阿司匹林多少钱", now=now) == (None, None)

    def test_extract_order_keyword_ignores_generic_words(self):
        from agents.order_agent import extract_order_keyword
        assert extract_order_keyword("我买过阿司匹林吗") == "阿司匹林"
        assert extract_order_keyword("我买过布洛芬缓释胶囊吗") == "布洛芬缓释胶囊"
        # "详情/状态"是业务通用词，不是药名，不能当关键词去搜
        assert extract_order_keyword("查看订单详情") == ""
        assert extract_order_keyword("订单状态") == ""

    def test_summary_question_uses_aggregate(self):
        summary = {"totalOrders": 30, "byStatus": {"PAID": 5, "CANCELLED": 25},
                   "paidAmount": 128.5, "refundedAmount": 64.2, "pointsUsed": 3000}
        with patch.object(self.agent, "order_summary", return_value=summary) as agg:
            text = self.agent.generate_text("我这个月花了多少钱", user_id="1")
        assert agg.called
        assert "128.5" in text and "3000" in text and "本月" in text

    def test_cancel_question_produces_confirm_marker_only(self):
        """取消只产出待确认提案（[cancel:id]），绝不自行调用取消接口"""
        detail = {"id": 88, "orderNo": "009d3afc84294d96aa8b384b23356c20",
                  "status": "PENDING", "medicineName": "苯磺酸氨氯地平片", "quantity": 1,
                  "totalAmount": 23.8}
        with patch.object(self.agent, "query_order_detail", return_value=detail), \
             patch.object(self.agent, "query_orders", return_value={"list": [], "total": 0}):
            text = self.agent.generate_text("帮我取消订单 009d3afc84294d96aa8b384b23356c20", user_id="1")
        assert "[cancel:88]" in text
        assert "点下面的按钮" in text

    def test_cancelled_status_question_is_not_a_cancel_request(self):
        """问"哪些订单已取消"是查询，不能被当成取消请求"""
        order_list = {"list": [
            {"id": 88, "orderNo": "009d3afc84294d96aa8b384b23356c20", "status": "CANCELLED",
             "medicineName": "A", "quantity": 1, "totalAmount": 23.8, "payType": "CASH",
             "createTime": "2026-09-28T15:20:47"},
        ], "total": 1}
        with patch.object(self.agent, "query_orders", return_value=order_list):
            text = self.agent.generate_text("哪些订单已取消", user_id="1")
        assert "已取消的订单" in text
        assert "[cancel:" not in text
