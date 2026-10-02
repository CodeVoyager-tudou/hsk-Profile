"""带容量上限与空闲 TTL 的内存检查点器：防止图状态随会话数无限增长。

【这个模块做什么】
    LangGraph 的 checkpointer 用来保存每个会话（thread）的图执行状态，本项目
    `thread_id` 就是会话 ID（见 `coordinator._config()` 的 `thread_id=session_id or uuid4()`）。
    官方 `InMemorySaver` 把这些状态放进一个普通 dict，**没有任何淘汰机制**：
      · 每新建一个会话就多一份永不释放的图状态；
      · 匿名请求（不传 session_id）每次都生成新 uuid，即每次都新增一条；
      · 进程长期运行 → 内存单调增长 → 最终 OOM。
    本模块的 `BoundedInMemorySaver` 在官方实现之上加了两层淘汰来封住这个增长：

      1. 容量上限（LRU）：最多保留 `max_threads` 个会话，超出时淘汰最久未使用的；
      2. 空闲 TTL：超过 `ttl_seconds` 未被访问的会话被清理（惰性清除，读写时顺带执行）。

【对功能的影响】
    淘汰只影响**图内部状态**（LangGraph 的 checkpointer 数据），不影响用户可见的会话内容：
    会话正文、摘要、标题、归属都由 `ChatSessionManager`（PostgreSQL，失败降级内存）持久化，
    `query()`/`query_stream()` 每次都显式传入完整 state（`user_query`/`history_text` 等）。
    因此被淘汰的会话在下次问答时会**重建图状态**，功能不受影响，只是丢失图内的中间态
    （本项目未依赖跨请求的图中间态）。

【可调参数】config.ini：

    [graph]
    max_threads = 500        # 最多保留多少个会话的图状态（<=0 表示不限制）
    ttl_seconds = 3600       # 空闲多久后清理（<=0 表示不按时间清理）
"""
import threading
import time
from collections import OrderedDict
from typing import Any, Optional

from base.config import Config
from base.logger import logger

_conf = Config("config.ini")


def _cfg_int(section: str, key: str, fallback: int) -> int:
    """读取 config.ini 中的整型配置；缺段/缺项/值非法时回退 fallback。

    配置问题不应该让进程在 import 阶段就崩掉，因此这里统一吞异常走默认值。
    """
    try:
        return _conf.config.getint(section, key, fallback=fallback)
    except Exception:
        return fallback


MAX_THREADS = _cfg_int("graph", "max_threads", 500)     # 默认最多保留 500 个会话的图状态
TTL_SECONDS = _cfg_int("graph", "ttl_seconds", 3600)    # 默认空闲 1 小时后清理


