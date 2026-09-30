# 慢性病 AI 问诊项目 — 代码阅读指南

按一次用户对话的完整生命周期串起来，跟着这个顺序读就能理解全貌。

---

## 总体架构（5 层，从外到内）

```
┌─────────────────────────────────────────────────────┐
│ 第 1 层：入口                                       │
│   static/index.html  →  app.py（HTTP / WebSocket）  │
├─────────────────────────────────────────────────────┤
│ 第 2 层：预处理 & 会话管理                           │
│   core/preprocess.py  +  core/chat_session.py       │
├─────────────────────────────────────────────────────┤
│ 第 3 层：编排调度                                    │
│   core/coordinator.py  →  core/graph.py（LangGraph） │
├─────────────────────────────────────────────────────┤
│ 第 4 层：路由 & 专家智能体                            │
│   agents/router_agent.py  →  agents/*_agent.py       │
├─────────────────────────────────────────────────────┤
│ 第 5 层：知识检索 & 支撑                              │
│   core/prompts.py  +  document_loader/               │
└─────────────────────────────────────────────────────┘
```

---

## 第 1 层：入口 — 请求怎么进来的

### `static/index.html`

用户打开页面 → 前端自动调 `POST /api/create_session` 创建新会话 → 拿到 `sessionId`。

发送消息策略：**WebSocket 优先 → HTTP 降级**。

```js
// index.html L203-L228
function streamViaWebSocket(query, onToken) {
    const ws = new WebSocket(`${wsProto}//${location.host}/api/stream`);
    // ws.onopen → ws.send({ query, session_id })
    // ws.onmessage → onToken(token)
}
// 失败降级：
try { await streamViaWebSocket(...) }
catch (e) { await queryViaHttp(...) }
```

### `app.py` — HTTP 端点清单

| 行号 | 端点和函数 | 作用 |
|---|---|---|
| L174-L188 | `class QueryRequest(BaseModel)` | 请求体校验（query 必填，1~4000 字符） |
| L256-L260 | `GET /` | 返回 `static/index.html` |
| L263-L265 | `POST /api/create_session` | 创建会话（兼容旧接口） |
| L269-L272 | `GET /api/sessions` | 历史会话列表（按更新时间倒序） |
| L275-L280 | `POST /api/sessions` | 创建会话 V2（带 user_id + title） |
| L283-L286 | `GET /api/sessions/{id}/messages` | 回看某会话全量历史消息 |
| L289-L296 | `PATCH /api/sessions/{id}` | 重命名会话标题 |
| L299-L302 | `DELETE /api/sessions/{id}` | 删除会话 |
| L304-L308 | `GET /api/sources` | 知识源列表（供前端筛选） |
| L321-L393 | `POST /api/query` | **核心：同步问答**（一次性返回完整答案） |
| L393-L523 | `WS /api/stream` | **核心：WebSocket 流式问答**（给自带前端用） |
| L569+ | `POST /api/query/stream` | **流式问答**（给 Java 服务用，HTTP SSE 风格） |

### `app.py` — 惰性单例（L87-L96）

```python
_vector_store = None    # Milvus 向量库（含 BGE 大模型约 1GB，惰性加载）
_chat_sessions = None   # 会话管理器（PostgreSQL + 内存降级）
_coordinator = None     # 总调度器（持有 graph + 所有 agent）
_init_lock = threading.Lock()

def get_vector_store():
    global _vector_store
    if _vector_store is None:
        with _init_lock:
            if _vector_store is None:  # 双重检查锁定，线程安全
                _vector_store = create_vector_store(rebuild=False)
    return _vector_store
```

---

## 第 2 层：预处理 — 问题先过三道拦截

### `core/preprocess.py`

在 LLM 之前做**三道快筛**，拦截非医疗问题：

```
用户输入
  → check_greeting()          "你好/你是谁/hi" → 返回固定欢迎语，0 秒响应
  → check_general_knowledge()  "1+1=?" / "今天天气" / "推荐股票" → 返回"不在专业范围"
  → check_emergency()          20 个急症关键词 → "建议立即拨打 120"
  → 都没拦截 → 进第 3 层，真正调 LLM
