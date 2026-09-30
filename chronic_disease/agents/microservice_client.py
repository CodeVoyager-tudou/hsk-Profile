"""微服务 HTTP 客户端：AI 服务调用 Java 内部接口的统一出口（订单/商城/资产都用它）。

SSRF 防护（服务只应访问配置指定的 shop-service / points-service）：
- 基址在模块加载时校验协议（仅 http/https）并解析出主机合法 IP 集合；
- 拼入 URL 的动态路径段做白名单字符校验，杜绝路径注入；
- 每次请求前重新解析主机并比对 IP（防 DNS rebinding）；
- 禁止跟随重定向（302 到其他主机即失败）。

鉴权：内部接口由 Java 侧校验 `X-Internal-Token`（fail-closed），令牌取值优先级
环境变量 INTERNAL_TOKEN > config.ini [chronic_disease] internal_token；未配置则不发送该头，
由 Java 侧拒绝（不会静默放行）。`user_id` 只是"查谁的"参数，不是身份凭证。
"""
import ipaddress
import json
import os
import re
import socket
import urllib.error
import urllib.request
from urllib.parse import urlsplit

from base.config import Config
from base.logger import logger

conf = Config()

# Java 微服务地址（shop-service 8083 / points-service 8082；环境变量可覆盖）
SHOP_SERVICE_URL = os.environ.get(
    "SHOP_SERVICE_URL",
    conf.config.get("chronic_disease", "shop_service_url", fallback="http://localhost:8083")
)
POINTS_SERVICE_URL = os.environ.get(
    "POINTS_SERVICE_URL",
    conf.config.get("chronic_disease", "points_service_url", fallback="http://localhost:8082")
)

# 服务间内部令牌（与 Java 侧 chronic.internal-token 同值）
INTERNAL_TOKEN = (
    os.environ.get("INTERNAL_TOKEN")
    or conf.config.get("chronic_disease", "internal_token", fallback="")
).strip()

# 动态路径段白名单：订单号/用户ID/药品关键词只能是字母数字下划线连字符（≤64 字符）
_SAFE_SEGMENT_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")


def _parse_base(url: str, name: str) -> str:
    """启动时固定服务地址要素：协议 + 主机 + 端口只来自受信配置"""
    split = urlsplit(url)
    if split.scheme not in ("http", "https") or not split.hostname:
        raise RuntimeError(f"{name} 非法（仅允许 http/https）: {url}")
    return f"{split.scheme}://{split.netloc}"


SHOP_BASE = _parse_base(SHOP_SERVICE_URL, "SHOP_SERVICE_URL")
POINTS_BASE = _parse_base(POINTS_SERVICE_URL, "POINTS_SERVICE_URL")


def _resolve_host_ips(host: str) -> set:
    """解析主机名对应的全部 IP；解析失败返回空集合（由调用方决定放行还是拒绝）"""
    try:
        infos = socket.getaddrinfo(host, None)
        return {ipaddress.ip_address(i[4][0]) for i in infos}
    except Exception:
        return set()


_ALLOWED_IPS = {
    urlsplit(SHOP_SERVICE_URL).hostname: _resolve_host_ips(urlsplit(SHOP_SERVICE_URL).hostname),
    urlsplit(POINTS_SERVICE_URL).hostname: _resolve_host_ips(urlsplit(POINTS_SERVICE_URL).hostname),
}


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """禁止重定向：302 到其他主机即视为失败，防止被引流到任意地址"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_OPENER = urllib.request.build_opener(_NoRedirect)


def _safe_url(base: str, path: str, query: str = "") -> str:
    """校验动态路径段后拼接请求 URL，非法输入直接拒绝"""
    for seg in path.split("/"):
        if seg and not _SAFE_SEGMENT_RE.match(seg):
            raise ValueError(f"URL 路径段含非法字符: {seg!r}")
    url = f"{base}{path}"
    return f"{url}?{query}" if query else url


def _check_host(host: str):
    """防 DNS rebinding：请求前重新解析主机，IP 必须与已认知的合法集合有交集"""
    ips = _resolve_host_ips(host)
    known = _ALLOWED_IPS.get(host) or set()
    if known and ips and not (ips & known):
        raise ValueError(f"服务主机解析结果异常: {host}")
    if ips:
        _ALLOWED_IPS[host] = ips


def get_json(path: str, query: str = "", user_id: str = None, base: str = None) -> dict:
    """GET 内部接口 JSON。base 默认 shop-service；查资产时传 POINTS_BASE。"""
    target = base or SHOP_BASE
    host = urlsplit(target).hostname
    url = _safe_url(target, path, query)
    _check_host(host)
    req = urllib.request.Request(url, method="GET")
    req.add_header("Content-Type", "application/json")
    if user_id:
        req.add_header("X-User-Id", str(user_id))
    if INTERNAL_TOKEN:
        req.add_header("X-Internal-Token", INTERNAL_TOKEN)
    with _OPENER.open(req, timeout=5) as resp:
        return json.loads(resp.read().decode("utf-8"))


def post_json(path: str, query: str = "", user_id: str = None, base: str = None) -> dict:
    """POST 内部接口 JSON（目前只用于 AI 代操作的取消订单，且由前端二次确认后才触发）"""
    target = base or SHOP_BASE
    host = urlsplit(target).hostname
    url = _safe_url(target, path, query)
    _check_host(host)
    req = urllib.request.Request(url, method="POST")
    req.add_header("Content-Type", "application/json")
    if user_id:
        req.add_header("X-User-Id", str(user_id))
    if INTERNAL_TOKEN:
        req.add_header("X-Internal-Token", INTERNAL_TOKEN)
    try:
        with _OPENER.open(req, timeout=8) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        # 业务错误（如"订单已取消"）也是 JSON，读出来交给调用方判断
        try:
            return json.loads(e.read().decode("utf-8"))
        except Exception:
            logger.error(f"POST 内部接口失败: {url} -> {e}")
            return {"code": e.code, "message": str(e)}
