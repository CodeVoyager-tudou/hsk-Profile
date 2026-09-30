"""OrderAgent 单元测试"""
import sys
import os
import json
from datetime import datetime
from unittest.mock import patch, MagicMock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))


class TestOrderAgent:
    def setup_method(self):
        from agents.order_agent import OrderAgent
        self.agent = OrderAgent()

    def test_agent_name(self):
        assert self.agent.name == "order"
        assert self.agent.role == "订单查询"

    def test_no_user_id(self):
        result = self.agent.generate_text("查看订单", user_id=None)
        assert "登录" in result

    def test_empty_user_id(self):
        result = self.agent.generate_text("查看订单", user_id="")
        assert "登录" in result

    def test_query_orders_no_orders(self):
        with patch.object(self.agent, 'query_orders', return_value={"list": [], "total": 0}):
            result = self.agent.generate_text("查看我的订单", user_id="123")
            assert "没有订单" in result

    def test_query_orders_with_data(self):
        mock_orders = {
            "list": [
                {
                    "orderNo": "abc123",
                    "medicineName": "苯磺酸氨氯地平片",
                    "quantity": 2,
                    "totalAmount": 57.0,
                    "payType": "CASH",
                    "status": "PAID",
                    "createTime": "2026-09-01T10:00:00"
                }
            ],
            "total": 1
        }
        with patch.object(self.agent, 'query_orders', return_value=mock_orders):
            result = self.agent.generate_text("查看我的订单", user_id="123")
            assert "abc123" in result
            assert "苯磺酸氨氯地平片" in result
            assert "已支付" in result

    def test_query_order_detail(self):
        mock_order = {
            "orderNo": "abc123",
            "medicineName": "盐酸二甲双胍",
            "quantity": 1,
            "unitPrice": 35.0,
            "totalAmount": 35.0,
            "payType": "POINTS",
            "status": "PAID",
            "pointsUsed": 500,
            "createTime": "2026-09-01T10:00:00"
        }
        with patch.object(self.agent, 'query_order_detail', return_value=mock_order):
            result = self.agent.generate_text("查看订单 abc123", user_id="123")
            assert "abc123" in result
            assert "盐酸二甲双胍" in result
            assert "积分兑换" in result

    def test_order_not_found(self):
        with patch.object(self.agent, 'query_order_detail', return_value={}) as m:
            result = self.agent.generate_text("查看订单 ord999", user_id="123")
            assert "未找到" in result
            # 订单号查询应带上归属用户身份（服务端校验归属）
            assert m.call_args.kwargs.get("user_id") == "123"

    def test_query_text_without_digits_goes_to_list(self):
        r"""中文语境下"查看订单详情/订单状态"不能被当成订单号（\w 会匹配汉字）"""
        with patch.object(self.agent, 'query_order_detail') as detail:
            with patch.object(self.agent, 'query_orders',
                              return_value={"list": [], "total": 0}):
                result = self.agent.generate_text("查看订单详情", user_id="123")
        detail.assert_not_called()
        assert "没有订单" in result

    def test_pending_status_displayed(self):
        orders = [{
            "orderNo": "ord003",
            "medicineName": "药品C",
            "quantity": 1,
            "totalAmount": 30.0,
            "payType": "CASH",
            "status": "PENDING",
            "createTime": "2026-09-03T10:00:00"
        }]
        result = self.agent._format_order_list(orders, 1)
        assert "待支付" in result

    def test_format_order_list(self):
        orders = [
            {
                "orderNo": "ord001",
                "medicineName": "药品A",
                "quantity": 1,
                "totalAmount": 100.0,
                "payType": "CASH",
                "status": "PAID",
                "createTime": "2026-09-01T10:00:00"
            },
            {
                "orderNo": "ord002",
                "medicineName": "药品B",
                "quantity": 3,
                "totalAmount": 90.0,
                "payType": "POINTS",
                "status": "CANCELLED",
                "createTime": "2026-09-02T10:00:00"
            }
        ]
        result = self.agent._format_order_list(orders, 2)
        assert "ord001" in result
        assert "ord002" in result
        assert "药品A" in result
        assert "药品B" in result
        assert "已支付" in result
        assert "已取消" in result

    def test_format_single_order(self):
        order = {
            "orderNo": "ord001",
            "medicineName": "药品A",
            "quantity": 2,
            "unitPrice": 50.0,
            "totalAmount": 100.0,
            "payType": "CASH",
            "status": "PAID",
            "discountAmount": 10.0,
            "pointsEarned": 50,
            "createTime": "2026-09-01T10:00:00"
        }
        result = self.agent._format_single_order(order)
        assert "ord001" in result
        assert "药品A" in result
        assert "优惠金额" in result
        assert "获得积分" in result

    def test_streaming_response(self):
        with patch.object(self.agent, 'generate_text', return_value="line1\nline2\n"):
            chunks = list(self.agent.generate_response("test", stream=True, user_id="123"))
            assert len(chunks) >= 2


    def test_pending_order_carries_pay_marker(self):
        """待支付订单带 [order:<id>] 标记（前端据此渲染「去支付」按钮）；已支付/已取消不带"""
        order_list = {
            "list": [
                {"id": 87, "orderNo": "pend87", "status": "PENDING", "medicineName": "B",
                 "quantity": 1, "totalAmount": 2, "payType": "CASH",
                 "createTime": datetime.now().strftime("%Y-%m-%dT%H:%M:%S")},
                {"id": 88, "orderNo": "paid88", "status": "PAID", "medicineName": "A",
                 "quantity": 1, "totalAmount": 1, "payType": "CASH",
                 "createTime": "2026-09-28T10:00:00"},
            ],
            "total": 2,
        }
        with patch.object(self.agent, "query_orders", return_value=order_list):
            text = self.agent.generate_text("查看我的订单", user_id="1")
        assert "[order:87]" in text
        assert "[order:88]" not in text

    def test_single_order_detail_marker_only_when_pending(self):
        """详情页同样只在待支付时给标记"""
        detail = {"id": 87, "orderNo": "pend87", "status": "PENDING", "medicineName": "B",
                  "quantity": 1, "unitPrice": 2, "totalAmount": 2, "payType": "CASH",
                  "createTime": "2026-09-28T11:00:00"}
        assert "[order:87]" in self.agent._format_single_order(detail)
        detail["status"] = "PAID"
        assert "[order:87]" not in self.agent._format_single_order(detail)

    def test_refunded_question_only_lists_refunded_orders(self):
        """问"已退款"只列真正退过款的（CANCELLED 且有退款金额），超时关单的不算「已退款」"""
        order_list = {
            "list": [
                # 付过款又取消 → 真退款
                {"id": 91, "orderNo": "refund91", "status": "CANCELLED", "refundAmount": 23.8,
                 "refundTime": "2026-09-28T15:00:00", "medicineName": "A", "quantity": 1,
                 "totalAmount": 23.8, "payType": "CASH", "createTime": "2026-09-28T14:00:00"},
                # 未支付超时关单 → 没有退款金额，不能算"已退款"
                {"id": 92, "orderNo": "timeout92", "status": "CANCELLED", "refundAmount": None,
                 "medicineName": "B", "quantity": 1, "totalAmount": 19.9, "payType": "CASH",
                 "createTime": "2026-09-28T12:00:00"},
            ],
            "total": 2,
        }
        with patch.object(self.agent, "query_orders", return_value=order_list):
            text = self.agent.generate_text("我有哪些是已退款的", user_id="1")
        assert "已退款的订单" in text
        assert "refund91" in text and "已退款 ¥23.8" in text
        assert "timeout92" not in text

    def test_refunded_filter_is_pushed_down_to_sql(self):
        """「已退款」必须以 status=REFUNDED 下推给后端在 SQL 里筛。

        早期实现是"取最近 50 笔回来在内存里筛"，用户订单超过 50 笔后
        更早的退款单就永远查不到——这个用例锁住请求参数，防止退回内存筛选。
        复核 P1-2 之后：旧后端不认 REFUNDED 别名时恒返回 0 行，所以首调查到
        total==0 还会再发一次无状态查询做内存兜底 —— 首调必须仍然带别名下推。
        """
        calls = []

        def fake_get_json(path, query="", *a, **kw):
            calls.append((path, query))
            return {"code": 200, "data": {"records": [], "total": 0}}

        with patch("agents.order_agent._get_json", side_effect=fake_get_json):
            text = self.agent.generate_text("我有哪些是已退款的", user_id="1")
        assert calls, "至少要发一次订单查询"
        first_path, first_query = calls[0]
        assert first_path == "/internal/order/list"
        assert "status=REFUNDED" in first_query, first_query
        assert "pageSize=50" in first_query, first_query
        # 兜底必须发生：第二次调用不带 status，而不是拿 0 行直接下结论
        assert len(calls) == 2 and "status=" not in calls[1][1], calls
        assert "没有看到退款单" in text, text

    def test_refunded_alias_fallback_finds_refunds_in_recent_orders(self):
        """复核 P1-2 正向路径：旧后端不认 REFUNDED 别名（恒 0 行）时，内存兜底能找到退款单。

        第一调带别名返回 0 笔；第二调（无状态筛选）返回含退款单的最近订单 ——
        回答必须列出退款单并注明是"从最近 N 笔里筛出"，而不是说"你没有已退款的订单"。
        """
        refunded_order = {
            "id": 93, "orderNo": "refund93", "status": "CANCELLED", "refundAmount": 12.0,
            "refundTime": "2026-09-01T10:00:00", "medicineName": "阿司匹林肠溶片",
            "quantity": 1, "totalAmount": 12.0, "payType": "CASH",
            "createTime": "2026-09-01T09:00:00",
        }
        responses = [
            {"code": 200, "data": {"records": [], "total": 0}},               # 带别名的首调
            {"code": 200, "data": {"records": [refunded_order], "total": 1}},  # 无状态兜底
        ]

        def fake_get_json(path, query="", *a, **kw):
            return responses.pop(0)

        with patch("agents.order_agent._get_json", side_effect=fake_get_json):
            text = self.agent.generate_text("我有哪些是已退款的", user_id="1")
        assert "已退款的订单" in text, text
        assert "refund93" in text and "已退款 ¥12" in text, text
        assert "最近" in text, text  # 措辞必须说明只扫了最近 N 笔这个边界

    def test_refunded_summary_also_uses_the_alias(self):
        """汇总走同一个条件构造，所以"我退过款的订单花了多少"也能按类聚合"""
        with patch.object(self.agent, "order_summary", return_value={
                "totalOrders": 2, "byStatus": {"CANCELLED": 2},
                "paidAmount": 0, "refundedAmount": 47.6, "pointsUsed": 0}) as mocked:
            text = self.agent.generate_text("我退过款的订单一共花了多少钱", user_id="1")
        args, kwargs = mocked.call_args
        status_arg = kwargs.get("status", args[1] if len(args) > 1 else None)
        assert status_arg == "REFUNDED", mocked.call_args
        assert "已退款" in text and "¥47.6" in text, text

    def test_status_question_filters_paid_and_cancelled(self):
        """已支付/已取消同样按状态过滤，而不是把最近订单整段列出来"""
        order_list = {
            "list": [
                {"id": 93, "orderNo": "paid93", "status": "PAID", "medicineName": "A", "quantity": 1,
                 "totalAmount": 1, "payType": "CASH", "createTime": "2026-09-28T10:00:00"},
                {"id": 94, "orderNo": "cancel94", "status": "CANCELLED", "medicineName": "B",
                 "quantity": 1, "totalAmount": 1, "payType": "CASH", "createTime": "2026-09-28T09:00:00"},
            ],
            "total": 2,
        }
        with patch.object(self.agent, "query_orders", return_value=order_list):
            paid_text = self.agent.generate_text("我有哪些已支付的订单", user_id="1")
            cancel_text = self.agent.generate_text("哪些订单已取消", user_id="1")
        assert "paid93" in paid_text and "cancel94" not in paid_text
        assert "cancel94" in cancel_text and "paid93" not in cancel_text

    def test_order_list_hint_carries_a_copyable_order_no(self):
        """列表末尾的提示要给一个可复制的订单号，而不是只说"告诉我具体订单号" """
        order_list = {
            "list": [{"id": 95, "orderNo": "sample95", "status": "PAID", "medicineName": "A",
                      "quantity": 1, "totalAmount": 1, "payType": "CASH",
                      "createTime": "2026-09-28T10:00:00"}],
            "total": 1,
        }
        with patch.object(self.agent, "query_orders", return_value=order_list):
            text = self.agent.generate_text("查看我的订单", user_id="1")
        assert "把订单号发给我" in text
        assert "sample95" in text

    # ===== 契约锁 =====    # 下面几个用例只 mock 掉 HTTP 层（_get_json），真实走 query_orders / query_order_detail
    # 的解析逻辑。此前那两个方法读的是 data.list，而 Java 侧返回的是
    # Result{data:{records,total}}（MyBatis-Plus 分页体）——键名对不上导致列表恒为空、
    # 用户问订单被答成"您目前没有订单记录"；而原有用例把 HTTP 全 mock 了，所以一直没暴露。

    def test_query_orders_unwraps_result_page_records(self):
        """必须解开 Result.data.records 并拿到真实总数"""
        java_response = {
            "code": 200, "message": "success",
            "data": {
                "records": [
                    {
                        "orderNo": "60a2eec6c3ab40f2baf67c13ffe2431f",
                        "medicineName": "阿司匹林肠溶片 限时秒杀",
                        "quantity": 1,
                        "totalAmount": 1.99,
                        "payType": "CASH",
                        "status": "PENDING",
                        "createTime": "2026-09-28T11:22:39",
                    },
                ],
                "total": 3, "size": 5, "current": 1,
            },
        }
        with patch("agents.order_agent._get_json", return_value=java_response):
            result = self.agent.query_orders("1")
        assert len(result["list"]) == 1
        assert result["total"] == 3
        assert result["list"][0]["orderNo"] == "60a2eec6c3ab40f2baf67c13ffe2431f"

    def test_query_orders_returns_empty_on_error_and_keeps_legacy_key(self):
        """内部令牌不匹配（403）等异常回空列表并标记 error；仍兼容 data.list 旧写法"""
        with patch("agents.order_agent._get_json",
                   return_value={"code": 403, "message": "内部接口禁止外部调用"}):
            failed = self.agent.query_orders("1")
        assert failed["error"] is True and failed["list"] == []
        with patch("agents.order_agent._get_json",
                   return_value={"code": 200, "data": {"list": [{"orderNo": "x"}], "total": 1}}):
            assert self.agent.query_orders("1")["total"] == 1

    def test_query_failure_is_not_reported_as_no_orders(self):
        """查询失败要明说不可用，不能伪装成"您没有订单记录"（会误导用户）"""
        with patch.object(self.agent, "query_orders",
                          return_value={"list": [], "total": 0, "error": True}):
            text = self.agent.generate_text("查看我的订单", user_id="1")
        assert "暂时不可用" in text
        assert "没有订单" not in text

    def test_query_order_detail_uses_order_no_path(self):
        """详情按订单号查：订单号不是主键 id，不能拿去撞 /shop/order/{id}"""
        captured = {}

        def fake_get_json(path, query="", user_id=None):
            captured.update({"path": path, "query": query})
            return {"code": 200, "data": {"orderNo": "60a2eec6c3ab40f2baf67c13ffe2431f",
                                          "status": "PAID", "totalAmount": 1.99}}

        with patch("agents.order_agent._get_json", side_effect=fake_get_json):
            detail = self.agent.query_order_detail("60a2eec6c3ab40f2baf67c13ffe2431f", user_id="1")
        assert captured["path"] == "/internal/order/no/60a2eec6c3ab40f2baf67c13ffe2431f"
        assert "userId=1" in captured["query"]
        assert detail["status"] == "PAID"

    def test_pending_orders_first_with_remaining_time_hint(self):
        """待支付排最前，并提示剩余支付时间与超时自动取消"""
        order_list = {
            "list": [
                {"orderNo": "paid0001", "status": "PAID", "medicineName": "A", "quantity": 1,
                 "totalAmount": 1, "payType": "CASH", "createTime": "2026-09-28T10:00:00"},
                {"orderNo": "pend0001", "status": "PENDING", "medicineName": "B", "quantity": 1,
                 "totalAmount": 2, "payType": "CASH",
                 "createTime": datetime.now().strftime("%Y-%m-%dT%H:%M:%S")},
            ],
            "total": 2,
        }
        with patch.object(self.agent, "query_orders", return_value=order_list):
            text = self.agent.generate_text("查看我的订单", user_id="1")
        assert text.index("pend0001") < text.index("paid0001")
        assert "约剩" in text
        assert "自动取消" in text

    def test_pending_only_question_filters_out_paid_and_cancelled(self):
        """问"未支付的订单"时不能把已支付/已取消的也列出来（答非所问）"""
        order_list = {
            "list": [
                {"orderNo": "paid0001", "status": "PAID", "medicineName": "A", "quantity": 1,
                 "totalAmount": 1, "payType": "CASH", "createTime": "2026-09-28T10:00:00"},
            ],
            "total": 1,
        }
        with patch.object(self.agent, "query_orders", return_value=order_list):
            text = self.agent.generate_text("我有哪些未支付的订单", user_id="1")
        assert "没有「待支付」" in text
        assert "paid0001" not in text

        order_list["list"].append(
            {"orderNo": "pend0002", "status": "PENDING", "medicineName": "B", "quantity": 1,
             "totalAmount": 2, "payType": "CASH", "createTime": "2026-09-28T11:00:00"})
        with patch.object(self.agent, "query_orders", return_value=order_list):
            text = self.agent.generate_text("我有哪些未支付的订单", user_id="1")
        assert "pend0002" in text
        assert "paid0001" not in text

    def test_greeting_short_circuit_skipped_for_order_query(self):
        """「你好，帮我查订单」不能被问候语短路掉（否则用户只拿到一句自我介绍）"""
        from core.preprocess import check_greeting
        assert check_greeting("你好，帮我查订单") is None
        assert check_greeting("我有哪些未支付的订单") is None
        assert check_greeting("你好") is not None


