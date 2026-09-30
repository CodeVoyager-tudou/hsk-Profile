-- =============================================
-- 管理操作审计表（管理端的"每一步都有据可查"）
--
-- 【为什么管理操作必须落审计】
-- 管理员能改价格/库存/退款/重试补偿 —— 全是高权限动作。
-- 审计回答三个问题：谁（operator）、对什么做了什么（target + action/detail）、
-- 当时整条链路的上下文是什么（trace_id，由 TraceIdFilter 写入 MDC，
-- 可与网关/下游日志按同一 ID 串联回放）。
-- 审计只增不改不删（append-only），是事后追责与对账的底线。
-- =============================================

SET @tbl_exists := (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'admin_audit');
SET @ddl := IF(@tbl_exists = 0,
    'CREATE TABLE admin_audit (
        id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT ''主键'',
        operator_id BIGINT NOT NULL COMMENT ''操作人用户ID(网关注入可信身份)'',
        action VARCHAR(60) NOT NULL COMMENT ''动作: MEDICINE_UPDATE MEDICINE_STATUS SECCILL_CREATE ORDER_REFUND COMP_RETRY ...'',
        target_type VARCHAR(30) NOT NULL COMMENT ''目标类型: MEDICINE SECKILL_ACTIVITY ORDER COMPENSATION_TASK'',
        target_id BIGINT COMMENT ''目标ID(创建类动作可为空)'',
        detail VARCHAR(500) COMMENT ''动作明细(参数摘要)'',
        trace_id VARCHAR(64) COMMENT ''链路ID(MDC X-Trace-Id,可串联网关与下游日志)'',
        create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT ''操作时间'',
        INDEX idx_target (target_type, target_id),
        INDEX idx_operator_time (operator_id, create_time)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT=''管理操作审计表''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
