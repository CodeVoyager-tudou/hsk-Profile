"""慢性病系统 LLM 配置解析。

读项目 config.ini 的 [llm] 段（支持本地 Ollama 等自建服务），
缺项逐项回退根 config.ini 的 [llm]（DashScope）。
路由器、专家、整合器统一从这里取，保证换模型只改一处配置。
"""
from base.config import Config

import httpx
import openai

_root = Config()
_chronic = Config()

# 模型名，如 qwen3-max（云）/ qwen2.5:7b（本地 Ollama）
LLM_MODEL = _chronic.config.get("llm", "model", fallback=_root.LLM_MODEL)
# API Key：Ollama 不校验，占位即可；兼容旧键名 dashscope_api_key
LLM_API_KEY = _chronic.config.get("llm", "api_key",
                                  fallback=_chronic.config.get("llm", "dashscope_api_key",
                                                               fallback=_root.DASHSCOPE_API_KEY))
# OpenAI 兼容地址：Ollama 为 http://localhost:11434/v1；兼容旧键名 dashscope_base_url
LLM_BASE_URL = _chronic.config.get("llm", "base_url",
                                   fallback=_chronic.config.get("llm", "dashscope_base_url",
                                                                fallback=_root.DASHSCOPE_BASE_URL))

# ============================================================================
# LLM 调用超时（秒）
#
# 【为什么必须有一个上界】
#   OpenAI SDK 的默认超时很长（约 10 分钟）甚至可能无限等待。
#   原先 router_agent 与 coordinator 创建 client 时**完全没设超时**：
#   一旦后端卡住（Ollama 进程僵死、网络黑洞、模型在加载大文件），
#   这个请求会一直挂着，并**占住一个工作线程**——
#   并发几个这样的请求，服务就再也没有线程可用了（雪崩）。
#
# 【为什么不能设太短】
#   本地 Ollama 用 CPU 推理时，首 token 可能要几十秒。设 5 秒会误杀正常请求。
#   因此默认 300 秒：足够慢机器跑完，又能保证最终一定会失败而不是永久挂起。
#
# 可在 config.ini 的 [llm] 段用 timeout 覆盖。
# 这是**兜底值**：单个调用仍可显式传 timeout 覆盖它。
# ============================================================================
LLM_TIMEOUT = _chronic.config.getint("llm", "timeout", fallback=300)


def make_openai_client() -> "openai.OpenAI":
    """路由器 / 专家 / 整合器统一用这里构造 client，换模型换地址只改这一处。

    http_client 固定 trust_env=False——base_url 是 config.ini 写死的本机/内网服务
    （Ollama），绝不该走代理。Windows 开着「系统代理」时，OpenAI SDK 底层的 httpx
    会读注册表代理设置，但它不认 ProxyOverride 的 <local> 例外规则，
    连 localhost 的请求也会被塞进代理，表现为 502 / 连接被重置
    （curl、ollama CLI 不读注册表所以「明明能通」，极具迷惑性，2026-09-30 实踩）。
    """
    return openai.OpenAI(
        api_key=LLM_API_KEY,
        base_url=LLM_BASE_URL,
        timeout=LLM_TIMEOUT,
        http_client=httpx.Client(trust_env=False),
    )