class TestRouterKeywordsOrder:
    def test_order_keywords_exist(self):
        from core.router_keywords import AGENT_KEYWORDS
        assert "order" in AGENT_KEYWORDS

    def test_order_keyword_match(self):
        from core.router_keywords import keyword_route, keyword_routes
        agent, score = keyword_route("查看我的订单")
        assert agent == "order"
        assert score >= 1

    def test_order_keyword_in_routes(self):
        from core.router_keywords import keyword_routes
        routes = keyword_routes("我买的东西什么时候发货", min_score=1)
        assert "order" in routes

    def test_order_priority_before_medication(self):
        """订单关键词优先于医疗关键词"""
        from core.router_keywords import keyword_routes
        routes = keyword_routes("查看订单用药记录", min_score=1)
        assert routes[0] == "order"

    def test_non_order_query_not_routed(self):
        from core.router_keywords import keyword_route
        agent, score = keyword_route("高血压吃什么药")
        assert agent != "order"

    def test_router_falls_back_to_keywords_when_llm_returns_empty(self):
        """LLM 路由器返回空结果时必须退回关键词路由。

        否则只命中一个关键词的问法（如「我有哪些是已退款的」，仅命中"退款"）会因为
        并集阈值是 2 分而被漏掉，最终落到「抱歉，暂时无法回答您的问题」的兜底话术 ——
        实测踩到过：同一句换成「我有哪些未支付的订单」时 LLM 恰好认出来了，纯属运气。
        """
        from agents.router_agent import RouterAgent
        router = RouterAgent()
        fake = MagicMock()
        fake.choices = [MagicMock()]
        fake.choices[0].message.content = '[]'
        with patch.object(router.client.chat.completions, "create", return_value=fake):
            routes = router.route("我有哪些是已退款的")
        assert any(r.get("agent") == "order" for r in routes), routes
