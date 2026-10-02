-- =============================================
-- 移除邮箱字段
-- 原因：系统没有任何邮箱登录/验证/通知场景，保留即无用途的个人信息（数据最小化）。
-- 幂等：列/索引不存在则跳过。
-- =============================================

SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND INDEX_NAME = 'idx_email');
SET @ddl := IF(@idx_exists > 0, 'ALTER TABLE sys_user DROP INDEX idx_email', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'email');
SET @ddl := IF(@col_exists > 0, 'ALTER TABLE sys_user DROP COLUMN email', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
