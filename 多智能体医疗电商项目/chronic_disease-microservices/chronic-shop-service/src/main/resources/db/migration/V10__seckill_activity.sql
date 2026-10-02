-- =============================================
-- 秒杀活动表（Redis + Lua 预扣 + MQ 异步落库链路的权威库存）
--
-- 【秒杀链路总览】
--   预热：WarmupJob 把 total_stock 写进 Redis（seckill:stock:{id}，SETNX 不覆盖运行值）
--   抢购：Lua 原子执行「一人一单 + 库存校验 + 预扣」（Redis 单线程，杜绝并发超卖）
--   落库：预扣成功发 RocketMQ → 消费者在本表原子扣名额 + 建秒杀单（余额同步支付）
--   回补：落库业务失败 → Lua 反向回补（INCRBY + SREM），方向保持「少卖不超卖」
--   对账：ReconcileJob 定时比对 Redis 剩余与 DB(总-已售)，偏差告警
--
-- 【为什么秒杀库存独立于 medicine.stock】
--   秒杀是营销活动的"活动名额"，与日常可售库存是两个业务概念；
--   秒杀单同样占用 medicine 库存（由建单链路统一扣减），避免同一份数据两处维护。
-- =============================================

SET @tbl_exists := (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'seckill_activity');
SET @ddl := IF(@tbl_exists = 0,
    'CREATE TABLE seckill_activity (
        id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT ''主键'',
        medicine_id BIGINT NOT NULL COMMENT ''秒杀药品ID'',
        title VARCHAR(100) NOT NULL COMMENT ''活动标题'',
        seckill_price DECIMAL(10,2) NOT NULL COMMENT ''秒杀价(元)'',
        total_stock INT NOT NULL COMMENT ''秒杀总名额'',
        sold_count INT NOT NULL DEFAULT 0 COMMENT ''已售名额'',
        start_time DATETIME NOT NULL COMMENT ''开始时间'',
        end_time DATETIME NOT NULL COMMENT ''结束时间'',
        status TINYINT NOT NULL DEFAULT 1 COMMENT ''1-上架 0-下架'',
        create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间'',
        update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''更新时间'',
        INDEX idx_status_time (status, start_time, end_time)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT=''秒杀活动表''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 种子数据：一条进行中的秒杀（管理员手工把窗口改到演示时段即可）
SET @seed_exists := (SELECT COUNT(*) FROM seckill_activity WHERE medicine_id = 1);
SET @ddl := IF(@seed_exists = 0,
    'INSERT INTO seckill_activity (medicine_id, title, seckill_price, total_stock, start_time, end_time, status)
     VALUES (1, ''布洛芬缓释胶囊 限时秒杀'', 9.90, 50,
             DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 1 DAY), 1)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
