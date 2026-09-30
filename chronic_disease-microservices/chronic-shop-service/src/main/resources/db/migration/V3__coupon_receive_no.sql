-- =============================================
-- 优惠券领取业务唯一ID：receive_no + 唯一键（防并发超领）
-- 由 Flyway 执行（spring.flyway.locations=classpath:db/migration）
-- 约定：所有 DDL 都用 IF NOT EXISTS / 信息模式守卫写成幂等，老库（已有表）与新库都能直接跑
-- =============================================

-- 1) 加列（老库已手工迁移过则跳过）
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_coupon' AND COLUMN_NAME = 'receive_no');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE user_coupon ADD COLUMN receive_no INT NOT NULL DEFAULT 1 COMMENT ''该用户第几次领取该券(业务唯一ID组成部分)''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) 回填存量：同一 (user_id, coupon_id) 内按 id（即领取先后）递增编号（幂等，重复执行结果一致）
UPDATE user_coupon uc
SET uc.receive_no = (
    SELECT COUNT(*) FROM (SELECT id, user_id, coupon_id FROM user_coupon) x
    WHERE x.user_id = uc.user_id AND x.coupon_id = uc.coupon_id AND x.id <= uc.id);

-- 3) 唯一键：同一用户在同一活动下的同一序号只能有一条记录
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_coupon' AND INDEX_NAME = 'uk_user_coupon_user_coupon_no');
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE user_coupon ADD UNIQUE KEY uk_user_coupon_user_coupon_no (user_id, coupon_id, receive_no)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
