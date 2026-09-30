"""慢性病 AI 问诊服务 —— HTTP 入口（FastAPI）。

【这个文件是干什么的】
    它是整个 Python AI 服务的「大门」：对外暴露 HTTP/WebSocket 接口，
    收到请求后转交给下面的三层去处理，再把结果返回。
    把这个项目想成一家医院：本文件 = 挂号窗口，负责收单、分诊、把结果交回给病人；
    真正「看病」的是 core/ 与 agents/ 里的代码。

【三层结构（从下往上）】
    1. document_loader/  知识层：把医学文档切片、向量化后存进 Milvus（向量数据库），
                         供后续「按语义搜相似段落」。相当于医院的资料库。
    2. core/ + agents/   大脑层：
                         · router_agent 先判断问题属于哪个科室（疾病/用药/生活方式/检验/风险）
                         · 各领域 agent 去资料库检索资料，再交给大模型（Ollama）生成回答
                         · graph.py 用 LangGraph 把这些步骤编排成一张流程图
                         · chat_session.py 负责记住多轮对话（免得每次都从零开始）
    3. app.py（本文件）  接口层：只做参数校验、鉴权、调度和返回。

【对外提供的接口】
    POST /api/query              普通问答（一次性返回完整答案）
    POST /api/query/stream       流式问答（边生成边返回，给 Java 服务用）
    WS   /api/stream             WebSocket 流式问答（给自带前端用）
    GET/POST/PATCH/DELETE /api/sessions...   历史会话的增删改查
    GET  /api/sources            知识源列表

【一个容易踩的坑：为什么初始化要「惰性」】
    下面用的是 get_vector_store() / get_chat_sessions() / get_coordinator() 这种写法，
    而不是在模块顶层直接创建对象。原因：
        加载向量库要读取 BGE 大模型（约 1GB）并连接 Milvus，非常慢。
        如果写在模块顶层，那么任何 `import app`（跑单元测试、CI 静态检查）都会
        被迫等上几十秒甚至直接失败。改成「第一次真正用到时才创建」之后，
        import 只要 3 秒左右。
"""
from fastapi import FastAPI, WebSocket, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse, JSONResponse, StreamingResponse
from starlette.websockets import WebSocketDisconnect
from pydantic import BaseModel, Field
import os
import json
import uuid
import time
import queue
import asyncio
import threading
from concurrent.futures import TimeoutError as FuturesTimeout
from typing import Optional

from base.config import Config
from base.logger import logger
from core.coordinator import ChronicDiseaseCoordinator
from core.preprocess import check_greeting, check_emergency, check_general_knowledge, EMERGENCY_TIP
from core.session import format_history
from core.chat_session import (
    ChatSessionManager,
    MESSAGE_TRUNCATE_CHARS,
    SessionBusyError,
    SessionLoadError,
)
from core.security import HEADER_NAME as INTERNAL_TOKEN_HEADER
from core.security import requires_token, token_matches
from document_loader.vector_store import create_vector_store

conf = Config()
chronic_conf = Config("config.ini")

# 提示词热更新（可选）：config.ini 里配了 [nacos] server_addr 才会真正启动后台轮询；
# pytest 进程内自动跳过，保证测试断言的是内置默认提示词（机制见 core/prompt_store.py）
from core.prompt_store import start_prompt_refresh  # noqa: E402 —— 依赖 conf 的构造时机
start_prompt_refresh(chronic_conf)

# ===== 惰性单例 =====
# 为什么不在模块顶层直接创建这三个对象：向量库要加载 BGE 模型（约 1GB）并连接
# Milvus，底层重依赖（torch / sentence_transformers）本身导入就要十几秒。
# 若写在模块顶层，任何 `import app`（跑单测、CI 静态检查、迁移脚本）都会被强制
# 等待几十秒，依赖不可达时甚至直接抛异常中断导入。改为「首次真正用到时才初始化」后，
# `import app` 恢复为纯声明（约 3 秒）。
_vector_store = None
_chat_sessions = None
_coordinator = None
_init_lock = threading.RLock()
# 为什么必须 RLock（可重入）而不是 Lock：get_coordinator() 持锁初始化时（line 121）
# 会再调 get_vector_store()，后者在同一把锁上再次 with（line 98）——
# 普通 Lock 不可重入，首个走到这里的真实问题会当场自锁死，之后所有请求永久排队。
# 「你好」因问候语短路在 get_coordinator 之前就返回，永远踩不到这个坑，极具迷惑性。

