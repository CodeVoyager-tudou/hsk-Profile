# chronic_disease_ai（AI 问诊服务 · FastAPI + LangGraph + RAG）

慢性病健康管理平台的 AI 服务：**多智能体 RAG 问答**（路由 → 并行领域专家 → 整合），
真流式输出、多轮会话摘要、会话 checkpoint 持久化。前端不直连本服务，生产由网关经
Java `user-service`（WebClient/SSE）转发；也可单独启动使用自带简易页面演示。

配套仓库：微服务 `chronic_disease`、前端 `chronic_disease_vue`。

## 处理链路

```
提问 ──▶ 前置快筛(问候/急症/通用知识) ──▶ 会话上下文(滚动摘要 + 最近窗口)
      ──▶ LangGraph: route ─(Send 并行, ≤3 路)─▶ expert × N ──▶ synthesize
             每个专家: Milvus 混合检索(稠密BGE-M3 + 稀疏) + Reranker 重排 ──▶ LLM 生成
      ──▶ 流式 token 顺序闸门(并行执行、有序输出) ──▶ 会话写回(PostgreSQL checkpoint)
```

## 目录

```
app.py                 # FastAPI 入口：HTTP/流式(NDJSON)/WebSocket、会话 CRUD
config.ini.example     # 配置模板（复制为 config.ini 后按环境改；config.ini 不入库）
core/
  graph.py             # LangGraph 编排（route/expert/synthesize、流式事件、顺序闸门）
  coordinator.py       # 图 facade：query()/query_stream()
  chat_session.py      # 多会话持久化 + 摘要窗口（PostgreSQL 优先，故障降级内存）
  preprocess.py        # 问候/急症/通用知识前置拦截
  router_keywords.py   # LLM 路由失败时的关键词兜底与并集补充
  prompts.py llm_config.py session.py
agents/                # router + disease/medication/lifestyle/lab/risk 五个专家
document_loader/       # PDF/OCR 解析、分块、Milvus 入库（vector_store / ingest_fast）
scripts/               # 运维脚本：UTF-8 BOM 守卫、RAG 语料质量指标（rag_metrics.py）
data/                  # 知识文档（精编 md 入库；PDF/大文件不入库）
                       #   data/archive/ 是**有意排除**在入库范围外的统计年报
                       #   （《中国心血管健康与疾病报告2024》），不在 valid_sources 里，勿当遗漏补入
test_*.py run_eval.py  # 测试与 23 题评测
```

## 快速启动（Python 3.10）

```bash
pip install -r requirements.txt        # 含 fastapi/langgraph/pymilvus/FlagEmbedding 等
cp config.ini.example config.ini       # 按环境修改：Milvus/Ollama/BGE 模型路径/PostgreSQL
# 前置：Milvus 集合已入库并加载；本地 Ollama 提供 OpenAI 兼容接口(默认 qwen2.5:7b)
python app.py                          # 启动 :8001（自带 /static 演示页）
```

主要接口：`POST /api/query`（同步）、`POST /api/query/stream`（NDJSON 流式）、
`WS /api/stream`、`GET /api/sources`、会话 `GET/POST/PATCH/DELETE /api/sessions...`。

## 关键设计

- **多智能体编排**：LangGraph `StateGraph`，路由后 `Send` 并行专家（≤3 路），墙钟≈最慢一路；
  流式用 `stream_mode="custom"` + 顺序闸门，保证多段输出严格按路由顺序；
  `responses` 通道用可序列化的字符串重置哨兵 + reducer（踩坑见 docs）。
- **混合检索**：稠密+稀疏双路召回后 Reranker 重排；全局检索对每个知识源各取候选再统一重排，
  解决 disease 源占库 70% 垄断候选池的问题（检索命中 12/18 → 18/18）；解析/切分改造并重入库后
  当前为 23 题评测 23/23（可检索块 8109）。
