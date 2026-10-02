"""多会话管理：会话增删改查、归属校验、对话窗口与滚动摘要压缩。

【本模块解决什么问题】
    大模型本身没有记忆：用户追问「那这个药有什么副作用」时，模型并不知道「这个药」
    指的是什么，除非每次提问都把之前的对话一起发给它。但对话会越来越长，
    全量发送既会撞上模型的输入长度上限，也越慢越贵。所以这里做了两件事：

      · 窗口（window）：最近 N 条对话原样保留 —— 最近的上下文最相关；
      · 滚动摘要（summary）：更早的对话交给模型压缩成一段简短摘要。
      每次提问发给模型的内容 = 摘要 + 最近窗口，无论聊多久输入长度都有界。

    举例（窗口 10 条、压缩阈值 14 条）：对话累积到第 15 条时触发一次压缩，
    把较早的若干条并入摘要，最近 10 条仍保留原文。

【本模块还负责什么】
    · 会话的增删改查与归属校验（防止 A 用户读到 B 用户的病史等敏感信息）；
    · 持久化：优先 PostgreSQL（重启不丢、多实例共享），连不上时自动降级为进程内存。

【存储设计】（表名取 config.ini [postgres] table，默认 chronic_chat_session）
    sid            TEXT PRIMARY KEY  会话 ID
    user_id        TEXT              归属用户（空 = 匿名）
    title          TEXT              会话标题
    created_at     DOUBLE PRECISION  创建时间戳
    updated_at     DOUBLE PRECISION  最近更新时间戳
    summary        TEXT              滚动摘要（更早对话的压缩记忆）
    summarized_idx INTEGER           已并入摘要的消息下标
    messages       TEXT              全量消息 JSON（供前端回看，超 MAX_MESSAGES 从头裁剪）
    另有 (user_id, updated_at DESC) 索引，支撑「历史会话按时间倒序」的列表查询。

【窗口参数为何这样取值】
    真正进入 LLM 上下文的只有：摘要(≤ SUMMARY_MAX_CHARS) + 最近 WINDOW_MESSAGES 条原文
    （每条截断 MESSAGE_TRUNCATE_CHARS）。按中文 1 字≈1 token 估算，
    10 条 × 800 字 + 摘要 800 字 ≈ 8~9k token 上限，远低于模型限制，
    又给「检索文档 + 提示词 + 输出」留足预算，同时控制每轮延迟。
    未压缩消息超过 COMPRESS_TRIGGER 条才压缩一次，摊薄摘要的 LLM 调用
    （大约每 2 轮对话一次）；压缩在取上下文时惰性执行，失败降级为截断拼接，
    绝不阻塞问答主流程。

【故障处理与并发上的几处关键设计】
    · 「读取失败」与「会话不存在」严格区分：读失败抛 SessionLoadError，
      调用方不得据此走「新建会话」分支，否则空 meta 会经 UPSERT 整行覆盖已有历史。
    · 归属校验不依赖读取是否成功：会话管理接口（回看/改名/删除）要求非空身份，
      避免匿名调用者仅凭 session_id 就能读到他人的敏感健康信息。
    · 「一轮问答」串行化：取上下文 → 调模型 → 写回三步由 turn_lock 串起来，
      否则同一会话的并发提问会各自读到同一份「最近窗口」、互相看不见对方，
      后一个回答基于过时上下文（表现为前后矛盾或重复建议）。
    · PostgreSQL 瞬时故障只做节流重连，不永久降级；恢复后把断连期间内存里新增的
      会话补写回库（ON CONFLICT DO NOTHING，不覆盖库里已存在的旧行）。
    · 超期会话按 session_ttl_days 惰性清理（0 = 不过期），避免表无限增长。
"""
import json
import re
import threading
import time
import uuid
from contextlib import contextmanager
from typing import Callable, Dict, List, Optional, Tuple
from urllib.parse import quote_plus

from openai import OpenAI
from sqlalchemy import create_engine, text

from base.config import Config
from base.logger import logger
from core.llm_config import LLM_MODEL, LLM_API_KEY, LLM_BASE_URL, LLM_TIMEOUT

conf = Config("config.ini")

