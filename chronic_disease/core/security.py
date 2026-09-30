"""服务间内部令牌校验。

背景：Python AI 服务之前对 8001/9000 端口完全开放，内网任何人都能直接调用模型
（成本与数据双风险）。接入方式：
  - 设置环境变量 AI_INTERNAL_TOKEN 后，所有 /api/* 与 WebSocket 请求必须携带 X-Internal-Token；
  - 未设置时保持开放（本地开发/评测脚本无需改造）。
"""

import hmac
import os

HEADER_NAME = "x-internal-token"

# 无需令牌的路径（静态页面与健康检查）
PUBLIC_PATHS = ("/", "/health", "/docs", "/openapi.json", "/redoc")


def internal_token() -> str:
    """当前配置的内部令牌；返回空串表示未开启鉴权。"""
    return (os.getenv("AI_INTERNAL_TOKEN") or "").strip()


def token_matches(provided: str) -> bool:
    """常量时间比较，避免逐字符比较带来的计时侧信道。"""
    expected = internal_token()
    if not expected:
        return True
    if not provided:
        return False
    return hmac.compare_digest(provided.strip(), expected)


def requires_token(path: str) -> bool:
    """该路径是否需要令牌。"""
    if not internal_token():
        return False
    if path in PUBLIC_PATHS or path.startswith("/static"):
        return False
    return path.startswith("/api/") or path.startswith("/api")
