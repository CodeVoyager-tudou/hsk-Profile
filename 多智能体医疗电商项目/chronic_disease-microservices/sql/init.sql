-- =============================================
-- 慢性病管理系统 - 数据库初始化（只建库）
--
-- 表结构与种子数据已全部纳入 Flyway 版本管理，各服务启动时自动迁移：
--   chronic-user-service/src/main/resources/db/migration/   -> edu_user
--   chronic-points-service/src/main/resources/db/migration/ -> edu_points
--   chronic-shop-service/src/main/resources/db/migration/   -> edu_shop
--
-- 执行：mysql -h<HOST> -P3307 -uroot -p < sql/init.sql
-- 之后启动服务即可自动建表 + 灌种子（幂等，可重复执行）
-- =============================================

CREATE DATABASE IF NOT EXISTS edu_user   DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS edu_points DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS edu_shop   DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