```

---

## 第 2 层续：会话管理 — 历史记忆怎么存

### `core/chat_session.py`

**`ChatSessionManager`** — 会话全生命周期管理。

存储优先级：**PostgreSQL（持久化）> 进程内存（降级）**。

PostgreSQL 不可用时自动降级为内存存储，恢复后补写回库。

| 方法 | 行号 | 作用 |
|---|---|---|
| `create_session()` | L319 | 生成 UUID → 写入库 → 返回 `session_id` |
| `append_exchange()` | L425 | 一对一问答写回 → **自动生成标题** |
| `get_context()` | L460 | 取 `滚动摘要 + 最近窗口` 组合成 LLM 上下文 |
| `list_sessions()` | L329 | 按 `updated_at` 倒序返回（单次 SQL，非 N+1） |
| `get_messages()` | L370 | 回看全量消息 |
| `rename_session()` | — | `PATCH /api/sessions/{id}` |
| `delete_session()` | — | `DELETE /api/sessions/{id}` |
| `turn_lock()` | — | 会话级闸门（同一会话不能并发提问，等超 3 秒抛 `SessionBusyError`） |

### 自动标题生成（L442）

```python
# 第一次问答写回时，取首问前 24 个字符作为标题：
meta["title"] = user_text.strip().replace("\n", " ")[:AUTO_TITLE_LEN]

# 例："高血压的诊断标准是多少？需要注意什么？"
#   → 标题就是 "高血压的诊断标准是多少？"（前 24 字）

# 用户可通过 PATCH /api/sessions/{session_id} 手动改名
```

### 上下文构建（L460+）

```
全量消息 [msg1, msg2, ..., msg20]
         │
         ├── 已压缩部分 (msg1~msg14) → 滚动摘要（LLM 压缩，≤800 字）
         │
         └── 窗口部分 (msg15~msg20) → 最近 10 条消息原文（每条截断 ≤800 字）
                                      ↑ WINDOW_MESSAGES = 10
       触发压缩：未压缩消息 > COMPRESS_TRIGGER（14 条）时
```

---

## 第 3 层：编排 — LangGraph 流程图

### `core/coordinator.py`

**`ChronicDiseaseCoordinator`** — 持有所有子智能体 + 构建 LangGraph 图：

```python
self.agents = {
    "disease":    DiseaseAgent(vector_store),     # 疾病科普
    "medication": MedicationAgent(vector_store),  # 用药咨询
    "lifestyle":  LifestyleAgent(vector_store),   # 饮食运动
    "lab":        LabAgent(vector_store),         # 指标解读
    "risk":       RiskAgent(vector_store),        # 风险预警
    "order":      OrderAgent()                    # 订单查询（不走 RAG）
}
self.router = RouterAgent()
```

两个核心方法：

| 方法 | 原理 |
|---|---|
| `query()` | `graph.invoke()` 一次返回完整答案 |
| `query_stream()` | `graph.stream()` + `_stream_ordered()` 逐 token 输出 |

### `core/graph.py`

**整个系统的大脑 — StateGraph**：

```
               START
                 │
            ┌────▼────┐
            │  route  │  ① 路由节点：RouterAgent 决定需要哪些专家
            └────┬────┘
                 │ dispatch（条件分发，动态 Send fan-out）
            ┌────┼────┬────┐
            ▼    ▼    ▼    │
         expert expert expert  ② 专家节点：并行执行（最多 3 路）
            │    │    │    │
            └────┼────┴────┘
                 ▼
            ┌─────────┐
            │synthesize│  ③ 整合：收齐所有专家回答，合成一篇
            └────┬────┘
                 ▼
                END
```

关键设计：

| 设计 | 说明 |
|---|---|
| `Send` fan-out | 专家并行执行，墙钟只等最慢那路 |
| 白名单校验 | `agent in agents`，非法 agent 名静默丢弃 |
| 数量截断 | `[:3]`，最多 3 路，避免延迟/费用倍增 |
| `_stream_ordered()` | 流式输出按 index 重排，多专家并行的 token 保持有序 |
| 流式/非流式分流 | `streaming=True` 时 `synthesize` 只做纯拼接；非流式调 LLM 二次整合 |

---

## 第 4 层：智能体 — 谁在回答问题

### `agents/router_agent.py`

**三层路由，逐级兜底**：

```
① LLM 路由（主）
   调大模型分析意图 → [{"agent":"disease", "query":"高血压诊断标准"}]

