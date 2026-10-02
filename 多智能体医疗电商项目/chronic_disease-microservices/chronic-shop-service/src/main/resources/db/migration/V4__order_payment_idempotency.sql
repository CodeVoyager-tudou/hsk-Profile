-- =============================================
-- 订单幂等键 request_id + 支付时间 + 分页索引（防重复下单/支撑支付状态机）
-- 由 Flyway 执行（spring.flyway.locations=classpath:db/migration）
-- 约定：所有 DDL 都用 IF NOT EXISTS / 信息模式守卫写成幂等，老库（已有表）与新库都能直接跑
-- =============================================

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_order' AND COLUMN_NAME = 'request_id');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE shop_order ADD COLUMN request_id VARCHAR(64) NULL COMMENT ''客户端幂等键(X-Request-Id)，同一用户重复提交只会有一单''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_order' AND COLUMN_NAME = 'pay_time');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE shop_order ADD COLUMN pay_time DATETIME NULL COMMENT ''支付成功时间(渠道回调时写入)''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 唯一键允许 NULL 多行：历史数据 request_id 为空不受影响
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_order' AND INDEX_NAME = 'uk_order_request');
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE shop_order ADD UNIQUE KEY uk_order_request (request_id)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_order' AND INDEX_NAME = 'idx_user_create_time');
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE shop_order ADD INDEX idx_user_create_time (user_id, create_time)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