class BoundedInMemorySaver:
    """有容量上限与 TTL 的内存检查点器（鸭子类型，接口对齐 InMemorySaver）。

    仅代理本项目实际用到的能力：`get_tuple` / `put` / `put_writes` / `list` / 删除类操作。
    未覆盖的方法委托给内部 `InMemorySaver` 实例，保证兼容性。
    """

    def __init__(self, max_threads: int = None, ttl_seconds: int = None,
                 delegate: Any = None):
        """max_threads/ttl_seconds 为 None 时取 config.ini [graph] 的全局配置。

        delegate 可注入任意实现了 checkpointer 协议的对象（测试时常用官方 InMemorySaver），
        缺省在内部新建一个官方 InMemorySaver 做真实存储。
        """
        from langgraph.checkpoint.memory import InMemorySaver

        self._max_threads = MAX_THREADS if max_threads is None else max_threads
        self._ttl = TTL_SECONDS if ttl_seconds is None else ttl_seconds
        # 内部真实存储：委托给官方实现，避免自行实现 checkpointer 协议出错
        self._inner = delegate if delegate is not None else InMemorySaver()
        # thread_id -> 最近访问时间戳；OrderedDict 维护 LRU 顺序（最近使用在最右）
        self._lru: "OrderedDict[str, float]" = OrderedDict()
        self._lock = threading.Lock()
        self._evicted_total = 0

    # ---------------- 内部：淘汰 ----------------

    def _prune_locked(self):
        """淘汰过期与超量的 thread（调用方须持锁）"""
        now = time.time()

        # 1) 空闲 TTL：从最旧开始清理，直到遇到未过期的
        if self._ttl and self._ttl > 0:
            expired = [tid for tid, ts in self._lru.items() if now - ts > self._ttl]
            for tid in expired:
                self._drop_locked(tid)

        # 2) 容量上限：LRU 淘汰最久未用的
        if self._max_threads and self._max_threads > 0:
            while len(self._lru) > self._max_threads:
                oldest, _ = self._lru.popitem(last=False)
                self._delete_inner(oldest)
                self._evicted_total += 1

    def _drop_locked(self, thread_id: str):
        """淘汰单个 thread：从 LRU 表与内部存储中一并移除（调用方须持锁）"""
        self._lru.pop(thread_id, None)
        self._delete_inner(thread_id)
        self._evicted_total += 1

    def _delete_inner(self, thread_id: str):
        """从内部 saver 删除一个 thread 的全部检查点（尽力而为，不抛错）。

        直接操作官方 InMemorySaver 的 storage/writes/blobs 三个 dict——
        官方没有公开的「按 thread 删除」接口，属性缺失或结构变化时静默跳过，
        最坏情况只是该份状态多留一会儿，不能因此让问答链路报错。
        """
        try:
            storage = getattr(self._inner, "storage", None)
            if isinstance(storage, dict):
                storage.pop(thread_id, None)
            writes = getattr(self._inner, "writes", None)
            if isinstance(writes, dict):
                writes.pop(thread_id, None)
            blobs = getattr(self._inner, "blobs", None)
            if isinstance(blobs, dict):
                blobs.pop(thread_id, None)
        except Exception as e:
            logger.debug(f"清理检查点失败（忽略）: thread={thread_id}, {e}")

    def _touch(self, thread_id: str):
        """标记一次访问：刷新该 thread 的 LRU 位置，并顺带做一次惰性淘汰。

        thread_id 为空（匿名请求未带会话 ID 时理论上不会出现，但防御性处理）直接返回。
        """
        if not thread_id:
            return
        with self._lock:
            self._lru[thread_id] = time.time()
            self._lru.move_to_end(thread_id, last=True)
            self._prune_locked()

    @staticmethod
    def _tid(config: Any) -> Optional[str]:
        """从 LangGraph 的 config 中取出 thread_id（即会话 ID）；取不到返回 None。"""
        try:
            return (config or {}).get("configurable", {}).get("thread_id")
        except Exception:
            return None

    # ---------------- 对外：checkpointer 协议 ----------------
    # 以下四个方法都先 _touch 记录一次访问（刷新 LRU 顺序并顺带淘汰），再委托给内部实现。
    # LangGraph 只依赖这些协议方法，其余属性/方法由 __getattr__ 兜底转发。

    def get_tuple(self, config: Any):
        """读取某 thread 的检查点元组（LangGraph 恢复图状态时调用）"""
        self._touch(self._tid(config))
        return self._inner.get_tuple(config)

    def put(self, config: Any, checkpoint: Any, metadata: Any, new_versions: Any):
        """写入检查点（LangGraph 每步执行后调用）"""
        self._touch(self._tid(config))
        return self._inner.put(config, checkpoint, metadata, new_versions)

    def put_writes(self, config: Any, writes: Any, task_id: str, task_path: str = ""):
        """写入某任务的中间写结果（并行分支落盘时调用）"""
        self._touch(self._tid(config))
        return self._inner.put_writes(config, writes, task_id, task_path)

    def list(self, config: Any, **kwargs):
        """列出某 thread 的历史检查点"""
        self._touch(self._tid(config))
        return self._inner.list(config, **kwargs)

    # ---- 兼容：其余属性/方法一律委托给内部实现 ----

    def __getattr__(self, name: str):
        """未显式实现的方法/属性一律转发给内部 saver，保证与官方实现完全兼容。
        注意：__getattr__ 只在常规属性查找失败时被调用，
        因此 _inner 等已定义属性不会走到这里（否则会无限递归）。
        """
        return getattr(self._inner, name)

    # ---------------- 可观测性 ----------------

    @property
    def tracked_threads(self) -> int:
        """当前被跟踪的会话（thread）数量"""
        with self._lock:
            return len(self._lru)

    @property
    def evicted_total(self) -> int:
        """累计淘汰的会话数量（用于排查内存增长是否受控）"""
        return self._evicted_total

    def stats(self) -> dict:
        """当前容量/TTL 配置与运行计数快照，便于排查「内存是否已受控」"""
        return {"tracked_threads": self.tracked_threads,
                "evicted_total": self._evicted_total,
                "max_threads": self._max_threads,
                "ttl_seconds": self._ttl}
