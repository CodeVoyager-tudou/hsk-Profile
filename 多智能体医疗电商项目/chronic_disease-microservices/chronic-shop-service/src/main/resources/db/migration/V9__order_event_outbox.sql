-- =============================================
-- J-14 修复：订单事件本地消息表（Transactional Outbox）
--
-- 【要解决的问题】
--   原实现是「事务提交后再发 MQ」（OrderEventPublisher.publishAfterCommit）：
--       try { rocketMQTemplate.convertAndSend(...) }
--       catch (Exception e) { log.error(...) }      ← 异常被完全吞掉
--   这有两个致命弱点：
--     1. 订单已经 PAID，但消息因为 Broker 抖动/超时而没发出去，
--        只留下一行 error 日志，**没有任何补偿机制，也无法重放**；
--     2. 无法回答「这条订单的事件到底发出去了没有」。
--
-- 【outbox 怎么解决】
--   把「写订单」和「记下要发的消息」放在**同一个数据库事务**里：
--     要么两个都成功，要么两个都回滚 —— 不可能出现"订单在、消息丢"。
--   然后由一个定时中继任务（OrderEventRelayJob）扫描本表，
--   把 PENDING 的记录真正发到 MQ，成功置 SENT，失败按退避重试。
--   即使 Broker 长时间不可用，消息也只是"晚到"而不是"丢失"。
--
-- 幂等：全部 DDL 用 IF NOT EXISTS 守卫，新库/老库均可直接执行。
-- =============================================

CREATE TABLE IF NOT EXISTS order_event_outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    order_id BIGINT NOT NULL COMMENT '订单ID',
    order_no VARCHAR(32) NOT NULL COMMENT '订单号(便于排查)',
    event_type VARCHAR(40) NOT NULL COMMENT '事件类型: ORDER_CREATED 等',
    payload TEXT COMMENT '事件载荷(JSON)',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING-待发送 SENT-已发送 FAILED-超过重试上限',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '已重试次数',
    last_error VARCHAR(500) COMMENT '最后一次失败原因',
    next_retry_time DATETIME COMMENT '下次重试时间',
    sent_time DATETIME COMMENT '实际发送成功时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    -- 幂等：同一订单的同一类事件只允许一条，重试不会重复投递
    UNIQUE KEY uk_order_event (order_id, event_type),
    -- 中继任务按「状态 + 下次重试时间」扫描
    INDEX idx_status_next_retry (status, next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单事件本地消息表(Outbox)';
