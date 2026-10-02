# chronic_disease（微服务端 · Spring Cloud Alibaba）

慢性病健康管理平台的后端微服务：网关鉴权 + 用户/积分/商城三个业务服务，配合 AI 问诊代理。
配套仓库：AI 服务 `chronic_disease_ai`、前端 `chronic_disease_vue`。

## 架构

```
前端/Vue ──▶ chronic-gateway :8090（JWT 鉴权 · 白名单 · 身份头剥离/注入 · Sentinel 限流）
              ├─▶ chronic-user-service  :8081  登录/注册/资料/AI 代理(SSE)  ──WebClient──▶ Python AI(nginx:9000)
              ├─▶ chronic-points-service :8082  积分账户/签到/流水（内部接口不对外暴露）
              └─▶ chronic-shop-service  :8083  药品/购物车/订单/优惠券/积分兑换/秒杀 ──Feign──▶ points-service

中间件：Nacos 注册/配置 · Redis(令牌黑名单/秒杀预扣) · MySQL(edu_user/edu_points/edu_shop) · RocketMQ(订单事件/支付超时延迟消息) · xxl-job(补偿/清理)
```

## 快速启动

```bash
# 1) 初始化数据库（含建库建表与种子账号 admin/123456，预置 500 积分）
mysql -h<MYSQL_HOST> -P3307 -uroot -proot < sql/init.sql
# 表结构 + 种子数据由 Flyway 在服务启动时自动迁移：老库/新库/重复执行都幂等（见 sql/README.md）

# 1.5) 准备本地密钥（首次运行生成随机 JWT/内部令牌到 .env.local，已 gitignore；生产用环境变量注入）
. .\scripts\local-env.ps1

# 2) 构建（JDK 17；Spring Boot 3.3.13 + Spring Cloud 2023.0.6 + Spring Cloud Alibaba 2023.0.3.4）
mvn -pl chronic-common,chronic-gateway,chronic-user-service,chronic-points-service,chronic-shop-service -am package -DskipTests

# 3) 启动（顺序无强依赖；需 Nacos/Redis/RocketMQ 可达。
#    注意 -Dfile.encoding=UTF-8：配置已搬到 Nacos，正文含中文注释，中文 Windows 上
#    JVM 默认 file.encoding=GBK 会让 YAML 解析静默失败；一键启动脚本已内置该参数）
java -Dfile.encoding=UTF-8 -jar chronic-gateway/target/chronic-gateway-1.0.0-SNAPSHOT.jar            # 8090
java -Dfile.encoding=UTF-8 -jar chronic-user-service/target/chronic-user-service-1.0.0-SNAPSHOT.jar  # 8081
java -Dfile.encoding=UTF-8 -jar chronic-points-service/target/chronic-points-service-1.0.0-SNAPSHOT.jar # 8082
java -Dfile.encoding=UTF-8 -jar chronic-shop-service/target/chronic-shop-service-1.0.0-SNAPSHOT.jar  # 8083
```

> 配置中心：公共/服务级配置已迁到 Nacos（`DEFAULT_GROUP`，10 个 dataId）。
> 正文与对照表见仓库根 `deploy/nacos/`（含 `import-all.ps1` 一键导入与 `validate.jsh` 占位符校验），
> 方案见 `docs/Nacos配置迁移方案.md`。
> 各服务本地 `application.yml` 只保留引导项（服务名、Nacos 地址、`spring.config.import`）
> 与启动护栏（端口、网关的 reactive/自动配置排除）。

> 生产环境用 `deploy/config/<service>/config/application.yml` 覆盖配置（该目录不入库，含密钥）；
> nginx 编排示例见同级 `deploy/nginx/chronic.conf`。

## 关键技术点