# 窗口与摘要参数（config.ini [chat] 可覆盖）
WINDOW_MESSAGES = conf.config.getint("chat", "window_messages", fallback=10)        # 进入上下文的最近消息数（5 轮）
COMPRESS_TRIGGER = conf.config.getint("chat", "compress_trigger", fallback=14)      # 未压缩消息超过此数触发压缩
SUMMARY_MAX_CHARS = conf.config.getint("chat", "summary_max_chars", fallback=800)   # 滚动摘要长度上限（字符）
MESSAGE_TRUNCATE_CHARS = conf.config.getint("chat", "message_truncate_chars", fallback=800)  # 单条消息进入上下文的截断
MAX_MESSAGES = conf.config.getint("chat", "max_messages", fallback=200)             # 会话全量消息保存上限（展示用）
SESSION_TTL_DAYS = conf.config.getint("chat", "session_ttl_days", fallback=30)      # 会话保留天数（0=不过期）
AUTO_TITLE_LEN = conf.config.getint("chat", "auto_title_len", fallback=24)          # 自动标题长度（取首问前 N 字）

# PostgreSQL 连接参数（config.ini [postgres] 可覆盖）
PG_HOST = conf.PG_HOST
PG_PORT = conf.PG_PORT
PG_USER = conf.PG_USER
PG_PASSWORD = conf.PG_PASSWORD
PG_DATABASE = conf.PG_DATABASE
PG_TABLE = conf.PG_SESSION_TABLE

# 健壮性节流（秒）
PG_RETRY_INTERVAL = 30      # Postgres 瞬时故障后的重连节流：期间操作走内存，不反复打库
PG_CLEANUP_INTERVAL = 3600  # 过期会话惰性清理的最小间隔（避免每次操作都做 DELETE 扫描）

# 「一轮问答」串行化的等待上限（秒）：取不到会话轮次锁说明已有提问在处理，
# 最多等这么久就快速失败。取值不宜过大——让用户干等体验差，
# 且前端本身有"发送中不允许再发"的守卫，正常不会并发。
TURN_LOCK_TIMEOUT = 3.0

# 摘要提示词：明确要求保留「确诊疾病/在用药物与剂量/关键指标/核心建议/偏好禁忌」，
# 这些是慢性病多轮问诊里一旦丢失就会导致后续回答跑偏的信息
_SUMMARY_PROMPT = (
    "你是一个医患对话摘要器。请把【旧摘要】与【新增对话】合并为一份新的对话摘要，"
    f"不超过{SUMMARY_MAX_CHARS}字。必须保留：患者已确诊的疾病、正在使用的药物与剂量、"
    "关键检验指标、医生/助手给出的核心建议、患者明确的偏好与禁忌。"
    "用第三人称简洁要点表述，不要寒暄。直接输出摘要正文。"
)


class SessionLoadError(RuntimeError):
    """会话**读取失败**（区别于「会话不存在」）。

    存储层瞬时故障时抛出。调用方不得据此走「新建会话」分支：
    一旦据此新建，空 meta 会经 UPSERT 整行覆盖掉该会话的既有历史，
    既能清空历史、又能改写归属。正确做法是显式失败并让上游重试。
    """


class SessionBusyError(RuntimeError):
    """该会话已有另一轮问答在处理中。

    同一会话并发提问时，两个请求会各自读到同一份「最近窗口」而互相看不到对方，
    后一个回答会基于**过时的上下文**（可能与前一轮矛盾或重复）。
    因此用会话级闸门把同一会话的问答串行化；拿不到闸门时抛本异常，
    让调用方明确告知用户「稍后再试」，而不是给出一个基于过期上下文的答案。
    """


