"""提示词热更新：Nacos 覆盖层 + 内置默认值（可缺席、可失败、可回退）。

【这个文件是干什么的】
    core/prompts.py 是提示词的「唯一默认值来源」；本文件在它之上叠一层可选的
    Nacos 覆盖：后台线程轮询 Nacos 上的 chronic-ai-prompts.yaml，内容有变化就
    整体替换覆盖层，实现「控制台改提示词 → 最迟一个轮询间隔生效、无需重启服务」。

【为什么这样设计】
    - 不引 Nacos 官方 Python SDK：提示词场景「30 秒内生效」足够，用 requests
      轮询开放 API（几十行）即可，少一个依赖少一份版本兼容风险；
    - 覆盖层是运行时查表，core/prompts.py 的默认值一字不动——Nacos 没配置、
      网络断了、内容写坏了，问答链路都回退到内置默认，与 PostgreSQL checkpoint
      「连不上就降级内存」是同一套故障哲学；
    - 覆盖值必须通过占位符校验（与默认模板的字段集合完全一致、大括号闭合），
      防止控制台上手一改把 {query} 删了，线上所有请求当场 KeyError。

【Nacos 上的数据格式】（deploy/nacos/chronic-ai-prompts.yaml）
    prompts:
      router_prompt: |
        （模板原文，与 core/prompts.py 的默认值同构，占位符必须一致）
      disease_system_prompt: |
        ...
"""
import hashlib
import threading
import time
from string import Formatter

import requests
import yaml

from base.config import Config
from base.logger import logger


def _extract_fields(template: str) -> set:
    """提取模板的占位符字段集合；模板有大括号没闭合时抛 ValueError。

    {{x}} 是转义（.format 输出字面量大括号），不算占位符，会被自动忽略。
    """
    return {field for _, field, _, _ in Formatter().parse(template) if field}


def parse_prompts_yaml(content: str):
    """从 Nacos 配置原文里取 prompts 段；格式不对返回 None（由调用方记日志）。"""
    try:
        data = yaml.safe_load(content) or {}
    except yaml.YAMLError:
        return None
    if not isinstance(data, dict):
        return None
    prompts = data.get("prompts")
    return prompts if isinstance(prompts, dict) else None


class PromptStore:
    """提示词覆盖层：默认值在 core/prompts.py，这里只存 Nacos 覆盖。"""

    _lock = threading.Lock()
    _defaults: dict = {}
    _overrides: dict = {}
    _source = "default"

    @classmethod
    def register_defaults(cls, mapping: dict):
        """注册默认模板（core/prompts.py 导入时调用一次），占位符契约以它为准。"""
        with cls._lock:
            cls._defaults = dict(mapping)

    @classmethod
    def get(cls, name: str, default: str) -> str:
        """取模板：有覆盖用覆盖，没有用默认。问答链路的每次调用都走这里。"""
        with cls._lock:
            value = cls._overrides.get(name)
        return default if value is None else value

    @classmethod
    def apply(cls, prompts: dict, source: str):
        """校验并整体替换覆盖层。返回 (接受条数, 拒绝明细列表)。

        校验规则：模板名必须在默认注册表里、内容非空、大括号闭合、
        占位符字段与默认模板完全一致（缺一个运行时 KeyError，多一个同样 KeyError）。
        全部被拒时保留现有覆盖不动。
        """
        with cls._lock:
            defaults = dict(cls._defaults)
        accepted, rejected = {}, []
        for name, text in (prompts or {}).items():
            if name not in defaults:
                rejected.append(f"{name}(未知模板名)")
                continue
            if not isinstance(text, str) or not text.strip():
                rejected.append(f"{name}(内容为空)")
                continue
            try:
                fields = _extract_fields(text)
            except ValueError:
                rejected.append(f"{name}(存在未闭合的大括号)")
                continue
            required = _extract_fields(defaults[name])
            if fields != required:
                rejected.append(
                    f"{name}(占位符不符，需要 {sorted(required)}，实际 {sorted(fields)})")
                continue
            accepted[name] = text
        with cls._lock:
            if accepted:
                cls._overrides = accepted
                cls._source = source
        return len(accepted), rejected

    @classmethod
    def clear_overrides(cls):
        """清空覆盖（测试与运维用），回到纯内置默认。"""
        with cls._lock:
            cls._overrides = {}
            cls._source = "default"

    @classmethod
    def source(cls) -> str:
        with cls._lock:
            return cls._source


