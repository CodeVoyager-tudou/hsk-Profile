#!/usr/bin/env bash
# 把本目录下 10 个 yaml 批量发布到 Nacos（Group=DEFAULT_GROUP，格式 YAML）
# 用法：
#   export NACOS_ADDR=192.168.100.128:8848
#   export NACOS_USERNAME=xxx NACOS_PASSWORD=xxx
#   export NACOS_NAMESPACE=<namespace ID>   # 可选，留空=public
#   bash deploy/nacos/import-all.sh
# 重复执行是幂等的：同名 dataId 会被覆盖为当前文件内容。
set -euo pipefail

NACOS="${NACOS_ADDR:-http://192.168.100.128:8848}"
case "$NACOS" in http://*|https://*) ;; *) NACOS="http://$NACOS" ;; esac
: "${NACOS_USERNAME:?请先 export NACOS_USERNAME}"
: "${NACOS_PASSWORD:?请先 export NACOS_PASSWORD}"
TENANT="${NACOS_NAMESPACE:-}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

TOKEN=$(curl -s -X POST "$NACOS/nacos/v1/auth/login" \
  -d "username=$NACOS_USERNAME&password=$NACOS_PASSWORD" \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || { echo "[失败] 登录取不到 accessToken，检查 Nacos 地址与凭据" >&2; exit 1; }

for f in chronic-ai-prompts chronic-common chronic-common-service chronic-common-redis \
         chronic-common-oss chronic-common-security \
         chronic-gateway chronic-user-service chronic-points-service chronic-shop-service; do
  args=(-s -X POST "$NACOS/nacos/v1/cs/configs" -d "accessToken=$TOKEN")
  [ -n "$TENANT" ] && args+=(-d "tenant=$TENANT")
  resp=$(curl "${args[@]}" \
    --data-urlencode "dataId=$f.yaml" \
    --data-urlencode "group=DEFAULT_GROUP" \
    --data-urlencode "type=yaml" \
    --data-urlencode "content@$DIR/$f.yaml")
  echo "$f.yaml -> $resp"
done