② 关键词路由（兜底）
   LLM 不可用时 → keyword_routes() 对 50+ 关键词精确打分

③ 并集补充
   LLM 命中了 2 路，但关键词还命中 1 路高置信 → 补回来
```

### `agents/base_agent.py`

所有领域专家的基类：

```python
class BaseAgent:
    def generate_text(self, query, search_query, history_text):
        docs = self._retrieve_knowledge(search_query)     # ① 去 Milvus 检索
        prompt = self._build_prompt(query, docs)           # ② 拼接系统提示 + 知识 + 问题
        return self._openai_chat(prompt)                   # ③ 调 LLM 生成

    def generate_response(self, query, stream=True, ...):
        # 流式版本，逐 token yield
```

### `agents/disease_agent.py`

疾病科普专家。继承 `BaseAgent`，覆盖系统提示词：

```python
class DiseaseAgent(BaseAgent):
    @property
    def name(self): return "疾病科普"
    
    def _system_prompt(self):
        return ChronicDiseasePrompts.disease_system_prompt()
    # "你是一位从事慢性病管理的医学科普专家..."
```

### `agents/medication_agent.py` / `lifestyle_agent.py` / `lab_agent.py` / `risk_agent.py`

结构同 `DiseaseAgent`，各自覆盖系统提示词。四者都是从 `prompts.py` 读取提示词模板，从向量库检索各自领域的文档。

### `agents/order_agent.py`

订单智能体，**不走 RAG**，直接调外部 shop-service HTTP 接口：

| 防护 | 说明 |
|---|---|
| URL 路径校验 | 正则拒绝非法字符 |
| DNS rebinding | 每次请求前重新解析 IP 对比 |
| 禁止重定向 | 拦截 302 防止被引流 |
| 统一超时 | `timeout=5` 硬超时 |

---

## 第 5 层：支撑

### `core/prompts.py`

所有系统提示词的**单一来源**：

| 方法 | 作用 |
|---|---|
| `router_prompt()` | "你是一个医疗分诊助手，分析用户问题属于哪些科室……" |
| `disease_system_prompt()` | "你是一位从事慢性病管理的医学科普专家……" |
| `medication_system_prompt()` | "你是一位临床药师，擅长用药指导……" |
| `lifestyle_system_prompt()` | "你是一位健康管理师……" |
| `lab_system_prompt()` | "你是一位临床检验专家……" |
| `risk_system_prompt()` | "你是一位慢性病风险评估专家……" |
| `answer_prompt()` | 组装：`系统提示词 + 历史摘要 + 检索知识 + 用户问题` |
| `synthesis_prompt()` | 多专家回答整合："把以下多位专家的回答整合成一篇……" |

### `document_loader/vector_store.py`

Milvus 向量库的创建与检索：

```python
def create_vector_store(rebuild=False):
    # 加载 BGE 中文嵌入模型 → 连接 Milvus → 返回 VectorStore

def hybrid_search(query, source_filter=None, top_k=5):
    # BM25 关键词 + 语义向量 → 混合检索 → Reranker 重排序
```

### `core/security.py`

内部令牌校验：

```python
HEADER_NAME = "X-Internal-Token"

def token_matches(provided):
    # hmac.compare_digest() 常量时间比较，防时序攻击
    # 未配置 AI_INTERNAL_TOKEN 时所有请求放行