- **解析与切分**：PDF 逐页择优取序（双栏排版按 y 排序会把左右栏逐行交错，实测交错率 43.7% → 9.5%）、
  视觉行重组成段落（否则块边界只落在排版行末，实测句末收尾率 5.8% → 23.5%）、
  表格单元格换行归一化（markdown 行数与表格行数对齐，破损表 32 → 0）；
  每页一个 Document，检索结果带 `page` 可溯源，答案引用会标成"根据《指南》第 N 页"。
  指标口径可用 `python scripts/rag_metrics.py --out after.json` 复测。
- **会话与 checkpoint**：滚动摘要(≤800字)+最近窗口(各截断800字)控制 token；
  会话正文/摘要/标题持久化到 PostgreSQL（`chronic_chat_session`，含 TTL 清理、
  故障节流重连、断连内存补写），缺驱动/连不上时自动降级进程内存。
- **降级链**：路由失败→关键词兜底；本知识源无命中→全库兜底；整合失败→分段拼接；
  摘要失败→截断拼接；急症问题先于检索秒回 120 提示。

## 测试与评测

**先跑 pytest 全量（2026-09-29 起整目录可直接跑）**，再手跑下面两个**独立脚本**：

```bash
# ① pytest 全量（162 过 / 2 skip；conftest.py 统一路径，slow 用例可用 -m "not slow" 跳过）
python -m pytest -q .

# ② 两个独立脚本 —— **它们没有 test_ 函数，pytest 收集不到，改完必须手跑**！
#    （第 2 轮复核的教训：graph 闸门的过期断言正是因为没人跑这些脚本才溜过去的）
python test_graph_refactor.py      # LangGraph 编排 32/32（离线 FakeAgent；含 user_id 传递/空段丢弃/单专家出错回归）
python test_router_keywords.py     # 路由兜底/并集/结构一致性 47/47

# ③ 下面这个**有** test_ 函数，pytest 已经在跑；单独执行会走脚本自带的 main
python test_graph_bounds.py        # 顺序闸门边界 9/9
```

其余专项（也已并入 pytest 全量，此处按主题单跑）：

```bash
python test_chat_session.py        # 会话 CRUD/压缩 + PostgreSQL checkpoint（无库自动跳过）
python test_chunker.py             # 分块：parent_id 唯一性/跨页连续/页码透传
python test_loaders.py             # 解析：取序择优/段落重组/表格单元格归一化
python test_eval_judge.py          # 评测判据：关键词覆盖与共现窗口
python test_knowledge_citation.py  # 出处标注：文件名 + 页码拼装
python test_chronic_disease.py     # 全链路（需 Ollama 在线）
python run_eval.py                 # 检索层评测（23 题黄金集；其中 5 题带 max_span 共现判定）
python run_eval.py --full          # 全链路评测（调 LLM，较慢）
python scripts/rag_metrics.py --out after.json   # 语料质量指标（跨栏交错/边界落点/表格保真）
```

> 注意：e2e-ai（`node scripts/e2e-ai.mjs`，走真实 LLM）在 **Ollama 没启动时也会全绿**——
> 关键词兜底路由会扛住全部断言（选路正确，但 RAG 段是降级文案）。跑之前先确认 11434 在监听，
> 或看 `logs/app.log` 有无「路由分析失败: Connection error」。

## 服务间内部令牌（可选，建议生产开启）

设置环境变量 `AI_INTERNAL_TOKEN` 后，所有 `/api/**` 与 WebSocket 请求必须携带 `X-Internal-Token` 头
（常量时间比较），未携带或错误一律 403；未设置时保持开放，方便本地开发与评测脚本直接调用。

```bash
# Linux/macOS
export AI_INTERNAL_TOKEN=$(openssl rand -hex 24)
# Windows PowerShell
$env:AI_INTERNAL_TOKEN = -join ((1..24) | ForEach-Object { '{0:x2}' -f (Get-Random -Max 256) })
```

user-service 侧对应 `chronic.ai.internal-token`（见 `.env.example`），会通过 WebClient defaultHeader 自动携带。
单元测试：`python -m pytest -q test_security.py`