# 流式并发上限。
# 每个流式请求会起一个 daemon 线程消费同步生成器（LLM/检索均为阻塞调用），
# 原先无任何上限：高并发或客户端断连不消费时，线程与队列会持续堆积。
# 以信号量为闸门，超出即快速失败，让上游（Java/nginx）按 503 重试，而不是拖垮进程。
MAX_CONCURRENT_STREAMS = 8
_stream_semaphore = threading.BoundedSemaphore(MAX_CONCURRENT_STREAMS)

# 单个流式请求的事件队列上限。消费端落后于生产端时最多缓冲这么多事件：
# token 事件远小于 1KB，1024 条约 1MB 量级，既足够吸收抖动，又能防止客户端断连时无界增长。
STREAM_QUEUE_MAXSIZE = 1024

# 向队列投递单个事件的最长等待（秒）：超时即认为消费端不可用，丢弃并停止生成
STREAM_PUT_TIMEOUT = 5.0


def get_vector_store():
    """向量库单例（首次调用时加载模型并连接 Milvus，之后复用）"""
    global _vector_store
    if _vector_store is None:
        with _init_lock:
            if _vector_store is None:
                _vector_store = create_vector_store(rebuild=False)
    return _vector_store


def get_chat_sessions() -> ChatSessionManager:
    """会话管理器单例（PostgreSQL 优先，不可用时自动降级内存）"""
    global _chat_sessions
    if _chat_sessions is None:
        with _init_lock:
            if _chat_sessions is None:
                _chat_sessions = ChatSessionManager()
    return _chat_sessions


def get_coordinator():
    """协调器单例：避免每次请求重建 6 个 OpenAI client 与 6 个智能体实例。

    协调器/智能体无可变状态，多线程复用安全（FastAPI 同步端点在独立线程执行）。
    """
    global _coordinator
    if _coordinator is None:
        with _init_lock:
            if _coordinator is None:
                _coordinator = ChronicDiseaseCoordinator(get_vector_store())
    return _coordinator


# 保持向后兼容：模块级名称仍可访问（首次访问时才初始化）。
# 注意：`app.py` 内部一律使用上面的 get_*()，不要直接引用这两个变量，
# 否则会绕过惰性初始化。保留它们只是为了让既有引用者（如测试）不立即报错。
def __getattr__(name):
    """模块级属性回退：访问 vector_store / chat_sessions / coordinator 时按需初始化。

    PEP 562 机制，模块属性找不到时才会调用这里。作用是让既有引用者
    （例如测试里的 `app.vector_store`）不必改成先调 get_*()，
    同时避免模块导入阶段就构造重对象。名字不认识时照常抛 AttributeError。
    """
    if name == "vector_store":
        return get_vector_store()
    if name == "chat_sessions":
        return get_chat_sessions()
    if name == "coordinator":
        return get_coordinator()
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")


app = FastAPI(title="慢性病管理多智能体系统", description="基于多智能体协作的慢性病管理问答系统")

app.add_middleware(
    CORSMiddleware,
    # 前端来源白名单（vite dev / 网关 / 本服务静态页）；服务间调用不经 CORS
    allow_origins=[
        "http://localhost:5173", "http://127.0.0.1:5173",
        "http://localhost:8080", "http://127.0.0.1:8080",
        "http://localhost:8001", "http://127.0.0.1:8001",
    ],
    allow_credentials=True, # 允许携带凭证
    allow_methods=["*"], # 允许所有方法
    allow_headers=["*"], # 允许所有头
)

