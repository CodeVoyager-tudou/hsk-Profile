-- =============================================
-- 秒杀单溯源：shop_order 记录来源活动 id
--
-- 为什么需要这列：秒杀单改成「待支付现金单」后，超时未支付会被关单，
-- 而关单路径只归还药品库存与优惠券，不碰秒杀名额。若订单认不出自己来自哪个活动，
-- 每次未支付被关单都会永久吃掉一个名额（seckill_activity.sold_count 与 Redis 库存都不回补）。
-- 有了本列，关单/取消时就能把名额连同"已抢用户"一起释放（名额回补 + 该用户可再抢）。
--
-- 普通单、余额单该列为 NULL，行为完全不变。
-- 约定：DDL 用 information_schema 守卫写成幂等，老库（已有表）与新库都能直接跑
-- =============================================

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_order' AND COLUMN_NAME = 'seckill_activity_id');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE shop_order ADD COLUMN seckill_activity_id BIGINT NULL COMMENT ''秒杀单来源活动id(普通单NULL)；关单时据此回补秒杀名额''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
