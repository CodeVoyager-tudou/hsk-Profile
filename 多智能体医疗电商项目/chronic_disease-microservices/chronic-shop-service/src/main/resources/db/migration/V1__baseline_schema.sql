-- =============================================
-- edu_shop 基线表结构（药品 / 订单 / 优惠券活动 / 用户券）
-- 由 Flyway 执行（spring.flyway.locations=classpath:db/migration）
-- 约定：所有 DDL 都用 IF NOT EXISTS / 信息模式守卫写成幂等，老库（已有表）与新库都能直接跑
-- =============================================

CREATE TABLE IF NOT EXISTS medicine (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '药品ID',
    name VARCHAR(100) NOT NULL COMMENT '药品名称',
    generic_name VARCHAR(100) COMMENT '通用名',
    category VARCHAR(50) COMMENT '药品分类',
    indication TEXT COMMENT '适应症',
    dosage TEXT COMMENT '用法用量',
    price DECIMAL(10,2) NOT NULL COMMENT '价格',
    stock INT DEFAULT 0 COMMENT '库存',
    points_reward INT DEFAULT 0 COMMENT '现金购买可获积分',
    points_price INT DEFAULT 0 COMMENT '积分兑换所需积分(0表示不可兑换)',
    image_url VARCHAR(255) COMMENT '图片地址',
    manufacturer VARCHAR(200) COMMENT '生产厂家',
    status TINYINT DEFAULT 1 COMMENT '状态: 0-下架 1-上架',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='药品商品表';

CREATE TABLE IF NOT EXISTS shop_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '订单ID',
    order_no VARCHAR(32) NOT NULL UNIQUE COMMENT '订单号',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    medicine_id BIGINT NOT NULL COMMENT '药品ID',
    medicine_name VARCHAR(100) COMMENT '药品名称',
    quantity INT NOT NULL COMMENT '购买数量',
    unit_price DECIMAL(10,2) NOT NULL COMMENT '单价',
    total_amount DECIMAL(10,2) NOT NULL COMMENT '总金额(优惠前)',
    points_earned INT DEFAULT 0 COMMENT '获得积分',
    points_status TINYINT DEFAULT 0 COMMENT '积分发放状态: 0-待发放(待补偿) 1-已发放',
    pay_type VARCHAR(20) DEFAULT 'CASH' COMMENT '支付方式: CASH-现金 POINTS-积分兑换',
    coupon_id BIGINT COMMENT '使用的用户优惠券ID(user_coupon.id)',
    discount_amount DECIMAL(10,2) DEFAULT 0 COMMENT '优惠券抵扣金额',
    points_used INT DEFAULT 0 COMMENT '积分兑换消耗的积分',
    refund_amount DECIMAL(10,2) COMMENT '退款金额',
    refund_time DATETIME COMMENT '退款时间',
    status VARCHAR(20) DEFAULT 'PENDING' COMMENT '状态: PENDING-待支付 PAID-已支付 CANCELLED-已取消',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_id (user_id),
    INDEX idx_status (status),
    INDEX idx_points_status (points_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

CREATE TABLE IF NOT EXISTS coupon (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '优惠券ID',
    name VARCHAR(100) NOT NULL COMMENT '券名称',
    type VARCHAR(30) DEFAULT 'FULL_REDUCTION' COMMENT '类型: FULL_REDUCTION-满减',
    threshold_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '消费满多少可用(0为无门槛)',
    discount_amount DECIMAL(10,2) NOT NULL COMMENT '抵扣金额',
    total_count INT NOT NULL COMMENT '发放总量',
    issued_count INT DEFAULT 0 COMMENT '已发放数量',
    limit_per_user INT DEFAULT 1 COMMENT '每人限领',
    start_time DATETIME NOT NULL COMMENT '活动开始时间',
    end_time DATETIME NOT NULL COMMENT '活动结束时间(券有效期同止)',
    status TINYINT DEFAULT 1 COMMENT '状态: 0-下线 1-进行中',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券活动表';

CREATE TABLE IF NOT EXISTS user_coupon (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    coupon_id BIGINT NOT NULL COMMENT '优惠券ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    status VARCHAR(20) DEFAULT 'UNUSED' COMMENT '状态: UNUSED-未使用 USED-已使用 EXPIRED-已过期',
    receive_time DATETIME COMMENT '领取时间',
    expire_time DATETIME COMMENT '过期时间',
    use_time DATETIME COMMENT '使用时间',
    order_id BIGINT COMMENT '核销本券的订单ID',
    INDEX idx_user_id (user_id),
    INDEX idx_coupon_id (coupon_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户优惠券表';

-- 补偿失败台账：跨服务调用（积分发放/回收/退还）失败时落库，由定时任务重试并对失败次数告警
CREATE TABLE IF NOT EXISTS compensation_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    biz_type VARCHAR(40) NOT NULL COMMENT '业务类型: ORDER_POINTS_GRANT/ORDER_POINTS_REVOKE/POINTS_REFUND',
    biz_id BIGINT NOT NULL COMMENT '业务ID(订单ID等)',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    payload VARCHAR(500) COMMENT '补偿所需参数(JSON)',
    status VARCHAR(20) DEFAULT 'PENDING' COMMENT '状态: PENDING-待补偿 DONE-已完成 FAILED-超过重试上限',
    retry_count INT DEFAULT 0 COMMENT '已重试次数',
    last_error VARCHAR(500) COMMENT '最后一次失败原因',
    next_retry_time DATETIME COMMENT '下次重试时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_biz_type_biz_id (biz_type, biz_id),
    INDEX idx_status_next_retry (status, next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跨服务补偿任务表';
