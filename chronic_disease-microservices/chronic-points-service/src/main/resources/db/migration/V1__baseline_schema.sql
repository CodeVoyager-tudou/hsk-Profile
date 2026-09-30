-- =============================================
-- edu_points 基线表结构（积分账户 / 流水 / 签到）
-- 由 Flyway 执行（spring.flyway.locations=classpath:db/migration）
-- 约定：所有 DDL 都用 IF NOT EXISTS / 信息模式守卫写成幂等，老库（已有表）与新库都能直接跑
-- =============================================

CREATE TABLE IF NOT EXISTS user_points (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    user_id BIGINT NOT NULL UNIQUE COMMENT '用户ID',
    total_points INT DEFAULT 0 COMMENT '累计获得积分',
    used_points INT DEFAULT 0 COMMENT '已使用积分(可用=total-used)',
    version INT DEFAULT 0 COMMENT '乐观锁版本号',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户积分表';

CREATE TABLE IF NOT EXISTS points_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    points INT NOT NULL COMMENT '积分变动（正数增加，负数扣减）',
    type VARCHAR(30) NOT NULL COMMENT '类型: SIGN_IN/SIGN_IN_BONUS/ORDER_PURCHASE/POINTS_EXCHANGE/POINTS_REFUND/ORDER_CANCEL_REVOKE',
    source_id BIGINT COMMENT '关联ID（签到记录ID/订单ID）',
    remark VARCHAR(200) COMMENT '备注',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_user_id (user_id),
    INDEX idx_create_time (create_time),
    -- 幂等护栏：同一来源的同一类型操作只记一次账，Feign 重试/补偿补发不会重复加减分
    UNIQUE KEY uk_type_source (type, source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分变动记录表';

CREATE TABLE IF NOT EXISTS sign_in_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    sign_date DATE NOT NULL COMMENT '签到日期',
    points INT DEFAULT 0 COMMENT '获得积分',
    consecutive_days INT DEFAULT 1 COMMENT '本周已签到天数(周循环规则)',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_user_date (user_id, sign_date),
    INDEX idx_sign_date (sign_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='签到记录表';
