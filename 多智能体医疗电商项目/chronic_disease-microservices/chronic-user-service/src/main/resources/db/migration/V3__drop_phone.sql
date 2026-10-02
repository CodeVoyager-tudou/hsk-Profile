-- =============================================
-- 移除手机号字段
-- 原因：个人练习项目无法承担敏感个人信息的加密/合规成本，直接不收集（用户名 + 昵称足够用）。
-- 幂等：列/索引不存在则跳过，老库（已有 phone 列）与新库都能执行。
-- =============================================

SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND INDEX_NAME = 'idx_phone');
SET @ddl := IF(@idx_exists > 0, 'ALTER TABLE sys_user DROP INDEX idx_phone', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'phone');
SET @ddl := IF(@col_exists > 0, 'ALTER TABLE sys_user DROP COLUMN phone', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
