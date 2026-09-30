"""会话历史工具。

    本模块只有 `format_history()` 在用（app.py 与 coordinator.py 用它把历史消息拼成提示词文本）。

    注意 `SessionStore` 是**遗留实现**：它是早期基于 Redis 的会话历史存储，
    现已被 core/chat_session.py 的 `ChatSessionManager`（PostgreSQL 优先）取代，
    app.py 不再使用它。保留仅为兼容旧引用，新代码请勿再接入——
    两套会话存储并存会导致「同一会话在两边内容不一致」。
"""
import json
from typing import Dict, List

from base.config import Config
from base.logger import logger

conf = Config()

SESSION_TTL = 1800   # 会话过期时间：30 分钟
MAX_TURNS = 6        # 最多保留最近 6 轮对话（12 条消息）


class SessionStore:
    """【遗留，勿在新代码中使用】基于 Redis 的会话历史存储，Redis 不可用时降级进程内存。

    与 ChatSessionManager 的差别：只存对话历史（没有标题/归属/摘要），
    TTL 交给 Redis 的 EX 过期，因此重启或过期即丢。
    """

    def __init__(self):
        """连接 Redis 并 ping 一次探活；失败则整个实例退化为内存模式（服务不中断）"""
        self._memory: Dict[str, List[Dict]] = {}
        self._redis = None
        try:
            import redis
            client = redis.StrictRedis(
                host=conf.REDIS_HOST,
                port=conf.REDIS_PORT,
                password=conf.REDIS_PASSWORD,
                db=conf.REDIS_DB,
                decode_responses=True,
                socket_connect_timeout=2
            )
            client.ping()
            self._redis = client
            logger.info("会话存储已连接 Redis")
        except Exception as e:
            logger.warning(f"Redis 不可用（{e}），会话历史降级为内存存储")
            self._redis = None

    @staticmethod
    def _key(session_id: str) -> str:
        """Redis 键名（加 chronic:session: 前缀，便于与其他业务数据区分）"""
        return f"chronic:session:{session_id}"

    def get_history(self, session_id: str) -> List[Dict]:
        """取会话历史；session_id 为空或读取异常时返回空列表（读失败不当成致命错误）"""
        if not session_id:
            return []
        try:
            if self._redis:
                raw = self._redis.get(self._key(session_id))
                return json.loads(raw) if raw else []
            return list(self._memory.get(session_id, []))
        except Exception as e:
            logger.warning(f"读取会话失败: {e}")
            return list(self._memory.get(session_id, []))

    def append(self, session_id: str, role: str, content: str):
        """追加一条消息：先读旧历史再整体写回，只保留最近 MAX_TURNS 轮"""
        if not session_id or not content:
            return
        try:
            history = self.get_history(session_id)
            history.append({"role": role, "content": content})
            history = history[-MAX_TURNS * 2:]
            if self._redis:
                self._redis.set(
                    self._key(session_id),
                    json.dumps(history, ensure_ascii=False),
                    ex=SESSION_TTL
                )
            else:
                self._memory[session_id] = history
        except Exception as e:
            logger.warning(f"写入会话失败: {e}")


def format_history(history: List[Dict], max_chars: int = 200) -> str:
    """把会话历史格式化为提示词文本，供路由和专家智能体理解上下文。

    每条消息截断到 max_chars，并只取最近 MAX_TURNS 轮，控制提示词长度。
    """
    if not history:
        return ""
    lines = []
    for msg in history[-MAX_TURNS * 2:]:
        role = "用户" if msg.get("role") == "user" else "助手"
        lines.append(f"{role}: {msg.get('content', '')[:max_chars]}")
    return "\n".join(lines)