```

### `core/checkpointer.py`

`BoundedInMemorySaver` — 带 LRU + TTL 的 LangGraph 检查点器：

```python
max_threads=500    # 最多保留 500 个会话的图状态
ttl=3600           # 1 小时没访问自动清理
```

### `base/config.py`

读取 `config.ini`：LLM 地址、PostgreSQL 连接、Milvus 连接、会话参数。

---

## 建议阅读顺序（按文件）

| 序号 | 文件 | 关注点 |
|---|---|---|
| ① | `static/index.html` | 前端怎么发请求（WebSocket 优先、HTTP 降级） |
| ② | `app.py` L174-L188 | `QueryRequest` 请求体定义 |
| ③ | `app.py` L304-L393 | `POST /api/query` 完整链路 |
| ④ | `app.py` L393-L523 | `WS /api/stream` 流式通道 |
| ⑤ | `core/preprocess.py` | 问候/急症/通用知识三道拦截 |
| ⑥ | `core/chat_session.py` L319-L470 | 会话创建、标题、上下文压缩 |
| ⑦ | `core/graph.py` | LangGraph 图（route → dispatch → expert → synthesize） |
| ⑧ | `agents/router_agent.py` | 双层路由（LLM + 关键词兜底） |
| ⑨ | `agents/base_agent.py` | 专家基类（检索 + 提示词 + 调模型） |
| ⑩ | `agents/disease_agent.py` | 挑一个专家看懂 |
| ⑪ | `core/coordinator.py` | 全局调度器如何组装各部分 |
| ⑫ | `core/prompts.py` | 所有提示词集中管理 |
| ⑬ | `document_loader/vector_store.py` | Milvus 向量检索 |
| ⑭ | `core/security.py` + `core/checkpointer.py` + `base/config.py` | 安全 + 检查点 + 配置 |

---

## 一次完整对话调用了哪些类（速查）

```
用户发 "高血压怎么控制"
        │
        ▼
[static/index.html]
  new WebSocket("/api/stream") 或 fetch("/api/query")
        │
        ▼
[app.py] POST /api/query
  ├── QueryRequest           # Pydantic 校验
  ├── check_greeting()       # core/preprocess.py
  ├── check_general_knowledge()  # core/preprocess.py
  ├── check_emergency()      # core/preprocess.py
  ├── ChatSessionManager.get_context()  # core/chat_session.py → 取摘要+窗口
  ├── ChatSessionManager.turn_lock()    # 会话级闸门
        │
        ▼
[core/coordinator.py] ChronicDiseaseCoordinator.query()
        │
        ▼
[core/graph.py] StateGraph.invoke()
  ├── route_node()
  │     └── RouterAgent.route()               # agents/router_agent.py
  ├── expert_node() × N（并行）
  │     └── DiseaseAgent.generate_text()       # agents/disease_agent.py
  │           ├── VectorStore.hybrid_search()   # document_loader/vector_store.py
  │           └── prompts.answer_prompt()      # core/prompts.py
  └── synthesize_node()
        │
        ▼
[app.py] _append_exchange_safely()
  └── ChatSessionManager.append_exchange()  # 写回问答 + 自动标题
        │
        ▼
[static/index.html] 渲染回答（marked.parse）
```

---

## 新会话创建流程

```
用户打开页面 或 点"新会话"按钮
        │
        ▼
[static/index.html] createSession()
  → POST /api/create_session
        │
        ▼
[app.py] create_session(L263)
  → ChatSessionManager.create_session()      # core/chat_session.py L319
      ├── sid = uuid4()
      ├── meta = {user_id, title:"", created_at, updated_at, summary:"", summarized_idx:0}
      ├── _save_session(sid, meta, messages=[])
      └── return {session_id, title, ...}
        │
        ▼
[static/index.html] sessionId = data.session_id
  → 后续每次提问都带上 sessionId
  → 第一次问答写回时自动生成标题（取首问前 24 字）
```

---

## 防御体系速查

| 攻击类型 | 防御措施 | 文件 |
|---|---|---|
| Prompt 注入 | 三道前置拦截 + 角色约束 + 内部令牌 | `preprocess.py` + `prompts.py` + `security.py` |
| 死循环 | `BoundedSemaphore(8)` + `Queue(1024)` + 5s 队列超时 + 300s LLM 超时 | `app.py` + `llm_config.py` |
| 并发堆积 | 会话级 `turn_lock()` + 全局 `_stream_semaphore` | `chat_session.py` + `app.py` |
| 工具传参错误 | 路由白名单 + `[:3]` 截断 + 关键词兜底 | `graph.py` + `router_agent.py` |
| 会话无限增长 | `max_messages=200` + `session_ttl_days=7` + 检查点 LRU | `chat_session.py` + `checkpointer.py` |
| SSRF | URL 路径正则 + DNS rebinding + 禁重定向 + 5s 超时 | `order_agent.py` |
| 存储故障覆盖历史 | `SessionLoadError` 向上抛，绝不重试为新建 | `chat_session.py` |