class ChatSessionManager:
    """多会话管理器：CRUD + 归属校验 + 窗口/摘要上下文。PostgreSQL 优先，失败降级内存。

    summarizer: 摘要器可注入（测试用）；缺省走 LLM（_llm_summarize）。
    force_memory: True 时完全不碰数据库，永远走内存分支（测试用）。
    """

    def __init__(self, summarizer: Optional[Callable[[str, List[Dict]], str]] = None,
                 force_memory: bool = False):
        """构造管理器；force_memory=False 时立即尝试连库并做一次过期清理。"""
        self._summarizer = summarizer or self._llm_summarize
        # 单次方法调用的会话级锁：保证「读-改-写」在一次调用内原子，避免同会话写入互相覆盖
        self._locks_guard = threading.Lock()
        self._locks: Dict[str, threading.Lock] = {}
        # 「一轮问答」的会话级闸门：与 _locks 是两把不同的锁，见 turn_lock()
        self._turn_locks_guard = threading.Lock()
        self._turn_locks: Dict[str, threading.Lock] = {}
        # 内存降级存储：{sid: meta_dict}，消息放在 meta["messages"]
        self._memory: Dict[str, Dict] = {}
        # 内存模式下的用户索引：{uid: [(updated_at, sid), ...]}，支撑列表按时间倒序
        self._memory_index: Dict[str, List[Tuple[float, str]]] = {}
        self._force_memory = force_memory   # True = 纯内存（测试用），_ensure_pg 永不重连
        self._pg_engine = None
        self._pg = None
        self._pg_retry_at = 0.0             # 下次允许重连 Postgres 的时刻（节流）
        self._pg_cleanup_at = 0.0           # 下次允许清理过期会话的时刻（节流）
        self._pg_reinit_guard = threading.Lock()   # 防多线程同时重连
        self._summarizer_client = None      # 摘要 LLM client，复用见 _llm_summarize
        self._table = self._safe_table(PG_TABLE)
        if not force_memory:
            self._init_pg()
            self._maybe_cleanup_expired()

    # ---------------- 存储初始化 ----------------

    @staticmethod
    def _safe_table(name: str) -> str:
        """校验表名为合法 SQL 标识符。表名来自受控配置，这里是防注入的双保险。"""
        if not name or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name):
            raise ValueError(f"非法的 PostgreSQL 表名: {name!r}")
        return name

    def _pg_url(self) -> str:
        """按本机已安装的驱动拼 SQLAlchemy URL（psycopg2 / psycopg3 优先）"""
        creds = f"{PG_USER}:{quote_plus(PG_PASSWORD)}@{PG_HOST}:{PG_PORT}/{PG_DATABASE}"
        for drv in ("psycopg2", "psycopg"):
            try:
                __import__(drv)
                return f"postgresql+{drv}://{creds}"
            except Exception:
                continue
        # 两个驱动都没有时仍返回 psycopg2 形式：缺驱动会在 connect 处抛错并触发内存降级
        return f"postgresql+psycopg2://{creds}"

    @staticmethod
    def _table_ddl(table: str) -> str:
        """会话表建表语句（IF NOT EXISTS，可重复执行）。

        字段含义见模块 docstring 的「存储设计」；messages 存全量消息 JSON 字符串，
        供前端回看，超 MAX_MESSAGES 时由 _save_session 的调用方从头裁剪。
        """
        return f"""
            CREATE TABLE IF NOT EXISTS {table} (
                sid            TEXT PRIMARY KEY,
                user_id        TEXT NOT NULL DEFAULT '',
                title          TEXT NOT NULL DEFAULT '',
                created_at     DOUBLE PRECISION NOT NULL,
                updated_at     DOUBLE PRECISION NOT NULL,
                summary        TEXT NOT NULL DEFAULT '',
                summarized_idx INTEGER NOT NULL DEFAULT 0,
                messages       TEXT NOT NULL DEFAULT '[]'
            )
        """

    @staticmethod
    def _index_ddl(table: str) -> str:
        """用户维度索引：支撑「按 user_id 查会话、按更新时间倒序」的列表查询"""
        return f"CREATE INDEX IF NOT EXISTS idx_{table}_user ON {table}(user_id, updated_at DESC)"

    def _init_pg(self) -> bool:
        """建立 PostgreSQL 连接并建表（幂等）；失败只降级内存，不中断服务"""
        try:
            self._pg_engine = create_engine(
                self._pg_url(),
                pool_pre_ping=True, pool_size=5, max_overflow=5, pool_recycle=1800,
                connect_args={"connect_timeout": 3},
            )
            with self._pg_engine.connect() as conn:
                conn.execute(text(self._table_ddl(self._table)))
                conn.execute(text(self._index_ddl(self._table)))
                conn.commit()
            self._pg = self._pg_engine
            logger.info(f"会话管理已连接 PostgreSQL（{PG_HOST}:{PG_PORT}/{PG_DATABASE}，"
                        f"checkpoint 表 {self._table}）")
            return True
        except Exception as e:
            logger.warning(f"PostgreSQL 不可用（{e}），会话管理降级为内存存储（重启丢失）")
            self._pg = None
            self._pg_engine = None
            return False

    def _ensure_pg(self) -> bool:
        """Postgres 可用返回 True；不可用时按 PG_RETRY_INTERVAL 节流尝试重连。

        每个存储操作的入口都会先调它。瞬时故障不做永久降级：故障后仍周期性重试，
        恢复成功即把断连期间在内存里新增的会话补写回库。
        force_memory 模式（测试用）永远返回 False，不触碰数据库。
        """
        if self._force_memory:
            return False
        if self._pg is not None:
            self._maybe_cleanup_expired()
            return True
        now = time.time()
        if now < self._pg_retry_at:
            return False
        with self._pg_reinit_guard:
            # 拿到锁后可能已被其他线程重连成功
            if self._pg is not None:
                return True
            if now < self._pg_retry_at:
                return False
            if self._pg_engine is not None:
                try:
                    self._pg_engine.dispose()
                except Exception:
                    pass
                self._pg_engine = None
            self._pg_retry_at = time.time() + PG_RETRY_INTERVAL  # 先节流再尝试
            if self._init_pg():
                self._flush_memory_to_pg()
                return True
        return False

    def _degrade_pg(self):
        """Postgres 操作中途失败：释放连接并进入节流重连状态（不永久降级）"""
        if self._pg_engine is not None:
            try:
                self._pg_engine.dispose()
            except Exception:
                pass
        self._pg = None
        self._pg_engine = None
        self._pg_retry_at = time.time() + PG_RETRY_INTERVAL

    def _flush_memory_to_pg(self):
        """断连恢复后，把降级期间内存里新增的会话补写回 Postgres。

        用 ON CONFLICT DO NOTHING：若该 sid 在库里已有旧行，不覆盖它——
        内存副本可能因为降级期间的读取失败而不完整，覆盖反而会丢数据。
        """
        if not self._memory:
            return
        pending = [(sid, dict(m)) for sid, m in self._memory.items()]
        try:
            with self._pg_engine.connect() as conn:
                for sid, meta in pending:
                    conn.execute(text(f"""
                        INSERT INTO {self._table}
                            (sid, user_id, title, created_at, updated_at, summary, summarized_idx, messages)
                        VALUES (:sid, :user_id, :title, :created_at, :updated_at, :summary, :summarized_idx, :messages)
                        ON CONFLICT (sid) DO NOTHING
                    """), {
                        "sid": sid, "user_id": meta.get("user_id", ""),
                        "title": meta.get("title", ""),
                        "created_at": meta.get("created_at", 0.0),
                        "updated_at": meta.get("updated_at", 0.0),
                        "summary": meta.get("summary", ""),
                        "summarized_idx": meta.get("summarized_idx", 0),
                        "messages": json.dumps(meta.get("messages") or [], ensure_ascii=False),
                    })
                conn.commit()
            logger.info(f"PostgreSQL 已恢复，补写降级期间内存会话 {len(pending)} 个")
            self._memory.clear()
            self._memory_index.clear()
        except Exception as e:
            logger.warning(f"内存会话补写 Postgres 失败（下次重连再试）: {e}")

    def _maybe_cleanup_expired(self):
        """惰性清理超过 session_ttl_days 未更新的会话（SESSION_TTL_DAYS<=0 表示不过期）。

        由 _ensure_pg 顺带触发，并按 PG_CLEANUP_INTERVAL 节流，
        避免每次存储操作都做一次 DELETE 全表扫描。
        """
        if self._pg is None or SESSION_TTL_DAYS <= 0:
            return
        now = time.time()
        if now < self._pg_cleanup_at:
            return
        self._pg_cleanup_at = now + PG_CLEANUP_INTERVAL
        try:
            cutoff = now - SESSION_TTL_DAYS * 86400
            with self._pg_engine.connect() as conn:
                res = conn.execute(
                    text(f"DELETE FROM {self._table} WHERE updated_at < :cutoff"),
                    {"cutoff": cutoff},
                )
                conn.commit()
                if res.rowcount:
                    logger.info(f"清理超过 {SESSION_TTL_DAYS} 天未更新的会话 {res.rowcount} 个")
        except Exception as e:
            logger.warning(f"过期会话清理失败（不影响主流程）: {e}")

    # ---------------- 会话 CRUD ----------------

    def create_session(self, user_id: Optional[str] = None, title: str = "") -> Dict:
        """新建会话，返回 {session_id, title, created_at, updated_at, message_count}。

        sid 由服务端生成 UUID；user_id 为空表示匿名会话（问答链路允许匿名）。
        """
        sid = str(uuid.uuid4())
        now = time.time()
        meta = {"user_id": user_id or "", "title": title, "created_at": now,
                "updated_at": now, "summary": "", "summarized_idx": 0}
        self._save_session(sid, meta, messages=[])
        logger.info(f"创建会话: sid={sid}, user={user_id or 'anonymous'}")
        return {"session_id": sid, "title": title, "created_at": now,
                "updated_at": now, "message_count": 0}

    def list_sessions(self, user_id: Optional[str], limit: int = 50) -> List[Dict]:
        """历史会话列表，按更新时间倒序。

        user_id 为空时直接返回空列表（匿名会话不归属任何人，不展示）。
        """
        if not user_id:
            return []
        if self._ensure_pg():
            try:
                with self._pg_engine.connect() as conn:
                    # 单条 SQL 一次带出列表所需全部字段，避免逐会话 _load_session 的 N+1 查询
                    rows = conn.execute(
                        text(f"""
                            SELECT sid, title, created_at, updated_at,
                                   COALESCE(json_array_length(messages::json), 0) AS message_count
                            FROM {self._table}
                            WHERE user_id = :uid
                            ORDER BY updated_at DESC
                            LIMIT :lim
                        """),
                        {"uid": user_id, "lim": limit}
                    ).mappings().all()
                return [{"session_id": r["sid"], "title": r["title"] or "",
                         "updated_at": float(r["updated_at"]),
                         "created_at": float(r["created_at"]),
                         "message_count": int(r["message_count"])}
                        for r in rows]
            except Exception as e:
                logger.warning(f"列会话失败: {e}")
                self._degrade_pg()
        # Postgres 不可用或本次查询失败：退回内存索引
        entries = sorted(self._memory_index.get(user_id, []), reverse=True)[:limit]
        out = []
        for sid in [sid for _, sid in entries]:
            meta, msgs = self._load_session(sid)
            if meta is None:
                continue
            out.append({"session_id": sid, "title": meta["title"] or self._default_title(msgs),
                        "updated_at": meta["updated_at"], "created_at": meta["created_at"],
                        "message_count": len(msgs)})
        return out

    def get_messages(self, sid: str, user_id: Optional[str] = None) -> Dict:
        """回看某会话的全量消息（展示用，不截断）。

        属会话管理接口：强制要求非空身份，匿名调用一律 PermissionError，
        避免任何匿名调用者仅凭 session_id 就读到他人的病史与用药记录。
        会话不存在时抛 KeyError（由 app 层映射为 404）。
        """
        meta, msgs = self._load_session(sid)
        if meta is None:
            raise KeyError("会话不存在")
        self._check_owner(meta, user_id, require_identity=True)
        return {"session_id": sid, "title": meta["title"] or self._default_title(msgs),
                "summary": meta["summary"], "messages": msgs}

    def rename_session(self, sid: str, title: str, user_id: Optional[str] = None) -> Dict:
        """自定义会话标题。管理类接口：同样强制要求非空身份。"""
        title = (title or "").strip()
        if not title:
            raise ValueError("标题不能为空")
        if len(title) > 60:
            title = title[:60]
        meta, msgs = self._load_session(sid)
        if meta is None:
            raise KeyError("会话不存在")
        self._check_owner(meta, user_id, require_identity=True)
        meta["title"] = title
        meta["updated_at"] = time.time()
        self._save_session(sid, meta, messages=msgs)
        return {"session_id": sid, "title": title}

    def delete_session(self, sid: str, user_id: Optional[str] = None) -> Dict:
        """删除会话。会话本就不存在时视为成功（幂等）；管理类接口同样要求非空身份。"""
        meta, _ = self._load_session(sid)
        if meta is None:
            return {"ok": True}
        self._check_owner(meta, user_id, require_identity=True)
        if self._ensure_pg():
            try:
                with self._pg_engine.connect() as conn:
                    conn.execute(text(f"DELETE FROM {self._table} WHERE sid = :sid"), {"sid": sid})
                    conn.commit()
            except Exception as e:
                logger.warning(f"删除会话失败: {e}")
                self._degrade_pg()
        # 内存分支（含 Postgres 删除失败的降级情形）：同步清掉内存副本与用户索引
        if not self._pg:
            self._memory.pop(sid, None)
            idx = self._memory_index.get(meta["user_id"], [])
            self._memory_index[meta["user_id"]] = [e for e in idx if e[1] != sid]
        logger.info(f"删除会话: sid={sid}")
        return {"ok": True}

    # ---------------- 问答链路接入 ----------------

    def append_exchange(self, sid: str, user_text: str, assistant_text: str,
                        user_id: Optional[str] = None):
        """追加一轮「用户提问 + 助手回答」，并按需自动生成标题、裁剪超长历史。

        整个「读-改-写」在一次 _session_lock 内完成，避免同一会话的并发写入互相覆盖。
        注意 meta is None 只会是「会话确实不存在」（读取失败会抛 SessionLoadError），
        因此这里新建空会话是安全的；反之若把读取失败也当成 None，就会覆盖掉已有历史。
        """
        with self._session_lock(sid):
            meta, msgs = self._load_session(sid)
            now = time.time()
            if meta is not None:
                self._check_owner(meta, user_id)
            else:
                meta = {"user_id": user_id or "", "title": "", "created_at": now,
                        "updated_at": now, "summary": "", "summarized_idx": 0}
                msgs = []
            # 首轮提问的前 AUTO_TITLE_LEN 字作会话标题，便于前端列表辨认
            if not meta["title"]:
                meta["title"] = (user_text or "").strip().replace("\n", " ")[:AUTO_TITLE_LEN]
            meta["updated_at"] = now
            msgs = msgs + [
                {"role": "user", "content": user_text, "ts": round(now, 3)},
                {"role": "assistant", "content": assistant_text, "ts": round(now, 3)},
            ]
            # 全量消息超过上限时从头裁剪；summarized_idx 同步前移，否则下标会错位指向错误的消息
            if len(msgs) > MAX_MESSAGES:
                drop = len(msgs) - MAX_MESSAGES
                msgs = msgs[drop:]
                meta["summarized_idx"] = max(0, meta["summarized_idx"] - drop)
            self._save_session(sid, meta, messages=msgs)

    def get_context(self, sid: str, user_id: Optional[str] = None) -> Tuple[str, List[Dict]]:
        """取本轮问答的 LLM 上下文，返回 (滚动摘要, 最近窗口)。

        未压缩消息超过 COMPRESS_TRIGGER 时顺带做一次惰性压缩：把 idx..evict_end 的
        旧消息并入摘要，并推进 summarized_idx。会话不存在返回 ("", [])。
        调用方（app.py）应把它与「调模型」「写回」一起放在 turn_lock 内。
        """
        with self._session_lock(sid):
            meta, msgs = self._load_session(sid)
            if meta is None:
                return "", []
            self._check_owner(meta, user_id)
            idx = meta["summarized_idx"]
            unsummarized = len(msgs) - idx
            if unsummarized > COMPRESS_TRIGGER:
                evict_end = len(msgs) - WINDOW_MESSAGES
                # 守卫：若配置成 compress_trigger < window_messages，则「可压缩区间」是空的
                # （evict_end <= idx）。此时必须放弃压缩而不是照常推进 summarized_idx——
                # 否则会把一段根本没进过摘要的消息标记为「已摘要」，永久丢失其内容。
                if evict_end <= idx:
                    logger.warning(
                        f"跳过压缩：compress_trigger({COMPRESS_TRIGGER}) < "
                        f"window_messages({WINDOW_MESSAGES})，evicted 区间为空")
                else:
                    evicted = msgs[idx:evict_end]
                    try:
                        meta["summary"] = self._summarizer(meta["summary"], evicted)
                    except Exception as e:
                        # 摘要失败不能阻塞问答：降级为「旧摘要 + 被逐出消息的截断拼接」
                        logger.warning(f"会话摘要压缩失败，降级为截断拼接: {e}")
                        merged = self._naive_merge(meta["summary"], evicted)
                        meta["summary"] = merged
                    meta["summarized_idx"] = evict_end
                    self._save_session(sid, meta, messages=msgs)
                    idx = evict_end
            window = msgs[max(idx, len(msgs) - WINDOW_MESSAGES):]
            return meta["summary"], window

    # ---------------- 摘要 ----------------

    def _llm_summarize(self, old_summary: str, evicted: List[Dict]) -> str:
        """默认摘要器：把「旧摘要 + 被逐出的对话」交给 LLM 合并成一份新摘要。

        client 懒创建并复用（避免每个会话都新建 OpenAI 连接对象）；
        摘要为空视为失败并抛错，由 get_context 降级到 _naive_merge。
        """
        if self._summarizer_client is None:
            self._summarizer_client = OpenAI(api_key=LLM_API_KEY, base_url=LLM_BASE_URL,
                                             timeout=LLM_TIMEOUT)
        chat = []
        for m in evicted:
            role = "用户" if m.get("role") == "user" else "助手"
            chat.append(f"{role}: {str(m.get('content', ''))[:MESSAGE_TRUNCATE_CHARS]}")
        prompt = (f"【旧摘要】\n{old_summary or '（无）'}\n\n"
                  f"【新增对话】\n" + "\n".join(chat))
        resp = self._summarizer_client.chat.completions.create(
            model=LLM_MODEL,
            messages=[{"role": "user", "content": _SUMMARY_PROMPT + "\n\n" + prompt}],
            temperature=0.2,
        )
        text = (resp.choices[0].message.content or "").strip()
        if not text:
            raise RuntimeError("摘要为空")
        return text[:SUMMARY_MAX_CHARS]

    def _naive_merge(self, old_summary: str, evicted: List[Dict]) -> str:
        """不调 LLM 的降级摘要：把被逐出的消息按行截断后拼到旧摘要末尾。

        只保留末尾 SUMMARY_MAX_CHARS 字，保证摘要长度仍有上界。
        """
        lines = [f"{('用户' if m['role'] == 'user' else '助手')}: {m['content'][:120]}"
                 for m in evicted]
        merged = (old_summary + "\n" + "\n".join(lines)) if old_summary else "\n".join(lines)
        return merged[-SUMMARY_MAX_CHARS:]

    # ---------------- 存取与工具 ----------------

    def _session_lock(self, sid: str) -> threading.Lock:
        """取（必要时创建）该会话的读改写锁；锁对象按需创建并长期复用。

        注意首次获取锁本身也要用 _locks_guard 保护，否则并发首次访问会创建出两把锁。
        """
        with self._locks_guard:
            lock = self._locks.get(sid)
            if lock is None:
                lock = threading.Lock()
                self._locks[sid] = lock
            return lock

    # ---------------- 「一轮问答」串行化 ----------------

    def _turn_lock_for(self, sid: str) -> threading.Lock:
        """取（必要时创建）该会话的轮次闸门（与 _session_lock 是两把不同的锁）"""
        with self._turn_locks_guard:
            lock = self._turn_locks.get(sid)
            if lock is None:
                lock = threading.Lock()
                self._turn_locks[sid] = lock
            return lock

    @contextmanager
    def turn_lock(self, sid: str, timeout: float = TURN_LOCK_TIMEOUT):
        """会话级「一轮问答」闸门：同一会话同一时刻只允许一轮问答在跑。

        用法：with turn_lock(sid): 取上下文 → 调模型(慢) → 写回。
        超时仍拿不到锁说明已有提问在处理，抛 SessionBusyError 让调用方快速失败
        （app 层映射为 409），而不是返回一个基于过时上下文的答案。
        闸门按会话隔离：A 会话在跑不影响 B 会话。
        """
        lock = self._turn_lock_for(sid)
        if not lock.acquire(timeout=timeout):
            raise SessionBusyError("该会话正在处理上一条提问，请稍后再试")
        try:
            yield
        finally:
            lock.release()

    @staticmethod
    def _check_owner(meta: Dict, user_id: Optional[str], require_identity: bool = False):
        """归属校验：会话 owner 与调用者必须一致。

        require_identity=True（会话管理接口：回看/改名/删除）时，调用者身份不得为空——
        否则匿名调用者只要知道 session_id 就能读到他人会话。
        问答链路不传该参数，以便保留匿名演示能力。
        """
        owner = meta.get("user_id") or ""
        caller = user_id or ""
        if require_identity and not caller:
            raise PermissionError("会话管理接口需要登录身份")
        if owner != caller:
            raise PermissionError("无权访问该会话")

    @staticmethod
    def _default_title(msgs: List[Dict]) -> str:
        """未显式设置标题时，取首条用户消息前 AUTO_TITLE_LEN 字作为展示标题"""
        for m in msgs:
            if m.get("role") == "user":
                return (m.get("content") or "").strip().replace("\n", " ")[:AUTO_TITLE_LEN] or "新会话"
        return "新会话"

    def _load_session(self, sid: str) -> Tuple[Optional[Dict], List[Dict]]:
        """读取会话 meta + 全量消息。

        返回值的两种「空」含义严格不同，调用方必须区分：
        - (None, [])      会话确实不存在（可以据此新建）；
        - 抛 SessionLoadError  存储层读取失败（**不得**据此新建，否则覆盖历史）。

        Postgres 分支读失败时先 _degrade_pg 转入内存降级，再抛异常。
        """
        if self._ensure_pg():
            try:
                with self._pg_engine.connect() as conn:
                    row = conn.execute(
                        text(f"""
                            SELECT user_id, title, created_at, updated_at, summary,
                                   summarized_idx, messages
                            FROM {self._table}
                            WHERE sid = :sid
                        """),
                        {"sid": sid}
                    ).mappings().first()
                if row is None:
                    return None, []
                meta = {"user_id": row["user_id"], "title": row["title"],
                        "created_at": float(row["created_at"]),
                        "updated_at": float(row["updated_at"]),
                        "summary": row["summary"] or "",
                        "summarized_idx": int(row["summarized_idx"] or 0)}
                msgs = json.loads(row["messages"] or "[]")
                return meta, msgs
            except Exception as e:
                logger.warning(f"读取会话失败: {e}")
                self._degrade_pg()
                raise SessionLoadError(str(e)) from e
        state = self._memory.get(sid)
        if not state:
            return None, []
        return dict(state), list(state["messages"])

    def _save_session(self, sid: str, meta: Dict, messages: List[Dict]):
        """写入会话 meta + 全量消息（整行 UPSERT，以 sid 为冲突键）。

        注意是**整行覆盖**：调用方必须先 _load_session 拿到完整 meta 再改字段，
        否则漏传的字段会被写成默认值。Postgres 写失败时降级写内存。
        """
        if self._ensure_pg():
            try:
                payload = json.dumps(messages, ensure_ascii=False) if messages else "[]"
                with self._pg_engine.connect() as conn:
                    conn.execute(text(f"""
                        INSERT INTO {self._table}
                            (sid, user_id, title, created_at, updated_at, summary, summarized_idx, messages)
                        VALUES (:sid, :user_id, :title, :created_at, :updated_at, :summary, :summarized_idx, :messages)
                        ON CONFLICT (sid) DO UPDATE SET
                            user_id = excluded.user_id, title = excluded.title,
                            created_at = excluded.created_at, updated_at = excluded.updated_at,
                            summary = excluded.summary, summarized_idx = excluded.summarized_idx,
                            messages = excluded.messages
                    """), {
                        "sid": sid, "user_id": meta["user_id"], "title": meta["title"],
                        "created_at": meta["created_at"], "updated_at": meta["updated_at"],
                        "summary": meta["summary"], "summarized_idx": meta["summarized_idx"],
                        "messages": payload,
                    })
                    conn.commit()
                return
            except Exception as e:
                logger.warning(f"写入会话失败（降级内存）: {e}")
                self._degrade_pg()
        self._memory[sid] = {**meta, "messages": list(messages)}
        # 同步维护内存用户索引（先剔除旧条目再追加，避免同一 sid 出现多条导致列表重复）
        if meta["user_id"]:
            entries = [e for e in self._memory_index.get(meta["user_id"], []) if e[1] != sid]
            entries.append((meta["updated_at"], sid))
            self._memory_index[meta["user_id"]] = entries