class NacosPromptPoller:
    """轮询 Nacos 上的提示词配置；所有失败都只记日志、绝不抛出中断服务。"""

    def __init__(self, conf: Config):
        self.server = (conf.NACOS_SERVER_ADDR or "").strip()
        self.namespace = (conf.NACOS_NAMESPACE or "").strip()
        self.username = conf.NACOS_USERNAME
        self.password = conf.NACOS_PASSWORD
        self.data_id = conf.NACOS_PROMPT_DATA_ID
        self.group = conf.NACOS_PROMPT_GROUP
        self.interval = max(10, int(conf.NACOS_PROMPT_REFRESH or 30))
        self._token = None
        self._token_at = 0.0
        self._last_md5 = None
        # 地址只允许 http/https（本项来自本机 config.ini，属操作员配置，非用户输入；
        # 允许内网地址是需求本身——Nacos 就部署在内网）
        if self.server.startswith("http://") or self.server.startswith("https://"):
            self.base = self.server.rstrip("/")
        else:
            self.base = f"http://{self.server}".rstrip("/")

    def _login(self) -> str:
        now = time.time()
        # Nacos 的 accessToken 有效期通常数小时，按 50 分钟提前量续期
        if self._token and now - self._token_at < 3000:
            return self._token
        resp = requests.post(
            f"{self.base}/nacos/v1/auth/login",
            data={"username": self.username, "password": self.password},
            timeout=5)
        resp.raise_for_status()
        token = resp.json().get("accessToken")
        if not token:
            raise RuntimeError("登录响应中没有 accessToken（检查账号口令与服务端鉴权配置）")
        self._token = token
        self._token_at = now
        return token

    def _fetch_config(self) -> str:
        token = self._login()
        params = {"dataId": self.data_id, "group": self.group, "accessToken": token}
        if self.namespace:
            params["tenant"] = self.namespace
        resp = requests.get(f"{self.base}/nacos/v1/cs/configs",
                            params=params, timeout=5)
        if resp.status_code == 403:
            # token 失效：清空登录态，下一轮重新登录
            self._token = None
            resp.raise_for_status()
        resp.raise_for_status()
        return resp.text

    def _poll_once(self):
        content = self._fetch_config()
        # dataId 还没发布过时 Nacos 返回空串：静默等待，不算错误
        if not content or not content.strip():
            return
        md5 = hashlib.sha256(content.encode("utf-8")).hexdigest()
        if md5 == self._last_md5:
            return
        prompts = parse_prompts_yaml(content)
        self._last_md5 = md5
        if prompts is None:
            logger.warning(f"提示词配置 {self.data_id} 没有 prompts 段或不是合法 YAML，忽略本次内容")
            return
        accepted, rejected = PromptStore.apply(prompts, source=f"nacos({time.strftime('%H:%M:%S')})")
        if rejected:
            logger.warning(f"提示词热更新：接受 {accepted} 条，拒绝 {rejected}")
        elif accepted:
            logger.info(f"提示词已热更新：{accepted} 条来自 Nacos（{self.data_id}）")

    def run(self):
        logger.info(f"提示词热更新已启动：{self.base} dataId={self.data_id} 每 {self.interval}s 轮询")
        fail_logged = False
        while True:
            try:
                self._poll_once()
                fail_logged = False
            except Exception as e:  # noqa: BLE001 —— 轮询线程里任何异常都不能杀死循环
                if not fail_logged:
                    logger.warning(f"提示词热更新本轮失败（沿用现有提示词继续服务）：{e}")
                    fail_logged = True
            time.sleep(self.interval)


def start_prompt_refresh(conf: Config = None):
    """按配置启动提示词热更新后台线程（幂等）；条件不满足时静默跳过。

    两个闸门：
    - config.ini 没有 [nacos] server_addr 即视为未启用（默认关闭，零行为变化）；
    - pytest 进程内一律不启动——测试断言的是 core/prompts.py 的内置默认值，
      不能让 Nacos 上的内容混进来，造成测试结果随线上配置漂移。
    """
    import sys
    if "pytest" in sys.modules:
        return None
    conf = conf or Config()
    if not (conf.NACOS_SERVER_ADDR or "").strip():
        return None
    try:
        poller = NacosPromptPoller(conf)
    except Exception as e:  # noqa: BLE001 —— 配置坏了不该影响服务启动
        logger.warning(f"提示词热更新未启动：{e}")
        return None
    thread = threading.Thread(target=poller.run, name="nacos-prompt-refresh", daemon=True)
    thread.start()
    return thread
