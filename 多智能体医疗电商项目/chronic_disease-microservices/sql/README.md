# 数据库表结构管理（Flyway）

## 结论先说
表结构**不再手工执行 SQL**：每个服务启动时由 Flyway 自动把 `src/main/resources/db/migration/`
下的脚本按版本号顺序执行，执行记录写在各自的 `flyway_schema_history` 表里。

| 服务 | 数据库 | 迁移目录 |
| --- | --- | --- |
| chronic-user-service | edu_user | `chronic-user-service/src/main/resources/db/migration/` |
| chronic-points-service | edu_points | `chronic-points-service/src/main/resources/db/migration/` |
| chronic-shop-service | edu_shop | `chronic-shop-service/src/main/resources/db/migration/` |

## 为什么这么设计
- **老库零风险接入**：`baseline-on-migrate=true` + `baseline-version=0`，
  已存在的库会被当作"基线 0"，随后正常执行 V1…（所有脚本都写成幂等：`CREATE TABLE IF NOT EXISTS`、
  信息模式守卫的 `ALTER`、`ON DUPLICATE KEY UPDATE` 种子），老库跑一遍不会报错也不会丢数据。
- **新库一键起**：`sql/init.sql` 只建三个空库，启动服务即自动建表灌种子。
- **可回溯**：每次结构变更都新增一个 `V{n}__xxx.sql`，不修改已发布的脚本（Flyway 会校验 checksum）。

## 新增一次变更怎么做
1. 新建 `V{下一个版本号}__简短英文描述.sql`；
2. DDL 一律写成幂等（参考 `V3__coupon_receive_no.sql` 的信息模式守卫写法）；
3. 本地跑一次验证（见下），提交。

## 本地/CI 验证
默认跳过（需要真实 MySQL），手动执行：

```powershell
mvn -pl chronic-shop-service -am test -Dtest=FlywayMigrationDbTest `
    "-DflywayDbIt=1" "-Dmysql.host=192.168.100.128" "-Dmysql.port=3307" `
    "-Dmysql.user=root" "-Dmysql.password=root"
```

该测试会：空库从零迁移 → 重复迁移（应为 0 个新增）→ 删掉历史表再迁移（模拟老库首次接入，
脚本必须幂等）→ 断言关键表/列/索引存在 → 清理临时库。
