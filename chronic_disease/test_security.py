"""内部令牌校验的单元测试（不依赖模型/数据库）。"""

import importlib
import os
import unittest


class InternalTokenTest(unittest.TestCase):

    def setUp(self):
        self._old = os.environ.get("AI_INTERNAL_TOKEN")
        os.environ.pop("AI_INTERNAL_TOKEN", None)
        import core.security as security
        self.security = importlib.reload(security)

    def tearDown(self):
        if self._old is None:
            os.environ.pop("AI_INTERNAL_TOKEN", None)
        else:
            os.environ["AI_INTERNAL_TOKEN"] = self._old
        import core.security as security
        importlib.reload(security)

    def test_open_when_token_not_configured(self):
        # 未配置令牌时保持开放：本地开发与评测脚本不需要改造
        self.assertTrue(self.security.token_matches(None))
        self.assertFalse(self.security.requires_token("/api/query"))

    def test_requires_token_when_configured(self):
        os.environ["AI_INTERNAL_TOKEN"] = "s3cret-token"
        import core.security as security
        self.security = importlib.reload(security)

        self.assertTrue(self.security.requires_token("/api/query"))
        self.assertTrue(self.security.requires_token("/api/sessions/abc"))
        # 静态页面与健康检查不需要令牌
        self.assertFalse(self.security.requires_token("/static/app.js"))
        self.assertFalse(self.security.requires_token("/health"))
        # 令牌校验：正确/错误/缺失
        self.assertTrue(self.security.token_matches("s3cret-token"))
        self.assertTrue(self.security.token_matches("  s3cret-token  "))
        self.assertFalse(self.security.token_matches("wrong"))
        self.assertFalse(self.security.token_matches(None))

    def test_header_name(self):
        self.assertEqual("x-internal-token", self.security.HEADER_NAME)


if __name__ == "__main__":
    unittest.main()
