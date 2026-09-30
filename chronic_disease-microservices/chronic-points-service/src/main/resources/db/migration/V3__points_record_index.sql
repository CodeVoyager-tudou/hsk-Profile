-- =============================================
-- 积分流水按用户分页查询的联合索引（替代全表扫描）
-- 由 Flyway 执行（spring.flyway.locations=classpath:db/migration）
-- 约定：所有 DDL 都用 IF NOT EXISTS / 信息模式守卫写成幂等，老库（已有表）与新库都能直接跑
-- =============================================

SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'points_record' AND INDEX_NAME = 'idx_user_create_time');
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE points_record ADD INDEX idx_user_create_time (user_id, create_time)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
