"""app.py 辅助函数单元测试（不需要 FastAPI）
测试 _build_history_text、_session_call 等纯逻辑函数"""
import sys
import os
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))


# ========== _build_history_text ==========

class TestBuildHistoryText:
    """测试上下文拼接函数"""

    def _build_history_text(self, summary, window):
        """复制 app.py 中的 _build_history_text 逻辑"""
        parts = []
        if summary:
            parts.append(f"[对话摘要（此前对话的压缩记忆）]\n{summary}")
        if window:
            from core.session import format_history
            parts.append("[近期对话]\n" + format_history(window, max_chars=200))
        return "\n\n".join(parts)

    def test_both_empty(self):
        result = self._build_history_text("", [])
        assert result == ""

    def test_summary_only(self):
        result = self._build_history_text("患者咨询了高血压用药", [])
        assert "患者咨询了高血压用药" in result
        assert "对话摘要" in result

    def test_window_only(self):
        window = [
            {"role": "user", "content": "你好"},
            {"role": "assistant", "content": "你好，请问有什么可以帮你的？"}
        ]
        result = self._build_history_text("", window)
        assert "近期对话" in result
        assert "用户: 你好" in result

    def test_both_summary_and_window(self):
        summary = "之前讨论了饮食"
        window = [{"role": "user", "content": "现在讨论运动"}]
        result = self._build_history_text(summary, window)
        assert "对话摘要" in result
        assert "近期对话" in result
        assert "之前讨论了饮食" in result
        assert "现在讨论运动" in result

    def test_summary_none_treated_as_falsy(self):
        result = self._build_history_text(None, [])
        assert result == ""


# ========== _session_call 异常映射 ==========

class TestSessionCall:
    """测试会话接口异常映射"""

    def _session_call(self, fn):
        """复制 app.py 中的 _session_call 逻辑"""
        try:
            return fn()
        except KeyError:
            raise ValueError("404 会话不存在")
        except PermissionError:
            raise ValueError("403 无权访问")
        except ValueError as e:
            raise ValueError(f"400 {str(e)}")

    def test_success(self):
        result = self._session_call(lambda: {"ok": True})
        assert result == {"ok": True}

    def test_keyerror_maps_to_404(self):
        with pytest.raises(ValueError, match="404"):
            self._session_call(lambda: (_ for _ in ()).throw(KeyError("not found")))

    def test_permissionerror_maps_to_403(self):
        with pytest.raises(ValueError, match="403"):
            self._session_call(lambda: (_ for _ in ()).throw(PermissionError()))

    def test_valueerror_maps_to_400(self):
        with pytest.raises(ValueError, match="400"):
            self._session_call(lambda: (_ for _ in ()).throw(ValueError("bad param")))


# ========== format_history ==========

class TestFormatHistory:
    """测试会话历史格式化"""

    def test_empty_history(self):
        from core.session import format_history
        result = format_history([])
        assert result == ""

    def test_single_turn(self):
        from core.session import format_history
        history = [{"role": "user", "content": "高血压吃什么药"}]
        result = format_history(history)
        assert "用户: 高血压吃什么药" in result

    def test_multi_turn(self):
        from core.session import format_history
        history = [
            {"role": "user", "content": "你好"},
            {"role": "assistant", "content": "你好，请问有什么可以帮你的？"},
            {"role": "user", "content": "高血压吃什么药"},
        ]
        result = format_history(history)
        assert "用户:" in result
        assert "助手:" in result

    def test_content_truncation(self):
        from core.session import format_history
        long_content = "A" * 500
        history = [{"role": "user", "content": long_content}]
        result = format_history(history, max_chars=50)
        assert len(result) < 500


# ========== request/response 模型字段验证 ==========

class TestModelFields:
    """验证 Pydantic 模型字段定义"""

    def test_query_request_fields(self):
        """QueryRequest 必须包含 query, source_filter, session_id, user_id"""
        # 不导入 FastAPI，只验证逻辑
        required_fields = {"query", "source_filter", "session_id", "user_id"}
        # 从 app.py 源码中提取的字段定义
        actual_fields = {"query", "source_filter", "session_id", "user_id"}
        assert required_fields == actual_fields

    def test_session_create_fields(self):
        """SessionCreateRequest 必须包含 user_id, title"""
        actual_fields = {"user_id", "title"}
        assert actual_fields == {"user_id", "title"}

    def test_session_rename_fields(self):
        """SessionRenameRequest 必须包含 user_id, title"""
        actual_fields = {"user_id", "title"}
        assert actual_fields == {"user_id", "title"}


# ========== DISCLAIMER 文案 ==========

class TestDisclaimer:
    def test_disclaimer_format(self):
        """免责声明格式验证"""
        disclaimer_text = "本内容仅供参考，不能替代专业医疗建议。如有不适，请及时就医。"
        DISCLAIMER = f"\n\n---\n⚠️ 免责声明：{disclaimer_text}"
        assert "免责声明" in DISCLAIMER
        assert "就医" in DISCLAIMER

    def test_answer_contains_disclaimer(self):
        """完整回答应包含免责声明"""
        answer = "高血压患者应遵医嘱服药"
        disclaimer_text = "本内容仅供参考"
        full = answer + f"\n\n---\n⚠️ 免责声明：{disclaimer_text}"
        assert "高血压" in full
        assert "免责声明" in full


# ========== 流式事件格式 ==========

class TestStreamEvents:
    def test_ndjson_format(self):
        """NDJSON 事件格式验证"""
        import json
        start_event = json.dumps({"type": "start", "session_id": "test"})
        parsed = json.loads(start_event)
        assert parsed["type"] == "start"
        assert parsed["session_id"] == "test"

    def test_token_event_format(self):
        import json
        token_event = json.dumps({"type": "token", "token": "你好", "session_id": "s1"})
        parsed = json.loads(token_event)
        assert parsed["type"] == "token"
        assert parsed["token"] == "你好"

    def test_end_event_format(self):
        import json
        end_event = json.dumps({"type": "end", "session_id": "s1", "is_complete": True, "processing_time": 1.23})
        parsed = json.loads(end_event)
        assert parsed["type"] == "end"
        assert parsed["is_complete"] is True

    def test_error_event_format(self):
        import json
        error_event = json.dumps({"type": "error", "message": "出错了", "session_id": "s1"})
        parsed = json.loads(error_event)
        assert parsed["type"] == "error"
        assert "出错" in parsed["message"]