# 服务间内部令牌：设置 AI_INTERNAL_TOKEN 后，/api/** 与 WebSocket 必须携带 X-Internal-Token
# （user-service 已通过 WebClient defaultHeader 携带；nginx/user-service 之外的直连一律 403）
@app.middleware("http")
async def verify_internal_token(request, call_next):
    """HTTP 中间件：对需要鉴权的路径校验内部令牌，不通过直接返回 403"""
    if requires_token(request.url.path) and not token_matches(request.headers.get(INTERNAL_TOKEN_HEADER)):
        return JSONResponse(status_code=403, content={"detail": "非法请求：缺少或错误的内部令牌"})
    return await call_next(request)


# 前端页面随模块目录定位，不依赖启动时的工作目录
_STATIC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static")
# 新检出仓库时 static/ 可能尚未创建，先确保目录存在，否则下面 StaticFiles 挂载会直接报错
os.makedirs(_STATIC_DIR, exist_ok=True)

# 问候语模式与急症快筛逻辑统一在 core/preprocess.py 维护（评测脚本共用同一口径）

# 免责声明文案来自配置文件，修改配置即可生效，无需改代码
disclaimer_text = chronic_conf.config.get(
    "chronic_disease", "disclaimer",
    fallback="本内容仅供参考，不能替代专业医疗建议。如有不适，请及时就医。"
)
DISCLAIMER = f"\n\n---\n⚠️ 免责声明：{disclaimer_text}"


class QueryRequest(BaseModel):
    """问答请求体（/api/query 与 /api/query/stream 共用）。

    为什么每个字段都要加上长度边界：不设限时，任意长度的 query 会被原样拼进提示词并送进
    检索与推理，十万字的输入足以拖垮推理服务；而 session_id / user_id 会被当作数据库主键
    与日志内容使用，不限长度既无意义又浪费存储。下面的 max_length 取的是
    「足够宽松但不失控」的值，正常提问远小于该上限，不会误伤真实用户。
    """
    query: str = Field(..., min_length=1, max_length=4000,
                       description="用户问题，1~4000 字符")
    # 注意：source_filter 目前仅作接口兼容接收，问答链路尚未按它过滤知识源
    # （检索的知识源由各专家智能体自身的 get_knowledge_source() 决定）。
    source_filter: Optional[str] = Field(default=None, max_length=32,
                                         description="知识源筛选，如 disease/medication")
    session_id: Optional[str] = Field(default=None, max_length=64,
                                      description="会话 ID（UUID 36 位；Java 侧形如 ai-<uid>-<uuid>）")
    user_id: Optional[str] = Field(default=None, max_length=64,
                                   description="用户 ID，由上游服务注入")


class QueryResponse(BaseModel):
    """非流式问答的响应体（流式接口返回 JSON Lines，不用本模型）"""
    answer: str                 # 完整答案（含急症提示前缀与免责声明）
    is_streaming: bool          # 恒为 False，保留给旧调用方判断
    session_id: str             # 本次问答所属会话（未传则服务端新生成）
    processing_time: float      # 服务端处理耗时（秒）


class SessionCreateRequest(BaseModel):
    """新建会话请求体"""
    user_id: Optional[str] = Field(default=None, max_length=64)
    title: str = Field(default="", max_length=60, description="会话标题，最长 60 字")


class SessionRenameRequest(BaseModel):
    """重命名会话请求体：标题必填，空标题会被 _check 层拒绝"""
    user_id: Optional[str] = Field(default=None, max_length=64)
    title: str = Field(..., min_length=1, max_length=60, description="新标题，1~60 字")


def _build_history_text(summary: str, window: list) -> str:
    """组合 LLM 上下文：滚动摘要（更早对话的压缩记忆）+ 最近窗口原文"""
    parts = []
    if summary:
        parts.append(f"[对话摘要（此前对话的压缩记忆）]\n{summary}")
    if window:
        parts.append("[近期对话]\n" + format_history(window, max_chars=MESSAGE_TRUNCATE_CHARS))
    return "\n\n".join(parts)


