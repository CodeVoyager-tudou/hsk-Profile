-- =============================================
-- J-13 修复：补齐订单查询缺失的复合索引
--
-- 背景：随着订单表增长（十万级），两个定时任务会退化为全表扫描：
--   1) selectExpiredPending —— 每 5 分钟由 xxl-job 触发（closeExpiredOrderJob），
--      WHERE status = 'PENDING' AND create_time < ?
--      原先只能用到单列索引 idx_status(status)，create_time 条件要回表逐行过滤。
--   2) PointsCompensateJob —— @Scheduled 每 5 分钟跑一次，
--      WHERE status='PAID' AND pay_type='CASH' AND points_status=0
--            AND points_earned>0 AND create_time < ?
--      原先没有任何复合索引能覆盖这个组合。
--
-- 说明：`selectByRequestId`（WHERE user_id=? AND request_id=?）所需的索引
--   已由 V7 的 uk_user_order_request(user_id, request_id) 覆盖，此处不再重复创建。
--
-- 幂等：全部 DDL 用 information_schema 守卫，新库/老库均可直接执行。
-- =============================================

-- 1) 超时关单：status + create_time
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_order' AND INDEX_NAME = 'idx_status_create_time');
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE shop_order ADD INDEX idx_status_create_time (status, create_time)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) 积分补发扫描：status + pay_type + points_status + create_time
--    列顺序按"区分度从高到低、且等值条件在前"排列，范围条件（create_time）放最后，
--    这样最左前缀能完整匹配 WHERE 中的等值列。
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_order' AND INDEX_NAME = 'idx_points_grant_scan');
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE shop_order ADD INDEX idx_points_grant_scan (status, pay_type, points_status, create_time)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
