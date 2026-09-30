-- =============================================
-- AI 订单查询的复合索引：user_id + status + create_time
--
-- 背景：AI 问答（/internal/order/{list,summary}）的查询形态是
--   WHERE user_id = ? [AND status = ?] AND create_time BETWEEN ? AND ?
--   ORDER BY create_time DESC
-- 其中「已退款」问句会落成 status = 'CANCELLED' AND refund_amount > 0
-- （已退款不是独立状态，而是"取消且真退过钱"）。
--
-- 注意：注释的 `--` 后面必须跟空格（MySQL 的注释语法要求），
-- 写成 `--（…` 会被当成表达式解析，整段脚本报 SQL 语法错误 —— 本文件第一版就是这么挂的。
--
-- 已有的两个索引各只覆盖一半：
--   idx_user_create_time   (user_id, create_time)  —— 不带状态筛选时可用；带 status 时该列要回表逐行过滤
--   idx_status_create_time (status, create_time)   —— 服务于超时关单（跨用户扫描），不以 user_id 打头
-- 订单涨到十万级后，用户维度 + 状态 + 时间的组合查询会退化成"按 user_id 取出全部再逐行判状态"。
--
-- 列顺序：等值列在前（user_id 区分度最高，其次 status），范围列 create_time 放在最后，
-- 使最左前缀能把 WHERE 里的等值列吃满。
--
-- 与本索引并存、不删除 idx_user_create_time：本索引在 user_id 等值之后剩下 (status, create_time) 的
-- 排序，无法直接满足 ORDER BY create_time；"只看时间、不看状态"的查询仍应由 idx_user_create_time 承担。
--
-- 幂等：DDL 用 information_schema 守卫，新库/老库均可直接执行。
-- =============================================
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_order' AND INDEX_NAME = 'idx_user_status_create_time');
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE shop_order ADD INDEX idx_user_status_create_time (user_id, status, create_time)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