def _session_call(fn):
    """会话接口统一异常映射：404 不存在 / 403 越权 / 400 参数错"""
    try:
        return fn()
    except KeyError:
        raise HTTPException(status_code=404, detail="会话不存在")
    except PermissionError:
        raise HTTPException(status_code=403, detail="无权访问该会话")
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))


def _append_exchange_safely(session_id: str, query_text: str, answer: str,
                            user_id: Optional[str], start_time: float):
    """写回一轮问答，**绝不让读取故障演变成历史覆盖**。

    append_exchange 在读取失败时抛 SessionLoadError；此处吞掉该异常并记日志，
    因为「写回失败」不应影响本轮已经生成的答案。关键点是**绝不重试为新建会话**：
    一旦新建，UPSERT 会用空 meta 覆盖掉该会话既有历史。
    """
    try:
        get_chat_sessions().append_exchange(session_id, query_text, answer, user_id=user_id)
    except SessionLoadError as e:
        logger.error(f"会话读取失败，已跳过本轮写回以避免覆盖历史: sid={session_id}, {e}")
    except PermissionError:
        # 归属不符：本轮答案仍然返回给调用者，但不写入他人会话
        logger.warning(f"会话归属校验失败，已跳过写回: sid={session_id}")
    except Exception as e:
        logger.error(f"写回会话失败（不影响本轮答案）: sid={session_id}, {e}")


app.mount("/static", StaticFiles(directory=_STATIC_DIR), name="static")


@app.get("/")
async def read_root():
    """返回内置静态前端页面（自带前端用；Java 服务不走这里）"""
    return FileResponse(os.path.join(_STATIC_DIR, "index.html"))


@app.post("/api/create_session")
async def create_session(user_id: Optional[str] = None):
    """兼容旧接口：创建会话（现纳入会话管理，可带 user_id 归属）"""
    return get_chat_sessions().create_session(user_id)


# ===== 会话管理：列表 / 创建 / 回看 / 重命名 / 删除 =====

@app.get("/api/sessions")
def list_sessions(user_id: Optional[str] = None):
    """历史会话列表（按更新时间倒序）；user_id 为空返回空列表"""
    return {"sessions": get_chat_sessions().list_sessions(user_id)}


@app.post("/api/sessions")
def create_session_v2(request: SessionCreateRequest):
    """新建会话（带 user_id 归属与自定义标题），返回新会话的 session_id"""
    return get_chat_sessions().create_session(request.user_id, request.title)


@app.get("/api/sessions/{session_id}/messages")
def get_session_messages(session_id: str, user_id: Optional[str] = None):
    """回看会话全量消息（前端加载历史会话用）"""
    return _session_call(lambda: get_chat_sessions().get_messages(session_id, user_id))


@app.patch("/api/sessions/{session_id}")
def rename_session(session_id: str, request: SessionRenameRequest):
    """自定义会话标题"""
    return _session_call(lambda: get_chat_sessions().rename_session(session_id, request.title, request.user_id))


@app.delete("/api/sessions/{session_id}")
def delete_session(session_id: str, user_id: Optional[str] = None):
    """删除会话（需为会话归属人）"""
    return _session_call(lambda: get_chat_sessions().delete_session(session_id, user_id))


@app.get("/api/sources")
async def get_sources():
    """知识源列表（供前端筛选下拉框），来自配置文件，不再是死配置"""
    sources = [s.strip() for s in chronic_conf.config.get(
        "chronic_disease", "valid_sources",
        fallback="disease,medication,lifestyle,lab,risk"
    ).split(",")]
    return {"sources": sources}


def _stream_worker(query_text: str, history_text: str, session_id: str, token_queue: queue.Queue,
                   user_id: str = None):
    """在工作线程中消费流式生成器，通过队列把 token 送回事件循环，避免阻塞 asyncio。

    仅 WebSocket 端点使用：该路径一个连接对应一个消费端，用普通无界队列；
    HTTP 流式路径改用 _stream_worker_async + 有界 asyncio.Queue 以约束内存。
    无论正常结束还是异常，最后都投递 None 作为结束哨兵。
    """
    try:
        for token in get_coordinator().query_stream(query_text, history_text=history_text,
                                               session_id=session_id, user_id=user_id):
            token_queue.put(token)
    except Exception as e:
        logger.error(f"流式生成失败: {e}")
    finally:
        token_queue.put(None)  # 结束哨兵


