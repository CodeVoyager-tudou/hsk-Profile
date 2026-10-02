-- =============================================
-- edu_points 种子数据（admin 预置 500 积分）
-- 由 Flyway 执行（spring.flyway.locations=classpath:db/migration）
-- 约定：所有 DDL 都用 IF NOT EXISTS / 信息模式守卫写成幂等，老库（已有表）与新库都能直接跑
-- =============================================

INSERT INTO user_points (id, user_id, total_points, used_points, version)
VALUES (1, 1, 500, 0, 0)
ON DUPLICATE KEY UPDATE id = id;
