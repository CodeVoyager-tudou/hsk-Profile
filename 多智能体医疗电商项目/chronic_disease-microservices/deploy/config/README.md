# 本目录包含生产环境真实密钥，已自忽略：任何 git 仓库都不会跟踪本目录内容。
#
# 部署方法：把各服务文件夹整个拷贝到部署机 jar 的同级目录，保证路径为
#   <jar所在目录>/config/application.yml
#
# 示例（Linux）：
#   cp -r deploy/config/gateway        /opt/chronic/gateway
#   cp -r deploy/config/user-service   /opt/chronic/user-service
#   cp -r deploy/config/points-service /opt/chronic/points-service
#   cp -r deploy/config/shop-service   /opt/chronic/shop-service
# （以上命令会把 config/application.yml 一并放到 jar 旁边）
#
# 一致性要求：
#   - gateway 与 user-service 的 chronic.jwt.secret 必须完全相同
#   - points-service 与 shop-service 的 chronic.internal-token 必须完全相同
#   - 三处 xxl.job.accessToken 必须与 xxl-job-admin 的 application.properties 相同
#     （admin 侧同步修改后重启 admin）
#
# 泄露应急：怀疑任何一个值泄露，重新生成（openssl rand -base64 48）并按上表成对同步，
# JWT 换值后全员重新登录一次，属预期行为。