# 同步端点：FastAPI 会自动放入线程池执行，避免阻塞式 LLM 调用卡死事件循环、影响其他请求并发
@app.post("/api/query")
def query(request: QueryRequest):
    """普通问答（非流式）：一次性返回完整答案。

    处理顺序：问候语/通用知识/急症快筛（命中即短路，不进检索与大模型）→
    取会话上下文 → 调协调器 → 写回会话。

    错误码约定（不再用 HTTP 200 承载「系统出错」文案，上游可据此重试）：
      502 = 大模型/编排失败；503 = 会话存储不可用；409 = 该会话已有提问在处理。
    """
    start_time = time.time()
    session_id = request.session_id or str(uuid.uuid4())

    greeting_response = check_greeting(request.query)
    if greeting_response:
        _append_exchange_safely(session_id, request.query, greeting_response, request.user_id,
                                start_time)
        return {
            "answer": greeting_response + DISCLAIMER,
            "is_streaming": False,
            "session_id": session_id,
            "processing_time": time.time() - start_time
        }

    # 通用知识拦截：非慢性病相关问题返回友好提示
    general_response = check_general_knowledge(request.query)
    if general_response:
        _append_exchange_safely(session_id, request.query, general_response, request.user_id,
                                start_time)
        return {
            "answer": general_response + DISCLAIMER,
            "is_streaming": False,
            "session_id": session_id,
            "processing_time": time.time() - start_time
        }

    # 急症问题：在完整回答前先给出醒目的急救提示（HTTP 非流式，无法提前单独返回）
    emergency_tip = check_emergency(request.query)
    prefix = f"{emergency_tip}\n\n" if emergency_tip else ""

    # 会话读取失败（存储层瞬时故障）必须显式失败，不能伪装成「空上下文」继续，
    # 否则本轮回答会基于空历史生成（静默失忆），上游无从察觉。
    #
    # 整轮问答（取上下文 -> 调模型 -> 写回）必须持有会话级闸门。
    # 否则同一会话的并发提问会各自读到同一份"最近窗口"、互相看不到对方，
    # 后一个回答基于过时上下文（表现为前后矛盾/重复建议）。
    try:
        with get_chat_sessions().turn_lock(session_id):
            summary, window = get_chat_sessions().get_context(session_id, request.user_id)
            try:
                answer = get_coordinator().query(
                    request.query,
                    history_text=_build_history_text(summary, window),
                    session_id=session_id,
                    user_id=request.user_id,
                )
            except Exception as e:
                logger.error(f"查询失败: {e}")
                # 明确返回 5xx + 结构化错误，不再用 HTTP 200 承载「系统处理出错」文案
                raise HTTPException(status_code=502, detail="AI 服务暂时不可用，请稍后重试")
            # 写回失败（读取故障）不影响本轮已生成的答案，但需记录且不覆盖历史
            _append_exchange_safely(session_id, request.query, answer, request.user_id, start_time)
    except SessionLoadError as e:
        logger.error(f"会话读取失败，拒绝本轮问答以避免覆盖历史: {e}")
        raise HTTPException(status_code=503, detail="会话存储暂时不可用，请稍后重试")
    except SessionBusyError as e:
        # 同一会话已有提问在处理：快速失败并给出明确提示，而不是返回过时上下文生成的答案
        logger.warning(f"会话正忙，拒绝并发提问: sid={session_id}, {e}")
        raise HTTPException(status_code=409, detail="该会话正在处理上一条提问，请稍后再试")

    return {
        "answer": prefix + answer + DISCLAIMER,
        "is_streaming": False,
        "session_id": session_id,
        "processing_time": time.time() - start_time
    }


