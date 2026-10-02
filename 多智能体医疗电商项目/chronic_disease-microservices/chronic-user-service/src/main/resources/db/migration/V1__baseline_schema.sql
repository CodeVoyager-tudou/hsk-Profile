-- =============================================
-- edu_user 基线表结构（用户表 + 健康档案表）
-- 由 Flyway 执行（spring.flyway.locations=classpath:db/migration）
-- 约定：所有 DDL 都用 IF NOT EXISTS / 信息模式守卫写成幂等，老库（已有表）与新库都能直接跑
-- =============================================

CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户ID',
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    password VARCHAR(255) NOT NULL COMMENT '密码(BCrypt 加密存储)',
    nickname VARCHAR(50) COMMENT '昵称',
    avatar VARCHAR(255) COMMENT '头像',
    phone VARCHAR(20) COMMENT '手机号',
    email VARCHAR(100) COMMENT '邮箱',
    gender TINYINT DEFAULT 0 COMMENT '性别: 0-未知 1-男 2-女',
    birthday DATE COMMENT '出生日期',
    status TINYINT DEFAULT 1 COMMENT '状态: 0-禁用 1-正常',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

CREATE TABLE IF NOT EXISTS user_health_profile (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    user_id BIGINT NOT NULL UNIQUE COMMENT '用户ID',
    height DECIMAL(5,2) COMMENT '身高(cm)',
    weight DECIMAL(5,2) COMMENT '体重(kg)',
    blood_type VARCHAR(10) COMMENT '血型',
    allergies TEXT COMMENT '过敏史',
    medical_history TEXT COMMENT '既往病史',
    family_history TEXT COMMENT '家族病史',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户健康档案表';

-- 健康档案访问审计：谁在什么时候读了/改了谁的健康信息（合规最小要求）
CREATE TABLE IF NOT EXISTS health_data_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    operator_id BIGINT NOT NULL COMMENT '操作者用户ID',
    target_user_id BIGINT NOT NULL COMMENT '被访问数据的归属用户ID',
    action VARCHAR(30) NOT NULL COMMENT '动作: READ_PROFILE/WRITE_PROFILE/DELETE_AI_SESSION/...',
    detail VARCHAR(200) COMMENT '补充说明',
    client_ip VARCHAR(64) COMMENT '来源IP',
    trace_id VARCHAR(64) COMMENT '链路ID',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_target_user (target_user_id, create_time),
    INDEX idx_operator (operator_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健康数据访问审计表';
