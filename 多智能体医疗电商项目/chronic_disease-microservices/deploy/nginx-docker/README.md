# 在 VM(Docker) 部署 nginx —— AI 服务 9000 入口

把 nginx 放到 VM（示例：`192.168.100.128`）的 Docker 里，作为 AI 服务统一入口：
`user-service --WebClient--> nginx:9000 --> Python AI(8001/8002)`。

上游地址通过环境变量 `AI_UPSTREAM` 注入，模板由 nginx 官方镜像 entrypoint 自动 envsubst：
`templates/*.template` → `/etc/nginx/conf.d/*.conf`。**换环境只改环境变量，不动配置。**

## 目录

```
nginx-docker/
├─ docker-compose.yml               # 推荐：compose 一键起
├─ templates/ai-proxy.conf.template # 9000 反代模板（${AI_UPSTREAM} 占位）
└─ README.md
```

## 部署（在 VM 上执行）

```bash
# 0) 先确认上游可达（示例：Python AI 在开发机）
curl -s --max-time 5 http://192.168.100.1:8001/api/sources

# 1) 上传本目录到 VM
sudo mkdir -p /opt/chronic-nginx
# 在开发机执行（把文件拷进去）：
#   scp -r deploy/nginx-docker/* root@<VM_IP>:/opt/chronic-nginx/

# 2) 启动
cd /opt/chronic-nginx
sudo docker compose up -d                     # 或指定上游：AI_UPSTREAM=chronic-ai:8001 sudo -E docker compose up -d

# 3) 校验
sudo docker ps --filter name=chronic-ai-nginx --format '{{.Names}} | {{.Image}} | {{.Status}} | {{.Ports}}'
curl -s --max-time 5 http://127.0.0.1:9000/api/sources     # 期望 5 个知识源
sudo docker logs --tail 5 chronic-ai-nginx                 # 访问日志即在此
```

### 不用 compose（等价 docker run）

```bash
sudo docker rm -f chronic-ai-nginx 2>/dev/null
sudo docker run -d --name chronic-ai-nginx --restart unless-stopped \
  -p 9000:9000 \
  -e AI_UPSTREAM=192.168.100.1:8001 \
  -v /opt/chronic-nginx/templates:/etc/nginx/templates:ro \
  nginx:1.24-alpine
```

## 上游怎么选

| 场景 | AI_UPSTREAM |
| --- | --- |
| Python AI 跑在开发机（当前开发形态） | `192.168.100.1:8001`（VMnet8 宿主地址） |
| Python AI 也在 VM 内（生产形态） | 同一 docker network 的服务名，如 `chronic-ai:8001` |

## 镜像拉取

VM 直连 Docker Hub 可能超时（`registry-1.docker.io`），可用加速源：
```bash
sudo docker pull docker.1ms.run/library/nginx:1.24-alpine
sudo docker tag docker.1ms.run/library/nginx:1.24-alpine nginx:1.24-alpine
```
仓库另有面向 Linux 服务器的一体化配置（前端静态 + 80 端口网关反代 + 9000 负载均衡）：
`deploy/nginx/chronic.conf`；本目录只是 AI 入口的最小容器化版本。

## 部署后（开发机侧）

`user-service` 的 AI 入口指向 VM：开发环境已内置 dev profile

```powershell
# application-dev.yml: chronic.ai.base-url=http://192.168.100.128:9000
powershell -File deploy\scripts\start-user-service-dev.ps1 -StopExisting
```

验证（经网关走完整链路）：`GET /api/ai/sources`、`POST /api/ai/query`、
`POST /api/ai/query/stream`（SSE，需 `proxy_buffering off`）。