@app.websocket("/api/stream")
async def websocket_endpoint(websocket: WebSocket):
    """WebSocket 流式问答：一条连接可连续多轮提问，逐 token 推送。

    事件格式与 /api/query/stream 一致（start / token / end / error）。
    收到问候或通用知识类问题只回一条 token 后保持连接（不 break），
    方便用户在同一连接里继续提问。
    """
    # WebSocket 不经过 HTTP 中间件，这里显式校验内部令牌
    if not token_matches(websocket.headers.get(INTERNAL_TOKEN_HEADER)):
        await websocket.close(code=1008)
        return
    await websocket.accept()
    try:
        while True:
            data = await websocket.receive_text()
            request_data = json.loads(data)
            query_text = request_data.get("query")
            session_id = request_data.get("session_id", str(uuid.uuid4()))
            start_time = time.time()

            if websocket.client_state == websocket.client_state.CONNECTED:
                await websocket.send_json({
                    "type": "start",
                    "session_id": session_id
                })

            greeting_response = check_greeting(query_text)
            if greeting_response:
                if websocket.client_state == websocket.client_state.CONNECTED:
                    await websocket.send_json({
                        "type": "token",
                        "token": greeting_response + DISCLAIMER,
                        "session_id": session_id
                    })
                    await websocket.send_json({
                        "type": "end",
                        "session_id": session_id,
                        "is_complete": True,
                        "processing_time": time.time() - start_time
                    })
                continue  # 连接保持：问候后用户可继续提问（原先 break 会断开连接）

            # 通用知识拦截：非慢性病相关问题返回友好提示
            general_response = check_general_knowledge(query_text)
            if general_response:
                if websocket.client_state == websocket.client_state.CONNECTED:
                    await websocket.send_json({
                        "type": "token",
                        "token": general_response + DISCLAIMER,
                        "session_id": session_id
                    })
                    await websocket.send_json({
                        "type": "end",
                        "session_id": session_id,
                        "is_complete": True,
                        "processing_time": time.time() - start_time
                    })
                continue  # 连接保持：与问候分支一致

            try:
                # 急症快速通道：不等检索/LLM 流程，第一时间推送急救提示，后续答案继续流式补充
                emergency_tip = check_emergency(query_text)
                if emergency_tip:
                    if websocket.client_state == websocket.client_state.CONNECTED:
                        await websocket.send_json({
                            "type": "token",
                            "token": emergency_tip + "\n\n",
                            "session_id": session_id
                        })

                # 多轮对话：滚动摘要+最近窗口组成上下文（取上下文/写回均在线程池执行，不阻塞事件循环）
                try:
                    summary, window = await asyncio.to_thread(
                        get_chat_sessions().get_context, session_id, request_data.get("user_id"))
                except SessionLoadError as e:
                    # 会话存储瞬时故障：显式报错，不静默降级为空上下文
                    logger.error(f"会话读取失败，拒绝本轮流式问答: {e}")
                    if websocket.client_state == websocket.client_state.CONNECTED:
                        await websocket.send_json({
                            "type": "error",
                            "message": "会话存储暂时不可用，请稍后重试。",
                            "session_id": session_id
                        })
                    continue
                history_text = _build_history_text(summary, window)
                # 真流式：工作线程逐 token 产出，事件循环只负责转发，两者互不阻塞
                token_queue = queue.Queue()
                threading.Thread(
                    target=_stream_worker,
                    args=(query_text, history_text, session_id, token_queue,
                          request_data.get("user_id")),
                    daemon=True
                ).start()

                full_answer = ""
                while True:
                    token = await asyncio.to_thread(token_queue.get)
                    if token is None:
                        break
                    full_answer += token
                    if websocket.client_state == websocket.client_state.CONNECTED:
                        await websocket.send_json({
                            "type": "token",
                            "token": token,
                            "session_id": session_id
                        })

                await asyncio.to_thread(
                    _append_exchange_safely,
                    session_id, query_text, full_answer,
                    request_data.get("user_id"), start_time)

                if websocket.client_state == websocket.client_state.CONNECTED:
                    await websocket.send_json({
                        "type": "token",
                        "token": DISCLAIMER,
                        "session_id": session_id
                    })
                    await websocket.send_json({
                        "type": "end",
                        "session_id": session_id,
                        "is_complete": True,
                        "processing_time": time.time() - start_time
                    })
            except Exception as e:
                logger.error(f"WebSocket 查询失败: {e}")
                if websocket.client_state == websocket.client_state.CONNECTED:
                    await websocket.send_json({
                        "type": "error",
                        "message": "系统处理出错，请稍后重试。",
                        "session_id": session_id
                    })
    except WebSocketDisconnect as e:
        logger.info(f"WebSocket disconnected: code={e.code}, reason={e.reason}")
    except Exception as e:
        logger.error(f"WebSocket error: {e}")


