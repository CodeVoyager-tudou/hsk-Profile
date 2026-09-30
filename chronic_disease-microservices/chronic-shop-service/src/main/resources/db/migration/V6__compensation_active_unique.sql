-- =============================================
-- J-04 修复：补偿台账唯一键允许「失败后重建」
--
-- 问题：原唯一键 uk_biz_type_biz_id (biz_type, biz_id) 是**全状态唯一**。
--   一旦某任务重试超限转为 FAILED（CompensationRetryJob.markFailed），该行仍占用唯一键；
--   此后同一笔业务再需要补偿时，recordCompensation 的 INSERT 会撞键，
--   而那里只 log.warn 吞掉（ShopOrderServiceImpl:380-381）—— 该订单永久无法再建补偿，积分再也退不回。
--
-- 方案：把唯一性从「(biz_type, biz_id)」改为「(biz_type, biz_id, active_flag)」，
--   其中 active_flag 是一个**生成列**：仅当 status IN ('PENDING') 时为 1，否则为 NULL。
--   MySQL 唯一索引允许多个 NULL，因此：
--     - 同一业务同时只可能存在 1 条 PENDING（防重复建账，保留原语义）；
--     - DONE / FAILED 的历史行不占用唯一性，可重新建账（修复点）。
--
-- 幂等：全部 DDL 用 information_schema 守卫，新库/老库均可直接执行。
-- 兼容：老库若已存在旧唯一键，本脚本先删除再加新键。
-- =============================================

-- 1) 新增生成列 active_flag（PENDING -> 1，其余 -> NULL）
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'compensation_task' AND COLUMN_NAME = 'active_flag');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE compensation_task ADD COLUMN active_flag TINYINT
        GENERATED ALWAYS AS (CASE WHEN status = ''PENDING'' THEN 1 ELSE NULL END) VIRTUAL
        COMMENT ''活跃标记: 仅 PENDING=1，其余为 NULL，使唯一键只约束在途任务''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) 删除旧的「全状态唯一」键
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'compensation_task' AND INDEX_NAME = 'uk_biz_type_biz_id');
SET @ddl := IF(@idx_exists > 0,
    'ALTER TABLE compensation_task DROP INDEX uk_biz_type_biz_id',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3) 建立新的「活跃唯一」键
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'compensation_task' AND INDEX_NAME = 'uk_biz_active');
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE compensation_task ADD UNIQUE KEY uk_biz_active (biz_type, biz_id, active_flag)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4) 支撑「按业务查历史（含 FAILED）」的人工排查查询
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'compensation_task' AND INDEX_NAME = 'idx_biz_type_biz_id');
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE compensation_task ADD INDEX idx_biz_type_biz_id (biz_type, biz_id)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 5) 把历史遗留的 FAILED 行显式「解锁」说明：active_flag 为 NULL 已天然不占唯一键，
--    无需数据订正；此处仅补充注释性说明，保留历史行以备审计。