| 主题 | 实现 |
| --- | --- |
| 鉴权 | 网关统一校验 JWT（access/refresh 区分）；白名单精确匹配；**剥离客户端伪造的 X-User-Id/X-Username 后再注入可信身份头**；登出/封禁写 Redis 黑名单即时失效（Redis 故障降级放行） |
| 服务间调用 | 积分 add/deduct/refund **不经网关暴露**，仅 Feign 内部调用 + `X-Internal-Token` 拦截器校验；Feign+Sentinel 熔断降级（PointsFeignClientFallback）；**自定义 `ErrorDecoder` 把下游 `{code,message}` 错误体还原成业务异常如实透传**——否则"积分不足"会被熔断降级统一报成"服务不可用"，把业务规则误报成系统故障 |
| 数据一致性 | 跨服务扣减：本地事务内建单，Feign 返回码≠200 抛异常回滚库存/订单；扣款结果不明（真超时）回滚后写补偿台账对账；积分/余额流水 `(type, source_id)` 唯一键幂等，防 Feign 重试双扣 |
| 并发 | 积分 SQL 层原子扣减（余额不足直接 0 行）+ `@Version` 乐观锁；库存原子扣减防超卖；订单取消原子抢占防重复退 |
| 支付与幂等 | **统一收银台模型**：余额/积分下单只建 `PENDING` 单（**此刻不扣资产**）→ 收银台 `POST /shop/order/pay/{id}` 按 `payType` 分派扣款（BALANCE 扣余额 / POINTS 扣积分 / CASH 走模拟渠道）→ 推进 `PAID`；30 分钟未付由 RocketMQ 两段延迟消息自动关单（只回补库存/券，因未扣款无资金退款）；**支付为模拟实现**（个人项目不接真实渠道）；`X-Request-Id` + `shop_order(user_id, request_id)` 唯一键防重复下单 |
| 购物车与优惠券 | 购物车落库 `cart_item`（同药品加购按唯一键合并）；结算把勾选的 N 件合成**一笔**订单（`shop_order_item` 明细表），**满减券门槛按多件合计判定**——解决"单件够不着门槛、优惠券用不上"；下单时用 Redisson 锁 + 唯一键防超领，取消/关单按明细逐件回补库存并按 `order_id` 校验退券 |
| 补偿可追踪 | 跨服务调用失败写 `compensation_task` 台账，`CompensationRetryJob` 指数退避重试，超限转 FAILED 并计指标 `chronic.compensation.failed`（不再是"只打一行日志靠人看"） |
| 表结构版本化 | Flyway（三库各自 `db/migration`，`baseline-on-migrate`），真实 MySQL 上有 `FlywayMigrationDbTest` 验证空库/老库/重复迁移三条路径 |
| 可观测 | actuator 健康探针（liveness/readiness）+ `/actuator/prometheus` 指标 + `X-Trace-Id` 全链路透传（日志带 traceId）+ 优雅停机 |
| 密钥治理 | 配置里不再有默认口令（缺失即启动失败）；prod 下弱口令/短 JWT 密钥直接拒绝启动；`scripts/local-env.ps1` 生成本地随机密钥 |
| 登录防撞库 | Redis 计数，连续失败 N 次锁定 M 分钟（默认 5 次/15 分钟），Redis 异常降级不阻断登录 |
| AI 内部鉴权 | Python `/api/**`、WebSocket 支持 `X-Internal-Token` 校验（`AI_INTERNAL_TOKEN`），user-service 自动携带 |
| 优惠券防超领 | 领取用 **Redisson 分布式锁**（key=`chronic:lock:coupon:receive:{couponId}:{userId}`，锁粒度=用户×活动，加锁/解锁由 Redis 侧 Lua 原子完成）把「查已领序号→占名额→建记录」串行化，并放在一个事务里（建记录失败自动归还名额）；数据库侧 `user_coupon(user_id, coupon_id, receive_no)` **唯一键兜底**，多实例/锁过期/重试最多一条成功；Redis 不可用时降级为仅靠唯一键（可用性优先，数据仍不会超领） |
| 消息/定时 | RocketMQ 在事务 `afterCommit` 才发订单事件；xxl-job 定时补偿（积分补发）与清理 |
| 数据最小化 | 不收集手机号与邮箱（`V3__drop_phone.sql`/`V4__drop_email.sql` 移除 `sys_user.phone`、`sys_user.email`）；越权读他人资料的两个接口已收紧为仅本人（403） |
| 安全自检 | 用户 `GET /user/{id}`、`/user/username/{x}` 已收紧为**仅本人可查**（403），防越权枚举他人资料 |

## 目录

```
chronic-common/        # Result/JWT/BusinessException/全局异常/OSS 存储
chronic-gateway/       # 路由、AuthFilter、Sentinel 网关限流规则
chronic-user-service/  # 登录注册、健康档案、AI 代理(WebClient+SSE)
chronic-points-service/# 积分账户、余额账户、签到(周循环)、流水、内部加/减/退分
chronic-shop-service/  # 药品、购物车、订单(SINGLE/CART × BALANCE/POINTS/CASH)、优惠券、秒杀、Feign 调积分
sql/init.sql           # 建库建表 + 种子数据
```

## 测试

```bash
mvn -B test     # 237 tests, 0 failures(7 项因缺外部依赖跳过)
#   chronic-common 44 / chronic-gateway 9 / chronic-user-service 23 / chronic-points-service 27 / chronic-shop-service 134
```

> 端到端冒烟（需四个服务已启动，走网关）：仓库根 `node scripts/e2e-smoke.mjs`。

## 约定

- 业务失败统一返回 `HTTP 200 + {code, message, data}`（code≠200 为失败），前端据此提示；
- 身份一律取自网关注入的 `X-User-Id`，前端传的 `userId` 仅作冗余校验。