def _stream_worker_async(query_text: str, history_text: str, session_id: str,
                         aqueue: asyncio.Queue, loop: asyncio.AbstractEventLoop,
                         user_id: str = None):
    """在工作线程中消费流式生成器，把 token 事件投递到 asyncio.Queue 供事件循环转发。

    LLM/检索是同步阻塞调用（OpenAI SDK + LangGraph），无法直接 await，
    因此每个活跃流占一个 daemon 线程；事件循环只做非阻塞转发，不占用线程池。

    投递失败不再逃逸成线程异常：
    - 队列已满（消费端断连/落后）时丢弃后续 token，避免生产端阻塞或无界堆积；
    - 事件循环已关闭时（客户端断开导致请求结束）静默停止投递。
    """
    dropped = False

    def _put(item) -> bool:
        """投递一个事件；成功返回 True。队列满或事件循环关闭时返回 False。"""
        nonlocal dropped
        try:
            fut = asyncio.run_coroutine_threadsafe(aqueue.put(item), loop)
            fut.result(timeout=STREAM_PUT_TIMEOUT)
            return True
        except FuturesTimeout:
            if not dropped:
                dropped = True
                logger.warning("流式队列已满（消费端落后或已断连），开始丢弃后续 token")
            return False
        except Exception as e:
            # RuntimeError: 事件循环已关闭；其余异常同样不应让工作线程崩掉
            logger.debug(f"流式事件投递终止: {type(e).__name__}: {e}")
            return False

    try:
        for token in get_coordinator().query_stream(query_text, history_text=history_text,
                                               session_id=session_id, user_id=user_id):
            if not _put({"type": "token", "token": token}):
                break  # 消费端已不可用，停止生成，避免继续消耗 LLM
    except Exception as e:
        logger.error(f"流式生成失败: {e}")
        _put({"type": "error", "message": "系统处理出错，请稍后重试。"})
    finally:
        _put(None)  # 结束哨兵（消费端已失效时投递失败，无害）


