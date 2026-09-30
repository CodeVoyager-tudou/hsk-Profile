-- =============================================
-- 余额账户（用户资产）：user_account 账户表 + account_record 流水表
--
-- 【为什么放 points-service】
--   余额与积分同属「用户资产」，复用同一套幂等流水 / SQL 原子更新 / 内部令牌 / 补偿体系；
--   单独拆到 user-service 反而要重造一遍幂等护栏。
--
-- 【幂等设计】
--   account_record 的 UNIQUE KEY (type, source_id)：BALANCE_PAY / BALANCE_REFUND 的
--   source_id = 订单 ID，同一订单的同一类资金动作至多一条流水（Feign 重试/补偿任务重跑安全）。
--   RECHARGE 无业务单据，source_id 用雪花号保证每次充值一条流水。
--
-- 约定：所有 DDL 用信息模式守卫写成幂等，老库与新库都能直接执行（同 V1~V3）。
-- =============================================

SET @tbl_exists := (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_account');
SET @ddl := IF(@tbl_exists = 0,
    'CREATE TABLE user_account (
        id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT ''主键'',
        user_id BIGINT NOT NULL COMMENT ''用户ID'',
        balance DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT ''账户余额(元)'',
        version INT NOT NULL DEFAULT 0 COMMENT ''版本号(SQL层原子更新时手动+1，用于排查)'',
        create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间'',
        update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''更新时间'',
        UNIQUE KEY uk_user_account_user (user_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT=''用户余额账户表''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @tbl_exists := (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'account_record');
SET @ddl := IF(@tbl_exists = 0,
    'CREATE TABLE account_record (
        id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT ''主键'',
        user_id BIGINT NOT NULL COMMENT ''用户ID'',
        amount DECIMAL(10,2) NOT NULL COMMENT ''金额变动(正数入账,负数扣款)'',
        type VARCHAR(30) NOT NULL COMMENT ''类型: RECHARGE-充值 BALANCE_PAY-余额支付 BALANCE_REFUND-余额退款'',
        source_id BIGINT NOT NULL COMMENT ''来源ID(订单ID/充值流水号)'',
        remark VARCHAR(200) COMMENT ''备注'',
        create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间'',
        UNIQUE KEY uk_type_source (type, source_id),
        INDEX idx_user_create_time (user_id, create_time)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT=''余额账户流水表''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
