-- =============================================
-- J-02 修复：订单幂等键加入 user_id 维度
--
-- 问题：原唯一键 uk_order_request (request_id) **不含 user_id**，而幂等查询
--   ShopOrderMapper.selectByRequestId 是带 user_id 的：
--     A 用户先用了 requestId=R，B 用户再用同一个 R 时，B 的查询查不到（因带 user_id 过滤），
--     于是 B 正常走到 INSERT，撞上全表唯一的 uk_order_request -> DuplicateKeyException。
--   createOrder 未捕获该异常 -> GlobalExceptionHandler -> Result.error -> HTTP 200 + code 500。
--   即：跨用户复用 requestId 表现为「稳定可触发的 500 可用性故障」，
--   并可通过错误与否探测某 requestId 是否已被他人使用（可探测性）。
--   同时与 user_coupon 的做法不一致（V3 的 uk_user_coupon_user_coupon_no 含 user_id）。
--
-- 方案：唯一键改为 (user_id, request_id)，与查询口径一致。
--   幂等语义变为「同一用户的同一 requestId 只允许一单」——这正是前端幂等键的本意。
--
-- 幂等：information_schema 守卫，新库/老库均可直接执行。
-- 注意：new 键允许 request_id 为 NULL 的多行（MySQL 唯一索引允许多 NULL），
--   故未传 X-Request-Id 的历史订单不受影响。
-- =============================================

-- 1) 删除旧的全表唯一键 uk_order_request (request_id)
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_order' AND INDEX_NAME = 'uk_order_request');
SET @ddl := IF(@idx_exists > 0,
    'ALTER TABLE shop_order DROP INDEX uk_order_request',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) 建立含用户维度的唯一键
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_order' AND INDEX_NAME = 'uk_user_order_request');
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE shop_order ADD UNIQUE KEY uk_user_order_request (user_id, request_id)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