# 流式问答端点：供 Java 服务经 WebClient 拉取（Python 不直接面向前端）。
# 输出 JSON Lines（application/x-ndjson），每行一个 JSON 事件：
#   {"type":"start","session_id":...} / {"type":"token","token":...} /
#   {"type":"end","is_complete":true,...} / {"type":"error","message":...}
# async generator 转发 token，SSE 封装由 Java 层完成
@app.post("/api/query/stream")
async def query_stream(request: QueryRequest):
    """流式问答（NDJSON）：逐 token 产出，供 Java 服务经 WebClient 拉取。

    并发受 _stream_semaphore 约束，超出上限直接 503（不排队）；事件写入有界队列，
    客户端断连时生产端不会无界堆积内存。
    """
    start_time = time.time()
    session_id = request.session_id or str(uuid.uuid4())
    loop = asyncio.get_running_loop()

    # 并发闸门：超过上限时立即拒绝，避免线程/队列无界堆积拖垮进程。
    # 非阻塞获取，拿不到就快速失败（上游可按 503 重试），不在此排队。
    if not _stream_semaphore.acquire(blocking=False):
        logger.warning(f"流式并发已达上限 {MAX_CONCURRENT_STREAMS}，拒绝新请求: sid={session_id}")
        raise HTTPException(status_code=503, detail="当前并发请求过多，请稍后重试")

    # 有界队列：消费端（事件循环）若因客户端断连而停止消费，生产端不会无限堆积内存
    aqueue: asyncio.Queue = asyncio.Queue(maxsize=STREAM_QUEUE_MAXSIZE)
    # 保证任何退出路径都归还信号量（含生成器被提前关闭的情况）
    released = threading.Event()

    def _release_once():
        """归还并发闸门，且保证只归还一次（重复 release 会抛 ValueError）"""
        if not released.is_set():
            released.set()
            try:
                _stream_semaphore.release()
            except ValueError:
                pass  # 已归还，忽略

    async def token_gen():
        """流式事件生成器：依次产出 start / token… / end（或 error），finally 中归还闸门。

        任何退出路径（正常结束、异常、客户端提前断开导致生成器被关闭）都必须归还信号量，
        否则信号量会泄漏，最终永久拒绝所有流式请求。
        """
        yield json.dumps({"type": "start", "session_id": session_id}, ensure_ascii=False) + "\n"
        try:
            # 问候/通用知识拦截：单 token 直接返回，不走检索与 LLM
            direct_response = check_greeting(request.query) or check_general_knowledge(request.query)
            if direct_response:
                yield json.dumps({"type": "token", "token": direct_response + DISCLAIMER,
                                  "session_id": session_id}, ensure_ascii=False) + "\n"
            else:
                # 急症快速通道：第一时间推送急救提示，后续答案继续流式补充
                emergency_tip = check_emergency(request.query)
                if emergency_tip:
                    yield json.dumps({"type": "token", "token": emergency_tip + "\n\n",
                                      "session_id": session_id}, ensure_ascii=False) + "\n"

                # 取上下文可能触发惰性摘要压缩（含一次 LLM 调用），放线程池避免阻塞事件循环
                summary, window = await asyncio.to_thread(get_chat_sessions().get_context, session_id,
                                                      request.user_id)
                history_text = _build_history_text(summary, window)
                threading.Thread(
                    target=_stream_worker_async,
                    args=(request.query, history_text, session_id, aqueue, loop,
                          request.user_id),
                    daemon=True
                ).start()
                full_answer = ""
                errored = False
                while True:
                    item = await aqueue.get()
                    if item is None:
                        break
                    if item["type"] == "token":
                        full_answer += item["token"]
                    elif item["type"] == "error":
                        errored = True
                    yield json.dumps(item, ensure_ascii=False) + "\n"

                if not errored:
                    # 写回失败不得影响已产出的答案，更不得覆盖历史
                    await asyncio.to_thread(
                        _append_exchange_safely, session_id, request.query, full_answer,
                        request.user_id, start_time)
                    yield json.dumps({"type": "end", "session_id": session_id, "is_complete": True,
                                      "processing_time": round(time.time() - start_time, 3)},
                                     ensure_ascii=False) + "\n"
        except SessionLoadError as e:
            # 会话存储瞬时故障：显式 error 事件，不静默降级为空上下文
            logger.error(f"会话读取失败，拒绝本轮流式问答: {e}")
            yield json.dumps({"type": "error", "message": "会话存储暂时不可用，请稍后重试。",
                              "session_id": session_id}, ensure_ascii=False) + "\n"
        except Exception as e:
            logger.error(f"流式查询失败: {e}")
            yield json.dumps({"type": "error", "message": "系统处理出错，请稍后重试。",
                              "session_id": session_id}, ensure_ascii=False) + "\n"
        finally:
            # 无论正常结束、异常还是客户端提前断开导致生成器被关闭，
            # 都必须归还并发闸门，否则信号量会泄漏并最终永久拒绝所有请求。
            _release_once()

    return StreamingResponse(
        token_gen(),
        media_type="application/x-ndjson",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=8001)
    print("服务器已启动")